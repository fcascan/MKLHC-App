package com.mklhc.app.desktop

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import com.mklhc.app.FakeRadiod
import com.mklhc.app.FakeRadiodLauncher
import com.mklhc.app.LogCapture
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.identity.deriveKartIdentity
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.radio.RadioMessage
import com.mklhc.core.wifi_ap.mac.Ieee80211Frame
import com.mklhc.core.wifi_ap.mac.InformationElements
import com.mklhc.core.wifi_ap.mac.MacAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [DesktopKartRuntime] against an in-process fake radiod: the real socket, supervisor, access point, radio unit,
 * DataStore pairing file and session repository, with only the radiod process replaced by [FakeRadiod]. It proves the
 * production wiring: the session's start reaches radiod with the configured adapter and the stored identity and puts
 * the game network on air, pairing puts the QR's network on air and cancelling it takes the radio down for good, stop
 * and shutdown leave nothing running (an idle radiod exits through the closed link, unkilled), a relaunched radiod gets
 * the network back, and the identity outlives the runtime.
 */
class DesktopKartRuntimeTest {

    private val tempDir: Path = Files.createTempDirectory("mklhc-runtime-test")
    private val dataDirectory: Path = tempDir.resolve("data")
    private val radiodExecutable: Path = Files.createFile(tempDir.resolve("radiod-fake"))
    private val launcher = FakeRadiodLauncher()
    private val runtimes = mutableListOf<DesktopKartRuntime>()

    @AfterTest
    fun tearDown() {
        // A test that failed half-way still releases its runtimes (shutdown is idempotent) and its radiods.
        runBlocking { runtimes.forEach { it.shutdown() } }
        launcher.processes.forEach { it.crash() }
        tempDir.toFile().deleteRecursively()
    }

    // ---- The session over the radio -------------------------------------------------------------------

    @Test
    fun startingTheSessionBringsTheRadioUpWithTheStoredIdentityAndBeaconsTheGameNetwork() = runtimeTest {
        val runtime = runtime(settings(vid = 0x2357, pid = 0x012D))
        runtime.start()

        runtime.session.start()

        val radiod = launcher.awaitProcess(0)
        val beacon = radiod.awaitMessages<RadioMessage.StartBeacon>(1).single()
        assertEquals(
            listOf("Hello", "OpenDevice", "Init", "SetIdentity", "StartBeacon"),
            radiod.messages().map { it::class.simpleName },
        )
        assertEquals(RadioMessage.OpenDevice(0x2357, 0x012D), radiod.messages()[1], "the configured adapter is opened")
        val store = runtime.pairingStore
        assertEquals(RadioMessage.Init(channel = store.getChannel(), bandwidthMhz = 20, txPowerIndex = 0), radiod.messages()[2])
        val bssid = store.getApBssid()
        assertContentEquals(bssid, (radiod.messages()[3] as RadioMessage.SetIdentity).mac, "the radio takes the stored BSSID")
        val frame = checkNotNull(Ieee80211Frame.parse(beacon.beacon)) { "not an 802.11 frame" }
        assertTrue(frame.isBeacon)
        assertEquals(MacAddress.fromBytes(bssid), frame.addr2)
        // The game network of the stored identity: its SSID is hidden (the real length, every octet zero).
        val identity = deriveKartIdentity(store.getMasterSecret(), CryptoPrimitives())
        assertContentEquals(ByteArray(identity.gameSsid.encodeToByteArray().size), hiddenSsidOf(beacon.beacon))
        awaitLinkState(runtime, LinkState.Beaconing(KartNetworkKind.GAME))
    }

    @Test
    fun stopThenShutdownShutsRadiodDownWithoutRelaunchingItAndRemovesTheSocket() = runtimeTest {
        val runtime = runtime(settings())
        runtime.start()
        runtime.session.start()
        val radiod = launcher.awaitProcess(0)
        radiod.awaitMessages<RadioMessage.StartBeacon>(1)

        runtime.session.stop()
        runtime.shutdown()

        assertEquals(listOf<RadioMessage>(RadioMessage.StopBeacon, RadioMessage.Shutdown), radiod.messagesAfterTheBeacon())
        assertTrue(radiod.hasExited)
        assertFalse(radiod.wasKilled, "radiod exited by itself on SHUTDOWN")
        // Longer than the supervisor's first restart backoff (0.5 s): a relaunch would have happened by now.
        delay(RELAUNCH_WINDOW_MS)
        assertEquals(1, launcher.processes.size, "radiod exited after SHUTDOWN and was not launched again")
        assertFalse(Files.exists(launcher.configs.single().socketPath.parent), "the socket and its directory are removed")
        runtime.shutdown() // idempotent: a second call does nothing and does not throw
    }

    @Test
    fun aShutdownWithoutASessionEndsTheIdleRadiodThroughTheClosedLink() = runtimeTest {
        val runtime = runtime(settings())
        runtime.start()
        val radiod = launcher.awaitProcess(0)
        radiod.awaitMessages<RadioMessage.Hello>(1)

        runtime.session.stop()
        runtime.shutdown()

        assertTrue(radiod.hasExited, "radiod saw its connection end and exited")
        // Killed would mean the shutdown waited out its 3 s grace for an exit the closed link never caused.
        assertFalse(radiod.wasKilled, "radiod exited through the closed link, it was not killed after the grace")
        assertEquals(0, radiod.exitCode, "radiod exits cleanly on the end of its connection")
        assertEquals(listOf("Hello"), radiod.messages().map { it::class.simpleName }, "nothing was brought up")
        delay(RELAUNCH_WINDOW_MS)
        assertEquals(1, launcher.processes.size, "radiod was not launched again")
    }

    @Test
    fun pairingFromTheSessionBeaconsThePairingNetworkAndCancellingItShutsTheRadioDownForGood() = runtimeTest {
        val runtime = runtime(settings())
        runtime.start()

        runtime.session.startPairing()

        val qr = checkNotNull(withTimeoutOrNull(STATE_TIMEOUT_MS) { runtime.session.pairingQr.filterNotNull().first() }) {
            "no pairing QR within $STATE_TIMEOUT_MS ms -> state=${runtime.session.linkState.value}"
        }
        val radiod = launcher.awaitProcess(0)
        val beacon = radiod.awaitMessages<RadioMessage.StartBeacon>(1).single()
        assertEquals(
            listOf("Hello", "OpenDevice", "Init", "SetIdentity", "StartBeacon"),
            radiod.messages().map { it::class.simpleName },
        )
        // The network the QR describes is the one on air: a pairing SSID, hidden in the beacon (its length, every
        // octet zero), from the stored BSSID.
        val pairingSsid = ssidOf(qr)
        assertTrue(pairingSsid.startsWith(PAIRING_SSID_PREFIX), pairingSsid)
        assertContentEquals(ByteArray(pairingSsid.encodeToByteArray().size), hiddenSsidOf(beacon.beacon))
        val frame = checkNotNull(Ieee80211Frame.parse(beacon.beacon)) { "not an 802.11 frame" }
        assertEquals(MacAddress.fromBytes(runtime.pairingStore.getApBssid()), frame.addr2)
        awaitLinkState(runtime, LinkState.Beaconing(KartNetworkKind.PAIRING))

        runtime.session.cancelPairing()

        radiod.awaitMessages<RadioMessage.Shutdown>(1)
        assertEquals(listOf<RadioMessage>(RadioMessage.StopBeacon, RadioMessage.Shutdown), radiod.messagesAfterTheBeacon())
        assertNull(runtime.session.pairingQr.value, "the QR is gone with the pairing network")
        awaitLinkState(runtime) { it is LinkState.Failed }
        // No orchestrator is left: radiod, relaunched after its SHUTDOWN, connects and is never told to bring the radio up.
        val relaunched = launcher.awaitProcess(1)
        relaunched.awaitMessages<RadioMessage.Hello>(1)
        delay(SETTLE_MS)
        assertEquals(listOf("Hello"), relaunched.messages().map { it::class.simpleName }, "nothing brings the radio up again")
        assertTrue(runtime.session.linkState.value is LinkState.Failed, "the link stays settled -> state=${runtime.session.linkState.value}")
        runtime.session.stop()
        runtime.shutdown()
    }

    @Test
    fun aRelaunchedRadiodGetsTheSameGameNetworkBack() = runtimeTest {
        val runtime = runtime(settings())
        runtime.start()
        runtime.session.start()
        val first = launcher.awaitProcess(0)
        val firstBeacon = first.awaitMessages<RadioMessage.StartBeacon>(1).single()

        first.crash() // the supervisor relaunches radiod after its backoff and the link accepts the new connection

        val second = launcher.awaitProcess(1)
        val secondBeacon = second.awaitMessages<RadioMessage.StartBeacon>(1).single()
        assertEquals(
            listOf("Hello", "OpenDevice", "Init", "SetIdentity", "StartBeacon"),
            second.messages().map { it::class.simpleName },
        )
        assertContentEquals(firstBeacon.beacon, secondBeacon.beacon, "the same network, with the same keys, is beaconed again")

        runtime.session.stop()
        runtime.shutdown()
        assertTrue(second.messages().any { it is RadioMessage.Shutdown })
        assertEquals(2, launcher.processes.size, "no third launch during shutdown")
    }

    @Test
    fun aMissingRadiodIsReportedWithItsPathAndVariableAndTheSessionShowsNoAdapter() = runtimeTest {
        val logs = LogCapture()
        val missing = tempDir.resolve("no-such-radiod")
        val runtime = runtime(settings().copy(radiodExecutable = missing), log = logs.logger())

        runtime.start()
        runtime.session.start()

        logs.awaitLine("ERROR: start(): radiod was not found")
        assertTrue(logs.has("path=$missing") && logs.has("MKLHC_RADIOD"), logs.text())
        // Long enough for the session to have gone past the adapter check if it could.
        delay(SETTLE_MS)
        assertEquals(LinkState.NoAdapter, runtime.session.linkState.value)
        assertTrue(launcher.processes.isEmpty(), "nothing is launched")
        runtime.session.stop()
        runtime.shutdown()
    }

    // ---- The identity and the secrets ----------------------------------------------------------------

    @Test
    fun thePairingIdentityPersistsAcrossTwoRuntimesOnTheSameDataDirectory() = runtimeTest {
        val first = runtime(settings())
        val secret = first.pairingStore.getMasterSecret()
        val bssid = first.pairingStore.getApBssid()
        val channel = first.pairingStore.getChannel()
        first.session.stop()
        first.shutdown()
        assertTrue(Files.isRegularFile(dataDirectory.resolve("pairing.preferences_pb")), "the identity is in the data directory")

        // The second runtime can only open the file because the first one released it.
        val second = runtime(settings())

        assertContentEquals(secret, second.pairingStore.getMasterSecret())
        assertContentEquals(bssid, second.pairingStore.getApBssid())
        assertEquals(channel, second.pairingStore.getChannel())
        second.session.stop()
        second.shutdown()
    }

    @Test
    fun theTokenTheMasterSecretAndTheGameKeyAreNeverLogged() = runtimeTest {
        val logs = LogCapture()
        withGlobalLogWriter(logs) {
            val runtime = runtime(settings(), log = logs.logger())
            runtime.start()
            runtime.session.start()
            launcher.awaitProcess(0).awaitMessages<RadioMessage.StartBeacon>(1)
            awaitLinkState(runtime, LinkState.Beaconing(KartNetworkKind.GAME))
            val secret = runtime.pairingStore.getMasterSecret()
            runtime.session.stop()
            runtime.shutdown()

            val identity = deriveKartIdentity(secret, CryptoPrimitives())
            val text = logs.text()
            assertTrue(logs.has("shutdown(): radio runtime shut down"), "the whole run was captured:\n$text")
            listOf(
                "token" to launcher.configs.single().token,
                "master secret" to secret.toHexString(),
                "game key" to identity.gamePsk.toHexString(),
            ).forEach { (what, value) -> assertFalse(text.contains(value, ignoreCase = true), "the $what appears in the log") }
        }
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    private fun settings(vid: Int = 0x0BDA, pid: Int = 0xC812) = DesktopRadioSettings(
        demo = false,
        radioVid = vid,
        radioPid = pid,
        radiodExecutable = radiodExecutable,
        dataDirectory = dataDirectory,
    )

    private fun runtime(settings: DesktopRadioSettings, log: Logger = Logger.withTag("DesktopKartRuntimeTest")) =
        DesktopKartRuntime(settings, launcher = launcher, log = log).also { runtimes += it }

    private suspend fun awaitLinkState(runtime: DesktopKartRuntime, expected: LinkState) =
        awaitLinkState(runtime, expected.toString()) { it == expected }

    private suspend fun awaitLinkState(runtime: DesktopKartRuntime, what: String = "the state", reached: (LinkState) -> Boolean) {
        val state = withTimeoutOrNull(STATE_TIMEOUT_MS) { runtime.session.linkState.first { reached(it) } }
        checkNotNull(state) { "the link did not reach $what within $STATE_TIMEOUT_MS ms -> state=${runtime.session.linkState.value}" }
    }

    /** What radiod received after the first START_BEACON, without the frames it transmitted. */
    private fun FakeRadiod.messagesAfterTheBeacon(): List<RadioMessage> =
        messages().dropWhile { it !is RadioMessage.StartBeacon }.drop(1).filter { it !is RadioMessage.TxFrame }

    /** The SSID in the 62-octet pairing payload (octets 0x10 to 0x2F, zero padded). */
    private fun ssidOf(payload: ByteArray): String =
        payload.copyOfRange(SSID_FIELD_START, SSID_FIELD_END).takeWhile { it != 0.toByte() }.toByteArray().decodeToString()

    /** The SSID element of a beacon frame. */
    private fun hiddenSsidOf(beacon: ByteArray): ByteArray {
        val frame = checkNotNull(Ieee80211Frame.parse(beacon)) { "not an 802.11 frame" }
        val fields = checkNotNull(frame.beaconFields()) { "not a beacon" }
        return checkNotNull(InformationElements.find(fields.elements, InformationElements.SSID)) { "no SSID element" }.value
    }

    /** Adds [writer] to the global logger, which every class of the stack logs through, for the length of [block]. */
    private suspend fun withGlobalLogWriter(writer: LogWriter, block: suspend () -> Unit) {
        val previous = Logger.config.logWriterList
        Logger.setLogWriters(previous + writer)
        try {
            block()
        } finally {
            Logger.setLogWriters(previous)
        }
    }

    private fun runtimeTest(block: suspend CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(60.seconds) { block() } }

    private companion object {
        const val STATE_TIMEOUT_MS = 10_000L
        const val RELAUNCH_WINDOW_MS = 1_000L
        const val SETTLE_MS = 500L
        const val PAIRING_SSID_PREFIX = "mklhc-pair_"
        const val SSID_FIELD_START = 0x10
        const val SSID_FIELD_END = 0x30
    }
}

package com.mklhc.app.spike

import com.mklhc.app.FakeRadiod
import com.mklhc.app.FakeRadiodLauncher
import com.mklhc.app.LogCapture
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.session.SessionConfig
import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.Arp
import com.mklhc.core.netstack.ArpPacket
import com.mklhc.core.netstack.DhcpMessageType
import com.mklhc.core.netstack.EtherType
import com.mklhc.core.netstack.Ipv4Packet
import com.mklhc.core.netstack.tcp.TcpSegment
import com.mklhc.core.radio.RadioMessage
import com.mklhc.core.wifi_ap.ap.FakeStation
import com.mklhc.core.wifi_ap.lp2p.Lp2pKeys
import com.mklhc.core.wifi_ap.lp2p.NonceTable
import com.mklhc.core.wifi_ap.mac.Ieee80211Frame
import com.mklhc.core.wifi_ap.mac.LlcSnap
import com.mklhc.core.wifi_ap.mac.MacAddress
import com.mklhc.feature.pairing.presentation.qr.encodeQrMatrix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [runSpikeSession] end to end against an in-process fake radiod: the real [com.mklhc.core.radio.DesktopRadioLink]
 * socket, the real [com.mklhc.core.radio.RadiodSupervisor], the real [com.mklhc.core.wifi_ap.ap.RadioAccessPoint] and
 * [com.mklhc.core.netstack.NetstackNode] over it, and a real pcap file, with only the radiod process replaced by a
 * thread that speaks the IPC protocol ([FakeRadiod], shared with the desktop runtime's tests). A fake kart sends its
 * frames through that radiod. This is everything the hardware run does except the adapter, so a failure here is a
 * wiring bug, not a radio problem.
 */
class SpikeSessionTest {

    private val tempDir: Path = Files.createTempDirectory("mklhc-spike-test")
    private val outDir: Path = tempDir.resolve("captures")
    private val radiodExecutable: Path = Files.createFile(tempDir.resolve("radiod-fake"))
    private val launcher = FakeRadiodLauncher()

    @AfterTest
    fun tearDown() {
        launcher.processes.forEach { it.crash() }
        tempDir.toFile().deleteRecursively()
    }

    // ---- The GO scenario through the whole composition --------------------------------------------

    @Test
    fun aWholeSessionBeaconsAssociatesTheKartAnswersItsDhcpArpAndPairingTcpAndWritesThePcap() = sessionTest {
        val console = ConsoleCapture()
        val logs = LogCapture()
        val stop = CompletableDeferred<StopRequest>()
        val session = async(Dispatchers.Default) {
            runSpikeSession(args(), stop, launcher = launcher, nonceTable = knownNonceTable(), console = console::add, log = logs.logger())
        }

        // radiod starts, is told to bring the radio up in order, and the pairing beacon goes on air.
        val radiod = launcher.awaitProcess(0)
        val beacon = radiod.awaitMessages<RadioMessage.StartBeacon>(1).single()
        assertEquals(
            listOf("Hello", "OpenDevice", "Init", "SetIdentity", "StartBeacon"),
            radiod.messages().map { it::class.simpleName },
        )
        assertEquals(RadioMessage.OpenDevice(0x0BDA, 0xC812), radiod.messages()[1])
        assertEquals(RadioMessage.Init(channel = 6, bandwidthMhz = 20, txPowerIndex = 0), radiod.messages()[2])
        val beaconFrame = assertNotNullFrame(beacon.beacon)
        assertTrue(beaconFrame.isBeacon)
        val identity = radiod.messages()[3] as RadioMessage.SetIdentity
        assertEquals(beaconFrame.addr2, MacAddress.fromBytes(identity.mac), "the BSSID of the beacon is the radio's identity")

        // The console shows the pairing payload; the fake kart derives the network key from it, as the real one would.
        val payload = console.qrPayload()
        val ssid = ssidOf(payload)
        assertTrue(ssid.startsWith("mklhc-pair_"), ssid)
        assertTrue(console.text().contains("\u2588") || console.text().contains("\u2580") || console.text().contains("\u2584"), "the QR code is drawn")
        val kart = kartFor(payload, beaconFrame.addr2)

        // The kart authenticates, associates and broadcasts a DHCP DISCOVER, all through radiod's RX_FRAME messages.
        radiod.sendRxFrame(kart.authRequest())
        radiod.sendRxFrame(kart.assocRequest(ssid))
        radiod.sendRxFrame(kart.dhcpFrame(kart.discover(), pn = 1))
        val transmitted = radiod.awaitMessages<RadioMessage.TxFrame>(3)
        assertTrue(Ieee80211Frame.parse(transmitted[0].frame)!!.isAuth)
        assertTrue(Ieee80211Frame.parse(transmitted[1].frame)!!.isAssocResponse)
        val offer = assertNotNull(kart.dhcpFromAp(transmitted[2].frame))
        assertEquals(DhcpMessageType.OFFER, offer.messageType)
        assertEquals(AddressPlan.KART_LEASE, offer.yourIp)

        // Once the beacon is on air the QR code is printed again, so the code to scan is the last thing on screen.
        assertTrue(console.text().contains("The pairing network is on air"), "the QR code is printed again once on air")
        assertEquals(2, console.text().lines().count { it.startsWith("Pairing QR payload") })

        // The same code is saved as an SVG image next to the capture, and its path is printed with the payload.
        val qrImage = Files.list(outDir).use { files -> files.toList() }.single { it.fileName.toString().endsWith(".svg") }
        assertTrue(qrImage.fileName.toString().matches(Regex("spike-\\d{8}-\\d{6}-qr\\.svg")), qrImage.toString())
        assertEquals(renderQrAsSvg(encodeQrMatrix(payload)), Files.readString(qrImage))
        assertEquals(2, console.text().lines().count { it.startsWith("Pairing QR image") && it.endsWith(qrImage.toAbsolutePath().toUri().toString()) })

        // The DHCP exchange goes on: REQUEST is acknowledged and the kart takes its address.
        val ack = assertNotNull(kart.dhcpFromAp(radiod.exchange(kart.dhcpFrame(kart.request(), pn = 2), "the DHCP ACK")))
        assertEquals(DhcpMessageType.ACK, ack.messageType)

        // The kart asks who has the app's address and is told it is the BSSID, the address frames come from.
        val whoHas = ArpPacket(
            operation = Arp.OPERATION_REQUEST,
            senderMac = kart.mac.toByteArray(),
            senderIp = AddressPlan.KART_LEASE,
            targetMac = ByteArray(MacAddress.SIZE),
            targetIp = AddressPlan.AP,
        )
        val arpPayload = assertNotNull(kart.fromAp(radiod.exchange(kart.linkFrame(EtherType.ARP, whoHas.encode(), pn = 3), "the ARP reply")))
        assertEquals(EtherType.ARP, arpPayload.etherType)
        val arpReply = assertNotNull(ArpPacket.decode(arpPayload.payload))
        assertEquals(Arp.OPERATION_REPLY, arpReply.operation)
        assertContentEquals(beaconFrame.addr2.toByteArray(), arpReply.senderMac, "the ARP reply names the BSSID")
        assertEquals(AddressPlan.AP, arpReply.senderIp)
        assertEquals(AddressPlan.KART_LEASE, arpReply.targetIp)

        // The kart opens its pairing connection: the SYN is answered, and the kart's ACK makes the probe accept it.
        radiod.openPairingConnection(kart, beaconFrame.addr2, kartPort = KART_PORT, pn = 4)
        logs.awaitLine("pairing TCP accepted -> peer=${AddressPlan.KART_LEASE}:$KART_PORT")

        // Stopping tears everything down in order and succeeds.
        stop.complete(StopRequest("test finished", failed = false))
        assertEquals(EXIT_OK, session.await())
        assertFalse(Files.exists(qrImage), "the QR image, which holds the run's seed, is removed at stop")

        val afterBeacon = radiod.messages().dropWhile { it !is RadioMessage.StartBeacon }.drop(1).filter { it !is RadioMessage.TxFrame }
        assertEquals(listOf<RadioMessage>(RadioMessage.StopBeacon, RadioMessage.Shutdown), afterBeacon)
        assertEquals(1, launcher.processes.size, "radiod exited by itself after SHUTDOWN and was not launched again")
        assertFalse(Files.exists(launcher.configs.single().socketPath.parent), "the socket and its directory are removed")
        assertTrue(logs.has("runSpikeSession(): pcap written:"), logs.text())

        // The pcap on disk is valid, radiotap, and holds the whole exchange decrypted; it shares the QR image's name stem.
        val pcapFile = Files.list(outDir).use { files -> files.toList() }.single()
        assertTrue(pcapFile.fileName.toString().matches(Regex("spike-\\d{8}-\\d{6}\\.pcap")), pcapFile.toString())
        assertEquals(qrImage.fileName.toString().removeSuffix("-qr.svg"), pcapFile.fileName.toString().removeSuffix(".pcap"))
        val capture = parsePcap(Files.readAllBytes(pcapFile))
        assertEquals(127, capture.linkType)
        // auth req/resp, assoc req/resp, DISCOVER, OFFER, REQUEST, ACK, who-has, is-at, SYN, SYN-ACK and the kart's ACK;
        // a SYN-ACK retransmitted before the kart's ACK would add frames in between, so only the tail is open-ended.
        assertTrue(capture.frames.size >= 13, "frames=${capture.frames.size}")
        val frames = capture.frames.map { assertNotNullFrame(it.frame) }
        val discoverFrame = frames[4]
        assertFalse(discoverFrame.isProtected)
        val discover = assertNotNull(FakeStation.dhcpFromBody(discoverFrame.body))
        assertEquals(DhcpMessageType.DISCOVER, discover.messageType)
        assertContentEquals(kart.mac.toByteArray(), discover.clientMac)
        assertEquals(DhcpMessageType.OFFER, FakeStation.dhcpFromBody(frames[5].body)?.messageType)
        assertEquals(DhcpMessageType.REQUEST, FakeStation.dhcpFromBody(frames[6].body)?.messageType)
        assertEquals(DhcpMessageType.ACK, FakeStation.dhcpFromBody(frames[7].body)?.messageType)
        assertEquals(Arp.OPERATION_REQUEST, arpOf(frames[8])?.operation)
        assertEquals(Arp.OPERATION_REPLY, arpOf(frames[9])?.operation)
        assertTrue(assertNotNull(tcpOf(frames[10])).isSyn)
        assertTrue(assertNotNull(tcpOf(frames[11])).let { it.isSyn && it.isAck })
        // The kart's ACK follows the first SYN-ACK; a retransmitted SYN-ACK (200 ms) can come before it on a slow machine.
        val ackFromKart = frames.drop(12).any { frame -> frame.toDs && tcpOf(frame)?.let { it.isAck && !it.isSyn } == true }
        assertTrue(ackFromKart, "an ACK from the kart follows the first SYN-ACK in the capture")
    }

    @Test
    fun aKartThatLeavesGetsItsLeaseFreedAndCanOpenANewPairingConnectionWhenItComesBack() = sessionTest {
        val console = ConsoleCapture()
        val logs = LogCapture()
        val stop = CompletableDeferred<StopRequest>()
        val session = async(Dispatchers.Default) {
            runSpikeSession(args(), stop, launcher = launcher, nonceTable = knownNonceTable(), console = console::add, log = logs.logger())
        }
        val radiod = launcher.awaitProcess(0)
        val bssid = assertNotNullFrame(radiod.awaitMessages<RadioMessage.StartBeacon>(1).single().beacon).addr2
        val payload = console.qrPayload()
        val ssid = ssidOf(payload)
        val kart = kartFor(payload, bssid)

        // The kart joins, takes its lease and opens a pairing connection, which the probe accepts.
        radiod.exchange(kart.authRequest(), "the authentication response")
        radiod.exchange(kart.assocRequest(ssid), "the association response")
        radiod.exchange(kart.dhcpFrame(kart.discover(), pn = 1), "the DHCP OFFER")
        radiod.exchange(kart.dhcpFrame(kart.request(), pn = 2), "the DHCP ACK")
        radiod.openPairingConnection(kart, bssid, kartPort = KART_PORT, pn = 3)
        logs.awaitLine("pairing TCP accepted -> peer=${AddressPlan.KART_LEASE}:$KART_PORT")

        // It leaves (its pairing never finishes, so the kart resets). The log lines are the proof that the lease is
        // freed and that the abort of the connection was requested; the connection really being gone is shown by the
        // new connection below.
        radiod.sendRxFrame(kart.deauth())
        logs.awaitLine("freeLeaseWhenStationLeaves(): the station left, DHCP lease freed -> sta=${kart.mac}")
        logs.awaitLine("the station left, aborting its pairing connection -> peer=${AddressPlan.KART_LEASE}:$KART_PORT")

        // It comes back and does DHCP again (the OFFER only shows that DHCP still answers: the server offers to any
        // client, so it is no proof that the lease was freed) and connects from another source port, which the stack
        // would ignore while the old connection still held the port: the probe accepts it as well.
        radiod.exchange(kart.authRequest(), "the authentication response")
        radiod.exchange(kart.assocRequest(ssid), "the association response")
        val offer = assertNotNull(kart.dhcpFromAp(radiod.exchange(kart.dhcpFrame(kart.discover(), pn = 5), "the DHCP OFFER")))
        assertEquals(DhcpMessageType.OFFER, offer.messageType)
        radiod.openPairingConnection(kart, bssid, kartPort = KART_PORT + 1, pn = 6)
        logs.awaitLine("pairing TCP accepted -> peer=${AddressPlan.KART_LEASE}:${KART_PORT + 1}")

        stop.complete(StopRequest("test finished", failed = false))
        assertEquals(EXIT_OK, session.await())
    }

    // ---- Failure and lifecycle --------------------------------------------------------------------

    @Test
    fun aRadiodErrorDuringBringUpEndsTheSessionWithAFailureAndStillShutsRadiodDown() = sessionTest {
        launcher.initReply = RadioMessage.Error(code = 3u, text = "cannot open the adapter")
        val console = ConsoleCapture()

        val code = runSpikeSession(args(), CompletableDeferred(), launcher = launcher, nonceTable = knownNonceTable(), console = console::add)

        assertEquals(EXIT_RADIO_FAILED, code)
        val radiod = launcher.processes.single()
        assertTrue(radiod.messages().any { it is RadioMessage.Shutdown }, "cleanup still shuts radiod down")
        assertTrue(radiod.messages().none { it is RadioMessage.StartBeacon }, "no beacon after a failed bring-up")
        // The pcap file exists and is a valid, empty capture; the QR image was removed, so it is the only file left.
        val pcapFile = Files.list(outDir).use { files -> files.toList() }.single()
        assertEquals(0, parsePcap(Files.readAllBytes(pcapFile)).frames.size)
    }

    @Test
    fun aStopWhileTheRadioIsStillComingUpNeverServesTheNetworkAndStillShutsRadiodDown() = sessionTest {
        launcher.initReply = null // radiod never answers INIT, so the bring-up is still waiting for READY at the stop
        val console = ConsoleCapture()
        val logs = LogCapture()
        val stop = CompletableDeferred<StopRequest>()
        val session = async(Dispatchers.Default) {
            runSpikeSession(args(), stop, launcher = launcher, nonceTable = knownNonceTable(), console = console::add, log = logs.logger())
        }
        val radiod = launcher.awaitProcess(0)
        radiod.awaitMessages<RadioMessage.Init>(1)

        stop.complete(StopRequest("test finished", failed = false))

        assertEquals(EXIT_OK, session.await())
        val messages = radiod.messages()
        assertTrue(messages.none { it is RadioMessage.SetIdentity || it is RadioMessage.StartBeacon }, "nothing is served while stopping")
        assertTrue(messages.any { it is RadioMessage.Shutdown }, "cleanup still shuts radiod down")
        assertFalse(console.text().contains("The pairing network is on air"), "no on-air banner while stopping")
        assertFalse(logs.has("endSessionJobs()"), "the waiting bring-up ended as soon as it was cancelled:\n${logs.text()}")
        assertEquals(1, launcher.processes.size, "radiod is not launched again")
    }

    @Test
    fun aRadiodRestartBringsTheRadioUpAgainOnTheNewConnection() = sessionTest {
        val stop = CompletableDeferred<StopRequest>()
        // The adapter named on the command line, not the default: --vid and --pid reach every bring-up.
        val args = args().copy(vid = 0x2357, pid = 0x012D)
        val session = async(Dispatchers.Default) { runSpikeSession(args, stop, launcher = launcher, nonceTable = knownNonceTable(), console = ConsoleCapture()::add) }
        val first = launcher.awaitProcess(0)
        val firstBeacon = first.awaitMessages<RadioMessage.StartBeacon>(1).single()
        assertEquals(RadioMessage.OpenDevice(0x2357, 0x012D), first.messages()[1])

        first.crash() // radiod dies; the supervisor restarts it after its backoff and the link accepts the new one

        val second = launcher.awaitProcess(1)
        val secondBeacon = second.awaitMessages<RadioMessage.StartBeacon>(1).single()
        assertEquals(
            listOf("Hello", "OpenDevice", "Init", "SetIdentity", "StartBeacon"),
            second.messages().map { it::class.simpleName },
            "the spike names its adapter and never scans",
        )
        assertEquals(RadioMessage.OpenDevice(0x2357, 0x012D), second.messages()[1])
        assertContentEquals(firstBeacon.beacon, secondBeacon.beacon, "the same pairing network is beaconed again")

        stop.complete(StopRequest("test finished", failed = false))
        assertEquals(EXIT_OK, session.await())
        assertTrue(second.messages().any { it is RadioMessage.Shutdown })
        assertEquals(2, launcher.processes.size, "no third launch during shutdown")
    }

    @Test
    fun theDurationOptionEndsTheSessionByItself() = sessionTest {
        val code = runSpikeSession(args(durationSeconds = 1), CompletableDeferred(), launcher = launcher, nonceTable = knownNonceTable(), console = ConsoleCapture()::add)

        assertEquals(EXIT_OK, code)
        assertTrue(launcher.processes.single().messages().any { it is RadioMessage.Shutdown })
    }

    @Test
    fun withNoQrTheConsoleCodeIsSkippedButTheImageIsStillWrittenAndPrinted() = sessionTest {
        val console = ConsoleCapture()
        val stop = CompletableDeferred<StopRequest>()
        val session = async(Dispatchers.Default) { runSpikeSession(args().copy(showQr = false), stop, launcher = launcher, nonceTable = knownNonceTable(), console = console::add) }
        launcher.awaitProcess(0).awaitMessages<RadioMessage.StartBeacon>(1)

        assertFalse(console.text().any { it == '\u2588' || it == '\u2580' || it == '\u2584' }, "no QR code is drawn in the console")
        val qrImage = Files.list(outDir).use { files -> files.toList() }.single { it.fileName.toString().endsWith(".svg") }
        assertEquals(renderQrAsSvg(encodeQrMatrix(console.qrPayload())), Files.readString(qrImage))
        assertTrue(console.text().lines().any { it.startsWith("Pairing QR image") && it.endsWith(qrImage.toAbsolutePath().toUri().toString()) })

        stop.complete(StopRequest("test finished", failed = false))
        assertEquals(EXIT_OK, session.await())
        assertFalse(Files.exists(qrImage), "the QR image is removed at stop")
    }

    @Test
    fun aMissingRadiodExecutableIsReportedAndNothingIsStarted() = sessionTest {
        val console = ConsoleCapture()
        val missing = tempDir.resolve("no-such-radiod")

        val code = runSpikeSession(args().copy(radiod = missing), CompletableDeferred(), launcher = launcher, nonceTable = knownNonceTable(), console = console::add)

        assertEquals(EXIT_USAGE, code)
        assertTrue(console.text().contains("radiod executable was not found"), console.text())
        assertTrue(launcher.processes.isEmpty())
        assertFalse(Files.exists(outDir), "no capture file is created")
    }

    // ---- The stop file ----------------------------------------------------------------------------

    @Test
    fun creatingTheStopFileDuringARunEndsItCleanlyAndTheFileIsGoneAfterwards() = sessionTest {
        val logs = LogCapture()
        val stop = CompletableDeferred<StopRequest>()
        val session = async(Dispatchers.Default) {
            runSpikeSession(args(), stop, launcher = launcher, nonceTable = knownNonceTable(), console = ConsoleCapture()::add, log = logs.logger())
        }
        val radiod = launcher.awaitProcess(0)
        radiod.awaitMessages<RadioMessage.StartBeacon>(1)
        val stopFile = outDir.resolve("spike.stop")
        assertFalse(stop.isCompleted, "nothing has asked the run to stop yet")

        Files.createFile(stopFile)

        val code = withTimeoutOrNull(STOP_TIMEOUT_MS) { session.await() }
        checkNotNull(code) { "the run did not stop within $STOP_TIMEOUT_MS ms of the stop file being created:\n${logs.text()}" }
        assertEquals(EXIT_OK, code)
        val request = stop.await()
        assertEquals("stop file found", request.reason)
        assertFalse(request.failed, "a stop file is a normal stop")
        assertFalse(Files.exists(stopFile), "the stop file is deleted when it has ended the run, so the next run starts clean")
        assertTrue(logs.has("runSpikeSession(): stopping -> reason=stop file found failed=false"), logs.text())

        // The same clean path as Enter: the beacon stops, radiod is shut down and exits, nothing is launched again.
        val afterBeacon = radiod.messages().dropWhile { it !is RadioMessage.StartBeacon }.drop(1).filter { it !is RadioMessage.TxFrame }
        assertEquals(listOf<RadioMessage>(RadioMessage.StopBeacon, RadioMessage.Shutdown), afterBeacon)
        assertEquals(1, launcher.processes.size, "radiod exited by itself after SHUTDOWN and was not launched again")
        assertFalse(Files.exists(launcher.configs.single().socketPath.parent), "the socket and its directory are removed")
        // Only the pcap is left in the out directory: the QR image went with the stop, and so did the stop file.
        assertTrue(Files.list(outDir).use { files -> files.toList() }.single().fileName.toString().endsWith(".pcap"))
    }

    @Test
    fun aStopFileLeftByAnEarlierRunIsRemovedAtStartAndDoesNotEndTheRun() = sessionTest {
        Files.createDirectories(outDir)
        val stopFile = Files.createFile(outDir.resolve("spike.stop"))
        val console = ConsoleCapture()
        val logs = LogCapture()
        val stop = CompletableDeferred<StopRequest>()
        val session = async(Dispatchers.Default) {
            runSpikeSession(args(), stop, launcher = launcher, nonceTable = knownNonceTable(), console = console::add, log = logs.logger())
        }
        // A run that a stale file ended early never gets to its beacon: fail with what stopped it instead of hanging.
        val beacon = withTimeoutOrNull(LOG_TIMEOUT_MS) {
            launcher.awaitProcess(0).awaitMessages<RadioMessage.StartBeacon>(1)
        }
        checkNotNull(beacon) {
            val reason = if (stop.isCompleted) stop.await().reason else "none"
            "the beacon never started within $LOG_TIMEOUT_MS ms -> stopReason=$reason\n${console.text()}\n${logs.text()}"
        }
        val radiod = launcher.processes.first()

        // The watcher looks as soon as it starts and then every STOP_FILE_POLL_MS: one more look after the beacon is enough
        // to show that a file older than the run does not stop it.
        delay(STOP_FILE_POLL_MS)

        assertFalse(Files.exists(stopFile), "the stale stop file is removed before the radio comes up")
        assertTrue(logs.has("removed a stop file left by an earlier run"), logs.text())
        assertFalse(stop.isCompleted, "a file older than the run does not stop it:\n${logs.text()}")
        assertFalse(session.isCompleted, "the run is still going")
        assertTrue(radiod.messages().none { it is RadioMessage.StopBeacon || it is RadioMessage.Shutdown }, "radiod was left alone")

        // Another stop still works, and the run ends the usual way.
        stop.complete(StopRequest("test finished", failed = false))
        assertEquals(EXIT_OK, session.await())
        assertTrue(radiod.messages().any { it is RadioMessage.Shutdown })
    }

    @Test
    fun aStopFileThatCannotBeRemovedAtStartRefusesTheRunBeforeAnythingIsStarted() = sessionTest {
        // A directory that is not empty cannot be deleted, on any platform; as a stop file it would end the run at once.
        val blocker = outDir.resolve("spike.stop")
        Files.createDirectories(blocker)
        Files.createFile(blocker.resolve("keep"))
        val console = ConsoleCapture()

        val code = runSpikeSession(args(), CompletableDeferred(), launcher = launcher, nonceTable = knownNonceTable(), console = console::add)

        assertEquals(EXIT_USAGE, code)
        assertTrue(console.text().contains("could not delete the stop file left by an earlier run"), console.text())
        assertTrue(console.text().contains(blocker.toAbsolutePath().toString()), "the message names the path to delete: ${console.text()}")
        assertTrue(launcher.processes.isEmpty(), "radiod is never started")
        assertEquals(listOf("spike.stop"), Files.list(outDir).use { files -> files.toList() }.map { it.fileName.toString() }, "no capture and no QR image were created")
    }

    @Test
    fun theRunningLineNamesTheStopFileByItsAbsolutePathAndHowToCreateItInPowerShell() = sessionTest {
        // A relative --out, as typed by the user: the run's working directory is the repository root, a new terminal's is
        // not, so the line has to carry the absolute path (the test's working directory stands in for the root).
        val relativeOut = Path.of("build", "tmp", "spike-stop-file-test-${System.nanoTime()}")
        try {
            val console = ConsoleCapture()
            val stop = CompletableDeferred<StopRequest>()
            val session = async(Dispatchers.Default) {
                runSpikeSession(args().copy(outDir = relativeOut), stop, launcher = launcher, nonceTable = knownNonceTable(), console = console::add)
            }

            val line = console.awaitLine("Spike running")

            val stopFile = relativeOut.resolve("spike.stop").toAbsolutePath().toString()
            assertFalse(relativeOut.isAbsolute, "the test gives --out as a relative path")
            assertTrue(stopFile in line, "the stop file is named by its absolute path: $line")
            assertTrue("New-Item ${powerShellQuoted(stopFile)}" in line, "the PowerShell command to create it is given: $line")
            assertTrue("Press Enter to stop" in line, "Enter is still the first way to stop: $line")

            stop.complete(StopRequest("test finished", failed = false))
            assertEquals(EXIT_OK, session.await())
        } finally {
            relativeOut.toFile().deleteRecursively()
        }
    }

    @Test
    fun aPathWithAnApostropheIsQuotedForPowerShellByDoublingTheApostrophe() {
        assertEquals("'C:\\it''s\\spike.stop'", powerShellQuoted("C:\\it's\\spike.stop"))
        assertEquals("'C:\\with space\\spike.stop'", powerShellQuoted("C:\\with space\\spike.stop"))
    }

    // ---- Fakes and helpers ------------------------------------------------------------------------

    private val primitives = CryptoPrimitives()

    private fun args(durationSeconds: Long = 0): SpikeArgs =
        SpikeArgs(radiod = radiodExecutable, outDir = outDir, durationSeconds = durationSeconds)

    /** A nonce table whose random pick is always index 0, so the test can recompute the network key. */
    private fun knownNonceTable() = NonceTable(table = ByteArray(0x10000 * 16), nowEpochSeconds = { 0L }, randomInt = { 0 })

    private fun sessionTest(block: suspend CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(60.seconds) { block() } }

    private fun assertNotNullFrame(bytes: ByteArray): Ieee80211Frame =
        checkNotNull(Ieee80211Frame.parse(bytes)) { "not an 802.11 frame" }

    /** The SSID in the 62-octet pairing payload (octets 0x10 to 0x2F, zero padded). */
    private fun ssidOf(payload: ByteArray): String =
        payload.copyOfRange(0x10, 0x30).takeWhile { it != 0.toByte() }.toByteArray().decodeToString()

    /** The fake kart of a session: it derives the network's CCMP key from the pairing payload, as the real kart would. */
    private fun kartFor(payload: ByteArray, bssid: MacAddress): FakeStation {
        val key = Lp2pKeys(primitives).deriveCcmpKey(knownNonceTable().getPlaintextNonce(0), primitives.sha256(payload.copyOf(16)))
        return FakeStation(key, bssid, primitives = primitives)
    }

    /** An IPv4 packet from the kart's lease to the app carrying [segment]. */
    private fun tcpPacket(segment: TcpSegment): ByteArray = Ipv4Packet(
        source = AddressPlan.KART_LEASE,
        destination = AddressPlan.AP,
        protocol = Ipv4Packet.PROTOCOL_TCP,
        payload = segment.encode(AddressPlan.KART_LEASE, AddressPlan.AP),
    ).encode()

    /** The ARP packet in a captured (decrypted) data frame, or null when it carries something else. */
    private fun arpOf(frame: Ieee80211Frame): ArpPacket? {
        val snap = LlcSnap.unwrap(frame.body) ?: return null
        return if (snap.etherType == EtherType.ARP) ArpPacket.decode(snap.payload) else null
    }

    /** The TCP segment in a captured (decrypted) data frame, or null when it carries something else. */
    private fun tcpOf(frame: Ieee80211Frame): TcpSegment? {
        val snap = LlcSnap.unwrap(frame.body) ?: return null
        if (snap.etherType != EtherType.IPV4) return null
        val packet = Ipv4Packet.parse(snap.payload) ?: return null
        if (packet.protocol != Ipv4Packet.PROTOCOL_TCP) return null
        return TcpSegment.parse(packet.payload, packet.source, packet.destination)
    }

    /**
     * Plays the kart opening a TCP connection from [kartPort] to the app's pairing port: the SYN, which must be answered
     * with a SYN-ACK, then the ACK that completes the handshake. Uses packet numbers [pn] and [pn] + 1.
     */
    private suspend fun FakeRadiod.openPairingConnection(kart: FakeStation, bssid: MacAddress, kartPort: Int, pn: Long) {
        val syn = TcpSegment(kartPort, PAIRING_PORT, KART_ISS, 0, TcpSegment.SYN, TCP_WINDOW, maxSegmentSize = TCP_MSS)
        val synAckFrame = exchange(kart.linkFrame(EtherType.IPV4, tcpPacket(syn), pn, destination = bssid), "the SYN-ACK")
        val payload = assertNotNull(kart.fromAp(synAckFrame), "the kart cannot decrypt the SYN-ACK")
        assertEquals(EtherType.IPV4, payload.etherType)
        val packet = assertNotNull(Ipv4Packet.parse(payload.payload))
        assertEquals(AddressPlan.AP, packet.source)
        assertEquals(AddressPlan.KART_LEASE, packet.destination)
        val synAck = assertNotNull(TcpSegment.parse(packet.payload, AddressPlan.AP, AddressPlan.KART_LEASE))
        assertTrue(synAck.isSyn && synAck.isAck, "the pairing port answers a SYN with SYN+ACK")
        assertEquals(KART_ISS + 1, synAck.acknowledgmentNumber)
        assertEquals(PAIRING_PORT, synAck.sourcePort)
        assertEquals(kartPort, synAck.destinationPort)

        val ack = TcpSegment(kartPort, PAIRING_PORT, KART_ISS + 1, (synAck.sequenceNumber + 1) and MASK_32, TcpSegment.ACK, TCP_WINDOW)
        sendRxFrame(kart.linkFrame(EtherType.IPV4, tcpPacket(ack), pn + 1, destination = bssid))
    }

    /** Collects what the session prints to the console. */
    private class ConsoleCapture {
        private val lines = CopyOnWriteArrayList<String>()

        fun add(line: String) {
            lines += line
        }

        fun text(): String = lines.joinToString("\n")

        /** Suspends until a line starting with [prefix] is printed and returns it; fails the test with the output if none is. */
        suspend fun awaitLine(prefix: String): String {
            val line = withTimeoutOrNull(LOG_TIMEOUT_MS) {
                var found = lines.firstOrNull { it.startsWith(prefix) }
                while (found == null) {
                    delay(POLL_MS)
                    found = lines.firstOrNull { it.startsWith(prefix) }
                }
                found
            }
            return checkNotNull(line) { "no console line starts with '$prefix' within $LOG_TIMEOUT_MS ms:\n${text()}" }
        }

        /** The 62-octet pairing payload, from the hex line printed after its heading. */
        fun qrPayload(): ByteArray {
            val heading = lines.indexOfFirst { it.startsWith("Pairing QR payload") }
            check(heading >= 0) { "no QR payload heading in the console output:\n${text()}" }
            return lines[heading + 1].hexToByteArray()
        }
    }

    private companion object {
        const val POLL_MS = 20L
        const val LOG_TIMEOUT_MS = 10_000L

        /** How long a run may take to stop (a poll of the stop file plus the teardown) before the test gives up on it. */
        const val STOP_TIMEOUT_MS = 15_000L

        /** The app's TCP pairing port (the production session's), and the fake kart's TCP numbers. */
        val PAIRING_PORT = SessionConfig().pairingRcdPort
        const val KART_PORT = 40000
        const val KART_ISS = 1000L
        const val TCP_WINDOW = 8192
        const val TCP_MSS = 1460
        const val MASK_32 = 0xFFFFFFFFL
    }
}

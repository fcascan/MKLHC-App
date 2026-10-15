package com.mklhc.core.radio

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Loopback tests for [DesktopRadioLink]: an in-process fake radiod connects to the link's real
 * Unix domain socket and speaks the IPC protocol through [IpcCodec]. This verifies the transport
 * (HELLO, command framing, RX and event dispatch, reconnects, malformed input) without the real
 * radiod binary, which is exercised only in the hardware spike (Task 1.8).
 */
class DesktopRadioLinkTest {

    /** The radiod side of the socket, driven by the test. */
    private class FakeRadiod(socketPath: Path) {
        val channel: SocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX).also {
            it.connect(UnixDomainSocketAddress.of(socketPath))
        }

        /** Reads the next message sent by the app; cancellable (interrupt closes the channel). */
        suspend fun receive(): RadioMessage = runInterruptible(Dispatchers.IO) {
            val header = ByteArray(IpcCodec.HEADER_SIZE)
            readFully(header, 0, header.size)
            val length = IpcCodec.payloadLengthOf(header)
            val bytes = ByteArray(IpcCodec.HEADER_SIZE + length)
            header.copyInto(bytes)
            readFully(bytes, IpcCodec.HEADER_SIZE, length)
            IpcCodec.decode(bytes)
        }

        fun send(message: RadioMessage) = sendRaw(IpcCodec.encode(message))

        fun sendRaw(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        fun close() {
            runCatching { channel.close() }
        }

        private fun readFully(target: ByteArray, offset: Int, length: Int) {
            val buffer = ByteBuffer.wrap(target, offset, length)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw EOFException("link closed the connection")
            }
        }
    }

    private val tempDir: Path = Files.createTempDirectory("mklhc-radio")
    private val socketPath: Path = tempDir.resolve("radiod.sock")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val link = DesktopRadioLink(socketPath = socketPath, token = "session-token", scope = scope)
    private val radiods = mutableListOf<FakeRadiod>()

    @AfterTest
    fun tearDown() {
        radiods.forEach { it.close() }
        link.close()
        scope.cancel()
        tempDir.toFile().deleteRecursively()
    }

    private fun connectRadiod(): FakeRadiod = FakeRadiod(socketPath).also { radiods += it }

    /** Binds the link, connects a fake radiod and consumes the HELLO that opens the session. */
    private suspend fun connectedRadiod(): FakeRadiod {
        link.listen()
        val radiod = connectRadiod()
        assertThat(radiod.receive())
            .isEqualTo(RadioMessage.Hello(IpcCodec.PROTOCOL_VERSION, "session-token"))
        link.connected.first { it }
        return radiod
    }

    private fun linkTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30.seconds) { block() } }

    @Test
    fun `sends HELLO with the version and token when radiod connects`() = linkTest {
        link.listen()
        assertThat(link.connected.value).isFalse()

        val radiod = connectRadiod()

        assertThat(radiod.receive())
            .isEqualTo(RadioMessage.Hello(protocolVersion = IpcCodec.PROTOCOL_VERSION, token = "session-token"))
        link.connected.first { it }
    }

    @Test
    fun `commands are framed to radiod in call order`() = linkTest {
        val radiod = connectedRadiod()
        val beacon = ByteArray(120) { it.toByte() }
        val frame = byteArrayOf(0x80.toByte(), 0, 0, 0)

        link.openDevice(vid = 0x0bda, pid = 0xc812)
        link.init(channel = 6, bandwidthMhz = 20, txPowerIndex = 30)
        link.setIdentity(byteArrayOf(2, 1, 2, 3, 4, 5))
        link.startBeacon(intervalTu = 100, beacon = beacon)
        link.updateBeacon(beacon)
        link.txFrame(rate = "6M", retryLimit = 7, flags = 0, frame = frame)
        link.stopBeacon()
        link.shutdown()

        val received = List(8) { radiod.receive() }
        assertThat(received).containsExactly(
            RadioMessage.OpenDevice(0x0bda, 0xc812),
            RadioMessage.Init(6, 20, 30),
            RadioMessage.SetIdentity(byteArrayOf(2, 1, 2, 3, 4, 5)),
            RadioMessage.StartBeacon(100, beacon),
            RadioMessage.UpdateBeacon(beacon),
            RadioMessage.TxFrame("6M", 7, 0, frame),
            RadioMessage.StopBeacon,
            RadioMessage.Shutdown,
        )
    }

    @Test
    fun `concurrent commands never interleave their bytes`() = linkTest {
        val radiod = connectedRadiod()
        val count = 100

        val senders = (0 until count).map { index ->
            async(Dispatchers.Default) {
                link.txFrame("6M", 1, 0, ByteArray(300) { index.toByte() })
            }
        }
        senders.awaitAll()

        val received = List(count) { radiod.receive() as RadioMessage.TxFrame }
        val firstBytes = received.map { it.frame.first().toInt() and 0xFF }.sorted()
        assertThat(firstBytes).isEqualTo((0 until count).toList())
        received.forEach { message -> assertThat(message.frame.toSet().size).isEqualTo(1) }
    }

    @Test
    fun `RX_FRAME from radiod reaches rxFrames`() = linkTest {
        val radiod = connectedRadiod()
        val expected = RadioFrame(rssiDbm = -55, rate = 12, tsf = 0x1122334455667788uL, frame = byteArrayOf(0x40, 1, 2))

        link.rxFrames.test(timeout = 5.seconds) {
            radiod.send(RadioMessage.RxFrame(-55, 12, 0x1122334455667788uL, byteArrayOf(0x40, 1, 2)))
            assertThat(awaitItem()).isEqualTo(expected)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `READY STATUS LOG and ERROR reach events in order`() = linkTest {
        val radiod = connectedRadiod()
        val ready = RadioMessage.Ready("RTL8812CU", "v9", "0.3")
        val status = RadioMessage.Status(rx = 1u, tx = 2u, txErrors = 0u, beacons = 9u)
        val logLine = RadioMessage.Log(RadioMessage.Log.LEVEL_INFO, "usb opened")
        val error = RadioMessage.Error(code = 12u, text = "tx stalled")

        link.events.test(timeout = 5.seconds) {
            radiod.send(ready)
            radiod.send(status)
            radiod.send(logLine)
            radiod.send(error)
            assertThat(awaitItem()).isEqualTo(ready)
            assertThat(awaitItem()).isEqualTo(status)
            assertThat(awaitItem()).isEqualTo(logLine)
            assertThat(awaitItem()).isEqualTo(error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a malformed message is skipped and the stream stays in sync`() = linkTest {
        val radiod = connectedRadiod()
        val unknownType = byteArrayOf(0x77, 0x77, 0, 0, 2, 0, 0, 0, 9, 9)
        val valid = RadioMessage.RxFrame(-40, 6, 5uL, byteArrayOf(1))

        link.rxFrames.test(timeout = 5.seconds) {
            radiod.sendRaw(unknownType)
            radiod.send(valid)
            assertThat(awaitItem()).isEqualTo(valid.toRadioFrame())
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(link.connected.value).isTrue()
    }

    @Test
    fun `an oversized declared length drops the connection`() = linkTest {
        val radiod = connectedRadiod()

        radiod.sendRaw(byteArrayOf(0x02, 0x01, 0, 0, -1, -1, -1, -1))

        link.connected.first { !it }
        assertFailsWith<IllegalStateException> { link.stopBeacon() }
    }

    @Test
    fun `commands fail while radiod is not connected`() = linkTest {
        link.listen()

        assertFailsWith<IllegalStateException> { link.txFrame("6M", 0, 0, byteArrayOf(1)) }
    }

    @Test
    fun `a radiod restart is picked up on the same socket`() = linkTest {
        val first = connectedRadiod()
        first.close()
        link.connected.first { !it }
        assertFailsWith<IllegalStateException> { link.stopBeacon() }

        val second = connectRadiod()
        assertThat(second.receive())
            .isEqualTo(RadioMessage.Hello(IpcCodec.PROTOCOL_VERSION, "session-token"))
        link.connected.first { it }
        link.openDevice(vid = 1, pid = 2)

        assertThat(second.receive()).isEqualTo(RadioMessage.OpenDevice(1, 2))
    }

    @Test
    fun `listen twice is rejected`() = linkTest {
        link.listen()

        assertFailsWith<IllegalStateException> { link.listen() }
    }

    @Test
    fun `close drops the connection and removes the socket file`() = linkTest {
        connectedRadiod()
        assertThat(Files.exists(socketPath)).isTrue()

        link.close()

        assertThat(link.connected.value).isFalse()
        assertThat(Files.exists(socketPath)).isFalse()
        assertFailsWith<IllegalStateException> { link.stopBeacon() }
    }
}

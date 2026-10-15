package com.mklhc.core.radio

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [RadioLink] test double, shipped in main sources so other modules can use it in tests.
 *
 * Business logic: every command is recorded, in call order, as the [RadioMessage.AppToRadiod]
 * radiod would have received ([sentMessages]); nothing touches a socket. A test plays the radiod
 * side by pushing received frames with [emitRxFrame] and notifications with [emitEvent]. The flows
 * behave like the real link's: hot, no replay, so collect before emitting.
 *
 * Failure injection: set [failure] to make every following command throw it instead of recording.
 * The recorded list is held in a [StateFlow], so concurrent commands never lose a record.
 */
class FakeRadioLink : RadioLink {
    private val rxFramesFlow = MutableSharedFlow<RadioFrame>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val eventsFlow = MutableSharedFlow<RadioMessage>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val sent = MutableStateFlow<List<RadioMessage.AppToRadiod>>(emptyList())

    override val rxFrames: Flow<RadioFrame> = rxFramesFlow.asSharedFlow()

    override val events: Flow<RadioMessage> = eventsFlow.asSharedFlow()

    /** Every command received so far, oldest first. */
    val sentMessages: List<RadioMessage.AppToRadiod>
        get() = sent.value

    /** Same as [sentMessages] but observable, for tests that wait for a command to arrive. */
    val sentMessagesFlow: StateFlow<List<RadioMessage.AppToRadiod>> = sent.asStateFlow()

    /** When non-null, every command throws this instead of being recorded. */
    var failure: Throwable? = null

    /** Returns the recorded commands of type [T], oldest first. */
    inline fun <reified T : RadioMessage.AppToRadiod> sentOfType(): List<T> = sentMessages.filterIsInstance<T>()

    /** Forgets every recorded command. */
    fun clearSentMessages() {
        sent.value = emptyList()
    }

    /** Plays radiod delivering a received frame to collectors of [rxFrames]. */
    fun emitRxFrame(frame: RadioFrame) {
        rxFramesFlow.tryEmit(frame)
    }

    /** Plays radiod sending a notification (READY, STATUS, LOG, ERROR) to collectors of [events]. */
    fun emitEvent(event: RadioMessage.RadiodToApp) {
        eventsFlow.tryEmit(event)
    }

    override suspend fun openDevice(vid: Int, pid: Int) = record(RadioMessage.OpenDevice(vid, pid))

    override suspend fun init(channel: Int, bandwidthMhz: Int, txPowerIndex: Int) =
        record(RadioMessage.Init(channel, bandwidthMhz, txPowerIndex))

    override suspend fun setIdentity(mac: ByteArray) = record(RadioMessage.SetIdentity(mac.copyOf()))

    override suspend fun startBeacon(intervalTu: Int, beacon: ByteArray) =
        record(RadioMessage.StartBeacon(intervalTu, beacon.copyOf()))

    override suspend fun updateBeacon(beacon: ByteArray) = record(RadioMessage.UpdateBeacon(beacon.copyOf()))

    override suspend fun stopBeacon() = record(RadioMessage.StopBeacon)

    override suspend fun txFrame(rate: String, retryLimit: Int, flags: Int, frame: ByteArray) =
        record(RadioMessage.TxFrame(rate, retryLimit, flags, frame.copyOf()))

    override suspend fun shutdown() = record(RadioMessage.Shutdown)

    private fun record(message: RadioMessage.AppToRadiod) {
        failure?.let { throw it }
        sent.update { it + message }
    }

    private companion object {
        const val BUFFER_CAPACITY = 256
    }
}

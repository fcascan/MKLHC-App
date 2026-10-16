package com.mklhc.core.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import co.touchlab.kermit.Logger
import java.nio.ByteBuffer

/**
 * Decodes Annex-B H.264 access units with the hardware [MediaCodec] and renders them straight onto a [Surface].
 *
 * Business logic: the decoder is the Android half of the video seam, and it is built for a live feed, so
 * latency beats completeness at every choice:
 * - It waits for the first IDR picture and the stream's SPS/PPS ([KeyframeGate]) before it creates the codec,
 *   configured for H.264 ("video/avc") with those parameter sets, realtime priority and, on Android 11 and
 *   later, the low-latency hint. The codec renders into [surface] itself; no pixel ever reaches the app.
 * - Each access unit is queued with a short wait for a free input slot. If none frees up the decoder is behind:
 *   the picture is dropped and the gate is told, so everything is skipped until the next IDR (a live view is
 *   better frozen for a moment than smeared with corrupt frames).
 * - After each input the output is drained without blocking. If several pictures are ready only the newest is
 *   shown; the older ones are released unrendered, so the display never lags the stream.
 * - A codec error closes the codec and waits for the next IDR to open a new one. After [MAX_CONSECUTIVE_FAILURES]
 *   failures in a row without a single picture shown the decoder gives up ([isUnusable]) so a device that cannot
 *   decode the stream does not loop forever.
 *
 * The picture size is reported through [onVideoSize] when the codec announces its output format, so the host can
 * letterbox the surface to the stream's aspect ratio.
 *
 * Threading: the instance is driven from a single decode coroutine and is not thread-safe. [close] must be called
 * from that coroutine (or after it has finished) and releases the codec.
 *
 * DEVICE-UNVERIFIED: this compiles against the Android SDK but has not run on a device. The assumptions to check at
 * the hardware spike: the kart's stream carries SPS/PPS in-band ahead of its IDR pictures; the stream is
 * decodable at the default 1280x720 size hint (the codec re-reads the real size from the SPS); the decoder
 * tolerates input buffers of [MAX_INPUT_SIZE] octets; and the kart sends IDR pictures often enough that
 * waiting for one after a drop is not noticeable.
 *
 * @param surface the surface the codec renders to; it must stay valid for the lifetime of this decoder.
 * @param onVideoSize called with the picture width and height each time the codec reports its output format.
 */
internal class H264SurfaceDecoder(
    private val surface: Surface,
    private val onVideoSize: (width: Int, height: Int) -> Unit,
) : AutoCloseable {

    private val log = Logger.withTag(TAG)
    private val gate = KeyframeGate()
    private val bufferInfo = MediaCodec.BufferInfo()
    private var codec: MediaCodec? = null
    private var consecutiveFailures = 0

    /** True once the device has failed repeatedly to decode the stream; the caller should stop feeding it. */
    var isUnusable: Boolean = false
        private set

    /** Offers one Annex-B access unit to the decoder; it is decoded, or dropped if it cannot be used right now. */
    fun accept(accessUnit: ByteArray) {
        if (isUnusable) return
        val decision = gate.decide(accessUnit) as? KeyframeGate.Decision.Decode ?: return
        try {
            val active = codec ?: run {
                val parameterSets = decision.parameterSets
                if (parameterSets == null) {
                    // No codec and no parameter sets to open one with: wait for the next IDR.
                    gate.lostSync()
                    return
                }
                openCodec(parameterSets)
            }
            if (!queueAccessUnit(active, accessUnit, decision.keyFrame)) {
                gate.lostSync()
                return
            }
            drainOutput(active)
        } catch (failure: Exception) {
            onCodecFailure(failure)
        }
    }

    override fun close() {
        closeCodec()
    }

    private fun openCodec(parameterSets: ParameterSets): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, INITIAL_WIDTH, INITIAL_HEIGHT).apply {
            setByteBuffer(CSD_SPS, ByteBuffer.wrap(parameterSets.sps))
            setByteBuffer(CSD_PPS, ByteBuffer.wrap(parameterSets.pps))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            setInteger(MediaFormat.KEY_PRIORITY, REALTIME_PRIORITY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            decoder.configure(format, surface, null, 0)
            decoder.start()
        } catch (failure: Exception) {
            runCatching { decoder.release() }
            throw failure
        }
        codec = decoder
        log.i { "openCodec(): decoder started -> name=${decoder.name} sps=${parameterSets.sps.size}B pps=${parameterSets.pps.size}B" }
        return decoder
    }

    /** Queues [accessUnit] into a free input buffer; false when none freed up in time or the unit does not fit. */
    private fun queueAccessUnit(codec: MediaCodec, accessUnit: ByteArray, keyFrame: Boolean): Boolean {
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            log.w { "queueAccessUnit(): no free input buffer, decoder is behind -> dropping until the next IDR" }
            return false
        }
        val input = codec.getInputBuffer(index)
        if (input == null || input.capacity() < accessUnit.size) {
            // Hand the slot back empty so the codec does not lose a buffer.
            codec.queueInputBuffer(index, 0, 0, 0L, 0)
            log.w { "queueAccessUnit(): access unit does not fit -> size=${accessUnit.size} capacity=${input?.capacity()}" }
            return false
        }
        input.clear()
        input.put(accessUnit)
        val flags = if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        codec.queueInputBuffer(index, 0, accessUnit.size, System.nanoTime() / NANOS_PER_MICRO, flags)
        return true
    }

    /** Shows the newest decoded picture, if any, and releases older ones unrendered. */
    private fun drainOutput(codec: MediaCodec) {
        var newest = -1
        var timeoutUs = OUTPUT_TIMEOUT_US
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            // Wait briefly for the first picture only; after that just collect what is already there.
            timeoutUs = 0L
            when {
                index >= 0 -> {
                    if (newest >= 0) codec.releaseOutputBuffer(newest, false)
                    newest = index
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportVideoSize(codec.outputFormat)
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                // Any other info code (the deprecated buffers-changed notice) needs no action.
            }
        }
        if (newest >= 0) {
            codec.releaseOutputBuffer(newest, true)
            consecutiveFailures = 0
        }
    }

    private fun reportVideoSize(format: MediaFormat) {
        val width = if (format.containsKey(CROP_LEFT) && format.containsKey(CROP_RIGHT)) {
            format.getInteger(CROP_RIGHT) - format.getInteger(CROP_LEFT) + 1
        } else {
            format.getInteger(MediaFormat.KEY_WIDTH)
        }
        val height = if (format.containsKey(CROP_TOP) && format.containsKey(CROP_BOTTOM)) {
            format.getInteger(CROP_BOTTOM) - format.getInteger(CROP_TOP) + 1
        } else {
            format.getInteger(MediaFormat.KEY_HEIGHT)
        }
        log.i { "reportVideoSize(): output format changed -> width=$width height=$height" }
        onVideoSize(width, height)
    }

    private fun onCodecFailure(failure: Exception) {
        consecutiveFailures++
        log.e(failure) { "onCodecFailure(): decoder failed -> failures=$consecutiveFailures message=${failure.message}" }
        closeCodec()
        gate.lostSync()
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            isUnusable = true
            log.e { "onCodecFailure(): giving up on this device's decoder after $consecutiveFailures failures in a row" }
        }
    }

    private fun closeCodec() {
        val active = codec ?: return
        codec = null
        runCatching { active.stop() }.onFailure { log.w(it) { "closeCodec(): stop failed -> message=${it.message}" } }
        runCatching { active.release() }.onFailure { log.w(it) { "closeCodec(): release failed -> message=${it.message}" } }
        log.i { "closeCodec(): decoder released" }
    }

    private companion object {
        const val TAG = "H264SurfaceDecoder"

        // The codec reads the real size from the SPS; these only size its buffers until then.
        const val INITIAL_WIDTH = 1280
        const val INITIAL_HEIGHT = 720
        const val MAX_INPUT_SIZE = 1024 * 1024

        // Priority 0 asks for realtime operation.
        const val REALTIME_PRIORITY = 0

        const val INPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 2_000L
        const val NANOS_PER_MICRO = 1_000L
        const val MAX_CONSECUTIVE_FAILURES = 5

        // Codec-specific data keys for the SPS and the PPS, and the keys of the output crop rectangle.
        const val CSD_SPS = "csd-0"
        const val CSD_PPS = "csd-1"
        const val CROP_LEFT = "crop-left"
        const val CROP_RIGHT = "crop-right"
        const val CROP_TOP = "crop-top"
        const val CROP_BOTTOM = "crop-bottom"
    }
}

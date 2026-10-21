package com.mklhc.core.video

import co.touchlab.kermit.Logger
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.LogCallback
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.ffmpeg.swscale.SwsFilter
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer

/*
 * Desktop H.264 decoding with FFmpeg's libavcodec and libswscale, through the JavaCPP presets
 * (org.bytedeco:ffmpeg, LGPL build). Original code; it only calls the FFmpeg API.
 */

/**
 * One decoded picture, ready to draw: tightly packed BGRA, four octets per pixel, rows top to bottom.
 *
 * @property width the picture width in pixels.
 * @property height the picture height in pixels.
 * @property bgra `width * height * 4` octets; a buffer the decoder reuses, valid until its next [FrameDecoder.decode]
 *   call, so copy it (as drawing does) before decoding again.
 */
internal class VideoPicture(val width: Int, val height: Int, val bgra: ByteArray)

/** What the desktop video surface needs from a decoder; [H264FrameDecoder] is the real one. */
internal interface FrameDecoder : AutoCloseable {

    /** True once the decoder has failed repeatedly; the caller should stop feeding it. */
    val isUnusable: Boolean

    /** Decodes one Annex-B access unit; returns its picture, or null when it yields none. Never throws. */
    fun decode(accessUnit: ByteArray): VideoPicture?
}

/**
 * Decodes the kart's H.264 access units in software with FFmpeg and converts each picture to BGRA for drawing.
 *
 * Business logic: the desktop half of the video seam, built for a live feed like the Android decoder:
 * - It waits for the first IDR picture and the stream's SPS/PPS ([KeyframeGate]) before it feeds FFmpeg anything,
 *   since the kart's stream joins mid-flight and the pictures before an IDR cannot be decoded. On a (re)start the
 *   parameter sets are put in front of the access unit, in case they came in an earlier one.
 * - FFmpeg runs single-threaded with the low-delay flag, so a picture comes out of the same [decode] call that fed its
 *   access unit (frame threading would hold pictures back by one per thread). The kart's 1280x800 High-profile
 *   stream decodes in about 3 ms per picture on a laptop, far inside its 33 ms frame time.
 * - An error from FFmpeg flushes the decoder and waits for the next IDR, as a picture decoded after a broken one
 *   would be smeared. After [MAX_CONSECUTIVE_FAILURES] errors without a picture in between the decoder gives up
 *   ([isUnusable]).
 * - Each picture is converted to BGRA by libswscale (BT.601, the stream's colour space) into one array the decoder
 *   reuses while the picture size holds (a fresh 4 MB array per picture would be 120 MB/s of garbage at 30 fps).
 * - Nothing escapes [decode]: an empty access unit is ignored, and any failure, FFmpeg's or the conversion's, is
 *   handled like a decoder error.
 *
 * FFmpeg's own log goes to Kermit at debug level under the tag "FFmpeg", warnings and errors only.
 *
 * Threading: not thread-safe; one decode coroutine drives it and closes it. [close] is idempotent; a closed decoder
 * decodes nothing.
 *
 * @param context the opened H.264 decoder context; owned and freed by this instance.
 * @param log where decoder events are logged.
 */
internal class H264FrameDecoder private constructor(
    private val context: AVCodecContext,
    private val log: Logger,
) : FrameDecoder {

    private val gate = KeyframeGate()
    private val packet: AVPacket = avcodec.av_packet_alloc()
    private val decoded: AVFrame = avutil.av_frame_alloc()
    private var converted: AVFrame? = null
    private var scaler: SwsContext? = null
    private var pixels = ByteArray(0)
    private var consecutiveFailures = 0
    private var closed = false

    override var isUnusable: Boolean = false
        private set

    override fun decode(accessUnit: ByteArray): VideoPicture? {
        if (closed || isUnusable || accessUnit.isEmpty()) return null
        return try {
            decodeAccessUnit(accessUnit)
        } catch (failure: Exception) {
            avutil.av_frame_unref(decoded)
            onFailure("decoding failed: ${failure.message}", 0)
            null
        }
    }

    private fun decodeAccessUnit(accessUnit: ByteArray): VideoPicture? {
        val decision = gate.decide(accessUnit) as? KeyframeGate.Decision.Decode ?: return null
        val parameterSets = decision.parameterSets
        val data = if (parameterSets != null) parameterSets.sps + parameterSets.pps + accessUnit else accessUnit

        if (avcodec.av_new_packet(packet, data.size) < 0) {
            onFailure("av_new_packet failed", 0)
            return null
        }
        packet.data().put(data, 0, data.size)
        val sent = avcodec.avcodec_send_packet(context, packet)
        avcodec.av_packet_unref(packet)
        if (sent < 0) {
            // EAGAIN included: every call drains the decoder, so a refused packet is a picture lost.
            onFailure("avcodec_send_packet failed", sent)
            return null
        }

        var newest: VideoPicture? = null
        while (true) {
            val received = avcodec.avcodec_receive_frame(context, decoded)
            if (received == avutil.AVERROR_EAGAIN() || received == avutil.AVERROR_EOF) break
            if (received < 0) {
                onFailure("avcodec_receive_frame failed", received)
                return null
            }
            // Only the newest picture is shown; with low delay there is normally just one.
            val picture = toBgra(decoded)
            avutil.av_frame_unref(decoded)
            if (picture == null) {
                onFailure("the picture could not be converted", 0)
                return null
            }
            newest = picture
        }
        if (newest != null) consecutiveFailures = 0
        return newest
    }

    override fun close() {
        if (closed) return
        closed = true
        scaler?.let { swscale.sws_freeContext(it) }
        scaler = null
        converted?.let { avutil.av_frame_free(it) }
        converted = null
        avutil.av_frame_free(decoded)
        avcodec.av_packet_free(packet)
        avcodec.avcodec_free_context(context)
        log.i { "close(): decoder released" }
    }

    /**
     * Converts [frame] to a tightly packed BGRA picture in [pixels], reusing the scaler, the target frame and the
     * array while the size holds; null when the scaler or the target cannot be set up.
     */
    private fun toBgra(frame: AVFrame): VideoPicture? {
        val width = frame.width()
        val height = frame.height()
        // A null context (a pixel format the scaler cannot read) must never reach sws_scale: it would crash the JVM.
        val activeScaler = swscale.sws_getCachedContext(
            scaler, width, height, frame.format(), width, height, avutil.AV_PIX_FMT_BGRA, swscale.SWS_BILINEAR,
            null as SwsFilter?, null as SwsFilter?, null as DoublePointer?,
        ) ?: return null
        scaler = activeScaler
        val target = converted?.takeIf { it.width() == width && it.height() == height } ?: newTarget(width, height) ?: return null
        swscale.sws_scale(activeScaler, frame.data(), frame.linesize(), 0, height, target.data(), target.linesize())

        val rowBytes = width * BYTES_PER_PIXEL
        if (pixels.size != rowBytes * height) pixels = ByteArray(rowBytes * height)
        val stride = target.linesize(0)
        val source: BytePointer = target.data(0)
        if (stride == rowBytes) {
            source.position(0).get(pixels, 0, pixels.size)
        } else {
            for (row in 0 until height) source.position(row.toLong() * stride).get(pixels, row * rowBytes, rowBytes)
            source.position(0)
        }
        return VideoPicture(width, height, pixels)
    }

    /** Allocates the BGRA target frame for a new picture size; null (and nothing leaked) when that fails. */
    private fun newTarget(width: Int, height: Int): AVFrame? {
        converted?.let { avutil.av_frame_free(it) }
        converted = null
        val frame = avutil.av_frame_alloc() ?: return null
        frame.format(avutil.AV_PIX_FMT_BGRA)
        frame.width(width)
        frame.height(height)
        val allocated = avutil.av_frame_get_buffer(frame, 1)
        if (allocated < 0) {
            avutil.av_frame_free(frame)
            log.w { "newTarget(): the picture buffer could not be allocated -> width=$width height=$height code=$allocated" }
            return null
        }
        converted = frame
        log.i { "newTarget(): picture size -> width=$width height=$height" }
        return frame
    }

    private fun onFailure(what: String, code: Int) {
        consecutiveFailures++
        log.w { "onFailure(): $what, waiting for the next IDR -> code=$code failures=$consecutiveFailures" }
        avcodec.avcodec_flush_buffers(context)
        gate.lostSync()
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            isUnusable = true
            log.e { "onFailure(): giving up on the decoder after $consecutiveFailures failures in a row" }
        }
    }

    companion object {
        private const val TAG = "H264FrameDecoder"
        private const val BYTES_PER_PIXEL = 4
        private const val MAX_CONSECUTIVE_FAILURES = 30

        /** Forwards FFmpeg's warnings and errors to Kermit; held here so the native side never calls a collected object. */
        private val ffmpegLog: LogCallback by lazy {
            val log = Logger.withTag("FFmpeg")
            object : LogCallback() {
                override fun call(level: Int, message: BytePointer) {
                    if (level <= avutil.AV_LOG_WARNING) log.d { "call(): ${message.string.trim()} -> level=$level" }
                }
            }
        }

        /**
         * Loads FFmpeg and opens an H.264 decoder.
         *
         * @throws IllegalStateException when the native libraries cannot be loaded or the decoder cannot be opened.
         */
        fun open(log: Logger = Logger.withTag(TAG)): H264FrameDecoder {
            try {
                // Process-wide FFmpeg settings; this module is FFmpeg's only user, so setting them on every open is harmless.
                avutil.setLogCallback(ffmpegLog)
                avutil.av_log_set_level(avutil.AV_LOG_WARNING)
                val codec = checkNotNull(avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264)) { "FFmpeg has no H.264 decoder" }
                val context = checkNotNull(avcodec.avcodec_alloc_context3(codec)) { "cannot allocate the decoder context" }
                context.thread_count(1)
                context.flags(context.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
                val opened = avcodec.avcodec_open2(context, codec, null as AVDictionary?)
                if (opened < 0) {
                    avcodec.avcodec_free_context(context)
                    throw IllegalStateException("avcodec_open2 failed -> code=$opened")
                }
                log.i { "open(): FFmpeg H.264 decoder opened -> version=${avcodec.avcodec_version()}" }
                return H264FrameDecoder(context, log)
            } catch (missing: LinkageError) {
                throw IllegalStateException("FFmpeg could not be loaded: ${missing.message}", missing)
            }
        }
    }
}

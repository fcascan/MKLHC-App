package com.mklhc.core.video

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Android actual of the video surface: a [SurfaceView] fed by a hardware H.264 decoder.
 *
 * Business logic: a surface view gives the decoder a window-level surface it can render into directly, which is
 * what keeps latency and power use low. The decoder only exists while that surface does: it is started when the
 * surface is created and stopped, synchronously, when the surface is destroyed (the surface must not be used
 * once the destroy callback returns), and the whole thing is released when the composable leaves the composition.
 * The picture is shown at its own aspect ratio, centred on black, as soon as the decoder has reported the
 * stream's size; until then the surface fills the space. The Compose overlay the cockpit draws on top of this
 * composable stays on top of the video.
 *
 * Passing a different [accessUnits] flow restarts the whole pipeline.
 */
@Composable
actual fun VideoSurface(
    accessUnits: Flow<ByteArray>,
    modifier: Modifier,
) {
    key(accessUnits) {
        var aspectRatio by remember { mutableStateOf<Float?>(null) }
        val host = remember {
            SurfaceDecoderHost(accessUnits) { width, height ->
                if (width > 0 && height > 0) aspectRatio = width.toFloat() / height.toFloat()
            }
        }
        DisposableEffect(host) {
            onDispose { host.release() }
        }

        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val ratio = aspectRatio
            AndroidView(
                factory = { context -> SurfaceView(context).also { it.holder.addCallback(host) } },
                modifier = if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize(),
                onRelease = { view ->
                    view.holder.removeCallback(host)
                    host.stop()
                },
            )
        }
    }
}

/**
 * Owns the decode coroutine for one surface view: starts it when the surface appears, stops it when it goes.
 *
 * Business logic: the surface lifecycle and the decode loop have to stay in lock step. [surfaceCreated] launches a
 * loop on a single background thread that collects the access units into an [H264SurfaceDecoder]; [surfaceDestroyed]
 * cancels it and waits for it to finish, which releases the codec before the surface goes away. The wait is short
 * (the loop never blocks for longer than a codec timeout) and is required: a codec still rendering to a surface that
 * has been destroyed is a crash on some devices. A failure inside the loop is logged and ends it; the next surface
 * creation starts a fresh one.
 */
private class SurfaceDecoderHost(
    private val accessUnits: Flow<ByteArray>,
    private val onVideoSize: (width: Int, height: Int) -> Unit,
) : SurfaceHolder.Callback {

    private val log = Logger.withTag(TAG)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var job: Job? = null

    override fun surfaceCreated(holder: SurfaceHolder) {
        start(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stop()
    }

    /** Starts decoding onto [surface], replacing any loop that is already running. */
    private fun start(surface: Surface) {
        stop()
        log.i { "start(): surface created, starting the decode loop" }
        job = scope.launch {
            try {
                H264SurfaceDecoder(surface, onVideoSize).use { decoder ->
                    accessUnits
                        .takeWhile { !decoder.isUnusable }
                        .collect { decoder.accept(it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.e(failure) { "start(): the decode loop failed -> message=${failure.message}" }
            }
        }
    }

    /** Cancels the decode loop and waits until the codec is released; does nothing when none is running. */
    fun stop() {
        val running = job ?: return
        job = null
        runBlocking { running.cancelAndJoin() }
        log.i { "stop(): decode loop stopped" }
    }

    /** Stops the loop and shuts the host's scope down for good. */
    fun release() {
        stop()
        scope.cancel()
    }

    private companion object {
        const val TAG = "VideoSurface"
    }
}

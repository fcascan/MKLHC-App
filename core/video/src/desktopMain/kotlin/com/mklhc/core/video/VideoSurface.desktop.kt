package com.mklhc.core.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import co.touchlab.kermit.Logger
import com.mklhc.core.video.generated.resources.Res
import com.mklhc.core.video.generated.resources.video_camera_description
import com.mklhc.core.video.generated.resources.video_frames_received
import com.mklhc.core.video.generated.resources.video_unavailable_desktop
import com.mklhc.core.video.generated.resources.video_waiting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage

/**
 * Desktop actual of the video surface: the kart's H.264 decoded in software with FFmpeg and drawn in Compose.
 *
 * Business logic: see [DesktopVideoSurface]; this binds it to the real decoder, [H264FrameDecoder].
 */
@Composable
actual fun VideoSurface(
    accessUnits: Flow<ByteArray>,
    modifier: Modifier,
) {
    DesktopVideoSurface(accessUnits, modifier, openDecoder = { H264FrameDecoder.open() })
}

/**
 * Decodes [accessUnits] off the UI thread and shows the newest picture, letterboxed at its aspect ratio on black.
 *
 * Business logic: a dedicated single-threaded decode loop opens the decoder (loading FFmpeg's native libraries takes
 * a moment, so never on the UI thread), then feeds it every access unit in order and publishes each picture it
 * returns; Compose draws only the latest one, so the display never lags the stream. Until the first picture the box
 * says it is waiting for video and counts the access units received, which tells a stream that arrives but does not
 * decode (the demo session's placeholders, or a kart that has not sent an IDR yet) from no stream at all. When the
 * decoder cannot be opened (FFmpeg missing for this platform), gives up, or the loop fails, any picture is taken
 * down (a frozen picture would look live) and the box says the preview is unavailable and keeps counting: the
 * session goes on without a picture. The decoder is closed when the loop ends, which is when the composable leaves
 * the composition or [accessUnits] changes. The decoder's reused pixel buffer is copied into a Skia image right away,
 * before the next access unit is decoded.
 *
 * @param accessUnits the reassembled Annex-B access units.
 * @param modifier the layout modifier of the box.
 * @param openDecoder opens the decoder; replaceable so a test can make it fail.
 */
@Composable
internal fun DesktopVideoSurface(
    accessUnits: Flow<ByteArray>,
    modifier: Modifier = Modifier,
    openDecoder: () -> FrameDecoder,
) {
    var framesReceived by remember(accessUnits) { mutableLongStateOf(0L) }
    var picture by remember(accessUnits) { mutableStateOf<ImageBitmap?>(null) }
    var unavailable by remember(accessUnits) { mutableStateOf(false) }

    LaunchedEffect(accessUnits) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            val decoder = try {
                openDecoder()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.e(failure) { "DesktopVideoSurface(): the video decoder cannot be opened, no preview -> message=${failure.message}" }
                unavailable = true
                null
            }
            try {
                accessUnits.collect { unit ->
                    framesReceived++
                    if (decoder == null || unavailable) return@collect
                    val decoded = decoder.decode(unit)
                    if (decoder.isUnusable) {
                        // A frozen last picture would look live: take it down and say so.
                        log.e { "DesktopVideoSurface(): the video decoder gave up, no preview" }
                        unavailable = true
                        picture = null
                    } else if (decoded != null) {
                        picture = decoded.toImageBitmap()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.e(failure) { "DesktopVideoSurface(): the decode loop failed, no preview -> message=${failure.message}" }
                unavailable = true
                picture = null
            } finally {
                decoder?.close()
            }
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val shown = picture
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = stringResource(Res.string.video_camera_description),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(if (unavailable) Res.string.video_unavailable_desktop else Res.string.video_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = CAPTION_ALPHA),
                )
                if (framesReceived > 0) {
                    Text(
                        text = stringResource(Res.string.video_frames_received, framesReceived),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = CAPTION_ALPHA),
                    )
                }
            }
        }
    }
}

/** Wraps the BGRA pixels in a Skia image (copied once) that Compose can draw. */
private fun VideoPicture.toImageBitmap(): ImageBitmap =
    SkiaImage.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bgra, width * 4)
        .toComposeImageBitmap()

private val log = Logger.withTag("VideoSurface")

private const val CAPTION_ALPHA = 0.7f

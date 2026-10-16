package com.mklhc.core.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mklhc.core.video.generated.resources.Res
import com.mklhc.core.video.generated.resources.video_frames_received
import com.mklhc.core.video.generated.resources.video_unavailable_desktop
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.stringResource

/**
 * Desktop actual of the video surface: a placeholder, not a decoder.
 *
 * Business logic: a desktop H.264 decoder means shipping heavy native libraries (an FFmpeg binding), which is a
 * separate spike and is deliberately not pulled in yet. Until then the desktop build, which exists to develop and
 * test the UI without a phone, shows a black box with a caption saying the preview is unavailable. It does
 * collect the stream, but only to count the access units, so a developer can see on screen that the session is
 * delivering video; nothing is decoded and no frame is kept.
 */
@Composable
actual fun VideoSurface(
    accessUnits: Flow<ByteArray>,
    modifier: Modifier,
) {
    var framesReceived by remember(accessUnits) { mutableLongStateOf(0L) }
    LaunchedEffect(accessUnits) {
        accessUnits.collect { framesReceived++ }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(Res.string.video_unavailable_desktop),
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

private const val CAPTION_ALPHA = 0.7f

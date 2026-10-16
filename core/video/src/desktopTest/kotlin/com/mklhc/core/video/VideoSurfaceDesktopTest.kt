package com.mklhc.core.video

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * The desktop placeholder of [VideoSurface] rendered offscreen: it must say the preview is unavailable, show
 * nothing about frames while none arrive, and count the access units the flow delivers (it decodes nothing).
 */
@OptIn(ExperimentalTestApi::class)
class VideoSurfaceDesktopTest {

    @Test
    fun showsTheUnavailableCaptionAndNoCountWhileNoFramesArrive() = runComposeUiTest {
        setContent { VideoSurface(emptyFlow()) }

        onNodeWithText("Video preview unavailable on desktop").assertExists()
        onNodeWithText("Receiving video: 0 frames").assertDoesNotExist()
    }

    @Test
    fun countsTheAccessUnitsTheFlowDelivers() = runComposeUiTest {
        val frames = flowOf(ByteArray(4), ByteArray(4), ByteArray(4))
        setContent { VideoSurface(frames) }

        waitForIdle()

        onNodeWithText("Receiving video: 3 frames").assertExists()
    }
}

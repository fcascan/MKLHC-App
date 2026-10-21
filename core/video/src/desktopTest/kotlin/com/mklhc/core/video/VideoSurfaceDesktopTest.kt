package com.mklhc.core.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * The desktop [VideoSurface] rendered offscreen: it waits (counting the access units) until a picture decodes, then
 * shows the camera picture; when the decoder cannot be loaded it says the preview is unavailable and keeps counting.
 */
@OptIn(ExperimentalTestApi::class)
class VideoSurfaceDesktopTest {

    private val stream = SyntheticH264()

    @Test
    fun waitsForVideoWithoutACountWhileNothingArrives() = runComposeUiTest {
        setContent { VideoSurface(emptyFlow()) }

        onNodeWithText("Waiting for video").assertExists()
        onNodeWithText("Receiving video: 0 frames").assertDoesNotExist()
        onNodeWithContentDescription("Kart camera").assertDoesNotExist()
    }

    @Test
    fun keepsWaitingAndCountingWhileNothingDecodes() = runComposeUiTest {
        // Not H.264, as the demo session streams: received, but never a picture.
        setContent { VideoSurface(flowOf(ByteArray(4), ByteArray(4), ByteArray(4))) }

        waitUntil(timeoutMillis = WAIT_MILLIS) { onAllNodesWithText("Receiving video: 3 frames") }
        onNodeWithText("Waiting for video").assertExists()
    }

    @Test
    fun showsTheCameraPictureOnceAFrameDecodes() = runComposeUiTest {
        setContent { VideoSurface(flowOf(stream.idr(y = 81, cb = 90, cr = 240))) }

        waitUntil(timeoutMillis = WAIT_MILLIS) { onAllNodesWithContentDescription("Kart camera") }
        onNodeWithText("Waiting for video").assertDoesNotExist()
    }

    @Test
    fun saysThePreviewIsUnavailableWhenTheDecoderCannotBeLoadedAndKeepsCounting() = runComposeUiTest {
        setContent {
            DesktopVideoSurface(
                accessUnits = flowOf(stream.idr(y = 81, cb = 90, cr = 240), ByteArray(4)),
                openDecoder = { throw IllegalStateException("FFmpeg could not be loaded") },
            )
        }

        waitUntil(timeoutMillis = WAIT_MILLIS) { onAllNodesWithText("Receiving video: 2 frames") }
        onNodeWithText("Video preview unavailable").assertExists()
        onNodeWithContentDescription("Kart camera").assertDoesNotExist()
    }

    @Test
    fun takesThePictureDownWhenTheDecoderGivesUp() = runComposeUiTest {
        val decoder = GivingUpDecoder()
        val units = MutableSharedFlow<ByteArray>(replay = 2)
        setContent { DesktopVideoSurface(accessUnits = units, openDecoder = { decoder }) }

        units.tryEmit(ByteArray(4))
        waitUntil(timeoutMillis = WAIT_MILLIS) { onAllNodesWithContentDescription("Kart camera") }

        units.tryEmit(ByteArray(4)) // the decoder gives up on this one
        waitUntil(timeoutMillis = WAIT_MILLIS) { onAllNodesWithText("Video preview unavailable") }
        onNodeWithContentDescription("Kart camera").assertDoesNotExist()
    }

    @Test
    fun closesTheDecoderWhenTheSurfaceLeavesTheComposition() = runComposeUiTest {
        val decoder = GivingUpDecoder()
        var shown by mutableStateOf(true)
        setContent { if (shown) DesktopVideoSurface(accessUnits = MutableSharedFlow(), openDecoder = { decoder }) }
        waitForIdle()

        shown = false

        waitUntil(timeoutMillis = WAIT_MILLIS) { decoder.closed }
    }

    /** Yields one 2x2 picture, then fails for good on the next access unit. */
    private class GivingUpDecoder : FrameDecoder {
        private var calls = 0

        @Volatile
        var closed = false

        override var isUnusable: Boolean = false

        override fun decode(accessUnit: ByteArray): VideoPicture? {
            calls++
            if (calls == 1) return VideoPicture(2, 2, ByteArray(2 * 2 * 4) { 0x7f })
            isUnusable = true
            return null
        }

        override fun close() {
            closed = true
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithText(text: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithContentDescription(text: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}

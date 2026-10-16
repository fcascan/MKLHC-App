package com.mklhc.core.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow

/**
 * Renders the kart's live H.264 video: the one place in the app that touches the raw frame flow.
 *
 * Business logic: the kart streams H.264, which the session layer reassembles into complete access units
 * (Annex-B, start-code framed; the parameter sets SPS and PPS are expected in-band, ahead of the IDR pictures).
 * This composable takes that stream and puts the pictures on screen; it fills the space the [modifier] gives
 * it. It is the seam between the platform-neutral cockpit and the platform's decoder, so the frames never
 * travel through ViewModel state (a state holder updating per frame would recompose the screen at the video
 * rate): the ViewModel exposes the session's flow as is and hands it straight here.
 *
 * Platform behaviour:
 * - Android: a `SurfaceView` fed by a hardware `MediaCodec` configured for low latency where the device
 *   supports it. Decoding starts at the first IDR picture, and when the decoder falls behind frames are dropped
 *   until the next IDR rather than queued (a live picture is worth more than a complete one). The decoder is
 *   released when the surface goes away or the composable leaves the composition. It has been compiled but
 *   not yet exercised on a device.
 * - Desktop: a placeholder box with a caption (and a count of the frames received). A real desktop H.264
 *   decoder is deferred to its own spike, because it means shipping heavy native libraries.
 *
 * Collection: the flow is collected for as long as the composable is in the composition, on a background
 * dispatcher on Android. Pass an empty flow in previews and tests.
 *
 * @param accessUnits the reassembled H.264 access units, in stream order.
 * @param modifier the modifier to apply; the surface fills the space it is given.
 */
@Composable
expect fun VideoSurface(
    accessUnits: Flow<ByteArray>,
    modifier: Modifier = Modifier,
)

package com.mklhc.core.design_system.drive

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Makes the modified element a one-axis touch control: it reports where the finger is along the element, and
 * reports when the finger lets go.
 *
 * Business logic: this is the gesture behind the steering slider and the throttle pedal, and it carries the
 * safety rule of the drive controls: RELEASE MEANS NEUTRAL. The gesture starts the moment a finger goes down
 * (not after a touch slop, so the control answers a plain press) and reports every move as a normalised -1..1
 * value through [onValue]. When the finger lifts, OR the gesture is cancelled by the system or by another
 * handler, OR the control is disabled or leaves the composition while a finger is down, [onRelease] is called
 * exactly once, in a `finally`, so no path can leave the last non-neutral value standing. The cockpit answers
 * [onRelease] by sending a neutral command; a control that did not report release would keep driving the kart.
 * Only the first pointer is tracked, so a second finger on the same control does nothing.
 *
 * The callbacks are read through [State] so a recomposition that swaps the lambdas is picked up by a gesture
 * already in flight, without restarting it (a restart would itself release the control).
 *
 * @param enabled whether the control reacts; turning it off while a finger is down releases it.
 * @param vertical whether the axis runs along the element's height (top is +1) rather than its width (left is -1).
 * @param insetFraction how far in from each end the thumb centre stops, as a fraction of the element's cross
 *   dimension (its width for a vertical control, its height for a horizontal one); it must match the drawing.
 * @param onValue called with each new normalised value while a finger is down.
 * @param onRelease called once when the finger lifts or the gesture ends for any other reason.
 */
internal fun Modifier.axisTouch(
    enabled: Boolean,
    vertical: Boolean,
    insetFraction: Float,
    onValue: State<(Float) -> Unit>,
    onRelease: State<() -> Unit>,
): Modifier = pointerInput(enabled, vertical, insetFraction) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val main = (if (vertical) size.height else size.width).toFloat()
        val cross = (if (vertical) size.width else size.height).toFloat()
        val inset = cross * insetFraction
        fun axisAt(point: Offset): Float = axisFromPosition(
            position = if (vertical) point.y else point.x,
            length = main,
            inverted = vertical,
            inset = inset,
        )

        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        try {
            onValue.value(axisAt(down.position))
            drag(down.id) { change ->
                onValue.value(axisAt(change.position))
                change.consume()
            }
        } finally {
            onRelease.value()
        }
    }
}

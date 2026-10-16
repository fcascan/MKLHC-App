package com.mklhc.core.design_system.drive

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import com.mklhc.core.design_system.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drive controls' touch behaviour, driven with injected touches: what each control reports while a finger
 * is down and, above all, that EVERY way a finger can leave reports release (finger up, a cancelled gesture, control
 * disabled mid-press), because release is what returns the kart to neutral.
 *
 * The desktop test harness delivers nothing for `cancel()`, so a cancelled gesture is covered through the causes
 * it can reproduce: another handler consuming the pointer, and the control being disabled or removed mid-press.
 * All of them end the gesture through the same `finally` in [axisTouch].
 */
@OptIn(ExperimentalTestApi::class)
class DriveControlsTest {

    /**
     * An ancestor that lets the first event through and consumes every later one in the Initial pass, before the
     * control sees it: the control's own drag then ends as cancelled, as it does when another handler (a parent
     * scroller, the system) takes the gesture away.
     */
    private fun Modifier.takeOverOnSecondEvent(): Modifier = pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.all { !it.pressed }) break
            }
        }
    }

    private fun hasState(expected: String) = SemanticsMatcher("state is '$expected'") {
        it.config.getOrNull(SemanticsProperties.StateDescription) == expected
    }

    // ---- steering ------------------------------------------------------------------------------

    @Test
    fun steeringReportsThePositionWhileDraggingAndReleasesOnFingerUp() = runComposeUiTest {
        val values = mutableListOf<Float>()
        var releases = 0
        setContent {
            AppTheme {
                SteeringSlider(
                    value = 0f,
                    onValue = { values += it },
                    onRelease = { releases++ },
                    modifier = Modifier.testTag("steering"),
                )
            }
        }

        // Injected events are delivered when each block ends, so the "finger is down" state is observed between blocks.
        onNodeWithTag("steering").performTouchInput { down(Offset(width * 0.95f, centerY)) }
        assertEquals(0, releases, "no release while the finger is down")
        onNodeWithTag("steering").performTouchInput { moveTo(Offset(width * 0.05f, centerY)) }
        assertEquals(0, releases, "no release while the finger is dragging")
        onNodeWithTag("steering").performTouchInput { up() }

        assertEquals(1f, values.first(), "touching the far right is full right")
        assertEquals(-1f, values.last(), "dragging to the far left is full left")
        assertEquals(1, releases, "finger up releases exactly once")
    }

    @Test
    fun steeringRespondsToAPlainPressWithoutADragSlop() = runComposeUiTest {
        val values = mutableListOf<Float>()
        setContent {
            AppTheme { SteeringSlider(value = 0f, onValue = { values += it }, onRelease = {}, modifier = Modifier.testTag("steering")) }
        }

        onNodeWithTag("steering").performTouchInput {
            down(Offset(width * 0.75f, centerY))
            up()
        }

        assertEquals(1, values.size)
        // Three quarters along a 64dp-high track is past half right, because the thumb centre cannot reach the
        // outer 45% of the height at either end (the usable track is shorter than the control).
        assertTrue(values.single() > 0.55f && values.single() < 0.7f, "a press at three quarters reads about 0.63: ${values.single()}")
    }

    @Test
    fun steeringReleasesWhenAnotherHandlerTakesOverTheGesture() = runComposeUiTest {
        // The nearest thing the desktop harness can inject to a cancelled gesture: an ancestor consumes the
        // pointer, which ends the control's drag as "cancelled" (the same path a system cancel takes).
        val values = mutableListOf<Float>()
        var releases = 0
        setContent {
            AppTheme {
                Box(modifier = Modifier.takeOverOnSecondEvent().testTag("host")) {
                    SteeringSlider(value = 0f, onValue = { values += it }, onRelease = { releases++ })
                }
            }
        }

        onNodeWithTag("host").performTouchInput { down(Offset(width * 0.5f, centerY)) }
        assertEquals(0, releases)
        onNodeWithTag("host").performTouchInput { moveTo(Offset(width * 0.6f, centerY)) }
        waitForIdle()

        assertEquals(1, releases, "a gesture taken over by another handler must release, not leave the last value standing")
        onNodeWithTag("host").performTouchInput { up() }
        waitForIdle()
        assertEquals(1, releases, "and finger-up afterwards must not release a second time")
    }

    @Test
    fun steeringReleasesWhenDisabledWhileAFingerIsDown() = runComposeUiTest {
        var enabled by mutableStateOf(true)
        var releases = 0
        setContent {
            AppTheme {
                SteeringSlider(
                    value = 0f,
                    onValue = {},
                    onRelease = { releases++ },
                    enabled = enabled,
                    modifier = Modifier.testTag("steering"),
                )
            }
        }

        onNodeWithTag("steering").performTouchInput { down(Offset(width * 0.8f, centerY)) }
        enabled = false
        waitForIdle()

        assertEquals(1, releases, "disabling the control mid-press ends the gesture and must release")
    }

    @Test
    fun disabledSteeringIgnoresTouch() = runComposeUiTest {
        val values = mutableListOf<Float>()
        var releases = 0
        setContent {
            AppTheme {
                SteeringSlider(
                    value = 0f,
                    onValue = { values += it },
                    onRelease = { releases++ },
                    enabled = false,
                    modifier = Modifier.testTag("steering"),
                )
            }
        }

        onNodeWithTag("steering").performTouchInput {
            down(Offset(width * 0.8f, centerY))
            up()
        }

        assertTrue(values.isEmpty())
        assertEquals(0, releases)
    }

    @Test
    fun steeringDescribesItsStateToAScreenReader() = runComposeUiTest {
        var steering by mutableStateOf(0f)
        setContent {
            AppTheme { SteeringSlider(value = steering, onValue = {}, onRelease = {}, modifier = Modifier.testTag("steering")) }
        }

        onNodeWithTag("steering").assert(hasState("Centered"))
        steering = -0.3f
        waitForIdle()
        onNodeWithTag("steering").assert(hasState("30 percent left"))
        steering = 0.85f
        waitForIdle()
        onNodeWithTag("steering").assert(hasState("85 percent right"))
    }

    // ---- throttle ------------------------------------------------------------------------------

    @Test
    fun throttleTopIsForwardBottomIsReverseAndReleaseIsReported() = runComposeUiTest {
        val values = mutableListOf<Float>()
        var releases = 0
        setContent {
            AppTheme {
                ThrottlePedal(
                    value = 0f,
                    onValue = { values += it },
                    onRelease = { releases++ },
                    modifier = Modifier.testTag("throttle"),
                )
            }
        }

        onNodeWithTag("throttle").performTouchInput {
            down(Offset(centerX, height * 0.02f))
            moveTo(Offset(centerX, height * 0.5f))
            moveTo(Offset(centerX, height * 0.98f))
            up()
        }

        assertEquals(1f, values.first(), "the top of the pedal is full forward")
        assertEquals(0f, values[1], "the middle is exactly neutral")
        assertEquals(-1f, values.last(), "the bottom is full reverse")
        assertEquals(1, releases)
    }

    @Test
    fun throttleReleasesWhenAnotherHandlerTakesOverTheGesture() = runComposeUiTest {
        var releases = 0
        setContent {
            AppTheme {
                Box(modifier = Modifier.takeOverOnSecondEvent().testTag("host")) {
                    ThrottlePedal(value = 0f, onValue = {}, onRelease = { releases++ })
                }
            }
        }

        onNodeWithTag("host").performTouchInput { down(Offset(centerX, height * 0.3f)) }
        onNodeWithTag("host").performTouchInput { moveTo(Offset(centerX, height * 0.2f)) }
        waitForIdle()

        assertEquals(1, releases)
    }

    @Test
    fun throttleReleasesWhenItLeavesCompositionWhileAFingerIsDown() = runComposeUiTest {
        var shown by mutableStateOf(true)
        var releases = 0
        setContent {
            AppTheme {
                if (shown) {
                    ThrottlePedal(value = 0f, onValue = {}, onRelease = { releases++ }, modifier = Modifier.testTag("throttle"))
                }
            }
        }

        onNodeWithTag("throttle").performTouchInput { down(Offset(centerX, height * 0.2f)) }
        shown = false
        waitForIdle()

        assertEquals(1, releases, "a screen change mid-press must not leave the throttle held")
    }

    @Test
    fun throttleDescribesForwardAndReverse() = runComposeUiTest {
        var throttle by mutableStateOf(0f)
        setContent {
            AppTheme { ThrottlePedal(value = throttle, onValue = {}, onRelease = {}, modifier = Modifier.testTag("throttle")) }
        }

        onNodeWithTag("throttle").assert(hasState("Neutral"))
        throttle = 0.5f
        waitForIdle()
        onNodeWithTag("throttle").assert(hasState("50 percent forward"))
        throttle = -0.2f
        waitForIdle()
        onNodeWithTag("throttle").assert(hasState("20 percent reverse"))
    }

    // ---- two hands -----------------------------------------------------------------------------

    @Test
    fun steeringAndThrottleWorkAtTheSameTimeAndReleaseIndependently() = runComposeUiTest {
        val steering = mutableListOf<Float>()
        val throttle = mutableListOf<Float>()
        var steeringReleases = 0
        var throttleReleases = 0
        setContent {
            AppTheme {
                Row {
                    SteeringSlider(value = 0f, onValue = { steering += it }, onRelease = { steeringReleases++ }, modifier = Modifier.testTag("steering"))
                    ThrottlePedal(value = 0f, onValue = { throttle += it }, onRelease = { throttleReleases++ }, modifier = Modifier.testTag("throttle"))
                }
            }
        }
        val steeringBounds = onNodeWithTag("steering").fetchSemanticsNode().boundsInRoot
        val throttleBounds = onNodeWithTag("throttle").fetchSemanticsNode().boundsInRoot

        // Two fingers down at once: pointer 0 on the steering slider, pointer 1 near the top of the pedal.
        onRoot().performTouchInput {
            down(0, Offset(steeringBounds.left + steeringBounds.width * 0.9f, steeringBounds.center.y))
            down(1, Offset(throttleBounds.center.x, throttleBounds.top + throttleBounds.height * 0.05f))
        }
        assertTrue(steering.isNotEmpty() && steering.last() > 0.8f, "steering follows the first finger: $steering")
        assertTrue(throttle.isNotEmpty() && throttle.last() > 0.8f, "throttle follows the second finger: $throttle")

        // Lifting the steering finger releases only the steering; the throttle is still held.
        onRoot().performTouchInput { up(0) }
        waitForIdle()
        assertEquals(1, steeringReleases)
        assertEquals(0, throttleReleases, "the other hand is still on the pedal")

        onRoot().performTouchInput { up(1) }
        waitForIdle()
        assertEquals(1, steeringReleases)
        assertEquals(1, throttleReleases)
    }

    // ---- brake light ---------------------------------------------------------------------------

    @Test
    fun brakeLightIsOnWhileHeldAndOffOnRelease() = runComposeUiTest {
        val changes = mutableListOf<Boolean>()
        setContent {
            AppTheme { BrakeLightButton(active = false, onActiveChange = { changes += it }, modifier = Modifier.testTag("brake")) }
        }

        onNodeWithTag("brake").performTouchInput { down(center) }
        assertEquals(listOf(true), changes, "lit as soon as the finger is down")
        onNodeWithTag("brake").performTouchInput { up() }

        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun brakeLightGoesOffWhenAnotherHandlerTakesOverTheGesture() = runComposeUiTest {
        val changes = mutableListOf<Boolean>()
        setContent {
            AppTheme {
                Box(modifier = Modifier.takeOverOnSecondEvent().testTag("host")) {
                    BrakeLightButton(active = false, onActiveChange = { changes += it })
                }
            }
        }

        onNodeWithTag("host").performTouchInput { down(center) }
        onNodeWithTag("host").performTouchInput { moveBy(Offset(2f, 2f)) }
        waitForIdle()

        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun brakeLightGoesOffWhenDisabledWhileHeld() = runComposeUiTest {
        var enabled by mutableStateOf(true)
        val changes = mutableListOf<Boolean>()
        setContent {
            AppTheme { BrakeLightButton(active = false, onActiveChange = { changes += it }, enabled = enabled, modifier = Modifier.testTag("brake")) }
        }

        onNodeWithTag("brake").performTouchInput { down(center) }
        enabled = false
        waitForIdle()

        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun brakeLightDescribesItsState() = runComposeUiTest {
        var lit by mutableStateOf(false)
        setContent {
            AppTheme { BrakeLightButton(active = lit, onActiveChange = {}, modifier = Modifier.testTag("brake")) }
        }

        onNodeWithTag("brake").assert(hasState("Off"))
        lit = true
        waitForIdle()
        onNodeWithTag("brake").assert(hasState("On"))
    }
}

package com.mklhc.feature.cockpit.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import co.touchlab.kermit.Logger

/** What a drive key does: one direction of one axis, or the brake light. */
internal enum class DriveKey { Forward, Reverse, Left, Right, Brake }

/** The drive key [key] stands for: W/A/S/D and the arrows for the axes, the space bar for the brake light. */
internal fun driveKeyOf(key: Key): DriveKey? = when (key) {
    Key.W, Key.DirectionUp -> DriveKey.Forward
    Key.S, Key.DirectionDown -> DriveKey.Reverse
    Key.A, Key.DirectionLeft -> DriveKey.Left
    Key.D, Key.DirectionRight -> DriveKey.Right
    Key.Spacebar -> DriveKey.Brake
    else -> null
}

/**
 * The held drive keys and the axis values they make.
 *
 * Business logic: a keyboard is digital, so a held key is the full value of its axis (the session still applies the
 * user's max-throttle and expo), opposite keys cancel to zero, and letting a key go returns its axis to zero:
 * RELEASE MEANS NEUTRAL, as with the touch controls. One rule makes the keyboard as safe as a finger: a key only
 * drives if it went down while the cockpit was driving and had the focus. The operating system auto-repeats a held
 * key as a stream of key-downs, and that stream must never start the kart by itself:
 * - A key pressed while not driving, or still down when the cockpit stopped driving ([suspendHeld]), is ignored
 *   until it comes up.
 * - When the focus goes elsewhere the key-ups go there too, so this side cannot know which keys are still down when
 *   the focus returns: [releaseOnFocusLoss] ignores EVERY key until it comes up. The cost is that the first press of
 *   each key after the focus returns does nothing; the next one drives.
 *
 * Not thread-safe; it lives on the UI thread with the key events.
 */
internal class KeyboardDriveState {

    private val held = mutableSetOf<DriveKey>()
    private val ignoredUntilUp = mutableSetOf<DriveKey>()

    /** -1 (full reverse) .. 1 (full forward). */
    val throttle: Float get() = axis(DriveKey.Forward, DriveKey.Reverse)

    /** -1 (full left) .. 1 (full right). */
    val steering: Float get() = axis(DriveKey.Right, DriveKey.Left)

    val brakeLight: Boolean get() = DriveKey.Brake in held

    /** Whether any key is driving right now. */
    val anyHeld: Boolean get() = held.isNotEmpty()

    /** [key] went down (or auto-repeated); it drives only if [enabled] and it was not waiting for its release. */
    fun keyDown(key: DriveKey, enabled: Boolean) {
        if (key in ignoredUntilUp) return
        if (enabled) held += key else ignoredUntilUp += key
    }

    /** [key] came up: it stops driving, and a key that was being ignored may drive again on its next press. */
    fun keyUp(key: DriveKey) {
        held -= key
        ignoredUntilUp -= key
    }

    /** The cockpit stopped driving: every held key stops driving and is ignored until it comes up. */
    fun suspendHeld() {
        ignoredUntilUp += held
        held.clear()
    }

    /** The focus went elsewhere and no key-up will arrive: nothing drives, and every key is ignored until it comes up. */
    fun releaseOnFocusLoss() {
        held.clear()
        ignoredUntilUp += DriveKey.entries
    }

    /** The cockpit is going away: nothing drives and nothing is remembered. */
    fun clear() {
        held.clear()
        ignoredUntilUp.clear()
    }

    private fun axis(positive: DriveKey, negative: DriveKey): Float =
        (if (positive in held) 1f else 0f) - (if (negative in held) 1f else 0f)
}

/**
 * Lets the driver drive with the keyboard while the modified element (the cockpit) has the focus: W/A/S/D or the
 * arrows for the throttle and the steering, the space bar for the brake light.
 *
 * Business logic: the element takes the focus when it appears (and again when the window gets the focus back), and
 * each key event is turned through [KeyboardDriveState] into the same callbacks the touch controls use: a changed
 * axis is reported with its new value, or as a release when it returns to zero, and the brake light as pressed or
 * released. Only changes are reported; the cockpit's ViewModel keeps a held command alive, so the auto-repeat of a
 * held key adds nothing. The drive keys are consumed so the arrows do not also move the focus; a key-down with Ctrl,
 * Alt or Meta held is left alone (it is a shortcut, not driving), while a key-up always counts.
 *
 * SAFETY: when [enabled] turns off (the kart stopped driving) the held keys are released and ignored until they
 * come up; when the element or the window loses the focus (another window, a dialog) every key is released and
 * ignored until it comes up, because its key-up will never arrive here; when the element leaves the composition
 * everything held is released. Each time the cockpit gets the releases and sends neutral. The keyboard and the touch
 * controls share the axes: whichever lets go first returns the axis to zero, and a key still held after a touch
 * release does nothing more until it is pressed again.
 *
 * @param enabled whether the kart is driving; presses are ignored otherwise.
 */
@Composable
internal fun Modifier.keyboardDrive(
    enabled: Boolean,
    onSteering: (Float) -> Unit,
    onThrottle: (Float) -> Unit,
    onSteeringRelease: () -> Unit,
    onThrottleRelease: () -> Unit,
    onBrake: (Boolean) -> Unit,
): Modifier {
    val keyboard = remember { KeyboardDriveReporter(KeyboardDriveState()) }
    val callbacks = DriveCallbacks(onSteering, onThrottle, onSteeringRelease, onThrottleRelease, onBrake)
    SideEffect { keyboard.callbacks = callbacks }
    val currentEnabled = rememberUpdatedState(enabled)
    val focusRequester = remember { FocusRequester() }

    DisposableEffect(enabled) {
        if (!enabled) keyboard.applyAndReport("not driving, releasing the held keys") { suspendHeld() }
        onDispose { }
    }
    DisposableEffect(keyboard) {
        onDispose { keyboard.applyAndReport("cockpit left, releasing the held keys") { clear() } }
    }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo, focusRequester) {
        // Only a loss of a focus the window had counts; regaining it takes the keyboard focus back.
        var wasFocused = windowInfo.isWindowFocused
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (focused && !wasFocused) focusRequester.requestFocus()
            if (!focused && wasFocused) {
                keyboard.applyAndReport("window lost the focus, releasing the held keys") { releaseOnFocusLoss() }
            }
            wasFocused = focused
        }
    }

    val keyModifier = remember(keyboard, focusRequester) {
        Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focus -> keyboard.onFocusChanged(focus.hasFocus) }
            .onPreviewKeyEvent { event -> keyboard.onKeyEvent(event.key, event.type, event.isShortcut(), currentEnabled) }
            .focusTarget()
    }
    return this.then(keyModifier)
}

private fun androidx.compose.ui.input.key.KeyEvent.isShortcut(): Boolean = isCtrlPressed || isAltPressed || isMetaPressed

/** The cockpit's control callbacks, as last composed. */
private class DriveCallbacks(
    val onSteering: (Float) -> Unit,
    val onThrottle: (Float) -> Unit,
    val onSteeringRelease: () -> Unit,
    val onThrottleRelease: () -> Unit,
    val onBrake: (Boolean) -> Unit,
)

/** The axis values at one moment, to tell what a key event changed. */
private data class DriveSnapshot(val throttle: Float, val steering: Float, val brakeLight: Boolean)

private fun KeyboardDriveState.snapshot() = DriveSnapshot(throttle, steering, brakeLight)

/** Applies key events and safety releases to a [KeyboardDriveState] and reports what changed through [callbacks]. */
private class KeyboardDriveReporter(private val state: KeyboardDriveState) {

    private val log = Logger.withTag(TAG)

    /** Set after every composition, so a report always reaches the newest lambdas. */
    var callbacks: DriveCallbacks? = null

    private var hadFocus = false

    /** The cockpit's focus changed; losing a focus it had releases the keys (the first report, unfocused, does not). */
    fun onFocusChanged(hasFocus: Boolean) {
        if (hadFocus && !hasFocus) applyAndReport("cockpit lost the focus, releasing the held keys") { releaseOnFocusLoss() }
        hadFocus = hasFocus
    }

    /** Handles one key event; true when it was a drive key and is consumed. */
    fun onKeyEvent(key: Key, type: KeyEventType, shortcut: Boolean, enabled: State<Boolean>): Boolean {
        val driveKey = driveKeyOf(key) ?: return false
        when (type) {
            KeyEventType.KeyDown -> {
                if (shortcut) return false
                applyAndReport(reason = null) { keyDown(driveKey, enabled.value) }
            }
            KeyEventType.KeyUp -> applyAndReport(reason = null) { keyUp(driveKey) }
        }
        return true
    }

    /** Applies [action] and reports the axes it changed; logs [reason] when it released keys that were driving. */
    fun applyAndReport(reason: String?, action: KeyboardDriveState.() -> Unit) {
        val wasHeld = state.anyHeld
        val before = state.snapshot()
        state.action()
        val after = state.snapshot()
        if (reason != null && wasHeld) log.i { "applyAndReport(): $reason -> before=$before" }
        val report = callbacks ?: return
        if (after.throttle != before.throttle) {
            if (after.throttle == 0f) report.onThrottleRelease() else report.onThrottle(after.throttle)
        }
        if (after.steering != before.steering) {
            if (after.steering == 0f) report.onSteeringRelease() else report.onSteering(after.steering)
        }
        if (after.brakeLight != before.brakeLight) report.onBrake(after.brakeLight)
    }

    private companion object {
        const val TAG = "KeyboardDrive"
    }
}

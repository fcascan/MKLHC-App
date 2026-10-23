package com.mklhc.feature.cockpit.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [KeyboardDriveState], the held-key bookkeeping behind driving with the keyboard: keys held while driving make full
 * axis values, opposite keys cancel, releasing a key returns its axis to zero, and a key that was down when the
 * cockpit stopped driving or lost the focus does nothing until it comes up and goes down again, so the kart never
 * starts moving on its own from a key's auto-repeat.
 */
class KeyboardDriveTest {

    private val drive = KeyboardDriveState()

    @Test
    fun startsNeutral() {
        assertNeutral()
    }

    @Test
    fun eachKeyDrivesItsAxisToTheFullValue() {
        drive.keyDown(DriveKey.Forward, enabled = true)
        assertEquals(1f, drive.throttle)
        drive.keyUp(DriveKey.Forward)

        drive.keyDown(DriveKey.Reverse, enabled = true)
        assertEquals(-1f, drive.throttle)
        drive.keyUp(DriveKey.Reverse)

        drive.keyDown(DriveKey.Left, enabled = true)
        assertEquals(-1f, drive.steering)
        drive.keyUp(DriveKey.Left)

        drive.keyDown(DriveKey.Right, enabled = true)
        assertEquals(1f, drive.steering)
        drive.keyUp(DriveKey.Right)

        drive.keyDown(DriveKey.Brake, enabled = true)
        assertTrue(drive.brakeLight)
        drive.keyUp(DriveKey.Brake)

        assertNeutral()
    }

    @Test
    fun throttleAndSteeringAreHeldTogether() {
        drive.keyDown(DriveKey.Forward, enabled = true)
        drive.keyDown(DriveKey.Left, enabled = true)

        assertEquals(1f, drive.throttle)
        assertEquals(-1f, drive.steering)

        drive.keyUp(DriveKey.Left)

        assertEquals(1f, drive.throttle, "releasing the steering key leaves the throttle alone")
        assertEquals(0f, drive.steering)
    }

    @Test
    fun oppositeKeysCancelAndReleasingOneLeavesTheOther() {
        drive.keyDown(DriveKey.Forward, enabled = true)
        drive.keyDown(DriveKey.Reverse, enabled = true)
        assertEquals(0f, drive.throttle)

        drive.keyUp(DriveKey.Forward)
        assertEquals(-1f, drive.throttle)

        drive.keyDown(DriveKey.Left, enabled = true)
        drive.keyDown(DriveKey.Right, enabled = true)
        assertEquals(0f, drive.steering)
    }

    @Test
    fun aRepeatedKeyDownChangesNothing() {
        drive.keyDown(DriveKey.Forward, enabled = true)
        drive.keyDown(DriveKey.Forward, enabled = true)
        drive.keyUp(DriveKey.Forward)

        assertEquals(0f, drive.throttle, "the auto-repeat of a key is not a second press")
    }

    @Test
    fun aKeyPressedWhileNotDrivingDoesNothingEvenOnceDriving() {
        drive.keyDown(DriveKey.Forward, enabled = false)
        assertNeutral()

        // Driving resumes while the key is still down: its auto-repeat must not start the kart.
        drive.keyDown(DriveKey.Forward, enabled = true)
        assertNeutral()

        // Up and down again: a deliberate press drives.
        drive.keyUp(DriveKey.Forward)
        drive.keyDown(DriveKey.Forward, enabled = true)
        assertEquals(1f, drive.throttle)
    }

    @Test
    fun suspendingReleasesEverythingAndIgnoresTheKeysStillDown() {
        drive.keyDown(DriveKey.Forward, enabled = true)
        drive.keyDown(DriveKey.Right, enabled = true)
        drive.keyDown(DriveKey.Brake, enabled = true)

        drive.suspendHeld()
        assertNeutral()

        drive.keyDown(DriveKey.Forward, enabled = true)
        drive.keyDown(DriveKey.Right, enabled = true)
        assertNeutral()

        drive.keyUp(DriveKey.Right)
        drive.keyDown(DriveKey.Right, enabled = true)
        assertEquals(1f, drive.steering, "pressed again after the suspension")
        assertEquals(0f, drive.throttle, "the forward key is still waiting for its release")
    }

    @Test
    fun afterAFocusLossEveryKeyNeedsAFreshPress() {
        drive.keyDown(DriveKey.Forward, enabled = true)

        // The focus went away: the key-ups go elsewhere, so which keys are still down is unknown.
        drive.releaseOnFocusLoss()
        assertNeutral()

        // Back in focus, a key-down may be the auto-repeat of a key held all along: none drives...
        drive.keyDown(DriveKey.Forward, enabled = true)
        drive.keyDown(DriveKey.Reverse, enabled = true)
        assertNeutral()

        // ...until it comes up and goes down again.
        drive.keyUp(DriveKey.Reverse)
        drive.keyDown(DriveKey.Reverse, enabled = true)
        assertEquals(-1f, drive.throttle)
    }

    @Test
    fun clearingReleasesEverythingAndForgetsTheIgnoredKeys() {
        drive.keyDown(DriveKey.Forward, enabled = true)
        drive.keyDown(DriveKey.Left, enabled = false)

        drive.clear()
        assertNeutral()
        assertFalse(drive.anyHeld)

        drive.keyDown(DriveKey.Left, enabled = true)
        assertEquals(-1f, drive.steering)
    }

    @Test
    fun anyHeldFollowsTheDrivingKeysOnly() {
        assertFalse(drive.anyHeld)
        drive.keyDown(DriveKey.Brake, enabled = false)
        assertFalse(drive.anyHeld, "an ignored key is not driving")

        drive.keyDown(DriveKey.Right, enabled = true)
        assertTrue(drive.anyHeld)
    }

    @Test
    fun aKeyUpForAKeyNeverSeenIsHarmless() {
        drive.keyUp(DriveKey.Left)

        assertNeutral()
    }

    private fun assertNeutral() {
        assertEquals(0f, drive.throttle)
        assertEquals(0f, drive.steering)
        assertFalse(drive.brakeLight)
    }
}

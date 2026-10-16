package com.mklhc.core.design_system.drive

import com.mklhc.core.kart_protocol.teleop.DriveCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic between a finger and the wire: pointer position to a normalised -1..1 axis, and that axis to
 * the signed octet [DriveCommand] carries. A wrong sign or an off-by-one here would be a driving bug, so both
 * directions and every edge are pinned.
 */
class DriveAxisTest {

    // ---- pointer position -> axis -------------------------------------------------------------

    @Test
    fun positionMapsEdgesAndCentreOfTheTrack() {
        assertEquals(-1f, axisFromPosition(position = 0f, length = 200f))
        assertEquals(0f, axisFromPosition(position = 100f, length = 200f))
        assertEquals(1f, axisFromPosition(position = 200f, length = 200f))
        assertEquals(-0.5f, axisFromPosition(position = 50f, length = 200f))
        assertEquals(0.5f, axisFromPosition(position = 150f, length = 200f))
    }

    @Test
    fun positionOutsideTheTrackIsClamped() {
        assertEquals(-1f, axisFromPosition(position = -40f, length = 200f))
        assertEquals(1f, axisFromPosition(position = 999f, length = 200f))
    }

    @Test
    fun invertedAxisPutsTheTopOfAVerticalTrackAtPlusOne() {
        assertEquals(1f, axisFromPosition(position = 0f, length = 200f, inverted = true))
        assertEquals(-1f, axisFromPosition(position = 200f, length = 200f, inverted = true))
        assertEquals(0.5f, axisFromPosition(position = 50f, length = 200f, inverted = true))
    }

    @Test
    fun smallDeflectionsInTheDeadZoneSnapToExactlyNeutral() {
        // The centre of an inverted axis must be +0, not -0, or boxed comparison downstream would differ.
        assertEquals(0f, axisFromPosition(position = 100f, length = 200f, inverted = true))
        assertEquals(0f, axisFromPosition(position = 101f, length = 200f))
        assertEquals(0f, axisFromPosition(position = 99f, length = 200f, deadZone = 0.05f))
        assertEquals(0f, axisFromPosition(position = 100f, length = 200f, inverted = true, deadZone = 0f))
        assertTrue(axisFromPosition(position = 130f, length = 200f) > 0f)
    }

    @Test
    fun insetShrinksTheUsableTrackSoTheThumbCentreAgreesWithTheFinger() {
        // A 200 px track with a 20 px inset at each end: the thumb centre travels 20..180.
        assertEquals(-1f, axisFromPosition(position = 20f, length = 200f, inset = 20f))
        assertEquals(1f, axisFromPosition(position = 180f, length = 200f, inset = 20f))
        assertEquals(0f, axisFromPosition(position = 100f, length = 200f, inset = 20f))
        assertEquals(0.5f, axisFromPosition(position = 140f, length = 200f, inset = 20f))
        // The inset bands at the very ends still read as full deflection.
        assertEquals(-1f, axisFromPosition(position = 0f, length = 200f, inset = 20f))
        assertEquals(1f, axisFromPosition(position = 200f, length = 200f, inset = 20f))
        assertEquals(1f, axisFromPosition(position = 20f, length = 200f, inverted = true, inset = 20f))
    }

    @Test
    fun degenerateTrackReadsAsNeutral() {
        assertEquals(0f, axisFromPosition(position = 10f, length = 30f, inset = 20f), "inset consumes the whole track")
        assertEquals(0f, axisFromPosition(position = 10f, length = 0f))
        assertEquals(0f, axisFromPosition(position = 10f, length = -5f))
        assertEquals(0f, axisFromPosition(position = Float.NaN, length = 200f))
    }

    // ---- axis -> signed octet -----------------------------------------------------------------

    @Test
    fun axisMapsToTheFullSignedOctetRange() {
        assertEquals(127, 1f.toAxisOctet())
        assertEquals(-128, (-1f).toAxisOctet())
        assertEquals(0, 0f.toAxisOctet())
    }

    @Test
    fun axisMapsProportionallyEitherSideOfNeutral() {
        assertEquals(64, 0.5f.toAxisOctet())
        assertEquals(-64, (-0.5f).toAxisOctet())
        assertEquals(1, 0.01f.toAxisOctet())
        assertEquals(-1, (-0.01f).toAxisOctet())
    }

    @Test
    fun axisOutsideTheRangeOrNotANumberStaysRepresentable() {
        assertEquals(127, 5f.toAxisOctet())
        assertEquals(-128, (-5f).toAxisOctet())
        assertEquals(0, Float.NaN.toAxisOctet())
        assertEquals(127, Float.POSITIVE_INFINITY.toAxisOctet())
    }

    @Test
    fun driveCommandCarriesThrottleSteeringAndBrake() {
        assertEquals(
            DriveCommand(throttle = 64, steering = -128, brakeLight = true),
            driveCommandOf(throttle = 0.5f, steering = -1f, brakeLight = true),
        )
    }

    @Test
    fun releasedControlsProduceTheNeutralCommand() {
        assertEquals(DriveCommand.NEUTRAL, driveCommandOf(throttle = 0f, steering = 0f, brakeLight = false))
    }

    @Test
    fun driveCommandNeverThrowsForOutOfRangeInput() {
        // DriveCommand rejects an unrepresentable octet at construction; the mapper must clamp first.
        val command = driveCommandOf(throttle = 7f, steering = -7f, brakeLight = false)
        assertEquals(127, command.throttle)
        assertEquals(-128, command.steering)
    }
}

package com.mklhc.core.video

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [H264FrameDecoder] with the real FFmpeg decoder over synthetic streams from [SyntheticH264]: the picture comes out
 * of the same call that fed its access unit, at the stream's size and in the colour the I_PCM samples encode; P
 * pictures wait for an IDR; data that is not H.264 never throws.
 */
class H264FrameDecoderTest {

    private val stream = SyntheticH264(widthInMbs = 2, heightInMbs = 2)
    private val decoder = H264FrameDecoder.open()

    @AfterTest
    fun tearDown() {
        decoder.close()
    }

    @Test
    fun anIdrComesOutAsAPictureOfItsSizeAndColourInTheSameCall() {
        val picture = assertNotNull(decoder.decode(stream.idr(RED_Y, RED_CB, RED_CR)), "no delay: the IDR's own call")

        assertEquals(32, picture.width)
        assertEquals(32, picture.height)
        assertEquals(32 * 32 * 4, picture.bgra.size, "tightly packed BGRA")
        assertColour(picture, red = true)
    }

    @Test
    fun aDifferentColourDecodesToThatColour() {
        val picture = assertNotNull(decoder.decode(stream.idr(BLUE_Y, BLUE_CB, BLUE_CR)))

        assertColour(picture, red = false)
    }

    @Test
    fun aSkippedPPictureAfterTheIdrRepeatsThePicture() {
        decoder.decode(stream.idr(RED_Y, RED_CB, RED_CR))

        val picture = assertNotNull(decoder.decode(stream.skippedP(frameNum = 1)))

        assertColour(picture, red = true)
    }

    @Test
    fun aPPictureBeforeAnyIdrIsNotDecoded() {
        assertNull(decoder.decode(stream.skippedP(frameNum = 1)), "the stream joins mid-flight: wait for an IDR")

        assertNotNull(decoder.decode(stream.idr(RED_Y, RED_CB, RED_CR)), "the IDR then starts it")
    }

    @Test
    fun dataThatIsNotH264YieldsNoPictureAndNeverThrows() {
        assertNull(decoder.decode(ByteArray(100) { 0x55 }))
        assertNull(decoder.decode(ByteArray(0)))

        decoder.decode(stream.idr(RED_Y, RED_CB, RED_CR))
        decoder.decode(byteArrayOf(0, 0, 0, 1, 0x41, 0x7f, 0x00, 0x13)) // a broken P slice: concealed or refused
        val next = assertNotNull(decoder.decode(stream.idr(BLUE_Y, BLUE_CB, BLUE_CR, idrPicId = 1)), "the next IDR")
        assertColour(next, red = false)
        assertFalse(decoder.isUnusable)
    }

    @Test
    fun thePictureSizeFollowsTheStreamAtAnIdr() {
        decoder.decode(stream.idr(RED_Y, RED_CB, RED_CR))

        val wide = SyntheticH264(widthInMbs = 3, heightInMbs = 1)
        val picture = assertNotNull(decoder.decode(wide.idr(BLUE_Y, BLUE_CB, BLUE_CR, idrPicId = 1)))

        assertEquals(48, picture.width)
        assertEquals(16, picture.height)
        assertColour(picture, red = false)
    }

    @Test
    fun anEmptyAccessUnitIsIgnoredAndDoesNotBreakTheStream() {
        decoder.decode(stream.idr(RED_Y, RED_CB, RED_CR))

        assertNull(decoder.decode(ByteArray(0)))

        assertColour(assertNotNull(decoder.decode(stream.skippedP(frameNum = 1)), "still in step"), red = true)
    }

    @Test
    fun closeIsIdempotentAndAClosedDecoderDecodesNothing() {
        decoder.close()
        decoder.close()

        assertNull(decoder.decode(stream.idr(RED_Y, RED_CB, RED_CR)))
    }

    /** The centre pixel is clearly red, or clearly blue, in BGRA order. */
    private fun assertColour(picture: VideoPicture, red: Boolean) {
        val centre = ((picture.height / 2) * picture.width + picture.width / 2) * 4
        val b = picture.bgra[centre].toInt() and 0xFF
        val g = picture.bgra[centre + 1].toInt() and 0xFF
        val r = picture.bgra[centre + 2].toInt() and 0xFF
        val a = picture.bgra[centre + 3].toInt() and 0xFF
        val rgb = "r=$r g=$g b=$b"
        if (red) assertTrue(r > 200 && g < 60 && b < 60, "expected red, got $rgb") else assertTrue(b > 200 && r < 60 && g < 60, "expected blue, got $rgb")
        assertEquals(255, a, "opaque")
    }

    private companion object {
        // BT.601 limited range: pure red is about Y 81, Cb 90, Cr 240; pure blue about Y 41, Cb 240, Cr 110.
        const val RED_Y = 81
        const val RED_CB = 90
        const val RED_CR = 240
        const val BLUE_Y = 41
        const val BLUE_CB = 240
        const val BLUE_CR = 110
    }
}

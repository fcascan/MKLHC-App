package com.mklhc.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Basics of [KartInfo], the details shown once the kart is identified. */
class KartInfoTest {

    private val info = KartInfo(
        serial = "ABC123",
        character = 2,
        firmwareSystem = 1 to 7,
        firmwareBoot = 3 to 4,
        sha1 = "0123456789abcdef0123456789abcdef01234567",
    )

    @Test
    fun fieldsAreKeptAsGiven() {
        assertEquals("ABC123", info.serial)
        assertEquals(2, info.character)
        assertEquals(1 to 7, info.firmwareSystem)
        assertEquals(3 to 4, info.firmwareBoot)
        assertEquals("0123456789abcdef0123456789abcdef01234567", info.sha1)
    }

    @Test
    fun systemAndBootVersionsAreDistinctFields() {
        val swapped = info.copy(firmwareSystem = info.firmwareBoot, firmwareBoot = info.firmwareSystem)
        assertNotEquals(info, swapped)
    }

    @Test
    fun isComparedByValue() {
        assertEquals(info, info.copy())
        assertEquals(info.hashCode(), info.copy().hashCode())
        assertNotEquals(info, info.copy(serial = "XYZ"))
    }
}

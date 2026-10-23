package com.mklhc.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** [UsbAdapterCatalog]: the known adapters, their support level and the order the automatic mode prefers. */
class UsbAdapterCatalogTest {

    @Test
    fun entriesAreInAutomaticModePreferenceOrder() {
        assertEquals(
            listOf("2357:012d", "0bda:c812", "0bda:c811", "0bda:8812"),
            UsbAdapterCatalog.entries.map { it.id.toString() },
        )
    }

    @Test
    fun supportLevelsMatchTheSupportedAdaptersTable() {
        assertEquals(
            listOf(
                AdapterSupport.COMPATIBLE,
                AdapterSupport.COMPATIBLE,
                AdapterSupport.NOT_RECOMMENDED,
                AdapterSupport.NOT_RECOMMENDED,
            ),
            UsbAdapterCatalog.entries.map { it.support },
        )
    }

    @Test
    fun chipsAreRecorded() {
        assertEquals(
            listOf("RTL8822BU", "RTL8812CU", "RTL8811CU", "RTL8812AU"),
            UsbAdapterCatalog.entries.map { it.chip },
        )
    }

    @Test
    fun theTestedAdapterIsNamedAndFound() {
        val t3u = assertNotNull(UsbAdapterCatalog.find(UsbId(0x2357, 0x012D)))
        assertEquals("TP-Link Archer T3U", t3u.name)
        assertEquals(AdapterSupport.COMPATIBLE, t3u.support)
    }

    @Test
    fun everyEntryIsFoundByItsOwnId() {
        UsbAdapterCatalog.entries.forEach { assertEquals(it, UsbAdapterCatalog.find(it.id)) }
    }

    @Test
    fun anUnknownIdIsNotFound() {
        assertNull(UsbAdapterCatalog.find(UsbId(0x1234, 0x5678)))
        assertNull(UsbAdapterCatalog.find(UsbId(0x0BDA, 0xC813)))
    }

    @Test
    fun idsAreUniqueAndNamesAreNotBlank() {
        assertEquals(UsbAdapterCatalog.entries.size, UsbAdapterCatalog.entries.map { it.id }.toSet().size)
        UsbAdapterCatalog.entries.forEach { assertEquals(false, it.name.isBlank()) }
    }
}

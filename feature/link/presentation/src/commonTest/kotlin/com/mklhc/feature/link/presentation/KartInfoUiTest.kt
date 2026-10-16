package com.mklhc.feature.link.presentation

import com.mklhc.core.domain.KartInfo
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mappers from the domain models to the link screen's UI models: the kart information rows and the battery.
 * The character id must come out as a neutral numbered label (never a character name), and the firmware
 * versions as one "sys a.b / boot c.d" line.
 */
class KartInfoUiTest {

    private fun info(character: Int = 1, system: Pair<Int, Int> = 1 to 4, boot: Pair<Int, Int> = 0 to 9) = KartInfo(
        serial = "SN-0012345",
        character = character,
        firmwareSystem = system,
        firmwareBoot = boot,
        sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709",
    )

    @Test
    fun mapsSerialCharacterAndFirmware() {
        assertEquals(
            KartInfoUi(serial = "SN-0012345", characterLabel = "Kart 1", firmware = "sys 1.4 / boot 0.9"),
            info().toKartInfoUi(),
        )
    }

    @Test
    fun characterLabelIsANeutralNumberedLabel() {
        assertEquals("Kart 1", info(character = 1).toKartInfoUi().characterLabel)
        assertEquals("Kart 2", info(character = 2).toKartInfoUi().characterLabel)
        assertEquals("Kart 7", info(character = 7).toKartInfoUi().characterLabel)
    }

    @Test
    fun firmwareIsFormattedAsSystemAndBootVersions() {
        val firmware = info(system = 12 to 0, boot = 3 to 21).toKartInfoUi().firmware
        assertEquals("sys 12.0 / boot 3.21", firmware)
    }

    @Test
    fun batteryMapsBarsAndTheChargingCable() {
        assertEquals(
            BatteryUi(bars = 3, charging = false),
            Telemetry.BatteryStatus(cableFlags = 0, batteryBars = 3).toBatteryUi(),
        )
        assertEquals(
            BatteryUi(bars = 2, charging = true),
            Telemetry.BatteryStatus(cableFlags = 1, batteryBars = 2).toBatteryUi(),
        )
    }

    @Test
    fun batteryChargingFollowsOnlyTheCableBit() {
        // Bits 1 and 2 of the cable octet are of unknown meaning and must not read as "charging".
        assertEquals(false, Telemetry.BatteryStatus(cableFlags = 0b110, batteryBars = 4).toBatteryUi().charging)
        assertEquals(true, Telemetry.BatteryStatus(cableFlags = 0b111, batteryBars = 4).toBatteryUi().charging)
    }
}

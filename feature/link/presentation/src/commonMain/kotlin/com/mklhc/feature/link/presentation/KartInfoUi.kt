package com.mklhc.feature.link.presentation

import com.mklhc.core.domain.KartInfo
import com.mklhc.core.kart_protocol.telemetry.Telemetry

/**
 * The kart information as the link screen shows it: three ready-to-render strings.
 *
 * Business logic: a UI model, formatted once in the presentation layer so the composables only place text. The
 * character is shown as a neutral numbered label (the app names no characters) and the two firmware versions as
 * a single line.
 *
 * @property serial the kart's serial number text.
 * @property characterLabel a neutral label for the kart's character id, for example "Kart 1".
 * @property firmware the system and boot-loader versions on one line, for example "sys 1.4 / boot 0.9".
 */
data class KartInfoUi(
    val serial: String,
    val characterLabel: String,
    val firmware: String,
)

/**
 * The kart battery as the link screen's gauge needs it.
 *
 * @property bars the battery level in bars as the kart reported it (the gauge clamps it to 0..4).
 * @property charging whether the charging cable is connected.
 */
data class BatteryUi(
    val bars: Int,
    val charging: Boolean,
)

/**
 * Maps the domain kart information to its UI model.
 *
 * Business logic: the character id becomes "Kart N" (never a character name) and the firmware pairs become
 * "sys a.b / boot c.d". The firmware SHA-1 is not shown on this screen.
 */
fun KartInfo.toKartInfoUi(): KartInfoUi = KartInfoUi(
    serial = serial,
    characterLabel = "Kart $character",
    firmware = "sys ${firmwareSystem.first}.${firmwareSystem.second} / boot ${firmwareBoot.first}.${firmwareBoot.second}",
)

/**
 * Maps the kart's battery telemetry to its UI model.
 *
 * Business logic: only bit 0 of the cable octet is understood (the charging cable); the other bits are of
 * unknown meaning and never read as charging.
 */
fun Telemetry.BatteryStatus.toBatteryUi(): BatteryUi = BatteryUi(
    bars = batteryBars,
    charging = cableConnected,
)

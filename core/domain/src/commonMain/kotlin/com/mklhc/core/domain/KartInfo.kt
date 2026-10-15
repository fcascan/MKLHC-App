package com.mklhc.core.domain

/**
 * What the app knows about the kart once it has identified it, shown in the kart-information screen.
 *
 * Business logic: assembled by the orchestrator from the kart's product code (serial and character
 * id) and its system-information answer (boot loader and system firmware versions plus the firmware
 * SHA-1). Nothing in the session depends on these values; they are display and diagnostics data only.
 * The domain model is deliberately independent of the wire types the data is decoded from.
 *
 * @property serial the kart's serial number text.
 * @property character the character id the kart reports (1 and 2 are the known ones).
 * @property firmwareSystem (major, minor) of the system firmware.
 * @property firmwareBoot (major, minor) of the boot loader.
 * @property sha1 the firmware SHA-1 text as the kart reports it.
 */
data class KartInfo(
    val serial: String,
    val character: Int,
    val firmwareSystem: Pair<Int, Int>,
    val firmwareBoot: Pair<Int, Int>,
    val sha1: String,
)

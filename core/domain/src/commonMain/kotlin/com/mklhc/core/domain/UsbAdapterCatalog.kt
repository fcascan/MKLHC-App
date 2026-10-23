package com.mklhc.core.domain

/**
 * How well the app's userspace driver is known to work with an adapter.
 *
 * Business logic: [COMPATIBLE] adapters are the ones the app picks by itself. [NOT_RECOMMENDED] adapters are
 * recognised and may be chosen by hand, but the automatic mode never picks them because the driver is known
 * to be weaker on them (for example, frames to the kart can be lost for want of hardware retries).
 */
enum class AdapterSupport {
    /** Validated with the kart; the automatic mode may pick it. */
    COMPATIBLE,

    /** Recognised and allowed by hand, never picked automatically. */
    NOT_RECOMMENDED,
}

/**
 * One adapter the app knows about.
 *
 * Business logic: only the product name and the chip are data here; the text that explains a support level
 * belongs to the screen that shows it.
 *
 * @property id the adapter's USB id.
 * @property name the product name, for display.
 * @property chip the Wi-Fi chip, for display.
 * @property support how well the driver works with it.
 */
data class UsbAdapterInfo(
    val id: UsbId,
    val name: String,
    val chip: String,
    val support: AdapterSupport,
)

/**
 * The adapters the app knows, in the order the automatic mode prefers them.
 *
 * Business logic: mirrors the supported-adapters table of the hardware guide; a device whose id is not here is
 * unknown to the app (listed, but never picked and not selectable). The order is the automatic-mode preference:
 * the adapter the driver was bench-tested on comes first, then the reference adapter, then the not recommended
 * ones (which the automatic mode skips anyway).
 */
object UsbAdapterCatalog {

    /** Every known adapter, in automatic-mode preference order. */
    val entries: List<UsbAdapterInfo> = listOf(
        UsbAdapterInfo(UsbId(0x2357, 0x012D), "TP-Link Archer T3U", "RTL8822BU", AdapterSupport.COMPATIBLE),
        UsbAdapterInfo(UsbId(0x0BDA, 0xC812), "LB-LINK WDN1300H", "RTL8812CU", AdapterSupport.COMPATIBLE),
        UsbAdapterInfo(UsbId(0x0BDA, 0xC811), "COMFAST CF-811AC", "RTL8811CU", AdapterSupport.NOT_RECOMMENDED),
        UsbAdapterInfo(UsbId(0x0BDA, 0x8812), "ALFA AWUS036ACH", "RTL8812AU", AdapterSupport.NOT_RECOMMENDED),
    )

    /** The catalog entry for [id], or null when the app does not know that device. */
    fun find(id: UsbId): UsbAdapterInfo? = entries.firstOrNull { it.id == id }
}

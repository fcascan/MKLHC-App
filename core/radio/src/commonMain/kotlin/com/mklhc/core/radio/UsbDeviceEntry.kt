package com.mklhc.core.radio

/**
 * Whether radiod could open a USB device it listed, found by a trial open and close (no interface claim).
 *
 * Business logic: tells the app why an adapter that is plugged in cannot be used. radiod only trial-opens devices
 * that have a vendor-specific interface (class 0xFF), as every Wi-Fi adapter the driver knows does; any other device
 * is listed as [NOT_PROBED] and never opened. [code] is the wire value of
 * DEVICE_LIST's `access` byte. Decoding is lenient: a code this app does not know (sent by a newer radiod) becomes
 * [UNKNOWN] instead of rejecting the whole list, the same way an unknown LOG level is treated as an error rather
 * than rejected. radiod never sends [UNKNOWN]'s code.
 */
enum class UsbAccess(val code: Int) {
    /** libusb opened the device: it is bound to a libusb-capable driver (WinUSB on Windows) and free. */
    OPENABLE(0),

    /** `LIBUSB_ERROR_NOT_SUPPORTED`: on Windows the device is not bound to WinUSB. */
    DRIVER_UNSUPPORTED(1),

    /** `LIBUSB_ERROR_ACCESS` outside Windows: no permission (on Linux, a missing udev rule). */
    ACCESS_DENIED(2),

    /**
     * In use by another program: on Windows, `LIBUSB_ERROR_ACCESS` (WinUSB grants one handle at a time, so this
     * includes radiod's own radio); elsewhere `LIBUSB_ERROR_BUSY`, which libusb_open rarely returns.
     */
    BUSY(3),

    /** Any other libusb failure. */
    ERROR(4),

    /** Not opened: it does not look like a Wi-Fi adapter (no vendor-specific interface). */
    NOT_PROBED(5),

    /** A code this app does not know, from a newer radiod. */
    UNKNOWN(0xFF),
    ;

    companion object {
        /** Returns the access with wire value [code], or [UNKNOWN] when the code is not defined. */
        fun fromCode(code: Int): UsbAccess = entries.firstOrNull { it.code == code && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * One USB device in radiod's DEVICE_LIST: its ids, where it sits on the bus, its device class and whether radiod can
 * open it.
 *
 * Business logic: the desktop app learns which USB devices are present from radiod, which sees them through libusb.
 * Hubs are left out by radiod. Fields are range checked like [RadioMessage.OpenDevice]'s, so an out-of-range value
 * fails fast instead of being truncated on the wire: [vid] and [pid] are u16, [bus], [address] and [deviceClass]
 * (`bDeviceClass`; 0 means "defined per interface") are u8.
 */
data class UsbDeviceEntry(
    val vid: Int,
    val pid: Int,
    val bus: Int,
    val address: Int,
    val deviceClass: Int,
    val access: UsbAccess,
) {
    init {
        requireU16("vid", vid)
        requireU16("pid", pid)
        requireU8("bus", bus)
        requireU8("address", address)
        requireU8("deviceClass", deviceClass)
    }

    companion object {
        /** Size in bytes of one device on the wire: u16 vid, u16 pid, u8 bus, u8 address, u8 deviceClass, u8 access. */
        const val WIRE_SIZE = 8
    }
}

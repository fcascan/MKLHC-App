package com.mklhc.core.domain

/**
 * Whether the app can open a detected USB device.
 *
 * Business logic: the domain mirror of what the platform scan reports for each device (a trial open, with
 * no claim), so the app can tell "the adapter is not plugged in" from "it is plugged in but Windows has not
 * bound the userspace driver to it". Only [OPENABLE] devices can be used.
 */
enum class UsbDeviceAccess {
    /** The device opened; the app can use it. */
    OPENABLE,

    /** The operating system's driver is not the userspace one (on Windows, not bound to WinUSB). */
    DRIVER_UNSUPPORTED,

    /** No permission to open it (on Linux, a missing udev rule). Not reported on Windows, where it means [BUSY]. */
    ACCESS_DENIED,

    /**
     * In use by another program, including the app's own radio (on Windows, where WinUSB grants one handle at a
     * time, libusb reports this as an access error).
     */
    BUSY,

    /** Any other failure to open it. */
    ERROR,

    /** Not opened: it does not look like a Wi-Fi adapter (no vendor-specific interface), so it was never probed. */
    NOT_PROBED,

    /** The scan did not say. */
    UNKNOWN,
}

/**
 * A USB device the platform scan found.
 *
 * @property id the device's USB id.
 * @property bus the USB bus number.
 * @property address the device address on that bus.
 * @property access whether the app can open it.
 */
data class DetectedUsbDevice(
    val id: UsbId,
    val bus: Int,
    val address: Int,
    val access: UsbDeviceAccess,
)

/** Why no adapter can be used. */
enum class UnavailableReason {
    /** No scan has answered yet. */
    NOT_SCANNED_YET,

    /** Automatic mode: no compatible adapter is plugged in. */
    NO_COMPATIBLE_DEVICE,

    /** Manual mode: the chosen adapter is not plugged in. */
    SELECTED_DEVICE_ABSENT,

    /** Manual mode: the chosen id is not an adapter the app knows. */
    SELECTED_DEVICE_NOT_SUPPORTED,

    /** The adapter is plugged in but cannot be opened (see its [UsbDeviceAccess]). */
    NOT_OPENABLE,
}

/** The outcome of [resolveAdapter]. */
sealed interface AdapterResolution {

    /** The app will use [device]. */
    data class Use(val device: DetectedUsbDevice) : AdapterResolution

    /**
     * No adapter can be used, for [reason]; [device] is the adapter involved when there is one (for
     * [UnavailableReason.NOT_OPENABLE], the one that could not be opened).
     */
    data class Unavailable(
        val reason: UnavailableReason,
        val device: DetectedUsbDevice? = null,
    ) : AdapterResolution
}

/**
 * Decides which detected device the app will use for [selection].
 *
 * Business logic: pure, so the same rules serve every platform and the screen can show the answer.
 * - Not scanned yet ([devices] is null): [UnavailableReason.NOT_SCANNED_YET].
 * - [AdapterSelection.Automatic]: the first [AdapterSupport.COMPATIBLE] device in catalog order that is
 *   [UsbDeviceAccess.OPENABLE]. If compatible devices exist but none opens, [UnavailableReason.NOT_OPENABLE]
 *   with the first of them in catalog order; with none at all, [UnavailableReason.NO_COMPATIBLE_DEVICE].
 *   Not recommended and unknown devices are never picked automatically.
 * - [AdapterSelection.Manual]: an id outside the catalog is [UnavailableReason.SELECTED_DEVICE_NOT_SUPPORTED];
 *   an absent one is [UnavailableReason.SELECTED_DEVICE_ABSENT]; a present one that does not open is
 *   [UnavailableReason.NOT_OPENABLE] with that device; an openable one is used, even when not recommended.
 *   Another adapter is never substituted.
 * - Two identical adapters (same id): the first openable one wins, else the first detected.
 *
 * @param selection the user's choice.
 * @param devices the devices of the latest scan, or null if none has completed.
 */
fun resolveAdapter(selection: AdapterSelection, devices: List<DetectedUsbDevice>?): AdapterResolution {
    if (devices == null) return AdapterResolution.Unavailable(UnavailableReason.NOT_SCANNED_YET)
    return when (selection) {
        AdapterSelection.Automatic -> resolveAutomatic(devices)
        is AdapterSelection.Manual -> resolveManual(selection.id, devices)
    }
}

private fun resolveAutomatic(devices: List<DetectedUsbDevice>): AdapterResolution {
    var firstBlocked: DetectedUsbDevice? = null
    for (entry in UsbAdapterCatalog.entries) {
        if (entry.support != AdapterSupport.COMPATIBLE) continue
        val matches = devices.filter { it.id == entry.id }
        matches.firstOrNull { it.access == UsbDeviceAccess.OPENABLE }?.let { return AdapterResolution.Use(it) }
        if (firstBlocked == null) firstBlocked = matches.firstOrNull()
    }
    return if (firstBlocked != null) {
        AdapterResolution.Unavailable(UnavailableReason.NOT_OPENABLE, firstBlocked)
    } else {
        AdapterResolution.Unavailable(UnavailableReason.NO_COMPATIBLE_DEVICE)
    }
}

private fun resolveManual(id: UsbId, devices: List<DetectedUsbDevice>): AdapterResolution {
    if (UsbAdapterCatalog.find(id) == null) {
        return AdapterResolution.Unavailable(UnavailableReason.SELECTED_DEVICE_NOT_SUPPORTED)
    }
    val matches = devices.filter { it.id == id }
    val openable = matches.firstOrNull { it.access == UsbDeviceAccess.OPENABLE }
    if (openable != null) return AdapterResolution.Use(openable)
    val blocked = matches.firstOrNull() ?: return AdapterResolution.Unavailable(UnavailableReason.SELECTED_DEVICE_ABSENT)
    return AdapterResolution.Unavailable(UnavailableReason.NOT_OPENABLE, blocked)
}

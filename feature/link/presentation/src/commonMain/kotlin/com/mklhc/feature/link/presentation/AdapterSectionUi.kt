package com.mklhc.feature.link.presentation

import androidx.compose.runtime.Immutable
import com.mklhc.core.data.radio.AdapterOverview
import com.mklhc.core.domain.AdapterResolution
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.AdapterSupport
import com.mklhc.core.domain.DetectedUsbDevice
import com.mklhc.core.domain.UnavailableReason
import com.mklhc.core.domain.UsbAdapterCatalog
import com.mklhc.core.domain.UsbDeviceAccess
import com.mklhc.core.domain.UsbId
import com.mklhc.core.presentation.UiText
import com.mklhc.feature.link.presentation.generated.resources.Res
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_absent
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_helper_down
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_cannot_open_busy
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_cannot_open_driver
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_cannot_open_error
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_cannot_open_permission
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_no_compatible
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_no_devices
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_scanning
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_unsupported
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_status_using
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_unknown_device

/** How the radio adapter is chosen, as the mode rows of the screen show it. */
enum class AdapterMode {
    /** The app picks the first compatible adapter it can open. */
    AUTOMATIC,

    /** The app uses the adapter the user picked from the list. */
    MANUAL,
}

/**
 * One small status capsule on a device row. The screen gives each its text and tone (compatible = success, not
 * recommended / driver / permission / cannot be opened = warning, in use = info, will be used = success).
 */
enum class AdapterPill {
    COMPATIBLE,
    NOT_RECOMMENDED,
    IN_USE,
    NEEDS_DRIVER,
    NO_PERMISSION,
    CANNOT_BE_OPENED,
    WILL_BE_USED,
}

/**
 * One detected USB device as the "Radio adapter" section shows it.
 *
 * @property id the device's USB id (what picking the row saves).
 * @property bus the USB bus number; with [address] it tells two adapters of the same id apart (the row's key).
 * @property address the device address on that bus.
 * @property name the catalog product name, or "Unknown USB device".
 * @property idText the `vvvv:pppp` text of [id].
 * @property chip the Wi-Fi chip when the device is in the catalog, else null.
 * @property pills the capsules to show, in order: support (known devices), then in use or the access problem, then
 *   will be used.
 * @property selectable whether the row is a radio button: manual mode and a device the app knows.
 * @property selected whether this is the manually chosen adapter.
 */
@Immutable
data class AdapterDeviceUi(
    val id: UsbId,
    val bus: Int,
    val address: Int,
    val name: UiText,
    val idText: String,
    val chip: String?,
    val pills: List<AdapterPill>,
    val selectable: Boolean,
    val selected: Boolean,
)

/**
 * Everything the "Radio adapter" section renders.
 *
 * @property mode the current selection mode.
 * @property devices the detected devices, catalog devices first (in catalog order), then unknown ones by id.
 * @property status the line under the list: what is being looked for, or which adapter will be used or why none.
 * @property manualAvailable whether the Manual row can be chosen: the list holds a device the app knows, or the mode
 *   is already manual (so the current state is never a dead end).
 */
@Immutable
data class AdapterSectionUi(
    val mode: AdapterMode,
    val devices: List<AdapterDeviceUi>,
    val status: UiText,
    val manualAvailable: Boolean,
) {
    companion object {
        /** The section before the first scan has answered. */
        val INITIAL: AdapterSectionUi = AdapterOverview.INITIAL.toAdapterSectionUi()
    }
}

/**
 * Maps what the adapter repository knows to the section's UI model.
 *
 * Business logic: a pure function, so the screen and its tests share one mapping.
 * - Rows: every detected device, catalog devices first in catalog order, then unknown devices by id (bus and address
 *   break ties). Each row's pills are the support level (known devices only), then "in use" when the device is the
 *   adapter the radio has open (this wins over any access problem, because the scan reports an open adapter as busy)
 *   or else its access problem, then "will be used" for the device the resolution picked (only while the radio is down).
 *   Whether the controls can be used at all depends on the link state, which the screen state decides.
 * - Status line: "the radio helper is not running" while the helper that scans is not connected; then the adapter in
 *   use when there is one; "looking" until the first scan answers; "no USB devices found" for an empty scan; then the
 *   resolution: the device that will be used, or why none can be.
 */
fun AdapterOverview.toAdapterSectionUi(): AdapterSectionUi {
    val mode = if (selection is AdapterSelection.Manual) AdapterMode.MANUAL else AdapterMode.AUTOMATIC
    // While the radio is up, the adapter in use is the one in use; no other device is "about to be used".
    val used = if (inUse == null) (resolution as? AdapterResolution.Use)?.device else null
    val sorted = devices.orEmpty().sortedWith(DisplayOrder)
    return AdapterSectionUi(
        mode = mode,
        devices = sorted.map { device -> device.toRow(this, mode, used) },
        status = statusText(),
        manualAvailable = mode == AdapterMode.MANUAL || sorted.any { UsbAdapterCatalog.find(it.id) != null },
    )
}

/**
 * The id of the first catalog device of the list, in display order, or null when it holds none (or is unscanned).
 *
 * Business logic: what "Manual" starts from when there is neither an adapter in use nor one the resolution picked.
 */
internal fun AdapterOverview.firstCatalogDeviceId(): UsbId? =
    devices.orEmpty().sortedWith(DisplayOrder).firstOrNull { UsbAdapterCatalog.find(it.id) != null }?.id

/** Catalog devices first in catalog order, then the others by vendor and product id; bus and address break ties. */
private val DisplayOrder: Comparator<DetectedUsbDevice> = compareBy<DetectedUsbDevice>(
    { catalogRank(it.id) },
    { it.id.vid },
    { it.id.pid },
    { it.bus },
    { it.address },
)

private fun catalogRank(id: UsbId): Int =
    UsbAdapterCatalog.entries.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: Int.MAX_VALUE

private fun DetectedUsbDevice.toRow(overview: AdapterOverview, mode: AdapterMode, used: DetectedUsbDevice?): AdapterDeviceUi {
    val info = UsbAdapterCatalog.find(id)
    val inUse = id == overview.inUse
    val pills = buildList {
        when (info?.support) {
            AdapterSupport.COMPATIBLE -> add(AdapterPill.COMPATIBLE)
            AdapterSupport.NOT_RECOMMENDED -> add(AdapterPill.NOT_RECOMMENDED)
            null -> Unit
        }
        if (inUse) {
            add(AdapterPill.IN_USE)
        } else {
            access.toPill()?.let { add(it) }
        }
        if (!inUse && this@toRow == used) add(AdapterPill.WILL_BE_USED)
    }
    val manualSelection = overview.selection as? AdapterSelection.Manual
    return AdapterDeviceUi(
        id = id,
        bus = bus,
        address = address,
        name = if (info != null) UiText.DynamicString(info.name) else UiText.StringResource(Res.string.link_adapter_unknown_device),
        idText = id.toString(),
        chip = info?.chip,
        pills = pills,
        selectable = mode == AdapterMode.MANUAL && info != null,
        selected = manualSelection?.id == id,
    )
}

private fun UsbDeviceAccess.toPill(): AdapterPill? = when (this) {
    // Not probed: not a Wi-Fi adapter (a mouse, a camera), so there is nothing to say about opening it.
    UsbDeviceAccess.OPENABLE, UsbDeviceAccess.NOT_PROBED -> null
    UsbDeviceAccess.DRIVER_UNSUPPORTED -> AdapterPill.NEEDS_DRIVER
    UsbDeviceAccess.ACCESS_DENIED -> AdapterPill.NO_PERMISSION
    UsbDeviceAccess.BUSY -> AdapterPill.IN_USE
    UsbDeviceAccess.ERROR, UsbDeviceAccess.UNKNOWN -> AdapterPill.CANNOT_BE_OPENED
}

private fun AdapterOverview.statusText(): UiText {
    if (!helperConnected) return UiText.StringResource(Res.string.link_adapter_status_helper_down)
    inUse?.let { return using(it) }
    val scanned = devices ?: return UiText.StringResource(Res.string.link_adapter_status_scanning)
    if (scanned.isEmpty()) return UiText.StringResource(Res.string.link_adapter_status_no_devices)
    return when (val result = resolution) {
        is AdapterResolution.Use -> using(result.device.id)
        is AdapterResolution.Unavailable -> when (result.reason) {
            UnavailableReason.NOT_SCANNED_YET -> UiText.StringResource(Res.string.link_adapter_status_scanning)
            UnavailableReason.NO_COMPATIBLE_DEVICE -> UiText.StringResource(Res.string.link_adapter_status_no_compatible)
            UnavailableReason.SELECTED_DEVICE_ABSENT -> absent((selection as? AdapterSelection.Manual)?.id)
            UnavailableReason.SELECTED_DEVICE_NOT_SUPPORTED -> UiText.StringResource(Res.string.link_adapter_status_unsupported)
            UnavailableReason.NOT_OPENABLE -> notOpenable(result.device)
        }
    }
}

/** The chosen adapter is not plugged in; the resolver only says so for a manual choice, so [id] is never null there. */
private fun absent(id: UsbId?): UiText =
    if (id == null) {
        UiText.StringResource(Res.string.link_adapter_status_no_compatible)
    } else {
        UiText.StringResource(Res.string.link_adapter_status_absent, listOf(displayName(id), id.toString()))
    }

private fun using(id: UsbId): UiText =
    UiText.StringResource(Res.string.link_adapter_status_using, listOf(displayName(id), id.toString()))

private fun notOpenable(device: DetectedUsbDevice?): UiText {
    val resource = when (device?.access) {
        UsbDeviceAccess.DRIVER_UNSUPPORTED -> Res.string.link_adapter_status_cannot_open_driver
        UsbDeviceAccess.ACCESS_DENIED -> Res.string.link_adapter_status_cannot_open_permission
        UsbDeviceAccess.BUSY -> Res.string.link_adapter_status_cannot_open_busy
        else -> Res.string.link_adapter_status_cannot_open_error
    }
    return UiText.StringResource(resource, listOf(device?.let { displayName(it.id) } ?: ""))
}

/** The catalog name of [id], or its `vvvv:pppp` text for a device the catalog does not hold. */
private fun displayName(id: UsbId): String = UsbAdapterCatalog.find(id)?.name ?: id.toString()

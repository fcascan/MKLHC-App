/*
 * radiod - MKLHC-App radio helper (GPL-2.0-only). Links devourer (https://github.com/OpenIPC/devourer, GPL-2.0-only); no devourer source copied into this file.
 *
 * UsbScan.cpp - implementation of the LIST_DEVICES scan declared in UsbScan.h. Uses libusb directly (not
 * devourer): libusb_init on a context of its own, libusb_get_device_list, a look at each device's
 * configuration descriptor, a trial open of the ones that may be adapters, then libusb_free_device_list and
 * libusb_exit, whatever happened in between.
 */

#include "UsbScan.h"

#include <exception>
#include <set>
#include <string>

#if __has_include(<libusb.h>)
#include <libusb.h>
#else
#include <libusb-1.0/libusb.h>
#endif

namespace radiod {
namespace {

/** bDeviceClass of a hub; hubs are not adapters and are left out of the list. */
constexpr uint8_t kHubDeviceClass = 0x09;

/** bInterfaceClass of a vendor-specific interface, which every Wi-Fi adapter the driver knows exposes. */
constexpr uint8_t kVendorSpecificClass = 0xFF;

std::string scanErrorText(int rc) {
    return std::string(libusb_error_name(rc)) + " (" + libusb_strerror(static_cast<libusb_error>(rc)) + ")";
}

std::string where(libusb_device* device) {
    return "bus " + std::to_string(libusb_get_bus_number(device)) + " address " +
           std::to_string(libusb_get_device_address(device));
}

/** Maps the result of a trial libusb_open to a DEVICE_LIST access code. */
uint8_t accessFromOpenResult(int rc) {
    switch (rc) {
        case LIBUSB_SUCCESS:
            return ipc::kUsbAccessOpenable;
        case LIBUSB_ERROR_NOT_SUPPORTED:
            return ipc::kUsbAccessDriverUnsupported;
        case LIBUSB_ERROR_ACCESS:
#if defined(_WIN32)
            // WinUSB grants one handle at a time: a device another program (or our own radio) holds fails
            // with an access error, not BUSY. Permissions are not the issue on Windows.
            return ipc::kUsbAccessBusy;
#else
            return ipc::kUsbAccessDenied;
#endif
        case LIBUSB_ERROR_BUSY:
            return ipc::kUsbAccessBusy;
        default:
            return ipc::kUsbAccessError;
    }
}

/** Opens and immediately closes [device] (no claim, no configuration change); returns libusb_open's result. */
int trialOpen(libusb_device* device) {
    libusb_device_handle* handle = nullptr;
    const int rc = libusb_open(device, &handle);
    if (rc == LIBUSB_SUCCESS && handle != nullptr) {
        libusb_close(handle);
    }
    return rc;
}

enum class AdapterLook { VendorSpecific, Other, Unreadable };

/**
 * Whether some interface of [device]'s active configuration (or its first one, when the active one cannot be
 * read) is vendor specific. Reads only the descriptors libusb has cached; the device is not opened.
 */
AdapterLook lookAt(libusb_device* device) {
    libusb_config_descriptor* config = nullptr;
    if (libusb_get_active_config_descriptor(device, &config) != LIBUSB_SUCCESS || config == nullptr) {
        config = nullptr;
        if (libusb_get_config_descriptor(device, 0, &config) != LIBUSB_SUCCESS || config == nullptr) {
            return AdapterLook::Unreadable;
        }
    }
    bool vendorSpecific = false;
    for (uint8_t i = 0; i < config->bNumInterfaces && !vendorSpecific; ++i) {
        const libusb_interface& iface = config->interface[i];
        for (int alt = 0; alt < iface.num_altsetting; ++alt) {
            if (iface.altsetting[alt].bInterfaceClass == kVendorSpecificClass) {
                vendorSpecific = true;
                break;
            }
        }
    }
    libusb_free_config_descriptor(config);
    return vendorSpecific ? AdapterLook::VendorSpecific : AdapterLook::Other;
}

/** The scan's own libusb context and device list, released in the order libusb requires on every exit path. */
struct ScanResources {
    libusb_context* ctx = nullptr;
    libusb_device** devices = nullptr;

    ScanResources() = default;
    ScanResources(const ScanResources&) = delete;
    ScanResources& operator=(const ScanResources&) = delete;

    ~ScanResources() {
        if (devices != nullptr) {
            libusb_free_device_list(devices, 1);
        }
        if (ctx != nullptr) {
            libusb_exit(ctx);
        }
    }
};

/** Runs one scan into [devices]; adds what went wrong to [problems] (sorted, so equal scans compare equal). */
void scanInto(std::vector<ipc::UsbDeviceEntry>& devices, std::set<std::string>& problems) {
    ScanResources scan;
    const int rc = libusb_init(&scan.ctx);
    if (rc != 0) {
        scan.ctx = nullptr;
        problems.insert("libusb_init failed: " + scanErrorText(rc));
        return;
    }
    // Trial opens are expected to fail on some devices (wrong driver, in use); the access codes report that,
    // so libusb's own messages about it would only be noise on stderr.
    libusb_set_option(scan.ctx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_NONE);

    const ssize_t count = libusb_get_device_list(scan.ctx, &scan.devices);  // libusb.h defines ssize_t on MSVC
    if (count < 0) {
        scan.devices = nullptr;
        problems.insert("libusb_get_device_list failed: " + scanErrorText(static_cast<int>(count)));
        return;
    }

    for (ssize_t i = 0; i < count; ++i) {
        libusb_device* device = scan.devices[i];
        libusb_device_descriptor descriptor{};
        const int descriptorRc = libusb_get_device_descriptor(device, &descriptor);
        if (descriptorRc != LIBUSB_SUCCESS) {
            problems.insert(where(device) + ": device descriptor unreadable: " + scanErrorText(descriptorRc));
            continue;
        }
        if (descriptor.bDeviceClass == kHubDeviceClass) {
            continue;
        }
        ipc::UsbDeviceEntry entry;
        entry.vid = descriptor.idVendor;
        entry.pid = descriptor.idProduct;
        entry.bus = libusb_get_bus_number(device);
        entry.address = libusb_get_device_address(device);
        entry.deviceClass = descriptor.bDeviceClass;
        switch (lookAt(device)) {
            case AdapterLook::VendorSpecific:
                entry.access = accessFromOpenResult(trialOpen(device));
                break;
            case AdapterLook::Other:
                entry.access = ipc::kUsbAccessNotProbed;
                break;
            case AdapterLook::Unreadable:
                entry.access = ipc::kUsbAccessNotProbed;
                problems.insert(where(device) + ": configuration descriptor unreadable, not probed");
                break;
        }
        devices.push_back(entry);
    }
}

}  // namespace

UsbScanResult UsbScanner::scan() {
    UsbScanResult result;
    std::set<std::string> problems;
    try {
        scanInto(result.devices, problems);
    } catch (const std::exception& e) {
        result.devices.clear();
        problems.insert(std::string("scan failed: ") + e.what());
    } catch (...) {
        result.devices.clear();
        problems.insert("scan failed with an unknown exception");
    }

    std::string current;
    for (const std::string& problem : problems) {
        current += (current.empty() ? "" : "; ") + problem;
    }
    if (current != lastProblems_ && !current.empty()) {
        result.warning = "LIST_DEVICES: " + current;
    }
    lastProblems_ = current;
    return result;
}

}  // namespace radiod

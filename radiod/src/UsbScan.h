/*
 * radiod - MKLHC-App radio helper (GPL-2.0-only). Links devourer (https://github.com/OpenIPC/devourer, GPL-2.0-only); no devourer source copied into this file.
 *
 * UsbScan.h - LIST_DEVICES: the USB devices radiod can see, and whether it could open each one.
 *
 * Business logic: the app asks which USB devices are present (about every 2 s while the radio is down) so the
 * user can see whether the adapter is detected at all and why it cannot be used (wrong driver, no permission,
 * in use). Each scan runs on its own temporary libusb context, independent of the Radio's, so it works before
 * and after OPEN_DEVICE and never touches the open adapter's handle. Hubs (device class 0x09) are left out.
 * Only a device with a vendor-specific interface (class 0xFF, as every Wi-Fi adapter the driver knows has)
 * gets a trial libusb_open + libusb_close (no claim, no configuration change), whose result is its access
 * code; every other device is reported NOT_PROBED and never opened, so the scan does not wake suspended
 * devices, briefly take WinUSB's single handle of unrelated devices, or open HID devices every 2 s.
 * A scan never fails the session: any failure yields an empty list. Problems are reported as a warning only
 * when they differ from the previous scan's, so a lasting problem is logged once, not every 2 s.
 */

#pragma once

#include <string>

#include "Ipc.h"

namespace radiod {

/** Result of one scan: the devices found, and a warning to log when the scan's problems changed (else empty). */
struct UsbScanResult {
    std::vector<ipc::UsbDeviceEntry> devices;
    std::string warning;
};

/** Enumerates USB devices on demand; one instance per session keeps the last problems seen. Not thread-safe. */
class UsbScanner {
public:
    /** Scans once; never throws. Hides libusb so callers compile without it. */
    UsbScanResult scan();

private:
    /** The previous scan's problems, as reported; empty when it had none. */
    std::string lastProblems_;
};

}  // namespace radiod

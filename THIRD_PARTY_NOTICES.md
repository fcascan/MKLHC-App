# Third-Party Notices

MKLHC-App itself is licensed under the GNU Affero General Public License version 3 (AGPL-3.0). The `radiod/` directory holds a separate native helper process that is licensed under the GNU General Public License version 2 only (GPL-2.0-only), because it links the GPL-2.0 `devourer` driver; it is kept apart from the AGPL-3.0 app. The full license texts are in `LICENSE` at the repository root (AGPL-3.0) and in `radiod/LICENSE` (GPL-2.0).

Every external origin the project draws on is listed below, with its repository, its SPDX license identifier, and how MKLHC-App uses it.

| Component | Repository | SPDX license | How MKLHC-App uses it |
| --- | --- | --- | --- |
| OpenKart SDK | https://github.com/OpenKart-SDK | BSD-3-Clause | LP2P access-point keys/IEs, RCD protocol and Fuji control/pairing logic are ported to Kotlin. |
| mk-live | https://github.com/olus85/mk-live | MIT | Video (LSP/FRAM) reassembly, LVNI keepalive and event-channel responses are ported to Kotlin. |
| devourer | https://github.com/OpenIPC/devourer | GPL-2.0-only | Userspace USB Wi-Fi driver, linked only inside the separate GPL-2.0 `radiod` helper process. |
| PixelPilot | https://github.com/OpenIPC/PixelPilot | GPL-3.0-only | Reference only for the Android USB-file-descriptor technique; no code copied. |
| hostapd (static_ccmp_key patch) | https://github.com/OpenKart-SDK/hostapd | BSD-3-Clause | Static-CCMP access-point behaviour re-implemented; no code copied. |
| libusb | https://github.com/libusb/libusb | LGPL-2.1-only | USB access used by `radiod`. |
| ZXing core | https://github.com/zxing/zxing | Apache-2.0 | QR code generation for pairing. |

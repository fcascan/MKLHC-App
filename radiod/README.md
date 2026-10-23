# radiod

`radiod` is the native radio helper of MKLHC-App. The Kotlin app (`:core:radio`) listens on a local
AF_UNIX socket and spawns `radiod`; `radiod` connects to that socket and drives the USB Wi-Fi adapter
through the userspace driver [devourer](https://github.com/OpenIPC/devourer). The app tells it what to do
(open the adapter, set channel and identity, run the hardware beacon, transmit raw 802.11 frames) and it
reports back received frames, counters, logs and errors.

radiod is licensed GPL-2.0-only (see `LICENSE`) because it links devourer. It is kept apart from the
AGPL-3.0 app; no devourer source is copied into radiod's own files.

## Status

radiod builds against devourer `357f045` (2026-10-20, Visual Studio Build Tools 2026, libusb 1.0.30 from
vcpkg). It has run end to end with the real binary but without a radio: the app spawns it, the AF_UNIX
socket and the HELLO handshake work on Windows, and an OPEN_DEVICE failure reaches the app as an ERROR
before radiod exits. Still to do in the Task 1.8 hardware spike: the first run with an adapter bound to
WinUSB (radio up, beacon on air), then with the kart (association and DHCP).

Earlier checks, made without devourer with MSVC on Windows, still hold:

- `Ipc.cpp` against the byte vectors of the Kotlin `IpcCodecTest` (TX_FRAME, RX_FRAME, STATUS, header,
  SET_IDENTITY) plus round trips of all 14 messages and the strict-decode cases.
- `main.cpp` with a stub `Radio` against a fake app over a real Windows AF_UNIX socket: HELLO handshake,
  command dispatch, READY / RX_FRAME / STATUS / LOG, bad token, bad version, malformed message, corrupt
  length, app closing the socket, SHUTDOWN.

## Layout

| File | Purpose |
|---|---|
| `src/Ipc.h`, `src/Ipc.cpp` | Framed message codec, byte-identical to the Kotlin `IpcCodec` |
| `src/Radio.h`, `src/Radio.cpp` | Wraps devourer: open, init, beacon, TX queue and thread, RX callback, counters |
| `src/UsbScan.h`, `src/UsbScan.cpp` | LIST_DEVICES: enumerates the USB devices on a libusb context of its own, trial-opening those that may be adapters |
| `src/main.cpp` | Argument parsing, AF_UNIX connect, HELLO check, command dispatch, STATUS and LOG forwarding |
| `CMakeLists.txt` | Builds devourer (as a subdirectory) and the `radiod` executable |

## Get devourer

devourer is a git submodule at `radiod/devourer` (radiod does not vendor it), pinned at `357f045`, the
master of 2026-10-18 whose Jaguar2 support was checked for the TP-Link Archer T3U. After cloning
MKLHC-App, fetch it with:

```
git submodule update --init radiod/devourer
```

Do not add `--recursive`: devourer's own `reference/` submodules are vendor driver trees it needs only
for its tests and generators; the code it builds is already vendored in its tree. radiod reports the
short commit of the checkout to the app in READY.

## Build

Requirements: CMake 3.15 or newer, a C++20 compiler, libusb-1.0 and pkg-config.

```
cmake -S radiod -B radiod/build
cmake --build radiod/build --config Release
```

Windows: install libusb with a standalone vcpkg (`vcpkg install libusb pkgconf --triplet x64-windows`; the
vcpkg bundled with Visual Studio works only in manifest mode), set `VCPKG_ROOT`, and configure from the x64
Native Tools Command Prompt of Visual Studio Build Tools 2026, whose CMake has the Visual Studio 2026
generator (the CMake shipped with the Android SDK does not; with it, add `-G Ninja`). MSYS2 UCRT64 also
works, where devourer takes libusb from pkg-config.
`CMakeLists.txt` loads the vcpkg toolchain before `project()` when `VCPKG_ROOT` is set. The adapter must be
bound to WinUSB (for example with Zadig) or libusb cannot open it. With a dynamic vcpkg triplet,
`libusb-1.0.dll` must sit next to `radiod.exe`.

devourer builds about twenty example executables by default and has no switch to disable them, so
`CMakeLists.txt` adds it with `EXCLUDE_FROM_ALL`; only the library radiod links is built.

## Command line

```
radiod --socket <path> --token <token> [--retry-limit N] [--verbose]
```

`--retry-limit` is the device-wide hardware retry limit for unicast frames (1..63, default 7). Unknown
extra arguments are logged and ignored. The socket path must be shorter than 108 bytes (the AF_UNIX
`sun_path` limit, also on Windows), so the app should bind in a short directory. Exit codes: 0 clean, 1 usage, 2 connect or handshake failure,
3 fatal radio error (an ERROR message is sent first), 4 unexpected exception.

## Protocol notes

The wire format is documented in `src/Ipc.h`. The protocol version is 2 (version 2 added LIST_DEVICES and
DEVICE_LIST); the app and radiod must carry the same version or the handshake fails. Behavior that is
radiod's own choice:

- The app speaks first. `DesktopRadioLink` sends HELLO (version, token) right after radiod connects;
  radiod checks the version and token and answers a mismatch with ERROR code 1, then exits. radiod does
  not send HELLO itself.
- LIST_DEVICES (`0x000A`, `u32 requestId`) is answered by exactly one DEVICE_LIST (`0x0106`): the same
  `u32 requestId`, `u32 count`, then per device `u16 vid, u16 pid, u8 bus, u8 address, u8 deviceClass,
  u8 access`. The echoed id lets the app ignore a late answer to an earlier request. radiod scans on a
  temporary libusb context of its own (not the radio's) and leaves hubs (device class `0x09`) out. Only a
  device with a vendor-specific interface (class `0xFF`, in its active or else first configuration) gets a
  trial `libusb_open` + `libusb_close` with no claim; every other device is reported 5 "not probed" and is
  never opened (no wake-ups of suspended devices, no grab of WinUSB's single handle of unrelated devices).
  Access codes: 0 openable, 1 driver unsupported (`LIBUSB_ERROR_NOT_SUPPORTED`; on Windows, not bound to
  WinUSB), 2 access denied (`LIBUSB_ERROR_ACCESS` outside Windows; on Linux, a missing udev rule), 3 in use
  by another program (`LIBUSB_ERROR_ACCESS` on Windows, where WinUSB grants one handle at a time;
  `LIBUSB_ERROR_BUSY` elsewhere), 4 any other error, 5 not probed. It is allowed before and after
  OPEN_DEVICE; the radio's own open adapter then reports whatever libusb says (in use on Windows). It is
  never fatal: a scan that fails answers with an empty list. Scan problems are sent as a LOG warning only
  when they differ from the previous scan's, and nothing else is logged per scan (the app scans about every
  2 s while the radio is down).
- TX_FRAME `flags` bit 0 (`0x01`) means "do not expect an ACK". Group-addressed frames are always sent
  without ACK; unicast frames are sent with hardware retries. Other bits are reserved.
- TX_FRAME `rate` is a devourer rate string ("6M", "MCS3", ...). An unparsable rate falls back to 6M with a
  warning on stderr. The per-frame `retryLimit` is not applied (devourer takes one limit at creation); use
  `--retry-limit`.
- INIT `txPowerIndex` 0 keeps the chip default; any other value is passed to `SetTxPower`. A second INIT
  on a running radio retunes the channel.
- RX_FRAME: frames with a bad CRC and chip reports are dropped, the trailing FCS is stripped, `tsf` carries
  only the low 32 bits (all devourer exposes), `rate` is the low byte of devourer's data rate.
- STATUS `beacons` counts successful beacon loads (START_BEACON plus UPDATE_BEACON); the hardware does not
  report how many beacons it aired.
- Always send SHUTDOWN (or close the socket) and let radiod exit: the chip keeps beaconing after the host
  process dies, and a hard kill gives radiod no chance to stop it. On Jaguar2 (RTL8822BU / RTL8812BU)
  devourer has no power-down at teardown either, so after a hard kill the beacon stays on air until the
  adapter is unplugged.
- On exit radiod half-closes the socket and drains it for up to a second before closing, so the app always
  reads the last messages, including the ERROR of a fatal failure. A plain close with unread bytes from the
  app would be a reset on Windows, which can drop that ERROR.

## Hardware spike checklist (Task 1.8)

1. Fetch the submodule (above); install libusb and pkg-config; build.
2. Bind the adapter to WinUSB with Zadig; note its VID:PID.
3. Verify the API assumptions marked in `Radio.cpp` behave on hardware (they compile against devourer
   `357f045`): `CreateRadio` with a real `libusb_context`, `rssi[0]` units (dBm or percent), 40/80 MHz
   `ChannelOffset`. `SetAckResponder` before `StartBeacon` is safe on Jaguar2: devourer only refuses a
   beacon while a station identity owns port 0, which `SetAckResponder` does not arm.
4. Run the app (or a stub server) and confirm HELLO, READY, RX_FRAME, STATUS and a beacon on air.

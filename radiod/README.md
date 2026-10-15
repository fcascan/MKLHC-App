# radiod

`radiod` is the native radio helper of MKLHC-App. The Kotlin app (`:core:radio`) listens on a local
AF_UNIX socket and spawns `radiod`; `radiod` connects to that socket and drives the USB Wi-Fi adapter
through the userspace driver [devourer](https://github.com/OpenIPC/devourer). The app tells it what to do
(open the adapter, set channel and identity, run the hardware beacon, transmit raw 802.11 frames) and it
reports back received frames, counters, logs and errors.

radiod is licensed GPL-2.0-only (see `LICENSE`) because it links devourer. It is kept apart from the
AGPL-3.0 app; no devourer source is copied into radiod's own files.

## Status

The code is written but has not been built against devourer or run on hardware: the machine it was written
on had no libusb, so devourer was not fetched. What was checked without devourer, with MSVC on Windows:

- `Ipc.cpp` against the byte vectors of the Kotlin `IpcCodecTest` (TX_FRAME, RX_FRAME, STATUS, header,
  SET_IDENTITY) plus round trips of all 14 messages and the strict-decode cases.
- `main.cpp` with a stub `Radio` against a fake app over a real Windows AF_UNIX socket: HELLO handshake,
  command dispatch, READY / RX_FRAME / STATUS / LOG, bad token, bad version, malformed message, corrupt
  length, app closing the socket, SHUTDOWN.
- `Radio.cpp` only type-checks against hand-written mock devourer headers.

`Radio.cpp` therefore still needs its first real build, and radiod its first end-to-end run, in the Task 1.8
hardware spike.

## Layout

| File | Purpose |
|---|---|
| `src/Ipc.h`, `src/Ipc.cpp` | Framed message codec, byte-identical to the Kotlin `IpcCodec` |
| `src/Radio.h`, `src/Radio.cpp` | Wraps devourer: open, init, beacon, TX queue and thread, RX callback, counters |
| `src/main.cpp` | Argument parsing, AF_UNIX connect, HELLO check, command dispatch, STATUS and LOG forwarding |
| `CMakeLists.txt` | Builds devourer (as a subdirectory) and the `radiod` executable |

## Get devourer

devourer is added by the repository author as a git submodule (radiod does not vendor it):

```
git submodule add https://github.com/OpenIPC/devourer radiod/devourer
```

No commit is pinned yet. Pin the commit that was verified in the Task 1.8 hardware spike
(`git -C radiod/devourer rev-parse HEAD`, then commit the submodule pointer). radiod reports the short
commit of the checkout to the app in READY.

## Build

Requirements: CMake 3.15 or newer, a C++20 compiler, libusb-1.0 and pkg-config.

```
cmake -S radiod -B radiod/build
cmake --build radiod/build --config Release
```

Windows: install libusb with vcpkg (`vcpkg install libusb pkgconf`), set `VCPKG_ROOT`, and configure from a
developer prompt for the MSVC toolchain (or use MSYS2 UCRT64, where devourer takes libusb from pkg-config).
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

The wire format is documented in `src/Ipc.h`. Behavior that is radiod's own choice:

- The app speaks first. `DesktopRadioLink` sends HELLO (version, token) right after radiod connects;
  radiod checks the version and token and answers a mismatch with ERROR code 1, then exits. radiod does
  not send HELLO itself.
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
  process dies, and a hard kill gives radiod no chance to stop it.

## Hardware spike checklist (Task 1.8)

1. Add the submodule and pin it (above); install libusb and pkg-config; build.
2. Bind the adapter to WinUSB with Zadig; note its VID:PID.
3. Verify the API assumptions marked in `Radio.cpp` compile and behave: `CreateRadio` with a real `libusb_context`,
   `rssi[0]` units (dBm or percent), `SetAckResponder` versus the beacon BSSID, 40/80 MHz `ChannelOffset`.
4. Run the app (or a stub server) and confirm HELLO, READY, RX_FRAME, STATUS and a beacon on air.

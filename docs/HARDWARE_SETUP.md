# Hardware setup and radio spike

MKLHC-App hosts the kart's Wi-Fi network itself, on a commercial USB Wi-Fi adapter driven by a userspace driver. This guide
gets the adapter ready and runs the **radio spike**, the first end-to-end hardware test: the app beacons a pairing
network, the kart joins it, and the app writes a decrypted packet capture that opens in Wireshark.

## What you need

- A supported USB Wi-Fi adapter (see the next section). The reference adapter is the RTL8812CU, USB ID `0bda:c812`.
- The kart, charged, and able to enter pairing mode (front LEDs blinking).
- A desktop computer with a USB 2.0 or 3.0 port. These steps are written for Windows; Linux notes are included.
- JDK 21 (Gradle provisions it for the build), and the native radio helper `radiod` built once (see
  [Build radiod](#build-radiod)).
- Wireshark, to read the capture.

## Supported adapters

Adapters are identified only by their USB vendor and product id (`VID:PID`). Product names are not a reliable guide,
because the same product name can ship with different chips, so check the ID (Zadig shows it; so does Device Manager
under Details, Hardware Ids, for example `USB\VID_0BDA&PID_C812`).

| VID:PID | Chip | Example product | Notes |
|---|---|---|---|
| `0bda:c812` | RTL8812CU | LB-LINK WDN1300H | Reference adapter. Start here. |
| `2357:012d` | RTL8822BU (also labelled RTL8812BU; same 8822B chip) | TP-Link Archer T3U | Widely sold alternative. The userspace driver was developed and bench-tested on this exact adapter. Run the spike with `--vid 2357 --pid 012d`. |
| `0bda:c811` | RTL8811CU | COMFAST CF-811AC | Not recommended for now: the userspace driver applies the unicast retry limit, which the kart link relies on, only on the 8822B, not on this chip. |
| `0bda:8812` | RTL8812AU | ALFA AWUS036ACH | Allowed, not recommended: this chip family has no hardware retries for unicast frames, so frames to the kart can be lost. |

The spike opens `0bda:c812` by default; use `--vid` and `--pid` (hexadecimal) for another adapter from the table.

### Adapters that are NOT supported

- **AIC8800D80** is not supported by the userspace driver. It is a FullMAC adapter: its firmware owns the 802.11
  layer and exposes no raw frame access, which the access point needs.
- **MT7922** is not supported by the userspace driver. It is a PCIe card, so it cannot attach over USB, and the driver
  has no support for it.

Other adapters, including look-alikes of the ones above that report a different `VID:PID`, are not supported until they
are validated and added to the table.

## Bind the adapter to WinUSB (Windows, one time)

The userspace driver talks to the adapter through libusb, which on Windows needs the adapter bound to the WinUSB
driver. Binding replaces the driver Windows normally uses for that adapter, so **it stops working as an ordinary Wi-Fi
adapter** until you restore the original driver (see below). Use a dedicated adapter.

1. Plug the adapter in and download Zadig from https://zadig.akeo.ie.
2. Start Zadig and choose **Options, List All Devices**.
3. Pick the adapter in the drop-down. Check that the USB ID shown is one from the table (for the reference adapter,
   `0BDA C812`; for the TP-Link Archer T3U, `2357 012D`). Wi-Fi adapters often appear with a generic name such as
   `802.11ac NIC`, or with the name of their current driver, such as `TP-Link Wireless USB Adapter`; the USB ID is what
   identifies them. If the adapter lists several
   interfaces, choose the Wi-Fi one (normally interface 0).
4. In the driver box, on the right of the arrow, select **WinUSB**.
5. Click **Replace Driver** (or **Install Driver**) and wait for the success message.
6. Unplug the adapter and plug it back in.

To go back to the normal Wi-Fi driver: open Device Manager, find the adapter under *Universal Serial Bus devices*,
choose **Uninstall device**, tick **Delete the driver software for this device**, then unplug and replug the adapter.

## Linux notes

No driver binding is needed, but the user needs permission to open the USB device. Add a udev rule for each adapter you
use, for example for the reference adapter in `/etc/udev/rules.d/99-mklhc-adapter.rules`:

```
SUBSYSTEM=="usb", ATTR{idVendor}=="0bda", ATTR{idProduct}=="c812", MODE="0660", TAG+="uaccess"
```

Then run `sudo udevadm control --reload-rules && sudo udevadm trigger` and replug the adapter.

## Build radiod

`radiod` is the small native helper that owns the adapter. The app starts it and talks to it over a local socket. It is
built from `radiod/` against the userspace driver, which the repository author adds as a git submodule
(`radiod/devourer`); the full prerequisites and the Windows notes (libusb through vcpkg, `libusb-1.0.dll` next to
`radiod.exe`) are in [`radiod/README.md`](../radiod/README.md). In short:

```
cmake -S radiod -B radiod/build
cmake --build radiod/build --config Release
```

On Windows, run these from the **x64 Native Tools Command Prompt** of Visual Studio Build Tools 2026 (or later): its
CMake knows the Visual Studio 2026 generator. The CMake that comes with the Android SDK does not, so with that one add
`-G Ninja`. The vcpkg bundled with Visual Studio only works in manifest mode, so install a standalone vcpkg for libusb
as described in [`radiod/README.md`](../radiod/README.md).

The spike looks for the result at `radiod/build/Release/radiod.exe`, `radiod/build/radiod.exe` or `radiod/build/radiod`;
pass `--radiod <path>` if it is somewhere else.

## Run the spike

With the adapter bound to WinUSB and plugged in, from the repository root:

```
./gradlew :app:runSpike
```

Options go through `--args` (quote paths that contain spaces):

```
./gradlew :app:runSpike --args="--radiod C:\tools\radiod.exe --channel 6 --duration 300"
```

| Option | Meaning |
|---|---|
| `--radiod <path>` | The `radiod` executable. |
| `--vid <hex>`, `--pid <hex>` | Adapter USB ID (default `0bda`, `c812`). |
| `--channel <1\|6\|11>` | Wi-Fi channel (default 6). |
| `--tx-power <0..255>` | TX power index; 0 keeps the chip default. Use a level that is legal where you are. |
| `--beacon-interval <n>` | Beacon interval in time units of 1024 microseconds (default 100). |
| `--out <dir>` | Where the capture and the QR image are written (default `spike-captures/`). |
| `--duration <seconds>` | Stop after this long; 0 runs until you press Enter (default 0). |
| `--ready-timeout <seconds>` | How long to wait for the radio to come up before giving up (default 120). |
| `--verbose` | Debug logging, including `radiod`'s own output. |
| `--no-qr`, `--light-terminal` | Skip drawing the QR code in the console (the QR image file is still written), or draw it for a light terminal background. |

The program then:

1. Starts `radiod`, opens the adapter and starts the hardware beacon of a fresh **pairing network** (a hidden network
   whose name starts with `mklhc-pair_`, with a random key every run).
2. Prints the pairing QR payload (62 bytes, in hex), draws the QR code in the console and saves it as an image next
   to the capture (`spike-<date>-<time>-qr.svg`, path printed as `Pairing QR image`), then prints all of it again under
   `=== The pairing network is on air ... ===` once the beacon is up: scan the code only then, since the network does
   not exist before. The payload contains the pairing seed, so treat it like the QR code on a screen: anyone who can
   see it can join that one-run network. The image is deleted as soon as the spike starts stopping; a hard kill
   leaves it behind, so delete it by hand then (the spike also prints a warning if it cannot delete it).
3. Waits for the kart, and answers its authentication, association, DHCP and ARP, and accepts its TCP pairing
   connection and serves the RCD pairing handshake on it (and nothing beyond: see the next sections).

Once the on-air banner appears, put the kart into pairing mode (front LEDs blinking) and point its camera at the QR
code printed under it. A dark terminal background gives the right polarity by default; on a light background use
`--light-terminal`. If the console shows `?` or other garbled characters instead of a square, the output went through a
console that cannot print the block characters (under Gradle on Windows the console uses the ANSI code page). Then open
the `Pairing QR image` file in a browser or image viewer on the same computer and show that to the kart instead; it is
always dark on light. Never upload the image or paste the hex payload into an online QR tool: both hold the pairing seed
of the run.

Press **Enter** to stop (or use `--duration`). Stopping asks `radiod` to shut the radio down, which also stops the
beacon.

### Bringing the radio up (no kart needed)

The radio takes a while to come up. On Windows the RTL8822BU (Archer T3U) needs about 50 seconds, most of it spent
writing the chip's PHY tables one USB control transfer at a time. Meanwhile the console shows `applying BB phy_reg ...`
and `applying RF radioa ...`, then `radio up: chip=...` and `beacon started`. On this chip a line
`LCK TIMEOUT (LO not locked!)` is a known false alarm of the driver's lock check; the radio works.

To check that the beacon is on air before involving the kart, list the networks with another Wi-Fi adapter of the
computer (on Windows, `netsh wlan show networks mode=bssid`; a scan cycle can take a minute) and look for the BSSID the
spike printed. It appears as a hidden 802.11g network on the chosen channel.

### What a good run looks like (GO)

The console logs these milestones, in this order (association, the kart's first data frame, DHCP, ARP, then the TCP
pairing connection). They come from the access point (`RadioAccessPoint`), the DHCP server and network stack
(`NetstackNode`) and the spike itself; each real log line starts with the function that wrote it, for example
`onManagementFrame():`, which is left out here:

```
auth -> sta=<kart mac> retry=0 rssiDbm=<n> responses=1
assoc -> sta=<kart mac> retry=0 rssiDbm=<n> responses=1
KartAssociated -> sta=<kart mac> aid=1
first data frame decrypted -> sta=<kart mac> bytes=<n> total=<n>
offering the lease -> xid=<xid> client=<kart mac> yiaddr=169.254.98.66
lease acknowledged -> xid=<xid> client=<kart mac> yiaddr=169.254.98.66 leaseSeconds=<n>
answering who-has -> requester=169.254.98.66 ourIp=169.254.98.65
pairing TCP accepted -> peer=169.254.98.66:<port>
RCD pairing handshake completed -> device=<name>
```

The kart takes its address from the broadcast DHCP OFFER and ACK, resolves the app's address (169.254.98.65) by ARP,
which the app answers with the BSSID as the hardware address, and then connects to TCP port 5201, the app's pairing
port. The spike accepts that connection and serves the RCD handshake on it with a throwaway identity made for this run.
A final `pcap written: <path> -> frames=<n>` is logged at stop.

The spike stops there on purpose. A real pairing goes on by connecting to the kart's own pairing service and sending it
the game network's name and key (the `SetGroupInfo` command); the spike never does either. Since it never sends the
pairing request that stores the game network, the kart should keep the pairing it has stored, and a kart that was paired
before should stay paired. After the handshake the kart is expected to wait about five seconds for the app to do that
(the design's timeout: the kart resets if the app does not connect within 5 s after the handshake), give up, release the
lease, deauthenticate and start over, so the milestones repeat every few seconds. That loop is expected. When the kart
drops off, the spike frees the DHCP lease and aborts the kart's stale TCP connection, which the network stack logs at
`INFO` level as `abortWithReset(): resetting the connection at the application's request`.

A `radiod` error logged during the run (an `ERROR` line from `radiod` or `onEvent()`, or `CCMP rejected a frame`) does
not stop the capture on its own, so do not read a clean exit as proof the run was good: confirm the milestones above and
the pcap before calling it a GO.

Open the file from `spike-captures/` in Wireshark: the kart's DHCP, ARP and TCP packets are shown in plaintext. The
capture uses the radiotap link type and holds the management exchange as received and the data frames after decryption
(the Protected bit is cleared and the encryption header is removed), plus everything the app sends back (DHCP, ARP and
TCP). The criteria for the gate are:

- **A.** The kart associates with the pairing network (`auth` and `assoc` with `retry=0`).
- **B.** The app decrypts the kart's DHCP DISCOVER and the exported capture shows it in plaintext.
- **C.** The kart's pairing TCP connection reaches the RCD handshake (`answering who-has`, `pairing TCP accepted` and
  `RCD pairing handshake completed`).

The capture contains the kart's traffic. Keep it out of the repository (`spike-captures/`, `*.pcap` and the
`spike-*-qr.svg` images are ignored by git) and do not share it without looking at it first.

### If it does not work

| Symptom | What to check |
|---|---|
| `the radiod executable was not found` | Build `radiod` (above) or pass `--radiod <path>`. |
| `radiod did not connect` | `radiod` crashed at start-up. Run with `--verbose` to see its output. On Windows, make sure `libusb-1.0.dll` sits next to `radiod.exe`. |
| An error from `radiod` about opening the device | The adapter is not bound to WinUSB (or the udev rule is missing), another program holds it, or the `--vid` / `--pid` do not match the adapter. |
| `radiod did not report READY` | The adapter was found but did not come up. Replug it, and try a different USB port (a direct port, not a hub). |
| The beacon runs but the kart never joins | Make sure the kart is in pairing mode and sees the QR code, try another `--channel`, move the adapter close to the kart, and try `--beacon-interval 50` or a higher `--tx-power`. Association needs a signal in both directions. |
| `auth` appears with `retry=1` | The kart's frames were not acknowledged in time. Try a different channel or a closer position. |
| The kart joins but no `first data frame decrypted` appears | Look for `CCMP rejected a frame` in the log: the key does not match, which usually means a stale QR code was scanned. Restart the spike and scan the new one. |
| The kart takes its address but `answering who-has` does not appear | The kart did not get the DHCP ACK (look for `lease acknowledged`), or its ARP frame was not received. Try a closer position. |
| `answering who-has` appears but `pairing TCP accepted` does not | The kart's connection to port 5201 did not complete. Run with `--verbose` and look at the `TcpEndpoint` and `TcpConnection` lines; the SYN-ACK has to reach the kart, so try a closer position. |
| The beacon keeps airing after the spike was killed | Stop the spike with Enter or `--duration` so `radiod` can stop the beacon. RTL8822BU / RTL8812BU adapters (such as the Archer T3U) keep beaconing after a hard kill until they are unplugged, so unplug the adapter in that case. |

Run the spike once with a stable setup and note what you changed (beacon interval, power, channel, timing): those
values are the hardware tuning the project records after the spike.

# Hardware setup and radio spike

MKLHC-App hosts the kart's Wi-Fi network itself, on a commercial USB Wi-Fi adapter driven by a userspace driver. This guide
gets the adapter ready and runs the **radio spike**, the first end-to-end hardware test: the app beacons a pairing
network, the kart joins it, and the app writes a decrypted packet capture that opens in Wireshark. The last section runs
the desktop app itself with the radio, to pair the kart and drive it (see
[Run the desktop app with the radio](#run-the-desktop-app-with-the-radio)).

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

The spike and the desktop app look for the result at `radiod/build/Release/radiod.exe`, `radiod/build/radiod.exe`,
`radiod/build/RelWithDebInfo/radiod.exe` or `radiod/build/radiod`; if it is somewhere else, pass `--radiod <path>` to the
spike, or set `MKLHC_RADIOD` for the app.

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
| `--out <dir>` | Where the capture, the QR image and the stop file are written or looked for (default `spike-captures/`). |
| `--duration <seconds>` | Stop after this long; 0 runs until you press Enter or create the stop file (default 0). |
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

To stop, press **Enter**, create the **stop file**, or use `--duration`. All three end the run cleanly: the spike asks
`radiod` to shut the radio down, which also stops the beacon, and the QR image is deleted.

The **stop file** is `spike.stop` in the `--out` directory. It needs no keyboard input at the spike. From any other
terminal, in PowerShell, copy the `New-Item '...'` command from the `Spike running` line the spike prints (it holds the
absolute path, so it works from any directory), or, from a terminal at the repository root with the default `--out`,
run

```
New-Item spike-captures\spike.stop
```

The spike looks for the file twice a second and stops through the same clean path as Enter, logging
`stopping -> reason=stop file found`. It deletes the file again once it stops, so the next run starts clean, and it
deletes a file left by an earlier run before the radio comes up, so a forgotten file never ends a run by mistake (if a
file cannot be deleted, the spike says which path to remove by hand and does not start). Use the stop file whenever
Enter does not reach the spike. The Enter has to reach it through Gradle: on 2026-10-20 an Enter typed in the Claude
desktop app's Terminal panel never arrived (see "Why Enter did not arrive" below). Ctrl+C under Gradle on Windows ends
the spike without its cleanup, so use the stop file instead; after a Ctrl+C unplug the adapter to stop the beacon (see
the last row of the table below).

### Why Enter did not arrive

Investigated without hardware: `.\gradlew.bat :app:runSpike --console=plain` and `--console=rich` with
`--radiod C:\Windows\System32\whoami.exe`, an executable that exits at once, so radiod never connects and the run only
ends by a stop trigger or by its 30 s `radiod did not connect` timeout. The Enter was written to the pipe feeding
`gradlew.bat` twelve seconds after the start, from a script that kept the pipe open.

- An Enter sent as LF or as CRLF stopped the run at once (`stopping -> reason=Enter pressed`, exit code 0), with the plain
  and with the rich Gradle console: with a piped stdin the console type made no difference.
- An Enter sent as a lone CR was ignored: the run went on until `radiod did not connect` after 34 s (exit code 1). The
  spike used to read the Enter with `readlnOrNull()`, which waits for LF and never returns on a lone CR. A terminal
  that writes the Enter key straight into a pipe, with no console in between to turn the CR into CRLF, sends exactly that.
  The spike now treats a lone CR as an Enter too (re-run: stopped at once, `reason=Enter pressed`).
- Not reproduced: the Claude desktop app's Terminal panel itself, which could not be driven from the investigation.
  Whether it sends a lone CR, or does not forward its input to the Gradle client, is not known, so the cause there is
  inconclusive; the stop file is the dependable way to stop from that terminal.

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
lease, deauthenticate and start over, so the milestones repeat every few seconds. That loop is expected, and it is what
the hardware run of 2026-10-20 showed: every cycle reached the handshake, then the kart released the lease, closed the
connection and deauthenticated (reason 3). The ARP question appears only in the first cycle, because the kart keeps the
app's address in its cache. A few `CCMP rejected a frame ... retry=true reason=PN replay detected` warnings are the
kart's retransmissions of frames already received, and are harmless. When the kart
drops off, the spike frees the DHCP lease and aborts the kart's stale TCP connection, which the network stack logs at
`INFO` level as `abortWithReset(): resetting the connection at the application's request`. A kart that goes quiet
without deauthenticating (switched off or out of range) is dropped once nothing has arrived from it for 20 seconds: the
access point logs `station inactive, dropped -> sta=<kart mac> idleMs=<n>`, sends it a deauthentication and treats it
like a kart that left.

Optional check of that drop: switch the kart off (or carry it out of range) while it is associated, after
`KartAssociated` and before it leaves on its own. About 20 seconds later the log shows `station inactive, dropped`,
followed by the spike's `the station left, DHCP lease freed -> sta=<kart mac>`. If `station left` appears instead, the
kart had deauthenticated just before; try again. A kart that comes back still believing it is associated is answered
with `data from a station that is not associated, deauthenticating -> sta=<kart mac> reason=7` and joins again.

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
| The kart takes its address but `answering who-has` does not appear | Normal after the first cycle: the kart caches the app's address, so only the first association asks. If not even the first one does and `pairing TCP accepted` never follows, the kart did not get the DHCP ACK (look for `lease acknowledged`) or its ARP frame was not received. Try a closer position. |
| `answering who-has` appears but `pairing TCP accepted` does not | The kart's connection to port 5201 did not complete. Run with `--verbose` and look at the `TcpEndpoint` and `TcpConnection` lines; the SYN-ACK has to reach the kart, so try a closer position. |
| Enter does nothing and the spike keeps running | Create the stop file from another terminal: copy the `New-Item '...'` command from the `Spike running` line (it has the absolute path). The spike notices the file within half a second and stops cleanly. |
| The beacon keeps airing after the spike was killed | Stop the spike with Enter, the stop file (the command is in the `Spike running` line) or `--duration` so `radiod` can stop the beacon, and do not use Ctrl+C under Gradle. RTL8822BU / RTL8812BU adapters (such as the Archer T3U) keep beaconing after a hard kill until they are unplugged, so unplug the adapter in that case. |

Run the spike once with a stable setup and note what you changed (beacon interval, power, channel, timing): those
values are the hardware tuning the project records after the spike.

## Run the desktop app with the radio

The desktop app runs the real kart session over the radio: it starts `radiod` itself, brings the radio up, and pairs
and drives the kart from its screens. Before pairing a kart, read the warning in
[Start, pair and stop](#start-pair-and-stop): completing pairing replaces the kart's stored pairing. With the adapter
bound to WinUSB and plugged in, from the repository root:

```
./gradlew :app:run
```

`:app:run` starts the app from the repository root, so it finds `radiod` where the spike does. The app takes no options
on the command line other than `--demo`; it reads environment variables, set in the same shell before the command (in
PowerShell, for example, `$env:MKLHC_RADIO = "2357:012d"`):

| Variable | Meaning | Default |
|---|---|---|
| `MKLHC_RADIO` | The adapter's USB ID as `vvvv:pppp`, in hexadecimal. | `0bda:c812`, the reference adapter. The TP-Link Archer T3U is `2357:012d`. |
| `MKLHC_RADIOD` | The `radiod` executable. | The first build found in `radiod/build` (see [Build radiod](#build-radiod)), from the working directory. |
| `MKLHC_DATA_DIR` | Where the app keeps its pairing identity. | `%APPDATA%\MKLHC` on Windows, `~/Library/Application Support/MKLHC` on macOS, `$XDG_CONFIG_HOME/MKLHC` (or `~/.config/MKLHC`) on Linux. |
| `MKLHC_DEMO` | `1` runs the scripted demo instead: no radio and no kart, for working on the screens. | `0` |
| `MKLHC_VIDEO_DUMP` | A folder to dump the kart's raw video datagrams into, for studying the video stream (see [Dump the raw video](#dump-the-raw-video)). | Unset: no dump. |

For UI work without hardware, use `MKLHC_DEMO=1` or `./gradlew :app:run --args="--demo"`; the window title then ends in
`(demo)`. A variable set to an empty value counts as unset. A value the app cannot use (for example
`MKLHC_RADIO=0bda-c812`) or any other argument stops it at start-up with an error that names the variable or the
argument; the app never falls back to the demo on its own. Its first log line says what it uses, for example
`main(): starting -> mode=radio radio=0bda:c812 radiod=<path> dataDirectory=<path>`. When `radiod` is not where the app
looks, the log has an `ERROR` line `start(): radiod was not found, the radio stays off -> path=<path>` naming
`MKLHC_RADIOD`, and the app stays on its no-adapter state: build `radiod` or set the variable, then start the app again.
A path may be given in double quotes (cmd's `set MKLHC_RADIOD="C:\Program Files\radiod.exe"` keeps them); the app drops
them.

Only one instance runs per data directory: two would fight over the adapter and could each write an identity to the
same pairing file. A second one stops at start-up, before any window opens, with the `ERROR` line
`lockDataDirectoryOrExit(): another MKLHC-App is already running with this data directory, not starting` (exit code 3).
Close the other one first. The lock is the file `instance.lock` in the data directory; the operating system releases it
when the app ends, however it ends, so a crash never leaves a stale lock behind.

v1 limit: the app is configured only through these environment variables, which `./gradlew :app:run` passes on from
the shell. A packaged or installed app started from the Start menu or the Finder gets no way to set them, and does not
find `radiod` by default (it looks under `radiod/build` from its working directory, which is not the repository then).
Run the app with `./gradlew :app:run` for now.

### The pairing identity

On its first run the app creates its identity and keeps it in `pairing.preferences_pb` in the data directory: a master
secret (the game network's name and key, and the identity the app presents to the kart, are derived from it), the
access point's address (BSSID) and the Wi-Fi channel (1, 6 or 11), together with the paired kart and the driving
settings. The master secret is stored unencrypted: keep the file private, and never copy it into the repository. On
macOS and Linux the app creates the data directory readable by its owner only; a directory that already exists is left
as it is, with a warning in the log when other users can reach it (`chmod 700` it). Every later run uses the same
identity, which is how a paired kart finds the app's game network again.

Deleting the file, or pointing `MKLHC_DATA_DIR` at another directory, makes the app create a new identity on its next
run. A kart paired with the old identity no longer finds the game network, and must be paired again.

### Start, pair and stop

> **Warning: completing pairing replaces the kart's stored pairing.** In step 4 the app sends the kart the game
> network's credentials (the `SetGroupInfo` command), as soon as the kart has connected on the pairing network. The kart
> stores them in place of the pairing it had: it forgets its previous pairing and, from then on, joins this app's game
> network. The spike never sends this command; the app does. Pair a kart only if you accept that it will have to be
> paired again with whatever it was used with before. Cancelling or leaving the Pairing screen before the kart has
> scanned the code changes nothing on the kart.

1. Start the app. It starts `radiod` in the background, which connects to the app within a second or two.
2. Press **Start** on the Kart link screen to bring the radio up and beacon the game network of a kart that is already
   paired with this app, or **Pair a kart** to pair one. The radio takes a while to come up, about 50 seconds for the
   Archer T3U on Windows (see [Bringing the radio up](#bringing-the-radio-up-no-kart-needed)); the screen shows that the
   radio is starting. If it stays on the no-adapter state instead, `radiod` is not connected: see
   [If the app does not work](#if-the-app-does-not-work).
3. On the Pairing screen the app beacons a new pairing network once the radio is up, and shows its QR code in the
   window. Put the kart in pairing mode (front LEDs blinking) and point its camera at the window.
4. The kart joins the pairing network and connects. The app completes the pairing handshake and hands the kart the
   game network's name and key. The kart then resets onto the game network, the app identifies it and remembers it as
   the paired kart, and the cockpit can drive it. Right after the handshake the kart may refuse the app's first
   connects to its pairing (and later its control) service, because it starts listening a moment later; the app
   retries every 100 ms for up to 5 seconds, which the log shows as
   `connectToKart(): connected after the kart refused -> kartPort=5106 refusals=<n>`.

If `radiod` crashes, the app starts it again and puts the same network back on air, with the same keys, so the kart can
join again.

To stop, close the window (on macOS, Cmd+Q closes it the same way). The app powers the kart off, or only puts it to
sleep when the shutdown-on-exit setting is off (it is on by default, kept with the identity in the pairing file, and no
screen changes it yet), stops the beacon and shuts `radiod` down. That takes a few seconds at most: the app waits at
most 6 seconds for the kart and the beacon, then at most 6 seconds for `radiod`, and closes anyway. Closing while the
radio is still coming up is harmless: nothing is on air yet, and `radiod`, busy with the adapter, is killed after a few
seconds. The app runs the same cleanup when the operating system ends it (a logout, a SIGTERM, Ctrl+C in a terminal),
but not when it is killed outright: as with the spike, do not stop it with Ctrl+C under Gradle, which on Windows ends it
without that cleanup; if an adapter keeps beaconing after a hard kill, unplug it.

### Drive the kart

The cockpit's controls answer only while the kart is driving (the status overlay is gone). On the desktop, drive with
the keyboard, which works both axes at once:

| Key | Does |
|---|---|
| W or Up | Forward |
| S or Down | Reverse |
| A or Left | Steer left |
| D or Right | Steer right |
| Space | Brake light, while held |

A held key is the full value of its axis; the app caps the throttle at the max-throttle setting, half the kart's full
throttle by default. A key pressed together with Ctrl, Alt or Cmd is a shortcut and does not drive. The on-screen
steering slider and throttle pedal also work with the mouse, one at a time; whichever of a key and the mouse lets go
of an axis first returns it to zero.

Letting go means neutral: releasing a key, or the mouse button, returns that axis to zero. Every other way of losing
the controls also stops the kart:
- The window or the cockpit loses the focus (another window, a dialog): every key is released, since their key-ups go
  elsewhere. When the focus comes back, the first press of each key does nothing (it could be the auto-repeat of a key
  held all along); press it again to drive.
- The link drops: the cockpit sends neutral and covers the controls. A key still held when it comes back does nothing
  until it is released and pressed again.
- The screen stops refreshing the command (its thread hangs): the session sends neutral after 250 ms without a fresh
  command.
- The window closes: the kart is powered off (or put to sleep), as described above.

### Dump the raw video

The desktop app decodes the kart's camera with FFmpeg and shows it in the cockpit; until the first picture decodes
the cockpit says it is waiting for video and counts the frames received. To study the kart's video stream with real
data, set `MKLHC_VIDEO_DUMP` to a folder before starting the app (in PowerShell,
`$env:MKLHC_VIDEO_DUMP = "spike-captures\video"`). The app then writes every datagram that reaches its video port, exactly
as received and before any reassembly, to `video-<date>-<time>.viddump` in that folder (created if needed), one file per
run; its first log lines name the file (`open(): dumping the raw video datagrams -> path=<file>`). Closing the window
closes the file (`close(): video dump closed -> path=<file> datagrams=<n> bytes=<n>`).

Use a folder on a local disk: the app writes each datagram as it arrives, and a slow folder (a network share, a synced
folder) slows the kart session down with it.

The file starts with the 8 ASCII octets `MKLHCVD1`, then holds one record per datagram: the receive time (8 octets,
microseconds since the Unix epoch, from the wall clock), the payload length (4 octets), both little-endian, then the
payload. A run that was killed, or a full disk, can leave a short last record: drop it when reading. Only the video
port's payloads are written, never the radio frames or the control traffic, so the file holds no network key; it does
hold the camera's picture, so keep it on your machine (`*.viddump` files are ignored by git). The kart sends about
280 KB per second; the dump stops by itself at 256 MiB, about a quarter of an hour, with a warning in the log, and also
stops with a warning if a write fails (a full disk). The kart session goes on either way. A folder that cannot be created
is reported at start-up (`openVideoDumpOrNull(): the video dump cannot be opened, running without it`) and the app runs
without the dump.

### If the app does not work

| Symptom | What to check |
|---|---|
| `start(): radiod was not found, the radio stays off -> path=<path>` | Build `radiod` (see [Build radiod](#build-radiod)) or set `MKLHC_RADIOD` to its path. |
| `launchOrNull(): cannot start radiod -> executable=<path>` | The file exists but cannot be run: it is not an executable for this system (a build for another operating system, or not a `radiod` build at all), or it lacks the execute permission on macOS or Linux (`chmod +x`). The supervisor keeps retrying with a growing delay; fix the file or `MKLHC_RADIOD` and start the app again. |
| `radiod exited` warnings, and the app stays on its no-adapter state | `radiod` crashes at start-up. Its own output is logged under the `radiod-output` tag; on Windows, check that `libusb-1.0.dll` is next to `radiod.exe`. |
| `the kart refused the connection to port <port> until the connect window of 5s elapsed` | The kart never started its service within the 5 seconds after the handshake. While pairing this ends the pairing (the screen shows the error; the kart keeps its old pairing): press cancel, which also takes the pairing network off the air, power cycle the kart and pair again. In a game session the app recovers on its own. |
| `another MKLHC-App is already running with this data directory, not starting` | Another instance uses the same data directory (see above): close it. A second instance on another `MKLHC_DATA_DIR` has an identity of its own (a kart paired with the first one does not find it) and needs an adapter of its own (`MKLHC_RADIO`). |
| `the data directory cannot be used, not starting` | The data directory (or its parent) cannot be created or written. Fix its permissions, or set `MKLHC_DATA_DIR` to a directory you can write. |

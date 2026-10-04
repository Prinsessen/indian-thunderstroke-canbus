# Flashing the Indian CAN sniffer

**What is on the bike (2026-10-04):** the owner's own board, **CANFD-MC rev 1.0,
board #0001** — ESP32-S3-WROOM-1U, MCP2518FD on SPI, TCAN332G transceiver, LM5164
buck from the bike's 12 V, four soldered pads to the service connector and **no
USB connector** (USB is on solder pads on the back, see §4b). The LilyGO T-2CANFD
was retired on 2026-09-28, when a first CANFD-MC rev 1.0 went on the bike; that
board failed, and #0001 replaced it on 2026-10-03 (§5b). Board **#0002** is the
bench board (§5c). The pin map is the LilyGO's, so the bike's build environment
is still `sniffer-t2can`; the bench board is built from `bench-canfdmc`.

**The normal way to update is OTA**, not this file: `update` on the home LAN or
`mqtt` from anywhere the board reaches the broker, with automatic rollback — see
[OTA.md](OTA.md). USB is for the first flash of a board and for recovery.

**Read this first:** flash from your **LOCAL machine** (the one physically
connected to the ESP32 over USB) — **NOT** the openHAB server.

You are editing these files over VS Code **Remote-SSH**, so the files live on the
openHAB server. But the ESP32's USB port is on your local laptop, and the server
has no line of sight to it. Build/flash locally; the server only ever receives
data over MQTT/WiFi.

```
ESP32 (flashed locally, mounted on the bike)
   │  WiFi → MQTT
   ▼
mqtt.example.com  ◄──  openHAB server subscribes
```

Once flashed and on WiFi, it does not matter which machine flashed it.

---

## 1. Get the project onto your local machine

Copy the whole `indian-canbus/` folder to your laptop. Easiest is `scp` from the
laptop (adjust host/path to your server):

```bash
# run on your LOCAL machine
scp -r <user>@<openhab-server>:/etc/openhab-firmware/indian-canbus ./indian-canbus
cd indian-canbus
```

(Or `git clone <user>@<openhab-server>:/etc/openhab-firmware/indian-canbus` —
the firmware is its own repository on the server. A clone does not bring
`src/config.h` along, see §3.)

---

## 2. Install PlatformIO Core (CLI only — lightweight)

No heavy IDE needed. It is just a pip package:

```bash
# run on your LOCAL machine
pip install --user platformio      # or: pipx install platformio
pio --version                      # confirm it's on PATH
```

If `pio` is not found afterwards, add your user scripts dir to PATH
(`~/.local/bin` on Linux/macOS, the Python `Scripts` dir on Windows).

---

## 3. Create your local `config.h`

`src/config.h` is **git-ignored** and is NOT copied by git. If you `scp`-ed the
folder it came along; if you cloned from git it will be missing.

- If missing: `cp src/config.example.h src/config.h`
- Then edit `src/config.h` and set:
  - the **WiFi SSIDs and passwords** (up to three networks; the board joins the
    strongest one it can see),
  - the **MQTT user and password**,
  - **`BLE_PASSKEY`** (the pairing PIN the phone must type),
  - **`FW_VERSION`**,
  - **`OTA_FIRMWARE_URL`** — the address of your own server, one file per
    identity (bike and bench).

  The example already selects PRODUCTION mode and the MCP2518FD backend, which is
  what the CANFD-MC needs. The broker host and the DigiCert root CA are prefilled
  in `config.example.h`; the broker is reached over TLS on the board's TLS
  listener (`MQTT_PORT` in `config.h`).

---

## 4. Wire the hardware (listen-only, CANFD-MC)

The board hangs on the **DIAG** service connector, 8-way, cavities `A`–`H`
(full table in [WIRING-DIAGRAMS.md](WIRING-DIAGRAMS.md) §8). Four wires, soldered
to the four pads along the board's bottom edge:

| DIAG pin | Wire        | Signal | CANFD-MC pad |
|----------|-------------|--------|--------------|
| **A**    | ENGBRK-8 RD/DB | 12 V through the engine breaker, permanent | VBAT |
| **D**    | GND3-03 BK  | Ground | GND          |
| **G**    | C02-2 DG    | CAN-L  | CANL         |
| **H**    | C02-1 YE    | CAN-H  | CANH         |

Pins C, E and F are empty; pin B is switched 12 V and is not used.

- **The board is powered from the connector** (pin A, permanent 12 V, through the
  LM5164 buck). It is therefore powered whether or not the ignition is on, which
  is what deep sleep is for — see [SLEEP.md](SLEEP.md).
- **No terminator on the board**, and nothing to open: the bike's bus is already
  terminated at both ends.
- **Do NOT** connect any CAN shield/drain at the tap — the bus shield is
  already grounded at the ECM (single-point). A second ground = ground loop. The
  board has no shield pad for that reason.
- **Check before tapping:** with ignition OFF, measure resistance across
  DIAG **H ↔ G**. Expect **~60 Ω** (two 120 Ω terminators in parallel = a healthy
  terminated CAN bus). Open circuit / very high = wrong pins.

The controller is an **MCP2518FD on SPI**, fixed on the board; there is nothing
to wire chip-to-chip (`src/can_hal_mcp.cpp`):

| Signal | ESP32-S3 GPIO |
|--------|---------------|
| SPI SCLK | 12 |
| SPI MOSI | 11 |
| SPI MISO | 13 |
| MCP2518FD CS | 10 |
| MCP2518FD INT | 8 (also the wake-on-CAN pin) |

Crystal: 40 MHz, set in `can_hal_mcp.cpp`. A wrong value reads zero frames.

## 4b. USB on the CANFD-MC: the pads on the back

The CANFD-MC has **no USB connector**. On the back there is a row of flashing
pads, 2.54 mm pitch:

**GND · EN · TX · RX · IO0 · USB_D+ · USB_D−**

- USB flashing and the serial console use the ESP32-S3's native USB: a cut USB
  cable soldered to **USB_D+**, **USB_D−** and **GND**. The cable's 5 V wire is
  not connected; **the board is powered from 12 V**, not from USB.
- The port shows up as `/dev/ttyACM0` on Linux, `/dev/cu.usbmodemXXXX` on macOS
  and a `COMn` port on Windows.
- If esptool cannot put the chip into download mode by itself: hold **IO0** to
  GND, pulse **EN** to GND, release IO0.
- **TX / RX** are UART0. The console is on USB, so they are free; a 3.3 V
  USB-TTL adapter on them is the fallback flash path.

On the bike the board sits under the seat, so a USB flash there means the seat
off and a cable on the pads. That is why OTA has automatic rollback
([OTA.md](OTA.md)) and why anything new is tried on bench board #0002 first.

### History: the LilyGO T-CAN485 (the first board, classic ESP32)

Kept for whoever picks that board up again; none of it applies to the CANFD-MC.
The T-CAN485 was tapped on DIAG **H / G** for the bus and a ground only, and was
**powered separately** (USB or its own buck), not from DIAG.

The CAN transceiver is **on the board** and hard-wired to the ESP32's native
TWAI controller — there is nothing to wire chip-to-chip. You only land the bus:

| T-CAN485 | ESP32 GPIO | Role |
|----------|-----------|------|
| CAN TX   | 27 | TWAI TX → onboard transceiver (fixed) |
| CAN RX   | 26 | TWAI RX ← onboard transceiver (fixed) |
| CAN_SE   | 23 | transceiver mode: LOW = normal (firmware sets it) |
| 5V_EN    | 16 | **HIGH = transceiver powered** (firmware sets it) |
| CANH / CANL / GND | CAN terminal | to the bike bus (table above) |

> ⚠️ **Two T-CAN485 gotchas** (both handled in firmware, but check the hardware):
> the **5 V boost enable (GPIO 16)** must be HIGH or the transceiver is unpowered
> (zero frames), and the board's **own 120 Ω terminator jumper must be opened**
> for a mid-bus DIAG tap (the bus is already terminated — see the 60 Ω check).

---

## 5. Build, flash, monitor

**Always name the environment.** `platformio.ini` has no default, so a bare
`pio run` builds every environment in the file, and a bare `pio run -t upload`
tries to upload each of them, starting with the old T-CAN485 one.

| board | environment |
|-------|-------------|
| the bike's board (CANFD-MC #0001, or a board replacing it) | `sniffer-t2can` |
| a bench board (CANFD-MC #0002) | `bench-canfdmc` |

```bash
# run on your LOCAL machine, inside indian-canbus/
pio run -e sniffer-t2can                       # compile only (optional sanity build)
pio run -e sniffer-t2can -t upload -t monitor  # build + flash + open serial monitor @115200
```

Pick the right serial port if auto-detect fails:

```bash
pio device list                                             # find the port
pio run -e sniffer-t2can -t upload --upload-port /dev/ttyACM0
pio run -e sniffer-t2can -t upload --upload-port COM6       # windows
```

`pio run -e <env> -t upload` writes the app only. A **blank board has no bootloader and no
partition table** and needs `firmware.factory.bin` at `0x0` instead — see the
table further down and §5b.

### ⚠️ This is NOT an ESPHome project — the ESPHome dashboard can't build it

`indian-canbus` is a **PlatformIO / C++ (Arduino)** project, not an ESPHome YAML
config. The **ESPHome dashboard / esphome.io can only build+flash firmware it
generated from YAML**, so it **cannot compile or flash `src/main.cpp`**. Use
PlatformIO (above) to build.

### Alternative: browser-flash a prebuilt `.bin` (no `pio upload` needed)

If you'd rather flash from a browser (e.g. the laptop has no working USB driver
for `pio upload`, or you want a one-click reflash), you still **build with
PlatformIO** but flash the resulting binary with a WebSerial flasher:

```bash
# on your LOCAL machine, inside indian-canbus/
pio run -e sniffer-t2can              # builds .pio/build/sniffer-t2can/firmware.bin
```

Then, in **Chrome/Edge** (WebSerial only works in Chromium browsers), open one of:

- **https://web.esphome.io** → *Prepare for first use* / **Install** → choose
  **your own `firmware.bin`** — the "Install" button is generic ESP Web Tools and
  will flash any `.bin`, not just ESPHome ones.
- **https://web.esptool.js.org** → connect → add `firmware.bin` at offset
  **`0x10000`** → *Program*.

> The **build step always needs PlatformIO** — the browser only does the *flash*.
> ESPHome never compiles this firmware. Serial monitor after a browser-flash:
> `pio device monitor -b 115200` (or any serial terminal at 115200).

### Alternative: `esptool` directly

```bash
# CANFD-MC (ESP32-S3; the same for the retired LilyGO T-2CANFD)
# port is /dev/cu.usbmodemXXXX on macOS, /dev/ttyACM0 on Linux
esptool --chip esp32s3 -p <PORT> write_flash 0x10000 .pio/build/sniffer-t2can/firmware.bin

# a blank board: the factory image at 0x0 (wipes NVS, see below)
esptool --chip esp32s3 -p <PORT> write_flash 0x0 .pio/build/sniffer-t2can/firmware.factory.bin

# History: T-CAN485 (classic ESP32), env sniffer
esptool --chip esp32 -p /dev/ttyUSB0 write_flash 0x10000 .pio/build/sniffer/firmware.bin
```

For a bench board the paths are `.pio/build/bench-canfdmc/`.

> The command is now `esptool`; the `esptool.py` spelling still works but warns
> that it is deprecated. No PlatformIO install of your own is needed — the one
> PlatformIO already downloaded works, and its bundled Python has `pyserial`:
> ```bash
> ~/.platformio/penv/bin/python ~/.platformio/packages/tool-esptoolpy/esptool.py <args>
> ```

#### ⚠️ `firmware.bin` at `0x10000` vs `firmware.factory.bin` at `0x0`

The build produces both. **They are not interchangeable**, and picking the wrong
one silently destroys state:

| File | Offset | Erases | Use when |
|------|--------|--------|----------|
| `firmware.bin` | `0x10000` | `app0` only | **Normal case.** Preserves NVS. |
| `firmware.factory.bin` | `0x0` | bootloader + partition table + **NVS** | A blank board, a changed bootloader/partition table, or you deliberately want a clean slate |

The partition table puts **`nvs` at `0x9000`**, which is *inside* the range a
factory flash erases (`0x0`-`0x15dfff`). So flashing the factory image **wipes
NVS — including BLE bonding keys**.

Learned the hard way on 2026-09-02: after a factory flash the phone still held
its old link key while the board had none, so pairing failed twice with

```
[ble] pairing FAILED - dropping link
[ble] client disconnected (reason 534)
```

(`534` = `0x216` = HCI `0x16`, *Connection Terminated By Local Host* — the
firmware's own deliberate `disconnect()`.) It only recovered once the phone gave
up and negotiated a fresh key. **If you must flash the factory image, forget the
device on the phone at the same time.**

OTA writes only the app partition, so it never has this problem.

BLE keys are not all NVS holds. A factory flash also resets the **deep-sleep flag
to off** (`sleepcfg/en`, see [SLEEP.md](SLEEP.md)), the ride counters and the
remembered WiFi network, and it blanks `otadata`, so the board boots `app0`.
Checked 2026-10-03 against the CANFD-MC kit: the factory image is 0xFF across the
whole `nvs` range, `0x9000`-`0xdfff`.

Expected boot output on a CANFD-MC with the ignition on (the lines that matter;
`[svc]`, `[cnt]`, `[ble]`, `[wifi]` and `[mqtt]` lines from the other modules come
in between):

```
 Indian Springfield 2017 - CAN sniffer (LISTEN-ONLY)
 MQTT + BLE | ESP32-S3 + MCP2518FD (CANFD-MC, T-2CANFD) | never TX
 MODE: PRODUCTION — decoding in-firmware, publishing canbus/springfield/state
[rollback] running app0, state none, other slot none
[ble] advertising as "Springfield" (passkey pairing required)
Fixed bitrate 250000: listening frames=NNNN

>> Detected bus: 250 kbps  (NNNN frames / 1500 ms)

Logging frames (USB) + publishing changes (MQTT):
```

Three things in it are not what they seem:

- **Images up to 2026.10.04-2 print an older banner**, the one on the bike
  included: `USB + MQTT | LilyGO T-2CANFD (MCP2518FD/SPI, CAN A)` and a fixed
  `canbus/indian/state` on the `MODE` line, whatever the board and its base topic.
  The source was corrected on 2026-10-04; the lines above are what the next image
  prints (the topic is the build's own base topic).
- **There is no bitrate scan.** `CAN_FIXED_BITRATE` is 250000 in `config.h`, so
  the firmware listens at 250 kbit/s only ([SLEEP.md](SLEEP.md) has the reason).
- **`[rollback]`** reads `state none` after a USB flash and `state pending` on
  the first boot after an OTA ([OTA.md](OTA.md), "Automatic rollback"). A bench
  build advertises as `CANFD-bench`.

If **no frames arrive** with the ignition ON: check the four pads (CANH/CANL not
swapped, GND, 12 V on VBAT) and the 60 Ω reading in §4. The firmware's own hint
at this point says the same from the next image on; images up to 2026.10.04-2
still list `PIN_5V_EN`, `CAN_SE` and the 120 Ω jumper, which are T-CAN485 parts
and do not exist on the CANFD-MC.

> **Note (2026-08-14):** the first attempt no longer has to succeed. If the ignition
> is OFF at boot the firmware keeps retrying from `loop()` (and since 2026-09-27
> the scan window services the phone link while it waits) and **auto-attaches
> when the bus wakes up — no reboot required**. You'll see
> `No frames on any rate (ignition OFF?)` followed by silent retries until
> `>> Detected bus:` appears.

---

## 5b. Swapping in a new board (a CANFD-MC replacing the bike's)

Everything below went wrong once, on 2026-10-03, when a spare board replaced a
broken bike board. It took four OTAs to get back to "as before". Done in this
order, it takes one USB flash and one switch.

**1. Decide the role before you build anything.** Two environments build the same
code under different identities:

| env | MQTT base | client id | BLE name | openHAB items | OTA file |
|-----|-----------|-----------|----------|---------------|----------|
| `sniffer-t2can` | `canbus/springfield` | `indian-canbus-<mac>` | Springfield | `CanBus_*` | `indian-canbus-firmware.bin` |
| `bench-canfdmc` | `canbus/bench` | `canfd-bench-<mac>` | CANFD-bench | `CanBench_*` | `indian-canbus-bench-firmware.bin` |

(`bench-rollback` and `bench-rollback-bad` are test builds with the bench
identity; see [OTA.md](OTA.md). `-bad` never goes on the bike.)

A board that replaces the bike's board is built from **`sniffer-t2can`**, even if it
sat on the bench yesterday. Flashing the bench kit "because it is a bench board"
turns the replacement into a second bench board. You then have to OTA it across
on purpose (step 4).

**2. Flash over USB from the build you would OTA.** Build `sniffer-t2can` with the
current `FW_VERSION` and check that the image in `/etc/openhab/html/` has the same
md5 as `.pio/build/sniffer-t2can/firmware.bin`. A new board has no app, so it needs
`firmware.factory.bin` at `0x0`. Remember that this wipes NVS (see the table above).

**3. Put back what the wipe removed.** With the ignition on and the board reporting
on `canbus/springfield`:
- **Deep sleep:** switch `CanBus_Sleep` ON. Confirm it from the board's own reply,
  `CanBus_SleepState` / the retained `sleep/status` showing `"enabled":"ON"`,
  not from the switch. The board's MQTT account may read and write all of
  `canbus/#`, so the bike topic works. Do not detour through the bench identity
  to set it.
- **BLE:** the board has a new MAC. On the phone and on the navigator, forget the
  old "Springfield" in the system Bluetooth settings, not just in the app, and pair
  again.
- **Counters** start again from zero. That is expected.

**4. Each identity has its own OTA file.** Since 2026.10.04-1 the bike identity
pulls `indian-canbus-firmware.bin` and the bench identity
`indian-canbus-bench-firmware.bin`, for `update` and for `mqtt` alike, so an OTA
no longer changes a board's identity by accident. (On 2026-10-03 both shared one
URL, which served the bike image: an `update` on `canbus/bench/ota` turned a
bench board into a bike board. That is where the four OTAs went.) To cross on
purpose, copy the other identity's image into the file of the identity the board
has **now**, send one `update`, and put the right image back as soon as the board
reports `Downloading 100%`. `tools/deploy-bench-ota.sh` refuses anything but a
bench build, so that copy is done by hand. Each crossing is a full OTA. Check the
ignition before every one (see "Before pressing Update" in [OTA.md](OTA.md)).

**5. Check the result** on `CanBus_MetaRaw`: `fw` is the expected version and
`can_detected: true`. After an OTA: `reset: sw`, `ota_state: valid`, and
`CanBus_OTA` reads `Running <version> - verified`. After a USB flash:
`ota_state: none`, `CanBus_OTA` reads `Running <version>`, and `reset` is
`poweron` or `unknown` (the esptool reset). The same `FW_VERSION` before and
after is correct when the point was the identity rather than new code. The
version is bumped only when `src/` changes.

---

## 5c. The bench board (#0002)

A second CANFD-MC on the bench, on 12 V with no CAN bus. It runs
the same code under its own identity, so it can come and go without touching the
bike's items, and nothing new goes to the bike before it has run here.

| | bench | bike |
|---|---|---|
| environment | `bench-canfdmc` | `sniffer-t2can` |
| MQTT base | `canbus/bench` | `canbus/springfield` |
| BLE name | `CANFD-bench` | `Springfield` |
| OTA file in `/etc/openhab/html/` | `indian-canbus-bench-firmware.bin` | `indian-canbus-firmware.bin` |
| openHAB | `canbus-bench.things` / `canbus-bench.items`, `CanBench_*` | `canbus.things` / `canbus.items`, `CanBus_*` |

**First flash** (USB pads, §4b): `pio run -e bench-canfdmc`, then
`firmware.factory.bin` from `.pio/build/bench-canfdmc/` at `0x0`.

**After that, OTA** — on the server:

```bash
cd /etc/openhab-firmware/indian-canbus
~/.platformio/penv/bin/pio run -e bench-canfdmc
tools/deploy-bench-ota.sh bench-canfdmc      # copies to the bench file only; refuses a non-bench env
```

Then send `update` (HTTP, home LAN) or `mqtt` (over the broker link) to
`CanBench_OTA` and watch `CanBench_OTA_Status` and `CanBench_Meta`
(`fw`, `ota_state`, `ota_other`, `reset`).

- Deep sleep on the bench: `CanBench_SleepEnable`. With no bus there is nothing
  to wake it but the hourly backstop, so leave it off unless sleep is what is
  being tested.
- Over `mqtt` a board declines the image it already runs (`No update
  available`), so a test build needs its own `FW_VERSION`.
- Without an antenna the module reaches about a metre; put it next to an AP.

---

## 6. TPMS discovery (DISCOVERY builds only)

The helper exists only in DISCOVERY builds (`FIRMWARE_MODE 0`); PRODUCTION, which
the bike runs, compiles it out (`TPMS_DISCOVERY` follows the mode in
`main.cpp`). The tyre sensors were found with it — PGN 65268 — and are decoded;
the steps are kept for reference. In the serial monitor of a DISCOVERY build:

1. Ride briefly to wake the tyre sensors, then park (engine can idle/off).
2. Press **`z`** — resets the byte min/max baseline.
3. Slowly bleed one tyre for ~30 s.
4. Press **`r`** — prints IDs whose bytes moved a little (TPMS candidates).

The hit was **PGN 65268 (0xFEF4)**, which the firmware decodes. The helper's
code is marked with `>>> REMOVE ON CLEANUP <<<` tags in `main.cpp`.

---

## 7. The openHAB side (on the server)

The thing and the items are in place and live: `things/canbus.things` and
`items/canbus.items` under `/etc/openhab` are the originals, and `canbus.things`
/ `canbus.items` in this repository are copies refreshed from them. To set the
integration up on another openHAB server, copy them the other way; the thing
expects an MQTT broker bridge named `mqtt:broker:broker`:

```bash
# on the openHAB server
cp /etc/openhab-firmware/indian-canbus/canbus.things /etc/openhab/things/
cp /etc/openhab-firmware/indian-canbus/canbus.items  /etc/openhab/items/
```

No restart needed — openHAB reloads things/items on save. Watch:

```bash
tail -f /var/log/openhab/openhab.log /var/log/openhab/events.log
```

Data flows: ESP32 → MQTT (`canbus/springfield/...`) → `mqtt:broker:broker` →
items in group `gCanBus`. The bench board has its own pair,
`canbus-bench.things` / `canbus-bench.items` (`canbus/bench/...`, items
`CanBench_*`).

---

## 8. (Optional / advanced) Active OBD-II polling

Everything above is **100 % passive** — the firmware only listens to J1939
broadcasts and never touches the bus. A few values an OBD-II dongle can show are
**not broadcast**; they only exist as **request/response** (OBD-II Mode 01 PIDs).
Nothing on the bus asks for them, so a listen-only sniffer never sees them:

| Value | Mode 01 PID | Response decode |
|-------|-------------|-----------------|
| Engine Load | `0x04` | `41 04 A` → `A × 100 / 255` % |
| Runtime since start | `0x1F` | `41 1F A B` → `A×256 + B` s |
| Fuel Pressure | `0x0A` | `41 0A A` → `A × 3` kPa |
| (bonus) Intake temp, timing advance, O₂, fuel trim… | `0x0F`, `0x0E`, … | see any OBD-II PID table |

To capture these the sniffer must **stop being passive and become a
participant**: take the controller out of listen-only mode (`canInit()` with
`listenOnly` false; on the CANFD-MC that is the MCP2518FD leaving `ListenOnly`),
transmit request frames (`7DF 02 01 <PID>…`), and decode the ECU's replies on
`7E8` / `18DAF11x`.

**Trade-off — decide before enabling:**

| | Listen-only (default) | Active OBD polling |
|---|---|---|
| Bus impact | none, cannot even ACK | sends frames, ACKs traffic |
| Risk on a running bike | zero | low, but **not** zero |
| Extra data | — | Engine Load, Runtime, Fuel Pressure + ~30 OBD PIDs |

> The Thunder Stroke 111 is **air-cooled**, so there is **no coolant temp** PID —
> one of the more useful OBD values simply does not exist on this bike.

**If you decide it's worth it**, the intended design (not yet built) is a guarded
opt-in so the default stays passive:

```cpp
// main.cpp
#define OBD_ACTIVE_POLL 0     // 1 = enable request/response polling

#if OBD_ACTIVE_POLL
//  - init the controller in normal mode instead of listen-only
//  - only poll while the engine runs (RPM > 0)
//  - conservative rate: 1 request/sec, round-robin PIDs 0x04,0x1F,0x0A
//  - probe supported PIDs once via PID 0x00 and log the bitmask
//  - decode 7E8 replies → publish <base>/obd/<pid>
#endif
```

Recommendation: leave it **off**. It's only worth enabling if you later want a
real **diagnostic mode** (read/clear DTCs, live fuel trim/timing while chasing a
fault). Ask and it can be added as a proper toggle rather than for these three
"nice-to-have" numbers alone.

---

## Safety recap

- Firmware is **hardware listen-only** (the MCP2518FD in `ListenOnly` mode on the
  CANFD-MC; TWAI `TWAI_MODE_LISTEN_ONLY` on the old T-CAN485); it cannot
  transmit or ACK. `TX_ENABLED 0` keeps all transmit code compiled out.
- Never bond the CAN shield at the tap.
- Do the 60 Ω bench check before trusting the pins.

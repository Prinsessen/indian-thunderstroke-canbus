# Flashing the Indian CAN sniffer

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

(Or clone the openhab repo locally and use `indian-canbus/` from there.)

---

## 2. Install PlatformIO Core (CLI only — lightweight)

No heavy IDE needed. It is just a pip package:

```bash
# run on your LOCAL machine
pip install --user platformio      # or: pipx install platformio
pio --version                      # confirm it's on PATH
```

If `pio` is not found afterwards, add your user scripts dir to PATH
(`~/.local/bipion` on Linux/macOS, the Python `Scripts` dir on Windows).

---

## 3. Create your local `config.h`

`src/config.h` is **git-ignored** and is NOT copied by git. If you `scp`-ed the
folder it came along; if you cloned from git it will be missing.

- If missing: `cp src/config.example.h src/config.h`
- Then edit `src/config.h` and set your **WiFi SSID/password**.
  (MQTT is already prefilled for the house broker: `mqtt.example.com:8883` over TLS,
  with the DigiCert root CA pinned in `config.h` / `config.example.h`.)

---

## 4. Wire the hardware (listen-only pigtail)

Tap the **DIAG** connector (C02). Physical cavities `H G F E D C B A`:

| DIAG pin | Wire       | Signal | To T-CAN485 CAN terminal |
|----------|------------|--------|--------------------------|
| **H**    | C02-1 YE   | CAN-H  | CANH                     |
| **G**    | C02-2 DG   | CAN-L  | CANL                     |
| **F/E**  | GND3-03 BK | Ground | GND (0 V ref)            |

- Power the T-CAN485 **separately** (USB / its own buck) — do NOT rely on DIAG
  for board power.
- **Do NOT** connect any CAN shield/drain at the pigtail — the bus shield is
  already grounded at the ECM (single-point). A second ground = ground loop.
- **Bench check before tapping:** with ignition OFF, measure resistance across
  DIAG **H ↔ G**. Expect **~60 Ω** (two 120 Ω terminators in parallel = a healthy
  terminated CAN bus). Open circuit / very high = wrong pins or bus not powered.

### T-CAN485 CAN wiring (onboard transceiver — no SPI)

The CAN transceiver is **on the board** and hard-wired to the ESP32's native
TWAI controller — there is nothing to wire chip-to-chip. You only land the bus:

| T-CAN485 | ESP32 GPIO | Role |
|----------|-----------|------|
| CAN TX   | 27 | TWAI TX → onboard transceiver (fixed) |
| CAN RX   | 26 | TWAI RX ← onboard transceiver (fixed) |
| CAN_SE   | 23 | transceiver mode: LOW = normal (firmware sets it) |
| 5V_EN    | 16 | **HIGH = transceiver powered** (firmware sets it) |
| CANH / CANL / GND | CAN terminal | to the bike bus (table above) |

> ⚠️ **Two board gotchas** (both handled in firmware, but check the hardware):
> the **5 V boost enable (GPIO 16)** must be HIGH or the transceiver is unpowered
> (zero frames), and the board's **own 120 Ω terminator jumper must be opened**
> for a mid-bus DIAG tap (the bus is already terminated — see the 60 Ω check).

---

## 5. Build, flash, monitor

```bash
# run on your LOCAL machine, inside indian-canbus/
pio run                       # compile only (optional sanity build)
pio run -t upload -t monitor  # build + flash + open serial monitor @115200
```

Pick the right serial port if auto-detect fails:

```bash
pio device list                       # find the port
pio run -t upload --upload-port /dev/ttyUSB0
pio run -t upload --upload-port COM6 # windows
```

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
pio run                               # builds .pio/build/sniffer/firmware.bin
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
# T-CAN485 (classic ESP32)
esptool --chip esp32 -p /dev/ttyUSB0 write_flash 0x10000 .pio/build/sniffer/firmware.bin

# T-2CANFD (ESP32-S3) — port is /dev/cu.usbmodemXXXX on macOS, /dev/ttyACM0 on Linux
esptool --chip esp32s3 -p <PORT> write_flash 0x10000 .pio/build/sniffer-t2can/firmware.bin
```

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
| `firmware.factory.bin` | `0x0` | bootloader + partition table + **NVS** | Bootloader/partition table changed, or you deliberately want a clean slate |

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

Expected boot output:

```
 Indian Springfield 2017 - CAN sniffer (LISTEN-ONLY)
 USB + MQTT | LilyGO T-CAN485 (ESP32 TWAI) | never TX
Scanning bitrates (listen-only)...
  250 kbps : listening      frames=NNNN
  ...
>> Detected bus: 250 kbps  (NNNN frames / 1500 ms)
Logging frames (USB) + publishing changes (MQTT):
[tpms] discovery ON: serial 'z'=reset baseline, 'r'=report
```

If **no frames on any rate**: check **PIN_5V_EN (GPIO 16) HIGH** (transceiver
power), **CAN_SE (GPIO 23) LOW**, the board's onboard **120 Ω jumper opened**,
CANH/CANL not swapped, common GND, and ignition ON.

> **Note (2026-08-14):** the boot scan no longer has to succeed. If the ignition
> is OFF at boot the firmware keeps re-scanning from `loop()` (one rate per
> attempt) and **auto-attaches when the bus wakes up — no reboot required**.
> You'll see `No frames on any rate (ignition OFF?)` followed by silent retries
> until `>> Detected bus:` appears.

---

## 6. TPMS discovery (temporary helper)

Firmware ships with `#define TPMS_DISCOVERY 1`. In the serial monitor:

1. Ride briefly to wake the tyre sensors, then park (engine can idle/off).
2. Press **`z`** — resets the byte min/max baseline.
3. Slowly bleed one tyre for ~30 s.
4. Press **`r`** — prints IDs whose bytes moved a little (TPMS candidates).

The likely hit is **PGN 65268 (0xFEF4)**, which the firmware already decodes.
Set `TPMS_DISCOVERY 0` to compile the helper out once you've found it (see the
`>>> REMOVE ON CLEANUP <<<` tags).

---

## 7. Activate in openHAB (on the server)

Once you're happy with the data, copy the thing + items into place **on the
openHAB server** (transforms already live in `/etc/openhab/transform/`):

```bash
# on the openHAB server
cp /etc/openhab-firmware/indian-canbus/canbus.things /etc/openhab/things/
cp /etc/openhab-firmware/indian-canbus/canbus.items  /etc/openhab/items/
```

No restart needed — openHAB reloads things/items on save. Watch:

```bash
tail -f /var/log/openhab/openhab.log /var/log/openhab/events.log
```

Data flows: ESP32 → MQTT (`canbus/indian/...`) → `mqtt:broker:broker` → items in
group `gCanBus`.

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
participant**: switch TWAI from `TWAI_MODE_LISTEN_ONLY` to `TWAI_MODE_NORMAL`,
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
//  - init TWAI in TWAI_MODE_NORMAL instead of LISTEN_ONLY
//  - only poll while the engine runs (RPM > 0)
//  - conservative rate: 1 request/sec, round-robin PIDs 0x04,0x1F,0x0A
//  - probe supported PIDs once via PID 0x00 and log the bitmask
//  - decode 7E8 replies → publish canbus/indian/obd/<pid>
#endif
```

Recommendation: leave it **off**. It's only worth enabling if you later want a
real **diagnostic mode** (read/clear DTCs, live fuel trim/timing while chasing a
fault). Ask and it can be added as a proper toggle rather than for these three
"nice-to-have" numbers alone.

---

## Safety recap

- Firmware is **hardware listen-only** (TWAI `TWAI_MODE_LISTEN_ONLY`); it cannot
  transmit or ACK. `TX_ENABLED 0` keeps all transmit code compiled out.
- Never bond the CAN shield at the tap.
- Do the 60 Ω bench check before trusting the pins.

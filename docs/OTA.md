# Indian CAN Bus — Firmware OTA Update

The ESP32 firmware updates **over the air via a single button in openHAB**. You
press **🔄 Update Now** in the sitemap; the device downloads the new firmware
over plain HTTP from the openHAB web server, flashes itself, reboots, and
reports the running version back — all wirelessly, from anywhere the device can
reach the broker + web server.

No cable. No laptop. No mDNS. No inbound connections to the device.

---

## How it works (the whole chain)

```
 openHAB UI  ──"update" cmd──►  CanBus_OTA item
      │
      ▼  (automation/js/canbus-ota.js)
 publishMQTT  ──►  MQTT topic  canbus/indian/ota  = "update"
      │
      ▼  (ESP32 subscribed, onMqttMessage in main.cpp)
 httpUpdate.update("http://192.0.2.10:8080/static/indian-canbus-firmware.bin")
      │            └─ progress ──►  canbus/indian/ota/status  = "Downloading NN%"
      ▼
 flash + reboot
      │
      ▼  (on boot, mqttConnect)
 publish  canbus/indian/ota/status = "Running <FW_VERSION>"
          canbus/indian/meta       = {..., "fw":"<FW_VERSION>"}
```

Everything the device reports flows back into openHAB items so the UI shows live
progress and the confirmed running version.

### Why HTTP pull, not espota/ArduinoOTA push?

The device usually rides on a phone hotspot / cellular link behind NAT. The old
**espota** (ArduinoOTA, UDP port 3232) needs the *server* to open an inbound
connection back to the device — impossible through NAT, so it always timed out
("No response from device"). **HTTP pull** is the reverse: the *device* opens an
outbound connection to fetch the image, which sails straight through NAT.

> ⚠️ Use the openHAB server's **LAN IP** in the URL, not `openhab.local`. The
> Arduino `WiFiClient` has **no mDNS resolver**, so a `.local` hostname fails
> with `HTTP error: connection refused` / `HTTP_UPDATE_FAILED (-1)`.

---

> ⚠️ **First flash of a build that changes the radio stack goes over USB, not
> OTA.** OTA is delivered over WiFi, so a change that breaks the WiFi link takes
> the recovery path with it. This applies to anything touching WiFi/BLE
> coexistence — notably the first firmware with `ENABLE_BLE 1` (see the "BLE
> phone link" section in [README.md](README.md)). Confirm it on the bench with
> `pio run -e sniffer-t2can -t upload -t monitor`, then go back to OTA for the
> iterations after that.

---

## Doing an update (normal workflow)

1. **Bump the version** in `src/config.h`:
   ```cpp
   #define FW_VERSION "2026.08.16-3"
   ```
2. **Build** on the openHAB server (matches the deployed libraries — see the
   library-version note below):
   ```bash
   cd /etc/openhab/indian-canbus
   ~/.platformio/penv/bin/pio run
   ```
3. **Deploy** the image to the web root that the device downloads from:
   ```bash
   cp .pio/build/sniffer/firmware.bin \
      /etc/openhab/html/indian-canbus-firmware.bin
   ```
   Verify it's served:
   ```bash
   curl -s -o /dev/null -w "HTTP %{http_code} size=%{size_download}\n" \
     http://192.0.2.10:8080/static/indian-canbus-firmware.bin
   ```
4. **Trigger the update** — press **🔄 Update Now** in the sitemap
   (Indian CAN Bus → Firmware), or from the console:
   ```bash
   echo "openhab:send CanBus_OTA update" \
     | /usr/share/openhab/runtime/bin/client -p habopen
   ```
5. **Watch it happen** in the *Firmware* frame:
   - `OTA Status` → `Requested — waiting for device...`
   - → `Starting download...` → `Downloading 10%…100%`
   - → `Running 2026.08.16-3` after the reboot
   - `Running Version` (CanBus_FW_Version) flips to the new version too.

Download of ~1.26 MB takes ~10 s on LAN, longer on a slow cellular link.

> If the UI still shows the *old* version after the reboot, it's browser cache.
> Hard-refresh with **Ctrl+Shift+R**. The item states (and MQTT) are the truth.

---

## openHAB pieces

| File | Role |
|------|------|
| `automation/js/canbus-ota.js` | JSRule: on `CanBus_OTA` command `update`, publishes `update` to `canbus/indian/ota` via `actions.Things.getActions('mqtt', 'mqtt:broker:broker').publishMQTT(...)`. Sets `Requested — waiting for device...`; then the ESP32 drives the status line. |
| `things/canbus.things` | MQTT channels `otaStatus` (`stateTopic canbus/indian/ota/status`) and `fw` (JSONPATH `$.fw` from `.../meta`). |
| `items/canbus.items` | `CanBus_OTA` ← `otaStatus` channel (live status). `CanBus_FW_Version` ← `fw` channel (running version). |
| `sitemaps/myhouse.sitemap` | *Firmware* frame: Running Version, OTA Status, **🔄 Update Now** switch, WiFi IP. |

**Why a JS rule and not an outbound item binding?** An outbound-only MQTT item
binding never updates the item's own state, so DSL `changed`/`received command`
triggers fired unreliably and the command often never reached the broker. The
JSRule on the command is deterministic and logs each step.

---

## Firmware pieces (`src/`)

| Symbol | Where | Role |
|--------|-------|------|
| `OTA_FIRMWARE_URL` | `config.h` (fallback in `main.cpp`) | HTTP URL of the image. Must be the server **IP**, e.g. `http://192.0.2.10:8080/static/indian-canbus-firmware.bin`. |
| `FW_VERSION` | `config.h` (fallback `"dev"` in `main.cpp`) | Version string, published in `/meta` and as `Running <ver>`. |
| `onMqttMessage()` | `main.cpp` | On `canbus/indian/ota == "update"`: registers `httpUpdate.onProgress`, `rebootOnUpdate(true)`, runs `httpUpdate.update()`, publishes result. |
| `publishOtaStatus()` | `main.cpp` | Retained publish to `canbus/indian/ota/status` (also mirrored to `/debug`). |
| `mqttConnect()` | `main.cpp` | On connect, subscribes to `.../ota` and publishes `Running <FW_VERSION>`. |
| WiFi failover | `main.cpp` `wifiConnect()` | Tries SSID1→2→3. Calls `WiFi.disconnect(true)` + delay **before each** attempt (fixes `sta is connecting, cannot set config` / `ESP_ERR_WIFI_STATE 0x3006` that previously broke fallback). |

The `#ifndef` fallbacks for `OTA_FIRMWARE_URL` / `FW_VERSION` in `main.cpp` mean
the firmware still builds on a machine whose (git-ignored) `config.h` predates
the OTA block — the real values still come from `config.h` when present.

---

## MQTT topics

| Topic | Dir | Payload |
|-------|-----|---------|
| `canbus/indian/ota` | openHAB → ESP32 | `update` (trigger) |
| `canbus/indian/ota/status` | ESP32 → openHAB | `Running <ver>` / `Starting download...` / `Downloading NN%` / `OK — rebooting` / `FAILED (n): ...` (retained) |
| `canbus/indian/meta` | ESP32 → openHAB | JSON incl. `"fw":"<ver>"` + `"ip"` (retained) |
| `canbus/indian/debug` | ESP32 → openHAB | Free-text log incl. `[ota] ...` lines |

**Watch a live update from the server:**
```bash
/etc/openhab/.venv/bin/python - <<'PY'
import ssl, time, paho.mqtt.client as mqtt
c=mqtt.Client(client_id="ota-watch"); c.username_pw_set(MQTT_USER, MQTT_PASS)
c.tls_set(cert_reqs=ssl.CERT_NONE); c.tls_insecure_set(True)
c.on_message=lambda cl,u,m: print(time.strftime('%H:%M:%S'), m.topic, m.payload.decode())
c.on_connect=lambda cl,u,f,rc,p=None:[cl.subscribe(t) for t in
  ("canbus/indian/ota/status","canbus/indian/debug","canbus/indian/status","canbus/indian/meta")]
c.connect("mqtt.example.com",8883,30); c.loop_forever()
PY
```

---

## First flash / emergency recovery (USB cable)

OTA only works once a *good* image (correct IP URL + WiFi fix) is on the device.
For the very first flash, or if a bad image bricks OTA, flash over USB with the
dedicated cable environment (does not touch the espota config):

```bash
# On the machine with the ESP32 plugged in:
git pull                                   # get latest src + platformio.ini
rm -rf .pio                                # if you copied .pio from another host
pio run -e sniffer-usb -t upload -t monitor \
  --upload-port /dev/cu.usbserial-XXXX     # your serial port (pio device list)
```
`[env:sniffer-usb]` extends `[env:sniffer]` with `upload_protocol = esptool`.

---

## ⚠️ Library-version caveat (build on the server)

The deployed OTA image **must be built on the openHAB server**, whose PlatformIO
has arduino-esp32 **3.3.9** (WiFi/HTTPUpdate/WiFiClientSecure `3.3.9`). A laptop
with the older **2.0.0** libraries produces a *different, smaller* binary
(~1.03 MB vs ~1.26 MB). Always build + deploy the OTA `.bin` from the server so
what you serve matches what you tested. USB flashing from a laptop is fine for
emergency recovery, but treat the **server build as the source of truth** for
OTA.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Status stuck, device never downloads | Command never reached MQTT | Check `canbus-ota.js` log line `published "update" to canbus/indian/ota`; confirm broker `mqtt:broker:broker` is ONLINE |
| `HTTP_UPDATE_FAILED (-1): connection refused` | Old firmware still has `openhab.local`, or wrong IP | Reflash a build with `OTA_FIRMWARE_URL` = server IP; verify `curl` serves the `.bin` |
| Device offline, never reconnects on the bench | SSID1 absent + old WiFi-failover bug | Reflash the WiFi-failover fix, or move device where an SSID from `config.h` exists |
| UI shows old version after OTA | Browser cache | **Ctrl+Shift+R**; trust the item states / MQTT |
| Downloads then boot-loops | Bad/corrupt image | USB recovery flash (see above) |

---

## Version history

| Version | Notes |
|---------|-------|
| `2026.08.17-1` | **CAN HAL abstraction** — one firmware now runs on both LilyGO T-CAN485 (ESP32, native TWAI) and T-2CAN (ESP32-S3 + MCP2518FD/SPI), selected by `CAN_BACKEND` in `config.h`. The CAN controller + pin map moved out of `main.cpp` into `can_hal_twai.cpp` / `can_hal_mcp.cpp` behind a board-agnostic `CanFrame` interface. **No behaviour change on T-CAN485**: byte-for-byte identical decode/publish, verified live (250 kbps auto-detect, full `/state` telemetry). MCP2518FD backend compiled-out on the TWAI build. |
| `2026.08.16-4` | PRODUCTION `/state` publish cap raised to 5 Hz (`STATE_PUBLISH_INTERVAL_MS` 200 ms) — safe because production sends one ~390 B JSON per cycle, not the per-ID fan-out DISCOVERY does. DISCOVERY stays at 1 Hz. |
| `2026.08.16-3` | State heartbeat: republish `/state` at least every 30 s even when no CAN value changed, so the UI never looks frozen while parked. |
| `2026.08.16-2` | Verified end-to-end wireless OTA (live progress + version report). |
| `2026.08.16-1` | First image with IP URL, WiFi-failover fix, `FW_VERSION`, live OTA status. |
| (pre-versioned) | ArduinoOTA/espota era — push OTA never worked through NAT; hardcoded `openhab.local` URL failed (no mDNS on `WiFiClient`). Superseded by HTTP pull. |

## Future enhancements

- [x] MQTT-triggered OTA from openHAB (`canbus/indian/ota`) — **done** (`canbus-ota.js`)
- [x] Firmware version item in openHAB (`CanBus_FW_Version`) — **done**
- [x] Live progress in the UI (`canbus/indian/ota/status`) — **done**
- [ ] Rollback to previous version via MQTT command
- [ ] Serve a versioned filename (`indian-canbus-<ver>.bin`) + symlink for audit trail


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
      ▼  (ESP32 subscribed; since 2026-09-27 the network task in net.cpp)
 httpUpdate.update("http://192.0.2.10:8080/static/indian-canbus-firmware.bin")
      │            └─ progress ──►  canbus/indian/ota/status  = "Downloading NN%"
      ▼
 flash + reboot
      │
      ▼  (on boot, first broker connection, net.cpp)
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
> phone link" section in [README.md](../README.md)). Confirm it on the bench with
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
   cd /etc/openhab-firmware/indian-canbus
   ~/.platformio/penv/bin/pio run -e sniffer-t2can
   ```
   The environment name matters. The board moved to the LilyGO T-2CANFD and
   `sniffer-t2can` is the only environment that still builds; a bare `pio run`
   picks up whatever is listed first and writes it to a different path than the
   one step 3 copies from.
3. **Deploy** the image to the web root that the device downloads from:
   ```bash
   cp .pio/build/sniffer-t2can/firmware.bin \
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

Download of ~1.38 MB takes ~10 s on LAN, longer on a slow cellular link.
Measured 2026-09-05: from trigger to `Running <version>` was under 25 seconds.

> If the UI still shows the *old* version after the reboot, it's browser cache.
> Hard-refresh with **Ctrl+Shift+R**. The item states (and MQTT) are the truth.

### If the phone will not stay connected afterwards

**Forget the device in Android's Bluetooth settings — not just in the app — and
pair again with the passkey.**

Android caches the GATT service table for every bonded device and does not
re-read it on reconnect. When the ESP32 reboots mid-connection, which is exactly
what an OTA does, that cache can be left stale, and the phone then writes to
handles that no longer mean what it thinks. The symptom is specific:

```
CCCD write failed (133) — pairing may be required
Disconnected (status 133)   /  (status 8)  /  (status 147)
```

connecting fine, negotiating MTU 517, and dropping within seconds to a minute.
Once, MTU came back as 23.

This is Android's cache, not a firmware fault — nothing here deletes bonds, and
`setSecurityAuth` has bonding on. Forgetting the device is what clears it.

Seen 2026-09-05 after flashing 2026.09.05-56: fifteen minutes of connect-and-drop
across six attempts. Re-pairing fixed it outright, and the link then held through
ignition on *and* ignition off with no dropouts. Worth knowing because the
obvious suspects — the new firmware, and the bike's ignition being off — were
both wrong, and the ignition theory in particular looked convincing: the one long
connection of the evening happened to span the only ignition-on window.

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
| `runHttpOta()` | `net.cpp` (was `onMqttMessage()` in `main.cpp` until 2026-09-27) | On `<base>/ota == "update"`: registers `httpUpdate.onProgress`, `rebootOnUpdate(true)`, runs `httpUpdate.update()` **in the network task**, so the bus and the phone keep running during the download; `netBusy()` holds deep sleep off meanwhile. |
| `publishOtaStatus()` | `net.cpp` | Retained publish to `<base>/ota/status` (also mirrored to `/debug`). |
| `mqttConnectMaybe()` | `net.cpp` | On connect, subscribes to `.../ota` and publishes `Running <FW_VERSION>` once per boot. |
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

## Rolling back (no cable needed)

Every image that has been flashed is kept, exactly as served, in
`releases/indian-canbus-firmware-<version>.bin` (git-ignored; binaries do not
belong in history). The source it was built from carries the tag
`known-good-<version>`. Both were introduced 2026-09-14, before the first change
made after the Zealand ride, because **USB flashing means taking the fairing
off** — the board is built into the motorcycle — so OTA has to be able to undo
itself.

To go back to the previous image:

```bash
cd /etc/openhab-firmware/indian-canbus
cp releases/indian-canbus-firmware-2026.09.14-2.bin /etc/openhab/html/indian-canbus-firmware.bin
md5sum /etc/openhab/html/indian-canbus-firmware.bin     # 3b340650feb5ade42d0dd647cce4f119
echo "openhab:send CanBus_OTA update" | /usr/share/openhab/runtime/bin/client -p habopen
```

Then watch `OTA Status` until it reads `Running 2026.09.14-2`. To go back in
*source*: `git checkout known-good-2026.09.14-2 -- src/main.cpp`, rebuild, deploy.

**What OTA cannot undo:** an image that crashes before the network task has connected,
or that never joins WiFi, cannot receive the next `update`. Every change since
2026-09-14 has therefore been reviewed against one question first — *does any
of it run before the network is up, and can it crash there?* — and the answer
for each is written into the commit. Keep doing that.

**Automatic rollback: half there (checked 2026-10-03).** The bootloader *is* built
with `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y` (the Arduino core's esp32s3
sdkconfig), so a new image boots as "pending verify" and any reset before it is
marked valid returns to the previous one. But the core marks it valid itself, in
`initArduino()`, before `setup()` runs, because our firmware does not override the
weak `verifyRollbackLater()`. In practice: an image that dies before Arduino starts
rolls back; one that boots and then never reaches the broker does not. Closing that
gap is IDEAS D7 step 1: return `true` from `verifyRollbackLater()`, call
`esp_ota_mark_app_valid_cancel_rollback()` after the first broker connection, and
reboot (which rolls back) if that has not happened within a few minutes.

**The archive above had lapsed:** nothing between 2026.09.15-1 and 2026.09.27-3 was
kept. 2026.09.27-3, the image on the bike, was archived and tagged
`known-good-2026.09.27-3` (commit `aa0c703`, md5 `8853b295b17b22818b19743e0bdbaf0c`)
on 2026-10-03. Archive every image you OTA to the bike, at the moment you deploy it.

### Before pressing Update

1. `curl -s -o /dev/null -w "%{http_code} %{size_download}\n" http://192.0.2.10:8080/static/indian-canbus-firmware.bin` — 200, and the size of the image you just copied.
2. `md5sum` of the served file equals `md5sum` of `.pio/build/sniffer-t2can/firmware.bin`.
3. The previous image is in `releases/` and its md5 is written next to its tag.
4. The bike is on home WiFi. If the new image is wrong, the rollback OTA still has to reach it.
5. **The ignition is on, proven by the bus.** `efmsg` in the health JSON
   (`canbus/<base>/bus/health`, `CanBus_Bus_CleanMsgs` / `CanBench_Health` in openHAB) is the controller's error-free message count, so it only
   climbs while frames arrive. Read it twice, 30 s apart. If it climbed, the
   ignition is on. Nothing else proves it:
   - `status = online` and a fresh `meta` only prove WiFi and MQTT.
   - The `state` JSON goes on republishing the last decoded values (odometer,
     battery) after the bus has gone quiet.
   - `quiet_s` in `sleep/status` is a snapshot from the last connect or `sleep/en`
     command, not a live value (see [SLEEP.md](SLEEP.md)).
   - openHAB logs a health event only when the payload *changes*. More than 30 s
     with no new health line in `events.log` means the bus is silent.

   Losing power mid-download does not brick the board. The image goes to the
   inactive app slot, and the boot partition switches over only after
   `Update.end()` has verified it, so the board boots the old image again. What it
   does cost is a half-finished OTA that nobody can read: `Downloading 90%` stays on
   the item, and you cannot tell whether the board took the image. Say how long the
   ignition must stay on before sending: about 35 s of download plus 15 s for the
   reboot, on home WiFi at RSSI around -87.
6. **Know which identity the board has, and which image the URL serves.**
   `bench-canfdmc` and `sniffer-t2can` share this URL. See "Swapping in a new board"
   in [FLASHING.md](FLASHING.md).
7. **Send `update` once.** If the call that sent it was interrupted, read
   `events.log` for the command and for `Downloading` lines before sending again.
   A second `update` that nobody meant to send cost one extra identity round trip
   on 2026-10-03.

## Automatic rollback (branch `rollback-bench`, on the bench first)

Written 2026-10-04, tested on bench board #0002 before anything of it goes near the
bike. What it does, in `src/rollback.cpp`:

- `verifyRollbackLater()` returns true, so the Arduino core no longer marks every
  image valid before `setup()`.
- An image that arrived by OTA boots `pending`. The first broker connection marks
  it `valid` (`ota/status` becomes `Running <ver> - verified`, meta `ota_state`
  `valid`).
- No broker within `ROLLBACK_GRACE_MS` (5 min; 2 min in the test build): the image
  marks itself invalid and reboots, and the bootloader starts the previous one.
  Any other reset while pending - power cut, deep-sleep wake - makes the
  bootloader do the same (state `aborted`). Deep sleep is therefore held off
  while pending.
- A USB-flashed image has no pending state and is never touched.
- `meta.ota_other` shows the other slot: `invalid` or `aborted` there is the
  footprint of a roll-back.

Environments: `bench-rollback` (FW 2026.10.04-rb1, honest) and `bench-rollback-bad`
(2026.10.04-rb9-bad: sees the broker and ignores it, grace 120 s). Both are the
bench identity and pull `/static/indian-canbus-bench-firmware.bin` - the URL is
split by identity in `config.h` since this branch, so a bench OTA can no longer
fetch the bike's file. Deploy with `tools/deploy-bench-ota.sh <env>`; command with
`CanBench_OTA update`.

### The test, in order

| # | do | expect |
|---|----|--------|
| 1 | USB flash rb1 (kit `~/canfd-flash-rollback-rb1.zip`) | serial `[rollback] running app0, state none`; `CanBench_Meta.ota_state` = `none` |
| 2 | rebuild with `-D FW_VERSION=\"2026.10.04-rb2\"`, deploy, `update` | boots `pending`, then `valid` within a minute; `ota/status` `Running 2026.10.04-rb2 - verified` |
| 3 | deploy `bench-rollback-bad`, `update` | boots `pending`, serial/debug `TEST: broker reached and ignored`; after 120 s `ROLLBACK: no broker ...` on `ota/status`; then `Running 2026.10.04-rb2` again, meta `reset: sw`, `ota_other: invalid` |
| 4 | deploy `-bad` again, `update`; pull 12 V ~30 s after it boots; power up | rb2 boots, `ota_other: aborted` (the bootloader's own roll-back) |
| 5 | rb2 stays `valid` across a power cycle and a deep-sleep wake | `ota_state: valid` after each |

**Results, 2026-10-04, board #0002 without an antenna (RSSI -88 to -91):**

| # | result |
|---|--------|
| 1 | passed 08:22. Serial `running app0, state none, other slot none`; meta `ota_state: none`, `ota_other: none` |
| 2 | passed 08:26. `update` 08:25:49, download 11 s, `Running 2026.10.04-rb2` and `- verified` both at 08:26:38; meta `valid` / `none` |
| 3 | passed 08:33. `update` 08:30:10, `Running 2026.10.04-rb9-bad` 08:30:59, `ROLLBACK: no broker within 120 s, returning to the previous image` 08:32:24 (120 s after the boot, not after the broker connection: the weak link took 35 s to associate), `Running 2026.10.04-rb2` 08:33:24; meta `valid`, `ota_other: invalid`, `reset: sw` |
| 4 | the roll-back passed, the footprint did not match. `update` 08:42:46, `Running 2026.10.04-rb9-bad` 08:43:33, 12 V pulled about 08:43:50 and put back, `Running 2026.10.04-rb2` 08:44:23; meta `valid`, `reset: poweron`, but `ota_other: none` where `aborted` was expected. `none` is either no otadata entry for app0 or state UNDEFINED (`rollbackOtherState()` cannot tell them apart). The otadata dump (`read-flash 0xe000 0x2000`, 08:48) shows why: sector 0, which held the trial image's entry, is erased (all `FF`), sector 1 holds seq 2 = app1, state 2 = VALID, crc `55F63774` (correct for seq 2). The bootloader rewrites a pending entry as erase-then-write; the erase landed and the write did not, so power went again inside that window (the cut itself or the re-plug, the dump cannot say which). The outcome is still the safe one: the other sector is never touched, so the previous image boots. `ota_other: none` after a power cut is therefore a third legitimate footprint, next to `invalid` and `aborted` |
| 5 | passed 09:06. Power cycle: rb2 came up `valid` after both cuts of step 4 (`reset: poweron`). Deep sleep: rb3 (the meta fix, and for the bench only `SLEEP_QUIET_MS` 60 s and `SLEEP_BACKSTOP_S` 120 s, there being no bus to wake it) went in by OTA, `- verified` 09:02:03; sleep enabled 09:02:52, asleep 09:03:00, awake again with `wake: timer`, `reset: deepsleep`, `ota_state: valid`; sleep switched off again 09:06:31 |

Step 4 again, 08:56, this time the 12 V plug pulled (in the first run the supply was
probably switched off instead, by accident; its output sags slowly): `update` 08:56:25, `Running 2026.10.04-rb9-bad`
08:58:08, plug out about 08:58:20, in again after 10 s, `Running 2026.10.04-rb2` 08:58:58; meta
`valid`, `ota_other: aborted`, `reset: poweron`. **Passed as written.** So a clean cut leaves
`aborted`, a sagging supply can leave `none`, and both boot the previous image.

Open after step 4: the boot following the otadata read-out reported `reset: panic` (08:48:45,
rb2, stable since). The same esptool reset gave `unknown` at 08:22 and at 08:53, so this was a
real panic handler run. The coredump partition (`0x7f0000`, `0x10000`), read at 08:53, is
blank: nothing to decode. Seen once, not explained; watch `reset` in meta.

Found on the way: the meta JSON was cut at 260 bytes (08:55, `...,"ble_gap_max_ms":53` and no
closing brace). The two `ota_*` fields had used up the slack in `char buf[260]`. Buffer 400 and
a tripwire that refuses to publish a cut payload, from rb3 on. Master does not have the fields
and is not affected; this must be in before the branch merges.

All five passed. The board was left on rb3 with deep sleep off, and the served bench file is
rb3 (md5 `89ae0745005313df17dd38c76b7dbf7c`). rb3's short sleep timers are a bench build flag,
not in the source. Binaries and ELFs of rb2 and rb3 are in `releases/` (git-ignored); a build
with other `PLATFORMIO_BUILD_FLAGS` wipes `.pio/build`, so copy the ELF out first.

Needs the board on the home LAN (the OTA pulls from 192.0.2.10) and the serial
monitor open for steps 1 and 3. Without an antenna the WROOM-1U reaches only a
metre or so; put it next to an AP.

Reaching a board that is away from home is the next section.

## OTA over the MQTT link (command `mqtt`, branch `rollback-bench`)

`update` pulls the image over HTTP from the server's LAN address, so it works at
home and nowhere else, and the image is not to be served to the internet: it
carries the compiled WiFi and MQTT credentials. The broker is reached from
anywhere the board has a network, over TLS. So `mqtt` on `<base>/ota` brings the
image the same way. Written and proven 2026-10-04 (IDEAS D7 step 2).

The board pulls (`src/net.cpp`, "OTA over the MQTT link"); openHAB answers
(`automation/js/canbus-ota-mqtt.js`, requests arrive on a trigger channel of the
board's thing):

| board, on `<base>/ota/req` | server |
|---|---|
| `info` | `<base>/ota/info`: `<size> <md5>` (the file is read from disk here) |
| `<md5> <offset> <count> <len>` | `<base>/ota/chunk`, `<count>` times: 4 bytes offset (LE) + `<len>` bytes |

- Window 8 x 2048 bytes. A chunk with any offset but the next one is dropped, so a
  request repeated after a timeout (10 s) or a reconnect cannot damage the image.
- The same image as the running one (md5 against `ESP.getSketchMD5()`) answers
  `No update available` without a download.
- `Update.end()` checks the md5 of the whole image before the boot partition is
  switched. No data for 240 s: `FAILED: ...`, the running image stays.
- The file changed on the server half-way: `ERR ...` on `ota/info`, the download stops.
- After the reboot the roll-back rule above applies as for any OTA.
- The image is the file `update` serves (`tools/deploy-bench-ota.sh` for the bench).
  Each board is listed in `BOARDS` in the rule with its own file, so a board cannot
  be handed another identity's image. `CanBus_OTA` / `CanBench_OTA` take the command.

**Results, 2026-10-04, board #0002, 1 405 584 bytes:**

| # | what | result |
|---|------|--------|
| 1 | rb4 (first image with the receiver) by `update` on the LAN | `- verified` 09:21:36 |
| 2 | rb5 by `mqtt`, office WiFi (-86, no antenna) | `mqtt` 09:22:05, 100 % 09:22:40 (35 s), `Running 2026.10.04-rb5 - verified` 09:23:13 |
| 3 | rb5 offered again | `No update available` after 1 s |
| 4 | rb6 by `mqtt`, **phone hotspot** (the board on the hotspot's own address, -58) | `mqtt` 09:31:03, 100 % 09:31:31 (28 s), `Running 2026.10.04-rb6 - verified` 09:31:46, still on the hotspot |
| 5 | rb7 by `mqtt`, hotspot, after Springfield was added to the rule | `mqtt` 09:40:52, 100 % 09:41:21 (29 s), `- verified` 09:41:39 |

Not tested yet: a link that drops in the middle of a download.

### First time on the bike (2026.10.04-1)

The bike's image has no MQTT receiver yet, so the first one goes by `update` on the
home LAN. What arrives first is the safety net itself.

1. Ignition on, proven from the bus ("Before pressing Update" above), and the owner's OK.
2. `cp releases/indian-canbus-firmware-2026.10.04-1.bin /etc/openhab/html/indian-canbus-firmware.bin`,
   md5 `091547a2801b20e8720c7b1af21a5d31`.
3. One `update` on `CanBus_OTA`. Expect `Downloading ...`, `Running 2026.10.04-1`, then
   `Running 2026.10.04-1 - verified` within a minute; meta `ota_state: valid`.
   `ota_other` then shows the state of the slot holding 2026.09.27-3.
4. If it says `Running 2026.09.27-3` again instead, the new image did not reach the
   broker in five minutes and went back by itself: nothing is lost, read `debug`.
5. Afterwards: tag `known-good-2026.10.04-1`, and try one `mqtt` with the phone hotspot.

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
(~1.03 MB vs ~1.38 MB). Always build + deploy the OTA `.bin` from the server so
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
| First `update` after a wake fails at once (`FAILED (-11): HTTP error: read Timeout`) or crawls and dies | Not understood (seen three times on 2026-09-27, on the old and the new code path); the second `update` a minute later ran in 20-40 s every time | Send `update` again. The guard restarts the chip if a download stalls for 300 s, so a failed first attempt costs at most five minutes. |
| Downloads then boot-loops | Bad/corrupt image | USB recovery flash (see above) |

---

## Version history

| Version | Notes |
|---------|-------|
| `2026.10.04-1` | **Automatic rollback and OTA over the MQTT link.** An OTA image is on trial until it has reached the broker (5 min), otherwise the previous image boots again; `mqtt` on `<base>/ota` brings the image over the MQTT/TLS link, so an update no longer needs the LAN. Meta gains `ota_state` / `ota_other`, and its buffer goes 260 -> 400 with a tripwire. Both proven on bench board #0002 on 2026-10-04 (sections above). Built and archived (`releases/indian-canbus-firmware-2026.10.04-1.bin`, md5 `091547a2801b20e8720c7b1af21a5d31`); **not on the bike yet** - it waits for the ignition. Rollback image: `releases/...-2026.09.27-3.bin`, md5 `8853b295b17b22818b19743e0bdbaf0c`. |
| `2026.09.27-3` | The `asleep` marker is given 250 ms to leave the radio after `netFlush()`; on 27-2 the chip went down with it still in the TCP buffer and openHAB got only the last will. |
| `2026.09.27-2` | **The network in its own task** (`src/net.cpp`): WiFi, SNTP, MQTT and OTA on core 0 behind two queues; `loop()` never waits for the broker. Measured with the broker blocked: loop gap 5019 → 4 ms, BLE gap 6656 → 3 ms, app uninterrupted. The network is now chosen by scan and signal, not by which SSID worked last. `ENABLE_MQTT 0` compiles again; env `sniffer-t2can-nomqtt` keeps it that way. |
| `2026.09.27-1` | Two gauges in `meta`: `loop_max_ms` and `ble_gap_max_ms`, worst of the last 30 s, so a stall can be measured from the garage instead of felt on the road. |
| `2026.09.15-1` | **The failover to the hotspot works now.** The association always did; the broker was being looked up in lwIP's DNS cache, which still held the home resolver's answer (`192.0.2.10`, TTL 3600) for an hour after leaving the garage, so every connect went to a LAN address over cellular and timed out. `wifiConnect()` now flushes the cache (`dns_clear_cache()` via `esp_netif_tcpip_exec`, in the lwIP thread) after every association. Also: MQTT retry stamped at the *end* of the attempt and 15 s apart (the 5 s stamp-before-connect of `-3` expired during the 5 s timeout, so loop() was blocked back to back and BLE died whenever the hotspot was up); and `logEvent()` lines written while offline are buffered (24 lines, repeats collapsed) and published with the next connection, so the road finally reports. **Proven on a test ride the same afternoon**: 55 s from link loss to online over the hotspot, broker resolved to the public address, 11 min on cellular with no drop, and back to home WiFi on return. Rollback image: `releases/…-2026.09.14-4.bin`, md5 `72d6c6239db2cc460333e3b66df35b41`. |
| `2026.09.14-4` | Range to empty decoded as 16 bits (`b[3] | b[4]<<8`); it wrapped at 256 and a full tank read 85. `probe/throttle` prints `b5`. Proven at the 2026-09-15 fill-up: 254 → 257 → … → 272 without a wrap. Rollback image: `releases/…-2026.09.14-3.bin`. |
| `2026.09.14-3` | State JSON buffer 900 → 1536 with a tripwire (the 900-byte cut froze openHAB items for ~6 h on the Zealand ride, silently); TLS connect/handshake bounded at 5 s / 10 s (were 30 s / 120 s, blocking BLE+CAN 30 s of every 35 with the hotspot up and cellular down); one 5-s MQTT retry cadence for every caller; CAN drained and BLE fed inside the WiFi and NTP waits via `drainCan()` (no more frozen needle during a scan). Rollback image: `releases/…-2026.09.14-2.bin`. |
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


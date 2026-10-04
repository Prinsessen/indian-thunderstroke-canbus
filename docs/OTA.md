# Indian CAN Bus — Firmware OTA Update

The ESP32 firmware updates **over the air from two buttons in openHAB**:

- **🔄 Update Now** (command `update`): the board downloads the image over plain
  HTTP from the openHAB web server. **Home LAN only** — the URL is the server's
  LAN address.
- **📡 Update via MQTT** (command `mqtt`): the board pulls the same image in
  chunks over the MQTT/TLS link it already has. Works **wherever the board
  reaches the broker**, a phone hotspot included.

Either way the board flashes itself, reboots and reports the running version
back, and the new image is **on trial until it has reached the broker**: if it
does not within five minutes, or the board resets before that, the previous
image boots again by itself.

No cable. No laptop. No mDNS. No inbound connections to the device.

The bike's board is CANFD-MC rev 1.0 #0001 (base topic `canbus/springfield`,
items `CanBus_*`); bench board #0002 has its own identity (`canbus/bench`,
`CanBench_*`) and its own image file. `<base>` below is either one.

---

## How it works (the whole chain)

```
 openHAB UI  ──"update" cmd──►  CanBus_OTA item
      │
      ▼  (automation/js/canbus-ota.js)
 publishMQTT  ──►  MQTT topic  canbus/springfield/ota  = "update"
      │
      ▼  (ESP32 subscribed; since 2026-09-27 the network task in net.cpp)
 httpUpdate.update("http://192.0.2.10:8080/static/indian-canbus-firmware.bin")
      │            └─ progress ──►  canbus/springfield/ota/status  = "Downloading NN%"
      ▼
 flash + reboot, image "pending"
      │
      ▼  (on boot, first broker connection, net.cpp + rollback.cpp)
 publish  canbus/springfield/ota/status = "Running <FW_VERSION>"
                                    then  "Running <FW_VERSION> - verified"
          canbus/springfield/meta       = {..., "fw":"<FW_VERSION>", "ota_state":"valid"}
```

With `mqtt` instead of `update` the first and last steps are the same and the
download in the middle goes over the broker: the board asks on `<base>/ota/req`
and `automation/js/canbus-ota-mqtt.js` answers on `<base>/ota/info` and
`<base>/ota/chunk` (section "OTA over the MQTT link").

Everything the device reports flows back into openHAB items so the UI shows live
progress and the confirmed running version.

### Why HTTP pull, not espota/ArduinoOTA push?

The old **espota** (ArduinoOTA, UDP port 3232) needs the *server* to open an
inbound connection back to the device — impossible through NAT, so on a phone
hotspot it always timed out ("No response from device"). **HTTP pull** is the
reverse: the *device* opens an outbound connection to fetch the image. It needs
no inbound connection, but the URL is a LAN address, so it works at home and
nowhere else; away from home the image comes over the MQTT link (`mqtt`).

> ⚠️ Use the openHAB server's **LAN IP** in the URL, not `openhab.local`. The
> Arduino `WiFiClient` has **no mDNS resolver**, so a `.local` hostname fails
> with `HTTP error: connection refused` / `HTTP_UPDATE_FAILED (-1)`.

---

> ⚠️ **A build that changes the radio stack goes to the bench board first.** OTA
> is delivered over WiFi, so a change that breaks the WiFi link takes the
> recovery path with it. This applies to anything touching WiFi/BLE coexistence
> (see the "BLE phone link" section in [README.md](../README.md)). Try it on bench
> board #0002 (`bench-canfdmc`, "The bench board" in [FLASHING.md](FLASHING.md)),
> where USB is at hand. On the bike the safety net is the automatic rollback: an
> image that cannot reach the broker goes back after five minutes.

---

## Doing an update (normal workflow)

1. **Bump the version** in `src/config.h`:
   ```cpp
   #define FW_VERSION "<version>"       // e.g. 2026.10.04-2
   ```
2. **Build** on the openHAB server (matches the deployed libraries — see the
   library-version note below):
   ```bash
   cd /etc/openhab-firmware/indian-canbus
   ~/.platformio/penv/bin/pio run -e sniffer-t2can
   ```
   The environment name matters. The bike's board (CANFD-MC rev 1.0, same pin map
   as the LilyGO T-2CANFD it replaced) is built from `sniffer-t2can`, the bench
   board from `bench-canfdmc`; a bare `pio run` builds every environment in
   `platformio.ini`.
3. **Deploy** the image to the web root that the device downloads from, and
   **archive it** under its version at the same moment:
   ```bash
   cp .pio/build/sniffer-t2can/firmware.bin \
      /etc/openhab/html/indian-canbus-firmware.bin
   cp .pio/build/sniffer-t2can/firmware.bin \
      releases/indian-canbus-firmware-<version>.bin
   ```
   Verify it's served:
   ```bash
   curl -s -o /dev/null -w "HTTP %{http_code} size=%{size_download}\n" \
     http://192.0.2.10:8080/static/indian-canbus-firmware.bin
   ```
   (A bench build goes out with `tools/deploy-bench-ota.sh <env>` instead, which
   writes the bench file only.)
4. **Check the list in "Before pressing Update"** — the ignition above all.
5. **Trigger the update** in the sitemap (SpringCommand → **Board & Firmware**).
   Which button depends on where the bike is:

   | | **🔄 Update Now** (`update`) | **📡 Update via MQTT** (`mqtt`) |
   |---|---|---|
   | image travels over | HTTP from the server's LAN address | the MQTT/TLS link, in 2 kB chunks |
   | works | on the home LAN only | on any network that reaches the broker |
   | time for 1.4 MB (2026-10-04) | 10 s | 28–40 s |

   At home either works; `update` is faster. Away from home only `mqtt` works.
   Over `mqtt` a board declines the image it already runs (`No update
   available`). Or from the console:
   ```bash
   echo "openhab:send CanBus_OTA update" \
     | /usr/share/openhab/runtime/bin/client -p habopen
   ```
6. **Watch it happen** in the *Board & Firmware* frame:
   - `OTA Status` → `Requested — waiting for device...`
   - → `Starting download...` → `Downloading 10%…100%`
   - → `Running <version>` after the reboot, then `Running <version> - verified`
     once the new image has reached the broker (within a minute)
   - `Running Version` (CanBus_FW_Version) flips to the new version too.
   - `Running <old version>` instead means the new image did not reach the
     broker and went back by itself; read `<base>/debug`.
7. **Tag the source** `known-good-<version>` once it reads `- verified`.

The image is about 1.4 MB. Measured 2026-10-04 on the bike: `update` on the home
LAN took 10 s to download and 30 s from the command to `- verified`; `mqtt` over
a phone hotspot took 40 s and 55 s.

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
| `automation/js/canbus-ota.js` | JSRule: on `CanBus_OTA` command `update` or `mqtt`, publishes that command to `canbus/springfield/ota` via `actions.Things.getActions('mqtt', 'mqtt:broker:broker').publishMQTT(...)`. Sets `Requested — waiting for device...`; then the ESP32 drives the status line. |
| `automation/js/canbus-ota-mqtt.js` | JSRule for the `mqtt` path: triggered by the boards' `otaReq` channels, answers `<base>/ota/req` with the image's size and md5 on `<base>/ota/info` and with the chunks on `<base>/ota/chunk`. `BOARDS` in the rule gives each board its own file, so a board cannot be handed another identity's image. |
| `things/canbus.things` | MQTT channels `otaStatus` (`stateTopic canbus/springfield/ota/status`), `otaReq` (`canbus/springfield/ota/req`, a trigger channel) and `fw` (JSONPATH `$.fw` from `.../meta`). |
| `items/canbus.items` | `CanBus_OTA` ← `otaStatus` channel (live status). `CanBus_FW_Version` ← `fw` channel (running version). |
| `things/canbus-bench.things`, `items/canbus-bench.items` | The same for the bench board under `canbus/bench`: `CanBench_OTA` (takes `update` / `mqtt`), `CanBench_OTA_Status`, `CanBench_Meta`. |
| `sitemaps/myhouse.sitemap` | *Board & Firmware* frame under SpringCommand: Running Version, OTA Status, a switch with **🔄 Update Now** and **📡 Update via MQTT**, Deep sleep, WiFi IP. |

**Why a JS rule and not an outbound item binding?** An outbound-only MQTT item
binding never updates the item's own state, so DSL `changed`/`received command`
triggers fired unreliably and the command often never reached the broker. The
JSRule on the command is deterministic and logs each step.

---

## Firmware pieces (`src/`)

| Symbol | Where | Role |
|--------|-------|------|
| `OTA_FIRMWARE_URL` | `config.h` (fallback in `main.cpp`) | HTTP URL of the image for `update`, **one file per identity**: `.../indian-canbus-firmware.bin` for the bike, `.../indian-canbus-bench-firmware.bin` for a bench build. Must be the server **IP**, e.g. `http://192.0.2.10:8080/static/indian-canbus-firmware.bin`. |
| `FW_VERSION` | `config.h` (fallback `"dev"` in `main.cpp`) | Version string, published in `/meta` and as `Running <ver>`. |
| `runHttpOta()` | `net.cpp` (was `onMqttMessage()` in `main.cpp` until 2026-09-27) | On `<base>/ota == "update"`: registers `httpUpdate.onProgress`, `rebootOnUpdate(true)`, runs `httpUpdate.update()` **in the network task**, so the bus and the phone keep running during the download; `netBusy()` holds deep sleep off meanwhile. |
| `motaStart()`, `motaOnInfo()`, `motaOnChunk()`, `motaTick()` | `net.cpp` | On `<base>/ota == "mqtt"`: ask the server for the image on `<base>/ota/req`, write the chunks in order, check the md5 in `Update.end()`, reboot. Gives up after 240 s without data. |
| `OTA_STALL_MS` guard | `net.cpp` | An `esp_timer` that restarts the chip when an OTA of either kind makes no progress for 300 s. |
| `publishOtaStatus()` | `net.cpp` | Retained publish to `<base>/ota/status` (also mirrored to `/debug`). |
| `mqttConnectMaybe()` | `net.cpp` | On connect, subscribes to `<base>/ota`, `<base>/ota/info` and `<base>/ota/chunk` and publishes `Running <FW_VERSION>` once per boot. |
| `rollbackBegin()`, `rollbackNoteBrokerUp()`, `rollbackTick()` | `rollback.cpp` | The trial: an OTA image boots `pending`, is marked valid at the first broker connection (`Running <ver> - verified`), and goes back to the previous image after `ROLLBACK_GRACE_MS` (5 min) without one. `rollbackPending()` holds deep sleep off meanwhile. |
| WiFi | `net.cpp` `wifiConnect()` | Scans and joins the **strongest** known SSID, 20 s per attempt; the remembered order is the fallback when the scan sees none of them. Calls `WiFi.disconnect(true)` + delay **before each** attempt (fixes `sta is connecting, cannot set config` / `ESP_ERR_WIFI_STATE 0x3006` that previously broke fallback). |

The `#ifndef` fallbacks for `OTA_FIRMWARE_URL` / `FW_VERSION` in `main.cpp` mean
the firmware still builds on a machine whose (git-ignored) `config.h` predates
the OTA block — the real values still come from `config.h` when present.

---

## MQTT topics

`<base>` is `canbus/springfield` for the bike and `canbus/bench` for the bench
board.

| Topic | Dir | Payload |
|-------|-----|---------|
| `<base>/ota` | openHAB → ESP32 | `update` (HTTP, home LAN) or `mqtt` (over this link) |
| `<base>/ota/status` | ESP32 → openHAB | `Running <ver>` / `Running <ver> - verified` / `Starting download...` / `Downloading NN%` / `OK - rebooting` / `No update available` / `FAILED ...` / `ROLLBACK: no broker within N s, ...` (retained) |
| `<base>/ota/req` | ESP32 → openHAB | `mqtt` path only: `info`, then `<md5> <offset> <count> <len>` |
| `<base>/ota/info` | openHAB → ESP32 | `mqtt` path only: `<size> <md5>`, or `ERR ...` |
| `<base>/ota/chunk` | openHAB → ESP32 | `mqtt` path only: 4 bytes offset (LE) + data |
| `<base>/meta` | ESP32 → openHAB | JSON incl. `"fw":"<ver>"`, `"ota_state"`, `"ota_other"`, `"reset"` + `"ip"` (retained) |
| `<base>/debug` | ESP32 → openHAB | Free-text log incl. `[ota] ...` and `[rollback] ...` lines |

**Watch a live update from the server:**
```bash
/etc/openhab/.venv/bin/python - <<'PY'
import ssl, time, paho.mqtt.client as mqtt
c=mqtt.Client(client_id="ota-watch"); c.username_pw_set("<user>","<pw>")
c.tls_set(cert_reqs=ssl.CERT_NONE); c.tls_insecure_set(True)
c.on_message=lambda cl,u,m: print(time.strftime('%H:%M:%S'), m.topic, m.payload.decode())
c.on_connect=lambda cl,u,f,rc,p=None:[cl.subscribe(t) for t in
  ("canbus/springfield/ota/status","canbus/springfield/debug","canbus/springfield/status","canbus/springfield/meta")]
c.connect("mqtt.example.com",8883,30); c.loop_forever()
PY
```

---

## Rolling back by hand (no cable needed)

This is the deliberate way back to an older image. An image that fails to reach
the broker goes back **by itself**; that is the section "Automatic rollback"
below.

Every image that has been flashed is kept, exactly as served, in
`releases/indian-canbus-firmware-<version>.bin` (git-ignored; binaries do not
belong in history). The source it was built from carries the tag
`known-good-<version>`. Both were introduced 2026-09-14, before the first change
made after the Zealand ride, because the board is built into the motorcycle and
OTA has to be able to undo itself. A USB flash today means **taking the seat off
and soldering a cable to the pads on the back of the board** (the CANFD-MC has
no USB connector; [FLASHING.md](FLASHING.md) §4b).

To go back to the previous image (today that is 2026.10.04-1; its md5 is in the
version table below):

```bash
cd /etc/openhab-firmware/indian-canbus
cp releases/indian-canbus-firmware-<version>.bin /etc/openhab/html/indian-canbus-firmware.bin
md5sum /etc/openhab/html/indian-canbus-firmware.bin     # compare with the md5 noted for that version
echo "openhab:send CanBus_OTA update" | /usr/share/openhab/runtime/bin/client -p habopen
```

Then watch `OTA Status` until it reads `Running <version> - verified`. Away from
home, send `mqtt` instead of `update`. To go back in *source*:
`git checkout known-good-<version> -- src/ platformio.ini`, rebuild, deploy. Take
the whole of `src/`, not one file: the firmware is spread over `main.cpp`,
`net.cpp`, `rollback.cpp`, `sleep.cpp` and more, and one file from an old tag
does not match the rest.

**What OTA cannot undo by itself:** an image that reaches the broker — and is
therefore marked valid — but is broken in some other way, for instance one that
no longer acts on `<base>/ota`. An image that crashes early, never joins WiFi or
never reaches the broker goes back automatically since 2026.10.04-1. A
USB-flashed image has no trial state and is never rolled back. So the question
every change since 2026-09-14 has been reviewed against still stands, one step
further on: *can any of it break the path from the broker to the next OTA?* The
answer for each is written into the commit. Keep doing that.

**Automatic rollback, how it got here.** Checked 2026-10-03: the bootloader *is*
built with `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y` (the Arduino core's esp32s3
sdkconfig), so a new image boots as "pending verify" and any reset before it is
marked valid returns to the previous one. But the core marked it valid itself, in
`initArduino()`, before `setup()` ran, because the firmware did not override the
weak `verifyRollbackLater()`. In practice an image that died before Arduino
started rolled back; one that booted and then never reached the broker did not.
That gap was closed on 2026-10-04 (IDEAS D7 step 1, `src/rollback.cpp`):
`verifyRollbackLater()` returns `true`, the image is marked valid after the first
broker connection, and it goes back if that has not happened within five minutes.

**The archive above had lapsed:** nothing between 2026.09.15-1 and 2026.09.27-3 was
kept. 2026.09.27-3, then the image on the bike, was archived and tagged
`known-good-2026.09.27-3` (commit `aa0c703`, md5 `8853b295b17b22818b19743e0bdbaf0c`)
on 2026-10-03. Archive every image you OTA to the bike, at the moment you deploy it.

### Before pressing Update

1. `curl -s -o /dev/null -w "%{http_code} %{size_download}\n" http://192.0.2.10:8080/static/indian-canbus-firmware.bin` — 200, and the size of the image you just copied.
2. `md5sum` of the served file equals `md5sum` of `.pio/build/sniffer-t2can/firmware.bin`.
3. The previous image is in `releases/` and its md5 is written next to its tag.
4. For `update` the bike must be on home WiFi; `mqtt` works on any network that
   reaches the broker. A new image that never reaches the broker goes back by
   itself after five minutes, so the way home no longer depends on a second OTA.
5. **The ignition is on, proven by the bus.** `efmsg` in the health JSON
   (`<base>/bus/health`, `CanBus_Bus_CleanMsgs` / `CanBench_Health` in openHAB) is the controller's error-free message count, so it only
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
   ignition must stay on before sending. Measured on the bike 2026-10-04, from
   the command to `- verified`: 30 s with `update` on home WiFi at RSSI -61 (10 s
   of it download), 55 s with `mqtt` over a phone hotspot (40 s download). On
   2026-10-03, at RSSI around -87, it was about 35 s of download plus 15 s for
   the reboot.
6. **Know which identity the board has.** Each identity pulls its own file:
   `indian-canbus-firmware.bin` for the bike (`sniffer-t2can`),
   `indian-canbus-bench-firmware.bin` for the bench (`bench-canfdmc`). Check that
   the file for *this* board holds the image you mean to send. See "Swapping in
   a new board" in [FLASHING.md](FLASHING.md).
7. **Send `update` once.** If the call that sent it was interrupted, read
   `events.log` for the command and for `Downloading` lines before sending again.
   A second `update` that nobody meant to send cost one extra identity round trip
   on 2026-10-03.

## Automatic rollback (since 2026.10.04-1; proven on the bench first)

Written 2026-10-04 on branch `rollback-bench` and tested on bench board #0002
before any of it went near the bike; merged to master and on the bike the same
day. What it does, in `src/rollback.cpp`:

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
blank: nothing to decode. Seen once. The owner's explanation, given the same day: the supply
was switched off and on to get out of the serial monitor on the Windows PC. A brief dip that
never takes the chip all the way down fits a `panic` with no coredump; not reproduced, and
not seen again in the eight OTAs that followed (six on the bench, two on the bike).

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

## OTA over the MQTT link (command `mqtt`, since 2026.10.04-1)

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
- In the sitemap the bike's OTA row has two buttons: `Update Now` sends `update` (HTTP, home
  LAN only), `Update via MQTT` sends `mqtt` (works wherever the board reaches the broker).
  The ignition rule in "Before pressing Update" holds for both.

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

**Done 2026-10-04, in the garage, ignition on** (`efmsg` 5709 -> 11824 in 30 s, home WiFi -61):
`update` 10:11:32, 100 % 10:11:42 (10 s), `Running 2026.10.04-1` 10:11:55, `- verified` 10:12:02.
Meta: `ota_state: valid`, `ota_other: valid` (the slot holding 2026.09.27-3), `reset: sw`,
250 kbps detected, `efmsg` climbing again, decoded values flowing. Against 2026.09.27-3 a minute
earlier: heap 71.8 kB both, `heap_min` 20.7 kB (was 17.4), loop and BLE gaps 4 ms both.
Then `mqtt` with the same image, 10:13:40: Springfield asked, the rule answered with md5
`091547a2...`, the board said `No update available` - the MQTT path works on the bike end to
end, nothing flashed. Tagged `known-good-2026.10.04-1`.

**The same morning, over cellular.** One deep-sleep round with the phone's hotspot on the
seat put the board on the hotspot (woke on CAN 10:21:44, -63 there). `efmsg` 5709 -> 11824,
then `mqtt` 10:22:18 with 2026.10.04-2 (the same code under a new version; a board declines
the image it runs): 100 % 10:22:58 (40 s), `OK - rebooting`, `Running 2026.10.04-2 - verified`
10:23:13, still on the hotspot. During the download the worst loop and BLE gaps were 104 and
105 ms (3 ms otherwise; the app calls a stream dead at 2500). When the hotspot left at 10:23:53
the board was back on the home WiFi at 10:24:44 without a reboot. Tagged `known-good-2026.10.04-2`.

## First flash / emergency recovery (USB cable)

OTA only works once a *good* image is on the device. For the very first flash of
a board, or if automatic rollback has nothing to go back to, flash over USB with
the board's own environment:

```bash
# On the machine with the board's USB attached:
rm -rf .pio                                # if you copied .pio from another host
pio run -e sniffer-t2can -t upload -t monitor \
  --upload-port /dev/ttyACM0               # your serial port (pio device list);
                                           # /dev/cu.usbmodemXXXX on macOS
```

- `sniffer-t2can` is the bike identity; a bench board takes `-e bench-canfdmc`.
- **The CANFD-MC has no USB connector.** USB is on solder pads on the back
  (GND, USB_D+, USB_D−), the board is powered from 12 V, and on the bike it sits
  under the seat. See "USB on the CANFD-MC" in [FLASHING.md](FLASHING.md).
- `-t upload` writes the app only. A blank board needs `firmware.factory.bin` at
  `0x0`, which wipes NVS; see [FLASHING.md](FLASHING.md) §5 and §5b.
- A USB-flashed image has no trial state: it is never rolled back.

(`sniffer-usb`, which this section used to name, is the cable environment of the
first board, the classic-ESP32 T-CAN485. It does not fit an ESP32-S3.)

---

## ⚠️ Library-version caveat (build on the server)

The deployed OTA image **must be built on the openHAB server**, whose PlatformIO
has arduino-esp32 **3.3.9** (WiFi/HTTPUpdate/WiFiClientSecure `3.3.9`). A laptop
with the older **2.0.0** libraries produces a *different, smaller* binary
(~1.03 MB vs ~1.4 MB). Always build + deploy the OTA `.bin` from the server so
what you serve matches what you tested. USB flashing from a laptop is fine for
emergency recovery, but treat the **server build as the source of truth** for
OTA.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Status stuck, device never downloads | Command never reached MQTT | Check `canbus-ota.js` log line `published "update" to canbus/springfield/ota`; confirm broker `mqtt:broker:broker` is ONLINE |
| `update` fails with `FAILED (-1): ... connection refused`, or never gets past `Starting download...` | The board is not on the home LAN (a hotspot, for instance), so the server's LAN address is out of reach; or `OTA_FIRMWARE_URL` is wrong | Send `mqtt` instead; at home, verify that `curl` serves the `.bin` at the URL in `config.h` |
| `mqtt` ends in `FAILED: no data from the server for 240 s` | Nothing answered `<base>/ota/req` | Check that `canbus-ota-mqtt.js` is loaded, that the board is listed in its `BOARDS`, and that the thing's `otaReq` trigger channel exists |
| `mqtt` answers `No update available` | The served file is the image the board already runs (same md5) | Nothing is wrong. To test the path, build under a new `FW_VERSION` |
| `Running <old version>` after the update | The new image did not reach the broker within five minutes, or the board was reset while the image was pending, and the previous image came back | Read `<base>/debug`; `ota_other` in `meta` reads `invalid` or `aborted` (or `none` after a sagging power cut) |
| Device offline, never reconnects on the bench | None of the SSIDs in `config.h` is in range (without an antenna the module reaches about a metre) | Move the board next to an AP it knows |
| UI shows old version after OTA | Browser cache | **Ctrl+Shift+R**; trust the item states / MQTT |
| First `update` after a wake fails at once (`FAILED (-11): HTTP error: read Timeout`) or crawls and dies | Not understood (seen three times on 2026-09-27, on the old and the new code path); the second `update` a minute later ran in 20-40 s every time | Send `update` again. The guard restarts the chip if a download stalls for 300 s, so a failed first attempt costs at most five minutes. |
| Downloads, then crashes at boot | Bad image | The first reset while the image is pending makes the bootloader start the previous image; nothing to do but read `meta` (`ota_other: aborted`). USB recovery flash (see above) only if no previous image is left |

---

## Version history

Not every version has a row. The versions between `2026.08.17-1` and
`2026.09.14-3`, and those between `2026.09.15-1` and `2026.09.27-1` other than
the two listed, are in `git log` only.

| Version | Notes |
|---------|-------|
| `2026.10.04-3` | The cruise probe also reports the ECM's own copy of PGN 65265 (SA 0, byte 4 bits 0-1, with brake, clutch and the derived hold), so a ride with the cruise in use settles whether SPN 595 is on the bus after all. The derived cruise update no longer sits inside the probe block (a build with the probes compiled out would have frozen it). The serial banner names the board it runs on and prints the build's own base topic, and the no-frames hint lists what applies to the MCP2518FD boards. On the bike since 2026-10-04 12:54 by `update` on the home LAN (ignition proven, 10 s download, `- verified` 25 s after the command), tag `known-good-2026.10.04-3`, md5 `a41238ce1b5cfb236bb0e4d28de94e33`. Rollback image: `releases/...-2026.10.04-2.bin`. |
| `2026.10.04-2` | The code of 2026.10.04-1 under a new version, built to prove an `mqtt` OTA on the bike over the phone's hotspot (a board declines the image it already runs). On the bike since 2026-10-04 10:23, tag `known-good-2026.10.04-2`, md5 `c5f2d813a2de53af39e2f4d1d5cab714`. Rollback image: `releases/...-2026.10.04-1.bin`. |
| `2026.10.04-1` | **Automatic rollback and OTA over the MQTT link.** An OTA image is on trial until it has reached the broker (5 min), otherwise the previous image boots again; `mqtt` on `<base>/ota` brings the image over the MQTT/TLS link, so an update no longer needs the LAN. Meta gains `ota_state` / `ota_other`, and its buffer goes 260 -> 400 with a tripwire. Both proven on bench board #0002 on 2026-10-04 (sections above). On the bike since 2026-10-04 10:12 (`releases/indian-canbus-firmware-2026.10.04-1.bin`, md5 `091547a2801b20e8720c7b1af21a5d31`, tag `known-good-2026.10.04-1`). Rollback image: `releases/...-2026.09.27-3.bin`, md5 `8853b295b17b22818b19743e0bdbaf0c`. |
| `2026.09.27-3` | The `asleep` marker is given 250 ms to leave the radio after `netFlush()`; on 27-2 the chip went down with it still in the TCP buffer and openHAB got only the last will. |
| `2026.09.27-2` | **The network in its own task** (`src/net.cpp`): WiFi, SNTP, MQTT and OTA on core 0 behind two queues; `loop()` never waits for the broker. Measured with the broker blocked: loop gap 5019 → 4 ms, BLE gap 6656 → 3 ms, app uninterrupted. The network is now chosen by scan and signal, not by which SSID worked last. `ENABLE_MQTT 0` compiles again; env `sniffer-t2can-nomqtt` keeps it that way. |
| `2026.09.27-1` | Two gauges in `meta`: `loop_max_ms` and `ble_gap_max_ms`, worst of the last 30 s, so a stall can be measured from the garage instead of felt on the road. |
| `2026.09.19-2` | Two BLE centrals at once (`BLE_MAX_CENTRALS` 2): advertising is restarted after the first connect, pairing state is kept per link. |
| `2026.09.19-1` | The handlebar buttons over BLE: a button characteristic, two bytes per press. |
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

- [x] MQTT-triggered OTA from openHAB (`<base>/ota`) — **done** (`canbus-ota.js`)
- [x] Firmware version item in openHAB (`CanBus_FW_Version`) — **done**
- [x] Live progress in the UI (`<base>/ota/status`) — **done**
- [x] Automatic rollback when a new image does not reach the broker — **done**
      2026.10.04-1 (`rollback.cpp`)
- [x] OTA over the MQTT link, for a board away from home (`mqtt`) — **done**
      2026.10.04-1 (`net.cpp`, `canbus-ota-mqtt.js`)
- [ ] Rollback to a chosen older version by one command (today: copy the image
      from `releases/` into the served file and send `update` or `mqtt`)
- [ ] Serve a versioned filename (`indian-canbus-<ver>.bin`) + symlink for audit
      trail (today the versioned copies are in `releases/`, the served name is fixed)
- [ ] Test a link that drops in the middle of an `mqtt` download (bench)


/*
 * Configuration for the Indian CAN sniffer.
 *
 * COPY this file to  config.h  and fill in your real credentials.
 * config.h is git-ignored so your secrets are not committed.
 *
 * As it stands this builds the image for the CANFD-MC (or a LilyGO T-2CANFD):
 * PRODUCTION mode, MCP2518FD backend, BLE on. Set at least WiFi, the MQTT user
 * and password, BLE_PASSKEY, and the server address in OTA_FIRMWARE_URL.
 *   pio run -e sniffer-t2can
 */
#pragma once

// ---- WiFi ----
#define WIFI_SSID       "YOUR_WIFI_SSID"
#define WIFI_PASSWORD   "YOUR_WIFI_PASSWORD"

// Up to three known networks. The board scans and joins the STRONGEST one it
// sees (20 s per attempt); the order below is only the fallback when the scan
// sees none of them. Typical set: home, the phone's hotspot, a router on the bike.
#define WIFI_SSID2      "YOUR_WIFI_SSID2"      // Leave empty "" to disable
#define WIFI_PASSWORD2  "YOUR_WIFI_PASSWORD2"  // fallback WiFi #1
#define WIFI_SSID3      "YOUR_WIFI_SSID3"      // Leave empty "" to disable
#define WIFI_PASSWORD3  "YOUR_WIFI_PASSWORD3"  // fallback WiFi #2

// ---- MQTT ----
#define MQTT_BROKER     "mqtt.example.com"
#define MQTT_PORT       8883
#define MQTT_USERNAME   "YOUR_MQTT_USER"
#define MQTT_PASSWORD   "YOUR_MQTT_PASS"
// PREFIX only — the firmware appends the last 3 bytes of the board MAC
// (e.g. "indian-canbus-A1B2C3") so two boards flashed from the same config.h
// don't collide on the broker (duplicate MQTT client IDs make the broker evict
// one board when the other connects). Publish topics use MQTT_BASE_TOPIC as-is.
#if BENCH_BOARD
#define MQTT_CLIENT_ID  "canfd-bench"     // env bench-canfdmc: a CANFD-MC on the bench next to the live board
#else
#define MQTT_CLIENT_ID  "indian-canbus"
#endif

// TLS root CA for mqtt.example.com (DigiCert Global Root G2).
// Public root certificate, pinned so the broker's leaf cert can renew without
// requiring firmware changes. TLS needs a valid clock; main.cpp syncs SNTP.
static const char MQTT_ROOT_CA[] PROGMEM = R"EOF(
-----BEGIN CERTIFICATE-----
MIIDjjCCAnagAwIBAgIQAzrx5qcRqaC7KGSxHQn65TANBgkqhkiG9w0BAQsFADBh
MQswCQYDVQQGEwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3
d3cuZGlnaWNlcnQuY29tMSAwHgYDVQQDExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBH
MjAeFw0xMzA4MDExMjAwMDBaFw0zODAxMTUxMjAwMDBaMGExCzAJBgNVBAYTAlVT
MRUwEwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3dy5kaWdpY2VydC5j
b20xIDAeBgNVBAMTF0RpZ2lDZXJ0IEdsb2JhbCBSb290IEcyMIIBIjANBgkqhkiG
9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuzfNNNx7a8myaJCtSnX/RrohCgiN9RlUyfuI
2/Ou8jqJkTx65qsGGmvPrC3oXgkkRLpimn7Wo6h+4FR1IAWsULecYxpsMNzaHxmx
1x7e/dfgy5SDN67sH0NO3Xss0r0upS/kqbitOtSZpLYl6ZtrAGCSYP9PIUkY92eQ
q2EGnI/yuum06ZIya7XzV+hdG82MHauVBJVJ8zUtluNJbd134/tJS7SsVQepj5Wz
tCO7TG1F8PapspUwtP1MVYwnSlcUfIKdzXOS0xZKBgyMUNGPHgm+F6HmIcr9g+UQ
vIOlCsRnKPZzFBQ9RnbDhxSJITRNrw9FDKZJobq7nMWxM4MphQIDAQABo0IwQDAP
BgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBhjAdBgNVHQ4EFgQUTiJUIBiV
5uNu5g/6+rkS7QYXjzkwDQYJKoZIhvcNAQELBQADggEBAGBnKJRvDkhj6zHd6mcY
1Yl9PMWLSn/pvtsrF9+wX3N3KjITOYFnQoQj8kVnNeyIv/iPsGEMNKSuIEyExtv4
NeF22d+mQrvHRAiGfzZ0JFrabA0UWTW98kndth/Jsw1HKj2ZL7tcu7XUIOGZX1NG
Fdtom/DzMNU+MeKNhJ7jitralj41E6Vf8PlwUHBHQRFXGU7Aj64GxJUTFy8bJZ91
8rGOmaFvE7FBcf6IKshPECBV1/MUReXgRPTqh5Uykw7+U0b6LJ3/iyK5S9kJRaTe
pLiaWN0bfVKfjllDiIGknibVb63dDcY3fe0Dkhvld1927jyNxF1WW6LZZm6zNTfl
MrY=
-----END CERTIFICATE-----
)EOF";

// Base topic. In PRODUCTION mode the board publishes:
//   <base>/status      -> "online" / "offline" (retained, LWT)
//   <base>/state       -> the decoded vehicle state, one retained JSON
//   <base>/meta        -> the board itself: fw, ota_state, reset, heap, rssi, ...
//   <base>/bus/health  -> the CAN controller's error counters
//   <base>/sleep/status, <base>/ota/status, <base>/button, <base>/gear, <base>/debug
// (docs/PROTOCOL.md and the README list them all; openhab/canbus.things binds them.)
// A bench board (env bench-canfdmc, -D BENCH_BOARD=1) gets its own base topic so
// its last will cannot mark the live board offline.
#if BENCH_BOARD
#define MQTT_BASE_TOPIC "canbus/bench"
#else
#define MQTT_BASE_TOPIC "canbus/springfield"
#endif

// ---- Firmware version -------------------------------------------------------
// Bump this on every build you flash/OTA. It is published in <base>/meta (JSON
// field "fw") and echoed on <base>/ota/status as "Running <ver>" right after
// boot, so the openHAB UI confirms exactly which image is live after an OTA.
// A board declines an OTA of the image it already runs.
#ifndef FW_VERSION          // a build may set it: -D FW_VERSION=\"...\" (bench-rollback envs)
#define FW_VERSION      "2026.10.04-2"
#endif

// ---- CAN bitrate ------------------------------------------------------------
// This bus is 250 kbit/s, and the board listens at that rate only. Without this
// line the firmware searches 250/500/125/100/50 in turn, which matters for deep
// sleep: a controller parked on the wrong rate hears nothing, so wake-on-CAN
// silently degrades to the hourly backstop.
// Comment this out to get the search back, for a different machine.
#define CAN_FIXED_BITRATE 250000

// ---- OTA --------------------------------------------------------------------
// "update" on <base>/ota: the board downloads this image over plain HTTP and
// flashes itself. Use your server's LAN IP address, NOT a ".local" name: the
// Arduino WiFiClient does not resolve mDNS. One file per identity, so an update
// cannot hand a bench board the bike's image.
// "mqtt" on <base>/ota needs no URL: the image comes in chunks over the MQTT
// link the board already has (openhab/rules/canbus-ota-mqtt.js is the server
// half), so it also works from a phone hotspot. Either way a new image is on
// trial until it has reached the broker, and the previous one boots again if it
// does not. See docs/OTA.md.
#if BENCH_BOARD
#define OTA_FIRMWARE_URL  "http://192.0.2.10:8080/static/indian-canbus-bench-firmware.bin"
#else
#define OTA_FIRMWARE_URL  "http://192.0.2.10:8080/static/indian-canbus-firmware.bin"
#endif

// ---- Publish behaviour ----
// Minimum time between MQTT publishes of changed IDs (ms).
// 1000 ms (1 Hz) suits the real-world deployment where the ESP rides on a phone
// hotspot and reaches the broker through a cellular->internet->home NAT hairpin.
// At 200 ms a busy 250k J1939 bus fires 50-100 small TLS retained publishes/sec
// (each changed ID -> /id AND /pgn, plus /frame + TPMS split), which saturates
// the mobile uplink and stalls updates. 1 Hz is plenty for live telemetry and
// cuts the packet rate ~5x. Lower toward 200-500 only on a solid LAN link.
#define MQTT_PUBLISH_INTERVAL_MS  1000

// PRODUCTION-only publish interval. Production emits ONE compact JSON on
// <base>/state per cycle (not the per-ID fan-out DISCOVERY does), so 5 Hz is a
// few small TLS packets a second, fine even over cellular. This is the
// live-telemetry smoothness knob; DISCOVERY keeps the safer 1 Hz above.
#define STATE_PUBLISH_INTERVAL_MS  200

// Set to 1 to also enable MQTT, 0 for USB-only (no WiFi).
#if NO_MQTT_BUILD
#define ENABLE_MQTT  0
#else
#define ENABLE_MQTT  1
#endif
// WiFi without a broker: ArduinoOTA over the LAN still works, nothing is
// published. Implied by ENABLE_MQTT; set to 1 with ENABLE_MQTT 0 for that.
#define ENABLE_WIFI  ENABLE_MQTT

// ---- BLE local phone link ---------------------------------------------------
// 1 = also serve the decoded state over a BLE GATT service so a phone app can
// read the bike live WITHOUT WiFi/MQTT. This is the transport that works on the
// road; MQTT is the one that works at home. Set 0 and NimBLE is not linked at
// all — no flash, no RAM, no radio.
//
// PRODUCTION ONLY: DISCOVERY mode has no decoded state to serve, and the build
// fails with a #error on that combination (see ble.h / main.cpp).
#define ENABLE_BLE  1

// Advertised name. Keep it short — it shares the 31-byte advertising packet
// with the service UUID.
#if BENCH_BOARD
#define BLE_DEVICE_NAME  "CANFD-bench"   // 11 chars, same length as "Springfield"
#else
#define BLE_DEVICE_NAME  "Springfield"
#endif

// 6-digit passkey the phone must type to bond. MITM protection is on, so an
// unpaired phone gets nothing: the link is dropped unless it encrypts.
// CHANGE THIS — it is a shared secret, not a placeholder to leave as-is.
#define BLE_PASSKEY  123456   // CHANGE THIS. It is the pairing PIN; the real one lives in config.h, which is gitignored.

// Notify intervals. The fast characteristic carries the 8 packed bytes that
// make a gauge look alive (rpm/speed/throttle/gear/switches); the JSON one
// carries everything else, which the bus only updates once or twice a second
// anyway. Splitting them keeps the phone's radio (and battery) idle most of
// the time without making the needles steppy.
#define BLE_FAST_MS  100
#define BLE_JSON_MS  1000

// Maximum ATT MTU the device will agree to. NimBLE's own default is 256, which
// caps a notification at 253 bytes and silently truncates the state JSON — the
// client sees a JSON fragment, not an error. 517 is the BLE maximum.
#define BLE_MTU  517

// ---- Firmware mode ----------------------------------------------------------
// 1 = PRODUCTION (what the bike runs): decode the confirmed signals in firmware
//     and publish ONE retained JSON on <base>/state. BLE needs this mode.
//     Bind with openhab/canbus.things + canbus.items.
// 0 = DISCOVERY: raw firehose (every id/pgn/frame) for reverse-engineering a
//     bus you do not know yet. No decoded state, so ENABLE_BLE must be 0.
#define FIRMWARE_MODE 1

// ---- Probes (PRODUCTION only) -----------------------------------------------
// Compiles in the change detector and the readouts used to find new signals.
// Each probe is switched over MQTT (<base>/probe/en/<name>) and is silent until
// it is; the setting is kept in NVS. Leave this at 1: the derived cruise-hold
// state is updated from the same block.
#define PROBE_CHANGES 1

// ---- CAN hardware backend ---------------------------------------------------
// Selects which CAN controller this firmware drives. Everything else (J1939
// decode, MQTT, OTA, WiFi) is identical — only the thin HAL layer
// (can_hal_*.cpp) differs. See can_hal.h.
//   CAN_BACKEND_MCP2518 = ESP32-S3 + MCP2518FD on SPI, 40 MHz crystal: the
//                         CANFD-MC (the board on the bike) and the LilyGO
//                         T-2CANFD it replaced. Same pin map. PlatformIO env
//                         sniffer-t2can (bench identity: bench-canfdmc).
//   CAN_BACKEND_TWAI    = LilyGO T-CAN485 (classic ESP32, native TWAI, onboard
//                         transceiver): the first test rig. Env sniffer /
//                         sniffer-usb.
#define CAN_BACKEND  CAN_BACKEND_MCP2518
// ---- Onboard status LED -----------------------------------------------------
// ONLY the T-CAN485 has a programmable WS2812 RGB LED (GPIO4). When enabled the
// firmware colours it by live health: white=boot, blue(blink)=connecting,
// amber(breathe)=no CAN yet, GREEN(heartbeat)=running, red=fault.
//   T-CAN485            → set 1
//   CANFD-MC, T-2CANFD  → keep 0 (no LED hardware; also compiles out FastLED)
#define STATUS_LED 0

/*
 * Configuration for the Indian CAN sniffer.
 *
 * COPY this file to  config.h  and fill in your real credentials.
 * config.h is git-ignored so your secrets are not committed.
 */
#pragma once

// ---- WiFi ----
#define WIFI_SSID       "YOUR_WIFI_SSID"
#define WIFI_PASSWORD   "YOUR_WIFI_PASSWORD"

// WiFi failover: if SSID1 fails after 10 seconds, try SSID2, then SSID3
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
// (e.g. "indian-canbus-5A8EE0") so two boards flashed from the same config.h
// don't collide on the broker (duplicate MQTT client IDs make the broker evict
// one board when the other connects). Publish topics use MQTT_BASE_TOPIC as-is.
#define MQTT_CLIENT_ID  "indian-canbus"

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

// Base topic. The sniffer publishes:
//   <base>/status   -> "online" / "offline" (retained, LWT)
//   <base>/frame    -> JSON per CAN ID on change (throttled)
//   <base>/meta     -> JSON scan result (detected bitrate, id count)
#define MQTT_BASE_TOPIC "canbus/indian"

// ---- Publish behaviour ----
// Minimum time between MQTT publishes of changed IDs (ms).
// 1000 ms (1 Hz) suits the real-world deployment where the ESP rides on a phone
// hotspot and reaches the broker through a cellular->internet->home NAT hairpin.
// At 200 ms a busy 250k J1939 bus fires 50-100 small TLS retained publishes/sec
// (each changed ID -> /id AND /pgn, plus /frame + TPMS split), which saturates
// the mobile uplink and stalls updates. 1 Hz is plenty for live telemetry and
// cuts the packet rate ~5x. Lower toward 200-500 only on a solid LAN link.
#define MQTT_PUBLISH_INTERVAL_MS  1000

// Set to 1 to also enable MQTT, 0 for USB-only (no WiFi).
#define ENABLE_MQTT  1

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
#define BLE_DEVICE_NAME  "Springfield"

// 6-digit passkey the phone must type to bond. MITM protection is on, so an
// unpaired phone gets nothing: the link is dropped unless it encrypts.
// CHANGE THIS — it is a shared secret, not a placeholder to leave as-is.
#define BLE_PASSKEY  123456   // CHANGE THIS. It is the pairing PIN.

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
// 0 = DISCOVERY (default): raw firehose (every id/pgn/frame) for reverse-eng.
// 1 = PRODUCTION: decode confirmed signals in-firmware, publish ONE retained
//     JSON on canbus/indian/state. Far less MQTT traffic + zero openHAB JS.
//     Bind with indian-canbus/canbus_production.things.
#define FIRMWARE_MODE 0

// ---- CAN hardware backend ---------------------------------------------------
// Selects which CAN controller this firmware drives. Everything else (J1939
// decode, MQTT, OTA, WiFi, LED) is identical on both boards — only the thin HAL
// layer (can_hal_*.cpp) differs. See can_hal.h.
//   CAN_BACKEND_TWAI    = LilyGO T-CAN485  (ESP32,     native TWAI, onboard xcvr)
//   CAN_BACKEND_MCP2518 = LilyGO T-2CANFD  (ESP32-S3 + external MCP2518FD on SPI)
// NOTE: switching to CAN_BACKEND_MCP2518 also needs the matching PlatformIO env
// (ESP32-S3 board + ACAN2517FD lib — see [env:sniffer-t2can] in platformio.ini)
// and the MCP oscillator set to the board crystal (40 MHz on T-2CANFD). On the
// T-2CANFD wire the bus to the "CAN A" terminal: CanH / CanL / GND ONLY — the
// terminal's 5VDC pin is a power rail, NOT a CAN signal; leave it unconnected.
#define CAN_BACKEND  CAN_BACKEND_TWAI
// ---- Onboard status LED -----------------------------------------------------
// ONLY the T-CAN485 has a programmable WS2812 RGB LED (GPIO4). When enabled the
// firmware colours it by live health: white=boot, blue(blink)=connecting,
// amber(breathe)=no CAN yet, GREEN(heartbeat)=running, red=fault.
//   T-CAN485  → set 1
//   T-2CANFD  → keep 0 (no LED hardware; also compiles out FastLED)
#define STATUS_LED 1

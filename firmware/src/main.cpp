/*
 * Indian Springfield 2017 — CAN bus LISTEN-ONLY sniffer  (USB + MQTT)
 *
 * ONE firmware, TWO boards — the CAN controller is abstracted behind a HAL
 * (can_hal.h) selected by CAN_BACKEND in config.h:
 *   • LilyGO TTGO T-CAN485  — ESP32, BUILT-IN transceiver on the native TWAI
 *                             controller (default, currently deployed).
 *   • LilyGO T-2CAN         — ESP32-S3 + external MCP2518FD on SPI.
 * Everything below the wire (J1939 decode, TP/BAM, MQTT, OTA, WiFi, LED) is
 * identical on both; only the ~50-line HAL backend differs.
 *
 * SAFETY: This firmware NEVER transmits on the CAN bus. The controller is put
 * in hardware LISTEN-ONLY mode (TWAI listen-only / MCP ListenOnly), so it emits
 * no ACKs and no frames. Only safe way to probe a live vehicle CAN bus until
 * IDs/rates are known.
 *
 * Outputs in parallel:
 *   1. USB serial  — every raw frame (full detail)
 *   2. MQTT        — per-CAN-ID JSON on change, throttled (no flooding)
 *
 * Copy src/config.example.h -> src/config.h and fill in WiFi/MQTT first.
 */

#include <Arduino.h>
#include <esp_timer.h>   // OTA dead man's switch -- see otaGuardStart()
#include <map>
#include <stdarg.h>
#include <string.h>

#include "config.h"
#include "can_hal.h"       // board-agnostic CAN layer (TWAI or MCP2518 backend)

// Firmware mode selector. Default PRODUCTION so a build with an old config.h
// (that predates this define) matches the deployed device, which runs
// PRODUCTION — same rationale as the OTA fallbacks below. The real value comes
// from config.h (currently FIRMWARE_MODE 1) whenever it defines it.
#ifndef FIRMWARE_MODE
#define FIRMWARE_MODE 1
#endif
#define MODE_DISCOVERY  0
#define MODE_PRODUCTION 1

#include "vehstate.h"
#include "service.h"
#include "counters.h"
#include "probeflags.h"      // fault tallies that outlive a reboot and an OTA
#include "ble.h"           // local phone link; compiles to nothing when ENABLE_BLE 0

// BLE serves the DECODED state, which only exists in PRODUCTION. In DISCOVERY
// the firmware is a raw frame firehose for reverse engineering, so there is
// nothing to advertise — fail the build rather than ship an empty service.
#if ENABLE_BLE && FIRMWARE_MODE != MODE_PRODUCTION
#error "ENABLE_BLE requires FIRMWARE_MODE PRODUCTION. DISCOVERY has no decoded state to serve."
#endif

// Safe fallbacks for non-secret OTA config, so the firmware still builds on a
// machine whose (git-ignored) config.h predates these defines — e.g. a laptop
// that hasn't had the OTA block added. The real values live in config.h; these
// only apply when it doesn't define them. Not secrets, so hard-coding is fine.
#ifndef OTA_FIRMWARE_URL
#define OTA_FIRMWARE_URL "http://192.0.2.10:8080/static/indian-canbus-firmware.bin"
#endif
#ifndef FW_VERSION
#define FW_VERSION "dev"
#endif

// ---- Onboard WS2812 status LED (T-CAN485 has ONE RGB LED on GPIO4) ----------
#ifndef STATUS_LED
#define STATUS_LED 1
#endif
#if STATUS_LED
  #define FASTLED_INTERNAL          // silence FastLED's version pragma
  #include <FastLED.h>
  #define LED_PIN     4             // WS2812B DATA (LilyGO T-CAN485 pin map)
  #define LED_COUNT   1
  #define LED_ORDER   GRB           // WS2812B is GRB
  static CRGB gLeds[LED_COUNT];
#endif


#if ENABLE_MQTT
  #include <WiFi.h>
    #include <WiFiClientSecure.h>
  #include <ArduinoOTA.h>
  #include <HTTPUpdate.h>
  #include <PubSubClient.h>
  #include <ArduinoJson.h>
    #include <time.h>
#endif

// ======================= CAN HARDWARE ======================================
// The CAN controller + its pin map now live in the HAL backend
// (can_hal_twai.cpp / can_hal_mcp.cpp), selected by CAN_BACKEND in config.h.
// main.cpp talks only to the board-agnostic canInit/canReceive/canTransmit
// interface, so nothing here is board-specific any more.

#define SCAN_WINDOW_MS 1500

// ======================= TX (REQUEST) — DISABLED BY DEFAULT ================
// SAFETY: This sniffer is LISTEN-ONLY. Requesting data the ECU does NOT
// broadcast automatically (e.g. VIN via PGN 59904 / 0xEA00) requires
// TRANSMITTING on the bus — leaving listen-only mode and sending frames/ACKs.
// Keep this 0 to preserve the hardware guarantee that this device can never
// disturb a live vehicle bus. Only set to 1 deliberately, ENGINE OFF, once you
// understand the risk. When 0, the request code below is compiled out entirely.
#define TX_ENABLED 0
// ==========================================================================

// ======================= ROBUSTNESS TUNABLES ==============================
// USB per-frame logging floods Serial @115200 on a busy 250k J1939 bus and
// stalls the main loop long enough to miss MQTT keepalives -> the broker fires
// the LWT ('offline') and the watchdog reboots the ESP32. That was the root
// cause of the online/offline flapping. Keep 0 for normal running; set 1 only
// for bench debugging on an idle bus.
#ifndef DEBUG_USB_FRAMES
#define DEBUG_USB_FRAMES 0
#endif

// Cap CAN frames processed per loop() pass, then yield to service WiFi/MQTT.
// Guarantees a saturated bus can never starve the network stack.
#define MAX_FRAMES_PER_PASS 200

// J1939 Transport Protocol (multi-packet) reassembly. Some dash values
// (Trip 2, range, etc.) are only sent inside TP/BAM sessions, which arrive as
// many 8-byte fragments the plain per-ID publisher cannot stitch back together.
// When enabled we eavesdrop the fragment stream (still 100% listen-only — we
// never send CTS) and republish the reassembled message on
//   canbus/indian/pgn/<PGN>   (retained, same shape as a normal frame)
// Bounded RAM: TP_MAX_SESSIONS fixed buffers, per-source-address, with a
// timeout so a half-finished transfer can never wedge a slot.
#ifndef TP_REASSEMBLY
#define TP_REASSEMBLY 1
#endif
#define TP_MAX_SESSIONS 4        // concurrent multi-packet transfers tracked
#define TP_MAX_BYTES    512      // largest reassembled message we accept
#define TP_TIMEOUT_MS   2000     // drop a stalled transfer after this
// ==========================================================================

// ============================================================================
// >>>>>>>>>>>>>>>>>>  TEMPORARY: TPMS DISCOVERY MODE  <<<<<<<<<<<<<<<<<<<<<<<<<
// ----------------------------------------------------------------------------
// PURPOSE: help find whether tyre-pressure (TPMS) is broadcast on the bus.
// TPMS frames are multi-byte and change VERY slowly (pressure barely moves),
// so they drown in the flood of RPM/speed frames. This helper tracks, per CAN
// ID, the min/max of every byte and how often it changes, then prints a ranked
// list of "slow-changing multi-byte" candidates on demand.
//
// HOW TO USE (in the serial monitor, 115200):
//   z  -> zero/reset the discovery baseline (do this, then bleed a tyre)
//   r  -> print the candidate report (IDs that changed a little, not a lot)
// Typical flow: ride briefly to wake the TPMS sensors, park, press 'z',
// slowly bleed a tyre for ~30 s, then press 'r' and look for an ID whose
// bytes moved a bit. That ID is your TPMS candidate.
//
// >>> REMOVE ON CLEANUP <<< : this whole block, the TpmsDisc struct/functions
// further down, and the two loop hooks marked with the same tag. Set the flag
// to 0 to compile it out entirely. See README "TPMS discovery (temporary)".
// PRODUCTION mode is kept CLEAN: this reverse-engineering helper (and its
// std::vector report) is compiled out entirely there — only DISCOVERY builds
// include it.
#if FIRMWARE_MODE == MODE_PRODUCTION
#define TPMS_DISCOVERY 0
#else
#define TPMS_DISCOVERY 1
#endif
#if TPMS_DISCOVERY
#include <vector>          // only the TPMS discovery report uses std::vector
#endif
// ============================================================================

// Bitrate presets live in the HAL (CAN_RATES / CAN_NUM_RATES, can_hal.cpp) so
// the auto-detect scanner is backend-independent.

// --- SAE J1939 decode (Polaris/Indian use J1939 across their vehicles) -------
// A 29-bit extended ID encodes: Priority(3) | EDP(1) | DP(1) | PF(8) | PS(8) | SA(8)
struct J1939 {
    uint8_t  priority;
    uint32_t pgn;
    uint8_t  sa;      // source address (which module sent it)
    bool     valid;   // only meaningful for extended (29-bit) frames
};

J1939 decodeJ1939(uint32_t id, bool ext) {
    J1939 j = {0, 0, 0, false};
    if (!ext) return j;                 // J1939 is always 29-bit
    j.priority = (id >> 26) & 0x7;
    uint8_t dp  = (id >> 24) & 0x1;      // data page
    uint8_t pf  = (id >> 16) & 0xFF;     // PDU format
    uint8_t ps  = (id >> 8)  & 0xFF;     // PDU specific (dest addr or group ext)
    j.sa        =  id        & 0xFF;     // source address
    if (pf < 240) {                      // PDU1: PS = destination address
        j.pgn = ((uint32_t)dp << 16) | ((uint32_t)pf << 8);
    } else {                             // PDU2: PS = group extension
        j.pgn = ((uint32_t)dp << 16) | ((uint32_t)pf << 8) | ps;
    }
    j.valid = true;
    return j;
}

uint32_t detectedBitrate = 0;
const char *detectedName = "?";
bool haveSpeed = false;

// When a CAN frame was last seen. The bus is silent with the ignition off and
// busy the moment it comes on, so this IS the ignition, read straight off the
// wire without decoding anything.
//
// Found the long way round on 2026-09-04: a bit in PGN 65386 goes high when the
// bike wakes, and an afternoon went into it before the obvious sank in -- that
// PGN transmits every 11 seconds and catches a button press about one time in
// ten, while the bus itself announces the same event instantly and every time.
// The signal we were hunting was underneath the whole search.
uint32_t lastFrameMs = 0;

// When the front wheel speed was last actually reported. A sensor can fail in
// three ways -- report zero, report 0xFFFF "not available", or stop being sent
// at all -- and only the first shows up as a value. The other two leave the
// last good reading sitting in place looking healthy, which is the worst
// possible failure for a warning system. See wheelCheck().
uint32_t speedFrontAt = 0;

// Brief front-sensor dropouts since boot. The count, not any single one, is the
// signal: an active Hall sensor being worn back fails intermittently long
// before it fails for good. See wheelCheck().
// The three tallies now live in NVS -- see counters.h. They were RAM globals
// for one evening, which defeated their purpose: thirty reboots in a day, and
// every OTA zeroing the evidence.
uint32_t speedRearAt = 0;
uint32_t scanFrames = 0;   // frames counted during the last bitrate detection

// Per-ID state so MQTT only publishes what changed (keeps the bus off the air).
// DISCOVERY-only: the raw-firehose publisher. Production decodes in-firmware
// and never builds this table.
#if FIRMWARE_MODE == MODE_DISCOVERY
struct IdState {
    uint8_t  dlc;
    uint8_t  data[8];
    uint32_t count;
    bool     ext;     // 29-bit extended frame (J1939) vs 11-bit standard
    bool     dirty;   // changed since last MQTT publish
};
std::map<uint32_t, IdState> idTable;
#endif

#if ENABLE_MQTT
WiFiClientSecure wifiClient;
PubSubClient mqtt(wifiClient);
#if FIRMWARE_MODE == MODE_DISCOVERY
uint32_t lastPublish = 0;             // discovery publishChanges() throttle
#endif
uint32_t lastHeartbeat = 0;
bool noCanReported = false;

String topic(const char *leaf) { return String(MQTT_BASE_TOPIC) + "/" + leaf; }

// Per-board unique MQTT client ID = MQTT_CLIENT_ID + "-" + last 3 MAC bytes.
// Two boards flashed from the SAME config.h would otherwise share the client ID
// "indian-canbus"; MQTT brokers evict the older session when a duplicate client
// ID connects, so identical IDs make the boards kick each other off the broker
// in an endless offline/online war. The MAC suffix guarantees uniqueness with
// zero per-board config, while the base TOPIC stays "canbus/indian" so openHAB
// (things/items/sitemap/OTA rule) needs no changes. Cached after first call.
const char *mqttClientId() {
    static String id;
    if (id.length() == 0) {
        uint8_t mac[6];
        WiFi.macAddress(mac);
        char suffix[8];
        snprintf(suffix, sizeof(suffix), "-%02X%02X%02X", mac[3], mac[4], mac[5]);
        id = String(MQTT_CLIENT_ID) + suffix;
    }
    return id.c_str();
}

void mqttDebugPublish(const char *msg) {
    if (!mqtt.connected()) {
        return;
    }
    mqtt.publish(topic("debug").c_str(), (const uint8_t *) msg, strlen(msg), false);
}

void logEvent(const char *msg) {
    Serial.println(msg);
    mqttDebugPublish(msg);
}

void logEventf(const char *fmt, ...) {
    char buf[192];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    Serial.println(buf);
    mqttDebugPublish(buf);
}

const char *mqttStateText(int8_t state) {
    switch (state) {
        case MQTT_CONNECTION_TIMEOUT:
            return "connection timeout";
        case MQTT_CONNECTION_LOST:
            return "connection lost";
        case MQTT_CONNECT_FAILED:
            return "connect failed";
        case MQTT_DISCONNECTED:
            return "disconnected";
        case MQTT_CONNECTED:
            return "connected";
        case MQTT_CONNECT_BAD_PROTOCOL:
            return "bad protocol";
        case MQTT_CONNECT_BAD_CLIENT_ID:
            return "bad client id";
        case MQTT_CONNECT_UNAVAILABLE:
            return "broker unavailable";
        case MQTT_CONNECT_BAD_CREDENTIALS:
            return "bad credentials";
        case MQTT_CONNECT_UNAUTHORIZED:
            return "unauthorized";
        default:
            return "unknown";
    }
}

bool waitForClockSync(uint32_t timeoutMs = 20000) {
    const time_t minValidEpoch = 1700000000; // 2023-11-14 UTC-ish: good enough for TLS validity checks
    uint32_t start = millis();
    time_t now = time(nullptr);
    while (now < minValidEpoch && millis() - start < timeoutMs) {
        delay(250);
        now = time(nullptr);
    }
    if (now < minValidEpoch) {
        logEvent("[time] SNTP sync FAILED - TLS handshake may fail");
        return false;
    }

    struct tm tmNow;
    gmtime_r(&now, &tmNow);
    char buf[32];
    strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S UTC", &tmNow);
    logEventf("[time] SNTP synced: %s", buf);
    return true;
}

// ---- OTA & MQTT message handling ----

// Publish a human-readable OTA status line, retained so the openHAB UI still
// shows the last result after a reconnect. We flush directly via mqtt.publish()
// (PubSubClient writes synchronously) so it works even while the blocking HTTP
// download is running and the normal mqtt.loop() is not being serviced.
static void publishOtaStatus(const char* msg) {
    if (mqtt.connected()) {
        mqtt.publish(topic("ota/status").c_str(), msg, true);
    }
    logEventf("[ota] status: %s", msg);
}

// ---------------------------------------------------------------------------
// OTA dead man's switch.
//
// On 2026-09-04 httpUpdate.update() reached 100 % and then simply stopped: no
// reboot, no error, no further MQTT. The whole loop was blocked inside the
// call, so nothing was left running to notice -- and the device had to be
// recovered over USB, which on a bike means taking it off the bike.
//
// esp_timer callbacks run from their own task, so this fires even when loop()
// is wedged. The timer is refreshed on every progress report; if the download
// stalls for OTA_STALL_MS the chip restarts. That is safe: the boot partition
// is only switched after the image is written AND validated, so a restart part
// way through comes back up on the firmware that is already running.
//
// A stalled OTA should cost a reboot, never a trip to the garage with a cable.
//
// The window has to clear the SILENT phase at the end. Progress callbacks stop
// at 100 %, and the image is then written out and verified with nothing
// reported: a bench run on 2026-09-04 took 29 seconds between "Downloading
// 100 %" and the reboot.
//
// Sized twice, and the second time from better data. 45 s came first, then 120
// after a bench run showed 29 seconds of silence. Then the same firmware
// updated on the bike and the silent phase took **160 seconds** -- a bench with
// a strong signal is not the worst case, and the guard would have restarted the
// chip in the middle of a healthy update. Which is the exact failure it exists
// to prevent, arrived at from the other direction.
//
// 300 s is set against the worst observed (160) with most of a factor of two
// spare. A hang bounded to five minutes is still the whole point; a guard that
// fires on healthy updates is worse than no guard at all, because it turns a
// working feature into a coin toss.
// ---------------------------------------------------------------------------
#define OTA_STALL_MS 300000

static esp_timer_handle_t otaGuard = nullptr;

static void otaGuardFired(void *) {
    // Nothing here can be trusted to reach the network, so just go.
    esp_restart();
}

static void otaGuardStart() {
    if (!otaGuard) {
        const esp_timer_create_args_t args = {
            .callback = &otaGuardFired,
            .arg = nullptr,
            .dispatch_method = ESP_TIMER_TASK,
            .name = "ota_guard",
            .skip_unhandled_events = false
        };
        if (esp_timer_create(&args, &otaGuard) != ESP_OK) return;
    }
    esp_timer_stop(otaGuard);
    esp_timer_start_once(otaGuard, (uint64_t)OTA_STALL_MS * 1000);
}

static void otaGuardKick() {
    if (otaGuard) { esp_timer_stop(otaGuard); esp_timer_start_once(otaGuard, (uint64_t)OTA_STALL_MS * 1000); }
}

static void otaGuardStop() {
    if (otaGuard) esp_timer_stop(otaGuard);
}

// Retained, so openHAB shows the truth after a restart of either end.
static void publishProbeFlags() {
    char buf[160];
    size_t n = probeFlagsJson(buf, sizeof(buf));
    mqtt.publish(topic("probe/enabled").c_str(), (const uint8_t *)buf, n, true);
}

void onMqttMessage(char* inTopic, byte* payload, unsigned int length) {
    String payloadStr = String((char*)payload).substring(0, length);

    // Probe switches: canbus/<base>/probe/en/<name>  payload ON or OFF.
    //
    // One topic per probe rather than a single command channel, because that is
    // what an openHAB Switch channel binds to directly -- no rule, no parsing,
    // and the item works from the phone anywhere there is a network.
    //
    // This does not touch the CAN bus. It is our own code deciding whether to
    // talk to our own broker.
    {
        String prefix = topic("probe/en/");
        String t = String(inTopic);
        if (t.startsWith(prefix)) {
            String name = t.substring(prefix.length());
            int id = probeIdFromName(name.c_str());
            if (id >= 0) {
                String v = payloadStr; v.toUpperCase();
                bool on = (v == "ON" || v == "1" || v == "TRUE");
                probeSetEnabled((ProbeId)id, on);
                logEvent((String("[probe] ") + name + " -> " + (on ? "ON" : "OFF")).c_str());
                publishProbeFlags();
            }
            return;
        }
    }
    // OTA trigger: canbus/indian/ota = "update" (case-insensitive)
    if (String(inTopic) == topic("ota")) {
        payloadStr.toLowerCase();
        if (payloadStr == "update") {
            logEvent("[ota] update trigger received via MQTT, starting HTTP download...");
            publishOtaStatus("Starting download...");

            // Report download progress back to openHAB every ~10%.
            httpUpdate.onProgress([](int cur, int total) {
                static int lastPct = -1;
                int pct = (total > 0) ? (int)((int64_t)cur * 100 / total) : 0;
                otaGuardKick();          // progress means it is still alive
                if (pct != lastPct && (pct % 10 == 0)) {
                    lastPct = pct;
                    char m[40];
                    snprintf(m, sizeof(m), "Downloading %d%%", pct);
                    publishOtaStatus(m);
                }
            });
            httpUpdate.rebootOnUpdate(true);
            otaGuardStart();             // armed until the download finishes

            // Download firmware from OpenHAB web server (URL in config.h — use
            // the server LAN IP, not openhab.local: WiFiClient has no mDNS).
            WiFiClient client;
            // A socket that goes quiet must not block for ever either.
            client.setTimeout(15);       // seconds, per read
            t_httpUpdate_return ret = httpUpdate.update(client, OTA_FIRMWARE_URL);
            otaGuardStop();              // returned, whatever the outcome
            switch(ret) {
                case HTTP_UPDATE_FAILED: {
                    char m[96];
                    snprintf(m, sizeof(m), "FAILED (%d): %s",
                             httpUpdate.getLastError(),
                             httpUpdate.getLastErrorString().c_str());
                    publishOtaStatus(m);
                    break;
                }
                case HTTP_UPDATE_NO_UPDATES:
                    publishOtaStatus("No update available");
                    break;
                case HTTP_UPDATE_OK:
                    // Rarely reached: rebootOnUpdate(true) restarts before this.
                    publishOtaStatus("OK — rebooting");
                    break;
            }
        }
    }
}

void mqttConnect() {
    if (mqtt.connected()) return;
    String willTopic = topic("status");
    Serial.printf("[mqtt] connecting to %s:%d as clientId=%s ...\n",
                  MQTT_BROKER, MQTT_PORT, mqttClientId());
    if (mqtt.connect(mqttClientId(), MQTT_USERNAME, MQTT_PASSWORD,
                     willTopic.c_str(), 0, true, "offline")) {
        mqtt.publish(willTopic.c_str(), "online", true);
        mqtt.setCallback(onMqttMessage);           // Set callback for incoming messages
        mqtt.subscribe(topic("ota").c_str());      // Subscribe to OTA command topic
        mqtt.subscribe(topic("probe/en/+").c_str());   // one switch per probe
        publishProbeFlags();                           // state, retained
        // Announce the running firmware version (retained) so the UI confirms
        // which image is live -- especially right after an OTA reboot.
        //
        // ONCE PER BOOT, not once per reconnect. It used to publish on every
        // MQTT connection, which made the line indistinguishable from a boot
        // banner: on 2026-09-04 a reconnect storm read as a reboot loop, and
        // several hours went into chasing a crash that had never happened. The
        // device had been up continuously the whole time.
        //
        // The topic is retained, so publishing once still leaves the UI with
        // the right answer after a reconnect. What a reboot actually looks like
        // is meta: "reset" changes, and "heap_min" jumps back up instead of
        // creeping down.
        static bool announced = false;
        if (!announced) {
            announced = true;
            mqtt.publish(topic("ota/status").c_str(), "Running " FW_VERSION, true);
        }
        logEvent("[mqtt] connected");
    } else {
        logEventf("[mqtt] connect failed: state=%d (%s)",
                  mqtt.state(), mqttStateText(mqtt.state()));
    }
}

void wifiConnect() {
    WiFi.mode(WIFI_STA);
    const uint32_t WIFI_TIMEOUT_MS = 10000;  // 10 seconds per SSID
    const char* ssidList[] = {WIFI_SSID, WIFI_SSID2, WIFI_SSID3};
    const char* passList[] = {WIFI_PASSWORD, WIFI_PASSWORD2, WIFI_PASSWORD3};
    
    for (int idx = 0; idx < 3; idx++) {
        // Skip if SSID is empty (fallback disabled)
        if (!ssidList[idx] || strlen(ssidList[idx]) == 0) continue;
        
        Serial.printf("[wifi] attempting SSID%d: %s", idx + 1, ssidList[idx]);
        // Fully reset the STA state before each attempt. Without this, starting
        // a new WiFi.begin() while the previous attempt is still "connecting"
        // fails with "wifi:sta is connecting, cannot set config" /
        // ESP_ERR_WIFI_STATE (0x3006), so failover to SSID2/SSID3 never worked.
        WiFi.disconnect(true);   // drop connection + clear stored config
        delay(200);              // let the WiFi driver settle into idle
        WiFi.begin(ssidList[idx], passList[idx]);
        uint32_t start = millis();
        while (WiFi.status() != WL_CONNECTED && millis() - start < WIFI_TIMEOUT_MS) {
            delay(300); Serial.print(".");
        }
        Serial.println();
        
        if (WiFi.status() == WL_CONNECTED) {
            Serial.printf("[wifi] connected to SSID%d\n", idx + 1);
            Serial.printf("[wifi] IP %s\n", WiFi.localIP().toString().c_str());
            IPAddress brokerIp;
            bool brokerResolved = false;
            if (WiFi.hostByName(MQTT_BROKER, brokerIp)) {
                brokerResolved = true;
                Serial.printf("[wifi] broker %s resolved to %s\n", MQTT_BROKER, brokerIp.toString().c_str());
            } else {
                Serial.printf("[wifi] DNS failed for broker %s\n", MQTT_BROKER);
            }

            configTime(0, 0, "dk.pool.ntp.org", "pool.ntp.org", "time.cloudflare.com");
            waitForClockSync();

            wifiClient.setCACert(MQTT_ROOT_CA);
            mqtt.setServer(MQTT_BROKER, MQTT_PORT);
            mqtt.setBufferSize(3072);        // room for reassembled TP messages
            // Keepalive well ABOVE the 30 s heartbeat so a single slow/dropped
            // PINGRESP can't fire the LWT. Matters most when the ESP rides on a
            // phone hotspot: the broker is reached via a cellular->internet->home
            // NAT hairpin that adds latency + occasional packet loss, so the old
            // 30 s keepalive / 10 s socket timeout produced an "online then ~30 s
            // offline" flap whenever the bus was idle (no CAN traffic to keep the
            // TCP socket warm). 60 s keepalive + 15 s socket timeout absorbs that.
            mqtt.setKeepAlive(60);
            mqtt.setSocketTimeout(15);
            mqtt.setCallback(onMqttMessage);  // Set message callback BEFORE connect
            mqttConnect();
            if (mqtt.connected()) {
                logEventf("[wifi] connected to SSID%d, IP %s", idx + 1, WiFi.localIP().toString().c_str());
                if (brokerResolved) {
                    logEventf("[wifi] broker %s resolved to %s", MQTT_BROKER, brokerIp.toString().c_str());
                } else {
                    logEventf("[wifi] DNS failed for broker %s", MQTT_BROKER);
                }

                // ---- OTA Setup ----
                ArduinoOTA.setHostname(mqttClientId());
                ArduinoOTA.setPassword(""  /* no auth password for now */);
                ArduinoOTA.setTimeout(120000);  // 120 second timeout for high-latency links
                ArduinoOTA.onStart([]() {
                    logEvent("[ota] OTA update starting...");
                });
                ArduinoOTA.onEnd([]() {
                    logEvent("[ota] OTA update complete, rebooting...");
                });
                ArduinoOTA.onError([](ota_error_t error) {
                    logEventf("[ota] OTA error code %u", error);
                });
                ArduinoOTA.begin();
                logEvent("[ota] ready (hostname: indian-canbus.local:3232/update)");
            }
            return;  // Connected successfully, exit function
        }
    }
    
    // All WiFi attempts failed
    Serial.printf("[wifi] FAILED - all SSID attempts exhausted, continuing USB-only\n");
    logEvent("[wifi] FAILED - all SSID attempts exhausted, continuing USB-only");
}

// Publish all dirty IDs as compact JSON, respecting the throttle interval.
// Each ID goes to its own retained topic  <base>/id/0x<ID>  so openHAB can bind
// a channel per ID. Also mirrors the newest change to <base>/frame (non-retained).
// DISCOVERY-only: production uses publishState() instead.
#if FIRMWARE_MODE == MODE_DISCOVERY
void publishChanges() {
    if (millis() - lastPublish < MQTT_PUBLISH_INTERVAL_MS) return;
    lastPublish = millis();
    if (!mqtt.connected()) { mqttConnect(); if (!mqtt.connected()) return; }

    String frameTopic = topic("frame");
    for (auto &kv : idTable) {
        if (!kv.second.dirty) continue;
        kv.second.dirty = false;

        JsonDocument doc;
        char idHex[12];
        snprintf(idHex, sizeof(idHex), "0x%X", kv.first);
        doc["id"]    = idHex;
        doc["dlc"]   = kv.second.dlc;
        doc["count"] = kv.second.count;
        // SAE J1939 decode (Polaris/Indian). Only meaningful for 29-bit frames.
        J1939 j = decodeJ1939(kv.first, kv.second.ext);
        if (j.valid) {
            doc["pgn"] = j.pgn;
            doc["sa"]  = j.sa;
            doc["pri"] = j.priority;
        }
        JsonArray bytes = doc["data"].to<JsonArray>();
        for (int i = 0; i < kv.second.dlc; i++) bytes.add(kv.second.data[i]);
        // Hex string of the payload, handy for MAP/JS transforms in openHAB.
        char hex[24]; int p = 0;
        for (int i = 0; i < kv.second.dlc; i++)
            p += snprintf(hex + p, sizeof(hex) - p, "%02X", kv.second.data[i]);
        doc["hex"] = hex;

        char payload[288];
        size_t n = serializeJson(doc, payload, sizeof(payload));

        // Per-ID retained topic (openHAB-friendly), e.g. canbus/indian/id/0x18FEF100
        String idTopic = String(MQTT_BASE_TOPIC) + "/id/" + idHex;
        mqtt.publish(idTopic.c_str(), (const uint8_t *)payload, n, true);
        // Per-PGN retained topic for J1939 frames, e.g. canbus/indian/pgn/65262
        // (same payload). Groups all source addresses of one parameter group.
        if (j.valid) {
            String pgnTopic = String(MQTT_BASE_TOPIC) + "/pgn/" + String((unsigned long)j.pgn);
            mqtt.publish(pgnTopic.c_str(), (const uint8_t *)payload, n, true);

            // --- TPMS split: PGN 65268 (0xFEF4) Tire Condition ---------------
            // Front & rear share this PGN. J1939 SPN 929 "Tyre Location" is in
            // byte 0 (axle*? position); we also fall back to the source address
            // so each wheel lands on its own retained topic:
            //   canbus/indian/tpms/front , canbus/indian/tpms/rear
            //   canbus/indian/tpms/sa/<SA>   (always, so nothing is lost)
            if (j.pgn == 65268 && kv.second.dlc >= 2) {
                uint8_t loc = kv.second.data[0];         // tyre location code
                const char *pos = nullptr;
                // Common J1939 axle/position: 0x00=front-ish, higher=rear-ish.
                // These are heuristics; verify on the bike and adjust if needed.
                if (loc == 0x00 || loc == 0x11) pos = "front";
                else if (loc == 0x10 || loc == 0x21 || loc == 0x01) pos = "rear";
                if (pos) {
                    String t = String(MQTT_BASE_TOPIC) + "/tpms/" + pos;
                    mqtt.publish(t.c_str(), (const uint8_t *)payload, n, true);
                }
                // Always publish per source address as a reliable fallback.
                String saT = String(MQTT_BASE_TOPIC) + "/tpms/sa/" + String(j.sa);
                mqtt.publish(saT.c_str(), (const uint8_t *)payload, n, true);
            }
        }
        // Rolling "latest change" stream (non-retained)
        mqtt.publish(frameTopic.c_str(), (const uint8_t *)payload, n, false);
    }
}
#endif  // FIRMWARE_MODE == MODE_DISCOVERY (publishChanges)

// Why the chip last restarted, as one short word for the meta topic.
static const char *resetReasonName() {
    switch (esp_reset_reason()) {
        case ESP_RST_POWERON:  return "poweron";
        case ESP_RST_EXT:      return "external";
        case ESP_RST_SW:       return "sw";        // OTA or esp_restart -- expected
        case ESP_RST_PANIC:    return "panic";     // crash: bad pointer, assert
        case ESP_RST_INT_WDT:  return "int_wdt";   // interrupt watchdog
        case ESP_RST_TASK_WDT: return "task_wdt";  // a task starved the scheduler
        case ESP_RST_WDT:      return "wdt";
        case ESP_RST_DEEPSLEEP:return "deepsleep";
        case ESP_RST_BROWNOUT: return "brownout";  // supply sagged
        case ESP_RST_SDIO:     return "sdio";
        default:               return "unknown";
    }
}

void publishHeartbeat() {
    if (!mqtt.connected()) { mqttConnect(); if (!mqtt.connected()) return; }
    if (millis() - lastHeartbeat < 30000) return;
    lastHeartbeat = millis();

    mqtt.publish(topic("status").c_str(), "online", true);

    JsonDocument meta;
    meta["bitrate"] = haveSpeed ? detectedName : "none";
    meta["scan_frames"] = scanFrames;   // real detection count, not 0
    meta["can_detected"] = haveSpeed;
    meta["ip"] = WiFi.localIP().toString();
    meta["fw"] = FW_VERSION;             // running firmware version (OTA verify)
    // Why the last boot happened, and how much memory is left.
    //
    // Added 2026-09-04 after the device turned out to be REBOOTING rather than
    // losing its radio: BLE dropped every few seconds, MQTT flapped in the same
    // windows, and both were symptoms rather than the fault. Without this the
    // only evidence a reset happened at all was the boot banner appearing twice
    // in a minute, which says nothing about the cause -- and panic, watchdog and
    // brownout are three different faults with three different fixes.
    //
    // "sw" is the ordinary one: an OTA reboot, or esp_restart. Anything else on
    // a device that has not just been flashed is worth chasing.
    meta["reset"] = resetReasonName();
    meta["heap"] = (uint32_t)ESP.getFreeHeap();
    meta["heap_min"] = (uint32_t)ESP.getMinFreeHeap();   // low-water mark; a leak shows here
    // Seconds since boot. The single unambiguous answer to "did it restart?",
    // which is the question that cost the most time on 2026-09-04.
    meta["uptime"] = (uint32_t)(millis() / 1000);
    char buf[260];
    size_t n = serializeJson(meta, buf, sizeof(buf));
    mqtt.publish(topic("meta").c_str(), (const uint8_t *)buf, n, true);
}

#if FIRMWARE_MODE == MODE_PRODUCTION
// ===========================================================================
// PRODUCTION MODE — decode every CONFIRMED signal IN-FIRMWARE and publish ONE
// compact retained JSON on  canbus/indian/state  (final display-ready values).
// ---------------------------------------------------------------------------
// This replaces the ~25 openHAB JS transforms and the 50+ per-id/pgn MQTT
// messages with a single 1 Hz publish. Every scale below is copied verbatim
// from the matching transform/canbus_*.js so the output is byte-identical to
// what openHAB showed before. Multi-source PGNs are filtered by source address
// here (the firmware sees the SA on every frame), which is MORE reliable than
// the per-ID topic trick used on the openHAB side.
//
// Fields (unavailable = key omitted -> the openHAB item keeps its last value,
// exactly like the old transforms returning ""):
//   rpm throttle gear coolant speed fuel odometer trip fuelEconomy battery
//   ambient tyreFront tyreRear tyreFrontTemp tyreRearTemp brakeRear
//   cruise cruiseEnable cruiseSw hazard headlight indLeft indRight
//   dm1 dm1Raw vin softwareId
// VIN + softwareId (PGN 65242 SOFT) and DM1 (PGN 65226) are decoded here too:
// the identity/diagnostic TP/BAM messages are reassembled (see tpComplete) and
// single-frame DM1 is handled in decodeState(). Static VIN/SW are set once and
// then ride along in every retained publish.
// ===========================================================================
// struct VehState now lives in vehstate.h — both this file and ble.cpp read it.
VehState st;
bool     stateDirty  = false;
uint32_t lastStatePub = 0;

// Heartbeat: even when nothing on the bus changes (bike parked / ignition off),
// republish the full state at least this often so the openHAB UI keeps showing
// a recent value instead of a frozen one. 0 disables (change-only behaviour).
#ifndef STATE_HEARTBEAT_MS
#define STATE_HEARTBEAT_MS 30000
#endif

// Minimum spacing between PRODUCTION /state publishes. Production sends ONE
// compact JSON per cycle, so this can be far tighter than the DISCOVERY rate.
// Falls back to MQTT_PUBLISH_INTERVAL_MS if config.h predates this define.
#ifndef STATE_PUBLISH_INTERVAL_MS
#define STATE_PUBLISH_INTERVAL_MS MQTT_PUBLISH_INTERVAL_MS
#endif

void resetState() {
    st.rpm = st.throttle = st.coolant = st.speed = st.fuel = NAN;
    st.odometer = st.trip = st.fuelEcon = st.battery = st.ambient = NAN;
    st.tyreFront = st.tyreRear = st.tyreFrontTemp = st.tyreRearTemp = NAN;
    st.gripTempL = st.gripTempR = NAN;
    st.speedFront = NAN;
    st.fuelRate = st.fuelEconInst = NAN;
    st.wheels[0] = 0;
    st.gear[0] = 0; st.headlight[0] = 0; st.security[0] = 0;
    st.vin[0] = st.swid[0] = st.dm1[0] = st.dm1Raw[0] = 0;
    st.brakeRear = st.cruiseEnable = st.cruiseSw = st.hazard = -1;
    st.cruiseHold = st.clutch = -1;
    st.indLeft = st.indRight = -1;
    st.grips = -1;
    st.lean = -1;
    st.stand[0] = 0;
}

#define SETF(f, v) do { float _v = (v); \
    if (isnan(st.f) || fabsf(st.f - _v) > 1e-4f) { st.f = _v; stateDirty = true; } } while (0)
// For the tri-state switch flags: -1 unknown, 0 off, 1 on.
#define SETI(f, v) do { int8_t _v = (int8_t)(v); \
    if (st.f != _v) { st.f = _v; stateDirty = true; } } while (0)
// For fields wider than a switch. SETI casts to int8_t, which is right for the
// flags it was written for and silently wrong for anything else: used on the
// tilt reading it turned every value above 127 negative, so leaning the bike
// to the RIGHT of vertical made the field vanish from the state entirely and
// put a "no reading" strike across the graphic. Three attempts went into the
// thresholds before the cast turned out to be the whole story.
#define SETI16(f, v) do { int16_t _v = (int16_t)(v); \
    if (st.f != _v) { st.f = _v; stateDirty = true; } } while (0)
#define SETS(f, v) do { const char *_s = (v); \
    if (strncmp(st.f, _s, sizeof(st.f)) != 0) { \
        strncpy(st.f, _s, sizeof(st.f) - 1); st.f[sizeof(st.f) - 1] = 0; stateDirty = true; } } while (0)

// DM1 (PGN 65226) active-DTC decode — mirrors transform/canbus_dm1.js.
// Works on a single 8-byte frame OR a reassembled TP buffer (b, nn).
// DM1 is broadcast by SEVERAL modules, each reporting only its own faults.
//
// Three send it here — SA 0, 11 and 39 — and the decoder used to write whichever
// arrived last straight into st.dm1. So a module holding a fault and a module
// holding none overwrote each other in turn, and the state alternated between
// "SPN 520304 FMI 12" and "No active DTC" about twice a second. Two hours of
// that is in the openHAB history for 3 September: 109 samples with the fault
// and 1,808 without, interleaved.
//
// On the phone it meant the warning banner appeared and vanished at the same
// rate — which is indistinguishable from no warning at all, and is why a key fob
// battery the dash was plainly complaining about raised nothing in the app.
//
// So each source keeps its own slot, and the published state is the union of
// whatever every live source is saying. A lamp is lit if ANY module lights it,
// which is what a lamp on a dashboard means.
#define DM1_SOURCES   4
#define DM1_STALE_MS  10000u

struct Dm1Src {
    bool     used;
    uint8_t  sa;
    uint8_t  lamps;
    uint32_t seen;
    char     dtc[72];        // this source's faults, "" when it reports none
};
static Dm1Src dm1Src[DM1_SOURCES];

// ---------------------------------------------------------------------------
// Cruise control, DERIVED.
//
// SPN 595 is not transmitted on this bus -- proved on the road 2026-09-05 with
// the cruise demonstrably holding and byte 4 unmoved, while byte 5 reported
// every button press in the same frames. So the engaged state cannot be read.
// It can, however, be worked out, and every input below is a MEASURED signal:
// only the rule joining them is inferred.
//
//   SPN 596  the rocker is on
//   SPN 599  SET pressed        SPN 601  RESUME pressed
//   SPN 597  brake              SPN 598  clutch
//   speed
//
// The confirmation is what makes this more than a guess. A press only ARMS the
// state; it becomes HOLDING when the speed then sits still for three seconds.
// Cruise that never caught -- too slow, wrong gear, a press that did nothing --
// never produces a steady speed, and so never claims to be holding.
//
// It is also what handles the exits we cannot see. The rider can drop the cruise
// with a small backward flick of the grip, and that is invisible to us: SPN 91,
// the rider's own demand, is not on this bus either. But the moment the cruise
// lets go, the speed stops sitting still -- so the same test that confirms the
// engagement also ends it, without needing a list of every way out.
//
// Brake, clutch and the rocker are hard exits, because they are certain.
// ---------------------------------------------------------------------------
#define CRUISE_MIN_KMH     35.0f   // below this the system will not engage
#define CRUISE_BAND_KMH     3.0f   // "sitting still" while arming
#define CRUISE_DROP_KMH     6.0f   // drifted this far from target = let go
#define CRUISE_CONFIRM_MS  3000u   // steady this long before claiming a hold
#define CRUISE_DROP_MS     2000u   // drifted this long before giving it up

static uint32_t cruiseArmedAt = 0;      // 0 = not arming
static float    cruiseTarget  = NAN;
static uint32_t cruiseDriftAt = 0;

// A SET or RESUME press. Arms only; the speed decides whether it took.
static void cruiseArm() {
    if (st.cruiseEnable != 1) return;
    if (isnan(st.speed) || st.speed < CRUISE_MIN_KMH) return;
    cruiseArmedAt = millis();
    cruiseTarget  = st.speed;
    cruiseDriftAt = 0;
}

static void cruiseClear() {
    cruiseArmedAt = 0; cruiseTarget = NAN; cruiseDriftAt = 0;
    SETI(cruiseHold, 0);
}

// Called from the main loop, once per pass.
static void cruiseUpdate() {
    // Hard exits first: these are measured and certain.
    if (st.cruiseEnable != 1 || st.brakeRear == 1 || st.clutch == 1) {
        if (st.cruiseHold != 0 || cruiseArmedAt) cruiseClear();
        return;
    }
    if (isnan(st.speed) || isnan(cruiseTarget)) return;

    const uint32_t now = millis();
    const float off = fabsf(st.speed - cruiseTarget);

    if (st.cruiseHold == 1) {
        // Holding. Drifting away from the target, and staying away, means the
        // cruise has let go -- by a flick of the grip or anything else.
        if (off > CRUISE_DROP_KMH) {
            if (!cruiseDriftAt) cruiseDriftAt = now;
            else if (now - cruiseDriftAt > CRUISE_DROP_MS) cruiseClear();
        } else {
            cruiseDriftAt = 0;
            // Track slow legitimate changes: RESUME/ACCEL nudges the set speed,
            // and a long hill moves it a little either way.
            //
            // TIME-GATED, and that is not a detail. This runs from the main
            // loop, which on an ESP32 turns over thousands of times a second,
            // so an unconditional 2 % pull would have the target catching the
            // real speed inside a fraction of a second -- and the drift exit
            // below could then never fire, because the thing it measures
            // against would already have moved. The cruise would appear to hold
            // forever. Once a second, 2 %, gives a time constant near a minute,
            // which is slower than any real drop-out and faster than a hill.
            static uint32_t adaptAt = 0;
            if (now - adaptAt >= 1000) {
                adaptAt = now;
                cruiseTarget += (st.speed - cruiseTarget) * 0.02f;
            }
        }
        return;
    }

    if (!cruiseArmedAt) return;

    // Arming. Wander outside the band and the press simply did not take.
    if (off > CRUISE_BAND_KMH) { cruiseClear(); return; }
    if (now - cruiseArmedAt >= CRUISE_CONFIRM_MS) {
        cruiseArmedAt = 0;
        SETI(cruiseHold, 1);
    }
}

void decodeDM1(uint8_t sa, const uint8_t *b, uint16_t nn) {
    if (nn < 2) return;

    char hx[20]; int hp = 0;
    for (uint16_t i = 0; i < nn && hp < (int)sizeof(hx) - 2; i++)
        hp += snprintf(hx + hp, sizeof(hx) - hp, "%02X", b[i]);
    hx[hp] = 0; SETS(dm1Raw, hx);

    // --- what THIS source is reporting -------------------------------------
    char out[72]; int op = 0; int ndtc = 0;
    for (uint16_t i = 2; i + 3 < nn; i += 4) {
        uint32_t spn = b[i] | (b[i + 1] << 8) | (((uint32_t)(b[i + 2] >> 5) & 7) << 16);
        uint8_t fmi = b[i + 2] & 0x1F;
        uint8_t oc  = b[i + 3] & 0x7F;
        if (spn == 0 && fmi == 0) continue;                        // empty slot
        if (spn == 0x7FFFF || (b[i] == 0xFF && b[i + 1] == 0xFF)) continue;
        op += snprintf(out + op, sizeof(out) - op, "%sSPN %lu FMI %u (x%u)",
                       ndtc ? "; " : "", (unsigned long)spn, fmi, oc);
        if (++ndtc && op > (int)sizeof(out) - 24) break;
    }
    out[op] = 0;

    // --- park it in this source's slot --------------------------------------
    const uint32_t now = millis();
    int slot = -1;
    for (int i = 0; i < DM1_SOURCES; i++) if (dm1Src[i].used && dm1Src[i].sa == sa) slot = i;
    if (slot < 0) for (int i = 0; i < DM1_SOURCES; i++) if (!dm1Src[i].used) { slot = i; break; }
    // Every slot taken by a live source: reuse the stalest rather than drop this.
    if (slot < 0) { slot = 0;
        for (int i = 1; i < DM1_SOURCES; i++) if (dm1Src[i].seen < dm1Src[slot].seen) slot = i; }
    dm1Src[slot].used  = true;
    dm1Src[slot].sa    = sa;
    dm1Src[slot].lamps = b[0];
    dm1Src[slot].seen  = now;
    strncpy(dm1Src[slot].dtc, out, sizeof(dm1Src[slot].dtc) - 1);
    dm1Src[slot].dtc[sizeof(dm1Src[slot].dtc) - 1] = 0;

    // --- compose from every source still talking ----------------------------
    char all[96]; int ap = 0; int total = 0;
    uint8_t lamps = 0;
    for (int i = 0; i < DM1_SOURCES; i++) {
        if (!dm1Src[i].used) continue;
        if (now - dm1Src[i].seen > DM1_STALE_MS) { dm1Src[i].used = false; continue; }
        // A lamp is lit if any module lights it. The 2-bit fields are ORed
        // pairwise, so 01 from one source survives 00 from the others.
        for (uint8_t sh = 0; sh <= 6; sh += 2)
            if (((dm1Src[i].lamps >> sh) & 3) == 1) lamps |= (uint8_t)(1u << sh);
        if (!dm1Src[i].dtc[0]) continue;
        int n = snprintf(all + ap, sizeof(all) - ap, "%s%s", total ? "; " : "", dm1Src[i].dtc);
        if (n > 0) ap += n;
        total++;
        if (ap > (int)sizeof(all) - 24) break;
    }
    all[ap] = 0;

    #define LMP(sh) (((lamps >> (sh)) & 1) ? "ON" : "off")
    char full[112];
    snprintf(full, sizeof(full), "%s | MIL:%s Stop:%s Warn:%s Prot:%s",
             total ? all : "No active DTC", LMP(6), LMP(4), LMP(2), LMP(0));
    #undef LMP
    SETS(dm1, full);
}

// SOFT (PGN 65242) identity record — mirrors canbus_vin.js / canbus_swid.js.
// The reassembled payload is a '*'-delimited ASCII record; we pull the 17-char
// VIN and the full ' | '-joined record.
void decodeSoft(const uint8_t *b, uint16_t nn) {
    char field[48]; int fp = 0;
    char rec[112];  int rp = 0; bool recStarted = false;
    char vinFound[18]; vinFound[0] = 0;
    for (uint16_t i = 0; i <= nn; i++) {
        uint8_t c = (i < nn) ? b[i] : (uint8_t)'*';
        char ch = (c >= 32 && c < 127) ? (char)c : '*';           // non-print -> delim
        if (ch == '*') {
            field[fp] = 0;
            if (fp == 17 && !vinFound[0]) {                       // VIN candidate
                bool ok = true;
                for (int k = 0; k < 17; k++) {
                    char x = field[k];
                    bool good = (x >= '0' && x <= '9') ||
                                (x >= 'A' && x <= 'Z' && x != 'I' && x != 'O' && x != 'Q');
                    if (!good) { ok = false; break; }
                }
                if (ok) { memcpy(vinFound, field, 17); vinFound[17] = 0; }
            }
            if (fp > 0) {                                         // append to record
                if (recStarted && rp < (int)sizeof(rec) - 4)
                    rp += snprintf(rec + rp, sizeof(rec) - rp, " | ");
                if (rp < (int)sizeof(rec) - 1)
                    rp += snprintf(rec + rp, sizeof(rec) - rp, "%s", field);
                recStarted = true;
            }
            fp = 0;
        } else if (fp < (int)sizeof(field) - 1) {
            field[fp++] = ch;
        }
    }
    if (vinFound[0]) SETS(vin, vinFound);
    if (rp > 0)      SETS(swid, rec);
}

#if FIRMWARE_MODE == MODE_PRODUCTION && PROBE_CHANGES
// ---------------------------------------------------------------------------
// Discovery probe. Still earning its keep 2026-09-05 -- and it has found more
// than anything else on this project, so the old "remove once the signals are
// identified" note has been replaced by an honest account of what it is for.
//
// It started as a hunt for two things, the throttle and the sidestand, and both
// are long settled. What it has done since: the heated grips, the grip
// temperatures, the hazard warning, the immobiliser, the cruise switch layer,
// and three separate NULL results that are worth as much (the horn, the
// saddlebag locks, the alarm -- none of them on this bus).
//
// FOUR outputs now, and the difference between them matters:
//   probe            byte-level change detector, STATIONARY ONLY
//   probe/2304       lean/throttle candidate, stationary only
//   probe/cruise     PGN 65265 SA39 bytes 4-5, at ANY SPEED
//   probe/throttle   PGN 65382 SA0 bytes 1 and 4, at ANY SPEED, 2 Hz
//
// The two that sit above the stationary gate are there because the question
// they answer can only be asked while moving, and each was added after a ride
// was wasted discovering that the probe had been silent the whole time.
//
// WHEN TO REMOVE IT: when PGN 65382 bytes 1 and 4 are identified. That is the
// last thing it is hunting.
//
// This is the second version. The first put mqtt.connected() at the top of a
// function called on EVERY frame, which is a network-stack call in the hottest
// path on the device. That is fixed here by caching the answer: the main loop
// sets probeOnline once per pass, and this reads a bool.
//
// For the record, that first version was blamed for a day of BLE dropouts and
// was innocent -- removing it made things worse, and the cause turned out to be
// the phone's own 2.4 GHz radio. The fix stands on its own merits regardless.
// ---------------------------------------------------------------------------
static volatile bool probeOnline = false;   // set by the main loop, read here

// Which BYTES to ignore, not which PGNs.
//
// The first version skipped whole PGNs whose values churn, and that was too
// blunt: a switch can sit in a quiet byte of a noisy message, and PGN 65265 --
// filtered out entirely -- is J1939's own home for parking-brake-style
// switches. A sidestand could have been sitting there in plain view.
//
// The masks are measured, not guessed: a bit is set where that byte took more
// than one value across the 42,365 captured frames. Everything else is
// reported, so nothing can hide behind a noisy neighbour.
struct ProbeMask { uint32_t pgn; uint8_t ignore; };
// Which BYTES to ignore, not which PGNs.
//
// The first version skipped whole PGNs whose values churn, and that was too
// blunt: a switch can sit in a quiet byte of a noisy message, and PGN 65265 --
// filtered out entirely -- is J1939's own home for parking-brake-style
// switches. A sidestand could have been sitting there in plain view.
//
// The masks are measured, not guessed: a bit is set where that byte took more
// than one value across the 42,365 captured frames. Everything else is
// reported, so nothing can hide behind a noisy neighbour.
//
// Restored 2026-09-05 after the all-bytes hunt for the saddlebag locks and the
// immobiliser. Two lessons from running without them, both written up in
// GARAGE-RUN.md: zeroing EVERY mask starves the probe, because 65265 bytes 7-8
// are a counter and checksum that change every frame and ate 989 of 998 log
// lines; and a mask can hide the answer, which is why the lock null had to be
// re-run three times before it was worth anything.
// Which BYTES to ignore, not which PGNs.
//
// The masks are measured, not guessed: a bit is set where that byte took more
// than one value across the 42,365 captured frames. Everything else is
// reported, so nothing can hide behind a noisy neighbour.
//
// Restored 2026-09-05 for the 200 km ride, after two evenings of hunting with
// them lowered. Two lessons from running without them, both in GARAGE-RUN.md:
// zeroing EVERY mask starves the probe, because 65265 bytes 7-8 are a counter
// and checksum that change on every frame and ate 989 of 998 log lines; and a
// mask can hide the answer, which is why the saddlebag-lock null had to be run
// three times before it was worth anything.
//
// On a long ride the masks matter more than usual: probe/throttle publishes
// twice a second for the whole distance, and it needs the budget.
static const ProbeMask probeMasks[] = {
    {  61444, 0x98 },   // rpm
    {  65382, 0x0B },   // rpm/256
    {  65215, 0x13 },   // wheel speeds
    {  65217, 0x33 },   // distance
    {  65262, 0x01 },   // cylinder head temperature
    {  65265, 0xC6 },   // speed pair + counter/checksum; brake and cruise visible
    {  65266, 0x7F },   // fuel economy
    {  65268, 0x0F },   // TPMS
    {  65271, 0x30 },   // battery
    {  65276, 0x02 },   // fuel level
    {  65394, 0x03 },   // grip temperature
    {  65254, 0x06 },   // clock
};

// Transport plumbing carries no signals at all.
static bool probeTransport(uint32_t pgn) {
    return pgn == 59904 || pgn == 60160 || pgn == 60416 || pgn == 60928;
}

static uint8_t probeIgnoreMask(uint32_t pgn) {
    for (const ProbeMask &m : probeMasks) if (m.pgn == pgn) return m.ignore;
    return 0;
}

#define PROBE_SLOTS 48
struct ProbeSlot { bool used; uint32_t pgn; uint8_t sa, dlc, data[8]; };
static ProbeSlot probeTab[PROBE_SLOTS];
static uint32_t probeLastPub = 0;
static uint32_t probe2304At  = 0;
static int      probe2304Val = -1;

static void probeFrame(const J1939 &j, const uint8_t *b, uint8_t nn) {
    if (!probeOnline) return;                      // cached; no network call here

    // --- PGN 65265 SA 39: the cruise bytes, at ANY speed --------------------
    // Deliberately ABOVE the stationary gate, and it is the only thing here
    // that is. Cruise cannot engage below roughly 40 km/h, so a probe that
    // only speaks while parked can never answer the one open question about
    // it -- and the ignore mask for 65265 (0xDF) hides exactly these two bytes
    // from the generic detector below, so nothing else would report them either.
    // The instruction to "watch the probe while cruising" was written before
    // either of those was noticed and could not have worked.
    //
    //   b[3] = manual's byte 4, SPN 595 cruise active in bits 0-1 (3 = n/a)
    //   b[4] = manual's byte 5, the switch byte: Set/Decel, Resume/Accel
    if (probeEnabled(PROBE_CRUISE) && j.pgn == 65265 && j.sa == 39 && nn >= 5) {
        static uint8_t crB3 = 0, crB4 = 0;
        static bool crSeen = false;
        if (!crSeen || b[3] != crB3 || b[4] != crB4) {
            crSeen = true; crB3 = b[3]; crB4 = b[4];
            char msg[72];
            snprintf(msg, sizeof(msg), "b4=%02X 595=%u  b5=%02X  %d km/h",
                     b[3], (unsigned)(b[3] & 0x03), b[4],
                     isnan(st.speed) ? 0 : (int)st.speed);
            mqtt.publish(topic("probe/cruise").c_str(), msg, false);
        }
        // deliberately no return: the generic detector still sees byte 5 while
        // parked, which is the one byte of this PGN the mask lets through.
    }

    // --- PGN 65382 SA 0: the two unexplained engine bytes, at ANY speed ------
    // Also above the stationary gate, and for the same reason probe/cruise is:
    // the question can only be asked while moving.
    //
    // Bytes 1 and 4 (indices 0 and 3) are the busiest unexamined values on this
    // bus -- 255 and 80 distinct values across 4,721 captured frames -- in a
    // message that already turned out to carry rpm/256. This bike is drive by
    // wire, so the rider's demand and the throttle valve are two different
    // numbers, and they diverge in exactly one situation on an ordinary ride:
    // cruise holding the speed with the grip released. Demand goes to zero, the
    // valve stays open. A hill does not separate them; nothing else does.
    //
    // Rate-limited to twice a second. Byte 0 changes on nearly every frame, and
    // the lesson from the all-bytes run is that a flood starves the probe of the
    // budget it needs to report anything else -- 989 of 998 lines went to a
    // counter that day and a five-second indicator flash got one line through.
    // EEC1 byte 8, SPN 2432 Engine Demand Percent Torque, offset -125. Kept
    // here so the throttle probe can carry it on the same line: correlating two
    // streams that were logged separately means aligning timestamps afterwards,
    // and at 2 Hz against a message arriving far faster that is guesswork.
    static uint8_t lastTorque = 0xFF;
    if (j.pgn == 61444 && j.sa == 0 && nn >= 8) lastTorque = b[7];

    if (probeEnabled(PROBE_THROTTLE) && j.pgn == 65382 && j.sa == 0 && nn >= 4) {
        // millis() directly: `now` is declared below the stationary gate, and
        // this block deliberately sits above it.
        static uint32_t thrAt = 0;
        const uint32_t tnow = millis();
        if (tnow - thrAt >= 500) {
            thrAt = tnow;
            // Everything needed to separate the three candidates, on one line
            // and one clock:
            //   b1, b4   the two unexplained bytes
            //   trq      torque demand, already offset so 0 = neither driving
            //            nor braking and negative means overrun
            //   thr      throttle valve position (SPN 51)
            //   fuel     fuel rate -- injector duty should track this closely,
            //            and ignition advance should not track it at all
            //   rpm      because advance depends on revs as well as load
            char msg[96];
            snprintf(msg, sizeof(msg),
                     "b1=%3u b4=%3u trq=%+4d thr=%3d%% fuel=%4.1f rpm=%4d %3d km/h",
                     b[0], b[3],
                     lastTorque == 0xFF ? -999 : (int)lastTorque - 125,
                     isnan(st.throttle) ? -1 : (int)st.throttle,
                     isnan(st.fuelRate) ? -1.0f : st.fuelRate,
                     isnan(st.rpm) ? -1 : (int)st.rpm,
                     isnan(st.speed) ? 0 : (int)st.speed);
            mqtt.publish(topic("probe/throttle").c_str(), msg, false);
        }
    }

    // The generic change detector and the 2304 readout are one switch: both are
    // stationary-only, both are for hunting, and nobody wants half of that.
    if (!probeEnabled(PROBE_SCAN)) return;

    // Stationary only, so this is silent on a ride. NaN counts as stationary:
    // before the first speed frame the bike is parked.
    if (!isnan(st.speed) && st.speed >= 2.0f) return;

    uint32_t now = millis();

    // --- PGN 2304 byte 0: the throttle candidate --------------------------
    // Its own readout because it is a live analogue value that moves on nearly
    // every frame; a change detector would drown in it. Reported raw and signed
    // against the 113 it rests at with the bike parked, because the question is
    // which way it moves from rest.
    if (j.pgn == 2304 && nn >= 1) {
        if (now - probe2304At >= 250 &&
            (probe2304Val < 0 || abs((int)b[0] - probe2304Val) >= 2)) {
            probe2304At  = now;
            probe2304Val = b[0];
            char msg[24];
            snprintf(msg, sizeof(msg), "%u %+d", b[0], (int)b[0] - 113);
            mqtt.publish(topic("probe/2304").c_str(), msg, false);
        }
        return;
    }

    // Address claims are transport, but they are worth hearing. Every module
    // announces itself on 60928 with the NAME that says what it is, and SA 136
    // -- an instrument-cluster-class module from a different manufacturer than
    // the dash -- does nothing else at all. Reporting WHEN it claims is the only
    // passive handle we have on what it is for: a module that wakes to do a job
    // usually announces itself when it wakes.
    //
    // Rate-limited per source so a contending module cannot flood the log.
    if (j.pgn == 60928) {
        if (!probeEnabled(PROBE_CLAIMS)) return;
        static uint32_t claimAt[8] = {0};
        static uint8_t  claimSa[8] = {0};
        const uint32_t cnow = millis();
        int slot = -1;
        for (int i = 0; i < 8; i++) if (claimSa[i] == j.sa && claimAt[i]) slot = i;
        if (slot < 0) for (int i = 0; i < 8; i++) if (!claimAt[i]) { slot = i; break; }
        if (slot >= 0 && (claimSa[slot] != j.sa || cnow - claimAt[slot] > 2000)) {
            claimSa[slot] = j.sa; claimAt[slot] = cnow;
            char msg[40];
            snprintf(msg, sizeof(msg), "CLAIM sa=%u", j.sa);
            mqtt.publish(topic("probe").c_str(), msg, false);
        }
        return;
    }
    if (probeTransport(j.pgn)) return;
    if (now - probeLastPub < 80) return;           // ~12 messages a second, ceiling
    const uint8_t ignore = probeIgnoreMask(j.pgn);

    // --- everything else: report bytes that change -------------------------
    ProbeSlot *slot = nullptr, *free_ = nullptr;
    for (int i = 0; i < PROBE_SLOTS; i++) {
        if (!probeTab[i].used) { if (!free_) free_ = &probeTab[i]; continue; }
        if (probeTab[i].pgn == j.pgn && probeTab[i].sa == j.sa) { slot = &probeTab[i]; break; }
    }
    if (!slot) {
        if (!free_) return;
        free_->used = true; free_->pgn = j.pgn; free_->sa = j.sa;
        free_->dlc = nn; memcpy(free_->data, b, nn > 8 ? 8 : nn);
        // ANNOUNCE it. The first sighting is the baseline and is otherwise
        // silent, which is a blind spot for any test where the interesting
        // thing is a message that only exists in one condition -- pull the key
        // fob out of range and the security module may send something it has
        // never sent before, and the probe would quietly file it as normal.
        // Added 2026-09-05 when the owner asked, before running that test,
        // whether every module was really being watched. They were; a message
        // that had never appeared at all was not.
        char msg[48];
        snprintf(msg, sizeof(msg), "NEW pgn=%lu sa=%u dlc=%u",
                 (unsigned long)j.pgn, j.sa, nn);
        mqtt.publish(topic("probe").c_str(), msg, false);
        return;
    }

    uint8_t lim = nn > 8 ? 8 : nn;
    for (uint8_t i = 0; i < lim; i++) {
        if (slot->data[i] == b[i]) continue;
        if (ignore & (1u << i)) { slot->data[i] = b[i]; continue; }  // known-noisy byte
        char msg[64];
        snprintf(msg, sizeof(msg), "pgn=%lu sa=%u b%u %02X->%02X  (%u)",
                 (unsigned long)j.pgn, j.sa, i, slot->data[i], b[i], b[i]);
        mqtt.publish(topic("probe").c_str(), msg, false);
        slot->data[i] = b[i];
        probeLastPub = now;
    }
    slot->dlc = nn;
}
#endif  // PROBE_CHANGES

static const char *standState();   // defined below; used by gearChanged()

// Gear changes, logged with the state the bike was in.
//
// PURELY OBSERVATIONAL. It watches, counts and reports, and it never touches
// anything -- most of all not the interlock that cuts the engine when the bike
// is in gear with the stand down. That interlock is not an inconvenience: if the
// gear is REAL and the clutch comes out, the bike lunges, the stand digs in and
// 400 kg goes over with the rider under it.
//
// The reason it exists: this bike's gear position sensor is unreliable. A pin
// held against the sensor by a spring makes the seven contacts, the mechanism is
// dirty, and it can report a gear that is not engaged. Three sensors have been
// replaced under warranty without anyone cleaning the mechanism, and the fault
// has never gone. When it glitches at idle on the stand, the interlock does its
// job and stops the engine -- correctly, on a lie.
//
// What the bus CANNOT tell us is whether a change was real. The clutch switch
// would settle it (SPN 598) and Indian sends it as "not available", so a false
// gear and a real one look identical here. This therefore records events for a
// human to judge, and classifies nothing: the rider knows whether she touched
// the lever, and the log knows the time and the circumstances.
//
// That is enough. Twenty-three timestamped events on a stationary bike on its
// stand is not an opinion a service desk can wave away, and after the mechanism
// is finally cleaned the same counter says whether it worked.
static void gearChanged(char to) {
    if (st.gear[0] == 0) return;              // first reading, not a change
    if (!mqtt.connected()) return;

    const bool still   = !isnan(st.speed) && st.speed < 2.0f;
    const char *stand  = standState();
    const bool onStand = stand && strcmp(stand, "STAND") == 0;
    const int  rpm     = isnan(st.rpm) ? -1 : (int)lroundf(st.rpm);

    // Suspicious is stationary, on the stand, engine running. That is the exact
    // circumstance the sensor fails in, and the one a rider is certain about.
    const bool suspect = still && onStand && rpm > 400;
    if (suspect) counterBump(CNT_GEAR_GLITCH);

    char msg[96];
    snprintf(msg, sizeof(msg), "%c->%c rpm=%d speed=%.0f stand=%s%s",
             st.gear[0], to, rpm, isnan(st.speed) ? 0.0f : st.speed,
             stand ? stand : "?", suspect ? "  SUSPECT" : "");
    mqtt.publish(topic("gear").c_str(), msg, false);
}

// Decode one CAN frame into the vehicle state. Scales mirror the JS transforms.
void decodeState(uint32_t id, bool ext, const CanFrame &frm) {
    if (!ext) return;
    J1939 j = decodeJ1939(id, ext);
    if (!j.valid) return;
    const uint8_t *b = frm.data;
    uint8_t nn = frm.data_length_code;
#if PROBE_CHANGES
    probeFrame(j, b, nn);          // >>> TEMPORARY, see DECODE-PLAN.md <<<
#endif
    switch (j.pgn) {
        case 61444:  // EEC1 RPM (canbus_rpm.js)
            if (nn >= 5) { uint16_t r = b[3] | (b[4] << 8); if (r != 0xFFFF) SETF(rpm, r * 0.125f); }
            break;
        case 65382:
            // Not decoded. Byte 1 was read as throttle percent on a 0-11 scale,
            // and it is a coarse tachometer: engine speed divided by 256.
            //
            // Correlated against rpm across 1,391 seconds of capture it scores
            // +0.991, and the mean rpm per value is a straight line — 933 at
            // value 3, 1667 at 6, 2421 at 9, 2892 at 11. A throttle follows
            // engine speed loosely and leads it into a corner exit; it does not
            // track it linearly in every bucket. The method was checked against
            // EEC1 byte 4, which is literally the rpm high byte and scores
            // 1.000 exactly as it should.
            //
            // That is why the app showed 27% with the engine idling and nobody
            // touching the throttle: 933 / 256 is 3, and 3 of 11 is 27.
            //
            // Nothing else in 42,365 frames behaves like a throttle either. The
            // next candidates down — 0.82 and 0.76 — are the speed low byte and
            // fuel economy, which follow revs on a ride without being throttle.
            //
            // Finding it needs a capture that separates throttle from revs:
            // hold a steady speed and blip the throttle against the clutch, and
            // roll off while the engine is still turning. A byte that moves with
            // the hand rather than with the crank is the one.
            break;
        case 61445:  // ETC1 gear ASCII in byte5 (canbus_gear.js)
            if (nn >= 6) { uint8_t ch = b[5]; char g[2] = { '-', 0 };
                if (ch == 78) g[0] = 'N'; else if (ch >= 49 && ch <= 54) g[0] = (char)ch;
                if (g[0] != st.gear[0]) gearChanged(g[0]);
                SETS(gear, g); }
            break;
        case 2304:
            // Tilt, byte 0. Proved on the bike 2026-09-04: the stand was moved
            // down, up and down again, and this ramped smoothly 113 -> 127 and
            // back, holding while the bike was held upright. A switch steps;
            // this follows the machine physically moving.
            //
            //   127  upright        113  resting on the sidestand
            //
            // It is the tip-over sensor, NOT a lean angle. In a balanced corner
            // the resultant force runs down the bike's own vertical axis, so an
            // accelerometer reads upright however far over you are -- which the
            // August rides confirm: at 66-103 km/h it never left 127 for more
            // than a few seconds, while the long excursions all sit at 114 with
            // the speed at zero. Those are stops with the bike on its stand.
            //
            // So this answers "is it parked on its stand", and says nothing
            // useful about cornering. Deriving a lean angle from it would be
            // inventing a number the sensor cannot know.
            if (nn >= 1 && b[0] != 0xFF) SETI16(lean, b[0]);
            break;
        case 65262:  // ET1 byte 1. NOT coolant -- this bike is air-cooled. Indian reuses
                     // the slot for engine OIL temperature: ambient at rest, 100-115 C warm.
            if (nn >= 1 && b[0] != 0xFF) SETF(coolant, (float)b[0] - 40.0f);
            break;
        case 65215:
            // EBC2, front wheel speed, from the ABS module. 1/256 km/h.
            //
            // Not a second opinion on the speedometer -- it is the honest one.
            // The dash reads the REAR wheel, which is driven and therefore
            // slips: measured against this one across 1,249 samples the rear
            // runs 1.0351x high, and the error grows with speed because slip
            // grows with torque. That gap is deliberate and stays; see
            // DECODE-PLAN.md.
            //
            // What it is decoded FOR is the comparison. See wheelCheck().
            if (nn >= 2) {
                uint16_t f = b[0] | (b[1] << 8);
                if (f != 0xFFFF) { SETF(speedFront, f / 256.0f); speedFrontAt = millis(); }
            }
            break;
        case 65265:  // CCVS — multi-source: speed(SA11), brake light(SA0); SA39 sends only switches
            if (j.sa == 11 && nn >= 3) { uint16_t r = b[1] | (b[2] << 8);
                if (r != 0xFFFF) { SETF(speed, r / 256.0f); speedRearAt = millis(); } }
            // CCVS brake switch is a TWO-bit field: 0 off, 1 on, 2 error,
            // 3 not available. Masking with &1 turned "not available" into
            // "pressed" — harmless today because only SA 0 is read and it never
            // sends 3, but SA 11 and SA 39 send nothing else (4330 and 5223
            // frames of it in the captures), so the moment that filter moved,
            // the brake would have read as permanently on.
            //
            // This is also the brake LIGHT switch, and on a motorcycle either
            // control operates it. It is not the rear brake specifically, which
            // is why both levers have always moved the same signal.
            if (j.sa == 0 && nn >= 4) {
                uint8_t sw = (b[3] >> 4) & 3;
                if (sw <= 1) SETI(brakeRear, sw);
                // The clutch rides in the same byte, bits 6-7 (SPN 598), and
                // has been arriving free in every frame we already read. It is
                // decoded now because it is one of the things that drops the
                // cruise control, and the derived hold below needs it.
                uint8_t cl = (b[3] >> 6) & 3;
                if (cl <= 1) SETI(clutch, cl);
            }
            // Cruise: WITHDRAWN 2026-09-03. This read was byte 5 bit 0, and
            // byte 5 of CCVS is not the cruise state at all — it is the switch
            // byte: SET(599) in bits 0-1, coast(600) in 2-3, RESUME(601) in
            // 4-5, accelerate(602) in 6-7. So the tell-tale was lighting on a
            // momentary button press and going out again a second or two later,
            // which is exactly how it behaved on the bike.
            //
            // The real cruise state, SPN 595, lives in byte 4 bits 0-1. Indian
            // sends byte 4 as a constant 0xF7 across all 5223 SA-39 frames in
            // the captures: 595 reads 3, "not available". Byte 6, the set speed
            // (SPN 86), is a constant 0xFF.
            //
            // That was once written here as proof the state is never sent. It
            // is not: cruise was NEVER USED on any of those four rides, so the
            // captures could not have shown an engaged state whether or not it
            // is transmitted. A null result from a test incapable of a positive
            // one. Untested, not absent -- see NEXT-RIDE.md.
            //
            // The service manual names the switches Set/DECEL (599) and
            // Resume/ACCEL (601): one rocker, two jobs, depending on whether
            // cruise is already engaged. Both were found; neither was missed.
            //
            // What IS there, and confirmed across three rides: SET and RESUME
            // as momentary presses, 1-7 seconds each, with minutes of nothing
            // between them. Useful as events, useless as a state, and the
            // production rule is that we decode what we can use — so nothing
            // is published until we decide what a button press should drive.
            // See DECODE-PLAN.md, "Fartpilot", for the latch that would turn
            // these presses into a state and what it would cost.
            //
            // --- DECODED 2026-09-05, measured in the garage ------------------
            // Byte 4 (index 3) from SA 39, as two-bit fields:
            //   bits 0-1  SPN 595 cruise ACTIVE
            //   bits 2-3  SPN 596 cruise ENABLE   F3 -> F7 when the rocker is on
            //   bits 4-5  SPN 597 brake           (read from SA 0 above)
            //   bits 6-7  SPN 598 clutch
            // Byte 5 (index 4):
            //   bits 0-1  SPN 599 SET      CC -> CD
            //   bits 4-5  SPN 601 RESUME   CC -> DC
            //   bits 2-3 (coast) and 6-7 (accel) stay at 3, "not available":
            //   Indian does not send them. The decel/accel functions are the
            //   SAME two switches, reinterpreted by the ECU once engaged, which
            //   is why the manual names them Set/Decel and Resume/Accel.
            //
            // 0xF7 is exactly the constant byte 4 held across all 5223 SA-39
            // frames in the August captures. It was read then as nothing but
            // "595 is not available". It is that -- but bits 2-3 are 01, so
            // cruise was ENABLED for all four rides and simply never set.
            //
            // ACTIVE is left unknown rather than forced to 0 when the field
            // reads 3: "not available" is not "off", and pretending otherwise
            // is what put a false cruise tell-tale on the dash for three weeks.
            if (j.sa == 39 && nn >= 5) {
                uint8_t en  = (b[3] >> 2) & 3;
                if (en  <= 1) SETI(cruiseEnable, en);
                uint8_t set = b[4] & 3, res = (b[4] >> 4) & 3;
                SETI(cruiseSw, set == 1 ? 1 : (res == 1 ? 2 : 0));

                // SPN 595 is NOT read here any more. Settled 2026-09-05 on the
                // road: byte 4 held 0xF7 at 94, 87 and 73 km/h with the cruise
                // demonstrably holding the speed and the rider's hand off the
                // grip. The control was built into the measurement -- byte 5
                // reported every SET and RESUME press in the same frames, so the
                // message was being received and decoded correctly while byte 4
                // sat still. Indian does not transmit the engaged state.
                //
                // What replaces it is derived below, from measured inputs only.
                if (set == 1 || res == 1) cruiseArm();
            }
            break;
        case 65276:
            // Fuel level, byte 1. TWO modules send this — SA 0 (669 frames in
            // the captures) and SA 23 (270) — and both send plausible readings
            // a couple of percent apart, so an unfiltered decode made the gauge
            // flicker between two opinions of the same tank.
            //
            // SA 0 is chosen because it is where the rest of the engine-side
            // signals come from: rpm, coolant, battery, fuel economy. That is a
            // choice for determinism, not a claim that SA 23 is wrong — and the
            // difference may be no more than two readings of a sloshing tank
            // taken moments apart.
            if (j.sa == 0 && nn >= 2 && b[1] != 0xFF) SETF(fuel, b[1] * 0.4f);
            break;
        case 65217: {  // HRVD odometer(b0-3) + trip1(b4-7), x0.005 km
            if (nn >= 8) {
                uint32_t od = (uint32_t)b[0] | (b[1] << 8) | (b[2] << 16) | ((uint32_t)b[3] << 24);
                if (od != 0xFFFFFFFFu) SETF(odometer, od * 0.005f);
                uint32_t tr = (uint32_t)b[4] | (b[5] << 8) | (b[6] << 16) | ((uint32_t)b[7] << 24);
                if (tr != 0xFFFFFFFFu) SETF(trip, tr * 0.005f);
            }
            break; }
        case 65266:
            // LFE, and we were reading one field of four.
            //
            // The whole message was decoded on 2026-09-04 after counting how
            // many varying bytes had never been looked at. The throttle was in
            // here the entire time -- byte 7, which J1939 calls Engine Throttle
            // Valve 1 Position (SPN 51) at 0.4 % per bit.
            //
            //   1-2  fuel rate, 0.05 L/h per bit          (SPN 183)
            //   3-4  instantaneous economy, 1/512 km/L    (SPN 184)
            //   5-6  average economy, 1/512 km/L          (SPN 185)
            //   7    throttle valve position, 0.4 %       (SPN 51)
            //   8    second throttle valve -- constant 0xFF here, only one body
            //
            // Four independent things say byte 7 is the throttle. The standard
            // puts it there. It LEADS engine speed by about a second, which is
            // the hand moving before the crank does. Its correlation with rpm is
            // loose (0.64) where the decode withdrawn on 2026-09-02 scored 0.991
            // at zero lag, because that one WAS the rpm. And at idle it sits at
            // 5 % and holds tight, which is a throttle plate at its idle stop --
            // not zero, and not wandering.
            if (j.sa != 0) break;
            if (nn >= 2) { uint16_t r = b[0] | (b[1] << 8);
                if (r != 0xFFFF) SETF(fuelRate, r * 0.05f); }
            if (nn >= 4) { uint16_t r = b[2] | (b[3] << 8);
                if (r != 0xFFFF && r != 0) SETF(fuelEconInst, 100.0f / (r / 512.0f)); }
            if (nn >= 6) { uint16_t r = b[4] | (b[5] << 8);
                if (r != 0xFFFF && r != 0) SETF(fuelEcon, 100.0f / (r / 512.0f)); }
            if (nn >= 7 && b[6] != 0xFF) SETF(throttle, b[6] * 0.4f);
            break;
        case 65271:  // VEP1 battery, b4-5 x0.05 V (canbus_battery.js)
            if (nn >= 6) { uint16_t r = b[4] | (b[5] << 8); if (r != 0xFFFF) SETF(battery, r * 0.05f); }
            break;
        case 65269:  // AMB ambient, b3-4 x0.03125 -273 (canbus_ambient.js)
            if (nn >= 5) { uint16_t r = b[3] | (b[4] << 8); if (r != 0xFFFF) SETF(ambient, r * 0.03125f - 273.0f); }
            break;
        case 65268: {  // TPMS pressure(b1 x0.580152 PSI) + temp(b2-3 /32 -273)
            uint8_t loc = b[0];
            bool front = (loc == 0x00 || loc == 0x11);
            bool rear  = (loc == 0x10 || loc == 0x21 || loc == 0x01);
            if (nn >= 2 && b[1] != 0xFF) { float psi = b[1] * 0.580152f;
                if (front) SETF(tyreFront, psi); else if (rear) SETF(tyreRear, psi); }
            if (nn >= 4 && !(b[2] == 0xFF && b[3] == 0xFF)) { uint16_t r = b[2] | (b[3] << 8);
                float t = r / 32.0f - 273.0f;
                if (front) SETF(tyreFrontTemp, t); else if (rear) SETF(tyreRearTemp, t); }
            break; }
        case 65390:
            // Not decoded. This was read as the front brake on byte 0 bit 5,
            // and four rides of captures say it cannot be: every one of the 23
            // frames is either DF FF FF FF FF FF FF FF or all 0xFF, with the
            // rest of the frame filler in both cases. The frames where bit 5 is
            // set are exactly the frames where every bit is set — J1939's "not
            // available" — so the decoder was reporting the filler as a pressed
            // brake, which is why the front brake appeared to follow the rear.
            //
            // Leaving brakeFront unknown is the honest state. The app already
            // draws an unreported switch struck through, so nothing pretends.
            // Finding the real signal needs a capture with each lever operated
            // on its own; see the note in PROTOCOL.md.
            break;
        case 65381:
            // Source-filtered and guarded, both from the captures.
            //
            // Two modules send this PGN. SA 0 sends nothing but 0xFF — pure
            // filler — and 0xFF & 0x40 is true, so an unfiltered decode reported
            // full beam every time one arrived. That is why the headlight
            // flipped between High and Low on a bike standing still.
            //
            // SA 39 carries the real thing: 0x10 and 0x11 for dipped, 0x40 for
            // main, over 299 frames. Bit 6 and bit 4 are exclusive there, which
            // is what makes the either/or reading safe.
            if (j.sa == 39 && nn >= 1 && b[0] != 0xFF) {
                const char *h = (b[0] & 0x40) ? "High" : ((b[0] & 0x10) ? "Low" : "Off");
                SETS(headlight, h);
            }
            // Hazard warning, byte 2 bit 0. Measured 2026-09-05: FC -> FD for
            // the exact duration of the hazard flashing, back to FC on release.
            // A held state, not the momentary blip the switch bits in byte 1
            // produce -- which is what makes it worth shipping.
            if (j.sa == 39 && nn >= 3 && b[2] != 0xFF) SETI(hazard, b[2] & 1);
            break;
        case 65089:
            // Indicators, byte 1: bit 6 left, bit 4 right. Confirmed clean in
            // the captures — 0x0F both off, 0x1F right, 0x4F left, 0x5F hazard,
            // and nothing else in 356 frames. The 0xFF guard is added anyway,
            // because reading filler as "both indicators on" is the same mistake
            // the headlight made, and it costs one comparison to rule out.
            if (nn >= 2 && b[1] != 0xFF) {
                SETI(indLeft,  (b[1] & 0x40) ? 1 : 0);
                SETI(indRight, (b[1] & 0x10) ? 1 : 0);
            }
            break;
        case 65394:
            // Heated grip temperature, one byte per grip, J1939 -40 offset.
            //
            // Proved on the bike 2026-09-04, and it took three runs because the
            // first two were confounded: the ignition and the grips came on at
            // nearly the same moment, and energised electronics warm from
            // ambient on the same curve as a heated grip. The grips also
            // remember their level across an ignition cycle, so the intended
            // "ignition on, grips off" baseline silently never happened.
            //
            // The clean run switched the grips off before the key:
            //   grips 1->10        +8 C in 84 s
            //   grips switched off +2 C of lag, then flat
            //   grips off at start 25 -> 23 C, falling
            //   ignition cycled    returns at 23 C, does not reset
            //
            // That last line is what rules out a counter: a value climbing from
            // zero on every start would have reset. It has thermal mass.
            //
            // Corroborated by the August rides, where with the grips certainly
            // off on a 25 C afternoon the two sat at 22-29 C and drifted DOWN
            // 28 -> 23 C over one ride -- ambient, not electronics pinned above
            // it.
            //
            // Byte 0 is the LEFT grip, byte 1 the RIGHT. Settled 2026-09-04 by
            // holding a bare hand on the left grip with the heat off: byte 0
            // rose 18 to 22 C in a minute while byte 1 did not move.
            //
            // Worth doing, because the inference was backwards. Byte 0 warms
            // faster under the heaters, and the rider's recollection was that
            // the right grip warms faster, so the two together said byte 0 was
            // the right one. The physics says otherwise: the right grip sits on
            // the throttle tube, a metal cylinder that has to be warmed along
            // with the rubber, while the left sits straight on the bar. More
            // mass on the right means a slower sensor -- byte 0 leads because
            // it is the lighter of the two, not because it is the right.
            if (nn >= 2) {
                if (b[0] != 0xFF) SETF(gripTempL, (float)b[0] - 40.0f);
                if (b[1] != 0xFF) SETF(gripTempR, (float)b[1] - 40.0f);
            }
            break;
        case 65386:
            // Horn, byte 0 bit 6. One source only (SA 39) and no filler in 50
            // frames — 0x3F and 0xBF with the bit clear, 0x7F with it set — so
            // this one was never affected by the multi-source fault that caught
            // the headlight and DM1. Guarded against 0xFF on the same principle
            // as the indicators.
            // Horn: WITHDRAWN 2026-09-04. Byte 0 bit 6 is not the horn -- it
            // follows the wake button, which is why the tell-tale lit when the
            // ignition was pressed and no sound came out.
            //
            // SETTLED 2026-09-05: the horn is not on this bus at all. Eight
            // presses across two garage runs, the rig proved working in both by
            // the indicator control test, and the second run had 65265 unmasked
            // so nothing was excluded. Not one byte moved.
            //
            // The manual agrees. SPN 520293 has only FMI 5 and 6 -- open circuit
            // and grounded circuit, both OUTPUT DRIVER faults. No FMI 9 (what a
            // networked signal gets) and no FMI 31 "Switch Stuck" (what every
            // real switch signal here has: 596, 599, 601). The harness runs a
            // GY "HORN SWITCH OUTPUT" wire in and a WH "HORN POWER" wire out.
            // A switch driving a monitored output inside one module. Nothing to
            // decode, and nothing to look for. See DECODE-PLAN.md.
            //
            // The owner watched the app while pressing the ignition button: the
            // horn tell-tale lights, and the bike makes no sound at all. That
            // matches the captures -- at 09:46:17 this byte went 0x3F to 0x7F
            // the moment the ignition came on, and back 14 seconds later.
            //
            // It was never confirmed. The reverse-engineering log has carried
            // "honk-verify pending" since 2026-08-14 and the check was never
            // done, so a guess from a switch sweep shipped as fact and has been
            // lighting a horn symbol at every start since.
            //
            // Confirming it properly is also awkward: this PGN transmits about
            // every 11 seconds, with gaps to 471 in the captures, so a normal
            // press has roughly two chances in eleven of being sampled at all.
            // A real horn signal on this bus would be nearly unobservable, and
            // is not worth a tell-tale that cannot be trusted to be lit when
            // the horn sounds. See DECODE-PLAN.md.
            // Heated grips, byte 2. Confirmed on the bike 2026-09-04 by stepping
            // all ten levels and holding each: the byte moves in exact steps of
            // 25, from 0 off to 250 at level 10. That is the ordinary J1939
            // percent scaling, 0.4 % per bit, read here as a level because the
            // control has ten detents and a rider thinks in detents, not percent.
            //
            // It rode in the same frame as the horn the whole time, which is why
            // it was never found: nothing was looking past byte 0.
            //
            // 251-255 are the J1939 error/not-available codes. A momentary 254
            // appeared once at full heat during the test, so the guard is not
            // theoretical.
            if (nn >= 3 && b[2] <= 250) SETI(grips, b[2] / 25);

            // --- SECURITY / KEY FOB, byte 0 bits 6-7. Decoded 2026-09-05. -----
            //
            // This is what the withdrawn horn actually was. The old comment even
            // listed all three values -- 0x3F and 0xBF "with the bit clear",
            // 0x7F "with it set" -- and read a three-state field as one bit on
            // and off. Bit 6 is not a horn; it is the security system looking
            // for the key fob, which is why the horn tell-tale lit every time
            // the wake button was pressed and no sound ever came out.
            //
            //   00  0x3F  authorised, at rest
            //   01  0x7F  searching for the fob
            //   10  0xBF  fob NOT detected
            //
            // Proved by a controlled pair on 2026-09-05. Ignition on with the
            // fob in a pocket: 3F -> 7F -> 3F inside one second. Ignition on
            // with the fob left indoors: 3F -> 7F, held for twenty seconds, then
            // -> BF, and the bike shut down. The manual gives the same twenty
            // seconds, and describes the dash lamp as lit while searching and
            // FLASHING when the fob is not detected -- which is exactly what the
            // owner saw in each run.
            //
            // The August captures agree without being asked: the 14:43 ride
            // begins mid-wake and goes 7F -> BF -> 7F -> 3F, a fob found on the
            // second attempt. BF never appears while riding, only in a wake.
            //
            // Whether this IS the manual's SPN 520330 cannot be proved from
            // here -- that is a proprietary PGN, not the standard's immobiliser
            // parameter. What is certain is that it carries the state the
            // security lamp displays.
            if (j.sa == 39 && nn >= 1 && b[0] != 0xFF) {
                const char *k = ((b[0] >> 6) & 3) == 0 ? "OK"
                              : ((b[0] >> 6) & 3) == 1 ? "SEARCHING" : "NOT FOUND";
                SETS(security, k);
            }
            break;
        case 65226:  // DM1 active DTCs, single frame (canbus_dm1.js)
            decodeDM1(j.sa, b, nn);
            break;
    }
}

// Publish the whole state as one retained JSON. Throttled to at most one per
// MQTT_PUBLISH_INTERVAL_MS. Publishes when a value changed, OR — even with no
// change — once every STATE_HEARTBEAT_MS as a heartbeat so a parked bike still
// shows a recent (not frozen) value in the UI.
// Build the state JSON. Split out of publishState() so the BLE path can reuse
// it: the MQTT gate below (return early when the broker is unreachable) is
// exactly wrong for BLE, which matters most when there is NO WiFi at all.
// One serialiser, two transports — see vehstate.h.
// Is the bike parked on its stand, standing upright, or lying down?
//
// Derived rather than decoded: the bus carries no sidestand STATE. It does carry
// the stand as an EVENT -- try to start with it down and DM1 reports SPN 520267
// FMI 31, Indian's P181C, then clears. The position is private; the refusal is
// public.
//
// Originally written as "no sidestand switch at all" --
// checked with a per-byte change detector over the whole bus while the stand
// was worked up and down, and nothing moved but the tilt. So this reads the
// tilt instead, which is the better answer anyway: a switch says where the
// stand is, this says whether the bike is resting on it.
//
// Only meaningful STATIONARY. Riding upright and cornering both read 127, so
// while moving there is nothing here worth reporting and the field is left out.
//
// DOWN has to be held. The captures reach 94 and 203 on bumps at road speed, so
// a single extreme sample is a pothole, not a fallen motorcycle. A bike that is
// down stays down; three seconds of it is the difference.
#define LEAN_UPRIGHT   127
#define LEAN_UPRIGHT_BAND 6      // +/- counts still called upright
#define LEAN_DOWN_DEV  30        // far past the stand, either way
#define LEAN_DOWN_HOLD 3000u     // ms it must persist

static const char *standState() {
    if (st.lean < 0) return nullptr;

    // Moving? Then it is upright, and no tilt reading is needed to say so.
    //
    // The first version returned nothing above 2 km/h, on the reasoning that an
    // accelerometer is blind to lean in a balanced corner. That is true and it
    // was the wrong conclusion: cornering hides the ANGLE, not the state. You
    // cannot be resting on your sidestand at fifty, so "upright" is the one
    // answer that is certainly right while the wheels are turning.
    //
    // And 2 km/h was far too low to mean "riding" in the first place. Rolling
    // the bike off its stand turns the wheel, which read as motion and put a
    // "no reading" strike across the graphic in the middle of the one manoeuvre
    // the graphic exists to show.
    if (!isnan(st.speed) && st.speed >= 2.0f) return "UPRIGHT";

    const int dev = (int)st.lean - LEAN_UPRIGHT;
    static uint32_t extremeSince = 0;
    static const char *lastStable = nullptr;

    if (abs(dev) > LEAN_DOWN_DEV) {
        if (extremeSince == 0) extremeSince = millis();
        if (millis() - extremeSince >= LEAN_DOWN_HOLD) return "DOWN";
        // Extreme, but not yet convinced. Keep answering what it was rather
        // than "no reading".
        //
        // Returning nothing here put a strike across the graphic every time
        // the rider climbed onto the bike: this is an accelerometer, not a
        // protractor, so a body settling onto the seat and the machine rocking
        // under it throw spikes far past any angle the bike is actually at. The
        // spikes are exactly what the three-second hold exists to ignore -- and
        // then the code went and reported them anyway, as an absence.
        //
        // Doubt is not the same as ignorance. Until the hold decides, the bike
        // is still where it last convincingly was.
        return lastStable;
    }
    extremeSince = 0;

    // Contiguous, with no gap to fall into.
    //
    // The first version had three boxes with holes between them and returned
    // nothing in the holes, which put a "no reading" strike across the graphic
    // every time the bike was lifted past vertical -- righting a motorcycle
    // overshoots, and the overshoot landed in a hole. Bands that do not touch
    // are a bug in themselves.
    //
    // The stand is on the left, so any real lean to the left while stationary
    // means the bike is on it or going onto it. Everything else that is not on
    // its side is upright. That is coarser than the tilt sensor can measure,
    // and it is the right amount of detail for a three-state graphic.
    lastStable = (dev <= -LEAN_UPRIGHT_BAND) ? "STAND" : "UPRIGHT";
    return lastStable;
}

// Are both wheel speed sensors still talking?
//
// This exists because of a real failure. A shim was left out during a service,
// the rotating tone ring machined the face off the front ABS sensor, and the
// first warning the rider got was the ABS quitting in traffic in front of a
// car. Four days off the road and 2,400 kroner, and none of it needed to be a
// surprise: the sensor did not fail in an instant, it was ground away over
// time.
//
// The sensor is a 2-wire ACTIVE Hall device, current-modulated between 7 mA and
// 14 mA -- not the passive inductive type. That distinction decides what this
// check can and cannot see, and an earlier version of this comment had it
// wrong. See docs/ABS_WHEEL_SPEED_SENSOR_DIAGNOSTIC.md, written by the owner,
// who is an electronics engineer and had already measured all of this.
//
// An active sensor's output does not weaken with speed: the current levels are
// fixed, which is the whole reason for using one. So a sensor being worn back
// does NOT read progressively slow, and there is no drift in the ratio between
// the wheels to watch for. It counts the teeth correctly, or it does not.
//
// What a growing air gap does produce is INTERMITTENCY. The field reaches the
// threshold on some teeth and not others, so the reading drops out briefly and
// comes back -- and the module tolerates brief losses without setting a fault.
// Those glimpses are the early warning, and they are only visible by counting
// them: one is nothing, and one a week becoming twenty a ride is a sensor being
// ground away.
//
// The two wheels track each other at r = 0.9998 across the captures, with the
// rear reading 1.0351x high from drive slip. That is a tight enough
// relationship that a sensor going wrong breaks it visibly.
//
// So there are two outputs. A sustained loss is called out loud, and every
// brief one is counted. The count is the number that matters over months: it
// needs no calibration, it cannot cry wolf, and a rising trend is the thing the
// ABS module will not tell you because it has not decided anything is wrong
// yet.
#define WHEEL_MIN_KMH   25.0f    // below this the readings are too coarse to compare
#define WHEEL_LOST_FRAC 0.20f    // "reporting nothing" against the other wheel
#define WHEEL_HOLD_MS   3000u    // must persist; a single frame is not a fault
#define WHEEL_STALE_MS  2000u    // no report for this long counts as no signal
#define WHEEL_BLIP_MS   300u     // shorter than this is noise, not a dropout

static const char *wheelCheck() {
    if (isnan(st.speed)) return nullptr;

    const uint32_t now = millis();
    const float rear = st.speed;
    static uint32_t frontBadSince = 0, rearBadSince = 0;

    // Gone quiet counts as lost. Not being told is not the same as being told
    // zero, and a reading that simply stops arriving leaves the last good value
    // sitting there looking healthy -- which is the one way a warning system
    // must not fail.
    const bool frontStale = (speedFrontAt == 0) || (now - speedFrontAt > WHEEL_STALE_MS);
    if (rear >= WHEEL_MIN_KMH && frontStale) {
        if (frontBadSince == 0) frontBadSince = now;
        if (now - frontBadSince >= WHEEL_HOLD_MS) return "FRONT LOST";
        return "OK";
    }
    // Came back before it counted as lost: that is a blip, and blips are the
    // whole point of counting.
    if (frontBadSince != 0 && now - frontBadSince >= WHEEL_BLIP_MS) {
        counterBump(CNT_WHEEL_FRONT);
        stateDirty = true;
    }
    if (isnan(st.speedFront)) return nullptr;

    const float front = st.speedFront;

    // Front silent while the rear is clearly turning.
    if (rear >= WHEEL_MIN_KMH && front < rear * WHEEL_LOST_FRAC) {
        if (frontBadSince == 0) frontBadSince = now;
        if (now - frontBadSince >= WHEEL_HOLD_MS) return "FRONT LOST";
    } else {
        if (frontBadSince != 0 && now - frontBadSince >= WHEEL_BLIP_MS) {
            counterBump(CNT_WHEEL_FRONT);
            stateDirty = true;
        }
        frontBadSince = 0;
    }

    const bool rearStale = (speedRearAt == 0) || (now - speedRearAt > WHEEL_STALE_MS);
    if (front >= WHEEL_MIN_KMH && (rearStale || rear < front * WHEEL_LOST_FRAC)) {
        if (rearBadSince == 0) rearBadSince = now;
        if (now - rearBadSince >= WHEEL_HOLD_MS) return "REAR LOST";
    } else {
        if (rearBadSince != 0 && now - rearBadSince >= WHEEL_BLIP_MS) {
            counterBump(CNT_WHEEL_REAR);
            stateDirty = true;
        }
        rearBadSince = 0;
    }

    // Both turning and in proportion. Only say so once there is enough speed
    // for the statement to mean anything.
    if (rear >= WHEEL_MIN_KMH || front >= WHEEL_MIN_KMH) return "OK";
    return nullptr;
}

// Clear the values that stop being true when the bus goes quiet.
//
// Everything here keeps its last reading for ever otherwise, because nothing
// arrives to replace it. Walk up to a parked motorcycle with the app open and
// the rev counter reads 1069 -- the engine speed from the moment it was
// switched off, presented as though it were now.
//
// That is the same quiet lie that cost four withdrawn signals in a day, in
// another form: a value that was true once, still on screen, with nothing
// saying it is old.
//
// Only the ones that stop meaning anything. Engine speed, throttle, road speed
// and fuel flow are all zero-or-nothing on a stopped bike; the momentary
// switches cannot be known. Oil temperature, battery, tyres, grips, the
// odometer and the stand are all still exactly as true as they were a second
// ago, so they stay.
static void clearLiveValues() {
    st.rpm = st.throttle = st.speed = st.speedFront = NAN;
    st.fuelRate = st.fuelEconInst = NAN;
    st.brakeRear = st.indLeft = st.indRight = -1;
    st.cruiseEnable = st.cruiseSw = st.hazard = -1;
    st.cruiseHold = st.clutch = -1;
    stateDirty = true;
}


size_t buildStateJson(char *out, size_t cap, bool includeVin) {
    // includeVin doubles as "this is the MQTT payload".
    const bool ble = !includeVin;

    // Short keys on the radio, long keys on MQTT.
    //
    // A GATT notification carries 514 bytes and cannot fragment. Measured on
    // 2026-09-04 with the tyres reporting and no fault at all, the long-key
    // payload came to 560 -- over the limit on an ordinary ride, silently, the
    // app simply ceasing to update while the cluster carried on from the binary
    // frame. JSON spends most of itself on its own field names: "wheelBlipsRear"
    // is fourteen characters to introduce a number.
    //
    // Two-letter keys take the same worst case to 388 and leave 126 to grow
    // into. openHAB keeps the long names over MQTT, where there is no limit and
    // where a human reads them, so its channels are untouched.
    //
    // The app parses the short set. Both sides were generated from one list so
    // a typo could not quietly drop a field on one side only.
    #define K(l, sh) (ble ? (sh) : (l))
    JsonDocument doc;
    if (!isnan(st.rpm))          doc[K("rpm","r")]          = (int)lroundf(st.rpm);
    if (!isnan(st.throttle))     doc[K("throttle","th")]     = (int)lroundf(st.throttle);
    if (st.gear[0])              doc[K("gear","g")]         = st.gear;
    // "ot" is a leftover misnomer: this is the CYLINDER HEAD temperature, not
    // oil -- the engine has no oil temperature sensor at all (2026-09-05, from
    // the service manual). The long key and every label the rider sees were
    // corrected; this two-character BLE key was not, because firmware and app
    // must change together or the app shows no temperature at all.
    // PAIRED CHANGE: BikeState.kt line with coolantC = i("ot").
    if (!isnan(st.coolant))      doc[K("coolant","ot")]      = (int)lroundf(st.coolant);
    if (!isnan(st.speed))        doc[K("speed","sp")]        = roundf(st.speed * 10) / 10.0;
    if (!isnan(st.fuel))         doc[K("fuel","fl")]         = (int)lroundf(st.fuel);
    if (!isnan(st.odometer))     doc[K("odometer","od")]     = (int)lroundf(st.odometer);
    if (!isnan(st.trip))         doc[K("trip","tp")]         = roundf(st.trip * 10) / 10.0;
    if (!isnan(st.fuelEcon))     doc[K("fuelEconomy","fe")]  = roundf(st.fuelEcon * 10) / 10.0;
    if (!isnan(st.battery))      doc[K("battery","bv")]      = roundf(st.battery * 10) / 10.0;
    if (!isnan(st.ambient))      doc[K("ambient","am")]      = roundf(st.ambient * 10) / 10.0;
    if (!isnan(st.tyreFront))    doc[K("tyreFront","tf")]    = roundf(st.tyreFront * 10) / 10.0;
    if (!isnan(st.tyreRear))     doc[K("tyreRear","tr")]     = roundf(st.tyreRear * 10) / 10.0;
    if (!isnan(st.tyreFrontTemp))doc[K("tyreFrontTemp","tft")]= roundf(st.tyreFrontTemp * 10) / 10.0;
    if (!isnan(st.tyreRearTemp)) doc[K("tyreRearTemp","trt")] = roundf(st.tyreRearTemp * 10) / 10.0;
    if (st.brakeRear >= 0)       doc[K("brakeRear","br")]    = st.brakeRear ? "PRESSED" : "RELEASED";
    // "HOLDING" rather than "ON", so the value itself says this is not the same
    // kind of fact as the fields around it: it is worked out, not read.
    if (st.cruiseHold >= 0)      doc[K("cruise","cc")]       = st.cruiseHold ? "HOLDING" : "off";
    if (st.clutch >= 0)          doc[K("clutch","cl")]       = st.clutch ? "PULLED" : "out";
    if (st.cruiseEnable >= 0)    doc[K("cruiseEnable","ce")] = st.cruiseEnable ? "ON" : "OFF";
    // The legend printed on the rocker itself: SET/DEC below, RES/ACC above --
    // and the manual names the switches Set/Decel (599) and Resume/Accel (601).
    // Publishing bare "SET" and "RESUME" threw away half of what the rider can
    // actually read on the handlebar.
    if (st.cruiseSw >= 0)        doc[K("cruiseSw","cs")]     = st.cruiseSw == 1 ? "SET/DEC"
                                                            : st.cruiseSw == 2 ? "RES/ACC" : "none";
    if (st.hazard >= 0)          doc[K("hazard","hz")]       = st.hazard ? "ON" : "OFF";
    if (st.security[0])          doc[K("security","se")]     = st.security;
    if (st.headlight[0])         doc[K("headlight","hl")]    = st.headlight;
    if (st.indLeft >= 0)         doc[K("indLeft","il")]      = st.indLeft ? "ON" : "OFF";
    if (st.indRight >= 0)        doc[K("indRight","ir")]     = st.indRight ? "ON" : "OFF";
    if (st.grips >= 0)           doc[K("grips","gr")]        = st.grips;
    if (!isnan(st.speedFront))   doc[K("speedFront","sf")]   = roundf(st.speedFront * 10) / 10.0;
    if (!isnan(st.fuelRate))     doc[K("fuelRate","fr")]     = roundf(st.fuelRate * 100) / 100.0;
    if (!isnan(st.fuelEconInst)) doc[K("fuelEconInst","fi")] = roundf(st.fuelEconInst * 10) / 10.0;
    { const char *wc = wheelCheck(); if (wc) doc[K("wheels","wh")] = wc; }
    // Always published, including zero. These were sent only when above zero,
    // which meant a bike with no faults left the fields out entirely and openHAB
    // showed "null" -- the display for "never heard from" rather than for "none
    // so far". Those are different facts, and the counters genuinely know which
    // one is true: they are read from NVS at boot and are zero because nothing
    // has happened, not because nobody looked.
    //
    // Zero also charts. "none" would read a little better in a label and could
    // not be graphed, and a dropout count is exactly the sort of thing worth
    // seeing a line of over a season.
    doc[K("wheelBlips","wf")]     = counterGet(CNT_WHEEL_FRONT);
    doc[K("wheelBlipsRear","wr")] = counterGet(CNT_WHEEL_REAR);
    doc[K("gearGlitches","gg")]   = counterGet(CNT_GEAR_GLITCH);
    if (st.lean >= 0)            doc[K("lean","ln")]         = st.lean;   // app reads "stand", not this
    { const char *sd = standState(); if (sd) doc[K("stand","st")] = sd; }
    // Ignition, from bus activity rather than any decoded bit.
    //
    // The first version guarded on "have we ever seen a frame", on the grounds
    // that never having seen one is not the same as the bus having gone quiet.
    // It is the same thing here, and the guard made the field vanish in exactly
    // the case it was written for: after any restart with the ignition off, no
    // frame has ever arrived, so it reported nothing at all.
    //
    // The one genuinely unknown moment is the first seconds after boot, before
    // the bitrate scan has had a chance. After that, silence is an answer.
    if (millis() > 10000u || lastFrameMs != 0) {
        doc[K("ignition","ig")] = (lastFrameMs != 0 && millis() - lastFrameMs < 3000u) ? "ON" : "OFF";
    }
    if (!isnan(st.gripTempL))    doc[K("gripTempL","gl")]    = roundf(st.gripTempL * 10) / 10.0;
    if (!isnan(st.gripTempR))    doc[K("gripTempR","gR")]    = roundf(st.gripTempR * 10) / 10.0;
    // VIN and the software-ID record are identity, not telemetry. They ride
    // along on MQTT (openHAB wants them once) but stay off the BLE link.
    if (includeVin && st.vin[0])  doc[K("vin","vn")]        = st.vin;
    if (includeVin && st.swid[0]) doc[K("softwareId","si")] = st.swid;
    if (st.dm1[0])              doc[K("dm1","d1")]          = st.dm1;
    // dm1Raw is omitted from the BLE payload for the same reason the VIN
    // is: a GATT notification carries at most MTU-3 bytes, 514 at the
    // 517 we negotiate, and the JSON has to fit in one. With tyres
    // reporting and a long DM1 the full payload reaches ~517, which
    // would truncate mid-string and leave the app unable to parse it --
    // silently, and precisely when a fault has just appeared. openHAB
    // still gets it over MQTT, where there is no such limit, and the
    // app never reads the field.
    if (includeVin && st.dm1Raw[0])           doc[K("dm1Raw","dr")]       = st.dm1Raw;

    // The odometer at the last service, kept in the bike's own NVS.
    //
    // Published on both transports so openHAB can chart it and the app can stop
    // depending on its own storage. Omitted entirely when never recorded, which
    // is how a consumer tells "no service logged" from "serviced at 0 km".
    if (serviceLastKm() != SERVICE_KM_UNSET) doc[K("svcKm","sk")] = serviceLastKm();

    // Always present, on both transports. It is twelve bytes, and it is the
    // first thing anyone asks for when a phone and a bike disagree — a client
    // that cannot say which firmware it is talking to makes every report of odd
    // behaviour start with a guess.
    doc[K("fw","fw")] = FW_VERSION;

    return serializeJson(doc, out, cap);
}

// Publish the whole state as one retained JSON, throttled and change-driven.
void publishState() {
    if (millis() - lastStatePub < STATE_PUBLISH_INTERVAL_MS) return;
    bool heartbeat = (STATE_HEARTBEAT_MS > 0) &&
                     (millis() - lastStatePub >= STATE_HEARTBEAT_MS);
    if (!stateDirty && !heartbeat) return;
    if (!mqtt.connected()) { mqttConnect(); if (!mqtt.connected()) return; }
    lastStatePub = millis();
    stateDirty = false;

    char payload[900];
    size_t n = buildStateJson(payload, sizeof(payload), true /*includeVin*/);
    mqtt.publish(topic("state").c_str(), (const uint8_t *)payload, n, true);
}
#endif  // FIRMWARE_MODE == MODE_PRODUCTION


#if TP_REASSEMBLY
// ===========================================================================
// J1939 Transport Protocol reassembly (BAM 0x20 + RTS/CTS 0x10 eavesdrop)
// ---------------------------------------------------------------------------
// PGN 60416 (0xEC00) TP.CM announces a multi-packet transfer:
//   BAM: [0x20, size_lo, size_hi, numPkts, 0xFF, pgn_lo, pgn_mid, pgn_hi]
//   RTS: [0x10, size_lo, size_hi, numPkts, maxPkts, pgn_lo, pgn_mid, pgn_hi]
// PGN 60160 (0xEB00) TP.DT carries the data:
//   [seq(1..N), b0..b6]
// We can't (and won't) send CTS — but both the announce and the data packets
// are broadcast on the wire, so a listen-only node can stitch them together.
// Sessions are keyed by source address; PS (destination) is irrelevant to us.
// ===========================================================================
struct TpSession {
    bool     active;
    uint8_t  sa;
    uint32_t pgn;
    uint16_t size;
    uint8_t  numPkts;
    uint8_t  gotCount;
    uint32_t lastMs;
    uint8_t  buf[TP_MAX_BYTES];
    bool     rcvd[TP_MAX_BYTES / 7 + 2];   // per-packet dedup (seq is 1-based)
};
TpSession tpSessions[TP_MAX_SESSIONS];

void tpExpire() {
    uint32_t now = millis();
    for (auto &s : tpSessions)
        if (s.active && now - s.lastMs > TP_TIMEOUT_MS) s.active = false;
}

TpSession *tpForSa(uint8_t sa) {
    for (auto &s : tpSessions) if (s.active && s.sa == sa) return &s;
    return nullptr;
}

TpSession *tpAlloc(uint8_t sa) {
    for (auto &s : tpSessions) if (!s.active) return &s;   // free slot
    TpSession *oldest = &tpSessions[0];                    // else evict oldest
    for (auto &s : tpSessions) if (s.lastMs < oldest->lastMs) oldest = &s;
    return oldest;
}

// Reassembled message -> same JSON shape as a normal frame so the existing
// openHAB transforms / capture scripts just work, plus tp:true and len.
void tpPublish(TpSession *s) {
    if (!mqtt.connected()) return;
    JsonDocument doc;
    doc["pgn"] = s->pgn;
    doc["sa"]  = s->sa;
    doc["tp"]  = true;
    doc["len"] = s->size;
    JsonArray a = doc["data"].to<JsonArray>();
    for (uint16_t i = 0; i < s->size; i++) a.add(s->buf[i]);
    static char payload[TP_MAX_BYTES * 5 + 64];
    size_t n = serializeJson(doc, payload, sizeof(payload));
    String t = String(MQTT_BASE_TOPIC) + "/pgn/" + String((unsigned long)s->pgn);
    mqtt.publish(t.c_str(), (const uint8_t *)payload, n, true);
    mqtt.publish(topic("frame").c_str(), (const uint8_t *)payload, n, false);
}

// Completion hook for a fully reassembled multi-packet message.
// DISCOVERY: republish the whole payload on /pgn (JS transforms decode it).
// PRODUCTION: decode the identity/diagnostic PGNs straight into the state JSON
// (no /pgn traffic, no openHAB JS) — VIN + softwareId (65242) and DM1 (65226).
void tpComplete(TpSession *s) {
#if FIRMWARE_MODE == MODE_PRODUCTION
    if (s->pgn == 65242)      decodeSoft(s->buf, s->size);   // VIN + software id
    else if (s->pgn == 65226) decodeDM1(s->sa, s->buf, s->size);    // multi-packet DM1
#else
    tpPublish(s);
#endif
}

void handleTransport(const J1939 &j, const CanFrame &f) {
    const uint8_t *d = f.data;
    if (j.pgn == 60416) {                          // TP.CM (announce)
        if (f.data_length_code < 8) return;
        uint8_t ctrl = d[0];
        if (ctrl != 0x20 && ctrl != 0x10) return;  // only BAM / RTS start a xfer
        uint16_t size    = d[1] | (d[2] << 8);
        uint8_t  numPkts = d[3];
        uint32_t pgn     = (uint32_t)d[5] | ((uint32_t)d[6] << 8) | ((uint32_t)d[7] << 16);
        if (size == 0 || size > TP_MAX_BYTES) return;
        if (numPkts == 0 || numPkts > (TP_MAX_BYTES / 7 + 1)) return;
        TpSession *s = tpForSa(j.sa);
        if (!s) s = tpAlloc(j.sa);
        s->active = true; s->sa = j.sa; s->pgn = pgn;
        s->size = size; s->numPkts = numPkts; s->gotCount = 0;
        s->lastMs = millis();
        memset(s->buf, 0xFF, sizeof(s->buf));
        memset(s->rcvd, 0, sizeof(s->rcvd));
    } else if (j.pgn == 60160) {                   // TP.DT (data)
        if (f.data_length_code < 1) return;
        TpSession *s = tpForSa(j.sa);
        if (!s) return;                            // no announce seen for this SA
        uint8_t seq = d[0];
        if (seq < 1 || seq > s->numPkts) return;
        uint16_t off = (uint16_t)(seq - 1) * 7;
        for (int i = 0; i < 7 && off + i < s->size; i++) s->buf[off + i] = d[1 + i];
        if (!s->rcvd[seq]) { s->rcvd[seq] = true; s->gotCount++; }
        s->lastMs = millis();
        if (s->gotCount >= s->numPkts) {           // all packets in -> publish
            tpComplete(s);
            s->active = false;
        }
    }
}
#endif // TP_REASSEMBLY
#endif // ENABLE_MQTT

// Scan one bitrate in listen-only for windowMs, counting frames. Bitrate
// scanning is ALWAYS listen-only, even if TX is enabled — we must never
// transmit onto an unknown/unconfirmed bus. Backend install/teardown is handled
// by the HAL (canInit/canStop).
bool tryRate(uint32_t bitrate, uint32_t windowMs, uint32_t &frameCount) {
    frameCount = 0;
    if (!canInit(bitrate, true)) return false;   // listenOnly = true
    uint32_t start = millis();
    CanFrame msg;
    while (millis() - start < windowMs) {
        if (canReceive(msg, 5)) frameCount++;
    }
    canStop();
    return true;
}

// Operational bus mode after detection. Listen-only unless TX is deliberately
// enabled at compile time (see TX_ENABLED). This is the ONLY place we might
// ever leave listen-only.
bool enterBusMode() {
#if TX_ENABLED
    return canInit(detectedBitrate, false);      // CAN TRANSMIT
#else
    return canInit(detectedBitrate, true);       // never transmits
#endif
}

// ============================================================================
// >>> REMOVE ON CLEANUP <<<  TPMS DISCOVERY implementation
// Tracks per-ID byte min/max + change count since the last 'z' reset, and
// prints a ranked report of slow-changing multi-byte candidates on 'r'.
// ============================================================================
#if TPMS_DISCOVERY
struct TpmsDisc {
    uint8_t  minB[8];
    uint8_t  maxB[8];
    uint8_t  dlc;
    bool     ext;
    uint32_t changes;   // how many times any byte changed since reset
    uint32_t count;     // frames seen since reset
    uint8_t  last[8];
};
std::map<uint32_t, TpmsDisc> discTable;

void discReset() {
    discTable.clear();
    Serial.println("[tpms] baseline reset — now bleed a tyre slowly, then press 'r'");
}

void discFeed(uint32_t id, bool ext, const CanFrame &f) {
    TpmsDisc &d = discTable[id];
    if (d.count == 0) {                     // first sight: seed min/max
        d.dlc = f.data_length_code; d.ext = ext;
        for (int i = 0; i < f.data_length_code; i++) {
            d.minB[i] = d.maxB[i] = d.last[i] = f.data[i];
        }
        d.count = 1;
        return;
    }
    bool changed = false;
    for (int i = 0; i < f.data_length_code && i < 8; i++) {
        uint8_t v = f.data[i];
        if (v < d.minB[i]) d.minB[i] = v;
        if (v > d.maxB[i]) d.maxB[i] = v;
        if (v != d.last[i]) { changed = true; d.last[i] = v; }
    }
    d.count++;
    if (changed) d.changes++;
}

void discReport() {
    // A TPMS-like ID: multi-byte, has SOME movement (a byte range > 0) but is
    // NOT churning like RPM/speed (low change ratio). We score by total byte
    // range while penalising very high change counts.
    Serial.println("\n===== TPMS discovery report =====");
    Serial.println("ID          DLC  chg/seen  moved-bytes (min..max)");
    struct Row { uint32_t id; uint32_t score; String line; };
    std::vector<Row> rows;
    for (auto &kv : discTable) {
        TpmsDisc &d = kv.second;
        if (d.dlc < 2) continue;                 // single-byte switches: skip
        uint32_t range = 0; int movedBytes = 0;
        for (int i = 0; i < d.dlc; i++) {
            uint8_t r = d.maxB[i] - d.minB[i];
            if (r > 0) { range += r; movedBytes++; }
        }
        if (range == 0) continue;                // static: not it
        // change ratio in percent; TPMS should be LOW (rarely changes)
        uint32_t ratio = (d.changes * 100) / (d.count ? d.count : 1);
        if (ratio > 60) continue;                // fast churn (RPM/speed): skip
        // score: prefer some movement, few changes, a couple of moved bytes
        uint32_t score = range * (movedBytes) * (100 - ratio);
        char buf[96];
        snprintf(buf, sizeof(buf), "%s0x%0*lX  %d   %lu/%lu (%lu%%)",
                 d.ext ? "" : "", d.ext ? 8 : 3, (unsigned long)kv.first,
                 d.dlc, (unsigned long)d.changes, (unsigned long)d.count,
                 (unsigned long)ratio);
        String line = String(buf) + "  ";
        for (int i = 0; i < d.dlc; i++) {
            if (d.maxB[i] - d.minB[i] > 0) {
                char b[24];
                snprintf(b, sizeof(b), "b%d:%d..%d ", i, d.minB[i], d.maxB[i]);
                line += b;
            }
        }
        rows.push_back({ kv.first, score, line });
    }
    // simple sort: highest score first
    for (size_t a = 0; a < rows.size(); a++)
        for (size_t b = a + 1; b < rows.size(); b++)
            if (rows[b].score > rows[a].score) std::swap(rows[a], rows[b]);
    if (rows.empty()) {
        Serial.println("(no slow-changing multi-byte candidates yet — ride to wake");
        Serial.println(" the TPMS sensors, press 'z', bleed a tyre, then 'r' again)");
    } else {
        int shown = 0;
        for (auto &r : rows) { Serial.println(r.line); if (++shown >= 15) break; }
    }
    Serial.println("=================================\n");
}

void discPoll() {                 // check serial monitor for z / r commands
    while (Serial.available()) {
        char c = Serial.read();
        if (c == 'z' || c == 'Z') discReset();
        else if (c == 'r' || c == 'R') discReport();
    }
}
#endif // TPMS_DISCOVERY
// >>> END REMOVE ON CLEANUP <<<

#if TX_ENABLED
// Send a J1939 Request (PGN 59904 / 0xEA00) asking node(s) to transmit `pgn`.
// Destination 0xFF = global broadcast. Priority 6. Our source address 0xFE.
// Example: request VIN via PGN 65260 (0xFEEC) -> sendJ1939Request(65260).
void sendJ1939Request(uint32_t pgn) {
    CanFrame req = {};
    req.identifier = ((uint32_t)6 << 26) | ((uint32_t)0xEA << 16) |
                     ((uint32_t)0xFF << 8) | 0xFE;
    req.extd = true;                   // 29-bit extended frame
    req.data_length_code = 3;
    req.data[0] =  pgn        & 0xFF;  // requested PGN, little-endian
    req.data[1] = (pgn >> 8)  & 0xFF;
    req.data[2] = (pgn >> 16) & 0xFF;
    canTransmit(req);
    Serial.printf("[tx] J1939 request for PGN %lu sent\n", (unsigned long)pgn);
}
#endif

#if DEBUG_USB_FRAMES
void printFrame(const CanFrame &frame, uint32_t id, bool ext) {
    Serial.printf("%s 0x%0*X ", ext ? "EXT" : "STD", ext ? 8 : 3, id);
    J1939 j = decodeJ1939(id, ext);
    if (j.valid) Serial.printf("PGN=%-6lu SA=%-3d P=%d ",
                               (unsigned long)j.pgn, j.sa, j.priority);
    Serial.printf("[%d] ", frame.data_length_code);
    for (int i = 0; i < frame.data_length_code; i++) Serial.printf("%02X ", frame.data[i]);
    Serial.println();
}
#endif

// Update the per-ID table; mark dirty only when the data actually changed.
// DISCOVERY-only.
#if FIRMWARE_MODE == MODE_DISCOVERY
void updateTable(uint32_t id, bool ext, const CanFrame &frame) {
    IdState &s = idTable[id];
    s.count++;
    s.ext = ext;
    bool changed = (s.dlc != frame.data_length_code) ||
                   memcmp(s.data, frame.data, frame.data_length_code) != 0;
    if (changed) {
        s.dlc = frame.data_length_code;
        memcpy(s.data, frame.data, frame.data_length_code);
        s.dirty = true;
    }
}
#endif

// Run a listen-only bitrate scan and, on success, lock the driver into bus mode.
//
// firstBoot=true : probe EVERY rate once (generic first detection).
// firstBoot=false: probe ONE rate per call, round-robin, so each retry blocks
//                  only ~SCAN_WINDOW_MS (not NUM_RATES×) and MQTT keepalive is
//                  serviced between retries.
//
// Returns true once a live bus is found and bus mode is entered. While it keeps
// returning false, loop() simply calls it again — this is what lets the sniffer
// AUTO-ATTACH when the ignition is switched on AFTER the ESP has already booted,
// with NO reboot required (previously the scan ran once in setup() only, so a
// boot with ignition off left the device idle forever).
bool scanAndEnterBus(bool firstBoot) {
    static int rrIdx = 0;                 // round-robin cursor for retries
    uint32_t best = 0; int bestIdx = -1;

    if (firstBoot) {
        Serial.println("Scanning bitrates (listen-only)...");
        for (int i = 0; i < CAN_NUM_RATES; i++) {
            uint32_t cnt = 0;
            bool ok = tryRate(CAN_RATES[i].bitrate, SCAN_WINDOW_MS, cnt);
            Serial.printf("  %-9s : %-14s frames=%lu\n",
                          CAN_RATES[i].name, ok ? "listening" : "install-failed",
                          (unsigned long)cnt);
            if (ok && cnt > best) { best = cnt; bestIdx = i; }
        }
    } else {
        // One rate per call so a silent-bus retry never blocks the network
        // stack for more than a single scan window.
        int i = rrIdx;
        rrIdx = (rrIdx + 1) % CAN_NUM_RATES;
        uint32_t cnt = 0;
        bool ok = tryRate(CAN_RATES[i].bitrate, SCAN_WINDOW_MS, cnt);
        if (ok && cnt > 0) { best = cnt; bestIdx = i; }
    }

    if (bestIdx < 0 || best == 0) {
        // Bus silent — almost always just the ignition being OFF. Keep retrying
        // from loop(); it will attach by itself once the bus wakes up.
        if (firstBoot) {
            logEvent("!! No frames on any rate (ignition OFF?).");
            logEvent("   Will keep re-scanning until the bus wakes up — NO reboot needed.");
            logEvent("   If it never appears, check: PIN_5V_EN HIGH (transceiver");
            logEvent("   power), CAN_SE LOW, onboard 120R jumper, CANH/CANL swap, GND.");
            noCanReported = true;
            logEvent("   MQTT heartbeat continues meanwhile.");
        }
        return false;
    }

    detectedBitrate = CAN_RATES[bestIdx].bitrate;
    detectedName    = CAN_RATES[bestIdx].name;
    scanFrames      = best;
    Serial.printf("\n>> Detected bus: %s  (%lu frames / %d ms)\n\n",
                  detectedName, (unsigned long)best, SCAN_WINDOW_MS);

    if (!enterBusMode()) {
        logEvent("!! Bus detected but driver install failed; will retry.");
        return false;
    }
    haveSpeed     = true;
    noCanReported = false;

#if TX_ENABLED
    // Example: uncomment to request the VIN once at startup (PGN 65260 / 0xFEEC).
    // Requires TX_ENABLED = 1. Engine OFF recommended.
    // delay(200); sendJ1939Request(65260);
    Serial.println("[tx] TX_ENABLED=1 — device CAN transmit. Use with care.");
#endif

#if ENABLE_MQTT
    if (mqtt.connected()) {
        JsonDocument meta;
        meta["bitrate"] = detectedName;
        meta["scan_frames"] = best;
        char buf[128]; size_t n = serializeJson(meta, buf, sizeof(buf));
        mqtt.publish(topic("meta").c_str(), (const uint8_t *)buf, n, true);
        logEventf(">> Detected bus: %s (%lu frames / %d ms)",
                  detectedName, (unsigned long) best, SCAN_WINDOW_MS);
    }
#endif
    logEvent("Logging frames (USB) + publishing changes (MQTT):");
#if FIRMWARE_MODE == MODE_DISCOVERY && TPMS_DISCOVERY
    // >>> REMOVE ON CLEANUP <<<
    logEvent("[tpms] discovery ON: serial 'z'=reset baseline, 'r'=report");
#endif
    return true;
}

// ===========================================================================
// Onboard status LED — at-a-glance health with no USB/MQTT needed.
// ---------------------------------------------------------------------------
// Colour = state, animation = liveness:
//   white  (steady)      boot / initialising
//   blue   (blink 2 Hz)  connecting WiFi
//   blue   (breathe)     WiFi up, connecting MQTT
//   amber  (breathe)     online but CAN bus not locked yet (ignition off)
//   GREEN  (heartbeat)   all good: CAN bus locked + network up (running)
//   cyan   (heartbeat)   CAN bus locked, USB-only / MQTT down (still reading!)
//   red    (steady)      CAN driver fault
// A steady heartbeat/breathe means the loop() is alive — a frozen LED = a hung
// board. Disabled entirely with STATUS_LED 0 in config.h.
// ===========================================================================
#if STATUS_LED
enum LedState { LED_BOOT, LED_WIFI, LED_MQTT, LED_NOCAN, LED_RUN, LED_RUN_NONET, LED_FAULT };
bool     gLedFault = false;   // set true if the TWAI driver failed to install

void ledSetup() {
    FastLED.addLeds<WS2812B, LED_PIN, LED_ORDER>(gLeds, LED_COUNT);
    FastLED.setBrightness(60);            // cap current draw; plenty visible
    gLeds[0] = CRGB::White;               // boot = white
    FastLED.show();
}

static LedState ledCurrentState() {
    if (gLedFault) return LED_FAULT;
    if (haveSpeed) {                      // CAN bus locked = the important bit
#if ENABLE_MQTT
        return mqtt.connected() ? LED_RUN : LED_RUN_NONET;
#else
        return LED_RUN_NONET;             // USB-only build: no network to expect
#endif
    }
#if ENABLE_MQTT
    if (WiFi.status() != WL_CONNECTED) return LED_WIFI;
    if (!mqtt.connected())             return LED_MQTT;
    return LED_NOCAN;                     // fully online, just waiting for the bus
#else
    return LED_NOCAN;
#endif
}

// Non-blocking: recompute colour+brightness from millis() and push at ~30 Hz.
void ledUpdate() {
    static uint32_t lastShow = 0;
    if (millis() - lastShow < 33) return;
    lastShow = millis();

    CRGB    base;
    uint8_t bright;
    switch (ledCurrentState()) {
        case LED_FAULT:                   // steady red
            base = CRGB::Red;  bright = 255; break;
        case LED_WIFI: {                  // blue blink 2 Hz
            base = CRGB::Blue; bright = ((millis() / 250) & 1) ? 255 : 0; break; }
        case LED_MQTT: {                  // blue breathe
            base = CRGB::Blue; bright = 25 + scale8(sin8(millis() / 4), 230); break; }
        case LED_NOCAN: {                 // amber breathe
            base = CRGB(255, 120, 0); bright = 25 + scale8(sin8(millis() / 4), 230); break; }
        case LED_RUN_NONET:               // cyan heartbeat (CAN ok, no MQTT)
        case LED_RUN: {                   // green heartbeat (all good)
            base = (ledCurrentState() == LED_RUN) ? CRGB::Green : CRGB::Cyan;
            uint16_t t = millis() % 1000; // double-beat like a pulse
            if      (t < 100) bright = map(t,   0, 100,  25, 255);
            else if (t < 200) bright = map(t, 100, 200, 255,  25);
            else if (t < 320) bright = map(t, 200, 320,  25, 200);
            else if (t < 420) bright = map(t, 320, 420, 200,  25);
            else              bright = 25;
            break; }
        default:                          // white steady (boot)
            base = CRGB::White; bright = 200; break;
    }
    gLeds[0] = base;
    gLeds[0].nscale8_video(bright);
    FastLED.show();
}
#endif  // STATUS_LED

void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println();
    Serial.println("========================================================");
    Serial.println(" Indian Springfield 2017 - CAN sniffer (LISTEN-ONLY)");
#if CAN_BACKEND == CAN_BACKEND_MCP2518
    Serial.println(" USB + MQTT | LilyGO T-2CANFD (MCP2518FD/SPI, CAN A) | never TX");
#else
    Serial.println(" USB + MQTT | LilyGO T-CAN485 (ESP32 TWAI) | never TX");
#endif
    Serial.println("========================================================");

    // Board-specific CAN bring-up (transceiver power on T-CAN485, or SPI on the
    // T-2CANFD) is handled by the selected HAL backend so main.cpp stays
    // board-agnostic.
    canHardwareInit();

#if STATUS_LED
    ledSetup();                           // onboard RGB LED -> white (boot)
#endif

#if FIRMWARE_MODE == MODE_PRODUCTION
    resetState();
    Serial.println(" MODE: PRODUCTION — decoding in-firmware, publishing canbus/indian/state");
#else
    Serial.println(" MODE: DISCOVERY — raw firehose (every id/pgn/frame)");
#endif

    // BLE BEFORE WiFi, deliberately. Bringing the BT controller up while the
    // station is already associated crashed setup() with
    //     Guru Meditation Error: Core 0 panic'ed (Unhandled debug exception)
    //     Debug exception reason: Stack canary watchpoint triggered (ipc0)
    // on 2026-09-02: NimBLE's controller init reaches the other core through
    // esp_ipc_call_blocking, whose task stack is only 1024 bytes, and the
    // WiFi-already-up coexistence path pushes it over. Initialising BLE first
    // moves that coexistence work into WiFi's own (much larger) init stack.
    // Marginal-stack bugs are intermittent (the device booted fine on the retry
    // with the unfixed build), so this was only accepted after 3-4 clean cold
    // boots on 2026-09-02 — one good boot proves nothing here.
    // Before bleSetup(): NimBLE runs its callbacks on its own task, so a write
    // could in principle land the moment the server exists. Loading the stored
    // value first means such a write is validated against a real figure rather
    // than against an uninitialised one.
    serviceBegin();
    countersBegin();      // tallies that must outlive an OTA
    probeFlagsBegin();    // which probes run; also outlives an OTA
    bleSetup();

#if ENABLE_MQTT
    wifiConnect();
#endif

    // First detection: probe every rate once. If the ignition is OFF (silent
    // bus) this returns without locking and loop() keeps retrying — the device
    // auto-attaches when the bus wakes up, so a boot-with-ignition-off no longer
    // requires a reboot.
    scanAndEnterBus(true);
}

#if ENABLE_MQTT
// Non-blocking network watchdog: reconnect WiFi (10 s backoff) and MQTT (5 s
// backoff) WITHOUT rebooting, so a dropped link self-heals in place.
uint32_t lastWifiRetry = 0;
uint32_t lastMqttRetry = 0;
void ensureNetwork() {
    if (WiFi.status() != WL_CONNECTED) {
        if (millis() - lastWifiRetry > 10000) {
            lastWifiRetry = millis();
            Serial.println("[wifi] link down - reconnecting");
            WiFi.disconnect();
            WiFi.reconnect();
        }
        return;                            // no point touching MQTT yet
    }
    if (!mqtt.connected()) {
        if (millis() - lastMqttRetry > 5000) {
            lastMqttRetry = millis();
            mqttConnect();
        }
    }
    mqtt.loop();                           // service keepalive / RX
}
#endif

void loop() {
#if ENABLE_MQTT
    ensureNetwork();
#if FIRMWARE_MODE == MODE_PRODUCTION && PROBE_CHANGES
    // Once per pass, so probeFrame never reaches into the network stack.
    probeOnline = mqtt.connected();
    cruiseUpdate();          // derived cruise hold; see the cruise section above
#endif
#if FIRMWARE_MODE == MODE_PRODUCTION
    // The bus has gone quiet: the ignition is off, and the live readings are
    // now history. Cleared once per transition, not once per pass.
    static bool liveCleared = false;
    if (lastFrameMs != 0 && millis() - lastFrameMs > 3000u) {
        if (!liveCleared) { liveCleared = true; clearLiveValues(); }
    } else if (lastFrameMs != 0) {
        liveCleared = false;
    }
#endif
    if (mqtt.connected()) {
        mqtt.loop();              // MQTT message processing (calls callback)
        publishHeartbeat();
        ArduinoOTA.handle();       // OTA update handler (non-blocking when idle)
    }
#else
    ArduinoOTA.handle();
#endif
#if STATUS_LED
    ledUpdate();                          // refresh onboard health LED (~30 Hz)
#endif

#if FIRMWARE_MODE == MODE_PRODUCTION
    // Deliberately outside the ENABLE_MQTT/mqtt.connected() guards below: BLE is
    // the transport that must keep working with no WiFi. Placed before the
    // !haveSpeed return for the same reason the MQTT heartbeat is — a connected
    // phone should keep seeing the last known values on a bike with the ignition
    // off, not a frozen screen.
    bleUpdate(st);
#endif

    if (!haveSpeed) {
        // No bus locked yet — the ignition was probably OFF at boot. Re-scan
        // periodically (one rate per attempt, ~SCAN_WINDOW_MS each) so we attach
        // automatically the moment the bus wakes up, without a reboot. MQTT
        // keepalive/heartbeat above still runs between attempts.
        static uint32_t lastRescan = 0;
        uint32_t rescanGap = noCanReported ? 3000 : 1000;
        if (millis() - lastRescan > rescanGap) {
            lastRescan = millis();
            scanAndEnterBus(false);
        }
        // Keep publishing while the bus is down. This return used to skip the
        // state publish entirely, which meant the one transition that matters
        // most -- the ignition going OFF -- could never be reported: the field
        // says the ignition is off precisely BECAUSE the bus went quiet, and
        // the old code answered that by going quiet too.
        //
        // Nothing dishonest is published here. Every field is guarded on having
        // been seen, so unknowns stay absent, and the values that do carry over
        // are still true: a bike parked on its stand is still on its stand
        // after the ignition is switched off.
        publishState();
        delay(50);
        return;
    }

    // Drain the RX queue, but cap the work per pass so a saturated bus can
    // never starve the WiFi/MQTT stack (that starvation caused the flapping).
    CanFrame frame;
    int processed = 0;
    while (processed < MAX_FRAMES_PER_PASS && canReceive(frame, 0)) {
        processed++;
        lastFrameMs = millis();
        bool ext = frame.extd;
        uint32_t id = frame.identifier;
#if DEBUG_USB_FRAMES
        printFrame(frame, id, ext);       // bench-only: floods Serial @115200
#endif
#if FIRMWARE_MODE == MODE_PRODUCTION
        decodeState(id, ext, frame);      // in-firmware decode -> one state JSON
#if ENABLE_MQTT && TP_REASSEMBLY
        if (ext) {                        // stitch VIN/SW/DM1 multi-packet msgs
            J1939 jt = decodeJ1939(id, ext);
            if (jt.valid && (jt.pgn == 60416 || jt.pgn == 60160))
                handleTransport(jt, frame);
        }
#endif
#else
        updateTable(id, ext, frame);      // table for MQTT (changes only)
#if ENABLE_MQTT && TP_REASSEMBLY
        if (ext) {                        // stitch multi-packet TP/BAM messages
            J1939 jt = decodeJ1939(id, ext);
            if (jt.valid && (jt.pgn == 60416 || jt.pgn == 60160))
                handleTransport(jt, frame);
        }
#endif
#if TPMS_DISCOVERY
        discFeed(id, ext, frame);         // >>> REMOVE ON CLEANUP <<<
#endif
#endif  // FIRMWARE_MODE
    }

#if FIRMWARE_MODE == MODE_DISCOVERY && TPMS_DISCOVERY
    discPoll();                           // >>> REMOVE ON CLEANUP <<< : z=reset r=report
#endif

#if ENABLE_MQTT
    if (mqtt.connected()) {
        mqtt.loop();
#if FIRMWARE_MODE == MODE_PRODUCTION
        publishState();                   // ONE retained state JSON, throttled
#else
        publishChanges();                 // MQTT: throttled, changes only
#endif
    }
#if TP_REASSEMBLY
    tpExpire();                           // drop stalled multi-packet transfers
#endif
#endif
}

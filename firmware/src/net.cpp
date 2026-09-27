/*
 * net.cpp -- WiFi, MQTT and OTA in their own task. See net.h for the contract
 * and the reason it exists. What follows is the same network code main.cpp
 * carried until 2026-09-27, moved behind two queues; the comments that
 * explain a number or an order of operations came along with it.
 */
#include "net.h"

#if ENABLE_WIFI

#include <WiFi.h>
#include <Preferences.h>
#include <esp_netif.h>            // esp_netif_tcpip_exec(): run a call in the lwIP thread
#include <lwip/dns.h>             // dns_clear_cache()
#include <time.h>
#include <esp_timer.h>
#include <ArduinoOTA.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/queue.h>
#if ENABLE_MQTT
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <HTTPUpdate.h>
#endif

// ---- queues and flags -------------------------------------------------------

enum OutKind : uint8_t { OUT_PUBLISH = 0, OUT_LOG = 1 };

struct OutMsg {
    uint8_t  kind;
    bool     retained;
    uint16_t len;
    char     topic[80];
    char     payload[];            // len bytes + NUL
};

struct InMsg {
    char leaf[48];
    char payload[96];
};

static const UBaseType_t OUT_DEPTH = 16;   // 16 x 1.5 kB of state at the very worst
static const UBaseType_t IN_DEPTH  = 8;

static QueueHandle_t sOutQ = nullptr;
static QueueHandle_t sInQ  = nullptr;
static NetInboundFn  sOnMessage = nullptr;

static volatile bool     sWifiUp  = false;
static volatile bool     sMqttUp  = false;
static volatile bool     sBusy    = false;    // HTTP OTA running
static volatile bool     sSending = false;    // task is inside mqtt.publish()
static volatile uint32_t sDropped = 0;

static String baseTopic(const char *leaf) { return String(MQTT_BASE_TOPIC) + "/" + leaf; }

// ---- the log while the broker is out of reach --------------------------------
//
// Every log line goes to Serial and to <base>/debug, and nobody holds the serial
// port on a motorcycle. The lines that explain a failover -- link down,
// rescanning, joined the hotspot, broker resolved to WHAT, connect failed WHY --
// are written while there is no broker to write them to. So they are kept here
// and published, in order and with the uptime they were written at, as soon as
// the next connection is made. The FIRST lines of an outage are kept, not the
// last; a line identical to the one before it is counted, not stored.
static const uint8_t  DBG_RING_LINES = 24;
static const uint8_t  DBG_RING_WIDTH = 112;
static char     sDbgLine[DBG_RING_LINES][DBG_RING_WIDTH];
static uint32_t sDbgAtSec[DBG_RING_LINES];
static uint16_t sDbgRepeat[DBG_RING_LINES];
static uint8_t  sDbgCount = 0;
static uint16_t sDbgDropped = 0;

static void ringAdd(const char *msg) {
    if (sDbgCount > 0 && strncmp(sDbgLine[sDbgCount - 1], msg, DBG_RING_WIDTH - 1) == 0) {
        if (sDbgRepeat[sDbgCount - 1] < 65535) sDbgRepeat[sDbgCount - 1]++;
        return;
    }
    if (sDbgCount >= DBG_RING_LINES) {
        if (sDbgDropped < 65535) sDbgDropped++;
        return;
    }
    strncpy(sDbgLine[sDbgCount], msg, DBG_RING_WIDTH - 1);
    sDbgLine[sDbgCount][DBG_RING_WIDTH - 1] = '\0';
    sDbgAtSec[sDbgCount]  = millis() / 1000;
    sDbgRepeat[sDbgCount] = 1;
    sDbgCount++;
}

#if ENABLE_MQTT
static WiFiClientSecure sWifiClient;
static PubSubClient     sMqtt(sWifiClient);
static uint32_t         sMqttLastTry = 0;
static const uint32_t   MQTT_RETRY_MS = 15000;
static volatile bool    sOtaRequested = false;

static void debugPublish(const char *msg) {
    sMqtt.publish(baseTopic("debug").c_str(), (const uint8_t *)msg, strlen(msg), false);
}

static void ringFlush() {
    if (sDbgCount == 0 && sDbgDropped == 0) return;
    char out[DBG_RING_WIDTH + 48];
    snprintf(out, sizeof(out),
             "[log] %u line(s) written while the broker was out of reach (uptime now %lus):",
             (unsigned)sDbgCount, (unsigned long)(millis() / 1000));
    debugPublish(out);
    for (uint8_t i = 0; i < sDbgCount; i++) {
        if (sDbgRepeat[i] > 1)
            snprintf(out, sizeof(out), "[+%lus] %s (x%u)",
                     (unsigned long)sDbgAtSec[i], sDbgLine[i], (unsigned)sDbgRepeat[i]);
        else
            snprintf(out, sizeof(out), "[+%lus] %s", (unsigned long)sDbgAtSec[i], sDbgLine[i]);
        debugPublish(out);
    }
    if (sDbgDropped) {
        snprintf(out, sizeof(out), "[log] %u more line(s) dropped; the buffer holds %u",
                 (unsigned)sDbgDropped, (unsigned)DBG_RING_LINES);
        debugPublish(out);
    }
    sDbgCount = 0;
    sDbgDropped = 0;
}
#endif

// Task-side log line: Serial, then the broker if it is there, the ring if not.
static void tlog(const char *msg) {
    Serial.println(msg);
#if ENABLE_MQTT
    if (sMqtt.connected()) { debugPublish(msg); return; }
#endif
    ringAdd(msg);
}

static void tlogf(const char *fmt, ...) {
    char buf[192];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    tlog(buf);
}

// ---- producer side (any task) ----------------------------------------------

static bool enqueue(uint8_t kind, const char *topic, const char *payload, size_t len, bool retained) {
    if (!sOutQ) return false;
    if (len > NET_PAYLOAD_MAX) { sDropped = sDropped + 1; return false; }
    OutMsg *m = (OutMsg *)malloc(sizeof(OutMsg) + len + 1);
    if (!m) { sDropped = sDropped + 1; return false; }
    m->kind = kind;
    m->retained = retained;
    m->len = (uint16_t)len;
    strncpy(m->topic, topic ? topic : "", sizeof(m->topic) - 1);
    m->topic[sizeof(m->topic) - 1] = '\0';
    memcpy(m->payload, payload, len);
    m->payload[len] = '\0';
    if (xQueueSend(sOutQ, &m, 0) != pdTRUE) {   // never wait: this is the loop
        free(m);
        sDropped = sDropped + 1;
        return false;
    }
    return true;
}

bool netPublishTopic(const char *fullTopic, const char *payload, size_t len, bool retained) {
    // Same rule the publishers had when they held the client themselves: a
    // publish with no broker is dropped, not queued. Queueing it would replay a
    // backlog of stale state the moment the link returns.
    if (!sMqttUp) return false;
    return enqueue(OUT_PUBLISH, fullTopic, payload, len, retained);
}

bool netPublish(const char *leaf, const char *payload, size_t len, bool retained) {
    return netPublishTopic(baseTopic(leaf).c_str(), payload, len, retained);
}

void netLog(const char *line) {
    // Logs are queued whether or not the broker is there: the task decides
    // between publishing and the ring, which only it may touch.
    enqueue(OUT_LOG, "", line, strlen(line), false);
}

bool netWifiConnected() { return sWifiUp; }
bool netMqttConnected() { return sMqttUp; }
bool netBusy()          { return sBusy; }
uint32_t netDropped()   { return sDropped; }
String netIp()          { return sWifiUp ? WiFi.localIP().toString() : String(); }
int netRssi()           { return sWifiUp ? (int)WiFi.RSSI() : 0; }

void netPoll() {
    if (!sInQ || !sOnMessage) return;
    InMsg m;
    while (xQueueReceive(sInQ, &m, 0) == pdTRUE) sOnMessage(m.leaf, m.payload);
}

bool netFlush(uint32_t timeoutMs) {
    if (!sMqttUp || !sOutQ) return false;
    const uint32_t t0 = millis();
    while ((uxQueueMessagesWaiting(sOutQ) > 0 || sSending) && millis() - t0 < timeoutMs)
        delay(5);
    return uxQueueMessagesWaiting(sOutQ) == 0 && !sSending;
}

// ---- WiFi ---------------------------------------------------------------------

// mqtt.example.com is one address from inside the house and another from anywhere
// else: the home resolver answers with the LAN address, TTL 3600. lwIP caches
// that answer for the full hour and does NOT empty the cache when the board
// moves to another network. On 2026-09-15 the bike left the garage, joined the
// phone's hotspot, asked the cache where the broker was, got the LAN address,
// and sent every SYN of the ride into a private address that does not exist on
// the cellular side. dns_clear_cache() is lwIP's own; this core asserts when it
// is entered from outside the lwIP thread, so it is handed to
// esp_netif_tcpip_exec(), which runs it there and waits.
static esp_err_t dnsClearInTcpipThread(void *) { dns_clear_cache(); return ESP_OK; }
static void flushDnsCache() { esp_netif_tcpip_exec(dnsClearInTcpipThread, nullptr); }

static const char *sSsid[3] = { WIFI_SSID, WIFI_SSID2, WIFI_SSID3 };
static const char *sPass[3] = { WIFI_PASSWORD, WIFI_PASSWORD2, WIFI_PASSWORD3 };

static bool tryAssociate(int idx, uint32_t timeoutMs) {
    if (!sSsid[idx] || strlen(sSsid[idx]) == 0) return false;
    Serial.printf("[wifi] attempting SSID%d: %s\n", idx + 1, sSsid[idx]);
    // Fully reset the STA state before each attempt: a new WiFi.begin() while
    // the previous one is still "connecting" fails with ESP_ERR_WIFI_STATE and
    // failover never happens.
    WiFi.disconnect(true);
    vTaskDelay(pdMS_TO_TICKS(200));
    WiFi.begin(sSsid[idx], sPass[idx]);
    const uint32_t start = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - start < timeoutMs)
        vTaskDelay(pdMS_TO_TICKS(100));
    return WiFi.status() == WL_CONNECTED;
}

// Pick the network by strength, not by memory. Until 2026-09-27 the SSID that
// worked last time was tried first and kept the moment it answered -- so a
// bike in the garage sat on the house network at -82 dBm while the phone's
// hotspot stood next to it, and a download that should take 20 s took six
// minutes and died. A scan costs two to three seconds here in the task, where
// nothing waits for it, and puts the board on the best of the networks it
// knows. The remembered order is still the fallback when the scan sees none of
// them (a hidden SSID, or a scan that came back empty).
//
// Twenty seconds per SSID, not ten: the board runs BLE and WiFi on one radio
// with no coexistence tuning, and association has to share it with a connected
// phone. Only affordable because this no longer blocks anything.
static bool wifiConnect() {
    const uint32_t WIFI_TIMEOUT_MS = 20000;
    WiFi.mode(WIFI_STA);

    Preferences prefs;
    prefs.begin("wificfg", false);
    const int remembered = prefs.getUChar("last", 0) % 3;
    prefs.end();

    // Scan, and rank the known networks by what the scan saw.
    WiFi.disconnect(true);
    vTaskDelay(pdMS_TO_TICKS(200));
    int order[3]; int nOrder = 0;
    int rssiOf[3] = { -1000, -1000, -1000 };
    const int found = WiFi.scanNetworks(false, false, false, 300);
    for (int i = 0; i < found; i++) {
        for (int k = 0; k < 3; k++) {
            if (!sSsid[k] || strlen(sSsid[k]) == 0) continue;
            if (WiFi.SSID(i) == sSsid[k] && WiFi.RSSI(i) > rssiOf[k]) rssiOf[k] = WiFi.RSSI(i);
        }
    }
    WiFi.scanDelete();
    for (int k = 0; k < 3; k++) if (rssiOf[k] > -1000) order[nOrder++] = k;
    for (int a = 0; a < nOrder; a++)                    // strongest first
        for (int b = a + 1; b < nOrder; b++)
            if (rssiOf[order[b]] > rssiOf[order[a]]) { int t = order[a]; order[a] = order[b]; order[b] = t; }
    if (nOrder > 0) {
        char line[160]; size_t p = 0;
        p += snprintf(line + p, sizeof(line) - p, "[wifi] scan:");
        for (int a = 0; a < nOrder && p < sizeof(line) - 24; a++)
            p += snprintf(line + p, sizeof(line) - p, " %s %d", sSsid[order[a]], rssiOf[order[a]]);
        tlog(line);
    } else {
        tlogf("[wifi] scan saw none of the known networks (%d found); trying the remembered order", found);
        for (int n = 0; n < 3; n++) order[nOrder++] = (remembered + n) % 3;
    }

    for (int a = 0; a < nOrder; a++) {
        const int idx = order[a];
        if (!tryAssociate(idx, WIFI_TIMEOUT_MS)) { Serial.println("[wifi] no association"); continue; }
        // Remember it, but only when it changed: NVS has a write budget.
        if (idx != remembered) {
            Preferences wp;
            wp.begin("wificfg", false);
            wp.putUChar("last", (uint8_t)idx);
            wp.end();
        }
        return true;
    }
    tlog("[wifi] FAILED - all SSID attempts exhausted");
    return false;
}

static bool waitForClockSync(uint32_t timeoutMs) {
    const time_t minValidEpoch = 1700000000;   // 2023-11-14: good enough for TLS validity
    const uint32_t start = millis();
    time_t now = time(nullptr);
    while (now < minValidEpoch && millis() - start < timeoutMs) {
        vTaskDelay(pdMS_TO_TICKS(100));
        now = time(nullptr);
    }
    if (now < minValidEpoch) { tlog("[time] SNTP sync FAILED - TLS handshake may fail"); return false; }
    struct tm tmNow; gmtime_r(&now, &tmNow);
    char buf[32]; strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S UTC", &tmNow);
    tlogf("[time] SNTP synced: %s", buf);
    return true;
}

// Per-board unique client id and OTA hostname: MQTT_CLIENT_ID + last 3 MAC
// bytes. Two boards from the same config.h would otherwise share an id, and a
// broker evicts the older session when a duplicate connects.
static const char *clientId() {
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

static bool sOtaReady = false;
#if ENABLE_MQTT
static void onMqttMessage(char *inTopic, byte *payload, unsigned int length);
#endif

// Once per link-up: what the old wifiConnect() did after association.
static void onWifiUp() {
    tlogf("[wifi] connected to %s, IP %s, rssi %d",
          WiFi.SSID().c_str(), WiFi.localIP().toString().c_str(), (int)WiFi.RSSI());
    flushDnsCache();                       // this network's resolver, not the last one's
#if ENABLE_MQTT
    IPAddress brokerIp;
    if (WiFi.hostByName(MQTT_BROKER, brokerIp)) tlogf("[wifi] broker %s resolved to %s", MQTT_BROKER, brokerIp.toString().c_str());
    else                                        tlogf("[wifi] DNS failed for broker %s", MQTT_BROKER);
#endif
    configTime(0, 0, "dk.pool.ntp.org", "pool.ntp.org", "time.cloudflare.com");
    waitForClockSync(20000);
#if ENABLE_MQTT
    static bool clientConfigured = false;
    if (!clientConfigured) {
        clientConfigured = true;
        sWifiClient.setCACert(MQTT_ROOT_CA);
        // The two things that block when the hotspot is up but the cellular
        // link is not: the core's defaults are 30 s for the TCP connect and
        // 120 s for the TLS handshake. A SYN/ACK over cellular is well under a
        // second; a handshake with a 2048-bit key on this chip is two to three.
        sWifiClient.setConnectionTimeout(5000);
        sWifiClient.setHandshakeTimeout(10000);
        sMqtt.setServer(MQTT_BROKER, MQTT_PORT);
        sMqtt.setBufferSize(3072);         // room for reassembled TP messages
        // Keepalive well ABOVE the 30 s heartbeat so one slow or dropped
        // PINGRESP cannot fire the last will: through a hotspot the broker is
        // reached via a cellular -> internet -> home NAT hairpin, and 30/10
        // produced an "online then offline" flap whenever the bus was idle.
        sMqtt.setKeepAlive(60);
        sMqtt.setSocketTimeout(15);
        sMqtt.setCallback(onMqttMessage);  // before the first connect
    }
    sMqttLastTry = 0;                      // a fresh link earns an immediate try
#endif
    if (!sOtaReady) {
        sOtaReady = true;
        ArduinoOTA.setHostname(clientId());
        ArduinoOTA.setPassword("");
        ArduinoOTA.setTimeout(120000);     // high-latency links
        ArduinoOTA.onStart([]() { tlog("[ota] OTA update starting..."); });
        ArduinoOTA.onEnd([]()   { tlog("[ota] OTA update complete, rebooting..."); });
        ArduinoOTA.onError([](ota_error_t e) { tlogf("[ota] OTA error code %u", (unsigned)e); });
        ArduinoOTA.begin();
        tlogf("[ota] ready (hostname: %s.local:3232)", clientId());
    }
}

// The link watchdog the loop used to run: three cheap reconnects (a router
// that rebooted, a brief dropout) ten seconds apart, then the full scan --
// because a bike that rode away needs the list, not the SSID it just lost.
static void serviceWifi() {
    static uint32_t lastRetry = 0;
    static uint8_t  cheapRetries = 0;
    static bool     everConnected = false;

    if (WiFi.status() == WL_CONNECTED) {
        if (!sWifiUp) { sWifiUp = true; everConnected = true; cheapRetries = 0; onWifiUp(); }
        return;
    }
    if (sWifiUp) { sWifiUp = false; sMqttUp = false; tlog("[wifi] link down"); }
    if (lastRetry != 0 && millis() - lastRetry < 10000) return;   // ten seconds between attempts, the first one at once
    lastRetry = millis();
    if (everConnected && ++cheapRetries <= 3) {
        tlogf("[wifi] link down - reconnecting (%u/3)", (unsigned)cheapRetries);
        WiFi.disconnect();
        WiFi.reconnect();
    } else {
        if (everConnected) tlog("[wifi] link down - rescanning all SSIDs");
        cheapRetries = 0;
        wifiConnect();                     // blocks this task, nobody else
        lastRetry = millis();
    }
}

// ---- MQTT ---------------------------------------------------------------------
#if ENABLE_MQTT

static const char *mqttStateText(int8_t state) {
    switch (state) {
        case MQTT_CONNECTION_TIMEOUT:     return "connection timeout";
        case MQTT_CONNECTION_LOST:        return "connection lost";
        case MQTT_CONNECT_FAILED:         return "connect failed";
        case MQTT_DISCONNECTED:           return "disconnected";
        case MQTT_CONNECTED:              return "connected";
        case MQTT_CONNECT_BAD_PROTOCOL:   return "bad protocol";
        case MQTT_CONNECT_BAD_CLIENT_ID:  return "bad client id";
        case MQTT_CONNECT_UNAVAILABLE:    return "broker unavailable";
        case MQTT_CONNECT_BAD_CREDENTIALS:return "bad credentials";
        case MQTT_CONNECT_UNAUTHORIZED:   return "unauthorized";
        default:                          return "unknown";
    }
}

// Inbound, in the task. The OTA trigger is handled here because the download
// belongs in this task; everything else is copied to the loop.
static void onMqttMessage(char *inTopic, byte *payload, unsigned int length) {
    String t(inTopic);
    const String prefix = String(MQTT_BASE_TOPIC) + "/";
    if (!t.startsWith(prefix)) return;
    const String leaf = t.substring(prefix.length());
    String v; v.reserve(length);
    for (unsigned int i = 0; i < length; i++) v += (char)payload[i];

    if (leaf == "ota") {
        v.toLowerCase();
        if (v == "update") sOtaRequested = true;
        return;
    }
    InMsg m;
    strncpy(m.leaf, leaf.c_str(), sizeof(m.leaf) - 1);       m.leaf[sizeof(m.leaf) - 1] = '\0';
    strncpy(m.payload, v.c_str(), sizeof(m.payload) - 1);    m.payload[sizeof(m.payload) - 1] = '\0';
    if (!sInQ || xQueueSend(sInQ, &m, 0) != pdTRUE) tlogf("[net] inbound queue full, dropped %s", m.leaf);
}

static void queueSynthetic(const char *leaf) {
    InMsg m;
    strncpy(m.leaf, leaf, sizeof(m.leaf) - 1); m.leaf[sizeof(m.leaf) - 1] = '\0';
    m.payload[0] = '\0';
    if (sInQ) xQueueSend(sInQ, &m, 0);
}

// Backoff stamped at the END of the attempt, fifteen seconds. Stamped before
// it, a connect that times out had already used up the wait when it returned,
// and the next pass started the next one at once.
static void mqttConnectMaybe() {
    if (sMqtt.connected()) return;
    if (sMqttLastTry != 0 && millis() - sMqttLastTry < MQTT_RETRY_MS) return;
    const String willTopic = baseTopic("status");
    Serial.printf("[mqtt] connecting to %s:%d as clientId=%s ...\n", MQTT_BROKER, MQTT_PORT, clientId());
    if (sMqtt.connect(clientId(), MQTT_USERNAME, MQTT_PASSWORD, willTopic.c_str(), 0, true, "offline")) {
        sMqtt.publish(willTopic.c_str(), "online", true);
        sMqtt.subscribe(baseTopic("ota").c_str());
        sMqtt.subscribe(baseTopic("probe/en/+").c_str());
        sMqtt.subscribe(baseTopic("sleep/en").c_str());
        // Announce the running firmware ONCE PER BOOT, not once per reconnect:
        // on 2026-09-04 a reconnect storm read as a reboot loop and several
        // hours went into a crash that had never happened. The topic is
        // retained, so once is enough. A reboot shows in meta: "reset"
        // changes and "heap_min" jumps back up.
        static bool announced = false;
        if (!announced) {
            announced = true;
            sMqtt.publish(baseTopic("ota/status").c_str(), "Running " FW_VERSION, true);
        }
        sMqttUp = true;
        ringFlush();                       // what happened while we were away
        tlog("[mqtt] connected");
        queueSynthetic("$connected");      // the loop republishes what it owns
    } else {
        tlogf("[mqtt] connect failed: state=%d (%s)", sMqtt.state(), mqttStateText(sMqtt.state()));
    }
    sMqttLastTry = millis();               // after the attempt, not before
}

// ---- HTTP OTA, in the task ----------------------------------------------------
//
// The dead man's switch: on 2026-09-04 httpUpdate.update() reached 100 % and
// simply stopped -- no reboot, no error. esp_timer callbacks run from their own
// task, so this fires even when the download is wedged. Refreshed on every
// progress report; if nothing moves for OTA_STALL_MS the chip restarts, which is
// safe because the boot partition is only switched after the image is written
// and validated. 300 s: the silent phase between "100 %" and the reboot took
// 160 s on the bike once, and a guard that fires on a healthy update is worse
// than none.
#define OTA_STALL_MS 300000
static esp_timer_handle_t sOtaGuard = nullptr;
static void otaGuardFired(void *) { esp_restart(); }
static void otaGuardArm() {
    if (!sOtaGuard) {
        const esp_timer_create_args_t args = {
            .callback = &otaGuardFired, .arg = nullptr, .dispatch_method = ESP_TIMER_TASK,
            .name = "ota_guard", .skip_unhandled_events = false
        };
        if (esp_timer_create(&args, &sOtaGuard) != ESP_OK) return;
    }
    esp_timer_stop(sOtaGuard);
    esp_timer_start_once(sOtaGuard, (uint64_t)OTA_STALL_MS * 1000);
}
static void otaGuardStop() { if (sOtaGuard) esp_timer_stop(sOtaGuard); }

static void publishOtaStatus(const char *msg) {
    if (sMqtt.connected()) sMqtt.publish(baseTopic("ota/status").c_str(), msg, true);
    tlogf("[ota] status: %s", msg);
}

static void runHttpOta() {
    sBusy = true;
    tlog("[ota] update trigger received via MQTT, starting HTTP download...");
    publishOtaStatus("Starting download...");
    httpUpdate.onProgress([](int cur, int total) {
        static int lastPct = -1;
        const int pct = (total > 0) ? (int)((int64_t)cur * 100 / total) : 0;
        otaGuardArm();                     // progress means it is still alive
        if (pct != lastPct && (pct % 10 == 0)) {
            lastPct = pct;
            char m[40];
            snprintf(m, sizeof(m), "Downloading %d%%", pct);
            publishOtaStatus(m);
        }
    });
    httpUpdate.rebootOnUpdate(true);
    otaGuardArm();
    WiFiClient client;
    client.setTimeout(15);                 // seconds, per read
    const t_httpUpdate_return ret = httpUpdate.update(client, OTA_FIRMWARE_URL);
    otaGuardStop();
    switch (ret) {
        case HTTP_UPDATE_FAILED: {
            char m[96];
            snprintf(m, sizeof(m), "FAILED (%d): %s", httpUpdate.getLastError(), httpUpdate.getLastErrorString().c_str());
            publishOtaStatus(m);
            break;
        }
        case HTTP_UPDATE_NO_UPDATES: publishOtaStatus("No update available"); break;
        case HTTP_UPDATE_OK:         publishOtaStatus("OK - rebooting"); break;   // rarely reached
    }
    sBusy = false;
}

// Write the out-queue to the broker; with no broker, publishes are dropped and
// log lines go to the ring.
static void drainOut() {
    OutMsg *m;
    while (xQueueReceive(sOutQ, &m, 0) == pdTRUE) {
        if (m->kind == OUT_LOG) {
            tlog(m->payload);
        } else if (sMqtt.connected()) {
            sSending = true;
            sMqtt.publish(m->topic, (const uint8_t *)m->payload, m->len, m->retained);
            sSending = false;
        }
        free(m);
    }
}
#else
static void drainOut() {
    OutMsg *m;
    while (xQueueReceive(sOutQ, &m, 0) == pdTRUE) {
        if (m->kind == OUT_LOG) tlog(m->payload);
        free(m);
    }
}
#endif  // ENABLE_MQTT

// ---- the task -----------------------------------------------------------------

static void netTask(void *) {
    for (;;) {
        serviceWifi();
#if ENABLE_MQTT
        if (sWifiUp) {
            if (!sMqtt.connected()) {
                if (sMqttUp) { sMqttUp = false; tlogf("[mqtt] lost: state=%d (%s)", sMqtt.state(), mqttStateText(sMqtt.state())); }
                mqttConnectMaybe();
            } else {
                sMqtt.loop();
            }
            if (sOtaRequested) { sOtaRequested = false; runHttpOta(); }
        }
#endif
        drainOut();
        if (sWifiUp && sOtaReady) ArduinoOTA.handle();
        vTaskDelay(pdMS_TO_TICKS(5));
    }
}

void netBegin(NetInboundFn onMessage) {
    sOnMessage = onMessage;
    sOutQ = xQueueCreate(OUT_DEPTH, sizeof(OutMsg *));
    sInQ  = xQueueCreate(IN_DEPTH, sizeof(InMsg));
    // Core 0, where the WiFi and BT stacks already live; loop() has core 1.
    // 12 kB of stack: the TLS handshake is the deepest thing that runs here.
    xTaskCreatePinnedToCore(netTask, "net", 12288, nullptr, 1, nullptr, 0);
}

#endif  // ENABLE_WIFI

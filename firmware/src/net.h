/*
 * net.h -- the network half of the firmware, behind a queue.
 *
 * Everything that can block for seconds lives here and runs in its own
 * FreeRTOS task on core 0: WiFi association and the scan that picks the
 * strongest known network, SNTP, the TLS connect to the broker, MQTT
 * keepalive, ArduinoOTA and the HTTP firmware download. loop() on core 1 --
 * the CAN bus and the phone -- never waits for any of it.
 *
 * Why. Until 2026-09-27 mqtt.connect() ran in loop(). With the phone's hotspot
 * up but its cellular link gone, every attempt froze the loop for the 5 s TCP
 * connect timeout (measured on the bike: loop_max_ms 5019, ble_gap_max_ms
 * 6656), and the app -- which calls a stream older than 2.5 s dead -- fell
 * out "on and off at regular intervals". Three earlier rounds each shortened
 * that stall (5 s connect timeout, backoff stamped after the attempt, BLE
 * serviced inside the WiFi wait); none removed it, because the blocking call
 * was still in the loop that services a real-time bus.
 *
 * The contract:
 *   - loop() publishes with netPublish(): the JSON is copied into a queue and
 *     the call returns at once. The task writes it to the broker.
 *   - inbound topics arrive through netPoll(), called from loop(), so the
 *     handlers keep running in the context they were written for.
 *   - the only shared state is a few flags read through the functions below.
 *   - the MQTT client object exists only in net.cpp. Nothing else may touch it.
 *
 * With ENABLE_WIFI 0 this header compiles to nothing: every call is an empty
 * inline, the way ble.h does it, so main.cpp has no #if of its own for the
 * network. ENABLE_MQTT implies ENABLE_WIFI; ENABLE_WIFI alone keeps ArduinoOTA
 * (upload over the LAN) without a broker.
 */
#pragma once
#include <Arduino.h>
#include "config.h"

#ifndef ENABLE_MQTT
#define ENABLE_MQTT 0
#endif
#ifndef ENABLE_WIFI
#define ENABLE_WIFI ENABLE_MQTT
#endif
#if ENABLE_MQTT && !ENABLE_WIFI
#error "ENABLE_MQTT needs ENABLE_WIFI"
#endif

// Called from loop() via netPoll() for every inbound message: leaf is the topic
// below MQTT_BASE_TOPIC ("sleep/en", "probe/en/claims"). The task also delivers
// one synthetic leaf, "$connected", once per broker connection, so the loop can
// republish the retained state it owns (probe flags, sleep status).
typedef void (*NetInboundFn)(const char *leaf, const char *payload);

// Largest single payload the queue accepts. The MQTT client buffer is 3072 for
// reassembled TP messages; state JSON is 1536.
#define NET_PAYLOAD_MAX 3072

#if ENABLE_WIFI

void     netBegin(NetInboundFn onMessage);   // creates the queues and the task
bool     netWifiConnected();
bool     netMqttConnected();
bool     netBusy();                          // an OTA (HTTP or MQTT) is running in the task
bool     netPublish(const char *leaf, const char *payload, size_t len, bool retained);
bool     netPublishTopic(const char *fullTopic, const char *payload, size_t len, bool retained);
void     netLog(const char *line);           // <base>/debug, or the ring while offline
void     netPoll();                          // dispatch inbound messages, from loop()
bool     netFlush(uint32_t timeoutMs);       // wait until the out-queue is on the wire
uint32_t netDropped();                       // publishes lost to a full queue since boot
String   netIp();
int      netRssi();

#else

static inline void     netBegin(NetInboundFn) {}
static inline bool     netWifiConnected() { return false; }
static inline bool     netMqttConnected() { return false; }
static inline bool     netBusy() { return false; }
static inline bool     netPublish(const char *, const char *, size_t, bool) { return false; }
static inline bool     netPublishTopic(const char *, const char *, size_t, bool) { return false; }
static inline void     netLog(const char *) {}
static inline void     netPoll() {}
static inline bool     netFlush(uint32_t) { return true; }
static inline uint32_t netDropped() { return 0; }
static inline String   netIp() { return String(); }
static inline int      netRssi() { return 0; }

#endif

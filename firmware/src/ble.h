/*
 * BLE GATT server — local phone link.
 *
 * Serves the SAME decoded state that goes to openHAB over MQTT, but over a
 * short-range radio that does not need WiFi. That is the whole point: MQTT
 * works when the bike is home and on WiFi; BLE works wherever the rider's
 * phone is, which is where a live dashboard is actually wanted.
 *
 * Like can_hal_*.cpp, the implementation wraps its whole body in `#if ENABLE_BLE`
 * so a build with BLE off links no NimBLE at all — no flash, no RAM, no radio.
 *
 * PRODUCTION ONLY. DISCOVERY mode has no decoded state to serve (it is a raw
 * frame firehose for reverse engineering), so main.cpp raises a #error on that
 * combination rather than silently advertising an empty service.
 *
 * Security: connections require passkey pairing with MITM protection, and the
 * characteristics are readable only on an encrypted+authenticated link. The VIN
 * is omitted from the payload regardless — see buildStateJson() in vehstate.h.
 */
#pragma once

// Arduino.h FIRST: config.h declares MQTT_ROOT_CA with PROGMEM, which is only
// defined once pgmspace.h has been pulled in. main.cpp includes Arduino.h before
// config.h and so never noticed; ble.cpp includes this header first, so the
// ordering has to be guaranteed here rather than at each call site.
#include <Arduino.h>

#include "config.h"
#include "vehstate.h"

// Safe fallback so the firmware still builds against a (git-ignored) config.h
// that predates the BLE block — same rationale as the OTA fallbacks in main.cpp.
#ifndef ENABLE_BLE
#define ENABLE_BLE 0
#endif

// Enforced here as well as in main.cpp so the rule fires whichever translation
// unit the compiler reaches first. FIRMWARE_MODE 1 is MODE_PRODUCTION (the
// constants themselves live in main.cpp, which this header must not depend on).
#if ENABLE_BLE && defined(FIRMWARE_MODE) && FIRMWARE_MODE != 1
#error "ENABLE_BLE requires FIRMWARE_MODE 1 (PRODUCTION). DISCOVERY is a raw frame firehose with no decoded state to serve."
#endif

#if ENABLE_BLE

// Bring up the GATT server and start advertising. Call once from setup().
void bleSetup();

// Feed the current state. Cheap to call every loop(): it rate-limits itself to
// BLE_FAST_MS for the packed binary characteristic and BLE_JSON_MS for the JSON
// one, and does nothing at all while no phone is connected.
//
// Deliberately does NOT consume the shared `stateDirty` flag: that flag is
// cleared by whoever reads it first, so sharing it would mean MQTT and BLE
// stealing each other's updates. BLE is a live display — resending an unchanged
// 8-byte packet costs nothing and keeps the two transports independent.
void bleUpdate(const VehState &st);

// True while a phone is connected AND has completed pairing.
bool bleClientConnected();

#else   // ---- compiled out -------------------------------------------------

static inline void bleSetup() {}
static inline void bleUpdate(const VehState &) {}
static inline bool bleClientConnected() { return false; }

#endif  // ENABLE_BLE

/*
 * BLE GATT server implementation — see ble.h for the rationale.
 *
 * Two characteristics, because the signals have very different natures:
 *
 *   "fast"  — 8 packed bytes (rpm, speed, throttle, gear, switch flags) pushed
 *             at BLE_FAST_MS. These come off the bus tens of times a second and
 *             are what makes a phone gauge look alive rather than steppy.
 *   "state" — the full JSON at BLE_JSON_MS. Fuel, odometer, tyre pressures and
 *             temperatures update once or twice a second on the bus at best;
 *             sending them at gauge rate would just repeat the same number and
 *             burn the phone's battery.
 *
 * The split also keeps the fast path inside a single BLE notification with room
 * to spare, so it never depends on MTU negotiation succeeding.
 */
#include "ble.h"

#if ENABLE_BLE

#include <Arduino.h>
#include <NimBLEDevice.h>

#include "service.h"

// ---- Fallbacks so a config.h predating the BLE block still builds -----------
#ifndef BLE_DEVICE_NAME
#define BLE_DEVICE_NAME "Springfield"
#endif
#ifndef BLE_PASSKEY
#define BLE_PASSKEY 123456
#endif
#ifndef BLE_FAST_MS
#define BLE_FAST_MS 100
#endif
#ifndef BLE_JSON_MS
#define BLE_JSON_MS 1000
#endif
#ifndef BLE_MTU
#define BLE_MTU 517
#endif

// Custom 128-bit UUIDs. Randomly generated for this project — they are not any
// adopted SIG service, so nothing else on the phone will try to interpret them.
#define BLE_SVC_UUID   "5f6d0000-9b2a-4c31-8f0e-2a7c1d3e4b50"
#define BLE_CHR_FAST   "5f6d0001-9b2a-4c31-8f0e-2a7c1d3e4b50"
#define BLE_CHR_STATE  "5f6d0002-9b2a-4c31-8f0e-2a7c1d3e4b50"
#define BLE_CHR_SVC    "5f6d0003-9b2a-4c31-8f0e-2a7c1d3e4b50"

// Sentinels for "the bus has not told us yet". The app must treat these as
// no-data rather than as a real zero — a real 0 rpm and an unknown rpm mean
// very different things on a dashboard.
#define FAST_U16_UNKNOWN 0xFFFF
#define FAST_U8_UNKNOWN  0xFF

static NimBLEServer         *gServer  = nullptr;
static NimBLECharacteristic *gChFast  = nullptr;
static NimBLECharacteristic *gChState = nullptr;
static NimBLECharacteristic *gChSvc   = nullptr;

static bool     gPaired    = false;   // connected AND encrypted
static uint32_t gLastFast  = 0;
static uint32_t gLastJson  = 0;

// ---------------------------------------------------------------------------
// Server callbacks
// ---------------------------------------------------------------------------
class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer *server, NimBLEConnInfo &info) override {
        Serial.println("[ble] client connected - awaiting pairing");
        // Ask for a 15-30 ms connection interval (units of 1.25 ms). Android is
        // free to refuse, but when it agrees the fast characteristic can push
        // ~20 notifications/sec, which is what the gauge rate needs.
        server->updateConnParams(info.getConnHandle(), 12, 24, 0, 400);
    }

    void onDisconnect(NimBLEServer *server, NimBLEConnInfo &info, int reason) override {
        gPaired = false;
        Serial.printf("[ble] client disconnected (reason %d) - advertising again\n", reason);
        NimBLEDevice::startAdvertising();
    }

    void onAuthenticationComplete(NimBLEConnInfo &info) override {
        if (!info.isEncrypted()) {
            // Wrong passkey, or a peer that refused to pair. Drop it rather than
            // leaving an unencrypted link sitting on the server: the whole point
            // of the passkey is that an unpaired phone gets nothing.
            Serial.println("[ble] pairing FAILED - dropping link");
            NimBLEDevice::getServer()->disconnect(info.getConnHandle());
            return;
        }
        gPaired = true;
        Serial.println("[ble] paired, link encrypted");
    }
};

// ---------------------------------------------------------------------------
// Service characteristic
// ---------------------------------------------------------------------------
//
// Four bytes, little-endian int32: the odometer reading at the last service.
// The only writable thing on this server, and the only reason to have one — the
// figure cannot be measured, only remembered, so somebody has to be able to
// tell the bike it has been serviced.
//
// Binary rather than text on purpose. A decimal string would need parsing,
// length checks and a decision about what "12 000" or "12.0" means; four bytes
// have exactly one interpretation and cannot arrive half-valid.
//
// Reads are served from NVS so the app can confirm what actually landed rather
// than trusting its own write — which matters because the bike is allowed to
// refuse one.
class ServiceCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *chr, NimBLEConnInfo &info) override {
        // Belt and braces: the descriptor already demands an authenticated
        // link, but this is the one characteristic where a stray write has a
        // lasting effect, so it is checked here too.
        if (!info.isEncrypted() || !info.isAuthenticated()) {
            Serial.println("[ble] service write on an unauthenticated link - ignored");
            return;
        }

        // NimBLEAttValue rather than std::string: 2.x returns its own type and
        // leaning on the implicit conversion is how a build breaks on the next
        // library bump for no reason anyone remembers.
        const NimBLEAttValue v = chr->getValue();
        if (v.size() != 4) {
            Serial.printf("[ble] service write of %u bytes - expected 4\n",
                          (unsigned)v.size());
            return;
        }

        const uint8_t *b = v.data();
        const int32_t km = (int32_t)((uint32_t)b[0] |
                                     ((uint32_t)b[1] << 8) |
                                     ((uint32_t)b[2] << 16) |
                                     ((uint32_t)b[3] << 24));
        serviceSetLastKm(km);

        // Reflect what is actually stored, accepted or not. A phone that wrote
        // a figure the bike refused should see the refusal, not its own number
        // read back.
        publishServiceValue();
    }

    void onRead(NimBLECharacteristic *chr, NimBLEConnInfo &info) override {
        publishServiceValue();
    }

public:
    static void publishServiceValue() {
        if (!gChSvc) return;
        const int32_t km = serviceLastKm();
        uint8_t out[4] = {
            (uint8_t)(km & 0xFF), (uint8_t)((km >> 8) & 0xFF),
            (uint8_t)((km >> 16) & 0xFF), (uint8_t)((km >> 24) & 0xFF)
        };
        gChSvc->setValue(out, sizeof(out));
    }
};

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------
void bleSetup() {
    NimBLEDevice::init(BLE_DEVICE_NAME);

    // Raise OUR ceiling before anything negotiates. NimBLE caps the ATT MTU at
    // 256 by default, so a client asking for 517 still lands on 256 — a 253-byte
    // payload — and the state JSON is silently truncated mid-object. Found on the
    // bench 2026-09-02: the Android app reported
    //     Unparseable state JSON (253 B)
    // which is exactly 256 - 3, the giveaway that the server, not the phone, was
    // the one holding the line down.
    // Transmit power is left at the stack default, deliberately.
    //
    // It was raised to the radio's maximum on 2026-09-04 on the reasoning that
    // the phone is in a pocket and there is no battery to save. Both halves
    // were wrong for this device: it runs off a motorcycle battery that is not
    // always being charged, and higher transmit power means higher peak current
    // exactly when the radio is working hardest. On the afternoon it was added,
    // the bike sat with the ignition on and the heated grips at full for over an
    // hour with the engine off; the battery fell to 12.3 V, and BLE was dropping
    // within seconds. Raising the peaks into a sagging supply is the opposite of
    // the fix, and the change was speculative to begin with -- it was not made
    // to solve anything.
    NimBLEDevice::setMTU(BLE_MTU);

    // Static passkey with MITM protection: the phone must type the number to
    // bond. Bonding is on, so it is a one-time step per phone.
    NimBLEDevice::setSecurityAuth(true /*bonding*/, true /*MITM*/, true /*secure connections*/);
    NimBLEDevice::setSecurityPasskey(BLE_PASSKEY);
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);

    gServer = NimBLEDevice::createServer();
    gServer->setCallbacks(new ServerCallbacks());

    NimBLEService *svc = gServer->createService(BLE_SVC_UUID);

    // READ_ENC|READ_AUTHEN means the stack refuses the read unless the link is
    // both encrypted and authenticated — i.e. paired with the passkey.
    const uint32_t props = NIMBLE_PROPERTY::READ |
                           NIMBLE_PROPERTY::NOTIFY |
                           NIMBLE_PROPERTY::READ_ENC |
                           NIMBLE_PROPERTY::READ_AUTHEN;

    gChFast  = svc->createCharacteristic(BLE_CHR_FAST,  props);
    gChState = svc->createCharacteristic(BLE_CHR_STATE, props);

    // The one writable characteristic. WRITE_ENC|WRITE_AUTHEN mirrors the read
    // side: an unpaired phone cannot set the bike's service history any more
    // than it can read the bike's speed.
    gChSvc = svc->createCharacteristic(
        BLE_CHR_SVC,
        NIMBLE_PROPERTY::READ   | NIMBLE_PROPERTY::WRITE |
        NIMBLE_PROPERTY::READ_ENC  | NIMBLE_PROPERTY::READ_AUTHEN |
        NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::WRITE_AUTHEN);
    gChSvc->setCallbacks(new ServiceCallbacks());
    ServiceCallbacks::publishServiceValue();

    // NimBLE 2.x starts services with the server — NimBLEService::start() is a
    // deprecated no-op there, so start the server instead.
    gServer->start();

    NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
    adv->setName(BLE_DEVICE_NAME);
    adv->addServiceUUID(BLE_SVC_UUID);
    adv->enableScanResponse(true);
    adv->start();

    Serial.printf("[ble] advertising as \"%s\" (passkey pairing required)\n", BLE_DEVICE_NAME);
}

bool bleClientConnected() {
    return gPaired;
}

// ---------------------------------------------------------------------------
// Notify
// ---------------------------------------------------------------------------
//
// Packed fast frame, little-endian, 8 bytes:
//   [0..1] uint16  rpm                     0xFFFF = unknown
//   [2..3] uint16  speed x10 (km/h)        0xFFFF = unknown
//   [4]    uint8   throttle (%)            0xFF   = unknown
//   [5]    char    gear ('N','1'..'6','-') 0      = unknown
//   [6]    uint8   flags   bit0 cruise SET/DEC, 1 brakeRear, 2 cruise engaged,
//                          bit3 indLeft, 4 indRight, 5 cruise RES/ACC,
//                          bit6 cruiseEnable, 7 hazard
//   [7]    uint8   flagsValid — same bit positions, 1 = the bus has told us
//
// flagsValid exists because every switch is tri-state in VehState (-1 unknown).
// Without it the app could not tell "brake released" from "never seen a brake
// message", and would show a confident RELEASED on a bike that never reported.
static void buildFastPacket(const VehState &st, uint8_t *out) {
    uint16_t rpm = isnan(st.rpm)   ? FAST_U16_UNKNOWN : (uint16_t)lroundf(st.rpm);
    uint16_t spd = isnan(st.speed) ? FAST_U16_UNKNOWN : (uint16_t)lroundf(st.speed * 10.0f);
    uint8_t  thr = isnan(st.throttle) ? FAST_U8_UNKNOWN : (uint8_t)lroundf(st.throttle);

    out[0] = (uint8_t)(rpm & 0xFF);
    out[1] = (uint8_t)(rpm >> 8);
    out[2] = (uint8_t)(spd & 0xFF);
    out[3] = (uint8_t)(spd >> 8);
    out[4] = thr;
    out[5] = (uint8_t)st.gear[0];          // 0 when never decoded

    // Bits 0 and 5 held brakeFront and horn until they were retired earlier
    // today, and were left pinned invalid because the app was not being rebuilt
    // in that change -- putting a new signal in the old brakeFront slot would
    // have lit a front-brake tell-tale on an app that had never heard of it.
    //
    // They now carry the cruise rocker, because the app IS being rebuilt with
    // this. The two must ship together; an old build would read SET as a front
    // brake and RESUME as a horn.
    //
    // These belong in the fast packet rather than only in the JSON state: a
    // press lasts about a second and the state publishes at 1 Hz, so a tap can
    // fall between two publishes and never be seen at all. That is the same
    // problem openHAB solved with a latch rule, and 10 Hz solves it properly.
    const int8_t sw = st.cruiseSw;
    // Bit 2 carries the DERIVED hold. st.cruise was the measured SPN 595 and is
    // never assigned any more -- that signal is not on this bus, proved on the
    // road 2026-09-05 -- so leaving the bit pointed at it would have made the
    // cruise lamp permanently "never reported".
    const int8_t flags[8] = { sw < 0 ? (int8_t)-1 : (int8_t)(sw == 1),
                              st.brakeRear, st.cruiseHold,
                              st.indLeft, st.indRight,
                              sw < 0 ? (int8_t)-1 : (int8_t)(sw == 2),
                              st.cruiseEnable, st.hazard };
    uint8_t bits = 0, valid = 0;
    for (uint8_t i = 0; i < 8; i++) {
        if (flags[i] < 0) continue;        // unknown -> leave both bits clear
        valid |= (uint8_t)(1u << i);
        if (flags[i]) bits |= (uint8_t)(1u << i);
    }
    out[6] = bits;
    out[7] = valid;
}

void bleUpdate(const VehState &st) {
    if (!gPaired || !gChFast || !gChState) return;

    const uint32_t now = millis();

    if (now - gLastFast >= BLE_FAST_MS) {
        gLastFast = now;
        uint8_t pkt[8];
        buildFastPacket(st, pkt);
        gChFast->setValue(pkt, sizeof(pkt));
        gChFast->notify();
    }

    if (now - gLastJson >= BLE_JSON_MS) {
        gLastJson = now;
        // Same serialiser MQTT uses, minus the VIN — one decode path, so the
        // app and openHAB can never disagree about what a value means.
        char payload[900];
        size_t n = buildStateJson(payload, sizeof(payload), false /*includeVin*/);
        // A GATT notification carries at most ATT_MTU-3 bytes and the stack
        // truncates silently past that, which would hand the app a JSON object
        // cut off mid-string -- unparseable, and with no error anywhere to say
        // why. The payload is trimmed to stay well inside 514 at MTU 517 (VIN
        // and dm1Raw are left out; see buildStateJson), but it grows with tyre
        // readings and the length of an active fault list, so say so out loud
        // if it ever gets close rather than discovering it on a ride.
        if (n > 500) {
            Serial.printf("[ble] WARNING state JSON %u bytes, near the %d-byte "
                          "notification limit\n", (unsigned)n, 514);
        }
        if (n > 0) {
            gChState->setValue((const uint8_t *)payload, n);
            gChState->notify();
        }
    }
}

#endif  // ENABLE_BLE

/*
 * Decoded vehicle state — shared declaration.
 *
 * The struct used to live inside main.cpp, which was fine while MQTT was the
 * only consumer. It moved here the moment a SECOND transport (BLE) needed the
 * same decoded values: keeping one struct and one serialiser is what stops the
 * two paths from drifting apart and re-implementing the J1939 scaling twice.
 *
 * Everything is filled by decodeState() in main.cpp. "Unavailable" is NAN for
 * floats, an empty string for text, and -1 for the tri-state int8 flags.
 */
#pragma once

#include <stdint.h>
#include <stddef.h>
#include <math.h>

struct VehState {
    float rpm, throttle, coolant, speed, fuel, odometer, trip, fuelEcon;
    float battery, ambient, tyreFront, tyreRear, tyreFrontTemp, tyreRearTemp;
    float gripTempL, gripTempR;   // heated grip temperature, left and right
    float speedFront;    // front wheel, PGN 65215 (ABS module) -- true road speed
    float fuelRate;      // L/h, PGN 65266 bytes 1-2
    float fuelEconInst;  // l/100km right now, PGN 65266 bytes 3-4
    char  wheels[12];    // "OK", "FRONT LOST", "REAR LOST"; empty = not judged
    char  gear[3];       // "N","1".."6","-"
    char  headlight[6];  // "High","Low","Off"
    // Security / key fob, PGN 65386 SA 39 byte 0 bits 6-7. Decoded 2026-09-05
    // from a controlled pair: fob present resolves in a second, fob left
    // indoors sits SEARCHING for twenty seconds and then reports NOT FOUND.
    char  security[10];  // "OK","SEARCHING","NOT FOUND"
    char  vin[18];       // 17-char VIN (PGN 65242 SOFT, TP/BAM)
    char  swid[112];     // full software/ID record ('|'-joined)
    char  dm1[112];      // decoded active DTC summary (PGN 65226)
    // The same summary as numbers, for BLE, where 514 bytes is a hard ceiling
    // and the readable form does not fit. "520250:8:2,904:12:1|5"
    char  dm1c[64];
    char  dm1Raw[20];    // DM1 first-frame raw hex
    // brakeFront and horn were removed 2026-09-05. Neither was ever assigned
    // after their decodes were withdrawn, so both shipped as permanently absent
    // keys; the horn is now proven not to exist on this bus at all.
    // cruiseHold is DERIVED, not measured -- SPN 595 is not on this bus. See
    // the cruise section in main.cpp. clutch is SPN 598, free from the byte the
    // brake already comes from.
    // `cruise` (the measured SPN 595) was removed 2026-09-05. It is not on this
    // bus -- proved on the road with the cruise holding at 94 km/h and the byte
    // unmoved -- so the field could never carry a value. cruiseHold replaces it
    // and is DERIVED; see the cruise section in main.cpp.
    int8_t brakeRear, cruiseEnable, cruiseSw, cruiseHold, clutch, hazard,
           indLeft, indRight;                                   // -1=unknown
    int8_t grips;        // heated grips, 0=off, 1..10; -1=unknown
    int16_t lean;        // PGN 2304 byte 0 raw, 127 upright; -1 unknown
    char  stand[9];      // "UPRIGHT", "STAND", "DOWN" -- only while stationary
};

/*
 * Serialise the decoded state to JSON (implemented in main.cpp).
 *
 * includeVin=false omits the VIN and the software-ID record. That is what the
 * BLE link passes: the VIN is a permanent vehicle identifier, and while pairing
 * keeps the link encrypted, there is no reason to put the stelnummer on a radio
 * that advertises in a car park when no consumer needs it. openHAB already has
 * it over MQTT.
 *
 * Returns the number of bytes written (excluding the NUL), as serializeJson().
 */
// dm1Max caps the fault-summary string; 0 means no cap. It exists because a
// GATT notification cannot fragment, and the fault list is the one field whose
// length is unbounded -- see the trimming in ble.cpp.
size_t buildStateJson(char *out, size_t cap, bool includeVin, size_t dm1Max = 0,
                      bool includeFw = true);

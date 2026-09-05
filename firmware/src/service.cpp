#include "service.h"

#include <Arduino.h>
#include <Preferences.h>
#include <math.h>

#include "vehstate.h"

// The decoded state, so a write can be checked against the real odometer.
extern VehState st;

static Preferences gPrefs;
static int32_t     gLastKm = SERVICE_KM_UNSET;

/** Nothing on this bus will ever legitimately report more than this. */
static const int32_t SERVICE_KM_MAX = 999999;

/**
 * How far ahead of the odometer a recorded service may sit.
 *
 * Not zero: the app reads the odometer from a JSON frame up to a second old and
 * the bike may have moved a little between reading and writing. A few
 * kilometres absorbs that without admitting a figure that is meaningfully in
 * the future.
 */
static const int32_t SERVICE_KM_SLACK = 5;

void serviceBegin() {
    // Read-write, own namespace. Opening read-only first would fail on a brand
    // new device that has never written the namespace.
    if (!gPrefs.begin("svc", false)) {
        Serial.println("[svc] NVS open FAILED - service memory unavailable");
        return;
    }
    gLastKm = gPrefs.getInt("lastKm", SERVICE_KM_UNSET);
    if (gLastKm == SERVICE_KM_UNSET) Serial.println("[svc] no service recorded");
    else Serial.printf("[svc] last service at %ld km\n", (long)gLastKm);
}

int32_t serviceLastKm() {
    return gLastKm;
}

bool serviceSetLastKm(int32_t km) {
    if (km < 0 || km > SERVICE_KM_MAX) {
        Serial.printf("[svc] REJECTED %ld km - out of range\n", (long)km);
        return false;
    }

    // Only checked when the odometer is actually known. Refusing the write
    // because the bus has not yet reported would make the feature fail in the
    // first seconds after power-on, which is exactly when a rider standing at
    // the bike would use it.
    if (!isnan(st.odometer)) {
        const int32_t odo = (int32_t)lroundf(st.odometer);
        if (km > odo + SERVICE_KM_SLACK) {
            Serial.printf("[svc] REJECTED %ld km - ahead of odometer %ld\n",
                          (long)km, (long)odo);
            return false;
        }
    }

    gLastKm = km;
    gPrefs.putInt("lastKm", km);
    Serial.printf("[svc] service recorded at %ld km\n", (long)km);
    return true;
}

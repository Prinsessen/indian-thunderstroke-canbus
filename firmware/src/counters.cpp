#include "counters.h"
#include <Arduino.h>
#include <Preferences.h>

static Preferences gPrefs;
static bool     gOpen = false;
static uint16_t gVal[CNT__COUNT] = {0, 0, 0};

// Short, stable keys. NVS keys are capped at 15 characters and renaming one
// loses the tally behind it, so these are chosen once and left alone.
static const char *KEY[CNT__COUNT] = { "wheelF", "wheelR", "gearGl" };

void countersBegin() {
    gOpen = gPrefs.begin("canbuscnt", false);
    if (!gOpen) {
        Serial.println("[cnt] NVS open FAILED - counters will not survive a reboot");
        return;
    }
    for (int i = 0; i < CNT__COUNT; i++) gVal[i] = gPrefs.getUShort(KEY[i], 0);
    Serial.printf("[cnt] loaded  wheelF=%u wheelR=%u gearGlitch=%u\n",
                  gVal[0], gVal[1], gVal[2]);
}

uint16_t counterGet(CounterId id) {
    return (id < CNT__COUNT) ? gVal[id] : 0;
}

uint16_t counterBump(CounterId id) {
    if (id >= CNT__COUNT) return 0;
    if (gVal[id] >= 65000) return gVal[id];      // stop rather than wrap to zero
    gVal[id]++;

    // Persist, but not faster than once a second per counter. These events are
    // rare by nature; a sensor failing hard could chatter, and flash wears out.
    // Losing the last second of a tally to a power cut is nothing against
    // burning the partition that holds all of it.
    static uint32_t lastWrite[CNT__COUNT] = {0, 0, 0};
    const uint32_t now = millis();
    if (gOpen && (lastWrite[id] == 0 || now - lastWrite[id] > 1000)) {
        lastWrite[id] = now;
        gPrefs.putUShort(KEY[id], gVal[id]);
    }
    return gVal[id];
}

#include "probeflags.h"

#include <Preferences.h>
#include <stdio.h>
#include <string.h>

static Preferences gPrefs;
static bool gOpen = false;
static bool gOn[PROBE__COUNT];

// Short keys: the NVS key length limit is 15 characters, and these are written
// once in a blue moon, so there is nothing to gain from spelling them out.
static const char *KEY[PROBE__COUNT] = { "scan", "cruise", "thr", "claim" };
static const char *NAME[PROBE__COUNT] = { "scan", "cruise", "throttle", "claims" };

// Defaults on a bike that has never been told otherwise. Everything cheap is on;
// the 2 Hz throttle probe is off, because leaving it running is the whole
// problem this file exists to solve.
static const bool DEFAULT_ON[PROBE__COUNT] = { true, true, false, true };

void probeFlagsBegin() {
    gOpen = gPrefs.begin("canbusprb", false);
    for (int i = 0; i < PROBE__COUNT; i++)
        gOn[i] = gOpen ? gPrefs.getBool(KEY[i], DEFAULT_ON[i]) : DEFAULT_ON[i];
}

bool probeEnabled(ProbeId id) {
    return (id >= 0 && id < PROBE__COUNT) ? gOn[id] : false;
}

void probeSetEnabled(ProbeId id, bool on) {
    if (id < 0 || id >= PROBE__COUNT) return;
    if (gOn[id] == on) return;             // flash has a finite number of erases
    gOn[id] = on;
    if (gOpen) gPrefs.putBool(KEY[id], on);
}

int probeIdFromName(const char *name) {
    if (!name) return -1;
    for (int i = 0; i < PROBE__COUNT; i++)
        if (strcasecmp(name, NAME[i]) == 0) return i;
    return -1;
}

size_t probeFlagsJson(char *out, size_t cap) {
    int n = snprintf(out, cap, "{");
    for (int i = 0; i < PROBE__COUNT; i++)
        n += snprintf(out + n, cap - n, "%s\"%s\":\"%s\"",
                      i ? "," : "", NAME[i], gOn[i] ? "ON" : "OFF");
    n += snprintf(out + n, cap - n, "}");
    return (size_t)n;
}

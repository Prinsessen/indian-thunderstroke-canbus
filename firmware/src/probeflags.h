/*
 * Which probes are running, and remembering it across a reboot.
 *
 * The probes have found six signals and settled three nulls, so they earn their
 * place -- but they are not all free. Three of them (the change detector, the
 * 2304 readout, the address claims) are silent or near-silent while riding.
 * probe/throttle is not: it publishes twice a second for as long as the wheels
 * turn, about 7,200 messages an hour over whatever network the bike is on.
 *
 * That is fine while hunting and pointless the rest of the time, so each probe
 * can now be switched from openHAB, which the owner can reach from anywhere.
 *
 * The flags live in NVS for the same reason the fault counters do: the ESP32 is
 * restarted often, and a setting that resets on every OTA is not a setting. The
 * counters learned that lesson the hard way -- see counters.h.
 *
 * Nothing here touches the CAN bus. It is our own code deciding whether to talk
 * to our own broker.
 */
#pragma once

#include <stdint.h>
#include <stddef.h>

enum ProbeId {
    PROBE_SCAN = 0,    // byte-level change detector + 2304, stationary only
    PROBE_CRUISE,      // 65265 SA39 bytes 4-5, any speed, on change only
    PROBE_THROTTLE,    // 65382 SA0 bytes 1+4, any speed, 2 Hz -- the expensive one
    PROBE_CLAIMS,      // address claims
    PROBE__COUNT
};

/** Open the namespace and load the stored flags. Call once from setup(). */
void probeFlagsBegin();

/** Is this probe enabled? Cheap: reads a RAM cache, not flash. */
bool probeEnabled(ProbeId id);

/** Set one flag and persist it. Writes only when the value actually changes. */
void probeSetEnabled(ProbeId id, bool on);

/** Look up by the name used on MQTT ("scan", "cruise", "throttle", "claims"). */
int probeIdFromName(const char *name);

/** JSON of the current flags, for the retained state topic openHAB binds to. */
size_t probeFlagsJson(char *out, size_t cap);

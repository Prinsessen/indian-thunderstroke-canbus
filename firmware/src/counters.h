/*
 * Fault counters that outlive a reboot.
 *
 * Three tallies exist to be read weeks after the events they count: brief
 * dropouts from each wheel speed sensor, and gear changes that happened
 * stationary on the stand with the engine running. None of them is a live
 * reading. Each is evidence, accumulated slowly, for a conversation with a
 * service desk that has already replaced the same part three times.
 *
 * They were RAM globals for their first evening, which quietly defeated the
 * entire purpose: the ESP32 was restarted about thirty times that day alone,
 * every OTA zeroing the tally. Arriving at a dealer with "3" instead of "23" --
 * and no way to know twenty were missing -- is worse than not counting at all,
 * because it looks like data.
 *
 * NVS survives a reboot and an OTA. It does NOT survive a full serial erase,
 * because the nvs partition sits at 0x9000 and a factory flash writes over it.
 * Read the numbers off the app before doing that.
 *
 * Writes are rate-limited. A counter that increments does so rarely by nature,
 * but a stuck sensor could chatter, and flash has a finite number of erases.
 */
#pragma once

#include <stdint.h>

enum CounterId { CNT_WHEEL_FRONT = 0, CNT_WHEEL_REAR, CNT_GEAR_GLITCH, CNT__COUNT };

/** Open the namespace and load the stored tallies. Call once from setup(). */
void countersBegin();

/** Current value. */
uint16_t counterGet(CounterId id);

/** Add one and persist it. Returns the new value. */
uint16_t counterBump(CounterId id);

/*
 * Deep sleep while the bus is quiet, waking on the first frame.
 *
 * WHY IT IS SHAPED THIS WAY
 * -------------------------
 * The board takes permanent 12 V from the Indian's service connector, so it is
 * powered whether or not the motorcycle is. Left awake it holds WiFi up and
 * publishes a heartbeat every thirty seconds, forever, into a battery that is
 * not being charged.
 *
 * THE WAKE PATH, AND WHY IT NEEDS NO REGISTER HACKING
 * --------------------------------------------------
 * The obvious design puts the MCP2518FD to sleep too and wakes it on bus
 * activity. ACAN2517FD can enter and leave Sleep mode but does not expose the
 * wake-on-CAN interrupt configuration, so that path means raw SPI writes to
 * registers the library does not touch -- and no way to test them off the bike.
 *
 * So the controller stays in Normal mode and keeps receiving. Its INT line is
 * level-triggered and already wired to GPIO 8, which is RTC-capable on the
 * ESP32-S3: the first frame after the ignition comes on pulls INT low, and ext0
 * wakes the chip. Nothing undocumented, and the same interrupt the driver
 * already relies on.
 *
 * That leaves the controller and transceiver powered, so this does not reach the
 * lowest current the board can theoretically do. It does switch off the part
 * that actually costs -- the radio and the CPU -- and it does so without writing
 * registers nobody can verify from here. Measure before going further.
 *
 * THE THREE THINGS THAT KEEP IT RECOVERABLE
 * -----------------------------------------
 * A sleep bug on a motorcycle means riding out to pull a fuse, so:
 *
 *   1. OFF BY DEFAULT, and switched from openHAB like the probes. It cannot
 *      sleep until it is told to, and it can be told not to from anywhere.
 *   2. A TIMER BACKSTOP is armed alongside the CAN wake, always. If wake-on-CAN
 *      never fires -- wrong pin state, a controller quirk, anything -- the board
 *      still comes back on its own and can be reached and disabled.
 *   3. A MINIMUM AWAKE WINDOW after every wake. Without it a device that wakes,
 *      connects and immediately sleeps again can never be caught, because the
 *      window in which it is reachable is shorter than the time it takes to send
 *      it a command.
 *
 * Deep sleep is a reset: RAM is lost, WiFi and MQTT come up again from nothing,
 * and the first seconds of a ride go unrecorded. The fault counters survive
 * because they live in NVS -- see counters.h.
 */
#pragma once

#include <stdint.h>
#include <stddef.h>

/** Load the stored enable flag and record why we woke. Call early in setup(). */
void sleepBegin();

/** Is sleeping allowed at all? */
bool sleepEnabled();

/** Set and persist. Writes flash only when the value actually changes. */
void sleepSetEnabled(bool on);

/** Call on every received CAN frame: this is what "the bus is awake" means. */
void sleepNoteFrame();

/**
 * Decide whether to sleep, and do it. Call once per loop.
 *
 * Sleeps only when: enabled, past the minimum awake window, no CAN frame for
 * the quiet period, and nothing important is in flight.
 */
void sleepTick(bool mqttConnected, bool busy);

/** "power-on", "can", "timer" or "other" -- how this boot began. */
const char *sleepWakeReason();

/**
 * Retained status for openHAB, same shape as probe/enabled.
 *
 * `asleep` marks the copy published in the last moment before going down. It
 * exists because openHAB cannot otherwise tell the two silences apart: the
 * board sleeping on purpose and the board crashed, out of WiFi range or
 * unpowered all leave the same "offline" behind, and they want very different
 * responses. Once it is behind a panel rather than on a USB lead, that
 * distinction is the difference between shrugging and going to look.
 */
size_t sleepStatusJson(char *out, size_t cap, bool asleep);

/**
 * Publish the going-to-sleep marker. Implemented in main.cpp, which owns the
 * MQTT client; called from sleepTick() as the last thing before the chip stops.
 */
void sleepPublishAsleep();

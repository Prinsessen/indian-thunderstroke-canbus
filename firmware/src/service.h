/*
 * Service interval memory — the odometer reading at the last service.
 *
 * This lives on the bike, in NVS, and that is the entire point of the file.
 *
 * The number was previously kept in the phone app's preferences, where it was
 * one uninstall away from being lost — and unlike every other figure on this
 * bus, it cannot be re-derived. An odometer, a tyre pressure and a fuel level
 * are all measured afresh every second; the mileage of an oil change eight
 * months ago exists only because somebody wrote it down. Storage that survives
 * a phone being reinstalled, replaced or dropped in a car park is therefore not
 * a nicety here, it is the requirement.
 *
 * NVS survives a reboot and an OTA update. It does NOT survive a full serial
 * erase (`esptool erase_flash`, or a factory flash over the whole 0x0-0x15dfff
 * range), because the nvs partition sits at 0x9000 — inside it. Read the value
 * off the app before doing that.
 */
#pragma once

#include <stdint.h>

/** Sentinel for "no service has ever been recorded". */
#define SERVICE_KM_UNSET (-1)

/** Open the namespace and load the stored value. Call once from setup(). */
void serviceBegin();

/** Odometer reading at the last service, or SERVICE_KM_UNSET. */
int32_t serviceLastKm();

/**
 * Record a service.
 *
 * Refuses anything implausible rather than storing it: a negative reading, a
 * figure beyond any odometer this bike will reach, or one ahead of where the
 * bike actually is. The last check is the one that matters — a corrupted BLE
 * write that set the service point 40 000 km into the future would silently
 * disable every reminder, and the failure would look exactly like the feature
 * working.
 *
 * Returns false if it was rejected, in which case nothing was written.
 */
bool serviceSetLastKm(int32_t km);

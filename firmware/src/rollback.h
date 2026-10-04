/*
 * rollback.h -- an OTA image has to earn its place.
 *
 * The bootloader is built with CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE: an image
 * that arrived by OTA boots as PENDING_VERIFY, and if the chip resets again
 * before the image is marked valid, the bootloader marks it ABORTED and boots
 * the previous one. Until 2026-10-04 that bought nothing, because the Arduino
 * core marked every image valid in initArduino(), before setup() ran -- an
 * image that booted and never found the network stayed, and the only way back
 * was the USB pads under the seat (OTA.md, "What OTA cannot undo").
 *
 * This module takes that decision away from the core (verifyRollbackLater()
 * returns true) and makes it:
 *
 *   valid      the first time this boot reaches the broker. That is the one
 *              thing the next OTA needs, so it is the one thing that counts.
 *   rolled     if the broker has not been reached ROLLBACK_GRACE_MS after boot:
 *   back       esp_ota_mark_app_invalid_rollback_and_reboot(), and the previous
 *              image announces itself a few seconds later.
 *
 * While the image is pending, deep sleep is held off (rollbackPending() is
 * part of sleepTick()'s busy argument): a deep-sleep wake goes through the
 * bootloader, which would read "pending, booted twice" and roll back a good
 * image that was merely asleep.
 *
 * A USB-flashed image has no pending state (otadata is blank after a factory
 * flash) and is never touched here. There is nothing to roll back to anyway.
 *
 * ROLLBACK_TEST_NO_BROKER, a build flag for the bench only: the broker
 * connection is seen and deliberately ignored, so the roll-back path can be
 * watched on a board with working WiFi. Never in an image for the bike.
 */
#pragma once
#include <Arduino.h>

#ifndef ROLLBACK_GRACE_MS
#define ROLLBACK_GRACE_MS (5UL * 60UL * 1000UL)
#endif

void        rollbackBegin();          // read this image's state; in setup(), before netBegin()
void        rollbackNoteBrokerUp();   // from the "$connected" handler: mark valid
void        rollbackTick();           // from loop(): roll back when the grace runs out
bool        rollbackPending();        // true until marked valid; holds deep sleep off
const char *rollbackState();          // for meta: "none" / "pending" / "valid" / "invalid" / "aborted"
const char *rollbackOtherState();     // the other slot, for meta: "invalid" there after a roll-back

#include <Arduino.h>
#include <Preferences.h>
#include <esp_sleep.h>
#include <driver/rtc_io.h>
#include "config.h"
#include "can_hal.h"
#include "sleep.h"

// The controller's interrupt line. Same pin the driver already uses; see
// can_hal_mcp.cpp. RTC-capable on the ESP32-S3, which is what makes ext0 work.
#ifndef MCP_INT
#define MCP_INT 8
#endif
#ifndef MCP_CS
#define MCP_CS 10
#endif

// Quiet for this long before sleeping. Generous on purpose: a queue at a level
// crossing is not the ignition going off, and waking costs more than staying up
// for another minute.
#ifndef SLEEP_QUIET_MS
#define SLEEP_QUIET_MS (5UL * 60UL * 1000UL)
#endif

// The backstop. Even with wake-on-CAN broken, the board reappears this often,
// which is the difference between a bug and a ride out to pull the fuse.
#ifndef SLEEP_BACKSTOP_S
#define SLEEP_BACKSTOP_S (60UL * 60UL)
#endif

// Never sleep within this long of waking. A device whose reachable window is
// shorter than the time it takes to send it a command cannot be recalled.
#ifndef SLEEP_MIN_AWAKE_MS
#define SLEEP_MIN_AWAKE_MS (90UL * 1000UL)
#endif

static Preferences  gPrefs;
static bool         gEnabled   = false;
static uint32_t     gLastFrame = 0;
static uint32_t     gBootMs    = 0;
static const char  *gWakeWhy   = "power-on";

void sleepBegin() {
    gBootMs    = millis();
    gLastFrame = millis();

    switch (esp_sleep_get_wakeup_cause()) {
        case ESP_SLEEP_WAKEUP_EXT0:  gWakeWhy = "can";   break;
        case ESP_SLEEP_WAKEUP_TIMER: gWakeWhy = "timer"; break;
        case ESP_SLEEP_WAKEUP_UNDEFINED: gWakeWhy = "power-on"; break;
        default: gWakeWhy = "other"; break;
    }

    // Release any pin held through the last sleep, or the driver cannot drive
    // CS and every SPI transfer fails silently.
    gpio_hold_dis((gpio_num_t)MCP_CS);
    gpio_deep_sleep_hold_dis();

    gPrefs.begin("sleepcfg", false);
    gEnabled = gPrefs.getBool("en", false);
    gPrefs.end();
}

bool sleepEnabled() { return gEnabled; }

void sleepSetEnabled(bool on) {
    if (on == gEnabled) return;              // flash has a write budget
    gEnabled = on;
    gPrefs.begin("sleepcfg", false);
    gPrefs.putBool("en", on);
    gPrefs.end();
}

void sleepNoteFrame() { gLastFrame = millis(); }

const char *sleepWakeReason() { return gWakeWhy; }

size_t sleepStatusJson(char *out, size_t cap, bool asleep) {
    const uint32_t quiet = millis() - gLastFrame;
    return snprintf(out, cap,
        "{\"enabled\":\"%s\",\"state\":\"%s\",\"wake\":\"%s\","
        "\"quiet_s\":%lu,\"after_s\":%lu}",
        gEnabled ? "ON" : "OFF", asleep ? "asleep" : "awake", gWakeWhy,
        (unsigned long)(quiet / 1000UL),
        (unsigned long)(SLEEP_QUIET_MS / 1000UL));
}

void sleepTick(bool mqttConnected, bool busy) {
    if (!gEnabled) return;
    if (busy) return;                                        // OTA or similar
    if (millis() - gBootMs   < SLEEP_MIN_AWAKE_MS) return;   // stay catchable
    if (millis() - gLastFrame < SLEEP_QUIET_MS)    return;    // bus still alive

    // Pin the controller to a rate that will actually receive, or the wake
    // never happens. Asleep at 500 on a 250 kbps bus it hears nothing, INT never
    // asserts, and wake-on-CAN quietly degrades into "wakes hourly on the
    // backstop" -- which looks like it works, until you time how long the
    // ignition takes to bring it back.
    //
    // This mattered acutely when the firmware round-robined 250/500/125/100/50
    // kbps looking for the bus, because the controller could then be on any of
    // them at the moment we decided to sleep. CAN_FIXED_BITRATE removed that
    // search, so the rate is no longer free to wander -- but the pin stays,
    // because it costs nothing and it is the line that makes the wake path
    // independent of whatever the scan code does next.
    //
    // CAN_RATES[0] is 250 kbps, which this bus has been confirmed at since
    // August and is marked "try first" in the table for that reason.
    canStop();
    canInit(CAN_RATES[0].bitrate, true /*listen-only*/);

    // Say so before going quiet, so the offline that follows is explained
    // rather than merely observed.
    sleepPublishAsleep();

    Serial.printf("[sleep] quiet %lus, sleeping at %s; wake on CAN or in %lus\n",
                  (unsigned long)((millis() - gLastFrame) / 1000UL),
                  CAN_RATES[0].name,
                  (unsigned long)SLEEP_BACKSTOP_S);
    Serial.flush();

    // Hold CS high through the sleep. Left floating, the controller can see
    // phantom selects and the wake-up frame is the one that gets corrupted.
    pinMode(MCP_CS, OUTPUT);
    digitalWrite(MCP_CS, HIGH);
    gpio_hold_en((gpio_num_t)MCP_CS);
    gpio_deep_sleep_hold_en();

    // Hold the pull-up on INT through the sleep, or none of this works.
    //
    // ACAN2517FD sets the pin up as INPUT_PULLUP and relies on the ESP32's
    // internal pull-up to hold it high when the controller has nothing to say.
    // Internal pull-ups are switched off in deep sleep unless the RTC domain is
    // told to keep them, so the line floated: ext0 compares the pad level, and a
    // floating pad is undefined. It read high enough never to wake.
    //
    // Measured on the bike 2026-09-07: slept at 05:03:51, ignition on at 05:06,
    // and it was still asleep two minutes later. The hourly backstop would have
    // returned it around 06:04, which is the whole reason that line exists.
    rtc_gpio_pullup_en((gpio_num_t)MCP_INT);
    rtc_gpio_pulldown_dis((gpio_num_t)MCP_INT);

    // INT is active low and level-held while a frame is unread, so it is still
    // asserted by the time the chip has finished going to sleep.
    esp_sleep_enable_ext0_wakeup((gpio_num_t)MCP_INT, 0);

    // Armed every single time, never conditionally. This is the line that keeps
    // a wake-on-CAN failure from being a fuse-pulling failure.
    esp_sleep_enable_timer_wakeup((uint64_t)SLEEP_BACKSTOP_S * 1000000ULL);

    esp_deep_sleep_start();                                   // does not return
}

// See rollback.h for what this is and why.
#include "rollback.h"
#include <esp_ota_ops.h>
#include "config.h"
#include "net.h"

static bool                 sHaveState = false;
static esp_ota_img_states_t sState     = ESP_OTA_IMG_UNDEFINED;
static bool                 sPending   = false;   // PENDING_VERIFY at boot, not yet decided
static bool                 sValidNow  = false;   // marked valid in this boot
static bool                 sGaveUp    = false;   // grace ran out, nothing to go back to

// The Arduino core asks this in initArduino(); false (the weak default) makes
// it mark the running image valid before setup(). C linkage: the core is C.
extern "C" bool verifyRollbackLater() { return true; }

static const char *stateName(esp_ota_img_states_t s) {
    switch (s) {
        case ESP_OTA_IMG_NEW:            return "new";
        case ESP_OTA_IMG_PENDING_VERIFY: return "pending";
        case ESP_OTA_IMG_VALID:          return "valid";
        case ESP_OTA_IMG_INVALID:        return "invalid";
        case ESP_OTA_IMG_ABORTED:        return "aborted";
        default:                         return "none";    // UNDEFINED: a factory flash
    }
}

void rollbackBegin() {
    const esp_partition_t *run = esp_ota_get_running_partition();
    if (run && esp_ota_get_state_partition(run, &sState) == ESP_OK) {
        sHaveState = true;
        sPending   = (sState == ESP_OTA_IMG_PENDING_VERIFY);
    }
    Serial.printf("[rollback] running %s, state %s, other slot %s\n",
                  run ? run->label : "?", rollbackState(), rollbackOtherState());
    if (sPending)
        Serial.printf("[rollback] this image must reach the broker within %lu s or it goes back\n",
                      (unsigned long)(ROLLBACK_GRACE_MS / 1000UL));
#if ROLLBACK_TEST_NO_BROKER
    Serial.println("[rollback] TEST BUILD: the broker will be ignored, this image rolls back on purpose");
#endif
}

bool rollbackPending() { return sPending; }

const char *rollbackState() {
    if (sValidNow) return "valid";
    if (sPending)  return "pending";
    return sHaveState ? stateName(sState) : "none";
}

const char *rollbackOtherState() {
    const esp_partition_t *run   = esp_ota_get_running_partition();
    const esp_partition_t *other = run ? esp_ota_get_next_update_partition(run) : nullptr;
    esp_ota_img_states_t st;
    if (other && esp_ota_get_state_partition(other, &st) == ESP_OK) return stateName(st);
    return "none";
}

static void say(const char *line) {
    Serial.println(line);
    netLog(line);                          // <base>/debug, or the ring while offline
}

void rollbackNoteBrokerUp() {
    if (!sPending) return;
#if ROLLBACK_TEST_NO_BROKER
    say("[rollback] TEST: broker reached and ignored; the grace timer decides");
    return;
#else
    const esp_err_t e = esp_ota_mark_app_valid_cancel_rollback();
    if (e == ESP_OK) {
        sPending  = false;
        sValidNow = true;
        say("[rollback] broker reached, image marked valid");
        static const char kMsg[] = "Running " FW_VERSION " - verified";
        netPublish("ota/status", kMsg, sizeof(kMsg) - 1, true);
    } else {
        char b[80];
        snprintf(b, sizeof(b), "[rollback] mark valid failed: %s", esp_err_to_name(e));
        say(b);
    }
#endif
}

void rollbackTick() {
    if (!sPending || sGaveUp) return;
    if (millis() < ROLLBACK_GRACE_MS) return;

    static char msg[96];
    snprintf(msg, sizeof(msg), "ROLLBACK: no broker within %lu s, returning to the previous image",
             (unsigned long)(ROLLBACK_GRACE_MS / 1000UL));
    say(msg);
    netPublish("ota/status", msg, strlen(msg), true);   // arrives only in the TEST build, by design
    netFlush(3000);
    delay(200);

    // Marks this slot INVALID, points the bootloader at the other one, reboots.
    // Returns only when there is no valid image to go back to.
    const esp_err_t e = esp_ota_mark_app_invalid_rollback_and_reboot();
    char b[96];
    snprintf(b, sizeof(b), "[rollback] nothing to go back to (%s); staying on this image", esp_err_to_name(e));
    say(b);
    sGaveUp = true;
    sPending = false;
    esp_ota_mark_app_valid_cancel_rollback();   // or the bootloader aborts it on the next reset
}

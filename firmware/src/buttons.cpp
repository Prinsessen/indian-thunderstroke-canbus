#include "buttons.h"
#include <stdio.h>

static const uint32_t LONG_MS = 800;      // four 200 ms samples: not reachable by a normal tap
static const uint32_t DOUBLE_GAP_MS = 600; // release-to-press gap that still counts as a double

static button_emit_t s_emit;
static bool s_down;            // any button held
static bool s_both;            // both seen held during this press
static bool s_left;            // the side of the current/last press (when not both)
static uint32_t s_downSince;   // ms when the press began
static bool s_pendingShort;    // a short press waiting to see if a second one follows
static bool s_pendingLeft, s_pendingBoth;
static uint32_t s_releasedAt;

static uint8_t codeFor(const char *side, const char *kind) {
    const uint8_t k = kind[0] == 's' ? 1 : kind[0] == 'l' ? 2 : 3;          // short / long / double
    const uint8_t s = side[0] == 'l' ? 0 : side[0] == 'r' ? 1 : 2;          // left / right / both
    return (uint8_t)(k + 3 * s);
}
static void emit(const char *side, const char *kind) {
    static char buf[24];
    snprintf(buf, sizeof(buf), "%s %s", side, kind);
    if (s_emit) s_emit(buf, codeFor(side, kind));
}
static const char *side(bool both, bool left) { return both ? "both" : (left ? "left" : "right"); }

void buttonsBegin(button_emit_t e) { s_emit = e; }

void buttonsSample(bool left, bool right, uint32_t now) {
    const bool any = left || right;
    if (any && !s_down) {                          // press begins
        s_down = true; s_downSince = now; s_both = left && right; s_left = left;
        if (s_pendingShort && now - s_releasedAt <= DOUBLE_GAP_MS && s_pendingBoth == (left && right) && s_pendingLeft == left) {
            s_pendingShort = false;
            emit(side(s_pendingBoth, s_pendingLeft), "double");
            s_downSince = 0;                       // this press is consumed by the double
        } else if (s_pendingShort) {
            s_pendingShort = false;
            emit(side(s_pendingBoth, s_pendingLeft), "short");
        }
        return;
    }
    if (any && s_down) { if (left && right) s_both = true; return; }
    if (!any && s_down) {                          // release
        s_down = false;
        if (s_downSince == 0) return;              // second half of a double, already reported
        const uint32_t held = now - s_downSince;
        if (held >= LONG_MS) emit(side(s_both, s_left), "long");
        else { s_pendingShort = true; s_pendingLeft = s_left; s_pendingBoth = s_both; s_releasedAt = now; }
    }
}

void buttonsTick(uint32_t now) {
    if (s_pendingShort && now - s_releasedAt > DOUBLE_GAP_MS) {
        s_pendingShort = false;
        emit(side(s_pendingBoth, s_pendingLeft), "short");
    }
}

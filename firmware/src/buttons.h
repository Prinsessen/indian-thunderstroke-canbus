/*
 * The two handlebar MFD/trip buttons as events.
 *
 * Found 2026-09-18: PGN 65381 SA 39 byte 0, bit 0 = left MFD/trip button,
 * bit 2 = right TPMS/trip button, set while pressed. The VCM sends that
 * message every 200 ms, so timing resolves to a fifth of a second.
 *
 * The buttons keep their factory job (the cluster pages on every press, a
 * long left press resets the trip), so the patterns the house acts on are the
 * ones the cluster does not mind: a long RIGHT press, a double press, both
 * together. Short presses are reported too, for completeness.
 *
 * Events, one line each on canbus/<base>/button, not retained:
 *   "left short" "left long" "left double" "right short" "right long"
 *   "right double" "both short" "both long" ("both double" is possible too)
 *
 * The same event as a one-byte code, for the BLE `button` characteristic
 * (2026-09-19): code = kind + 3 * side, kind 1 short / 2 long / 3 double,
 * side 0 left / 1 right / 2 both. So left short = 1 ... right double = 6,
 * both short = 7, both long = 8, both double = 9. 0 is never sent.
 */
#pragma once
#include <stdint.h>

typedef void (*button_emit_t)(const char *event, uint8_t code);
void buttonsBegin(button_emit_t emit);
void buttonsSample(bool left, bool right, uint32_t nowMs);   /* every 65381 SA 39 frame */
void buttonsTick(uint32_t nowMs);                            /* from loop(): resolves shorts after the double-press gap */

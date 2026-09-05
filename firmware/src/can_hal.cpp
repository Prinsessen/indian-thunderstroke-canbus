/*
 * can_hal.cpp — shared (backend-independent) parts of the CAN HAL.
 * ---------------------------------------------------------------------------
 * Just the bitrate table the auto-detect scanner walks. Numeric bit/s so it is
 * identical for every backend; each backend (can_hal_twai.cpp / can_hal_mcp.cpp)
 * maps a rate to its own timing representation. Always compiled.
 */
#include "can_hal.h"

// 250 kbps first: classic SAE J1939 (Polaris/Indian) — the rate this bike uses.
// The rest cover the other standard J1939 / powertrain rates so the scanner can
// still lock a different vehicle if this firmware is ever reused.
const CanRate CAN_RATES[] = {
    { 250000, "250 kbps" },   // classic SAE J1939 (Polaris/Indian) — try first
    { 500000, "500 kbps" },   // J1939-14 / powertrain
    { 125000, "125 kbps" },
    { 100000, "100 kbps" },
    {  50000, "50 kbps"  },
};
const int CAN_NUM_RATES = sizeof(CAN_RATES) / sizeof(CAN_RATES[0]);

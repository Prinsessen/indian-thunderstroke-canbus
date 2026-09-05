/*
 * can_hal.h — CAN Hardware Abstraction Layer
 * ---------------------------------------------------------------------------
 * ONE firmware, TWO boards. Everything above the CAN wire (J1939 decode, TP/BAM
 * reassembly, MQTT, OTA, WiFi, status LED) is byte-for-byte identical no matter
 * which CAN controller is fitted. Only this thin layer differs per board, and
 * the board is chosen with a single #define CAN_BACKEND in config.h:
 *
 *   CAN_BACKEND_TWAI     LilyGO T-CAN485   ESP32,     native TWAI + onboard xcvr
 *   CAN_BACKEND_MCP2518  LilyGO T-2CAN     ESP32-S3 + external MCP2518FD on SPI
 *
 * The trick that keeps main.cpp untouched: CanFrame below uses the SAME field
 * names as the ESP32 twai_message_t (.identifier / .extd / .data_length_code /
 * .data[]), so every decode function compiles unchanged against either backend.
 *
 * Each backend lives in its own .cpp (can_hal_twai.cpp / can_hal_mcp.cpp) and
 * wraps its whole body in `#if CAN_BACKEND == …`, so the unused one compiles to
 * nothing and pulls in no library. The shared bitrate table lives in
 * can_hal.cpp (always compiled).
 */
#pragma once
#include <stdint.h>
#include <stddef.h>

// ---- Backend identifiers (referenced by CAN_BACKEND in config.h) -----------
#define CAN_BACKEND_TWAI     0   // ESP32 native TWAI  (LilyGO T-CAN485)
#define CAN_BACKEND_MCP2518  1   // External MCP2518FD (LilyGO T-2CAN, SPI)

// Safe default so a config.h that predates the backend selector still builds
// for the currently deployed T-CAN485. The real value comes from config.h.
#ifndef CAN_BACKEND
#define CAN_BACKEND CAN_BACKEND_TWAI
#endif

// ---------------------------------------------------------------------------
// Board-agnostic CAN frame. Field names deliberately mirror twai_message_t so
// the J1939 decode layer needs ZERO changes between backends. Only the four
// fields the decoders actually read are exposed.
// ---------------------------------------------------------------------------
struct CanFrame {
    uint32_t identifier;         // 11-bit (std) or 29-bit (ext/J1939) CAN ID
    bool     extd;               // true = 29-bit extended frame (J1939)
    uint8_t  data_length_code;   // number of valid data bytes, 0..8
    uint8_t  data[8];            // payload
};

// ---------------------------------------------------------------------------
// Shared bitrate table the auto-detect scanner walks (defined in can_hal.cpp).
// Numeric bit/s so it is backend-independent; each backend maps a rate to its
// own timing representation. 250 kbps first because that is classic SAE J1939
// (Polaris/Indian), which is what this bike uses.
// ---------------------------------------------------------------------------
struct CanRate { uint32_t bitrate; const char *name; };
extern const CanRate CAN_RATES[];
extern const int     CAN_NUM_RATES;

// ---------------------------------------------------------------------------
// Backend interface — implemented once per board in can_hal_*.cpp.
// ---------------------------------------------------------------------------

// One-time board bring-up: transceiver power / SPI / controller reset. Call
// once in setup() BEFORE the first canInit(). On T-CAN485 this drives the 5V
// boost + transceiver mode pins (the "read zero frames" gotcha); on T-2CAN it
// starts SPI and hard-resets the MCP2518FD.
void canHardwareInit();

// Install + start the controller at `bitrate` (bit/s). listenOnly=true puts the
// controller in hardware listen-only mode — it never ACKs and never transmits,
// the only safe way to probe an unknown/live vehicle bus. Returns false if the
// controller could not be installed or started.
bool canInit(uint32_t bitrate, bool listenOnly);

// Stop + uninstall the controller so a different bitrate can be tried during
// auto-detect. Safe to call when already stopped.
void canStop();

// Wait up to timeoutMs for one frame. Returns true and fills `f` when a frame
// arrived, false on timeout / empty queue. timeoutMs==0 = non-blocking poll.
bool canReceive(CanFrame &f, uint32_t timeoutMs);

// Transmit one frame. ONLY reached when TX is deliberately enabled at compile
// time (TX_ENABLED). Returns false if the controller is listen-only or the send
// failed. Kept in the interface so the (compiled-out by default) J1939 request
// helper is board-agnostic too.
bool canTransmit(const CanFrame &f);

// True once canInit() has succeeded and the controller is running.
bool canRunning();

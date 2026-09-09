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

// ---------------------------------------------------------------------------
// Bus health — the only thing in this project that measures OUR OWN
// installation rather than the motorcycle.
//
// Every other signal describes the machine. These describe the wire we spliced
// into it: a T-tap and a length of cable on something that vibrates, feeding a
// bus that carries ABS data. We believe the board is passive. This is how that
// belief gets checked rather than asserted.
//
// The counters are per-CONTROLLER-START, not lifetime. canInit() reconfigures
// the controller from scratch and deep sleep restarts the whole chip, so every
// wake opens a clean window -- which is one ride, and exactly the granularity
// wanted. It also removes any need to clear the latching flags, which the
// MCP2518FD driver does not expose a way to do.
// ---------------------------------------------------------------------------
struct CanHealth {
    bool     valid;        // false = the backend cannot report, do not publish
    uint16_t tec;          // transmit error counter (C1TREC)
    uint16_t rec;          // receive error counter (C1TREC)
    uint16_t rxErr;        // nominal-bitrate RECEIVE error count (C1BDIAG0)
    uint16_t txErr;        // nominal-bitrate TRANSMIT error count (C1BDIAG0)
    // Error-FREE messages seen this ride, accumulated across the hardware
    // counter's 16-bit wrap.
    //
    // This field was briefly published as "bus errors" and it is the opposite.
    // C1BDIAG1's low half is EFMSGCNT, not an error count, and the mistake was
    // caught within a minute of the first reading: it climbed by about 197 a
    // second on a healthy bus while every genuine error indicator sat at zero.
    // That is the J1939 message rate. A bus with 197 errors a second is dead.
    //
    // Correctly named it is the more useful number of the two, because it is a
    // DENOMINATOR. Errors per million messages says something across rides of
    // different lengths; a raw error count only says how far you rode.
    uint32_t efMsgs;
    char     state[8];     // "OK", "WARN", "PASSIVE", "BUSOFF"
    // Comma-separated error TYPES seen since the controller started, e.g.
    // "STUFF,CRC". Empty when clean. This is the field worth reading: a rising
    // stuff or CRC count is the signature of a connector working loose or a
    // wire chafing, and it appears long before anything visible fails, because
    // CAN retransmits and the other modules recover.
    //
    // Only the MCP2518FD backend fills it -- the ESP32 TWAI peripheral counts
    // bus errors but does not break them down by type, so there it stays empty
    // and that is a limit of the silicon, not a fault.
    char     errs[64];
};

// Read the controller's error state. Returns false if the backend cannot report
// or the controller is not running.
bool canHealth(CanHealth &h);

/*
 * can_hal_mcp.cpp — CAN HAL backend for an external MCP2518FD controller.
 * ---------------------------------------------------------------------------
 * Board: LilyGO T-2CANFD (T-2Can-Fd_V1.0) — ESP32-S3-WROOM-1U + an MCP2518FD
 * CAN-FD controller on SPI. The controller sits on the board's "CAN A" port
 * (the second port, "CAN B", is the ESP32-S3 NATIVE TWAI on GPIO6/7 and is NOT
 * used by this backend). Classic (non-FD) J1939 frames are read exactly like
 * the TWAI backend; the decode layer above never knows the difference.
 *
 * Compiled ONLY when config.h selects  CAN_BACKEND == CAN_BACKEND_MCP2518.  The
 * whole file is #if-guarded, so the T-CAN485/TWAI build pulls in NONE of this
 * and does NOT need the ACAN2517FD library.
 *
 * PIN MAP + OSC verified against LilyGO's official T-2Can pin_config.h and the
 * T_2Can_Fd examples (examples/can, examples/original_test):
 *       SPI  SCLK=12  MOSI=11  MISO=13   MCP2518 CS=10   INT=8
 *       Crystal = 40 MHz  (Longan_CANFD + ACAN2517FD both assume 40 MHz here).
 *
 * ⚠️  IMPORTANT — the MCP2518FD has NO hardware RESET pin (unlike the MCP2515 on
 *     the older non-FD T-2Can). On the T-2CANFD, ESP32 GPIO9/GPIO3 are the
 *     controller's INT0/INT1 OUTPUTS, so they must be left as inputs — never
 *     driven. ACAN2517FD issues a software reset over SPI inside begin(), so no
 *     manual reset is needed (LilyGO's FD example does none either).
 */
#include <Arduino.h>
#include "config.h"
#include "can_hal.h"

#if CAN_BACKEND == CAN_BACKEND_MCP2518

#include <SPI.h>
#include <ACAN2517FD.h>

// ==================== PIN CONFIG (LilyGO T-2CANFD, CAN A) ====================
// ESP32-S3 SPI wiring to the MCP2518FD, from LilyGO's official pin_config.h
// (T_2Can_Fd). Overridable from config.h.
#ifndef MCP_SCK
#define MCP_SCK   12   // SPI_SCLK
#endif
#ifndef MCP_MOSI
#define MCP_MOSI  11   // SPI_MOSI
#endif
#ifndef MCP_MISO
#define MCP_MISO  13   // SPI_MISO
#endif
#ifndef MCP_CS
#define MCP_CS    10   // MCP2518_CS
#endif
#ifndef MCP_INT
#define MCP_INT    8   // MCP2518_INT  (main interrupt used by ACAN2517FD)
#endif
// NOTE: there is deliberately NO MCP_RST. The MCP2518FD has no reset pin, and on
// the T-2CANFD ESP32 GPIO9 (INT_0) / GPIO3 (INT_1) are controller OUTPUTS —
// driving them causes pin contention. begin() software-resets over SPI.

// ⚡ MUST match the crystal fitted on the board (LilyGO T-2CANFD = 40 MHz). Wrong
//    value ⇒ the controller never syncs and you read ZERO frames.
#ifndef MCP_OSC_HZ
#define MCP_OSC_HZ 40000000UL
#endif

static ACAN2517FD s_can(MCP_CS, SPI, MCP_INT);
static bool       s_running    = false;
static uint32_t   s_curBitrate = 0;

void canHardwareInit() {
    // Bring up SPI on the T-2CANFD pin map. NO manual reset: the MCP2518FD has
    // no reset pin, GPIO9/GPIO3 are the controller's INT0/INT1 outputs, and
    // ACAN2517FD::begin() performs a software reset over SPI. This mirrors
    // LilyGO's own FD example, which does nothing but SPI.begin() + begin().
    SPI.begin(MCP_SCK, MCP_MISO, MCP_MOSI, MCP_CS);
}

bool canInit(uint32_t bitrate, bool listenOnly) {
    if (s_running) canStop();
    // Classic CAN (not CAN-FD): arbitration == data bitrate, factor x1. J1939 is
    // classic 8-byte frames, so we drive the MCP2518FD in classic-compatible
    // mode and read them as CANFDMessage of type CAN_DATA.
    ACAN2517FDSettings settings(
        ACAN2517FDSettings::OSC_40MHz,      // keep in sync with MCP_OSC_HZ below
        bitrate, DataBitRateFactor::x1);
    settings.mRequestedMode = listenOnly
        ? ACAN2517FDSettings::ListenOnly    // never ACKs / never transmits
        : ACAN2517FDSettings::Normal20B;    // classic + extended IDs
    // Deep FIFOs so a 250k J1939 flood doesn't overflow between polls.
    settings.mDriverReceiveFIFOSize  = 64;
    settings.mDriverTransmitFIFOSize = 8;

    const uint32_t err = s_can.begin(settings, [] { s_can.isr(); });
    if (err != 0) return false;             // non-zero = config/SPI error
    s_running    = true;
    s_curBitrate = bitrate;
    return true;
}

void canStop() {
    if (!s_running) return;
    s_can.end();
    s_running = false;
}

bool canReceive(CanFrame &out, uint32_t timeoutMs) {
    // ACAN2517FD::receive() is a non-blocking poll; emulate the TWAI blocking
    // timeout by spinning until a frame arrives or the deadline passes.
    const uint32_t start = millis();
    CANFDMessage msg;
    for (;;) {
        if (s_can.receive(msg)) {
            out.identifier       = msg.id;
            out.extd             = msg.ext;
            out.data_length_code = (msg.len > 8) ? 8 : msg.len;  // clamp: J1939 is 8
            for (int i = 0; i < 8; i++)
                out.data[i] = (i < out.data_length_code) ? msg.data[i] : 0;
            return true;
        }
        if (timeoutMs == 0 || millis() - start >= timeoutMs) return false;
        delay(1);
    }
}

bool canTransmit(const CanFrame &in) {
    if (!s_running) return false;
    CANFDMessage msg;
    msg.type = CANFDMessage::CAN_DATA;      // classic frame, not FD
    msg.id   = in.identifier;
    msg.ext  = in.extd;
    msg.len  = in.data_length_code;
    for (int i = 0; i < in.data_length_code && i < 8; i++) msg.data[i] = in.data[i];
    return s_can.tryToSend(msg);
}

bool canRunning() { return s_running; }

// ---------------------------------------------------------------------------
// Bus health, from the MCP2518FD's three diagnostic registers.
//
// ACAN2517FD exposes them as two public reads and nothing else, which is all
// that is needed: errorCounters() returns C1TREC and diagInfos(n) returns
// C1BDIAG0 or C1BDIAG1. There is no write, so the latching flags in C1BDIAG1
// cannot be cleared -- and they do not need to be, because canInit() runs
// begin() from scratch on every boot and deep sleep restarts the chip. Each
// wake is therefore a fresh window covering one ride.
//
// THE BIT POSITIONS BELOW COME FROM THE MCP2518FD DATA SHEET, not from the
// library, which defines no names for them. That is worth saying out loud: a
// wrong offset here would produce confident nonsense rather than an error. The
// empirical check is that a healthy bus must report NOTHING -- if a flag is
// lit on a bus that is working, the decode is wrong, not the wire.
// ---------------------------------------------------------------------------
// C1TREC (0x034)
static const uint32_t TREC_REC_MASK  = 0x000000FFu;   // bits  0-7   receive error counter
static const uint32_t TREC_TEC_SHIFT = 8;             // bits  8-15  transmit error counter
static const uint32_t TREC_EWARN     = 1u << 16;      // either counter >= 96
static const uint32_t TREC_RXBP      = 1u << 19;      // receiver error passive
static const uint32_t TREC_TXBP      = 1u << 20;      // transmitter error passive
static const uint32_t TREC_TXBO      = 1u << 21;      // bus off
// C1BDIAG1 (0x03C) -- low 16 bits are BERRCNT, the rest are latching error types
static const uint32_t D1_NBIT0ERR = 1u << 16;
static const uint32_t D1_NBIT1ERR = 1u << 17;
static const uint32_t D1_NACKERR  = 1u << 18;
static const uint32_t D1_NFORMERR = 1u << 19;
static const uint32_t D1_NSTUFERR = 1u << 20;
static const uint32_t D1_NCRCERR  = 1u << 21;

bool canHealth(CanHealth &h) {
    h.valid = false;
    if (!s_running) return false;

    const uint32_t trec   = s_can.errorCounters();
    const uint32_t bdiag1 = s_can.diagInfos(1);

    h.rec       = (uint16_t)(trec & TREC_REC_MASK);
    h.tec       = (uint16_t)((trec >> TREC_TEC_SHIFT) & 0xFFu);
    h.busErrors = (uint16_t)(bdiag1 & 0xFFFFu);

    // Worst state first: bus-off is terminal, error-passive means the node has
    // stopped being trusted by itself, warning is the early one that matters.
    const char *st = "OK";
    if      (trec & TREC_TXBO)                    st = "BUSOFF";
    else if (trec & (TREC_RXBP | TREC_TXBP))      st = "PASSIVE";
    else if (trec & TREC_EWARN)                   st = "WARN";
    snprintf(h.state, sizeof(h.state), "%s", st);

    // Types, most diagnostic first. STUFF and CRC are the two that a loosening
    // connector produces; ACK and BIT errors would point at us transmitting,
    // which in listen-only would itself be the finding.
    h.errs[0] = 0;
    struct { uint32_t bit; const char *name; } kinds[] = {
        { D1_NSTUFERR, "STUFF" }, { D1_NCRCERR,  "CRC"  },
        { D1_NFORMERR, "FORM"  }, { D1_NACKERR,  "ACK"  },
        { D1_NBIT0ERR, "BIT0"  }, { D1_NBIT1ERR, "BIT1" },
    };
    for (const auto &k : kinds) {
        if (!(bdiag1 & k.bit)) continue;
        if (h.errs[0]) strncat(h.errs, ",", sizeof(h.errs) - strlen(h.errs) - 1);
        strncat(h.errs, k.name, sizeof(h.errs) - strlen(h.errs) - 1);
    }

    h.valid = true;
    return true;
}

#endif // CAN_BACKEND == CAN_BACKEND_MCP2518

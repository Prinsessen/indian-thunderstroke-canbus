/*
 * can_hal_twai.cpp — CAN HAL backend for the ESP32 native TWAI controller.
 * ---------------------------------------------------------------------------
 * Board: LilyGO TTGO T-CAN485 — ESP32 with a BUILT-IN CAN transceiver wired to
 * the ESP32's native TWAI peripheral (no SPI, no MCP, no crystal setting — the
 * ESP32 clocks the bit timing itself from its APB clock).
 *
 * Compiled only when config.h selects  CAN_BACKEND == CAN_BACKEND_TWAI  — the
 * whole file is #if-guarded, so an MCP2518 build pulls none of this in.
 *
 * This is a straight lift of the TWAI code that used to live in main.cpp, now
 * hidden behind the board-agnostic canInit/canReceive/canTransmit interface.
 */
#include <Arduino.h>
#include "config.h"
#include "can_hal.h"

#if CAN_BACKEND == CAN_BACKEND_TWAI

#include "driver/twai.h"
#include "driver/gpio.h"

// ======================= PIN CONFIG (LilyGO T-CAN485) =======================
// The T-CAN485 wires the ESP32's native TWAI (CAN) peripheral to an onboard
// transceiver. Overridable from config.h if a different board revision is used.
//
//  ⚡ CRITICAL: the onboard CAN/RS485 transceivers are powered by a DC-DC boost
//     that is OFF at reset. PIN_5V_EN must be driven HIGH or you read ZERO
//     frames (transceiver unpowered). This is the #1 gotcha on this board.
#ifndef PIN_5V_EN
#define PIN_5V_EN     16   // HIGH = enable onboard 5V boost feeding the transceivers (MANDATORY)
#endif
#ifndef CAN_TX
#define CAN_TX        27   // ESP32 TWAI TX  -> onboard CAN transceiver
#endif
#ifndef CAN_RX
#define CAN_RX        26   // ESP32 TWAI RX  <- onboard CAN transceiver
#endif
#ifndef CAN_SE_PIN
#define CAN_SE_PIN    23   // CAN transceiver Silent/Standby: LOW = normal, HIGH = standby
#endif

static bool s_running = false;

// Map a numeric bitrate to the matching ESP-IDF TWAI timing preset. The ESP32
// derives bit timing from its own APB clock, so every standard J1939 rate is
// available directly — no crystal dependency.
static twai_timing_config_t timingFor(uint32_t bitrate) {
    switch (bitrate) {
        case 500000: { twai_timing_config_t t = TWAI_TIMING_CONFIG_500KBITS(); return t; }
        case 125000: { twai_timing_config_t t = TWAI_TIMING_CONFIG_125KBITS(); return t; }
        case 100000: { twai_timing_config_t t = TWAI_TIMING_CONFIG_100KBITS(); return t; }
        case 50000:  { twai_timing_config_t t = TWAI_TIMING_CONFIG_50KBITS();  return t; }
        case 250000:
        default:     { twai_timing_config_t t = TWAI_TIMING_CONFIG_250KBITS(); return t; }
    }
}

void canHardwareInit() {
    // Power the onboard CAN/RS485 transceivers via the DC-DC boost. Without
    // PIN_5V_EN HIGH the transceiver is unpowered and you read ZERO frames.
    pinMode(PIN_5V_EN, OUTPUT);
    digitalWrite(PIN_5V_EN, HIGH);
    // Put the CAN transceiver in normal (non-silent) mode. LOW = normal.
    pinMode(CAN_SE_PIN, OUTPUT);
    digitalWrite(CAN_SE_PIN, LOW);
    delay(20);                            // let the boost + transceiver settle
}

bool canInit(uint32_t bitrate, bool listenOnly) {
    if (s_running) canStop();
    twai_mode_t mode = listenOnly ? TWAI_MODE_LISTEN_ONLY : TWAI_MODE_NORMAL;
    twai_general_config_t g =
        TWAI_GENERAL_CONFIG_DEFAULT((gpio_num_t)CAN_TX, (gpio_num_t)CAN_RX, mode);
    // Deep RX queue so a 250k J1939 flood doesn't overflow between polls.
    g.rx_queue_len = 64;
    g.tx_queue_len = 5;
    twai_timing_config_t t = timingFor(bitrate);
    twai_filter_config_t f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
    if (twai_driver_install(&g, &t, &f) != ESP_OK) return false;
    if (twai_start() != ESP_OK) { twai_driver_uninstall(); return false; }
    s_running = true;
    return true;
}

void canStop() {
    if (!s_running) return;
    twai_stop();
    twai_driver_uninstall();
    s_running = false;
}

bool canReceive(CanFrame &out, uint32_t timeoutMs) {
    twai_message_t msg;
    if (twai_receive(&msg, pdMS_TO_TICKS(timeoutMs)) != ESP_OK) return false;
    out.identifier       = msg.identifier;
    out.extd             = msg.extd;
    out.data_length_code = msg.data_length_code;
    for (int i = 0; i < 8; i++) out.data[i] = msg.data[i];
    return true;
}

bool canTransmit(const CanFrame &in) {
    if (!s_running) return false;
    twai_message_t msg = {};
    msg.identifier       = in.identifier;
    msg.extd             = in.extd ? 1 : 0;
    msg.data_length_code = in.data_length_code;
    for (int i = 0; i < 8; i++) msg.data[i] = in.data[i];
    return twai_transmit(&msg, pdMS_TO_TICKS(10)) == ESP_OK;
}

bool canRunning() { return s_running; }

#endif // CAN_BACKEND == CAN_BACKEND_TWAI

#!/usr/bin/env python3
"""
Brake separation live-diagnostic — decides ONCE whether the Indian bus exposes
FRONT and REAR brakes separately, or only a shared brake-light signal.

WHY
---
From the stationary ride capture the two candidate bits LOOKED separated:
  * FRONT candidate  PGN 65390 (0xFF6E) SA39 byte0 bit5 (0x20): idle 0xDF, 0xFF pressed
  * REAR  candidate  PGN 65265 (0xFEF1) SA0  byte3 bit4 (0x10): idle 0x0C, 0x1C pressed
BUT live, both brakes appeared on the "rear" item and the front reacted only
once — because 65390 transmits only ~0.15 Hz (too rare) and the 65265 bit may in
fact be the BRAKE LIGHT (either lever). This tool settles it.

HOW TO USE
----------
  cd /etc/openhab && ./.venv/bin/python3 -u indian-canbus/tools/brake_separation_test.py

Then, with ignition ON (engine can be off), do EXACTLY this, slowly:
  1. Hold ONLY the FRONT brake lever for ~6 seconds, then fully release. Wait 3 s.
  2. Hold ONLY the REAR brake pedal for ~6 seconds, then fully release. Wait 3 s.
  3. Ctrl-C.

READING THE RESULT
------------------
The tool prints a line every time EITHER candidate changes, showing both:
  FRONT(65390.b0.5)=PRESSED/rel   REAR(65265.b3.4)=PRESSED/rel
Verdict logic at exit:
  * If REAR bit went PRESSED during the FRONT-only hold  -> it is a shared
    BRAKE-LIGHT signal (any brake). We cannot split front/rear from it.
  * If REAR bit stayed released during the FRONT hold and only fired on the REAR
    hold -> the split is REAL and it was only a frequency problem.

READ-ONLY (MQTT subscribe). Never touches the bike.
"""
import json
import time
from datetime import datetime

import paho.mqtt.client as mqtt

import mqtt_config
BROKER, PORT = mqtt_config.BROKER, mqtt_config.PORT
USER, PASS = mqtt_config.USER, mqtt_config.PASS
BASE = "canbus/indian"

# Per-ID topics for the two candidates (avoids multi-source clobber).
ID_FRONT = "0x18FF6E27"   # PGN 65390 + SA 0x27=39
ID_REAR = "0x18FEF100"    # PGN 65265 + SA 0x00=0

state = {"front": None, "rear": None}
events = []  # (t, which, value)


def log(msg):
    print("%s %s" % (datetime.now().strftime("%H:%M:%S"), msg), flush=True)


def on_connect(c, u, f, rc):
    log("[connected rc=%s] hold FRONT 6s, release, then REAR 6s, release." % rc)
    c.subscribe(BASE + "/id/" + ID_FRONT)
    c.subscribe(BASE + "/id/" + ID_REAR)


def on_message(c, u, msg):
    try:
        d = json.loads(msg.payload)["data"]
    except Exception:
        return
    if msg.topic.endswith(ID_FRONT) and len(d) >= 1:
        v = "PRESSED" if (d[0] >> 5) & 1 else "rel"
        if v != state["front"]:
            state["front"] = v
            events.append((time.time(), "FRONT", v))
            log("FRONT(65390.b0.5)=%-7s  REAR(65265.b3.4)=%-7s  raw front byte0=0x%02X"
                % (v, state["rear"], d[0]))
    elif msg.topic.endswith(ID_REAR) and len(d) >= 4:
        v = "PRESSED" if (d[3] >> 4) & 1 else "rel"
        if v != state["rear"]:
            state["rear"] = v
            events.append((time.time(), "REAR", v))
            log("FRONT(65390.b0.5)=%-7s  REAR(65265.b3.4)=%-7s  raw rear byte3=0x%02X"
                % (state["front"], v, d[3]))


def main():
    c = mqtt.Client()
    c.username_pw_set(USER, PASS)
    c.on_connect = on_connect
    c.on_message = on_message
    c.connect(BROKER, PORT, 30)
    c.loop_start()
    try:
        while True:
            time.sleep(2)
    except KeyboardInterrupt:
        pass
    finally:
        c.loop_stop()
        # Verdict: did REAR go PRESSED while FRONT was PRESSED (front-only phase)?
        shared = False
        front_pressed_until = 0
        for t, which, v in events:
            if which == "FRONT" and v == "PRESSED":
                front_pressed_until = t + 2.0
            if which == "REAR" and v == "PRESSED" and t <= front_pressed_until:
                shared = True
        print("\n=== VERDICT ===")
        if not events:
            print("No brake events captured — check ignition ON and try again.")
        elif shared:
            print("REAR bit fired while FRONT was held -> SHARED BRAKE-LIGHT signal.")
            print("Front/rear CANNOT be split from 65265.b3.4; keep 65390 as the")
            print("only front-specific source (low-rate) + one 'Brake (any)' item.")
        else:
            print("REAR bit did NOT fire during the FRONT hold -> the split is REAL.")
            print("It was only a frequency problem (65390 transmits ~0.15 Hz).")
        print("Events:")
        for t, which, v in events:
            print("  %s %-5s %s" % (datetime.fromtimestamp(t).strftime("%H:%M:%S"), which, v))


if __name__ == "__main__":
    main()

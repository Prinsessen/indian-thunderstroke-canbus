#!/usr/bin/env python3
"""Live switch/button watcher for the Indian Polaris-proprietary PGNs.

*** SUPERSEDED 2026-09-05 -- use tools/probe_watch.sh instead. ***

Two reasons, both discovered the hard way:

  1. It subscribes to canbus/indian/pgn/# , which only PRODUCTION firmware never
     publishes -- those topics exist in DISCOVERY mode alone. The board has run
     production firmware for weeks, so this tool receives nothing at all and
     looks exactly like a bus with nothing on it.

  2. The per-PGN topic merges SOURCE ADDRESSES. PGN 65381 is sent by SA 0 (pure
     0xFF filler) and SA 39 (the real thing), and on one topic the last writer
     wins -- so the two flicker against each other and read as constant change.
     The firmware-side probe keys on PGN *and* source, which is why it works.

Kept because the WATCH list below is a decent record of which PGNs were thought
to carry switches, and because the bit-display idea was right even though the
plumbing was wrong.


Prints the FULL data of each candidate switch PGN every time it changes, with
byte[0] shown in binary so you can see which BIT a button toggles. Toggle one
control at a time and read the bit that flips. No fragile baseline.

USAGE (ignition ON; engine can be off):
    source /etc/openhab/.venv/bin/activate
    python3 -u /etc/openhab-firmware/indian-canbus/tools/switch_watch.py | tee /tmp/canbus_switch.log

    # background:
    nohup python3 -u .../switch_watch.py > /tmp/canbus_switch.log 2>&1 &

IMPORTANT: always run python with -u (unbuffered) or output stays stuck in the
buffer when redirected to a file.

Then, to read only real changes:
    grep 'changed=' /tmp/canbus_switch.log | tail -30

MQTT: broker and credentials come from src/config.h (gitignored) via mqtt_config.py
Topics: canbus/indian/pgn/<PGN>  (retained JSON {"data":[...]})
"""
import paho.mqtt.client as mqtt
import json, time
import mqtt_config

# Polaris-proprietary + body PGNs most likely to carry switch/lamp state.
WATCH = {"65381", "65382", "65386", "65387", "65388", "65390",
         "65393", "65394", "65276", "65089", "2304"}

last = {}


def ts():
    return time.strftime("%H:%M:%S", time.localtime()) + f".{int((time.time()%1)*1000):03d}"


def bits(v):
    return format(v & 0xFF, "08b")


def on_msg(c, u, m):
    pgn = m.topic.split("/")[-1]
    if pgn not in WATCH:
        return
    try:
        data = json.loads(m.payload.decode("utf-8", "replace")).get("data")
    except Exception:
        return
    if not isinstance(data, list):
        return
    prev = last.get(pgn)
    last[pgn] = data
    if prev == data:
        return
    changed = [i for i in range(min(len(prev or []), len(data)))
               if (prev or [])[i] != data[i]]
    ch = ""
    if prev is not None and changed:
        ch = "  changed=" + ",".join(f"b{i}:{prev[i]}->{data[i]}" for i in changed)
    print(f"[{ts()}] PGN {pgn:>6} {data}  b0={bits(data[0])}{ch}")


def main():
    c = mqtt.Client()
    c.username_pw_set(mqtt_config.USER, mqtt_config.PASS)
    c.on_connect = lambda c, u, f, rc: c.subscribe("canbus/indian/pgn/#")
    c.on_message = on_msg
    c.connect(mqtt_config.BROKER, mqtt_config.PORT, 30)
    print("Watching switch PGNs (full bytes on every change). Toggle one control at a time.")
    try:
        c.loop_forever()
    except KeyboardInterrupt:
        print("\nstopped")


if __name__ == "__main__":
    main()

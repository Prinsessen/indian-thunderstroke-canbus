#!/usr/bin/env python3
"""
TPMS ride capture — the definitive test for whether tyre-pressure data ever
reaches our CAN sniffer on the 2017 Indian Springfield.

WHY THIS EXISTS
---------------
TPMS is the PRIMARY goal of the whole sniffer project. From a parked bike we
have proven everything we can from the desk:
  * The firmware captures EVERY frame (TWAI ACCEPT_ALL) and publishes every
    ID/PGN — nothing is filtered out, so TPMS cannot be hidden by us.
  * PGN 65268 (0xFEF4 "Tire Condition") IS present on our bus, broadcast by the
    cluster (source address 39). Its layout: data[0]=tyre location
    (0=front, 16=rear), data[1]=pressure @ 4 kPa/bit.
  * BUT data[1] has ALWAYS been 0xFF (J1939 "no valid data") — live and in the
    full history. No other PGN carries a plausible pressure pair.
  * While parked the bus goes fully asleep (0 frames), so pressure can only ever
    appear once the wheels spin and the sensors wake.

The ONLY way to resolve it is to RIDE. This tool captures everything needed to
give a one-and-done answer, so we never have to guess again.

WHAT IT LOGS
------------
  * EVERY PGN 65268 frame: timestamp, source address, all 8 bytes, and the
    decoded pressure the instant data[1] != 0xFF (front & rear).
  * Any NEW source address, PGN or 29-bit ID that appears during the ride but
    was never seen parked — in case real wheel sensors transmit on their OWN
    IDs only when awake (separate from the cluster's placeholder frame).
  * GENERAL byte-activity harvest: the first time ANY byte in ANY known PGN moves
    outside its parked baseline range it is flagged ("ACTIVE ..."), and a summary
    at the end lists every byte that became active. This is how a single ride
    surfaces OTHER still-unmapped signals — front/rear brake, cruise control,
    hazard lights, kill switch, neutral / side-stand — not just TPMS. Known
    rolling-counter/CRC noise bytes are excluded so real signals stand out.
  * A FULL raw record of every frame to a timestamped logfile (screen shows only
    the highlights; the file keeps everything for later offline analysis).

  Tip: operate ONE control at a time during the ride (e.g. hold the front brake
  for a few seconds, then the rear, then cruise) and note the time — the
  "ACTIVE pgn byte[i]" line at that moment tells you exactly which bit it is.

HOW TO USE
----------
  1. Start this BEFORE the ride. Copy-paste this ONE line (no `source` needed,
     uses the venv Python directly so the shell can't get stuck):

        cd /etc/openhab && ./.venv/bin/python3 -u indian-canbus/tools/tpms_ride_capture.py

     (If the shell shows a `>` prompt it's stuck on a stray quote — press Ctrl-C
      once, then paste the line again. Don't split it across two lines.)
  2. Turn the ignition ON, then RIDE at least ~1-2 minutes above walking pace
     (wheel rotation is what wakes battery TPMS sensors). A few stop/start
     cycles help.
  3. Watch the terminal:
        * "*** PRESSURE! ..."  -> TPMS DATA ARRIVES. Note front/rear PSI.
                                  Verify CanBus_TyreFront/Rear then finalise scale.
        * "NEW ..." lines      -> a previously-unseen SA/PGN/ID woke up; that may
                                  be the real sensor — note it for decoding.
        * Only "65268 ... press=none" for the whole ride -> pressure never comes
          to our bus. Conclusion: either the sensor batteries are dead (2017 bike
          = ~9 yr, past typical TPMS battery life) OR TPMS lives on a separate
          bus we are not tapped into. Either way: move the Tyre items to
          "Confirmed NOT on the bus" and drop them.
  4. Ctrl-C to stop. The logfile path is printed at exit.

This is READ-ONLY (MQTT subscribe). It never touches the bike.
"""
import json
import os
import sys
import time
from datetime import datetime

import paho.mqtt.client as mqtt

import mqtt_config
BROKER = mqtt_config.BROKER
PORT = 1883
USER = mqtt_config.USER
PASS = mqtt_config.PASS
BASE = "canbus/indian"

# 4 kPa/bit -> PSI. Matches transform/canbus_tpms.js.
PSI_PER_BIT = 4.0 * 0.145038  # 0.580152

LOGDIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "captures")
os.makedirs(LOGDIR, exist_ok=True)
LOGPATH = os.path.join(
    LOGDIR, "tpms_ride_%s.log" % datetime.now().strftime("%Y%m%d_%H%M%S")
)

# Baseline of what we already see while PARKED (so we can flag anything NEW that
# only appears once moving). Filled during the first BASELINE_S seconds.
BASELINE_S = 8
baseline_sa = set()
baseline_pgn = set()
baseline_id = set()
seen_sa = set()
seen_pgn = set()
seen_id = set()
baseline_done_at = None

pressure_hits = 0
frame_count = 0
last_65268 = {}  # sa -> last data list, to only log real changes

# --- General byte-activity harvest (find brake/cruise/hazard/etc in one ride) --
# Per PGN we track each byte's value range seen during the parked BASELINE window
# vs during the ride. The first time a byte moves OUTSIDE its baseline range we
# shout once (candidate new signal), then keep updating its range silently. A
# summary at exit lists every byte that became active during the ride.
byte_base = {}   # pgn -> {i: (min,max)} seen during baseline
byte_ride = {}   # pgn -> {i: (min,max)} seen during ride
byte_flagged = set()  # (pgn,i) already announced, so we log each once
ride_prev = {}   # (pgn,i) -> last value seen during ride (to detect real toggles)
latest_tyre = {}  # sa -> "front 36.0 PSI" / "rear none" for the status line

# Rolling counters / CRC / analog drift bytes that change constantly and are NOT
# switches — excluded from the activation shout so real signals aren't buried.
# (Documented in REVERSE_ENGINEERING.md "Known bus noise".) 65268 byte0 is the
# tyre-location code, handled specially, so skip it here too.
NOISE = {
    (65265, 6), (65265, 7),   # rolling counter / CRC
    (65266, 6),               # heartbeat 15<->16
    (2304, 0),                # analog drift
    (65276, 1),               # fuel-sender analog drift
    (65268, 0),               # tyre location code (handled in TPMS block)
}

logf = open(LOGPATH, "a", buffering=1)


def log(line):
    ts = datetime.now().strftime("%H:%M:%S")
    msg = "%s %s" % (ts, line)
    print(msg, flush=True)
    logf.write(msg + "\n")


def filelog(line):
    """Write to the logfile only (full raw record), not the screen."""
    ts = datetime.now().strftime("%H:%M:%S")
    logf.write("%s %s\n" % (ts, line))


def on_connect(c, u, flags, rc):
    log("[connected rc=%s] subscribing to full bus; logfile=%s" % (rc, LOGPATH))
    # Subscribe to the live change stream + per-ID/PGN + the TPMS split topics.
    c.subscribe(BASE + "/frame")
    c.subscribe(BASE + "/id/#")
    c.subscribe(BASE + "/pgn/#")
    c.subscribe(BASE + "/tpms/#")


def on_message(c, u, msg):
    global frame_count, pressure_hits, baseline_done_at
    try:
        d = json.loads(msg.payload)
    except Exception:
        return
    if not isinstance(d, dict) or "data" not in d:
        return
    b = d["data"]
    pgn = d.get("pgn")
    sa = d.get("sa")
    idv = d.get("id")
    frame_count += 1

    now = time.time()
    in_baseline = baseline_done_at is None or now < baseline_done_at

    # Full raw record to the logfile only (so nothing from the ride is ever lost;
    # screen stays readable with just the highlights below). Dedup identical
    # consecutive frames is already done firmware-side (publishes on change).
    filelog("RAW pgn=%s sa=%s id=%s data=%s" % (pgn, sa, idv, b))

    # Track novelty.
    for val, sset, bset, label in (
        (sa, seen_sa, baseline_sa, "SA"),
        (pgn, seen_pgn, baseline_pgn, "PGN"),
        (idv, seen_id, baseline_id, "ID"),
    ):
        if val is None:
            continue
        if in_baseline:
            bset.add(val)
        seen_add = val not in sset
        sset.add(val)
        if seen_add and not in_baseline and val not in bset:
            log("NEW %s appeared while riding: %s  (pgn=%s sa=%s id=%s data=%s)"
                % (label, val, pgn, sa, idv, b))

    # General byte-activity harvest on 29-bit J1939 PGN frames: which bytes move
    # during the ride vs parked. This is what surfaces brake / cruise / hazard /
    # kill-switch / neutral / side-stand etc. in a single ride.
    if pgn is not None:
        if in_baseline:
            bb = byte_base.setdefault(pgn, {})
            for i, v in enumerate(b):
                lo, hi = bb.get(i, (v, v))
                bb[i] = (min(lo, v), max(hi, v))
        else:
            rr = byte_ride.setdefault(pgn, {})
            bb = byte_base.get(pgn, {})
            for i, v in enumerate(b):
                lo, hi = rr.get(i, (v, v))
                rr[i] = (min(lo, v), max(hi, v))
                if (pgn, i) in NOISE:
                    ride_prev[(pgn, i)] = v
                    continue
                blo, bhi = bb.get(i, (None, None))
                prev = ride_prev.get((pgn, i))
                ride_prev[(pgn, i)] = v
                # Fire only on a REAL transition during the ride (value changed
                # from its previous ride value) AND outside the parked range.
                # This ignores the initial burst of bytes that are merely
                # statically different once moving, so genuine switch toggles
                # (brake, cruise, hazard, kill, neutral, side-stand) stand out.
                if (pgn, i) in byte_flagged:
                    continue
                if prev is not None and prev != v and blo is not None and not (blo <= v <= bhi):
                    byte_flagged.add((pgn, i))
                    log("ACTIVE pgn=%s byte[%d]: parked %d..%d, toggled %d\u2192%d  (data=%s)"
                        % (pgn, i, blo, bhi, prev, v, b))

    # Focus: PGN 65268 tyre condition.
    if pgn == 65268 and len(b) >= 2:
        prev = last_65268.get(sa)
        if prev != b:
            last_65268[sa] = list(b)
            loc = b[0]
            pos = "front" if loc in (0x00, 0x11) else ("rear" if loc in (0x10, 0x21, 0x01) else "loc0x%02X" % loc)
            if b[1] == 0xFF:
                latest_tyre[sa] = "%s none" % pos
                log("65268 sa=%s %-5s data=%s  press=none(0xFF)" % (sa, pos, b))
            else:
                pressure_hits += 1
                psi = b[1] * PSI_PER_BIT
                latest_tyre[sa] = "%s %.1f PSI" % (pos, psi)
                log("*** PRESSURE! 65268 sa=%s %-5s data=%s  byte[1]=%d -> %.1f PSI ***"
                    % (sa, pos, b, b[1], psi))
                # Also surface temperature if present (SPN 242, bytes 3-4 LE, 1/32 K - 273).
                if len(b) >= 5 and b[3] != 0xFF and b[4] != 0xFF:
                    raw = b[3] | (b[4] << 8)
                    temp = raw / 32.0 - 273.0
                    log("    (temp candidate bytes3-4: raw=%d -> %.1f C)" % (raw, temp))


def main():
    global baseline_done_at
    log("=== TPMS ride capture start ===")
    log("Ride >=1-2 min above walking pace. Ctrl-C to stop.")
    c = mqtt.Client()
    c.username_pw_set(USER, PASS)
    c.on_connect = on_connect
    c.on_message = on_message
    c.connect(BROKER, PORT, 30)
    baseline_done_at = time.time() + BASELINE_S
    c.loop_start()
    try:
        while True:
            time.sleep(5)
            if baseline_done_at and time.time() > baseline_done_at:
                tyre = "  ".join("%s" % v for v in latest_tyre.values()) or "no 65268 yet"
                log("[status] frames=%d  TPMS[%s]  pressure_hits=%d  active_bytes=%d"
                    % (frame_count, tyre, pressure_hits, len(byte_flagged)))
    except KeyboardInterrupt:
        pass
    finally:
        c.loop_stop()
        log("=== stop: frames=%d pressure_hits=%d ===" % (frame_count, pressure_hits))

        # Summary of every byte that became active during the ride (candidate
        # switches/signals to decode next). Excludes known-noise bytes.
        actives = []
        for pgn in sorted(byte_ride):
            bb = byte_base.get(pgn, {})
            for i in sorted(byte_ride[pgn]):
                if (pgn, i) in NOISE:
                    continue
                rlo, rhi = byte_ride[pgn][i]
                blo, bhi = bb.get(i, (None, None))
                if blo is None:
                    # byte only seen while riding (PGN absent when parked)
                    if rlo != rhi:
                        actives.append((pgn, i, "ride-only", rlo, rhi))
                elif rlo < blo or rhi > bhi:
                    actives.append((pgn, i, "%d..%d" % (blo, bhi), rlo, rhi))
        if actives:
            log("--- BYTES THAT BECAME ACTIVE DURING THE RIDE (decode candidates) ---")
            for pgn, i, base, rlo, rhi in actives:
                log("    pgn=%-6s byte[%d]  parked[%s] → ride[%d..%d]"
                    % (pgn, i, base, rlo, rhi))
            log("    (cross-check against the control you operated at that moment)")
        else:
            log("--- No byte moved outside its parked range (bus may have stayed idle) ---")

        if pressure_hits:
            log("RESULT: TPMS pressure DID arrive on our bus. Finalise scale/items.")
        else:
            log("RESULT: NO pressure in this session. If the ride was long enough,")
            log("        TPMS is NOT usable on our bus (dead sensor batteries or")
            log("        separate bus). Move Tyre items to 'NOT on the bus'.")
        log("logfile: %s" % LOGPATH)
        logf.close()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Worst-case size of the BLE state notification.

A GATT notification at ATT_MTU 517 carries 514 bytes and cannot fragment. Past
that the stack truncates in silence and the app receives JSON cut off mid-string:
unparseable, with nothing anywhere saying why. So the budget has to be known
before a ride, not discovered on one.

The field list is READ OUT OF main.cpp rather than typed here, because a hand
kept list is exactly the kind that goes stale the week someone adds a field.
Worst-case value widths are the one thing this file supplies, and a short key
with no entry is a hard error -- add the field, and the tool makes you say how
wide it can get.

    python3 tools/ble_budget.py            # the table
    python3 tools/ble_budget.py --verbose  # every field, widest first
"""
import re, sys, pathlib

CEILING = 514

# Widest each value can render, in characters, as JSON writes it.
#
# AUDITED 2026-09-06 against src/main.cpp and the buffer sizes in vehstate.h,
# after "wh" was found at twice its real width. Three were wrong:
#
#   br  9 -> 10    "RELEASED" is eight characters. Under-stated, which is the
#                  direction that matters: the model said the payload fitted when
#                  it was a byte larger than claimed.
#   si  22 -> 113  swid[112], and the real record read off the bike is about 110
#                  characters of '|'-joined identity. MQTT-only, so the radio was
#                  never at risk, but the MQTT figure was nonsense.
#   dr  40 -> 21   dm1Raw[20]. Over-stated by nineteen. MQTT-only.
#
# The rest check out against their literals or their buffers. Two notes for
# whoever audits next: "ln" looks like a string in a naive scan because the line
# carries a comment quoting one, and it is an integer; and "g" is 3 here for the
# single character the gear decode actually emits, while gear[3] would allow two.
W = {
    "r": 4, "th": 3, "g": 3, "ot": 3, "sp": 5, "fl": 3, "od": 6, "tp": 5,
    "fe": 5, "bv": 4, "am": 5, "tf": 4, "tr": 4, "tft": 5, "trt": 5,
    "br": 10, "cc": 9, "cl": 8, "ce": 5, "cs": 9, "hz": 5, "se": 11,
    "hl": 6, "il": 5, "ir": 5, "gr": 1, "sf": 5, "fr": 4, "fi": 5,
    # "wh" was 24 until 2026-09-06 and had never been checked against the source.
    # wheelCheck() returns only "OK", "REAR LOST" and "FRONT LOST" -- twelve bytes
    # quoted, exactly half what the model claimed. Over-stating is the safe
    # direction to be wrong in and it is still wrong: it said the payload was at
    # its ceiling with two faults when it had twelve bytes to spare, and that
    # nearly bought an app change nobody needed.
    "wh": 12, "wf": 5, "wr": 5, "gg": 3, "ln": 3, "st": 9, "ig": 5,   # st: "UPRIGHT"
    "gl": 5, "gR": 5, "sk": 6, "fw": 15, "sd": 6, "ks": 6,   # "DOWN"/"UP", "RUN"/"STOP"
    # Ages in seconds. Both are MQTT-only, so they cost the radio nothing -- but
    # the model still needs a width or the MQTT worst case is understated. Six
    # digits is eleven days, which a board with deep sleep switched off can reach
    # while the machine sits over a winter.
    "ia": 6, "ta": 6,
    # Range to empty, km. A single byte on the bus, so three digits is the
    # true ceiling and not a guess. ON the radio since 2026-09-08, paid for
    # by moving fuelRate off it: "fr" cost 11 bytes and this costs 9, so the
    # trade came out two bytes ahead rather than level.
    "rg": 3,
    # MQTT-only; never on the radio.
    "vn": 19, "si": 113, "dr": 21, "sb": 10,
}
# The two DM1 encodings, measured per fault rather than as one lump.
DM1_LAMPS_LONG   = len(" | MIL:off Stop:off Warn:off Prot:off")
DM1_CLEAN_LONG   = len("No active DTC")
DM1_FAULT_LONG   = len("SPN 520250 FMI 8 (x2); ")
DM1_LAMPS_SHORT  = len("|15")
DM1_FAULT_SHORT  = len("520250:8:2,")

src = (pathlib.Path(__file__).parent.parent / "src/main.cpp").read_text()
body = src[src.index("size_t buildStateJson("):]
fields, mqtt_only = [], set()
for line in body.splitlines():
    # Skip comments. One describing the parser was enough to crash it on
    # 2026-09-06, because it quoted the very text being searched for.
    if line.lstrip().startswith("//"):
        continue
    # A gate wrapped onto its own line would hide from the check below and the
    # field would be counted against the radio. Refuse to guess: this tool exists
    # to produce a number that can be trusted, so it stops rather than quietly
    # returning the wrong one. Happened 2026-09-06 with startButton.
    if line.strip().startswith("if (includeVin") and "doc[K(" not in line:
        sys.exit("tools/ble_budget.py: an includeVin gate is on its own line:\n"
                 "    %s\n"
                 "  Put the gate and the field on ONE line, or this tool cannot\n"
                 "  tell an MQTT-only field from one on the radio." % line.strip())
    if "doc[K(" not in line:
        continue
    long_k, short_k = re.search(r'K\("([^"]+)"\s*,\s*"([^"]+)"\)', line).groups()
    if short_k == "d1":
        continue                      # sized separately below
    if short_k not in W:
        sys.exit(f"tools/ble_budget.py: no worst-case width for '{short_k}' "
                 f"({long_k}) -- add it to W and re-run")
    if "includeVin &&" in line:
        mqtt_only.add(short_k)
    fields.append((long_k, short_k))

def total(keys, short_keys, compact_dm1, faults, with_fw):
    """Key length and fault encoding are SEPARATE axes.

    An earlier version tied them together, so its "readable" column was really
    "long keys", a combination nothing has run since 2026-09-04. The bike was
    meanwhile running short keys with readable faults -- the one case the table
    did not show. Keep them apart.
    """
    n = 2                                          # the braces
    for long_k, k in keys:
        if k == "fw" and not with_fw:
            continue
        n += len(k if short_keys else long_k) + 3 + W[k] + 1   # "key":value,
    n += len("d1" if short_keys else "dm1") + 3 + 2             # the key, quotes
    if compact_dm1:
        n += DM1_LAMPS_SHORT + faults * DM1_FAULT_SHORT
    else:
        n += DM1_LAMPS_LONG + ((faults * DM1_FAULT_LONG) if faults else DM1_CLEAN_LONG)
    return n

ble = [f for f in fields if f[1] not in mqtt_only]
print(f"{len(fields)} fields emitted, {len(ble)} of them on BLE. Ceiling {CEILING}.")
print("All three columns use the short keys; only the fault encoding differs.\n")
print(f"{'faults':>6} {'readable (was shipping)':>24} {'compact, fw always':>19} {'compact, fw rarely':>19}")
for f in (0, 1, 2, 4):
    a = total(ble, True, False, f, True)
    b = total(ble, True, True,  f, True)
    c = total(ble, True, True,  f, False)
    m = lambda v: f"{v:4d} {'OVER' if v > CEILING else ' ok ':>4}"
    print(f"{f:>6} {m(a):>24} {m(b):>19} {m(c):>19}")
print(f"\nLong keys, readable, no fault: {total(ble, False, False, 0, True)} "
      f"-- why the radio moved to short keys on 2026-09-04.")
print(f"MQTT worst case (long keys, VIN, 4 faults): {total(fields, False, False, 4, True)} bytes, no limit")

if "--check" in sys.argv:
    # Calibration against a payload captured off the bike. The model must come
    # out ABOVE the real one for the same fields -- it claims worst case. Below
    # means a width in W is too small, which is the failure that matters, since
    # it would under-report the risk of a silent truncation.
    import json
    real = json.load(open(sys.argv[sys.argv.index("--check") + 1]))
    raw = len(json.dumps(real, separators=(",", ":")))
    present = [f for f in ble if f[1] in real]
    pred = total(present, True, False, 0, "fw" in real)
    print(f"\nCalibration: {len(present)} of {len(ble)} fields present.")
    print(f"  measured  {raw}")
    print(f"  modelled  {pred}  ({pred - raw:+d})")
    if pred < raw:
        sys.exit("  MODEL TOO LOW -- a width in W is wrong")
    absent = [k for _, k in ble if k not in real]
    print(f"  absent: {' '.join(absent)}")
    print(f"  with those present and faults compacted: "
          f"{total(ble, True, True, 0, True)} worst case")

if "--verbose" in sys.argv:
    print("\nWidest fields on the radio:")
    for long_k, k in sorted(ble, key=lambda f: -(len(f[1]) + W[f[1]])):
        print(f"  {k:<4} {len(k)+3+W[k]:>3}  ({long_k})")

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
W = {
    "r": 4, "th": 3, "g": 3, "ot": 3, "sp": 5, "fl": 3, "od": 6, "tp": 5,
    "fe": 5, "bv": 4, "am": 5, "tf": 4, "tr": 4, "tft": 5, "trt": 5,
    "br": 9, "cc": 9, "cl": 8, "ce": 5, "cs": 9, "hz": 5, "se": 11,
    "hl": 6, "il": 5, "ir": 5, "gr": 1, "sf": 5, "fr": 5, "fi": 5,
    "wh": 24, "wf": 5, "wr": 5, "gg": 3, "ln": 3, "st": 6, "ig": 5,
    "gl": 5, "gR": 5, "sk": 6, "fw": 15,
    # MQTT-only; never on the radio.
    "vn": 19, "si": 22, "dr": 40,
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

def total(keys, short, faults, with_fw):
    n = 2                                          # the braces
    for long_k, k in keys:
        if k == "fw" and not with_fw:
            continue
        n += len(k if short else long_k) + 3 + W[k] + 1   # "key":value,
    if short:
        n += len("d1") + 3 + 2 + DM1_LAMPS_SHORT + faults * DM1_FAULT_SHORT + 1
    else:
        n += len("dm1") + 3 + 2 + DM1_LAMPS_LONG + 1
        n += (faults * DM1_FAULT_LONG) if faults else DM1_CLEAN_LONG
    return n - 1                                   # no comma after the last

ble = [f for f in fields if f[1] not in mqtt_only]
print(f"{len(fields)} fields emitted, {len(ble)} of them on BLE. Ceiling {CEILING}.\n")
print(f"{'faults':>6} {'readable, fw always':>20} {'compact, fw always':>19} {'compact, fw rarely':>19}")
for f in (0, 1, 2, 4):
    a = total(ble, False, f, True)
    b = total(ble, True,  f, True)
    c = total(ble, True,  f, False)
    m = lambda v: f"{v:4d} {'OVER' if v > CEILING else ' ok ':>4}"
    print(f"{f:>6} {m(a):>20} {m(b):>19} {m(c):>19}")
print(f"\nMQTT worst case (long keys, VIN, 4 faults): {total(fields, False, 4, True)} bytes, no limit")

if "--verbose" in sys.argv:
    print("\nWidest fields on the radio:")
    for long_k, k in sorted(ble, key=lambda f: -(len(f[1]) + W[f[1]])):
        print(f"  {k:<4} {len(k)+3+W[k]:>3}  ({long_k})")

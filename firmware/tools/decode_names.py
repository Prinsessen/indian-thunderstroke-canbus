#!/usr/bin/env python3
"""Decode the J1939 NAME out of every address claim in the captures.

Every module announces itself on PGN 60928 with a 64-bit NAME that says what it
IS -- manufacturer, function, instance -- rather than what it happens to send.
Those claims sat in the captures from 2026-08-15 for three weeks while the
module map was assembled by guessing from traffic instead.

Run:  python3 tools/decode_names.py
"""
import glob
import os
import re

# SAE J1939-81 function codes, Industry Group 0 / Vehicle System 0.
# Only the ones this bus uses are worth listing; the rest would be padding.
#
# Confidence note: 0 and 19 are cross-checked against what those modules
# actually send -- SA 0 carries engine data and SA 23 carries the odometer,
# trip and ambient, which is an instrument cluster by any reading. That
# agreement is what makes the table trustworthy for 30 as well.
FUNCTION = {
    0:  "Engine",
    3:  "Transmission",
    9:  "Brakes, system controller",
    10: "Brakes, steer axle",
    11: "Brakes, drive axle",
    14: "Cruise control",
    19: "Instrument cluster",
    20: "Trip recorder",
    23: "Vehicle navigation",
    24: "Vehicle security",
    26: "Body controller",
    30: "Management computer",
}

RX = re.compile(r'RAW pgn=60928 sa=(\d+) id=\S+ data=\[([\d, ]+)\]')


def decode(data):
    n = int.from_bytes(bytes(data), 'little')
    return {
        'identity':     n & 0x1FFFFF,
        'manufacturer': (n >> 21) & 0x7FF,
        'ecu_instance': (n >> 32) & 0x07,
        'fn_instance':  (n >> 35) & 0x1F,
        'function':     (n >> 40) & 0xFF,
        'vehicle_sys':  (n >> 49) & 0x7F,
        'vsys_inst':    (n >> 56) & 0x0F,
        'industry':     (n >> 60) & 0x07,
        'arbitrary':    (n >> 63) & 0x01,
    }


def main():
    here = os.path.dirname(__file__)
    claims, counts = {}, {}
    for path in sorted(glob.glob(os.path.join(here, '..', 'captures', '*.log'))):
        for line in open(path, errors='ignore'):
            m = RX.search(line)
            if not m:
                continue
            sa = int(m.group(1))
            data = [int(x) for x in m.group(2).split(',')]
            claims.setdefault(sa, tuple(data))
            counts[sa] = counts.get(sa, 0) + 1

    print("%-4s %-6s %-6s %-26s %-5s %-4s %s" %
          ("SA", "claims", "mfr", "function", "vsys", "ind", "identity"))
    for sa in sorted(claims):
        f = decode(list(claims[sa]))
        name = FUNCTION.get(f['function'], "** not in the standard table **")
        print("%-4d %-6d %-6d %-26s %-5d %-4d %d" % (
            sa, counts[sa], f['manufacturer'], name,
            f['vehicle_sys'], f['industry'], f['identity']))


if __name__ == '__main__':
    main()

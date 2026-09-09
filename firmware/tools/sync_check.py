#!/usr/bin/env python3
"""Do the firmware and the app still agree about the state payload?

They can drift apart in three ways and ALL THREE ARE SILENT. Nothing crashes,
nothing logs, no gauge turns red -- a field just quietly stops meaning anything:

  1. The firmware sends a key the app does not read.   New data, invisible.
  2. The app reads a key the firmware no longer sends. A gauge goes blank.
  3. The app reads a key that is MQTT-only.            Always null, for ever.

Case 2 nearly shipped on 2026-09-08: fuelRate was moved off BLE to make room for
range, and had the app not been changed in the same commit its FUEL RATE gauge
would have read "--" with no indication why. Case 1 is how `range` sat on the
wire for a day before the app could show it.

This reads both sides and diffs them. Run it before every OTA that touches
buildStateJson() or BikeState.kt.

    python3 tools/sync_check.py            # exits non-zero on any mismatch

The firmware is the source of truth: it is what is actually on the wire.
"""
import re
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "src" / "main.cpp"
# The app lives in the openHAB tree, not in this repo -- one of the two places
# the project is split across. Fall back gracefully rather than crashing, so the
# tool is still useful on a machine that only has the firmware checked out.
APP = ROOT.parent.parent / "openhab" / "source-code" / "indian-canbus-app" \
      / "app" / "src" / "main" / "java" / "dk" / "agesen" / "springfield" / "BikeState.kt"


def firmware_keys():
    """{short_key: (long_key, mqtt_only)} for every field buildStateJson emits."""
    out = {}
    for line in MAIN.read_text().splitlines():
        stripped = line.strip()
        if stripped.startswith("//") or "doc[K(" not in line:
            continue
        m = re.search(r'K\("([^"]+)"\s*,\s*"([^"]+)"\)', line)
        if not m:
            continue
        long_k, short_k = m.groups()
        out[short_k] = (long_k, "includeVin &&" in line)
    return out


def app_keys():
    """Every short key BikeState.kt pulls out of the JSON."""
    if not APP.exists():
        return None
    return set(re.findall(r'\b[dis]\("([A-Za-z0-9]+)"\)', APP.read_text()))


def main():
    fw = firmware_keys()
    app = app_keys()
    if app is None:
        print(f"sync_check: app not found at {APP}")
        print("  Nothing to compare. This is not a failure -- the app tree is")
        print("  only present on the server that builds it.")
        return 0

    ble = {k for k, (_, mqtt_only) in fw.items() if not mqtt_only}
    mqtt_only = {k for k, (_, mo) in fw.items() if mo}

    print(f"firmware emits {len(fw)} fields, {len(ble)} of them over BLE")
    print(f"app reads {len(app)}")
    print()

    problems = 0

    unread = sorted(ble - app)
    if unread:
        problems += len(unread)
        print("ON THE WIRE BUT THE APP NEVER READS IT -- new data, invisible:")
        for k in unread:
            print(f"    {k:6s}  ({fw[k][0]})")
        print()

    dead = sorted(app - set(fw))
    if dead:
        problems += len(dead)
        print("THE APP READS IT BUT NOTHING SENDS IT -- the field is blank:")
        for k in dead:
            print(f"    {k}")
        print()

    wrong_transport = sorted(app & mqtt_only)
    if wrong_transport:
        problems += len(wrong_transport)
        print("THE APP READS IT BUT IT IS MQTT-ONLY -- always null over BLE:")
        for k in wrong_transport:
            print(f"    {k:6s}  ({fw[k][0]})")
        print()

    if problems == 0:
        print("In sync. Every BLE field is read, and every field the app reads is sent.")
        return 0
    print(f"{problems} mismatch(es). The firmware is the source of truth: it is")
    print("what is actually on the wire, so the app is what needs changing --")
    print("unless a field was dropped by mistake.")
    return 1


if __name__ == "__main__":
    sys.exit(main())

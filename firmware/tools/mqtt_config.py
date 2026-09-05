"""Read the MQTT credentials from src/config.h instead of hard-coding them.

config.h is gitignored; these tools are not. The password sat in clear text in
four tracked files until 2026-09-05, which is four copies to forget about the
next time it changes, and one of them was a markdown file anybody could read
without running anything.

Mirrors what tools/probe_watch.sh already did on the shell side.
"""
import os
import re

_CONFIG = os.path.join(os.path.dirname(__file__), '..', 'src', 'config.h')


def _define(name, fallback=None):
    try:
        with open(_CONFIG, errors='ignore') as fh:
            m = re.search(r'^#define\s+%s\s+"([^"]*)"' % name, fh.read(), re.M)
            if m:
                return m.group(1)
    except OSError:
        pass
    if fallback is None:
        raise SystemExit(
            "Could not read %s from %s.\n"
            "That file is gitignored, so a fresh clone has to copy it across by "
            "hand -- see BUILD-SETUP.md." % (name, os.path.normpath(_CONFIG)))
    return fallback


BROKER = _define('MQTT_BROKER', 'mqtt.example.com')
USER = _define('MQTT_USERNAME')
PASS = _define('MQTT_PASSWORD')
BASE = _define('MQTT_BASE_TOPIC', 'canbus/springfield')
PORT = 1883

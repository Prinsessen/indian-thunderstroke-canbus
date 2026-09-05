#!/bin/bash
# Watch the temporary discovery probe.  >>> REMOVE WITH PROBE_CHANGES <<<
#
#   canbus/springfield/probe/2304 -> "<raw> <signed vs 127>"  (throttle/lean test)
#   canbus/springfield/probe      -> "pgn=N sa=N bI XX->YY (dec)"  (heated grips etc.)
#
#   canbus/springfield/probe/cruise -> "b4=XX 595=N b5=XX N km/h"  (cruise test)
#
# probe and probe/2304 only publish while the bike is STATIONARY. probe/cruise
# is the exception and reports at ANY speed -- cruise control cannot be engaged
# below about 40 km/h, so a stationary-only probe could never test it.
# The other two only publish while the bike is STATIONARY. Ignition on, engine may be off.
# Hold one control for ~8 seconds, pause 5, then the next. Tapping mixes bits.
set -u
HOST=$(sed -n 's/^#define MQTT_BROKER *"\(.*\)".*/\1/p' "$(dirname "$0")/../src/config.h")
USER=$(sed -n 's/^#define MQTT_USERNAME *"\(.*\)".*/\1/p' "$(dirname "$0")/../src/config.h")
PASS=$(sed -n 's/^#define MQTT_PASSWORD *"\(.*\)".*/\1/p' "$(dirname "$0")/../src/config.h")
exec mosquitto_sub -h "${HOST:-mqtt.example.com}" -u "$USER" -P "$PASS" -v \
     -t 'canbus/springfield/probe' -t 'canbus/springfield/probe/2304' \
     -t 'canbus/springfield/probe/cruise' \
  | while read -r topic rest; do printf '%s  %-28s %s\n' "$(date +%H:%M:%S)" "${topic##*/}" "$rest"; done

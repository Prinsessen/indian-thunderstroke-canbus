#!/bin/bash
# Refuse to publish house infrastructure. Run before every push.
#
# WHY THIS IS A SCRIPT AND NOT A REMINDER
# ---------------------------------------
# The same mistake has now happened twice -- 2026-09-05 and 2026-09-06 -- both
# times by copying firmware/src across from the server and carrying the real
# OTA_FIRMWARE_URL with it. Both were caught by reading the diff, which is the
# only reason neither is in the history. Twice is a process, not an accident:
# "remember to read the diff" is advice, and this is a check.
#
#   tools/check-public.sh          scan the working tree
#   tools/check-public.sh --all    scan the whole history as well (slower)
#
# Exits non-zero on any hit. A hit is not automatically a leak -- docs/WORKFLOW.md
# legitimately shows "10.0.5.x" in its redaction table -- so read what it found.
set -u
cd "$(dirname "$0")/.." || exit 2

PATTERNS=(
    '10\.0\.5\.[0-9]'          # the openHAB server and the LAN it sits on
    '10\.235\.'                # the device's cellular address
    '192\.168\.'
    'mqtt\.agesen\.dk'
    '56KTHAAAXH3343342'        # the VIN
    'adminops@'
    'id_ed25519'
    # A credential with a real-looking value. Placeholders are what
    # config.example.h is FOR, so excluding them is not a loophole -- a check
    # that cries wolf is one that gets waved through, which is the failure this
    # project already had with the ride pre-flight this same morning.
    'MQTT_PASSWORD +"(?!YOUR_|CHANGE|xxx|)'
)

fail=0
echo "Scanning the working tree..."
for p in "${PATTERNS[@]}"; do
    hits=$(grep -rIlP "$p" . --exclude-dir=.git --exclude=check-public.sh 2>/dev/null)
    if [ -n "$hits" ]; then
        echo "  HIT  $p"
        echo "$hits" | sed 's/^/         /'
        fail=1
    fi
done

if [ "${1:-}" = "--all" ]; then
    echo "Scanning history (this takes a moment)..."
    for p in "${PATTERNS[@]}"; do
        hits=$(git grep -I -l -P "$p" $(git rev-list --all) 2>/dev/null \
               | awk -F: '{print $2}' | sort -u | grep -v check-public.sh)
        if [ -n "$hits" ]; then
            echo "  HIT IN HISTORY  $p"
            echo "$hits" | sed 's/^/         /'
            fail=1
        fi
    done
fi

if [ "$fail" = 0 ]; then
    echo "Clean. Nothing found that should not be published."
else
    echo
    echo "Read each hit before pushing. History cannot be un-pushed."
fi
exit $fail

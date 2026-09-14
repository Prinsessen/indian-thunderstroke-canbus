#!/bin/bash
# Refuse to publish house infrastructure. Run before every push.
#
# WHY THIS EXISTS HERE
# --------------------------------------------------------------------------
# This repository has been clean so far on care alone -- reading the diff before
# pushing. That worked, and it is exactly what the CAN bus project was doing
# right up until it carried a real OTA URL across twice in two days. Care is not
# a process; it is a thing you have less of when you are in a hurry.
#
# WHY THE PATTERNS ARE NOT IN THIS FILE
# --------------------------------------------------------------------------
# They were, on 2026-09-12, and this script excluded itself from its own scan so
# that its own patterns would not trip it. The result: a VIN, a device MAC, a
# broker hostname and a broker password were committed and pushed to a public
# repository, by the very file written to prevent that, which reported the tree
# clean while doing it.
#
# A list of the things you must not publish is itself a thing you must not
# publish. So the list lives outside the repository and this file carries none
# of it. The self-exclusion is gone with it -- this script is now scanned like
# everything else, because there is nothing in it to hide.
#
# WHERE THE PATTERNS LIVE
#   $CHECK_PUBLIC_PATTERNS      if set
#   ~/.config/innovv-k7/public-scan.patterns    otherwise
#
# One PCRE per line; blank lines and # comments ignored. If the file is missing
# this script FAILS rather than passing -- a checker that cannot find its list
# and says "clean" is worse than no checker at all.
#
#   tools/check-public.sh          scan the working tree
#   tools/check-public.sh --all    scan the whole history as well (slower)
#
# Exits non-zero on any hit. A hit is not automatically a leak -- read what it
# found.
set -u
cd "$(dirname "$0")/.." || exit 2

PATFILE="${CHECK_PUBLIC_PATTERNS:-$HOME/.config/innovv-k7/public-scan.patterns}"

if [ ! -r "$PATFILE" ]; then
    echo "REFUSING TO RUN: no pattern file at $PATFILE" >&2
    echo "Set CHECK_PUBLIC_PATTERNS, or create that file (one PCRE per line)." >&2
    echo "This is a failure, not a pass. Nothing has been checked." >&2
    exit 2
fi

PATTERNS=()
while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    PATTERNS+=("$line")
done < "$PATFILE"

if [ "${#PATTERNS[@]}" -eq 0 ]; then
    echo "REFUSING TO RUN: $PATFILE contains no patterns." >&2
    exit 2
fi

echo "Scanning the working tree against ${#PATTERNS[@]} patterns..."
fail=0
n=0
for p in "${PATTERNS[@]}"; do
    hits=$(grep -rIlP "$p" . --exclude-dir=.git 2>/dev/null)
    if [ -n "$hits" ]; then
        echo "  HIT  (pattern $((++n)) of ${#PATTERNS[@]})"
        echo "$hits" | sed 's/^/         /'
        fail=1
    fi
done

if [ "${1:-}" = "--all" ]; then
    echo "Scanning history (this takes a moment)..."
    for p in "${PATTERNS[@]}"; do
        hits=$(git grep -I -l -P "$p" $(git rev-list --all) 2>/dev/null \
               | awk -F: '{print $2}' | sort -u)
        if [ -n "$hits" ]; then
            echo "  HIT IN HISTORY"
            echo "$hits" | sed 's/^/         /'
            fail=1
        fi
    done
fi

if [ "$fail" = "0" ]; then
    echo "Clean. Nothing found that should not be published."
else
    echo
    echo "Read each hit before deciding. Published history cannot be withdrawn."
fi
exit $fail

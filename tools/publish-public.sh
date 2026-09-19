#!/bin/bash
# Build the PUBLIC repository's tree from the two private ones, redacted, and
# prove it clean before it goes anywhere.
#
#   github.com/Prinsessen/indian-thunderstroke-canbus  (public)
#     app/       <- /etc/openhab/source-code/indian-canbus-app   (source only; the
#                   APK is built on Windows and never published)
#     docs/      <- firmware docs + app docs, links rewritten for this layout
#     firmware/  <- /etc/openhab-firmware/indian-canbus src/, platformio.ini, a
#                   fixed subset of tools/
#     tools/     <- this script and the pattern-free checker
#     openhab/   <- canbus.items, canbus.things and the CAN rules from
#                   /etc/openhab/automation/js (2026-09-19, they carry no secrets)
#     README.md, LICENSE   public-only, never touched here
#
# NOT published, on purpose: captures/ (VIN and odometer of one motorcycle in
# every line), REVERSE_ENGINEERING.md, GIT-NOTES.md, canbus_production.*,
# the app's README/IDEAS, and any binary. WIRING-DIAGRAMS.md and images/wiring-*
# (excerpts of Polaris' service manual) ARE published, by the owner's decision.
 Adding to the public surface is a
# decision, so the lists below are explicit rather than "everything".
#
# Redactions live OUTSIDE the repositories (a list of what you must not publish
# is itself one of those things -- 2026-09-12):
#   ~/.config/indian-canbus-app/redactions.sed     what to rewrite
#   ~/.config/innovv-k7/public-scan.patterns      what must not survive
# Missing either -> this script refuses to run.
#
#   tools/publish-public.sh /path/to/public/clone
#
# Writes only into the clone's working tree. Commit and push are yours, after
# reading `git diff`.
set -u
FW=/etc/openhab-firmware/indian-canbus
APP=/etc/openhab/source-code/indian-canbus-app
MAP="${MAKE_PUBLIC_REDACTIONS:-$HOME/.config/indian-canbus-app/redactions.sed}"
PAT="${CHECK_PUBLIC_PATTERNS:-$HOME/.config/innovv-k7/public-scan.patterns}"
DEST="${1:?usage: $0 /path/to/public/clone}"
[ -r "$MAP" ] || { echo "FAIL: no redaction map at $MAP"; exit 2; }
[ -r "$PAT" ] || { echo "FAIL: no pattern list at $PAT"; exit 2; }
[ -d "$DEST/.git" ] || { echo "FAIL: $DEST is not a git clone"; exit 2; }

STAGE=$(mktemp -d "${TMPDIR:-/tmp}/itc-public.XXXXXX")
put() {  # put <src-file> <dest-rel>
  mkdir -p "$STAGE/$(dirname "$2")"
  case "$1" in *.png|*.jpg|*.jpeg|*.webp|*.ttf|*.otf|*.bin|*.jar|*.apk) cp -p "$1" "$STAGE/$2" ;;
    *) sed -f "$MAP" "$1" > "$STAGE/$2" ;; esac
  chmod --reference="$1" "$STAGE/$2"
}

# ---- firmware ----------------------------------------------------------------
for f in $(git -C "$FW" ls-files src platformio.ini); do put "$FW/$f" "firmware/$f"; done
for t in ble_budget.py brake_separation_test.py decode_names.py mqtt_config.py \
         probe_watch.sh switch_watch.py sync_check.py tpms_ride_capture.py; do
  put "$FW/tools/$t" "firmware/tools/$t"; done
for d in DECODE-PLAN DISPLAY-INTEGRATION FLASHING FUTURE-HARDWARE GARAGE-RUN NEXT-RIDE OTA PROTOCOL SKILLS SLEEP \
         TOOLING-GAPS TRANSMIT UNEXPLORED-BYTES WIRING-DIAGRAMS; do put "$FW/$d.md" "docs/$d.md"; done
for i in $(ls "$FW/images"); do put "$FW/images/$i" "docs/images/$i"; done   # incl. wiring-*: service-manual excerpts, published on the owner's decision 2026-09-19
# ---- openHAB side (2026-09-19): the live items/things pair and the CAN rules ----
# The .items/.things carry topics and JSONPATH picks only; the broker bridge with
# its host and credentials lives in a different file and is not copied.
put "$FW/canbus.items"  openhab/canbus.items
put "$FW/canbus.things" openhab/canbus.things
put "$FW/canbus_button_garage.example.js" openhab/rules/canbus_button_garage.example.js
for r in canbus-ota canbus-probe-queue canbus-cruise-latch canbus-trip-this-ride canbus-clear-live-values; do
  put /etc/openhab/automation/js/$r.js openhab/rules/$r.js; done

# ---- app: source only. Root docs go to docs/, README/IDEAS/tools stay private -
for f in $(git -C /etc/openhab ls-files source-code/indian-canbus-app | sed 's|^source-code/indian-canbus-app/||'); do
  case "$f" in */*) ;; *.md|.gitignore) continue ;; esac
  case "$f" in tools/check-public.sh|tools/make-public.sh) continue ;; esac
  put "$APP/$f" "app/$f"; done
for d in APP-GUIDE BUILD-SETUP DTC-CODES KEIS-PROTOCOL WORKFLOW CHANGELOG; do put "$APP/$d.md" "docs/$d.md"; done

# ---- tools: this script and the checker that carries no patterns -----------
put "$FW/tools/publish-public.sh" tools/publish-public.sh
put "$APP/tools/check-public.sh" tools/check-public.sh
chmod +x "$STAGE"/tools/*.sh

# ---- links: the private trees are flat, the public one is not ---------------
for f in "$STAGE"/docs/*.md; do
  sed -i -E 's#\]\((src/[^)]*)\)#](../firmware/\1)#g; s#\]\((app/src/[^)]*)\)#](../app/\1)#g; s#\]\(README\.md\)#](../README.md)#g; s#\]\(platformio\.ini\)#](../firmware/platformio.ini)#g; s#\]\((tools/[^)]*)\)#](../firmware/\1)#g' "$f"
done
# DECODE-PLAN points at two private files; the public copy has said so since day one.
sed -i -e 's#\[REVERSE_ENGINEERING\.md\](REVERSE_ENGINEERING\.md) (the running log of \*how\* things#the private working log (the running record of *how* things#' \
       -e 's#\[REVERSE_ENGINEERING\.md\](REVERSE_ENGINEERING\.md)\.#the private working log.#' \
       -e 's#\[captures/\](captures/)\. Every number below is measured from those files, not#captures held privately. **They are not in this repository:** they carry the VIN\nand odometer of one specific motorcycle, and nothing in the code needs them.\nEvery number below is measured from those files, not#' \
       -e 's#see `docs/ABS_WHEEL_SPEED_SENSOR_DIAGNOSTIC\.md`, written by#established from the owner'"'"'s own service notes, written by#' \
       "$STAGE/docs/DECODE-PLAN.md"

# SKILLS lists the private files a reader here cannot open; the public copy has
# always dropped those rows and named the captures without linking them.
sed -i -e '/\](\.\.\/\.\.\/openhab\/docs\/ABS_WHEEL_SPEED_SENSOR_DIAGNOSTIC\.md)/d' \
       -e '/\[REVERSE_ENGINEERING\.md\](REVERSE_ENGINEERING\.md)/d' \
       -e '/\[GIT-NOTES\.md\](GIT-NOTES\.md)/d' \
       -e 's#\](\.\./\.\./openhab/source-code/indian-canbus-app/WORKFLOW\.md)#](WORKFLOW.md)#' \
       -e 's#\*\*Inventory from the captures first\.\*\* \[`captures/`\](captures/) holds#**Inventory from captures first.** The reference set holds#' \
       "$STAGE/docs/SKILLS.md"

# ---- report: dangling relative links ----------------------------------------
echo "Dangling links in docs/ (targets that do not exist in the public tree):"
n=0
for f in "$STAGE"/docs/*.md; do
  for t in $(grep -o -E '\]\([^)#:]+' "$f" | sed 's/^](//' | grep -v '^http'); do
    if [ -e "$STAGE/docs/$t" ]; then continue; fi
    case "$t" in ../README.md|../LICENSE) [ -e "$DEST/${t#../}" ] && continue ;; esac
    echo "  $(basename $f): $t"; n=$((n+1))
  done
done; [ $n -eq 0 ] && echo "  none"

# ---- scan the staged tree, not the sources ---------------------------------
echo; ( cd "$STAGE" && git init -q . && git add -A && CHECK_PUBLIC_PATTERNS="$PAT" tools/check-public.sh ); RC=$?
rm -rf "$STAGE/.git"
if [ $RC -ne 0 ]; then echo; echo "NOT CLEAN -- nothing written to $DEST. Staged tree left at $STAGE for reading."; exit $RC; fi

# ---- into the clone. README.md and LICENSE at the root are public-only ------
( cd "$STAGE" && tar -cf - . ) | ( cd "$DEST" && tar -xf - )
( cd "$DEST" && git ls-files ) | while read -r f; do
  case "$f" in README.md|LICENSE|.gitignore) continue ;; esac
  [ -e "$STAGE/$f" ] || rm -f "$DEST/$f"
done
rm -rf "$STAGE"
echo; echo "Written to $DEST. Now, in the clone:"; echo "  git status --short | wc -l ; git diff --stat ; and READ the diff before committing."
echo "Files: $(cd "$DEST" && git ls-files --others --exclude-standard | wc -l) new, $(cd "$DEST" && git diff --name-only | wc -l) modified, $(cd "$DEST" && git ls-files --deleted | wc -l) deleted"

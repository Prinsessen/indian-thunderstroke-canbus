# Working on SpringCommand

Everything needed to move code and assets between the three machines, build,
flash and debug. Written for PowerShell on the Windows build machine.

The **openHAB server is the source of truth**. It holds the git repository; the
Windows machine holds a working copy that is refreshed by `scp`. Files move by
copy, and edits made on Windows must be sent back up or they are lost on the
next refresh.

> **Two repositories, and only one of them is private.**
>
> `/etc/openhab` on the server is the private one. It holds `secrets/`, real LAN
> addresses, the broker hostname, the VIN, and an MQTT password that is still in
> its old commits. It has no remote and must not get one until that credential
> is rotated.
>
> **This repository is published at `github.com/Prinsessen/indian-thunderstroke-canbus`
> and it is PUBLIC.** Anything committed here is on the internet the moment it is
> pushed, and history cannot be un-pushed. Nothing that identifies the house, the
> network or the machine belongs in it.
>
> Files move **server → here**, never the other way, and every push is preceded by:
>
> ```bash
> tools/check-public.sh --all
> ```
>
> The server table further down carries the REAL host and address, because this
> is the private working copy and that is what you type. The published copy must
> not, and is produced by `tools/make-public.sh` rather than by remembering —
> see **Publishing** at the foot of this file.

### Where everything goes, once

Three machines, and only one of them is the source of anything.

```
  SERVER   /etc/openhab/source-code/indian-canbus-app
           the source of truth. Private. Holds the real host, the real
           addresses, the broker and the SSID, correctly.
    |
    |-- scp -->  WINDOWS   C:\SpringfieldAndroid\indian-canbus-app
    |            A BUILD MACHINE, nothing more. gradlew makes an APK, the
    |            APK goes to the phone, and that is where it ends. Nothing
    |            here reaches GitHub -- not the APK, not build/, not the
    |            working copy. An edit made here is lost on the next refresh
    |            unless it is sent back up.
    |
    `-- tools/make-public.sh -->  redacted copy  -->  public clone  -->  GitHub
                 Source only, from the SERVER, with the house taken out of
                 it. Verified: the copy carries no APK, no build/, no
                 .gradle, no local.properties -- 1.5 MB of source and
                 markdown, and nothing else.
```

The two arrows are separate errands and neither passes through the other. If
Windows ever seems to be on the path to GitHub, something has gone wrong.

### Copying source into this repository

The server tree is the one that runs on the bike; this repository is a published
copy of it. Files move **server → repository**, never the other way.

One line differs on purpose and must be re-applied every time `main.cpp` is
copied across:

```c
// server                                    // here
"http://192.0.2.10:8080/static/..."            "http://192.0.2.10:8080/static/..."
```

`192.0.2.0/24` is TEST-NET-1, the range reserved for documentation, so it is
obviously not a real address to anyone reading it. The server's own address is
not secret in any serious sense, but it is house infrastructure and there is no
reason for it to be on the internet.

**Check the diff after copying, not before.** On 2026-09-05 three firmware files
were copied across in one command and the real address went with them; it was
caught by reading `git diff` before committing, which is the only reason it is
not in the history. `git diff` before `git commit`, every time.

**And it happened again on 2026-09-06**, the same way: `main.cpp` and
`vehstate.h` copied across to mirror the sidestand decode, carrying the real
address with them again. Caught the same way, again before committing.

Twice is a process rather than an accident, so the reminder became a check:
`tools/check-public.sh` scans the tree, and `--all` scans every commit as well.
Run it before pushing. It knows this redaction table exists and will still flag
`192.0.2.10` in it — a hit is something to read, not automatically a leak.

---

## The everyday one

> **Always take the whole project, never single files.**
>
> Targeted `scp` of one file was used four times on 2026-09-04 to save a few
> seconds, and it nearly cost a change: two app edits landed between one build
> and the next, and only one file was copied. The throttle marker on the rev bar
> was almost lost that way, silently -- a build that succeeds with an old file in
> it looks exactly like a build that worked.
>
> The copy takes seconds. Take all of it, every time.


> **One app tree on the server, and it is `source-code`. Checked 2026-09-14.**
>
> `/etc/openhab/source-code/indian-canbus-app` is the working copy, the one the
> phone is built from, and the only one here. There is no second tree: neither
> this repository nor the firmware repository has an `app/` directory, and
> neither has a commit that ever touched one.
>
> This warning used to say there were two, and it was right when written. The
> other one went with the firmware when that was lifted out to
> `/etc/openhab-firmware/` on 2026-09-06 -- the same day the warning describes,
> which is why it survived as long as it did.
>
> **What is still true** is why the warning existed. On 2026-09-06 an export
> that was a day behind was copied over the working copy and silently reverted
> the previous evening's `Dtc.line()` fix; an hour earlier an edit had been made
> in the export, where the phone could not see it. If a file has to exist in two
> places, change it in `source-code` and carry it outward. Never the reverse.
>
> **What is NOT verified from here:** whether the published repository on GitHub
> still carries an old `app/` export. This server has no clone of it and no
> remote pointing at it, so nothing in this file can tell you. See
> **Publishing** below before the first push after a long gap.


| | |
|---|---|
| Server | `admin@your-server.example` (`192.0.2.10`) |
| App source | `/etc/openhab/source-code/indian-canbus-app` |
| Firmware source | `/etc/openhab-firmware/indian-canbus` |
| Local working copy | `C:\SpringfieldAndroid\indian-canbus-app` |

After any change on the server, refresh the source. **Copy the whole project,
not `app\src`:**

```powershell
cd C:\SpringfieldAndroid
Remove-Item -Recurse -Force indian-canbus-app\app\src
scp -r admin@your-server.example:/etc/openhab/source-code/indian-canbus-app .
```

**Why the whole project.** For a long time only `app\src` ever changed, so the
command copied only that — and it was right until it was not. The Gradle wrapper
arrived outside it, and `app\build.gradle.kts` sat stale on the Windows side for
a day: the source read `versionName = "0.2"` while the phone reported `0.1`, so
the code was current and only the number was old. That is a bad hour of
debugging bought for nothing, and the fix is to stop deciding which files matter.

`local.properties`, `build\` and `.gradle\` live outside git and are untouched —
`scp` overwrites, it never deletes.

> **Run it from `C:\SpringfieldAndroid`, never from inside the project.**
> `scp -r` of a *directory* places that directory inside the destination, so
> running it one level down produces `indian-canbus-app\indian-canbus-app\` —
> a complete, correct copy nested one level too deep, while the outer shell
> keeps a stale `app\build.gradle.kts` and an old `build\` and no `app\src`
> at all. Gradle then fails with `mainManifest ... doesn't exist`, which points
> at the manifest and not at the mistake.
>
> This happened on 2026-09-05, from a paraphrase of these very commands that
> dropped the `cd` and the `indian-canbus-app\` prefix. **Copy the block, do
> not retype it.**
>
> **If it does happen:** the nested copy is the good one. Promote it, and take
> `local.properties` with you — it is gitignored and Windows-only, holds the
> Android SDK path, and does not exist on the server, so a promoted copy without
> it fails with `SDK location not found`.
>
> ```powershell
> cd C:\SpringfieldAndroid
> Move-Item indian-canbus-app\indian-canbus-app indian-canbus-app-new
> Copy-Item -Recurse -Force indian-canbus-app-new\* indian-canbus-app\
> Test-Path indian-canbus-app\app\src\main\AndroidManifest.xml   # must be True
> ```
>
> Then delete `indian-canbus-app-new`, and build in Android Studio.
>
> **Copy into the existing folder; do not rename it.** The first version of this
> block renamed the broken folder aside and promoted the good one, and it failed
> on 2026-09-05 with `Access to the path ... is denied` — Android Studio and the
> Gradle daemon hold handles inside `build\` and `.gradle\`, so the directory
> cannot be renamed while either is running. Copying only writes, so no handle
> is in the way, and it keeps `local.properties`, `build\` and `.gradle\` where
> they already are instead of needing to be rescued.

**`Remove-Item` on `app\src` first is still not optional.** `scp` does not merge
directories: copy `src` onto an existing `src` and it lands as `app\src\src`.
Deleting also clears files renamed or removed on the server, which a copy alone
would leave behind to be compiled.

Then in Android Studio: **File → Sync Project with Gradle Files**, and build.

**Building from the command line instead?** `.\gradlew` needs `JAVA_HOME`, and
Android Studio's own JDK is too new for this toolchain — see BUILD-SETUP.md,
trap 1. Point it at the JDK 21 installed alongside:

```powershell
$env:JAVA_HOME = (Get-ChildItem "$env:LOCALAPPDATA\claude-jdks" -Directory |
                  Where-Object Name -like 'jdk-21*' | Select-Object -First 1).FullName
.\gradlew assembleDebug
```

Without it the wrapper stops at `ERROR: JAVA_HOME is not set`, which says
nothing about the project and sends you looking in the wrong place.

### After installing, check the build actually landed

Settings shows the app version with the time this copy was installed:

```
App 0.2 (03/09 11:42)  ·  Firmware 2026.09.03-1
```

The timestamp comes from the package manager and moves on every install, so it
answers the question the version number cannot: **is this the build I just
made?** A version string is a constant — six rebuilds in a day all report it
identically. Check the time, not the number.

## What NOT to overwrite

These exist only on Windows and are lost if the whole project is re-copied:

| File | Why it matters |
|---|---|
| `gradle/wrapper/gradle-wrapper.jar` | Binary; generated by Android Studio, not in the repo |
| `local.properties` | Path to your SDK — machine-specific |
| `build.gradle.kts` (root) | Holds the AGP version Android Studio may have upgraded |
| `.gradle/`, `.idea/`, `build/` | Caches and IDE state |

Refreshing only `app\src` leaves all of them alone, which is why that is the
everyday command rather than a full re-copy.

## Sending work back up

If you edit a file on Windows, push it to the server or the next refresh
overwrites it:

```powershell
scp app\src\main\java\dk\agesen\springfield\GaugeView.kt `
  admin@your-server.example:/etc/openhab/source-code/indian-canbus-app/app/src/main/java/dk/agesen/springfield/

# a whole directory
scp -r app\src\main\res\layout `
  admin@your-server.example:/etc/openhab/source-code/indian-canbus-app/app/src/main/res/
```

Then say so, and it gets committed. Nothing on the server is committed
automatically.

## Assets

```powershell
# splash artwork — one per orientation, same filename
scp portrait.jpg  admin@your-server.example:/etc/openhab/source-code/indian-canbus-app/app/src/main/res/drawable-port-nodpi/splash_art.jpg
scp landscape.jpg admin@your-server.example:/etc/openhab/source-code/indian-canbus-app/app/src/main/res/drawable-land-nodpi/splash_art.jpg

# display typeface — lowercase, no hyphens, or the resource name is rejected
scp display.ttf admin@your-server.example:/etc/openhab/source-code/indian-canbus-app/app/src/main/res/font/display.ttf
```

Sizes and composition rules: [app/src/main/res/README-artwork.md](../app/app/src/main/res/README-artwork.md).

---

## Before handing a layout change over to be built

**No `--` inside an XML comment.** Two hyphens end a comment as far as the parser
is concerned, so `<!-- moved off the dial -- it crowded the face -->` fails
`:app:parseDebugLocalResources` with *"The string \"--\" is not permitted within
comments"* and nothing else in the build is even attempted. Kotlin comments have
no such rule, which is exactly why it slips across: the same sentence is legal
one file over. Use an em dash, or a single hyphen.

Costs a whole Windows build round trip to find, so check it here first — this
catches every occurrence and parses every file, and takes a second:

```bash
python3 - <<'EOF'
import glob, re, io, xml.dom.minidom
for f in sorted(glob.glob('app/src/**/*.xml', recursive=True)):
    s = io.open(f, encoding='utf-8').read()
    for m in re.finditer(r'<!--(.*?)-->', s, re.S):
        if '--' in m.group(1):
            print("DOUBLE HYPHEN:", f, "line", s[:m.start()].count('\n') + 1)
    try: xml.dom.minidom.parse(f)
    except Exception as e: print("PARSE FAIL:", f, e)
EOF
```

---

## Firmware

Built on the server (PlatformIO builds are cheap there; Gradle builds are not —
see below). Two artefacts, and they are **not** interchangeable:

| File | Offset | Erases | Use when |
|---|---|---|---|
| `firmware.bin` | `0x10000` | `app0` only | **Normal case.** Preserves NVS, so the phone stays paired. |
| `firmware.factory.bin` | `0x0` | bootloader + partition table + **NVS** | Bootloader or partition table changed, or you want a clean slate |

`nvs` lives at `0x9000`, inside the range a factory flash erases — so it takes
the BLE bonding keys with it, and the phone then holds a link key the board no
longer has. Pairing fails until you forget the device on the phone.

```powershell
# fetch
scp admin@your-server.example:/etc/openhab-firmware/indian-canbus/.pio/build/sniffer-t2can/firmware.bin .

# flash over USB (PlatformIO's own python has pyserial; the system one may not)
~/.platformio/penv/bin/python ~/.platformio/packages/tool-esptoolpy/esptool.py `
  --chip esp32s3 --port COM5 write_flash 0x10000 firmware.bin

# serial monitor
pio device monitor -p COM5 -b 115200
```

Find the port with `pio device list`. On the Mac it is `/dev/cu.usbmodemXXXX`.

**OTA is easier when the bike is on WiFi.** It writes only the app partition, so
bonds survive. Ask, and it is pushed from the server — the board pulls it over
HTTP and reboots in about half a minute.

---

## Debugging on the phone

**Install straight from the build machine. Over the top, never uninstall first.**

```powershell
$env:PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:PATH"
cd C:\SpringfieldAndroid\indian-canbus-app
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

That is one command instead of copying the APK to the phone and installing it by
hand, and the difference is not small when a day runs to a dozen builds.

Two things bite the first time, and they bit together:

**`adb` is not on PATH.** It ships with the SDK, not with Windows. The first
line puts it there for the session only — open a new terminal and it is gone
again. Check it exists at all with:

```powershell
Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
```

`False` means platform-tools is not installed: Android Studio → **Settings →
Languages & Frameworks → Android SDK → SDK Tools → Android SDK Platform-Tools**.

**The APK path is relative to the project, not to its parent.** From
`C:\SpringfieldAndroid` the path fails even with `adb` working, which reads like
a second unrelated fault. Hence the `cd`.

To make it permanent, Windows search → *Edit the system environment variables* →
**Environment Variables** → `Path` under your user → **New**:

```
%LOCALAPPDATA%\Android\Sdk\platform-tools
```

`%VAR%` is correct **in that dialog** and wrong in PowerShell, which wants
`$env:VAR`. Open a fresh terminal afterwards; a running one does not re-read PATH.

### Logs

```powershell
adb devices                        # must say "device", not "unauthorized"
adb logcat -c                      # clear, then reproduce
adb logcat -s BikeBle:V            # the BLE client's own log
adb logcat *:E                     # everything at error level
```

`BikeBle` is the only tag the app logs under; it carries the connection
lifecycle — scan, connect, MTU, subscribe, and every disconnect reason.

### Running the tests

```powershell
.\gradlew.bat test
```

Seconds, on the build machine — no phone, no bike, no weather. They cover the
two files with no Android in them: the heat curve's arithmetic and the
fault-code tables. Both have shipped a wrong answer that a few lines here would
have caught, which is the only reason the file exists.

Nothing else is covered. Drawing, BLE and anything with a thread in it needs
hardware, and pretending otherwise would buy false confidence.

### The diagnostics screen

Long-press the firmware line in settings. **Long-press the dump itself to send it
as text** — every diagnosis so far has travelled as a screenshot, which loses
whatever did not fit on screen, and what did not fit was usually the ride log:
the part that answers questions.

It carries the link state and raw fast packet, active faults with their lamps,
the Keis assignment and curve, service on the bike against service on the phone,
the fuel filter, tyre memory, the unit settings, the last state JSON, and the
ride log. Between them they separate "the bike said something odd" from "the app
did something odd with it", which is the only question the screen exists for.

`-r` keeps the app's data. A deliberate uninstall wipes it, and that is not a
short list: both Keis controller assignments, the heat curves, tyre targets,
all-time records and the service fallback. During a day of rebuilding, deleting
the app between installs means re-pairing the garments every time — and because
the two controllers are indistinguishable over the air, that means switching one
on at a time to work out which is which. Only uninstall when the signing key
changes and `INSTALL_FAILED_UPDATE_INCOMPATIBLE` forces it.

### After adding a BLE characteristic to the firmware

**Every already-paired phone must forget the bike and pair again.**

Android caches a bonded device's service database and does not rediscover it on
its own. A characteristic added by an OTA simply does not exist as far as a
phone that bonded before it is concerned: the link comes up, the service is
found, and that one entry is missing. The failure looks nothing like a stale
cache — it looks like the feature is broken.

This cost an afternoon when `5f6d0003` was added for the service odometer. The
app reported "bike not reachable" for a motorcycle sitting three feet away with
a live link and a good signal.

Bluetooth settings → forget **Springfield** → connect again → passkey. One time,
per phone, per characteristic added.

**Disconnect reasons** are NimBLE host codes, `0x200 + HCI error`:

| Code | Meaning |
|---|---|
| `531` | Remote user terminated — the phone hung up |
| `534` | Terminated by local host — the firmware's own drop after failed pairing |

Passkey is `BLE_PASSKEY` in the firmware's `src/config.h` (currently `the value in config.h`).
If the pairing prompt seems not to appear, **pull down the notification shade** —
Android usually delivers it there rather than as a dialog.

## Split screen on the head unit, and the two settings that force it

The head unit runs the CAN app beside OsmAnd+, and **the app needs no change for
this.** There is no `screenOrientation` lock and `resizeableActivity` is not
declared, so it defaults to true on `targetSdk 35`. Split screen works out of the
box.

It also survives entering and leaving split the same way it survives rotation,
and for the reason the manifest already gives: no page holds state, everything is
re-read from `BikeRepository`, and the BLE link lives in the service. Split is the
same class of configuration change as a rotation.

**Setting it up (Android 13):** open OsmAnd+, open Recents, tap the *app icon* at
the top of its card, choose split screen, then pick the CAN app for the other
half.

### If the head unit's ROM blocks it

Head units run modified launchers and some disable or replace Recents. These two
global settings force the behaviour, and **neither is needed for this app** — it
is already resizeable. They exist only for when the device itself is in the way.

```bash
adb shell settings put global force_resizable_activities 1
adb shell settings put global enable_freeform_support 1
adb reboot
```

**These persist across reboots.** `settings put global` writes to the system
settings database; it is a one-off, not something to repeat at every boot.

**The split arrangement itself is *not* persisted** — it is a window state, so a
reboot loses it. In practice that should not bite, because the head unit has a
10 000 mAh battery and sleeps rather than powering down with the ignition, and a
sleeping device keeps its window state.

### Reverting them

`force_resizable_activities` is the one that can go sideways: it overrides what
*every* app declares, including apps that genuinely cannot handle being resized,
and those can misbehave or crash.

**Delete rather than set to zero.** Deleting restores the true default; zero is a
value, and a value is not the same as unset.

```bash
adb shell settings delete global force_resizable_activities
adb shell settings delete global enable_freeform_support
adb reboot
```

**To see where they stand:**

```bash
adb shell settings get global force_resizable_activities
adb shell settings get global enable_freeform_support
```

`null` means unset, which is the shipped state.

## When the app and openHAB disagree

**Long-press the firmware line in settings** for the diagnostics screen: app and
firmware versions, link state, RSSI, negotiated MTU, the raw 8-byte fast packet
in hex with its decode beside it, the raw state JSON, and the tail of the ride
log.

That screen answers the only question worth asking when the two sides differ —
is the decode wrong, or are the bytes? Both sides come from one struct and one
serialiser in the firmware and cannot legitimately differ, so it is always one
or the other.

The ride log is also written to a file, because `adb logcat` only helps while
the phone is attached to a computer, which is the one place it will never be
during a ride:

```powershell
adb shell run-as dk.agesen.springfield cat files/ridelog.txt > ridelog.txt
```

## Pulling an APK, when a protocol needs recovering

How the Keis protocol was found, recorded because it will be needed again if
iControl changes it — and because the adb invocations are easy to get wrong in
PowerShell, where `<placeholders>` are a parser error rather than a hint.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices          # must show a device, not an empty list

$pkg = (& $adb shell pm list packages | Select-String 'keis') -replace 'package:','' -replace '\s',''
$paths = (& $adb shell pm path $pkg) -replace 'package:','' -replace '\s',''
foreach ($p in $paths) { & $adb pull $p }
scp *.apk admin@your-server.example:/tmp/
```

Android splits an app into `base.apk` plus resource splits; **the code is in
`base.apk`** and the rest are translations and images. Send them all anyway —
cheaper than discovering the one you left behind held what you wanted.

On the server, the first pass needs no decompiler at all:

```bash
unzip -q base.apk -d x && cd x
strings classes.dex | grep -oiE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | sort -u
strings classes.dex | grep -iE 'level|heat|writeCharacteristic'
```

UUIDs are string literals and fall straight out. **Numeric constants do not** —
those need a decompiler, and jadx is a zip that runs on the JDK already
installed:

```bash
curl -sL -o jadx.zip <latest release zip from github.com/skylot/jadx>
unzip -q jadx.zip -d jadx && chmod +x jadx/bin/jadx
nice -n 15 ./jadx/bin/jadx -d out --no-res -q base.apk
grep -rn 'DEVICE_LEVEL' out --include=*.java
```

Keep it in `/tmp` and delete it after — it is a 70 MB tool on a machine whose
job is running the house.

> Decompiling for **interoperability** with an independently written program is
> expressly permitted under Article 6 of the EU Software Directive. That is what
> this is, and it is worth knowing rather than assuming.

## Checking the app against openHAB

MQTT runs in parallel from the same firmware, so both sides show the same
decoded values. If they disagree, the app's decoding is wrong — they come from
one struct and one serialiser in the firmware and cannot legitimately differ.

```powershell
ssh admin@your-server.example "curl -s http://localhost:8080/rest/items/CanBus_RPM/state; echo"
ssh admin@your-server.example "curl -s http://localhost:8080/rest/items/CanBus_Status/state; echo"
```

`CanBus_Status` is the MQTT Last Will: `online` while the board holds its broker
connection, `offline` once it drops. **A plausible RPM with `Status: offline` is
a retained echo, not live data** — the `/state` topic is published retained, so
openHAB keeps showing the last values indefinitely.

## Poking about on the server

```powershell
ssh admin@your-server.example "ls -la /etc/openhab/source-code/indian-canbus-app/app/src/main/java/dk/agesen/springfield/"
ssh admin@your-server.example "cd /etc/openhab && git log --oneline -10"
ssh admin@your-server.example "tail -40 /var/log/openhab/openhab.log"
```

---

## ⚠️ Never build the app on the server

Gradle wants 4–6 GB of RAM and every core. That host runs openHAB at ~3 GB and
~70 % CPU with under 2 GB free, and the OOM killer's first pick would be openHAB
itself — which runs the heating, the alarm and the door locks.

PlatformIO firmware builds *are* fine there: about one core and a few hundred MB.

## ⚠️ The emulator cannot be used

The Android emulator has no Bluetooth radio, so BLE cannot be tested in it at
all. A physical phone with USB debugging is required. There is no workaround.

---

## Publishing

The server tree is private and **correctly** holds the real host, the real LAN
addresses, the broker and the SSID — they are what you type. The published copy
must carry none of it.

That used to be done by remembering. It was forgotten on 2026-09-05 and again on
2026-09-06, caught both times only by reading `git diff` before committing; and
on 2026-09-12, in a sibling repository, it was not caught at all and a VIN, a
MAC, a broker hostname and a broker password went to a public repository.
Remembering is not a process.

```bash
# the published repository is app/ + docs/ + firmware/ + tools/, built from BOTH
# private trees by one script that lives with the firmware:
/etc/openhab-firmware/indian-canbus/tools/publish-public.sh /path/to/public/clone
```

It stages the public layout from explicit lists (what is published is a
decision, not "everything"), applies the redaction map to every **text** file,
rewrites the links the flat private trees use into the public layout, reports
any link that would dangle, and runs `check-public.sh` **on the staged tree** —
the source is allowed to hold these strings; the copy is not. Only a clean stage
is written into the clone, and it never commits or pushes. `tools/make-public.sh`
in this directory is the older app-only version and is kept for the app tree
alone.

Checked 2026-09-14 against the published copy: the pattern list caught 4 of 11
categories the old checker had covered, so both the list and the map were
extended (bare house domain, SSID, Windows user path, SSH key name, BLE
pairing PIN, MQTT username, public IP) and re-tested at 11 of 11.

The map lives at `~/.config/indian-canbus-app/redactions.sed`, **outside this
repository**, because a list of the things you must not publish is itself a thing
you must not publish — that is the 2026-09-12 lesson written down. If the map is
missing the script fails rather than producing an unredacted "public" copy.

Nothing in it may match `dk.agesen.springfield`: that is the Java package name,
it appears in 55 files, and rewriting it would break the build in a way that
looks like a redaction working.

**Check the clone's layout the first time.** `make-public.sh` writes THIS
repository's shape -- `app/`, `gradle/`, the markdown at the root. If the
published repository is arranged differently, copy the parts into place rather
than emptying the script over its root.

**Expect a large first diff after a gap.** The published copy is updated by hand,
so it is behind by however long it has been since anyone last did it -- not by
one session. A diff showing far more than today's work is the normal case, not a
sign that something went wrong.

**Then still read it.** All of it. The script catches what it was told to catch
and has never seen the thing you added today, and a long diff is exactly the one
people skim -- which is how the real address got through on 2026-09-05 and again
on 2026-09-06.

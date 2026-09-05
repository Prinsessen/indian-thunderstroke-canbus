# Working on SpringCommand

Everything needed to move code and assets between the three machines, build,
flash and debug. Written for PowerShell on the Windows build machine.

The **openHAB server is the source of truth**. It holds the git repository; the
Windows machine holds a working copy that is refreshed by `scp`. There is no git
remote, so nothing pulls — files move by copy, and edits made on Windows must be
sent back up or they are lost on the next refresh.

| | |
|---|---|
| Server | `admin@your-server.example` (`192.0.2.10`) |
| App source | `/etc/openhab/source-code/indian-canbus-app` |
| Firmware source | `/etc/openhab/indian-canbus` |
| Local working copy | `C:\SpringfieldAndroid\indian-canbus-app` |

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

Sizes and composition rules are documented alongside the artwork in the private working copy.

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
scp admin@your-server.example:/etc/openhab/indian-canbus/.pio/build/sniffer-t2can/firmware.bin .

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

Passkey is `BLE_PASSKEY` in the firmware's `src/config.h` — yours, not one published here.
If the pairing prompt seems not to appear, **pull down the notification shade** —
Android usually delivers it there rather than as a dialog.

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

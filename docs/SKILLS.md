# Working on this project — start here

A handover note. Drop this into a fresh session on any machine and it should
begin where the last one left off rather than from nothing.

It deliberately does **not** repeat the reference documents. Those are listed
below and are the authority on what has been found. This one is about **how the
work is done, where things live, and what has already cost time.**

Last revised **2026-09-07**: deep sleep is running on the bike, the kill switch
and start button are decoded, and section 8 records why the bus cannot start
this machine.

---

## 0. Check this file before believing it

A handover note that describes a state which no longer exists is worse than
none, because it gets believed. Everything below was true when it was written
and some of it has a shelf life. **Four commands, thirty seconds:**

```bash
cd /etc/openhab && git log --oneline -15          # what has happened since
grep FW_VERSION indian-canbus/src/config.h        # what this file expects
echo "openhab:status CanBus_FW_Version" | /usr/share/openhab/runtime/bin/client -p habopen
grep -E "PROBE_CHANGES|FIRMWARE_MODE" indian-canbus/src/config.h
```

- **Versions disagree?** The bike is behind the source; something was built and
  not flashed, or an OTA failed. `meta` will say.
- **`PROBE_CHANGES 0`?** The discovery probe has been removed and section 4's
  hunting instructions need it back.
- **`FIRMWARE_MODE` not 1?** You are looking at the discovery build, not the one
  that runs on the bike.
- **Commits since this date?** Read them. The commit messages on this project
  carry the reasoning, not just the change — they are the real changelog.

Trust the repository over this file wherever they disagree, and then fix this
file.

---

## 1. What the machine is

A 2017 Indian Springfield, Thunder Stroke 111, **air-cooled** — there is no
coolant on it, whatever a J1939 field is called. The one engine temperature it
does have is the **CHT sensor on the front cylinder head**; there is no oil
temperature sensor either, only oil level and oil pressure. No traction control,
no IMU. ABS with two wheel speed sensors.

An ESP32-S3 (LilyGO T-2CANFD) sits on the J1939 bus in **hardware listen-only
mode**, decodes what it understands, and publishes to MQTT and over BLE. It
cannot transmit; that is a hardware guarantee, not a software one.

### The app's primary display is a head unit, not a phone

Confirmed 2026-09-07. The Android app runs on a **Hugerock X70**, a 7" 1920×1080
IPS head unit permanently mounted as the machine's navigation display. A phone is
the secondary case now, not the primary one. Three things follow:

**Landscape is the orientation that matters.** Portrait is the one nobody sees.
That reverses the priority behind [TOOLING-GAPS.md](TOOLING-GAPS.md) item 3,
where landscape kept being forgotten because it was the afterthought.

**It has more room, and the app already uses it.** 1920×1080 at 7" is about
315 ppi, so roughly 960 × 540 dp in landscape against a phone's ~410 dp of
height. The custom views scale into it rather than leaving gaps, because they
size themselves from `min(width, height)` in `onDraw` instead of fixed dp — the
owner reports the graphics come out larger and easier to read than on the phone,
and **no layout changes were needed**. Worth knowing that this was tested, not
assumed: a design that scales to an unfamiliar canvas has earned some trust.

**It outlives the ignition.** A 10 000 mAh internal battery keeps the head unit
running after the key comes out, while the board sleeps five minutes later. So
there is now a case the phone never produced: **the app running, connected to
nothing, for hours.** That is exactly what `STALE_MS` and `TyreMemory`'s honest
ages are for, and it is the strongest argument yet for the tyre-age fix made the
same day — a permanently connected display is precisely where a stale reading
stamped "just now" would have been believed.

**Which Android versions it runs on, and why.** `minSdk = 31` — **Android 12**,
not 13 — set deliberately, with the reason in `app/build.gradle.kts`: from API 31
Bluetooth uses `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` with `neverForLocation`, so
the app needs no location permission at all. Supporting anything older would drag
in the legacy `BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION` path, a second and quite
different permission flow, for phones nobody here owns.

So an Android 11 device (API 30) genuinely cannot install it, and the head unit
on Android 13 (API 33) sits two levels above the floor. Nothing to do with
Kotlin, which has no minimum Android version of its own — it compiles to the same
bytecode as Java and runs on releases far older than any of this.

Two things unmeasured as of 2026-09-07, both cheap to observe:

- Does scanning for a sleeping board over hours drain the head unit's battery?
- Does BLE reconnect cleanly when the board wakes, on the ignition or on the
  hourly backstop?

And one untested: BLE has held in the garage but has not yet been ridden with.
Short range from handlebars to service connector, so it should hold — but that is
an expectation, not a result.


**Four modules talk on the bus.** Knowing which one sent a frame matters more
than anything else here. Several PGNs have two or three senders that disagree,
and four decodes shipped broken because they read whichever module spoke last —
the headlight, the brake, DM1 and the fuel level. All four were fixed by
filtering on source address, not by being withdrawn; the withdrawals in section
6 are a separate list with a separate cause.

| SA | Module | Sends |
|---|---|---|
> The module names are not guesses any more: each one declares itself on PGN
> 60928 and `tools/decode_names.py` reads it. SA 0 says Engine, SA 23 says
> Instrument cluster, SA 39 says Management computer — and each of the first two
> agrees with what it actually sends, which is what makes the third credible.

| **0** | ECU, engine | rpm, cylinder head temp, fuel, throttle (valve), battery, indicators, brake + clutch, grip temperature, DM1, VIN |
| **11** | ABS | both wheel speeds, brake control, the speed the dash shows, DM1 |
| **23** | Instrument cluster | odometer, trip, ambient, a second fuel reading |
| **39** | VCU, body | tilt sensor, gear, clock, TPMS, **key fob / security**, heated grips, hazard, headlight, ignition, cruise switches |
| **136** | **Instrument cluster** (declared), mfr 146 | Claims an address EIGHT times across four rides — twice as often as anyone — and sends nothing else, ever. Same function code as the dash at SA 23 but a different manufacturer, so: fitted display-class equipment this bike gives no work to. See DECODE-PLAN.md, "the address claims". |

---

## 2. Read these, in this order

**Start with [DECODE-PLAN.md](DECODE-PLAN.md).** It is the standing record of
every signal on this bus — decoded, withdrawn, or established as absent — with
the reasoning for each. If you read only one other file, read that one.

| File | What it is |
|---|---|
| **[DECODE-PLAN.md](DECODE-PLAN.md)** | The standing list: what is decoded, what was withdrawn and why, what is left. **The main document.** |
| [UNEXPLORED-BYTES.md](UNEXPLORED-BYTES.md) | Every varying byte the firmware still does not read, measured from the captures |
| [NEXT-RIDE.md](NEXT-RIDE.md) | What is waiting on wheels, and the two things that need the rider to do something |
| [PROTOCOL.md](PROTOCOL.md) | The BLE contract — UUIDs, byte layout, the payload budget |
| [TOOLING-GAPS.md](TOOLING-GAPS.md) | Three things missing from how we work, and what each has cost |
| [OTA.md](OTA.md) · [FLASHING.md](FLASHING.md) | Updating over the air, and recovering a dead board over USB |
| [README.md](README.md) | Long-form background |
| [../source-code/indian-canbus-app/WORKFLOW.md](WORKFLOW.md) | How the app gets from this server to the phone |

---

## 3. The workbench

```
/etc/openhab-firmware/indian-canbus/          firmware, docs, captures
/etc/openhab-firmware/indian-canbus/src/      main.cpp is ~2,300 lines
/etc/openhab/source-code/indian-canbus-app/    the Android app
/etc/openhab/items|things|sitemaps/  the openHAB side
```

- openHAB server: **192.0.2.10** (`OpenHab5`), user `admin`
- ESP32 on WiFi: DHCP, currently 192.0.2.20 — read it from `meta`, do not assume
- MQTT: `mqtt.example.com:8883`, base topic `canbus/springfield`
- PlatformIO env: **`sniffer-t2can`** — the other two envs are for the older
  T-CAN485 board and fail to build while `config.h` points at the MCP2518 backend

### Build and flash over the air

```bash
cd /etc/openhab-firmware/indian-canbus
# bump FW_VERSION in src/config.h FIRST -- see the traps below
pio run -e sniffer-t2can
strings .pio/build/sniffer-t2can/firmware.bin | grep -o "2026\.[0-9.]*-[0-9]*"   # verify
cp .pio/build/sniffer-t2can/firmware.bin /etc/openhab/html/indian-canbus-firmware.bin
echo "openhab:send CanBus_OTA update" | /usr/share/openhab/runtime/bin/client -p habopen
```

Then wait for `CanBus_FW_Version` to show the new version. An OTA takes 40
seconds on a good day and has taken 160; the guard allows 300 before it restarts
the chip.

### Read what the bike is saying

```bash
P=$(sed -n 's/^#define MQTT_PASSWORD *"\(.*\)".*/\1/p' src/config.h)
mosquitto_sub -h mqtt.example.com -u "$MQTT_USER" -P "$P" -t 'canbus/springfield/state'
mosquitto_sub -h mqtt.example.com -u "$MQTT_USER" -P "$P" -t 'canbus/springfield/meta'
mosquitto_sub -h mqtt.example.com -u "$MQTT_USER" -P "$P" -t 'canbus/springfield/probe'
```

`state` is retained — the first message you get may be old. Take the second.

`meta` carries the reset reason, uptime, free heap and the detected bitrate.
**`uptime` is the honest answer to "did it restart?"**

### Recovering a dead board

If an OTA hangs and the board stops answering, it is flashed over USB from the
owner's own machine — the server has no line of sight to it.
[FLASHING.md](FLASHING.md) has the procedure. **A factory flash at `0x0` erases NVS**, taking the service
odometer and the fault counters with it. Read them off the app first.

### Working from another machine

A clone carries everything this file refers to — the documents, the full history
with its reasoning, the firmware source, and the four ride captures the whole
method is built on. About 76 MB, seconds over the LAN.

```bash
git clone admin@your-server.example:/etc/openhab openhab
cd openhab
scp admin@your-server.example:/etc/openhab-firmware/indian-canbus/src/config.h \
    indian-canbus/src/
```

**`config.h` is the one thing a clone cannot give you.** It holds the WiFi and
MQTT credentials and the firmware version, and it is git-ignored on purpose. Copy
it separately, or start from `config.example.h`.

**Do not push back.** The server's working tree is live — openHAB reads
`items/`, `things/` and `sitemaps/` straight from those files — and an
auto-commit service commits everything there every fifteen minutes as the
`openhab` user. Git refuses it anyway: `master` is checked out. Edit over VSCode
Remote-SSH, and clone only to read.

### The app

Built on the owner's Windows machine; **this server has no Android SDK**, so
Kotlin written here is never compiled before it is handed over. Always give the
whole-project copy, never single files:

```powershell
cd C:\SpringfieldAndroid
Remove-Item -Recurse -Force indian-canbus-app\app\src
scp -r admin@your-server.example:/etc/openhab/source-code/indian-canbus-app .
cd indian-canbus-app
.\gradlew.bat assembleDebug
$env:PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:PATH"
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 4. How signals get found

This is the part that matters. The method below found six signals in one day,
and skipping steps of it is what produced the four that had to be withdrawn.

1. **Inventory from captures first.** The reference set holds 42,365 frames
   from four rides. Which source addresses send the PGN, how many frames, which
   bytes actually vary. A byte constant across all of them carries nothing, and
   `0xFF` is J1939 for "not available" — never mask it into a value.

2. **Look up the standard, then distrust it.** Indian does not always follow the
   byte layout. Ambient temperature is the proof: J1939 puts it in bytes 5-6 of
   PGN 65269 and this bike puts it in 4-5. The standard generates hypotheses; it
   never answers.

3. **Correlate against something known** — speed, rpm, throttle. `r` near 1.00
   at zero lag means you have found a copy of an existing signal, not a new one.

4. **Check the lag.** This is what separated the throttle from the tachometer:
   the real throttle leads engine speed by about a second; the false one peaked
   at zero lag because it *was* engine speed.

5. **Ask for the slope, not just the correlation.** Front and rear wheel speed
   correlate at 0.9998 and are still 3.5 % apart. `r` says two things move
   together; only the slope says whether they agree.

6. **Design one manoeuvre that separates the survivors.** Hold each state eight
   seconds with real pauses. Tapping mixes bits; holding does not.

7. **Decide whether it earns a production field.** Understood and shipped are
   different decisions. Several signals are understood and deliberately not
   shipped.

### The rule that has saved us most

> **A test that reports nothing must first be shown capable of reporting
> something.**

Every null result gets a positive control — flick an indicator, work the
headlight — before it is believed. This has caught a broken scanner, a broken
probe, and a monotonic search that could not even find the odometer.

---

## 5. Traps, all of which have already bitten

**Assume more than one sender until the captures say otherwise.** Headlight (2),
CCVS (3), DM1 (3) and fuel level (2) all shipped broken for this reason.

**Two-bit fields are two bits.** `0 off, 1 on, 2 error, 3 not available`. `& 1`
reports "not available" as "on".

**`SETI` casts to `int8_t`.** It is right for the tri-state switch flags it was
written for and silently wrong for anything wider — used on a 0-255 tilt reading
it turned every value above 127 negative. Use `SETI16` for wider fields.

**Bump `FW_VERSION` before building, not after.** An image once went up under
the previous version number, and the fix it carried looked like it had failed.
Verify the version *inside the binary* with `strings` before flashing.

**"Running &lt;version&gt;" is published once per boot** — it used to fire on every
MQTT reconnect, and a reconnect storm was read as a reboot loop for hours. Use
`uptime` and `reset` from `meta` instead.

**The BLE state JSON has a hard 514-byte ceiling** and cannot fragment. Worst
case is 509 — five bytes of margin, with fault codes already compacted to numbers
and the firmware version already sent rarely. Adding a field is not free; run
`python3 tools/ble_budget.py` before you do. [PROTOCOL.md](PROTOCOL.md) has the
budget and what gets sacrificed when it still does not fit.

**Landscape has its own layout files.** Three views shipped portrait-only in one
day. Check `layout-land/` every single time.

**Never touch the safety interlocks.** The engine cut when the bike is in gear
with the stand down is not an inconvenience — if the gear is real and the clutch
comes out, 400 kg goes over with the rider under it. The gear glitch counter
observes and never acts.

---

## 6. Where things stand

**Decoded and shipped:** speed (both wheels), rpm, throttle, gear, oil
temperature, fuel level and rate and economy, battery, ambient, tyres, DM1,
odometer, trip, heated grips and their temperatures, tilt/stand, ignition,
wheel-sensor cross-check, service odometer.

**Understood and deliberately not shipped:** the front/rear speed disagreement
(the optimistic dash reading is a margin against a speed camera), the clock (12
hours and 7 minutes out, and not the one on the display), the wake bit (the bus
going live says it better).

**Withdrawn after being shipped on a guess:** cruise control, throttle-from-65382,
front brake, horn. Each keeps its `case` and a comment saying why. Two of them
turned out to be something else entirely: the "horn" bit is the security system
looking for the key fob, and the "front brake" is one brake signal that either
control operates.

**Established as absent, each exercised while watched:** lean angle in corners,
a sidestand *state*, Trip 2, **cruise engaged (SPN 595)**, cruise set speed,
**the horn**, **the saddlebag locks**, **the security alarm**, and coast/accel
(SPN 600/602 — Indian sends only SET and RESUME). The sidestand does appear as
an *event* — SPN 520267 FMI 31 when it blocks a start.

**The rule this bus taught us:** it carries state a rider reads and not actuation
a rider performs. Horn, locks and alarm are all switch into a module driving an
output in that module, with nothing that needs telling. Check which side of that
line a candidate falls on before spending an evening on it — and check the manual
for an FMI 9, "Abnormal Update Rate", which is the manufacturer saying out loud
that a signal is expected over the network.

**Open and worth doing:** PGN 65382 bytes 1 and 4 — now with a sharp test, since
cruise holding with the grip released separates the rider's demand (SPN 91, not
on the bus) from the valve (SPN 51, decoded); the immobiliser's relation to
SPN 520330; and the compact DM1 encoding.

---

## 7. The decision that is the owner's alone

Everything above is passive. Going further — stored fault codes (DM2), freeze
frames (DM4), anything answered on request rather than broadcast — needs the
sniffer to **transmit**, which means leaving hardware listen-only.

The owner has said she is willing, **after** passive listening is finished,
documented, the app is current, and a real test ride has happened. That
sequence is hers and it is the right one. `TX_ENABLED` in [`src/main.cpp`](../firmware/src/main.cpp) is 0 and
stays 0 until she says otherwise, at a moment she chooses, with the engine off
and a USB cable attached.

**[TRANSMIT.md](TRANSMIT.md) is the plan on the shelf** — what transmitting
would unlock ranked for this machine, what it costs, the two PGNs that must
stay impossible to call even then, and the protocol for a first attempt. It
changes nothing today.

---

## 8. What the bus cannot do — a security note

Asked directly, 2026-09-07: now that the ignition is mapped, could a start be
emulated from openHAB? The answer is no, and the reasons are worth writing down,
because "can someone start my bike over the network" is the real question hiding
inside a lot of the curiosity about this project.

The conclusion has two halves, and both are reassuring.

**This board adds no attack surface.** It is hardware listen-only — `TX_ENABLED`
is 0 and the controller is in ListenOnly / LISTEN_ONLY mode — so it physically
cannot put a frame on the bus, not even an ACK. Compromise openHAB, the MQTT
broker or the phone app and the worst anyone gets is *eavesdropping* on a
stationary bike. There is no path from the network onto the CAN bus, by design.
That is what makes it safe to hang this thing on a vehicle at all.

**The vehicle is robust independently of us.** Even an attacker with full active
CAN access cannot crank it, and it is not one lock but five, each sufficient
alone:

1. **You cannot transmit** without leaving listen-only (see section 7).
2. **Ignition is not a command.** It is derived — bus activity *is* the ignition
   (PGN-wide, not one signal). There is no frame meaning "turn on"; the modules
   are unpowered until the physical key turns, and a frame on a dead bus reaches
   nobody.
3. **The start button is not on the bus for this purpose.** It is a physical
   input to the handlebar module (SA 39) and reports *even while the kill switch
   blocks the crank*, precisely because the ECU reads the module's hardware pin,
   not a CAN frame. Spoofing "startBtn pressed" collides with the real module's
   address claim and is ignored.
4. **Interlocks.** The ECU will not crank until it has checked sidestand, kill
   switch and gear/clutch for itself.
5. **The immobiliser.** `CanBus_Security` tells the story — fob authorised /
   searching / **not found**. No fob in range, no start, and the fob↔security
   handshake is cryptographic, so a captured frame cannot be replayed.

None of this is accidental difficulty. A modern motorcycle assumes its CAN bus
can be listened to, so starting hangs deliberately on physical inputs and a
crypto handshake rather than on frames anyone can imitate. Mapping the ignition
*confirmed* this rather than weakening it: the ignition turned out to be a power
state, not a command.

Where remote-start attacks on vehicles actually live is the fob side — relay
attacks that extend the key's signal — which is a different vector entirely and
has nothing to do with this board or this bus.

---

## 9. Working with the owner

She rides the bike, she is an electronics engineer, and she is right more often
than the analysis is. Six corrections in one day — the cruise control, the horn,
the sensor physics, the oil temperature, which grip was which, and that the
probe should come out — every one of them held.

The next day added four more, and two of them changed what got built. She
refused a null on the saddlebag locks ("if the fob can lock it, it must go
through the bus"), which exposed three holes in the test and produced a
conclusion worth having. She pointed out that being told about a missing key fob
after twenty seconds is useless because you are already sitting on the bike,
which turned a failure alarm into a three-second one. She noticed the cruise
buttons reaching openHAB but not the app — twice, for two different signals. And
she supplied the fact that settled the temperature naming by simply reading the
instrument while riding.

So: **when she says something about the machine, that is data.** Measure against
it rather than around it. And when a conclusion rests on a guess, say which part
is the guess.

# Indian Thunder Stroke — CAN bus decoder

**Listen-only J1939 decoder for Indian Thunder Stroke motorcycles: ESP32 firmware,
an Android instrument cluster, and how each signal was found.**

Developed and verified on a **2017 Indian Springfield**. The service manual it was
worked from is the *2017 Indian Motorcycle (Full-Size)* volume, which covers the
Chief, Dark Horse, Vintage, Springfield, Chieftain, Roadmaster and Elite — they
share the engine and the bus, so most of this should apply to all of them. None of
it has been tested on anything but the Springfield. Scout and FTR are a different
engine and are out of scope.

---

## It cannot touch the motorcycle

The CAN controller runs in **hardware listen-only mode**. It is not a matter of
the firmware choosing not to transmit — the peripheral is configured so that it
*cannot*, and it does not even acknowledge frames. A silent receiver on a bus that
is running anyway.

That was the first decision made and it has never been relaxed. Nothing here can
send a command, clear a fault, or change a setting on the bike.

---

## What it reads that the factory dash does not

| | |
|---|---|
| **Tyre pressure with its temperature** | Both, per wheel, from the TPMS sensors |
| **Cold-equivalent pressure** | What each tyre would read at ambient, by the gas law. A tyre at 27 °C reading 35.4 PSI is 33.7 cold — against a 36 PSI cold spec, that is 2.3 low, and the dash cannot tell you so |
| **Named fault codes** | 179 SPNs transcribed from the service manual. Not "check engine" but *"Injector 1, Driver Circuit Open/Grounded"* |
| **Wheel-speed sensor monitoring** | Both sensors compared continuously; brief dropouts counted and kept across reboots |
| **Gear-change logging** | Every change with its context, and a tally of the implausible ones |
| **Lean angle** | Upright, resting on the stand, or lying down |
| **Grip heater level and both grip temperatures** | The level in its ten detents, and what each grip has actually reached — separately, so you can see whether the heat is arriving rather than only what you asked for. Which side is which was settled by holding a bare hand on one grip with the heat off |
| **Cylinder head temperature** | The engine's only temperature sensor. This bike is air-cooled and has no oil temperature sensor at all |
| **A derived cruise-control state** | See below — the bike does not transmit it |

---

## What is **not** on this bus

Worth as much as the list above, because it saves somebody a week:

| | how it was established |
|---|---|
| **The horn** | Eight presses across two sessions with every byte visible. Nothing moved. The manual gives SPN 520293 only output-driver faults, no "abnormal update rate" |
| **The saddlebag locks** | Console switch and both fob buttons, actuators confirmed running by the voltage sag they cause. The bus stayed silent |
| **The security alarm** | Arming chirps the horn and wakes nothing |
| **The sidestand *state*** | The switch exists — the instrument has a lamp for it — but it is never broadcast. It appears only as a DM1 event when it blocks a start |
| **Cruise control engaged (SPN 595)** | Held at 94 km/h with the rider's hand off the grip. Byte 4 never moved, while the button presses in the *same frames* came through perfectly |
| **Coast and accelerate (SPN 600 / 602)** | Not sent. Indian transmits SET and RESUME only; the decel/accel meaning is applied by the ECU once engaged |

The pattern behind them: **this bus carries state that other modules need.** A horn,
a lock and an alarm are a switch wired into a module driving its own output, with
nothing to tell anybody. Check which side of that line a signal falls on before
spending an evening on it — and check the manual for an FMI 9, "Abnormal Update
Rate", which is the manufacturer admitting out loud that something is broadcast.

---

## Cruise control, derived

SPN 595 is not transmitted, so the engaged state is worked out instead — from
measured inputs only. The rocker (SPN 596), the SET and RESUME presses (599/601),
the brake (597), the clutch (598) and road speed.

A press only *arms* the state. It becomes `HOLDING` once the speed has sat still
for three seconds, so a press that never caught — too slow, wrong gear — never
claims to be holding. Brake, clutch and the rocker end it outright; drifting off
the held speed ends it too, which catches a backward flick of the grip even though
that input is invisible.

Only the rule is inferred. Every input is measured, and the published value says
`HOLDING` rather than `ON` so that the word itself marks what kind of fact it is.

---

## The app

An instrument cluster rather than a data readout. Around 10,000 lines of Kotlin,
plain Views and Canvas — no Compose — because everything on screen is a drawn
instrument and a layout engine has nothing to contribute to a needle.

**Four pages.**

| | |
|---|---|
| **Ride** | Speedometer with the gear window and turn arrows on the face, a digital rev readout with the ignition and ABS lamps beside it, fuel, grip heaters, and a row of tell-tales |
| **Tyres** | Both wheels: what is in the tyre now, what that would be cold, the temperature that separates them, and the target. Plus a slow-leak trend measured across weeks on cold-corrected figures, since raw readings taken at different temperatures mostly describe the weather |
| **Machine** | Cylinder head, battery, ambient, odometer, service interval, fuel economy |
| **Heat** | The rider's own clothing — see below |

**A fault banner across the top of every page.** The diagnostics were decoded and
then buried on a page nobody visits while a fault is developing, which is the one
time they matter. Six conditions in the order a rider wants them: what could put
you down, then what will strand you, then what needs planning. It is deliberately
not dismissible — a warning you can swipe away is one you will swipe away.

**Three lamp states, not two.** Every switch is lit, dark, or *struck through*
when the bus has never mentioned it. A dark lamp reads as "not active", which is
a claim the app has no business making about something it has never heard from.

**Two transports.** A packed eight-byte BLE frame at 10 Hz for the things that
move — revs, speed, throttle, gear, switch flags — and a JSON state at 1 Hz for
everything else. Over WiFi the same JSON arrives by MQTT. The fast frame carries
a second byte of *validity* flags for exactly the reason above.

**It remembers what the bike cannot.** The CAN interface is listen-only, so the
motorcycle's own trip meter can never be reset from here — the app keeps its own.
Tyre readings, their trend, the service interval and the ride distance all live on
the phone.

---

## Heated clothing

The app also drives **Keis heated jacket and trousers** over Bluetooth, because
they are the other half of being warm and there is no reason to carry a second
app for them.

The protocol is undocumented. It was recovered by decompiling *Keis iControl* —
which under the EU Software Directive's interoperability provision is a permitted
purpose, and which proved both faster and more reliable than sniffing packets: a
capture shows what was sent once, the source constants show what the firmware was
written to accept. [`docs/KEIS-PROTOCOL.md`](docs/KEIS-PROTOCOL.md) documents what
was found.

**Three levels, not a percentage.** The hardware has off, green, amber and red.
Modelling it as 0-100 would let the app ask for 45%, which does not exist, and the
driver would then round to something the rider never chose.

**Automatic control against ambient**, with hysteresis, and it watches the supply:
the controllers run off the bike, so the app knows when the engine is off and the
heat is coming out of the battery.

---

## Layout

```
firmware/     ESP32-S3 (LilyGO T-2CANFD, MCP2518FD). PlatformIO.
app/          Android cluster. Kotlin, plain Views and Canvas, no Compose.
docs/         The protocol, the decode plan, and the method.
```

| document | what it is |
|---|---|
| [`PROTOCOL.md`](docs/PROTOCOL.md) | Every field, over MQTT and over BLE, and what each one means |
| [`DECODE-PLAN.md`](docs/DECODE-PLAN.md) | What is known, what is not, what has been ruled out and why |
| [`GARAGE-RUN.md`](docs/GARAGE-RUN.md) | Eight test sessions written up as they happened, including the ones that failed and why |
| [`UNEXPLORED-BYTES.md`](docs/UNEXPLORED-BYTES.md) | Every byte that varies and is not yet read |
| [`KEIS-PROTOCOL.md`](docs/KEIS-PROTOCOL.md) | The heated clothing BLE protocol |
| [`DTC-CODES.md`](docs/DTC-CODES.md) | The fault code tables |
| [`FLASHING.md`](docs/FLASHING.md) · [`OTA.md`](docs/OTA.md) | Getting firmware onto the board, by cable and over the air |
| [`BUILD-SETUP.md`](docs/BUILD-SETUP.md) · [`WORKFLOW.md`](docs/WORKFLOW.md) | Building the app, and the traps that cost an afternoon each |
| [`SKILLS.md`](docs/SKILLS.md) | A handover note: the machine, the bus, the method, and eight ways to be wrong |

**Start with [`docs/PROTOCOL.md`](docs/PROTOCOL.md)** — every field, over MQTT and
over BLE, and what each one actually means.

**Then [`docs/DECODE-PLAN.md`](docs/DECODE-PLAN.md)** — what is known, what is not,
what has been ruled out and why. It is the most useful document here for anybody
doing the same work on their own machine.

---

## Building it

**Firmware.** Copy `firmware/src/config.example.h` to `config.h` and fill in your
WiFi, your MQTT broker and **your own BLE passkey**. Then:

```
cd firmware && pio run -e sniffer-t2can -t upload
```

Over-the-air updates are supported after the first flash; see
[`docs/OTA.md`](docs/OTA.md).

**App.** Android Studio, or `./gradlew assembleDebug`. Note that the bundled JDK
is too new for this toolchain — see [`docs/BUILD-SETUP.md`](docs/BUILD-SETUP.md).

**Firmware and app must be built from the same commit.** The BLE packet is a fixed
eight bytes with a flag bitfield, and bit meanings have changed between versions.
Pairing an old app with new firmware will show one signal as another.

---

## The method

Most of what is here was found the same way, and the documents say so at each step:

1. **Look up what the standard says, then treat it as a guess.** Indian does not
   always follow J1939's byte layout. Ambient temperature is the proof: the
   standard puts it in bytes 5–6 and this bike puts it in 4–5. The decode is
   empirically right and structurally wrong, and it works — it was later
   confirmed by a physical measurement it had predicted.
2. **Correlate against something already known**, and check the lag. A correlation
   of 1.00 at zero lag means you have found a copy of a signal, not a new one.
3. **Design one manoeuvre that separates the survivors**, and hold each state for
   eight seconds. Tapping mixes bits.
4. **A test that reports nothing must first be shown capable of reporting
   something.** Every null here was checked with a known control — an indicator
   flicked in the same run — before it was believed. Three conclusions were
   overturned by that rule, all of them because the instrument was at fault rather
   than the bus.
5. **Twice is a candidate. Once is a coincidence.**

Four decodes were shipped on guesses and later withdrawn. Each keeps its `case`
statement and a comment saying what it actually turned out to be, because the
withdrawal is more useful to the next person than a clean file would be.

---

## Credit

**Built and tested on a 2017 Indian Springfield by Nanna Agesen**
([@Prinsessen](https://github.com/Prinsessen)) — who rode it, pressed the buttons,
and checked the tyre pressures with a gauge when the maths said something was
wrong.

Code and analysis written with Claude (Anthropic).

## Licence

MIT. See [LICENSE](LICENSE).

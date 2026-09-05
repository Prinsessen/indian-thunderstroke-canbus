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
| **Grip heater level and both grip temperatures** | |
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

## Layout

```
firmware/     ESP32-S3 (LilyGO T-2CANFD, MCP2518FD). PlatformIO.
app/          Android cluster. Kotlin, plain Views and Canvas, no Compose.
docs/         The protocol, the decode plan, and the method.
```

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

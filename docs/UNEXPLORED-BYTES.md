# Unexplored bytes — what is left on this bus

Every byte that **varies** in the 2026-08-15 captures and that the firmware does
not read. Generated from 42,365 frames, not from a standard document: the
observed values are measured, and only the "what it should be" column comes from
J1939-71.

Written 2026-09-04, after counting these for the first time and finding the
throttle in the very first one examined. It had been sitting in PGN 65266 byte 7
-- a message we had decoded one field of out of four -- for as long as we had
been looking for it elsewhere.

---

## Read this before using the table

**The captures contain changes, not transmissions.** The firmware publishes a
frame only when its payload differs from the previous one on that ID
(`updateTable()` sets the dirty flag on `memcmp` alone), and the capture tool
records that stream. So every "frames" count in this file — and the 42,365 total
above — is a count of *changes*, not of messages sent.

For finding varying bytes this costs nothing: a change-only stream keeps every
change by definition, which is precisely what the table is built from. But it
makes three things invalid, and they are worth naming because each looks
plausible:

- **Message rates cannot be derived.** PGN 65381's 299 records over four rides
  say the message changed 299 times, not that it is slow. Do not conclude
  anything about how often a module talks.
- **Periodic versus event-driven cannot be told apart** from these files at all.
  Both look identical after deduplication.
- **Lag and correlation are computed on irregular samples.** The throttle result
  survives this — it was confirmed against the standard and the manual — but a
  marginal correlation found this way is weaker than it looks.

A byte that holds one value in every record still genuinely held that value
every time the message was seen to change, so "constant across N frames"
remains a fair statement. It just is not a statement about N transmissions.

**Indian does not always follow the standard byte layout.** Ambient temperature
is the proof: J1939 puts it in bytes 5-6 of PGN 65269 and this bike puts it in
4-5, where the standard expects cab interior temperature. Our decode is
empirically right and structurally wrong, and it works. So the standard is a
hypothesis generator here, never an answer.

**The method that works**, in order, and it is the same one that found the
throttle:

1. Look up what the standard puts in that byte. Treat it as a guess.
2. Check the range. A byte spanning 0-250 in steps that reach both ends is a
   percentage; one centred on 125 is a signed value; one that only ever holds
   two values is a switch.
3. Correlate against something known -- speed, rpm, throttle. `r` near 1.00 at
   zero lag means you have found a copy of that signal, not a new one.
4. **Check the lag.** This is what separated the throttle from the tachometer:
   the real throttle leads engine speed by about a second, and the false one
   peaked at zero lag because it *was* engine speed.
5. Design one manoeuvre that separates the survivors, and hold each state for
   eight seconds.

**A test that reports nothing must first be shown capable of reporting
something.** Every null result in this project has been checked with a known
control -- flick an indicator, work the headlight -- before being believed.

---

## The table

| PGN | SA | byte | values | range | what the standard says | odds | note |
|---|---|---|---|---|---|---|---|
| 65382 | 0 | 0 | 255 | 0–255 | Proprietary | **HIGH** | 255 values on 4,721 frames -- the busiest unexamined byte on the bus. Byte 2 of this PGN was the false throttle (rpm/256), so this message clearly carries engine data. Prime candidate for load, ignition advance or injector duty. |
| 65382 | 0 | 3 | 80 | 29–252 | Proprietary | **SOLVED** | **Range to empty, in km, one count per km** (2026-09-08). Paired against the dash twice on one evening. See below. |
| 61444 | 0 | 7 | 75 | 103–185 | EEC1 byte 8 — Engine Demand Percent Torque (SPN 2432), offset -125 | **HIGH** | Negative means overrun braking. Range 103-179 reads as -22 % to +54 %, which is exactly right for a bike that coasts and pulls. Cheap: correlate against throttle now that we have it. |
| 65215 | 11 | 4 | 88 | 39–220 | EBC2 byte 5 — Relative Speed, Rear Axle Left Wheel (SPN 907) | **HIGH** | 88 values, r = +0.84 against speed, offset 125 = zero. If that scaling holds this is the REAR wheel from the ABS module -- a third speed source, and a direct cross-check on the one the dash uses. |
| 65265 | 11 | 0 | 2 | 63–127 | CCVS byte 1 — parking brake, two-speed axle, cruise pause | **MEDIUM** | Two values from SA 11. Two-bit fields; worth one controlled test. |
| 65381 | 39 | 1 | 4 | 3–67 | Proprietary | **MEDIUM** | Four values, moves with the headlight switch. Probably a lamp state we do not need. |
| 61441 | 11 | 5 | 3 | 204–220 | EBC1 byte 6 — ABS/EBS related | **MEDIUM** | Only 41 frames and three values, all of which also appear in the cruise switch byte. Could be a mirror. Ten minutes. |
| 65390 | 39 | 0 | 2 | 223–255 | Proprietary | **MEDIUM** | Two values, one bit. Stepped at the start of the sidestand test. The PGN the front brake was withdrawn from; bit 5 is unexplained. |
| 61445 | 39 | 4 | 2 | 32–83 | ETC2 byte 5 — transmission field | **LOW** | Two values only. Indian already deviates in this PGN (we read the gear as ASCII in byte 6, which is not the standard), so the standard is a weak guide here. |
| 65381 | 39 | 2 | 2 | 252–253 | Proprietary | **LOW** | Two values. |
| 65381 | 39 | 3 | 2 | 243–255 | Proprietary | **SOLVED** | Bit 2 is the START BUTTON, set while pressed (2026-09-06). Rated LOW on the note "moved during the lights test" -- it had been seen and mis-scored, and a deliberate five-press run settled it in three minutes. |
| 65386 | 39 | 1 | 2 | 252–253 | Proprietary | **LOW** | Two values, moves with the headlight. Byte 1 is the ignition/wake bit and byte 3 is the grips. |
| 65265 | 39 | 4 | 3 | 204–220 | CCVS byte 5 — cruise Set/Decel + Resume/Accel switches | **SOLVED** | Momentary presses, understood. The decode was withdrawn because it read a button as a state — not because the state is absent. Byte 4 is the state, and it is untested: cruise was never engaged while capturing. |
| 65382 | 0 | 1 | 12 | 0–11 | Proprietary | **SOLVED** | rpm/256. The withdrawn throttle. |
| 65276 | 23 | 1 | 33 | 140–235 | DD byte 2 — Fuel Level 1 (SPN 96) from SA 23 | **SOLVED** | The second opinion on the tank. Deliberately not used: SA 0 was chosen for determinism. |
| 65265 | 39 | 6 | 16 | 15–255 | Cruise Control Input Message Counter (U1405) | **NONE** | Named by the service manual. Not a signal; low nibble always 0xF. |
| 65265 | 39 | 7 | 29 | 4–245 | Cruise Control Input Checksum (SPN 524079, U0405) | **NONE** | Named by the service manual, which also confirms this message is the cruise control message. |
| 65265 | 0 | 6 | 2 | 240–241 | CCVS byte 7 — PTO state | **NONE** | Message counter, not a signal. Low nibble is always 0xF and it does not step sequentially. |
| 65381 | 0 | 7 | 2 | 252–253 | Proprietary (SA 0) | **NONE** | Toggles every 15 seconds regardless of anything. A heartbeat bit. |

| 65266 | 0 | 7 | 8 | 0–250 | Engine Throttle Valve 1 Position (SPN 51) | **OPEN** | Decoded and correct — but it visits two separate resting values with the engine stopped. See below. |

`SOLVED` and `NONE` are listed so nobody spends an evening on them again.

---

---

## Open question: the throttle byte has two resting values

**PGN 65266 SA 0 byte 7 (SPN 51), engine stopped.** The decode itself is settled
and the scaling is right — 250 raw is exactly 100.0 %, which is the top of the
J1939 single-byte percent range, and full throttle lands on it. What is not
settled is where the plate rests when nothing is touching it.

The sensor reports whenever the ignition is on; it does not need the engine
running. The garage session of 2026-09-05 proves that on its own: 153 changes on
this byte across nine hours with the engine never started.

Measured against real engine speed from PGN 61444:

| state | median | max |
|---|---|---|
| engine stopped, < 50 rpm | 5.6 – 6.0 % | — |
| idle, 50–1200 rpm | 4.4 – 4.8 % | 12 % |
| riding, > 1200 rpm | 9.6 – 11.2 % | 100 % |

So a parked machine reads about 6 %, which is a throttle plate sitting on its
idle stop rather than closed. That much is expected.

**What is not expected:** in the garage the same byte also visited 0–1.6 %,
twenty-six times, interleaved through the whole day with the normal 4.0–4.8 %
readings. Not a separate session — the two clusters alternate. With the engine
stopped and nobody riding, the plate should sit in one place.

Three candidates, none of them tested:

1. The grip was being turned and released during the tests, and the return
   spring does not always leave the plate in the same place.
2. The sensor or the ECU reports zero briefly across an ignition cycle, before
   the first real reading.
3. Something else moves the plate with the engine off — an idle actuator
   self-checking, for instance.

**The measurement that would settle it** is cheap and needs no ride: ignition on,
hands off the machine, log PGN 65266 byte 7 for a few minutes, then cycle the
ignition twice while still not touching the grip. If it moves on its own, it is
(2) or (3); if it only moves when the grip does, it is (1).

Worth doing because a resting value that is not stable is worth knowing about
before anyone builds an alert on the throttle reading.

## Solved: the range to empty — PGN 65382 SA 0 byte 3

**One count per kilometre, unscaled, no offset.** The number the original dash
displays as range to empty. Found 2026-09-08.

The probe prints this byte as `b4`, because `probe/throttle` names bytes from one
and this table names them from zero. Same byte.

### How it was settled

The candidate came out of this table: 80 distinct values over 29–252, in a
message already known to carry engine data. It correlated with fuel level at
r = +0.990 across both August rides, which was the reason to look and also the
reason to distrust it — a rescaled fuel gauge would correlate just as well.

Two paired readings settled it. The owner switched the ignition on, read the dash
and reported the number; the probe logged the byte at 2 Hz over the same seconds.

| when | dash | byte 3 |
|---|---|---|
| 2026-09-08 19:57 | 212 km | *no data — the board was still joining WiFi* |
| 2026-09-08 20:05–20:06 | **212 km** | **212**, on all 58 samples |

The first attempt is listed because it is the useful half of the lesson: WiFi
wake takes up to a minute after the bus goes live, so a reading taken in the
first minute of ignition-on gets nothing. Switch on, wait, then read the dash.

### Why it is not simply the fuel gauge rescaled

This is the part that needed proving, and the ride data initially argued against
the hypothesis: byte 3 climbs 173 → 252 early in a ride and falls back later,
tracking the tank sender's slosh almost exactly.

The retained telemetry from the evening of the test breaks the tie:

| when | fuel | byte 3 | km per fuel-% |
|---|---|---|---|
| 2026-08-15 13:36 | 94.4 % | 251 | 2.66 |
| 2026-08-15 15:12 | 82.8 % | 222 | 2.68 |
| 2026-09-08 20:05 | 100 % | **212** | **2.12** |

The tank was **fuller** than at any point in August and the byte was **lower**.
A rescaled fuel level at 100 % would have had to read at least 265, which does
not fit in a byte at all. So byte 3 is not a function of fuel alone — and it
agreed with the dash to the digit.

The drift is itself informative. Against the 20.8 l tank, 2.66 km per fuel-%
is 7.8 l/100 km and 2.12 is 9.8 l/100 km, while the ECU's own `fuelEconomy` in
the same packet reads 6.6. **The dash computes range from a recent-consumption
window, not from the lifetime average.** That is why the displayed range is
consistently pessimistic against what the machine actually averages.

### Confirmed live, 2026-09-08 20:33

The decode shipped in firmware 2026.09.08-1 and was watched from openHAB at 1 Hz
while the owner sat on the machine and started it. The log separates two causes
that the test had expected to be tangled together:

| time | what happened | range |
|---|---|---|
| 20:32:07 | machine righted off the stand, lean 113 -> 127, stand UPRIGHT | 212, unmoved |
| 20:32:53 | start button, engine fires, fuel rate 0 -> 5.05 L/h | 212 |
| 20:33:04 | eleven seconds of idling later | **211** |

**Attitude does nothing.** Forty-six seconds upright and the number did not
move, so the ECU damps the tank sender rather than reporting it raw. That was
one of the two outcomes the test was set up to distinguish and it is worth
having: a range reading that ignored attitude was not the safe assumption.

**Idling moves it, and the arithmetic is the point.** Eleven seconds at
2-5 L/h is about 0.015 litres burned. One count of range is roughly 0.098
litres at the ratio measured that evening. So six times too little fuel was
consumed to account for a 1 km drop, and the tank gauge itself never left
100 %. The tank did not fall -- **the estimator changed its mind**, because
idling is poor economy and the consumption window updated.

That is the last thing a rescaled fuel gauge could not do, and it closes the
question. The dash also read 211 at the same moment, so this is a second
paired reading, and this time a paired *change* rather than a static match.

The filter behaved: a one-count step is far under RANGE_STEP_KM and went
through with no delay.

### Two things a decode must handle

**The 255 ceiling is reachable.** Byte 4 is 0 in all 4,721 frames, so no 16-bit
high byte is confirmed, and the highest value ever observed is 252. At the
lifetime 6.6 l/100 km a full tank is 315 km, which does not fit. What the ECU
does above 255 has not been seen: it may saturate, or 255 may mean not
available in the usual J1939 way. Do not assume — a tank filled after a gentle
run is the measurement that would settle it.

**Bursts of exactly 29.** Twelve frames across three separate moments, always in
runs of exactly three, always the value 29, then straight back to the previous
reading. Fuel was 56–75 % each time and nothing else moved, so it is not a real
low-range warning. It is too clean to be noise and too regular to be a glitch;
the cause is unknown. Any decode must reject a lone dip to 29 rather than publish
it, or the app will show a 29 km panic three times a ride.

## Where the remaining value probably is

**PGN 65382, byte 1.** The busiest unexamined byte on the bus -- 255 distinct
values across 4,721 frames -- in a proprietary message that has now given up two
of its four live bytes: byte 2 is engine speed over 256, and byte 4 is the dash's
range to empty. Whatever Indian keeps private about how the engine is running is
most likely in what remains. Load, ignition advance and injector duty are all
plausible and all testable now that the throttle is known: hold a steady throttle
and change the load, and watch which byte follows the load rather than the hand.

**PGN 65215 byte 5.** If the standard holds, the rear wheel speed straight from
the ABS module -- a third speed source and an independent check on the one the
dash uses. Given the sensor history on this bike that is worth having.

**PGN 61444 byte 8.** Engine demand torque, signed around 125, so negative
values are engine braking. Cheap to confirm now: it should track the throttle
closely and go negative the moment the throttle shuts.

---

## What is not here, and will not be

Passive listening has been close to exhausted. These are established absences,
each checked with a working detector rather than assumed:

- **Lean angle.** No IMU. The tilt sensor reads upright through every corner.
- **Sidestand switch, as a STATE.** Checked byte by byte across the whole bus
  while the stand was worked up and down, and nothing moved but the tilt. The
  search was right and the conclusion went one step too far: it does appear as
  an **event**. Try to start with the stand down and DM1 carries SPN 520267 FMI
  31 -- Indian's P181C, "engine disabled due to extended kickstand" -- then
  clears two seconds later. The position is private; the refusal is public.
  A change detector cannot see something that is only ever reported at the
  moment it matters.
- **Trip 2.** PGN 65217 has exactly two distance fields and both are used.
- **Cruise engaged**, and **cruise set speed**. Transmitted as permanently
  "not available".

What remains beyond the table needs the sniffer to **transmit** -- a J1939
request rather than passive listening. That would open DM2 (stored faults, which
for this bike's history is the most valuable thing on the list), DM4 (freeze
frames: the conditions when a fault occurred), and any parameter answered on
demand rather than broadcast. It also means leaving hardware listen-only mode,
which is a decision with its own weight and is recorded in `TX_ENABLED` in
`src/config.h`.

**[TRANSMIT.md](TRANSMIT.md) works that out in full**: the ranking, the cost in
address claims and error frames, the two PGNs that must never be callable, and
the garage protocol for a first attempt.

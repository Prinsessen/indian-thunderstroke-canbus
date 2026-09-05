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
| 65382 | 0 | 3 | 80 | 29–252 | Proprietary | **HIGH** | 80 values. Same message as above, same reasoning. |
| 61444 | 0 | 7 | 75 | 103–185 | EEC1 byte 8 — Engine Demand Percent Torque (SPN 2432), offset -125 | **HIGH** | Negative means overrun braking. Range 103-179 reads as -22 % to +54 %, which is exactly right for a bike that coasts and pulls. Cheap: correlate against throttle now that we have it. |
| 65215 | 11 | 4 | 88 | 39–220 | EBC2 byte 5 — Relative Speed, Rear Axle Left Wheel (SPN 907) | **HIGH** | 88 values, r = +0.84 against speed, offset 125 = zero. If that scaling holds this is the REAR wheel from the ABS module -- a third speed source, and a direct cross-check on the one the dash uses. |
| 65265 | 11 | 0 | 2 | 63–127 | CCVS byte 1 — parking brake, two-speed axle, cruise pause | **MEDIUM** | Two values from SA 11. Two-bit fields; worth one controlled test. |
| 65381 | 39 | 1 | 4 | 3–67 | Proprietary | **MEDIUM** | Four values, moves with the headlight switch. Probably a lamp state we do not need. |
| 61441 | 11 | 5 | 3 | 204–220 | EBC1 byte 6 — ABS/EBS related | **MEDIUM** | Only 41 frames and three values, all of which also appear in the cruise switch byte. Could be a mirror. Ten minutes. |
| 65390 | 39 | 0 | 2 | 223–255 | Proprietary | **MEDIUM** | Two values, one bit. Stepped at the start of the sidestand test. The PGN the front brake was withdrawn from; bit 5 is unexplained. |
| 61445 | 39 | 4 | 2 | 32–83 | ETC2 byte 5 — transmission field | **LOW** | Two values only. Indian already deviates in this PGN (we read the gear as ASCII in byte 6, which is not the standard), so the standard is a weak guide here. |
| 65381 | 39 | 2 | 2 | 252–253 | Proprietary | **LOW** | Two values. |
| 65381 | 39 | 3 | 2 | 243–255 | Proprietary | **LOW** | Two values, moved during the lights test. |
| 65386 | 39 | 1 | 2 | 252–253 | Proprietary | **LOW** | Two values, moves with the headlight. Byte 1 is the ignition/wake bit and byte 3 is the grips. |
| 65265 | 39 | 4 | 3 | 204–220 | CCVS byte 5 — cruise Set/Decel + Resume/Accel switches | **SOLVED** | Momentary presses, understood. The decode was withdrawn because it read a button as a state — not because the state is absent. Byte 4 is the state, and it is untested: cruise was never engaged while capturing. |
| 65382 | 0 | 1 | 12 | 0–11 | Proprietary | **SOLVED** | rpm/256. The withdrawn throttle. |
| 65276 | 23 | 1 | 33 | 140–235 | DD byte 2 — Fuel Level 1 (SPN 96) from SA 23 | **SOLVED** | The second opinion on the tank. Deliberately not used: SA 0 was chosen for determinism. |
| 65265 | 39 | 6 | 16 | 15–255 | Cruise Control Input Message Counter (U1405) | **NONE** | Named by the service manual. Not a signal; low nibble always 0xF. |
| 65265 | 39 | 7 | 29 | 4–245 | Cruise Control Input Checksum (SPN 524079, U0405) | **NONE** | Named by the service manual, which also confirms this message is the cruise control message. |
| 65265 | 0 | 6 | 2 | 240–241 | CCVS byte 7 — PTO state | **NONE** | Message counter, not a signal. Low nibble is always 0xF and it does not step sequentially. |
| 65381 | 0 | 7 | 2 | 252–253 | Proprietary (SA 0) | **NONE** | Toggles every 15 seconds regardless of anything. A heartbeat bit. |

`SOLVED` and `NONE` are listed so nobody spends an evening on them again.

---

## Where the remaining value probably is

**PGN 65382, bytes 1 and 4.** The busiest unexamined bytes on the bus -- 255 and
80 distinct values across 4,721 frames -- in a proprietary message that already
proved to carry engine data, since its byte 2 is engine speed over 256. Whatever
Indian keeps private about how the engine is running is most likely here. Load,
ignition advance and injector duty are all plausible and all testable now that
the throttle is known: hold a steady throttle and change the load, and watch
which byte follows the load rather than the hand.

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

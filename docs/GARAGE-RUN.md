# Garage run sheet — stationary hunts, one at a time

Everything here is done with the bike **parked, ignition on, engine off**. No
riding, no movement, nothing transmitted onto the bus. Written 2026-09-05 from
the service-manual cross-reference in [DECODE-PLAN.md](DECODE-PLAN.md).

---

## Before you start

```bash
tools/probe_watch.sh
```

Four things about that probe that decide whether the evening works:

1. **It is stationary-only** — `probeFrame()` returns above 2 km/h. That is
   exactly right for this list, and exactly wrong for the cruise test, which is
   why cruise is on the ride sheet and not here.
2. **The first frame of every message is swallowed.** The probe takes the first
   sighting of each PGN+source as its baseline and says nothing. So let it run
   **thirty seconds before touching anything** — otherwise the first control you
   work is compared against nothing and reports nothing.
3. **Hold, do not tap.** Eight seconds on, five seconds off. A tap can fall
   inside the 80 ms publish gate and vanish; worse, two quick taps mix their
   bits and read as one change.
4. **Some bytes are masked.** Known-noisy bytes are filtered per PGN so the log
   stays readable. PGN 65381, the main suspect below, has **no mask** — every
   byte of it is reported. But if a control produces nothing at all, a mask is
   one of the reasons it could be lying, and `probeMasks[]` in `main.cpp` is the
   place to check.

**The control that makes any of this trustworthy:** start by flicking an
indicator. It is known, it is visible, and it must show in the log. If it does
not, the probe is not working and every "nothing happened" that follows is
worthless. *A test that reports nothing must first be shown capable of reporting
something.*

---

## 1. The switch sweep — the big one

**What it is for:** SPN 520329, "Operator Switch Status (pOSS1)", is one of the
seven signals the manual confirms is broadcast. We have never looked for it. The
best-shaped candidate is **PGN 65381 from SA 39**, which has five bits nobody
can explain. See Tier 2d in DECODE-PLAN.md.

**Why it matters more than it sounds:** the horn and the front brake were both
withdrawn this week for being decoded off the wrong byte. A switch-status
message is where they should have been all along. This is their second chance.

Work these **one at a time**, eight seconds each, five seconds between. Say each
one out loud as you do it if you are logging by ear — the timestamps are what
you match against afterwards.

| # | Control | Hold | Watch for |
|---|---|---|---|
| 1 | Left indicator | 8 s | The control. Must appear. |
| 2 | Right indicator | 8 s | Should mirror #1 in a neighbouring bit |
| 3 | Hazards | 8 s | Often both indicator bits at once |
| 4 | **Front brake lever** | 8 s | A withdrawn decode, second chance |
| 5 | Rear brake pedal | 8 s | Manual calls it SPN 520323 |
| 6 | High-beam flash (pass) | 8 s | Momentary, distinct from the hi/lo switch |
| 7 | Headlight hi ↔ lo | 8 s | Already decoded — a second known control |
| 8 | **Fog lamp switch** | 8 s | SPN 520291/520292 exist in the fault table |
| 9 | Cruise enable rocker | 8 s | SPN 596. Does *enabled* show while parked? |
| 10 | Cruise SET, then RESUME | 8 s | Known: PGN 65265 byte 5 |
| 11 | Grip heater, one step up | 8 s | Already decoded — a third known control |
| 12 | **Horn** | **1 s ×4** | A withdrawn decode. See below — do not hold it. |

### The horn is the exception: one second, four times, and last

The bike has a compressor horn that pulls a heavy current, so the eight-second
hold is wrong for it on two counts.

**One second is enough for detection.** The eight seconds is not a detection
requirement — it exists so a change is unambiguously attributable to one control
and so the timestamps are easy to match afterwards. What detection actually
needs is for the message to be transmitted at least once while the horn is on
and once after it is off. **Four one-second presses with five seconds between
them are better evidence than one eight-second hold**, because the acceptance
rule here is that a bit moved *twice* and stayed still for everything else. A
repeated pattern satisfies that on its own; a single transition never does.

**It goes last in the sweep.** The engine is off, so nothing is charging the
battery, and a compressor horn on a parked bike is the largest load in this
list. Put it at the end and a flat battery costs you one test instead of eleven.

**And it brings a confound nothing else here has.** A heavy current draw sags the
system voltage, and a byte that moves during a horn press may be responding to
the sag rather than to the switch. Battery voltage is PGN 65271 bytes 4-5, and
those two bytes are masked out of the probe, so the log will not show you the
sag even though it is happening — watch `canbus/springfield/state` alongside if
you want to see it.

**The discriminator is clean, though:** a switch bit flips between exactly two
values and returns to precisely its previous one. A byte tracking voltage drifts
back and lands *near* where it was, not exactly on it. If your candidate returns
to the same value every single time, it is a switch.

Do **not** press the starter. The engine is meant to stay off, and a crank
floods the log with engine data at the moment you least want it.

Three of these (1, 8, 12) are already understood. They are in the list on
purpose: they prove the rig is working, spaced through the run rather than only
at the start, so a probe that dies halfway is caught.

---

## 2. The immobiliser — two power-ups

**What it is for:** SPN 520330, the second of the two FMI 9 signals never hunted.
If it carries an authorised/not-authorised state it answers "is the key present"
directly.

**This one has a visible control, which is rare here:** the shield telltale on
the cluster. The dash says what the answer should be, so nothing has to be
inferred from behaviour.

**Run A — fob in your pocket.** Probe running, ignition on, sit for thirty
seconds, ignition off. Note whether the shield lights and, more importantly,
**when it goes out** — most telltales do a lamp test at every power-up, so the
fact that it came on means nothing on its own.

**Run B — fob left indoors**, well away from the bike. Same again. The bike will
refuse to start; that is the point, and it is a safe refusal.

Any byte that differs between A and B the same way the shield does is the
immobiliser status. Any byte that does not differ is excluded. Two runs, no
movement, and a control that cannot be argued with.

---

## 3. The key fob and the locks

**What it is for:** Tier 2b. SPN 520312 is "Power Lock Motor Switch" — the side
cases — and SPN 520304 is the fob's own battery voltage, which the bike watches.
The fob battery was changed 2026-09-04, so that reading should be healthy now
and is a free sanity check on whichever byte turns out to be carrying it.

Lock, then unlock, then the side cases if they are electrically operated. Hold
each for the usual eight seconds.

**One warning, and it is the reason this is a separate section:** if you go
hunting the turn-signal unlock sequence, you are writing your own unlock code
into a log file that lives in a git repository. Decide that deliberately.

---

## 4. Lean and the tipover sensor

**What it is for:** confirming the lean byte behaves, and seeing whether the
bike has anything to say about SPN 520200.

Watch `probe/2304` and rock the bike gently upright off the sidestand and back.
The byte rests at 113 parked and the readout shows the signed offset, so this is
a five-second check that the reading still tracks.

**Do not test the tipover sensor.** SPN 520200 FMI 14 fires when the machine
decides it has fallen over, and the only way to produce that deliberately is to
drop a motorcycle. It is on the list as something to *watch for* if it ever
happens, never as something to provoke. Whether it even reports with the
ignition off is an open question — see Tier 2e.

---

## Afterwards

The log is the evidence. Anything that moved gets written into
[UNEXPLORED-BYTES.md](UNEXPLORED-BYTES.md) with what was being done at the time,
and anything that moved **twice, and stayed still for everything else**, is a
candidate for a decode.

Once, and it is a coincidence until proven otherwise. That rule is what the four
withdrawals this week were bought with.

---

# Results — run 1, 2026-09-05 07:47–07:51

## The rig worked

Items 1-3 produced exactly what `main.cpp` already documents for PGN 65089
byte 1: `0x4F` left, `0x1F` right, `0x5F` hazards, flashing at about 1 Hz. The
control test passed, so the null results below are worth something — except
where noted, and the exception is large.

## Confirmed, nothing new

| Control | Seen | Verdict |
|---|---|---|
| Indicators, hazards | 65089 b1 bits 6/4, ~1 Hz | Already decoded. The control. |
| Headlight | 65381 b0 `10`→`00`→`40` | Already decoded — Low, off, High |
| Grip heater | 65386 b2 `00`→`19`→`32` | Already decoded — levels 0, 1, 2 |

## New — the switch layer, and it is where Tier 2d said it would be

The lamps and the *switches* are different messages, and PGN 65381 from SA 39
carries the switches. This is the first direct evidence for the pOSS1 thesis.

**Strong — a held state, not a blip:**

- **65381 SA 39 byte 2, bit 0 = hazard warning active.** Went `FC`→`FD` at
  07:48:37 and back at 07:48:51, spanning the hazard flashing at 07:48:38-52
  exactly. A state held for the duration of the control, which is what a status
  bit looks like and what a switch press does not.
- **65386 SA 39 byte 1, bit 0 = main beam active.** `FC`→`FD` at 07:49:38, back
  at 07:49:50, spanning the high-beam period exactly. Corroborates the
  "moves with the headlight" note in UNEXPLORED-BYTES.md.

**Candidate — momentary blips at the moment the switch was worked:**

65381 SA 39 byte 1 blipped three different bits, each one immediately before or
at the transition it would explain:

| Time | Change | What happened next |
|---|---|---|
| 07:48:10 | bit 2 (`03`→`07`) | Left indicator began 07:48:11 |
| 07:48:22 | bit 2, then bit 6 | Left ended, right began 07:48:23 |
| 07:48:23 | bit 4 (`03`→`13`) | Right indicator running |
| 07:48:36 | bit 6 (`03`→`43`) | Hazards began 07:48:38 |

Reads as: **bit 2 = left switch, bit 4 = right switch, bit 6 = cancel or the
hazard button.** Consistent, but every bit has been seen twice at most, and the
standing rule here is that twice is a candidate and once is a coincidence. One
more run settles it.

Also unexplained: 65381 b0 bit 0 blipped (`10`→`11`→`10`) at 07:51:19, at the
moment the grip heater stepped. Once only.

## Four tests could not have reported anything

Front brake, rear brake, cruise enable and cruise SET/RESUME produced silence,
and **the silence was the probe's, not the bus's.**

CCVS byte 4 (index 3) carries the brake switch, the clutch switch, cruise enable
and cruise active; byte 5 (index 4) carries Set/Decel and Resume/Accel. The
ignore mask for PGN 65265 was `0xDF`, which hid both. Those four controls were
worked correctly and reported nothing because nothing could be reported.

The mask is now `0xC6` — blind only on the speed pair and the counter/checksum.
**Re-flash and re-run items 4, 5, 9 and 10.**

This is the failure the run sheet warns about, three sections above the table it
ruined, written two hours earlier and never checked against the twelve controls
it was about to be used on.

## Genuinely open

**Fog lamps (item 8)** and **the horn (item 12)** produced nothing on any
unmasked message. That is a real null for 65381, 65386 and 65390 — but not for
65265, which was blind at the time. Both need one more attempt after the flash
before anything is concluded.

---

## Follow-up, same morning: the two open items

**Item 6 is confirmed and item 7 was the same control.** The owner flipped the
switch from dipped to main, held eight seconds, and flipped back. That is exactly
the 07:49:37 → 07:49:50 event, and it matches the documented decode. The bike
comes up on dipped beam at ignition-on, so there was no separate hi/lo test to
run — 6 and 7 are one control on this machine.

**The horn is very probably not on this bus.**

Four presses of one to two seconds each, after 07:51:20, and the log is empty to
the end of the session. That is a real null on every unmasked message — 65089,
65381, 65386, 65390 and the rest — and it is a null from a rig that had already
proved itself four times over on the same run.

The manual now explains it. **SPN 520293, "Horn"**, exists, but it carries only
two failure modes: FMI 5 "Open Circuit / Short to B+" (C122A) and FMI 6
"Grounded Circuit" (C122B). Both are C-codes, and both are *output driver*
faults. There is no FMI 9 "Abnormal Update Rate", which is what a signal expected
over the network gets, and no FMI 31 "Switch Stuck", which is what the real
switch signals get — SPN 596, 599 and 601 all have it. The wiring table settles
the shape: a `GY` wire labelled **HORN SWITCH OUTPUT** into the module, a `WH`
wire labelled **HORN POWER** out of it.

So the horn is a switch wired to a module input, driving a monitored output, with
no reason for anything to broadcast it. It is a plain circuit — which for a
safety item drawing this much current is exactly what one would design.

**Not moved to "Not on the bus" yet, and the reason matters.** That list now
carries a membership rule: a signal belongs there only if it was exercised during
a capture and still did not appear. The horn was properly exercised, but PGN
65265 was masked at the time, so one message never had its chance. Re-run the
horn once after the flash. If it is silent again with `0xC6` in place, it has
earned its place on that list honestly, and the withdrawn decode in `main.cpp`
can finally say why rather than only that it was wrong.

---

# Results — run 2, 2026-09-05 08:20–08:22, after the mask fix

Four controls that reported nothing yesterday reported cleanly today. The
silence really was the mask.

## Control test passed again

Left indicator at 08:20:09: PGN 65381 b1 bit 2 (the switch), then 65089 b1
`0F`⇄`4F` at 1 Hz (the lamp), and at 08:20:19 bit 6 of 65381 b1 blipped and the
flashing stopped. **That is the second sighting of both bits**, so under the
standing rule they graduate from candidate: **65381 SA 39 byte 1 bit 2 = left
indicator switch, bit 6 = cancel.**

## SPN 597 — NOT a find. Corrected 2026-09-05.

**This section originally reported the brake switch as a discovery. It was
already decoded, and had been for weeks.** `main.cpp` reads PGN 65265 from SA 0,
byte 4, bits 4-5 — the exact field below — and its comment already states that
it is the brake *light* switch which either control operates, and that this is
why both levers have always moved the same signal.

So the test confirmed an existing decode rather than finding a new one. That is
worth something — it is the fourth control this run that proved the rig — but it
is not a find, and calling it one was not checking the code before claiming.

What follows is the measurement, kept because it is good corroboration.

## SPN 597 — the brake switch, confirmed against the existing decode

```
08:20:20  SA0 byte4 0C->1C   SPN 597 BRAKE switch: off -> ON     (front lever)
08:20:31  SA0 byte4 1C->0C   SPN 597 BRAKE switch: ON -> off
08:20:33  SA0 byte4 0C->1C   SPN 597 BRAKE switch: off -> ON     (rear pedal)
08:20:45  SA0 byte4 1C->0C   SPN 597 BRAKE switch: ON -> off
```

**PGN 65265 from SA 0, byte 4, bits 4-5.** Clean, held for the duration, and
returning to exactly the same value each time — the switch signature, not a
voltage artefact.

**But front and rear are the same bit.** The lever and the pedal produced
identical transitions. The manual gives them separate identities — SPN 520322
Front Brake Switch (P159A) and SPN 520323 Rear Brake Switch (P159D) — so the
module knows which is which; it simply does not say so on the bus. What travels
is J1939's combined "brake applied".

So the withdrawn front-brake decode was hunting something that does not exist in
that form. There is a brake signal, and it is honest, but it cannot tell you
which brake.

## SPN 596 — cruise enable, and it rewrites the August captures

```
08:20:53  SA39 byte4 F3->F7   SPN 596 cruise ENABLE: off -> ON
08:21:07  SA39 byte4 F7->F3   SPN 596 cruise ENABLE: ON -> off
```

**`0xF7` is exactly the constant that byte 4 held across all 5,223 SA-39 frames
in the August captures.** It was read as "SPN 595 is 3, not available" and
nothing more. It is that — bits 0-1 are `11` — but bits 2-3 are `01`, which means
**cruise control was enabled for every one of those four rides.** The rocker was
on the whole time. It was simply never set.

That sharpens the ride test rather than changing it. The question was never
whether the system was awake; it was whether SPN 595 populates when cruise
actually engages. Now we know the enable half of the message works exactly as
the standard says, which makes it far more likely that the active half does too.

## SPN 599 and 601 — and the answer about accel/decel

```
08:21:11  SA39 byte5 CC->CD   SPN 599 SET:    off -> ON
08:21:21  SA39 byte5 CD->CC   SPN 599 SET:    ON -> off
08:21:24  SA39 byte5 CC->DC   SPN 601 RESUME: off -> ON
08:21:34  SA39 byte5 DC->CC   SPN 601 RESUME: ON -> off
```

SET moves bits 0-1 and nothing else. RESUME moves bits 4-5 and nothing else.

**SPN 600 (coast/decel) and SPN 602 (accel) stay at `11`, "not available", the
whole time.** They are not transmitted. So the two extra functions are not extra
signals: Indian sends SET and RESUME only, and the decelerate/accelerate meaning
is applied by the ECU according to whether cruise is already engaged — exactly
as the manual's "Set/Decel" and "Resume/Accel" naming implies, and now measured
rather than inferred.

That closes the question. There is nothing further to hunt for accel and decel.

## The horn, settled

Four presses of one second each after 08:21:34, with `0xC6` in place so PGN
65265 was visible for the first time. The log is empty to the end of the session.

That is eight presses across two runs, both with the rig proven working in the
same session, and the second with nothing masked. Combined with a manual that
gives the horn only output-driver faults and a harness that wires it switch-to-
module-to-output, this is as settled as anything here gets.

Moved to "Not on the bus — stop looking" in DECODE-PLAN.md, and the firmware
comment now says *why* rather than only that the old decode was wrong.


---

# Run 3 — 2026-09-05 09:42, verifying the app and openHAB end to end

Everything shipped this morning was confirmed on the bike, and the four already-
understood controls were re-run alongside so the new ones could be believed.

**Hazard, twice**, and both times the bit leads and trails the lamps by about a
second, which is the switch state preceding its own lamps:

```
09:42:16  hazard ON  ->  indicators flash 09:42:17-23  ->  09:42:23 hazard OFF
09:43:05  hazard ON  ->  indicators flash 09:43:06-11  ->  09:43:11 hazard OFF
```

**Cruise enable**, eight transitions, each one matched byte-for-byte in the
probe: `65265 SA 39 byte 4` moving `F7` to `F3` and back.

**The cruise switch now reports the legend on the rocker** -- `SET/DEC` and
`RES/ACC` -- which is firmware 2026.09.05-40 doing what it was asked.

**SPN 595 stayed unknown throughout**, which is correct on a stationary bike and
is the whole reason it is never published as OFF.

The BLE validity mask read `0xDA`, which is exactly right: bits 0 and 5 invalid
(the retired front brake and horn), bit 2 invalid (cruise engaged, genuinely
unknown), and bits 1, 3, 4, 6, 7 valid. The app showed `cruise unknown` rather
than inventing an answer.

Brake, headlight and grips all behaved, so the rig was sound for the whole run.

**One thing left, deliberately:** the BLE short key for the cylinder head
temperature is still `"ot"`, from when it was mislabelled oil temp. Firmware and
app must change it in the same release or one side sends a key the other does
not read, so it waits for the next time both are rebuilt. Both sites carry a
PAIRED CHANGE comment.


---

# Run 4 — 2026-09-05 10:04, the key fob hunt

## Saddlebag locks: not on the bus

Console lock/unlock switch and the fob's lock and unlock buttons, ignition on,
bus awake and confirmed so. **The actuators were heard to lock and unlock**, so
the switches did their job. Nothing appeared on the bus at all.

The first attempt had no control in it and was therefore worth nothing — that
was caught before it was believed rather than after. A left indicator flicked at
10:07:53 came through cleanly on both PGN 65089 and the switch bits in 65381,
seconds before the locks were worked again and produced silence. The rig was
proved capable in the same run as the null, which is the whole rule.

Consistent with the manual: SPN 520312 has only FMI 31 "Switch Stuck", an input
fault, and no FMI 9.

## Re-run with every byte open — and the locks left a fingerprint after all

The owner objected, correctly: if the fob can lock and unlock, something must
carry it. The null above was called settled while 30 bytes across 12 messages
were masked out of the probe, and there is a precedent against that confidence --
the throttle was eventually found in PGN 65266, the fuel economy message, after
months of looking elsewhere, and 65266 was masked on seven of its eight bytes.

**First re-run, all masks zeroed: invalid, and the control caught it.** PGN 65265
bytes 7 and 8 are the message counter and checksum, which change on every frame
by design. They produced 989 of 998 lines at about 8 per second against the
probe's ~12/second ceiling and starved everything else: a five-second indicator
flash, roughly ten transitions, got exactly ONE line through. Without an
indicator in that run the silence from the locks would have read as a clean null
from a probe that could barely report at all.

**Second re-run, counter pair masked and nothing else.** 22 indicator lines, so
the rig was healthy, and every other byte on the bus was visible.

The locks still sent nothing. But they did leave a trace:

```
10:24:57  65271 b4  FC -> FA   12.6 V -> 12.5 V
10:24:58  65271 b4  FA -> FC   back
10:25:04  65271 b4  FC -> F8   12.6 V -> 12.4 V
10:25:05  65271 b4  F8 -> FC   back
```

Two brief sags seven seconds apart, which is a lock motor drawing current and
the battery giving way for a moment -- and the timing of "lock, five seconds,
unlock". Both the console switch and the fob were used; two sags out of four
actuations is what a two-byte voltage reading shows, since only a dip large
enough to cross a byte boundary moves the low byte at all.

So the actuators demonstrably ran **while every byte on the bus was being
watched**, and the bus said nothing. That is a far better answer than the first
one: the fob is radio rather than CAN, it talks to a receiver, and the lock
outputs sit in the same box. The signal never leaves the module. The only thing
that escapes is the current the motors draw, and that shows up on the battery,
not on the network.

## Third re-run: every hole closed

The owner asked, before the next test, whether every module on the bus was
actually being watched for the lock. The sources were: the probe keys its table
by message AND source address, and all five (SA 0, 11, 23, 39, 136) were
covered, 44 pairs against 48 slots.

But the question exposed a fourth hole nobody had thought of. **The probe is
silent on the first sighting of a message** -- that sighting becomes the
baseline. So a message that only exists when a lock fires would have been filed
as normal and never reported. Firmware 2026.09.05-44 announces new messages
instead, and the lock test was run a third time.

**Result: one new message, and it was the clock.**

```
10:34:37  NEW pgn=65254 sa=39
```

PGN 65254 is the time/date message, resolved on 09-04. It is simply slow, and
had not yet arrived when the baseline was built. Nothing else announced itself.

The locks again showed only as current draw:

```
10:34:45  65271 b4  FC -> FA   12.6 V -> 12.5 V
10:34:46            FA -> FC
10:34:54  65271 b4  FC -> FA
10:34:55            FA -> FC
```

Twelve indicator lines in the same run proved the rig. So: every module watched,
every byte open but the counter pair, new messages announced, control proved,
and the actuators demonstrably running. The bus said nothing.

**Three of the four holes in the original null came from the owner refusing to
accept it.** Masked bytes, a starved probe, and unannounced new messages were
all found because "settled" was challenged rather than taken. The conclusion is
the same one it was this morning; it is now worth something.

## What the two nulls together are worth

The horn and the locks are the same shape: a switch wired into a module driving
an output in that module, with no reason to broadcast. So the working rule for
this bus is now **state yes, actuation no** — it carries what a rider reads and
not what a rider operates. That is worth more than either null on its own,
because it says where not to look next.

---

# Run 5 — 2026-09-05 10:37 and 10:41, the immobiliser

**Found, and it is what the withdrawn horn decode actually was.**

PGN 65386 SA 39 byte 0, bits 6-7, is a three-state field:

| bits | value | meaning |
|---|---|---|
| `00` | `0x3F` | fob authorised |
| `01` | `0x7F` | searching for the fob |
| `10` | `0xBF` | fob NOT detected |

**Round A, fob in a pocket:** `3F -> 7F -> 3F` inside one second. Dash shield
dark, which the manual says means found and settled.

**Round B, fob left indoors:** `3F -> 7F` held for **twenty seconds**, then
`-> BF`, shield lit and flashing, and the bike shut itself down. The manual
gives the same twenty seconds and describes the lamp as lit while searching and
flashing when the fob is not detected.

**The August captures agree without being asked.** The 14:43 ride begins
mid-wake and runs `7F -> BF -> 7F -> 3F` — a fob found on the second attempt.
`BF` appears nowhere during riding, only inside a wake sequence.

**And the owner then watched all three states in sequence on the display:** fob
in pocket gives OK, fob indoors gives SEARCHING, and the moment the bike shuts
down it reads NOT FOUND. That is a third independent line of evidence on top of
the bus data and the manual, and it is the one that matters most — a decode that
tracks a decision a person can watch the machine make.

## What this says about the horn

Byte 0 bit 6 was decoded as the horn on 2026-08-14 and withdrawn on 09-04 for
lighting a tell-tale every time the wake button was pressed with no sound. It was
never a horn: it is the security system looking for the key fob, and pressing
wake is exactly when it looks.

The comment written at the time even listed all three values — `0x3F` and `0xBF`
"with the bit clear", `0x7F` "with it set" — and read a three-state field as one
bit toggling. Everything needed was on the screen three weeks before anyone saw
it.

---

# Run 6 — 2026-09-05 11:10, the security alarm

**Not on the bus, and the security state is evaluated only at a wake.**

Run one after a clean reset (`BF -> 7F -> 3F` in one second with the fob
present, confirmed as `OK` on screen before anything else was touched):

| action | horn | bus | security |
|---|---|---|---|
| Double-press lock (arm) | **chirped** | never woke | stayed `OK` |
| Press unlock (disarm) | — | never woke | stayed `OK` |

The horn chirping is the control: the alarm demonstrably armed. The module did
something and told nobody, which is the third time this bus has done exactly
that — horn, saddlebag locks, and now the alarm.

## What this corrects about the security state

Earlier the state was seen going `NOT FOUND` while the fob was in the owner's
pocket, and that was explained as the field tracking proximity live. **That was
wrong.** The owner armed the alarm and walked indoors with the fob, and the
state stayed `OK` the whole way.

The right reading is narrower: **the search runs only when the bus is woken.**
It does not poll. If the fob is in range at that moment the state reads `OK` and
then stays there, because nothing asks again — which is also why it sat on
`NOT FOUND` for twenty minutes after the earlier run.

## An honest loose end

At 10:51:53 the bus did wake, and at 10:52:44 a full wake signature appeared,
identical to an ignition-on. Nothing found since explains it, and the clean
single-action tests above say neither arming nor unlocking wakes anything.

Two things make that earlier observation weak: the actions were tangled together
in one run, and the board had rebooted at 10:47 and was still round-robin
scanning for the bus bitrate. What was read as "the bus woke" may well have been
the *board* finally locking onto a bus that had been awake the whole time.

It cannot be settled after the fact. The clean runs outweigh the tangled one, so
the conclusion stands — but the loose end is written down rather than tidied
away, because it is the sort of thing that looks like a contradiction to whoever
reads this next.

---

# Run 7 — 2026-09-05, the cold-pressure check

**A calculation predicted a physical measurement, twice, before it was taken.**

The tyre page had been leading with the cold-corrected pressure while the bike's
own dash shows the sensor's raw figure, and the owner caught the gap: 35.4 on the
dash, 33.7 on the page, and a banner claiming 2.3 PSI under a 36 target. Her
objection was reasonable -- a few kilometres on a 17 °C day did not feel like
enough warmth to be worth 2.3 PSI.

Working the arithmetic backwards gave a tyre at 27.2 °C, ten degrees over
ambient, which is ordinary. Turning the correction the other way up -- warming
the target to the tyre rather than cooling the tyre to the target -- gave the
same verdict, so the deficit was not an artefact of direction.

Then she measured it. **Four hours after the ride, on properly cold tyres:**

| | app predicted, from the bus | tyre gauge |
|---|---|---|
| Front | 33.7 PSI | 33.5 – 34 |
| Rear | 39.5 PSI | 39.5 |

Within 0.2 PSI on one wheel and exact on the other.

## What that one measurement validated

Everything in the chain had to be right for those numbers to land, and any single
error would have moved them:

- the TPMS pressure decode (PGN 65268, `raw x 0.580152`)
- the TPMS temperature decode (`raw / 32 - 273`)
- the ambient source, PGN 65269 bytes 4-5 — which is decoded at a byte offset
  that disagrees with the J1939 standard, and has always been justified as
  "empirically right and structurally wrong"
- the gas-law correction, on absolute rather than gauge pressure
- and the assumption that TPMS temperature is the air, not the sensor housing

**The front tyre was genuinely 2.3 PSI low.** The banner was right, the owner's
suspicion was reasonable and wrong, and the machine was the honest party. It has
since been set to 36 front and 41 rear cold, which is what the app already had
as its targets.

---

# Run 8 — 2026-09-05 19:43, the sidestand, properly this time

**Settled: the switch exists, the cluster shows it, and the bus never hears it.**

The sidestand was ruled off the bus in August, but that test ran with the full
mask set -- and the owner then pointed out that her instrument carries an `(S)`
lamp, so something plainly knows. Re-run tonight with only the counter pair
masked, first sightings announcing themselves, and an indicator flash as control.

**The test was better than the one asked for.** She held the motorcycle upright
for the whole minute and flicked the stand through its switch point, watching the
lamp go on and off. That separates two explanations at once, which the planned
test would have needed a second run for.

The lean byte tells the story, and it was misread first time:

```
19:43:25   113          resting on the stand
19:43:30   128          lifted upright
19:43:32   126-128      HELD UPRIGHT for a minute, stand flicked repeatedly
19:44:37   115          back on the stand
```

127 is upright, not 113 -- `LEAN_UPRIGHT` in the firmware says so. The first
reading of this log had the polarity backwards and her account of what she
actually did is what caught it.

**So the lamp follows the switch, not the tilt.** Had the cluster been inferring
it from lean the way `standState()` does, it could not have changed at all while
the bike stood upright throughout.

**And the switch broadcasts nothing.** Every byte visible, new messages
announced, the control flash through cleanly, and the only thing that moved on
the bus was an arm lifting a motorcycle.

## The pattern holds, with one wrinkle

Horn, saddlebag locks, security alarm, sidestand: four switches, four modules
driving their own outputs, nothing told to anybody. This bus carries state a
rider reads and not actuation a rider performs.

The wrinkle is that a sidestand lamp *is* state a rider reads -- so the rule is
narrower than it first looked. What travels is state the **other modules** need.
The ECU has the sidestand, because it cuts the engine with the stand down and in
gear, and it reports it as a DM1 event (SPN 520267 FMI 31) when it blocks a
start. It simply never says so the rest of the time.

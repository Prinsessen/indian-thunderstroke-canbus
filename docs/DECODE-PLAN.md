# Decode plan — what is left on the bus, and in what order

A standing, prioritised list of the CAN work still to do on the 2017 Springfield,
written so a session can be picked up cold. Pairs with
the private working log (the running record of *how* things
were found) and [PROTOCOL.md](PROTOCOL.md) (what the firmware publishes today).

> **New here?** [SKILLS.md](SKILLS.md) is the handover note: how the work is
> done, where everything lives, the four modules on the bus, and the traps that
> have each already cost a day. Read it first; this file is the *what*, that one
> is the *how*.

Evidence base: **42,365 frames across four captures from 2026-08-15**, in
captures held privately. **They are not in this repository:** they carry the VIN
and odometer of one specific motorcycle, and nothing in the code needs them.
Every number below is measured from those files, not
taken from a J1939 table. Where a standard table and the captures disagree, the
captures win — that has already happened four times.

Last revised **2026-09-05**, after two days in which the cruise control, the horn, the saddlebag locks, the alarm and the sidestand were all settled -- four of them as nulls.

---

## The rule: discover wide, ship narrow

The firmware has two builds, and the split is the answer to *"in production only
what we actually use should be decoded"*:

| | `MODE_DISCOVERY` | `MODE_PRODUCTION` |
|---|---|---|
| Decodes | every PGN, raw, to MQTT | only confirmed signals |
| Output | `canbus/indian/pgn/<PGN>` + tooling | one `state` JSON + BLE `fast` |
| Purpose | finding things | riding with things |
| Board | the spare | the one on the bike |

So a new signal never enters production to "see what it does". It is found in
discovery, proven against a capture, and only then does it get a field. A PGN
that is understood but useless — the transport-protocol plumbing, the message
counters — stays understood and unshipped. Being decoded and being published are
two different decisions, and the second one needs a reason.

This is not bureaucracy. Every field in the production build costs a byte of BLE
payload, a line of JSON, an openHAB item, a persistence series, and a place on a
screen a rider looks at while moving.

**Four signals have been withdrawn after shipping**, each a thing on the
dashboard that was quietly lying:

| | withdrawn | what it really was |
|---|---|---|
| throttle, from PGN 65382 | 2026-09-02 | engine speed over 256 — **the real throttle was later found in 65266 and is live** |
| front brake | 2026-09-03 | the shared brake-light switch; both levers work it |
| cruise control | 2026-09-03 | the SET/RESUME button, not the engaged state |
| horn | 2026-09-04 | the bike waking up |

Note what is *not* on that list. The **headlight** was corrected, not withdrawn:
it read from both senders, and SA 0 sends nothing but `0xFF` filler, which
`& 0x40` reads as full beam. A source filter fixed it and it has worked since.
The two are easy to conflate because the same commit did both.

---

## What it takes to bring one PGN in

Five steps. Steps 1–3 need no bike; the cost is almost always step 4.

**1. Inventory it from the captures.** Which source addresses send it, how many
frames, which byte positions actually vary. A byte that never changes across
42,365 frames carries nothing, and `0xFF` / `0xFFFF` is J1939 for "not
available" — a constant `0xFF` means the module declines to send that signal at
all, and no amount of masking will conjure it.

**2. Check the standard table, then distrust it.** Look up the PGN in J1939-71.
It gives the layout to *test*, not the answer. On this bus, CCVS byte 5 was read
as a state for a year and is actually a switch byte; PGN 65382 byte 1 looked
like throttle and is rpm/256; EEC2, the standard home of throttle position, is
not broadcast here at all.

**3. Correlate against something known.** Speed and rpm are trustworthy and are
in every capture. A candidate byte that correlates `r ≈ ±1.00` with a known
signal *is* that signal in another guise, which is a finding, not a new one. A
candidate that correlates near zero is independent — more interesting, and
harder.

**4. Design one manoeuvre that separates the hypotheses.** This is the real
work, and it is done sitting on the bike. A good manoeuvre changes exactly one
thing and holds it for ~8 seconds, with pauses either side long enough to see
the signal at rest. Tapping a control mixes bits; holding it does not. If two
hypotheses both survive the manoeuvre, the manoeuvre was wrong — design a
better one rather than a longer capture.

**5. Decide whether it earns a production field.** A signal can be fully
understood and still not ship. Ask what it would change on the screen or in a
rule. If the answer is "it would be interesting", it belongs in the log, not in
`MODE_PRODUCTION`.

---

## Who is actually on the bus — the address claims

Added 2026-09-05. Every module announces itself on **PGN 60928** with a 64-bit
J1939 NAME that says what it *is*: manufacturer, function, instance. Those claims
have been in the captures since 2026-08-15, and for three weeks the module map
was assembled by guessing from traffic instead of reading them.

`tools/decode_names.py` reads them out of the captures:

> Note the captures these were read from are all from **15 August 2026**, one
> afternoon. Anything fitted or removed since would not show — which is exactly
> the trap the OBD-dongle hypothesis fell into.

| SA | claims | mfr | function | what we had guessed |
|---|---|---|---|---|
| 0 | 4 | 51 | **Engine** | ECU ✓ |
| 11 | 4 | 7 | *not in the standard table* | ABS |
| 23 | 4 | 340 | **Instrument cluster** | instrument cluster ✓ |
| 39 | 4 | 449 | **Management computer** | "VCU, body" ✓ in spirit |
| 136 | **8** | **146** | **Instrument cluster** | *"claims an address and then says nothing"* |

**The table is trustworthy because two entries check themselves.** SA 0 declares
Engine and sends engine data; SA 23 declares Instrument cluster and sends the
odometer, trip and ambient temperature. Two independent agreements between what
a module *says it is* and what it *does* is what makes function 30 believable for
SA 39 as well.

### SA 136 is a second instrument cluster from a different manufacturer

This is the find. SA 136 declares **the same function as the dash** (19) but a
**different manufacturer** (146 against the cluster's 340) — and it has never
sent anything else. Not one frame in 42,365 beyond its own claim.

It also claims **twice as often as anybody else**: eight times across four rides,
where every other module manages one per capture.

A module that is powered, announces itself more insistently than the rest, and
then has nothing whatever to contribute is not a broken module. It reads as
**fitted equipment of the display class that this bike gives no work to** —
an accessory, an audio or infotainment head, or a position the factory wired for
and this trim level does not use.

**Settled far enough to be useful, 2026-09-05 17:01.** Firmware
`2026.09.05-50` reports address claims — the probe had been filtering them out
as transport, which is why this took all day. SA 136 claimed again today,
alongside the ECU, the ABS and the cluster, in the same second the ignition went
on. It is **permanently fitted equipment on this motorcycle**, not something
that happened to be plugged in during the August captures.

Two hypotheses died on the way, and both were mine: that it was factory kit this
trim gives no work to, and that it was the owner's OBD dongle. The second was
disposed of by the owner in one line — the dongle and the sniffer share the
service connector, so they were never on the bus together.

**What it most likely is: the saddlebag lock module.** The owner found a
description of Polaris body-side lock modules that claim an address at ignition
and then transmit only on lock/unlock. The first half matches SA 136 exactly.
The second half is disproved by measurement — three separate runs with every
byte visible and the actuators demonstrably firing, and SA 136 sent nothing. So
the likely reading is that the module is there, it claims, and it never speaks
at all.

**What would name it outright:** manufacturer code 146 against the cluster's 340
in a J1939 manufacturer list. That is a lookup, not an experiment.

**One test was considered and rejected.** Disconnecting a lock actuator to
provoke a DTC would have named the reporting module — DM1 is decoded per source
address. The owner pointed out it would not fault at all, since riding without
the bags is normal; and separately, writing a self-inflicted fault into a machine
whose fault memory is evidence in a dealer dispute is not worth a module name.

### SA 11 does not decode, and that is itself worth a line

The ABS module's NAME gives function 194, vehicle system 78 and industry group 7.
Industry group is three bits and 7 is unused; vehicle system 78 is out of range
for any group. Reversing the byte order yields function 255, "not available",
which is at least a legitimate way for a supplier to decline to declare — but
reversing one module's NAME and not the other four is an assumption, not a
finding. Recorded as an anomaly rather than explained away.

---

## The bus, as it stands

30 PGNs appear in the captures. They fall into four groups:

**Shipped (16 PGNs)** — 61444, 61445, 65089, 65217, 65226, 65262, 65265, 65266,
65268, 65269, 65271, 65276, 65381, 65382, 65386, 65390. Not all of these still
decode something: 65390 and 65382 are present as documented withdrawals, which
is deliberate — the comment is what stops the same wrong decode being written a
third time.

**Transport, not data (4 PGNs)** — 59904 (request), 60160 (TP.DT), 60416
(TP.CM), 60928 (address claimed). Already handled by the reassembly layer.
Nothing to decode; do not treat these as unknowns.

**Understood and deliberately unshipped (1)** — 65242, the software ID, arrives
by TP/BAM and is already read for the VIN.

**Genuinely unknown (9 PGNs)** — the list below.

---

## What the service manual says, and how to read it

Cross-referenced 2026-09-05 against the *2017 Indian Motorcycle (Full-Size)
Service Manual* (PN 9927618 R03). The manual is not in this repository — it is
gitignored — but the diagnostic-trouble-code tables in section 4 are worth
mining, because they are written by the people who built the bus.

### The useful trick: FMI 9 means "I expect this over the network"

The DTC tables list a component, a failure mode (FMI) and a code. Most failure
modes describe a *wire*: open circuit, short to B+, voltage too low. But **FMI 9,
"Abnormal Update Rate"**, is different. A module can only complain that a signal
arrives too slowly if it expects that signal to arrive *periodically from
somewhere else* — which on this machine means CAN.

So every FMI 9 row is a manufacturer's admission that a given signal is
broadcast. There are exactly seven in the whole manual:

| SPN | Signal | Code | Status here |
|---|---|---|---|
| 84 | Vehicle Speed Signal | P160A | **Shipped** — PGN 65265 |
| 523 | Gear Sensor Signal | P1914 | **Shipped** — PGN 61445, and the subject of the glitch log |
| 5582 | Static Roll Angle | P1062 | **Shipped** — this is almost certainly our PGN 2304 lean byte |
| 520300 | Tire Pressure Sensor (Front) | C1085 | **Shipped** — PGN 65268 |
| 520302 | Tire Pressure Sensor (Rear) | C1090 | **Shipped** — PGN 65268 |
| 520329 | **Operator Switch Status (pOSS1)** | P1063 | **Not looked for.** See Tier 1 below. |
| 520330 | **Immobilizer** | P106A | **Not looked for.** |

Five of seven were already found, which is a fair independent check on the work
so far. Two were not, and one of them is the most interesting lead in this
document.

**SPN 5582 is a quiet confirmation.** The lean value was found empirically in
PGN 2304 and named "tilt" because that is what it looked like. The manual calls
it Static Roll Angle, sourced from SPN 520296, "Accelerometer" — so the signal is
real, it is on the bus by design, and our reading of it has a name.

### What the rest of the table is — and is not

Two limits, and ignoring either one produces a wild goose chase:

**1. The manual covers seven models, not this one.** It is the *Full-Size*
manual: Chief Classic, Dark Horse, Vintage, Springfield, Chieftain, Roadmaster
and Elite. The DTC table is the union across all of them. SPN 520294/520295,
"Windshield Motor Driver / Switch", is a Chieftain and Roadmaster part — the
Springfield's screen is quick-release and manual. Check the bike has the part
before hunting its signal.

**2. A diagnostic code proves a module *knows* a signal, not that it broadcasts
it.** The front brake switch is SPN 520322 and the rear is SPN 520323, so both
exist and both are named — but they are wires into a module, and whether that
module then puts them on the bus is a separate question the manual does not
answer. This is the same distinction that the cruise control section below was
caught getting wrong: presence in a table is not presence on a wire.

So: the FMI 9 list is evidence. Everything else below is a **candidate list with
a provenance** — better than guessing, weaker than a capture.

### Names for things we already had

| SPN | Manual's name | What it settles |
|---|---|---|
| 520298 | Heated Grips | Confirms the grips are a first-class signal, not a proprietary accident |
| 520322 / 520323 | Front / Rear Brake Switch | Both brakes are real named signals. The withdrawn front-brake decode was reading the wrong byte, not inventing a signal that does not exist |
| 599 / 601 | Cruise Set/Decel, Resume/Accel | One rocker each, two meanings — nothing was missed |
| 524079 | Cruise Control Input Checksum (+ counter, U1405) | Names CCVS bytes 7-8 and confirms SA 39's CCVS *is* the cruise message |
| 520296 | Accelerometer | The source behind SPN 5582 and our lean byte |

### Candidates worth knowing about, none yet hunted

Named in the tables, plausibly on this bike, never considered until now:

| SPN | Signal | Note |
|---|---|---|
| 520312 | Power Lock Motor Switch | The side-case locks already on the key-fob list in Tier 2b — now with a part name |
| 520304 | Key Fob (battery voltage) | The bike monitors fob battery. Relevant: the fob battery was changed 2026-09-04 |
| 98 | Engine Oil Level Sensor Switch | An air-cooled bike's oil is its cooling system. Worth more than most of Tier 3 |
| 520320 / 520321 | Brake Light / Tail Light | Lamp *outputs*, distinct from the brake switches |
| 524046 / 520297 | Start Button / System On Button | 520297 may be the wake bit already seen in PGN 65386 byte 1 |
| 520250 / 520251 | ABS Pulsar (front / rear) | "Pulsar" is the tone ring. Directly relevant to the wheel-sensor monitor |
| 520263 | ABS Tire | Tyre-size mismatch detection — implies the ABS module compares the two wheel speeds, which is the same comparison our monitor makes |
| 1023 | Trip Sudden Decelerations | A stored counter, not a live signal, but an interesting one |
| 731 / 1071 / 520202 | Knock Sensor / Fan Relay / Canister Purge | Engine internals. Low value to a rider; listed so nobody re-derives them |

---

## Priority list

Ordered by *value to the rider ÷ cost to find*. Tier 1 items can be settled with
data already on disk.

### Tier 1 — answerable from the existing captures

**1. PGN 65215 (EBC2, front wheel speed) — SA 11, 4,328 frames. Highest value
on the list.**

The bike has no traction control. It has ABS and two wheel speed sensors, and
they drive different instruments: **the front sensor drives the trip meter, the
rear drives the speedometer.** Both sensors report to the ABS module (SA 11),
which is why both numbers are on the bus.

The speed we publish today comes from PGN 65265 SA 11 — the rear sensor, the
speedometer feed. PGN 65215 bytes 0–1 are the front, at 1/256 km/h.

Measured across 1,249 paired samples above 5 km/h:

```
rear = 1.0351 x front - 0.696      r = 0.9998
```

They plainly measure the same motion. But the rear reads high, and **the error
grows with speed**:

| speed | rear vs front |
|---|---|
| 20–39 km/h | +1.79 % |
| 40–59 km/h | +1.80 % |
| 60–79 km/h | +2.28 % |
| 80–99 km/h | +2.90 % |
| 100–119 km/h | +3.12 % |

A calibration difference — unequal rolling circumference — would be a *constant*
percentage. One that grows with speed is drive slip: the rear is the driven
wheel, a driven wheel slips under torque, and torque demand rises with
aerodynamic drag. The front wheel is undriven and therefore the honest one,
which is exactly why Indian metered distance off it.

So this PGN is **true road speed**, and the number on the dash is 2–3 % optimistic
at road speed.

**Decided 2026-09-03: we keep publishing the rear sensor, and this is not a bug
to fix.** Two reasons, and the second is the stronger one.

The app should agree with the instrument the rider is looking at. Two speeds
disagreeing by 3 % on the same handlebar is a fault report waiting to happen,
not a feature.

More importantly, the error is *protective*, and correcting it would remove the
protection. A rider works to the number in front of them. Ride to an indicated
130 on a speedometer that reads 3 % high and you pass the camera at a true 126;
publish the honest number and the same rider rides to a true 130. Speed traps
measure true speed, so the optimistic reading is a margin, and it is the margin
Indian deliberately built in — every manufacturer biases the speedometer high
for exactly this reason, and UNECE R39 requires that it never read low.

Note also that **the number where accuracy actually matters is already the
honest one**. Distance is metered off the front wheel, so the odometer, trip,
fuel economy in l/100 km and the 8,000 km service interval all ride on the
sensor that does not slip. Indian put the accuracy where it is needed and the
conservatism where it protects. There is nothing here to improve.

What the front sensor is still good for is the *comparison*: the ratio between
the two is the only wheel-slip signal this bus can offer, and a slow drift in
that ratio over months would be rear tyre wear. Neither justifies a production
field today.

Remaining work: confirm the byte-4 scaling (values 39–220, `r = +0.84` against
speed — likely a per-wheel relative speed with offset 125 = zero) against a
capture containing a known hard acceleration. Bytes 2–3 are constant `0x00` and
5–7 constant `0xFF`.

**2. PGN 2304 — SOLVED 2026-09-04. Lean, not throttle.**

Byte 0. Found by accident while hunting the sidestand, which is the better
story: asked to move the stand down, up and down again, the byte ramped
smoothly 113 -> 127 over two seconds, held for twenty, and ramped back to 113
over three.

**A switch does not do that.** It steps. This follows a physical movement --
and the movement was the bike being lifted off its stand, held upright by hand,
and leaned back. It even wobbled between 126 and 128 while held, which is a hand
not being perfectly still.

```
127   upright
113   resting on the sidestand
```

This explains everything the byte did all day. It sat immovably at 113 from
morning to afternoon **because the motorcycle was on its sidestand the whole
time**, and it clustered around 127 through the August rides because you ride a
bike upright. It never correlated with speed because lean has nothing to do with
speed. Almost certainly the tip-over sensor, which every bike has so it can cut
the engine when it goes down.

The scale is NOT established, and guessing it would be the mistake this document
exists to prevent. The August captures span 94-203; read against the sidestand
lean as a ruler that implies over 60 degrees, which no cruiser reaches. So the
axis is not linear, or acceleration is mixed into it, or the extremes are
transients. Direction and the two fixed points are solid; degrees are not.

**Wheel sensor cross-check, added 2026-09-04.** The front wheel speed is now
decoded and compared against the rear, and the pair is watched rather than
merely displayed. This exists because of a real failure, and the causal chain
matters for what the check should look for. A service left out BOTH 0.5 mm
shims, which narrowed each air gap to about 0.25 mm. On its own that was
survivable -- the rear ran that way and came through intact. The front also had
play in its wheel bearing, so the tone ring wandered off its plane, reached the
sensor and machined the face off it. The first warning was the ABS quitting in
traffic.

So the hazard is not a missing shim. It is a narrow gap AND a wheel that moves,
and bearing play develops on any wheel at any time. That is why the rear is
watched identically: it survived on a good bearing, not on a good gap.

The physics decides what the check can see, and the first version had it wrong.
These are **2-wire active Hall sensors, current-modulated 7/14 mA**, not passive
inductive ones -- established from the owner's own service notes, written by
the owner, an electronics engineer, who had already measured all of it. An
active sensor's output does not weaken with speed, so a worn sensor does not
read progressively slow and there is no drift in the wheel ratio to watch for.
It counts the teeth or it does not.

A widening air gap produces **intermittency** instead: the field clears the
threshold on some teeth and not others, and the module tolerates brief losses
without setting a fault. So the check has two outputs -- a sustained loss called
out loud, and a running count of the brief ones. The count is what matters over
months, needs no calibration, and cannot cry wolf.

The owner's own document already contains the manual version of this: speedometer
missing means the rear sensor, odometer not counting means the front. All we
have done is run that inference every second instead of at the roadside.

What the tilt is worth as a shipped signal: **"the bike is resting on its stand"**,
which is what the sidestand hunt was actually after and is better than a switch
-- a switch says where the stand is, this says whether the bike is on it. Gate
it on zero speed and it is unambiguous. Maximum lean on a ride is the other
obvious use, but that needs a scale.

**The stand does reach the bus, just not as a value.** Found hours after we
concluded otherwise: at 18:20 on 2026-09-04 the DM1 stream carried SPN 520267
FMI 31 six times and cleared -- Indian's P181C, *"engine disabled due to
extended kickstand"*. The bike had refused to start with the stand down and
announced it, and the app's fault table already knew the code by name.

We searched for a state, found none, and then declared the whole thing absent.
That was one step too far: a signal can exist as an event without existing as a
value, and a change detector cannot see something that is only ever reported at
the moment it matters.

Still open, and cheap: **PGN 65390 byte 0** stepped DF <-> FF (bit 5) at the
start of the same test. That is the PGN the front brake was withdrawn from, and
bit 5 is a candidate for the sidestand switch itself. One clean test would say.

**3. PGN 65394 — SOLVED 2026-09-04. Grip temperature, left and right.**

Bytes 0 and 1, J1939 -40 offset, one per grip. Byte 0 is the faster of the two,
consistent with the throttle-side grip having a tube to heat through.

It took three runs, because the first two were confounded and the rider spotted
it: the ignition and the grips came on at nearly the same moment, and energised
electronics warm from ambient on the same curve as a heated grip. Worse, **the
grips remember their level across an ignition cycle**, so the intended
"ignition on, grips off" baseline silently never happened — the second run came
up with the grips already at 10.

The clean run switched the grips off *before* the key. What settles it:

| condition | behaviour |
|---|---|
| grips stepped 1 to 10 | **+8 C in 84 s** |
| grips switched off | +2 C of lag, then flat |
| grips off from the start | 25 -> 23 C, falling |
| ignition cycled off and on | returns at 23 C, does not reset |

Rises fast under heat, falls slowly without it, and carries its value across a
power cycle. The last row is what rules out a counter: a value that climbed from
zero on every start would have reset, and this did not — it has thermal mass.

The August rides corroborate it. With the grips certainly off on a 25 C
afternoon the two sat at 22-29 C and drifted *down* 28 -> 23 C over one ride,
which is ambient, not an electronics temperature pinned above it.

So the bike can report how warm the rider's hands actually are, not merely which
detent was chosen — which is worth having beside the Keis clothing control,
since that control is currently inferring comfort from ambient and road speed
while a real measurement was on the bus all along.

Shipped: `st.gripTemp1` / `gripTemp2`, `$.gripTemp1` / `$.gripTemp2` in the
state JSON, two openHAB items, and a strip on the app's cluster page — the
grips belong to the motorcycle, not to the page that manages the rider's own
clothing.

**Byte 0 is the LEFT grip, byte 1 the RIGHT**, settled by holding a bare hand on
the left grip with the heat off: byte 0 rose 18 to 22 C in a minute while byte 1
did not move. Worth the minute, because the inference had them the other way
round -- byte 0 warms faster under the heaters, and that was read as the
throttle side. It is simply that the left grip is the one that gets warmest,
which is also what the rider had always felt.

**They are two independent sensors, and the proof is that they move at
different rates**: under the same warm-up one rose 8 C while the other rose 5.
A single shared value cannot do that.

Indian displays neither reading anywhere. The sensors have to exist for the
controller to regulate at all, but the numbers have never left it -- they have
been on the bus, unseen, since the bike was built.

Which gives a free self-test. With the heat off both grips read the air at the
bar, so **they should agree**; a standing disagreement with the grips off means
one of them is wrong. Nothing acts on that yet, but it is the cheapest health
check on the bike.

### Tier 2 — needs a specific manoeuvre on the bike

**4. Identify PGN 2304 byte 0.** *Next up — decided 2026-09-03.*

**Method decided: a temporary publish from the production firmware, not the
spare board.** The board on the bike runs `FIRMWARE_MODE 1`, and the raw
per-PGN topics live inside the `MODE_DISCOVERY` block, so 2304 is invisible
from it today. The orthodox route is the spare T-CAN485 in discovery mode, but
`config.h` is shared between the two boards: it would mean switching
`FIRMWARE_MODE`, `CAN_BACKEND` and `STATUS_LED`, flashing over USB, wiring a
second board onto the bus, and switching all of it back before the next OTA to
the bike. (That shared setting is also why the `sniffer` env fails to build
right now — `config.h` points at MCP2518, so the wrong HAL gets compiled. It is
not a missing library.)

For one byte of one PGN that is disproportionate. Three lines publishing 2304
byte 0 to a temporary MQTT topic costs one OTA, no cable, no board swap, and
leaves the app running during the test. A temporary debug topic is not a
production field, so this does not bend the rule at the top of this document —
it is removed once the byte is identified.

**Revised 2026-09-04: make that temporary publish generic, not 2304-specific.**
The heated grips (item 5) sit on an unknown PGN, and a topic aimed at one known
address cannot find a signal whose address we do not know. So the temporary
publish should report *any byte that changes while the bike is stationary*,
which is the method `tools/switch_watch.py` already assumes. Held stationary
with the ignition on, almost nothing on this bus changes, so the volume is
small — that is precisely why the hold-a-control-for-8-seconds technique works.
Gate it on zero speed and rate-limit it, and one OTA covers the grips, PGN 2304
and the front brake in a single session.

The manoeuvre: Ignition on, engine off, bike on its stand,
sniffer running. In order, with 8-second holds and 5-second pauses:
open the throttle fully and hold; release; lean the bike off the stand to the
left and hold; upright; right and hold. Then start the engine and repeat the
throttle hold. Three outcomes, three different signals: moves with the grip and
not with lean = throttle after all; moves with lean and not the grip = lean or a
lateral accelerometer; moves only with the engine running = load or torque.

**5. Heated grips — SOLVED 2026-09-04. PGN 65386 SA 39 byte 2.**

Found by stepping all ten detents on the bike and holding each for eight
seconds. The byte moves in exact steps of 25:

```
0 off,  25 = 1,  50 = 2,  75 = 3, 100 = 4, 125 = 5,
      150 = 6, 175 = 7, 200 = 8, 225 = 9, 250 = 10
```

Ordinary J1939 percent scaling (0.4 % per bit), read as a level because the
control has ten detents and a rider thinks in detents. 251-255 are the
error/not-available codes; a momentary 254 appeared once at full heat, so the
guard is not theoretical.

**It was in the same frame as the horn all along.** We had decoded byte 0 of
65386 since August and never looked past it. That is the fourth time a signal
was sitting beside one we already had, and it is now a standing habit: when a
PGN is confirmed, dump every byte of it before moving on.

Shipped: `st.grips` in the firmware, `$.grips` in the state JSON,
`CanBus_Grips` in openHAB.

**6. Front brake, still withdrawn.** Each control alone, held ~8 s, with real
pauses between. The brake *light* switch is shared by both levers — that much is
settled — so this is a hunt for a separate front-only signal, which may simply
not exist. Time-box it.

**7. Throttle position — SOLVED 2026-09-04.** PGN 65266 byte 7, SPN 51, 0.4 % per bit. Verified on the bike with the engine stopped: six sweeps, 5-7 % at rest, 100 % at full, rpm at zero throughout. EEC2 was never the place to look.

**8. Cruise control — see below.** Understood; what is missing is a decision,
not data.

### Tier 2b — the key fob and what it controls (stationary, passive)

Three hunts that need nothing but the change detector already in the firmware,
and no riding.

**A. Side case locks.** The fob locks and unlocks the panniers, so the VCU must
act on it and probably says so. Press lock, wait ten seconds, press unlock, wait
ten. A byte that follows the button is the answer.

**B. Key fob present or absent.** The bike will not start without it, so
something knows. With the ignition on, walk away with the fob until the dash
complains, then walk back. Slow, because the range is what it is, but entirely
passive.

**C. The turn-signal unlock code.** Four digits, entered as counted presses of
the left and right indicator switches in turn, and it opens the bike exactly as
the fob does.

**Read this before hunting C.** The indicators are broadcast in clear -- PGN
65089 byte 2, bit 7 left and bit 5 right, which this firmware already decodes.
Every press of the code is therefore on the bus while it is being entered, and
anything listening can reconstruct the code. That is a property of the bike, not
of our tooling, and it needs physical access to the bus, so it is not an urgent
exposure. But it means **hunting C writes the owner's unlock code into MQTT,
into InfluxDB and into any capture running at the time.**

So hunt the *event*, not the digits: look for the state change that says the
bike opened, and do it from a fob unlock rather than a code entry. That answers
the useful question -- did someone just open my motorcycle -- without recording
the secret.

**Add the immobiliser to this tier.** SPN 520330 faults with FMI 9, "Abnormal
Update Rate" (P106A), so it is one of the seven signals the manual confirms is
broadcast periodically — and it belongs with the fob work rather than on its
own, because the immobiliser is what the fob talks to. If the message carries an
authorised/not-authorised state, it is the cleanest possible answer to "is the
key present", far better than inferring presence from what the bike will let you
do. Hunt it in the same session as the rest of the fob work.

**And this one comes with a visible control, which is rare here.** The owner
reports a shield telltale on the instrument cluster for the immobiliser. That
means the cluster holds the state, so the signal is not merely inferred from
behaviour — there is a lamp on the dash that says what the answer should be at
every moment of the test.

Almost nothing else on this bus has offered that. The horn and the front brake
were both decoded wrong precisely because their state had to be inferred from
what the rider was doing; a telltale removes the inference.

**The test writes itself:** capture at power-up with the fob in your pocket, then
again with the fob left indoors, well away from the bike. The shield differs
between the two runs, so any byte that differs the same way is the immobiliser
status — and any byte that does not differ is excluded. Two runs, no movement,
and a control that cannot be argued with.

One caution: the shield may also light briefly at every power-up as a lamp test,
the way most telltales do. Note when it goes out, not merely that it came on.

### Tier 2c — the lights (stationary, passive, thirty seconds each)

**D. Fog lamps on the light bar.** An original Indian accessory with its own
switch and its own red/green LED, showing nothing on the dash. The owner assumed
that meant it was off the bus entirely.

It is not: Indian's own fault table has **SPN 520291 (left fog lamp)** and
**520292 (right fog lamp)**, mapping to C1075/C1076 and C1078/C1079. The bus
knows these lamps exist and can report them failing. What is unknown is whether
the *switch state* is broadcast, which is the sidestand distinction again —
position private, failure public.

Test: switch them on, hold ten seconds, off, hold ten, with the change detector
running. A byte moving in step is the switch.

Worth noting that the assumption behind the question is unsafe. "Not on the
dash" does not mean "not on the bus": the sidestand shows nothing on the dash
and reaches the bus as a fault, and grip temperature is on the bus while being
displayed nowhere on the motorcycle at all.

**E. Headlight bulb — already decoded, nothing to do.** Pull the bulb or blow
one and the app names it, today, with no change:

```
SPN 2350 FMI 5  ->  C107B  "Low beam lamp — Open Circuit / Short to B+"
SPN 2348 FMI 5  ->  C107E  "High beam lamp — Open Circuit / Short to B+"
```

FMI 5 is "current below normal or open circuit", which is exactly what a missing
or blown bulb is. Listed here so nobody goes hunting for something that already
works, and because it is worth knowing before a night ride: the app says *which*
lamp, not merely that something is wrong.

### Tier 2d — the Operator Switch Status message (stationary, ten minutes)

**From the manual cross-reference, 2026-09-05, and the best lead in this
document.** SPN 520329, "Operator Switch Status (pOSS1)", faults with FMI 9
"Abnormal Update Rate" (P1063), which means the ECU expects a periodic message
carrying the state of the operator's switches. We have never looked for it.

**The candidate is PGN 65381 from SA 39** — the message the headlight already
comes from. Its shape is right where nothing else on the bus is: few distinct
values, but several bits toggling independently, which is what a packed switch
field looks like and what an analogue value never looks like.

Everything observed across the four rides, in binary:

| byte 0 | seen | meaning |
|---|---|---|
| `00010000` | 237x | bit 4 — dipped beam, **decoded** |
| `00010001` | 27x | bit 4 + **bit 0 unexplained** |
| `00010100` | 6x | bit 4 + **bit 2 unexplained** |
| `01000000` | 6x | bit 6 — main beam, **decoded** |

| byte 1 | seen | meaning |
|---|---|---|
| `00000011` | 246x | bits 0-1, always set — baseline |
| `00000111` | 6x | **bit 2 unexplained** |
| `00010011` | 3x | **bit 4 unexplained** |
| `01000011` | 3x | **bit 6 unexplained** |

Five unexplained bits, each of which appeared only a handful of times during
ordinary riding — exactly how a switch behaves that is pressed occasionally.

**The test needs no ride and no movement.** Ignition on, engine off, run
`tools/switch_watch.py`, and work one control at a time holding each for eight
seconds: horn, both indicators, hazard, kill switch, front brake, rear brake,
starter, high-beam flash, fog lamps if fitted, the cruise enable rocker, and the
grip heater button. Anything that moves a bit in 65381 is identified on the
spot, and the horn and front brake — both withdrawn for being wrong — get a
second chance at an honest decode.

Two cautions. This bus does not label its bits, so a bit that moves during a
horn press is a *candidate* until it has moved twice and stayed still for
everything else. And 65381 is only the best-shaped candidate: if the switches do
not appear there, `switch_watch.py` will show which PGN did move, which answers
the question either way.

### Tier 2e — the tipover sensor, which costs no decoding at all

**Found in the manual cross-reference, 2026-09-05.** SPN 520200 is a *Tipover
Sensor* — a dedicated part, separate from the accelerometer at SPN 520296 — and
FMI 14 on it is `P1504`, "Condition Exists (tip over condition detected)".

The name has been in the app's fault table all along, sitting among 179 others.
What nobody noticed is what it implies: **the bike has an authoritative opinion
about whether it has fallen over, and it publishes that opinion on DM1**, which
we already decode.

That matters because `standState()` currently derives DOWN from the lean byte
with a three-second hold, and the comment there is honest about what it is: a
heuristic chosen because no sidestand state exists on the bus. A DM1 fault from
the machine itself is not a heuristic.

**Nothing needs to be decoded.** The work is to watch for SPN 520200 FMI 14
appearing in the DM1 stream and decide what it should drive — almost certainly
the same alert path as the towing and unplug warnings, since a motorcycle lying
on its side in a car park is the same class of news.

**Two things to establish before trusting it**, and neither needs a ride:

1. **Does it fire with the ignition off?** A tipover cut is an engine-protection
   function, so the ECU may only evaluate it while running. If it is silent on a
   parked bike, it is useless for exactly the case that matters most, and the
   derived lean reading stays the primary source.
2. **Does it self-clear?** SPN 520267, the kickstand, reports and then clears.
   If 520200 behaves the same way, an alert must latch on the transition rather
   than poll for a standing fault.

Until both are known, treat it as a *second opinion* that raises confidence in
the derived DOWN state, not as a replacement for it.

### Tier 3 — low yield, do when convenient

**9. PGN 65254 — RESOLVED 2026-09-04. A clock, but not the one you can see.**

Byte 1 minutes, byte 2 hours, seconds not sent. It runs **exactly 1:1 with real
time, including while the bike is parked** — checked across the gaps between the
August rides, where 53 minutes of standing still advanced it by 53 minutes.

An earlier note here claimed it did *not* track real time across ignition
cycles. That was wrong, and worth recording why: the apparent jumps were stale
first frames at the start of each capture, before the first fresh transmission.
Measuring from the second reading in each ride removes them entirely. Reading
the first sample of a slow PGN as live data is its own trap.

It is not the head unit's clock. With the display reading 10:11 and real time
10:14, the CAN clock read 20:08.

**And the offset is exact.** Measured against UTC rather than local time, across
three weeks:

```
11:35 UTC -> 23:28    +11h 53m
12:44 UTC -> 00:37    +11h 53m
13:09 UTC -> 01:02    +11h 53m
08:14 UTC -> 20:08    +11h 54m   (three weeks later)
```

That splits cleanly into **12 hours — AM and PM transposed — and seven minutes
slow**. It is not a time zone: a zone conversion lands on a whole hour, and the
seven minutes only make sense as a clock nobody ever set.

The stability is the striking part. One minute of drift in three weeks is a
*good* clock. It is simply twelve hours wrong.

The owner proposed a factory time zone -- US firmware carrying UTC-8 internally,
which against Danish summer time would give exactly ten hours. The reasoning was
sound and the arithmetic decided it: the measured figure is 11h53 from UTC, not
10h, and no zone offset produces seven spare minutes.

Two further explanations were tested and ruled out.

**The 12h/24h display setting.** An AM/PM flag applied wrongly would give
exactly twelve hours, and it can only arise if the firmware is working in
twelve-hour form. The dash is set to 24H, so it is not that.

**A shared J1939 time zone.** The message itself settles this: PGN 65254 carries
`Local minute offset` and `Local hour offset` in bytes 7 and 8 (SPN 1601 and
1602), and those fields exist precisely because the standard does NOT assume a
common zone -- there would be nothing to state if everyone already agreed. Indian
sends both as 0xFF, "not available", along with seconds and the whole date. The
bus carries a bare hour and minute with no stated reference at all.

And the arithmetic rules it out regardless: Pacific is UTC-8, eight hours
*behind*. The bike measures twelve hours *ahead*. Wrong sign, wrong size.

**Not shipped.** Correctable in principle -- subtract twelve hours, add seven
minutes -- but it would still be a second, worse clock beside the server's own,
and openHAB timestamps everything already.

**10. Low fuel — no signal needed.** With LOW FUEL showing on the head unit, the
fuel level we already decode from PGN 65276 read 10 %. The warning is a
threshold on a value we have, not a bit to find.

At the same moment DM1 reported `No active DTC | MIL:off Stop:off Warn:ON`. The
warning lamp is lit by the low fuel, not by a fault. Recorded so a future
session does not hunt for a fault that does not exist when the bike wants
filling.

**11. PGN 61441 (EBC1)** — SA 11, 41 frames, one varying byte taking `0xCC`,
`0xCD`, `0xDC`. Those are the *same three values* as the cruise switch byte,
which is either a mirror or a coincidence worth ten minutes.

**12. Horn — WITHDRAWN 2026-09-04. Byte 0 bit 6 of 65386 is not the horn.**

Settled by watching the app while pressing the ignition button: the horn
tell-tale lights and **the bike makes no sound at all**. That matches the
captures, where the byte went 0x3F to 0x7F the moment the ignition came on and
back 14 seconds later.

It was never confirmed in the first place. The reverse-engineering log has
carried "honk-verify pending" since 2026-08-14; the check was never done, so a
guess from a switch sweep shipped as fact and has been lighting a horn symbol
at every start since.

Confirming a real horn signal here would be awkward anyway: this PGN transmits
about every 11 seconds, with gaps to 471 in the captures, so a normal press has
roughly two chances in eleven of being sampled. A tell-tale that cannot be
trusted to be lit while the horn sounds is not worth having.

What bit 6 actually is: **the bike waking up.** The owner watched it while
pressing the wake button -- not the engine start -- and it went high both times,
alongside a burst of other traffic (the gear display stepping N to - to N) that
is a control unit booting.

It is not worth shipping, for the same reason the horn was not: this PGN
transmits about every 11 seconds, so a press under a second is caught perhaps
one time in ten.

**And it is not needed.** The bus going from silent to active says the bike woke
up, immediately and every time, and the firmware already reports that as
`can_detected`. The signal we went looking for turns out to have been available
all along somewhere better -- which is worth remembering the next time a bit
looks tempting.

**13. PGNs 65387, 65388, 65393, 56832** — 8, 8, 12 and 3 frames respectively,
nothing varying in any of them. There is no signal here to find in this data.
Revisit only if a capture that exercises something new makes them move.

**Trip 2 — not on the bus.** The bike has two trip meters and only the first is
broadcast. PGN 65217 is J1939's *High Resolution Vehicle Distance*, which has
exactly two fields -- total in bytes 0-3 and trip in 4-7 -- and Indian fills
both.

The proof is not a failed hunt for the value but a positive one: scanning a
whole 22-minute ride for fields that grow monotonically turns up exactly two,
the odometer and Trip 1, each up by 14.3 km. A second trip meter would have
grown by the same 14.3 km and been impossible to miss. (The scan was first run
across all four captures at once, found nothing, and was wrong to: concatenating
the files breaks the chronology and with it the monotonic test. It had to find
the odometer before its silence about anything else meant a thing.)

Trip 2 is kept inside the instrument cluster, like the clock on the display that
disagrees with the clock on the bus.

### Not on the bus — stop looking

Membership here requires that the signal was *exercised* while capturing and
still did not appear. Anything that merely failed to show up in data where it
could not have shown up belongs in "Untested" instead — see the cruise control
entry below for what happens when that distinction is skipped.

- **EEC2 (61443)**, the standard home of accelerator pedal position (SPN 91):
  never transmitted. This is why the throttle has never been found where the
  tables say it should be.
- ~~**Cruise control active (SPN 595)** and **set speed (SPN 86)**~~ — **moved
  out of this list 2026-09-05.** The bytes read "not available" in every
  captured frame, but cruise was never engaged on any of those rides, so this
  section was never entitled to the entry. Untested, not absent. See below.
- **The horn (SPN 520293).** Admitted 2026-09-05, and it satisfies the
  membership rule properly. Exercised twice on two separate runs — four presses
  each — with the rig proven working in both by the indicator control test, and
  the second run had PGN 65265 unmasked so no message was excluded. Nothing
  moved anywhere.

  The manual says why. SPN 520293 carries only FMI 5, "Open Circuit / Short to
  B+" (C122A), and FMI 6, "Grounded Circuit" (C122B) — both *output driver*
  faults. It has no FMI 9, which is what a signal expected over the network
  gets, and no FMI 31 "Switch Stuck", which every genuine switch signal here
  has: SPN 596, 599 and 601 all carry it. The wiring table completes the
  picture — a `GY` wire labelled HORN SWITCH OUTPUT into the module, a `WH`
  wire labelled HORN POWER out of it.

  A switch on a wire driving a monitored output, with nothing needing to
  broadcast it. For a safety item pulling that much current, exactly what one
  would design. **Stop looking.**
- **The saddlebag locks (SPN 520312, "Power Lock Motor Switch").** Admitted
  2026-09-05. The console lock/unlock switch and the key fob's lock and unlock
  buttons were all worked with the ignition on and the bus demonstrably awake,
  and **the actuators were confirmed to operate** — this was not a dead switch.
  Nothing moved anywhere on the bus. A left indicator flicked seconds later in
  the same run came through cleanly, so the rig was proved capable in the same
  breath as the null.

  The manual agrees in the same way it did for the horn: SPN 520312 carries only
  FMI 31, "Switch Stuck" (C1229) — an input fault — and no FMI 9, which is what a
  signal expected over the network gets.

- **A pattern worth using before the next hunt.** Between the horn and the locks,
  this bus has now twice refused to carry something that plainly works:

  | On the bus | Not on the bus |
  |---|---|
  | Speed, gear, rpm, tyre pressure | Horn |
  | Lamps, indicators, hazard | Saddlebag locks |
  | Brake, cruise, heated grips | Windshield motor (not fitted here anyway) |
  | Lean, fuel, temperature | |

  **It carries state a rider reads. It does not carry convenience actuation.**
  Horn and locks are switch → module → output, entirely inside one module, with
  nothing that needs telling. Before spending an evening on the next candidate,
  ask which side of that line it falls on — and check the manual for an FMI 9,
  which is the manufacturer saying it out loud.

- Anything the cluster computes internally — see "Confirmed NOT on the bus" in
  the private working log.

---

## Cruise control — what it actually is

Investigated 2026-09-03 after the tell-tale was reported behaving oddly. It was.

The decode read PGN 65265 byte 5 bit 0 from SA 39 and published it as cruise
on/off. **Byte 5 of CCVS is not the cruise state — it is the switch byte**:

| bits | SPN | meaning |
|---|---|---|
| 0–1 | 599 | cruise **SET** switch |
| 2–3 | 600 | coast |
| 4–5 | 601 | cruise **RESUME** switch |
| 6–7 | 602 | accelerate |

So the tell-tale was lighting on a *momentary button press*. Across the three
rides that contain any activity at all, SA 39 byte 5 sits at `0xCC` for minutes
and flicks to `0xCD` (SET) or `0xDC` (RESUME) for **1–7 seconds at a time** —
which is precisely what a lamp that blinks and goes out again looks like from
the saddle.

The real state is one byte earlier. CCVS byte 4 carries cruise active (SPN 595)
in bits 0–1, and Indian sends byte 4 as a **constant `0xF7` across all 5,223
SA-39 frames**: SPN 595 reads `3`, "not available". Byte 6, the cruise set
speed (SPN 86), is a constant `0xFF` for the same reason. Byte 4's bits 2–3 do
read `01`, "enable switch on", but they never change, so they say only that the
bike has cruise control — which we knew.

**The withdrawal stands: byte 5 is the switch byte, not the state.** That much
is settled, and the service manual confirms the naming — SPN 599 is the
*Set/Decel* switch and SPN 601 the *Resume/Accel* switch, one rocker doing two
jobs depending on whether cruise is already engaged. We had found both buttons;
nothing was missed.

**But "the state is not transmitted" was never proved, and is now in doubt.**

Byte 4 read a constant `0xF7` across all 5,223 frames, so SPN 595 reported "not
available" throughout — and that was taken as the answer. It cannot be:
**the owner never used cruise control on any of those four rides.** The captures
could not have shown an engaged state, because cruise was never engaged.

That is a null result from a test incapable of a positive one, which is the
exact failure this document has a rule against. The rule was not applied to its
own author.

The manual also says the dash shows **amber for enabled-but-not-set and green
for set**, so the machine plainly knows. Whether it says so on the bus is open.

**The test, on a ride:** engage cruise, hold it a minute, and watch `65265 SA 39
byte 4`. Bits 0-1 going from `11` to `01` is SPN 595 populated, and the signal
is shippable after all.

What could still be built, if it is wanted:

- **Publish the presses as events.** SET and RESUME are confirmed and honest. A
  press is a real thing that happened; it just is not a state.
- **Latch a state from them.** Set on SET or RESUME, clear on brake. The brake
  light switch is available and both levers operate it, so the clear condition
  is sound for the normal case. It would be wrong in two situations: cruise
  cancelled by the handlebar switch or by pulling the clutch (the clutch switch,
  SPN 598, is also `0xFF` here), and cruise dropped by the ECU on a steep climb.
  The tell-tale would then read on while cruise was off — the same class of
  quiet lie that has now been removed four times.

That trade is a decision to make deliberately, not a bug to fix. Until then the
field stays unpublished.

A footnote for whoever reads the raw frames: bytes 7 and 8 of SA-39's CCVS vary
constantly (16 and 29 distinct values) but the low nibble of byte 7 is always
`0xF` and of byte 8 always `0x4`, and neither steps sequentially. This is a
message-integrity pair, not a signal. Do not spend an evening on it.

The service manual names them: **SPN 524079, "Cruise Control Input Checksum"
(U0405) and "Cruise Control Input Message Counter" (U1405)**. So byte 7 carries
the counter and byte 8 the checksum, and their presence confirms that CCVS from
SA 39 *is* the cruise control message rather than merely carrying some of it.

---

## Rules this bus has taught us

Each of these was learned by getting it wrong first.

1. **Assume more than one sender until the captures say otherwise.** Headlight
   (2 sources), CCVS (3), DM1 (3) and fuel level (2) all shipped broken because
   the decode read whichever module spoke last. Filter on source address.
2. **`0xFF` is not data.** It means "not available", and masking it turns a
   declined signal into a confident wrong answer. Guard every byte.
3. **Two-bit fields are two bits.** `0 off, 1 on, 2 error, 3 not available`.
   `& 1` on such a field reports "not available" as "on". This has bitten the
   brake and the cruise decode.
4. **A test that reports nothing must first be shown capable of reporting
   something.** A scan with the wrong filter finds nothing and proves nothing.
5. **Correlate before believing.** `r = +0.988` is what promoted a byte to
   "throttle"; a later look showed it was rpm/256. High correlation identifies a
   *relationship*, not a meaning.
6. **Ask for the slope, not just the correlation.** Front and rear wheel speed
   correlate at `r = 0.9998` and are still 3.5 % apart — the interesting signal
   was in the regression, not the correlation. `r` says two things move
   together; only the slope says whether they agree.
7. **Ask the owner before inferring the vehicle.** The front/rear split was read
   as wheelspin detection until the owner pointed out the bike has no traction
   control, and that the two sensors feed the trip meter and the speedometer
   respectively. That one sentence turned a vague safety idea into a measured
   2-3 % speedometer error.
8. **Withdraw loudly.** A withdrawn signal keeps its `case` and its comment
   explaining why, so the next person does not rediscover the same wrong answer.

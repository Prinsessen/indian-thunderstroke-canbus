# Changelog

Dated by the day the work was done, newest first. Each entry says what changed
and *why*, because the reason is the part that is hard to recover later; the
commits carry the detail.

---

## 2026-09-14 — the cluster stops looking plotted

One session, triggered by the owner looking at the screen on a Hugerock X70 and
saying what was wrong with it. Everything here is cosmetic or layout; no decode,
no protocol, no BLE behaviour was touched. **Nothing in this entry has been seen
running** — it was written, measured and checked, but not yet built.

### Fullscreen, both ways up

The system bars were taking **72 dp** of a 393 dp landscape screen. After the
tab row, the link strip, the fragment padding and the tell-tale row, the two
dials the ride page exists for were left with about 160 dp — and both gauges
size on `min(width, height)` and scale every caption they draw off that size, so
a short dial does not merely look small, it crowds.

Immersive with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, so a swipe still brings
the bars back for a few seconds. Landscape first; portrait followed once the
question was put properly — it is the same app doing the same job, a phone on a
handlebar mount with the screen forced on is not a phone at that moment, and two
behaviours is one more thing to have learnt. Settings, diagnostics and about are
separate activities and keep their bars, because those *are* a phone.

### The dials carry only their own reading

The ride figure and the ignition lamp were drawn **on the dial faces** in
landscape, at 0.29 and 0.22 of the dial size below the centre, inside a ring of
radius 0.40. Nothing overlapped by arithmetic; it read as clutter, which is the
same failure on a motorcycle.

Neither is a live reading chased by a needle — the lamp answers *is the bike
awake*, the figure is the app's own arithmetic, and both are read while stopped.
They moved into the side column as `RideStripView`. The long-press that restarts
a ride travelled with the figure, and works on the left of the strip only: the
right half is the lamp, and a press aimed at a lamp must not wipe a distance.

Built wrong the first time and caught: `RIDE` was captioned *above* its figure
while the lamp was captioned *below* its ring — inverted rather than mirrored,
leaving the right half hanging 8.6 dp lower. Both captions now share one row and
the ring centres on the figure's optical centre.

### The rev figure sat 8× further from its caption than the speed figure

Measured, in units of dial size:

| | figure → caption | clear space |
|---|---|---|
| Speedometer | 0.048 | 0.024 |
| Tachometer | 0.229 | **0.197** |

The figure was above the pivot and the caption below it, so the hub cap came
between a number and its own unit. The stack moved below the pivot and now reads
down the centre line the way the speedometer's does. 0.190 is the first baseline
that clears the hub cap at that size; +0.056 restores exactly the speedometer's
white space.

### Motion became a language

Three rates, in `Cluster`, and nothing is allowed to breathe at any other:

| | rate | means |
|---|---|---|
| `PULSE_CALM` | 1700 ms | live and working. Life, not warning |
| `PULSE_CAUTION` | 1100 ms | something to plan for |
| `PULSE_URGENT` | 700 ms | something to act on now |

Something that is fine **sits still**. That is what makes the other two readable
from the corner of an eye.

They had been private constants inside the fuel bar, which made the vocabulary a
coincidence. Two pages were then found to be saying nothing at all:

- **TYRES** had no motion whatever. A wheel out of tolerance was a colour change
  on a page the rider only opens deliberately — so the one state worth
  interrupting a ride for was the quietest thing in the app. WATCH now breathes
  at the caution rate, ACT at the urgent one, ring and figure together.
- **MACHINE** had the same gap: a needle inside its caution band changed the
  arc's colour and nothing else, on a page of six dials. The figure now takes the
  caution colour too — the number *is* the reading that is out of range, and
  leaving it white had the ring arguing with it.

### Fuel

Each block is coloured by **where it sits in the tank**, not by how full the tank
is, interpolated between the app's three colours and pinned to the two thresholds
the tank actually has. The bar therefore reddens as it empties without a
threshold ever being crossed — the last blocks a rider runs on were always the
red ones. Unlit blocks keep a ghost of their own colour so the empty end reads as
a scale rather than a row of holes. The leading block breathes calm, the leading
pair quickens under a quarter, and under reserve the whole bar, the figure and
the glow beat urgently together and the glow swells.

### Cruise

Green was just green. It now breathes calm while holding, and amber deliberately
does not — the pulse is what separates *holding your speed* from *armed and
waiting*, and if both moved the movement would mean nothing.

The rocker is drawn rather than only named: **RES/ACC swings the needle up the
scale and flares the tick at that end, SET/DEC swings it down and flares the
other.** The dial runs 145° to 35° through the top, so a larger angle is a higher
speed and the needle leans the way the button moves the motorcycle. A press goes
to full brightness whatever the breath is doing, because a confirmation delivered
at the dim end of a pulse is one that gets missed. Both cues work from the armed
state, because SET is pressed *from* armed.

### Figures are lit, not printed

Every readout large enough to carry it gets a vertical ramp down the glyphs and a
soft halo in the cluster's amber instrument lighting. Both derived from the
figure's own colour, so one call dresses the white speed figure, the red one past
the redline — which glows red, because red numerals in an amber halo would be two
warnings arguing — and the muted dashes. Dashes get the ramp but no halo: a
placeholder that glowed would be claiming to be lit.

### The needle has mass

`Cluster.ease` is purely exponential, so the needle crept up to a value and never
ran past it. It now has a spring: ζ 0.7, about 4.6 % overshoot, settled inside
0.3 s — roughly what a cable-driven needle does.

The split is the point and it is not decoration: **the analogue half has mass,
the digital half does not.** The needle and the lit arc run on the spring; the
figures in the hub keep reading the settled value, because a number that ran to
83 and came back would simply be wrong. The redline is judged on the settled
value everywhere, so a needle swinging through the red cannot raise a warning the
engine has not earned.

### Lamps arrive instead of appearing

120 ms crossfade, done by drawing the old state and then the new one inside a
rising-alpha layer — so all six draw functions are untouched. Only the four lamps
that are simply on or off go through it: cruise has its own breath and needle and
the stand drawing already eases its angle, and a crossfade over either would be
two animations arguing about the same pixels. The lamp test keeps its hard steps,
because that sequence is meant to be crisp.

### The link got a strip

The status line was a monospace grey sentence on a flat bar — correct, and the
least designed thing on screen, on all four pages. Now a dot that breathes on the
calm rate while data is arriving, which is the app's only continuous proof that
anything is still coming in; signal as bars with the figure small beside them,
because nobody has an intuition for −62 dBm and everybody has one for four bars;
and 26 dp instead of 32.

### Build discipline

`--` inside an XML comment ends the comment, and one in a layout comment failed
`:app:parseDebugLocalResources` — so nothing else in the build was attempted and
a whole Windows round trip bought one broken comment. Kotlin has no such rule,
which is why it slipped across. [WORKFLOW.md](WORKFLOW.md) now carries the rule
and a check that parses every XML file under `app/src` in one pass.

---

## Earlier

Not reconstructed here. `git log` is the record for anything before
2026-09-14; this file starts where it starts rather than pretending to a history
it was not written alongside.

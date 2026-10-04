# SpringCommand — User Guide

A complete guide to the Android instrument panel for the Indian Thunder Stroke:
what every page shows, how every control behaves, and — where the app calculates
a figure rather than reading it from the motorcycle — exactly how that figure is
arrived at.

That last part is the reason this document exists. A number you cannot account
for is one you will eventually act on wrongly, and several of the most useful
figures here are inferences rather than measurements. Each one is set out below
with its formula, its inputs and the assumptions it rests on.

---

## Contents

1. [What it is](#1-what-it-is)
2. [What it will never do](#2-what-it-will-never-do)
3. [Requirements and first run](#3-requirements-and-first-run)
4. [Connecting to the motorcycle](#4-connecting-to-the-motorcycle)
5. [Conventions that apply everywhere](#5-conventions-that-apply-everywhere)
6. [The four pages](#6-the-four-pages)
7. [Alerts](#7-alerts)
8. [Heated clothing](#8-heated-clothing)
9. [Calculated figures, in full](#9-calculated-figures-in-full)
10. [Settings reference](#10-settings-reference)
11. [About, diagnostics and the ride log](#11-about-diagnostics-and-the-ride-log)
12. [Troubleshooting](#12-troubleshooting)
13. [Known limits](#13-known-limits)

---

## 1. What it is

An instrument panel that reads the motorcycle's CAN bus through a small ESP32
interface and shows it on a phone mounted on the handlebars, alongside figures
the factory dash does not offer at all: tyre pressure corrected for temperature,
fuel range, a ride trip that survives a coffee stop, and automatic control of
heated clothing based on how cold it actually feels at road speed.

The interface is the owner's own board, the **CANFD-MC rev 1.0** — an
ESP32-S3-WROOM-1U with an MCP2518FD CAN controller. It replaced the LilyGO
T-2CANFD the project started on: a first board went on the bike on 2026-09-28
and board #0001 on 2026-10-03. The bike runs firmware 2026.10.04-3 and still
advertises over Bluetooth as "Springfield"; a board on the bench advertises as
"CANFD-bench". New firmware needs no cable: the interface fetches it over HTTP
on the home network (`update`), or over its MQTT link from anywhere it can reach
the broker (`mqtt`) — a phone hotspot is enough — and rolls back by itself if
the new image does not come up.

Four pages — **RIDE**, **TYRES**, **MACHINE**, **HEAT** — reached by swiping or
by tapping the tab names along the top.

---

## 2. What it will never do

**It cannot touch the motorcycle.** The CAN interface is listen-only by design.
Nothing in this app can send a frame to the bike, and no setting changes that.

**It has no internet permission at all.** Every byte the app handles arrives over
Bluetooth. It uploads nothing, to anywhere, ever. Where a home automation server
is mentioned below, the motorcycle reaches it directly — the phone is not
involved and holds no credentials.

**It never asks for your location.** Bluetooth scanning is declared with
`neverForLocation`, which is why Android 12 is the minimum version: on older
Android a BLE scan required the fine-location permission whether or not position
was wanted.

---

## 3. Requirements and first run

| | |
|---|---|
| Android | 12 or newer (API 31) |
| Permissions | Bluetooth scan and connect, and on Android 13 or newer permission to show its ongoing notification. No location, no internet |
| Hardware | The CAN interface, powered from the motorcycle |
| Optional | One or two Keis heated-clothing controllers |

On first launch the app asks for the two Bluetooth permissions and, on Android 13
or newer, for permission to show the notification that keeps the link alive.
Grant the Bluetooth ones and it starts scanning by itself; a refused notification
is survivable, refused Bluetooth is not.

**The motorcycle interface requires pairing.** The first time a phone connects,
Android shows its own pairing dialog and you type the **six-digit passkey** set
in the firmware. This is a real bond with MITM protection, not a formality: the
characteristics are readable only over an encrypted and authenticated link, and
a phone that does not pair is dropped rather than served unencrypted. Pair once;
Android remembers it.

If the phone ever *connects but shows nothing*, that bond is the first thing to
suspect — see [troubleshooting](#12-troubleshooting).

The heated-clothing controllers are the opposite case and pair with nothing at
all; see [8.2](#82-setting-up).

**It finds the interface by the service UUID in the advertising packet, not by
device name.** That is deliberate: a name filter breaks the moment the firmware's
Bluetooth device name is changed, and the UUID does not move. So renaming the
interface does not break the connection, and the same app finds a bench board
that advertises as "CANFD-bench". What does not follow a rename is the app's own
advice: two pairing messages and the Bluetooth row in settings say "Springfield",
the bike's name, whatever the board in front of it is called.

Set these before the first real ride, all under the gear icon at the end of the
tab row:

1. **Front and rear tyre targets** — the cold pressures your tyres are judged
   against. Defaults are 36 PSI front, 41 PSI rear.
2. **Tank capacity**, in litres. Only needed for the app's fallback range
   estimate — the motorcycle usually reports its own range and that is what you
   will see. See [9.3](#93-fuel-range).
3. **Units** — speed and distance, temperature, and tyre pressure are chosen
   separately, because riders mix them. Kilometres and Celsius alongside PSI is a
   normal combination, not a mistake.
4. **Service interval and last service**, if you want the countdown.
5. **Heated clothing controllers**, if you have them — see
   [section 8](#8-heated-clothing).

---

## 4. Connecting to the motorcycle

The status line reports the link honestly, in these states:

| Shown | Meaning |
|---|---|
| `Bluetooth is off — waiting` | Turn Bluetooth on; the app will carry on by itself |
| `Scanning…` | Looking for the interface |
| `Searching (low power)…` | No bike for 45 seconds; scanning gently until it turns up |
| `Found the bike, connecting…` | Seen it, opening the link |
| `Connected, discovering services…` | Linked, reading the interface's capabilities |
| `Linked` | Running, data arriving |
| `Disconnected (status n)` | The link dropped; the app is already looking again |
| `Pairing refused — forget "Springfield" in Bluetooth settings` | Two failed pairings in a row. The bond is stale — see [troubleshooting](#12-troubleshooting) |

Alongside it sits **signal strength in dBm**, polled every two seconds. It is
there because a link about to drop looks exactly like a healthy one until the
moment it goes, and −90 dBm is the only warning available in advance.

**Battery use.** Scanning costs power; a connection does not. The app scans
aggressively for the first 45 seconds — the time you are walking up to the bike —
then drops to a low-power scan retried every 20 seconds. A phone in a pocket a
mile from the garage is not scanning flat out all day. Opening the app starts a
fresh aggressive window, because someone looking at the screen wants the link
now.

**Stopping it.** The ongoing notification carries a **Stop** action. That is the
only way to end the service; without it the link would run until Android or a
force-stop ended it.

---

## 5. Conventions that apply everywhere

### Unknown is never zero

Every field is nullable from the protocol upwards. The bus's `0xFFFF` and `0xFF`
sentinels, and the validity flags that accompany several signals, all decode to
*nothing* — and a field with nothing behind it shows `—` or dashes, never `0`.

A zero is a measurement. Dashes are the absence of one. They are different
claims, and the app does not confuse them.

The tell-tale lamps take this furthest with **three** states rather than two:

| State | Meaning |
|---|---|
| Lit | Active |
| Dark | Not active |
| *Struck through* | The bus has never mentioned this signal |

A dark lamp asserts that something is off. That is a claim the app has no right
to make about a switch it has never heard from, so it says so instead.

### The cruise lamp

The one tell-tale with more than a state to report, so the only one that moves.

| | what you see |
|---|---|
| Armed, no speed set | Amber, steady, empty dial. **Steady on purpose** — the stillness is what makes "holding" different from "armed" |
| Holding a speed | Green, with a needle, breathing on the app's 1700 ms heartbeat |
| RES/ACC pressed | The needle swings **up** the scale and the tick at the top end flares out past the arc |
| SET/DEC pressed | The needle swings **down**, and the bottom tick flares |

The dial's scale runs from 145° to 35° through the top, so a larger angle is a
higher speed on that face: the needle leans the way the button moves the
motorcycle. A press also takes the lamp to full brightness regardless of where
the breath is in its cycle, because a confirmation delivered at the dim end of a
pulse is one the rider misses.

The swing and the flare both work from the armed state too, not only while a
speed is held — SET is pressed *from* armed, and that press deserves the same
confirmation as any other. The rocker's own name still appears under the lamp
for 1.5 s, unchanged; the movement is there so it can be read without looking at
a word.

### The needle has mass, the number does not

The needle and the lit arc it sits at the end of run on a spring — they lag the
signal slightly, run past a value on a quick change and settle back, which is
most of what makes a mechanical cluster look alive rather than plotted. About
4.6 % overshoot, settled inside a third of a second.

**The figures do not.** A number that ran to 83 and came back would simply be
wrong, and a speedometer that lies for 200 ms is worse than one that looks
plotted. The redline is judged on the settled value everywhere too, so a needle
swinging through the red cannot raise a warning the engine has not earned.

### Lamps arrive, they do not appear

A tell-tale takes 120 ms to come up or go out, which reads as the cluster
reacting where an instant change reads as a redraw. Cruise and the leaning
motorcycle are left out of it: both already move on their own, and two animations
over the same pixels argue.

The start-up lamp test keeps its hard steps. That sequence is meant to be crisp.

### The link strip

Along the foot of every page:

| | |
|---|---|
| **The dot** | Green and breathing on the calm rate while data is arriving — the app's only continuous proof that anything is still coming in. Amber when the bike has been heard but not linked, red when it has not |
| **The words** | Whatever the link is doing, or a message of its own when the state is not one a dot can say |
| **The bars** | Signal, with the dBm figure small beside them. Nobody has an intuition for −62 dBm; everybody has one for four bars |

A page that has frozen and a bus that has gone quiet look identical. The dot is
what tells them apart without reading anything.

### Three pulses, and nothing else

Motion is a language on this cluster, so it has three words. They live in
`Cluster` (`PULSE_CALM`, `PULSE_CAUTION`, `PULSE_URGENT`) and nothing should
breathe at a rate that is not one of them — the whole value of a rate is that
it means the same thing on every page.

Four things do not keep to it yet: the red alert banner and the edge glow that
accompanies it breathe at 1400 ms, and the rev figure past the redline and the
glow behind a motorcycle lying on its side pulse at 900 ms.

| | rate | means | where |
|---|---|---|---|
| Calm | 1700 ms | live and working. Life, not warning | grips' top detent, the fuel bar's leading block, cruise holding a speed |
| Caution | 1100 ms | something to plan for | fuel under a quarter, a tyre on WATCH, a mini gauge inside its caution band |
| Urgent | 700 ms | something to act on now | fuel reserve, a tyre on ACT |

Something that is fine **sits still**. That is not an absence of design — it is
what makes the other two readable from the corner of an eye.

### Lit figures, not printed ones

Every readout large enough to carry it — the speed, the revs, this ride — is
drawn as a **lit** figure rather than a flat colour:

- a vertical ramp down the glyphs, brighter than the base colour at the top and
  darker at the bottom, which is the single difference between a painted number
  and one catching the backlight;
- a soft halo behind them in the cluster's amber instrument lighting, so they
  throw a little of their own light onto the face.

Both are derived from the figure's own colour, so the same code dresses the white
speed figure, the red one past the redline — which glows red, because red
numerals inside an amber halo would be two warnings arguing — and the muted
dashes standing in for a reading the bus has not sent. Dashes get the ramp but no
halo: a placeholder that glowed would be claiming to be lit.

Two numbers to turn if it wants more or less of it: the halo radius in `Ink`
(0.24 of the text size) and its alpha, set per view (`glowInk`, `glowHot`).

### Units

Speed and distance, temperature, and tyre pressure each have their own setting.
They are deliberately independent.

---

## 6. The four pages

### RIDE

The riding page, and the one that holds the main instrument in both orientations.

| Element | Notes |
|---|---|
| **Speedometer** | The primary face. Carries the **gear window** in its lower half, because the two things a rider glances down for belong together |
| **Tachometer** | A digital figure with a bar in portrait; its own dial to the right in landscape |
| **Throttle** | Where your right hand is, on the same track as the engine: a marker on the rev bar in portrait, a thin inner arc on the tachometer in landscape. Open the throttle and it jumps ahead of the revs; shut it and the revs run on past |
| **Redline** | Set once in settings. The dial marking, the edge glow and the haptic warning all read the same number |
| **Fuel bar** | Level as a bar, with **range remaining** beside the label — see [9.3](#93-fuel-range) |
| **Grips** | The motorcycle's own heated grips: the level they are set to, off or 1 to 10, and the temperature of each grip, marked L and R. With the heat off the two figures read the air at the bar |
| **Ride strip** | This ride's distance and the ignition lamp. Portrait puts both beside the rev figure; landscape gives them their own strip at the foot of the side column |
| **ABS lamp** | The amber ABS triangle from the bike's own dash, above the ignition lamp beside the rev figure in portrait. It is lit until the wheels have come up to speed and the ABS has tested itself, as on the machine |
| **Tell-tales** | Six along the foot, in the three states above: beam (blue for main, white for dipped), brake, cruise, hazard, the sidestand switch (red when the stand is out), and a small motorcycle that leans as the bike does — upright, on its stand, or on its side. Turn signals are on the dial face and neutral is in the gear window |

**The dials carry nothing but their own reading.** The ride figure and the
ignition lamp used to be drawn on the faces in landscape — the figure at 0.29 of
the dial size below the centre, the lamp at 0.22, both inside a ring of radius
0.40. Nothing overlapped by arithmetic, but on a short landscape dial they
crowded the two numbers the page exists for. Neither is a live reading being
chased by a needle, so both moved into the side column under the grips. The
long-press that restarts the ride travelled with the figure, and works on the
left of the strip only — the right half is the lamp, and a press aimed at a lamp
must not wipe a distance.

**The cluster runs fullscreen, both ways up.** The status bar and the navigation
bar are hidden on all four pages. Sideways on the X70 the screen is about 393 dp
tall and the two bars were taking 72 of it, on top of the tab row, the link strip
and the tell-tale row — the dials were left with roughly 160 dp, and since every
caption a gauge draws is scaled off `min(width, height)`, a short dial crowds its
own face. Portrait gains the same 72 dp; the speedometer there is bound by width,
so the room goes to the readout, the fuel bar, the grips and the tell-tales.

A swipe from the edge still brings the bars back for a few seconds, then they
hide themselves again — so the clock and the back gesture are a gesture away
rather than gone. Settings, diagnostics and about keep their bars, because those
are a phone rather than an instrument.

**The screen stays on** for as long as the cluster is showing. On a handlebar
mount the ordinary screen timeout would blank an instrument panel after half a
minute.

### TYRES

Both wheels, and the page is arranged around one idea: **a pressure without its
temperature is not a reading.**

For each wheel:

| Field | What it is |
|---|---|
| **Measured pressure** | The large figure in the hub: what the sensor reads, and the same number the motorcycle's own display shows |
| **COLD** and **TEMP** | Side by side beneath it, given equal billing. COLD is the pressure brought to a fixed 20 °C — the number to compare against a cold spec, and the one the ring, its colour and the alerts follow. TEMP is the tyre temperature it was corrected from. COLD is calculated, not measured: see [9.1](#91-cold-equivalent-tyre-pressure) |
| **TARGET** and **OUT** | Your cold target for that wheel, from settings, and — when the outside temperature is known — what the tyre would read cooled to today's weather. OUT is information only; nothing alerts on it |
| **The ring** | Where COLD sits within 8 PSI either side of target, with a notch at the target itself. Green and still within 2 PSI, amber and breathing within 4 (WATCH), red and urgent beyond (ACT) |
| **Age** | "measured 4 days ago". TPMS sensors sleep when the wheels stop, so a parked bike reports nothing — exactly when someone walks up to check the tyres. The last complete reading is kept, and shown with its age, because a stale number labelled as stale is useful where an unlabelled one is misleading |
| **Weekly trend** | Whether pressure is holding, or how much it is losing per week — see [9.2](#92-weekly-pressure-trend) |

The cold figure needs no outside temperature; without one, only OUT is missing.
See [9.1](#91-cold-equivalent-tyre-pressure) for why.

The line at the foot of the page gives the age of the reading and says what COLD
is: "measured 4 days ago · COLD is corrected to 20°C". (Up to 0.5 it said
"corrected to N°C ambient" or "no ambient, showing raw pressure", wording from
before the fixed reference.)

### MACHINE

Everything that is not about the moment you are in.

**Six arc gauges:**

| Gauge | Notes |
|---|---|
| **ECONOMY** | The running average. In l/100 km, or US miles per gallon when miles are chosen — and the caution band changes ends with the unit, because thirsty is a high number in one and a low one in the other |
| **CYL HEAD** | Cylinder head temperature. This engine is air-cooled and has no coolant; the one temperature sensor it has sits in the front cylinder head. Caution from 120 °C |
| **BATTERY** | Volts. Caution below 12 |
| **AMBIENT** | Outside temperature, as the bike measures it |
| **NOW** | Economy at this instant, beside the average: the average says what the tank is doing, this says what your right hand is doing to it |
| **RANGE** | Range to empty as the motorcycle's own dash computes it — see [9.3](#93-fuel-range). Caution under 60 km |

A needle inside its caution band breathes at the caution rate and takes the
caution colour, figure included.

**Below them:**

| Field | Notes |
|---|---|
| **Odometer** | As the bike reports it |
| **Ride distance** | This ride, kept by the app — see [9.6](#96-ride-distance) |
| **Top speed, average, moving time** | This ride. **Average is over moving time**, not elapsed — see [9.7](#97-ride-figures) |
| **All-time records** | Highest speed and rpm ever seen, on their own line, separately resettable. A ride's top speed and every ride's top speed answer different questions and are not put on one line |
| **Service** | Distance to the next service — see [9.8](#98-service-remaining) |
| **Diagnostics** | Active fault codes, or a statement that there are none |

### HEAT

Heated clothing. Covered in full in [section 8](#8-heated-clothing).

| Element | Notes |
|---|---|
| **Felt temperature** | What it feels like at your current road speed, not what the thermometer says — see [9.4](#94-felt-temperature) |
| **Four buttons per zone** | Trousers-and-socks, and jacket. Off, low, medium, high, in the controller's own colours; the one in force is filled, and haloed while heat is flowing |
| **Gloves** | A line rather than a row of buttons. They have their own controller with no Bluetooth, so the app can tell you the conditions but cannot act |

---

## 7. Alerts

One banner across the top of the cluster, above the pages so that changing page
cannot dismiss it. Nine conditions can raise it. Only the worst one shows, and
this is the order they are ranked in:

| | Banner | Raised when | |
|---|---|---|---|
| 1 | TYRE PRESSURE | A wheel is more than 4 PSI from target, on the cold figure | Red |
| 2 | WHEEL SENSOR | The front or rear wheel speed sensor is reporting nothing | Red |
| 3 | KEY FOB | The fob is not found, or the bike has been searching for it for more than three seconds | Red |
| 4 | ACTIVE FAULT | Any active fault code | Red — amber when the bike keeps its own check-engine lamp off for that code and it is not one that ends the ride anyway |
| 5 | KILL SWITCH | The run/stop switch is at STOP with the ignition on | Amber |
| 6 | CHARGING | Below 12 V with the engine turning | Red |
| 7 | FUEL | Under 50 km left at worst | Amber |
| 8 | TYRE PRESSURE | A wheel is between 2 and 4 PSI from target | Amber |
| 9 | SERVICE | The service is overdue | Amber |

**Red breathes and buzzes; amber sits still and stays silent.** A red banner
breathes rather than flashes — a flash is read once and then tuned out — and
buzzes twice when it appears, distinct from the single tick a gear change gives,
so the pattern alone says which just happened. While it stands, the edges of the
screen glow red with it, dimmer than the redline glow, which wins when both are
true. An amber banner is information, not an interruption: a buzz for a service
that has been due for a week would teach you that the buzz means nothing.

**None of them can be dismissed**, because a warning you can swipe away is one
you will. Each clears itself when its cause does.

A few of them need a word:

- **Tyres** are judged on the cold figure, never the raw one, and on the value of
  the last reading, **never on its age**. A low reading from three weeks ago
  means the tyre was low when last measured and nothing has said otherwise
  since. It clears the moment the wheel next reports a good pressure.
- **Key fob.** With the fob in a pocket the search resolves inside a second;
  left indoors, the bike searches for twenty before giving up. A search still
  running after three seconds has already given the answer, while you are
  standing beside the bike rather than sitting on it.
- **Kill switch.** Not a fault — the answer to why the engine will not start. It
  carries the power symbol rather than the warning triangle, so it reads as new
  beside another amber banner.
- **Charging.** A running engine should hold well over 13 V. Below 12 the
  motorcycle is running off its battery, which ends with one that will not
  restart, and nothing else on the machine will tell you.
- **Fuel** uses the app's own worst-case estimate from the filtered level
  ([9.5](#95-fuel-level)), and only once that level has been measured on this
  ride — a level remembered from the last one may predate a fill-up.
- **Faults.** Which codes stay red whatever the lamp does is set out in
  DTC-CODES.md.

### Before you ride

Once per connection, while the bike is standing still, a card headed **BEFORE
YOU RIDE** covers the cluster. Everything on it is somewhere else in the app; the
point is when it is shown — the only moment all of it can still be acted on.

| Row | Says |
|---|---|
| **TYRES** | Front and rear cold figures from the last reading, judged against target |
| **FUEL** | Worst-case range from the last level measured while moving. Marked "(last ride)" when that was an earlier ride, and then never worse than amber |
| **BATTERY** | Volts at rest — green from 12.4, red under 12.0 — or volts while charging |
| **FAULTS** | None stored, or the first active fault |
| **SERVICE** | Distance to the next one |

Each row carries a green, amber or red dot; a grey one means the bike has not
said. The card leaves by itself once the bike is moving, a tap dismisses it
sooner, and it is offered again after the link has been lost and found — after a
fuel stop, which is exactly when it is wanted.

---

## 8. Heated clothing

### 8.1 What this does that the manufacturer's app cannot

Keis iControl sets a level. It cannot do anything else, because it has no idea
you are moving — it is a Bluetooth remote for a switch.

This app knows your **road speed** and the **ambient temperature**, both from the
motorcycle, so it works in *felt* temperature: at 8 °C standing still you want
little, and at 8 °C doing 110 km/h you want a great deal. It also sees the
**supply** feeding the garments, which is the motorcycle's own electrical system,
and will hold back to protect it.

### 8.2 Setting up

Under settings → heated clothing:

1. **Assign each controller.** Scan and pick. Both controllers look identical
   over the air, so **switch on only the one you are assigning** and leave the
   other off.
2. **Choose automatic or manual only.**
3. **Set each zone's curve** — its off-at and full-at temperatures, in felt
   degrees. Defaults are off at 25 °C, full heat at 10 °C.

**The clothing needs no pairing code**, unlike the motorcycle interface — the two
are opposites in this respect and it is easy to expect the wrong one. The "press
the button on the controller" step in the Keis manual is part of *their* app's
first-time setup, not a Bluetooth bond; a controller that has been added once
accepts a plain connection.

### 8.3 The curve

Two numbers per zone, and they mean exactly what they say:

- **Off at** — above this felt temperature, nothing.
- **Full at** — at or below this, full heat.

Low and medium split what lies between, with medium at the midpoint. On a 25/10
curve: off above 25, low from 25 down, medium from 17.5 down, high from 10 down.

### 8.4 Three levels, not a percentage

A Keis controller has **off, low (33 %), medium (66 %) and high (100 %)** — three
positions with their own colours, not a continuous scale. The app models it as
what it is. Asking for 45 % would produce a level you never chose, because the
driver would round it.

### 8.5 Why the same temperature can give different levels

**This looks like a bug and is not.** Each boundary carries a **1.5 °C band
applied against the direction of travel**: warming up must clear the point where
cooling down switched.

At 17 °C on a 25/10 curve, arriving from off gives **low**; arriving from high
gives **medium**. Both are correct.

Without the band the controller would flap between two levels every time you
slowed for a village, because felt temperature moves by several degrees each
time. When a level surprises you, the question is not what the temperature is —
it is what the level was a minute ago.

There is also a **rate limit**: a change of one level waits until 45 seconds have
passed since the last change — except a jump of two levels or more, which is a
real change of conditions and does not wait out a timer.

### 8.6 Manual control, and getting back

**Touching a position** sets that zone and takes it off automatic. While manual,
the zone still shows what automatic *would* have chosen.

**Holding anywhere on the zone** returns it to automatic, with a haptic tick to
confirm.

If you press the button on the controller itself, the app notices and adopts that
level rather than fighting it. On connecting it asks the controller what level it
is on and takes that answer — only a report that *contradicts* something the app
asked for is treated as you having intervened.

### 8.7 The supply cap

The garments are powered by the motorcycle. The app can see both the load it is
asking for and the supply feeding it, and it will cap the former to protect the
latter:

| Supply | Cap |
|---|---|
| Engine running, above 12.8 V | High — no limit |
| Engine running, 12.3–12.8 V | Medium |
| Engine running, below 12.3 V | Low |
| Engine stopped | Off |

This is a **cap, not a setting, and it overrides manual as well as automatic.**
Choosing high is not choosing a flat battery forty kilometres from anywhere.

It is the one place the app overrules the person using it, which is exactly why
the zone displays **CAPPED** instead of its mode, and the page says why. An
override nobody can see reads as a fault. A capped zone also shows a broken red
ring round the level you asked for, so both figures are on screen at once.

A supply reading has to hold for six seconds before it counts — starting the
engine drags the battery through both thresholds in a couple of seconds, and
acting on each crossing took a jacket to off, low, medium and off again in
twenty. Once
it has held, a tightening cap applies at once rather than waiting for the next
temperature change, which might be minutes away.

### 8.8 A garment that is not worn costs nothing

The app holds a standing connection request rather than polling. A jacket in a
wardrobe is not something to fail to reach every eight seconds for a whole ride.
The link establishes itself whenever the controller is switched on, and until
then the zone reads **WAITING** and says **"not on the bike — connects by itself
when switched on"** rather than reporting a fault.

### 8.9 Testing without riding

Most of it works in a garage. Two things will make correct behaviour look dead:

**The engine has to be running.** With the ignition on and the engine stopped the
supply state is engine-off and every zone is capped to off — deliberately,
because nothing is charging. Automatic control will appear to do nothing, and it
will be right.

**Move the curve, not the weather.** Changing "off at" in settings walks the zone
through its levels without waiting for the temperature to change, and exercises
the same path a ride would. The new level is written at once: a hand on a
stepper is a deliberate act, so a curve change skips the 45-second hold that
automatic drift has to wait out.

Wind chill cannot be tested standing still — below 4.8 km/h the felt temperature
is simply the ambient. Neither can the fuel filter, which only counts readings
taken above 10 km/h.

### 8.10 Reading the ride log

Every level written carries its reason:

```
keis: LEGS -> LOW   (auto felt=17 curve=25/10 FINE)
keis: LEGS -> MED   (resume)
keis: JACKET -> OFF (manual)
keis: LEGS -> MED   (handlebar: left double)
keis: LEGS -> OFF   (cap ENGINE_OFF)
```

An automatic write records the felt temperature, the curve in force and the
supply state, because a level that looks wrong is explained by its inputs and
nothing else.

`reports` lines are the opposite direction — the controller telling the app what
a physical button press did. A `reports` immediately followed by a write is the
app overruling the rider, which it should never do.

### 8.11 From the handlebar

Since firmware 2026.09.19-1 the bike reports its two trip buttons to the app
over BLE, and three gestures step the clothing without taking a hand off the
bar or an eye off the road:

| gesture | does |
|---|---|
| **left double** (two quick presses on the left trip button) | one step warmer, both zones |
| **both short** (both trip buttons together, briefly) | one step colder, both zones |
| **both long** (both held for 0.8 s) | both zones back to automatic |

A step moves from the level the garment is actually at, one notch, and stops
at OFF and HIGH — pressing once too often cannot turn a jacket from HIGH to
OFF on a cold road. Each step puts the zone in manual, exactly like a tap on
its panel, and the app turns to the HEAT page by itself so you see where the
level lands. Short presses keep paging the cluster as they always did, a long
*left* press still resets the trip meter (that is the cluster's own function
and cannot be changed), and a *right* double press is reserved for the house
(the garage door), so the app ignores it.

### 8.12 Two devices: who controls the clothing

Each Keis controller talks to **one** client. If the app is installed on both
a tablet on the bar and a phone in your pocket, the phone's background
service takes both controllers the moment it sees them, and the tablet never
gets them — you would see the HEAT page stuck on "not connected" until the
phone's Bluetooth is off.

So each install says whether it owns the clothing: **Settings → Clothing is
controlled by → THIS DEVICE** on the tablet that rides on the bar, **ANOTHER
DEVICE** on the phone in the pocket. A device set to ANOTHER DEVICE never
connects to the controllers, and flipping the button releases or takes the
controllers immediately, no restart. Its HEAT page shows both zones as WAITING
and says why: "another device controls the clothing — change it in settings"
(from release 6; up to 0.5 it showed the "not on the bike" line a switched-off
garment gets). The bike itself
serves two devices at once since firmware 2026.09.19-2, so both still show
the ride; only the clothing needs one owner.

---

## 9. Calculated figures, in full

Everything in this section is **computed by the app**, not read from the
motorcycle. Each carries its formula and its assumptions.

### 9.1 Cold-equivalent tyre pressure

**The problem.** A tyre reading 44.7 PSI at 42 °C is not over-inflated by 4 PSI
against a 41 target. It is warm. Brought to the 20 °C a cold pressure is
specified at, it sits at about 40.6 — which is *under* target. The dash cannot
tell you this, and the error runs the wrong way, so the correction matters.

**The law.** Gay-Lussac, on *absolute* pressure at **constant volume**:

```
P₁ / T₁ = P₂ / T₂          with temperatures in kelvin
```

**The calculation.** Gauge pressure must be lifted to absolute before the ratio
and dropped back after:

```
cold = (measured + 14.696) × (293.15 / tyreK) − 14.696

  where  tyreK    = tyre temperature °C + 273.15
         293.15   = the 20 °C reference, in kelvin
         14.696   = standard atmospheric pressure, PSI
```

**Why a fixed 20 °C and not today's weather.** A target such as 36 front and 41
rear is a cold pressure, and "cold" on a placard means a tyre at rest at a
nominal 20 °C. It is a fixed number, so the reading has to be brought to a fixed
temperature before the two can be compared. The figure was referenced to ambient
until 2026-09-14, and that answered a different question — what the tyre will
read once it cools down outside — which moves with the weather while the target
does not. At 11–12 °C the cold alone took 1.6 PSI of a 2 PSI tolerance, and a
front tyre that had lost no air raised an alert; the weekly trend, for the same
reason, reported the arrival of autumn as a leak. A fixed reference cures both.

**OUT is the other question, kept as information.** The same ratio with ambient
in place of the reference:

```
out = (measured + 14.696) × (ambientK / tyreK) − 14.696

  where  ambientK = ambient temperature °C + 273.15
```

On a 17 °C day the tyre above would read about 40.0 once cooled. That is worth
seeing — on a cold day a correctly filled tyre genuinely does sit below its
target, and it says whether a seasonal top-up is due — and not worth alarming on,
because the answer changes with the forecast.

**The assumption.** Constant volume. A tyre is not a rigid vessel — the carcass
flexes a little with pressure and temperature, and the contact patch deforms
under load. That error is small fractions of a percent against the 10–15 % a hot
rear tyre shows, so it is accepted and noted rather than modelled.

**Ambient is not needed for the cold figure.** With no outside temperature only
OUT is blank, and it is blank rather than estimated: guessing the weather would
defeat the purpose of a figure that says what the tyre will read in it.

**The tolerance.** Within 2 PSI of target is fine, within 4 is worth watching,
beyond that is worth acting on. A reading outside 5–80 PSI or −30–95 °C is
discarded as a decode error rather than stored, and so is one that carries a
pressure without its temperature.

**Why not the rule of thumb.** "1 PSI per 10 °F" approximates the same thing and
is close enough over small spans, because it skips the conversion to absolute
pressure. The error grows the further you get from the reference, which is
precisely the hot rear tyre you most want to be right about. Doing it properly
costs nothing.

### 9.2 Weekly pressure trend

Ten readings are kept, **at least an hour apart** — without that spacing the ring
would fill in ten seconds and describe a moment rather than a season.

**Compared on cold equivalents, never on raw readings.** Two measurements taken
at different tyre temperatures differ by more than a fortnight's leak, so a trend
built on raw pressure would mostly describe the weather. The oldest and the
newest of the ten are compared, and nothing is said until they are at least
three days apart.

A slow puncture is the failure a rider cannot see and would most want warning of,
and it is only visible across weeks.

### 9.3 Fuel range

Two sources, and which one you are looking at changes what the number means.

**First choice: the motorcycle's own range to empty.** The bike broadcasts the
same figure its dash shows, and when it is there the app prints it exactly —
"211 km", not a band. This is a *report*, not a calculation: the ECU said 211, so
211 is what it said, and rounding it into a band would invent uncertainty that is
not the app's to add.

That figure is deliberately pessimistic. It runs on a recent-consumption window,
so it reads low on a full tank after a spell of hard riding. It is also not
cleared when the bus goes quiet — like the fuel level and the odometer it remains
a true statement about a parked machine.

**Fallback, when the bike does not report it:** the app computes its own from the
filtered fuel level ([9.5](#95-fuel-level)), the tank capacity from settings, and
the economy seen over the last three minutes: 180 samples, one per state message
from the bike, collected whether or not the RIDE page is in front. (Up to 0.5
the window was fed ten times a second from the RIDE page only and covered nearer
twenty seconds.)

**That one is a band, not a figure.** A sender reading in whole percent and a
rolling average do not between them support "183 km", and printing it would claim
a precision neither input has. The band comes from recent economy, so a headwind
or a spell of town riding **widens it honestly** rather than being averaged away.

So: an exact figure is the bike's, a range of figures is the app's.

### 9.4 Felt temperature

The standard wind-chill formula, in Celsius and km/h:

```
felt = 13.12 + 0.6215·T − 11.37·v^0.16 + 0.3965·T·v^0.16

  T = ambient °C, v = road speed km/h
```

**Outside its domain it returns the ambient unchanged**, which is the honest
answer rather than an extrapolation:

- above **10 °C** — wind chill is not defined there
- below **4.8 km/h** — there is no meaningful airflow

Both inputs come from the motorcycle. Without an ambient temperature there is no
felt temperature, and automatic control holds its last level rather than
computing from stale weather; the same holds when the link to the bike is lost.
Without a speed, the ambient is used as it is.

### 9.5 Fuel level

**The problem.** A float sender measures the fuel surface where it happens to be,
and on a motorcycle that surface is rarely flat. Left on the side stand the bike
leans far enough that the sender sits high out of the fuel and reads several
percent low — enough to put a nearly full tank into a critical warning, in a
garage, untouched. A rider shown that once learns to disbelieve the fuel warning,
and a disbelieved warning is worse than none.

**The fix, in two parts:**

1. **A reading only counts while the bike is moving** (above 10 km/h). A
   motorcycle in motion is upright by definition, which turns a hard problem —
   knowing lean angle from a bus that never reports it — into a simple one.
2. **The trusted figure is the median** of a window of moving samples, not the
   average. Braking and accelerating throw fuel up and down the tank; a median
   ignores the sloshing entirely, where an average folds every surge into the
   answer.

**How the bar is coloured.** Each block takes its colour from **where it sits in
the tank**, not from how full the tank currently is, interpolated between the
app's three semantic colours and pinned to the two thresholds the tank actually
has (25 % low, 12 % reserve). The bar therefore reddens as it empties without a
threshold ever having to be crossed — the last blocks a rider runs on were always
the red ones. A flat green bar that flipped to flat amber at 25 % said the same
thing later and all at once. Unlit blocks keep a faint ghost of their own colour,
so the empty end still reads as a scale rather than as a row of holes.

**Three pulses, three meanings**, readable from the corner of an eye without the
number:

| | rate | what breathes |
|---|---|---|
| Normal | 1700 ms | the leading block only — the app's heartbeat, the same cue the grips give their top detent. Life, not warning |
| Low, ≤ 25 % | 1100 ms | the leading pair. Something to plan for |
| Reserve, ≤ 12 % | 700 ms | the whole lit bar, the percentage and the glow, together, and the glow swells as well as beats. Something to act on |

Peripheral vision answers to movement long before it answers to hue, which is the
whole argument for pulsing something rather than only colouring it. None of the
tiers fire during the start-up sweep: a warning that goes off on every start is
one nobody reads by the second week.

### 9.6 Ride distance

The motorcycle has two trip meters, broadcasts only the first, and the interface
is listen-only — so neither can ever be zeroed from the app. This one is the
app's own: a mark dropped on the bike's trip reading, with everything since
counted as the ride.

**When it resets is the whole design.** The obvious rule — reset when the bike
starts — is wrong: stopping for fuel would wipe the hundred kilometres you just
rode. What a rider means by "this ride" survives a coffee, a tank and a
photograph, and ends when the bike is put away.

So the rule is **length of sleep, not the act of starting.** If the app has not
heard from the bike for **three hours**, whatever comes next is a new ride;
anything shorter continues the old one. Silence is the measure because the phone
usually loses Bluetooth before the key comes out, so the ignition going off is
often never seen — when it is seen, three hours of ignition off counts the same
way. That puts a long lunch and a ferry crossing safely inside the same ride
while an overnight stop starts fresh. A long press on the ride figure starts a
new one by hand.

All of it is local. Nothing here needs a network or a server — the number should
be right on a mountain road with no signal.

### 9.7 Ride figures

Top speed, average speed and moving time, for this ride.

**Average is over moving time, not elapsed time.** An average that counts twenty
minutes in a ferry queue is a number about the day, not about the ride, and it is
the wrong one to set beside a top speed.

Held in memory only. A ride is a session; carrying half of one across an app
restart would produce a figure that quietly means nothing. All-time records, by
contrast, are persisted and separately resettable.

### 9.8 Service remaining

Two numbers and a subtraction: the odometer at the last service, plus the
interval, minus where the odometer is now. The value is not in the arithmetic but
in nobody having to remember the mileage of an oil change from eight months ago.

The app reads the **motorcycle's** own answer first and falls back to its stored
setting only when the bike has none. A phone is the wrong home for a figure that
belongs to the machine: phones get reinstalled, replaced, and dropped in car
parks.

---

## 10. Settings reference

Reached from the gear at the end of the tab row. Every value is a **stepper**
rather than a text field or slider: these are round numbers changed occasionally
by known increments, a stepper cannot produce a nonsense entry the way a keyboard
can, and it is the only control here you have a chance of using with gloves on.

| Setting | Notes |
|---|---|
| Front / rear tyre target | The cold pressures each wheel is judged against. Bike- and tyre-specific; the app has no business guessing them |
| Redline | Read by the dial, the edge glow **and** the haptic — one number where there were three constants that could drift apart |
| Tank capacity | Litres. Only used for the app's own range estimate, which is the fallback when the motorcycle does not report its own — see [9.3](#93-fuel-range) |
| Tyre pressure units | PSI, kPa or bar — independent of the other units |
| Speed and distance | km/h and kilometres, or mph and miles. Economy follows it: l/100 km, or miles per US gallon — US because that is what Indian's own Ride Command shows |
| Temperature | °C or °F, for coolant, ambient and tyres |
| Screen brightness | Automatic or maximum. Full brightness beats direct sun behind a visor and dazzles at night, so which is right is the rider's call on the day |
| Screen orientation | Follow the phone, or locked to portrait or to landscape. A mount holds the phone one way up, and auto-rotate on a motorcycle answers to bumps and lean angle as readily as to intent |
| Ride distance | Reset. Also clears the ride figures — resetting half of them would leave a top speed from a road you are no longer on beside a distance of zero |
| Service interval | The manufacturer specifies 8000 km for the Thunder Stroke. Steps of 500 |
| Last service | Tap, and type the odometer reading at the last service. The box opens on the reading now, for the day the service has just been done, and shows Trip 1 beside it for reference. A figure above the odometer is refused. It is sent to the motorcycle and kept there; the phone keeps it only until the bike can be told. |
| Trousers / jacket controller | Scan and assign. Switch on only the one being assigned |
| Heated clothing | Automatic from felt temperature, or manual only |
| Clothing is controlled by | **THIS DEVICE** (the default): this install connects to the Keis controllers and runs the automatic and manual control, the handlebar gestures included. **ANOTHER DEVICE**: this install never connects to the controllers and only shows the bike; some other install owns the clothing. A Keis controller talks to one client, so exactly one device per bike says THIS DEVICE — the one on the bar — and the phone in the pocket says ANOTHER DEVICE. Flipping it takes or releases the controllers at once — see [8.12](#812-two-devices-who-controls-the-clothing) |
| Curve endpoints | Off-at and full-at per zone, in felt degrees. Both zones start at 25 and 10, and the two ends are held at least 3 degrees apart. |
| Fault codes | Shows how many are active. Tap to name one of the codes the bike is reporting now, so it is recognisable next time |
| All-time records | Reset the highest speed and rpm ever seen |
| Bluetooth pairing | Opens the system screen. The only cure for a stale bond is forgetting the device, so the app points at the door rather than describing where it is |
| Firmware line | **Long-press** for diagnostics — see [11](#11-about-diagnostics-and-the-ride-log) |
| Maker's plate | **Press** the copyright line for About: licence, hardware, why it cannot transmit, and the app and firmware versions together |

---

## 11. About, diagnostics and the ride log

### About

**Press the maker's plate** — the copyright line at the foot of the settings
page. A plain press, and it opens a plain page: the licence, what the machine is,
what the hardware is, why it cannot transmit, the bus and its speed, how the link
works, and the app and firmware versions side by side.

From release 6 the hardware line names the CANFD-MC ([section 1](#1-what-it-is))
and the link line says MQTT reaches home over WiFi or a phone hotspot. Up to 0.5
it named the LilyGO T-2CANFD, the board this started on.

It is a short press on purpose, where diagnostics below is a long one. Diagnostics
is for whoever is debugging this; About is for anyone holding the phone and
wondering what it is wired to.

**The versions are the useful part in practice.** App and firmware are shown
together, which is the first thing to check when a phone and a motorcycle
disagree about a figure.

### Diagnostics

**Long-press the firmware line** in settings to open a raw view, in this order:

| Section | Holds |
|---|---|
| Header | App version with the time this copy was installed, firmware version, link state, signal strength, MTU, the last status |
| FAST | The last fast packet in hex with its decode beside it |
| GRIPS | Level and both grip temperatures |
| WHEEL SENSORS | The verdict, both wheel speeds, and the dropout counters since the interface booted — a sensor being worn away shows as a rising count long before it sets a fault |
| STAND | Upright, on the stand, or down |
| FAULTS | Every active code in full, with SPN, FMI and P-code, and the four lamps |
| HEAT | Which controller is assigned to which zone, its level, the curves, and the supply state |
| SERVICE | The figure on the bike beside the figure on the phone, the interval, and what remains |
| FUEL | The sender's level beside the filtered one, and the tank capacity |
| TYRES | Age, and per wheel the reading, its temperature, the judged cold figure, the target and the verdict |
| UNITS | The units in force — the first suspect when a number looks wrong on screen and right on the bus |
| STATE | The last state message, as it arrived |
| LOG | The tail of the rolling log |

**Long-press the dump itself to send all of it as text.** A screenshot loses
whatever did not fit on the screen, and what did not fit is usually the log.

It is a long-press rather than a tab on purpose. A rider has no use for it, and a
screen nobody needs should not cost a swipe.

The log is also written to a file, capped at 64 KB, because `adb logcat` only
helps while the phone is attached to a computer — which is the one place it will
never be during a ride. Pull it with:

```
adb shell run-as dk.agesen.springfield cat files/ridelog.txt
```

---

## 12. Troubleshooting

| Symptom | Cause |
|---|---|
| **A tyre shows no OUT figure** | Ambient temperature was unknown when the reading was taken. The app will not estimate it; COLD and the alerts do not need it |
| **The tyre page says "corrected to N°C ambient"** | The wording of 0.5 and earlier; update the app. COLD is at a fixed 20 °C — see [9.1](#91-cold-equivalent-tyre-pressure) |
| **Tyre readings are hours or days old** | Normal. TPMS sensors sleep when the wheels stop. The age is shown for exactly this reason |
| **A tell-tale is struck through** | The bus has never mentioned that signal. Not a fault in itself |
| **Heat does nothing in the garage** | The engine must be running — see [8.9](#89-testing-without-riding) |
| **A zone says CAPPED** | The supply is limiting it. Check battery voltage — see [8.7](#87-the-supply-cap) |
| **A zone says WAITING, "not on the bike — connects by itself when switched on"** | The controller is off or out of range. Not a fault; the link forms by itself. On a device set to ANOTHER DEVICE the same line shows permanently, and there it means the setting — see [8.12](#812-two-devices-who-controls-the-clothing) |
| **A banner will not go away** | It cannot be dismissed; it clears when its cause does. A tyre banner stands until the wheel next reports a good pressure — see [7](#7-alerts) |
| **KILL SWITCH across the top** | The run/stop switch is at STOP. The engine will not start until it is moved back |
| **The heat level is not what the curve suggests** | Hysteresis. Ask what the level was a minute ago — see [8.5](#85-why-the-same-temperature-can-give-different-levels) |
| **Fuel reads low on the side stand** | Expected, and ignored: only moving readings count — see [9.5](#95-fuel-level) |
| **An automatic level lags the temperature** | A one-level change waits out 45 seconds since the last change, to prevent flapping. A curve change in settings, and handing a zone back to automatic, do not wait |
| **Both controllers assigned to one garment** | They look identical over the air. Re-assign with only one switched on |
| **The tablet cannot reach the bike or the clothing while the phone is in a pocket** | One client per controller, and until firmware 2026.09.19-2 one per bike. Set the phone to *Clothing is controlled by → ANOTHER DEVICE*, and update the bike's firmware — see [8.12](#812-two-devices-who-controls-the-clothing) |
| **A handlebar gesture does nothing** | The bike must run firmware 2026.09.19-1 or later, and the app must be connected to it (the gestures arrive over BLE). A right double press is reserved for the house on purpose — see [8.11](#811-from-the-handlebar) |
| **Connects but shows nothing** | The pairing bond is stale or was never made. The link stays unencrypted, so the characteristics read as empty. Forget the device in Android's Bluetooth settings — **not just in the app** — and pair again with the passkey |

---

## 13. Known limits

- **Gloves cannot be controlled.** They have their own controller with no
  Bluetooth. The page reports the conditions anyway, because knowing is useful
  even where acting is not possible.
- **Constant volume is assumed** in the tyre correction. See
  [9.1](#91-cold-equivalent-tyre-pressure).
- **The app's own fuel range is a band**, and deliberately so. It is only the
  fallback — the motorcycle's own figure is printed exactly whenever the bike
  reports one. Anyone wanting a single number from the band should read the low
  end of it.
- **Ride figures do not survive an app restart.** All-time records do.
- **Wind chill is undefined above 10 °C**, where felt temperature is simply the
  ambient. Heated clothing is not wanted at those temperatures in any case.
- **The interface cannot transmit.** Nothing here can change anything on the
  motorcycle, by design.

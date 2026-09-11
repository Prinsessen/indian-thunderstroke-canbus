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
11. [Diagnostics and the ride log](#11-diagnostics-and-the-ride-log)
12. [Troubleshooting](#12-troubleshooting)
13. [Known limits](#13-known-limits)

---

## 1. What it is

An instrument panel that reads the motorcycle's CAN bus through a small ESP32
interface and shows it on a phone mounted on the handlebars, alongside figures
the factory dash does not offer at all: tyre pressure corrected for temperature,
fuel range, a ride trip that survives a coffee stop, and automatic control of
heated clothing based on how cold it actually feels at road speed.

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
| Permissions | Bluetooth scan and connect. No location, no internet |
| Hardware | The CAN interface, powered from the motorcycle |
| Optional | One or two Keis heated-clothing controllers |

On first launch the app asks for the two Bluetooth permissions. Grant them and it
starts scanning by itself — there is no pairing code, no bond, and nothing to
type.

**It finds the interface by the service UUID in the advertising packet, not by
device name.** That is deliberate: a name filter breaks the moment the firmware's
Bluetooth device name is changed, and the UUID does not move. So renaming the
interface costs nothing, and no device name has to be kept in step between the
firmware and the phone.

Set these before the first real ride, all under the gear icon at the end of the
tab row:

1. **Front and rear tyre targets** — the cold pressures your tyres are judged
   against. Defaults are 36 PSI front, 41 PSI rear.
2. **Tank capacity**, in litres. This is the one figure the bus cannot supply,
   and without it there is no fuel range.
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
| `Found the bike, connecting…` | Seen it, opening the link |
| `Connected, discovering services…` | Linked, reading the interface's capabilities |
| `Connected` | Running |

Alongside it sits **signal strength in dBm**, polled every two seconds. It is
there because a link about to drop looks exactly like a healthy one until the
moment it goes, and −90 dBm is the only warning available in advance.

**Battery use.** Scanning costs power; a connection does not. The app scans
aggressively for the first 45 seconds — the time you are walking up to the bike —
then drops to a low-power scan retried every 20 seconds. A phone in a pocket a
mile from the garage is not scanning flat out all day.

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
| **Redline** | Set once in settings. The dial marking, the edge glow and the haptic warning all read the same number |
| **Fuel bar** | Level as a bar, with **range remaining** beside the label — see [9.3](#93-fuel-range) |
| **Tell-tales** | Indicators, high beam, neutral, ABS and the rest, in the three states above |

### TYRES

Both wheels, and the page is arranged around one idea: **a pressure without its
temperature is not a reading.**

For each wheel:

| Field | What it is |
|---|---|
| **Cold equivalent** | The large figure in the hub. What this tyre would read once cooled to ambient — the number to compare against a cold spec. Calculated, not measured: see [9.1](#91-cold-equivalent-tyre-pressure) |
| **Measured PSI** and **tyre temperature** | Side by side, given equal billing. The corrected figure is an inference and you should be able to see what it was inferred from |
| **Target** | Your cold target for that wheel, from settings |
| **Age** | "measured 4 days ago". TPMS sensors sleep when the wheels stop, so a parked bike reports nothing — exactly when someone walks up to check the tyres. The last complete reading is kept, and shown with its age, because a stale number labelled as stale is useful where an unlabelled one is misleading |
| **Weekly trend** | Whether pressure is holding, or how much it is losing per week — see [9.2](#92-weekly-pressure-trend) |

If ambient temperature is unknown, **the cold equivalent is blank.** It is not
estimated. See [9.1](#91-cold-equivalent-tyre-pressure) for why.

### MACHINE

Everything that is not about the moment you are in.

**Arc gauges:** economy, coolant temperature, battery voltage, ambient
temperature.

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
| **A bar per zone** | Jacket, and trousers-and-socks. Four positions: off, low, medium, high |
| **Gloves** | A line rather than a bar. They have their own controller with no Bluetooth, so the app can tell you the conditions but cannot act |

---

## 7. Alerts

Two conditions raise a banner across the top of the cluster, above the pages so
that changing page cannot dismiss it.

**An active fault code.** The interface reports the healthy case as a specific
string; anything else is a fault.

**A charging fault** — below 12 V with the engine turning. A running engine
should hold well over 13 V. Below 12 the motorcycle is running off its battery,
which ends with one that will not restart, and nothing else on the machine will
tell you.

The banner **breathes rather than flashes** — a flash is read once and then tuned
out — and it **cannot be dismissed**, because a warning you can swipe away is one
you will. It buzzes twice, distinct from the single tick a gear change gives, so
the pattern alone says which just happened.

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

There is no pairing code. The "press the button on the controller" step in the
Keis manual is part of *their* app's first-time setup, not a Bluetooth bond; a
controller that has been added once accepts a plain connection.

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

There is also a **rate limit**: changes under 10 % are ignored, and no more than
one change a minute — except a jump of 30 or more, which is a real change of
conditions and does not wait out a timer.

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
override nobody can see reads as a fault. A tightening cap applies at once rather
than waiting for the next temperature change, which might be minutes away.

### 8.8 A garment that is not worn costs nothing

The app holds a standing connection request rather than polling. A jacket in a
wardrobe is not something to fail to reach every eight seconds for a whole ride.
The link establishes itself whenever the controller is switched on, and until
then the page says **"will connect when switched on"** rather than reporting a
fault.

### 8.9 Testing without riding

Most of it works in a garage. Two things will make correct behaviour look dead:

**The engine has to be running.** With the ignition on and the engine stopped the
supply state is engine-off and every zone is capped to off — deliberately,
because nothing is charging. Automatic control will appear to do nothing, and it
will be right.

**Move the curve, not the weather.** Changing "off at" in settings walks the zone
through its levels without waiting for the temperature to change, and exercises
the same path a ride would. Expect a wait: a single-step change is held for 45
seconds to stop the level flapping, so the write follows the setting rather than
accompanying it.

Wind chill cannot be tested standing still — below 4.8 km/h the felt temperature
is simply the ambient. Neither can the fuel filter, which only counts readings
taken above 10 km/h.

### 8.10 Reading the ride log

Every level written carries its reason:

```
keis: LEGS -> LOW   (auto felt=17 FINE)
keis: LEGS -> MED   (resume)
keis: JACKET -> OFF (manual)
keis: LEGS -> OFF   (cap ENGINE_OFF)
```

`reports` lines are the opposite direction — the controller telling the app what
a physical button press did. A `reports` immediately followed by a write is the
app overruling the rider, which it should never do.

---

## 9. Calculated figures, in full

Everything in this section is **computed by the app**, not read from the
motorcycle. Each carries its formula and its assumptions.

### 9.1 Cold-equivalent tyre pressure

**The problem.** A tyre reading 44.7 PSI at 42 °C on a 17 °C day is not
over-inflated by 4 PSI against a 41 target. It is warm. Cooled to ambient it sits
at about 40.0 — which is *under* target. The dash cannot tell you this, and the
error runs the wrong way, so the correction matters.

**The law.** Gay-Lussac, on *absolute* pressure at **constant volume**:

```
P₁ / T₁ = P₂ / T₂          with temperatures in kelvin
```

**The calculation.** Gauge pressure must be lifted to absolute before the ratio
and dropped back after:

```
cold = (measured + 14.696) × (ambientK / tyreK) − 14.696

  where  tyreK    = tyre temperature °C + 273.15
         ambientK = ambient temperature °C + 273.15
         14.696   = standard atmospheric pressure, PSI
```

**The assumption.** Constant volume. A tyre is not a rigid vessel — the carcass
flexes a little with pressure and temperature, and the contact patch deforms
under load. That error is small fractions of a percent against the 10–15 % a hot
rear tyre shows, so it is accepted and noted rather than modelled.

**Ambient is mandatory.** With no ambient temperature the result is **blank, not
estimated.** Guessing it would defeat the purpose: the whole point is to say what
the tyre would read at the temperature it is actually going to cool to.

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
built on raw pressure would mostly describe the weather.

A slow puncture is the failure a rider cannot see and would most want warning of,
and it is only visible across weeks.

### 9.3 Fuel range

**Inputs:** filtered fuel level ([9.5](#95-fuel-level)), tank capacity from
settings, and the economy actually seen over roughly the last three minutes.

**Reported as a band, not a figure.** A sender that reads in whole percent and a
rolling economy average do not between them support "183 km". Printing that would
claim a precision neither input has.

The band is derived from recent economy, so a headwind or a spell of town riding
**widens it honestly** rather than being averaged away.

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

Both inputs come from the motorcycle. If either is missing, there is no felt
temperature and automatic control holds its last level rather than computing from
stale weather.

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

### 9.6 Ride distance

The motorcycle has two trip meters, broadcasts only the first, and the interface
is listen-only — so neither can ever be zeroed from the app. This one is the
app's own: a mark dropped on the bike's trip reading, with everything since
counted as the ride.

**When it resets is the whole design.** The obvious rule — reset when the bike
starts — is wrong: stopping for fuel would wipe the hundred kilometres you just
rode. What a rider means by "this ride" survives a coffee, a tank and a
photograph, and ends when the bike is put away.

So the rule is **length of sleep, not the act of starting.** Ignition off starts
a clock; if it comes back on within **three hours** the ride continues, and if
not a new one begins. That puts a long lunch and a ferry crossing safely inside
the same ride while an overnight stop starts fresh.

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
| Tank capacity | Litres. The one figure the bus cannot supply, and without it there is no range estimate |
| Tyre pressure units | PSI, kPa or bar — independent of the other units |
| Speed and distance | km/h and kilometres, or mph and miles |
| Temperature | °C or °F, for coolant, ambient and tyres |
| Screen brightness | Automatic or maximum. Full brightness beats direct sun behind a visor and dazzles at night, so which is right is the rider's call on the day |
| Screen orientation | Follow the phone, or locked. A mount holds the phone one way up, and auto-rotate on a motorcycle answers to bumps and lean angle as readily as to intent |
| Ride distance | Reset. Also clears the ride figures — resetting half of them would leave a top speed from a road you are no longer on beside a distance of zero |
| Service interval | The manufacturer specifies 8000 km for the Thunder Stroke |
| Last service | Tap to record one at the odometer showing now |
| Trousers / jacket controller | Scan and assign. Switch on only the one being assigned |
| Heated clothing | Automatic from felt temperature, or manual only |
| Curve endpoints | Off-at and full-at per zone, in felt degrees |
| Fault codes | Review, and name a code so it is recognisable next time |
| All-time records | Reset the highest speed and rpm ever seen |
| Bluetooth pairing | Opens the system screen. The only cure for a link-key mismatch is forgetting the device, so the app points at the door rather than describing where it is |

---

## 11. Diagnostics and the ride log

**Long-press the firmware line** in settings to open a raw view: firmware
versions, link state, signal strength, MTU, the last fast packet in hex with its
decode beside it, the last state message, and the tail of a rolling log.

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
| **A tyre's cold figure is blank** | Ambient temperature is unknown. The app will not estimate it |
| **Tyre readings are hours or days old** | Normal. TPMS sensors sleep when the wheels stop. The age is shown for exactly this reason |
| **A tell-tale is struck through** | The bus has never mentioned that signal. Not a fault in itself |
| **Heat does nothing in the garage** | The engine must be running — see [8.9](#89-testing-without-riding) |
| **A zone says CAPPED** | The supply is limiting it. Check battery voltage — see [8.7](#87-the-supply-cap) |
| **A zone says "will connect when switched on"** | The controller is off or out of range. Not a fault; the link forms by itself |
| **The heat level is not what the curve suggests** | Hysteresis. Ask what the level was a minute ago — see [8.5](#85-why-the-same-temperature-can-give-different-levels) |
| **Fuel reads low on the side stand** | Expected, and ignored: only moving readings count — see [9.5](#95-fuel-level) |
| **A level change lags a settings change** | A single-step change is held for 45 seconds to prevent flapping |
| **Both controllers assigned to one garment** | They look identical over the air. Re-assign with only one switched on |
| **Connects but shows nothing** | A link-key mismatch. Forget the device in Android's Bluetooth settings — not just in the app |

---

## 13. Known limits

- **Gloves cannot be controlled.** They have their own controller with no
  Bluetooth. The page reports the conditions anyway, because knowing is useful
  even where acting is not possible.
- **Constant volume is assumed** in the tyre correction. See
  [9.1](#91-cold-equivalent-tyre-pressure).
- **Fuel range is a band**, and deliberately so. Anyone wanting a single number
  should read the low end of it.
- **Ride figures do not survive an app restart.** All-time records do.
- **Wind chill is undefined above 10 °C**, where felt temperature is simply the
  ambient. Heated clothing is not wanted at those temperatures in any case.
- **The interface cannot transmit.** Nothing here can change anything on the
  motorcycle, by design.

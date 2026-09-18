# A display on the Springfield — what the bike offers, what the cluster owns, and how to do it without it looking bolted on (2026-09-16)

The question, as asked: a display that can be fitted to a 2017 Indian
Springfield so that it does not look DIY, that could in time replace the
factory instrument, and — because that is the part that decides everything —
what the ECM and the VCM do if the factory instrument is not there.

Written from the repository's own bus map (README, DECODE-PLAN, PROTOCOL), the
2017 rider's manual (Polaris PDF `lineup-2017.pdf`), Indian's fuel-gauge kit
instructions (9925621 R03), Indian Team Tip I-20-12-01 on odometer storage, and
the Danish inspection guide (Vejledning om syn af køretøjer, July 2024). Where
something has to be measured on the bike, it says so.

---

## 1. What the bike already has, and where

### The instrument node is three CAN devices behind one connector

Main-harness **C03 ("SPEEDOMETER")** carries CAN-H/CAN-L into the instrument
pod. Behind it, the instrument schematic shows a local splice feeding three
connectors (README, "instrument pod" section):

| pod connector | device | on this bike |
|---|---|---|
| **Co1** | Fuel_Gauge — CAN-H pin 3, CAN-L pin 2 | the accessory analogue fuel gauge position, **front-left of the console cover**; a factory plug fills the hole if no gauge is fitted (kit instructions 9925621: "remove fuel gauge plug … on front left of console", "locate six pin harness connection E and plug into fuel gauge") |
| **Co2** | Speedometer — the instrument cluster, **SA 23** | fitted, the tank-console gauge |
| **Co3** | Chassis_Speedo | **capped under the headlight housing**; CAN pins present, no device (README field note) |

So there are **two factory-wired positions for a second instrument** on this
motorcycle, both already on the bus: the fuel-gauge cutout in the console cover
(6-pin, CAN, and — to be confirmed from the schematic — power and illumination),
and the capped connector under the headlight. Neither needs a splice, a
bracket-from-the-hardware-store, or a wire that was not there from the factory.

**SA 136** is worth a second look in this light. DECODE-PLAN records it as a
node that declares *instrument cluster* (function 19) from a *different
manufacturer* (146 vs the cluster's 340), claims twice as often as anyone, and
has never transmitted a frame. The working theory was the saddlebag lock module.
A **listen-only accessory gauge** on the Co1 connector — a device that needs
fuel level from the bus and has nothing to say — fits the profile at least as
well. One look at the console cover settles whether a fuel gauge is fitted.
Either way SA 136 is the precedent: **a second, silent instrument node is
something this bus already tolerates.**

### What the factory cluster shows (2017 rider's manual, Springfield section)

Indicator lamps: chassis fault, TPMS, neutral, high beam, turn signals
(hazard), check engine, ABS not activated, cruise (amber = enabled, green =
set), low oil pressure, security system locked / fob searching, low battery
voltage.

MFD: speedometer (km/h or mph), odometer, trip 1, trip 2, engine speed, gear
(always, engine running), DC voltage, ambient air temperature, fuel range,
average fuel economy, clock, heated-grip level, fuel-level segments with a
low-fuel flash. Setup menus: units, clock, brightness, bottom-screen choice,
gauge hardware/software information, TPMS sensor registration (dealer).

### Which of those the firmware already has, from the bus

| cluster item | in this firmware | source on the bus |
|---|---|---|
| speed | `speed` | ABS, SA 11 |
| rpm, gear, throttle | `rpm`, `gear`, `throttle` | ECM, SA 0 |
| odometer, trip, ambient | `odometer`, `trip`, `ambient` | **the cluster itself, SA 23** |
| fuel level, range, economy | `fuel`, `range`, `fuelEconomy` | SA 0 (fuel also from SA 23) |
| battery voltage | `battery` | SA 0 |
| turn signals, hazard, high beam | `indLeft/Right`, `hazard`, `headlight` | VCM, SA 39 |
| cruise enabled / set | `cruiseEnable`, `cruise` | SA 39 / derived |
| security lamp | `security` | SA 39 (65386) |
| check engine, ABS lamp | DM1 lamps MIL / Warn | SA 0, SA 11 |
| heated grips | `grips` | SA 39 |
| TPMS pressures/temps | `tyreFront/Rear` | VCM, SA 39 |
| neutral | gear = N | SA 0 |
| **low oil pressure lamp** | not broadcast at key-on (2026-09-18) | switch on the VCM side; key-on/start/idle/key-off moved no DM1 lamp bit from SA 39 or SA 0 and no VCM status bit. Presumably a DM1 fault only with the engine running — assumed, not proven |
| **chassis fault lamp** | not decoded | DM1 from SA 39, presumably — to find |
| MFD/trip buttons (left, right) | `button` events | VCM, SA 39 (65381 byte 0, bits 0 and 2; 2026-09-18) |
| clock, settings | none | cluster-internal |

Everything a replacement display must show is on the bus except the clock and
the two lamps. The chassis-fault lamp has not been hunted yet and is a garage
evening with the DM1 decoder. The oil-pressure lamp was hunted on 2026-09-18
and is not broadcast at key-on; a replacement display will have to light it
from a DM1 fault, which can only be checked when a real low-pressure event
happens. The two trip buttons the display must react to are on the bus too.

---

## 2. What the cluster owns that nothing else on the bike has

This is the part that decides whether the factory instrument can ever leave.

1. **The odometer is stored in the instrument cluster on 2019-and-prior Chief /
   Springfield / Vintage.** Indian Team Tip I-20-12-01 (Dec 2020), component
   replacement guide: model years 2019 and prior — *Odometer data storage
   location: Instrument Cluster*; on replacement *"the miles will reset to
   zero"* and an Odometer Change Notification Form is required, three copies,
   one to Polaris. From model year 2020 the same models store it in the **ECM**.
   A 2017 Springfield is in the first group. The odometer figure lives in the
   gauge on the tank and nowhere else; the ECM does not have it.
2. **The odometer is *computed* there too**: front wheel-speed sensor → ABS →
   bus → cluster integrates distance (service notes: front WSS feeds the
   odometer, rear WSS the speedometer). Remove the cluster and the count stops.
   **The bus confirms it independently of Indian's paperwork:** the odometer
   travels in PGN 65217 (J1939 High Resolution Vehicle Distance, bytes 0–3,
   × 0.005 km, trip 1 in bytes 4–7), and in every raw capture in this
   repository that message has exactly one CAN ID — `18FEC117`, source
   address 0x17 = **23, the node that claims as Instrument cluster**. 824
   frames, none from any other sender; the ECM (SA 0) never transmits it.
   What openHAB shows as `CanBus_Odometer` (143 127 km on 2026-09-16) is the
   gauge on the tank reporting what it has counted itself.
3. **Ambient temperature** enters the bus from SA 23 — the AMB_AIR_TEMP sensor
   is on the instrument side of the harness. No cluster, no ambient.
4. **Trip odometers, clock, unit settings, brightness, the TPMS registration
   menu** — cluster-internal. Nobody else needs them; the dealer's TPMS tool
   does.
5. It is a **listener for DM1 and lamps** — but consumers cost the producers
   nothing. The ECM, ABS and VCM broadcast; they do not wait for the gauge to
   answer.

What the cluster does **not** own: the immobiliser. Key-fob search, found, not
found is **SA 39, the VCM** (PGN 65386, DECODE-PLAN "Tier 2b", found
2026-09-05). The cluster only shows the shield lamp. The power switch on the
console is a VCM input, not part of the gauge.

---

## 3. So: what happens if the cluster is absent

Reasoned from the bus map, not tested — see the note on testing below.

- **The engine starts.** Nothing in the start path — power switch → VCM →
  fob search → ECM enable — passes through SA 23.
- **ABS, cruise, lighting, TPMS reception, heated grips: unaffected.** All on
  SA 0/11/39; none consumes a cluster message that the captures have ever shown.
- **The odometer stops, permanently, and the figure is in the removed unit.**
  Any replacement display has to *carry* the last value forward and count from
  there — and that count is then private, not Indian's. (§5 says how to avoid
  ever being in this position.)
- **Ambient temperature disappears from the bus**, unless the sensor is rewired
  to the new instrument.
- **Fuel level stays** (SA 0 sends it; the firmware already prefers SA 0).
- **Fault memory, probably.** A generic J1939 network does not require a
  cluster, but a Polaris VCM/ECM may log a lost-communication fault for a
  missing expected node. Unknown for this platform. The owner's standing rule
  in DECODE-PLAN applies: *writing a self-inflicted fault into a machine whose
  fault memory is evidence in a dealer dispute is not worth a module name.* So
  the unplug-and-see test is cheap (5 minutes, C03 unplugged, logger on DIAG,
  ignition on, watch DM1 from SA 0 and SA 39 and whether the shield lamp and
  start behave) — and it is the owner's call whether it is worth a stored code.
- **Legally (Denmark), Vejledning om syn 2024, 10.04:** a motorcycle *must* have
  a speedometer showing km/h (10.04.030 (1), 10.04.002 (1)), readable without
  difficulty from the seat, illuminated when the mandatory lights are on, and
  *not GPS-based* (10.04.001). Accuracy per UNECE R39 applies to vehicles from
  1 July 2024 and is not checked at inspection. **The odometer requirement
  (10.04.030 (2)) applies only to motorcycles registered from 1 July 2024** — a
  2017 machine is exempt at inspection. That does not make the odometer
  unimportant: resale, the dealer relationship, and Indian's own odometer form
  all assume the factory figure exists.

**Bottom line:** the cluster can be *out of sight* but should never be *off the
bus*. Everything below is built on that.

---

## 4. Three ways to put a display on this motorcycle, ranked

### A. Additive: keep the cluster, put the display in a factory position — do this first

**A1. The console fuel-gauge position (Co1).** The console cover has a factory
cutout front-left with a 6-pin harness behind it that carries CAN. Indian sells
an analogue gauge for exactly this hole (2880731 / 2883445; fits 2014–2024
Springfield). A round display of the same outside diameter as that gauge, in
the same trim ring finish (chrome or black, both exist as OEM parts), sits in a
hole the factory made, on a connector the factory wired, next to a gauge the
factory designed. That is the definition of not looking bolted on.

What it needs: the cutout diameter and the gauge's bezel diameter and depth
(**measure**, or read the fuel gauge kit drawing); the 6-pin pinout from the
instrument schematic (**CAN-H pin 3, CAN-L pin 2 are known; confirm switched
12 V, ground and the illumination line** on the other four). A 2.1" round
480×480 panel has a 53 mm active circle, which is the size class of a 52 mm
(2-1/16") gauge; whether that is the Indian gauge's size is the first
measurement. The S3 drives a 2.1" RGB panel today; brightness is the problem
(see §6).

**A2. The capped Chassis_Speedo connector under the headlight.** A Chieftain-
style pod on the nacelle. Same bus, factory connector, but no factory hole and
no factory bracket — every mounting choice is yours, and that is where "DIY"
comes from. Second choice, or the place for a larger screen later.

### B. Replacement look, additive electronics: the cluster's *housing* with a new face

Keep the tank-console gauge housing, bezel and glass. Replace the dial, needle
and LCD with a **4" round 720×720 TFT** behind the original glass. Indian did
exactly this on the 2022 Chief: the 4" Ride Command display *"looks like a
traditional, old-school single-bezel gauge cluster"* and is a round TFT in the
classic gauge position. The look is proven by the manufacturer.

The trick that makes B safe: **do not remove the cluster's electronics from the
bus.** Relocate the OEM PCB (with its LCD unread) inside the console or under
the seat, still plugged into Co2. It keeps counting the odometer, keeps
broadcasting ambient, keeps its settings and its TPMS menu, and can be put back
behind its own glass in twenty minutes for an inspection or a dealer visit. The
new display mirrors the odometer from SA 23 and never needs a counter of its
own. Nothing Indian owns is touched; only what the rider looks at changes.

What it needs: housing inner diameter and depth behind the glass (**measure**;
a 4" panel is 101.5 mm active on a ~105 × 110 mm module, 2.3 mm thick, plus a
controller board); a MIPI-DSI host, i.e. **ESP32-P4** (FUTURE-HARDWARE.md §1),
or a smaller RGB/SPI round panel on the S3; and the two undecoded lamps.

### C. Retrofit Indian's own 4" Ride Command — no

Forum consensus (indianmotorcycles.net, "4in Round Ride Command Retrofit
Feasibility"): it needs the 2022 harness, VCU and ECU. It is a different
platform behind a similar-looking gauge. Not a path.

---

## 5. The odometer strategy, whichever path

- While the OEM cluster is on the bus (A, and B done as above): **mirror SA 23.**
  The display shows the factory figure; trip 1/2 likewise. No local counter.
- Keep a **shadow counter in NVS anyway**, seeded from SA 23 and advanced from
  the ABS front-wheel speed, and log the divergence. It costs nothing and it is
  the evidence the day a dealer asks, or the day the OEM cluster fails (they
  do: forum threads on dead Chief speedometers are common).
- Only if the cluster ever leaves the bus for good does the shadow counter
  become the odometer — and by then it has months of agreement on record.

---

## 6. The display itself — what a motorcycle demands

The numbers that separate a gauge from a gadget:

| | bench / dev boards | a gauge on a tank in July |
|---|---|---|
| brightness | 300–350 nits (Waveshare P4 4" round: 350) | **≥ 1000 nits**, optical bonding, anti-glare glass |
| temperature | 0…60 °C | **−30…+85 °C** panel; black gauge face in sun exceeds 60 °C |
| glass | 6H toughened | toughened + AG; dimming at night (the OEM "illuminated when lights are on" rule, and glare) |
| vibration | FPC on a header | FPC glued and strain-relieved; no pin headers (the CANFD-MC rule) |

Concrete panels found 2026-09-16:

- **Saef SF-TO400XC-8996A-N**: 4" round, 720×720, MIPI-DSI 4-lane, **500–1000
  nits typ. 1000**, **−30…+85 °C**, 101.9 mm active, 105.3 × 109.6 × 2.3 mm,
  PCAP optional, MOQ 3. The production-class candidate for path B.
- Raystar RFN04000A2A0YWMNN00: same size class, 350 nits, −20…70 °C — the
  bench version of the same thing.
- **Waveshare ESP32-P4-WIFI6-Touch-LCD-4C** ($75): P4 + 4" round 720×720 with
  optical bonding, 350 nits, 0…60 °C, MIPI-CSI/DSI, USB-HS, SDIO. **The right
  board to design the UI on**, indoors, this winter. Not the board for the tank.
- 2.1" / 2.8" round 480×480 RGB panels (S3-driveable) for path A1 — brightness
  is the open question; ask the panel vendors for a ≥ 800-nit variant before
  choosing the size.

MIPI-DSI means ESP32-P4. Path B is therefore the same decision as
FUTURE-HARDWARE.md §1, and the display is the argument for the P4 that WiFi 6
never was.

---

## 7. What the software has to do

- Reproduce every lamp in §1 from bus data; decode the two missing ones.
- Speed from the ABS (SA 11), exactly as the OEM does — it is the mandatory
  instrument, it must never come from GPS (10.04.001), and it must be readable
  in daylight.
- Listen-only on the bus. If the display ever transmits (a request, an
  address claim), it claims as an instrument cluster (function 19) under its own
  manufacturer code, the way SA 136 does — and never as SA 23.
- Night/day from the headlight state on the bus (`headlight`) plus an ambient
  light sensor; the OEM rule is *illuminated when the lights are on*.
- Boot in under two seconds: the rider looks at the gauge as the fob resolves.
  The current firmware's WiFi/NTP/MQTT sequence must not sit in front of the
  first frame — the CAN drain and the render come first, the network later.

---

## 8. The order of work

0. **Measure** (one evening, console cover off — two screws): fuel-gauge cutout
   Ø and whether a gauge or a plug is in it; the 6-pin pinout from the schematic;
   cluster housing inner Ø, glass Ø, depth behind the glass; whether the OEM PCB
   could physically live elsewhere in the console.
1. **UI on the bench**: the Waveshare P4 4" round board, fed the firmware's
   state JSON over BLE or MQTT. Gauge themes, lamps, night mode, boot time.
   Indoors, no bike, no risk.
2. **Decode the two lamps** (oil pressure, chassis fault) with the DM1 decoder.
3. **A1 on the bike**: the console position, reversible, factory hole, factory
   plug. This is the first display anyone sees on the motorcycle, and it must
   already look right.
4. **B**, if A1 proves the look and the panel: the OEM housing with a 1000-nit
   4" panel, the OEM PCB relocated and still on the bus, the shadow odometer
   already months old.
5. **Inspection**: before B, ask a synshal what they accept as the speedometer
   when the factory gauge's electronics are present but its face is not. The
   answer decides whether the OEM face goes back on for inspections.

---

## 9. Open questions, in the order they block things

1. Is a fuel gauge fitted in the console cutout? (Look.) Is SA 136 that gauge?
   (Unplug it with the logger on; if the claim stops, it is.)
2. The 6-pin Co1 pinout beyond CAN: 12 V switched? ground? illumination?
3. Cluster housing dimensions; console cutout dimensions.
4. Does the VCM/ECM store a fault with C03 unplugged? (Owner's call whether to
   find out.)
5. Where the low-oil-pressure and chassis-fault lamps come from on the bus.
6. A ≥ 800-nit round panel in the A1 size.

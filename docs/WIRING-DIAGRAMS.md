# Wiring diagrams — the harness the way the schematics draw it

*Copy of `indian-springfield-cluster/docs/WIRING-DIAGRAMS.md`, kept here because the decoder work needs the same connectors. Edit there, copy here.*

*Excerpts from the 2017 Indian Motorcycle (Full-Size) Service Manual, PN
9927618 R03, section 10 and the schematic appendix (PDF pages 640–649),
cut at 600–800 dpi so the pin labels are legible. Read on 2026-09-19. The
manual is Polaris' copyright; these are excerpts kept for our own work.*

Everything in VCM-SPARE-INPUTS.md in the cluster repository that was "very
likely" on the evening of 2026-09-19 is settled here, because the appendix
that the manual refers to on page 10.79 turned out to be *in* the PDF, after
the index, where the page classifier had filed it as more index. The user
noticed. Lesson kept: the last pages of a service manual are the schematics.

## 1. Which schematic covers which bike

| PDF page | schematic | harness | models |
|---|---|---|---|
| 640 | **Chassis Schematic** | **2413259-03** | **Chief Classic / Vintage / Dark Horse / Springfield / Chieftain / Roadmaster** — one harness for all six |
| 641 | Taillight Harness | — | Chief Classic / Vintage / Dark Horse |
| 642 | **Speedometer Schematic** | 2413261 | **Chief Classic / Vintage / Dark Horse / Springfield** — the round cluster's own harness |
| 643 | **Fairing Schematic** | 2413260-03 | Chieftain / Roadmaster |
| 644 | Console Harness (bags / heated grips) | 2413580 | Chieftain / Roadmaster |
| 645 | Saddlebag Speaker | — | Chieftain |
| 646 | **Trunk Schematic** | IRON_D-2 | Roadmaster |
| 647 | Chassis Schematic | 2412300-06 | Chieftain Dark Horse (its own harness) |
| 648 | Rear Fender Harness | — | Chieftain Dark Horse |
| 649 | Fairing Schematic | — | Chieftain Dark Horse |

The first row is the finding. **The Springfield's chassis harness is the
Chieftain's and the Roadmaster's, part for part**, and the connectors the
fairing and the trunk plug into are on it. On a Springfield they hang there
with nothing in them.

![Chassis schematic, all six models](images/wiring-schematic-chassis-all-models.png)

## 2. The VCM's three plugs, from the schematic

The printed connector map (10.49–10.51) has "not used" and "–" on pins the
schematic draws wires to. The schematic is the authority; the map's names
are kept where they agree.

### VCM B RH — the switch inputs

![VCM B](images/wiring-vcm-b-inputs.png)

| pin | net | wire | what | Springfield |
|---|---|---|---|---|
| 1 | VB01 | RD/YE | control power | — |
| 2 | VB02 | WH/BK | mode switch 1 = **left trip button** (LH_CONT_1 P3) | used, on the bus |
| 3 | VB03 | GN | mode switch 2 = **right trip button** (RH_CONT_2 P6) | used, on the bus |
| 4 | VB04 | GY | horn switch (LH_CONT_1 P7) | used |
| 5 | VB05 | DG/YE | headlight hi/lo (LH_CONT_1 P6) | used, on the bus |
| 6 | VB06 | DG/BK | aux light switch (AUX LIGHT SW P2) | used — driving lights |
| 7 | VB07 | DB/BK | hazard (RH_CONT_2 P8) | used, on the bus |
| 8 | VB08 | YE/BK | **oil pressure switch** (OIL PRESSURE P1) | used |
| 9 | GND7-03 | BK | ground — the map's "not used" | — |
| 10 | VB10 | OG | cruise on/off (RH_CONT_2 P4) | used, on the bus |
| 11 | VB11 | WH | cruise set (RH_CONT_2 P3) | used, on the bus |
| 12 | VB12 | WH/DG | cruise resume (RH_CONT_2 P2) | used, on the bus |
| 13 | VB13 | BU/YE | grip increase (HG1 P2, via CHASSIS_SPEEDO 11) | used |
| 14 | VB14 | BU/BK | grip decrease (HG2 pin 2, via CHASSIS_SPEEDO 9) | used |
| 15 | VB15 | YE/DG | left turn (LH_CONT_1 P1) | used, on the bus |
| 16 | VB16 | BN | right turn (LH_CONT_1 P2) | used, on the bus |
| **17** | **VB17** | **BN/DB** | **windshield up — to CHASSIS_FAIRING pin 5** | **free** |
| **18** | **VB18** | **DG/DB** | **windshield down — to CHASSIS_FAIRING pin 4** | **free** |

### VCM 1 CNTR — outputs, and five lines the map does not name

![VCM 1](images/wiring-vcm-1-outputs.png)

| pin | net | wire | what | Springfield |
|---|---|---|---|---|
| 1, 2 | V101, V102 | YE, DG | high / low beam | used |
| 3 | HNDWRM-1 | PK | heated grips power | used |
| 4 | 252 | WH | horn | used |
| 5, 11, 20 | VCMPWR | RD | power | — |
| **6, 7** | **V106, V107** | **GY/DB, GY/DG** | **windshield motor A / B — to CHASSIS_FAIRING 2 / 3** | **free H-bridge** |
| 8, 9 | LOCKB-1, LOCKA-1 | OG/WH, RD/WH | bag / trunk lock motors | used (bags) |
| **10** | **V110** | **OG/BN** | **to CHASSIS_TRUNK pin 12 — and the Roadmaster trunk harness does not use it either** | **free, function unknown** |
| 12, 13 | V112, V113 | DG/RD, DG/BN | LH / RH aux light | used |
| 14 | V114 | BN/WH | the power-button line: power button P3, cluster pin 6, and CHASSIS_SPEEDO 14 | used |
| **15** | **V115** | **BU** | **to CHASSIS_FAIRING 7, then a 2-pin plug "VCM_15" in the fairing (V115 + ground)** | **free, device unknown** |
| **16** | **V116** | **GN** | **to CHASSIS_FAIRING 15, then HOMELINK pin 3** | **free — see §6** |
| 17 | V117 | BK | ground | — |
| **18** | **V118** | **YE/BN** | **to CHASSIS_FAIRING 14, then HOMELINK pin 2** | **free — see §6** |
| **19** | **V119** | **PK/DB** | **to CHASSIS_TRUNK pin 5 — unused by the trunk harness too** | **free, function unknown** |

### VCM A LH

![VCM A](images/wiring-vcm-a.png)

Pins 5 and 7 (WH) are the keyless antenna and a heat-shrunk blunt wire;
pin 10 is E116 PK, the K15 ignition-on line to ECM 1 pin 16; pin 18 is the
antenna ground; 8/9 are CAN. Nothing spare here.

## 3. CHASSIS_FAIRING — the connector that is empty under the nacelle

Chassis side (page 640) and fairing side (page 643), pin for pin:

![CHASSIS_FAIRING, chassis side](images/wiring-chassis-fairing-connector.png)
![CHASSIS_FAIRING, fairing side, and the windshield motor](images/wiring-fairing-side-chassis-fairing-and-windshield-motor.png)

| pin | chassis net | wire | fairing side | goes to (Chieftain / Roadmaster) |
|---|---|---|---|---|
| 1 | — | | — | empty |
| 2 | V106 | GY/DB | V106 | windshield motor P1 |
| 3 | V107 | GY/DG | V107 | windshield motor P2 |
| 4 | VB18 | DG/DB | VB18 | LH_SW_CUBE P2 — windshield DOWN |
| 5 | VB17 | BN/DB | VB17 | LH_SW_CUBE P4 — windshield UP |
| 6 | 047 | BG | RADPWR-1 | radio / amp power (fuse 40 "Radio Fuse Output → Fairing") |
| 7 | V115 | BU | V115 | the 2-pin "VCM_15" plug, with ground |
| 8–11 | 204–207 | DG, DG/BK, VT, VT/BK | 268–271 | speaker pairs, passed straight through to CHASSIS_TRUNK 8–11 |
| 12 | — | | — | empty |
| 13 | 145 | DB/WH | — | passed through to CHASSIS_TRUNK 13; neither end used |
| 14 | V118 | YE/BN | V118 | HOMELINK pin 2 |
| 15 | V116 | GN | V116 | HOMELINK pin 3 |
| 16 | 069 | BK | RADGND-1 | radio ground |

So, in the connector that hangs empty under the Springfield's nacelle, on
the harness the schematic says the Springfield has: **two VCM switch inputs
(4, 5), a reversible 12 V motor output (2, 3), three more VCM 1 lines of
unknown role (7, 14, 15), fused radio power (6) and a ground (16).** The
multimeter step in VCM-SPARE-INPUTS.md §5 is now a confirmation, not a
question.

## 4. CHASSIS_TRUNK — the same story at the back

![CHASSIS_TRUNK](images/wiring-chassis-trunk-connector.png)
![What the Roadmaster trunk plugs into it](images/wiring-roadmaster-trunk-chassis-connector.png)
![The trunk's taillight plug](images/wiring-roadmaster-trunk-taillight.png)

| pin | net | wire | Roadmaster trunk uses it as |
|---|---|---|---|
| 1, 2 | LOCKA-2, LOCKB-2 | RD/WH, OG/WH | trunk lock motor |
| 3 | — | | empty |
| 4 | E113 | WH/OG | **ECM 1 pin 13 — trunk tail (running) lamp** |
| 5 | V119 | PK/DB | **not used by the trunk** |
| 6 | 143 | BG | not used by the trunk |
| 7 | E150 | YE/RD | **ECM 1 pin 50 — trunk brake lamp** |
| 8–11 | 204–207 | | four speakers |
| 12 | V110 | OG/BN | **not used by the trunk** |
| 13 | 145 | DB/WH | not used by the trunk |
| 14 | ACCCONST-4 | GY/RD | accessory port, constant 12 V |
| 15 | GND3-07 | BK | ground |
| 16 | 900 | BK | not used by the trunk |

For a Springfield that is a second empty connector, at the rear, with two
ECM lamp outputs that follow the tail and brake lights (the map's "NA" on
ECM 1-13 is the trunk running lamp), constant fused 12 V, ground, and two
VCM 1 lines nobody uses on any model.

## 5. The Springfield's own cluster harness

![Speedometer schematic, Springfield](images/wiring-schematic-speedometer-springfield.png)
![The cluster connector](images/wiring-springfield-cluster-connector.png)
![CHASSIS_SPEEDO on the cluster harness, and the bag lock switch](images/wiring-springfield-chassis-speedo-and-bag-lock.png)
![Grip switch and power button](images/wiring-springfield-grip-switch-and-power-button.png)
![CHASSIS_SPEEDO, chassis side](images/wiring-chassis-speedo-connector.png)
![The ambient sensor and the 12 V outlets](images/wiring-ambient-sensor-and-12v-outlets.png)

**The cluster connector, 16 cavities, seven wires:**

| pin | net | wire | what |
|---|---|---|---|
| 1 | C02-1 | YE | CAN high |
| 2 | C02-2 | DG | CAN low |
| 3 | VA11 | OG/YE | VCM A 11 "switched power control" — the wake/ignition line |
| 4 | 215 | OG | INS12V, instrumentation 12 V from fuse 47 |
| 5 | GND-4 | BK | ground |
| 6 | V114-3 | BN/WH | the power-button line, shared with VCM 1-14 and the power button |
| 15 | 014 | OG/DB | **ambient air temperature sensor** |
| 7–14, 16 | — | | empty |

So the cluster has exactly one analogue input, and it is the ambient
sensor: a 2-pin plug on the chassis harness, `014 OG/DB` to cluster pin 15
and `GND1-02 BK` to ground, through CHASSIS_SPEEDO pin 3. That answers the
garage checklist's step 2 on paper — an NTC to ground, one signal wire — and
leaves only the curve to measure. Fuel level does not come here: the
Springfield's fuel gauge is a separate 6-pin plug on the same harness, and
the ECM reads the sender. Everything else the cluster shows arrives on pins
1 and 2.

The ABS breakout (10.93) that named "cluster pins 16 and 17" as CAN is the
Chieftain's fairing speedometer through its "Fairing Inline"; the round
cluster's CAN is pins 1 and 2. The cluster repository's ARCHITECTURE.md is corrected accordingly.

Also on this harness: HG1 (grip increase: ground, VB13, VCMACC-6
illumination), HG2 (VA13 indicator, VB14 decrease), POWER_BUTTON (ground,
VA04 ignition signal, V114-2), BAG_LOCK_SW (VA02 unlock, VCMACC-7, VA03 lock,
ground).

## 6. The switch cubes — and one spare cavity in each

![LH_CONT_1, RUN/STOP, RH CONT 1, RH_CONT_2](images/wiring-switch-cube-connectors.png)

| connector | P1 | P2 | P3 | P4 | P5 | P6 | P7 | P8 |
|---|---|---|---|---|---|---|---|---|
| **LH_CONT_1** | VB15 left turn | VB16 right turn | VB02 left trip button | **empty** | GND1-07 | VB05 headlight | VB04 horn | E119 clutch switch (ECM 1-19) |
| **RH CONT 1** | C09-1 PPS | C09-2 PPS | C09-3 PPS | GND1-06 | C12-1 PPS | C12-2 PPS | C12-3 PPS | E134 cruise safety switch (ECM 1-34) |
| **RH_CONT_2** | **empty** | VB12 cruise resume | VB11 cruise set | VB10 cruise on/off | GND1-09 | VB03 right trip button | VA01 start | VB07 hazard |
| RUN/STOP | GND1-05 | E123 run/stop (ECM 1-23) | | | | | | |

RH CONT 1 is the ride-by-wire twist grip: two pedal-position sensors, three
wires each. **LH_CONT_1 P4 and RH_CONT_2 P1 are empty on all six models.** On
the Chieftain the cube's extra buttons do not use them; they arrive through a
second 8-pin plug on the fairing harness:

![The Chieftain LH switch cube's second plug](images/wiring-chieftain-lh-switch-cube-fairing-connector.png)

LH_SW_CUBE (fairing harness): P1 ground, P2 VB18 windshield down, P3
VCMACC-5 (illumination), P4 VB17 windshield up, P5–P8 nets 211/210/209/208 —
four wires to the 7-inch display's pins 9, 10, 25, 26.

So the Tour cube (4015197) has **two leads with two 8-pin plugs**: the upper
half's lead goes to LH_CONT_1 on the chassis harness, exactly like the
Springfield's cube, and the lower half's lead (rocker, the five-way audio /
map buttons, backlight) goes to LH_SW_CUBE, **whose mate is on the Chieftain
fairing harness, not on the chassis harness.** Under a Springfield's nacelle
there is no LH_SW_CUBE socket; there is CHASSIS_FAIRING, 16 pins, chassis
side. Between the two sits a piece of fairing harness that a Springfield
never had: four wires (VB17 → pin 5, VB18 → pin 4, VCMACC-5 → any VCMACC
feed, ground → pin 16) and the four display lines going nowhere unless our
board takes them. That piece is the donor's to give — the LH_SW_CUBE branch
of fairing harness 2413260-03 with the CHASSIS_FAIRING plug on its other
end — or it is a pigtail we crimp. **Those four wires
are the audio and map buttons.** They never see the VCM; the display reads
them. Which is why they do nothing for a Springfield, and why the windshield
pair does: it is the only thing on that plug that goes to the VCM.

### The pigtail we cut out of fairing harness 2413260

What stays after the knife, and what each wire does on a Springfield:

```
   cube's lower lead ──▶ LH_SW_CUBE socket (8-way, on the harness)        CHASSIS_FAIRING plug (16-way, grey, red seal)
                                                                           mates the empty connector under the nacelle
        P1  GND-03    BK    ── ground ─────────────────────────────────▶  pin 16  (069 BK)
        P2  VB18      DG/DB ── windshield DOWN, VCM B18 ───────────────▶  pin 4
        P3  VCMACC-5  PK/GN ── backlight feed: not on this plug; tap a VCMACC line at the console (HG1 P3) or leave dark
        P4  VB17      BN/DB ── windshield UP, VCM B17 ─────────────────▶  pin 5
        P5  211       YE/WH ┐  the five-way audio/map buttons. No new wire to the seat is
        P6  210       BG/WH │  needed: jumper them onto CHASSIS_FAIRING pins 8–11 (nets
        P7  209       VT/WH │  204–207, the speaker pairs), which the chassis harness already
        P8  208       OG/WH ┘  carries to CHASSIS_TRUNK pins 8–11 at the back, unused on a
                                Springfield — five free wires nacelle→rear, with pin 13 (145)
                                as the fifth. Our board picks them up at CHASSIS_TRUNK.

   keep with 30 cm of tail, as test leads for the unknowns:
        pin 7   V115  BU     (VCM 1-15, the "VCM_15" device)
        pin 14  V118  YE/BN  (VCM 1-18, HomeLink)
        pin 15  V116  GN     (VCM 1-16, HomeLink)
        pin 2/3 V106/V107    (the windshield motor H-bridge, if B6 ever happens)
   cut and tape:  pin 6 radio power
   pins 8–11 and 13 are the highway to the back — see above, do not cut
```

**The button lines never needed a new wire.** The chassis harness already
has five conductors that run from the connector under the nacelle to the
connector at the back and are connected to nothing on a Springfield: 204,
205, 206, 207 (the Chieftain's speaker pairs, CHASSIS_FAIRING 8–11 ↔
CHASSIS_TRUNK 8–11) and 145 (pin 13 ↔ pin 13). Four button lines in at the
front, four lines out at the back, next to the VCM and our board. And nobody
has to imitate Ride Command: the display is just the thing that happens to
read those switches on a Chieftain; on ours, our board reads them and puts
the events where the trip buttons already go. If the five buttons turn out to
be a resistor ladder, it is one analogue line and a ground, and 145 alone
carries it.

So it is one 8-way socket, a run of loom the length the donor gives us
(the harness goes from the bars to the triple-clamp bracket, so half a metre
or so), and one 16-way plug with three wires that matter, five kept as test
leads, and the rest taped. The four display lines end at our board or in
tape. Nothing on the Springfield's own harness is cut.

## 7. The Chieftain fairing, for what it tells us

![The 7-inch display](images/wiring-fairing-7inch-display.png)
![HomeLink, ignition switch, VCM_15](images/wiring-fairing-homelink-ignition-vcm15.png)
![Fairing console and CHASSIS_SPEEDO, fairing side](images/wiring-fairing-console-and-chassis-speedo.png)

- **The display is a CAN node** (pins 29/30, C04), powered by switched and
  instrumentation 12 V, with the amp remote on pin 15. Its hand-control
  inputs are the LH cube's four wires and four more (198, 200, 261, 262)
  from the other cube's second plug.
- **HOMELINK** — a garage-door-opener transmitter, standard on these two
  models — is a 4-pin plug: 1 VCMACC-2 (accessory power), 2 V118, 3 V116,
  4 ground. V116 and V118 are **VCM 1 pins 16 and 18.** The manual's text
  never mentions HomeLink, so which way those two lines run is not written
  down: either the VCM triggers the transmitter (outputs), or it reads its
  buttons (inputs). Either way they reach the empty connector under the
  Springfield's nacelle on pins 14 and 15, and a multimeter tells the
  direction in a minute: an input floats at a pull-up voltage, an output
  sits at 0 or 12 V. Given what this project just built — a garage door from
  the handlebar — the irony is noted.
- **VCM_15** is a 2-pin plug, V115 and ground, somewhere in the fairing. No
  text, no name. Same measurement.
- IGNITION_SW is the fairing's power button: V114-2, VA04, ground — the
  same three lines as the Springfield's power button.
- The fairing console (bag lock and grip switches) even gets its own CAN
  pair (C16, pins 1/2), which the Springfield's console harness does not.

## 8. Other chassis-harness plugs worth knowing

![Grips, heated seat, turn signals, headlight, rear lighting, aux lights, aux switch](images/wiring-grips-seat-lights-aux-switch.png)

- **HEATED SEAT**, 2-pin: 260 BK and ACCSW-4 GY/WH — switched accessory 12 V,
  nothing more. The Roadmaster seat has its own controller; there is no
  module input for it. On a Springfield it is a fused switched 12 V under
  the seat.
- **AUX LIGHT SW**, 4-pin: VA12 indicator lamp, VCMACC-9 illumination, VB06
  the switch, ground. **LH / RH AUX LIGHT**: V112 / V113 from VCM 1.
- **FR_12V_SWITCHED, REAR_12V_SWITCHED** (ACCSW-2/-3) and **BATTERY_TENDER**
  (ACCCONST-2): fused accessory power, front and rear.
- **REAR_LIGHTING**, 6-pin: E152 tail, E151 brake, E248 / E236 turn, ground,
  AUXENG-7 — the ECM drives all of it, which is why the fault table has
  brake- and tail-light codes.
- **LH / RH_HAND_WARM**: ECMRTN, E226 / E216 thermistor to ECM 2, ground,
  HNDWRM PK power — the grip heaters are powered by the VCM and their
  temperature is read by the ECM.

## 9. The breakout diagrams and connector maps, for reference

| page | what | image |
|---|---|---|
| 10.86 | power windshield operation, with the switch on the LH cube | ![](images/wiring-manual-10.86-power-windshield.png) |
| 10.97 | power windshield wiring: VCM 1-6/7, VCM B-17/18, fairing connector, limit switches | ![](images/wiring-manual-10.97-power-windshield-wiring.png) |
| 10.94 | central locking: lock switch and power switch into VCM A, lock motors from VCM 1 | ![](images/wiring-manual-10.94-central-locking.png) |
| 10.96 | keyless ignition: antenna on VCM A 5/7/18, K15 on A10 → ECM 1-16 | ![](images/wiring-manual-10.96-keyless-ignition.png) |
| 10.95 | starting system | ![](images/wiring-manual-10.95-starting-system.png) |
| 10.93 | ABS module, wheel speed sensors, the CAN splices | ![](images/wiring-manual-10.93-abs.png) |
| 10.55 | horn breakout (the horn is a VCM load, not a bus signal) | ![](images/wiring-manual-10.55-horn-breakout.png) |
| 10.18 | starter circuit | ![](images/wiring-manual-10.18-starter-circuit.png) |
| 10.49–10.51 | VCM connector map as printed | ![](images/wiring-manual-10.49-vcm-connector-map-A.png) ![](images/wiring-manual-10.50-vcm-connector-1.png) ![](images/wiring-manual-10.51-vcm-connector-B.png) |
| 4.24–4.25 | ECM connector map as printed | ![](images/wiring-manual-4.24-ecm-connector-map-1.png) ![](images/wiring-manual-4.25-ecm-connector-map-2.png) |
| 643 | fairing schematic, Chieftain / Roadmaster | ![](images/wiring-schematic-fairing-chieftain-roadmaster.png) |
| 644 | console harness, Chieftain / Roadmaster | ![](images/wiring-schematic-console-chieftain-roadmaster.png) |
| 646 | trunk schematic, Roadmaster | ![](images/wiring-schematic-trunk-roadmaster.png) |

## 10. As built on this Springfield — where it differs from the schematic

Owner's modifications, told 2026-09-19, so nobody reads the schematic and
goes looking in the wrong place:

- **A Roadmaster heat-and-cool seat is fitted.** The version without Ride
  Command integration, so it is not on the bus and no module knows about
  it. It has its own controller and its own up/down switch, and its power
  is the HEATED SEAT plug in §8 (switched accessory 12 V, `ACCSW-4 GY/WH`).
- **Two console switches have swapped places, by rewiring.** The seat's
  up/down switch now sits on the right of the console next to the heated
  grip switch, where the saddlebag lock switch (BAG_LOCK_SW, §5) used to
  be; the bag lock switch has moved to the seat's old switch position on the
  left of the seat. Electrically nothing changed: the same four bag-lock
  wires (`VA02`, `VCMACC-7`, `VA03`, ground) and the seat controller's own
  wires, just routed to each other's holes.
- What that leaves open: the seat's up/down lines are momentary contacts on
  the seat controller, so a board of ours could read them in parallel (seat
  level in the app) or pulse them (seat from the handlebar or the app), the
  way the Keis clothing is stepped today. Not on the bus, so it would be our
  wire, not the VCM's.

## 11. What is still not on paper

- The direction and meaning of V110, V115, V116, V118, V119 — five VCM 1
  lines the map leaves blank and the schematics route to plugs (HomeLink,
  VCM_15) or to nothing. A multimeter at the empty connectors, ignition on.
- Whether the VCM broadcasts VB17/VB18 on the bus, and whether it runs the
  windshield logic at all on a Springfield configuration. The garage test in
  VCM-SPARE-INPUTS.md §5.
- The ambient sensor's curve (its pins are now known).
- What the 7-inch display's other four hand-control wires come from.

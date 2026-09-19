# Ideas — the backlog for the Springfield, so nothing good gets lost

One line per idea is not enough; each needs what it depends on and the
measurement that decides it, or it turns into a wish. Newest at the top of
each section. Move an item to "Done" when it ships; move it to "Dropped"
with the reason, never delete it. Started 2026-09-19, the night the
schematics turned up.

Status words: **idea** (nobody has checked anything), **measure** (one
garage evening decides it), **ready** (all facts known, only work left),
**done**, **dropped**.

## A. Inputs — buttons the rider can reach

| # | idea | status | depends on / what decides it | where it is written up |
|---|---|---|---|---|
| A1 | **The windshield rocker as two handlebar buttons.** VCM B17/B18 are the only VCM switch inputs a Springfield leaves unused; the wires are in the empty connector under the nacelle. If the VCM broadcasts them, they are two clean buttons with short/long/double and no cluster side effects — long press finally free | **measure** | garage evening: ground BN/DB then DG/DB with the logger running; watch 65381/65386 SA 39; read fault memory after | cluster `VCM-SPARE-INPUTS.md` §5, `WIRING-DIAGRAMS.md` §3 |
| A2b | **The five display buttons reach the board without new wiring.** Chassis harness nets 204–207 and 145 run nacelle→rear (CHASSIS_FAIRING 8–11, 13 ↔ CHASSIS_TRUNK 8–11, 13) and are unused on a Springfield: jumper the cube's four button lines onto them at the front, pick them up at CHASSIS_TRUNK next to the board. Nothing imitates Ride Command; the board reads switches and emits `button` events | ready | A3 (the cube and the pigtail); finding CHASSIS_TRUNK on the bike; a multimeter on the five-way (ladder or matrix) | `WIRING-DIAGRAMS.md` §6 |
| A2 | **Same rocker, read by our own board** if the VCM stays silent. Wire the rocker (or the spare cavity LH_CONT_1 P4 / RH_CONT_2 P1) to a GPIO on the CANFD-MC board; the firmware emits the same `button` events | ready once A1 is answered | A1 negative | `WIRING-DIAGRAMS.md` §6 |
| A3 | **A donor Chieftain/Roadmaster LH switch cube with the rocker** — factory part, same bar, same screws, the first 8-pin plug identical to ours. Its second 8-pin plug carries the rocker (VB17/VB18), illumination (VCMACC-5) and **four more lines (208–211) that are the audio back / play-pause / forward and map up / down buttons**: five extra buttons that only the display reads on a Chieftain, so on a Springfield they are ours if our board reads those four wires (a ladder or matrix — measure). Take the mating pigtail from the donor's fairing harness (2413260-03) or make one; it plugs into CHASSIS_FAIRING pins 4/5 and 14/15 already there. The donor's RH cube has the two Ride Command triggers on its own second plug — two more. Mind the 2018 cube-fastener change (spec table 1.56): a 2017 cube fits without question. **Part numbers (web, 2026-09-19, dealer fiche not seen):** the Tour LH control is `4015197-156` chrome / `4015197-067` medium gloss black — "SWITCH, CONTROL, LH, TOUR", sold as "light, horn, radio", fitted 2014–2017 Chieftain/Roadmaster, about USD 670 new, so a used one is the target; the cruiser LH control the Springfield has is `4014177-156`. RH Tour is unconfirmed (`4015198-156` by the numbering, not verified). The Scout cubes `4014449/50/51/52` that searches return are not this platform. The mating pigtail is in the Chieftain fairing harness `2413260` (schematic: 2413260-03), an orderable part ("Harness, Fairing 7in", 2017 Roadmaster, on eBay used) — take the LH_SW_CUBE branch and the CHASSIS_FAIRING plug from it. A used one was found 2026-09-19 at about USD 76 (eBay 257278220557, label 2413260 REV 02, all plugs intact in the photos: the two grey 16-way plugs with red seals, the 8-way cube plug, the 32-way display plug). Identify the cube branch by its colours: BN/DB, DG/DB, PK/GN, BK, OG/WH, VT/WH, BG/WH, YE/WH in one 8-way plug. RH Tour control, only for the triggers: `2413027-156` (one dealer; it carries the throttle sensor, so dear) | ready — the cleanest hardware answer, whichever way A1 falls | A1 (rocker on the bus) or A2 (our board); the four display lines are our board either way | `WIRING-DIAGRAMS.md` §6, §7 |
| A4 | **The two HomeLink lines** (VCM 1 pins 16/18) — if they are inputs, two more buttons anywhere; if outputs, two things the VCM can switch | **measure** | multimeter at CHASSIS_FAIRING pins 14/15, ignition on: pull-up voltage = input, 0/12 V = output | `WIRING-DIAGRAMS.md` §7 |
| A5 | **VCM_15** (VCM 1 pin 15) — a 2-pin plug in the fairing, device unknown | **measure** | same evening, CHASSIS_FAIRING pin 7 | `WIRING-DIAGRAMS.md` §7 |
| A6 | **Seat up/down read in parallel** — the heat-and-cool seat's switch is two momentary contacts into its own controller; our board reads them and the app shows the seat level | **measure** | what the contacts switch (to ground or to plus) and what they rest at | `WIRING-DIAGRAMS.md` §10 |

## B. Things the bike could do for us

| # | idea | status | depends on | written up |
|---|---|---|---|---|
| B1 | **Heated clothing from the handlebar** — left double warmer, both short colder, both long auto; app turns to the heat page | **done 2026-09-19** (firmware on the bike; app awaiting the Windows build) | — | app `CHANGELOG.md`, decoder `README.md` button section |
| B2 | **Garage door from the handlebar** — right double toggles at home, close-only away; reason on the sitemap | **done 2026-09-18/19** | — | `canbus_button_garage.example.js` |
| B3 | **Seat from the handlebar and the app** — pulse the seat switch lines through an optocoupler; "one step warmer" then means jacket, trousers and seat together, and a cold morning can be pre-warmed from the phone | idea → measure | A6 first | `WIRING-DIAGRAMS.md` §10 |
| B4 | **A third brake light that just plugs in** — ECM 1-50 at CHASSIS_TRUNK pin 7 follows the brake light; pin 4 follows the tail light; 14/15 are constant 12 V and ground | ready | finding the rear connector on the bike | `WIRING-DIAGRAMS.md` §4 |
| B5 | **Proper power for the CAN board and the Keis clothing** from a fused accessory outlet (FR/REAR_12V_SWITCHED, or CHASSIS_TRUNK 14) instead of the battery | ready | — | `WIRING-DIAGRAMS.md` §8 |
| B6 | **The windshield motor output as a free reversible 12 V channel** (VCM 1-6/7), driven by the rocker with the VCM's own H-bridge | idea | A1 positive, and something worth moving | `VCM-SPARE-INPUTS.md` §4 |
| B7 | **Trip marker from the handlebar** — a gesture drops a point with position and time into the trip tracker's diary | idea | one free gesture (A1 makes that easy) | — |
| B8 | **Fuel-stop log** — a gesture at standstill with the engine running logs a fill-up with the odometer, so fuel economy gets real numbers | idea | one free gesture | — |
| B9 | **Terndrupvej door** — the garage rule's twin for the work geofence (ID 10), which already has a GPS rule | ready | a gesture that is not right double | `vehicle-geofence-terndrupvej-door.js` |
| B10 | **"Home in a minute"** — both short inside the home geofence: driveway and garage light on, heat pump up, ahead of the tracker rule | idea | a gesture | — |
| B11 | **Position by SMS from the handlebar** — both double sends the bike's position to the family; the SMS path exists in the motorcycle rules | idea | a gesture; a lockout so a fumble does not spam | — |
| B12 | **Acknowledge an alert** — a gesture silences a battery or tyre alert for 30 minutes | idea | — | — |
| B13 | **A Roadmaster heated seat** would have been plug-in (HEATED SEAT plug on every chassis harness) — noted for the record; the heat-and-cool seat is already fitted | done, by the owner | — | `WIRING-DIAGRAMS.md` §8, §10 |

## C. The cluster replacement

| # | idea | status | depends on | written up |
|---|---|---|---|---|
| C1 | **The S3 cluster is seven wires**: CAN on 1/2, INS12V on 4, ground 5, wake 3, power-button line 6, ambient NTC on 15. The connector is on paper | ready to design | the SA 23 profile (transmit set is settled) | `WIRING-DIAGRAMS.md` §5, `GARAGE-SA23.md` |
| C2 | **The ambient sensor.** Pins known (cluster pin 15 + ground). Owner prefers not to keep the NTC (non-linear, drifts at the ends). **Preferred: a waterproof DS18B20 on the same two wires**, parasite-powered, 4.7 kΩ pull-up at the cluster, ±0.5 °C, no calibration, same PGN 65269 out; probe moved out of the headlight's heat soak. TMP117 / SHT45 (±0.1 °C) only if four wires are run. The NTC curve is only worth measuring if the factory sensor stays | ready | the S3 cluster; a probe placement out of the nacelle's heat | `GARAGE-SA23.md` step 2, `WIRING-DIAGRAMS.md` §5 |
| C3 | **The chassis lamp** — DM1 from SA 39, presumably; the oil lamp is SPN 98 FMI 4 and will only ever show in a real event | measure | a bulb test on the chassis lamp | `GARAGE-SA23.md` step 4 |
| C4 | **The unplug test** — what complains when SA 23 leaves the bus | owner's decision | the logger cannot clear DTCs | `GARAGE-SA23.md` step 6 |

## D. The decoder and the app

| # | idea | status | depends on | written up |
|---|---|---|---|---|
| D1 | Build and test the app with the handlebar gestures and the automatic-start fix | waiting for the Windows build | — | app `CHANGELOG.md` |
| D2 | Mask the grips' 0xFA pulse at cranking; `CanBus_EngineRunning` from 65265 b3 bit 6 | ready | — | `DECODE-PLAN.md` garage run 3 |
| D3 | Frame-rate capture of 65217 while riding (rates probe from the phone) | measure | a ride | `DECODE-PLAN.md` |
| D4 | One item per button side in openHAB instead of the shared `CanBus_Button` | dropped 2026-09-19 — one item with a reason field was judged simpler | — | `canbus.items` |

## The garage evening that answers most of section A

Ignition on, engine off, logger running, multimeter in the other hand:

1. Find CHASSIS_FAIRING under the nacelle: 16 cavities, GY/DB, GY/DG,
   BN/DB, DG/DB on pins 2–5. Find CHASSIS_TRUNK at the rear.
2. A1: ground pin 5 (BN/DB) for a second, then pin 4 (DG/DB). Never both.
   Watch the bus. Read fault codes afterwards (C1222/C1225 expected forms).
3. A4/A5: voltage on pins 14, 15, 7 against ground; note which float high.
4. A6: the seat switch on the console — what its two contacts switch and
   rest at.
5. C2: the ambient sensor plug (2-pin, OG/DB + BK, top of the chassis
   harness): resistance now, and again indoors at a known temperature.

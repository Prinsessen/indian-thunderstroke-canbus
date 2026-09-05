# Fault codes — 2017 Indian Springfield (Thunder Stroke 111)

What the app turns `SPN 84 FMI 2` into, and where each name comes from.

**Source:** the trouble-code table in the 2017 Indian full-size service manual
(9927618 R03), section 4 — Fuel Delivery / EFI. Codes and component labels only;
the manual itself is not in this repository.

**Generated from `Dtc.kt`.** If the two ever disagree, the code is what runs.

## How a code is read

Three parts, and the app shows all of them:

```
Crankshaft position sensor — erratic or intermittent  [SPN 636 FMI 2 P0335]
^ component                  ^ failure mode            ^ dealer   ^ scanner
```

A dealer and Digital Wrench work in **SPN/FMI**. A generic scanner and most forum
threads use the **P-code**. They name the same fault, so the app prints both.

The failure mode shown is the manual's own wording for that exact SPN/FMI pair
where it has one — 235 of them do. The generic FMI meaning is only the fallback,
and the difference matters: SPN 520304 FMI 12 reads as "component or ECU fault"
from the FMI table, and as a low battery needing replacement from the manual.
The key fob wants a coin cell. Only one of those two readings gets you home.

**Six faults stay red whatever the lamp does.** A key fob battery, system power
low, oil level low, engine temperature high, and either tyre pressure low. The
MIL answers whether the engine will be harmed, and answers it well; it does not
answer whether the rider's day is about to end. A flat coin cell cannot hurt the
engine, so the lamp stays off — and you still do not get home.

**Otherwise severity follows the manual's MIL column.** 23 of the documented conditions
leave the bike's own check-engine lamp off — a key fob battery, a tyre pressure
sensor going flat, coolant merely warm. Those show amber and stay silent. The
manufacturer already decided which faults are worth interrupting a ride over,
and the app has no business being redder than the machine.

## Where a name comes from

| Tier | Shown as | Meaning |
|---|---|---|
| Your own name | no marker | Overrides everything. Set it in Settings → Fault codes |
| Service manual | no marker | Polaris's own words. 115 codes |
| Generic J1939 | `· generic J1939` | SAE's standard meaning, not verified for this bike. 51 codes |

A code in none of these shows as `Unknown code` with its number intact, and can
be named from the app.

## Failure modes (FMI)

Fixed by J1939-73 and identical on every machine — Polaris cannot mean anything
else by them.

| FMI | Meaning |
|---|---|
| 0 | critically high |
| 1 | critically low |
| 2 | erratic or intermittent |
| 3 | voltage high, shorted to 12 V |
| 4 | voltage low, shorted to ground |
| 5 | open circuit |
| 6 | current high, shorted to ground |
| 7 | not responding mechanically |
| 8 | abnormal frequency or pulse width |
| 9 | abnormal update rate |
| 10 | abnormal rate of change |
| 11 | root cause unknown |
| 12 | component or ECU fault |
| 13 | out of calibration |
| 14 | special instructions |
| 15 | high, warning only |
| 16 | high, moderately severe |
| 17 | low, warning only |
| 18 | low, moderately severe |
| 19 | bad data received over the bus |
| 20 | drifted high |
| 21 | drifted low |
| 31 | condition exists |

## Codes from the service manual (115)

### Engine and fuelling

| SPN | Component | FMI → P-code |
|---|---|---|
| 190 | Engine speed | 0 → P0219, 1 → C1060, 2 → C1061, 7 → P1219, 19 → C1066, 31 → P121C |
| 636 | Crankshaft position sensor | 2 → P0335, 8 → P0336 |
| 651 | Injector 1 | 3 → P0262, 4 → P1262, 5 → P0261 |
| 652 | Injector 2 | 3 → P0265, 4 → P1265, 5 → P0264 |
| 731 | Knock sensor 1 | 4 → P0327 |
| 1268 | Ignition coil primary driver 1 | 3 → P1353, 4 → P1361, 5 → P1351 |
| 1269 | Ignition coil primary driver 2 | 3 → P1354, 4 → P1362, 5 → P1352 |
| 1347 | Fuel pump driver circuit | 3 → P0232, 4 → P0231, 5 → P0230 |
| 3056 | Oxygen sensor 1 (front) | 2 → P0130, 3 → P0132, 4 → P0131, 12 → P113A |
| 65590 | Misfire — cylinder not identified | 7 → P0314 |
| 65591 | Misfire — cylinder 1 | 7 → P0301 |
| 65592 | Misfire — cylinder 2 | 7 → P0302 |
| 520202 | Canister purge valve | 3 → P0443, 4 → P0445, 5 → P0444 |
| 520204 | Fuel correction — front (pre) | 15 → P0172, 17 → P0171 |
| 520205 | Fuel correction — rear (post) | 15 → P0175, 17 → P0174 |
| 520209 | Oxygen sensor heater 1 (pre, front) | 2 → P0135, 3 → P0032, 4 → P0031, 5 → P0030 |
| 520210 | Oxygen sensor heater 2 (post, rear) | 2 → P0141, 3 → P0038, 4 → P0037, 5 → P0036 |
| 520331 | Knock sensor positive line | 3 → P1327, 4 → P1328 |
| 520332 | Knock sensor negative line | 3 → P132A, 4 → P132B |
| 520333 | Oxygen sensor (pre, bank 2) | 2 → P1136, 3 → P1137, 4 → P1138, 12 → P1139 |
| 524083 | Secondary air control valve | 3 → P1076, 4 → P1077, 5 → P1075 |

### Throttle and pedal (ride-by-wire)

| SPN | Component | FMI → P-code |
|---|---|---|
| 29 | Accelerator position 2 | 2 → P1225, 3 → P1228, 4 → P1227 |
| 51 | Throttle position sensor 1 | 0 → P1123, 1 → P1122, 2 → P0121, 3 → P0123, 4 → P0122, 10 → P0120, 13 → P1120 |
| 91 | Accelerator position 1 | 2 → P0225, 3 → P0228, 4 → P0227 |
| 65613 | ETC accelerator position sensors 1 & 2 correlation | 2 → P1135 |
| 520198 | Throttle position sensor 2 | 0 → P1223, 1 → P1222, 2 → P0221, 3 → P0223, 4 → P0222, 10 → P0220, 13 → P1220 |
| 520276 | Throttle position sensor (1 or 2 indeterminable) | 2 → P150C, 12 → P150B |
| 520277 | Throttle body control — power stage | 2 → P151A, 3 → P150D, 4 → P150E, 8 → P151B, 31 → P153F |
| 520278 | Throttle body control — return spring check failed | 31 → P151C |
| 520279 | Throttle body control — adaption aborted | 31 → P151D |
| 520280 | Throttle body control — limp home check failed | 31 → P151E |
| 520281 | Throttle body control — mechanical stop adaptation failure | — |
| 520282 | Throttle body control | 31 → P152B |
| 520283 | Throttle body control | 2 → P152F, 3 → P152C, 4 → P152D |
| 520284 | Throttle body control — position deviation | 31 → P152E |
| 520305 | Throttle body control — requested angle not plausible | 31 → P1530 |

### Air, temperature and pressure

| SPN | Component | FMI → P-code |
|---|---|---|
| 102 | Manifold absolute pressure sensor | 2 → P0106, 3 → P0108, 4 → P0107, 7 → P1106, 10 → P0109 |
| 105 | Intake air temperature sensor | 2 → P0111, 3 → P0113, 4 → P0112, 10 → P0114 |
| 110 | Engine temperature sensor | 0 → P1217, 2 → P0116, 3 → P0118, 4 → P0117, 10 → P0119, 15 → P1116, 16 → P0217, 17 → P0128 |

### Electrical and ECU

| SPN | Component | FMI → P-code |
|---|---|---|
| 168 | System power (battery / power input) | 0 → P1562, 1 → P1563, 3 → P0563, 4 → P0562, 16 → P1564, 18 → P1565 |
| 628 | ECU memory | 12 → P1602 |
| 677 | Starter solenoid driver circuit | 3 → P0617, 4 → P0616, 5 → P0615 |
| 1071 | Fan relay driver | 3 → P1482, 4 → P1483, 5 → P1481 |
| 3597 | ECU output supply voltage 1 | 0 → P16A3, 1 → P16A6, 3 → P16A2, 4 → P16A1, 16 → P16A5, 18 → P16A7 |
| 3598 | ECU output supply voltage 2 | 0 → P16AA, 1 → P16AC, 3 → P16A9, 4 → P16A8, 16 → P16AB, 18 → P16AD |
| 3599 | ECU output supply voltage 3 | 0 → P17AC, 1 → P17AE, 3 → P17AA, 4 → P17AB, 16 → P17AD, 18 → P17AF |
| 520208 | Chassis/accessory relay | 3 → P1614, 4 → P1613, 5 → P1611 |
| 520226 | ECU monitoring error | 31 → P1540 |
| 520264 | ABS ECU | 12 → C1041 |
| 520287 | ECU monitoring error (level 3) | 31 → P1541 |
| 520288 | ECU monitoring of injection cut-off (level 1) | 31 → P1542 |
| 520289 | ECU monitoring of injection cut-off (level 2) | 31 → P1543 |
| 520290 | Controller option settings not programmed | 31 → P1544 |
| 520311 | ECU fault — hardware disruption | 31 → P1537 |
| 520336 | ECU monitoring (pedal map mismatch) | 31 → P1545 |

### Speed, gears and cruise

| SPN | Component | FMI → P-code |
|---|---|---|
| 84 | Vehicle speed signal | 0 → P0500, 1 → P0502, 2 → P0503, 8 → P0501, 9 → P160A, 19 → P106B |
| 523 | Gear sensor signal | 2 → P0914, 3 → P0917, 4 → P0916, 9 → P1914 |
| 527 | Cruise control panel switches | 31 → P153D |
| 596 | Cruise control enable switch | 31 → P1590 |
| 598 | Clutch switch signal | 2 → P0704 |
| 599 | Cruise control set/decel switch | 31 → P1591 |
| 601 | Cruise control resume/accel switch | 31 → P1592 |
| 904 | Wheel speed sensor (front) | 2 → C1031, 5 → C1030 |
| 907 | Wheel speed sensor (rear) | 2 → C103D, 3 → C113D, 4 → C123D, 5 → C1036, 8 → C133D, 14 → C143D |
| 524079 | Cruise control input checksum | 31 → U0405 |
| 524080 | Cruise control input message counter | 31 → U1405 |

### ABS

| SPN | Component | FMI → P-code |
|---|---|---|
| 520250 | ABS pulsar (front) | 7 → C1022 |
| 520251 | ABS pulsar (rear) | 7 → C1023 |
| 520252 | ABS solenoid (RRI) | 5 → C1024 |
| 520253 | ABS solenoid (RRO) | 5 → C1025 |
| 520254 | ABS solenoid (FFI) | 5 → C1026 |
| 520255 | ABS solenoid (FFO) | 5 → C1027 |
| 520256 | ABS solenoid (RFI) | 5 → C1028 |
| 520257 | ABS solenoid (RFO) | 5 → C1029 |
| 520258 | ABS actuator (front) | 11 → C1032 |
| 520259 | ABS actuator (rear) | 11 → C1033 |
| 520260 | ABS motor | 3 → C1020, 4 → C1021, 8 → C0020 |
| 520261 | ABS fail-safe relay | 7 → C1034 |
| 520262 | ABS source voltage | 3 → C1039, 4 → C1038 |
| 520263 | ABS tyre size | 31 → C1040 |
| 520264 | ABS ECU | 12 → C1041 |
| 520265 | ABS module | 7 → C1042 |
| 520313 | ABS actuator (front) | 11 → C103A |
| 520314 | ABS actuator (rear) | 11 → C103B |

### Chassis, safety and lighting

| SPN | Component | FMI → P-code |
|---|---|---|
| 96 | Fuel level signal | 2 → P0461, 3 → P0463, 4 → P0462, 16 → P1462, 18 → P1463 |
| 98 | Engine oil level sensor switch | 3 → P1527, 4 → P1526, 17 → P250F |
| 1023 | Trip sudden decelerations | 5 → C1045 |
| 2348 | High beam lamp | 5 → C107E, 6 → C107F |
| 2350 | Low beam lamp | 5 → C107B, 6 → C107C |
| 2367 | Left turn indicator driver circuit | 3 → P1715, 4 → P1716, 5 → P1714 |
| 2369 | Right turn indicator driver circuit | 3 → P1711, 4 → P1712, 5 → P1710 |
| 5582 | Static roll angle | 9 → P1062 |
| 520200 | Tipover sensor | 2 → P1501, 3 → P1503, 4 → P1502, 14 → P1504 |
| 520267 | Kickstand switch | 31 → P181C |
| 520275 | Accelerator / brake position interaction | 31 → P150A |
| 520285 | Brake switch (1 or 2 indeterminable) | 2 → P153E |
| 520291 | Left fog lamp | 5 → C1075, 6 → C1076 |
| 520292 | Right fog lamp | 5 → C1078, 6 → C1079 |
| 520293 | Horn | 5 → C122A, 6 → C122B |
| 520320 | Brake light | 3 → P1594, 4 → P1595, 5 → P1593 |
| 520321 | Tail light | 3 → P1597, 4 → P1598, 5 → P1596 |
| 520322 | Front brake switch | 2 → P159B, 3 → P1599, 4 → P159A |
| 520323 | Rear brake switch | 2 → P159E, 3 → P159C, 4 → P159D |
| 520330 | Immobiliser | 9 → P106A, 13 → P1064 |
| 524046 | Start button | 31 → C1512 |

### Comfort and accessories

| SPN | Component | FMI → P-code |
|---|---|---|
| 520294 | Windshield motor driver | 5 → C1222, 6 → C1223 |
| 520295 | Windshield motor switch | 2 → C1225 |
| 520296 | Accelerometer | 12 → C1125 |
| 520297 | System on button | 31 → C1530 |
| 520298 | Heated grips | 5 → C1047, 6 → C1048 |
| 520299 | Power lock motor | 5 → C1226, 6 → C1227 |
| 520300 | Tyre pressure sensor (front) | 9 → C1085, 12 → C1083, 17 → C1084 |
| 520302 | Tyre pressure sensor (rear) | 9 → C1090, 12 → C1088, 17 → C1089 |
| 520304 | Key fob | 12 → P1633 |
| 520312 | Power lock motor switch | 31 → C1229 |
| 520329 | Operator switch status (pOSS1) | 9 → P1063 |

## Generic J1939 fallback (51)

Not in the manual. Correct for the standard, unverified for this bike — the app
marks these `· generic J1939` so a name is never mistaken for a diagnosis.

| SPN | Component |
|---|---|
| 92 | Engine percent load |
| 94 | Fuel delivery pressure |
| 100 | Engine oil pressure |
| 106 | Air inlet pressure |
| 107 | Air filter differential pressure |
| 109 | Coolant pressure |
| 111 | Coolant level |
| 132 | Air mass flow |
| 158 | Battery voltage, switched |
| 171 | Ambient air temperature |
| 172 | Air inlet temperature |
| 174 | Fuel temperature |
| 175 | Engine oil temperature |
| 177 | Transmission oil temperature |
| 183 | Engine fuel rate |
| 247 | Engine total hours |
| 250 | Total fuel used |
| 512 | Driver's demanded torque |
| 513 | Actual engine torque |
| 515 | Engine desired operating speed |
| 524 | Transmission selected gear |
| 558 | Accelerator pedal idle switch |
| 563 | ABS active |
| 597 | Brake switch |
| 600 | Cruise control coast switch |
| 602 | Cruise control accelerate switch |
| 611 | System diagnostic code, manufacturer |
| 620 | 5 V sensor supply |
| 627 | Power supply |
| 629 | Controller #1 (ECU) |
| 637 | Engine timing sensor |
| 639 | J1939 network #1 |
| 653 | Injector, cylinder 3 |
| 654 | Injector, cylinder 4 |
| 723 | Engine speed sensor #2 |
| 898 | Requested engine speed |
| 970 | Auxiliary engine shutdown switch |
| 1079 | 5 V sensor supply 1 |
| 1080 | 5 V sensor supply 2 |
| 1109 | Engine protection shutdown warning |
| 1110 | Engine protection shutdown |
| 1136 | ECU temperature |
| 1237 | Engine shutdown override switch |
| 1322 | Misfire, multiple cylinders |
| 1323 | Misfire, cylinder 1 |
| 1324 | Misfire, cylinder 2 |
| 1325 | Misfire, cylinder 3 |
| 1326 | Misfire, cylinder 4 |
| 3509 | Sensor supply 1 |
| 3510 | Sensor supply 2 |
| 3511 | Sensor supply 3 |

## Codes that were wrong

Gathered from forums before the manual arrived, and deleted once it did. Recorded
here so they are not re-added by someone finding the same lists.

| SPN | Circulating name | Actually |
|---|---|---|
| 520202 | Injector driver, cylinder 1 | Canister purge valve |
| 520203 | Injector driver, cylinder 2 | not in the manual |
| 520208 | Cruise control switch | Chassis/accessory relay |
| 520261 | Throttle actuator control | ABS fail-safe relay |
| 520262 | Throttle actuator position | ABS source voltage |
| 520275 | Fuel pump driver circuit | Accelerator / brake position interaction |
| 520331 | Cylinder 1 knock sensor | Knock sensor positive line |
| 520332 | Cylinder 2 knock sensor | Knock sensor negative line |
| 3216 | Front oxygen sensor | not in the manual — O2 is 3056 / 520209 / 520210 / 520333 |
| 3224 | Rear oxygen sensor | not in the manual |
| 520211 | Front oxygen sensor | not in the manual |
| 520212 | Rear oxygen sensor | not in the manual |
| 520268 | Tilt / bank angle sensor | not in the manual — tipover is 520200 |
| 524225 | Brake light switch | not in the manual — brake light is 520320 |
| 1231 | Generator excite circuit | not in the manual |
| 523905–523910 | ABS wheel speed sensor | not in the manual — ABS is 520250–520265 |

**The lesson, kept deliberately:** a plausible component name for a fault that
means something else sends someone to the wrong end of the engine, and costs more
than no name would have. Where the manual is silent, the app says so.

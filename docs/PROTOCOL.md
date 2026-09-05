# BLE protocol — Indian Springfield CAN interface

Everything a client (Android app, script, nRF Connect) needs to talk to the
firmware, written so it can be implemented **without reading the firmware
source**. Implemented by [src/ble.cpp](../firmware/src/ble.cpp); the rationale for the design
lives in the "BLE phone link" section of [README.md](README.md).

Firmware from `2026.09.02-2` onwards. Verified on the bike 2026-09-02.

---

## 1. Discovery

| | |
|---|---|
| Advertised name | `Springfield` |
| Service UUID | `5f6d0000-9b2a-4c31-8f0e-2a7c1d3e4b50` |
| Advertised | Continuously while powered, including with the ignition off |

The service UUID is in the advertising packet, so scan-filter on it rather than
on the name.

> **The device is an ESP32-S3, which has BLE only — no Bluetooth Classic.** There
> is no SPP / "Bluetooth serial" endpoint and there never will be on this
> hardware. Any approach built on `BluetoothSocket` / `BluetoothSerial` is a dead
> end here; this is GATT.

## 2. Characteristics

Both live under the service above and are **`READ | NOTIFY`**, gated
`READ_ENC | READ_AUTHEN` — the stack refuses both reads and CCCD writes on a link
that is not encrypted *and* authenticated.

| Name | UUID | Default rate | Payload |
|------|------|--------------|---------|
| **fast** | `5f6d0001-9b2a-4c31-8f0e-2a7c1d3e4b50` | 10 Hz | 8 binary bytes |
| **state** | `5f6d0002-9b2a-4c31-8f0e-2a7c1d3e4b50` | 1 Hz | UTF-8 JSON |

Rates come from `BLE_FAST_MS` (100) and `BLE_JSON_MS` (1000) in the firmware
config and may be retuned; do not hard-code a timing assumption, drive the UI off
arrival of notifications.

Notifications are sent **only while a phone is connected and paired**. Nothing is
queued while disconnected — this is live telemetry, not a log. openHAB holds the
history over MQTT.

## 3. Pairing

- **Passkey pairing with MITM protection, bonding enabled.** The phone is
  prompted for a 6-digit passkey on first connection; after that the bond is
  reused and no prompt appears.
- A peer that fails to encrypt is **actively disconnected** by the firmware. A
  failed passkey is not a silent read failure — the link drops.
- The bond is stored in the device's NVS and survives reboots and OTA updates.

> ⚠️ **Bond mismatch is the most likely "it just stopped working".** If the phone
> is unpaired on its side only, or the board is reflashed with
> `firmware.factory.bin` (which erases NVS — see [FLASHING.md](FLASHING.md)), the
> two ends disagree about the link key and pairing fails until one side is
> cleared. There is no remote bond-reset command yet.

**Disconnect reasons** surfaced by NimBLE are `0x200 + HCI error`:

| Value | HCI | Meaning |
|-------|-----|---------|
| `531` | `0x13` | Remote user terminated — the phone hung up |
| `534` | `0x16` | Terminated by local host — the firmware's own drop after failed pairing |

## 4. `fast` characteristic — 8 bytes

**Little-endian.** Fixed length, so it always fits one notification regardless of
the negotiated MTU.

| Byte | Type | Field | Unit | "Unknown" |
|------|------|-------|------|-----------|
| 0-1 | `uint16` | rpm | rpm | `0xFFFF` |
| 2-3 | `uint16` | speed × 10 | km/h | `0xFFFF` |
| 4 | `uint8` | throttle | % | `0xFF` |
| 5 | `char` | gear — `'N'`, `'1'`-`'6'`, `'-'` | — | `0x00` |
| 6 | `uint8` | flags | bitfield | — |
| 7 | `uint8` | flagsValid | bitfield | — |

### Flag bits (same positions in both bytes 6 and 7)

**Rewritten 2026-09-05.** Bits 0 and 5 carried the front brake and the horn
until both were withdrawn — there is one brake signal on this bus and either
control works it, and the horn is not on the bus at all. They were pinned
permanently invalid for one release and then reused for the cruise rocker.

| Bit | Signal | Set in byte 6 means |
|-----|--------|---------------------|
| 0 | cruiseSet | SET/DEC pressed (SPN 599) |
| 1 | brakeRear | brake applied (SPN 597 — either control) |
| 2 | cruiseHold | cruise is holding a speed — **derived**, see below |
| 3 | indLeft | on |
| 4 | indRight | on |
| 5 | cruiseRes | RES/ACC pressed (SPN 601) |
| 6 | cruiseEnable | the rocker is on (SPN 596) |
| 7 | hazard | hazard warning active |

**Firmware and client must ship together across this change.** A build older
than `2026.09.05-41` reads bit 0 as a front brake and bit 5 as a horn, so pairing
it with newer firmware lights a brake tell-tale every time SET is pressed.

**Byte 7 is not optional to implement.** Every switch is tri-state in the
firmware: pressed, released, or *never reported by the bus*. Without checking
`flagsValid`, a client cannot tell "brake released" from "no brake message has
ever arrived", and will confidently render RELEASED on a bike that has said
nothing. **Read bit *n* of byte 6 only when bit *n* of byte 7 is set;** otherwise
render the signal as unknown.

### Worked example

```
6A 04 00 00 00 4E 00 3F
```

| Bytes | Value | Meaning |
|-------|-------|---------|
| `6A 04` | `0x046A` = 1130 | 1130 rpm |
| `00 00` | 0 | 0.0 km/h |
| `00` | 0 | 0 % throttle |
| `4E` | `'N'` | neutral |
| `00` | `0b000000` | nothing pressed/on |
| `3F` | `0b111111` | all six signals are known |

### With the ignition off

```
FF FF FF FF FF 00 00 00
```

Everything unknown, no valid flags. This is the **correct** output for a silent
bus, not a fault. Note that while the bus is silent the firmware runs a blocking
bitrate scan, so notifications drop to roughly one every 1.5-3 s. Full rate
resumes the moment the bus is detected.

## 5. `state` characteristic — JSON

A UTF-8 JSON object, ~350-450 bytes when fully populated.

**A key is absent when the value is unavailable** — the client must treat a
missing key as "unknown", never as zero. On a silent bus the payload is literally
`{}`, which is valid and expected.

| Key | Type | Unit | Notes |
|-----|------|------|-------|
| `rpm` | int | rpm | |
| `throttle` | int | % | |
| `gear` | string | | `"N"`, `"1"`-`"6"`, `"-"` |
| `gearGlitches` | int | | Count of gear changes that were implausible -- a change with the bike stationary on its stand and the engine running. Survives a reboot; it is evidence, gathered slowly |
| `coolant` | int | °C | **Cylinder head temperature.** The key is a misnomer kept for continuity: this engine is air-cooled and has no coolant, and no oil temperature sensor either — the manual lists exactly one engine temperature sensor, the CHT on the front cylinder head. |
| `speed` | float | km/h | 1 decimal. From the ABS module, and the figure the dash shows |
| `speedFront` | float | km/h | The front wheel, from the same module. Kept separate on purpose: comparing the two is how a failing wheel-speed sensor is caught before the ABS decides anything is wrong |
| `fuel` | int | % | |
| `odometer` | int | km | |
| `svcKm` | int | km | Odometer at the last service. Held in the ESP32's NVS, not on the bike |
| `trip` | float | km | 1 decimal |
| `fuelEconomy` | float | l/100 km | 1 decimal, the bike's own average |
| `fuelEconInst` | float | l/100 km | Instantaneous |
| `fuelRate` | float | L/h | SPN 183. The discriminator for anything that might be injector duty |
| `battery` | float | V | 1 decimal. >13.5 V ⇒ engine running/charging |
| `ambient` | float | °C | 1 decimal |
| `tyreFront` | float | PSI | 1 decimal |
| `tyreRear` | float | PSI | 1 decimal |
| `tyreFrontTemp` | float | °C | 1 decimal |
| `tyreRearTemp` | float | °C | 1 decimal |
| `brakeRear` | string | | `"PRESSED"` / `"RELEASED"`. One brake signal, SPN 597, which either control operates — the name is historical. `brakeFront` was removed 2026-09-05. |
| `clutch` | string | | `"PULLED"` / `"out"` (SPN 598) |
| `cruise` | string | | `"HOLDING"` / `"off"` — **derived, not measured.** SPN 595 is not transmitted on this bus. The vocabulary differs from the fields around it on purpose, so the value itself says what kind of fact it is. |
| `cruiseEnable` | string | | `"ON"` / `"OFF"` (SPN 596, the rocker) |
| `cruiseSw` | string | | `"SET/DEC"`, `"RES/ACC"` or `"none"` — the legend printed on the rocker |
| `hazard` | string | | `"ON"` / `"OFF"` |
| `security` | string | | `"OK"`, `"SEARCHING"`, `"NOT FOUND"` — the key fob |
| `ignition` | string | | `"ON"` / `"OFF"`. Derived from whether the bus is alive, not from a signal -- pressing wake is what starts the traffic |
| `grips` | int | | Heated grips, 0 for off through 10. Ten detents, and the byte moves in exact steps of 25 |
| `gripTempL`, `gripTempR` | float | °C | What each grip has actually reached. Left is byte 0 -- confirmed by holding a bare hand on it with the heat off, which is the only way to be sure |
| `lean` | int | | Raw tilt, 0-255, with 127 upright. Not an angle: the scaling is unknown, so it is published as the machine sends it |
| `stand` | string | | `"UPRIGHT"`, `"STAND"`, `"DOWN"` -- **derived from `lean`.** The sidestand state is not on this bus. Omitted above walking pace, because an accelerometer reads upright in a balanced corner and would otherwise claim the bike was standing up straight through every bend |
| `wheels` | string | | `"OK"`, `"FRONT LOST"`, `"REAR LOST"`. Continuous comparison of the two wheel speeds |
| `wheelBlips`, `wheelBlipsRear` | int | | Brief dropouts counted per sensor since the board was last erased. Survives reboots and OTA |
| `headlight` | string | | `"High"` / `"Low"` / `"Off"` |
| `indLeft` | string | | `"ON"` / `"OFF"` |
| `indRight` | string | | `"ON"` / `"OFF"` |
| `dm1` | string | | Decoded active DTC summary, e.g. `"No active DTC \| MIL:off"` |
| `dm1Raw` | string | | Raw DM1 hex |
| `fw` | string | | Firmware version, e.g. `"2026.09.02-4"`. **Always present** from that build onwards — a client that cannot say which firmware it is talking to makes every report of odd behaviour start with a guess. |

### Deliberately absent over BLE

`vin` and `softwareId` are present on the MQTT path but **withheld from BLE**.
They are permanent vehicle identity, not telemetry, and BLE advertises to
whoever is within range in a car park. A client must not expect them, and adding
them back would need a firmware change, not a client one.

### The DM1 lamps, and which one is the ABS lamp

`dm1` ends with the four lamps J1939 defines, e.g. `MIL:off Stop:off Warn:ON
Prot:off`. They are the bike's own severity judgement and worth reading
separately from the fault list: a stored code with every lamp dark is a different
situation from one with Stop lit, and only the machine knows which it is.

| lamp | on this motorcycle's dash |
|---|---|
| **MIL** | The engine symbol. *Malfunction Indicator Lamp* is what the acronym means and what it has always been |
| **Warn** | **The amber ABS lamp.** Established 2026-09-05 by the owner watching the instrument while riding: Warn goes out and follows the ABS lamp as the wheels come up to speed |
| **Stop** | Almost certainly the red triangle. Not confirmed -- it cannot be provoked without doing real harm, so it waits until it lights on its own |
| **Prot** | Unobserved |

**The Warn finding closed a question that had stood for weeks.** DM1 had reported
`Warn:ON` with no active fault behind it, and it looked like a standing warning
with nothing behind it. It was the ABS self-test, which needs the wheels turning
and so can never clear on a parked bike -- and every observation until then had
been of a parked bike. No amount of analysis on stationary captures was going to
show it; it needed somebody moving, looking at the instrument.

**There is no `abs` field**, and there does not need to be. The app reads
`Warn:` out of this string and draws the lamp from it, which costs nothing extra
over BLE because the summary crosses for the fault list anyway.

### TPMS caveat

Tyre pressure and temperature come from wheel sensors that **only wake once the
wheels turn**. Expect those four keys to be absent on a stationary bike even with
the ignition on.

## 6. Connection parameters

On connect the firmware requests a **15-30 ms** interval (`updateConnParams(12,
24, 0, 400)` — units of 1.25 ms, 0 latency, 4 s supervision timeout). Android may
refuse. Request `CONNECTION_PRIORITY_HIGH` from the client side if the gauge
looks steppy; drop to balanced to save battery when the screen is off.

### ⚠️ MTU negotiation is REQUIRED for `state`

The default ATT MTU is **23 bytes**, which caps a notification payload at
**20 bytes**. `fast` is 8 bytes and is unaffected — but a `state` JSON of ~400
bytes is **silently truncated to 20 bytes** on a default-MTU link. The client
gets a fragment of JSON, not an error.

Call `requestMtu(517)` and wait for the callback **before** subscribing to
`state`. On Android that is `BluetoothGatt.requestMtu(517)` → `onMtuChanged`.

**A client request alone is not enough** — the firmware has to raise its own
ceiling too, which it does from `2026.09.02-3` (`NimBLEDevice::setMTU(517)`).
NimBLE's own default cap is 256, so before that build a client asking for 517
still landed on 256 no matter what it did.

**Read the byte count in the failure.** A truncated `state` payload names the MTU
that was actually negotiated, and the two cases have different fixes:

| Payload size | Negotiated MTU | Cause |
|---|---|---|
| 20 bytes | 23 (default) | The client never called `requestMtu` |
| 253 bytes | 256 | The **server** capped it — firmware older than `2026.09.02-3` |

Found on the bench 2026-09-02: the Android app reported `Unparseable state JSON
(253 B)`, and 253 = 256 − 3 pointed straight at the firmware rather than the
phone. Without that number in the message the search would have started in the
wrong half of the system.

## 7. Android client notes

**BLE cannot be tested in the emulator — it has no Bluetooth radio.** A physical
device with USB debugging is mandatory.

Permissions (Android 12+ / API 31+):

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                 android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

`neverForLocation` avoids needing the location permission. Both are runtime
permissions and must be requested, not just declared.

To keep receiving with the screen off, the connection must be held by a
**foreground service** — Android will otherwise tear it down.

Implementation order that fails cheapest:

1. Scan filtered on the service UUID, connect, bond.
2. Subscribe to `fast` only, log the raw 8 bytes, confirm they match the layout
   above against a known state (ignition off = all-unknown).
3. Add `state`, and assert that neither `vin` nor `softwareId` appears.
4. Only then build the UI.

## 8. Cross-checking

MQTT runs in parallel and independently: the same decoded values are published
retained on `canbus/springfield/state`, and openHAB shows them as `CanBus_*`
items. **If the app and openHAB disagree about a value, the app's decoding is
wrong** — both come from one `VehState` and one serialiser in the firmware, so
they cannot legitimately differ.

`canbus/springfield/status` carries the MQTT Last Will (`online`/`offline`), which
is how openHAB distinguishes live data from a retained echo. BLE has no
equivalent — for a BLE client, "connected" *is* the liveness signal.

## Service memory (`5f6d0003-…`)

The one writable characteristic on the server, and the only value on the bike a
phone can change.

| | |
|---|---|
| Payload | 4 bytes, little-endian `int32` — the odometer at the last service |
| Read | Returns what is stored in NVS, not what was last written |
| Write | Requires an encrypted **and** authenticated link, like every read |
| Also published | As `svcKm` in the state JSON, omitted when never recorded |

Binary rather than text because four bytes have exactly one interpretation, and
this is the only value where a misread would persist rather than being replaced
a second later.

**The bike may refuse a write.** It rejects a negative figure, anything past
999 999, and — the one that matters — anything more than 5 km ahead of the
odometer it is actually reading. A corrupted write that set the service point
40 000 km into the future would silently disable every reminder, and the failure
would look exactly like the feature working. A refusal is not reported back over
the link; read `svcKm` from the next state frame instead, which is what the
bike actually holds.

**Why it lives here at all.** Every other number on this bus is measured again
every second. The mileage of an oil change eight months ago exists only because
somebody wrote it down, so it is the one value that must outlive the phone that
recorded it. NVS survives reboots and OTA updates. It does **not** survive a
full serial erase — the `nvs` partition sits at 0x9000, inside the range a
factory flash wipes.

## What the captures corrected, and what they could not

Read back from four ride captures, 42,365 frames. The captures are not published — they carry the VIN of one motorcycle.
The TPMS decode those were taken for is correct and untouched.

### Headlight, PGN 65381 — fixed

Two modules send it. **SA 0 sends nothing but `0xFF`**, and `0xFF & 0x40` is
true, so an unfiltered decode reported main beam every time one of those 25
frames arrived. That is why the headlight flipped between High and Low on a bike
standing still.

**SA 39 carries the signal**: `0x10` and `0x11` for dipped over 260 frames,
`0x40` for main. Bit 6 and bit 4 are exclusive there, which is what makes the
either/or reading safe. Now filtered to SA 39 and guarded against `0xFF`.

### Brake switch, PGN 65265 (CCVS) — fixed, and renamed in spirit

The switch is a **two-bit field**: 0 off, 1 on, 2 error, 3 not available. It was
masked with `& 1`, which turns "not available" into "pressed". Harmless while
only SA 0 is read — it never sends 3 — but SA 11 and SA 39 send nothing else,
4,330 and 5,223 frames of it, so the moment that filter moved the brake would
have read as permanently on.

More importantly this is the brake **light** switch, and on a motorcycle either
control operates it. It was never the rear brake specifically, which is why both
levers have always moved the same signal.

### Front brake, PGN 65390 — withdrawn, not fixed

Every one of the 23 frames across four rides is either

```
DF FF FF FF FF FF FF FF
FF FF FF FF FF FF FF FF
```

with the whole remainder filler in both cases. The frames where bit 5 is set are
exactly the frames where every bit is set — J1939's "not available" — so the
decoder was reporting filler as a pressed brake. That is the whole reason the
front brake appeared to follow the rear: it was noise.

**Answered 2026-09-05, and there is no real one to find.** The console lock,
both fob buttons and both brake controls were worked in the garage with every
byte on the bus visible and new messages announcing themselves. The lever and
the pedal produce the identical transition on SPN 597 from SA 0 — one brake
signal, which either control operates. The manual gives them separate identities
(SPN 520322 front, SPN 520323 rear), so the module knows which is which; it
simply does not say so on the wire.

`brakeFront` was removed from the state entirely. The field had gone unassigned
since the withdrawal and shipped as a permanently absent key.

~~**To find the real one**~~ — superseded by the answer above; kept for the
method, which is still the right way to hunt a switch. A capture is needed with
each control operated alone: ignition on and everything at rest for 30 seconds,
then front lever only for five, pause ten, then foot pedal only for five — three
times each, and the same pattern for dipped, main and off. The pauses and repeats
are the point: a byte that changes at random will not hit the same pattern three
times running. A correlation against these captures found nothing, because they
contain only 76
frames with the brake applied at all.

### Throttle — found, in PGN 65266 byte 7

**Confirmed on the bike 2026-09-04.** Ignition on, engine stopped, six full
sweeps of the grip: 5-7 % at rest, exactly 100 % at full, back to 5-6 % on
release, and **rpm sat at zero throughout**. The value that was withdrawn below
was engine speed over 256 and could not have moved on a dead engine; this one
did, six times.

SPN 51, Engine Throttle Valve 1 Position, 0.4 % per bit. It was in a message we
had been reading one field of out of four, for as long as we had been hunting it
elsewhere.

The rest at 5-6 % is the idle stop -- a throttle plate is never fully shut --
and matches the 5.2 % median measured at idle across the August rides.

### Throttle, PGN 65382 byte 1 — withdrawn (this was the tachometer)

Read as throttle percent on a 0-11 scale. It is a coarse tachometer: engine
speed divided by 256.

Two tests, in opposite directions, agree. Correlated against rpm over 1,391
seconds it scores **+0.991**, with the mean rpm per value forming a straight
line — 933 at value 3, 1667 at 6, 2421 at 9, 2892 at 11. The method was checked
against EEC1 byte 4, which is literally the rpm high byte and scores 1.000.

The reverse test rules out engine load, which was the obvious alternative
reading. Load varies enormously at a fixed engine speed — near zero coasting,
near full accelerating — so it would show a wide spread within each rpm band.
It does not: at 1750-1999 rpm the value is 7 in 643 of 809 samples, and at
2250-2499 it is 9 in 667 of 843. One answer per band is a tachometer, not a
load.

That is why the app showed 27% with the engine idling and nobody touching the
throttle: 933 / 256 is 3, and 3 of 11 is 27.

Nothing else in 42,365 frames behaves like a throttle. The next candidates down
— 0.82 and 0.76 — are the speed low byte and fuel economy, which follow revs on
a ride without being throttle.

**The throttle was found on 2026-09-04**, and not here: it is PGN 65266 byte 7,
SPN 51, Engine Throttle Valve Position — sitting in the fuel-economy message,
which is why it survived so long unlooked-for.

**What is still missing is the other half.** This is a drive-by-wire machine, so
the rider's demand (SPN 91, with its redundant twin SPN 29) and the valve
position (SPN 51) are two different numbers. Only the valve is on the bus.

The test that separates them is better than the clutch-blip described here, and
it came from the owner: **cruise holding the speed with the grip released.**
Demand goes to zero while the valve stays open. Nothing else on an ordinary ride
does that — not a hill, not a blip. PGN 65382 bytes 1 and 4 are the standing
candidates (255 and 80 distinct values across 4,721 frames), and firmware
`2026.09.05-46` added `probe/throttle` above the stationary gate to watch them
while moving.

### Cruise control — the state is not transmitted, so it is derived

The original tell-tale read byte 5 bit 0 from SA 39. Byte 5 of CCVS is the
**switch** byte, so the lamp lit on a momentary press and went out a second
later. Withdrawn 2026-09-03.

**Settled on the road 2026-09-05.** Byte 4 held `0xF7` — SPN 595 reading 3, "not
available" — at 94, 87 and 73 km/h with the cruise demonstrably holding the
speed and the rider's hand off the grip. The control was inside the measurement:
byte 5 reported every SET and RESUME press in those same frames, so the message
was being received and decoded correctly while byte 4 sat still. Set speed
(SPN 86, byte 6) is a constant `0xFF` for the same reason.

That is a valid null, unlike the earlier one drawn from captures in which cruise
was never engaged at all.

**What is shipped instead is derived**, from measured inputs only:

| input | SPN | role |
|---|---|---|
| rocker on | 596 | precondition |
| SET / RESUME press | 599 / 601 | arms the state |
| speed | 84 | confirms it |
| brake | 597 | hard exit |
| clutch | 598 | hard exit |

A press only ARMS; it becomes `HOLDING` once the speed has sat still for three
seconds. Cruise that never caught never produces a steady speed and so never
claims to hold. Drifting more than 6 km/h off the held speed for two seconds
ends it — which is how a backward flick of the grip is caught despite SPN 91
being absent too. Only the rule is inferred; every input is measured.

**Two things carry the vocabulary of that distinction.** The field publishes
`"HOLDING"` rather than `"ON"`, and the openHAB label reads "Cruise held". A
derived fact should not be dressed as a measured one.

Withdrawn rather than corrected, because there is nothing to correct it to. The
presses themselves are real and could be published as events, and a latched
state could be inferred from them — the reasoning, and what that would cost, is
in [DECODE-PLAN.md](DECODE-PLAN.md).

---

## Payload budget

A GATT notification carries **ATT_MTU minus 3** bytes and cannot fragment. We
negotiate 517, the BLE maximum, so **514 bytes** is a hard ceiling — there is no
larger MTU to ask for. Past it the stack truncates in silence: the app receives
JSON cut off mid-string, cannot parse it, and stops updating while the cluster
carries on from the binary frame. Nothing anywhere reports an error. The first
sign on a ride would be tyre pressures that never change.

Measure it with `python3 tools/ble_budget.py`, run from the firmware
directory — `indian-canbus/` on the server, `firmware/` in the public
repository. The tool reads the field list out
of `buildStateJson` in `main.cpp` and supplies only the worst-case width of each
value, so a field added to the firmware without a width is a hard error rather
than a silent gap in the estimate.

Worst case — every field populated at its widest, 2026-09-05. All three columns
use the short keys; only the fault encoding differs:

| active faults | readable (was shipping) | compact, `fw` always | compact, `fw` rarely |
|---|---|---|---|
| 0 | 533 | 486 | 465 |
| 1 | 543 | 497 | 476 |
| 2 | 566 | 508 | 487 |
| 4 | 612 | 530 | **509** |

For reference: long keys with readable faults and no fault at all is **762**,
which is why the radio moved to short keys on 2026-09-04. The MQTT payload —
long keys, VIN, four faults — is 953 and has no limit.

An earlier version of the tool tied key length and fault encoding together, so
its "readable" column was really "long keys", a combination nothing had run
since 2026-09-04, while the bike was running short keys with readable faults —
the one case the table did not show. That case is the left-hand column, and it
is **over the ceiling from zero faults**.

These are also worse than the 2026-09-04 figures this section used to quote (560
/ 583 / 622 against 452 / 475 / 514). Those were a realistic ride; these assume
every optional field present at its widest value at once. The pessimistic number
is what to budget against, because the payload only overflows on the day
something is already wrong.

### Calibration against a real payload

`--check <file.json>` compares the model with a payload captured off the bike,
for the same field set. The model must come out **above** the real one; below
means a width is too small, which is the failure that matters, because it would
under-report the risk of a silent truncation.

Measured in the garage, 2026-09-05, engine off and the tyre sensors asleep:

```
34 of 40 fields present
  measured  391
  modelled  448  (+57)
  absent: tf tr tft trt fi wh
```

391 bytes with six fields missing. Four of those six are the tyre readings, which
are absent only because the bike had been parked eight hours; on a ride they are
all present and a realistic payload lands near 480. The margin was thinner than
it looked.

The right-hand column is what ships. Two changes got it there.

### Fault codes as numbers on the radio

`dm1` was the largest consumer. Four faults written as sentences is about 115
characters — a quarter of the payload — to say something the app can already say
for itself:

```
SPN 520250 FMI 8 (x2); SPN 904 FMI 12 (x1) | MIL:ON Stop:off Warn:ON Prot:off
520250:8:2,904:12:1|5
```

SPN, FMI and occurrence count per fault, then the lamps as bits: **MIL 1, Stop 2,
Warn 4, Prot 8**. Note that this is *not* the internal bit order — J1939 packs
the lamps with MIL highest, and the firmware remaps them in one place
(`decodeDM1`), so exactly one function knows both orders.

The app holds 115 manual SPNs, 51 generic J1939 entries, 264 P-codes and 23 FMI
descriptions; it does not need the words, it needs the numbers. openHAB keeps the
readable form over MQTT, where a person reads it on a sitemap and there is no
limit, so `canbus_dtc.js` and the sitemap were untouched. `Dtc.kt` parses both
formats and is tested against both in `DtcFormatTest`.

### The firmware version, sent rarely

`fw` is static for the life of a boot and costs 21 bytes of 514. It is sent on
connect — and on every reconnect, so a dropped link recovers it at once — and
then once every 30 s in case a notification was missed. The app caches the last
value it saw.

### What happens if it still does not fit

Two nets, in order, and the order is the point:

1. **Drop `fw`.** At four faults the version and the fault list cannot both fit.
   The version is the one the app already has and which cannot change without a
   reboot; dropping a fault instead would make the fault list lose an entry for
   one tick every half minute and get it back — a flicker on exactly the screen
   a rider is looking at when something is wrong.
2. **Drop whole faults from the end.** Never a partial one: a half-written SPN
   would parse as a different, real fault number. The lamp bits after the bar are
   never dropped — they are four bytes and they are what lights the cluster
   icons. Unlike the readable form on MQTT, the compact form is cut without an
   ellipsis, because an ellipsis would parse as nothing at all.

If neither is enough the payload is **dropped rather than sent oversized**. An
earlier version warned to a serial port nobody is watching on a ride and sent it
anyway; that failed exactly when it mattered, because it is the fault list that
overflows and a clean bike is far under the limit.

About four ordinary fields of headroom remain at four faults; one costs roughly
13 characters. Run the tool before adding one.

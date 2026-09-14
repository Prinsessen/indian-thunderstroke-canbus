# Next ride — what to do, and what happens by itself

## What the Zealand ride taught, 2026-09-13

1000 km, and a partial success. Three findings, in the order they matter.

### 1. The WiFi failover starves BLE, and everything else

Reported from the road: the BLE stream to the phone went on, off, on, off,
continuously — until the rider pulled over, unplugged the ESP32's service
connector, and plugged it back in. After that it held for most of the trip.

The cause is in `loop()`. `ensureNetwork()` runs first, and when WiFi is down it
does three cheap reconnects ten seconds apart and then calls `wifiConnect()`,
which **blocks for up to 30 seconds** walking three SSIDs at ten seconds each.
Nothing else in `loop()` runs while it blocks — including `bleUpdate()` and the
CAN drain.

The rhythm matches the report exactly: **about 30 seconds alive, 30 seconds
dead, repeating.**

The code states the right intent and does not achieve it:

```c
// Deliberately outside the ENABLE_MQTT/mqtt.connected() guards below: BLE is
// the transport that must keep working with no WiFi.
bleUpdate(st);
```

The *logical* dependency on MQTT was removed deliberately. The *temporal* one
was not: a 30-second blocking scan sits in front of it.

Why the power cycle cured it: after a reboot `wifiConnect()` runs once from
`setup()`, finds the hotspot, and `ensureNetwork()` then returns immediately on
every pass. No more blocking.

**Amplifier:** `YOUR_SSID` is SSID 1 and the RUTM50 is not fitted yet, so
every full rescan burns ten seconds on a network that does not exist before it
reaches the hotspot.

**And it does NOT explain the stale openHAB items — that paragraph was wrong
and stood here for a day.** Re-read against the InfluxDB export on 2026-09-14
(evening): in the frozen stretches (10:30→12:25, 13:36→15:36, 23:10→01:11) MQTT
was *up* — `bus/health` arrived every 30 s, `gear` events arrived carrying live
`rpm=` and `speed=` in their text, the controller counted 204 frames/s — and only
the `state` topic changed nothing in openHAB. Roughly six of fourteen riding
hours.

The cause was `char payload[900]` in `publishState()`. Rebuilt from the live
items: 764 bytes parked, ~947 riding (ten momentary inputs present; `softwareId`
alone is 111). ArduinoJson truncates silently at 899, and openHAB's JSONPATH then
fails on **every** key including `rpm` — tested on the server with a JSON cut at
899: the valid control returned `1112.5`, the cut one failed on each key, and
`openhab.log` said nothing. It cleared only on a wake because a boot empties
`vin`/`swid`/`dm1`; the JSON fits again until the identity messages arrive —
wake 10:28:25, last state update 10:30:24. Fixed in `2026.09.14-3` (buffer 1536
plus a tripwire that refuses to publish a cut JSON). See OTA.md.

Fixes, in increasing thoroughness:

1. Drop the dead SSID from the list until the router is fitted. Config change.
2. Call `bleUpdate()` inside the wait loop in `wifiConnect()`, so BLE survives a
   scan. Twenty lines, and it delivers what the comment above already promises.
3. Make `wifiConnect()` non-blocking. Cleanest, largest.

### 2. Range to empty wraps at 256

`b[3]` of PGN 65382 is a single byte, one km per count. The dash goes past 350.
Above 255 the app shows the dash reading minus 256.

Settled on 2026-09-08 against two dash readings — both of which must have been
under 255, or this would have surfaced then.

**The ride proves it.** 612 `CanBus_Range` samples over 1000 km including a
refuel: `min 0, max 254`. The reported range never once exceeded 254 on a
machine whose dash shows 350+. That ceiling is the byte.

**The high byte is probably already on screen.** `probe/throttle` prints `b1`
(= `b[0]`) beside the range byte, and calls it one of "the two unexplained
bytes". A range high byte would look exactly like that: **zero almost always,
one only above 255 km** — the kind of byte that reads as boring and gets
deprioritised.

**Test, thirty seconds, no ride:** enable `probe/throttle`, ignition on with a
tank showing over 255 km, read one line. `b1=1` with `b4` = dash minus 256
settles it, and the fix is `const int km = b[0] * 256 + b[3];`

### 3. The capture never ran

`ride_capture.py` was not started. No pidfile, and `captures/` held nothing
newer than 8 September. The probe stream from 1000 km is gone: `probe/throttle`
is bound to no item, and mosquitto logs topics and byte counts but not payloads.

The constant-throttle hill runs cannot be analysed for the load byte. That was
the purpose of the ride.

What survived is every decoded item, because `influxdb.persist` carries 48
CanBus items on `everyChange` — 189,610 rows exported to
`captures/ride_20260913_sjaelland_influx.csv`.

**This is the failure this file already described**, in these words: *invisible
from the saddle, identical to a good ride until you get home.* The instruction
to run `ride_capture.py check` before setting off exists precisely for it, and
it was not run. Writing the instruction down was not enough.

---

Everything waiting on wheels, in one list, so it takes one outing instead of
three. Firmware `2026.09.04-32` or later.

Most of it is passive: ride normally and the data arrives. Only two things ask
anything of the rider, and both take four minutes.

---

## Before setting off — the two minutes that decide whether any of this works

Ride 2 on 2026-09-05 produced nothing: 200 km past a listener subscribed to the
wrong topic. None of what follows means anything if that repeats, so do this
first, at the bike, with the **ignition on**.

```bash
cd /etc/openhab-firmware/indian-canbus
/etc/openhab/.venv/bin/python tools/ride_capture.py start
/etc/openhab/.venv/bin/python tools/ride_capture.py check     # takes ~6 s
```

**Set off only on `READY`.** Anything else exits non-zero and says which of
four situations you are in — not connected, connected but dead, link fine with
the ignition off, or the one that matters: link fine, ignition **on**, and the
probes silent anyway. That last one is the fault worth catching in the drive,
because it is invisible from the saddle and identical to a good ride until you
get home.

Verified on the bench 2026-09-05: connects with TLS, writes to `captures/`, and
the verdict is stable across repeated runs.

When you get back, **while the ride could still be repeated**:

```bash
/etc/openhab/.venv/bin/python tools/ride_capture.py stop
git add captures/ && git commit          # it cannot be rebuilt
```

Probe configuration confirmed live tonight —
`{"scan":"OFF","cruise":"ON","throttle":"ON","claims":"ON"}`. `scan` is off on
purpose: `probe` and `probe/2304` are stationary-only and would spend radio
budget a moving bike needs. `probe/throttle` (2 Hz) and `probe/cruise` publish
at any speed, and they are the two the ride is for.

---

## Two things to actually do

**1. Oil or cylinder head? — ANSWERED 2026-09-05, no ride needed**

The service manual settles it without the test. This engine has exactly one
temperature sensor: "CHT (Cylinder Head Temperature) Sensor, located on the rear
face of the front cylinder head". There is no oil temperature sensor -- the oil
has a level sensor and a pressure sensor and nothing else, and the phrase "oil
temperature" does not appear anywhere in the manual. So PGN 65262 is the
cylinder head, and the labels have been corrected everywhere.

Worth recording why the test existed at all: the reading was argued to be oil on
three grounds -- a slow 8-10 minute warm-up, a 115 C ceiling that seemed low for
an air-cooled head, and a fall from 106 to 98 C when speed dropped. Every one of
those was an expectation about what the numbers ought to look like, not a
measurement of anything. A parts list beats a curve shape.

~~4 minutes, at the end of the ride~~

We renamed the old "Coolant" reading to engine oil temperature, on the evidence
that it climbs over eight to ten minutes and settles at 100-115 C, where an
air-cooled head would run 150-200. That is reasoning, not proof.

With the engine hot, pull over and let it **idle stationary for three to four
minutes**, watching the figure.

- Climbs clearly → it is the **cylinder head**. No airflow, still burning fuel.
- Flat or slowly falling → it is the **oil**. The load is what went away.

Either answer is useful. The wrong name is the only bad outcome.

**2. Steady throttle, changing load — 2 minutes, anywhere with a hill**

PGN 65382 **byte 1** is the busiest unexamined byte on the bus: 255 distinct
values across 4,721 frames, in a proprietary message already known to carry
engine data. Now that the throttle is decoded it can finally be separated
from it.

Byte 4 was the other half of this task and it is done: it turned out to be the
dash's range to empty (2026-09-08). One byte left in this message.

> **The sitemap switch cannot reach a sleeping board.** The MQTT channel for
> these switches has a `commandTopic` with no `retained` flag, so a press while
> the board is asleep publishes into nothing and is lost. The switch only works
> while the ignition is on and the board is connected.
>
> To set a probe that survives the next sleep, publish it retained instead:
>
> ```bash
> mosquitto_pub -h <broker> -p 8884 -u <user> -P <pass> --capath /etc/ssl/certs \
>   -t 'canbus/springfield/probe/en/claims' -m 'ON' -r
> ```
>
> The board subscribes to `probe/en/+` on connect, so a retained value is applied
> the moment it wakes. **And it keeps being applied.** Turning that probe off
> from the sitemap later will work until the board reconnects, at which point the
> retained ON puts it back — which looks exactly like a switch that refuses to
> stay off. Clear it by publishing an empty retained payload to the same topic,
> or publish a retained `OFF`.
>
> Done on 2026-09-12 for `claims`, before the 1000 km Zealand ride, so it did not
> depend on remembering to flip a switch while suiting up.
>
> **Reading it back needs care.** The board publishes `probe/enabled` the moment
> it connects — with the state it booted with — and only *then* receives the
> retained `probe/en/+` values, applies them, and publishes `probe/enabled`
> again. A check that stops at the first message reports the old state and looks
> exactly like a retained value that failed.
>
> That happened on 2026-09-12: the first publish said `claims:OFF`, the second
> said `ON`, and the first one was believed. **Wait for the second, or query the
> retained topic a few seconds after the board is up.**

**Switch `probe/throttle` back on before riding.** All four probes were turned
off on 2026-09-08 once the range was settled, so this ride reports nothing
unless one is re-enabled — `CanBus_Probe_Throttle` on the Springcommand page,
or the retained topic `probe/en/throttle`. A ride spent with the probe silent is
the exact failure this file exists to prevent.

Hold the throttle at a **constant** opening and let the load change -- up a rise
and down the other side is ideal. A byte that follows the *hill* rather than the
*hand* is engine load, torque or ignition advance.

Do it twice so the pattern is not a coincidence.

---

## What happens without doing anything

Just ride, and these answer themselves from the log afterwards:

- **Wheel sensor dropout counters.** They should stay at zero. Both are watched
  now, because both shims were left out and only the front was destroyed -- the
  rear survived on a good bearing, not a good gap.
- **PGN 61444 byte 8**, engine demand torque: should track the throttle closely
  and go negative the moment it shuts. Cheap to confirm now.
- **PGN 65215 byte 5**: candidate for the rear wheel straight from the ABS
  module, which would be a third independent speed.
- **Grip temperature** should fall with speed while the heat is off -- the last
  loose end on that decode.
- **Link stability** with the phone's WiFi off, which is the configuration that
  was stable when it was tested.

---

## Cruise control — SETTLED 2026-09-05, no ride needed

Everything that was open here has been answered, and this section is kept only so
the next person does not repeat the ride.

**The engaged state is not on the bus.** Byte 4 of PGN 65265 from SA 39 held
`0xF7` — SPN 595 reading 3, "not available" — at 94, 87 and 73 km/h with cruise
demonstrably holding the speed and the rider's hand off the grip. Set speed
(SPN 86, byte 6) sat at a constant `0xFF` for the same reason.

What makes it a valid null rather than another silence: **the control was inside
the measurement.** Byte 5 reported every SET and RESUME press in those same
frames, so the message was being received and decoded correctly while byte 4 sat
still. See PROTOCOL.md for the full account.

That is the second null on this question. The first was drawn from captures in
which cruise had never been engaged at all, and it was withdrawn — a capture
cannot show a state nobody produced. This section used to carry that withdrawal
and an instruction to flash `probe/cruise` before riding. Both are obsolete.

**The buttons are found and shipped**: 65265 SA 39 byte 4 carries SET/DEC and
RES/ACC as momentary presses, reported as the legend printed on the rocker. The
enable rocker is byte 4 bit 0.

**What the app shows is derived in code** from measured inputs only — the rocker,
the presses, the brake, the clutch and road speed. It is honest about being a
derivation: the vocabulary differs from the measured fields around it on purpose.

## Two stationary tests — both closed, kept so they are not run again

Neither is outstanding. They are listed because both look like obvious things to
go and try, and each would cost half an hour to re-establish something already
known.

**Fog lamps -- DONE 2026-09-06, and they are not on the bus.** Six transitions,
nothing answered, with PGN 65265 visible this time (it was masked when the first
null was called, which is why that one did not count). Indian's fault table knows
the lamps -- SPN 520291 and 520292 -- so failures are reported; the switch
position is not. See GARAGE-RUN run 9.

**Headlight bulb.** Nothing to test — it is already decoded. Pulling a bulb
raises SPN 2350 FMI 5 for low beam or 2348 FMI 5 for high, and the app names it
with Indian's own C-code. Listed so it is not hunted for twice.

## Worth a glance while riding

The tilt reading is `UPRIGHT` above walking pace and says nothing about
cornering: this is a tip-over sensor, and an accelerometer reads upright in a
balanced turn however far over you are. If it ever says otherwise while moving,
that is a bug and worth reporting.


---

## RESOLVED 2026-09-05 — the `Warn:ON` lamp

DM1 has reported `Warn:ON` with no active fault behind it for weeks, and it sat
on the open list as an unexplained standing warning.

**It is the ABS self-test.** The owner watched it on the ride: `Warn` goes out
and follows the amber ABS lamp, which clears once the wheels turn.

Every look we had ever taken was at a parked bike, where the ABS has not
self-tested yet and cannot. So the lamp was doing exactly the right thing the
whole time, and the only way to see it was to be moving -- which no amount of
analysis on stationary captures was going to deliver.

*(It does not affect the app's alert banner: `Dtc.healthy()` matches on
"No active", so with no fault code there is no banner regardless of the lamps.
The lamps only shift severity when a real fault is present.)*

This section used to have a twin earlier in the file, written before the ride,
asking the rider to go and settle it. Removed 2026-09-09 — a task list that
still asks for an answer it already has costs the reader a ride.

# Next ride — what to do, and what happens by itself

Everything waiting on wheels, in one list, so it takes one outing instead of
three. Firmware `2026.09.04-32` or later.

Most of it is passive: ride normally and the data arrives. Only two things ask
anything of the rider, and both take four minutes.

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

PGN 65382 bytes 1 and 4 are the busiest unexamined bytes on the bus: 255 and 80
distinct values across 4,721 frames, in a proprietary message already known to
carry engine data. Now that the throttle is decoded they can finally be
separated from it.

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

## One thesis the ride settles by itself

`Warn:ON` has appeared in DM1 all day alongside `No active DTC`. It was written
up as the low fuel warning, on the evidence that the tank read 10 % with LOW
FUEL on the dash at the same moment.

The owner's explanation is better: it is the **amber ABS lamp**, which stays lit
from power-up until the ABS completes its self-test, and that test needs the
wheels turning at 8-10 km/h. The bike has been stationary all day, so the lamp
had no opportunity to go out — which fits a constant `Warn:ON` far better than a
fuel level that has not changed either.

**The ride answers it without anyone doing anything.** Watch the flag as you
pass walking pace:

- Goes off around 8-10 km/h → the ABS self-test, and the thesis holds.
- Stays on with the tank still low → the fuel warning after all.

Either way it is worth knowing, because a lamp that is lit before every single
ride is not a warning, and should not be read as one.

*(It does not currently affect the app's alert banner: `Dtc.healthy()` matches
on "No active", so with no fault code there is no banner regardless of the
lamps. The lamps only shift severity when a real fault is present.)*

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

## Two thirty-second tests, stationary, whenever convenient

Neither needs a ride. Both need the change detector, which is in the firmware
while `PROBE_CHANGES` is 1.

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

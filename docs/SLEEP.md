# Deep sleep

The board takes permanent 12 V from the Indian's service connector. It is
powered whether or not the motorcycle is, so left to itself it holds WiFi up and
publishes a heartbeat every thirty seconds, for ever, into a battery nobody is
charging. Deep sleep is the answer to that: go quiet when the bus goes quiet,
come back when the bus comes back.

It is **off by default** and stays off until switched on, for reasons set out
under [Recoverability](#recoverability-the-three-guarantees).

The board on the bike is the CANFD-MC rev 1.0 (board #0001, since 2026-10-03;
the LilyGO T-2CANFD was retired on 2026-09-28). The sleep code is the same for
both, because the pin map is. The currents are not: 4.62 mA asleep on the
CANFD-MC against 17 mA on the LilyGO. Where a section below is about the LilyGO
only, its heading says so.

---

## Quick reference

| | |
|---|---|
| Switch | openHAB → SpringCommand → **Board & Firmware** → *Deep sleep when bus is quiet* |
| Item | `CanBus_Sleep` (Switch) |
| State | `CanBus_SleepState` — `awake` / `asleep` |
| Why it woke | `CanBus_SleepWake` — `power-on` / `can` / `timer` / `other` |
| Retained topic | `canbus/springfield/sleep/status` |
| Command topic | `canbus/springfield/sleep/en` (`ON` / `OFF`; the switch publishes it not retained) |
| Bench board | the same under `canbus/bench`, item `CanBench_SleepEnable` |

The status payload:

```json
{"enabled":"ON","state":"awake","wake":"can","quiet_s":12,"after_s":300}
```

`quiet_s` is how long the bus had been silent **when the payload was written**;
`after_s` is how long it must stay silent before sleeping. The payload is
published only on a new broker session, on a `sleep/en` command and just before
sleeping, so `quiet_s` is not a live counter. A board can show `quiet_s: 0`
minutes after the ignition went off. To tell whether the bus is live, use `efmsg`
in `bus/health` (it climbs only while frames arrive). See "Before pressing Update" in
[OTA.md](OTA.md).

The flag lives in NVS. It survives reboots and OTA, but a USB flash of
`firmware.factory.bin` at `0x0` resets it to **off**. After a fresh flash, switch
`CanBus_Sleep` ON again and confirm `"enabled":"ON"` in the board's reply (see
"Swapping in a new board" in [FLASHING.md](FLASHING.md)).

---

## When it sleeps

Four conditions, all of them:

1. **Enabled** — the NVS flag is set
2. **Awake for at least 90 s** — `SLEEP_MIN_AWAKE_MS`
3. **No CAN frame for 5 minutes** — `SLEEP_QUIET_MS`
4. **Nothing in flight** — the `busy` argument, which is
   `netBusy() || rollbackPending()`: an OTA is running (HTTP or over MQTT), or an
   OTA image is still on trial (see [Deep sleep and OTA](#deep-sleep-and-ota))

Five minutes is generous on purpose. A queue at a level crossing is not the
ignition going off, and waking costs more than staying up for another minute.

Before the chip stops, it pins the controller to 250 kbit/s, publishes
`"state":"asleep"` retained, drives CS high and holds it, and holds the pull-up
on the interrupt line.

## How it wakes

**On CAN.** The MCP2518FD is *not* put into its own Sleep mode — it keeps
receiving, listen-only at 250 kbit/s. Its INT line is level-triggered and wired
to GPIO 8, which is RTC-capable on the ESP32-S3, so the first frame after the
ignition comes on pulls INT low and `ext0` wakes the chip.

That is a deliberate trade. ACAN2517FD can enter and leave the controller's own
Sleep mode but does not expose the wake-on-CAN interrupt configuration, so the
lower-power design means raw SPI writes to registers the library never touches.
When this was decided there was no way to test them off the bike; a bench board
with a USB-CAN adapter for the wake frame now can (IDEAS.md D6, not done yet).
Leaving the controller and transceiver powered does not reach the lowest current
the board can theoretically do. It does switch off the two parts that actually
cost, the radio and the CPU, without writing registers nobody has verified.

## Deep sleep and OTA

Since firmware 2026.10.04-1 two things hold sleep off, both through the `busy`
argument (`src/main.cpp`, both `sleepTick()` calls):

- **An OTA in progress** (`netBusy()`), whether the image comes by HTTP
  (`update`) or over the MQTT link (`mqtt`).
- **An OTA image on trial** (`rollbackPending()`). A new image boots "pending"
  and is kept only once it has reached the broker. A deep-sleep wake is a reset,
  and a reset while pending makes the bootloader start the previous image — so
  the board does not sleep until the trial is over. That is at the first broker
  connection, or after five minutes (`ROLLBACK_GRACE_MS`) when the image gives
  up and goes back by itself. At most five minutes of extra time awake, once per
  OTA.

A USB-flashed image has no trial and changes nothing here. The whole mechanism
is in [OTA.md](OTA.md), "Automatic rollback".

This does not make an OTA to a sleeping board possible: a sleeping board hears
nothing. The ignition rule in [OTA.md](OTA.md), "Before pressing Update", still
holds.

**On the timer.** A one-hour backstop, armed *every single time*, never
conditionally.

---

## Recoverability: the three guarantees

A sleep bug on a motorcycle means riding out to pull a fuse. Three things make
that unnecessary, and none of them is optional:

1. **Off by default**, switched from openHAB like the discovery probes. It
   cannot sleep until told to, and it can be told to stop from anywhere.
2. **The timer backstop is always armed.** If wake-on-CAN never fires — wrong
   pin state, a controller quirk, anything — the board still comes back within
   the hour and can be reached and disabled.
3. **A minimum awake window** after every wake. Without it, a device that wakes,
   connects and immediately sleeps again can never be caught, because the window
   in which it is reachable is shorter than the time it takes to send it a
   command.

Turning sleep **off is only possible while the board is awake** — a sleeping
board is not listening, and the switch's command is not retained, so a press
while it sleeps is lost (see [Operating it](#operating-it)). Worst case you wait
out the backstop and use the awake window that follows.

---

## What it costs

Deep sleep is a reset. RAM is gone, WiFi and MQTT come up from nothing, and
**the first seconds of a ride go unrecorded**. The fault counters survive
because they live in NVS (see `counters.h`), as does the sleep flag itself and
the remembered WiFi network.

Measured on the bike, 2026-09-07: **about 90 seconds** from the CAN frame that
woke the board to MQTT being up. The wake itself was immediate; essentially all
of that was WiFi failing over between networks. From 2026-09-15 the winning SSID
was remembered in NVS and tried first; since 2026-09-27 the network task scans
and takes the strongest known network instead (`net.cpp`), which is both faster
and right -- the remembered one had kept the bike on a -82 dBm house network with
the phone's hotspot standing next to it. On 2026-10-04 the board woke on CAN at
10:21:44 and took an OTA command over the broker at 10:22:18, so MQTT was up in
under 35 seconds.

### Measured on the CANFD-MC rev 1.0 — the board on the bike today

Measured 2026-09-27 on a CANFD-MC rev 1.0 on the bench, in series with the 12 V
input, with no bus connected:

| | current | power |
|---|---|---|
| **Asleep** | **4.62 mA, steady** | **0.055 W** |
| Awake (WiFi + BLE + MQTT, no bus) | 25–31 mA | 0.3–0.4 W |

That is a factor of 3.7 below the 17 mA the LilyGO T-2CANFD drew asleep (next
section). The difference is the power path: an LM5164 buck straight from 12 V,
and no isolated transceiver module with a DC-DC of its own. What is left in
those 4.62 mA is the MCP2518FD, still receiving, and the TCAN332G transceiver
(IDEAS.md D6).

**Calculated from those two figures, not measured:**

| | average | 18 Ah AGM to half charge, board alone |
|---|---|---|
| asleep the whole time | 4.62 mA | about 81 days |
| asleep, with the hourly backstop wake of 90 s at ~28 mA | about 5.2 mA | about 72 days |

**Confirmed on the bike.** The board has also been measured in place, with the
bus connected and supply and bus sharing the service connector's ground, and it
reads the same as on the bench (the owner's measurement, reported 2026-10-04). The question of current
through the CAN lines on a ground difference is thereby answered: none that a
meter shows.

### Measured on the LilyGO T-2CANFD, 2026-09-07 (history)

The LilyGO was on the bike until 2026-09-28. Everything from here to "The side
benefit" is that board, kept because the method and the reasoning still hold.

On a Fluke 175 True RMS, in series with the **12 V** input — not the 5 V USB
rail, so these figures include the board's own regulator losses.

**Measurement conditions, stated because they are not the final installation.**
The board was fed from a separate bench battery while CAN H and CAN L came from
the machine, so **supply ground and bus ground were not common**. In the finished
installation both come from the service connector and share a ground.

This does not invalidate the numbers. What is being measured is the current the
board draws from 12 V, and the bulk of it — regulator quiescent, MCP2518FD,
transceiver, USB-serial chip — is internal and ground-referenced, unaffected by
where the bus reference sits. The board received frames throughout, so the
common-mode offset stayed inside the transceiver's range.

The one thing that could differ is current flowing through the CAN lines
themselves on a ground potential difference. In listen-only the transceiver never
drives the bus, so this should be small, but the sign is unknown — the figure
could be reading slightly high or slightly low. **Worth one re-measure in situ
once it is wired in permanently**, which takes a minute and closes it. Nothing
below changes unless that reading is wildly different, and the sensitivity table
further down shows how much room there is.

| | current | power |
|---|---|---|
| Awake, LilyGO T-2CANFD | 50–73 mA, wandering | 0.6–0.9 W |
| **Asleep, LilyGO T-2CANFD** | **17 mA, steady** | **0.20 W** |

**On the LilyGO: a factor of four.** Against an 18 Ah AGM that is the difference between
reaching half charge in **five and a half days** and reaching it in **twenty-two**
— which is the difference between a board that has to be unplugged and one that
can stay wired in.

**The awake figure wanders and should not be trusted, and it no longer matters.**
It is widest with the bus quiet and steadies when traffic is flowing, which
points at the WiFi duty cycle: idle, the radio drops into modem sleep between
beacons and wakes in bursts; publishing at 1 Hz it stays busy. A handheld meter
samples a few times a second and cannot average millisecond radio bursts, so the
range is what the instrument wandered over, not a range the board sat in.

That would be a problem if the board were awake all the time. It is awake about
2.5 % of the time, so the uncertainty is diluted almost to nothing:

| if awake really were | average | half charge |
|---|---|---|
| 50 mA | 17.8 mA | 21.0 days |
| 73 mA | 18.4 mA | 20.4 days |
| 150 mA | 20.3 mA | 18.5 days |
| 250 mA | 22.8 mA | 16.4 days |

Triple the highest reading and the answer moves by a fifth. Without deep sleep
the same uncertainty swings it between 3.8 and 7.5 days — a factor of two on the
number that decides everything.

So the measurement that cannot be trusted is the one that stopped mattering, and
the one that governs is the steady DC asleep — 17 mA on the LilyGO, 4.62 mA on
the CANFD-MC — which is exactly what a handheld meter measures well. No better
instrumentation is needed to settle this.

### The side benefit

Worth stating because it was not the goal. Before this, the board was hard
powered down whenever the ignition went off, mid-whatever. Now it stays up for
the five-minute quiet window first — so it shuts down in its own time, having
had a chance to see the bus settle, rather than being cut off at the knees.

---

## Operating it

**Check what it is doing** — the retained topic answers even when the board is
gone:

```bash
mosquitto_sub -h <broker> -t 'canbus/springfield/sleep/status' -C 1
```

**Read the two silences.** `CanBus_Status` going offline means any of: sleeping
on purpose, crashed, out of WiFi range, or unpowered. Those want very different
responses, which is what `CanBus_SleepState` is for:

| `CanBus_Status` | `CanBus_SleepState` | Meaning |
|---|---|---|
| offline | `asleep` | working as designed |
| offline | `awake` | something went wrong — go and look |
| online | `awake` | normal |

**Before an OTA, check the status is fresh.** A retained `sleep/status` that says
`awake` may have been written before the board went down; it is retained, not
live. Confirm frames are arriving, or that the ignition is on, before starting an
update — and **leave the ignition on for the duration**. An OTA attempted with
the bus quiet stalls at "Downloading 0%"; the same update with the ignition on
took eleven seconds.

**Switching sleep off, and what to do if it will not wake on CAN.** The
`CanBus_Sleep` switch publishes `ON` / `OFF` to `<base>/sleep/en` **not
retained**, and no rule re-sends it (`canbus-probe-queue.js` does that for the
probe switches only). So:

- With the board **awake**, the switch works at once; confirm it on
  `"enabled"` in `sleep/status`, which the board republishes in reply.
- With the board **asleep**, a press is lost. Wait for the next wake — the
  first CAN frame, or the hourly backstop — and press it in the awake window
  that follows (at least 90 s after every wake, by design).
- To have it applied without being there at the right moment, publish `OFF`
  **retained** to `<base>/sleep/en` with any MQTT client that may write to it.
  The board subscribes on every connect, so it is applied at the next wake —
  **and at every wake after that**, overriding the switch, until the retained
  value is cleared with an empty retained payload on the same topic.

The bench board is the same under `canbus/bench` with `CanBench_SleepEnable`.

---

## What the bike found

Five faults, none of which showed up on the bench. Recorded because each one
looked like success from the desk.

**0. The `asleep` marker was lost (2026-09-27, the day the network moved into
its own task).** `sleepPublishAsleep()` handed the retained marker to the
out-queue and `netFlush()` returned as soon as the task had written it to the
TCP stack -- not when it had left the radio. The chip went down, openHAB got the
last will and never `asleep`. The old code had waited 120 ms after its
synchronous publish for exactly this; 27-3 waits 250 ms after the flush. A
useful reminder that "sent" has three meanings on this chip: queued, written to
the socket, and on the air.

**1. `sleepTick()` was unreachable.** It was called at the foot of `loop()`, but
the only branch reachable with the bus quiet is the `if (!haveSpeed) { ... return; }`
early return — which returns first. The board never slept, and the code looked
correct. It is now called in both places.

**2. The bitrate wandered.** The firmware used to round-robin 250/500/125/100/50
kbit/s looking for the bus, so at the moment it decided to sleep the controller
could be sitting on any of them. Asleep at 500 on a 250 kbit/s bus it hears
nothing, INT never asserts, and wake-on-CAN quietly degrades into "wakes hourly
on the backstop" — which looks like it works until you time how long the ignition
takes to bring it back. The owner cut this off at the root: *"we know what the
bus uses — 250 kbit"*. The search is now behind `CAN_FIXED_BITRATE`, and
`sleepTick()` still pins the rate before sleeping.

**3. The pull-up died in the sleep.** ACAN2517FD sets INT up as `INPUT_PULLUP`
and relies on the internal pull-up to hold it high when the controller has
nothing to say. **Internal pull-ups are switched off in deep sleep** unless the
RTC domain is told to keep them, so the line floated — and `ext0` compares the
pad level, which for a floating pad is undefined. It read high enough never to
wake. Slept 05:03:51, ignition on at 05:06, still asleep two minutes later; the
backstop would have returned it around 06:04, which is precisely why that line
exists. Fixed with `rtc_gpio_pullup_en()`.

**4. CS floated.** Left floating through the sleep, the controller can see
phantom selects, and the frame that gets corrupted is the wake-up frame. Held
high with `gpio_hold_en()`.

---

## Settled by the measurements

### The backstop stays. Question closed.

This was written as an open question on the estimate that awake cost twenty
times asleep, which would have made the hourly wake around 40 % of the total. On
the LilyGO the real ratio was four, and the arithmetic came out completely
differently:

| awake per hour (LilyGO, measured figures) | average | cost over pure sleep |
|---|---|---|
| 30 s | 17.4 mA | +0.4 mA (2 %) |
| 90 s | 18.3 mA | +1.3 mA (7 %) |

On the CANFD-MC the ratio is about six (4.62 mA asleep, 25–31 mA awake), and the
same arithmetic, **calculated** with 28 mA awake, gives:

| awake per hour (CANFD-MC, calculated) | average | cost over pure sleep |
|---|---|---|
| 30 s | 4.8 mA | +0.2 mA (4 %) |
| 90 s | 5.2 mA | +0.6 mA (13 %) |

**Well under 1.5 mA on either board for the one guarantee standing between a
sleep bug and a ride out to pull a fuse.** It stays, and not as a compromise — it
was never expensive, and the estimate that said it was is the thing that was
wrong.

---

## Still open

### The floor on the CANFD-MC is 4.62 mA, and there is a way lower

What is left asleep is the MCP2518FD, receiving, and the TCAN332G. Lower means
putting the controller into its own Sleep mode with a wake-on-CAN filter — raw
SPI writes ACAN2517FD does not expose — which would take the board to about
1 mA. It is IDEAS.md D6, status "measure": to be tried on a bench board with a
USB-CAN adapter sending the wake frame, never first on the bike.

### The on-bike measurement — closed

The board has been measured in place on the bike and reads the same as on the
bench (the owner's measurement, reported 2026-10-04). Nothing is open here.

### How long it can stand (CANFD-MC, calculated)

**Calculated, not measured**, from the measured 4.62 mA (bench and bike) and the same
assumptions as the LilyGO table in the history below — 18 Ah AGM, self-discharge
about 1 mA equivalent, 50 % state of charge as marginal cranking and 35 % as
doubtful on a big twin:

| machine's own quiescent | total | 50 % | 35 % |
|---|---|---|---|
| 5 mA | 10.6 mA | 35 days | 46 days |
| **10 mA** | **15.6 mA** | **24 days** | **31 days** |
| 20 mA | 25.6 mA | 14.6 days | 19 days |

If the machine draws 10 mA of its own, adding the board takes 11 mA to about 16
and roughly 34 days to 24. Winter shortens all of it, as the history section
says. On the 10 mA assumption, anything standing longer than about three weeks
still wants a maintenance charger.

**The missing measurement is still the machine's own quiescent draw** — meter in
series with the battery negative, ignition off, board disconnected. Thirty
seconds, and the table above stops being parametric.

### Wiring

The CANFD-MC has four soldered pads, CANL · CANH · GND · VBAT, on DIAG pins
G, H, D and A of the service connector. There is no isolation barrier and no
terminator on the board, so nothing to bridge and nothing to open; the
protection is the board's own fuse and the machine's fuse in the 12 V feed. The
check before wiring is unchanged: **CAN H ↔ CAN L** at the service connector
with the ignition off reads **60 Ω**. See [FLASHING.md](FLASHING.md) §4.

### The backstop has never actually fired on the bike

Armed on every sleep, but wake-on-CAN has always got there first, so the recovery
path it exists to provide is still untested in the field. On the bench it has
been seen: on 2026-10-04 a CANFD-MC with no bus and the backstop shortened to
120 s for the test woke with `wake: timer` ([OTA.md](OTA.md), rollback test
step 5).

---

## History: the LilyGO T-2CANFD (on the bike until 2026-09-28)

Everything in this section is about the LilyGO board: its isolated transceiver
module, its 17 mA floor, its headers and its terminal. None of it describes the
CANFD-MC, which was designed from these findings. Kept as written.

### The hunt for more mA is over — the floor is hardware

**Closed 2026-09-07, by a continuity test and a datasheet.**

This document predicted the remaining current was in the transceiver and
suggested looking for a standby pin. That was the wrong shape of answer.

The owner measured for continuity between the 12 V input's negative and the CAN
terminal's GND and found **none**. The CAN side is galvanically isolated, which
narrows the part down: the board carries a **Mornsun TD501MCANFD**, an isolated
CAN-FD transceiver module rated 5 VDC in, **2500 VDC isolation**, and — the line
that matters — **"Integrated Power: yes"**. It contains its own isolated DC-DC.

That converter is where the milliamps are. Mornsun's published static current is
26 mA at 5 V, which through a buck from 12 V is roughly 12.7 mA — about
three-quarters of the measured 17 mA. (That figure comes from a search summary,
not from reading the datasheet table; the 17 mA measurement is the authority
here. Note the two constrain each other: 26 mA at 5 V behind a *linear* regulator
would draw 26 mA at 12 V, which is more than the whole measured budget, so the
board must be using a switching regulator or the 26 mA is high.)

**The converter cannot be switched off without switching off the receiver, and
the receiver is wake-on-CAN.** There is no firmware change that recovers this
current. 17 mA is the floor **for as long as the bus is the thing that wakes the
board** — and that qualifier turns out to matter.

### There is a way lower, and it was costed rather than guessed

Worked out 2026-09-07 out of curiosity, and written down because "can we get
below 17 mA?" is a question that will be asked again.

The board brings out two small JST-SH 1.0 mm headers beside the USB-C connector,
and they are not in any of LilyGO's own notes:

| | pin 1 | pin 2 | pin 3 | pin 4 |
|---|---|---|---|---|
| **CNC1** | GND | VDD3V3 | `Uart_TX` | `Uart_RX` |
| **CNC2** | GND | VDD3V3 | **`IO1`** | **`IO2`** |

`IO1` and `IO2` are GPIO 1 and 2, both in the ESP32-S3's **RTC domain**, so
either can wake the chip from deep sleep with `ext0` — exactly as the
MCP2518FD's interrupt line does today. Wake on a switched **ACC** line instead of
on the bus, and the Mornsun module can be powered down while asleep. That takes
17 mA to about **4 mA**, the remainder being buck quiescent, the USB-serial chip
and the power LED, none of which can be switched.

**What it would actually buy depends on a number still unmeasured** — the
machine's own quiescent draw:

| machine's own | now (17 mA) | after (~4 mA) | gain |
|---|---|---|---|
| 5 mA | 16.3 days | **37.5 days** | +21 (2.3×) |
| 10 mA | 13.4 days | **25.0 days** | +12 (1.9×) |
| 20 mA | 9.9 days | 15.0 days | +5 (1.5×) |
| 30 mA | 7.8 days | 10.7 days | +3 (1.4×) |

At 10 mA the board's share of the total falls from 61 % to 27 %, so the machine
becomes the larger consumer and we stop being the problem. At 30 mA the whole
exercise is worth three days. **So measure the machine first** — meter in series
with the battery negative, ignition off, board disconnected. Thirty seconds
decides whether this is worth twenty-one days or three.

**And the cost is a different class from lifting `RZ2`.** That was seconds with a
hot tip. This is:

- ACC's 12 V cannot go straight to `IO1` — it needs a divider and clamp, or an
  opto.
- The module's `VCC` comes off the board's 5 V rail, so switching it means
  **getting in between them**: lifting a pin or cutting a track and inserting a
  load switch. That is surgery, not a desolder.
- It trades a **proven** wake path — the MCP interrupt line, working on the
  machine — for one you build. The timer backstop still stands behind it.

So: not a gap, not a limit either, but a costed option. If the machine's own draw
turns out to be small, this is the change worth having a spare board on the shelf
for — which lifting `RZ2` never was.

### It draws nothing extra from the machine's side

Worth stating because the obvious worry is wrong. The isolated module's bus side
is fed by its own DC-DC, whose **input** sits on the board's supply rail — it does
not tap the bus for power. The receiver itself is a high-impedance differential
input drawing microamps. And a termination resistor carries no current in the
recessive state, so with the ignition off and the bus silent it contributes
nothing at all.

**So the 17 mA transfers one-for-one to the machine's battery, with nothing on
top.** Had the module been the type that draws its bus-side power from the bus,
the arithmetic would have been different; "Integrated Power" is what rules that
out.

### How long it can stand (LilyGO, 17 mA)

With a stated set of assumptions — 18 Ah AGM, self-discharge about 1 mA
equivalent, 50 % state of charge as marginal cranking and 35 % as doubtful on a
big twin:

| machine's own quiescent | total | 50 % | 35 % |
|---|---|---|---|
| 5 mA | 23 mA | 16.3 days | 21.2 days |
| **10 mA** | **28 mA** | **13.4 days** | **17.4 days** |
| 20 mA | 38 mA | 9.9 days | 12.8 days |

Winter is doubly strict — capacity falls and the engine is harder to turn. At
0 °C the 13.4 days becomes about 10.7, at −10 °C about 9.4, and the real failure
arrives sooner than the table says because cranking demand rises at the same
time.

**Stated plainly: the board shortens standing time.** If the machine draws 10 mA
of its own, adding ours takes 11 mA to 28 and roughly 34 days to 13. Deep sleep
moved this from impossible (5.5 days) to practical (13), but it is not free.
Anything standing longer than a fortnight wants a maintenance charger, which is
ordinary practice for a parked motorcycle regardless of what is wired to it.

**The missing measurement is the machine's own quiescent draw** — meter in series
with the battery negative, ignition off, board disconnected. Thirty seconds, and
the table above stops being parametric. It is a useful baseline independently of
this project: a motorcycle that suddenly draws 40 mA at rest has something wrong.

### Wiring, and the isolation that gets bridged anyway

The service connector provides CAN H, CAN L, GND and permanent 12 V. Both the
board's DC input and the CAN terminal's GND take their reference from that one
connector, so **the isolation barrier is bridged externally the moment it is
wired**, and cannot be preserved while the supply and the bus share a source.

That costs nothing here. Isolated modules exist for installations where two
systems sit at different ground potentials; one machine with one battery and one
reference has nothing to isolate from. What is given up is fault containment — a
board that failed short would no longer be held off the machine's bus — and the
protection that actually matters on a permanent installation is a fuse in the
12 V feed.

The CAN A terminal is `D GND · CAN H · CAN L · S GND`. **`D GND` is the isolated
CAN ground and is the one the machine's ground belongs on**; `S GND` is for a
cable screen and stays open on unshielded wiring. Power comes from the separate
DC input, never from this terminal. (An earlier version of this note repeated
README.md's claim that pin 1 was a `5VDC` rail to be kept clear — that was wrong,
inherited from the other board, and is corrected there.)

One check worth doing before permanent installation, for bus health rather than
current: measure **CAN H ↔ CAN L** at the service connector with the ignition
off. **60 Ω** is correct. **40 Ω** means the board's own 120 Ω terminator is in
circuit on an already-terminated bus and must be opened.

### `busy` was hardcoded `false`

Until 2026.10.04-1 both `sleepTick()` call sites passed `false`, so an OTA could
not hold sleep off; in practice the bus was live during an OTA anyway, which is
why it never bit. Both now pass `netBusy() || rollbackPending()` — see
[Deep sleep and OTA](#deep-sleep-and-ota).

---

## Files

| | |
|---|---|
| `src/sleep.h` | the contract, and the reasoning behind the shape |
| `src/sleep.cpp` | the decision and the sleep itself |
| `src/main.cpp` | `sleepBegin()`, `sleepNoteFrame()`, two `sleepTick()` calls, `sleepPublishAsleep()`, the `sleep/en` handler |
| `src/net.cpp` | `netBusy()` — an OTA is running; one half of `busy` |
| `src/rollback.cpp` | `rollbackPending()` — an OTA image is on trial; the other half |
| `things/canbus.things` | the three MQTT channels (openHAB) |
| `items/canbus.items` | `CanBus_Sleep`, `CanBus_SleepState`, `CanBus_SleepWake` |
| `things/canbus-bench.things`, `items/canbus-bench.items` | the same for the bench board: `CanBench_SleepEnable`, `CanBench_Sleep` |

### Constants

| | | |
|---|---|---|
| `SLEEP_QUIET_MS` | 5 min | silence before sleeping |
| `SLEEP_BACKSTOP_S` | 3600 s | timer wake, always armed |
| `SLEEP_MIN_AWAKE_MS` | 90 s | minimum reachable window after a wake |

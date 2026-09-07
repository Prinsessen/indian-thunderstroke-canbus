# Deep sleep

The board takes permanent 12 V from the Indian's service connector. It is
powered whether or not the motorcycle is, so left to itself it holds WiFi up and
publishes a heartbeat every thirty seconds, for ever, into a battery nobody is
charging. Deep sleep is the answer to that: go quiet when the bus goes quiet,
come back when the bus comes back.

It is **off by default** and stays off until switched on, for reasons set out
under [Recoverability](#recoverability-the-three-guarantees).

---

## Quick reference

| | |
|---|---|
| Switch | openHAB → SpringCommand → **Board & Firmware** → *Deep sleep when bus is quiet* |
| Item | `CanBus_Sleep` (Switch) |
| State | `CanBus_SleepState` — `awake` / `asleep` |
| Why it woke | `CanBus_SleepWake` — `power-on` / `can` / `timer` / `other` |
| Retained topic | `canbus/springfield/sleep/status` |
| Command topic | `canbus/springfield/sleep/en` |

The status payload:

```json
{"enabled":"ON","state":"awake","wake":"can","quiet_s":12,"after_s":300}
```

`quiet_s` is how long the bus has been silent; `after_s` is how long it must stay
silent before sleeping. Watching those two converge is how you tell it is about
to go down.

---

## When it sleeps

Four conditions, all of them:

1. **Enabled** — the NVS flag is set
2. **Awake for at least 90 s** — `SLEEP_MIN_AWAKE_MS`
3. **No CAN frame for 5 minutes** — `SLEEP_QUIET_MS`
4. **Nothing in flight** — the `busy` argument

Five minutes is generous on purpose. A queue at a level crossing is not the
ignition going off, and waking costs more than staying up for another minute.

Before the chip stops, it publishes `"state":"asleep"` retained, pins the
controller to 250 kbit/s, drives CS high and holds it, and holds the pull-up on
the interrupt line.

## How it wakes

**On CAN.** The MCP2518FD is *not* put to sleep — it stays in Normal mode,
receiving. Its INT line is level-triggered and wired to GPIO 8, which is
RTC-capable on the ESP32-S3, so the first frame after the ignition comes on
pulls INT low and `ext0` wakes the chip.

That is a deliberate trade. ACAN2517FD can enter and leave the controller's own
Sleep mode but does not expose the wake-on-CAN interrupt configuration, so the
lower-power design means raw SPI writes to registers the library never touches —
with no way to test them off the bike. Leaving the controller and transceiver
powered does not reach the lowest current the board can theoretically do. It
does switch off the two parts that actually cost, the radio and the CPU, without
writing registers nobody can verify from here.

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

Turning the switch **off takes effect at the next wake, not immediately** — a
sleeping board is not listening. Worst case you wait out the backstop.

---

## What it costs

Deep sleep is a reset. RAM is gone, WiFi and MQTT come up from nothing, and
**the first seconds of a ride go unrecorded**. The fault counters survive
because they live in NVS (see `counters.h`), as does the sleep flag itself and
the remembered WiFi network.

Measured on the bike, 2026-09-07: **about 90 seconds** from the CAN frame that
woke the board to MQTT being up. The wake itself was immediate; essentially all
of that was WiFi failing over between networks. See `wifiConnect()` — the
winning SSID is now remembered in NVS and tried first, which removes most of it
when the same network is used twice running.

### Measured, 2026-09-07

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
| Awake | 50–73 mA, wandering | 0.6–0.9 W |
| **Asleep** | **17 mA, steady** | **0.20 W** |

**A factor of four.** Against an 18 Ah AGM that is the difference between
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
the one that governs is 17 mA of steady DC, which is exactly what a handheld
meter measures well. No better instrumentation is needed to settle this.

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

**If it will not wake:** switch `CanBus_Sleep` off and wait out the backstop. The
command is retained, so it is applied the moment the board next connects.

---

## What the bike found

Four faults, none of which showed up on the bench. Recorded because each one
looked like success from the desk.

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
times asleep, which would have made the hourly wake around 40 % of the total. The
real ratio is four, and the arithmetic comes out completely differently:

| awake per hour | average | cost over pure sleep |
|---|---|---|
| 30 s | 17.4 mA | +0.4 mA (2 %) |
| 90 s | 18.3 mA | +1.3 mA (7 %) |

**Under 1.5 mA for the one guarantee standing between a sleep bug and a ride out
to pull a fuse.** It stays, and not as a compromise — it was never expensive, and
the estimate that said it was is the thing that was wrong.

---

## Still open

### The next mA are in the transceiver — and there may be a cheap way in

An ESP32-S3 in deep sleep draws tens of microamps, so essentially **all** of the
17 mA is everything except the CPU: the CAN transceiver in normal mode, the
MCP2518FD, the 12 V regulator's quiescent draw, the USB-serial chip and a power
LED. That confirms what this document predicted before the numbers existed.

The expensive route is raw SPI writes to wake-on-CAN registers ACAN2517FD does
not expose, unverifiable off the bike — set out under
[How it wakes](#how-it-wakes).

**Check the cheap route first.** Many CAN transceivers have a hardware standby
pin (a TJA1051T/3 has `S`). If the T-2CANFD routes it to a GPIO, standby is a
`digitalWrite` rather than register hacking, and it is likely worth most of the
available 8–10 mA at none of the risk. **Open question: which transceiver is
fitted, and is that pin brought out?** Read the schematic before writing any
code.

Worth keeping in proportion, though: 17 mA already gives about three weeks to
half charge, and anything parked longer than that wants a tender regardless of
what this board does. This is a nice-to-have, not a gap.

### The backstop has never actually fired

Armed on every sleep, but wake-on-CAN has always got there first, so the recovery
path it exists to provide is still untested in the field.

### Other

**`busy` is hardcoded `false`** at both call sites. An OTA cannot currently hold
sleep off; in practice the bus is live during an OTA anyway, which is why this
has not bitten.

---

## Files

| | |
|---|---|
| `src/sleep.h` | the contract, and the reasoning behind the shape |
| `src/sleep.cpp` | the decision and the sleep itself |
| `src/main.cpp` | `sleepBegin()`, `sleepNoteFrame()`, two `sleepTick()` calls, `sleepPublishAsleep()`, the `sleep/en` handler |
| `things/canbus.things` | the three MQTT channels (openHAB) |
| `items/canbus.items` | `CanBus_Sleep`, `CanBus_SleepState`, `CanBus_SleepWake` |

### Constants

| | | |
|---|---|---|
| `SLEEP_QUIET_MS` | 5 min | silence before sleeping |
| `SLEEP_BACKSTOP_S` | 3600 s | timer wake, always armed |
| `SLEEP_MIN_AWAKE_MS` | 90 s | minimum reachable window after a wake |

# If the sniffer ever transmits

> **Nothing here is in effect.** `TX_ENABLED` in [`src/main.cpp`](../firmware/src/main.cpp)
> is 0 and the controller runs in hardware listen-only mode. This document
> describes what *would* become possible, what it would cost, and what must stay
> impossible even then. It is a plan on a shelf, written 2026-09-07 because the
> owner asked what leaving read-only would buy — explicitly as curiosity, not as
> a request.

The decision itself is the owner's and its conditions are in
[SKILLS.md](SKILLS.md) section 7: after passive listening is finished and
documented, the app is current, and a real test ride has happened.

---

## What it would unlock, ranked for this machine

### 1. DM2 — stored fault codes. The prize, and not close.

The gear position sensor is the reason to consider this at all. Three sensors
have been replaced under warranty without anyone cleaning the mechanism, and
`CanBus_GearGlitches` counts what *we* observe live since the last boot. DM2 is
what **the ECU itself has stored** — including faults from before this project
existed.

That is the difference between "I have measured twenty-three events" and "your
own control unit has been recording them since 2023". A service desk can wave
away the first. The second is their own witness.

### 2. DM4 — freeze frames

The conditions at the moment a fault set: road speed, engine speed, temperature.
For the front wheel sensor — the one that ran without its shim until the tone
ring machined the face off it and the ABS quit in traffic — this is the missing
piece. An intermittent fault without context is unsolvable; with a freeze frame
you know whether it trips cold, hot, at low speed or over bumps.

### 3. Component ID (PGN 65259), per module

This would settle **SA 136**, the second instrument cluster from a different
manufacturer that claims an address eight times across four rides and never says
another word (see [DECODE-PLAN.md](DECODE-PLAN.md), "SA 136"). Today we infer it
from a function code. A request asks it directly: who are you, what serial, what
software.

### 4. Anything answered on demand rather than broadcast

VIN and software ID arrive today by waiting for a TP/BAM that comes when it
comes. PGN 59904 asks and gets an answer in a second — and per module, not only
from the ECU.

---

## What it would cost

**It is not "set `TX_ENABLED` to 1".**

**Address claim.** The board must claim its own source address properly. Pick
one already in use and a real module is disrupted; address claim is a protocol
to implement correctly, not a number to choose. Four modules are on this bus
plus SA 136, and their addresses are documented in SKILLS.md section 1.

**Error frames.** A malformed or mistimed frame produces error frames, and
enough of them push a node toward bus-off. On a moving motorcycle that means the
ABS module going away. This is the failure mode that makes "engine off, in the
garage" non-negotiable for a first attempt.

**Leaving listen-only changes the board's presence.** Today it is invisible: it
cannot even ACK. That invisibility is what makes it safe to leave connected to a
vehicle, and it is what
[SKILLS.md section 8](SKILLS.md) rests on when it says the board adds no attack
surface. Transmitting trades that away, and the trade should be conscious.

---

## What must stay impossible

**DM3 and DM11 clear stored and active fault codes.** They would destroy exactly
the evidence this whole exercise is meant to collect. If transmit is ever
enabled, these must be **impossible to call — not discouraged, not commented
out, but never compiled in.**

**Proprietary Polaris diagnostic modes.** Undocumented and potentially
state-changing. Nothing on the list above needs them.

---

## The protocol for a first attempt

In this order, and only this:

1. In the garage, on the sidestand
2. Ignition **on**, engine **off**
3. USB cable attached — a serial console that does not depend on WiFi or MQTT
4. **One** request, to **one** address: DM2 from SA 0
5. Rate-limited to a single request; listen for the answer
6. Never on a moving machine

A side benefit already built: deep sleep keeps the board awake for five minutes
after the bus goes quiet, so a request/response session in the garage no longer
races the ignition timeout the way the switch hunts did. See [SLEEP.md](SLEEP.md).

---

## What this does not change

The security conclusion in [SKILLS.md](SKILLS.md) section 8 is unaffected by any
of the above. Transmitting would let *this board* ask the machine questions. It
would not make the machine startable from the bus: the ignition is a power state
rather than a command, the start button is a hardware pin the ECU reads
directly, and the immobiliser handshake is cryptographic. Those five locks are
properties of the motorcycle, not of our listen-only mode.

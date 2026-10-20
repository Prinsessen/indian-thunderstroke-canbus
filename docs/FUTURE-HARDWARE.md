# Future hardware — what was considered, and what was decided (2026-09-16)

Notes from a conversation the day after the DNS-cache fix and the CANFD-MC
rev 1.0 review, kept so the reasoning does not have to be redone. On that day
nothing here was scheduled and the order of work was: **bring up CANFD-MC
rev 1.0 first**, with a proven bus front end, a measured power profile and a
verified net list. Everything below builds on that, not instead of it.

**Status 2026-10-04.** That first step is done: CANFD-MC rev 1.0
([Prinsessen/canfd-mc-hardware](https://github.com/Prinsessen/canfd-mc-hardware)) is on the bike
(a first board from 2026-09-28, which failed; board #0001 since 2026-10-03),
board #0002 is the bench board, and the sleep current is measured (4.62 mA,
SLEEP.md). Of what follows, one thing has started: the cellular trial of §2, as
a Cat-1 HAT for the bench board (IDEAS.md B14, HAT ordered 2026-09-28). The
rest is still not scheduled. The text below is as written on 2026-09-16.

---

## 1. ESP32-P4 (e.g. JC-ESP32P4-M3, ESP32-P4NRW32 + ESP32-C6)

Not a faster S3 — a different class of chip. What is actually new in silicon:

- **Three native TWAI controllers** (classic CAN, not FD — fine, the bike is
  classic J1939). The MCP2518FD, SPI, crystal and load capacitors disappear,
  and with them the "reads zero frames" class of first-revision faults.
- **Dual-core RISC-V 400 MHz, 32 MB PSRAM in package** (the NRW32 suffix).
- **MIPI-DSI display with a 2D accelerator (PPA), MIPI-CSI camera with ISP,
  H.264 encoder, JPEG codec** — all in hardware.
- **USB 2.0 high-speed host**, SDIO 3.0, a low-power core.
- Radio on a separate **ESP32-C6** behind SDIO (WiFi 6 at 2.4 GHz only, BLE 5,
  802.15.4). WiFi/BLE coexistence leaves the core that reads the bus. WiFi 6
  and BLE 5 change nothing on a motorcycle by themselves; the argument is the
  display, camera, storage and USB side.

### What it would give, in order of value

1. **Raw CAN capture of every ride, always.** SD card or USB stick; 204 frames/s
   at ~24 bytes is 18 MB an hour, three years on a 64 GB card; the last two
   hours also in PSRAM as a ring buffer. `ride_capture.py` has cost two rides
   (Zealand 2026-09-13, the test ride 2026-09-15). This ends that. The dullest
   idea and the most valuable.
2. **Black box.** The same ring buffer dumped on events — DM1 change, ABS
   activity, tip-over, hard braking, the lamp that lit on 2026-09-15 — five
   minutes before, one after, without being asked. The history a warranty case
   about the gear position sensor needs.
3. **Camera with CAN data burnt in.** CSI in, H.264 out, speed/gear/lean/
   throttle/brake as overlay or as a sidecar track with the same clock. The
   INNOVV K7 films without data; syncing afterwards is guesswork. Also the only
   way the load-byte hunt gets to see road gradient.
4. **Own cellular uplink** via USB-HS host and a 4G dongle — see §2 for why a
   UART module is the better shape on any chip.
5. **The display as the instrument, properly.** DSI at 1024×600 or more, PPA
   for scaling/rotation, LVGL at full frame rate, the bus read by the other
   core undisturbed. On the S3 it is RGB-parallel at 800×480 with CAN, WiFi and
   BLE competing for one core.
6. **Rear-view / blind-spot camera** on the same CSI input, shown on the
   display on a left-indicator glance.
7. **A third CAN channel for transmitting** (TRANSMIT.md experiments) on an
   isolated channel while listening on the main bus — never a risk to the bus
   being read.
8. **Analysis on the bike**: load-byte correlation against torque, throttle and
   rpm in real time; tyre warm-up against ambient; fuel model against speed.
   Today this is done afterwards in InfluxDB.

### The price, honestly

- **The firmware is rewritten, not ported.** The Arduino core for the P4 is
  young and BLE goes through ESP-Hosted to the C6, where NimBLE support is
  immature. Realistically ESP-IDF from scratch: months, not weeks.
- **Power.** P4 + display + camera is 1–2 A at 5 V. The LM5164 on CANFD-MC is
  sized for < 0.5 A at 3.3 V. Deep sleep becomes a system design where display
  and camera are hard-switched off, not an `esp_deep_sleep` line. The sleep
  current just won has to be won again.
- **Size and mounting.** Not 55 × 32 mm any more, and MIPI FPC connectors are
  exactly the kind of connection CANFD-MC was designed away from.
- **Temperature.** A JC dev board is not specified the way CANFD-MC is; 32 MB
  in-package PSRAM typically carries the same 65 °C limit as the R8 on the S3.

**Verdict:** items 1 and 2 alone justify the chip, because they close the hole
that has cost the most; 3 and 4 are what the S3 can never deliver. It is a
rev 2 project on top of a finished CANFD-MC, not a replacement for it.

---

## 1b. The cranking dip — measured, and what rev 2 does about it (2026-10-20)

The rev 1.0 review left one measurement open: "UVLO releases at 6.5 V. A cold
crank can dip below that … measure the dip during cranking before rev 1.1 is
frozen. If it is a problem, the answer is a regulator with a lower minimum input,
not a bigger capacitor." The bike has done the measurement. On 26 of 27 starts
between 2026-09-18 and 2026-10-18 the board stayed up through the crank (the
starter's 200-250 rpm is in the samples), so the 12 V rail held above 6.5 V. On
the cold start of 2026-10-19 — head 4 °C, oil near the night's 0 °C, the Thunder
Stroke 111 at its heaviest — it did not: rpm 208 at 11:07:50.5, then a 17.5 s
hole, then "[mqtt] connected" with the message counter restarted, the ABS module
logging undervoltage in the same second. The dip went under 6.5 V for long
enough to drop the LM5164.

So the design is right for the riding season and wrong for a frosty morning,
and the review's own answer stands: **rev 2 gets a regulator with a lower
minimum input**, to the automotive cold-crank profile (ISO 16750-2: 4.5 V for
15 ms, 6 V for 1 s) — a buck-boost such as TI's LM5176 or TPS55288. Considered
and set aside: lowering UVLO to the LM5164's 6 V floor (under 1 V of margin, not
enough for what was seen); 10 000 µF of hold-up on the 12 V side (a 16 × 25 mm
can plus a diode, no room on 55 × 32 mm); a 0.47 F supercap on 3.3 V (about one
second, and this dip lasted longer). Until rev 2 the reboot is tolerated — the
wake path handles it, it costs 15 s of data and a BLE reconnect — and counted:
`CanBus_Start_Resets` on the SpringCommand page, with the temperatures. Stage 2
firmware adds the chip's own reset reason to `meta`, so a brownout is read, not
inferred. The bike winters indoors at 25 °C, so the count mostly matters in the
weeks before and after.

## 2. Cellular on the bike — four shapes compared

For the firmware, three of the four are identical: one more SSID in the list,
which the `YOUR_SSID` slot in `config.h` is already reserved for. The
differences are power, robustness and how fast the network is up after wake.

| | 4G WiFi stick on USB | 4G router (RUTM50 / RUT241) | Cat-1 module on the board (rev 2) | USB dongle on the S3 |
|---|---|---|---|---|
| Board / firmware change | none | none | second buck, load switch, antenna, `PPP.h` | USB host + PPP, shares the flash-path pins |
| Power, running | 1–2 W | 3–5 W | 1–2 W in bursts | 5 V, up to 1 A |
| Power, sleeping | 0 if ignition-switched | 0 if ignition-switched | 0, load switch | — |
| Ready after start | ~20–30 s | 60–90 s | ~15 s | ~20 s |
| Temperature | consumer, typ. 0–40 °C | industrial, −40…75 °C | module −40…85 °C | consumer |
| Mounting | USB plug, vibrates | box, ~100 × 70 mm | on the board | USB plug |
| Cost | ~300 DKK | 2 000–4 000 DKK | ~100 DKK in parts | ~300 DKK |

**Why not a USB dongle on the S3:** the S3's USB is full-speed OTG; ESP-IDF's
USB host has a CDC-ACM class driver but no RNDIS/ECM host, so it must be a
dongle in "stick mode" speaking AT/PPP over serial — and most cheap dongles
(Huawei HiLink type) are exactly ECM/RNDIS. The USB pins are also the first
flash path (USB-Serial-JTAG). Consumer housing, 5 V, 1 A bursts, in a plug.
Wrong shape on this chip; on the P4 the same objection applies apart from
speed.

**The right shape on the S3 is a Cat-1 module on UART:** SIMCom A7670E or
Quectel EC200U-EU / EG915U-EU — LTE Cat-1 bis, European bands, one antenna,
24 × 24 mm LGA, 10–15 €; what LilyGO's T-A7670 / T-SIM boards use. arduino-esp32
3.x has `PPP.h` on top of esp_modem with A7670/SIM7600 supported, so it fits
the current firmware rather than a rewrite. LTE-M would be kinder on power but
road coverage is not LTE's, and ~5 kB/s of state JSON is near its edge.

What the module design needs, and it is the whole design:

- **Its own rail.** 3.4–4.2 V, 2 A peaks during registration and transmit. A
  second buck at ~3.8 V, 2–3 A, still 36 V-tolerant (LMR33630 or similar), with
  470–1000 µF bulk at the module.
- **A load switch** so the module is fully unpowered in sleep. Modem PSM alone
  does not reach the sleep currents just won.
- **Antenna**: a second U.FL pigtail, 800–2600 MHz, placed like the WiFi one.
- **SIM**: eSIM (MFF2, soldered, no mechanics) rather than a nano-SIM holder,
  on a vibrating vehicle.
- **Timeline**: CAN wake → module powered → registration 10–20 s → MQTT up
  before the end of the driveway. Faster than the 55 s WiFi failover measured
  on 2026-09-15, with no hotspot to remember. LTE always resolves publicly, so
  the DNS-cache class of fault is gone by construction.

**Rev 1.1 is not the place for it.** Rev 1.1 is for what the bench finds on
rev 1.0; adding a modem, a buck, a load switch, an eSIM and an antenna in the
same revision doubles the suspects when something does not work. What rev 1.1
*should* do is **prepare**: a pad row with VBAT, GND, UART TX/RX, PWRKEY and an
enable line on spare GPIOs (there are over twenty), so a modem daughter board
(a T-A7670E-class module) can be wired on with five wires and tried in the
firmware while the main board stays unchanged and proven. If it works and the
power profile is known, the module moves onto the board in rev 2 with a proper
3.8 V rail.

### Recommendation, in order

*What happened since (2026-10-04):* the RUTM50 router is on the bike, and the
module of point 2 is being tried as a HAT on bench board #0002 before any board
change (IDEAS.md B14). OTA
no longer depends on the uplink being WiFi at home: since firmware 2026.10.04-1
the image can come over the MQTT link (OTA.md). The recommendation itself is
left as it was written.

1. **4G WiFi stick now** (Huawei E8372h-320 or ZTE MF79U on an ignition-switched
   5 V USB outlet) — as a trial, to give the bike its own uplink and a data
   point on real LTE coverage along the usual routes, while rev 1.0 is brought
   up. Fine for a season; not a permanent answer (consumer parts in a plug,
   under a seat, in the sun).
2. If the conclusion is that the bike needs its own uplink permanently: **the
   module, in rev 2** — not the router. The router (the original RUTM50 plan)
   is the right box for a vehicle with room and a 12 V bus that is not a
   motorcycle battery: 3–5 W continuous must be ignition-switched, and then
   it costs 60–90 s at every start, slower than today's failover.
3. Any of them needs a data plan: a Danish IoT/M2M SIM with a few hundred MB a
   month is plenty — state JSON at 5 Hz is under 20 MB an hour.

An integrated uplink removes the need for the RUTM50, for the phone hotspot,
and for most of the WiFi handling in `net.cpp` on the road.

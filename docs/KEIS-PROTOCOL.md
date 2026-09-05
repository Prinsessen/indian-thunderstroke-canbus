# Keis controller BLE protocol

Recovered from **Keis iControl** (`com.propagation.keis2`) by decompiling the
APK, which under the EU Software Directive's interoperability provision is a
permitted purpose — and which turned out to be both faster and more reliable
than sniffing packets. A capture shows what was sent once; the source constants
show what the firmware was written to accept.

Implemented by [`KeisBleDevice.kt`](app/src/main/java/dk/agesen/springfield/KeisBleDevice.kt).

---

## Identification

| | |
|---|---|
| Advertised name | `KEIS HEATED CLOTHING` |
| MAC prefix | `00:1E:C0:5` |

The controllers do **not** advertise their service UUID, so scanning filters on
the address prefix — which is what their own app does. A jacket controller and a
trouser controller are indistinguishable over the air; the rider assigns which
is which and the app remembers the addresses.

## GATT

| Role | UUID |
|---|---|
| Service | `00035b03-58e6-07dd-021a-08123a000300` |
| Characteristic (write **and** notify) | `00035b03-58e6-07dd-021a-08123a000301` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

One characteristic carries both directions.

## Commands — a single byte, written

The values are **ASCII digits**. Whoever designed this meant it to be readable
on a terminal.

| Command | Byte | ASCII | Meaning |
|---|---|---|---|
| `DEVICE_LEVEL_OFF` | `0x30` | `'0'` | Off |
| `DEVICE_LEVEL_LOW` | `0x32` | `'2'` | Green — 33 % |
| `DEVICE_LEVEL_MEDIUM` | `0x34` | `'4'` | Amber — 66 % |
| `DEVICE_LEVEL_HIGH` | `0x36` | `'6'` | Red — 100 % |
| `DEVICE_CMD_GET_STATE` | `0x37` | `'7'` | Ask the current level |
| `DEVICE_CMD_CONFIRM_ADDING` | `0xA0` | | Enrolment handshake |
| `DEVICE_CMD_CONFIRM_SUCCESS` | `0xA1` | | Enrolment handshake |
| `DEVICE_CMD_DISCONNECT` | `0xF1` | | Ask the controller to drop the link |

Their app writes exactly one byte: `characteristic.setValue(new byte[]{ level })`
then `writeCharacteristic`. Nothing is framed, checksummed or length-prefixed.

**Only these four level constants are ever sent.** Nothing computes or
interpolates a byte — a heating element is not somewhere to discover that a
scale was not linear after all.

## Notifications

The reply arrives on the same characteristic.

- **Byte 0** — the level, echoed back.
- **Byte 1**, in a reply to `0x37` — the current level, except **`0xFF` means
  off** rather than `0x30`. That is the one irregularity in an otherwise tidy
  protocol, and the app has to special-case it.

The controller reports **no battery level**, so the app shows none rather than
inventing one.

## No BLE bonding

There is no `createBond` anywhere in their app. The "press the button on the
controller to confirm pairing" step in the manual is an **application-level**
handshake — `0xA0` / `0xA1` — performed only when a controller is first added.
An already-enrolled controller accepts a plain GATT connection, which is why the
driver here has no pairing code at all.

That also means **the controllers were enrolled through iControl**, and this app
connects to them as a second client rather than replacing that enrolment.

## What is still unknown

- Whether a controller accepts two centrals at once, or whether iControl has to
  be closed. Most BLE peripherals hold one connection; assume it must be closed
  until proven otherwise.
- What a controller does when the phone disappears: hold the last level, or fall
  to off. That answer decides whether a dropped link is an inconvenience or a
  cold hour, and only a ride will tell.


## The controller has to be switched on by hand

A controller that has just been given power advertises nothing. It cannot be
scanned for, connected to, or woken over the air — by this app or by Keis
iControl, which was tested on the same hardware and needs the same press. There
is no command for it in the protocol, and there could not be: a radio that is
off cannot be told to come on.

Pressing any level button brings it up. From then on the app has full control,
including switching the heat off with 0x30 — which stops the heat while leaving
the controller awake and connected, so it can be turned back on from the phone.
Pressing the physical button until it is off takes the radio with it.

So the rider's routine is: press them on when the kit goes on. Everything after
that is the app's.

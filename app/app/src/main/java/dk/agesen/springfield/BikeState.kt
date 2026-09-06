package dk.agesen.springfield

import org.json.JSONObject

/**
 * Wire decoders for the bike's BLE protocol.
 *
 * The single rule that shapes everything here: **absent is not zero.** Every
 * field is nullable, and null means "the bus has not told us". A dashboard that
 * renders unknown as 0 is worse than one that renders nothing — it looks
 * confident while being wrong.
 *
 * Spec: indian-canbus/PROTOCOL.md in the openHAB repo.
 */

/**
 * Byte 6/7 bit positions. Same index in both bytes.
 *
 * Bits 0 and 5 held the front brake and the horn until both were retired
 * 2026-09-05 -- there is one brake signal on this bus and either control works
 * it, and the horn is not on the bus at all. They now carry the cruise rocker.
 *
 * Firmware and app must ship together for that reason: a build older than
 * 2026.09.05-41 reads bit 0 as a front brake and bit 5 as a horn.
 */
private const val BIT_CRUISE_SET = 0
private const val BIT_BRAKE_REAR = 1
private const val BIT_CRUISE = 2
private const val BIT_IND_LEFT = 3
private const val BIT_IND_RIGHT = 4
private const val BIT_CRUISE_RES = 5
private const val BIT_CRUISE_ENABLE = 6
private const val BIT_HAZARD = 7

/**
 * The 8-byte `fast` characteristic — rpm, speed, throttle, gear and the switch
 * flags, pushed ~10x/sec. Fixed length, so it survives the default 23-byte MTU.
 */
data class FastPacket(
    val rpm: Int?,
    val speedKmh: Double?,
    val throttlePct: Int?,
    val gear: String?,
    val brakeRear: Boolean?,
    /**
     * The rocker, in the fast packet rather than only the 1 Hz JSON: a press
     * lasts about a second, so at 1 Hz a tap can fall between two publishes and
     * never be seen. SPN 599 and 601.
     */
    val cruiseSet: Boolean?,
    val cruiseRes: Boolean?,
    /** SPN 595 -- cruise actually holding a speed. Green on the bike's own dash. */
    val cruise: Boolean?,
    /** SPN 596 -- the rocker is on but no speed is set. Amber on the dash. */
    val cruiseEnable: Boolean?,
    val indLeft: Boolean?,
    val indRight: Boolean?,
    val hazard: Boolean?
) {
    companion object {
        const val LENGTH = 8

        private const val U16_UNKNOWN = 0xFFFF
        private const val U8_UNKNOWN = 0xFF

        /** Returns null if the payload is not exactly 8 bytes. */
        fun parse(b: ByteArray): FastPacket? {
            if (b.size != LENGTH) return null

            fun u8(i: Int) = b[i].toInt() and 0xFF
            // Little-endian, as the firmware writes it.
            fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)

            val rawRpm = u16(0)
            val rawSpeed = u16(2)
            val rawThrottle = u8(4)
            val rawGear = u8(5)
            val flags = u8(6)
            val valid = u8(7)

            // A flag is only meaningful when its validity bit is set. Without
            // this check the app cannot distinguish "brake released" from "no
            // brake message has ever arrived".
            fun flag(bit: Int): Boolean? =
                if ((valid shr bit) and 1 == 1) ((flags shr bit) and 1) == 1 else null

            return FastPacket(
                rpm = if (rawRpm == U16_UNKNOWN) null else rawRpm,
                speedKmh = if (rawSpeed == U16_UNKNOWN) null else rawSpeed / 10.0,
                throttlePct = if (rawThrottle == U8_UNKNOWN) null else rawThrottle,
                gear = if (rawGear == 0) null else rawGear.toChar().toString(),
                brakeRear = flag(BIT_BRAKE_REAR),
                cruiseSet = flag(BIT_CRUISE_SET),
                cruiseRes = flag(BIT_CRUISE_RES),
                cruise = flag(BIT_CRUISE),
                cruiseEnable = flag(BIT_CRUISE_ENABLE),
                indLeft = flag(BIT_IND_LEFT),
                indRight = flag(BIT_IND_RIGHT),
                hazard = flag(BIT_HAZARD)
            )
        }
    }
}

/**
 * The `state` characteristic — the full JSON at ~1 Hz.
 *
 * The firmware omits a key entirely when the value is unavailable, so on a
 * silent bus the payload is literally `{}`. That is valid, not an error.
 *
 * `vin` and `softwareId` exist on the MQTT path but are deliberately withheld
 * from BLE, so they are absent here by design.
 */
data class BikeJsonState(
    val rpm: Int? = null,
    val throttlePct: Int? = null,
    val gear: String? = null,
    val coolantC: Int? = null,
    val speedKmh: Double? = null,
    val fuelPct: Int? = null,
    val odometerKm: Int? = null,
    /**
     * Odometer reading at the last service, as the *bike* remembers it.
     *
     * Absent from every firmware built so far, and harmless when absent — the
     * app falls back to its own setting. It is declared now so that the day the
     * ESP32 starts reporting it, the app already prefers it with no change here
     * and no second migration.
     */
    val serviceKm: Int? = null,
    val tripKm: Double? = null,
    val fuelEconomy: Double? = null,
    val batteryV: Double? = null,
    val ambientC: Double? = null,
    val tyreFrontPsi: Double? = null,
    val tyreRearPsi: Double? = null,
    val tyreFrontTempC: Double? = null,
    val tyreRearTempC: Double? = null,
    val brakeRear: String? = null,
    val cruise: String? = null,
    val cruiseEnable: String? = null,
    /** "SET/DEC" or "RES/ACC" -- the legend printed on the rocker itself. */
    val cruiseSw: String? = null,
    val hazard: String? = null,
    /**
     * "OK", "SEARCHING" or "NOT FOUND" -- the security system's view of the key
     * fob, from PGN 65386 SA 39 byte 0 bits 6-7. NOT FOUND means the bike is
     * about to shut down and the dash shield is flashing.
     */
    val security: String? = null,
    val headlight: String? = null,
    val indLeft: String? = null,
    val indRight: String? = null,
    /** Heated grips, 0 = off through 10. Present from firmware 2026.09.04-2. */
    val gripLevel: Int? = null,
    /**
     * Grip temperature, one sensor per grip, from 2026.09.04-4.
     *
     * The sides were settled on the bike by holding a bare hand on the left
     * grip with the heat off: the left reading rose 18 to 22 C in a minute
     * while the right did not move. The inference before that test had them
     * the other way round, which is why it was worth a minute.
     */
    val gripLeftC: Double? = null,
    val gripRightC: Double? = null,
    /**
     * "UPRIGHT" / "STAND" / "DOWN", absent while moving.
     *
     * Derived on the bike from the tip-over sensor. It answers a different
     * question from the switch below: not where the stand is, but whether the
     * machine is resting on it.
     *
     * The comment here used to say this bus carries no sidestand switch at all.
     * It does -- see standDown. The claim was wrong for three weeks because the
     * hunt that produced it had thirty bytes masked out of the probe.
     */
    val stand: String? = null,
    /**
     * "DOWN" / "UP" -- the sidestand switch itself, PGN 65381 SA 0 byte 7 bit 0.
     *
     * The ECU knows it because it cuts the engine with the stand down and in
     * gear. Confirmed 2026-09-06 against the cluster's own red lamp.
     */
    val standDown: String? = null,
    /**
     * "RUN" / "STOP" -- the red run/stop switch on the right bar, PGN 65381
     * SA 0 byte 4 bit 6, set for RUN.
     *
     * Absent with the ignition off, because the ECU is not transmitting 65381
     * then. Found 2026-09-06.
     */
    val killSwitch: String? = null,
    /** L/h, and instantaneous economy against the running average. */
    val fuelRate: Double? = null,
    val fuelEconInst: Double? = null,
    /** Front wheel speed from the ABS module -- the honest one. */
    val speedFrontKmh: Double? = null,
    /** "OK" / "FRONT LOST" / "REAR LOST", and the brief-dropout counters. */
    val wheels: String? = null,
    val wheelBlips: Int? = null,
    val wheelBlipsRear: Int? = null,
    /** "ON"/"OFF" -- bus activity, which is the ignition. */
    val ignition: String? = null,
    val dm1: String? = null,
    val dm1Raw: String? = null,
    /** Firmware version, present from 2026.09.02-4 onwards. */
    val fw: String? = null
) {
    /** Ignition as a boolean; null when the bike has never reported. */
    val ignitionOn: Boolean? get() = ignition?.let { it == "ON" }

    /** True when the engine is turning the alternator. */
    val charging: Boolean? get() = batteryV?.let { it > 13.5 }

    companion object {
        /**
         * The BLE payload uses two-letter keys.
         *
         * A GATT notification carries 514 bytes and cannot fragment, and with
         * long names the payload reached 560 on an ordinary ride with the tyres
         * reporting -- over the limit, silently, the app simply ceasing to
         * update while the cluster carried on from the binary frame. JSON
         * spends most of itself on its own field names: "wheelBlipsRear" is
         * fourteen characters to introduce a number.
         *
         * Two letters takes the worst case to 388. openHAB keeps the long names
         * over MQTT, where there is no limit and a human reads them, so nothing
         * there changed. Both sides were generated from one list.
         */
        fun parse(json: String): BikeJsonState? {
            val o = try {
                JSONObject(json)
            } catch (e: Exception) {
                // A truncated payload lands here. The usual cause is subscribing
                // to `state` before the MTU was raised — see requestMtu() in
                // BikeBleClient and the MTU warning in PROTOCOL.md.
                return null
            }

            fun i(k: String) = if (o.has(k)) o.optInt(k) else null
            fun d(k: String) = if (o.has(k)) o.optDouble(k) else null
            fun s(k: String) = if (o.has(k)) o.optString(k) else null

            return BikeJsonState(
                rpm = i("r"),
                throttlePct = i("th"),
                gear = s("g"),
                // "ot" is a leftover misnomer -- this is CYLINDER HEAD
                // temperature, not oil. PAIRED CHANGE: the firmware's
                // doc[K("coolant","ot")] must move in the same release, or one
                // side sends a key the other does not read.
                coolantC = i("ot"),
                speedKmh = d("sp"),
                fuelPct = i("fl"),
                odometerKm = i("od"),
                serviceKm = i("sk"),
                tripKm = d("tp"),
                fuelEconomy = d("fe"),
                batteryV = d("bv"),
                ambientC = d("am"),
                tyreFrontPsi = d("tf"),
                tyreRearPsi = d("tr"),
                tyreFrontTempC = d("tft"),
                tyreRearTempC = d("trt"),
                brakeRear = s("br"),
                cruise = s("cc"),
                cruiseEnable = s("ce"),
                cruiseSw = s("cs"),
                hazard = s("hz"),
                security = s("se"),
                headlight = s("hl"),
                indLeft = s("il"),
                indRight = s("ir"),
                gripLevel = i("gr"),
                gripLeftC = d("gl"),
                gripRightC = d("gR"),
                stand = s("st"),
                standDown = s("sd"),
                killSwitch = s("ks"),
                fuelRate = d("fr"),
                fuelEconInst = d("fi"),
                speedFrontKmh = d("sf"),
                wheels = s("wh"),
                wheelBlips = i("wf"),
                wheelBlipsRear = i("wr"),
                ignition = s("ig"),
                dm1 = s("d1"),
                dm1Raw = s("dr"),
                fw = s("fw")
            )
        }
    }
}

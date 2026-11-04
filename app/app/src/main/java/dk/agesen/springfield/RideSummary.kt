package dk.agesen.springfield

import android.content.Context
import android.content.SharedPreferences

/**
 * What the ride amounted to, gathered for the card that comes up at key-off.
 *
 * The owner, 2026-11-04: the REAR SLIP line is small print, a slip is over before
 * anyone could read it, and the only time the figure is any use is afterwards - so
 * not a bigger line, but one card with the whole ride on it, at the moment the
 * key goes off, readable from the saddle. "Det vil være en kæmpe improvement."
 *
 * The ride is [RideTrip]'s ride: a fuel stop, lunch and a ferry are inside it, the
 * next one starts after three hours' sleep (her choice, the same day). That is why
 * everything here is gathered across ignitions and kept in storage - the board
 * starts its slip min / max again at every key-on, and the app may be killed at
 * any stop. Distance, moving time, top speed and top rpm are [Settings] and
 * [RideStats]; this holds what nothing else did:
 *
 *  - rear slip, the lowest and highest the board sent over every leg;
 *  - every fault code that stood while the ignition was on;
 *  - how long the board was without the broker - only stretches of
 *    [NetWatch.GRACE_MS] or more, the same rule as the NO NETWORK banner, so a
 *    wake (10-15 s to join) or a handover (~1 min) is not "without network";
 *  - fuel used, from the filtered level ([FuelLevel], taken only while moving
 *    upright, whole per cent): the bike's own `fe` is its lifetime average, not
 *    this ride's. A rise of [REFILL_PCT] or more is a fill-up and is not counted
 *    against the ride.
 *
 * The card's own state: [pending] is set when the ignition is seen going ON->OFF
 * after the ride has moved, and cleared by a tap, the next ON, or a new ride.
 *
 * Pure apart from storage: the caller passes the time and the readings, so the
 * JUnit test holds it without a phone ([init] never runs there).
 */
object RideSummary {

    /** A rise this large between two filtered readings is a fill-up. */
    const val REFILL_PCT = 5

    /** Fewer per cent than this gives no economy figure: 1 % is 0.2 l of a 20.8 l tank. */
    const val MIN_FUEL_PCT = 3

    /** Below this the ride is too short to say l/100 about. */
    const val MIN_ECONOMY_KM = 10.0

    /** A gap in the fast packets longer than this ends a network stretch (the link dropped). */
    private const val NET_GAP_MS = 5_000L

    // ------------------------------------------------------------- state

    @Volatile var slipMin: Double? = null
        private set
    @Volatile var slipMax: Double? = null
        private set
    private val faults = linkedSetOf<String>()          // "spn:fmi", in the order seen
    @Volatile var noNetMs: Long = 0L
        private set
    @Volatile var netKnown: Boolean = false
        private set
    @Volatile var fuelUsedPct: Int = 0
        private set
    private var fuelLastPct: Int? = null
    /** The ride's distance as last seen, kept for a card shown after an app restart with the bike asleep. */
    @Volatile var distanceKm: Double? = null
        private set
    @Volatile var startedAt: Long = 0L
        private set
    @Volatile var endedAt: Long = 0L
        private set
    @Volatile var pending: Boolean = false
        private set

    // in memory only: a stretch, the last ignition seen, the last fast packet
    private var lostSince = 0L
    private var lastNetAt = 0L
    private var ignitionOn: Boolean? = null

    /** Called when [pending] changes, so the card can show or hide at once. */
    @Volatile var onChange: (() -> Unit)? = null

    val faultList: List<Dtc.Fault>
        @Synchronized get() = faults.mapNotNull { k ->
            val spn = k.substringBefore(':').toLongOrNull()
            val fmi = k.substringAfter(':').toIntOrNull()
            if (spn == null || fmi == null) null else Dtc.Fault(spn, fmi, 1)
        }

    // ------------------------------------------------------------- feeding

    /** Every fast packet: slip and network. */
    @Synchronized
    fun onFast(slipMinKmh: Double?, slipMaxKmh: Double?, net: Int?, nowMs: Long) {
        var changed = false
        if (slipMinKmh != null && slipMaxKmh != null) {
            val lo = slipMin; val hi = slipMax
            if (lo == null || slipMinKmh < lo) { slipMin = slipMinKmh; changed = true }
            if (hi == null || slipMaxKmh > hi) { slipMax = slipMaxKmh; changed = true }
        }

        // a dropped link ends a stretch where it was last heard, not now
        if (lastNetAt != 0L && nowMs - lastNetAt > NET_GAP_MS) changed = closeStretch(lastNetAt) || changed
        lastNetAt = nowMs
        if (net != null) {
            if (!netKnown) { netKnown = true; changed = true }
            if (net == NetWatch.HOME || ignitionOn != true) changed = closeStretch(nowMs) || changed
            else if (lostSince == 0L) lostSince = nowMs
        }
        if (changed) save()
    }

    /**
     * Every state (1 Hz).
     *
     * [fuelPct] is the filtered level only while it is this ride's ([FuelLevel.live]),
     * null otherwise. [moved] is whether the ride has covered any ground (the caller
     * asks [RideStats]). [rideKm] is the ride's distance, written with the rest
     * at key-off. Returns true when the ignition has just gone off, so the caller
     * can write what it keeps in memory.
     */
    @Synchronized
    fun onState(ignition: Boolean?, dm1: String?, fuelPct: Int?, moved: Boolean, rideKm: Double?, nowMs: Long): Boolean {
        var changed = false
        var wentOff = false
        if (rideKm != null) distanceKm = rideKm

        if (ignition == true) {
            for (f in Dtc.parse(dm1)) if (faults.add("${f.spn}:${f.fmi}")) changed = true
        }

        if (fuelPct != null && fuelPct in 0..100) {
            val last = fuelLastPct
            if (last == null || fuelPct - last >= REFILL_PCT) {
                fuelLastPct = fuelPct; changed = true          // first reading, or a fill-up
            } else if (fuelPct < last) {
                fuelUsedPct += last - fuelPct; fuelLastPct = fuelPct; changed = true
            }
        }

        val was = ignitionOn
        if (ignition != null && ignition != was) {
            ignitionOn = ignition
            if (was == true && !ignition) {
                wentOff = true
                closeStretch(nowMs)
                if (moved) { endedAt = nowMs; setPending(true) }
                changed = true
            } else if (ignition && pending) {
                setPending(false); changed = true
            }
        }
        if (changed) save()
        return wentOff
    }

    /** The rider asked for it (a tap on the RIDE line). */
    fun open() { setPending(true); save() }

    /** A tap on the card. */
    fun dismiss() { setPending(false); save() }

    /** A new ride: everything here belongs to the old one. */
    @Synchronized
    fun reset(nowMs: Long) {
        slipMin = null; slipMax = null
        faults.clear()
        noNetMs = 0L; netKnown = false; lostSince = 0L; lastNetAt = 0L
        fuelUsedPct = 0; fuelLastPct = null
        distanceKm = null
        startedAt = nowMs; endedAt = 0L
        setPending(false)
        save()
    }

    /** Fuel used this ride as l/100 km, or null when there is too little of either to say. */
    fun economyL100(rideKm: Double?, tankLitres: Double): Double? {
        if (rideKm == null || rideKm < MIN_ECONOMY_KM || fuelUsedPct < MIN_FUEL_PCT) return null
        return fuelUsedPct / 100.0 * tankLitres * 100.0 / rideKm
    }

    private fun closeStretch(endMs: Long): Boolean {
        val since = lostSince
        if (since == 0L) return false
        lostSince = 0L
        val length = endMs - since
        if (length < NetWatch.GRACE_MS) return false
        noNetMs += length
        return true
    }

    private fun setPending(v: Boolean) {
        if (pending == v) return
        pending = v
        onChange?.invoke()
    }

    // ------------------------------------------------------------- storage

    private const val PREFS = "ride_summary"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        synchronized(this) {
            fun d(k: String) = p.getFloat(k, Float.NaN).toDouble().takeIf { !it.isNaN() }
            slipMin = d("slip_min"); slipMax = d("slip_max")
            faults.clear(); p.getString("faults", "")!!.split(',').filter { it.isNotBlank() }.forEach { faults.add(it) }
            noNetMs = p.getLong("no_net_ms", 0L); netKnown = p.getBoolean("net_known", false)
            fuelUsedPct = p.getInt("fuel_used", 0); fuelLastPct = p.getInt("fuel_last", -1).takeIf { it >= 0 }
            distanceKm = d("distance_km")
            startedAt = p.getLong("started", 0L); endedAt = p.getLong("ended", 0L)
            pending = p.getBoolean("pending", false)
        }
    }

    private fun save() {
        val p = prefs ?: return
        p.edit()
            .putFloat("slip_min", (slipMin ?: Double.NaN).toFloat())
            .putFloat("slip_max", (slipMax ?: Double.NaN).toFloat())
            .putString("faults", synchronized(this) { faults.joinToString(",") })
            .putLong("no_net_ms", noNetMs).putBoolean("net_known", netKnown)
            .putInt("fuel_used", fuelUsedPct).putInt("fuel_last", fuelLastPct ?: -1)
            .putFloat("distance_km", (distanceKm ?: Double.NaN).toFloat())
            .putLong("started", startedAt).putLong("ended", endedAt)
            .putBoolean("pending", pending)
            .apply()
    }

    /** For the test: as after a fresh install. */
    internal fun clearForTest() {
        reset(0L); startedAt = 0L; ignitionOn = null; onChange = null
    }
}

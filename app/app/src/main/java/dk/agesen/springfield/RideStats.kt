package dk.agesen.springfield

import android.content.Context
import android.content.SharedPreferences

/**
 * What this ride has amounted to so far.
 *
 * openHAB keeps the history; this keeps the few figures a rider wants at the
 * end of a road without going and looking them up — top speed, average while
 * actually moving, and how long that took.
 *
 * **Average is over moving time, not elapsed time.** An average that counts
 * twenty minutes at a ferry queue is a number about the day, not about the
 * ride, and it is the wrong one to put next to a top speed.
 *
 * **Kept across an app restart since 2026-11-04**, and reset with the ride
 * ([RideTrip.newRide]). Until then this was memory only ("a ride is a session"),
 * which was true of the RIDE line and wrong for the card at key-off
 * ([RideSummary]): the owner chose the whole ride - a fuel stop, lunch, a ferry
 * inside it, three hours' sleep ends it - and the app is killed freely at any
 * of those stops. It was also never reset by a new ride, only by the button in
 * settings or by the app dying, so a top speed could outlive the ride it was set
 * on. Written at most every [PERSIST_EVERY_MS] (a top speed moves ten times a
 * second while accelerating) and at key-off ([flush]).
 */
object RideStats {

    /** Below this the bike is stopped, not crawling. */
    private const val MOVING_KMH = 3.0

    /** Ignore gaps longer than this — a dropped link is not riding time. */
    private const val MAX_GAP_MS = 3_000L

    /**
     * Above this, believe the bus rather than the bike — and disbelieve both.
     *
     * 0xFFFF is the protocol's "unknown" and is already filtered out, but a
     * corrupted frame decoding to, say, 640 km/h is not, and a top speed is a
     * high-water mark: one bad packet would poison the figure for the whole ride
     * with no way to correct it short of a reset. A Springfield does not do 250.
     */
    private const val IMPLAUSIBLE_KMH = 250.0

    /** Likewise for engine speed — well past any redline this engine has. */
    private const val IMPLAUSIBLE_RPM = 8000

    @Volatile var maxSpeedKmh: Double = 0.0
        private set

    @Volatile var maxRpm: Int = 0
        private set

    @Volatile private var movingMillis: Long = 0L
    @Volatile private var lastSampleAt: Long = 0L
    @Volatile private var savedAt: Long = 0L

    /** Time spent moving this ride, ms. */
    val movingMs: Long get() = movingMillis

    private const val PERSIST_EVERY_MS = 30_000L
    private const val PREFS = "ride_stats"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        maxSpeedKmh = p.getFloat("max_speed", 0f).toDouble()
        maxRpm = p.getInt("max_rpm", 0)
        movingMillis = p.getLong("moving_ms", 0L)
    }

    /** Write what is held, now (key-off). */
    fun flush() {
        savedAt = System.currentTimeMillis()
        prefs?.edit()?.putFloat("max_speed", maxSpeedKmh.toFloat())?.putInt("max_rpm", maxRpm)
            ?.putLong("moving_ms", movingMillis)?.apply()
    }

    fun feed(p: FastPacket) {
        val now = System.currentTimeMillis()
        val speed = p.speedKmh

        if (speed != null && speed > maxSpeedKmh && speed <= IMPLAUSIBLE_KMH) {
            maxSpeedKmh = speed
            // The all-time record only ever moves through a ride record, so the
            // same plausibility bound protects both — one check, not two that
            // could drift apart.
            if (speed > Settings.bestSpeedKmh) Settings.bestSpeedKmh = speed
        }
        p.rpm?.let {
            if (it > maxRpm && it <= IMPLAUSIBLE_RPM) {
                maxRpm = it
                if (it > Settings.bestRpm) Settings.bestRpm = it
            }
        }

        if (speed != null && speed in MOVING_KMH..IMPLAUSIBLE_KMH && lastSampleAt != 0L) {
            val gap = now - lastSampleAt
            // A long gap means the link dropped, not that the bike stood still
            // for that long with the engine reporting — either way it is not
            // time this ride can claim.
            if (gap in 1..MAX_GAP_MS) movingMillis += gap
        }
        lastSampleAt = now
        if (now - savedAt >= PERSIST_EVERY_MS) flush()
    }

    fun reset() {
        maxSpeedKmh = 0.0
        maxRpm = 0
        movingMillis = 0L
        lastSampleAt = 0L
        flush()
    }

    /** Average over moving time, or null before anything has moved. */
    fun averageKmh(distanceKm: Double?): Double? {
        if (distanceKm == null || movingMillis < 30_000L) return null
        val hours = movingMillis / 3_600_000.0
        if (hours <= 0.0) return null
        return distanceKm / hours
    }

    /** "1:24" — hours and minutes of movement, or null before there are any. */
    fun movingTime(): String? {
        if (movingMillis < 60_000L) return null
        val minutes = movingMillis / 60_000
        return "%d:%02d".format(minutes / 60, minutes % 60)
    }
}

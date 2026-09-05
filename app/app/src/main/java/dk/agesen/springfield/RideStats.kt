package dk.agesen.springfield

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
 * Held in memory only. A ride is a session; carrying half of one across an app
 * restart would produce a figure that quietly means nothing.
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
    }

    fun reset() {
        maxSpeedKmh = 0.0
        maxRpm = 0
        movingMillis = 0L
        lastSampleAt = 0L
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

package dk.agesen.springfield

import kotlin.math.pow

/**
 * Turning what the bike knows into how warm the clothing should be.
 *
 * This is the whole idea, and none of it depends on the Keis protocol — it is
 * arithmetic over ambient temperature and road speed, both of which the bike
 * already reports. Keis iControl cannot do any of it, not because their app is
 * poor but because it has no idea you are moving.
 */
object HeatCurve {

    /** The two zones the app can reach. Gloves are not among them. */
    enum class Zone { LEGS, JACKET }

    /**
     * What a Keis controller actually offers: three levels and off.
     *
     * Not a percentage. The controller has three positions with their own
     * colours, and modelling it as 0-100 would invite the app to ask for 45%,
     * which does not exist — the driver would then round, and the rider would
     * see a level they never chose.
     */
    enum class Level(val percent: Int, val label: String) {
        OFF(0, "OFF"),
        LOW(33, "LOW"),
        MEDIUM(66, "MED"),
        HIGH(100, "HIGH")
    }

    /**
     * Wind chill, Environment Canada's relation, metric.
     *
     * Only defined for **T ≤ 10 °C and v ≥ 4.8 km/h**, and outside that it does
     * not merely lose accuracy — it starts returning values above ambient, which
     * would have the app cooling the rider down as the day warms up. Beyond its
     * bounds the honest answer is the ambient temperature itself.
     */
    fun feelsLike(ambientC: Double?, speedKmh: Double?): Double? {
        val t = ambientC ?: return null
        val v = speedKmh ?: return t
        if (t > 10.0 || v < 4.8) return t
        val f = v.pow(0.16)
        return 13.12 + 0.6215 * t - 11.37 * f + 0.3965 * t * f
    }

    /**
     * How far below the boundary the temperature must fall to step up, and how
     * far above to step back down.
     *
     * With only three levels the boundaries are several degrees apart, and felt
     * temperature moves by more than that every time you slow for a village. A
     * band around each boundary is what stops the controller flipping between
     * green and amber for the length of a ride.
     */
    private const val HYSTERESIS_C = 1.5

    /**
     * The level for a felt temperature, given where the level currently sits.
     *
     * `current` is not a suggestion — it is what makes this stable. The
     * boundaries move against the direction of travel, so warming up has to
     * exceed the point where cooling down switched, and neither happens twice
     * on the same degree.
     */
    fun levelFor(zone: Zone, feltC: Double?, current: Level?): Level? =
        levelFor(Settings.heatOffAt(zone), Settings.heatFullAt(zone), feltC, current)

    /**
     * The same decision, with the curve handed in rather than fetched.
     *
     * Split out so the arithmetic can be tested without a phone. Reaching into
     * Settings from inside the calculation made this whole file untestable, and
     * that is how "full at 10" was shipped reaching full heat at 15: the check
     * that would have caught it needed an Android device to run, so it was never
     * written. Four lines of test now cover it.
     */
    fun levelFor(offAt: Double, fullAt: Double, feltC: Double?, current: Level?): Level? {
        val felt = feltC ?: return null
        if (offAt <= fullAt) return null            // nonsensical curve; do nothing

        // The two settings are the two ends, and they mean what they say:
        // nothing above "off at", full heat at or below "full at". LOW and
        // MEDIUM split what is between them.
        //
        // It used to divide the span into three, which put full heat two thirds
        // of the way down — 15 degrees for a curve set to 25/10. The rider had
        // asked for full heat at 10 and got it at 15, so every setting ran one
        // step hotter than configured through the middle of its range, and the
        // "full at" number named a temperature the curve never used.
        val lowAt = offAt
        val highAt = fullAt
        val mediumAt = (offAt + fullAt) / 2.0

        val now = current ?: Level.OFF
        // Going colder, a boundary must be properly crossed; going warmer, it
        // must be properly cleared. The same number, applied in the direction
        // that resists change.
        fun below(threshold: Double, target: Level): Boolean =
            if (now.ordinal >= target.ordinal) felt <= threshold + HYSTERESIS_C
            else felt <= threshold - HYSTERESIS_C

        return when {
            below(highAt, Level.HIGH) -> Level.HIGH
            below(mediumAt, Level.MEDIUM) -> Level.MEDIUM
            below(lowAt, Level.LOW) -> Level.LOW
            else -> Level.OFF
        }
    }

    /**
     * Whether to act on a newly computed level.
     *
     * The hysteresis above already stops boundary flutter; this is the second
     * guard, against a level that is genuinely oscillating for some other
     * reason. A change of two steps or more is a real change of conditions —
     * a tunnel, a stop — and does not wait.
     */
    private const val MIN_INTERVAL_MS = 45_000L

    fun shouldApply(current: Level?, computed: Level, lastChangeAt: Long): Boolean {
        if (current == null) return true
        if (computed == current) return false
        if (kotlin.math.abs(computed.ordinal - current.ordinal) >= 2) return true
        return System.currentTimeMillis() - lastChangeAt >= MIN_INTERVAL_MS
    }

    /**
     * What the bike's electrical system will allow, whatever the rider asked for.
     *
     * The clothing runs off the bike, not off its own battery, so the app is in
     * the unusual position of seeing both the load it is asking for and the
     * supply feeding it. Keis iControl cannot do this at all — it has no idea
     * there is an alternator involved.
     *
     * A running engine should hold well over 13 V. Sagging below that with the
     * engine turning means the system is not keeping up, and the heat is the
     * largest discretionary load on it. With the engine stopped there is nothing
     * replacing what the clothing draws at all.
     */
    enum class Supply { FINE, STRAINED, CRITICAL, ENGINE_OFF }

    private const val VOLTS_STRAINED = 12.8
    private const val VOLTS_CRITICAL = 12.3
    private const val ENGINE_RUNNING_RPM = 400

    fun supply(batteryV: Double?, rpm: Int?): Supply = when {
        batteryV == null || rpm == null -> Supply.FINE      // unknown is not a reason to act
        rpm < ENGINE_RUNNING_RPM -> Supply.ENGINE_OFF
        batteryV < VOLTS_CRITICAL -> Supply.CRITICAL
        batteryV < VOLTS_STRAINED -> Supply.STRAINED
        else -> Supply.FINE
    }

    /**
     * The highest level the supply will bear.
     *
     * A cap rather than a setting: this overrides both automatic and manual,
     * because a rider choosing HIGH has not chosen a flat battery forty
     * kilometres from anywhere. It is the one place the app overrules the person
     * using it, which is exactly why the page has to say so plainly — an
     * override nobody can see reads as a fault.
     */
    fun cap(supply: Supply): Level = when (supply) {
        Supply.FINE -> Level.HIGH
        Supply.STRAINED -> Level.MEDIUM
        Supply.CRITICAL -> Level.LOW
        Supply.ENGINE_OFF -> Level.OFF
    }

    /**
     * True when it is cold enough that the gloves want turning up.
     *
     * The one zone the app cannot reach is also the one that goes cold first, so
     * the least it can do is say so. Tied to the legs coming on rather than a
     * separate number: by the time the most exposed zone it *can* control is
     * calling for heat, the hands have been asking for a while.
     */
    fun glovesWanted(feltC: Double?): Boolean =
        (levelFor(Zone.LEGS, feltC, Level.OFF) ?: Level.OFF) != Level.OFF
}

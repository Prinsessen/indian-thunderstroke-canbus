package dk.agesen.springfield

/**
 * The fuel level, filtered for a tank that is only level when the bike is.
 *
 * A float sender in a tank measures the surface where it happens to be, and on
 * a bike that surface is rarely flat. Left on the side stand the Springfield
 * leans far enough that the sender sits high out of the fuel and reads several
 * percent low — enough to put a nearly full tank into a critical warning, in a
 * garage, with the bike untouched. A rider who has been shown that once has
 * learned to disbelieve the fuel warning, and the warning is then worse than
 * having none.
 *
 * So a reading only counts while the bike is moving. A motorcycle in motion is
 * upright by definition, which turns a hard problem — knowing the lean angle
 * from a bus that never reports it — into a simple one.
 *
 * Motion alone is not enough, because braking and accelerating throw the fuel
 * up and down the tank. The trusted figure is therefore the **median** of a
 * window of moving samples: a median ignores the sloshing entirely, where an
 * average would fold each surge into the answer.
 */
object FuelLevel {

    /**
     * Above this, the bike is upright.
     *
     * Not zero, and not the walking pace a bike can be pushed at with a lean on
     * it. Ten km/h is faster than anyone moves a 380 kg motorcycle by hand.
     */
    private const val UPRIGHT_KMH = 10.0

    /**
     * Samples kept for the median.
     *
     * At the 1 Hz the state JSON arrives, this is about half a minute — long
     * enough to average out a set of traffic lights, short enough to follow a
     * tank down over a long ride and to notice a refuelling within a minute of
     * setting off again.
     */
    private const val WINDOW = 31

    /**
     * A gap this long means the window is describing a different ride.
     *
     * The samples are cleared rather than left to age out, so a tank filled
     * while the bike was parked is not averaged against the readings from
     * before the fill.
     */
    private const val GAP_MS = 5 * 60_000L

    private val samples = ArrayDeque<Int>()
    private var lastFeedAt = 0L

    /**
     * The filtered level, or null while the bike has not yet been ridden.
     *
     * Null is the honest answer on a bike that has only ever been parked, and
     * every consumer treats it as "do not judge" rather than "empty". That is
     * what stops a warning firing on a reading nobody should trust.
     */
    val trusted: Int?
        get() {
            if (samples.isEmpty()) return Settings.lastFuelPct.takeIf { it >= 0 }
            val sorted = samples.sorted()
            return sorted[sorted.size / 2]
        }

    /**
     * True when the figure comes from this ride rather than from the last one.
     *
     * The distinction matters before setting off: a level remembered from a
     * previous ride is worth showing — it is the best answer anyone has while
     * the bike is standing still — but it is not worth warning on, because a
     * fill-up since would make the warning simply wrong.
     */
    val live: Boolean get() = samples.isNotEmpty()

    /**
     * Offer a reading. Cheap to call at any rate; it keeps only what it can use.
     */
    fun feed(fuelPct: Int?, speedKmh: Double?) {
        val pct = fuelPct ?: return
        val speed = speedKmh ?: return
        if (speed < UPRIGHT_KMH) return
        if (pct !in 0..100) return

        val now = System.currentTimeMillis()
        if (now - lastFeedAt > GAP_MS) samples.clear()
        lastFeedAt = now

        samples.addLast(pct)
        while (samples.size > WINDOW) samples.removeFirst()

        // Carried across restarts so the pre-ride check has something to say
        // about a bike that has not moved yet. Written from the median rather
        // than the sample, so a single lurch never becomes the remembered value.
        trusted?.let { if (it != Settings.lastFuelPct) Settings.lastFuelPct = it }
    }

    /** Forget this ride's window; the remembered level is left alone. */
    fun reset() = samples.clear()
}

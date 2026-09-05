package dk.agesen.springfield

/**
 * How far is left in the tank.
 *
 * The level and the economy were both decoded and both displayed, separately —
 * which leaves the rider doing the arithmetic at the exact moment they are least
 * inclined to. Together with the tank's capacity they give the number actually
 * wanted when the light comes on.
 *
 * Reported as a **band, not a figure.** A fuel sender that reads in whole
 * percent and a rolling economy average do not between them support "183 km",
 * and printing that would claim a precision neither input has. The band comes
 * from the economy actually seen over the last few minutes, so a headwind or a
 * spell of town riding widens it honestly rather than being averaged away.
 */
object FuelRange {

    /** Economy samples to keep, at the state characteristic's ~1 Hz. */
    private const val WINDOW = 180

    /** Below this the reading is noise, not economy. */
    private const val MIN_PLAUSIBLE_L100 = 2.0
    private const val MAX_PLAUSIBLE_L100 = 25.0

    private val samples = ArrayDeque<Double>()

    @Synchronized
    fun feed(economyL100: Double?) {
        val e = economyL100 ?: return
        if (e !in MIN_PLAUSIBLE_L100..MAX_PLAUSIBLE_L100) return
        samples.addLast(e)
        while (samples.size > WINDOW) samples.removeFirst()
    }

    @Synchronized
    fun reset() = samples.clear()

    /** Kilometres remaining as a low..high band, or null when unknowable. */
    @Synchronized
    fun estimateKm(fuelPct: Int?, tankLitres: Double): ClosedRange<Double>? {
        if (fuelPct == null || samples.isEmpty()) return null
        val litres = fuelPct / 100.0 * tankLitres
        if (litres <= 0.0) return 0.0..0.0

        // Worst economy gives the shortest range, and that is the end of the
        // band a rider should plan against.
        val worst = samples.max()
        val best = samples.min()
        val low = litres * 100.0 / worst
        val high = litres * 100.0 / best
        return low..high
    }

    /**
     * The band as text, rounded to ten and collapsed to one figure when the two
     * ends round together — a range of "180–180" is a figure pretending to be a
     * range, which is worse than either.
     */
    fun format(fuelPct: Int?, tankLitres: Double, convert: (Double) -> Double, unit: String): String? {
        val band = estimateKm(fuelPct, tankLitres) ?: return null
        val lo = (convert(band.start) / 10).toInt() * 10
        val hi = (convert(band.endInclusive) / 10).toInt() * 10
        return if (lo >= hi) "≈$lo $unit" else "$lo–$hi $unit"
    }
}

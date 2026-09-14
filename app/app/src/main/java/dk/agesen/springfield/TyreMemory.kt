package dk.agesen.springfield

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers the last tyre reading across app restarts, and interprets it.
 *
 * Two facts about this bike's TPMS shape everything here:
 *
 *  1. **The sensors sleep when the wheels stop.** They report nothing on a
 *     parked bike — which is exactly when a rider walks up and wants to check
 *     the tyres. An app that shows "—" at that moment is useless, so the last
 *     reading is persisted and shown with its age instead.
 *
 *  2. **A pressure reading is only meaningful with its temperature.** Real
 *     values off this bike: 44.7 PSI at 42 °C on a 17 °C day. That tyre is not
 *     at 44.7 PSI cold — it is at roughly 40. Reading the warm number against a
 *     cold target would have the rider let air out of a correctly inflated tyre.
 *
 * So the useful figure is the **cold equivalent**, and that is what gets
 * compared against the target.
 */
object TyreMemory {

    private const val PREFS = "tyres"
    private const val K_FRONT_PSI = "front_psi"
    private const val K_REAR_PSI = "rear_psi"
    private const val K_FRONT_TEMP = "front_temp"
    private const val K_REAR_TEMP = "rear_temp"
    private const val K_AMBIENT = "ambient"
    private const val K_TIME = "time"
    private const val K_HISTORY = "history"

    /**
     * How long between kept samples, and how many to keep.
     *
     * The state characteristic arrives at 1 Hz, so without a spacing rule the
     * ring would fill in ten seconds and describe a moment rather than a season.
     * An hour apart over ten samples covers weeks of parking, which is the
     * timescale a slow puncture actually works on.
     */
    private const val HISTORY_INTERVAL_MS = 60 * 60 * 1000L
    private const val HISTORY_SIZE = 10

    /** Standard atmospheric pressure, for converting gauge to absolute. */
    private const val ATMOSPHERIC_PSI = 14.696

    /**
     * The temperature the placard figure is specified at.
     *
     * Targets like 36 front and 41 rear are cold pressures, and "cold" in a
     * manual means the tyre is at rest at a nominal room temperature — the
     * industry uses 20 °C. They are a fixed number, not one that tracks the
     * weather, so the reading has to be brought to a fixed temperature before
     * it can be compared with them.
     */
    private const val REFERENCE_C = 20.0

    /** How far from target counts as fine / worth noting / act on it. */
    private const val TOLERANCE_OK = 2.0
    private const val TOLERANCE_WARN = 4.0

    /**
     * What a real tyre can read.
     *
     * A stored reading survives until the next complete one, which on a bike
     * parked for a week means a glitched frame showing 0 PSI would sit there
     * red and alarming for days. These bounds are wide enough to cover a
     * genuinely flat tyre being reported (a sensor still reads a few PSI) and
     * tight enough to reject a decode error.
     */
    private val PLAUSIBLE_PSI = 5.0..80.0
    private val PLAUSIBLE_TEMP_C = -30.0..95.0

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------- targets

    // Targets moved to Settings, where the rider can reach them. Read through
    // rather than copied, so a change takes effect on the next draw.
    private val targetFront: Double get() = Settings.targetFront
    private val targetRear: Double get() = Settings.targetRear

    // ------------------------------------------------------------- storing

    /**
     * Store a reading, but only a complete one. A JSON frame that carries
     * pressure without its temperature is worse than no frame at all: it would
     * overwrite a usable pair with one that cannot be temperature-corrected.
     */
    fun remember(s: BikeJsonState) {
        val fp = s.tyreFrontPsi
        val rp = s.tyreRearPsi
        val ft = s.tyreFrontTempC
        val rt = s.tyreRearTempC
        if (fp == null || rp == null || ft == null || rt == null) return
        if (fp !in PLAUSIBLE_PSI || rp !in PLAUSIBLE_PSI) return
        if (ft !in PLAUSIBLE_TEMP_C || rt !in PLAUSIBLE_TEMP_C) return

        // A reading is only new if the numbers moved.
        //
        // The ESP32 republishes its last decode about once a second whether or
        // not the sensors said anything -- nothing on the board clears a tyre
        // value, resetState() runs at boot and never again -- and the sensors
        // themselves sleep when the wheels stop. So without this check the
        // stored timestamp was rewritten on every packet, and a pressure from
        // last week's ride read as "just now" the moment the phone connected.
        //
        // That is the one direction this class must never be wrong in: it exists
        // because a rider walks up to a parked machine and wants the pressures to
        // pump against, and a stale reading presented as fresh is how a tyre gets
        // left soft.
        //
        // Comparing the four tyre numbers rather than the packet: ambient moves
        // on its own and must not count as a new measurement, and if it were the
        // only thing that changed the stored ambient would drift away from the
        // air temperature the pressures were actually taken at, which is what the
        // cold equivalent is computed against.
        //
        // If a sensor genuinely reports an identical quadruple twice, the age
        // overstates by one interval. That is the safe direction, and with two
        // temperatures in the comparison it is vanishingly rare in motion.
        val unchanged = prefs.contains(K_TIME) &&
            prefs.getFloat(K_FRONT_PSI, Float.NaN) == fp.toFloat() &&
            prefs.getFloat(K_REAR_PSI, Float.NaN) == rp.toFloat() &&
            prefs.getFloat(K_FRONT_TEMP, Float.NaN) == ft.toFloat() &&
            prefs.getFloat(K_REAR_TEMP, Float.NaN) == rt.toFloat()
        if (unchanged) return

        prefs.edit()
            .putFloat(K_FRONT_PSI, fp.toFloat())
            .putFloat(K_REAR_PSI, rp.toFloat())
            .putFloat(K_FRONT_TEMP, ft.toFloat())
            .putFloat(K_REAR_TEMP, rt.toFloat())
            // Ambient is stored alongside because the cold equivalent is only
            // meaningful against the air temperature at the time of the reading,
            // not whatever it happens to be when the rider opens the app.
            .putFloat(K_AMBIENT, (s.ambientC ?: Double.NaN).toFloat())
            .putLong(K_TIME, System.currentTimeMillis())
            .apply()

        rememberHistory(fp, rp, ft, rt, s.ambientC)
    }

    // ------------------------------------------------------------- history

    private fun rememberHistory(fp: Double, rp: Double, ft: Double, rt: Double, amb: Double?) {
        val now = System.currentTimeMillis()
        val arr = try { JSONArray(prefs.getString(K_HISTORY, "[]")) } catch (e: Exception) { JSONArray() }

        if (arr.length() > 0) {
            val last = arr.getJSONObject(arr.length() - 1).optLong("t")
            if (now - last < HISTORY_INTERVAL_MS) return
        }

        arr.put(JSONObject().apply {
            put("t", now); put("fp", fp); put("rp", rp)
            put("ft", ft); put("rt", rt)
            if (amb != null) put("amb", amb)
        })

        // Drop from the front, so the ring keeps the most recent window.
        val trimmed = JSONArray()
        val from = maxOf(0, arr.length() - HISTORY_SIZE)
        for (i in from until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString(K_HISTORY, trimmed.toString()).apply()
    }

    /** Pressure change per week, front and rear, or null without enough history. */
    data class Trend(val frontPsiPerWeek: Double, val rearPsiPerWeek: Double, val spanDays: Double)

    /**
     * A slow puncture is the failure a rider cannot see and would most want
     * warned about, and it is only visible across weeks.
     *
     * Compared on **cold equivalents**, not raw readings — two measurements taken
     * at different tyre temperatures differ by more than a fortnight's leak, so a
     * trend built on raw pressure would mostly describe the weather.
     */
    fun trend(): Trend? {
        val arr = try { JSONArray(prefs.getString(K_HISTORY, "[]")) } catch (e: Exception) { return null }
        if (arr.length() < 2) return null

        fun coldAt(o: JSONObject, psiKey: String, tempKey: String): Double =
            coldEquivalent(o.getDouble(psiKey), o.getDouble(tempKey))

        // Every sample counts now. The reference is fixed, so an entry written
        // while the outside temperature was unknown is no longer useless — and
        // a run of those used to be able to leave too little history to call a
        // trend at all. Only "amb" was ever optional; t, fp, rp, ft and rt are
        // written on every record.
        val a = arr.getJSONObject(0)
        val b = arr.getJSONObject(arr.length() - 1)

        val days = (b.getLong("t") - a.getLong("t")) / 86_400_000.0
        if (days < 3.0) return null          // too short to call a trend

        val fA = coldAt(a, "fp", "ft")
        val fB = coldAt(b, "fp", "ft")
        val rA = coldAt(a, "rp", "rt")
        val rB = coldAt(b, "rp", "rt")

        return Trend((fB - fA) / days * 7.0, (rB - rA) / days * 7.0, days)
    }

    // ------------------------------------------------------------- reading

    /** One wheel's worth of interpreted data. */
    data class Wheel(
        val psi: Double,
        val tempC: Double,
        val coldPsi: Double,        // at REFERENCE_C — what the alert judges
        val ambientPsi: Double?,    // at today's ambient — shown, never alerted on
        val target: Double
    ) {
        /** Always available now: the reference is fixed, so ambient is not needed. */
        val judged: Double get() = coldPsi
        val deviation: Double get() = judged - target

        val level: Level get() = when {
            kotlin.math.abs(deviation) <= TOLERANCE_OK -> Level.OK
            kotlin.math.abs(deviation) <= TOLERANCE_WARN -> Level.WATCH
            else -> Level.ACT
        }
    }

    enum class Level { OK, WATCH, ACT }

    data class Reading(
        val front: Wheel,
        val rear: Wheel,
        val ambientC: Double?,
        val timestamp: Long
    ) {
        val ageMillis: Long get() = System.currentTimeMillis() - timestamp
    }

    fun last(): Reading? {
        if (!prefs.contains(K_TIME)) return null
        val amb = prefs.getFloat(K_AMBIENT, Float.NaN).toDouble().takeIf { !it.isNaN() }

        fun wheel(psiKey: String, tempKey: String, target: Double): Wheel {
            val psi = prefs.getFloat(psiKey, 0f).toDouble()
            val temp = prefs.getFloat(tempKey, 0f).toDouble()
            return Wheel(psi, temp, coldEquivalent(psi, temp), atAmbient(psi, temp, amb), target)
        }

        return Reading(
            front = wheel(K_FRONT_PSI, K_FRONT_TEMP, targetFront),
            rear = wheel(K_REAR_PSI, K_REAR_TEMP, targetRear),
            ambientC = amb,
            timestamp = prefs.getLong(K_TIME, 0L)
        )
    }

    // ------------------------------------------------------------------ math

    /**
     * Gauge pressure this tyre would read at REFERENCE_C. **The leak number.**
     *
     * Gay-Lussac on *absolute* pressure at constant volume: P₁/T₁ = P₂/T₂ with
     * temperatures in Kelvin. Gauge pressure has to be lifted to absolute first
     * and dropped back after, which is the step a naive "1 PSI per 10 °F" rule
     * approximates — close enough over small spans, but this costs nothing and
     * does not drift on a hot rear tyre.
     *
     * NORMALISED TO A FIXED 20 °C, NOT TO TODAY'S AMBIENT, and that is the whole
     * point of this function. Referenced to ambient it answered a different
     * question — "what will it read when it cools down outside" — which is true
     * but moves with the weather, while the target it is compared against does
     * not. On 2026-09-13 the rider got a front-tyre alert at 11–12 °C on a tyre
     * that had lost no air: the temperature alone accounts for −1.6 PSI against
     * a 2.0 PSI tolerance, and below about 5 °C a perfectly correct tyre warns
     * on its own.
     *
     * It also poisoned trend(). Two readings taken weeks apart, each referenced
     * to its own ambient, carry the whole seasonal shift in the difference — so
     * the function written to keep weather out of the leak rate reported −1.6
     * PSI per week on a sealed tyre as autumn came in.
     *
     * Fixed reference fixes both, and needs no ambient at all, so a missing
     * outside temperature no longer leaves the tyre unjudgeable.
     */
    fun coldEquivalent(psi: Double, tyreTempC: Double): Double {
        val tyreK = tyreTempC + 273.15
        if (tyreK <= 0) return psi          // decode junk; better the raw figure
        return (psi + ATMOSPHERIC_PSI) * ((REFERENCE_C + 273.15) / tyreK) - ATMOSPHERIC_PSI
    }

    /**
     * Gauge pressure this tyre would read once cooled to today's ambient.
     * **The inflation number, and information only — nothing alerts on it.**
     *
     * The manufacturer's convention is to set the placard pressure with the tyre
     * at rest, whatever the weather, so on a cold day a correctly filled tyre
     * genuinely does sit lower than its target. That is worth showing, because a
     * rider who never sees it will spend a whole winter three PSI down without
     * anything mentioning it. It is not worth alarming on, because the answer
     * changes with the forecast and the rider cannot chase it.
     */
    fun atAmbient(psi: Double, tyreTempC: Double, ambientC: Double?): Double? {
        if (ambientC == null) return null
        val tyreK = tyreTempC + 273.15
        val ambientK = ambientC + 273.15
        if (tyreK <= 0 || ambientK <= 0) return null
        return (psi + ATMOSPHERIC_PSI) * (ambientK / tyreK) - ATMOSPHERIC_PSI
    }

    /** "4 days ago", "12 min ago" — deliberately coarse; precision implies freshness. */
    fun formatAge(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes < 2 -> "just now"
            minutes < 90 -> "$minutes min ago"
            minutes < 60 * 36 -> "${minutes / 60} h ago"
            else -> "${minutes / 1440} days ago"
        }
    }
}

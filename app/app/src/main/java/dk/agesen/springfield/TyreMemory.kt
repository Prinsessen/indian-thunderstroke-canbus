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

        fun coldAt(o: JSONObject, psiKey: String, tempKey: String): Double? {
            val amb = if (o.has("amb")) o.getDouble("amb") else return null
            return coldEquivalent(o.getDouble(psiKey), o.getDouble(tempKey), amb)
        }

        // The oldest and newest samples that can be corrected at all.
        var oldest: JSONObject? = null
        var newest: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!o.has("amb")) continue
            if (oldest == null) oldest = o
            newest = o
        }
        val a = oldest ?: return null
        val b = newest ?: return null
        if (a === b) return null

        val days = (b.getLong("t") - a.getLong("t")) / 86_400_000.0
        if (days < 3.0) return null          // too short to call a trend

        val fA = coldAt(a, "fp", "ft") ?: return null
        val fB = coldAt(b, "fp", "ft") ?: return null
        val rA = coldAt(a, "rp", "rt") ?: return null
        val rB = coldAt(b, "rp", "rt") ?: return null

        return Trend((fB - fA) / days * 7.0, (rB - rA) / days * 7.0, days)
    }

    // ------------------------------------------------------------- reading

    /** One wheel's worth of interpreted data. */
    data class Wheel(
        val psi: Double,
        val tempC: Double,
        val coldPsi: Double?,   // null when ambient was unknown
        val target: Double
    ) {
        /** Cold equivalent where known, otherwise the raw reading. */
        val judged: Double get() = coldPsi ?: psi
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
            return Wheel(psi, temp, coldEquivalent(psi, temp, amb), target)
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
     * Gauge pressure the tyre would read once cooled to ambient.
     *
     * Gay-Lussac on *absolute* pressure at constant volume: P₁/T₁ = P₂/T₂ with
     * temperatures in Kelvin. Gauge pressure has to be lifted to absolute first
     * and dropped back after, which is the step a naive "1 PSI per 10 °F" rule
     * approximates — close enough over small spans, but this costs nothing and
     * does not drift on a hot rear tyre.
     *
     * Returns null when ambient is unknown; guessing it would defeat the point.
     */
    fun coldEquivalent(psi: Double, tyreTempC: Double, ambientC: Double?): Double? {
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

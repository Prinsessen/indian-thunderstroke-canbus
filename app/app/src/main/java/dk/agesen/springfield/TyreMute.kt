package dk.agesen.springfield

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs

/**
 * A tyre warning the rider has acknowledged.
 *
 * Asked for after the ride of 2026-11-03: the rear read a few PSI under target
 * (cold equivalent 38.5-39.2 against 41) the whole ride, after the owner had
 * pumped it to 42 on her own gauge at a roadside stop. The amber banner came and went
 * nine times in an hour as the reading crossed the 2.0 PSI line in 0.6 PSI steps,
 * and the bike's own "rear tyre pressure low" (SPN 520302 FMI 17) stood red before
 * the stop. Which of the two gauges is right is not known yet - but a warning that
 * cannot be acknowledged is one that teaches the rider to ignore the banner.
 *
 * So a long press on a tyre banner mutes it, and the mute is built so that it can
 * only hide what the rider has already seen:
 *
 *  - it remembers how far each tyre was from target at that moment, and a tyre
 *    that falls a further [WORSEN_PSI] breaks through - a slow puncture is
 *    exactly the case that must not stay quiet;
 *  - the bike's own tyre code is muted only at FMI 17, the least severe "low";
 *    FMI 18 and FMI 1 (moderately and most severely low) always show, and so does
 *    any other code standing beside it;
 *  - it lapses after [HOLD_MS], so the next day's ride starts with the warning back.
 *
 * The decisions are plain functions of their arguments, so the JUnit test can
 * hold them without a phone; only [mute] and [current] touch storage.
 */
object TyreMute {

    /** How long an acknowledgement lasts: a day's riding, not a season. */
    const val HOLD_MS = 12 * 60 * 60 * 1000L

    /** A tyre this much further from target than when muted is news again. */
    const val WORSEN_PSI = 1.0

    /** The bike's tyre-low codes, and the only FMI the mute covers. */
    const val SPN_FRONT_LOW = 520300L
    const val SPN_REAR_LOW = 520302L
    const val FMI_LEAST_SEVERE = 17

    /** How far each tyre was from target when the rider muted (PSI, signed). */
    data class Mute(val atMs: Long, val frontDev: Double?, val rearDev: Double?)

    // ------------------------------------------------------------ decisions

    /**
     * True when the mute still covers this tyre at this deviation.
     *
     * A tyre with no deviation stored was not readable when the rider muted, so
     * nothing about it was acknowledged and nothing is hidden.
     */
    fun holds(m: Mute?, wheel: String, deviation: Double, nowMs: Long): Boolean {
        if (m == null) return false
        val age = nowMs - m.atMs
        if (age < 0 || age >= HOLD_MS) return false
        val at = (if (wheel == "FRONT") m.frontDev else m.rearDev) ?: return false
        return abs(deviation) <= abs(at) + WORSEN_PSI
    }

    /**
     * The tyres named by the active codes, or null if anything else is active.
     *
     * Null is the "show it" answer: a list with one fault that is not a tyre at
     * its least severe level is not a tyre warning any more, and the mute has no
     * business deciding about it.
     */
    fun tyreDtcWheels(faults: List<Dtc.Fault>): Set<String>? {
        if (faults.isEmpty()) return null
        val wheels = mutableSetOf<String>()
        for (f in faults) {
            if (f.fmi != FMI_LEAST_SEVERE) return null
            when (f.spn) {
                SPN_FRONT_LOW -> wheels += "FRONT"
                SPN_REAR_LOW -> wheels += "REAR"
                else -> return null
            }
        }
        return wheels
    }

    // ------------------------------------------------------------- storage

    private const val PREFS = "tyre_mute"
    private const val K_AT = "at"
    private const val K_FRONT = "front_dev"
    private const val K_REAR = "rear_dev"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null)
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun mute(nowMs: Long, frontDev: Double?, rearDev: Double?) {
        val p = prefs ?: return
        p.edit()
            .putLong(K_AT, nowMs)
            .putFloat(K_FRONT, (frontDev ?: Double.NaN).toFloat())
            .putFloat(K_REAR, (rearDev ?: Double.NaN).toFloat())
            .apply()
    }

    fun current(): Mute? {
        val p = prefs ?: return null
        if (!p.contains(K_AT)) return null
        fun dev(k: String) = p.getFloat(k, Float.NaN).toDouble().takeIf { !it.isNaN() }
        return Mute(p.getLong(K_AT, 0L), dev(K_FRONT), dev(K_REAR))
    }
}

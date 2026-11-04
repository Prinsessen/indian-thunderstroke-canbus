package dk.agesen.springfield

import android.content.Context
import android.content.SharedPreferences

/**
 * The ride's rear wheel slip, kept after the bike is switched off.
 *
 * The owner, 2026-11-04, the day app 0.9 showed the line for the first time: the
 * REAR SLIP line is small print at the foot of the MACHINE page, so she reads it
 * at a stop, not while riding - and a stop with the key off is exactly when it
 * went blank, because the line was drawn only from a live fast packet.
 *
 * So the last min / max the board sent are remembered here, across a dropped link
 * and an app restart, and the line keeps them until the board sends new ones.
 * The board starts a new min / max at the next ignition (main.cpp, slipNewRide),
 * so the first packet of the next ride replaces them: a fuel stop with the key off
 * still starts the figures again, as before. A packet without slip (older
 * firmware, or 0x7F before the ABS module's first reading after a wake) changes
 * nothing.
 *
 * [feed] and [last] are plain in-memory logic, so the JUnit test holds them
 * without a phone; only [init] and the save touch storage.
 */
object RideSlip {

    data class Slip(val minKmh: Double, val maxKmh: Double)

    /** The slip a packet carries, or null when it carries none. */
    fun of(p: FastPacket?): Slip? {
        if (p == null) return null
        val min = p.rearSlipMinKmh ?: return null
        val max = p.rearSlipMaxKmh ?: return null
        return Slip(min, max)
    }

    /** What the MACHINE page shows: the latest the board sent, live or not. */
    @Volatile var last: Slip? = null
        private set

    fun feed(p: FastPacket) {
        val s = of(p) ?: return
        if (s == last) return
        last = s
        save(s)
    }

    // ------------------------------------------------------------- storage

    private const val PREFS = "ride_slip"
    private const val K_MIN = "min_kmh"
    private const val K_MAX = "max_kmh"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        if (last == null && p.contains(K_MIN) && p.contains(K_MAX))
            last = Slip(p.getFloat(K_MIN, 0f).toDouble(), p.getFloat(K_MAX, 0f).toDouble())
    }

    private fun save(s: Slip) {
        prefs?.edit()?.putFloat(K_MIN, s.minKmh.toFloat())?.putFloat(K_MAX, s.maxKmh.toFloat())?.apply()
    }

    /** For the test: forget the in-memory figures (storage is not touched). */
    internal fun clearForTest() { last = null }
}

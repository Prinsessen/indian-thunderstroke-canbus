package dk.agesen.springfield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tyre mute may hide only what the rider has already seen.
 *
 * Numbers from the ride of 2026-11-03: rear 2.3 PSI under target when the
 * banner was acknowledged, wandering between 1.6 and 2.3 under for the rest of
 * the ride, and the bike's own SPN 520302 FMI 17 before the pump-up.
 */
class TyreMuteTest {

    private val t0 = 1_793_720_000_000L
    private val muted = TyreMute.Mute(t0, frontDev = -1.1, rearDev = -2.3)

    @Test
    fun `no mute hides nothing`() {
        assertFalse(TyreMute.holds(null, "REAR", -2.3, t0))
    }

    @Test
    fun `the reading that was muted, and its wander, stay quiet`() {
        assertTrue(TyreMute.holds(muted, "REAR", -2.3, t0 + 60_000))
        assertTrue(TyreMute.holds(muted, "REAR", -1.6, t0 + 60_000))
        assertTrue(TyreMute.holds(muted, "REAR", -3.3, t0 + 60_000))   // exactly 1.0 worse: still the same news
    }

    @Test
    fun `a tyre that keeps falling breaks through`() {
        assertFalse(TyreMute.holds(muted, "REAR", -3.4, t0 + 60_000))
        assertFalse(TyreMute.holds(muted, "REAR", -4.5, t0 + 60_000))  // ACT level
    }

    @Test
    fun `the other tyre is judged on its own figure`() {
        assertTrue(TyreMute.holds(muted, "FRONT", -2.0, t0 + 60_000))
        assertFalse(TyreMute.holds(muted, "FRONT", -2.2, t0 + 60_000))  // was -1.1: a new warning
    }

    @Test
    fun `a tyre that could not be read when muted is not muted`() {
        val m = TyreMute.Mute(t0, frontDev = null, rearDev = -2.3)
        assertFalse(TyreMute.holds(m, "FRONT", -2.5, t0 + 60_000))
    }

    @Test
    fun `the mute lapses after twelve hours`() {
        assertTrue(TyreMute.holds(muted, "REAR", -2.3, t0 + TyreMute.HOLD_MS - 1))
        assertFalse(TyreMute.holds(muted, "REAR", -2.3, t0 + TyreMute.HOLD_MS))
        assertFalse(TyreMute.holds(muted, "REAR", -2.3, t0 - 1))   // clock moved back: do not trust it
    }

    @Test
    fun `the bike's least severe tyre code is a tyre warning`() {
        assertEquals(setOf("REAR"), TyreMute.tyreDtcWheels(listOf(Dtc.Fault(520302, 17, 10))))
        assertEquals(setOf("FRONT", "REAR"),
            TyreMute.tyreDtcWheels(listOf(Dtc.Fault(520300, 17, 1), Dtc.Fault(520302, 17, 1))))
    }

    @Test
    fun `a more severe tyre code, or any other code beside it, is not muted`() {
        assertNull(TyreMute.tyreDtcWheels(listOf(Dtc.Fault(520302, 18, 1))))
        assertNull(TyreMute.tyreDtcWheels(listOf(Dtc.Fault(520302, 1, 1))))
        assertNull(TyreMute.tyreDtcWheels(listOf(Dtc.Fault(520302, 17, 1), Dtc.Fault(168, 4, 1))))
        assertNull(TyreMute.tyreDtcWheels(emptyList()))
    }

    @Test
    fun `the firmware's string for 2026-11-03 parses to a mutable tyre code`() {
        val dm1 = "SPN 520302 FMI 17 (x10) | MIL:off Stop:off Warn:off Prot:off"
        assertEquals(setOf("REAR"), TyreMute.tyreDtcWheels(Dtc.parse(dm1)))
    }
}

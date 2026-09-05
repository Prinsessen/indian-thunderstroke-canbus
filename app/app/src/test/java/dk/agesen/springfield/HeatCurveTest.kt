package dk.agesen.springfield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heat curve, checked on the build machine.
 *
 * This file exists because of a bug that reached a motorcycle. The curve divided
 * its span into three, so a setting of "off at 25, full at 10" reached full heat
 * at 15 — every configuration ran a step hot through the middle of its range,
 * and the number the rider had typed named a temperature the curve never used.
 *
 * It was found by a rider standing in a garage in September. Four lines here
 * would have found it in a second, months earlier, and that is the whole
 * argument for the file.
 */
class HeatCurveTest {

    private val off = 25.0
    private val full = 10.0

    // ------------------------------------------------------------ the bands

    @Test
    fun `the two settings are the two ends of the curve`() {
        // The bug. "Full at 10" has to mean full heat at 10, not at 15.
        assertEquals(HeatCurve.Level.HIGH, HeatCurve.levelFor(off, full, 10.0, HeatCurve.Level.HIGH))
        // And 16 is comfortably inside MEDIUM, not HIGH as the old maths gave.
        assertEquals(HeatCurve.Level.MEDIUM, HeatCurve.levelFor(off, full, 16.0, HeatCurve.Level.LOW))
    }

    @Test
    fun `above the off point nothing is asked for`() {
        assertEquals(HeatCurve.Level.OFF, HeatCurve.levelFor(off, full, 26.0, HeatCurve.Level.OFF))
    }

    @Test
    fun `the middle of the range steps through every level`() {
        assertEquals(HeatCurve.Level.LOW, HeatCurve.levelFor(off, full, 22.0, HeatCurve.Level.OFF))
        assertEquals(HeatCurve.Level.MEDIUM, HeatCurve.levelFor(off, full, 16.0, HeatCurve.Level.LOW))
        assertEquals(HeatCurve.Level.HIGH, HeatCurve.levelFor(off, full, 8.4, HeatCurve.Level.MEDIUM))
    }

    // ----------------------------------------------------------- hysteresis

    @Test
    fun `the same temperature answers differently depending on where it came from`() {
        // Not a defect, and the reason a level sometimes surprises a rider: the
        // 1.5 degree band is applied against the direction of travel. At 17,
        // half a degree from the MEDIUM boundary, that is the whole difference.
        assertEquals(HeatCurve.Level.LOW, HeatCurve.levelFor(off, full, 17.0, HeatCurve.Level.OFF))
        assertEquals(HeatCurve.Level.MEDIUM, HeatCurve.levelFor(off, full, 17.0, HeatCurve.Level.HIGH))
    }

    @Test
    fun `stepping up needs the boundary properly crossed`() {
        // 9 is below "full at 10" and still does not reach HIGH from MEDIUM,
        // because climbing a step demands 1.5 degrees past the line. This is the
        // behaviour, and writing it down stops it being reported as a bug.
        assertEquals(HeatCurve.Level.MEDIUM, HeatCurve.levelFor(off, full, 9.0, HeatCurve.Level.MEDIUM))
        assertEquals(HeatCurve.Level.HIGH, HeatCurve.levelFor(off, full, 8.4, HeatCurve.Level.MEDIUM))
    }

    @Test
    fun `coming back down needs it crossed the other way`() {
        assertEquals(HeatCurve.Level.HIGH, HeatCurve.levelFor(off, full, 10.0, HeatCurve.Level.HIGH))
        assertEquals(HeatCurve.Level.MEDIUM, HeatCurve.levelFor(off, full, 12.0, HeatCurve.Level.HIGH))
    }

    // -------------------------------------------------------------- refusal

    @Test
    fun `an unknown temperature decides nothing`() {
        assertNull(HeatCurve.levelFor(off, full, null, HeatCurve.Level.OFF))
    }

    @Test
    fun `a backwards curve decides nothing`() {
        // Off below full is meaningless. Guessing at an intent here would put
        // current through a heating element on the strength of a typo.
        assertNull(HeatCurve.levelFor(10.0, 25.0, 15.0, HeatCurve.Level.OFF))
    }

    // ------------------------------------------------------------ wind chill

    @Test
    fun `wind chill only applies inside its own limits`() {
        // Environment Canada's relation is defined for 10 degrees and below at
        // 4.8 km per hour and above. Outside that it does not merely lose
        // accuracy, it returns values above ambient — which would have the app
        // cooling the rider as the day warms up.
        assertEquals(17.0, HeatCurve.feelsLike(17.0, 0.0)!!, 0.001)
        assertEquals(12.0, HeatCurve.feelsLike(12.0, 80.0)!!, 0.001)
    }

    @Test
    fun `wind chill bites when it applies`() {
        assertEquals(-1.33, HeatCurve.feelsLike(5.0, 50.0)!!, 0.05)
    }

    @Test
    fun `no temperature from the bike means no answer`() {
        assertNull(HeatCurve.feelsLike(null, 50.0))
    }

    // ---------------------------------------------------------- the supply

    @Test
    fun `a stopped engine allows nothing`() {
        assertEquals(HeatCurve.Supply.ENGINE_OFF, HeatCurve.supply(13.8, 0))
        assertEquals(HeatCurve.Level.OFF, HeatCurve.cap(HeatCurve.Supply.ENGINE_OFF))
    }

    @Test
    fun `a running engine is judged on voltage`() {
        assertEquals(HeatCurve.Supply.FINE, HeatCurve.supply(14.2, 900))
        assertEquals(HeatCurve.Supply.STRAINED, HeatCurve.supply(12.6, 900))
        assertEquals(HeatCurve.Supply.CRITICAL, HeatCurve.supply(12.1, 900))
    }

    @Test
    fun `an unknown supply is not a reason to act`() {
        // Cutting the heat because a value went missing would turn a dropped
        // message into a cold rider.
        assertEquals(HeatCurve.Supply.FINE, HeatCurve.supply(null, 900))
        assertEquals(HeatCurve.Supply.FINE, HeatCurve.supply(14.0, null))
    }

    @Test
    fun `each supply state has a ceiling`() {
        assertEquals(HeatCurve.Level.HIGH, HeatCurve.cap(HeatCurve.Supply.FINE))
        assertEquals(HeatCurve.Level.MEDIUM, HeatCurve.cap(HeatCurve.Supply.STRAINED))
        assertEquals(HeatCurve.Level.LOW, HeatCurve.cap(HeatCurve.Supply.CRITICAL))
    }

    // ------------------------------------------------------- rate limiting

    @Test
    fun `an unchanged level is not resent`() {
        assertTrue(!HeatCurve.shouldApply(HeatCurve.Level.LOW, HeatCurve.Level.LOW, 0L))
    }

    @Test
    fun `the first level is always sent`() {
        assertTrue(HeatCurve.shouldApply(null, HeatCurve.Level.LOW, System.currentTimeMillis()))
    }

    @Test
    fun `two steps at once do not wait`() {
        // A change this size is a change of conditions — a tunnel, a stop — not
        // the boundary flutter the interval exists to damp.
        assertTrue(HeatCurve.shouldApply(HeatCurve.Level.OFF, HeatCurve.Level.MEDIUM,
                                         System.currentTimeMillis()))
    }

    @Test
    fun `one step waits out the interval`() {
        assertTrue(!HeatCurve.shouldApply(HeatCurve.Level.LOW, HeatCurve.Level.MEDIUM,
                                          System.currentTimeMillis()))
        assertTrue(HeatCurve.shouldApply(HeatCurve.Level.LOW, HeatCurve.Level.MEDIUM,
                                         System.currentTimeMillis() - 60_000L))
    }
}

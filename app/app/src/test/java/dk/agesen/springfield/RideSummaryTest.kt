package dk.agesen.springfield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The key-off card's figures (the owner, 2026-11-04): the whole ride across fuel
 * stops, shown when the key goes off, gone at the next key-on or a tap.
 */
class RideSummaryTest {

    private val min = 60_000L

    @Before
    fun clear() = RideSummary.clearForTest()

    private fun state(ign: Boolean?, at: Long, dm1: String? = "|4", fuel: Int? = null, moved: Boolean = true, km: Double? = null) =
        RideSummary.onState(ign, dm1, fuel, moved, km, at)

    @Test
    fun `the card comes up at key-off after a ride, and goes at the next key-on`() {
        state(true, 0L)
        assertFalse(RideSummary.pending)
        assertTrue("key-off is reported to the caller", state(false, 10 * min))
        assertTrue(RideSummary.pending)
        assertEquals(10 * min, RideSummary.endedAt)
        state(true, 20 * min)
        assertFalse(RideSummary.pending)
    }

    @Test
    fun `no card for a key-on and off in the garage`() {
        state(true, 0L, moved = false)
        state(false, min, moved = false)
        assertFalse(RideSummary.pending)
    }

    @Test
    fun `no card when the app meets a bike already switched off`() {
        state(false, 0L)
        assertFalse(RideSummary.pending)
    }

    @Test
    fun `a tap closes it, and the RIDE line opens it again`() {
        state(true, 0L); state(false, min)
        RideSummary.dismiss()
        assertFalse(RideSummary.pending)
        RideSummary.open()
        assertTrue(RideSummary.pending)
    }

    @Test
    fun `slip is the ride's, across a fuel stop where the board starts again`() {
        state(true, 0L)
        RideSummary.onFast(-5.0, 4.0, 2, 1_000L)
        state(false, 30 * min); state(true, 35 * min)
        RideSummary.onFast(0.0, 0.0, 2, 35 * min)       // the board's new leg, parked
        RideSummary.onFast(-1.0, 6.0, 2, 40 * min)
        assertEquals(-5.0, RideSummary.slipMin!!, 1e-9)
        assertEquals(6.0, RideSummary.slipMax!!, 1e-9)
    }

    @Test
    fun `no slip until the board sends any`() {
        RideSummary.onFast(null, null, 2, 0L)
        assertNull(RideSummary.slipMin)
    }

    @Test
    fun `every fault that stood with the ignition on, once each`() {
        state(true, 0L, dm1 = "520302:17:1|4")
        state(true, 1_000L, dm1 = "520302:17:1;100:1:1|4")
        state(true, 2_000L, dm1 = "|4")
        state(false, 3_000L, dm1 = "91:3:1|4")              // key off: not the ride's
        assertEquals(listOf(520302L to 17, 100L to 1), RideSummary.faultList.map { it.spn to it.fmi })
    }

    @Test
    fun `only a lasting loss of the broker counts, as the banner's rule`() {
        state(true, 0L)
        var t = 0L
        fun fast(net: Int, untilMs: Long) { while (t < untilMs) { RideSummary.onFast(null, null, net, t); t += 500L } }
        fast(2, 1 * min)
        fast(0, 1 * min + 30_000L)                         // a 30 s wake / handover
        fast(2, 2 * min)
        assertEquals(0L, RideSummary.noNetMs)
        fast(1, 12 * min)                                  // ten minutes: WiFi but no broker
        fast(2, 13 * min)
        assertEquals((10 * min).toDouble(), RideSummary.noNetMs.toDouble(), 1_000.0)
        assertTrue(RideSummary.netKnown)
    }

    @Test
    fun `a loss still open at key-off is counted`() {
        state(true, 0L)
        var t = 0L
        while (t < 5 * min) { RideSummary.onFast(null, null, 0, t); t += 500L }
        state(false, 5 * min)
        assertTrue(RideSummary.noNetMs >= 4 * min)
    }

    @Test
    fun `old firmware says nothing about the network`() {
        state(true, 0L)
        RideSummary.onFast(null, null, null, 0L)
        assertFalse(RideSummary.netKnown)
    }

    @Test
    fun `fuel used counts falls and not a fill-up`() {
        state(true, 0L, fuel = 80)
        state(true, 1_000L, fuel = 79)
        state(true, 2_000L, fuel = 80)                     // the float wobbling up: ignored
        state(true, 3_000L, fuel = 60)                     // 20 used
        state(true, 4_000L, fuel = 98)                     // the fuel stop
        state(true, 5_000L, fuel = 90)                     // 8 more
        assertEquals(28, RideSummary.fuelUsedPct)
        // 28 % of 20.8 l over 100 km
        assertEquals(5.824, RideSummary.economyL100(100.0, 20.8)!!, 1e-9)
    }

    @Test
    fun `too little ride or fuel for an economy figure`() {
        state(true, 0L, fuel = 80)
        state(true, 1_000L, fuel = 78)
        assertNull(RideSummary.economyL100(100.0, 20.8))   // 2 %: 0.4 l either way
        state(true, 2_000L, fuel = 70)
        assertNull(RideSummary.economyL100(5.0, 20.8))     // 5 km
    }

    @Test
    fun `the distance is kept for a card after an app restart`() {
        state(true, 0L, km = 42.5)
        state(true, 1_000L, km = null)                     // no trip reading in this state
        assertEquals(42.5, RideSummary.distanceKm!!, 1e-9)
    }

    @Test
    fun `a new ride forgets the old one, the card included`() {
        state(true, 0L, dm1 = "520302:17:1|4", fuel = 80, km = 10.0)
        RideSummary.onFast(-5.0, 4.0, 0, 0L)
        state(true, 1_000L, fuel = 70)
        state(false, 2 * min)
        RideSummary.reset(5 * 60 * min)
        assertFalse(RideSummary.pending)
        assertNull(RideSummary.slipMin)
        assertTrue(RideSummary.faultList.isEmpty())
        assertEquals(0, RideSummary.fuelUsedPct)
        assertNull(RideSummary.distanceKm)
        assertEquals(5 * 60 * min, RideSummary.startedAt)
    }
}

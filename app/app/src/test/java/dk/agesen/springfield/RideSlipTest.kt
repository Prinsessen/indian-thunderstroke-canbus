package dk.agesen.springfield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The REAR SLIP line keeps the ride's figures after the bike is switched off
 * (the owner, 2026-11-04: small print, read at a stop). Until then the line was
 * drawn only from a live packet and went blank 2.5 s after the link dropped.
 */
class RideSlipTest {

    private val ten = byteArrayOf(0, 0, 0, 0, 0, 'N'.code.toByte(), 0, 0, 0x7F, 2)
    private fun packet(min: Int, max: Int) = FastPacket.parse(ten + byteArrayOf(min.toByte(), max.toByte()))!!

    @Before
    fun clear() = RideSlip.clearForTest()

    @Test
    fun `the figures stay when no packet comes any more`() {
        RideSlip.feed(packet(-86, 95))
        // the key goes off: nothing more arrives, the page redraws from the stale tick
        assertEquals(-5.375, RideSlip.last!!.minKmh, 1e-9)
        assertEquals(5.9375, RideSlip.last!!.maxKmh, 1e-9)
    }

    @Test
    fun `a packet without slip changes nothing`() {
        RideSlip.feed(packet(-86, 95))
        RideSlip.feed(packet(0x7F, 0x7F))              // after a wake, before the ABS module's first reading
        RideSlip.feed(FastPacket.parse(ten)!!)          // older firmware
        assertEquals(RideSlip.Slip(-5.375, 5.9375), RideSlip.last)
    }

    @Test
    fun `the next ride's figures replace the last ride's`() {
        RideSlip.feed(packet(-86, 95))
        RideSlip.feed(packet(0, 0))                     // the board starts again at ignition: parked = 0
        assertEquals(RideSlip.Slip(0.0, 0.0), RideSlip.last)
    }

    @Test
    fun `nothing until the board has sent any`() {
        assertNull(RideSlip.last)
        RideSlip.feed(FastPacket.parse(ten)!!)
        assertNull(RideSlip.last)
    }
}

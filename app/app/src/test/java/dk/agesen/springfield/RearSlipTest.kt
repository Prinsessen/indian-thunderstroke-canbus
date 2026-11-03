package dk.agesen.springfield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rear wheel slip in bytes 10-11 of the fast packet (firmware 2026.11.03-1):
 * int8 in 1/16 km/h, the raw PGN 65215 byte 4 less 125, 0x7F unknown. The
 * figures are the extremes the August captures held (raw 39 and 220).
 */
class RearSlipTest {

    private val ten = byteArrayOf(0, 0, 0, 0, 0, 'N'.code.toByte(), 0, 0, 0x7F, 2)

    @Test
    fun `older packets have no slip`() {
        assertNull(FastPacket.parse(ten)!!.rearSlipMinKmh)
        assertNull(FastPacket.parse(ten.copyOf(9))!!.rearSlipMaxKmh)
    }

    @Test
    fun `the ride's extremes are read in km per hour, with their sign`() {
        val p = FastPacket.parse(ten + byteArrayOf((39 - 125).toByte(), (220 - 125).toByte()))!!
        assertEquals(-5.375, p.rearSlipMinKmh!!, 1e-9)
        assertEquals(5.9375, p.rearSlipMaxKmh!!, 1e-9)
        assertEquals(2, p.net)
    }

    @Test
    fun `0x7F is unknown, not plus eight`() {
        val p = FastPacket.parse(ten + byteArrayOf(0x7F, 0x7F))!!
        assertNull(p.rearSlipMinKmh)
        assertNull(p.rearSlipMaxKmh)
    }
}

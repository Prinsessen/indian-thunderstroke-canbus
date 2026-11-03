package dk.agesen.springfield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The "NO NETWORK" banner: the ride of 2026-11-03 left home with the hotspot
 * off, and the board went from the garage WiFi to nothing for six kilometres.
 */
class NetWatchTest {

    private val t0 = 1_793_719_487_000L   // 2026-11-03 15:24:47, link down

    @Test
    fun `home and unknown say nothing`() {
        val w = NetWatch()
        assertNull(w.update(NetWatch.HOME, t0))
        assertNull(w.update(null, t0 + 600_000))   // firmware without byte 9
    }

    @Test
    fun `a handover inside the grace says nothing`() {
        val w = NetWatch()
        assertNull(w.update(NetWatch.NONE, t0))
        assertNull(w.update(NetWatch.NONE, t0 + 55_000))     // 2026-09-15: 55 s to the hotspot
        assertNull(w.update(NetWatch.HOME, t0 + 56_000))
        assertNull(w.update(NetWatch.NONE, t0 + 100_000))    // a new loss starts its own grace
    }

    @Test
    fun `the hotspot left off is said after the grace`() {
        val w = NetWatch()
        w.update(NetWatch.NONE, t0)
        assertNull(w.update(NetWatch.NONE, t0 + NetWatch.GRACE_MS - 1))
        assertEquals(NetWatch.NONE, w.update(NetWatch.NONE, t0 + NetWatch.GRACE_MS))
        assertEquals(NetWatch.NONE, w.update(NetWatch.NONE, t0 + 342_000))   // 15:30:29, still nothing
    }

    @Test
    fun `wifi without the broker is its own message`() {
        val w = NetWatch()
        w.update(NetWatch.WIFI_ONLY, t0)
        assertEquals(NetWatch.WIFI_ONLY, w.update(NetWatch.WIFI_ONLY, t0 + NetWatch.GRACE_MS))
    }

    @Test
    fun `the broker answering clears it at once`() {
        val w = NetWatch()
        w.update(NetWatch.NONE, t0)
        w.update(NetWatch.NONE, t0 + 200_000)
        assertNull(w.update(NetWatch.HOME, t0 + 342_000))
    }

    @Test
    fun `a mute holds until the broker is reached, then the next loss warns again`() {
        val w = NetWatch()
        w.update(NetWatch.NONE, t0)
        w.update(NetWatch.NONE, t0 + NetWatch.GRACE_MS)
        w.mute()
        assertNull(w.update(NetWatch.NONE, t0 + 3_600_000))
        assertNull(w.update(NetWatch.HOME, t0 + 3_700_000))
        w.update(NetWatch.NONE, t0 + 3_800_000)
        assertEquals(NetWatch.NONE, w.update(NetWatch.NONE, t0 + 3_800_000 + NetWatch.GRACE_MS))
    }

    @Test
    fun `byte 9 is read from a ten-byte packet and ignored on older ones`() {
        val nine = byteArrayOf(0, 0, 0, 0, 0, 'N'.code.toByte(), 0, 0, 0x7F)
        assertNull(FastPacket.parse(nine)!!.net)
        assertEquals(0, FastPacket.parse(nine + byteArrayOf(0))!!.net)
        assertEquals(2, FastPacket.parse(nine + byteArrayOf(2))!!.net)
        assertNull(FastPacket.parse(nine + byteArrayOf(0xFF.toByte()))!!.net)
    }
}

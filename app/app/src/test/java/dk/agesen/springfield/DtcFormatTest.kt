package dk.agesen.springfield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The DM1 summary arrives in two forms and both have to parse.
 *
 * Readable over MQTT, where a person may be looking at it and there is no size
 * limit. Compact over BLE, where a notification carries 514 bytes and cannot
 * fragment — measured 2026-09-05, the rest of the state came to 468, leaving 38
 * characters for a fault summary whose lamp text alone was 34.
 *
 * These cases exist because the first attempt at telling the forms apart keyed
 * on the absence of "SPN", and a healthy bike reports "No active DTC | MIL:off
 * ..." — no SPN, and a bar. Every fault-free motorcycle would have had its lamp
 * section parsed as a number. The bug lived for four minutes because these
 * assertions were written before the code was trusted.
 */
class DtcFormatTest {

    @Test fun `readable healthy bike parses`() {
        val s = "No active DTC | MIL:off Stop:off Warn:ON Prot:off"
        assertTrue(Dtc.healthy(s))
        assertEquals(0, Dtc.parse(s).size)
        assertEquals(true, Dtc.warnLamp(s))
    }

    @Test fun `readable single fault parses`() {
        val s = "SPN 651 FMI 5 (x1) | MIL:ON Stop:off Warn:ON Prot:off"
        assertFalse(Dtc.healthy(s))
        assertEquals(1, Dtc.parse(s).size)
        assertEquals(651L, Dtc.parse(s)[0].spn)
        assertEquals(5, Dtc.parse(s)[0].fmi)
    }

    @Test fun `readable multiple faults parse`() {
        val s = "SPN 520250 FMI 8 (x2); SPN 904 FMI 12 (x1) | MIL:ON Stop:ON Warn:ON Prot:ON"
        assertEquals(2, Dtc.parse(s).size)
        assertEquals(2, Dtc.parse(s)[0].count)
    }

    @Test fun `compact healthy bike parses`() {
        val s = "|4"
        assertTrue(Dtc.healthy(s))
        assertEquals(0, Dtc.parse(s).size)
        assertEquals(true, Dtc.warnLamp(s))
    }

    @Test fun `compact faults parse`() {
        val s = "520250:8:2,904:12:1|5"
        assertFalse(Dtc.healthy(s))
        val f = Dtc.parse(s)
        assertEquals(2, f.size)
        assertEquals(520250L, f[0].spn); assertEquals(8, f[0].fmi); assertEquals(2, f[0].count)
        assertEquals(904L, f[1].spn);    assertEquals(12, f[1].fmi)
        assertEquals(true, Dtc.warnLamp(s))
    }

    @Test fun `compact lamps render as words for the diagnostics screen`() {
        assertEquals("MIL:ON Stop:off Warn:ON Prot:off", Dtc.lamps("651:5:1|5"))
        assertEquals("MIL:off Stop:off Warn:off Prot:off", Dtc.lamps("|0"))
    }

    @Test fun `machine page line never shows a raw compact string`() {
        // The regression this pins: "|4" went on screen verbatim, so the foot of
        // the machine page read as a bare vertical bar and a 4.
        assertEquals("No active DTC  |  MIL:off Stop:off Warn:ON Prot:off", Dtc.line("|4"))
        assertEquals("no diagnostics reported", Dtc.line(null))
        // Both encodings must render the same line for the same situation.
        assertEquals(
            Dtc.line("|4"),
            Dtc.line("No active DTC | MIL:off Stop:off Warn:ON Prot:off")
        )
        assertTrue(Dtc.line("520250:8:2|5").contains("MIL:ON"))
        assertFalse(Dtc.line("520250:8:2|5").startsWith("|"))
    }

    @Test fun `abs lamp is off when the bit is clear`() {
        assertEquals(false, Dtc.warnLamp("651:5:1|1"))
        assertEquals(false, Dtc.warnLamp("SPN 651 FMI 5 (x1) | MIL:ON Stop:off Warn:off Prot:off"))
    }

    @Test fun `nothing at all stays null rather than pretending`() {
        assertEquals(null, Dtc.warnLamp(null))
        assertEquals(0, Dtc.parse(null).size)
    }
}

package dk.agesen.springfield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fault-code tables and the order of authority between them.
 *
 * Two things here have already been wrong on a real bike. A gathered list had
 * SPN 520202 as an injector when the manual calls it the canister purge valve —
 * a confidently wrong component name sends someone to the wrong end of an
 * engine. And every fault was shown in red, including the ones the bike itself
 * keeps its lamp off for, which teaches a rider to wave the warnings away.
 *
 * Both are one assertion each now.
 */
class DtcTest {

    // Exactly what the firmware publishes, healthy and otherwise.
    private val healthy = "No active DTC | MIL:off Stop:off Warn:off Prot:off"
    private val oneFault = "SPN 84 FMI 2 (x3) | MIL:on Stop:off Warn:off Prot:off"
    private val twoFaults = "SPN 636 FMI 2 (x1); SPN 168 FMI 4 (x7) | MIL:on Stop:off"

    // ----------------------------------------------------------- parsing

    @Test
    fun `a healthy bus is recognised as healthy`() {
        assertTrue(Dtc.healthy(healthy))
        assertTrue(Dtc.parse(healthy).isEmpty())
        assertNull(Dtc.summary(healthy))
    }

    @Test
    fun `a fault is pulled apart correctly`() {
        val f = Dtc.parse(oneFault).single()
        assertEquals(84L, f.spn)
        assertEquals(2, f.fmi)
        assertEquals(3, f.count)
    }

    @Test
    fun `several faults in one message are all found`() {
        val f = Dtc.parse(twoFaults)
        assertEquals(2, f.size)
        assertEquals(636L, f[0].spn)
        assertEquals(168L, f[1].spn)
        assertEquals(7, f[1].count)
    }

    @Test
    fun `the lamps are kept separate from the faults`() {
        // A stored code with every lamp off is a different situation from one
        // with Stop lit, and only the bike knows which it is.
        assertEquals("MIL:on Stop:off Warn:off Prot:off", Dtc.lamps(oneFault))
    }

    @Test
    fun `a summary the app cannot parse is passed through, not hidden`() {
        // The app failing to understand a fault is not a reason to keep it from
        // the person riding the bike.
        val odd = "something no firmware has ever sent"
        assertEquals(odd, Dtc.summary(odd))
    }

    // ------------------------------------------------------------- naming

    @Test
    fun `the manual names the codes it covers`() {
        assertEquals("Crankshaft position sensor", Dtc.named(636L, null)?.first)
        assertEquals(Dtc.Source.MANUAL, Dtc.named(636L, null)?.second)
    }

    @Test
    fun `the manual beat the list that was circulating`() {
        // 520202 was gathered from forums as an injector driver. It is not.
        assertEquals("Canister purge valve", Dtc.named(520202L, null)?.first)
        assertEquals("ABS fail-safe relay", Dtc.named(520261L, null)?.first)
        assertEquals("Knock sensor positive line", Dtc.named(520331L, null)?.first)
    }

    @Test
    fun `the general standard fills in only where the manual is silent`() {
        val found = Dtc.named(100L, null)
        assertEquals("Engine oil pressure", found?.first)
        assertEquals(Dtc.Source.J1939_GENERIC, found?.second)
    }

    @Test
    fun `the rider outranks every table`() {
        // They may have been told something better by a dealer.
        val found = Dtc.named(636L, "Crank sensor, replaced Aug 26")
        assertEquals("Crank sensor, replaced Aug 26", found?.first)
        assertEquals(Dtc.Source.RIDER, found?.second)
    }

    @Test
    fun `a code nobody has a name for keeps its number`() {
        assertNull(Dtc.named(499999L, null))
        assertTrue(Dtc.describe(Dtc.Fault(499999L, 4, 1), null).contains("SPN 499999"))
    }

    // ---------------------------------------------------------- condition

    @Test
    fun `the manual's own wording beats the generic failure mode`() {
        // FMI 12 reads "component or ECU fault" from the FMI table, which sounds
        // like a dealer visit. The manual says the battery needs replacing: the
        // key fob wants a coin cell. Same code, and only one of those two
        // readings gets a rider home.
        assertEquals("Battery Voltage too Low (Replace)", Dtc.condition(520304L, 12))
        assertTrue(Dtc.describeShort(Dtc.Fault(520304L, 12, 1), null).contains("Battery Voltage too Low"))
    }

    @Test
    fun `the scanner code is carried alongside the pair`() {
        // A dealer works in SPN and FMI; a forum thread is titled after the
        // P-code. The rider should not have to translate between them.
        assertEquals("P0335", Dtc.pcode(636L, 2))
        assertEquals("P1633", Dtc.pcode(520304L, 12))
        assertTrue(Dtc.describe(Dtc.Fault(636L, 2, 1), null).contains("P0335"))
    }

    // ----------------------------------------------------------- severity

    @Test
    fun `a fault the bike does not light its own lamp for is not red`() {
        // 520321 FMI 3, tail light shorted to battery, lights the MIL.
        assertFalse(Dtc.lampOff(520321L, 3))
        // 520300 FMI 9, a tyre sensor reporting slowly, does not.
        assertTrue(Dtc.lampOff(520300L, 9))
        assertFalse(Dtc.urgent(520300L, 9))
    }

    @Test
    fun `a fault that strands the rider is red whatever the lamp does`() {
        // The MIL answers whether the engine will be harmed. A flat key fob
        // cannot harm an engine, so the lamp stays off — and the bike will not
        // start. That is the rider's question, and it is not the one the MIL is
        // answering.
        assertTrue(Dtc.lampOff(520304L, 12))
        assertTrue(Dtc.urgent(520304L, 12))
        assertTrue(Dtc.urgent(98L, 17))     // oil level low
        assertTrue(Dtc.urgent(520300L, 17)) // front tyre pressure low
    }

    @Test
    fun `a banner is only amber when every active fault is`() {
        assertTrue(Dtc.allLampsOff("SPN 520300 FMI 9 (x1) | MIL:off"))
        // One urgent fault among quiet ones still earns red.
        assertFalse(Dtc.allLampsOff("SPN 520300 FMI 9 (x1); SPN 84 FMI 2 (x1) | MIL:on"))
    }
}

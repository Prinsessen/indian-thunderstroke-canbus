package dk.agesen.springfield

/**
 * Turning `SPN 84 FMI 2 (x3)` into something a rider can act on.
 *
 * **There is nothing to translate the codes *into*.** Polaris display faults as
 * SPN/FMI on Ride Command and in Digital Wrench, and the service manuals list
 * them that way — unlike a car, there is no separate P-code system underneath.
 * The code is already the right code. What was missing is what it means.
 *
 * The tables below come from the owner's own reference for a 2017 Springfield
 * (Thunder Stroke 111), not from guesswork, which matters more here than
 * anywhere else in the app: a fault description that is confidently wrong sends
 * someone to the wrong part of the bike, and would be worse than the raw number
 * it replaced.
 *
 * Names are kept in English, as the source lists them, so the cluster stays in
 * one language and the terms match what a dealer's tool will say.
 *
 * SPNs at or above 520192 are the J1939 proprietary block, where Polaris define
 * their own meanings and no public list exists. The handful known here are
 * covered; the rest can be **named by the rider** and are then remembered — the
 * app builds its own table from the faults the bike actually throws, rather than
 * pretending to a completeness it has not got.
 */
object Dtc {

    /** Where the manufacturer-specific block begins in J1939. */
    private const val PROPRIETARY_FROM = 520192L

    data class Fault(val spn: Long, val fmi: Int, val count: Int)

    /**
     * The service manual's trouble-code table, transcribed whole.
     *
     * Polaris's own words for Polaris's own codes on this exact machine, and
     * with it the tier of gathered codes went away entirely. That list had been
     * useful when it was all there was, and it turned out to be wrong in eight
     * places and unsupported in ten more: 520202 is the canister purge valve,
     * not an injector; 520261 is the ABS fail-safe relay, not a throttle
     * actuator; 520331 is the knock sensor's positive line, not a cylinder. The
     * oxygen sensors are 3056, 520209, 520210 and 520333, and none of the
     * numbers that had been circulating for them appear in the manual at all.
     *
     * Keeping any of that beside the real table would have meant a rider seeing
     * a plausible component name for a fault that means something else — which
     * is the precise failure the tiers existed to prevent, so when the evidence
     * arrived the tier had to go rather than be demoted again.
     */
    private val MANUAL = mapOf(
        29L to "Accelerator position 2",
        51L to "Throttle position sensor 1",
        84L to "Vehicle speed signal",
        91L to "Accelerator position 1",
        96L to "Fuel level signal",
        98L to "Engine oil level sensor switch",
        102L to "Manifold absolute pressure sensor",
        105L to "Intake air temperature sensor",
        110L to "Engine temperature sensor",
        168L to "System power (battery / power input)",
        190L to "Engine speed",
        523L to "Gear sensor signal",
        527L to "Cruise control panel switches",
        596L to "Cruise control enable switch",
        598L to "Clutch switch signal",
        599L to "Cruise control set/decel switch",
        601L to "Cruise control resume/accel switch",
        628L to "ECU memory",
        636L to "Crankshaft position sensor",
        651L to "Injector 1",
        652L to "Injector 2",
        677L to "Starter solenoid driver circuit",
        731L to "Knock sensor 1",
        904L to "Wheel speed sensor (front)",
        907L to "Wheel speed sensor (rear)",
        1023L to "Trip sudden decelerations",
        1071L to "Fan relay driver",
        1268L to "Ignition coil primary driver 1",
        1269L to "Ignition coil primary driver 2",
        1347L to "Fuel pump driver circuit",
        2348L to "High beam lamp",
        2350L to "Low beam lamp",
        2367L to "Left turn indicator driver circuit",
        2369L to "Right turn indicator driver circuit",
        3056L to "Oxygen sensor 1 (front)",
        3597L to "ECU output supply voltage 1",
        3598L to "ECU output supply voltage 2",
        3599L to "ECU output supply voltage 3",
        5582L to "Static roll angle",
        65590L to "Misfire — cylinder not identified",
        65591L to "Misfire — cylinder 1",
        65592L to "Misfire — cylinder 2",
        65613L to "ETC accelerator position sensors 1 & 2 correlation",
        520198L to "Throttle position sensor 2",
        520200L to "Tipover sensor",
        520202L to "Canister purge valve",
        520204L to "Fuel correction — front (pre)",
        520205L to "Fuel correction — rear (post)",
        520208L to "Chassis/accessory relay",
        520209L to "Oxygen sensor heater 1 (pre, front)",
        520210L to "Oxygen sensor heater 2 (post, rear)",
        520250L to "ABS pulsar (front)",
        520251L to "ABS pulsar (rear)",
        520252L to "ABS solenoid (RRI)",
        520253L to "ABS solenoid (RRO)",
        520254L to "ABS solenoid (FFI)",
        520255L to "ABS solenoid (FFO)",
        520256L to "ABS solenoid (RFI)",
        520257L to "ABS solenoid (RFO)",
        520258L to "ABS actuator (front)",
        520259L to "ABS actuator (rear)",
        520260L to "ABS motor",
        520261L to "ABS fail-safe relay",
        520262L to "ABS source voltage",
        520263L to "ABS tyre size",
        520264L to "ABS ECU",
        520265L to "ABS module",
        520267L to "Kickstand switch",
        520275L to "Accelerator / brake position interaction",
        520276L to "Throttle position sensor (1 or 2 indeterminable)",
        520277L to "Throttle body control — power stage",
        520278L to "Throttle body control — return spring check failed",
        520279L to "Throttle body control — adaption aborted",
        520280L to "Throttle body control — limp home check failed",
        520281L to "Throttle body control — mechanical stop adaptation failure",
        520282L to "Throttle body control",
        520283L to "Throttle body control",
        520284L to "Throttle body control — position deviation",
        520285L to "Brake switch (1 or 2 indeterminable)",
        520226L to "ECU monitoring error",
        520287L to "ECU monitoring error (level 3)",
        520288L to "ECU monitoring of injection cut-off (level 1)",
        520289L to "ECU monitoring of injection cut-off (level 2)",
        520290L to "Controller option settings not programmed",
        520291L to "Left fog lamp",
        520292L to "Right fog lamp",
        520293L to "Horn",
        520294L to "Windshield motor driver",
        520295L to "Windshield motor switch",
        520296L to "Accelerometer",
        520297L to "System on button",
        520298L to "Heated grips",
        520299L to "Power lock motor",
        520300L to "Tyre pressure sensor (front)",
        520302L to "Tyre pressure sensor (rear)",
        520304L to "Key fob",
        520305L to "Throttle body control — requested angle not plausible",
        520311L to "ECU fault — hardware disruption",
        520312L to "Power lock motor switch",
        520313L to "ABS actuator (front)",
        520314L to "ABS actuator (rear)",
        520320L to "Brake light",
        520321L to "Tail light",
        520322L to "Front brake switch",
        520323L to "Rear brake switch",
        520329L to "Operator switch status (pOSS1)",
        520330L to "Immobiliser",
        520331L to "Knock sensor positive line",
        520332L to "Knock sensor negative line",
        520333L to "Oxygen sensor (pre, bank 2)",
        520336L to "ECU monitoring (pedal map mismatch)",
        524046L to "Start button",
        524079L to "Cruise control input checksum",
        524080L to "Cruise control input message counter",
        524083L to "Secondary air control valve"
    )

    /**
     * The P-code for a fault, where the manual gives one.
     *
     * Keyed on the pair, because the same component reports several and they
     * differ per failure mode: SPN 51 alone spans P0120 to P1123. This is the
     * number a generic scanner shows and the one a forum thread is titled
     * after, so it is what a rider will actually search for at a roadside —
     * the SPN/FMI pair is what a dealer wants, and the app should hand over
     * both rather than making someone translate between them.
     */
    private val PCODE = mapOf(
        29_02L to "P1225",
        29_03L to "P1228",
        29_04L to "P1227",
        51_00L to "P1123",
        51_01L to "P1122",
        51_02L to "P0121",
        51_03L to "P0123",
        51_04L to "P0122",
        51_10L to "P0120",
        51_13L to "P1120",
        84_00L to "P0500",
        84_01L to "P0502",
        84_02L to "P0503",
        84_08L to "P0501",
        84_09L to "P160A",
        84_19L to "P106B",
        91_02L to "P0225",
        91_03L to "P0228",
        91_04L to "P0227",
        96_02L to "P0461",
        96_03L to "P0463",
        96_04L to "P0462",
        96_16L to "P1462",
        96_18L to "P1463",
        98_03L to "P1527",
        98_04L to "P1526",
        98_17L to "P250F",
        102_02L to "P0106",
        102_03L to "P0108",
        102_04L to "P0107",
        102_07L to "P1106",
        102_10L to "P0109",
        105_02L to "P0111",
        105_03L to "P0113",
        105_04L to "P0112",
        105_10L to "P0114",
        110_00L to "P1217",
        110_02L to "P0116",
        110_03L to "P0118",
        110_04L to "P0117",
        110_10L to "P0119",
        110_15L to "P1116",
        110_16L to "P0217",
        110_17L to "P0128",
        168_00L to "P1562",
        168_01L to "P1563",
        168_03L to "P0563",
        168_04L to "P0562",
        168_16L to "P1564",
        168_18L to "P1565",
        190_00L to "P0219",
        190_01L to "C1060",
        190_02L to "C1061",
        190_07L to "P1219",
        190_19L to "C1066",
        190_31L to "P121C",
        523_02L to "P0914",
        523_03L to "P0917",
        523_04L to "P0916",
        523_09L to "P1914",
        527_31L to "P153D",
        596_31L to "P1590",
        598_02L to "P0704",
        599_31L to "P1591",
        601_31L to "P1592",
        628_12L to "P1602",
        636_02L to "P0335",
        636_08L to "P0336",
        651_03L to "P0262",
        651_04L to "P1262",
        651_05L to "P0261",
        652_03L to "P0265",
        652_04L to "P1265",
        652_05L to "P0264",
        677_03L to "P0617",
        677_04L to "P0616",
        677_05L to "P0615",
        731_04L to "P0327",
        904_02L to "C1031",
        904_05L to "C1030",
        907_02L to "C103D",
        907_03L to "C113D",
        907_04L to "C123D",
        907_05L to "C1036",
        907_08L to "C133D",
        907_14L to "C143D",
        1023_05L to "C1045",
        1071_03L to "P1482",
        1071_04L to "P1483",
        1071_05L to "P1481",
        1268_03L to "P1353",
        1268_04L to "P1361",
        1268_05L to "P1351",
        1269_03L to "P1354",
        1269_04L to "P1362",
        1269_05L to "P1352",
        1347_03L to "P0232",
        1347_04L to "P0231",
        1347_05L to "P0230",
        2348_05L to "C107E",
        2348_06L to "C107F",
        2350_05L to "C107B",
        2350_06L to "C107C",
        2367_03L to "P1715",
        2367_04L to "P1716",
        2367_05L to "P1714",
        2369_03L to "P1711",
        2369_04L to "P1712",
        2369_05L to "P1710",
        3056_02L to "P0130",
        3056_03L to "P0132",
        3056_04L to "P0131",
        3056_12L to "P113A",
        3597_00L to "P16A3",
        3597_01L to "P16A6",
        3597_03L to "P16A2",
        3597_04L to "P16A1",
        3597_16L to "P16A5",
        3597_18L to "P16A7",
        3598_00L to "P16AA",
        3598_01L to "P16AC",
        3598_03L to "P16A9",
        3598_04L to "P16A8",
        3598_16L to "P16AB",
        3598_18L to "P16AD",
        3599_00L to "P17AC",
        3599_01L to "P17AE",
        3599_03L to "P17AA",
        3599_04L to "P17AB",
        3599_16L to "P17AD",
        3599_18L to "P17AF",
        5582_09L to "P1062",
        65590_07L to "P0314",
        65591_07L to "P0301",
        65592_07L to "P0302",
        65613_02L to "P1135",
        520198_00L to "P1223",
        520198_01L to "P1222",
        520198_02L to "P0221",
        520198_03L to "P0223",
        520198_04L to "P0222",
        520198_10L to "P0220",
        520198_13L to "P1220",
        520200_02L to "P1501",
        520200_03L to "P1503",
        520200_04L to "P1502",
        520200_14L to "P1504",
        520202_03L to "P0443",
        520202_04L to "P0445",
        520202_05L to "P0444",
        520204_15L to "P0172",
        520204_17L to "P0171",
        520205_15L to "P0175",
        520205_17L to "P0174",
        520208_03L to "P1614",
        520208_04L to "P1613",
        520208_05L to "P1611",
        520209_02L to "P0135",
        520209_03L to "P0032",
        520209_04L to "P0031",
        520209_05L to "P0030",
        520210_02L to "P0141",
        520210_03L to "P0038",
        520210_04L to "P0037",
        520210_05L to "P0036",
        520226_31L to "P1540",
        520250_07L to "C1022",
        520251_07L to "C1023",
        520252_05L to "C1024",
        520253_05L to "C1025",
        520254_05L to "C1026",
        520255_05L to "C1027",
        520256_05L to "C1028",
        520257_05L to "C1029",
        520258_11L to "C1032",
        520259_11L to "C1033",
        520260_03L to "C1020",
        520260_04L to "C1021",
        520260_08L to "C0020",
        520261_07L to "C1034",
        520262_03L to "C1039",
        520262_04L to "C1038",
        520263_31L to "C1040",
        520264_12L to "C1041",
        520265_07L to "C1042",
        520267_31L to "P181C",
        520275_31L to "P150A",
        520276_02L to "P150C",
        520276_12L to "P150B",
        520277_02L to "P151A",
        520277_03L to "P150D",
        520277_04L to "P150E",
        520277_08L to "P151B",
        520277_31L to "P153F",
        520278_31L to "P151C",
        520279_31L to "P151D",
        520280_31L to "P151E",
        520282_31L to "P152B",
        520283_02L to "P152F",
        520283_03L to "P152C",
        520283_04L to "P152D",
        520284_31L to "P152E",
        520285_02L to "P153E",
        520287_31L to "P1541",
        520288_31L to "P1542",
        520289_31L to "P1543",
        520290_31L to "P1544",
        520291_05L to "C1075",
        520291_06L to "C1076",
        520292_05L to "C1078",
        520292_06L to "C1079",
        520293_05L to "C122A",
        520293_06L to "C122B",
        520294_05L to "C1222",
        520294_06L to "C1223",
        520295_02L to "C1225",
        520296_12L to "C1125",
        520297_31L to "C1530",
        520298_05L to "C1047",
        520298_06L to "C1048",
        520299_05L to "C1226",
        520299_06L to "C1227",
        520300_09L to "C1085",
        520300_12L to "C1083",
        520300_17L to "C1084",
        520302_09L to "C1090",
        520302_12L to "C1088",
        520302_17L to "C1089",
        520304_12L to "P1633",
        520305_31L to "P1530",
        520311_31L to "P1537",
        520312_31L to "C1229",
        520313_11L to "C103A",
        520314_11L to "C103B",
        520320_03L to "P1594",
        520320_04L to "P1595",
        520320_05L to "P1593",
        520321_03L to "P1597",
        520321_04L to "P1598",
        520321_05L to "P1596",
        520322_02L to "P159B",
        520322_03L to "P1599",
        520322_04L to "P159A",
        520323_02L to "P159E",
        520323_03L to "P159C",
        520323_04L to "P159D",
        520329_09L to "P1063",
        520330_09L to "P106A",
        520330_13L to "P1064",
        520331_03L to "P1327",
        520331_04L to "P1328",
        520332_03L to "P132A",
        520332_04L to "P132B",
        520333_02L to "P1136",
        520333_03L to "P1137",
        520333_04L to "P1138",
        520333_12L to "P1139",
        520336_31L to "P1545",
        524046_31L to "C1512",
        524079_31L to "U0405",
        524080_31L to "U1405",
        524083_03L to "P1076",
        524083_04L to "P1077",
        524083_05L to "P1075"
    )

    /**
     * The general J1939-71 numbering, for codes the manual does not list.
     *
     * Trimmed to what the manual leaves uncovered, and consulted last. Correct
     * for the standard, but the standard is not always what Polaris meant, so
     * it stays labelled when shown.
     */
    private val J1939 = mapOf(
        92L to "Engine percent load",
        94L to "Fuel delivery pressure",
        100L to "Engine oil pressure",
        106L to "Air inlet pressure",
        107L to "Air filter differential pressure",
        109L to "Coolant pressure",
        111L to "Coolant level",
        132L to "Air mass flow",
        158L to "Battery voltage, switched",
        171L to "Ambient air temperature",
        172L to "Air inlet temperature",
        174L to "Fuel temperature",
        175L to "Engine oil temperature",
        177L to "Transmission oil temperature",
        183L to "Engine fuel rate",
        247L to "Engine total hours",
        250L to "Total fuel used",
        512L to "Driver's demanded torque",
        513L to "Actual engine torque",
        515L to "Engine desired operating speed",
        524L to "Transmission selected gear",
        558L to "Accelerator pedal idle switch",
        563L to "ABS active",
        597L to "Brake switch",
        600L to "Cruise control coast switch",
        602L to "Cruise control accelerate switch",
        611L to "System diagnostic code, manufacturer",
        620L to "5 V sensor supply",
        627L to "Power supply",
        629L to "Controller #1 (ECU)",
        637L to "Engine timing sensor",
        639L to "J1939 network #1",
        653L to "Injector, cylinder 3",
        654L to "Injector, cylinder 4",
        723L to "Engine speed sensor #2",
        898L to "Requested engine speed",
        970L to "Auxiliary engine shutdown switch",
        1079L to "5 V sensor supply 1",
        1080L to "5 V sensor supply 2",
        1109L to "Engine protection shutdown warning",
        1110L to "Engine protection shutdown",
        1136L to "ECU temperature",
        1237L to "Engine shutdown override switch",
        1322L to "Misfire, multiple cylinders",
        1323L to "Misfire, cylinder 1",
        1324L to "Misfire, cylinder 2",
        1325L to "Misfire, cylinder 3",
        1326L to "Misfire, cylinder 4",
        3509L to "Sensor supply 1",
        3510L to "Sensor supply 2",
        3511L to "Sensor supply 3"
    )

    /**
     * Failure Mode Identifiers — the complete J1939-73 set.
     *
     * Phrased to finish the sentence "<component> — <fmi>", because the pair is
     * one statement rather than two facts.
     *
     * Unlike the SPNs, this table carries no risk: the FMI is a five-bit
     * enumeration fixed by the standard, identical on every J1939 machine ever
     * built, and Polaris have no room to mean something else by it. So all
     * twenty-one are here rather than only the ones the Springfield is known to
     * throw — a code arriving with FMI 9 should say what FMI 9 means.
     *
     * The severity words matter and are not padding. J1939 distinguishes three
     * degrees of "too high" (15 least, 16 moderate, 0 most) and three of "too
     * low", and collapsing them would throw away the bike's own judgement of
     * how bad it is.
     */
    private val FMI = mapOf(
        0 to "critically high",
        1 to "critically low",
        2 to "erratic or intermittent",
        3 to "voltage high, shorted to 12 V",
        4 to "voltage low, shorted to ground",
        5 to "open circuit",
        6 to "current high, shorted to ground",
        7 to "not responding mechanically",
        8 to "abnormal frequency or pulse width",
        9 to "abnormal update rate",
        10 to "abnormal rate of change",
        11 to "root cause unknown",
        12 to "component or ECU fault",
        13 to "out of calibration",
        14 to "special instructions",
        15 to "high, warning only",
        16 to "high, moderately severe",
        17 to "low, warning only",
        18 to "low, moderately severe",
        19 to "bad data received over the bus",
        20 to "drifted high",
        21 to "drifted low",
        31 to "condition exists"
    )



    /**
     * Pull the faults out of the firmware's summary string.
     *
     * Parsed rather than re-decoded: the bytes are turned into SPN/FMI once, on
     * the bike, and both openHAB and the app read that one result. Decoding the
     * raw frame a second time here would be a second implementation of the same
     * bit-shifting, free to drift from the first.
     */
    private val PATTERN = Regex("""SPN\s+(\d+)\s+FMI\s+(\d+)\s*\(x(\d+)\)""")

    fun parse(dm1: String?): List<Fault> {
        val text = dm1 ?: return emptyList()
        return PATTERN.findAll(text).mapNotNull { m ->
            val spn = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val fmi = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val n = m.groupValues[3].toIntOrNull() ?: 1
            Fault(spn, fmi, n)
        }.toList()
    }

    /** True when the summary says the bike has nothing active. */
    fun healthy(dm1: String?): Boolean =
        dm1 != null && dm1.startsWith("No active", ignoreCase = true)

    /** Where a name came from, because the tiers deserve different trust. */
    enum class Source { RIDER, MANUAL, J1939_GENERIC }

    /**
     * The best name available, and where it came from.
     *
     * A name the rider looked up for this bike beats the manual — they may have
     * been told something better by a dealer. The manual beats the general
     * standard. The general standard beats nothing at all.
     */
    fun named(spn: Long): Pair<String, Source>? = named(spn, Settings.dtcName(spn))

    /**
     * The same lookup, with the rider's own name handed in.
     *
     * Split for the same reason as the heat curve: the tables and their order of
     * authority are worth testing, and reading a preference from inside them
     * meant that could only happen on a device.
     */
    fun named(spn: Long, riderName: String?): Pair<String, Source>? {
        riderName?.let { return it to Source.RIDER }
        MANUAL[spn]?.let { return it to Source.MANUAL }
        J1939[spn]?.let { return it to Source.J1939_GENERIC }
        return null
    }

    /**
     * What the manual says is wrong, for this exact SPN and FMI.
     *
     * The FMI table gives the general shape of a failure; this gives the
     * specific one. The difference is not cosmetic. SPN 520304 FMI 12 renders
     * from the FMI table as "component or ECU fault", which sounds like a trip
     * to a dealer. The manual calls it a low battery needing replacement — the
     * key fob wants a coin cell. Same code, and only one of those two readings
     * gets you back on the road.
     */
    private val CONDITION = mapOf(
        29_02L to "Not Plausible",
        29_03L to "Voltage Too High",
        29_04L to "Voltage Too Low",
        51_00L to "Voltage Above Critical Level",
        51_01L to "Voltage Below Critical Level",
        51_02L to "Signal Out of Range (Not Plausible)",
        51_03L to "Voltage Too High",
        51_04L to "Voltage Too Low",
        51_10L to "Abnormal Rate of Change",
        51_13L to "Calibration / Adaption Failure",
        84_01L to "Vehicle Speed Too Low",
        84_08L to "Sensor Frequency Outside Normal Range",
        84_09L to "Abnormal Update Rate",
        91_02L to "Not Plausible",
        91_03L to "Voltage Too High",
        91_04L to "Voltage Too Low",
        96_02L to "Signal Fault",
        96_03L to "Voltage Too High",
        96_04L to "Voltage Too Low",
        96_16L to "Above Normal Operating Range",
        96_18L to "Below Normal Operating Range",
        98_03L to "Pressure Too High",
        98_04L to "Pressure Too Low",
        98_17L to "Oil Level Low",
        102_02L to "Signal Out of Range",
        102_03L to "Voltage Too High",
        102_04L to "Voltage Too Low",
        102_07L to "Pneumatic Fault",
        102_10L to "Abnormal Rate of Change",
        105_02L to "Signal Out of Range",
        105_03L to "Voltage Too High",
        105_04L to "Voltage Too Low",
        105_10L to "Abnormal Rate of Change",
        110_00L to "Engine Overheat Shutdown",
        110_02L to "Signal Out of Range",
        110_03L to "Voltage Too High",
        110_04L to "Voltage Too Low",
        110_10L to "Abnormal Rate of Change",
        110_15L to "Temperature Above Normal Range",
        110_16L to "Temperature Too High",
        168_00L to "Voltage Above Critical Level",
        168_01L to "Voltage Below Critical Level",
        168_16L to "Voltage Above Warning Level",
        168_18L to "Voltage Below Warning Level",
        190_00L to "Speed Exceeded Max Limit",
        190_01L to "Engine Speed Too Low",
        190_02L to "Data Erratic or Intermittent (or Missing)",
        190_07L to "CVT Threshold Exceeded",
        190_19L to "Received Engine Speed has Error",
        190_31L to "Error in Engine Speed Computation",
        523_02L to "Signal Fault",
        523_03L to "Voltage Too High",
        523_04L to "Voltage Too Low",
        523_09L to "Abnormal Update Rate",
        527_31L to "Switch/Switches Stuck",
        596_31L to "Switch Stuck",
        598_02L to "Signal Fault",
        599_31L to "Switch Stuck",
        601_31L to "Switch Stuck",
        628_12L to "EEPROM Read / Write Failure",
        636_02L to "Plausibility Fault",
        636_08L to "Circuit Fault",
        651_03L to "Driver Circuit Short to B+",
        651_04L to "Driver Circuit Grounded",
        651_05L to "Driver Circuit Open/Grounded",
        652_03L to "Driver Circuit Short to B+",
        652_04L to "Driver Circuit Grounded",
        652_05L to "Driver Circuit Open/Grounded",
        677_03L to "Driver Circuit Short to B+",
        677_04L to "Driver Circuit Grounded",
        677_05L to "Driver Circuit Open/Grounded",
        731_04L to "Voltage Too Low",
        904_02L to "Input Abnormal / Signal Failure",
        904_05L to "Open / Short",
        907_02L to "Plausibility Fault",
        907_03L to "Short to B+",
        907_04L to "Open/Short to GND",
        907_05L to "Open/Short",
        907_08L to "Abnormal Frequency",
        907_14L to "Incorrect Sensor / Improper Mounting",
        1023_05L to "Open / Short",
        1071_03L to "Driver Circuit Short to B+",
        1071_04L to "Driver Circuit Grounded",
        1071_05L to "Driver Circuit Open/Grounded",
        1268_03L to "Driver Circuit Short to B+",
        1268_04L to "Driver Circuit Grounded",
        1268_05L to "Driver Circuit Open/Grounded",
        1269_03L to "Driver Circuit Short to B+",
        1269_04L to "Driver Circuit Grounded",
        1269_05L to "Driver Circuit Open/Grounded",
        1347_03L to "Driver Circuit Short to B+",
        1347_04L to "Driver Circuit Grounded",
        1347_05L to "Driver Circuit Open/Grounded",
        2348_05L to "Open Circuit / Short to B+",
        2348_06L to "Grounded Circuit",
        2350_05L to "Open Circuit / Short to B+",
        2350_06L to "Grounded Circuit",
        2367_03L to "Driver Circuit Short to B+",
        2367_04L to "Driver Circuit Grounded",
        2367_05L to "Driver Circuit Open/Grounded",
        2369_03L to "Driver Circuit Short to B+",
        2369_04L to "Driver Circuit Grounded",
        2369_05L to "Driver Circuit Open/Grounded",
        3056_02L to "Signal Fault",
        3056_03L to "Voltage High",
        3056_04L to "Voltage Low",
        3056_12L to "Bad Component",
        5582_09L to "Abnormal Update Rate",
        65590_07L to "Misfire Detected",
        65591_07L to "Misfire Detected",
        65592_07L to "Misfire Detected",
        65613_02L to "Correlation Fault",
        520198_00L to "Voltage Above Critical Level",
        520198_01L to "Voltage Below Critical Level",
        520198_02L to "Signal Out of Range (Not Plausible)",
        520198_03L to "Voltage Too High",
        520198_04L to "Voltage Too Low",
        520198_10L to "Abnormal Rate of Change",
        520198_13L to "Calibration / Adaption Failure",
        520200_02L to "Signal Fault",
        520200_03L to "Voltage High",
        520200_04L to "Voltage Low",
        520200_14L to "Condition Exists (tip over condition detected)",
        520202_03L to "Driver Circuit Short to B+",
        520202_04L to "Driver Circuit Grounded",
        520202_05L to "Driver Circuit Open/Grounded",
        520204_15L to "System Too Rich 1 (Front) (Pre)",
        520204_17L to "System Too Lean 1 (Front) (Pre)",
        520205_15L to "System Too Rich 2 (Rear) (Post)",
        520205_17L to "System Too Lean 2 (Rear) (Post)",
        520208_03L to "Driver Circuit Short to B+",
        520208_04L to "Driver Circuit Grounded",
        520208_05L to "Driver Circuit Open/Grounded",
        520209_02L to "Plausibility Fault",
        520209_03L to "Driver Circuit Short to B+",
        520209_04L to "Driver Circuit Grounded",
        520209_05L to "Driver Circuit Open/Grounded",
        520210_02L to "Plausibility Fault",
        520210_03L to "Driver Circuit Short to B+",
        520210_04L to "Driver Circuit Grounded",
        520210_05L to "Driver Circuit Open/Grounded",
        520226_31L to "Condition Exists",
        520250_07L to "COG Chip",
        520251_07L to "COG Chip",
        520252_05L to "Open / Short",
        520253_05L to "Open / Short",
        520254_05L to "Open/Short",
        520255_05L to "Open/Short",
        520256_05L to "Open/Short",
        520257_05L to "Open/Short",
        520258_11L to "Wheel Lock (or VSS failure) ABS On",
        520259_11L to "Wheel Lock (or VSS failure) ABS On",
        520260_03L to "Off Stick",
        520260_04L to "On Stick",
        520260_08L to "Motor Lock",
        520261_07L to "On/Off Stick",
        520262_03L to "Raise",
        520262_04L to "Drop",
        520263_31L to "Irregular Tire Size",
        520264_12L to "ECU Error",
        520265_07L to "Incomplete Evacuation and Fill",
        520267_31L to "Condition Exists (engine disabled due to extended kickstand)",
        520275_31L to "Condition Exists",
        520276_02L to "Position Sensor Correlation Fault (One okay, one failed)",
        520276_12L to "Neither Position Sensor Passed Test",
        520277_02L to "Not Plausible",
        520277_04L to "Minimum",
        520277_08L to "Signal Error",
        520277_31L to "Deactivated power stages due to 5V sensor supply error",
        520278_31L to "Spring Check Failed",
        520279_31L to "Adaption Aborted",
        520280_31L to "520280",
        520282_31L to "Condition Exists",
        520283_02L to "Outside of Pedal Range(Level 1)",
        520283_03L to "Maximum",
        520283_04L to "Minimum",
        520284_31L to "Condition Exists",
        520285_02L to "Brake Switch Correlation Fault",
        520287_31L to "Condition Exists",
        520288_31L to "Condition Exists",
        520289_31L to "Condition Exists",
        520290_31L to "Condition Exists",
        520291_05L to "Open Circuit / Short to B+",
        520291_06L to "Grounded Circuit",
        520292_05L to "Open Circuit / Short to B+",
        520292_06L to "Grounded Circuit",
        520293_05L to "Open Circuit / Short to B+",
        520293_06L to "Grounded Circuit",
        520294_05L to "Open Circuit / Short to B+",
        520294_06L to "Grounded Circuit",
        520295_02L to "Both inputs are closed",
        520296_12L to "Bad Component",
        520297_31L to "Switch Stuck",
        520298_05L to "Open Circuit / Short to B+",
        520298_06L to "Grounded Circuit",
        520299_05L to "Open Circuit / Short to B+",
        520299_06L to "Grounded Circuit",
        520300_09L to "Abnormal Update Rate",
        520300_12L to "Battery Voltage too Low (Replace)",
        520300_17L to "Pressure to Low",
        520302_09L to "Abnormal Update Rate",
        520302_12L to "Battery Voltage too Low (Replace)",
        520302_17L to "Pressure to Low",
        520304_12L to "Battery Voltage too Low (Replace)",
        520305_31L to "Condition Exists",
        520311_31L to "Condition Exists",
        520312_31L to "Switch Stuck",
        520313_11L to "Wheel Lock (or VSS failure) ABS Off",
        520314_11L to "Wheel Lock (or VSS failure) ABS Off",
        520320_03L to "Shorted to Battery",
        520320_04L to "Shorted to Ground",
        520320_05L to "Open Circuit",
        520321_03L to "Shorted to Battery",
        520321_04L to "Shorted to Ground",
        520321_05L to "Open Circuit",
        520322_02L to "Signal Fault",
        520322_03L to "Voltage Too High",
        520322_04L to "Voltage Too Low",
        520323_02L to "Signal Fault",
        520323_03L to "Voltage Too High",
        520323_04L to "Voltage Too Low",
        520329_09L to "Abnormal Update Rate",
        520330_09L to "Abnormal Update Rate",
        520330_13L to "Out of Calibration",
        520331_03L to "Voltage Too High",
        520331_04L to "Voltage Too Low",
        520332_03L to "Voltage Too High",
        520332_04L to "Voltage Too Low",
        520333_02L to "Signal Fault",
        520333_04L to "Voltage Low",
        520333_12L to "Bad Component",
        520336_31L to "Condition Exists",
        524046_31L to "Switch Stuck",
        524079_31L to "Checksum does not match",
        524080_31L to "Counter not incremented",
        524083_03L to "Shorted to Battery",
        524083_04L to "Shorted to Ground",
        524083_05L to "Open Circuit"

    )

    /**
     * Whether the bike lights its own check-engine lamp for this fault.
     *
     * From the manual's MIL column, and 24 of the 249 documented conditions
     * have it off. Those are the ones the bike deliberately does not interrupt
     * you about — a key fob battery, a tyre pressure sensor going flat, coolant
     * merely warm. Treating them as red was the app shouting where the
     * manufacturer chose not to, and a warning that overstates itself is one a
     * rider learns to wave away.
     */
    private val MIL_OFF = setOf(
        98_17L,
        110_00L,
        110_15L,
        110_16L,
        168_03L,
        168_04L,
        168_18L,
        190_01L,
        190_02L,
        190_19L,
        2367_03L,
        2367_04L,
        2367_05L,
        2369_03L,
        2369_04L,
        2369_05L,
        520300_09L,
        520300_12L,
        520300_17L,
        520302_09L,
        520302_12L,
        520302_17L,
        520304_12L
    )

    /** The manufacturer's own description of this fault, if the manual has one. */
    fun condition(spn: Long, fmi: Int): String? = CONDITION[spn * 100 + fmi]

    /** True when the bike itself would not light the check-engine lamp. */
    fun lampOff(spn: Long, fmi: Int): Boolean = (spn * 100 + fmi) in MIL_OFF

    /**
     * Faults that strand or endanger the rider, whatever the lamp does.
     *
     * The MIL answers one question — will the engine be harmed — and Polaris
     * answer it well. It does not answer the question the rider has, which is
     * whether the day is about to end. Those come apart, and the key fob is the
     * clearest case: a flat coin cell cannot hurt the engine, so the lamp stays
     * off, and you still do not get home.
     *
     * Deliberately short. Every entry has to strand the machine or hurt someone,
     * or the list becomes a way of quietly undoing the manufacturer's judgement
     * one code at a time.
     */
    private val STRANDS = setOf(
        520304_12L,   // key fob battery — the bike will not start
        168_04L,      // system power low — the same, one step further along
        98_17L,       // oil level low — ride on and the engine pays
        110_16L,      // engine temperature too high
        520300_17L,   // front tyre pressure low
        520302_17L    // rear tyre pressure low
    )

    /**
     * Whether this fault deserves the red banner.
     *
     * The lamp decides, except where the fault ends the ride regardless.
     */
    fun urgent(spn: Long, fmi: Int): Boolean =
        !lampOff(spn, fmi) || (spn * 100 + fmi) in STRANDS

    /** The scanner code for this fault, if the manual lists one. */
    fun pcode(spn: Long, fmi: Int): String? = PCODE[spn * 100 + fmi]

    fun proprietary(spn: Long): Boolean = spn >= PROPRIETARY_FROM

    /**
     * One fault as a sentence.
     *
     * The SPN is kept alongside the description rather than replaced by it. A
     * rider ringing a dealer needs the number, and a description that hid it
     * would make the app worse at the one moment it matters most.
     */
    fun describe(f: Fault): String = describe(f, Settings.dtcName(f.spn))

    /**
     * The same sentence, with the rider's own name handed in.
     *
     * Split for the third time in this file, and the reason is worth stating
     * once properly: a function that fetches its own inputs from a global cannot
     * be tested without standing up that global. named() was split and these two
     * were not, so three tests reached Settings through the back door and failed
     * on an uninitialised SharedPreferences — which is the test suite correctly
     * reporting that the seam was in the wrong place.
     */
    fun describe(f: Fault, riderName: String?): String {
        val found = named(f.spn, riderName)
        val component = found?.first
            ?: if (proprietary(f.spn)) "Unknown Polaris code" else "Unknown code"
        // The manual's own words first; the FMI table is the fallback for a
        // pair it does not document.
        val mode = condition(f.spn, f.fmi) ?: FMI[f.fmi] ?: "FMI ${f.fmi}"
        val times = if (f.count > 1) " ×${f.count}" else ""
        // Only the generic tier is marked. The other two are either about this
        // bike or were written by the person reading the screen, and labelling
        // those would be noise; this one is a hint from the wider standard and
        // should not be mistaken for a diagnosis.
        // The manual and the rider's own names need no caveat; the other two do.
        // A rider about to buy a part should know whether the component name
        // came from Polaris or from someone's best guess.
        val tier = if (found?.second == Source.J1939_GENERIC) "  · generic J1939" else ""
        // The P-code alongside the pair, not instead of it. A dealer works in
        // SPN/FMI and a forum thread is titled after the P-code, and a rider at
        // the roadside should not have to translate between the two.
        val p = pcode(f.spn, f.fmi)?.let { " $it" } ?: ""
        return "$component — $mode  [SPN ${f.spn} FMI ${f.fmi}$p]$times$tier"
    }

    /** The same, short enough for a banner that has one line. */
    fun describeShort(f: Fault): String = describeShort(f, Settings.dtcName(f.spn))

    /** The short form, with the rider's own name handed in. */
    fun describeShort(f: Fault, riderName: String?): String {
        val component = named(f.spn, riderName)?.first ?: "SPN ${f.spn}"
        val mode = condition(f.spn, f.fmi) ?: FMI[f.fmi] ?: "FMI ${f.fmi}"
        return "$component — $mode"
    }

    /**
     * Every active fault, or null when there are none to describe.
     *
     * Falls back to the firmware's own string when nothing parses. That happens
     * for a summary this version has never seen, and showing the raw text beats
     * showing nothing — the app not understanding a fault is not a reason to
     * hide it from the person riding the bike.
     */
    fun summary(dm1: String?, short: Boolean = false): String? {
        if (dm1 == null || healthy(dm1)) return null
        val faults = parse(dm1)
        if (faults.isEmpty()) return dm1
        return faults.joinToString("; ") { if (short) describeShort(it) else describe(it) }
    }

    /**
     * True when every active fault is one the bike keeps its own lamp off for.
     *
     * The manufacturer already decided which faults are worth interrupting a
     * ride over. Where they chose not to, the app has no business being redder
     * than the machine — and a warning that overstates itself is one a rider
     * learns to wave away, which costs the real ones their meaning.
     */
    fun allLampsOff(dm1: String?): Boolean {
        val faults = parse(dm1)
        return faults.isNotEmpty() && faults.none { urgent(it.spn, it.fmi) }
    }

    /**
     * The four DM1 lamps, if the summary carries them.
     *
     * MIL, Stop, Warning and Protect are the bike's own severity judgement, and
     * worth keeping separate from the fault list: a stored code with every lamp
     * off is a different situation from one with Stop lit, and only the bike
     * knows which it is.
     */
    fun lamps(dm1: String?): String? =
        dm1?.substringAfter('|', "")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The amber warning lamp, which on this machine is the ABS lamp.
     *
     * Established on the road 2026-09-05: the owner watched Warn go out and
     * follow the amber ABS lamp as the wheels came up to speed. It had shown ON
     * with no fault behind it for weeks, and every look until then had been at
     * a parked bike -- where the ABS cannot self-test and so cannot clear.
     *
     * Costs nothing to read. The DM1 summary already crosses BLE for the fault
     * list, so the lamp comes free in a string the app is receiving anyway.
     *
     * Returns null when the bus has not said, which is not the same as off.
     */
    fun warnLamp(dm1: String?): Boolean? = when {
        dm1 == null -> null
        dm1.contains("Warn:ON", ignoreCase = true) -> true
        dm1.contains("Warn:off", ignoreCase = true) -> false
        else -> null
    }
}

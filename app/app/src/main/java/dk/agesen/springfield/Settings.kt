package dk.agesen.springfield

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo

/**
 * Everything the rider can change, in one place.
 *
 * Values that shape how the instruments read — tyre targets, where the redline
 * sits, which units — were previously constants scattered through the views.
 * That was fine while there was one reader of this app; it is not fine now that
 * any of them might be wrong for a different bike, a different tyre, or a rider
 * who thinks in miles.
 *
 * Nothing here is validated beyond clamping. A rider who wants 45 PSI in the
 * front knows something the app does not.
 */
object Settings {

    private const val PREFS = "settings"

    private const val K_TARGET_FRONT = "target_front"
    private const val K_TARGET_REAR = "target_rear"
    private const val K_REDLINE = "redline_rpm"
    private const val K_PRESSURE = "unit_pressure"
    private const val K_DISTANCE = "unit_distance"
    private const val K_TEMPERATURE = "unit_temperature"
    private const val K_ORIENTATION = "orientation"
    private const val K_TRIP_START = "trip_start_km"
    private const val K_IGN_WAS_ON = "ignition_was_on"
    private const val K_IGN_OFF_AT = "ignition_off_at"
    private const val K_LAST_STATE = "last_state_at"
    private const val K_BRIGHT = "force_bright"
    private const val K_BEST_SPEED = "best_speed_kmh"
    private const val K_BEST_RPM = "best_rpm"
    private const val K_TANK = "tank_litres"
    private const val K_SVC_INTERVAL = "service_interval_km"
    private const val K_SVC_LAST = "service_last_km"
    private const val K_FUEL_PCT = "last_fuel_pct"
    private const val K_DTC = "dtc_name_"
    private const val K_HEAT_AUTO = "heat_auto"
    private const val K_HEAT_OFF = "heat_off_"
    private const val K_HEAT_FULL = "heat_full_"
    private const val K_HEAT_MAC = "heat_mac_"

    /** Cold targets for the Springfield, per the rider. */
    const val DEFAULT_TARGET_FRONT = 36.0
    const val DEFAULT_TARGET_REAR = 41.0

    /** Thunder Stroke 111. Adjustable because it is a guess about an engine. */
    const val DEFAULT_REDLINE = 5000

    private lateinit var prefs: SharedPreferences
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ready = true
    }

    // -------------------------------------------------------------- tyres

    var targetFront: Double
        get() = prefs.getFloat(K_TARGET_FRONT, DEFAULT_TARGET_FRONT.toFloat()).toDouble()
        set(v) { prefs.edit().putFloat(K_TARGET_FRONT, v.coerceIn(20.0, 60.0).toFloat()).apply() }

    var targetRear: Double
        get() = prefs.getFloat(K_TARGET_REAR, DEFAULT_TARGET_REAR.toFloat()).toDouble()
        set(v) { prefs.edit().putFloat(K_TARGET_REAR, v.coerceIn(20.0, 60.0).toFloat()).apply() }

    // ------------------------------------------------------------- engine

    /**
     * Where the tachometer turns red, the edge glow builds, and the haptic
     * fires. One number, three consumers — which is the reason it moved here
     * from three separate constants that could drift apart.
     */
    var redlineRpm: Int
        get() = prefs.getInt(K_REDLINE, DEFAULT_REDLINE)
        set(v) { prefs.edit().putInt(K_REDLINE, v.coerceIn(3000, 6000)).apply() }

    /** The dial's full scale, kept a round step above the redline. */
    val maxRpm: Int get() = ((redlineRpm / 1000) + 1) * 1000

    /** Where the edge glow starts building, well before the limit. */
    val warnRpm: Int get() = redlineRpm - 800

    // -------------------------------------------------------------- units
    //
    // Three independent choices, not one "imperial" switch. A Danish rider uses
    // km/h and Celsius and still sets tyres in PSI or bar — pressure has its own
    // convention in every workshop, and tying it to the others would force a
    // wrong answer on somebody. Everything is stored canonically (km, °C, PSI)
    // and converted only for display, so switching units never rounds a stored
    // value away.

    enum class Pressure(val label: String, val fromPsi: Double, val step: Double, val decimals: Int) {
        PSI("PSI", 1.0, 0.5, 1),
        KPA("kPa", 6.894757, 5.0, 0),
        BAR("bar", 0.0689476, 0.05, 2)
    }

    enum class Distance(val speedLabel: String, val distanceLabel: String, val fromKm: Double) {
        METRIC("KM/H", "KM", 1.0),
        IMPERIAL("MPH", "MI", 0.621371)
    }

    enum class Temperature(val label: String) { CELSIUS("°C"), FAHRENHEIT("°F") }

    var pressureUnit: Pressure
        get() = Pressure.entries.getOrElse(prefs.getInt(K_PRESSURE, 0)) { Pressure.PSI }
        set(v) { prefs.edit().putInt(K_PRESSURE, v.ordinal).apply() }

    var distanceUnit: Distance
        get() = Distance.entries.getOrElse(prefs.getInt(K_DISTANCE, 0)) { Distance.METRIC }
        set(v) { prefs.edit().putInt(K_DISTANCE, v.ordinal).apply() }

    var temperatureUnit: Temperature
        get() = Temperature.entries.getOrElse(prefs.getInt(K_TEMPERATURE, 0)) { Temperature.CELSIUS }
        set(v) { prefs.edit().putInt(K_TEMPERATURE, v.ordinal).apply() }

    // ---- conversions, all from the canonical unit ---------------------------

    fun speed(kmh: Double): Double = kmh * distanceUnit.fromKm
    fun distance(km: Double): Double = km * distanceUnit.fromKm
    val speedLabel: String get() = distanceUnit.speedLabel
    val distanceLabel: String get() = distanceUnit.distanceLabel

    fun temperature(celsius: Double): Double =
        if (temperatureUnit == Temperature.FAHRENHEIT) celsius * 9.0 / 5.0 + 32.0 else celsius
    val temperatureLabel: String get() = temperatureUnit.label

    /**
     * Fuel economy, and the one conversion in the app that turns a scale upside
     * down.
     *
     * Litres per 100 km counts *down* to good; miles per gallon counts up. So
     * this is not a multiplication like the others — it is a reciprocal, and
     * everything drawn from it has to flip with it: the range of the gauge, and
     * which end of it is the one worth warning about.
     *
     * US gallons, because that is what an Indian's own Ride Command shows and
     * what the bike was designed against. An imperial gallon would read 20 %
     * better for no reason anybody could see.
     */
    private const val L100_TO_US_MPG = 235.2145

    fun economy(l100: Double): Double =
        if (distanceUnit == Distance.IMPERIAL) {
            // A stationary engine reports zero consumption, which is infinite
            // economy. Zero is the honest thing to draw for it, not infinity.
            if (l100 <= 0.05) 0.0 else L100_TO_US_MPG / l100
        } else l100

    val economyLabel: String get() =
        if (distanceUnit == Distance.IMPERIAL) "MPG" else "l/100"

    /** Full scale, in whichever direction the unit runs. */
    val economyScaleMax: Double get() =
        if (distanceUnit == Distance.IMPERIAL) 80.0 else 15.0

    fun pressure(psi: Double): Double = psi * pressureUnit.fromPsi
    fun pressureToPsi(shown: Double): Double = shown / pressureUnit.fromPsi
    val pressureLabel: String get() = pressureUnit.label

    /** Full scale of the speedometer, in whatever units are showing. */
    val maxSpeed: Float
        get() = if (distanceUnit == Distance.IMPERIAL) 120f else 200f

    /** Tick spacing that gives round numbers on either scale. */
    val speedMinorTick: Int get() = if (distanceUnit == Distance.IMPERIAL) 5 else 10
    val speedMajorTick: Int get() = 20

    // -------------------------------------------------------- brightness

    /**
     * Force the screen to full brightness while the cluster is showing.
     *
     * Off by default. In direct sun a phone on auto-brightness is unreadable
     * behind a visor, and this is the fix — but at night on a dark instrument
     * panel full brightness is dazzling, and it costs battery all day. Which of
     * those matters is the rider's call on the day, not a default.
     */
    var forceBright: Boolean
        get() = prefs.getBoolean(K_BRIGHT, false)
        set(v) { prefs.edit().putBoolean(K_BRIGHT, v).apply() }

    // -------------------------------------------------------- orientation

    /** 0 = follow the phone, 1 = portrait, 2 = landscape. */
    var orientation: Int
        get() = prefs.getInt(K_ORIENTATION, 0)
        set(v) { prefs.edit().putInt(K_ORIENTATION, v.coerceIn(0, 2)).apply() }

    val orientationName: String
        get() = when (orientation) {
            1 -> "Portrait"
            2 -> "Landscape"
            else -> "Follow phone"
        }

    /**
     * A mount holds the phone one way up. Auto-rotate on a bike answers to
     * bumps and lean angle as readily as to intent, and a cluster that flips
     * mid-corner is worse than one in the wrong orientation.
     */
    val requestedOrientation: Int
        get() = when (orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

    // ------------------------------------------------------- heated clothing

    var heatAuto: Boolean
        get() = prefs.getBoolean(K_HEAT_AUTO, true)
        set(v) { prefs.edit().putBoolean(K_HEAT_AUTO, v).apply() }

    /**
     * Curve endpoints per zone, in felt degrees.
     *
     * 25 to 10 for both zones, which is what a season of riding settled on.
     *
     * The first defaults staggered the two — legs leading the jacket, on the
     * reasoning that a cruiser's screen shelters the torso while the legs stand
     * in the wind off the front wheel. Sound enough on paper, and wrong in
     * practice: they also started far too cold, full heat at zero, which meant
     * the kit did almost nothing on the evenings anyone would actually reach for
     * it. One cold ride taught the right numbers better than the reasoning did.
     *
     * Both zones the same now, and still adjustable per zone for anyone whose
     * legs and torso disagree.
     */
    private fun defaultOff(zone: HeatCurve.Zone) = 25.0
    private fun defaultFull(zone: HeatCurve.Zone) = 10.0

    fun heatOffAt(zone: HeatCurve.Zone): Double =
        prefs.getFloat(K_HEAT_OFF + zone.name, defaultOff(zone).toFloat()).toDouble()

    /**
     * The two endpoints hold each other apart.
     *
     * They used to clamp independently, which let "off at" be set below "full
     * at". HeatCurve refuses an inverted curve — correctly, since it describes
     * nothing — but the automatic loop then skipped the zone silently, and the
     * panel had no idea why. A settings combination that switches a feature off
     * without saying so is worse than one the steppers refuse to reach.
     *
     * Three degrees is the least that leaves the three bands distinguishable.
     */
    private const val MIN_SPAN_C = 3.0

    fun setHeatOffAt(zone: HeatCurve.Zone, v: Double) {
        val floor = heatFullAt(zone) + MIN_SPAN_C
        prefs.edit().putFloat(K_HEAT_OFF + zone.name,
            v.coerceIn(-10.0, 25.0).coerceAtLeast(floor).toFloat()).apply()
    }

    fun heatFullAt(zone: HeatCurve.Zone): Double =
        prefs.getFloat(K_HEAT_FULL + zone.name, defaultFull(zone).toFloat()).toDouble()

    fun setHeatFullAt(zone: HeatCurve.Zone, v: Double) {
        val ceiling = heatOffAt(zone) - MIN_SPAN_C
        prefs.edit().putFloat(K_HEAT_FULL + zone.name,
            v.coerceIn(-25.0, 20.0).coerceAtMost(ceiling).toFloat()).apply()
    }

    /**
     * Which controller drives which zone, by MAC address.
     *
     * The app cannot tell a jacket from a pair of trousers — both answer to the
     * same service and both advertise the same name — so the rider assigns them
     * once and the app remembers.
     */
    fun heatMac(zone: HeatCurve.Zone): String =
        prefs.getString(K_HEAT_MAC + zone.name, "") ?: ""

    fun setHeatMac(zone: HeatCurve.Zone, mac: String) {
        prefs.edit().putString(K_HEAT_MAC + zone.name, mac.trim().uppercase()).apply()
    }

    // ---------------------------------------------------------------- fuel

    /**
     * Tank capacity in litres. The Springfield's is about 20.8.
     *
     * A setting because it is the one figure in the range calculation the app
     * cannot learn from the bus, and the one that would be silently wrong on any
     * other machine.
     */
    var tankLitres: Double
        get() = prefs.getFloat(K_TANK, 20.8f).toDouble()
        set(v) { prefs.edit().putFloat(K_TANK, v.coerceIn(5.0, 60.0).toFloat()).apply() }

    /**
     * The last fuel level measured while the bike was upright and moving.
     *
     * Kept so the pre-ride check can say something about a bike that has not
     * moved yet. -1 until one has ever been taken.
     */
    var lastFuelPct: Int
        get() = prefs.getInt(K_FUEL_PCT, -1)
        set(v) { prefs.edit().putInt(K_FUEL_PCT, v).apply() }

    // ----------------------------------------------------------------- dtc

    /**
     * The rider's own name for a fault code.
     *
     * The service manual's table covers this bike, but not everything a dealer
     * might tell you — and a code outside it should not stay nameless. So the
     * rider's own name outranks every table: look one up once, name it, and it
     * is named from then on.
     */
    fun dtcName(spn: Long): String? =
        // Guarded because this is the one Settings lookup reachable from a pure
        // JVM unit test. Dtc.line() -> summary() -> describe(f) -> here, and a
        // test has no Android context to call init() with, so prefs is still
        // lateinit and every fault-formatting test dies on the lookup rather
        // than on anything it was written to check. It has now broken
        // DtcFormatTest twice, each time through a call chain that did not
        // exist when it was last fixed, so the guard goes at the bottom of the
        // chain instead of at the top of each new test.
        //
        // Returning null is not swallowing the error, it is the correct answer:
        // no preferences means no rider-supplied name for this code, and
        // describe() falls back to the manual's table. Nor can it hide a real
        // missing init() -- every other accessor in this object reads prefs
        // directly and would throw long before a fault code needed naming.
        if (!::prefs.isInitialized) null
        else prefs.getString(K_DTC + spn, null)?.takeIf { it.isNotBlank() }

    fun setDtcName(spn: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) prefs.edit().remove(K_DTC + spn).apply()
        else prefs.edit().putString(K_DTC + spn, trimmed).apply()
    }

    // ------------------------------------------------------------- service

    /**
     * Distance between services. Indian specify 8 000 km for the Thunder Stroke.
     *
     * A setting rather than a constant because an interval is a decision — a
     * bike ridden hard, or on short winter trips, earns oil sooner than the book
     * says, and the book is not the authority on someone else's machine.
     */
    var serviceIntervalKm: Int
        get() = prefs.getInt(K_SVC_INTERVAL, 8_000)
        set(v) { prefs.edit().putInt(K_SVC_INTERVAL, v.coerceIn(1_000, 30_000)).apply() }

    /**
     * Odometer reading at the last service, or -1 if never recorded.
     *
     * Held here only as a fallback. The bike is the right owner of this number —
     * see [Service] — and once the firmware carries it, this becomes a cache of
     * what the bike said rather than the truth itself.
     */
    var serviceLastKm: Int
        get() = prefs.getInt(K_SVC_LAST, -1)
        set(v) { prefs.edit().putInt(K_SVC_LAST, v).apply() }

    // ------------------------------------------------------------- records

    /**
     * All-time highs, kept across restarts.
     *
     * Separate from the ride figures on purpose: a top speed is only interesting
     * next to the ride it happened on, and an all-time one only next to every
     * ride. Mixing them produces a number that answers neither question.
     *
     * Both are resettable, and worth knowing they exist: an all-time top speed
     * stored on a phone is a record of how fast this bike has been ridden.
     */
    var bestSpeedKmh: Double
        get() = prefs.getFloat(K_BEST_SPEED, 0f).toDouble()
        set(v) { prefs.edit().putFloat(K_BEST_SPEED, v.toFloat()).apply() }

    var bestRpm: Int
        get() = prefs.getInt(K_BEST_RPM, 0)
        set(v) { prefs.edit().putInt(K_BEST_RPM, v).apply() }

    fun resetRecords() {
        prefs.edit().remove(K_BEST_SPEED).remove(K_BEST_RPM).apply()
    }

    // ---------------------------------------------------------------- trip

    /**
     * The odometer reading when this ride was zeroed.
     *
     * The bike's own trip meter cannot be reset from here — the CAN interface
     * is listen-only and always will be. So the app keeps its own mark and
     * subtracts, which gives a ride distance that is independent of whatever
     * the bike's trip happens to be doing.
     */
    private var tripStartKm: Float
        get() = prefs.getFloat(K_TRIP_START, Float.NaN)
        set(v) { prefs.edit().putFloat(K_TRIP_START, v).apply() }

    /**
     * Ignition as last seen, and when it went off.
     *
     * Persisted rather than held in memory because the app is killed and
     * restarted freely, and a ride that spans that must not silently become two.
     * See [RideTrip].
     */
    var ignitionWasOn: Boolean
        get() = prefs.getBoolean(K_IGN_WAS_ON, false)
        set(v) { prefs.edit().putBoolean(K_IGN_WAS_ON, v).apply() }

    var ignitionOffAt: Long
        get() = prefs.getLong(K_IGN_OFF_AT, 0L)
        set(v) { prefs.edit().putLong(K_IGN_OFF_AT, v).apply() }

    /** When the app last heard anything at all from the bike. See [RideTrip]. */
    var lastStateAt: Long
        get() = prefs.getLong(K_LAST_STATE, 0L)
        set(v) { prefs.edit().putLong(K_LAST_STATE, v).apply() }

    /** True once a ride start has ever been marked. */
    val hasTripStart: Boolean get() = !tripStartKm.isNaN()

    fun resetTrip(currentTripKm: Double?) {
        if (currentTripKm != null) tripStartKm = currentTripKm.toFloat()
    }

    /** Distance since the last reset, or null if never reset or no reading. */
    fun rideDistanceKm(currentTripKm: Double?): Double? {
        val start = tripStartKm
        if (currentTripKm == null || start.isNaN()) return null
        // The bike's own trip can be zeroed at the bars, which would leave our
        // mark above it. Treat that as a fresh start rather than showing a
        // negative distance.
        val delta = currentTripKm - start
        return if (delta < 0) null else delta
    }
}

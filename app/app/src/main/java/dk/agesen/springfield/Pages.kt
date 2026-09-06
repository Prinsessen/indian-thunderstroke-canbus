package dk.agesen.springfield

import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * The three pages, kept together because each is a thin renderer over
 * BikeRepository and none of them holds state of its own.
 *
 * That is deliberate: fragments are destroyed and recreated on every rotation,
 * and the BLE link lives in a service that knows nothing about them. A page
 * that cached its own values would show stale numbers after a rotation, or
 * worse, blank ones. Every page rebinds from the repository in onBikeUpdate()
 * and in onResume(), so a fresh page is immediately correct.
 */
abstract class BikePage(private val layout: Int) : Fragment(), BikeRepository.Observer {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(layout, container, false)

    override fun onResume() {
        super.onResume()
        BikeRepository.addObserver(this)
        onBikeUpdate()          // a page arriving mid-ride must not wait for the next frame
    }

    override fun onPause() {
        BikeRepository.removeObserver(this)
        super.onPause()
    }
}

// ---------------------------------------------------------------- RIDE

class RideFragment : BikePage(R.layout.fragment_ride) {

    // Speed is the primary instrument and always holds the main face. Landscape
    // adds the tachometer beside it; portrait reduces the tacho to a digital
    // figure instead. All looked up as nullable, so one layout file per
    // orientation is the only difference — no branching on configuration.
    private val primary get() = view?.findViewById<GaugeView>(R.id.gauge)
    private val tacho get() = view?.findViewById<GaugeView>(R.id.gauge2)
    private val rpmDigits get() = view?.findViewById<DigitalReadoutView>(R.id.readout)
    private val tellTales get() = view?.findViewById<TellTaleView>(R.id.telltales)
    private val fuel get() = view?.findViewById<FuelBarView>(R.id.fuel)
    private val grips get() = view?.findViewById<GripsView>(R.id.grips)

    // Remembered so a change can be detected; a gear or a redline crossing is an
    // event, and the app only hears a stream of states.
    private var lastGear: String? = null
    private var wasRedline = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Long-pressing the ride figure starts it again. The automatic rule
        // covers the ordinary case -- a new ride after the bike has slept -- and
        // this is for the times a rider means something else by "this ride":
        // the start of a leg, the far end of the outward run, a border.
        val resetRide = {
            Settings.resetTrip(BikeRepository.state?.tripKm)
            onBikeUpdate()
        }
        rpmDigits?.onRideReset = resetRide   // portrait: beside the rev figure
        primary?.onRideReset = resetRide     // landscape: on the speed dial
    }

    override fun onBikeUpdate() {
        // A dead link means the values are unknown, not unchanged. Passing null
        // makes every instrument fall back to the same dashes and dark lamps it
        // shows before the bus has ever spoken, which is honest: a needle held
        // at 80 by a dropped connection is the one reading that could actually
        // mislead a rider.
        val live = BikeRepository.isLive
        val fast = if (live) BikeRepository.fast else null
        val state = if (live) BikeRepository.state else null
        val twoDials = tacho != null

        primary?.apply {
            dial = GaugeView.Dial.SPEED
            introDelay = Cluster.STAGGER_TACHO
            // With a pair on screen each dial takes one side, so the arrows end
            // up at the outer edges. Alone, it carries both.
            turnSide = if (twoDials) GaugeView.TurnSide.LEFT else GaugeView.TurnSide.BOTH
            speedKmh = fast?.speedKmh
            gear = fast?.gear
            // Landscape only: portrait's speed dial has the digital readout
            // below it carrying the same figure, and printing it twice on one
            // screen would just be noise.
            rideKm = if (twoDials) Settings.rideDistanceKm(BikeRepository.state?.tripKm)
                     else null
            setTurnSignals(fast?.indLeft, fast?.indRight)
        }

        tacho?.apply {
            dial = GaugeView.Dial.RPM
            ignition = state?.ignitionOn
            throttlePct = fast?.throttlePct ?: state?.throttlePct
            introDelay = Cluster.STAGGER_SPEEDO
            turnSide = GaugeView.TurnSide.RIGHT
            rpm = fast?.rpm
            setTurnSignals(fast?.indLeft, fast?.indRight)
        }

        rpmDigits?.apply {
            abs = Dtc.warnLamp(state?.dm1)
            ignition = state?.ignitionOn
            throttlePct = fast?.throttlePct ?: state?.throttlePct
            rpm = fast?.rpm
            // The app's own ride distance, not the bike's trip meter — the CAN
            // interface is listen-only, so the bike's trip can never be reset
            // from here, but ours can.
            // Deliberately NOT gated on the link being live, unlike everything
            // else on this page.
            //
            // A needle held at 80 by a dropped connection is the one reading
            // that can genuinely mislead, which is why the rest goes blank. This
            // is not a live measurement though -- it is the app's own arithmetic
            // over how far you have ridden, and that does not stop being true
            // because the phone lost contact in a car park. Blanking it hid the
            // number at exactly the moment a rider reaches for the phone to
            // read it: after switching off.
            // No fallback to the bike's own trip: that put four thousand
            // kilometres on screen under a RIDE label. Nothing is the honest
            // answer until the app has met the bike.
            tripKm = Settings.rideDistanceKm(BikeRepository.state?.tripKm)
        }

        feedback(fast)

        // Fuel and headlight both ride on the 1 Hz JSON rather than the fast
        // packet. Neither needs ten updates a second, and the packet's two spare
        // bits are better kept for something that does.
        FuelRange.feed(state?.fuelEconomy)
        fuel?.apply {
            // The bar shows the sender, because a gauge that disagreed with the
            // bike's own would be its own kind of lie. The range is a decision,
            // so it uses the filtered level — see FuelLevel.
            fuelPct = state?.fuelPct
            rangeText = FuelRange.format(
                FuelLevel.trusted, Settings.tankLitres,
                { Settings.distance(it) }, Settings.distanceLabel
            )
        }

        // The grips are the bike's, so they belong on the bike's page. Their two
        // temperatures are measured rather than inferred -- with the heat off
        // they read the air at the bar, which is nearer the rider's hands than
        // the ambient sensor is.
        grips?.apply {
            level = state?.gripLevel
            leftC = state?.gripLeftC
            rightC = state?.gripRightC
        }
        tellTales?.setStates(
            fast, state?.headlight, state?.stand,
            cruiseText = state?.cruise, cruiseEnableText = state?.cruiseEnable,
            hazardText = state?.hazard, brakeText = state?.brakeRear,
            cruiseSwText = state?.cruiseSw, sidestandText = state?.standDown
        )
    }

    /**
     * A short tick on the two things worth feeling rather than reading: a gear
     * change, and crossing into the redline.
     *
     * Deliberately sparse. Haptics stop meaning anything the moment they fire
     * for everything, and a glove dulls the difference between a subtle buzz and
     * none at all — so these are the only two, and the redline gets the heavier
     * one. Edge-triggered on purpose: the redline pulse fires on the crossing,
     * not for every frame spent above it.
     */
    private fun feedback(fast: FastPacket?) {
        val gear = fast?.gear
        if (gear != null && lastGear != null && gear != lastGear) tick(18, 90)
        if (gear != null) lastGear = gear

        val rpm = fast?.rpm
        if (rpm != null) {
            val now = rpm >= Settings.redlineRpm
            if (now && !wasRedline) tick(45, 180)
            wasRedline = now
        }
    }

    private fun tick(millis: Long, amplitude: Int) {
        val ctx = context ?: return
        val manager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager ?: return
        val vibrator = manager.defaultVibrator
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(millis, amplitude))
    }
}

// --------------------------------------------------------------- TYRES

class TyresFragment : BikePage(R.layout.fragment_tyres) {

    override fun onBikeUpdate() {
        val v = view ?: return
        val reading = TyreMemory.last()

        v.findViewById<TyreView>(R.id.front).apply {
            label = "FRONT"
            wheel = reading?.front
        }
        v.findViewById<TyreView>(R.id.rear).apply {
            label = "REAR"
            wheel = reading?.rear
        }

        // Age is the headline on this page, not a footnote. TPMS sensors sleep
        // with the wheels, so a rider standing at the bike is nearly always
        // looking at a reading from the last ride — saying how old it is turns a
        // misleading number into a useful one.
        // A slow leak only shows across weeks, and only on cold-corrected
        // figures — comparing raw readings taken at different tyre temperatures
        // would produce a trend that is mostly weather.
        val trend = TyreMemory.trend()
        v.findViewById<TextView>(R.id.tyreTrend).text = when {
            trend == null -> ""
            kotlin.math.abs(trend.frontPsiPerWeek) < 0.4 &&
                kotlin.math.abs(trend.rearPsiPerWeek) < 0.4 ->
                "holding steady over %.0f days".format(trend.spanDays)
            else -> "over %.0f days:  front %+.1f  rear %+.1f  %s / week".format(
                trend.spanDays,
                Settings.pressure(trend.frontPsiPerWeek),
                Settings.pressure(trend.rearPsiPerWeek),
                Settings.pressureLabel
            )
        }

        val ambient = reading?.ambientC
        v.findViewById<TextView>(R.id.tyreAge).text = when {
            reading == null -> "no reading yet · sensors wake once the wheels turn"
            ambient == null ->
                "measured ${TyreMemory.formatAge(reading.ageMillis)} · no ambient, showing raw pressure"
            else ->
                "measured ${TyreMemory.formatAge(reading.ageMillis)} · corrected to ${"%.0f".format(ambient)}°C ambient"
        }
    }
}

// ------------------------------------------------------------- MACHINE

class MachineFragment : BikePage(R.layout.fragment_machine) {

    override fun onBikeUpdate() {
        val v = view ?: return
        val s = if (BikeRepository.isLive) BikeRepository.state else null

        // Configured here rather than once in onViewCreated: the unit setting can
        // change while this page is alive — returning from settings does not
        // recreate the fragment — and a Fahrenheit reading on a Celsius scale
        // would sit at the wrong place on the dial without looking wrong.
        val deg = Settings.temperatureLabel
        fun t(c: Double) = Settings.temperature(c)

        // Scales and caution bands are per-quantity: "low" is bad for economy
        // and good for coolant, so a shared rule would be wrong for one of them.
        // Economy is the one gauge whose caution band changes ends with the
        // unit: thirsty is a high number in l/100 and a low one in mpg. Naming
        // the band by the quantity rather than by the end of the scale is what
        // keeps that from becoming a gauge that warns about good economy.
        val thirsty = 10.0                       // l/100km worth noticing
        v.findViewById<MiniGaugeView>(R.id.gFuel).configure(
            "ECONOMY", Settings.economyLabel,
            0.0, Settings.economyScaleMax, decimals = 1,
            cautionFrom = if (Settings.distanceUnit == Settings.Distance.IMPERIAL) 0.0
                          else Settings.economy(thirsty),
            cautionTo = if (Settings.distanceUnit == Settings.Distance.IMPERIAL)
                          Settings.economy(thirsty) else Settings.economyScaleMax
        )
        // Not coolant. This engine is air-cooled and has none; Indian reuses the
        // J1939 coolant slot for engine temperature, and the captures say oil:
        // it climbs over eight to ten minutes and settles at 100-115 C, where a
        // cylinder head on an air-cooled twin would run 150-200. The caution
        // band starts at 120 rather than 105 for the same reason -- 105 is
        // ordinary running temperature for oil, and a gauge that shows amber
        // every time the bike is warm teaches the rider to ignore it.
        v.findViewById<MiniGaugeView>(R.id.gCoolant)
            // CYL HEAD, not OIL. Corrected 2026-09-05 from the service manual: this
            // engine has exactly one temperature sensor, the CHT on the rear face of
            // the front cylinder head, and no oil temperature sensor at all. The
            // gauge was briefly labelled OIL TEMP on the correct observation that an
            // air-cooled bike has no coolant, and the wrong follow-through.
            .configure("CYL HEAD", deg, t(0.0), t(140.0), cautionFrom = t(120.0), cautionTo = t(140.0))
        v.findViewById<MiniGaugeView>(R.id.gBattery)
            .configure("BATTERY", "V", 10.0, 16.0, decimals = 1, cautionFrom = 10.0, cautionTo = 12.0)
        v.findViewById<MiniGaugeView>(R.id.gAmbient)
            .configure("AMBIENT", deg, t(-20.0), t(50.0))
        // Instantaneous against the running average beside it. The pair is the
        // point: the average tells you what the tank is doing, this tells you
        // what your right hand is doing to it.
        v.findViewById<MiniGaugeView>(R.id.gEconNow)
            .configure("NOW", Settings.economyLabel, 0.0,
                       if (Settings.distanceUnit == Settings.Distance.IMPERIAL)
                           Settings.economy(thirsty) else Settings.economyScaleMax,
                       decimals = 1)
        // Litres per hour. Where economy in l/100km goes to infinity -- idling,
        // walking pace, stuck in traffic -- this one still means something.
        v.findViewById<MiniGaugeView>(R.id.gFuelRate)
            .configure("FUEL RATE", "L/h", 0.0, 20.0, decimals = 1)

        v.findViewById<MiniGaugeView>(R.id.gFuel).value = s?.fuelEconomy?.let { Settings.economy(it) }
        v.findViewById<MiniGaugeView>(R.id.gCoolant).value = s?.coolantC?.let { t(it.toDouble()) }
        v.findViewById<MiniGaugeView>(R.id.gBattery).value = s?.batteryV
        v.findViewById<MiniGaugeView>(R.id.gAmbient).value = s?.ambientC?.let { t(it) }
        v.findViewById<MiniGaugeView>(R.id.gEconNow).value =
            s?.fuelEconInst?.let { Settings.economy(it) }
        v.findViewById<MiniGaugeView>(R.id.gFuelRate).value = s?.fuelRate

        v.findViewById<TextView>(R.id.odo).text = s?.odometerKm?.let {
            "%,.0f %s".format(Settings.distance(it.toDouble()), Settings.distanceLabel)
        } ?: "— ${Settings.distanceLabel}"

        // Service sits with the odometer because that is the number it is
        // measured against, and a distance to service printed away from the
        // distance travelled is a figure nobody can check.
        v.findViewById<TextView>(R.id.service).apply {
            text = Service.summary(s)
            setTextColor(
                resources.getColor(
                    when (Service.status(s)) {
                        Service.Status.OVERDUE, Service.Status.DUE -> R.color.act
                        Service.Status.SOON -> R.color.watch
                        else -> R.color.muted
                    }, null
                )
            )
        }

        // Ride figures: distance since the reset, top speed, average while
        // actually moving, and how long that took. Parts are omitted rather than
        // shown as zero until there is enough of a ride to mean something.
        val rideKm = Settings.rideDistanceKm(s?.tripKm)
        val parts = mutableListOf<String>()
        rideKm?.let { parts += "%.1f %s".format(Settings.distance(it), Settings.distanceLabel) }
        if (RideStats.maxSpeedKmh > 0) {
            parts += "max %.0f".format(Settings.speed(RideStats.maxSpeedKmh))
        }
        RideStats.averageKmh(rideKm)?.let { parts += "avg %.0f".format(Settings.speed(it)) }
        RideStats.movingTime()?.let { parts += it }
        v.findViewById<TextView>(R.id.ridestats).text =
            if (parts.isEmpty()) "" else "RIDE  " + parts.joinToString("  ·  ")

        // All-time highs on their own line. A ride's top speed and every ride's
        // top speed answer different questions, and putting them on one line
        // would invite reading one as the other.
        val best = mutableListOf<String>()
        if (Settings.bestSpeedKmh > 0) {
            best += "%.0f %s".format(Settings.speed(Settings.bestSpeedKmh), Settings.speedLabel)
        }
        if (Settings.bestRpm > 0) best += "%,d rpm".format(Settings.bestRpm)
        v.findViewById<TextView>(R.id.records).text =
            if (best.isEmpty()) "" else "BEST  " + best.joinToString("  ·  ")

        // Rendered through Dtc, never printed raw. This line used to print the
        // firmware's string directly and looked right only by accident: the
        // readable form happened to be a sentence. Since 2026.09.05-56 the radio
        // carries the compact form, and a healthy bike sends "|4" — the lamp
        // bits and nothing else — which put a bare "|4" at the foot of the page.
        //
        // An empty line still means the bus has not said anything yet, not that
        // the bike is healthy; those are different and the wording keeps them so.
        v.findViewById<TextView>(R.id.dtc).text = Dtc.line(s?.dm1)
    }
}

// ---------------------------------------------------------------- HEAT

/**
 * Heated clothing.
 *
 * The page is complete; the drivers behind it are not. Until the Keis protocol
 * is captured the zones will show "not connected" and refuse to send anything,
 * which is the honest state rather than a pretence of control.
 */
class HeatFragment : BikePage(R.layout.fragment_heat), Keis.Observer {

    private val legs get() = view?.findViewById<HeatZoneView>(R.id.zoneLegs)
    private val jacket get() = view?.findViewById<HeatZoneView>(R.id.zoneJacket)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        legs?.apply { zone = HeatCurve.Zone.LEGS; title = "TROUSERS + SOCKS" }
        jacket?.apply { zone = HeatCurve.Zone.JACKET; title = "JACKET" }
    }

    override fun onResume() {
        super.onResume()
        Keis.addObserver(this)
        onKeisUpdate()
    }

    override fun onPause() {
        Keis.removeObserver(this)
        super.onPause()
    }

    override fun onKeisUpdate() = onBikeUpdate()

    override fun onBikeUpdate() {
        val v = view ?: return
        val live = BikeRepository.isLive
        val ambient = if (live) BikeRepository.state?.ambientC else null
        val speed = if (live) BikeRepository.fast?.speedKmh else null
        val felt = HeatCurve.feelsLike(ambient, speed)

        // The header draws the inference and its inputs together; the fragment
        // just hands it the numbers.
        v.findViewById<FeltTempView>(R.id.felt).apply {
            this.feltC = felt
            this.ambientC = ambient
            this.speedKmh = speed
        }

        legs?.refresh()
        jacket?.refresh()

        // The zone the app cannot reach still gets told about -- but a rider who
        // has already turned the grips up has told us their hands are cold, and
        // a measurement beats the inference. Saying "cold enough for the gloves"
        // to someone who reached for the heat a minute ago is the app talking
        // over them.
        val gripLevel = if (live) BikeRepository.state?.gripLevel else null
        v.findViewById<TextView>(R.id.gloves).text = when {
            gripLevel != null && gripLevel > 0 ->
                "Grips at $gripLevel — the gloves are on their own controller"
            HeatCurve.glovesWanted(felt) ->
                "Cold enough for the gloves — they are on their own controller"
            else -> ""
        }

        // An override nobody can see reads as a fault, so the supply cap
        // announces itself before anything else on the page.
        val supplyNote = when (Keis.supply) {
            HeatCurve.Supply.ENGINE_OFF -> "Engine off — heat held at zero, nothing is charging"
            HeatCurve.Supply.CRITICAL -> "Battery low — heat limited to protect the bike"
            HeatCurve.Supply.STRAINED -> "Charging system under strain — heat capped"
            HeatCurve.Supply.FINE -> null
        }

        // Assigned but unreachable is a different problem from never assigned,
        // and telling them apart is most of what a status line is for.
        val unassigned = HeatCurve.Zone.entries.filter { Settings.heatMac(it).isBlank() }
        v.findViewById<TextView>(R.id.heatStatus).text = when {
            supplyNote != null -> supplyNote
            unassigned.size == 2 -> "No controllers assigned — pick them in settings"
            unassigned.isNotEmpty() -> "${unassigned.first().name.lowercase()} controller not assigned"
            else -> ""
        }
    }
}

package dk.agesen.springfield

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Raw link state, for when the app and openHAB disagree.
 *
 * The two sides cannot legitimately differ — both come from one struct and one
 * serialiser in the firmware — so a disagreement means the decode is wrong or
 * the bytes are. Answering which, from three machines away, needs the bytes
 * themselves, and this is where they are.
 *
 * Reached by long-pressing the firmware line in settings. Not a tab: a rider
 * has no use for it, and a screen nobody needs should not cost a swipe.
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reachable only by long-pressing the firmware line in settings, so
        // Settings has always been initialised in practice — but relying on the
        // route someone took to get here is how a crash arrives the first time
        // there is a second route. init() is idempotent.
        Settings.init(this)

        // The one screen you read in a car park with a torch in the other hand.
        window.attributes = window.attributes.apply {
            screenBrightness =
                if (Settings.forceBright) 1.0f
                else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        setContentView(R.layout.activity_diagnostics)
        val text = dump()
        findViewById<TextView>(R.id.dump).apply {
            this.text = text
            // Long-press to send the whole thing somewhere useful.
            //
            // Every diagnosis in this project so far has travelled as a
            // screenshot of a scrolling monospace dump, which loses whatever did
            // not fit on screen — and what did not fit was usually the ride log,
            // which is the part that answers questions. Sharing it as text
            // carries all of it, and it can be pasted rather than transcribed.
            setOnLongClickListener {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "SpringCommand diagnostics")
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    "Send diagnostics"
                ))
                true
            }
        }
    }

    /**
     * App version, and when this copy was installed.
     *
     * versionName alone is useless during a day of rebuilding: it is a constant,
     * so six builds all report the same string and there is no way to tell from
     * the phone whether the fix you just compiled is the one running. The
     * install time comes from the package manager and changes on every adb
     * install, which is exactly the question being asked — "is this the new
     * one?"
     *
     * It is also how the phone was found to be a build behind: the source said
     * 0.2 and the screen said 0.1.
     */
    private fun appVersion(): String = try {
        val p = packageManager.getPackageInfo(packageName, 0)
        val when_ = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.UK)
            .format(java.util.Date(p.lastUpdateTime))
        "${p.versionName} (installed $when_)"
    } catch (e: Exception) { "?" }

    private fun dump(): String {
        val app = appVersion()

        val raw = BikeRepository.lastFastRaw
        val hex = raw?.joinToString(" ") { "%02X".format(it) } ?: "(none)"
        val decoded = raw?.let { FastPacket.parse(it) }

        return buildString {
            appendLine("SPRINGCOMMAND DIAGNOSTICS")
            appendLine("=========================")
            appendLine("(long-press anywhere to send this as text)")
            appendLine()
            appendLine("app        $app")
            appendLine("firmware   ${BikeRepository.state?.fw ?: "not reported"}")
            appendLine("link       ${if (BikeRepository.isLive) "live" else "stale"}")
            appendLine("rssi       ${BikeRepository.rssi?.let { "$it dBm" } ?: "-"}")
            appendLine("mtu        ${BikeRepository.mtu?.toString() ?: "-"}")
            appendLine("status     ${BikeRepository.status}")
            appendLine()
            appendLine("FAST  $hex")
            if (decoded != null) {
                appendLine("  rpm      ${decoded.rpm ?: "unknown"}")
                appendLine("  speed    ${decoded.speedKmh ?: "unknown"}")
                appendLine("  throttle ${decoded.throttlePct ?: "unknown"}")
                appendLine("  gear     ${decoded.gear ?: "unknown"}")
                appendLine("  cruiseEn ${decoded.cruiseEnable ?: "unknown"}")
                appendLine("  brakeR   ${decoded.brakeRear ?: "unknown"}")
                appendLine("  cruise   ${decoded.cruise ?: "unknown"}")
                appendLine("  indL     ${decoded.indLeft ?: "unknown"}")
                appendLine("  indR     ${decoded.indRight ?: "unknown"}")
                appendLine("  hazard   ${decoded.hazard ?: "unknown"}")
            }
            appendLine()
            // Not in the FAST packet: the grips arrive in the state JSON, which
            // is where every value that is not needed ten times a second lives.
            appendLine("GRIPS")
            val bike = BikeRepository.state
            appendLine("  level    ${bike?.gripLevel?.let { if (it == 0) "off" else "$it / 10" } ?: "not reported"}")
            appendLine("  left     ${bike?.gripLeftC?.let { "$it C" } ?: "not reported"}")
            appendLine("  right    ${bike?.gripRightC?.let { "$it C" } ?: "not reported"}")
            appendLine()
            appendLine("WHEEL SENSORS")
            appendLine("  verdict  ${bike?.wheels ?: "not judged (stationary, or no reading)"}")
            appendLine("  speed F  ${bike?.speedFrontKmh?.let { "%.1f km/h".format(it) } ?: "-"}   (ABS module, the honest one)")
            appendLine("  speed R  ${bike?.speedKmh?.let { "%.1f km/h".format(it) } ?: "-"}   (what the dash shows)")
            // The counters, and why they are the number that matters.
            //
            // These are active Hall sensors: they count the teeth correctly or
            // they do not, so a worn one does not read progressively slow and
            // there is nothing to see in the ratio. A widening air gap shows up
            // as brief losses the ABS module tolerates without setting a fault.
            // One is nothing. One a week becoming twenty a ride is a sensor
            // being ground away, which is how the front one was lost.
            appendLine("  dropouts F ${bike?.wheelBlips ?: 0}   R ${bike?.wheelBlipsRear ?: 0}   (since the ESP32 booted)")
            appendLine()
            appendLine("STAND")
            appendLine("  state    ${bike?.stand ?: "not reported (moving, or never seen)"}")
            appendLine()
            appendLine("FAULTS")
            val dm1 = BikeRepository.state?.dm1
            when {
                dm1 == null -> appendLine("  (not reported)")
                Dtc.healthy(dm1) -> appendLine("  none active")
                else -> {
                    // The long form here, with the SPN kept beside the words:
                    // this is the screen someone reads out over the telephone
                    // to a dealer, and the number is what the dealer needs.
                    Dtc.parse(dm1).forEach { appendLine("  " + Dtc.describe(it)) }
                    if (Dtc.parse(dm1).isEmpty()) appendLine("  (unparsed) $dm1")
                }
            }
            Dtc.lamps(dm1)?.let { appendLine("  lamps    $it") }
            appendLine()
            appendLine("HEAT")
            for (zone in HeatCurve.Zone.entries) {
                val mac = Settings.heatMac(zone)
                // The assignment, not just the address. Both controllers look
                // identical over the air, so recovering it after a reinstall
                // otherwise means switching one garment on at a time to work out
                // which is which. Written down here, it is two taps.
                appendLine("  ${zone.name.lowercase().padEnd(7)} ${if (mac.isBlank()) "not assigned" else mac}" +
                        "  ${Keis.deviceFor(zone).level?.label ?: "-"}" +
                        "  ${if (Keis.deviceFor(zone).connected) "connected" else "away"}")
            }
            appendLine("  curve   off ${Settings.heatOffAt(HeatCurve.Zone.LEGS)}/${Settings.heatOffAt(HeatCurve.Zone.JACKET)}" +
                    "  full ${Settings.heatFullAt(HeatCurve.Zone.LEGS)}/${Settings.heatFullAt(HeatCurve.Zone.JACKET)}" +
                    "  auto ${Settings.heatAuto}  supply ${Keis.supply}")

            appendLine()
            appendLine("SERVICE")
            // The bike's figure and the phone's, side by side.
            //
            // Establishing that a service write had not landed took a query
            // against openHAB, because the settings screen showed a number
            // without saying whose it was — the bike's, or the local fallback
            // standing in for it. Those are the two answers that matter and they
            // are indistinguishable from one figure.
            val bikeSvc = BikeRepository.state?.serviceKm
            val phoneSvc = Settings.serviceLastKm.takeIf { it >= 0 }
            appendLine("  on the bike   ${bikeSvc?.let { "$it km" } ?: "not recorded"}")
            appendLine("  on the phone  ${phoneSvc?.let { "$it km" } ?: "-"}" +
                    if (bikeSvc == null && phoneSvc != null) "   <- waiting to be sent" else "")
            appendLine("  interval      ${Settings.serviceIntervalKm} km")
            appendLine("  remaining     ${Service.remainingKm(BikeRepository.state)?.let { "$it km" } ?: "-"}")

            appendLine()
            appendLine("FUEL")
            // Why the pre-ride card's fuel row is empty is not otherwise
            // answerable from the phone: the filter only counts readings taken
            // above 10 km/h, and "no samples yet" looks identical to "broken".
            appendLine("  sender        ${BikeRepository.state?.fuelPct?.let { "$it %" } ?: "-"}")
            appendLine("  filtered      ${FuelLevel.trusted?.let { "$it %" } ?: "none yet"}" +
                    if (FuelLevel.trusted != null && !FuelLevel.live) "  (remembered, not this ride)" else "")
            // One decimal, not the raw Double. tankLitres is stored as a float
            // preference and read back as a Double, so 19 litres arrives as
            // 18.999992370605469 and printed straight it looked like a fault in
            // the reading rather than in the formatting.
            //
            // One decimal rather than a whole number because the setting allows
            // 20.8, which is the standard tank on this machine -- rounding to an
            // integer would print 21 and quietly lose it. Every other litre
            // figure in the app already shows one decimal.
            appendLine("  tank          %.1f L".format(Settings.tankLitres))

            appendLine()
            appendLine("TYRES")
            val tyres = TyreMemory.last()
            if (tyres == null) appendLine("  no reading yet — the sensors report once the wheels turn")
            else {
                appendLine("  age           ${TyreMemory.formatAge(tyres.ageMillis)}")
                appendLine("  front         %.1f PSI at %.0f C -> %.1f judged, target %.1f  %s"
                    .format(tyres.front.psi, tyres.front.tempC, tyres.front.judged,
                            tyres.front.target, tyres.front.level))
                appendLine("  rear          %.1f PSI at %.0f C -> %.1f judged, target %.1f  %s"
                    .format(tyres.rear.psi, tyres.rear.tempC, tyres.rear.judged,
                            tyres.rear.target, tyres.rear.level))
            }

            appendLine()
            appendLine("UNITS")
            // First suspect whenever a number looks wrong on screen but right on
            // the bus.
            appendLine("  ${Settings.speedLabel}  ${Settings.distanceLabel}  " +
                    "${Settings.pressureLabel}  ${Settings.temperatureLabel}  " +
                    "economy ${Settings.economyLabel}")

            appendLine()
            appendLine("STATE")
            appendLine(BikeRepository.lastStateJson ?: "(none)")
            appendLine()
            appendLine("LOG  ${RideLog.path()}")
            appendLine(RideLog.recent())
        }
    }
}

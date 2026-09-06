package dk.agesen.springfield

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import android.widget.Button
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The settings screen.
 *
 * Steppers rather than text fields or sliders. Every value here is a round
 * number changed occasionally and by a known increment — a PSI, a hundred rpm —
 * and a stepper cannot produce a nonsense entry the way a keyboard can. It is
 * also the only control on this list you have a chance of using without taking
 * your gloves off.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        // The cluster's orientation choice applies here too, so opening settings
        // on a mounted phone does not spin the screen round.
        requestedOrientation = Settings.requestedOrientation
        applyBrightness()
        setContentView(R.layout.activity_settings)

        // Tyre steppers work in whatever pressure unit is showing: the value is
        // converted out of the stored PSI, stepped by that unit's natural
        // increment, and converted back. Half a PSI, five kPa and five hundredths
        // of a bar are all "one nudge" to the person turning the valve.
        pressureStepper(
            R.id.frontMinus, R.id.frontValue, R.id.frontPlus,
            { Settings.targetFront }, { Settings.targetFront = it }
        )
        pressureStepper(
            R.id.rearMinus, R.id.rearValue, R.id.rearPlus,
            { Settings.targetRear }, { Settings.targetRear = it }
        )
        stepper(
            R.id.redlineMinus, R.id.redlineValue, R.id.redlinePlus,
            get = { Settings.redlineRpm.toDouble() }, set = { Settings.redlineRpm = it.toInt() },
            step = 100.0, format = { "%,d".format(it.toInt()) }
        )

        cycler(R.id.pressureButton, { Settings.pressureUnit.label.uppercase() }) {
            val all = Settings.Pressure.entries
            Settings.pressureUnit = all[(Settings.pressureUnit.ordinal + 1) % all.size]
            repaintPressure()
        }
        cycler(R.id.distanceButton, {
            "${Settings.distanceUnit.speedLabel} · ${Settings.distanceUnit.distanceLabel}"
        }) {
            val all = Settings.Distance.entries
            Settings.distanceUnit = all[(Settings.distanceUnit.ordinal + 1) % all.size]
        }
        cycler(R.id.temperatureButton, { Settings.temperatureUnit.label }) {
            val all = Settings.Temperature.entries
            Settings.temperatureUnit = all[(Settings.temperatureUnit.ordinal + 1) % all.size]
        }

        val bright = findViewById<Button>(R.id.brightButton)
        fun paintBright() { bright.text = if (Settings.forceBright) "MAXIMUM" else "AUTOMATIC" }
        bright.setOnClickListener {
            Settings.forceBright = !Settings.forceBright
            paintBright()
            // Applied to *this* window as well, not only the cluster's.
            //
            // The setting was only ever pushed in MainActivity.onStart, so
            // tapping it here changed a label and nothing else — the screen
            // stayed exactly as it was until you navigated back. A brightness
            // control that does not change the brightness while you are looking
            // at it reads as broken, and there is no way to tell that from a
            // setting that genuinely has not applied.
            applyBrightness()
        }
        paintBright()

        val orientation = findViewById<Button>(R.id.orientationButton)
        fun paintOrientation() { orientation.text = Settings.orientationName.uppercase() }
        orientation.setOnClickListener {
            Settings.orientation = (Settings.orientation + 1) % 3
            paintOrientation()
            requestedOrientation = Settings.requestedOrientation
        }
        paintOrientation()

        val trip = findViewById<Button>(R.id.tripButton)
        fun paintTrip() {
            val km = Settings.rideDistanceKm(BikeRepository.state?.tripKm)
            trip.text = if (km == null) "RESET"
            else "RESET · %.1f %s".format(Settings.distance(km), Settings.distanceLabel)
        }
        trip.setOnClickListener {
            // Needs a trip reading to mark against. Without one there is nothing
            // to subtract from later, so the press does nothing rather than
            // quietly recording a zero it would then count up from.
            Settings.resetTrip(BikeRepository.state?.tripKm)
            // Distance and the figures derived from it are one ride; resetting
            // half of them would leave a top speed from a road you are no longer
            // on sitting next to a distance of zero.
            RideStats.reset()
            paintTrip()
        }
        paintTrip()

        macPicker(R.id.legsMacButton, HeatCurve.Zone.LEGS)
        macPicker(R.id.jacketMacButton, HeatCurve.Zone.JACKET)

        val heatAuto = findViewById<Button>(R.id.heatAutoButton)
        fun paintHeatAuto() { heatAuto.text = if (Settings.heatAuto) "AUTOMATIC" else "MANUAL ONLY" }
        heatAuto.setOnClickListener { Settings.heatAuto = !Settings.heatAuto; paintHeatAuto() }
        paintHeatAuto()

        // Curve endpoints are stored in Celsius and shown in the rider's unit,
        // like every other temperature in the app.
        fun degStepper(minusId: Int, valueId: Int, plusId: Int,
                       get: () -> Double, set: (Double) -> Unit) = stepper(
            minusId, valueId, plusId,
            get = { Settings.temperature(get()) },
            set = { shown ->
                val celsius = if (Settings.temperatureUnit == Settings.Temperature.FAHRENHEIT)
                    (shown - 32.0) * 5.0 / 9.0 else shown
                set(celsius)
            },
            step = 1.0,
            format = { "%.0f%s".format(it, Settings.temperatureLabel) }
        )

        degStepper(R.id.legsOffMinus, R.id.legsOffValue, R.id.legsOffPlus,
            { Settings.heatOffAt(HeatCurve.Zone.LEGS) },
            { Settings.setHeatOffAt(HeatCurve.Zone.LEGS, it)
              Keis.curveChanged(HeatCurve.Zone.LEGS) })
        degStepper(R.id.legsFullMinus, R.id.legsFullValue, R.id.legsFullPlus,
            { Settings.heatFullAt(HeatCurve.Zone.LEGS) },
            { Settings.setHeatFullAt(HeatCurve.Zone.LEGS, it)
              Keis.curveChanged(HeatCurve.Zone.LEGS) })
        degStepper(R.id.jacketOffMinus, R.id.jacketOffValue, R.id.jacketOffPlus,
            { Settings.heatOffAt(HeatCurve.Zone.JACKET) },
            { Settings.setHeatOffAt(HeatCurve.Zone.JACKET, it)
              Keis.curveChanged(HeatCurve.Zone.JACKET) })
        degStepper(R.id.jacketFullMinus, R.id.jacketFullValue, R.id.jacketFullPlus,
            { Settings.heatFullAt(HeatCurve.Zone.JACKET) },
            { Settings.setHeatFullAt(HeatCurve.Zone.JACKET, it)
              Keis.curveChanged(HeatCurve.Zone.JACKET) })

        stepper(
            R.id.tankMinus, R.id.tankValue, R.id.tankPlus,
            get = { Settings.tankLitres }, set = { Settings.tankLitres = it },
            step = 0.2, format = { "%.1f L".format(it) }
        )

        stepper(
            R.id.svcMinus, R.id.svcValue, R.id.svcPlus,
            get = { Settings.serviceIntervalKm.toDouble() },
            set = { Settings.serviceIntervalKm = it.toInt() },
            step = 500.0,
            format = { "%.0f %s".format(Settings.distance(it), Settings.distanceLabel) }
        )

        val svc = findViewById<Button>(R.id.svcRecord)
        fun paintService() {
            val last = Service.lastServiceKm(BikeRepository.state)
            svc.text = if (last == null) "NOT SET"
            else "%.0f %s".format(
                Settings.distance(last.toDouble()), Settings.distanceLabel
            ).uppercase()
        }
        svc.setOnClickListener {
            val odo = BikeRepository.state?.odometerKm
            if (odo == null) {
                svc.text = "ODOMETER NOT REPORTED"
                return@setOnClickListener
            }

            // The figure is typed, not assumed.
            //
            // The button used to record the odometer showing now, which asserts
            // the service happened today. That is right only when it did, and
            // the common case is the opposite: the service was thousands of
            // kilometres ago and the number needed is one the app cannot read
            // anywhere.
            //
            // Pre-filled with the current odometer all the same, because "I have
            // just had it serviced" is the one case where a rider reaches for
            // this button without already knowing the figure.
            //
            // Trip 1 is shown beside it with the subtraction done, and only as
            // information. A trip meter is a trip meter; it lines up with the
            // last service by coincidence when it does at all, and pre-filling
            // from it would bake in a meaning it does not have.
            val trip = BikeRepository.state?.tripKm

            val field = EditText(this).apply {
                setText(odo.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                setSelectAllOnFocus(true)
            }
            AlertDialog.Builder(this)
                .setTitle("Odometer at the last service")
                .setMessage(
                    "The bike reads %,d km now.%s\n\nThe next service falls %,d km after whatever you enter."
                        .format(
                            odo,
                            trip?.takeIf { it > 1.0 }?.let {
                                "\nTrip 1 is at %,.0f, so %,d if you have been counting from the last service on it."
                                    .format(it, odo - it.toInt())
                            } ?: "",
                            Settings.serviceIntervalKm
                        )
                )
                .setView(field)
                .setPositiveButton("Record") { _, _ ->
                    val km = field.text.toString().toIntOrNull()
                    if (km == null || km < 0 || km > odo) {
                        // The bike refuses a figure ahead of its own odometer, so
                        // catching it here saves a round trip and says why.
                        AlertDialog.Builder(this)
                            .setTitle("Not a usable figure")
                            .setMessage("It has to be a number between 0 and %,d — the bike cannot have been serviced at a mileage it has not reached.".format(odo))
                            .setPositiveButton("OK", null)
                            .show()
                        return@setPositiveButton
                    }
                    val problem = Service.recordAt(km)
                    paintService()
                    problem?.let {
                        AlertDialog.Builder(this)
                            .setTitle("Saved on the phone only")
                            .setMessage("The bike did not take it: $it.\n\nThe figure is kept here until it can be sent.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        paintService()

        val dtc = findViewById<Button>(R.id.dtcButton)
        fun paintDtc() {
            val faults = Dtc.parse(BikeRepository.state?.dm1)
            dtc.text = when {
                faults.isEmpty() -> "NONE ACTIVE"
                faults.size == 1 -> "1 ACTIVE"
                else -> "${faults.size} ACTIVE"
            }
        }
        dtc.setOnClickListener { nameFaultCode(::paintDtc) }
        paintDtc()

        val records = findViewById<Button>(R.id.recordsButton)
        fun paintRecords() {
            val s1 = Settings.bestSpeedKmh
            records.text = if (s1 <= 0) "NONE YET"
            else "RESET · %.0f %s".format(Settings.speed(s1), Settings.speedLabel)
        }
        records.setOnClickListener { Settings.resetRecords(); paintRecords() }
        paintRecords()

        findViewById<Button>(R.id.bluetoothButton).setOnClickListener { openBluetoothSettings() }

        // What we are actually talking to. Older firmware does not report a
        // version, and saying so is more useful than leaving the line blank —
        // "not reported" narrows it down, an empty row does not.
        val appVersion = try {
            val p = packageManager.getPackageInfo(packageName, 0)
            val stamp = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.UK)
                .format(java.util.Date(p.lastUpdateTime))
            "${p.versionName} ($stamp)"
        } catch (e: Exception) { "?" }
        val firmwareLine = findViewById<TextView>(R.id.firmware)
        firmwareLine.text = "App $appVersion  ·  Firmware " +
                (BikeRepository.state?.fw ?: "not reported")

        // Long-press rather than a button: the raw view is for the two of us
        // debugging, not for a rider, and a control nobody needs should not take
        // up room on a screen everybody sees.
        firmwareLine.setOnLongClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
            true
        }

        // The maker's plate opens About. A plain press, unlike the diagnostics
        // long-press above: that screen is for the two of us debugging, this one
        // is for anyone holding the phone and wondering what it is wired to.
        findViewById<TextView>(R.id.credit).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    /**
     * Push the brightness choice onto this window.
     *
     * BRIGHTNESS_OVERRIDE_NONE is -1f and means "whatever the phone is doing",
     * which is the default — so on a phone with auto-brightness turned down,
     * the app is dark and correctly so. MAXIMUM is what beats direct sun
     * through a visor.
     */
    private fun applyBrightness() {
        window.attributes = window.attributes.apply {
            screenBrightness =
                if (Settings.forceBright) 1.0f
                else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    /**
     * Naming a fault code.
     *
     * Only the codes the bike is reporting right now are offered. A list of
     * every SPN that exists would be a phone book, and the moment a rider wants
     * to name one is the moment it is on the screen in front of them with the
     * manual open.
     */
    private fun nameFaultCode(onSaved: () -> Unit) {
        val faults = Dtc.parse(BikeRepository.state?.dm1)
        if (faults.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Fault codes")
                .setMessage("Nothing is active. Codes can be named while the bike is reporting them.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = faults.map { Dtc.describe(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which code?")
            .setItems(labels) { _, which -> promptForName(faults[which].spn, onSaved) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForName(spn: Long, onSaved: () -> Unit) {
        val found = Dtc.named(spn)
        val field = EditText(this).apply {
            // Pre-filled with whatever name the app currently has, so correcting
            // a generic guess is an edit rather than a retype.
            setText(Settings.dtcName(spn) ?: found?.first ?: "")
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("SPN $spn")
            .setMessage(
                when (found?.second) {
                    Dtc.Source.J1939_GENERIC ->
                        "This name comes from the general J1939 standard, not from an Indian source — Polaris do not always mean the same thing by a number. Confirm it and correct it here."
                    Dtc.Source.MANUAL ->
                        "From the service manual's trouble-code table. You can still override it."
                    else ->
                        if (Dtc.proprietary(spn))
                            "A Polaris code with no published meaning. Look it up once — the manual, or a dealer — and it is named from then on."
                        else
                            "Give this code a name of your own."
                }
            )
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                Settings.setDtcName(spn, field.text.toString())
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Assigning a controller to a zone.
     *
     * A scan rather than typed MAC addresses: the rider has the garments in
     * front of them and the addresses on neither. Switch on the one you are
     * assigning and leave the other off, and the list is unambiguous.
     */
    private fun macPicker(buttonId: Int, zone: HeatCurve.Zone) {
        val button = findViewById<Button>(buttonId)
        fun paint() {
            val mac = Settings.heatMac(zone)
            button.text = if (mac.isBlank()) "SCAN" else mac.takeLast(8)
        }
        button.setOnClickListener {
            button.text = "SCANNING…"
            KeisScanner.scan(this) { found ->
                runOnUiThread {
                    if (found.isEmpty()) {
                        button.text = "NONE FOUND"
                        return@runOnUiThread
                    }
                    val labels = found.map { "${it.name ?: "Keis"}\n${it.address}" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Controller for ${zone.name.lowercase()}")
                        .setItems(labels) { _, which ->
                            Settings.setHeatMac(zone, found[which].address)
                            Keis.reconfigure(this)
                            paint()
                        }
                        .setNegativeButton("Cancel") { _, _ -> paint() }
                        .setNeutralButton("Clear") { _, _ ->
                            Settings.setHeatMac(zone, "")
                            Keis.reconfigure(this)
                            paint()
                        }
                        .show()
                }
            }
        }
        paint()
    }

    private fun openBluetoothSettings() {
        // The only cure for a link-key mismatch is forgetting the device, and
        // that lives in the system settings — so the app points at the door
        // rather than describing where it is.
        startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private val repaints = mutableListOf<() -> Unit>()
    private fun repaintPressure() = repaints.forEach { it() }

    /**
     * A button that shows a value and cycles it. The label is re-read after the
     * press rather than tracked, so the button always shows what was stored.
     */
    private fun cycler(id: Int, label: () -> String, advance: () -> Unit) {
        val button = findViewById<Button>(id)
        fun paint() { button.text = label() }
        button.setOnClickListener { advance(); paint() }
        paint()
    }

    /** A stepper over a pressure stored in PSI but shown in the chosen unit. */
    private fun pressureStepper(minusId: Int, valueId: Int, plusId: Int,
                                getPsi: () -> Double, setPsi: (Double) -> Unit) {
        val value = findViewById<TextView>(valueId)
        fun paint() {
            val u = Settings.pressureUnit
            value.text = "%.${u.decimals}f".format(Settings.pressure(getPsi()))
        }
        fun nudge(direction: Int) {
            val u = Settings.pressureUnit
            setPsi(Settings.pressureToPsi(Settings.pressure(getPsi()) + direction * u.step))
            paint()
        }
        findViewById<Button>(minusId).setOnClickListener { nudge(-1) }
        findViewById<Button>(plusId).setOnClickListener { nudge(+1) }
        // A lambda, not ::paint — Kotlin has no callable references to local
        // functions.
        repaints += { paint() }
        paint()
    }

    /**
     * Wires a minus/value/plus trio to one setting.
     *
     * The value is re-read from Settings after every press rather than tracked
     * locally, so the clamping in Settings is what the screen shows — press
     * past a limit and the number simply stops, which is the honest feedback.
     */
    private fun stepper(
        minusId: Int, valueId: Int, plusId: Int,
        get: () -> Double, set: (Double) -> Unit,
        step: Double, format: (Double) -> String
    ) {
        val value = findViewById<TextView>(valueId)
        fun paint() { value.text = format(get()) }
        findViewById<Button>(minusId).setOnClickListener { set(get() - step); paint() }
        findViewById<Button>(plusId).setOnClickListener { set(get() + step); paint() }
        paint()
    }
}

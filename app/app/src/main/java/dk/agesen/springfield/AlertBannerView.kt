package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.View

/**
 * A fault banner across the top of the cluster.
 *
 * The bike's diagnostics were decoded and then buried on the Machine page,
 * where a rider would find them only by going to look — which is the one thing
 * nobody does while a fault is developing.
 *
 * The same was true of two judgements the app was already making and keeping to
 * itself: how far the fuel will go, and whether a tyre has fallen away from its
 * target. Both were computed, both were correct, and both were visible only on
 * the page that owned them — so a rider on the heat page could ride an entire
 * tank down, or a tyre soft, while the app quietly knew. Running out of fuel and
 * a deflating tyre are the two commonest reasons to stop at the roadside, and
 * they are exactly the two this had nothing to say about.
 *
 * Six conditions now, in the order a rider would want them: what could put you
 * down, then what will strand you, then what needs planning.
 *
 * It is deliberately not dismissible. A warning you can swipe away is one you
 * will swipe away, and neither of these clears itself for a good reason: the
 * banner disappears when the bike stops reporting the fault, and not before.
 */
class AlertBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), BikeRepository.Observer {

    companion object {
        /**
         * Below this with the engine turning, the alternator is not keeping up.
         *
         * A running engine should hold well over 13 V. Under 12 while it turns
         * means the bike is running off the battery, which ends with a bike that
         * will not restart — and it is invisible unless something says so.
         */
        private const val CHARGING_FAULT_VOLTS = 12.0
        private const val ENGINE_RUNNING_RPM = 400

        /**
         * Worst-case range, below which fuel is worth interrupting for.
         *
         * The pessimistic end of the band, not the optimistic one: a warning
         * that assumes your best economy is a warning that arrives after the
         * fuel does. Fifty kilometres is far enough to reach a station from most
         * places you can get to on this bike, and near enough that it means
         * something.
         */
        private const val FUEL_WARN_KM = 50.0

        /**
         * How long a key fob search may run before it is worth interrupting for.
         *
         * Measured on the bike 2026-09-05: with the fob in a pocket the search
         * resolves inside ONE second; with the fob indoors it sat at SEARCHING
         * for the full twenty before giving up. Three seconds is comfortably
         * past anything healthy without firing on the ordinary case, and it
         * buys about seventeen seconds over waiting for the failure -- which is
         * the difference between hearing it while standing beside the bike and
         * hearing it after getting on.
         */
        private const val SEARCH_WARN_MS = 3000L

        /**
         * True while a red alert stands, for anything that wants to echo it.
         *
         * Published from here because this is where severity is decided — a
         * second consumer working it out again from the same inputs is how two
         * parts of one cluster end up disagreeing about whether something is
         * wrong. Written by the banner, read by the edge glow.
         */
        @Volatile
        var criticalActive = false
            private set
    }

    /** Red interrupts; amber informs. Only the worst one is shown. */
    private enum class Severity { CRITICAL, CAUTION }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val colCrit = Color.parseColor("#D2452F")
    private val colInk = Color.parseColor("#FFF3F1")
    private val colDim = Color.parseColor("#F0C3BC")

    // Amber carries its own ink. White on amber is the classic unreadable
    // warning strip; near-black on it is legible in direct sun through a visor,
    // which is the condition this has to survive.
    private val colWarn = Color.parseColor("#E8A33D")
    private val colWarnInk = Color.parseColor("#1A1204")
    private val colWarnDim = Color.parseColor("#4A3406")

    private var headline: String? = null
    private var detail: String? = null
    private var announced: String? = null

    /**
     * When the security system started looking for the key fob.
     *
     * Waiting for NOT FOUND is too late to be useful. The bike takes twenty
     * seconds to give up, and twenty seconds is long enough to have got on --
     * which is the whole complaint: having to climb off again to fetch the fob.
     *
     * SEARCHING is the early signal. Measured on the bike 2026-09-05: with the
     * fob in a pocket it resolves inside ONE second, and with the fob indoors it
     * sat at SEARCHING for the full twenty before reporting failure. So a search
     * still running after three seconds has already told you the answer, and
     * buys about seventeen seconds of standing next to the bike rather than
     * sitting on it.
     */
    private var searchingSince = 0L
    private var severity = Severity.CRITICAL

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BikeRepository.addObserver(this)
        onBikeUpdate()
    }

    override fun onDetachedFromWindow() {
        // Cleared on the way out: the flag outlives the view otherwise, and the
        // glow would keep burning on a screen with no banner to explain it.
        criticalActive = false
        BikeRepository.removeObserver(this)
        super.onDetachedFromWindow()
    }

    override fun onBikeUpdate() {
        val live = BikeRepository.isLive
        val state = if (live) BikeRepository.state else null
        val rpm = if (live) BikeRepository.fast?.rpm else null

        // The firmware reports the healthy case as text rather than an empty
        // field, so "no fault" is a specific string and anything else is one.
        val dm1 = state?.dm1
        val dtc = dm1 != null && !Dtc.healthy(dm1)

        // Only the two failure verdicts raise anything; "OK" is not news, and
        // the brief-dropout counters belong on the diagnostics screen rather
        // than in a rider's face at 80 km/h.
        val wheelFault = state?.wheels?.takeIf { it == "FRONT LOST" || it == "REAR LOST" }

        // The kill switch, and it outranks everything below it on purpose.
        //
        // The owner asked for this from her own riding: she has caught the
        // run/stop switch several times, moving and parked, and then stood there
        // for minutes wondering why the engine would not start. That is the
        // failure this banner exists for -- not a fault, but a question already
        // being asked out loud.
        //
        // First in the chain, because a tyre two PSI low is a true statement
        // that does not answer "why will it not start". Anything else on this
        // list appears the moment the switch goes back to RUN, which is the right
        // order to be told things in. And the engine cannot be running while this
        // is true, so nothing it displaces is urgent.
        //
        // CAUTION rather than CRITICAL: nothing is broken. Red on this cluster
        // means something is wrong with the motorcycle, and a switch doing
        // exactly what it was moved to do is not that. The headline carries it.
        //
        // Only visible with the ignition on -- with the key out the ECU sends no
        // 65381 at all and the field is absent, which is also the only time the
        // question does not arise.
        val killStop = state?.killSwitch == "STOP"

        // Key fob: a search that is taking too long, or one that has given up.
        val security = state?.security
        val nowMs = System.currentTimeMillis()
        if (security == "SEARCHING") {
            if (searchingSince == 0L) searchingSince = nowMs
        } else {
            searchingSince = 0L
        }
        val slowSearch = searchingSince != 0L && nowMs - searchingSince > SEARCH_WARN_MS
        val fobMissing = security == "NOT FOUND" || slowSearch

        val volts = state?.batteryV
        val charging = volts != null && rpm != null &&
                rpm > ENGINE_RUNNING_RPM && volts < CHARGING_FAULT_VOLTS

        // Tyres are judged on the cold equivalent, the same figure the tyre page
        // acts on — a warm tyre reading high is not over-inflated, and a banner
        // that shouted about it would train the rider to ignore the banner.
        val tyres = TyreMemory.last()
        val worstTyre = tyres?.let {
            listOf("FRONT" to it.front, "REAR" to it.rear)
                .maxByOrNull { w -> w.second.level.ordinal }
        }
        val tyreLevel = worstTyre?.second?.level

        // The filtered level, not the sender's.
        //
        // On the side stand the Springfield leans far enough that the float sits
        // high out of the fuel and reads several percent low — enough to put a
        // nearly full tank into a warning while the bike stands untouched in a
        // garage. FuelLevel only counts readings taken while moving, when the
        // bike is upright by definition.
        //
        // And only while that figure is from this ride: a level remembered from
        // the last one is worth showing on the pre-ride card, but warning on it
        // would be plainly wrong the first time anyone filled up.
        val rangeKm = if (FuelLevel.live)
            FuelRange.estimateKm(FuelLevel.trusted, Settings.tankLitres)?.start else null
        val lowFuel = rangeKm != null && rangeKm < FUEL_WARN_KM

        val newHeadline: String?
        val newDetail: String?
        val newSeverity: Severity
        // A key that survives the numbers moving, so a warning buzzes once when
        // it appears rather than every time the reading ticks.
        val key: String?

        when {
            killStop -> {
                newSeverity = Severity.CAUTION
                newHeadline = "KILL SWITCH"
                newDetail = "run/stop is at STOP — the engine will not start"
                key = "kill-stop"
            }
            tyreLevel == TyreMemory.Level.ACT && worstTyre != null -> {
                newSeverity = Severity.CRITICAL
                newHeadline = "TYRE PRESSURE"
                newDetail = tyreDetail(worstTyre.first, worstTyre.second)
                key = "tyre-act-" + worstTyre.first
            }
            wheelFault != null -> {
                // Ranked above the DTC because the ABS module has not decided
                // anything is wrong yet -- that is the entire value of the
                // check. By the time it sets a fault the rider already has the
                // lamp; this is the sentence that comes before it.
                //
                // Critical without qualification. A wheel speed sensor is what
                // the ABS thinks with, and on this bike one was ground away by a
                // tone ring until it quit in traffic. There is no version of
                // this that is worth an amber.
                newSeverity = Severity.CRITICAL
                newHeadline = "WHEEL SENSOR"
                newDetail = if (wheelFault == "FRONT LOST")
                    "front sensor reporting nothing — ABS may be out"
                else "rear sensor reporting nothing — ABS may be out"
                key = "wheel-" + wheelFault
            }
            fobMissing -> {
                // Ranked above the fault list because this is the answer to a
                // question the rider is already asking -- the bike has just cut
                // out, or will not wake -- and the DTC that follows, if one
                // follows at all, arrives after the fact and says less.
                //
                // Only NOT FOUND raises anything. SEARCHING is the normal state
                // for about a second at every wake, and a banner that appeared
                // each time the bike was switched on would be furniture inside
                // a week.
                //
                // Critical rather than amber, and it does buzz. It is not
                // dangerous -- the search runs only at a wake, so this cannot
                // appear at speed -- but it strands you exactly as a flat
                // battery would, and it is fixable in thirty seconds if you know
                // what it is: fetch the fob, or change its cell.
                newSeverity = Severity.CRITICAL
                newHeadline = "KEY FOB"
                newDetail = if (security == "NOT FOUND")
                    "not detected — the bike will not run"
                else "not found yet — check you have it before getting on"
                // One key for both, so the buzz does not repeat when a slow
                // search finally becomes a failed one. It is the same news.
                key = "security-missing"
            }
            dtc -> {
                // Amber when the bike keeps its own lamp off — except for the
                // handful that end the ride whatever the lamp does. A flat key
                // fob cannot hurt the engine, so the lamp stays off, and you
                // still do not get home: that is the rider's question, and it is
                // not the one the MIL answers.
                newSeverity = if (Dtc.allLampsOff(dm1)) Severity.CAUTION else Severity.CRITICAL
                newHeadline = "ACTIVE FAULT"
                // The short form: this line elides, and a rider glancing down
                // needs the component before the number. The full text with the
                // SPN is on the diagnostics screen, which is where anyone
                // telephoning a dealer will be.
                newDetail = Dtc.summary(dm1, short = true) ?: dm1
                key = "dtc-" + dm1
            }
            charging -> {
                newSeverity = Severity.CRITICAL
                newHeadline = "CHARGING"
                newDetail = String.format("%.1f V with the engine running", volts)
                key = "charging"
            }
            lowFuel && rangeKm != null -> {
                newSeverity = Severity.CAUTION
                newHeadline = "FUEL"
                newDetail = "about %.0f %s left at worst".format(
                    Settings.distance(rangeKm), Settings.distanceLabel)
                key = "fuel"
            }
            tyreLevel == TyreMemory.Level.WATCH && worstTyre != null -> {
                newSeverity = Severity.CAUTION
                newHeadline = "TYRE PRESSURE"
                newDetail = tyreDetail(worstTyre.first, worstTyre.second)
                key = "tyre-watch-" + worstTyre.first
            }
            Service.status(state) == Service.Status.OVERDUE -> {
                newSeverity = Severity.CAUTION
                newHeadline = "SERVICE"
                newDetail = Service.summary(state)
                key = "service"
            }
            else -> {
                newSeverity = Severity.CAUTION
                newHeadline = null; newDetail = null; key = null
            }
        }

        criticalActive = newHeadline != null && newSeverity == Severity.CRITICAL

        if (newHeadline != headline || newDetail != detail || newSeverity != severity) {
            headline = newHeadline
            detail = newDetail
            severity = newSeverity
            visibility = if (newHeadline == null) GONE else VISIBLE
            invalidate()
        }

        // Buzz once per distinct condition, not once per frame it persists and
        // not again because a decimal moved.
        if (key != null && key != announced) {
            announced = key
            // Amber is information, not an interruption. Buzzing for a service
            // that has been due for a week would teach the rider that the buzz
            // means nothing, which costs the red ones their meaning too.
            if (newSeverity == Severity.CRITICAL) buzz()
        } else if (key == null) {
            announced = null
        }
    }

    private fun tyreDetail(which: String, w: TyreMemory.Wheel): String {
        val dec = Settings.pressureUnit.decimals
        val unit = Settings.pressureLabel
        val off = Settings.pressure(kotlin.math.abs(w.deviation))
        val dir = if (w.deviation < 0) "under" else "over"
        return "%s %.${dec}f %s %s target".format(which.lowercase(), off, unit, dir)
    }

    private fun buzz() {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager ?: return
        val vibrator = manager.defaultVibrator
        if (!vibrator.hasVibrator()) return
        // Two pulses: distinct from the single tick a gear change gives, so the
        // pattern itself says which of them just happened.
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 90, 70), -1))
    }

    override fun onDraw(canvas: Canvas) {
        val head = headline ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        // Breathing rather than flashing. A flash is read once and then tuned
        // out; a slow swell stays noticeable without demanding the road's share
        // of attention.
        // Red breathes; amber holds steady. A caution that pulsed would compete
        // with the criticals for the same reflex, and the whole point of two
        // severities is that they do not feel the same.
        val critical = severity == Severity.CRITICAL
        val ground = if (critical) colCrit else colWarn
        val ink = if (critical) colInk else colWarnInk
        val dim = if (critical) colDim else colWarnDim

        val breath = if (critical) 0.72f + 0.28f * Cluster.pulse(1400L) else 1f
        bgPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(ground, ground and 0x00FFFFFF or 0xCC000000.toInt()),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        bgPaint.alpha = (255 * breath).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        bgPaint.shader = null
        bgPaint.alpha = 255

        // Warning triangle, drawn rather than a glyph so it scales with the bar.
        val s = h * 0.30f
        val cx = h * 0.52f
        val cy = h * 0.50f
        iconPaint.color = ink
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = h * 0.055f
        iconPaint.strokeJoin = Paint.Join.ROUND
        val path = android.graphics.Path()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s * 0.95f, cy + s * 0.72f)
        path.lineTo(cx - s * 0.95f, cy + s * 0.72f)
        path.close()
        canvas.drawPath(path, iconPaint)
        iconPaint.style = Paint.Style.FILL
        canvas.drawRect(cx - h * 0.028f, cy - s * 0.38f, cx + h * 0.028f, cy + s * 0.20f, iconPaint)
        canvas.drawCircle(cx, cy + s * 0.44f, h * 0.033f, iconPaint)

        val left = h * 1.05f
        textPaint.color = ink
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = h * 0.30f
        textPaint.letterSpacing = 0.12f
        canvas.drawText(head, left, h * 0.42f, textPaint)
        textPaint.letterSpacing = 0f

        detail?.let {
            textPaint.color = dim
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textPaint.textSize = h * 0.24f
            // Elide rather than wrap: a banner that grows to fit a long DM1
            // string would push the instruments down the screen.
            val avail = w - left - h * 0.3f
            var shown = it
            while (shown.length > 4 && textPaint.measureText(shown) > avail) {
                shown = shown.substring(0, shown.length - 2)
            }
            if (shown != it) shown = shown.trimEnd() + "…"
            canvas.drawText(shown, left, h * 0.78f, textPaint)
        }

        // Only the breathing red needs a frame loop; amber is still, and a
        // still banner that redrew forever would burn battery to no purpose.
        if (critical) postInvalidateOnAnimation()
    }
}

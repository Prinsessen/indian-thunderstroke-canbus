package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The main analogue dial — tachometer or speedometer, same instrument.
 *
 * One class for both so the two read as a matched pair when they sit side by
 * side in landscape: identical sweep, tick weight, needle and type. Only the
 * scale, the redline and what sits in the hub differ.
 *
 * The turn signals live *on* the dial rather than in a row underneath, at the
 * upper left and right of the face, which is where a rider's eye already looks
 * for them on a real cluster. In landscape each dial carries its own side, so
 * the arrows sit at the outer edges of the panel.
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), Cluster.Instrument {

    enum class Dial { RPM, SPEED }
    enum class TurnSide { NONE, LEFT, RIGHT, BOTH }

    companion object {
        private const val START_ANGLE = 150f
        private const val SWEEP_ANGLE = 240f


        /**
         * Nothing may be drawn within this radius of the dial centre.
         *
         * The needle pivots there and its hub cap covers 0.040 of the face; the
         * gear window and the digital figure were both centred across it, so the
         * cap sat on top of the reading. On a real cluster the pivot is left
         * alone and the readouts sit above and below it, which is what this
         * keeps them doing.
         */
        private const val HUB_CLEARANCE = 0.075f
    }

    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val redlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val arrowPath = Path()

    private val colDial = Color.parseColor("#1B2028")
    private val colFace = Color.parseColor("#0E1218")
    private val colTick = Color.parseColor("#8B949E")
    private val colAccent = Color.parseColor("#E8A33D")
    private val colRedline = Color.parseColor("#D2452F")
    private val colInk = Color.parseColor("#F2F5F9")
    private val colMuted = Color.parseColor("#7C8797")
    private val colNeutral = Color.parseColor("#4FA96B")   // bikes light neutral green
    private val colTurn = Color.parseColor("#4FA96B")
    private val colOff = Color.parseColor("#1E242C")
    private val colStrike = Color.parseColor("#39414D")

    /** Which instrument this dial is. */
    var dial: Dial = Dial.RPM
        set(v) { field = v; invalidate() }

    /**
     * Ignition, drawn on the rev dial only.
     *
     * Landscape has no digital rev readout to hang it beside -- the tachometer
     * gets a whole face there -- so the lamp rides on the dial instead. Same
     * lamp, same meaning, wherever the rev counter happens to be.
     */
    var ignition: Boolean? = null
        set(v) { field = v; invalidate() }

    /**
     * This ride's distance, drawn on the speed dial only.
     *
     * Landscape has no digital readout to put it beside -- both instruments get
     * a whole face -- so it goes under the speedometer's caption, mirroring the
     * ignition lamp under the tachometer's. The two dials each carry one thing
     * that is not a needle, on the same line, which is what stops the pair
     * looking lopsided.
     */
    var rideKm: Double? = null
        set(v) { field = v; invalidate() }

    /**
     * Throttle opening, 0-100, drawn as an inner arc on the rev dial.
     *
     * The outer arc is what the engine is DOING. This one is what the rider is
     * ASKING of it, and the gap between them is engine response made visible:
     * open the throttle and the inner arc leaps ahead, shut it and the outer one
     * runs on alone while the engine winds down.
     *
     * Neither number says that on its own. It is drawable only because both were
     * decoded on the same day, the throttle having spent a month inside a
     * message we were reading one field of out of four.
     */
    var throttlePct: Int? = null
        set(v) { field = v; invalidate() }

    /** Called when the rider long-presses the ride figure on the speed dial. */
    var onRideReset: (() -> Unit)? = null

    private var touchX = 0f
    private var touchY = 0f

    init { isLongClickable = true }

    /** Which turn arrows this dial carries. */
    var turnSide: TurnSide = TurnSide.BOTH
        set(v) { field = v; invalidate() }

    /** Position in the power-on cascade. */
    var introDelay: Long = Cluster.STAGGER_TACHO

    var rpm: Int? = null
        set(value) { field = value; postInvalidateOnAnimation() }

    var speedKmh: Double? = null
        set(value) { field = value; postInvalidateOnAnimation() }

    var gear: String? = null
        set(value) { field = value; invalidate() }

    var indLeft: Boolean? = null
    var indRight: Boolean? = null

    fun setTurnSignals(left: Boolean?, right: Boolean?) {
        indLeft = left; indRight = right
        invalidate()
    }

    private var displayed = 0f          // in the dial's own units
    private var lastFrameNs = 0L
    private var introStart = 0L

    private val arcRect = RectF()

    // Scale, redline and units are settings now, read per draw so a change on
    // the settings screen shows the moment you come back.
    private val maxValue: Float
        get() = if (dial == Dial.RPM) Settings.maxRpm.toFloat() else Settings.maxSpeed
    private val redline: Float get() = Settings.redlineRpm.toFloat()

    /** Speed in whatever units are showing; rpm is rpm everywhere. */
    private val liveValue: Float?
        get() = if (dial == Dial.RPM) rpm?.toFloat()
                else speedKmh?.let { Settings.speed(it).toFloat() }

    /** Slower on the speedo: road speed genuinely does not move like engine speed. */
    private val tau: Float get() = if (dial == Dial.RPM) 0.080f else 0.160f

    override fun playIntro() {
        introStart = System.currentTimeMillis()
        displayed = 0f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = min(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val radius = size / 2f * 0.80f
        val stroke = size * 0.045f

        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1_000_000_000f)
        lastFrameNs = now

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Backlight, bezel and glass, shared with every other dial in the app.
        DialFace.chrome(canvas, cx, cy, radius, size)

        dialPaint.strokeWidth = stroke
        dialPaint.color = colDial
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, dialPaint)

        if (dial == Dial.RPM) {
            redlinePaint.strokeWidth = stroke
            redlinePaint.color = colRedline
            canvas.drawArc(
                arcRect,
                START_ANGLE + SWEEP_ANGLE * (redline / maxValue),
                SWEEP_ANGLE * (1f - redline / maxValue), false, redlinePaint
            )
        }

        drawTicks(canvas, cx, cy, radius, stroke, size)

        val intro = Cluster.introProgress(introStart, introDelay)
        val fraction: Float?
        if (intro != null) {
            fraction = Cluster.introSweep(intro)
            displayed = fraction * maxValue
            postInvalidateOnAnimation()
        } else {
            val target = liveValue
            if (target == null) {
                displayed = 0f
                fraction = null
            } else {
                displayed = Cluster.ease(displayed, target, dt, tau)
                if (kotlin.math.abs(target - displayed) > 0.4f) postInvalidateOnAnimation()
                fraction = (displayed / maxValue).coerceIn(0f, 1f)
            }
        }

        if (fraction != null) {
            drawLitArc(canvas, stroke, fraction)
            drawNeedle(canvas, cx, cy, radius, size, fraction)
        }

        drawTurnSignals(canvas, cx, cy, radius, size, intro)
        drawHub(canvas, cx, cy, size, intro)

        // On the centre line, below the RPM caption.
        //
        // It sat low and left first, which put it across the dial's own figure
        // and made it small to keep it clear of the needle. Under the caption
        // it has the whole width to itself, so it can be half again as big and
        // reads as part of the readout rather than something parked on top of
        // it.
        //
        // Only on the tachometer: the speedometer already carries the turn
        // arrows and the gear window, and a third lamp would crowd the one dial
        // a rider actually reads at speed.
        if (dial == Dial.RPM) {
            drawThrottleArc(canvas, radius, stroke)
            IgnitionLamp.draw(canvas, cx, cy + size * 0.222f,
                              size * 0.070f, ignition, bezelPaint, labelPaint)
        } else {
            drawRide(canvas, cx, cy, size)
        }
    }

    /**
     * This ride, under the speedometer caption.
     *
     * The one number on the cluster the app owns rather than reads, so it earns
     * a place on a dial face rather than a line of small print. Long-press it to
     * start it again -- aimed at the figure itself, because a gesture that wipes
     * a number should not be triggered by a press anywhere on a speedometer.
     */
    private fun drawRide(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val km = rideKm ?: return
        val v = Settings.distance(km)

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = colMuted
        labelPaint.textSize = size * 0.040f
        labelPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        labelPaint.letterSpacing = 0.20f
        canvas.drawText("RIDE", cx, cy + size * 0.212f, labelPaint)
        labelPaint.letterSpacing = 0f

        labelPaint.color = colInk
        labelPaint.textSize = size * 0.072f
        labelPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(
            (if (v < 100) "%.1f".format(v) else "%.0f".format(v)) + " " + Settings.distanceLabel,
            cx, cy + size * 0.292f, labelPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchX = event.x; touchY = event.y
        }
        return super.onTouchEvent(event)
    }

    override fun performLongClick(): Boolean {
        // The lower middle of the speed dial, where the ride figure sits.
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        if (dial == Dial.SPEED && rideKm != null &&
            kotlin.math.abs(touchX - cx) < size * 0.20f &&
            touchY > cy + size * 0.14f && touchY < cy + size * 0.36f) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onRideReset?.invoke()
            return true
        }
        return super.performLongClick()
    }

    /**
     * The hand, inside the arc that shows the engine.
     *
     * Set in from the main arc by a little over its own width, so the two read
     * as related rather than as one thick band. Thin, and in the muted ink
     * rather than the accent, because it is the reference the bright arc is
     * chasing -- if it competed for attention the dial would have two subjects
     * and no answer.
     */
    private fun drawThrottleArc(canvas: Canvas, radius: Float, stroke: Float) {
        val t = throttlePct ?: return
        val inner = radius - stroke * 1.25f
        throttleRect.set(arcRect.centerX() - inner, arcRect.centerY() - inner,
                         arcRect.centerX() + inner, arcRect.centerY() + inner)

        tickPaint.color = colDial
        tickPaint.strokeWidth = stroke * 0.30f
        tickPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(throttleRect, START_ANGLE, SWEEP_ANGLE, false, tickPaint)

        val f = (t / 100f).coerceIn(0f, 1f)
        if (f > 0f) {
            tickPaint.color = colMuted
            canvas.drawArc(throttleRect, START_ANGLE, SWEEP_ANGLE * f, false, tickPaint)
        }
    }

    private val throttleRect = RectF()

    /** The travelled part of the dial glows, so the sweep leaves a trail. */
    private fun drawLitArc(canvas: Canvas, stroke: Float, fraction: Float) {
        val hot = dial == Dial.RPM && displayed >= redline
        litPaint.strokeWidth = stroke
        litPaint.shader = DialFace.litShader(
            arcRect.centerX(), arcRect.centerY(),
            START_ANGLE, SWEEP_ANGLE, if (hot) colRedline else colAccent
        )
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE * fraction, false, litPaint)
        litPaint.shader = null
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float, stroke: Float, size: Float) {
        labelPaint.color = colMuted
        labelPaint.textSize = size * 0.050f
        labelPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

        val minorStep = if (dial == Dial.RPM) 500 else Settings.speedMinorTick
        val majorStep = if (dial == Dial.RPM) 1000 else Settings.speedMajorTick

        var tick = 0
        while (tick <= maxValue.toInt()) {
            val major = tick % majorStep == 0
            val frac = tick / maxValue
            val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * frac).toDouble())
            val inner = radius - stroke / 2f - size * (if (major) 0.045f else 0.026f)
            val outer = radius - stroke / 2f - size * 0.008f

            tickPaint.strokeWidth = if (major) size * 0.010f else size * 0.005f
            tickPaint.color =
                if (dial == Dial.RPM && tick >= redline) colRedline else colTick
            canvas.drawLine(
                cx + (cos(angle) * inner).toFloat(), cy + (sin(angle) * inner).toFloat(),
                cx + (cos(angle) * outer).toFloat(), cy + (sin(angle) * outer).toFloat(),
                tickPaint
            )

            if (major) {
                val labelR = inner - size * 0.055f
                val text = if (dial == Dial.RPM) (tick / 1000).toString() else tick.toString()
                canvas.drawText(
                    text,
                    cx + (cos(angle) * labelR).toFloat(),
                    cy + (sin(angle) * labelR).toFloat() + labelPaint.textSize / 3f,
                    labelPaint
                )
            }
            tick += minorStep
        }
    }

    /**
     * A tapered blade with a counterweight, not a drawn line.
     *
     * The taper is what separates an instrument from a progress indicator: a
     * real needle is widest at the hub, where the load is, and narrows to a
     * point. Drawing it rotated in local coordinates keeps the geometry
     * readable and lets the hub cap sit on top of the blade's root.
     */
    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, radius: Float, size: Float, fraction: Float) {
        val angleDeg = START_ANGLE + SWEEP_ANGLE * fraction
        val hot = dial == Dial.RPM && displayed >= redline
        val colour = if (hot) colRedline else colAccent

        // Light thrown onto the face around the hub.
        DialFace.halo(canvas, cx, cy, size * 0.19f, colour, 89)

        val tipLen = radius - size * 0.068f
        val tailLen = size * 0.072f
        val wHub = size * 0.019f
        val wTip = size * 0.005f
        val wTail = size * 0.013f

        canvas.save()
        canvas.rotate(angleDeg, cx, cy)

        needlePaint.style = Paint.Style.FILL
        needlePaint.color = colour
        arrowPath.reset()
        arrowPath.moveTo(cx + tipLen, cy - wTip)
        arrowPath.lineTo(cx + size * 0.030f, cy - wHub)
        arrowPath.lineTo(cx - tailLen, cy - wTail)
        arrowPath.lineTo(cx - tailLen, cy + wTail)
        arrowPath.lineTo(cx + size * 0.030f, cy + wHub)
        arrowPath.lineTo(cx + tipLen, cy + wTip)
        arrowPath.close()
        canvas.drawPath(arrowPath, needlePaint)

        // A lit edge along the upper flank — the blade catches the dial light.
        needlePaint.color = Color.WHITE
        needlePaint.alpha = 60
        arrowPath.reset()
        arrowPath.moveTo(cx + tipLen, cy - wTip)
        arrowPath.lineTo(cx + size * 0.030f, cy - wHub)
        arrowPath.lineTo(cx + size * 0.030f, cy - wHub * 0.45f)
        arrowPath.lineTo(cx + tipLen, cy - wTip * 0.35f)
        arrowPath.close()
        canvas.drawPath(arrowPath, needlePaint)
        needlePaint.alpha = 255

        canvas.restore()

        // Hub cap: bright ring, dark centre, so the blade appears to pass under it.
        needlePaint.color = colour
        canvas.drawCircle(cx, cy, size * 0.040f, needlePaint)
        needlePaint.color = colFace
        canvas.drawCircle(cx, cy, size * 0.028f, needlePaint)
        needlePaint.color = colour
        needlePaint.alpha = 120
        canvas.drawCircle(cx, cy, size * 0.013f, needlePaint)
        needlePaint.alpha = 255

        if (hot) postInvalidateOnAnimation()
    }

    // ------------------------------------------------------- turn signals

    private fun drawTurnSignals(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, size: Float, intro: Float?
    ) {
        if (turnSide == TurnSide.NONE) return

        // In the dial's own opening at the bottom.
        //
        // They started at ten and two, which is where a cluster usually puts
        // them — but that is also where the tick labels live, and on the speed
        // face they landed squarely on 60 and 120. The arc spans 150° to 30°, so
        // the bottom of the face carries no track, no ticks and no numbers: the
        // one place on the instrument that is genuinely free.
        // Moved down into the opening, from 143/37 to 127/53.
        //
        // Seven degrees off the end of the arc was not enough: at that angle the
        // arrow sat about 0.02 of the dial from the first and last tick labels —
        // touching distance for the 0 and the 200 on the speed face. Pushing
        // them toward the bottom of the gap quadruples that clearance, and the
        // gap has the room because nothing else lives there: the arc has ended,
        // and the hub stack is a narrow column down the centre line.
        val leftAngle = Math.toRadians(127.0)
        val rightAngle = Math.toRadians(53.0)
        val r = radius * 0.93f
        val leftX = cx + (cos(leftAngle) * r).toFloat()
        val rightX = cx + (cos(rightAngle) * r).toFloat()
        val y = cy + (sin(leftAngle) * r).toFloat()

        val showLeft = turnSide == TurnSide.LEFT || turnSide == TurnSide.BOTH
        val showRight = turnSide == TurnSide.RIGHT || turnSide == TurnSide.BOTH

        // During the lamp test both are forced on for the same window.
        val testLit = intro != null && intro < 0.78f && intro > 0.06f

        if (showLeft) drawArrow(canvas, leftX, y, size, if (intro != null) testLit else indLeft, true)
        if (showRight) drawArrow(canvas, rightX, y, size, if (intro != null) testLit else indRight, false)
        if (intro != null) postInvalidateOnAnimation()
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, size: Float, state: Boolean?, left: Boolean) {
        val lit = state == true
        val colour = when (state) {
            true -> colTurn
            false -> colOff
            null -> colOff
        }

        if (lit) {
            // A lit indicator throws light onto the face around it.
            DialFace.halo(canvas, cx, cy, size * 0.105f, colTurn, 102)
        }

        needlePaint.color = colour
        needlePaint.style = Paint.Style.FILL
        val s = size * 0.048f
        val dir = if (left) -1f else 1f
        arrowPath.reset()
        arrowPath.moveTo(cx + dir * s, cy)
        arrowPath.lineTo(cx - dir * s * 0.12f, cy - s * 0.88f)
        arrowPath.lineTo(cx - dir * s * 0.12f, cy - s * 0.32f)
        arrowPath.lineTo(cx - dir * s, cy - s * 0.32f)
        arrowPath.lineTo(cx - dir * s, cy + s * 0.32f)
        arrowPath.lineTo(cx - dir * s * 0.12f, cy + s * 0.32f)
        arrowPath.lineTo(cx - dir * s * 0.12f, cy + s * 0.88f)
        arrowPath.close()
        canvas.drawPath(arrowPath, needlePaint)

        // Never reported: struck through, so a dark arrow is not read as "off".
        if (state == null) {
            needlePaint.style = Paint.Style.STROKE
            needlePaint.color = colStrike
            needlePaint.strokeWidth = size * 0.008f
            canvas.drawLine(cx - s, cy + s, cx + s, cy - s, needlePaint)
        }
    }

    // --------------------------------------------------------------- hub

    private fun drawHub(canvas: Canvas, cx: Float, cy: Float, size: Float, intro: Float?) {
        // Speed is the primary instrument, so it carries the gear window —
        // the two things a rider glances down for live together, and the
        // tachometer keeps only its own number.
        if (dial == Dial.SPEED) drawGearWindow(canvas, cx, cy, size, intro)
        else drawRpmReadout(canvas, cx, cy, size, intro)
    }

    /**
     * The gear position indicator, as a lit window rather than plain text.
     *
     * Neutral is green — that is the convention on every motorcycle, and a rider
     * reads the colour before the letter. A selected gear is amber, matching the
     * instrument lighting. Unknown is a dim dash in an unlit window.
     */
    /**
     * The speed dial's hub stack, all of it BELOW the pivot.
     *
     * The gear window went above first, which put it straight under the "100"
     * tick — the top of a 0-200 scale is dead centre of the face, so there is no
     * room up there. Below the pivot is the arc's own opening: no track, no
     * ticks, no numbers, and the turn arrows are out at the sides. Everything
     * therefore reads down the centre line in order: speed, its unit, then the
     * gear.
     */
    private fun drawGearWindow(canvas: Canvas, cx: Float, cy: Float, size: Float, intro: Float?) {
        // Digital speed, first clear of the hub cap.
        textPaint.color = colInk
        textPaint.textSize = size * 0.092f
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val shown = when {
            intro != null -> displayed.toInt().toString()
            speedKmh != null -> String.format("%.0f", displayed)
            else -> "---"
        }
        canvas.drawText(shown, cx, cy + size * 0.132f, textPaint)

        textPaint.color = colMuted
        textPaint.textSize = size * 0.034f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textPaint.letterSpacing = 0.18f
        canvas.drawText(Settings.speedLabel, cx, cy + size * 0.180f, textPaint)
        textPaint.letterSpacing = 0f

        // Gear window under it. No caption: a lit window with a green N says
        // what it is, and a label here would only crowd the column.
        val g = if (intro != null) null else gear
        val neutral = g == "N"
        val known = g != null
        val colour = when {
            !known -> colMuted
            neutral -> colNeutral
            else -> colAccent
        }

        val halfW = size * 0.072f
        val boxTop = cy + size * 0.204f
        val boxBottom = boxTop + size * 0.132f
        val box = RectF(cx - halfW, boxTop, cx + halfW, boxBottom)
        val r = size * 0.018f

        bezelPaint.style = Paint.Style.FILL
        bezelPaint.color = colFace
        canvas.drawRoundRect(box, r, r, bezelPaint)

        if (known) {
            DialFace.halo(canvas, box.centerX(), box.centerY(), halfW * 2.0f, colour, 51)
        }

        bezelPaint.style = Paint.Style.STROKE
        bezelPaint.strokeWidth = size * 0.005f
        bezelPaint.color = if (known) colour else colDial
        canvas.drawRoundRect(box, r, r, bezelPaint)

        textPaint.color = colour
        textPaint.textSize = size * 0.104f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        canvas.drawText(g ?: "–", box.centerX(), box.centerY() + size * 0.037f, textPaint)
    }

    /** The tachometer's hub: its own number, nothing else competing for it. */
    private fun drawRpmReadout(canvas: Canvas, cx: Float, cy: Float, size: Float, intro: Float?) {
        val hot = displayed >= redline
        val shown = when {
            intro != null -> displayed.toInt().toString()
            rpm != null -> rpm.toString()
            else -> "----"
        }
        textPaint.color = when {
            hot -> colRedline
            rpm == null && intro == null -> colMuted
            else -> colInk
        }
        if (hot) textPaint.alpha = (150 + 105 * Cluster.pulse()).toInt()
        textPaint.textSize = size * 0.140f
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        // Baseline above the pivot: the figure sits clear of the hub cap rather
        // than across it, which is where it was.
        canvas.drawText(shown, cx, cy - size * HUB_CLEARANCE - size * 0.014f, textPaint)
        textPaint.alpha = 255

        textPaint.color = colMuted
        textPaint.textSize = size * 0.046f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textPaint.letterSpacing = 0.20f
        canvas.drawText("RPM", cx, cy + size * 0.140f, textPaint)
        textPaint.letterSpacing = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Cluster.register(this)
    }

    override fun onDetachedFromWindow() {
        Cluster.unregister(this)
        super.onDetachedFromWindow()
    }
}

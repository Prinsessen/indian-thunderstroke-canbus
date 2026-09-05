package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * One wheel: a tyre ring whose fill tracks pressure against target, with the
 * cold-equivalent figure in the hub.
 *
 * The hub shows the **cold equivalent**, not the raw sensor reading, because
 * that is the number a rider acts on — a tyre at 44.7 PSI and 42 °C is not
 * over-inflated, it is warm. The raw pair is kept underneath so nothing is
 * hidden, and the ring is scaled to ±8 PSI around target, which is wide enough
 * to cover a genuinely soft tyre and tight enough that a 2 PSI drift is visible.
 */
class TyreView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), Cluster.Instrument {

    companion object {
        private const val SPAN_PSI = 8.0        // ring covers target ± this
        private const val START_ANGLE = 135f
        private const val SWEEP_ANGLE = 270f
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val colTrack = Color.parseColor("#232830")
    private val colInk = Color.parseColor("#F2F5F9")
    private val colMuted = Color.parseColor("#7C8797")
    private val colOk = Color.parseColor("#4FA96B")
    private val colWatch = Color.parseColor("#E8A33D")
    private val colAct = Color.parseColor("#D2452F")
    private val colUnknown = Color.parseColor("#3A414D")
    private val colWarm = Color.parseColor("#E8A33D")

    /**
     * The cold figure runs cool, which is the owner's idea and a good one.
     *
     * Two monospace numbers of similar size sitting side by side are easy to
     * read the wrong way round, and a tint separates them faster than the
     * captions beneath can. This is colour as a category marker rather than as a
     * state -- the cold equivalent is always the cold equivalent.
     *
     * Her other half was to make the temperature a warm red, and that one is
     * declined: the temperature ALREADY changes colour, white below 35 C and
     * amber above, so a fixed red would delete a signal rather than add one. Red
     * is also colAct on this page, the colour for a pressure that needs acting
     * on, and a permanently red number beside it would compete with the warning
     * that matters.
     */
    private val colCold = Color.parseColor("#7FB3E0")

    /**
     * The target, in a quieter shade of the same blue.
     *
     * It was muted grey, which the owner called boring white, and she is right
     * that it deserved better than the colour everything unimportant already
     * wears.
     *
     * Blue rather than a fourth hue because of what the number IS. The cold
     * figure and the target are the pair being compared -- cold against cold --
     * and the temperature beside them is the outsider that explains why they
     * differ. Sharing a family says that without a caption. Dimmer than the
     * reading, because a setpoint should sit behind the measurement rather than
     * compete with it.
     */
    private val colTarget = Color.parseColor("#6E93AF")

    private val rect = RectF()

    /** Wheel label — "FRONT" / "REAR". */
    var label: String = ""
        set(v) { field = v; invalidate() }

    /** null = never seen a reading; the view says so rather than drawing zero. */
    var wheel: TyreMemory.Wheel? = null
        set(v) { field = v; postInvalidateOnAnimation() }

    private var displayed = 0f
    private var lastFrameNs = 0L
    private var introStart = 0L

    /**
     * The wheels sweep with the rest of the cluster on power-on.
     *
     * They were the one pair of dials that did not, which made the tyre page
     * look like a static readout the app had pasted in rather than part of the
     * same instrument.
     */
    override fun playIntro() {
        introStart = System.currentTimeMillis()
        displayed = 0f
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Cluster.register(this)
    }

    override fun onDetachedFromWindow() {
        Cluster.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = min(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val radius = size / 2f * 0.74f
        val stroke = size * 0.085f

        // Backlight, bezel and glass, the same as every other dial in the app.
        DialFace.chrome(canvas, cx, cy, radius + stroke / 2f + size * 0.020f, size)

        trackPaint.strokeWidth = stroke
        trackPaint.color = colTrack
        valuePaint.strokeWidth = stroke

        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rect, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1_000_000_000f)
        lastFrameNs = now

        val data = wheel
        val intro = Cluster.introProgress(introStart, Cluster.STAGGER_MINOR)

        // The sweep runs whether or not a wheel has ever reported, because a
        // lamp test that skipped the unknown dials would tell a rider the
        // instrument is broken when it is only uninformed.
        if (intro != null) {
            displayed = Cluster.introSweep(intro)
            valuePaint.color = colWatch
            valuePaint.shader = DialFace.litShader(cx, cy, START_ANGLE, SWEEP_ANGLE, colWatch)
            canvas.drawArc(rect, START_ANGLE, SWEEP_ANGLE * displayed, false, valuePaint)
            valuePaint.shader = null
            postInvalidateOnAnimation()
            if (data == null) { drawEmpty(canvas, cx, cy, size); return }
            drawValues(canvas, cx, cy, size, data, colourOf(data))
            return
        }

        if (data == null) {
            displayed = 0f
            drawEmpty(canvas, cx, cy, size)
            return
        }

        val colour = colourOf(data)

        // Ring fill: where the judged pressure sits inside target ± SPAN.
        //
        // Eased rather than set, and slowly. Pressure is not a quantity that
        // jumps, so an arc that snapped to each new reading would be reporting
        // the sensor's resolution rather than the tyre's behaviour.
        val frac = (((data.judged - data.target) / SPAN_PSI + 1.0) / 2.0).coerceIn(0.0, 1.0)
        displayed = Cluster.ease(displayed, frac.toFloat(), dt, tau = 0.30f)
        if (kotlin.math.abs(frac.toFloat() - displayed) > 0.002f) postInvalidateOnAnimation()

        valuePaint.color = colour
        valuePaint.shader = DialFace.litShader(cx, cy, START_ANGLE, SWEEP_ANGLE, colour)
        canvas.drawArc(rect, START_ANGLE, SWEEP_ANGLE * displayed, false, valuePaint)
        valuePaint.shader = null

        // Target notch at the ring's midpoint, so "correct" is a visible place
        // on the dial rather than a number to remember.
        markPaint.color = colInk
        markPaint.strokeWidth = size * 0.010f
        val midAngle = Math.toRadians((START_ANGLE + SWEEP_ANGLE / 2f).toDouble())
        val inner = radius - stroke / 2f - size * 0.012f
        val outer = radius + stroke / 2f + size * 0.012f
        canvas.drawLine(
            cx + (Math.cos(midAngle) * inner).toFloat(), cy + (Math.sin(midAngle) * inner).toFloat(),
            cx + (Math.cos(midAngle) * outer).toFloat(), cy + (Math.sin(midAngle) * outer).toFloat(),
            markPaint
        )

        drawValues(canvas, cx, cy, size, data, colour)
    }

    private fun colourOf(data: TyreMemory.Wheel): Int = when (data.level) {
        TyreMemory.Level.OK -> colOk
        TyreMemory.Level.WATCH -> colWatch
        TyreMemory.Level.ACT -> colAct
    }

    private fun drawValues(
        canvas: Canvas, cx: Float, cy: Float, size: Float,
        data: TyreMemory.Wheel, colour: Int
    ) {
        textPaint.color = colMuted
        textPaint.textSize = size * 0.070f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText(label, cx, cy - size * 0.150f, textPaint)

        // Pressure is stored in PSI and converted here, so switching units never
        // rounds a stored target away.
        val dec = Settings.pressureUnit.decimals
        val unitLabel = Settings.pressureLabel

        // THE SENSOR'S OWN NUMBER, not the cold equivalent.
        //
        // The cold figure led here until 2026-09-05, and the owner caught what
        // that costs: her dash read 35.4 while this page read 33.7 and the
        // banner said 2.3 under a 36 target. Three numbers, all correct, and no
        // reason for a rider to believe ours over the motorcycle's.
        //
        // Both are still true. The comparison simply moved: instead of cooling
        // the measurement to meet a cold target, the target is warmed to meet
        // the measurement. Checked on those same numbers -- 2.3 under either
        // way -- so nothing is lost but the contradiction.
        textPaint.color = colour
        textPaint.textSize = size * 0.215f
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(
            String.format("%.${dec}f", Settings.pressure(data.psi)),
            cx, cy + size * 0.055f, textPaint
        )

        textPaint.color = colMuted
        textPaint.textSize = size * 0.058f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText(unitLabel, cx, cy + size * 0.125f, textPaint)

        // The cold equivalent and the temperature that produced it, side by
        // side under the sensor's own figure.
        //
        // The cold number came out for one version and went straight back in.
        // It is the figure that sent the owner to the garage with a gauge, and
        // it held to within 0.2 PSI on both wheels -- so it has earned its place
        // more thoroughly than anything else on this page.
        //
        // The layout now says the whole thing in order: what is in the tyre,
        // what that would be cold, the temperature that separates them, and the
        // cold target to compare the cold figure against. Like with like, and
        // the headline still agrees with the motorcycle's own display.
        // Pulled in from 0.150 on 2026-09-05, the owner's suggestion and the
        // better one -- moving the numbers and their captions together keeps
        // each caption under its own figure, where moving only the captions
        // would have left them tucked inboard.
        //
        // The ring is a BAND, not a line: radius 0.37 with an 0.085 stroke, so
        // it occupies 0.328 to 0.412. Both rows sat inside that. The arc is open
        // at the bottom, which is why they mostly got away with it -- but its
        // ends carry round caps that extend the sweep by half a stroke width,
        // 6.6 degrees at this radius, and TEMP's outer corner landed at 50.5
        // where the cap reaches 51.6.
        //
        // At 0.125 the temperature clears the band entirely (0.313 against an
        // inner edge of 0.328) and TEMP sits at 53.7 degrees, two clear of the
        // cap. Measured against the arc this time, not against the rim -- the
        // first attempt checked the outer edge of the dial and passed something
        // that was inside the ring the whole while.
        val colX = size * 0.125f
        val rowY = cy + size * 0.225f

        // Nudged right by half a character.
        //
        // The two columns are centred symmetrically on cx +/- colX, and still
        // look shifted left, because the left figure is four monospace
        // characters ("33.7") and the right is three ("28\u00b0"). Same centres,
        // different amounts of ink either side of them.
        //
        // Half a monospace advance at this text size is 0.6 * 0.078 / 2, which
        // is 0.023 of the dial. Optical centring rather than geometric, and the
        // owner spotted it by eye before it was measured.
        val optical = size * 0.023f

        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textPaint.textSize = size * 0.078f

        textPaint.color = if (data.coldPsi != null) colCold else colInk
        canvas.drawText(
            String.format("%.${dec}f", Settings.pressure(data.judged)),
            cx - colX + optical, rowY, textPaint
        )

        // Warms towards the accent as it rises above ambient, which is exactly
        // when the two pressure figures pull furthest apart.
        textPaint.color = if (data.tempC >= 35.0) colWarm else colInk
        canvas.drawText(
            String.format("%.0f\u00b0", Settings.temperature(data.tempC)), cx + colX + optical, rowY, textPaint
        )

        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textPaint.textSize = size * 0.046f
        textPaint.letterSpacing = 0.12f
        textPaint.color = colMuted
        // NO optical offset on the captions, and that was a mistake worth
        // naming. The 0.023 nudge exists because the two FIGURES are different
        // widths -- "33.7" is four monospace characters and "28\u00b0" is three --
        // so their ink sits unevenly about their centres. COLD and TEMP are both
        // four characters. They were already symmetric, and shifting them made
        // them genuinely off-centre while pushing TEMP a further 0.023 towards
        // the ring it was already close to. The owner saw both at once.
        canvas.drawText(if (data.coldPsi != null) "COLD" else "RAW", cx - colX, rowY + size * 0.060f, textPaint)
        canvas.drawText("TEMP", cx + colX, rowY + size * 0.060f, textPaint)

        // The cold target, because the figure to its left is now a cold one.
        textPaint.textSize = size * 0.044f
        textPaint.color = colTarget
        canvas.drawText(
            String.format("TARGET %.${dec}f", Settings.pressure(data.target)),
            cx, rowY + size * 0.130f, textPaint
        )
        textPaint.letterSpacing = 0f
    }

    private fun drawEmpty(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        textPaint.color = colMuted
        textPaint.textSize = size * 0.070f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText(label, cx, cy - size * 0.130f, textPaint)

        textPaint.color = colUnknown
        textPaint.textSize = size * 0.200f
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("--.-", cx, cy + size * 0.055f, textPaint)

        textPaint.color = colMuted
        textPaint.textSize = size * 0.052f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText("no reading yet", cx, cy + size * 0.135f, textPaint)
    }
}

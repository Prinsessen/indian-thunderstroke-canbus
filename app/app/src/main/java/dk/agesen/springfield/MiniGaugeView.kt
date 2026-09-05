package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Small arc gauge — fuel, coolant, battery, ambient.
 *
 * One reusable dial rather than four bespoke ones, so the Machine page reads as
 * a single instrument cluster instead of four unrelated widgets: same sweep,
 * same tick weight, same type sizes, only the scale and the caution band differ.
 *
 * Caution bands are per-quantity and set by the caller, because "low" means
 * opposite things for fuel (low is bad) and coolant (high is bad).
 */
class MiniGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), Cluster.Instrument {

    companion object {
        private const val START_ANGLE = 140f
        private const val SWEEP_ANGLE = 260f
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val colTrack = Color.parseColor("#232830")
    private val colInk = Color.parseColor("#F2F5F9")
    private val colMuted = Color.parseColor("#7C8797")
    private val colAccent = Color.parseColor("#E8A33D")
    private val colCaution = Color.parseColor("#D2452F")
    private val colDim = Color.parseColor("#3A414D")

    private val rect = RectF()

    var minValue = 0.0
    var maxValue = 100.0
    var label = ""
    var unit = ""
    var decimals = 0

    /** Caution band as a fraction of the sweep, or null for none. */
    var cautionFrom: Double? = null
    var cautionTo: Double? = null

    /** null = not reported; the dial dims and shows dashes rather than zero. */
    var value: Double? = null
        set(v) { field = v; postInvalidateOnAnimation() }

    private var displayed = 0f
    private var lastFrameNs = 0L
    private var introStart = 0L

    /** Fill and drain in step with the tachometer sweep. */
    override fun playIntro() {
        introStart = System.currentTimeMillis()
        displayed = 0f
        postInvalidateOnAnimation()
    }

    fun configure(
        label: String, unit: String, min: Double, max: Double,
        decimals: Int = 0, cautionFrom: Double? = null, cautionTo: Double? = null
    ) {
        this.label = label; this.unit = unit
        this.minValue = min; this.maxValue = max
        this.decimals = decimals
        this.cautionFrom = cautionFrom; this.cautionTo = cautionTo
        invalidate()
    }

    private fun fraction(v: Double) =
        ((v - minValue) / (maxValue - minValue)).coerceIn(0.0, 1.0).toFloat()

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f * 0.66f
        val stroke = size * 0.090f

        // Pulled in from 0.76 to make room for the bezel outside the caution
        // band. The band sits proud of the track by design — it marks the scale
        // rather than colouring the value — so the face has to be wide enough to
        // contain both, or the bezel would cut straight through it.
        val faceRadius = radius + stroke / 2f + size * 0.052f
        DialFace.chrome(canvas, cx, cy, faceRadius, size)

        trackPaint.strokeWidth = stroke
        trackPaint.color = colTrack
        valuePaint.strokeWidth = stroke
        bandPaint.strokeWidth = size * 0.022f

        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rect, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        // Caution band drawn just outside the track, so it marks the scale
        // without colouring the value itself.
        val cFrom = cautionFrom
        val cTo = cautionTo
        if (cFrom != null && cTo != null) {
            val bandR = radius + stroke / 2f + size * 0.030f
            val bandRect = RectF(cx - bandR, cy - bandR, cx + bandR, cy + bandR)
            bandPaint.color = colCaution
            canvas.drawArc(
                bandRect,
                START_ANGLE + SWEEP_ANGLE * fraction(cFrom),
                SWEEP_ANGLE * (fraction(cTo) - fraction(cFrom)),
                false, bandPaint
            )
        }

        drawTicks(canvas, cx, cy, radius, stroke, size)

        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1_000_000_000f)
        lastFrameNs = now

        val intro = Cluster.introProgress(introStart)
        val v = value
        if (intro != null) {
            displayed = Cluster.introSweep(intro)
            lit(cx, cy, colAccent)
            canvas.drawArc(rect, START_ANGLE, SWEEP_ANGLE * displayed, false, valuePaint)
            postInvalidateOnAnimation()
        } else if (v != null) {
            val targetFraction = fraction(v)
            displayed = Cluster.ease(displayed, targetFraction, dt, tau = 0.20f)
            if (kotlin.math.abs(targetFraction - displayed) > 0.002f) postInvalidateOnAnimation()
            val inCaution = cFrom != null && cTo != null && v >= cFrom && v <= cTo
            lit(cx, cy, if (inCaution) colCaution else colAccent)
            canvas.drawArc(rect, START_ANGLE, SWEEP_ANGLE * displayed, false, valuePaint)
        } else {
            displayed = 0f
        }

        textPaint.color = if (v == null && intro == null) colDim else colInk
        textPaint.textSize = size * 0.230f
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val shown = when {
            intro != null -> String.format("%.${decimals}f", minValue + (maxValue - minValue) * displayed)
            v != null -> String.format("%.${decimals}f", v)
            else -> "--"
        }
        canvas.drawText(shown, cx, cy + size * 0.055f, textPaint)

        textPaint.color = colMuted
        textPaint.textSize = size * 0.090f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText(unit, cx, cy + size * 0.155f, textPaint)

        textPaint.textSize = size * 0.085f
        textPaint.letterSpacing = 0.10f
        canvas.drawText(label, cx, cy + size * 0.330f, textPaint)
        textPaint.letterSpacing = 0f
    }

    /** The value arc, lit the same way the big dials light theirs. */
    private fun lit(cx: Float, cy: Float, colour: Int) {
        valuePaint.color = colour
        valuePaint.shader = DialFace.litShader(cx, cy, START_ANGLE, SWEEP_ANGLE, colour)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float, stroke: Float, size: Float) {
        tickPaint.color = colTrack
        tickPaint.strokeWidth = size * 0.012f
        for (i in 0..4) {
            val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * (i / 4f)).toDouble())
            val inner = radius - stroke / 2f - size * 0.030f
            val outer = radius - stroke / 2f - size * 0.004f
            canvas.drawLine(
                cx + (cos(angle) * inner).toFloat(), cy + (sin(angle) * inner).toFloat(),
                cx + (cos(angle) * outer).toFloat(), cy + (sin(angle) * outer).toFloat(),
                tickPaint
            )
        }
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

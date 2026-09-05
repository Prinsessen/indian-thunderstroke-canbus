package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The bike's own heated grips: the level it is set to, and how warm each grip
 * actually is.
 *
 * On the cluster, not on the heat page. The grips belong to the *motorcycle*;
 * the heat page is the rider's own clothing, and mixing the two put a machine
 * reading among personal ones. That is the owner's distinction and it is the
 * right one.
 *
 * Compact by necessity — the speedometer holds the weighted space on this page
 * and must not shrink to make room — so this is a strip in the same family as
 * the fuel bar beneath it: an eyebrow with the figures, and one row of detents.
 *
 * The two temperatures are a measurement rather than an inference. With the
 * heat off they read the air at the bar, which is closer to the rider's hands
 * than the bike's ambient sensor; with it on they read the warmth arriving.
 *
 * Drawn on the same 1700 ms clock as the rest of the app's live elements: one
 * heartbeat, and the same pulse meaning the same thing everywhere.
 */
class GripsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        /** Matched to HeatZoneView and FeltTempView, deliberately. */
        private const val PULSE_MS = 1700L

        /** The temperature axis. Cold enough for a Danish morning, hot enough
         *  for grips at ten, and the same span for both so they compare. */
        private const val MIN_C = -5.0
        private const val MAX_C = 50.0

        /** What the control offers. Ten detents, confirmed on the bike. */
        private const val STEPS = 10
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // The page's palette, unchanged. Cold is the one addition: the existing
    // set has no low end, because nothing else here needed to say "cold" as a
    // colour rather than as an absence.
    private val colCold = Color.parseColor("#3E6B8C")
    private val colWarm = Color.parseColor("#E8A33D")
    private val colHot = Color.parseColor("#D2452F")
    private val colOff = Color.parseColor("#1B212A")
    private val colInk = Color.parseColor("#F2F5F9")
    private val colMuted = Color.parseColor("#7C8797")
    private val colDim = Color.parseColor("#3A414D")

    /** 0..10, or null when the bike has not said. */
    var level: Int? = null
        set(v) { field = v; invalidate() }

    var leftC: Double? = null
        set(v) { field = v; invalidate() }

    var rightC: Double? = null
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private var scale = 1f
    private fun d(v: Float) = v * density * scale

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        scale = min(1f, h / (48f * density)).coerceAtLeast(0.72f)
    }

    /**
     * Cold to hot across the axis.
     *
     * Interpolated rather than banded, because a grip temperature is a
     * continuous thing and banding it would invent thresholds the bike does not
     * have. The zone panels below band their colours precisely because the
     * controller there *does* have detents.
     */
    private fun tempColour(c: Double): Int {
        val t = ((c - MIN_C) / (MAX_C - MIN_C)).coerceIn(0.0, 1.0)
        return if (t < 0.5) blend(colCold, colWarm, (t / 0.5).toFloat())
        else blend(colWarm, colHot, ((t - 0.5) / 0.5).toFloat())
    }

    private fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    )

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val pulse = Cluster.pulse(PULSE_MS)
        val lvl = level

        // --- row one: what it is, what it is set to, what it reads -----------
        text.textAlign = Paint.Align.LEFT
        text.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
        text.textSize = d(9f)
        text.letterSpacing = 0.16f
        text.color = colMuted
        canvas.drawText("GRIPS", 0f, d(10f), text)
        text.letterSpacing = 0f

        text.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        text.textSize = d(11f)
        text.color = if (lvl == null) colDim else if (lvl == 0) colMuted else colInk
        canvas.drawText(
            lvl?.let { if (it == 0) "OFF" else "$it / $STEPS" } ?: "--",
            d(42f), d(10f), text
        )

        // The two figures sit at the right, each in its own temperature colour,
        // so a cold grip and a hot one differ before either number is read.
        //
        // Right-hand figure first, walking leftwards, so they land in the order
        // a rider sees them from the saddle: left grip on the left. Each is
        // marked L or R -- the two are often a couple of degrees apart and
        // unlabelled numbers would leave it to guesswork which is which.
        text.textAlign = Paint.Align.RIGHT
        var x = w
        for ((mark, c) in listOf("R" to rightC, "L" to leftC)) {
            text.textSize = d(12f)
            text.color = if (c == null) colDim else tempColour(c)
            val fig = c?.let { "%.0f%s".format(Settings.temperature(it), Settings.temperatureLabel) } ?: "--"
            canvas.drawText(fig, x, d(11f), text)
            x -= text.measureText(fig)
            text.textSize = d(9f)
            text.color = colMuted
            canvas.drawText(mark, x - d(2f), d(11f), text)
            x -= text.measureText(mark) + d(14f)
        }

        // --- row two: the ten detents ----------------------------------------
        // Separate notches rather than a bar: the control has ten positions, and
        // a continuous bar would imply values between them.
        val notchTop = d(18f)
        val notchH = d(6f)
        val gap = d(3f)
        val notchW = (w - gap * (STEPS - 1)) / STEPS
        for (i in 0 until STEPS) {
            val on = lvl != null && i < lvl
            p.color = if (on) blend(colWarm, colHot, i / (STEPS - 1f)) else colOff
            // The topmost lit notch breathes; the rest sit still. One glance
            // says which detent is in force.
            p.alpha = if (on && i == lvl!! - 1) (200 + 55 * pulse).toInt() else 255
            rect.set(i * (notchW + gap), notchTop, i * (notchW + gap) + notchW, notchTop + notchH)
            canvas.drawRoundRect(rect, notchH / 2f, notchH / 2f, p)
        }
        p.alpha = 255

        // A hairline under the unlit part, so an OFF strip still reads as a
        // control with ten positions rather than an empty band.
        if (lvl == null || lvl == 0) {
            p.color = colDim
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(0.8f)
            for (i in 0 until STEPS) {
                rect.set(i * (notchW + gap), notchTop, i * (notchW + gap) + notchW, notchTop + notchH)
                canvas.drawRoundRect(rect, notchH / 2f, notchH / 2f, p)
            }
            p.style = Paint.Style.FILL
        }

        // The pulse runs only while there is something live to pulse about.
        if (lvl != null && lvl > 0 && hasWindowFocus() && isAttachedToWindow) {
            postInvalidateOnAnimation()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) invalidate()
    }

}

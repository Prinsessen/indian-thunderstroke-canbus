package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Fuel, on the ride screen where it belongs.
 *
 * It began life as one of four arc gauges on the Machine page, which is the
 * wrong place for it: fuel is not a diagnostic you go and look up, it is one of
 * the two or three things a rider checks without thinking, alongside speed and
 * gear.
 *
 * A segmented bar rather than another dial. The ride screen already has round
 * instruments competing for attention, and a bar reads at a glance precisely
 * because it is a different shape — the eye finds it without having to identify
 * it first. Segments rather than a continuous fill for the same reason a real
 * cluster uses them: you can count blocks in peripheral vision, but you cannot
 * judge the length of a smooth bar without looking at it properly.
 *
 * Colour carries the meaning, using the same three semantic values as the tyre
 * page so the whole app agrees what green, amber and red mean.
 */
class FuelBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), Cluster.Instrument {

    companion object {
        private const val SEGMENTS = 12

        /** Below this the bar turns amber; below the second, red and breathing. */
        private const val LOW_PCT = 25
        private const val RESERVE_PCT = 12
    }

    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val colOk = Color.parseColor("#4FA96B")
    private val colLow = Color.parseColor("#E8A33D")
    private val colReserve = Color.parseColor("#D2452F")
    private val colEmpty = Color.parseColor("#1E242C")
    private val colMuted = Color.parseColor("#7C8797")
    private val colDim = Color.parseColor("#3A414D")

    /** null = the bus has not reported a level; nothing is lit and the figure dashes. */
    var fuelPct: Int? = null
        set(v) { field = v; postInvalidateOnAnimation() }

    /** Distance remaining, already formatted and in the rider's units. */
    var rangeText: String? = null
        set(v) { field = v; invalidate() }

    private var displayed = 0f
    private var lastFrameNs = 0L
    private var introStart = 0L

    private val rect = RectF()

    override fun playIntro() {
        introStart = System.currentTimeMillis()
        displayed = 0f
        postInvalidateOnAnimation()
    }

    private fun colourFor(pct: Float): Int = when {
        pct <= RESERVE_PCT -> colReserve
        pct <= LOW_PCT -> colLow
        else -> colOk
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1_000_000_000f)
        lastFrameNs = now

        val intro = Cluster.introProgress(introStart, Cluster.STAGGER_MINOR)
        val live = fuelPct?.toFloat()

        if (intro != null) {
            displayed = Cluster.introSweep(intro) * 100f
            postInvalidateOnAnimation()
        } else if (live != null) {
            // Slow: a tank does not slosh between readings, and a fuel bar that
            // twitches is one you learn to ignore.
            displayed = Cluster.ease(displayed, live, dt, tau = 0.45f)
            if (kotlin.math.abs(live - displayed) > 0.2f) postInvalidateOnAnimation()
        } else {
            displayed = 0f
        }

        val known = live != null || intro != null
        val colour = colourFor(displayed)
        val reserve = known && intro == null && displayed <= RESERVE_PCT

        // --- caption on the left, with the range beside it ------------------
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textPaint.textSize = h * 0.20f
        textPaint.letterSpacing = 0.16f
        textPaint.color = colMuted
        canvas.drawText("FUEL", 0f, h * 0.34f, textPaint)
        val captionWidth = textPaint.measureText("FUEL")
        textPaint.letterSpacing = 0f

        // The range sits with the label rather than the percentage: it is the
        // answer to "how far", and the percentage is the answer to "how much".
        // Reading them as one number would be the obvious mistake.
        rangeText?.let {
            textPaint.color = colourFor(displayed)
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textPaint.textSize = h * 0.24f
            canvas.drawText(it, captionWidth + w * 0.035f, h * 0.35f, textPaint)
        }

        // --- figure on the right -------------------------------------------
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textPaint.textSize = h * 0.40f
        textPaint.color = if (known) colour else colDim
        if (reserve) textPaint.alpha = (150 + 105 * Cluster.pulse(800L)).toInt()
        val shown = if (known) "${displayed.toInt()}%" else "--%"
        canvas.drawText(shown, w, h * 0.42f, textPaint)
        textPaint.alpha = 255

        // --- the bar --------------------------------------------------------
        val barTop = h * 0.54f
        val barBottom = h * 0.92f
        val gap = w * 0.006f
        val segW = (w - gap * (SEGMENTS - 1)) / SEGMENTS
        val radius = segW * 0.16f

        val litExact = displayed / 100f * SEGMENTS
        for (i in 0 until SEGMENTS) {
            val left = i * (segW + gap)
            rect.set(left, barTop, left + segW, barBottom)

            // A segment lights once the level reaches its middle, so the bar
            // never claims a block the tank has not actually got.
            val lit = litExact >= i + 0.5f
            segPaint.color = if (lit && known) colour else colEmpty
            if (lit && reserve) {
                segPaint.alpha = (150 + 105 * Cluster.pulse(800L)).toInt()
            }
            canvas.drawRoundRect(rect, radius, radius, segPaint)
            segPaint.alpha = 255
        }

        // Lit segments throw a little light, which is what makes the reserve
        // warning catch the eye rather than merely being red.
        if (known && litExact > 0f) {
            val litW = (litExact.coerceAtMost(SEGMENTS.toFloat()) / SEGMENTS) * w
            glowPaint.shader = RadialGradient(
                litW * 0.5f, (barTop + barBottom) / 2f, litW.coerceAtLeast(1f),
                intArrayOf(colour and 0x00FFFFFF or 0x2B000000, Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, barTop - h * 0.10f, litW, barBottom + h * 0.10f, glowPaint)
            glowPaint.shader = null
        }

        if (reserve) postInvalidateOnAnimation()
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

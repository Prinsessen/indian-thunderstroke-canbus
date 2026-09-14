package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
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
 * page so the whole app agrees what green, amber and red mean -- but graduated
 * across the bar rather than applied to it in one lump. Each block is coloured
 * by WHERE IT SITS IN THE TANK, not by how full the tank currently is, so the
 * bottom of the bar is red whether it is lit or not and the top is green. The
 * point is that the bar then changes colour as it empties without any threshold
 * having to be crossed: the last blocks a rider is running on are the red ones,
 * because those blocks were always the red ones. A flat green bar that turned
 * flat amber at 25 % said the same thing later and all at once.
 *
 * Three speeds of pulse, and they mean three different things:
 *
 *   - the leading block always breathes slowly, on the app's 1700 ms heartbeat,
 *     the same cue GripsView gives its topmost detent: this is where the level
 *     is. Life, not warning.
 *   - below 25 % the leading pair quickens to 1100 ms. Something to plan for.
 *   - below 12 % the whole lit bar, the figure and the glow beat together at
 *     700 ms. Something to act on.
 *
 * A rider should be able to tell which of the three it is from the corner of an
 * eye, without reading the number -- which is the whole argument for pulsing
 * something rather than merely colouring it.
 */
class FuelBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), Cluster.Instrument {

    companion object {
        private const val SEGMENTS = 12

        /** Below this the level reads amber; below the second, red and urgent. */
        private const val LOW_PCT = 25
        private const val RESERVE_PCT = 12

        /** The same two, as tank fractions, which is what the gradient works in. */
        private const val RESERVE_F = 0.12f
        private const val LOW_F = 0.25f

        /** Above this the tank is simply full, and the green stops changing. */
        private const val EASY_F = 0.45f

    }

    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val colOk = Color.parseColor("#4FA96B")
    private val colFull = Color.parseColor("#63C07F")   // the top of a brimmed tank
    private val colLow = Color.parseColor("#E8A33D")
    private val colReserve = Color.parseColor("#D2452F")
    private val colEmpty = Color.parseColor("#1E242C")
    private val colMuted = Color.parseColor("#7C8797")
    private val colDim = Color.parseColor("#3A414D")

    /**
     * One vertical sheen over the whole bar, built once per size.
     *
     * Per-segment shaders would be twelve allocations a frame for the same
     * effect; this is one, and the hairline gaps it also crosses are six
     * thousandths of the width.
     */
    private var gloss: LinearGradient? = null

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

    /**
     * The colour of a given point in the TANK, 0..1 from empty to full.
     *
     * Interpolated between the app's three semantic colours rather than banded,
     * for the reason GripsView gives for its own temperature ramp: a fuel level
     * is a continuous thing, and banding it invents thresholds the tank does not
     * have. The two thresholds it DOES have -- reserve and low -- are the knots
     * the ramp is pinned to, so the gradient and the warnings cannot disagree.
     */
    private fun tankColour(f: Float): Int = when {
        f <= RESERVE_F -> colReserve
        f <= LOW_F -> blend(colReserve, colLow, (f - RESERVE_F) / (LOW_F - RESERVE_F))
        f <= EASY_F -> blend(colLow, colOk, (f - LOW_F) / (EASY_F - LOW_F))
        else -> blend(colOk, colFull, ((f - EASY_F) / (1f - EASY_F)).coerceAtMost(1f))
    }

    /** The same ramp, addressed by percentage, for the figure and the range. */
    private fun colourFor(pct: Float): Int = tankColour((pct / 100f).coerceIn(0f, 1f))

    private fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    )

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        gloss = null            // its stops are in pixels, so it must be rebuilt
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
        // Neither tier fires during the intro sweep: the bar runs from empty to
        // full on every start, and a warning that always goes off is one nobody
        // reads by the second week.
        val reserve = known && intro == null && displayed <= RESERVE_PCT
        val low = known && intro == null && !reserve && displayed <= LOW_PCT

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
        // The figure keeps the bar's company: it beats when the bar beats, at
        // the same rate, so the two never disagree about how bad it is.
        if (reserve) textPaint.alpha = Cluster.breath(150, 105, Cluster.PULSE_URGENT)
        else if (low) textPaint.alpha = Cluster.breath(190, 65, Cluster.PULSE_CAUTION)
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
        // The highest block currently lit -- the one standing at the level.
        val leading = kotlin.math.floor(litExact - 0.5f).toInt()

        for (i in 0 until SEGMENTS) {
            val left = i * (segW + gap)
            rect.set(left, barTop, left + segW, barBottom)

            // A segment lights once the level reaches its middle, so the bar
            // never claims a block the tank has not actually got.
            val lit = litExact >= i + 0.5f
            // Its own place in the tank, which is what gives it its colour --
            // lit or not. See tankColour.
            val own = tankColour((i + 0.5f) / SEGMENTS)

            if (lit && known) {
                segPaint.color = own
                segPaint.alpha = when {
                    reserve -> Cluster.breath(140, 115, Cluster.PULSE_URGENT)          // all of it
                    low && i >= leading - 1 -> Cluster.breath(170, 85, Cluster.PULSE_CAUTION)
                    i == leading -> Cluster.breath(200, 55, Cluster.PULSE_CALM)         // just the level
                    else -> 255
                }
            } else {
                // A ghost of what the block will be, so the empty end of the bar
                // still reads as a scale that runs from red to green rather than
                // as a row of holes. Faint enough that it is never mistaken for
                // fuel: the eye reads the lit blocks and nothing else.
                segPaint.color = blend(colEmpty, own, 0.16f)
                segPaint.alpha = 255
            }
            canvas.drawRoundRect(rect, radius, radius, segPaint)
        }
        segPaint.alpha = 255

        // Lit segments throw a little light, which is what makes the reserve
        // warning catch the eye rather than merely being red.
        if (known && litExact > 0f) {
            val litW = (litExact.coerceAtMost(SEGMENTS.toFloat()) / SEGMENTS) * w
            // On reserve the light itself swells and fades rather than merely
            // being red. Peripheral vision answers to movement long before it
            // answers to hue, and the whole reason for a glow is to be seen
            // without being looked at.
            val glowAlpha = if (reserve) Cluster.breath(0x2B, 0x3A, Cluster.PULSE_URGENT) else 0x2B
            glowPaint.shader = RadialGradient(
                litW * 0.5f, (barTop + barBottom) / 2f, litW.coerceAtLeast(1f),
                intArrayOf(colour and 0x00FFFFFF or (glowAlpha shl 24), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, barTop - h * 0.10f, litW, barBottom + h * 0.10f, glowPaint)
            glowPaint.shader = null
        }

        // A sheen down the blocks, the same trick DialFace plays on the dials,
        // so the bar belongs to the same cluster as the instruments beside it
        // rather than looking like a progress bar that wandered in.
        if (gloss == null) {
            gloss = LinearGradient(
                0f, barTop, 0f, barTop + (barBottom - barTop) * 0.55f,
                0x1EFFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        glowPaint.shader = gloss
        canvas.drawRect(0f, barTop, w, barBottom, glowPaint)
        glowPaint.shader = null

        // The bar is never quite still: something is always breathing somewhere,
        // so the frame is always worth asking for -- but only while anyone can
        // see it.
        if (known && hasWindowFocus() && isAttachedToWindow) postInvalidateOnAnimation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) invalidate()
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

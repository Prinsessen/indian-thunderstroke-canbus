package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The power-on identity screen.
 *
 * The badge is a miniature of the cluster's own dial — same 240° arc, same tick
 * spacing, same needle and bounce. That is the point: it introduces the
 * instrument rather than decorating it, and nothing here borrows a trademark.
 *
 * Phases overlap deliberately. A sequence where each element waits politely for
 * the last reads as a slideshow; letting the ring still settle while the
 * wordmark starts to arrive is what makes it one motion instead of six.
 */
class SplashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        /**
         * Long enough to watch. The first pass was under three seconds and read
         * as a flash before the app appeared — the sequence has to have room to
         * breathe or it is not an identity, just a delay.
         */
        const val DURATION_MS = 5400L

        private const val DIAL_START = 150f
        private const val DIAL_SWEEP = 240f
        private const val NEEDLE_REST = 0.62f      // where the badge's needle parks

        /** Filling is always right up to here, whatever fitting would cost. */
        private const val FREE_UPSCALE = 1.35f

        /**
         * How much more magnification filling may cost than fitting before the
         * letterbox wins.
         *
         * The first rule was a flat cap, which was right when the only artwork
         * was a landscape photo shown in portrait — filling meant 3.1x against
         * 0.77x for fitting, so fitting was obviously better. It is wrong now
         * that there is an image per orientation: filling costs only ~1.2x more
         * than fitting, and letterboxing a purpose-made portrait photo *in
         * portrait* to save that would be absurd. What matters is not the
         * absolute enlargement but how much worse filling is than the
         * alternative.
         */
        private const val FILL_TOLERANCE = 1.6f

        /** Never fill beyond this, however favourable the comparison. */
        private const val UPSCALE_CEILING = 2.6f
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }

    private val colGround = Color.parseColor("#07090C")
    private val colDial = Color.parseColor("#1B2028")
    private val colBezel = Color.parseColor("#3B4451")
    private val colAccent = Color.parseColor("#E8A33D")
    private val colInk = Color.parseColor("#F2F5F9")
    private val colMuted = Color.parseColor("#7C8797")

    /** Supplied artwork, if any — see Brand. Null means the drawn badge is used. */
    private val artwork get() = Brand.artwork(context)
    private val displayFace by lazy { Brand.display(context) }

    private var startMillis = 0L
    private var onDone: (() -> Unit)? = null
    private var finished = false

    private val rect = RectF()
    private val shimmerMatrix = Matrix()

    fun play(onDone: () -> Unit) {
        this.onDone = onDone
        finished = false
        startMillis = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    /** Progress of one phase, 0..1, given its window inside the whole run. */
    private fun phase(p: Float, from: Float, to: Float): Float =
        ((p - from) / (to - from)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        if (startMillis == 0L) return

        val elapsed = System.currentTimeMillis() - startMillis
        val p = (elapsed.toFloat() / DURATION_MS).coerceIn(0f, 1f)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.40f
        val size = min(w, h)

        val exit = phase(p, 0.90f, 1f)
        val alpha = ((1f - exit) * 255).toInt().coerceIn(0, 255)

        canvas.drawColor(colGround)

        val art = artwork
        if (art != null) {
            // A photograph is not a badge. It fills the frame and moves, and the
            // drawn dial steps aside rather than sitting on top of it.
            drawFullBleed(canvas, art, w, h, p, alpha)
            if (!Brand.ARTWORK_HAS_WORDMARK) drawWordmark(canvas, cx, h, size, p, alpha)
        } else {
            drawIgnition(canvas, cx, cy, w, size, p, alpha)
            drawEmblem(canvas, cx, cy, size, p, alpha)
            drawWordmark(canvas, cx, h, size, p, alpha)
        }
        drawVignette(canvas, cx, h * 0.5f, w, h, p)

        if (p < 1f) {
            postInvalidateOnAnimation()
        } else if (!finished) {
            finished = true
            onDone?.invoke()
        }
    }

    /**
     * The display waking: a hairline of light opens from the centre, then blooms
     * once and dies away. Old cathode-ray habits, but it is what "powering on"
     * looks like, and it gives the badge something to arrive out of.
     */
    private fun drawIgnition(canvas: Canvas, cx: Float, cy: Float, w: Float, size: Float, p: Float, alpha: Int) {
        val open = Cluster.easeOut(phase(p, 0.00f, 0.13f))
        val fade = 1f - phase(p, 0.10f, 0.30f)
        if (open <= 0f || fade <= 0f) return

        val halfW = w * 0.46f * open
        val lineH = size * 0.0022f + size * 0.010f * (1f - open)

        fillPaint.shader = LinearGradient(
            cx - halfW, 0f, cx + halfW, 0f,
            intArrayOf(Color.TRANSPARENT, colAccent, Color.WHITE, colAccent, Color.TRANSPARENT),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (alpha * fade).toInt().coerceIn(0, 255)
        canvas.drawRect(cx - halfW, cy - lineH, cx + halfW, cy + lineH, fillPaint)
        fillPaint.shader = null

        // The bloom around it.
        val bloom = phase(p, 0.06f, 0.26f)
        if (bloom > 0f && bloom < 1f) {
            glowPaint.shader = RadialGradient(
                cx, cy, size * 0.55f * bloom,
                intArrayOf(colAccent and 0x00FFFFFF or 0x38000000, Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            glowPaint.alpha = (alpha * (1f - bloom)).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, size * 0.55f * bloom, glowPaint)
            glowPaint.shader = null
            glowPaint.alpha = 255
        }
        fillPaint.alpha = 255
    }

    private fun drawEmblem(canvas: Canvas, cx: Float, cy: Float, size: Float, p: Float, alpha: Int) {
        val radius = size * 0.20f


        // 1. The bezel closes around the face, drawn as a sweep so it appears to
        //    be machined into place rather than switched on.
        val bezel = Cluster.easeInOut(phase(p, 0.10f, 0.42f))
        if (bezel > 0f) {
            val outer = radius + size * 0.040f
            ringPaint.strokeWidth = size * 0.014f
            ringPaint.shader = SweepGradient(
                cx, cy,
                intArrayOf(colDial, colBezel, colDial, colBezel, colDial),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            )
            ringPaint.alpha = alpha
            canvas.drawArc(
                RectF(cx - outer, cy - outer, cx + outer, cy + outer),
                -90f, 360f * bezel, false, ringPaint
            )
            ringPaint.shader = null
        }

        // 2. The dial track and its ticks build together.
        val draw = Cluster.easeInOut(phase(p, 0.16f, 0.50f))
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        if (draw > 0f) {
            ringPaint.strokeWidth = size * 0.017f
            ringPaint.color = colDial
            ringPaint.alpha = alpha
            canvas.drawArc(rect, DIAL_START, DIAL_SWEEP * draw, false, ringPaint)

            tickPaint.strokeWidth = size * 0.0045f
            var i = 0
            while (i <= 12) {
                val frac = i / 12f
                if (frac <= draw) {
                    val a = Math.toRadians((DIAL_START + DIAL_SWEEP * frac).toDouble())
                    val major = i % 2 == 0
                    val inner = radius - size * (if (major) 0.034f else 0.026f)
                    val outer = radius - size * 0.016f
                    tickPaint.color = colMuted
                    tickPaint.alpha = (alpha * (if (major) 1f else 0.55f)).toInt().coerceIn(0, 255)
                    canvas.drawLine(
                        cx + (cos(a) * inner).toFloat(), cy + (sin(a) * inner).toFloat(),
                        cx + (cos(a) * outer).toFloat(), cy + (sin(a) * outer).toFloat(),
                        tickPaint
                    )
                }
                i++
            }
        }

        // 3. The needle runs the same self-test as the real dials — out, hold,
        //    fall, bounce — then parks. Reusing Cluster's curve is what ties the
        //    badge to the instruments it is introducing.
        val sweepP = phase(p, 0.30f, 0.74f)
        if (sweepP > 0f) {
            val swept = Cluster.introSweep(sweepP)
            val park = Cluster.easeOut(phase(p, 0.68f, 0.80f))
            val frac = swept * (1f - park) + NEEDLE_REST * park
            val a = Math.toRadians((DIAL_START + DIAL_SWEEP * frac).toDouble())

            glowPaint.shader = RadialGradient(
                cx, cy, radius * 0.95f,
                intArrayOf(colAccent and 0x00FFFFFF or 0x4D000000, Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            glowPaint.alpha = alpha
            canvas.drawCircle(cx, cy, radius * 0.95f, glowPaint)
            glowPaint.shader = null
            glowPaint.alpha = 255

            ringPaint.color = colAccent
            ringPaint.alpha = (alpha * 0.9f).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = size * 0.017f
            canvas.drawArc(rect, DIAL_START, DIAL_SWEEP * frac, false, ringPaint)

            // Tapered blade, like the cluster's.
            val tipLen = radius - size * 0.030f
            fillPaint.color = colAccent
            fillPaint.alpha = alpha
            canvas.save()
            canvas.rotate((DIAL_START + DIAL_SWEEP * frac), cx, cy)
            val path = android.graphics.Path()
            path.moveTo(cx + tipLen, cy - size * 0.0035f)
            path.lineTo(cx + size * 0.022f, cy - size * 0.0125f)
            path.lineTo(cx - size * 0.045f, cy - size * 0.008f)
            path.lineTo(cx - size * 0.045f, cy + size * 0.008f)
            path.lineTo(cx + size * 0.022f, cy + size * 0.0125f)
            path.lineTo(cx + tipLen, cy + size * 0.0035f)
            path.close()
            canvas.drawPath(path, fillPaint)
            canvas.restore()

            canvas.drawCircle(cx, cy, size * 0.026f, fillPaint)
            fillPaint.color = colGround
            fillPaint.alpha = alpha
            canvas.drawCircle(cx, cy, size * 0.016f, fillPaint)
            fillPaint.alpha = 255
        }
        ringPaint.alpha = 255
    }

    /**
     * Supplied artwork, filling the frame.
     *
     * Centre-cropped, so any aspect ratio works and nothing is squashed — the
     * scale is taken from whichever axis needs more, and the overspill falls off
     * the edges. A slow push-in over the whole run keeps it alive; a still image
     * held for five seconds reads as a stall, and the movement is what makes it
     * a title sequence rather than a loading screen.
     *
     * A scrim deepens towards the bottom so the picture hands over to the dark
     * cluster underneath instead of cutting to it.
     */
    private fun drawFullBleed(canvas: Canvas, art: Drawable, w: Float, h: Float, p: Float, alpha: Int) {
        val appear = Cluster.easeOut(phase(p, 0.00f, 0.22f))
        if (appear <= 0f) return

        val iw = art.intrinsicWidth.takeIf { it > 0 } ?: return
        val ih = art.intrinsicHeight.takeIf { it > 0 } ?: return


        // Fill or fit, decided by how much worse filling actually is.
        //
        // With an image supplied per orientation (drawable-port / drawable-land)
        // filling costs about 1.2x more magnification than fitting, and the
        // right answer is obviously to fill. Hand the same view a landscape
        // photo in portrait and filling costs 4x more than fitting, and the
        // right answer is obviously to letterbox. One comparison covers both.
        val cover = maxOf(w / iw, h / ih)
        val contain = minOf(w / iw, h / ih)

        // Artwork carrying the wordmark is never cropped. The lettering in these
        // images runs almost edge to edge, so filling the frame — which trims
        // whichever axis is proportionally longer — cut "SPRINGCOMMAND" off on
        // both sides. Fitting it whole is not a compromise here: the image was
        // composed to be seen entire, and the bands left over are the same dark
        // ground the cluster sits on.
        val fill = !Brand.ARTWORK_HAS_WORDMARK &&
                (cover <= FREE_UPSCALE ||
                        (cover <= contain * FILL_TOLERANCE && cover <= UPSCALE_CEILING))

        // Motion, without ever exceeding the fit. Growing INTO place rather than
        // pushing past the edges keeps the lettering intact from first frame to
        // last, which a centre-crop zoom cannot promise.
        val zoom = if (fill) 1.02f + 0.06f * Cluster.easeInOut(p)
                   else 0.965f + 0.035f * Cluster.easeOut(p)

        val scale = (if (fill) cover else contain) * zoom

        val dw = iw * scale
        val dh = ih * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f

        art.alpha = (alpha * appear).toInt().coerceIn(0, 255)
        art.setBounds(left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt())
        art.draw(canvas)

        // Scrim anchored to the picture's own lower edge, not the screen's, so a
        // letterboxed image fades into the ground it sits on instead of having a
        // gradient float in the black beneath it.
        val bottom = minOf(top + dh, h)
        val scrimTop = bottom - dh * 0.45f
        fillPaint.shader = LinearGradient(
            0f, scrimTop, 0f, bottom,
            intArrayOf(Color.TRANSPARENT, colGround),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (alpha * appear * 0.92f).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, scrimTop, w, bottom, fillPaint)
        fillPaint.shader = null
        fillPaint.alpha = 255
    }

    /** The bezel alone, for when supplied artwork stands in for the dial. */
    private fun drawBezelOnly(canvas: Canvas, cx: Float, cy: Float, radius: Float, size: Float, p: Float, alpha: Int) {
        val bezel = Cluster.easeInOut(phase(p, 0.10f, 0.42f))
        if (bezel <= 0f) return
        val outer = radius + size * 0.048f
        ringPaint.strokeWidth = size * 0.012f
        ringPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(colDial, colBezel, colDial, colBezel, colDial),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        )
        ringPaint.alpha = alpha
        canvas.drawArc(
            RectF(cx - outer, cy - outer, cx + outer, cy + outer),
            -90f, 360f * bezel, false, ringPaint
        )
        ringPaint.shader = null
        ringPaint.alpha = 255
    }

    private fun drawWordmark(canvas: Canvas, cx: Float, h: Float, size: Float, p: Float, alpha: Int) {
        val y = h * 0.715f

        val appear = Cluster.easeOut(phase(p, 0.44f, 0.76f))
        if (appear <= 0f) return

        // Letter-spacing contracts and the line rises a little as it arrives, so
        // the wordmark settles into place rather than fading in flat.
        val spacing = 0.46f - 0.32f * appear
        val rise = (1f - appear) * size * 0.030f
        val wordAlpha = (alpha * appear).toInt().coerceIn(0, 255)

        textPaint.typeface = displayFace
        textPaint.textSize = size * 0.088f
        textPaint.letterSpacing = spacing

        val spring = "SPRING"
        val command = "COMMAND"
        val wSpring = textPaint.measureText(spring)
        val wCommand = textPaint.measureText(command)
        val left = cx - (wSpring + wCommand) / 2f
        val baseline = y + rise

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = colAccent
        textPaint.alpha = wordAlpha
        canvas.drawText(spring, left, baseline, textPaint)
        textPaint.color = colInk
        textPaint.alpha = wordAlpha
        canvas.drawText(command, left + wSpring, baseline, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.letterSpacing = 0f
        textPaint.alpha = 255

        // A specular highlight sweeps across the letters once, the way light
        // travels over polished metal. One pass only — repeated, it becomes a
        // loading indicator.
        val shine = phase(p, 0.62f, 0.86f)
        if (shine > 0f && shine < 1f) {
            val band = size * 0.34f
            val travel = left - band + (wSpring + wCommand + band * 2f) * shine
            shimmerPaint.typeface = textPaint.typeface
            shimmerPaint.textSize = textPaint.textSize
            shimmerPaint.letterSpacing = spacing
            val grad = LinearGradient(
                0f, 0f, band, 0f,
                intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            shimmerMatrix.reset()
            shimmerMatrix.setTranslate(travel, 0f)
            grad.setLocalMatrix(shimmerMatrix)
            shimmerPaint.shader = grad
            shimmerPaint.alpha = (alpha * 0.55f).toInt().coerceIn(0, 255)
            canvas.drawText(spring, left, baseline, shimmerPaint)
            canvas.drawText(command, left + wSpring, baseline, shimmerPaint)
            shimmerPaint.shader = null
            shimmerPaint.letterSpacing = 0f
        }

        // Subtitle and maker's plate arrive last and quietest.
        val tail = Cluster.easeOut(phase(p, 0.66f, 0.92f))
        if (tail > 0f) {
            val tailAlpha = (alpha * tail * 0.85f).toInt().coerceIn(0, 255)
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textPaint.textSize = size * 0.030f
            textPaint.letterSpacing = 0.24f
            textPaint.color = colMuted
            textPaint.alpha = tailAlpha
            canvas.drawText("INDIAN SPRINGFIELD · CAN BUS", cx, y + size * 0.064f, textPaint)

            // A hairline rule, then the maker's plate. Set apart rather than
            // stacked, so it reads as a signature on the work and not a third
            // line of subtitle.
            val ruleW = size * 0.10f
            fillPaint.color = colMuted
            fillPaint.alpha = (tailAlpha * 0.4f).toInt().coerceIn(0, 255)
            canvas.drawRect(cx - ruleW, y + size * 0.092f, cx + ruleW, y + size * 0.0935f, fillPaint)
            fillPaint.alpha = 255

            textPaint.textSize = size * 0.033f
            textPaint.letterSpacing = 0.28f
            textPaint.color = colAccent
            textPaint.alpha = (tailAlpha * 0.9f).toInt().coerceIn(0, 255)
            canvas.drawText("© NANNA AGESEN 2026", cx, y + size * 0.132f, textPaint)
            textPaint.letterSpacing = 0f
            textPaint.alpha = 255
        }
    }

    /** Corners darken slightly, pulling the eye to the badge. */
    private fun drawVignette(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, p: Float) {
        val strength = Cluster.easeOut(phase(p, 0.08f, 0.40f))
        if (strength <= 0f) return
        val r = maxOf(w, h) * 0.75f
        glowPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.TRANSPARENT, Color.BLACK),
            floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP
        )
        glowPaint.alpha = (110 * strength).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, glowPaint)
        glowPaint.shader = null
        glowPaint.alpha = 255
    }
}

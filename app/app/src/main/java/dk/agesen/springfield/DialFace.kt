package dk.agesen.springfield

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient

/**
 * The chrome every dial on the cluster shares: backlight, bezel, glass, and the
 * gradient that makes a value arc look lit rather than filled.
 *
 * It lives in one place for the same reason the easing constants do. The big
 * speedometer had all of this and the tyre rings and the small machine gauges
 * had none of it, so the app looked like two apps — a finished instrument on one
 * page and flat coloured arcs on the next. Copying the code into each view would
 * have fixed the look and left three copies to drift apart at the first change.
 *
 * Shaders are cached by geometry rather than rebuilt per frame. Views of the
 * same size draw in their own coordinate space, so the four gauges on the
 * Machine page share a single set between them.
 */
object DialFace {

    val colFace: Int = Color.parseColor("#0E1218")
    val colDial: Int = Color.parseColor("#1B2028")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // Bounded, and roomy enough that it never thrashes. Keys carry the colour
    // as well as the geometry, so a page of dials that change colour — three
    // tyre states, caution bands, the redline — needs more entries than there
    // are dials. A limit that clears mid-page would rebuild shaders every frame,
    // which is the cost this cache exists to remove.
    private const val CACHE_LIMIT = 48
    private val shaders = HashMap<String, Shader>()

    private fun cached(key: String, build: () -> Shader): Shader {
        shaders[key]?.let { return it }
        if (shaders.size >= CACHE_LIMIT) shaders.clear()
        return build().also { shaders[key] = it }
    }

    /**
     * Everything that makes a circle look like an instrument, in order:
     * backlight behind the face, machined bezel around it, glass across it.
     *
     * Call before the track and the value, never after — this is the dial the
     * reading sits on, not an overlay.
     */
    fun chrome(canvas: Canvas, cx: Float, cy: Float, radius: Float, size: Float) {
        backlight(canvas, cx, cy, radius)
        bezel(canvas, cx, cy, radius, size)
        glass(canvas, cx, cy, radius)
    }

    /** Faintly lit face, brightest at the hub — backlighting, not a black hole. */
    private fun backlight(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = cached("bl$cx$cy$radius") {
            RadialGradient(
                cx, cy, radius,
                intArrayOf(colFace, colDial and 0x00FFFFFF or 0x40000000, Color.TRANSPARENT),
                floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
    }

    /**
     * A ring lit from the top left, plus an inner hairline.
     *
     * Cheap, and most of the difference between "circle with an arc on it" and
     * something that looks machined.
     */
    private fun bezel(canvas: Canvas, cx: Float, cy: Float, radius: Float, size: Float) {
        val outer = radius + size * 0.038f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.016f
        paint.shader = cached("bz$cx$cy") {
            SweepGradient(
                cx, cy,
                intArrayOf(
                    Color.parseColor("#2A313B"), Color.parseColor("#495260"),
                    Color.parseColor("#1A1F27"), Color.parseColor("#39424E"),
                    Color.parseColor("#2A313B")
                ),
                floatArrayOf(0f, 0.25f, 0.55f, 0.8f, 1f)
            )
        }
        canvas.drawCircle(cx, cy, outer, paint)
        paint.shader = null

        paint.strokeWidth = size * 0.003f
        paint.color = Color.parseColor("#5A6472")
        paint.alpha = 90
        canvas.drawCircle(cx, cy, outer - size * 0.010f, paint)
        paint.alpha = 255
    }

    /** A wide, very faint highlight across the top of the face. */
    private fun glass(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.WHITE
        paint.alpha = 10
        rect.set(cx - radius * 0.94f, cy - radius * 0.94f, cx + radius * 0.94f, cy + radius * 0.42f)
        canvas.drawArc(rect, 180f, 180f, false, paint)
        paint.alpha = 255
    }

    /**
     * The gradient for the travelled part of a dial: dim where the scale begins,
     * full colour where the value is, so the arc reads as a trail behind the
     * reading rather than a bar someone filled in.
     *
     * Rotated to the dial's own start angle and stopped at the end of its sweep.
     * Without both, the bright end lands wherever 360° happens to fall and an
     * arc that spans 240° never reaches full colour at all — which is what the
     * speedometer was quietly doing before this moved here.
     */
    fun litShader(cx: Float, cy: Float, startAngle: Float, sweepAngle: Float, colour: Int): Shader =
        cached("lit$cx$cy$startAngle$sweepAngle$colour") {
            SweepGradient(
                cx, cy,
                // 0x8C, not the 0x4D the speedometer used. That value was
                // chosen while the gradient was misaligned and the dim end fell
                // somewhere off the scale; now that it correctly starts where
                // the scale does, a low reading is drawn entirely inside it — a
                // quarter tank of fuel would have been a barely visible smear.
                // .toInt() because a literal above 0x7FFFFFFF is a Long in Kotlin, and
                // intArrayOf will not take one.
                intArrayOf(colour and 0x00FFFFFF or 0x8C000000.toInt(), colour, colour),
                floatArrayOf(0f, (sweepAngle / 360f).coerceIn(0.05f, 1f), 1f)
            ).apply {
                setLocalMatrix(Matrix().apply { setRotate(startAngle, cx, cy) })
            }
        }

    /**
     * Light thrown onto the face by something lit — a needle hub, a warning, the
     * end of a value arc. Alpha is passed in so a caller can pulse it without
     * rebuilding the gradient.
     */
    fun halo(canvas: Canvas, cx: Float, cy: Float, radius: Float, colour: Int, alpha: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = cached("hl$cx$cy$radius$colour") {
            RadialGradient(
                cx, cy, radius,
                intArrayOf(colour, colour and 0x00FFFFFF, Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        }
        paint.alpha = alpha
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        paint.alpha = 255
    }
}

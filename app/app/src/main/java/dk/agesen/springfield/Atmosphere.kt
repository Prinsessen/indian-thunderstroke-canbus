package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * The slow sheen behind the instruments.
 *
 * A panel that is perfectly still reads as a screenshot. Two very soft pools of
 * light drift behind the dials on a long, non-repeating cycle — slow enough that
 * you never catch them moving, present enough that the screen is never dead.
 *
 * Redrawn at roughly 12 fps rather than every frame: nothing here changes fast
 * enough to justify more, and this runs for the length of a ride.
 */
class AmbientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colAccent = Color.parseColor("#E8A33D")
    private val colCool = Color.parseColor("#3D5A8A")

    private val startedAt = System.currentTimeMillis()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = (System.currentTimeMillis() - startedAt) / 1000.0

        // Two drifts on incommensurable periods, so the pattern never visibly
        // repeats over a ride.
        drawPool(canvas, w, h, t / 23.0, colAccent, 0.30f, 26)
        drawPool(canvas, w, h, t / 37.0 + 0.5, colCool, 0.34f, 20)

        postInvalidateDelayed(80)
    }

    private fun drawPool(canvas: Canvas, w: Float, h: Float, phase: Double, colour: Int, radiusFrac: Float, alpha: Int) {
        val cx = w * (0.5f + 0.34f * cos(phase * Math.PI * 2).toFloat())
        val cy = h * (0.45f + 0.28f * sin(phase * Math.PI * 2 * 0.7).toFloat())
        val r = minOf(w, h) * radiusFrac

        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(colour and 0x00FFFFFF or (alpha shl 24), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }
}

/**
 * Redline warning as light at the edges of the screen.
 *
 * A red number in the middle of a dial is easy to miss with your eyes on the
 * road; light in the periphery is not, which is the whole reason shift lights
 * exist. It builds from the warning threshold rather than switching on at the
 * redline, so it reads as approaching a limit rather than having crossed one.
 *
 * Draws nothing at all below the threshold — no cost when it is not needed.
 */
class EdgeGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), BikeRepository.Observer {

    companion object {
        private const val BAND = 0.13f          // glow depth, as a fraction of the short side
    }

    // One redline, set once and read by the dial, this glow and the haptic —
    // three consumers that used to carry their own copy of the number.
    private val warnRpm: Float get() = Settings.warnRpm.toFloat()
    private val redline: Float get() = Settings.redlineRpm.toFloat()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colWarn = Color.parseColor("#E8A33D")
    private val colRed = Color.parseColor("#FF3B25")

    // The banner's own red, so an alert's glow and its bar read as one event
    // rather than two things that happen to be red at the same moment.
    private val colAlert = Color.parseColor("#D2452F")

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BikeRepository.addObserver(this)
    }

    override fun onDetachedFromWindow() {
        BikeRepository.removeObserver(this)
        super.onDetachedFromWindow()
    }

    override fun onBikeUpdate() = postInvalidateOnAnimation()

    override fun onDraw(canvas: Canvas) {
        // Through the staleness check, like every other instrument. Without it a
        // dropped link left the redline warning burning at the edges of the
        // screen off the last packet before the drop — a warning about an engine
        // speed the app no longer knows.
        if (!BikeRepository.isLive) return

        val rpm = BikeRepository.fast?.rpm?.toFloat()
        val revs = rpm != null && rpm >= warnRpm
        val alert = AlertBannerView.criticalActive

        // Nothing to say, nothing drawn. A glow with no cause is worse than no
        // glow, because it costs the next real one its meaning.
        if (!revs && !alert) return

        val w = width.toFloat()
        val h = height.toFloat()
        val past = revs && rpm!! >= redline

        val colour: Int
        val intensity: Float
        val ceiling: Int
        if (revs) {
            // Revs win when both are true. A red alert is about the machine and
            // can wait a second; the redline is about what the throttle hand is
            // doing right now, and it has to reach the rider first.
            val over = ((rpm!! - warnRpm) / (redline - warnRpm)).coerceIn(0f, 1f)
            // Steady swell up to the redline; above it, a slow breath so the eye
            // registers a change of state rather than just more of the same.
            intensity = if (past) 0.85f + 0.15f * Cluster.pulse(700L) else over * 0.8f
            colour = if (past) colRed else colWarn
            ceiling = if (past) 150 else 96
        } else {
            // On the banner's clock, 1400 ms, so the bar and the periphery
            // breathe together. Dimmer than the redline on purpose: this is for
            // the corner of the eye, while the road keeps the middle of it.
            intensity = 0.55f + 0.45f * Cluster.pulse(1400L)
            colour = colAlert
            ceiling = 92
        }
        val peak = (intensity * ceiling).toInt().coerceIn(0, 255)
        val depth = minOf(w, h) * BAND

        edge(canvas, 0f, 0f, 0f, depth, w, depth, colour, peak, vertical = true)
        edge(canvas, 0f, h - depth, 0f, h, w, depth, colour, peak, vertical = true, flip = true)
        edge(canvas, 0f, 0f, depth, 0f, depth, h, colour, peak, vertical = false)
        edge(canvas, w - depth, 0f, w, 0f, depth, h, colour, peak, vertical = false, flip = true)

        // Both breathing states need a frame loop of their own; the steady
        // swell below the redline is redrawn by the next packet anyway.
        if (past || (!revs && alert)) postInvalidateOnAnimation()
    }

    private fun edge(
        canvas: Canvas, x: Float, y: Float, gx: Float, gy: Float,
        spanW: Float, spanH: Float, colour: Int, peak: Int,
        vertical: Boolean, flip: Boolean = false
    ) {
        val from = colour and 0x00FFFFFF or (peak shl 24)
        val colours = if (flip) intArrayOf(Color.TRANSPARENT, from) else intArrayOf(from, Color.TRANSPARENT)
        paint.shader = if (vertical) {
            LinearGradient(0f, y, 0f, if (flip) gy else gy, colours, null, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(x, 0f, if (flip) gx else gx, 0f, colours, null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(x, y, x + spanW, y + spanH, paint)
        paint.shader = null
    }
}

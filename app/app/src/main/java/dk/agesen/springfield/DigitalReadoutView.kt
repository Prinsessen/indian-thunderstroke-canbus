package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * The secondary number under the main dial in portrait.
 *
 * Portrait shows the speedometer as the primary face, so this carries the
 * tachometer's figure — a rider wants both, and a second dial in a tall window
 * would be too small to read at a glance.
 *
 * Drawn rather than laid out as TextViews so it shares the dial's type
 * treatment exactly: same monospace figures, same muted caption weight, same
 * "unknown is dashes, never zero" rule.
 */
class DigitalReadoutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), Cluster.Instrument {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val colInk = Color.parseColor("#F2F5F9")
    private val colAccent = Color.parseColor("#E8A33D")
    private val colRedline = Color.parseColor("#D2452F")
    private val colMuted = Color.parseColor("#7C8797")
    private val colDim = Color.parseColor("#3A414D")
    private val colTrack = Color.parseColor("#1B2028")

    private companion object {
        const val INTRO_TOP = 5600f
    }

    private val maxRpm: Float get() = Settings.maxRpm.toFloat()
    private val redline: Float get() = Settings.redlineRpm.toFloat()

    var rpm: Int? = null
        set(v) { field = v; postInvalidateOnAnimation() }

    /**
     * Ignition, as bus activity: true alive, false dead, null never heard from.
     *
     * It sits beside the rev counter rather than down in the tell-tale row
     * because it is not a warning, it is the precondition for everything else
     * on this page. A rider glancing at a rev counter reading zero wants to
     * know whether the bike is asleep or the link is broken, and the lamp next
     * to the figure answers that without moving the eye.
     */
    var ignition: Boolean? = null
        set(v) { field = v; invalidate() }

    /**
     * Throttle opening, 0-100, drawn ON the rev bar rather than beside it.
     *
     * Two numbers that mean nothing apart and a great deal together: the bar's
     * fill is what the engine is doing, the marker is what the rider is asking
     * of it. The gap between them is engine response, and it is only drawable
     * because both were finally decoded on the same day -- the throttle having
     * spent a month hidden in a message we read one field of.
     */
    var throttlePct: Int? = null
        set(v) { field = v; invalidate() }

    /**
     * Called when the rider long-presses the ride figure.
     *
     * Only that corner of the view, not the whole thing: a long press anywhere
     * on a rev counter should do nothing, and a gesture that wipes a number has
     * to be aimed at the number it wipes.
     */
    var onRideReset: (() -> Unit)? = null

    private val marker = Path()
    private var markerGlow: RadialGradient? = null
    private var touchX = 0f

    init { isLongClickable = true }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        markerGlow = null          // its radius is in pixels, so it must be rebuilt
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touchX = event.x
        return super.onTouchEvent(event)
    }

    override fun performLongClick(): Boolean {
        // The right-hand column, where the ride figure is drawn. Nothing to
        // reset if there is no reading, so the press falls through rather than
        // buzzing to confirm something that did not happen.
        if (tripKm != null && touchX > width * 0.78f) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onRideReset?.invoke()
            return true
        }
        return super.performLongClick()
    }

    /** Trip distance for this ride, shown small underneath. */
    var tripKm: Double? = null

    /** The ABS lamp -- the DM1 Warn bit. See AbsLamp. */
    var abs: Boolean? = null
        set(v) { field = v; invalidate() }

    private var displayed = 0f
    private var lastFrameNs = 0L
    private var introStart = 0L

    override fun playIntro() {
        introStart = System.currentTimeMillis()
        displayed = 0f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val cx = w / 2f

        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1_000_000_000f)
        lastFrameNs = now

        val intro = Cluster.introProgress(introStart, Cluster.STAGGER_SPEEDO)
        val v = rpm
        val shown: String
        if (intro != null) {
            displayed = Cluster.introSweep(intro) * INTRO_TOP
            shown = displayed.toInt().toString()
            postInvalidateOnAnimation()
        } else if (v == null) {
            displayed = 0f
            shown = "----"
        } else {
            displayed = Cluster.ease(displayed, v.toFloat(), dt)
            if (kotlin.math.abs(v.toFloat() - displayed) > 0.5f) postInvalidateOnAnimation()
            shown = displayed.toInt().toString()
        }

        val hot = displayed >= redline

        // A slim bar under the figure carries the same information as the dial's
        // arc, so the eye can catch a rising engine without reading the number.
        val barY = h * 0.80f
        val barH = h * 0.055f
        val inset = w * 0.14f
        barPaint.shader = null
        barPaint.style = Paint.Style.FILL
        barPaint.color = colTrack
        canvas.drawRoundRect(inset, barY, w - inset, barY + barH, barH / 2f, barH / 2f, barPaint)

        // The redline, marked on the track itself.
        //
        // The dial has always shown where the limit is; the bar only ever showed
        // where the engine was, so the same reading meant nothing without
        // knowing the scale. A dim red tail costs nothing and turns the bar into
        // an instrument rather than a progress indicator.
        val rlFrac = (redline / maxRpm).coerceIn(0f, 1f)
        if (rlFrac < 1f) {
            barPaint.color = colRedline
            barPaint.alpha = 60
            canvas.drawRoundRect(inset + (w - 2 * inset) * rlFrac, barY, w - inset,
                                 barY + barH, barH / 2f, barH / 2f, barPaint)
            barPaint.alpha = 255
        }

        val frac = (displayed / maxRpm).coerceIn(0f, 1f)
        if (frac > 0f) {
            barPaint.shader = LinearGradient(
                inset, 0f, w - inset, 0f,
                intArrayOf(colAccent, if (hot) colRedline else colAccent),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(
                inset, barY, inset + (w - 2 * inset) * frac, barY + barH,
                barH / 2f, barH / 2f, barPaint
            )
            barPaint.shader = null

            // A bright head on the leading edge, so the eye catches the engine
            // moving without reading either number. It is the one part of the
            // bar that is actually changing.
            val hx = inset + (w - 2 * inset) * frac
            barPaint.color = if (hot) colRedline else colInk
            canvas.drawRoundRect(hx - barH * 0.30f, barY, hx, barY + barH,
                                 barH / 2f, barH / 2f, barPaint)
        }

        // The hand, on the same track as the engine.
        //
        // A line rather than a second fill: two fills would read as two
        // quantities to compare in the abstract, while a marker on a bar reads
        // as a target the fill is chasing -- which is exactly what it is. Open
        // the throttle and the marker jumps ahead of the fill; shut it and the
        // fill runs on past while the engine winds down.
        throttlePct?.let { t ->
            val tf = (t / 100f).coerceIn(0f, 1f)
            val x = inset + (w - 2 * inset) * tf

            // A halo first, so the marker sits IN the bar rather than on top of
            // it. Without it the white pin read as a scratch on the glass.
            markerGlow = markerGlow ?: RadialGradient(
                0f, 0f, barH * 1.6f,
                intArrayOf(colInk, colInk, Color.TRANSPARENT),
                floatArrayOf(0f, 0.18f, 1f), Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.translate(x, barY + barH / 2f)
            barPaint.shader = markerGlow
            barPaint.alpha = 70
            canvas.drawCircle(0f, 0f, barH * 1.6f, barPaint)
            barPaint.alpha = 255
            barPaint.shader = null
            canvas.restore()

            // A pointer above the bar and a stem through it. The head is what
            // gives it a direction -- a bare line is a mark, a pointer is a
            // reading, and this one is the rider's own hand.
            barPaint.color = colInk
            barPaint.style = Paint.Style.FILL
            val head = barH * 0.62f
            marker.reset()
            marker.moveTo(x, barY - barH * 0.06f)
            marker.lineTo(x - head * 0.55f, barY - head)
            marker.lineTo(x + head * 0.55f, barY - head)
            marker.close()
            canvas.drawPath(marker, barPaint)

            val mw = h * 0.007f
            canvas.drawRoundRect(x - mw, barY, x + mw, barY + barH * 1.30f, mw, mw, barPaint)
        }

        textPaint.color = when {
            hot -> colRedline
            v == null && intro == null -> colDim
            else -> colInk
        }
        textPaint.textSize = h * 0.50f
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(shown, cx, h * 0.50f, textPaint)

        textPaint.color = colMuted
        textPaint.textSize = h * 0.135f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textPaint.letterSpacing = 0.18f
        canvas.drawText("RPM", cx, h * 0.66f, textPaint)

        // r was h*0.155 until 2026-09-06 and the arithmetic below it was wrong
        // in a way that put the two lamps on top of each other.
        //
        // IgnitionLamp draws a soft halo at 1.55r whenever the ignition is
        // known, which is nearly always. At the old radius the halo reached
        // h*0.180 -- not the h*0.265 the ring alone suggests -- and the ABS
        // brackets come down to h*0.207, so they overlapped by h*0.027. The
        // note that claimed seven clear dp had measured the ring and forgotten
        // the glow around it.
        //
        // At h*0.115 the halo tops out at h*0.242 and there are h*0.035 clear,
        // about 4dp on a 122dp row. There is no height to spare in this column,
        // so the fix is to shrink the ring rather than move anything: the owner
        // reported it both ways at once -- the lamps touching, and the ignition
        // ring looking enormous beside ABS. One number answers both. The rings
        // are now 0.115 against 0.095, near enough to read as a pair, with the
        // ignition still the larger because it is the one read from ten paces.
        IgnitionLamp.draw(canvas, w * 0.085f, h * 0.42f, h * 0.115f,
                          ignition, barPaint, textPaint)

        // ABS directly above it, sharing the column, running from h*0.013 to
        // h*0.207. Unchanged: it is the ignition ring that had to give way.
        AbsLamp.draw(canvas, w * 0.085f, h * 0.110f, h * 0.095f,
                     abs, barPaint, textPaint)

        // This ride, on the right, balancing the ignition lamp on the left.
        //
        // It used to sit small and centred under everything, which is where a
        // value goes when nobody has decided it matters. It does matter -- it is
        // the one number on this page the app owns rather than reads, and the
        // only one a rider watches climb on purpose.
        //
        // Stacked in three lines because the space either side of the figure is
        // narrow: a caption, the number, the unit. The decimal is dropped past
        // 100 so the figure never outgrows its column -- a distance that has to
        // be squeezed to fit stops being readable at exactly the point it gets
        // interesting.
        tripKm?.let {
            val v = Settings.distance(it)
            val rx = w * 0.915f

            textPaint.color = colMuted
            textPaint.textSize = h * 0.090f
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textPaint.letterSpacing = 0.16f
            canvas.drawText("RIDE", rx, h * 0.30f, textPaint)

            textPaint.color = colInk
            textPaint.textSize = h * 0.155f
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textPaint.letterSpacing = 0f
            canvas.drawText(
                if (v < 100) "%.1f".format(v) else "%.0f".format(v),
                rx, h * 0.48f, textPaint
            )

            textPaint.color = colMuted
            textPaint.textSize = h * 0.090f
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textPaint.letterSpacing = 0.16f
            canvas.drawText(Settings.distanceLabel, rx, h * 0.62f, textPaint)
            textPaint.letterSpacing = 0f
        }
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

package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Felt temperature, drawn against both zones' curves.
 *
 * A number on its own says how cold it is. This says how cold it is **and where
 * that sits relative to the points at which each garment steps up**, which is
 * the question a rider actually has when deciding whether the app has got it
 * right.
 *
 * Two tracks rather than one, because the zones have different curves by design.
 * Seeing them stacked is what makes it obvious that the legs lead, and what
 * makes a badly-set curve visible instead of merely wrong.
 *
 * The band each zone is *currently* in breathes, on the same clock as the
 * buttons below. That is the whole trick of the page: the same pulse means the
 * same thing everywhere on it — this is live, and this is what is happening now.
 */
class FeltTempView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        /** The axis. Wide enough for a Danish winter, tight enough to read. */
        private const val MIN_C = -10.0
        private const val MAX_C = 25.0

        /** Matched to HeatZoneView, deliberately: one heartbeat for the page. */
        private const val PULSE_MS = 1700L

        /** Enough steps that the rounded ends stay clean at any width. */
        private const val STEPS = 160
    }

    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    private val colOff = Color.parseColor("#1B212A")
    private val colLow = Color.parseColor("#4FA96B")
    private val colMedium = Color.parseColor("#E8A33D")
    private val colHigh = Color.parseColor("#D2452F")
    private val colInk = Color.parseColor("#F2F5F9")
    private val colMuted = Color.parseColor("#7C8797")
    private val colDim = Color.parseColor("#3A414D")

    var feltC: Double? = null
        set(v) { field = v; invalidate() }

    var ambientC: Double? = null
        set(v) { field = v; invalidate() }

    var speedKmh: Double? = null
        set(v) { field = v; invalidate() }

    /**
     * dp here too, for the same reason as the zone panels: a header sized
     * against its own box grows with the box, and this one sits above two
     * weighted views on screens of every height.
     */
    private val density = resources.displayMetrics.density
    private var scale = 1f
    private fun d(v: Float) = v * density * scale

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        scale = min(1f, h / (112f * density)).coerceAtLeast(0.78f)
        markerShader = null
    }

    private var markerShader: RadialGradient? = null

    private fun x(c: Double, w: Float) =
        (((c - MIN_C) / (MAX_C - MIN_C)).coerceIn(0.0, 1.0) * w).toFloat()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val felt = feltC

        val trackH = d(15f)
        val legsTop = d(50f)
        val jacketTop = legsTop + trackH + d(7f)

        // --- the figure -----------------------------------------------------
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = d(10f)
        text.letterSpacing = 0.16f
        text.color = colMuted
        canvas.drawText("FEELS LIKE", 0f, d(12f), text)
        text.letterSpacing = 0f

        text.textAlign = Paint.Align.RIGHT
        text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        text.textSize = d(24f)
        text.color = if (felt == null) colDim else colInk
        canvas.drawText(
            felt?.let { "%.0f%s".format(Settings.temperature(it), Settings.temperatureLabel) } ?: "--",
            w, d(24f), text
        )

        // The inputs, because the felt figure is an inference and a rider should
        // be able to see what it was inferred from — the same rule the tyre page
        // follows for its cold-corrected pressures.
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        text.textSize = d(10f)
        text.color = colMuted
        val amb = ambientC
        val spd = speedKmh
        canvas.drawText(
            when {
                amb == null -> "no temperature from the bike"
                spd == null || spd < 4.8 ->
                    "%.0f%s · standing still".format(
                        Settings.temperature(amb), Settings.temperatureLabel)
                else -> "%.0f%s at %.0f %s".format(
                    Settings.temperature(amb), Settings.temperatureLabel,
                    Settings.speed(spd), Settings.speedLabel)
            },
            0f, d(36f), text
        )

        // --- the two curves --------------------------------------------------
        val p = Cluster.pulse(PULSE_MS)
        track(canvas, w, legsTop, trackH, HeatCurve.Zone.LEGS, "LEGS", felt, p)
        track(canvas, w, jacketTop, trackH, HeatCurve.Zone.JACKET, "JACKET", felt, p)

        val axisY = jacketTop + trackH + d(13f)

        // --- axis --------------------------------------------------------------
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = d(9f)
        text.color = colDim
        var tick = MIN_C
        while (tick <= MAX_C) {
            val tx = x(tick, w)
            text.textAlign = when {
                tick == MIN_C -> Paint.Align.LEFT
                tick == MAX_C -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText("%.0f".format(Settings.temperature(tick)), tx, axisY, text)
            tick += 5.0
        }

        // --- where we are -------------------------------------------------------
        felt?.let {
            val mx = x(it, w)
            val top = legsTop - d(7f)
            val bottom = jacketTop + trackH + d(4f)

            // A halo on the head of the marker, on the same clock as everything
            // else, so the eye is drawn to the one value the page is about.
            val r = d(9f)
            markerShader = markerShader ?: RadialGradient(
                0f, 0f, r,
                intArrayOf(colInk, colInk, Color.TRANSPARENT),
                floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.translate(mx, top)
            glow.shader = markerShader
            glow.alpha = (40 + 80 * p).toInt()
            canvas.drawCircle(0f, 0f, r, glow)
            canvas.restore()

            mark.color = colInk
            mark.style = Paint.Style.FILL
            mark.strokeWidth = d(2f)
            mark.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(mx, top, mx, bottom, mark)
            canvas.drawCircle(mx, top, d(3.5f), mark)
        }

        // The pulse never stops while a felt temperature is known, and stops
        // dead when it is not — a still page is the honest picture of a dead link.
        if (felt != null) keepAnimating()
    }

    /**
     * Keep the pulse running only while someone can see it.
     *
     * `postInvalidateOnAnimation` is a self-sustaining 60 Hz loop, and on a bike
     * the screen spends most of a ride off with the service still running. Tying
     * it to window focus stops the loop dead when the phone sleeps or the rider
     * switches away, and restarts it when they come back — a redraw nobody is
     * looking at is pure battery.
     */
    private fun keepAnimating() {
        if (hasWindowFocus() && isAttachedToWindow) postInvalidateOnAnimation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) invalidate()
    }

    /**
     * One zone's curve: a rounded track coloured by the level that zone would be
     * at, with the segment it is in now breathing.
     *
     * Drawn by walking the axis rather than by computing three boundaries,
     * because the curve is the authority on where they are — duplicating that
     * arithmetic here is how a chart ends up disagreeing with the thing it is
     * meant to be showing.
     */
    private fun track(
        canvas: Canvas, w: Float, top: Float, h: Float,
        zone: HeatCurve.Zone, label: String, felt: Double?, pulse: Float
    ) {
        val radius = h / 2f
        rect.set(0f, top, w, top + h)

        // Clipped to a rounded track so the hard-edged steps inside keep their
        // honesty while the shape stops looking like a bar chart.
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)

        // Neutral "current" so the track shows the curve itself, not the
        // hysteresis-shifted view from wherever the zone happens to be.
        val here = felt?.let { HeatCurve.levelFor(zone, it, HeatCurve.Level.OFF) }
        val step = w / STEPS

        for (i in 0 until STEPS) {
            val c = MIN_C + (MAX_C - MIN_C) * (i + 0.5) / STEPS
            val level = HeatCurve.levelFor(zone, c, HeatCurve.Level.OFF) ?: HeatCurve.Level.OFF
            band.color = colourOf(level)
            // The band the rider is standing in lifts and falls; the rest sit
            // still. One glance says which step is in force.
            // The bands were drawn at full colour before the pulse existed, and
            // dropping the others to 150 to make the live one stand out dimmed
            // the whole chart instead. The contrast has to come from the top of
            // the range, not from taking the rest of it away.
            band.alpha = if (level == here && level != HeatCurve.Level.OFF) {
                (225 + 30 * pulse).toInt()
            } else if (level == HeatCurve.Level.OFF) 255 else 205
            canvas.drawRect(i * step, top, i * step + step + 1f, top + h, band)
        }

        canvas.restore()
        band.alpha = 255

        // A hairline rim, so an all-OFF track still reads as a track.
        band.style = Paint.Style.STROKE
        band.strokeWidth = d(1f)
        band.color = colDim
        canvas.drawRoundRect(rect, radius, radius, band)
        band.style = Paint.Style.FILL

        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        text.textSize = d(9f)
        text.letterSpacing = 0.1f
        text.color = Color.BLACK
        text.alpha = 140
        canvas.drawText(label, d(8f), top + h * 0.5f + d(3.2f), text)
        text.alpha = 255
        text.letterSpacing = 0f
    }

    private fun colourOf(level: HeatCurve.Level): Int = when (level) {
        HeatCurve.Level.OFF -> colOff
        HeatCurve.Level.LOW -> colLow
        HeatCurve.Level.MEDIUM -> colMedium
        HeatCurve.Level.HIGH -> colHigh
    }
}

package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * This ride, and the ignition lamp, as a strip of their own.
 *
 * Both of these used to be drawn on the dial faces in landscape -- the ride
 * figure under the speedometer's caption, the lamp under the tachometer's --
 * because at the time there was nowhere else for them to go. That was the wrong
 * answer and the owner saw it on the road: both gauges size on
 * min(width, height), every caption they draw is a fraction of that size, and
 * in landscape the height is small. The ride figure sits at 0.29 of it below
 * the centre and the lamp at 0.22, inside a ring of radius 0.40, so as the
 * dials got shorter these two crowded up into the faces they were sitting on.
 * Nothing was overlapping by arithmetic; it simply read as clutter, which is
 * the same failure on a motorcycle.
 *
 * So they move off the dials entirely and into the side column, under the fuel
 * bar and the grips. Neither is a live measurement being chased by a needle:
 * the lamp answers "is the bike awake", the figure is the app's own arithmetic,
 * and both are things a rider reads while stopped rather than at speed. The
 * column is exactly where the app already puts that kind of reading, and the
 * dials get their faces back for the two numbers they exist for.
 *
 * Portrait is untouched. There the tachometer is a digital readout that already
 * carries both, and this strip is not inflated at all.
 */
class RideStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    private val colInk = Color.parseColor("#F2F5F9")
    private val colMuted = Color.parseColor("#7C8797")
    private val colDim = Color.parseColor("#3A414D")

    /** The cluster's instrument lighting, behind the figure. See Ink. */
    private val glowInk = Color.parseColor("#4AE8A33D")
    private val ink = Ink()

    /** The app's own distance for this ride. null = it has not met the bike. */
    var rideKm: Double? = null
        set(v) { field = v; invalidate() }

    /** true = bus alive, false = quiet, null = never heard from. */
    var ignition: Boolean? = null
        set(v) { field = v; invalidate() }

    /**
     * Long-pressing the figure starts the ride again.
     *
     * The gesture came across from the dial with the number, because a rider who
     * has learnt it should not have to learn where it went. Aimed at the left of
     * the strip only -- the right half is the ignition lamp, and a press that
     * wipes a distance must not be triggered by one aimed at a lamp.
     */
    var onRideReset: (() -> Unit)? = null

    private var touchX = 0f

    private val density = resources.displayMetrics.density
    private var scale = 1f
    private fun d(v: Float) = v * density * scale

    init { isLongClickable = true }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // Same rule as GripsView above it, against this strip's own reference
        // height, so the two scale together when the column is tight.
        scale = min(1f, h / (52f * density)).coerceAtLeast(0.72f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touchX = event.x
        return super.onTouchEvent(event)
    }

    override fun performLongClick(): Boolean {
        if (rideKm != null && touchX < width * 0.62f) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onRideReset?.invoke()
            return true
        }
        return super.performLongClick()
    }

    /**
     * Two columns on the same two rows: captions along the top, the things they
     * name underneath.
     *
     * It was not built that way and the owner caught it. The RIDE caption sat
     * OVER its figure while the lamp's own caption sat UNDER its ring, so the
     * strip's two halves ran in opposite directions and the right one hung 8.6
     * dp lower than the left. Inverted rather than mirrored, which is the kind
     * of thing that reads as untidy long before anyone can say why.
     *
     * So the lamp gives up its own label -- see IgnitionLamp.draw(drawLabel) --
     * and IGN joins RIDE on the caption row. Both captions are muted, like FUEL
     * and GRIPS above them: the top row of this column names things, and the
     * colour belongs to the lamp itself, which is sitting right underneath.
     *
     * The lamp's centre is set on the figure's optical centre rather than on its
     * baseline, so a ring and a row of digits look level rather than measuring
     * level. Its ring is a little taller than the cap height, which is what a
     * lamp beside a number should be.
     */
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()

        // The lamp's column: the caption is centred over the ring rather than
        // pushed to the edge, so the two share one centre line. Far enough in
        // that the halo, at 1.55 r, still clears the right edge.
        val lampCx = w - d(16f)
        val r = d(9f)

        // --- row one: the captions -------------------------------------------
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = d(9f)
        text.letterSpacing = 0.16f
        text.color = colMuted
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("RIDE", 0f, d(10f), text)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("IGN", lampCx, d(10f), text)
        text.letterSpacing = 0f

        // --- row two: the figure, and the lamp ------------------------------
        // Dashes rather than a zero when the app has never met the bike: zero is
        // a distance, and claiming one would be a lie about a number this app
        // owns rather than reads.
        val km = rideKm
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        text.textSize = d(17f)
        text.color = if (km == null) colDim else colInk
        val fig = km?.let {
            val v = Settings.distance(it)
            (if (v < 100) "%.1f".format(v) else "%.0f".format(v)) + " " + Settings.distanceLabel
        } ?: "--"
        ink.on(text, d(30f), d(17f), if (km == null) colDim else colInk,
               if (km == null) Color.TRANSPARENT else glowInk)
        canvas.drawText(fig, 0f, d(30f), text)
        ink.off(text)

        // The figure's cap runs from d(30) minus its cap height to d(30); the
        // ring centres on the middle of that.
        IgnitionLamp.draw(canvas, lampCx, d(24f), r, ignition, p, text, drawLabel = false)
    }
}

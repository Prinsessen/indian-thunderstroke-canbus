package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * One heated zone: four buttons, and a way to overrule the app.
 *
 * Round and separate rather than a segmented bar, because these are four
 * positions of a switch and not four parts of one quantity — a bar invites the
 * reading that HIGH is "more full" than LOW, when what the controller has is a
 * choice of four. Each keeps the hardware's own colour, since a rider learns
 * those from the controller in their pocket and the app has no business
 * teaching a second set.
 *
 * The selected button carries a pulsing halo. It is not decoration: it is the
 * one thing on the page that says current is flowing *now*, and it stops the
 * moment the level is OFF or the link goes away. A rider glancing down at
 * gloved hands should be able to tell heat from no heat without reading a word.
 *
 * Touching a button sets it and takes the zone off automatic; holding anywhere
 * hands it back. No separate mode switch to find at 90 km/h — touching the
 * thing you want to change is the gesture everybody already makes.
 */
class HeatZoneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), Cluster.Instrument {

    companion object {
        private val LEVELS = HeatCurve.Level.entries

        /** The layout this is drawn to; it shrinks below this, never grows. */
        private const val DESIGN_HEIGHT_DP = 118f

        /** Landscape is cramped. Past this the type stops being readable. */
        private const val MIN_SCALE = 0.74f

        /** Slow enough to read as breathing rather than blinking. */
        private const val PULSE_MS = 1700L

        /** Each button lights in turn on power-on, with the rest of the cluster. */
        private const val LAMP_SLOT_MS = 130L
        private const val LAMP_HOLD_MS = 420L
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    // The controller's own colours.
    private val colLow = Color.parseColor("#4FA96B")     // green
    private val colMedium = Color.parseColor("#E8A33D")  // amber
    private val colHigh = Color.parseColor("#D2452F")    // red
    private val colOff = Color.parseColor("#8A95A5")     // deliberately off, not unknown
    private val colWell = Color.parseColor("#151A21")    // the unlit button's face
    private val colMuted = Color.parseColor("#7C8797")
    private val colDim = Color.parseColor("#3A414D")

    var zone: HeatCurve.Zone = HeatCurve.Zone.LEGS
    var title: String = ""

    fun refresh() = invalidate()

    // ------------------------------------------------------------ geometry

    private val density = resources.displayMetrics.density

    /**
     * Type and spacing in dp, scaled down only when the screen is too short.
     *
     * Sizing against the view's own height made a level label reach nearly 50dp
     * on a tall phone, because these panels are laid out with a weight and so
     * inherit whatever is left over. Fixed dp with a floor keeps a control panel
     * looking like one in both orientations.
     */
    private var scale = 1f
    private fun d(v: Float) = v * density * scale

    private var buttonRadius = 0f
    private var buttonY = 0f
    private var contentTop = 0f

    /** One gradient per button, rebuilt only on resize — not per frame. */
    private val haloShaders = arrayOfNulls<RadialGradient>(LEVELS.size)
    private var shaderRadius = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        scale = min(1f, h / (DESIGN_HEIGHT_DP * density)).coerceAtLeast(MIN_SCALE)

        // Fit the circles to the row, but never larger than the design size —
        // four huge discs on a tablet would look like a toy, not an instrument.
        val slot = w.toFloat() / LEVELS.size
        buttonRadius = min(d(27f), slot / 2f - d(7f))

        val content = d(34f) + buttonRadius * 2f + d(26f)
        contentTop = ((h - content) / 2f).coerceAtLeast(0f)
        buttonY = contentTop + d(34f) + buttonRadius

        haloShaders.fill(null)
        shaderRadius = buttonRadius + d(13f)
    }

    private fun centreX(index: Int) = width.toFloat() * (index + 0.5f) / LEVELS.size

    // --------------------------------------------------------------- intro

    private var introStart = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Cluster.register(this)
    }

    override fun onDetachedFromWindow() {
        Cluster.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun playIntro() {
        introStart = System.currentTimeMillis()
        invalidate()
    }

    /** Which button the lamp test is lighting, or null when it is over. */
    private fun lampIndex(): Int? {
        if (introStart == 0L) return null
        val elapsed = System.currentTimeMillis() - introStart
        if (elapsed > LEVELS.size * LAMP_SLOT_MS + LAMP_HOLD_MS) { introStart = 0L; return null }
        return (elapsed / LAMP_SLOT_MS).toInt().coerceAtMost(LEVELS.size - 1)
    }

    // --------------------------------------------------------------- touch

    private var pressX = 0f
    private var longFired = false

    private val longPress = Runnable {
        longFired = true
        Keis.returnToAuto(zone)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Detected by hand rather than through setOnLongClickListener:
                // consuming ACTION_DOWN, which this view must do to own the
                // gesture, stops the View's own detector ever running. That is
                // why holding used to do nothing at all.
                pressX = event.x
                longFired = false
                postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - pressX) > d(14f)) removeCallbacks(longPress)
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!longFired) hit(event.x, event.y)?.let {
                    Keis.setManual(zone, LEVELS[it])
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); return true }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    /**
     * Nearest button, if the touch was anywhere in the row.
     *
     * Deliberately not a circle test. This is operated in gloves, on a moving
     * bike, and a target you have to hit precisely is one you end up looking at
     * instead of the road — so the whole band belongs to the nearest button.
     */
    private fun hit(x: Float, y: Float): Int? {
        val band = buttonRadius + d(14f)
        if (y < buttonY - band || y > buttonY + band) return null
        return (x / width * LEVELS.size).toInt().coerceIn(0, LEVELS.size - 1)
    }

    // ---------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val device = Keis.deviceFor(zone)
        val connected = device.connected
        val auto = Keis.isAutomatic(zone)
        val capped = Keis.capped(zone)
        val level = Keis.requestedLevel(zone) ?: device.level
        val asked = if (capped) Keis.wantedLevel(zone) else null
        val lamp = lampIndex()

        // --- title, level, mode -------------------------------------------
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = d(10f)
        text.letterSpacing = 0.16f
        text.color = colMuted
        canvas.drawText(title, 0f, contentTop + d(10f), text)

        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        text.color = when {
            capped -> colHigh
            !connected -> colDim
            auto -> colLow
            else -> colMedium
        }
        canvas.drawText(
            when {
                capped -> "CAPPED"
                !connected -> "WAITING"
                // "OFF" rather than "MANUAL" when the global switch is off: the
                // zone is not in manual mode, automatic simply is not running,
                // and calling that MANUAL sent the rider looking for a
                // long-press that could never help.
                !Settings.heatAuto -> "AUTO OFF"
                auto -> "AUTO"
                else -> "MANUAL"
            },
            0f, contentTop + d(24f), text
        )
        text.letterSpacing = 0f

        text.textAlign = Paint.Align.RIGHT
        text.textSize = d(17f)
        text.color = if (level != null && connected) colourOf(level) else colDim
        canvas.drawText(level?.label ?: "—", w, contentTop + d(20f), text)

        // --- the four buttons ----------------------------------------------
        var animating = false
        for ((i, l) in LEVELS.withIndex()) {
            val cx = centreX(i)
            val colour = colourOf(l)
            // Selected follows the level, not the link.
            //
            // This used to require `connected`, and that blacked the whole panel
            // out whenever the garments were not switched on — which, since they
            // are often left at home, was most of the time. A control that shows
            // nothing reads as broken, not as idle. The link governs the halo
            // below, which is the thing that genuinely depends on it.
            val selected = lamp?.let { it == i } ?: (l == level)

            // Heat is flowing: OFF gets no halo, because there is nothing to
            // announce, and a link that is down gets none either — a glow with
            // no current behind it would be the one dishonest thing on the page.
            val alive = selected && l != HeatCurve.Level.OFF && (lamp != null || connected)
            if (alive) animating = true

            if (alive) {
                val p = Cluster.pulse(PULSE_MS)
                halo.shader = haloShaders[i] ?: RadialGradient(
                    cx, buttonY, shaderRadius,
                    intArrayOf(colour, colour, Color.TRANSPARENT),
                    floatArrayOf(0f, buttonRadius / shaderRadius, 1f),
                    Shader.TileMode.CLAMP
                ).also { haloShaders[i] = it }
                // Only the alpha moves, so the gradient itself stays cached and
                // the pulse costs nothing per frame.
                halo.alpha = (46 + 70 * p).toInt()
                canvas.drawCircle(cx, buttonY, shaderRadius, halo)
            }

            // A waiting zone is drawn down, not out. Enough to say the link is
            // absent, never so far that the buttons stop looking like buttons.
            val dimmed = !connected && lamp == null

            // The face
            fill.shader = null
            fill.color = if (selected) colour else colWell
            fill.alpha = if (selected && dimmed) 150 else 255
            canvas.drawCircle(cx, buttonY, buttonRadius, fill)
            fill.alpha = 255

            // The rim carries the colour on every button, lit or not: four
            // coloured outlines say "four things you can press" at a glance,
            // where four dark discs said nothing at all.
            ring.color = colour
            ring.alpha = when {
                selected -> if (dimmed) 190 else 255
                dimmed -> 105
                else -> 150
            }
            ring.strokeWidth = if (selected) d(2f) else d(1.4f)
            canvas.drawCircle(cx, buttonY, buttonRadius - ring.strokeWidth / 2f, ring)

            // What the rider asked for but cannot have: a broken ring outside
            // the button, so a capped zone shows both figures at once instead of
            // silently displaying the lower one.
            if (asked == l && !selected) {
                ring.color = colHigh
                ring.alpha = 200
                ring.strokeWidth = d(1.6f)
                canvas.drawArc(
                    cx - buttonRadius - d(5f), buttonY - buttonRadius - d(5f),
                    cx + buttonRadius + d(5f), buttonY + buttonRadius + d(5f),
                    -60f, 300f, false, ring
                )
            }

            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            text.textSize = d(11f)
            text.color = if (selected) Color.BLACK else colour
            text.alpha = when {
                selected -> 255
                dimmed -> 145
                else -> 210
            }
            canvas.drawText(l.label, cx, buttonY + d(4f), text)
            text.alpha = 255
        }

        // --- one footer line, whichever matters most -------------------------
        // Ordered by what the rider can act on. A blocked automatic outranks the
        // "hold for auto" hint, because holding cannot fix it and repeating the
        // hint while it fails is what makes the control look broken.
        val blocked = Keis.blockedReason(zone)
        val foot = when {
            !connected -> "not on the bike — connects by itself when switched on"
            blocked != null -> blocked
            capped -> "asked for ${asked?.label ?: "more"} — the bike cannot feed it"
            !auto -> Keis.autoWouldChoose(zone)
                ?.let { "hold for auto (${it.label})" } ?: "hold to resume auto"
            else -> null
        }
        foot?.let {
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            text.textSize = d(10f)
            text.color = colDim
            canvas.drawText(it, w / 2f, buttonY + buttonRadius + d(17f), text)
        }

        // Only run a frame loop while something is actually moving. A parked
        // bike with the heat off draws once and stops.
        if (animating || lamp != null) keepAnimating()
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

    private fun colourOf(level: HeatCurve.Level): Int = when (level) {
        HeatCurve.Level.OFF -> colOff
        HeatCurve.Level.LOW -> colLow
        HeatCurve.Level.MEDIUM -> colMedium
        HeatCurve.Level.HIGH -> colHigh
    }
}

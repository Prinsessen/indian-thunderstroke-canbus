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
 * The tell-tale row: headlight, brake, cruise, hazard and stand — drawn as
 * instrument lamps rather than listed as words.
 *
 * Turn signals are deliberately NOT here. They moved onto the dial face at the
 * ten- and two-o'clock positions, which is where they sit on a real cluster and
 * where a rider's eye already goes for them.
 *
 * Colours follow motorcycle convention rather than the app's palette: high beam
 * is blue, because that is what it is on every bike ever built, and a rider
 * reads the colour before the shape.
 *
 * Three visual states, not two, because the protocol has three. A lamp is lit,
 * dark, or struck through when the bus has never reported that signal. A dark
 * lamp reads as "not active", which is a claim the app has no right to make
 * about something it has never heard from.
 */
class TellTaleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), Cluster.Instrument {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()

    private val colHigh = Color.parseColor("#4C8DFF")     // high beam: blue, by convention
    private val colLow = Color.parseColor("#CBD3DE")
    private val colBrake = Color.parseColor("#D2452F")
    private val colStand = Color.parseColor("#4FA96B")     // parked and settled
    private val colDown = Color.parseColor("#D2452F")      // on its side
    private val colOff = Color.parseColor("#1E242C")
    private val colStrike = Color.parseColor("#39414D")
    private val colLabel = Color.parseColor("#59616D")
    private val colCruiseOn = Color.parseColor("#E8A33D")   // enabled, not holding
    private val colCruiseSet = Color.parseColor("#4FA96B")  // holding a speed
    private val colHazard = Color.parseColor("#D2452F")

    private val slots = 5

    /** How long a rocker press stays readable after the button is released. */
    private val PRESS_HOLD_MS = 1500L

    var brakeRear: Boolean? = null

    /**
     * Cruise, in the machine's own two colours.
     *
     * The service manual describes the bike's dash as amber for enabled-but-not-
     * set and green for set, so this lamp does the same thing rather than
     * inventing a scheme. A rider who already knows what the cluster means does
     * not have to learn a second language to read the phone.
     *
     * The lamp was removed from this row on 2026-09-04, when the old decode
     * turned out to be reading the SET button and calling it the engaged state.
     * It comes back because there is now a real signal behind it: SPN 596 for
     * the rocker, measured in the garage 2026-09-05, and SPN 595 for the engaged
     * state, which stays unknown until the bike is actually moving -- so this
     * lamp will sit struck through on a parked bike, which is honest.
     */
    var cruise: Boolean? = null
    var cruiseEnable: Boolean? = null

    /** Hazard warning, PGN 65381 SA 39 byte 2 bit 0. Blinks, because it does. */
    var hazard: Boolean? = null

    /**
     * The last rocker press, held on screen for a moment after the button is
     * let go.
     *
     * A press is about a second and the eye is usually on the road, so showing
     * it only while the contact is closed would mean showing it to nobody. The
     * label under the lamp carries it -- no new slot, and it is already where a
     * rider looks for anything to do with cruise.
     */
    private var lastPress: String? = null
    private var lastPressAt = 0L

    /**
     * "UPRIGHT" / "STAND" / "DOWN", or null.
     *
     * Null covers both "never reported" and "moving" -- the firmware leaves the
     * field out above walking pace on purpose, because an accelerometer reads
     * upright in a balanced corner and would otherwise claim the bike was
     * standing up straight through every bend.
     */
    var stand: String? = null


    /** "High" / "Low" / "Off" from the state JSON; null = never reported. */
    var headlight: String? = null

    private var introStart = 0L

    override fun playIntro() {
        introStart = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    /**
     * Fed from the BLE fast packet where there is one, and from the JSON state
     * where there is not.
     *
     * The fallback matters more than it looks: the fast packet only exists over
     * Bluetooth, so on WiFi every lamp driven from it would sit struck through
     * as "never reported" while the same signal was arriving over MQTT in the
     * next field down. The strike is supposed to mean the bus has never said,
     * not that the app chose the wrong pipe.
     */
    fun setStates(
        p: FastPacket?, beam: String?, standState: String?,
        cruiseText: String? = null, cruiseEnableText: String? = null,
        hazardText: String? = null, brakeText: String? = null,
        cruiseSwText: String? = null
    ) {
        fun onOff(t: String?): Boolean? = when (t) {
            // "HOLDING"/"off" is the derived cruise, which deliberately uses a
            // different vocabulary from the measured switches so the value says
            // what kind of fact it is. Missing it here would have struck the
            // cruise lamp through as "never reported" on every ride.
            "ON", "PRESSED", "HOLDING" -> true
            "OFF", "RELEASED", "off" -> false
            else -> null
        }
        brakeRear = p?.brakeRear ?: onOff(brakeText)
        // Fast packet first, JSON as the fallback -- same reasoning as the rest,
        // except here the fallback is genuinely worse rather than merely slower:
        // at 1 Hz a tap can be missed entirely, which is why the bits exist.
        val press = when {
            p?.cruiseSet == true -> "SET/DEC"
            p?.cruiseRes == true -> "RES/ACC"
            cruiseSwText == "SET/DEC" || cruiseSwText == "RES/ACC" -> cruiseSwText
            else -> null
        }
        if (press != null) { lastPress = press; lastPressAt = System.currentTimeMillis() }
        cruise = p?.cruise ?: onOff(cruiseText)
        cruiseEnable = p?.cruiseEnable ?: onOff(cruiseEnableText)
        hazard = p?.hazard ?: onOff(hazardText)
        headlight = beam
        stand = standState
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cellW = width / slots.toFloat()
        val cy = height / 2f - height * 0.08f
        val unit = min(cellW, height.toFloat())

        val intro = Cluster.introProgress(introStart, Cluster.STAGGER_MINOR)
        if (intro != null) postInvalidateOnAnimation()

        fun state(index: Int, real: Boolean?): Boolean? =
            if (intro != null) Cluster.introLamp(intro, index, slots) else real

        // Headlight is three-way, so it resolves separately. During the lamp
        // test it is forced to High, so the blue actually shows.
        val beam: String? = if (intro != null) {
            if (Cluster.introLamp(intro, 0, slots)) "High" else "Off"
        } else headlight

        // Five lamps. It was three between 2026-09-04 and 09-05, after the front
        // brake, cruise and horn decodes were withdrawn -- a lamp that can never
        // light is worse than no lamp, because it takes up the row and quietly
        // teaches the rider to ignore that spot. Cruise and hazard are back
        // because they were measured on the bike, and the horn stays gone
        // because it was measured too, and it is not there.
        //
        // Five cells cost less room than they look. The row is 76dp tall and
        // the lamp size is min(cellWidth, height), so height was already the
        // binding constraint at three -- going to five takes the lamps from
        // 76 to about 67, and nothing else on the page moves.
        drawHeadlight(canvas, cellW * 0.5f, cy, unit, beam)
        drawBrake(canvas, cellW * 1.5f, cy, unit, state(1, brakeRear), "")
        drawCruise(canvas, cellW * 2.5f, cy, unit,
                   if (intro != null) Cluster.introLamp(intro, 2, slots) else cruise,
                   if (intro != null) true else cruiseEnable)
        drawHazard(canvas, cellW * 3.5f, cy, unit, state(3, hazard))
        drawStand(canvas, cellW * 4.5f, cy, unit,
                  if (intro != null) "STAND" else stand)

        label(canvas, cellW * 0.5f, unit, "BEAM")
        label(canvas, cellW * 1.5f, unit, "BRAKE")
        // A press outranks the steady state for a moment and then gives the
        // label back, so the lamp still reads as cruise the rest of the time.
        val pressAge = System.currentTimeMillis() - lastPressAt
        val showPress = lastPress != null && pressAge < PRESS_HOLD_MS
        if (showPress) postInvalidateOnAnimation()
        label(canvas, cellW * 2.5f, unit, when {
            showPress -> lastPress!!
            cruise == true -> "HOLDING"
            else -> "CRUISE"
        })
        label(canvas, cellW * 3.5f, unit, "HAZARD")
        label(canvas, cellW * 4.5f, unit, when (stand) {
            "DOWN" -> "DOWN"
            "STAND" -> "ON STAND"
            "UPRIGHT" -> "UPRIGHT"
            else -> "STAND"
        })
    }

    /**
     * The bike, seen from behind, leaning as much as it really is.
     *
     * Head-on rather than side-on because lean is invisible in profile: a bike
     * on its stand and a bike stood up look identical from the side. From
     * behind, the angle IS the picture, and no label is needed to read it.
     *
     * The angle eases toward its target instead of snapping, so putting the
     * bike on its stand tips the drawing over the same way the machine does.
     * That is not decoration -- a shape that moves the way the object moved is
     * read without being decoded.
     *
     * Green on the stand: that is the bike parked correctly, and it should feel
     * settled. Red and breathing on its side, because that is the one state a
     * rider needs to notice from across a car park.
     */
    private var standAngle = 0f
    private var standAngleAt = 0L

    private fun drawStand(canvas: Canvas, cx: Float, cy: Float, unit: Float, st: String?) {
        val target = when (st) {
            "STAND" -> -15f
            "DOWN" -> -72f
            "UPRIGHT" -> 0f
            else -> 0f
        }
        // Ease at a fixed rate per frame so the travel time is the same from
        // any starting angle; snap when close enough to stop it creeping.
        val now = System.currentTimeMillis()
        val dt = if (standAngleAt == 0L) 16L else (now - standAngleAt).coerceIn(0, 64)
        standAngleAt = now
        val step = dt * 0.35f
        standAngle = when {
            kotlin.math.abs(target - standAngle) <= step -> target
            target > standAngle -> standAngle + step
            else -> standAngle - step
        }
        if (standAngle != target) postInvalidateOnAnimation()

        val colour = when (st) {
            "DOWN" -> colDown
            "STAND" -> colStand
            "UPRIGHT" -> colLow
            else -> colOff
        }
        if (st == "DOWN") {
            // Breathing, on the app's own clock. The only lamp here that asks
            // to be looked at rather than merely read.
            glowPaint.color = colDown
            glowPaint.alpha = (70 + 90 * Cluster.pulse(900L)).toInt()
            canvas.drawCircle(cx, cy, unit * 0.34f, glowPaint)
            postInvalidateOnAnimation()
        } else if (st == "STAND") {
            glow(canvas, cx, cy, unit, colour)
        }

        val ground = cy + unit * 0.26f
        strokePaint.color = colour
        strokePaint.strokeWidth = unit * 0.036f

        // The ground, so a leaning bike leans against something.
        strokePaint.strokeWidth = unit * 0.028f
        canvas.drawLine(cx - unit * 0.30f, ground, cx + unit * 0.30f, ground, strokePaint)

        // The stand itself, planted where it really is -- on the ground, and to
        // the left, which is the side Indian put it on.
        if (st == "STAND") {
            canvas.drawLine(cx, ground - unit * 0.10f, cx - unit * 0.17f, ground, strokePaint)
        }

        canvas.save()
        canvas.rotate(standAngle, cx, ground)
        strokePaint.strokeWidth = unit * 0.036f

        // Tyre, edge on.
        val w = unit * 0.085f
        val h = unit * 0.34f
        canvas.drawRoundRect(
            RectF(cx - w, ground - h, cx + w, ground),
            w, w, strokePaint
        )
        // Bars.
        canvas.drawLine(cx - unit * 0.20f, ground - h * 0.86f,
                        cx + unit * 0.20f, ground - h * 0.86f, strokePaint)
        canvas.restore()

        if (st == null) strike(canvas, cx, cy, unit)
    }


    private fun label(canvas: Canvas, cx: Float, unit: Float, text: String) {
        textPaint.color = colLabel
        textPaint.textSize = unit * 0.130f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textPaint.letterSpacing = 0.12f
        canvas.drawText(text, cx, height - unit * 0.05f, textPaint)
        textPaint.letterSpacing = 0f
    }

    private fun glow(canvas: Canvas, cx: Float, cy: Float, unit: Float, colour: Int) {
        glowPaint.shader = RadialGradient(
            cx, cy, unit * 0.34f,
            intArrayOf(colour and 0x00FFFFFF or 0x55000000, Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, unit * 0.34f, glowPaint)
        glowPaint.shader = null
    }

    private fun strike(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        strokePaint.color = colStrike
        strokePaint.strokeWidth = unit * 0.038f
        val r = unit * 0.26f
        canvas.drawLine(cx - r, cy + r, cx + r, cy - r, strokePaint)
    }

    /**
     * The headlamp symbol: a D-shaped lens with rays. Straight rays for high
     * beam, angled down for dipped — the distinction the standard symbols make,
     * and the reason both are worth drawing rather than writing HIGH and LOW.
     */
    private fun drawHeadlight(canvas: Canvas, cx: Float, cy: Float, unit: Float, beam: String?) {
        val high = beam == "High"
        val low = beam == "Low"
        val colour = when {
            high -> colHigh
            low -> colLow
            else -> colOff
        }
        if (high || low) glow(canvas, cx, cy, unit, colour)

        val s = unit * 0.22f
        paint.color = colour
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(cx - s * 0.75f, cy - s)
        path.lineTo(cx - s * 0.15f, cy - s)
        path.arcTo(RectF(cx - s * 0.75f, cy - s, cx + s * 0.55f, cy + s), -90f, 180f, false)
        path.lineTo(cx - s * 0.75f, cy + s)
        path.close()
        canvas.drawPath(path, paint)

        strokePaint.color = colour
        strokePaint.strokeWidth = unit * 0.036f
        for (i in -1..1) {
            val y0 = cy + i * s * 0.60f
            val y1 = if (high) y0 else y0 + s * 0.40f      // dipped beam angles down
            canvas.drawLine(cx + s * 0.70f, y0, cx + s * 1.50f, y1, strokePaint)
        }

        if (beam == null) strike(canvas, cx, cy, unit)
    }

    /** The standard brake tell-tale: (!) in a circle, with the wheel letter. */
    /**
     * A speedometer face with the needle pinned -- the conventional cruise
     * tell-tale, and the one shape a rider will already recognise.
     *
     * Two lit colours, following the bike: amber when the rocker is on but no
     * speed is held, green when it is actually holding. The needle only appears
     * once it IS holding, so the difference reads at a glance without needing
     * the colour: an empty dial means armed, a dial with a needle means working.
     */
    private fun drawCruise(canvas: Canvas, cx: Float, cy: Float, unit: Float,
                           engaged: Boolean?, enabled: Boolean?) {
        val colour = when {
            engaged == true -> colCruiseSet
            enabled == true -> colCruiseOn
            else -> colOff
        }
        if (engaged == true || enabled == true) glow(canvas, cx, cy, unit, colour)

        val r = unit * 0.24f
        strokePaint.color = colour
        strokePaint.strokeWidth = unit * 0.040f

        // The dial: an arc open at the bottom, the way a speedometer scale sits.
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(box, 145f, 250f, false, strokePaint)

        // Ticks at the ends of the sweep, so it reads as a scale and not a ring.
        strokePaint.strokeWidth = unit * 0.030f
        for (deg in intArrayOf(145, 270, 35)) {
            val rad = Math.toRadians(deg.toDouble())
            val ix = cx + (r * 0.62f) * kotlin.math.cos(rad).toFloat()
            val iy = cy + (r * 0.62f) * kotlin.math.sin(rad).toFloat()
            val ox = cx + (r * 0.90f) * kotlin.math.cos(rad).toFloat()
            val oy = cy + (r * 0.90f) * kotlin.math.sin(rad).toFloat()
            canvas.drawLine(ix, iy, ox, oy, strokePaint)
        }

        // The needle appears only when a speed is actually being held.
        if (engaged == true) {
            strokePaint.strokeWidth = unit * 0.042f
            val rad = Math.toRadians(-58.0)
            canvas.drawLine(cx, cy,
                cx + (r * 0.74f) * kotlin.math.cos(rad).toFloat(),
                cy + (r * 0.74f) * kotlin.math.sin(rad).toFloat(), strokePaint)
            paint.color = colour
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, unit * 0.032f, paint)
        }

        // Struck only when NEITHER half has ever been reported. Parked, the
        // engaged half is legitimately unknown while the rocker still answers,
        // so striking on `engaged == null` alone would put a strike across a
        // lamp that is working perfectly.
        if (engaged == null && enabled == null) strike(canvas, cx, cy, unit)
    }

    /**
     * The warning triangle, blinking on the app's own clock.
     *
     * It blinks because the thing it reports blinks; a steady hazard lamp would
     * be the one telltale here that does not behave like its subject. Square
     * wave rather than the breathing used for a fallen bike, so the two never
     * read as the same kind of alarm.
     */
    private fun drawHazard(canvas: Canvas, cx: Float, cy: Float, unit: Float, state: Boolean?) {
        val on = state == true && Cluster.pulse(760L) > 0.5f
        if (state == true) postInvalidateOnAnimation()
        val colour = if (on) colHazard else colOff
        if (on) glow(canvas, cx, cy, unit, colour)

        val r = unit * 0.26f
        strokePaint.color = colour
        strokePaint.strokeWidth = unit * 0.040f
        strokePaint.strokeJoin = Paint.Join.ROUND

        path.reset()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r * 0.92f, cy + r * 0.72f)
        path.lineTo(cx - r * 0.92f, cy + r * 0.72f)
        path.close()
        canvas.drawPath(path, strokePaint)

        // The bar and dot inside, which is what makes it a warning triangle
        // rather than a plain shape.
        paint.color = colour
        paint.style = Paint.Style.FILL
        canvas.drawRect(cx - unit * 0.018f, cy - r * 0.36f,
                        cx + unit * 0.018f, cy + r * 0.16f, paint)
        canvas.drawCircle(cx, cy + r * 0.40f, unit * 0.024f, paint)

        if (state == null) strike(canvas, cx, cy, unit)
    }

    private fun drawBrake(canvas: Canvas, cx: Float, cy: Float, unit: Float, state: Boolean?, wheel: String) {
        val colour = if (state == true) colBrake else colOff
        if (state == true) glow(canvas, cx, cy, unit, colour)

        val r = unit * 0.24f
        strokePaint.color = colour
        strokePaint.strokeWidth = unit * 0.040f
        canvas.drawCircle(cx, cy, r, strokePaint)

        paint.color = colour
        paint.style = Paint.Style.FILL
        canvas.drawRect(cx - unit * 0.020f, cy - r * 0.55f, cx + unit * 0.020f, cy + r * 0.12f, paint)
        canvas.drawCircle(cx, cy + r * 0.42f, unit * 0.025f, paint)

        textPaint.color = colour
        textPaint.textSize = unit * 0.145f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        canvas.drawText(wheel, cx + r * 1.45f, cy + r * 0.52f, textPaint)

        if (state == null) strike(canvas, cx, cy, unit)
    }

    private fun drawBadge(canvas: Canvas, cx: Float, cy: Float, unit: Float, state: Boolean?, lit: Int, text: String) {
        val colour = if (state == true) lit else colOff
        if (state == true) glow(canvas, cx, cy, unit, colour)

        strokePaint.color = colour
        strokePaint.strokeWidth = unit * 0.036f
        val r = unit * 0.26f
        canvas.drawRoundRect(
            RectF(cx - r, cy - r * 0.76f, cx + r, cy + r * 0.76f),
            unit * 0.07f, unit * 0.07f, strokePaint
        )

        textPaint.color = colour
        textPaint.textSize = unit * 0.21f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        canvas.drawText(text, cx, cy + unit * 0.075f, textPaint)

        if (state == null) strike(canvas, cx, cy, unit)
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

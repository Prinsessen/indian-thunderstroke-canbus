package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * The ten seconds before you pull away.
 *
 * Everything on this card is already somewhere in the app. The point is *when*
 * it is shown: standing still, engine idling, in the drive — the only moment
 * when all of it can still be acted on. A tyre is genuinely cold, the tank can
 * be filled, and a stored fault means something other than "keep going and hope".
 *
 * Five minutes later every one of these numbers is either unfixable or useless,
 * and the rider would have had to walk four pages and do the arithmetic to get
 * them. So the app does it once, unprompted, and then gets out of the way: the
 * card leaves by itself as soon as the bike is moving, and a tap dismisses it
 * sooner.
 *
 * It appears once per connection, never twice. A check that reappeared every
 * time you stopped at a junction would be the first thing anyone turned off.
 */
class PreRideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), BikeRepository.Observer {

    companion object {
        /** Above this the rider has left; the card's moment has passed. */
        private const val ROLLING_KMH = 5.0

        /** Worst-case range below which fuel is the thing to deal with now. */
        private const val FUEL_ACT_KM = 50.0
        private const val FUEL_WATCH_KM = 120.0

        /** A healthy resting battery on a bike that has been sitting. */
        private const val BATTERY_OK_V = 12.4
        private const val BATTERY_WATCH_V = 12.0
        private const val ENGINE_RUNNING_RPM = 400
    }

    private enum class Level { OK, WATCH, ACT, UNKNOWN }
    private data class Row(val label: String, val value: String, val level: Level)

    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val colCard = Color.parseColor("#141A22")
    private val colEdge = Color.parseColor("#2A3340")
    private val colInk = Color.parseColor("#F2F5F9")
    private val colMuted = Color.parseColor("#7C8797")
    private val colOk = Color.parseColor("#4FA96B")
    private val colWatch = Color.parseColor("#E8A33D")
    private val colAct = Color.parseColor("#D2452F")
    private val colUnknown = Color.parseColor("#3A414D")

    private val density = resources.displayMetrics.density
    private fun d(v: Float) = v * density

    /** Waiting for a connection to check; false once this one has been shown. */
    private var armed = true
    private var rows: List<Row> = emptyList()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BikeRepository.addObserver(this)
        onBikeUpdate()
    }

    override fun onDetachedFromWindow() {
        BikeRepository.removeObserver(this)
        super.onDetachedFromWindow()
    }

    override fun onBikeUpdate() {
        val live = BikeRepository.isLive
        val state = if (live) BikeRepository.state else null
        val speed = BikeRepository.fast?.speedKmh ?: state?.speedKmh

        if (!live || state == null) {
            // The link went away: re-arm, so the next time the bike wakes the
            // check is offered again. This is also what makes it appear after a
            // fuel stop, which is exactly when a rider would want it.
            armed = true
            if (visibility == VISIBLE) visibility = GONE
            return
        }

        if (visibility == VISIBLE) {
            if (speed != null && speed > ROLLING_KMH) visibility = GONE else build(state)
            return
        }

        if (armed && (speed == null || speed <= 1.0)) {
            armed = false
            build(state)
            visibility = VISIBLE
        }
    }

    /** Tap anywhere to dismiss — the card is information, not a gate. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            visibility = GONE
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun build(state: BikeJsonState) {
        val list = mutableListOf<Row>()

        // --- tyres, on the cold equivalent the tyre page acts on --------------
        val tyres = TyreMemory.last()
        list += if (tyres == null) {
            Row("TYRES", "no reading yet", Level.UNKNOWN)
        } else {
            val dec = Settings.pressureUnit.decimals
            val unit = Settings.pressureLabel
            val worst = maxOf(tyres.front.level.ordinal, tyres.rear.level.ordinal)
            Row(
                "TYRES",
                "front %.${dec}f · rear %.${dec}f %s".format(
                    Settings.pressure(tyres.front.judged),
                    Settings.pressure(tyres.rear.judged), unit
                ),
                when (worst) {
                    TyreMemory.Level.OK.ordinal -> Level.OK
                    TyreMemory.Level.WATCH.ordinal -> Level.WATCH
                    else -> Level.ACT
                }
            )
        }

        // --- fuel --------------------------------------------------------------
        // Standing still is exactly when the raw level cannot be trusted: on
        // the side stand the float reads low. So this uses the last figure taken
        // while moving, and says so when that figure is from an earlier ride —
        // a number whose age is stated is useful, one presented as current is not.
        val range = FuelRange.estimateKm(FuelLevel.trusted, Settings.tankLitres)?.start
        list += when {
            range == null -> Row("FUEL", "not measured on a ride yet", Level.UNKNOWN)
            else -> Row(
                "FUEL",
                "about %.0f %s%s".format(
                    Settings.distance(range), Settings.distanceLabel,
                    if (FuelLevel.live) "" else " (last ride)"
                ),
                when {
                    // A remembered level is never worse than a caution: it may
                    // predate a fill-up, and a red row that is simply out of
                    // date teaches the rider to skip the card.
                    range < FUEL_ACT_KM -> if (FuelLevel.live) Level.ACT else Level.WATCH
                    range < FUEL_WATCH_KM -> Level.WATCH
                    else -> Level.OK
                }
            )
        }

        // --- battery -----------------------------------------------------------
        val volts = state.batteryV
        val running = (BikeRepository.fast?.rpm ?: state.rpm ?: 0) > ENGINE_RUNNING_RPM
        list += when {
            volts == null -> Row("BATTERY", "not reported", Level.UNKNOWN)
            running -> Row("BATTERY", "%.1f V, charging".format(volts),
                if (volts < 12.5) Level.WATCH else Level.OK)
            else -> Row("BATTERY", "%.1f V at rest".format(volts), when {
                volts >= BATTERY_OK_V -> Level.OK
                volts >= BATTERY_WATCH_V -> Level.WATCH
                else -> Level.ACT
            })
        }

        // --- faults ------------------------------------------------------------
        val dm1 = state.dm1
        list += when {
            dm1 == null -> Row("FAULTS", "not reported", Level.UNKNOWN)
            Dtc.healthy(dm1) -> Row("FAULTS", "none stored", Level.OK)
            else -> Row("FAULTS", Dtc.summary(dm1, short = true) ?: dm1, Level.ACT)
        }

        // --- service -----------------------------------------------------------
        list += Row("SERVICE", Service.summary(state), when (Service.status(state)) {
            Service.Status.OK -> Level.OK
            Service.Status.SOON -> Level.WATCH
            Service.Status.DUE, Service.Status.OVERDUE -> Level.ACT
            Service.Status.UNKNOWN -> Level.UNKNOWN
        })

        rows = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (rows.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()

        // Dim what is behind, so the card is plainly a moment and not a page.
        card.color = Color.BLACK
        card.alpha = 165
        canvas.drawRect(0f, 0f, w, h, card)
        card.alpha = 255

        val cardW = min(w * 0.88f, d(400f))
        val rowH = d(38f)
        val headH = d(58f)
        val footH = d(34f)
        val cardH = headH + rowH * rows.size + footH
        val left = (w - cardW) / 2f
        val top = (h - cardH) / 2f
        rect.set(left, top, left + cardW, top + cardH)

        card.style = Paint.Style.FILL
        card.color = colCard
        canvas.drawRoundRect(rect, d(14f), d(14f), card)
        card.style = Paint.Style.STROKE
        card.strokeWidth = d(1f)
        card.color = colEdge
        canvas.drawRoundRect(rect, d(14f), d(14f), card)
        card.style = Paint.Style.FILL

        // --- heading -----------------------------------------------------------
        val pad = d(18f)
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        text.textSize = d(14f)
        text.letterSpacing = 0.14f
        text.color = colInk
        canvas.drawText("BEFORE YOU RIDE", left + pad, top + d(28f), text)
        text.letterSpacing = 0f

        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = d(10f)
        text.color = colMuted
        canvas.drawText("everything that can still be fixed", left + pad, top + d(45f), text)

        // --- rows ---------------------------------------------------------------
        rows.forEachIndexed { i, row ->
            val y = top + headH + rowH * i + rowH / 2f

            dot.color = colourOf(row.level)
            canvas.drawCircle(left + pad + d(4f), y, d(4.5f), dot)

            text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            text.textSize = d(11f)
            text.letterSpacing = 0.1f
            text.color = colMuted
            canvas.drawText(row.label, left + pad + d(18f), y + d(4f), text)
            text.letterSpacing = 0f

            text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            text.textSize = d(12f)
            text.color = if (row.level == Level.UNKNOWN) colUnknown else colInk
            text.textAlign = Paint.Align.RIGHT
            // Elide rather than overlap the label: a long fault string is still
            // better read as a truncated fault than as two words on top of
            // each other.
            var shown = row.value
            val avail = cardW - pad * 2f - d(18f) - text.measureText(row.label) - d(24f)
            while (shown.length > 4 && text.measureText(shown) > avail) {
                shown = shown.substring(0, shown.length - 2)
            }
            if (shown != row.value) shown = shown.trimEnd() + "…"
            canvas.drawText(shown, left + cardW - pad, y + d(4f), text)
            text.textAlign = Paint.Align.LEFT
        }

        // --- footer --------------------------------------------------------------
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = d(10f)
        text.color = colUnknown
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "tap to dismiss · clears itself when you move",
            left + cardW / 2f, top + cardH - d(14f), text
        )
        text.textAlign = Paint.Align.LEFT
    }

    private fun colourOf(level: Level) = when (level) {
        Level.OK -> colOk
        Level.WATCH -> colWatch
        Level.ACT -> colAct
        Level.UNKNOWN -> colUnknown
    }
}

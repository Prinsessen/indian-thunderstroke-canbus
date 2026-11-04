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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * The card at key-off: the whole ride, large enough to read from the saddle.
 *
 * The twin of [PreRideView], at the other end of the ride and for the opposite
 * reason. That card is for what can still be fixed before setting off; this one
 * is for what cannot be read while riding - a top speed, a rear wheel that went
 * light under braking, the twenty minutes the hotspot was off. The owner asked
 * for it on 2026-11-04, after the REAR SLIP line turned out to be small print
 * nobody could use at speed ([RideSummary] has the reasons for each figure).
 *
 * It comes up when the ignition is seen going off after the ride has moved, and
 * stays - through the board going to sleep and an app restart - until a tap, the
 * next key-on, or a new ride. A tap on the RIDE line of the MACHINE page brings
 * it back. Nothing on it asks for anything; it is read, then it goes.
 */
class RideSummaryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), BikeRepository.Observer {

    private enum class Level { INFO, OK, WATCH, ACT, UNKNOWN }
    private data class Row(val label: String, val value: String, val level: Level)

    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // PreRideView's palette, so the two cards read as one family
    private val colCard = Color.parseColor("#141A22")
    private val colEdge = Color.parseColor("#2A3340")
    private val colInk = Color.parseColor("#F2F5F9")
    private val colMuted = Color.parseColor("#7C8797")
    private val colOk = Color.parseColor("#4FA96B")
    private val colWatch = Color.parseColor("#E8A33D")
    private val colAct = Color.parseColor("#D2452F")
    private val colUnknown = Color.parseColor("#3A414D")
    // Lighter than PreRideView's on purpose (2026-11-04): this card is read in
    // daylight at a stop, and the muted grey is the one the owner could not see
    // in sun on the throttle arc (made white in 0.9).
    private val colLabel = Color.parseColor("#B8C2D0")

    private val density = resources.displayMetrics.density
    private var scale = 1f
    private fun d(v: Float) = v * density * scale

    private var rows: List<Row> = emptyList()
    private var subtitle = ""

    private val time = SimpleDateFormat("HH:mm", Locale.UK)
    private val dayTime = SimpleDateFormat("dd/MM HH:mm", Locale.UK)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BikeRepository.addObserver(this)
        RideSummary.onChange = { post { onBikeUpdate() } }
        onBikeUpdate()
    }

    override fun onDetachedFromWindow() {
        BikeRepository.removeObserver(this)
        RideSummary.onChange = null
        super.onDetachedFromWindow()
    }

    override fun onBikeUpdate() {
        if (!RideSummary.pending) {
            if (visibility == VISIBLE) visibility = GONE
            return
        }
        build()
        if (visibility != VISIBLE) visibility = VISIBLE
    }

    /** Tap anywhere to close it. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            RideSummary.dismiss()
            visibility = GONE
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun build() {
        val list = mutableListOf<Row>()
        val dist = Settings.distanceLabel
        val spd = Settings.speedLabel

        // --- distance and time ----------------------------------------------
        // The last trip reading the app has; after an app restart with the bike
        // asleep, the one RideSummary kept at key-off.
        val km = Settings.rideDistanceKm(BikeRepository.state?.tripKm) ?: RideSummary.distanceKm
        val moving = RideStats.movingTime()
        list += Row("DISTANCE", when {
            km == null -> "not measured"
            moving == null -> "%.1f %s".format(Settings.distance(km), dist)
            else -> "%.1f %s in %s".format(Settings.distance(km), dist, moving)
        }, if (km == null) Level.UNKNOWN else Level.INFO)

        // --- speed -------------------------------------------------------------
        val avg = RideStats.averageKmh(km)
        val top = RideStats.maxSpeedKmh
        list += Row("SPEED", when {
            top <= 0.0 -> "not measured"
            avg == null -> "top %.0f %s".format(Settings.speed(top), spd)
            else -> "avg %.0f · top %.0f %s".format(Settings.speed(avg), Settings.speed(top), spd)
        }, if (top <= 0.0) Level.UNKNOWN else Level.INFO)

        // --- engine ------------------------------------------------------------
        val rpm = RideStats.maxRpm
        list += Row("ENGINE", if (rpm > 0) "top %,d rpm".format(rpm) else "not measured",
            if (rpm > 0) Level.INFO else Level.UNKNOWN)

        // --- rear slip -----------------------------------------------------------
        // Rear minus front, the ABS module's own figure. The positive end carries
        // the tyres' circumference difference too (about +3 km/h at 90 steady).
        val lo = RideSummary.slipMin
        val hi = RideSummary.slipMax
        list += if (lo == null || hi == null) Row("REAR SLIP", "not reported", Level.UNKNOWN)
        else Row("REAR SLIP", "%+.1f braking · %+.1f drive %s".format(
            Settings.speed(lo), Settings.speed(hi), spd), Level.INFO)

        // --- fuel ----------------------------------------------------------------
        val economy = RideSummary.economyL100(km, Settings.tankLitres)
        val left = FuelLevel.trusted
        val parts = mutableListOf<String>()
        economy?.let { parts += "≈%.1f %s".format(Settings.economy(it), Settings.economyLabel) }
        left?.let { parts += "$it % left" }
        list += Row("FUEL", if (parts.isEmpty()) "not measured" else parts.joinToString(" · "),
            if (parts.isEmpty()) Level.UNKNOWN else Level.INFO)

        // --- faults --------------------------------------------------------------
        val faults = RideSummary.faultList
        list += if (faults.isEmpty()) Row("FAULTS", "none on this ride", Level.OK)
        else Row("FAULTS", faults.joinToString("; ") { Dtc.describeShort(it) }, Level.ACT)

        // --- network -------------------------------------------------------------
        val noNet = RideSummary.noNetMs
        list += when {
            !RideSummary.netKnown -> Row("NETWORK", "not reported", Level.UNKNOWN)
            noNet <= 0L -> Row("NETWORK", "reached home the whole ride", Level.OK)
            else -> Row("NETWORK", "%d min without network".format((noNet + 30_000L) / 60_000L), Level.WATCH)
        }

        // --- heading -------------------------------------------------------------
        val started = RideSummary.startedAt
        val ended = RideSummary.endedAt
        val today = System.currentTimeMillis() - 20 * 60 * 60 * 1000L
        fun at(ms: Long) = (if (ms < today) dayTime else time).format(Date(ms))
        subtitle = when {
            started > 0L && ended > 0L -> "${at(started)} – ${at(ended)}"
            ended > 0L -> "key off ${at(ended)}"
            else -> "so far"
        }

        rows = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (rows.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()

        card.color = Color.BLACK
        card.alpha = 185
        canvas.drawRect(0f, 0f, w, h, card)
        card.alpha = 255

        // Large on purpose - it is read from the saddle, a tablet at arm's length.
        // Shrinks only to fit a short screen (a phone on its side).
        scale = 1f
        val naturalH = d(78f) + d(54f) * rows.size + d(40f)
        scale = min(1f, h * 0.94f / naturalH).coerceAtLeast(0.6f)

        val cardW = min(w * 0.94f, d(640f))
        val rowH = d(54f)
        val headH = d(78f)
        val footH = d(40f)
        val cardH = headH + rowH * rows.size + footH
        val left = (w - cardW) / 2f
        val top = (h - cardH) / 2f
        rect.set(left, top, left + cardW, top + cardH)

        card.style = Paint.Style.FILL
        card.color = colCard
        canvas.drawRoundRect(rect, d(16f), d(16f), card)
        card.style = Paint.Style.STROKE
        card.strokeWidth = d(1f)
        card.color = colEdge
        canvas.drawRoundRect(rect, d(16f), d(16f), card)
        card.style = Paint.Style.FILL

        // --- heading -------------------------------------------------------------
        val pad = d(22f)
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        text.textSize = d(20f)
        text.letterSpacing = 0.14f
        text.color = colInk
        canvas.drawText("THIS RIDE", left + pad, top + d(38f), text)
        text.letterSpacing = 0f

        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = d(14f)
        text.color = colMuted
        canvas.drawText(subtitle, left + pad, top + d(62f), text)

        // --- rows ------------------------------------------------------------------
        rows.forEachIndexed { i, row ->
            val y = top + headH + rowH * i + rowH / 2f
            val labelX = left + pad + d(20f)

            if (row.level != Level.INFO) {
                dot.color = colourOf(row.level)
                canvas.drawCircle(left + pad + d(5f), y, d(5.5f), dot)
            }

            text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            text.textSize = d(14f)
            text.letterSpacing = 0.1f
            text.color = colLabel
            canvas.drawText(row.label, labelX, y + d(5f), text)
            val labelW = text.measureText(row.label)
            text.letterSpacing = 0f

            text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            text.textSize = d(24f)
            text.color = if (row.level == Level.UNKNOWN) colMuted else colInk
            text.textAlign = Paint.Align.RIGHT
            var shown = row.value
            val avail = cardW - pad * 2f - d(20f) - labelW - d(24f)
            while (shown.length > 4 && text.measureText(shown) > avail) {
                shown = shown.substring(0, shown.length - 2)
            }
            if (shown != row.value) shown = shown.trimEnd() + "…"
            canvas.drawText(shown, left + cardW - pad, y + d(8f), text)
            text.textAlign = Paint.Align.LEFT
        }

        // --- footer ------------------------------------------------------------------
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = d(13f)
        text.color = colMuted
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("tap to close · goes when you switch on",
            left + cardW / 2f, top + cardH - d(16f), text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun colourOf(level: Level) = when (level) {
        Level.OK -> colOk
        Level.WATCH -> colWatch
        Level.ACT -> colAct
        Level.UNKNOWN, Level.INFO -> colUnknown
    }
}

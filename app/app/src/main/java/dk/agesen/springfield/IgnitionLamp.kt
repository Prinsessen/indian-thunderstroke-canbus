package dk.agesen.springfield

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The ignition lamp, drawn wherever the rev counter happens to live.
 *
 * Portrait reduces the tachometer to a figure and landscape gives it a full
 * dial, so the lamp has two homes and exactly one appearance. Shared here
 * rather than written twice, because a lamp that means "the bike is awake"
 * drifting into two slightly different lamps is how a cluster stops being read
 * at a glance.
 *
 * Green alive, red dead, and a struck-through ring when the bike has never
 * spoken at all. Red rather than dark for off, because dark is what a broken
 * lamp looks like: a lit red says the app is watching and the answer is no,
 * while an empty ring says it has heard nothing, which is a fault in the link
 * rather than a state of the motorcycle.
 *
 * No symbol inside it. Every other lamp on the cluster is a glyph to be
 * recognised; this one answers the question a rider asks from ten paces, and a
 * colour answers that faster than a shape can.
 */
object IgnitionLamp {

    private val colOn = Color.parseColor("#4FA96B")
    private val colOff = Color.parseColor("#D2452F")
    private val colUnknown = Color.parseColor("#3A414D")

    /**
     * @param on true = bus alive, false = quiet, null = never heard from
     * @param r  radius of the ring; the label sits below it
     */
    fun draw(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        on: Boolean?, fill: Paint, text: Paint
    ) {
        val colour = when (on) {
            true -> colOn
            false -> colOff
            null -> colUnknown
        }

        fill.shader = null
        fill.style = Paint.Style.FILL
        fill.color = colour

        if (on != null) {
            // A soft halo, so it reads as lit rather than painted on.
            fill.alpha = 46
            canvas.drawCircle(cx, cy, r * 1.55f, fill)
            fill.alpha = 255
            canvas.drawCircle(cx, cy, r * 0.60f, fill)
        }

        fill.style = Paint.Style.STROKE
        fill.strokeWidth = r * 0.20f
        canvas.drawCircle(cx, cy, r, fill)

        if (on == null) {
            fill.strokeWidth = r * 0.14f
            val d = r * 0.72f
            canvas.drawLine(cx - d, cy + d, cx + d, cy - d, fill)
        }
        fill.style = Paint.Style.FILL

        val align = text.textAlign
        text.textAlign = Paint.Align.CENTER
        text.color = colour
        text.textSize = r * 0.52f
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        text.letterSpacing = 0.10f
        canvas.drawText("IGN", cx, cy + r * 1.95f, text)
        text.letterSpacing = 0f
        text.textAlign = align
    }
}

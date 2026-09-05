package dk.agesen.springfield

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * The ABS lamp, drawn above the ignition lamp on the rev readout.
 *
 * It is the amber triangle from the bike's own dash, and it is the DM1 Warn
 * bit. That connection was made by the owner watching the instrument while
 * riding: Warn goes out and follows the ABS lamp as the wheels come up to speed
 * and the system finishes testing itself. It had read ON with no fault behind it
 * for weeks, because every look until then had been at a parked bike -- where
 * the ABS cannot self-test and so cannot clear.
 *
 * **It costs nothing over BLE.** The DM1 summary already crosses for the fault
 * list, so the lamp is read out of a string the app receives regardless. That
 * was the owner's own requirement, and it turned out to be free.
 *
 * Shares a column with the ignition lamp rather than taking a sixth slot in the
 * tell-tale row. It went there first; six slots made the cell rather than the
 * row height the limit on lamp size, and everything shrank to fit a lamp that
 * had somewhere better to be. There are 32dp of clear space above the ignition
 * ring and the rev figure does not reach that column.
 *
 * Amber, because that is the colour on the machine, and a rider reads the colour
 * before the shape.
 *
 * **A ring with brackets, and it took two goes to get there.** Built as the ISO
 * symbol first -- letters in a circle between two half-rings -- then rebuilt as
 * a warning triangle when the owner described "the yellow triangle with ABS",
 * then back again when she looked more carefully: the triangle with an
 * exclamation mark is a SEPARATE lamp on her cluster, and a red one. The ABS
 * lamp is the ring. The first version was right and the correction was wrong,
 * which is worth leaving written down.
 */
object AbsLamp {

    private val colOn = Color.parseColor("#E8A33D")
    private val colOff = Color.parseColor("#1E242C")
    private val colStrike = Color.parseColor("#39414D")

    /**
     * @param on true = lamp lit (ABS not available -- self-test or fault),
     *           false = out, null = the bus has not said
     * @param r  radius of the ring. The brackets reach 1.52r sideways and 1.02r
     *           up and down, so the lamp needs a little over 2r of height.
     */
    fun draw(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        on: Boolean?, stroke: Paint, text: Paint
    ) {
        val colour = if (on == true) colOn else colOff

        stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = colour
        stroke.strokeWidth = r * 0.145f
        canvas.drawCircle(cx, cy, r, stroke)

        // The two half-rings either side. They are what makes this the ABS
        // symbol rather than any other lettered roundel on a dashboard.
        stroke.strokeWidth = r * 0.125f
        val out = RectF(cx - r * 1.52f, cy - r * 1.02f, cx + r * 1.52f, cy + r * 1.02f)
        canvas.drawArc(out, 152f, 56f, false, stroke)
        canvas.drawArc(out, -28f, 56f, false, stroke)

        text.color = colour
        text.textAlign = Paint.Align.CENTER
        text.textSize = r * 0.78f
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        text.letterSpacing = 0f
        canvas.drawText("ABS", cx, cy + r * 0.29f, text)

        // Never reported is not the same as out, and the strike says which.
        if (on == null) {
            stroke.color = colStrike
            stroke.strokeWidth = r * 0.12f
            val d = r * 0.80f
            canvas.drawLine(cx - d, cy + d, cx + d, cy - d, stroke)
        }
    }
}

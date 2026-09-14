package dk.agesen.springfield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The link, along the foot of every page.
 *
 * It was a TextView reading "Linked · -62 dBm" in 12sp monospace grey on a flat
 * bar: correct, and the least designed thing on the screen, sitting under the
 * instruments on all four pages. In landscape it was also costing 32 dp of a
 * screen that had none to spare.
 *
 * Three changes, and the third is the one that matters:
 *
 *   - a dot rather than a word for the state, breathing on the cluster's calm
 *     rate while the link is up. It is the app's only continuous proof that
 *     anything is still arriving, and a rider glancing at a frozen page can now
 *     tell a dead link from a quiet bus without reading anything.
 *   - signal as BARS, with the figure kept small beside them. -62 dBm is not a
 *     quantity anybody has an intuition for; four bars is.
 *   - 26 dp instead of about 32, which the landscape dials get back.
 *
 * The message still gets its own words, because "Bluetooth permission denied"
 * is not a state a dot can express.
 */
class LinkStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        /** Four bars, and where BLE actually sits. Measured on the bike, not guessed. */
        private const val BAR_4 = -55
        private const val BAR_3 = -67
        private const val BAR_2 = -78
        private const val BAR_1 = -88
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val colLive = Color.parseColor("#4FA96B")
    private val colSearch = Color.parseColor("#E8A33D")
    private val colDead = Color.parseColor("#D2452F")
    private val colMuted = Color.parseColor("#7C8797")
    private val colDim = Color.parseColor("#3A414D")

    /** The words. Whatever BikeRepository is saying, or a message of our own. */
    var status: String = "Starting…"
        set(v) { field = v; invalidate() }

    /** dBm, or null when nothing has been heard to measure. */
    var rssi: Int? = null
        set(v) { field = v; invalidate() }

    /** True only while data is actually arriving. */
    var live: Boolean = false
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private var scale = 1f
    private fun d(v: Float) = v * density * scale

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        scale = min(1f, h / (26f * density)).coerceAtLeast(0.72f)
    }

    private fun bars(dbm: Int): Int = when {
        dbm >= BAR_4 -> 4
        dbm >= BAR_3 -> 3
        dbm >= BAR_2 -> 2
        dbm >= BAR_1 -> 1
        else -> 0
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f

        val colour = when {
            live -> colLive
            rssi != null -> colSearch      // heard, not linked: it is out there
            else -> colDead
        }

        // --- the dot ---------------------------------------------------------
        val r = d(3.4f)
        p.color = colour
        p.style = Paint.Style.FILL
        if (live) {
            // The one thing on screen that says data is still arriving. Calm
            // rate: this is life, not a warning. See Cluster.PULSE_CALM.
            p.alpha = Cluster.breath(150, 105, Cluster.PULSE_CALM)
            canvas.drawCircle(d(12f), cy, r * 2.1f, p)
            p.alpha = 255
            postInvalidateOnAnimation()
        }
        canvas.drawCircle(d(12f), cy, r, p)

        // --- the words -------------------------------------------------------
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = d(11f)
        text.color = if (live) colMuted else colour
        canvas.drawText(status, d(24f), cy + d(4f), text)

        // --- signal, at the right --------------------------------------------
        val dbm = rssi ?: return
        text.textAlign = Paint.Align.RIGHT
        text.textSize = d(9.5f)
        text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        text.color = colMuted
        val figure = "$dbm dBm"
        canvas.drawText(figure, w - d(8f), cy + d(3.5f), text)
        val barsRight = w - d(8f) - text.measureText(figure) - d(8f)

        // Bars grow rightwards and upwards, the way every signal meter does, so
        // it needs no caption to be read.
        val lit = bars(dbm)
        val bw = d(3f)
        val gap = d(2f)
        for (i in 0 until 4) {
            val bh = d(3.5f) + d(2.6f) * i
            val x = barsRight - (3 - i) * (bw + gap)
            rect.set(x, cy + d(5f) - bh, x + bw, cy + d(5f))
            p.color = if (i < lit) colour else colDim
            canvas.drawRoundRect(rect, bw * 0.3f, bw * 0.3f, p)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) invalidate()
    }
}

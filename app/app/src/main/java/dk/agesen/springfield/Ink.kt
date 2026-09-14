package dk.agesen.springfield

import android.graphics.LinearGradient
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader

/**
 * The lit look of a readout figure, shared by every number on the cluster that
 * is large enough to carry it.
 *
 * The figures were flat #F2F5F9 and the owner's complaint about them was exactly
 * right: a single dead white is what a spreadsheet looks like, not what an
 * instrument looks like. A real cluster figure is either etched and catching the
 * backlight or it is emitting, and in both cases it is brighter along its top
 * edge than along its bottom, and it throws a little of its own light onto the
 * face behind it.
 *
 * So two things, both cheap:
 *
 *   - a vertical ramp down the glyphs, from brighter than the tint at the top to
 *     darker at the bottom. That single gradient is the whole difference between
 *     a painted number and a lit one.
 *   - a soft halo behind them, via the paint's shadow layer with no offset,
 *     which is the one blur that is reliably hardware accelerated for text.
 *
 * Derived from the tint rather than hardcoded, so the same call dresses the
 * white speed figure, the red one past the redline, and the muted dashes that
 * stand in for a number the bus has not sent. Nothing has to know which case it
 * is in.
 *
 * One instance per figure, held by the view that draws it. The gradient is
 * rebuilt only when the baseline, the size or the tint actually change, which
 * in practice means on a layout change and on crossing the redline -- not on
 * every frame. Views that draw two figures hold two of these.
 */
class Ink {

    private var shader: LinearGradient? = null
    private var keyTop = Float.NaN
    private var keyBottom = Float.NaN
    private var keyTint = 0

    /**
     * Dress [paint] to draw a figure with this baseline, size and colour.
     *
     * @param glow what the figure throws onto the face behind it. The cluster's
     *        instrument lighting is amber, so amber is the honest answer for
     *        white ink; a hot figure passes its own red instead, because a red
     *        number in an amber halo would be two warnings arguing.
     */
    fun on(paint: Paint, baselineY: Float, textSize: Float, tint: Int, glow: Int) {
        // The glyph box, near enough: caps reach about 0.72 of the size above
        // the baseline and the ramp wants a little room under it so the darkest
        // stop is not sitting exactly on the last row of pixels.
        val top = baselineY - textSize * 0.74f
        val bottom = baselineY + textSize * 0.08f

        if (shader == null || top != keyTop || bottom != keyBottom || tint != keyTint) {
            shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(lift(tint, 0.34f), tint, drop(tint, 0.30f)),
                floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
            )
            keyTop = top; keyBottom = bottom; keyTint = tint
        }
        paint.shader = shader
        paint.setShadowLayer(textSize * 0.24f, 0f, 0f, glow)
    }

    /**
     * Put the paint back. Every one of these paints is shared with the captions
     * and the small print around the figure, and a gradient left on it would
     * quietly dress those too.
     */
    fun off(paint: Paint) {
        paint.shader = null
        paint.clearShadowLayer()
    }

    private fun lift(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt()
    )

    private fun drop(c: Int, f: Float) = Color.rgb(
        (Color.red(c) * (1f - f)).toInt(),
        (Color.green(c) * (1f - f)).toInt(),
        (Color.blue(c) * (1f - f)).toInt()
    )
}

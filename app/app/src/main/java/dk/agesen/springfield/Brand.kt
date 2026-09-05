package dk.agesen.springfield

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Optional brand assets, resolved by name at runtime.
 *
 * Both of these are things the rider can drop in later without a single line of
 * code changing. Looking them up with getIdentifier rather than R.drawable.x
 * means the project still compiles with neither file present, and starts using
 * them the moment they appear — no placeholder art committed to the repo, no
 * build break if it is missing.
 *
 * ARTWORK — put a JPG or PNG at: app/src/main/res/drawable-nodpi/splash_art.jpg
 *   Any aspect ratio; it is centre-cropped to fill the splash. When present the
 *   splash switches to a full-bleed composition and stops drawing its own badge.
 *
 *   If the image already carries the name — as the supplied one does — also set
 *   ARTWORK_HAS_WORDMARK below, and the drawn wordmark, subtitle and credit are
 *   suppressed so they are not printed on top of the ones in the picture.
 *
 * DISPLAY FACE — put a TTF at: app/src/main/res/font/display.ttf
 *   Used for the wordmark and the dial labels. A condensed technical face is
 *   what instrument panels actually use, and swapping the system sans for one
 *   changes the character of the whole app more than any single graphic will.
 *   The filename must be lowercase with no hyphens; Android resource names
 *   reject anything else.
 */
object Brand {

    /**
     * True when the supplied artwork already contains the app name and credit.
     *
     * The photograph the rider provided has SPRINGCOMMAND, the strapline and the
     * copyright composited into it, so drawing them again would stack two
     * wordmarks on one screen. Set this false for a plain image that needs the
     * app to letter it.
     */
    const val ARTWORK_HAS_WORDMARK = true

    private var artworkId: Int? = null

    private var faceChecked = false
    private var faceCache: Typeface? = null

    /**
     * The splash artwork for the CURRENT configuration, or null when none has
     * been supplied.
     *
     * Only the resource id is remembered, never the Drawable. The id is the same
     * in both orientations — it is `getDrawable` that resolves which file it
     * means, and that answer changes when the phone turns. Caching the Drawable
     * meant a rotation kept serving the portrait picture in landscape, where it
     * needed 3.1x enlargement, blew past the ceiling and fell back to a small
     * letterboxed image adrift in the middle of the screen.
     */
    fun artwork(context: Context): Drawable? {
        val id = artworkId ?: context.resources
            .getIdentifier("splash_art", "drawable", context.packageName)
            .also { artworkId = it }
        if (id == 0) return null
        return try { ContextCompat.getDrawable(context, id) } catch (e: Exception) { null }
    }

    /** The bundled display face, or the system bold when none has been supplied. */
    fun display(context: Context): Typeface {
        if (!faceChecked) {
            faceChecked = true
            val id = context.resources.getIdentifier("display", "font", context.packageName)
            faceCache = if (id != 0) {
                try { ResourcesCompat.getFont(context, id) } catch (e: Exception) { null }
            } else null
        }
        return faceCache ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
}

package dk.agesen.springfield

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A rolling record of what the link did.
 *
 * `adb logcat` only helps while the phone is attached to a computer, which is
 * the one place it will never be during a ride. When something goes wrong an
 * hour from home, the question afterwards is always "what did it say at the
 * time", and without this there is no answer.
 *
 * Kept in memory for the diagnostics screen and appended to a file so it
 * survives the app being killed. Capped hard: a log that fills the phone is a
 * worse fault than the one it was meant to explain.
 */
object RideLog {

    private const val FILE = "ridelog.txt"
    private const val MAX_BYTES = 64 * 1024
    private const val MEMORY_LINES = 250

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.UK)
    private val lines = ArrayDeque<String>()
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, FILE)
    }

    @Synchronized
    fun add(text: String) {
        val line = "${stamp.format(Date())}  $text"
        lines.addLast(line)
        while (lines.size > MEMORY_LINES) lines.removeFirst()

        val f = file ?: return
        try {
            // Truncate rather than rotate. Two files to reason about is worse
            // than losing the oldest half of one, and the recent end is the
            // part anybody reads.
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2))
            f.appendText(line + "\n")
        } catch (e: Exception) {
            // A log that throws is worse than a log that misses a line.
        }
    }

    /** Newest last, as it reads naturally. */
    @Synchronized
    fun recent(count: Int = 60): String =
        lines.toList().takeLast(count).joinToString("\n")

    /** Where to point `adb pull` at. */
    fun path(): String = file?.absolutePath ?: "(not initialised)"
}

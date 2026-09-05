package dk.agesen.springfield

import kotlin.math.exp
import kotlin.math.sin

/**
 * Shared motion for every instrument on the page.
 *
 * The constants live here rather than in each view for one reason: a cluster
 * that wakes up as four widgets on slightly different timings reads as four
 * widgets. One clock and one set of easing constants is what makes the whole
 * panel feel like a single machine coming to life.
 */
object Cluster {

    // ---------------------------------------------------------------- easing

    /**
     * Time constant of the needle's exponential approach, in seconds.
     *
     * Frame-rate independence matters more than the exact value. A naive
     * `value += (target - value) * 0.35f` in onDraw moves twice as fast on a
     * 120 Hz phone as on a 60 Hz one — the same code, visibly different
     * instruments. Deriving the step from elapsed time fixes that.
     *
     * 80 ms settles inside the 100 ms gap between BLE frames, so the needle is
     * always caught up before the next one lands: smooth, never laggy.
     */
    private const val TAU_SECONDS = 0.080f

    /** Guards against a huge step after the app was paused. */
    private const val MAX_FRAME_SECONDS = 0.1f

    fun ease(current: Float, target: Float, dtSeconds: Float, tau: Float = TAU_SECONDS): Float {
        val dt = dtSeconds.coerceIn(0f, MAX_FRAME_SECONDS)
        return current + (target - current) * (1f - exp(-dt / tau))
    }

    // ----------------------------------------------------------------- intro

    /**
     * Length of the power-on sweep.
     *
     * Slower than instinct suggests. A needle carries mass, and the whole point
     * of a self-test is that you can watch it happen — hurried, it reads as a
     * glitch rather than a check. Three and a half seconds is roughly what a
     * real cluster takes.
     */
    const val INTRO_MS = 4200L

    /**
     * Stagger between instruments, in milliseconds.
     *
     * The dials do not start together: the tacho leads, the speedo follows a
     * beat behind, then the small gauges. A cascade reads as one machine
     * powering up in sequence; simultaneous motion reads as an animation.
     */
    const val STAGGER_TACHO = 0L
    const val STAGGER_SPEEDO = 180L
    const val STAGGER_MINOR = 340L

    /**
     * Where the intro is, 0..1, or null once it is over.
     * `delay` shifts this instrument later in the cascade.
     */
    fun introProgress(startMillis: Long, delay: Long = 0L): Float? {
        if (startMillis == 0L) return null
        val elapsed = System.currentTimeMillis() - startMillis - delay
        if (elapsed >= INTRO_MS) return null
        if (elapsed < 0) return 0f              // waiting its turn, held at rest
        return (elapsed.toFloat() / INTRO_MS).coerceIn(0f, 1f)
    }

    /**
     * The needle's scripted path during the sweep, as a fraction of full scale.
     *
     * Out slowly, hold at full, back — and then the part that sells it: the
     * needle overshoots slightly below zero and settles with a damped bounce,
     * the way a real one does when the spring takes it home. Without that it
     * reads as a progress bar; with it, as an instrument.
     */
    fun introSweep(p: Float): Float = when {
        p < 0.40f -> easeInOut(p / 0.40f)                       // out, with weight
        p < 0.54f -> 1f                                          // hold at full
        p < 0.80f -> fall((p - 0.54f) / 0.26f)                   // back, gathering speed
        p < 1f    -> bounce((p - 0.80f) / 0.20f)                 // bounce off the stop
        else -> 0f
    }

    /**
     * The return, as a fall rather than a glide.
     *
     * The first version eased *out* on the way down, so the needle decelerated
     * gently to zero and then — inexplicably — started bouncing. A needle that
     * arrives gently has nothing to bounce off. This accelerates instead, so it
     * meets the stop with speed and the bounce that follows has a cause.
     */
    private fun fall(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - x * x
    }

    /**
     * Damped bounce off the rest stop.
     *
     * Two diminishing hops, never negative — a needle cannot travel past its
     * stop. The cusp where each hop meets zero is the point of contact, and
     * leaving it sharp is what makes it read as striking something solid.
     *
     * The old version clipped a full sine wave at zero, which chopped the
     * negative half out and produced the hard, arbitrary-looking hopping.
     */
    private fun bounce(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val decay = exp(-3.4f * x)
        return 0.052f * decay * kotlin.math.abs(sin(x * Math.PI.toFloat() * 2f))
    }

    /**
     * Lamp test: each tell-tale lights in turn, then all go out together.
     * `index` is its position in the row, `count` the row length.
     */
    fun introLamp(p: Float, index: Int, count: Int): Boolean {
        if (p >= 0.78f) return false
        val slot = 0.50f / count
        return p >= index * slot && p < 0.78f
    }

    /** Smooth acceleration and deceleration — a needle has mass. */
    fun easeInOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f) * (-2f * x + 2f) / 2f
    }

    /** Decelerating ease, for things that arrive rather than travel. */
    fun easeOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x) * (1f - x)
    }

    // -------------------------------------------------------- intro registry

    /**
     * Which instruments exist, and when they are allowed to sweep.
     *
     * The self-test was previously triggered from the fragment lifecycle, and
     * that turned out to depend on three things landing in the right order:
     * when ViewPager2 resumes a page, whether the fragment object survives a
     * rotation, and when the splash releases the gate. It did not land reliably
     * in either orientation, and the sweep was simply not seen.
     *
     * An instrument registers itself when it is attached to a window. That is
     * the one moment that is certain — it happens for every dial, on every
     * layout, in every orientation, with no lifecycle to reason about. Power-on
     * releases them all at once, which is also what a real cluster does: the
     * whole panel tests itself when the key turns, not when you happen to look
     * at a particular gauge.
     */
    interface Instrument {
        fun playIntro()
    }

    private var introsUnlocked = false
    private val instruments = mutableSetOf<Instrument>()

    fun register(instrument: Instrument) {
        instruments += instrument
        if (introsUnlocked) instrument.playIntro()
    }

    fun unregister(instrument: Instrument) {
        instruments -= instrument
    }

    fun unlockIntros() {
        introsUnlocked = true
        instruments.toList().forEach { it.playIntro() }
    }

    /** 0..1..0 triangle, for pulsing a redline or a caution band. */
    fun pulse(periodMs: Long = 900L): Float {
        val phase = (System.currentTimeMillis() % periodMs).toFloat() / periodMs
        return if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
    }
}

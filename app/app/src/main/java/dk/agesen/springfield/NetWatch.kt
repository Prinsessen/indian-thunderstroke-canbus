package dk.agesen.springfield

/**
 * Whether the bike's data is getting home, as the board itself sees it.
 *
 * Asked for after the ride of 2026-11-03 (the owner: "vi skal ha hotspot ideen
 * med nu"): the phone's hotspot was left off, the board scanned for it for six
 * kilometres, and nothing on the cluster said so. The board knows - byte 9 of
 * the fast packet since firmware 2026.11.03-1 - so the app says it.
 *
 * Only a LASTING loss counts. The board needs ten to fifteen seconds after a
 * wake to join a network and reach the broker, and a handover from the garage
 * WiFi to the hotspot takes about a minute (55 s measured 2026-09-15), so the
 * warning waits [GRACE_MS] and clears the moment the broker answers.
 *
 * A long press on the banner mutes it until the broker is reached again - a ride
 * without the hotspot on purpose should not be an hour of amber.
 *
 * Pure: the caller passes the time, so the test can hold it.
 */
class NetWatch {

    companion object {
        const val GRACE_MS = 90_000L
        const val NONE = 0
        const val WIFI_ONLY = 1
        const val HOME = 2
    }

    private var lostSince = 0L
    private var muted = false

    /** What the banner should say: null when nothing, else the state that has lasted. */
    fun update(net: Int?, nowMs: Long): Int? {
        if (net == null || net == HOME) {
            // Unknown (old firmware, no link to the board) is not "lost"; and the
            // broker answering ends both the warning and the mute.
            lostSince = 0L
            if (net == HOME) muted = false
            return null
        }
        if (lostSince == 0L) lostSince = nowMs
        if (muted || nowMs - lostSince < GRACE_MS) return null
        return net
    }

    fun mute() { muted = true }
}

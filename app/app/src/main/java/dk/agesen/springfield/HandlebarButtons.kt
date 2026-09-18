package dk.agesen.springfield

/**
 * The two handlebar trip buttons, as the bike reports them over BLE.
 *
 * Firmware 2026.09.19-1 adds a `button` characteristic (5f6d0004-…) that
 * notifies two bytes per press: a sequence number and an event code. The
 * code is kind + 3 * side — kind 1 short, 2 long, 3 double; side 0 left,
 * 1 right, 2 both. The app acts on a sequence change, so two identical
 * presses in a row are still two presses.
 *
 * The buttons keep their factory job: every short press pages the cluster,
 * and a long LEFT press resets the trip meter. The gestures the app uses are
 * the ones the cluster does not mind. A right double press is taken by the
 * house (it toggles the garage door through openHAB), so it is deliberately
 * not mapped here — one gesture, one meaning, whichever radio hears it.
 *
 * What is mapped (2026-09-19), asked for by the rider who wanted the heated
 * clothing under her thumbs instead of under the tank bag:
 *
 *   left double   heat one step UP, both zones
 *   both short    heat one step DOWN, both zones
 *   both long     both zones back to automatic
 */
enum class HandlebarEvent(val code: Int, val label: String) {
    LEFT_SHORT(1, "left short"),
    LEFT_LONG(2, "left long"),
    LEFT_DOUBLE(3, "left double"),
    RIGHT_SHORT(4, "right short"),
    RIGHT_LONG(5, "right long"),
    RIGHT_DOUBLE(6, "right double"),
    BOTH_SHORT(7, "both short"),
    BOTH_LONG(8, "both long"),
    BOTH_DOUBLE(9, "both double");

    companion object {
        fun fromCode(code: Int): HandlebarEvent? = entries.firstOrNull { it.code == code }
    }
}

object HandlebarButtons {

    private var lastSeq = -1

    /**
     * Set by MainActivity while it is on screen. A heat gesture calls it so the
     * app turns to the heat page by itself and the rider can watch where the
     * level lands — OFF, LOW, MED or HIGH — without looking for the tab.
     */
    var onHeatGesture: (() -> Unit)? = null

    /** Raw bytes from the characteristic. Returns the event if it was new. */
    fun onNotify(value: ByteArray): HandlebarEvent? {
        if (value.size < 2) return null
        val seq = value[0].toInt() and 0xFF
        val code = value[1].toInt() and 0xFF
        // seq 0 is the characteristic's value before any press since boot —
        // what a read on connect returns. Not an event.
        if (seq == 0 || seq == lastSeq) return null
        lastSeq = seq
        return HandlebarEvent.fromCode(code)
    }

    /** One gesture, one meaning. Everything not listed is ignored on purpose. */
    fun handle(event: HandlebarEvent) {
        when (event) {
            HandlebarEvent.LEFT_DOUBLE -> { Keis.step(+1, "handlebar: left double"); onHeatGesture?.invoke() }
            HandlebarEvent.BOTH_SHORT -> { Keis.step(-1, "handlebar: both short"); onHeatGesture?.invoke() }
            HandlebarEvent.BOTH_LONG -> { Keis.returnAllToAuto("handlebar: both long"); onHeatGesture?.invoke() }
            else -> RideLog.add("handlebar: ${event.label} — no action")
        }
    }
}

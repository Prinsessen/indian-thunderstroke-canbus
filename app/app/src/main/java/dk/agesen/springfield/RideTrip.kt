package dk.agesen.springfield

/**
 * The distance covered on *this* ride, kept by the app.
 *
 * The bike has two trip meters and broadcasts only the first, and the CAN
 * interface is listen-only by design, so neither can ever be zeroed from here.
 * This one is ours: a mark dropped on the bike's trip reading, and everything
 * since is the ride.
 *
 * **When it resets is the whole design.** The obvious answer is "every time the
 * bike starts", and it is the wrong one: stopping for fuel would wipe the
 * hundred kilometres you just rode, which is exactly the thing that makes trip
 * meters annoying. What a rider means by "this ride" survives a coffee, a tank
 * and a photograph, and ends when the bike is put away.
 *
 * So the rule is length of sleep, not the act of starting. The ignition going
 * off starts a clock; if it comes back on within [SAME_RIDE_GAP_MS] the ride
 * continues, and if not it is a new one. Three hours puts a long lunch and a
 * ferry crossing safely inside the same ride while an overnight stop starts
 * fresh.
 *
 * All of it local. Nothing here needs the network, the server, or openHAB --
 * which was the point: the number should be right on a mountain road with no
 * signal.
 */
object RideTrip {

    /** How long the bike must sleep before the next start counts as a new ride. */
    const val SAME_RIDE_GAP_MS = 3 * 60 * 60 * 1000L

    /** How often the "last heard" mark is written to disk. */
    private const val PERSIST_EVERY_MS = 60_000L

    /**
     * Fed every state update.
     *
     * Deliberately driven from the repository rather than a screen, so a ride
     * that begins with the phone in a pocket is still measured from its start.
     */
    fun onState(s: BikeJsonState?) {
        val trip = s?.tripKm

        // The very first reading the app ever gets sets the mark.
        //
        // Without this the ride shows nothing until a three-hour sleep happens
        // to occur, and the old fallback filled the gap with the bike's own
        // Trip 1 under a RIDE label -- four thousand kilometres presented as
        // this afternoon's outing. Marking on first sight makes the number
        // start at zero the moment the app meets the bike, which is the only
        // honest reading it can have before it has watched anything.
        if (trip != null && !Settings.hasTripStart) Settings.resetTrip(trip)

        val now = System.currentTimeMillis()

        // How long since we last heard from the bike at all.
        //
        // This is the measure, not "did we watch the ignition go off". The
        // first version waited to observe that transition, and it cannot be
        // relied on: park the bike, walk indoors, and the phone loses Bluetooth
        // before the key comes out. The app never sees the ignition drop, so
        // the next ride simply continues the old one -- for weeks, if the
        // pattern holds.
        //
        // Silence is the honest signal. If the bike has not been heard from for
        // SAME_RIDE_GAP_MS, it has been standing still whatever the reason, and
        // whatever comes next is a new ride.
        val lastHeard = Settings.lastStateAt
        // Persisted at most once a minute. State arrives every second, and
        // writing a preference at 1 Hz for the length of a ride is a lot of
        // flash for a timestamp that only has to be accurate to the hour. A
        // minute of slop against a three-hour threshold changes nothing.
        if (now - lastHeard >= PERSIST_EVERY_MS) Settings.lastStateAt = now

        val silent = if (lastHeard == 0L) 0L else now - lastHeard
        if (silent >= SAME_RIDE_GAP_MS) {
            // Only mark against a real reading; marking against a null would
            // silently reset to whatever arrived first, which could be several
            // kilometres into the ride.
            if (trip != null) Settings.resetTrip(trip)
            return
        }

        // The ignition transition still counts when we do happen to see it: it
        // catches a long stop that the phone stayed awake through, where the
        // stream never went silent.
        val ignition = s?.ignitionOn ?: return
        val wasOn = Settings.ignitionWasOn
        if (ignition == wasOn) return
        Settings.ignitionWasOn = ignition

        if (!ignition) {
            Settings.ignitionOffAt = now
            return
        }
        val offAt = Settings.ignitionOffAt
        if (offAt != 0L && now - offAt >= SAME_RIDE_GAP_MS && trip != null) {
            Settings.resetTrip(trip)
        }
    }
}

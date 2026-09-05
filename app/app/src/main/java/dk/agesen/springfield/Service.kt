package dk.agesen.springfield

/**
 * How far it is to the next service.
 *
 * Two numbers and a subtraction, which is the whole feature — the value is not
 * in the arithmetic but in the fact that nobody has to remember the mileage of
 * an oil change from eight months ago.
 *
 * **On where the number lives.** A phone is the wrong home for it. The interval
 * belongs to the motorcycle, and a phone gets reinstalled, replaced, dropped in
 * a car park. So this reads the bike's own answer first and falls back to the
 * app's setting only when the bike has none — which is every firmware built so
 * far. Teaching the ESP32 to keep it in NVS and report it as `svcKm` is a small
 * change, and the moment it lands the number stops depending on this phone
 * existing. Nothing here changes when it does.
 */
object Service {

    /** Indian's figure for the Thunder Stroke 111, and the default interval. */
    const val INDIAN_INTERVAL_KM = 8_000

    enum class Status { UNKNOWN, OK, SOON, DUE, OVERDUE }

    /** Inside this, it is worth planning for. */
    private const val SOON_KM = 800

    /** The odometer at the last service — the bike's answer wins. */
    fun lastServiceKm(state: BikeJsonState?): Int? =
        state?.serviceKm ?: Settings.serviceLastKm.takeIf { it >= 0 }

    /** Kilometres remaining; negative means overdue by that much. */
    fun remainingKm(state: BikeJsonState?): Int? {
        val odo = state?.odometerKm ?: return null
        val last = lastServiceKm(state) ?: return null
        return last + Settings.serviceIntervalKm - odo
    }

    fun status(state: BikeJsonState?): Status {
        val left = remainingKm(state) ?: return Status.UNKNOWN
        return when {
            left < 0 -> Status.OVERDUE
            left == 0 -> Status.DUE
            left <= SOON_KM -> Status.SOON
            else -> Status.OK
        }
    }

    /**
     * One line, in the rider's distance unit.
     *
     * Converted rather than shown in kilometres, because a rider reading miles
     * everywhere else on the cluster should not have to translate the one number
     * that decides whether they book a workshop.
     */
    fun summary(state: BikeJsonState?): String {
        val left = remainingKm(state) ?: return when {
            state?.odometerKm == null -> "odometer not reported"
            else -> "no service recorded — set it in settings"
        }
        val unit = Settings.distanceLabel
        val v = Settings.distance(kotlin.math.abs(left).toDouble())
        return if (left < 0) "overdue by %.0f %s".format(v, unit)
        else "%.0f %s to service".format(v, unit)
    }

    /**
     * Record a service at the current odometer.
     *
     * Sent to the bike, which keeps it in NVS. That is the whole reason this
     * exists as a firmware feature rather than a preference: the phone is the
     * one part of the system that gets reinstalled, and this is the one number
     * in it that cannot be measured again.
     *
     * Nothing is confirmed from the write itself. The bike republishes `svcKm`
     * in its next state frame, and everything that reads the figure prefers
     * that — so if the bike refused the value as implausible, what appears is
     * what the bike still holds. Echoing back the number you just sent proves
     * only that you sent it.
     *
     * The local setting is written only when there is no link, as a note to
     * carry until the bike can be told. Writing it in both cases would leave a
     * stale fallback behind a rejected write.
     */
    fun recordAt(odometerKm: Int): String? {
        val problem = BleService.recordService(odometerKm)
        if (problem != null) Settings.serviceLastKm = odometerKm
        return problem
    }
}

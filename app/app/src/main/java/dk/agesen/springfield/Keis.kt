package dk.agesen.springfield

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The heated clothing: state, automatic control, and the one hole that is not
 * fillable yet.
 *
 * Everything here is written and working **except** talking to the hardware.
 * The Keis protocol is undocumented and has to be reverse-engineered from
 * captures of Keis iControl, so `KeisDevice` is an interface with a deliberately
 * inert implementation. When the capture is analysed, one class is written and
 * nothing else in the app changes.
 *
 * That split is the point of building it this way: the arithmetic, the curves,
 * the hysteresis and the page can all be got right and tested now, and the part
 * that must wait is fifty lines behind an interface rather than tangled through
 * the feature.
 */

/**
 * One controller.
 *
 * Levels are the three the hardware actually has — green, amber, red, and off —
 * rather than a percentage. Modelling it as 0-100 would let the app ask for 45%,
 * which does not exist, and the driver would then round to something the rider
 * never chose.
 */
interface KeisDevice {
    val zone: HeatCurve.Zone
    val connected: Boolean
    /** Last level the device reported or accepted, or null if unknown. */
    val level: HeatCurve.Level?
    val batteryPct: Int?

    fun connect(context: Context)
    fun disconnect()

    /** Connect now, having just seen this device advertise. Optional. */
    fun connectSeenNow(context: Context) {}

    /**
     * Set the level.
     *
     * **Never send a value not observed coming from iControl itself.** Reading a
     * CAN bus wrongly produces a wrong number on a screen; writing wrongly to a
     * heating element produces heat. A driver must replay the encodings the
     * capture showed and refuse anything outside them, rather than assuming a
     * scale is linear or a byte range is safe.
     */
    fun setLevel(level: HeatCurve.Level)
}

/**
 * The stand-in until the protocol is known.
 *
 * It is inert on purpose rather than approximately right: a driver that guessed
 * at the encoding would be worse than none, because it would appear to work.
 */
class UnimplementedKeisDevice(override val zone: HeatCurve.Zone) : KeisDevice {
    override val connected = false
    override val level: HeatCurve.Level? = null
    override val batteryPct: Int? = null
    override fun connect(context: Context) {
        RideLog.add("keis: $zone driver not implemented — protocol not captured")
    }
    override fun disconnect() {}
    override fun setLevel(level: HeatCurve.Level) {
        RideLog.add("keis: refused to set $zone to ${level.label} — no protocol")
    }
}

/**
 * Holds both controllers and runs the automatic control.
 *
 * Automatic mode computes from the bike's ambient and speed; manual mode holds
 * whatever the rider set. **Manual always wins until the rider hands it back** —
 * an app that quietly overrides what someone just set is one they stop trusting.
 */
object Keis : BikeRepository.Observer {

    interface Observer { fun onKeisUpdate() }

    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()

    var legs: KeisDevice = UnimplementedKeisDevice(HeatCurve.Zone.LEGS)
        private set
    var jacket: KeisDevice = UnimplementedKeisDevice(HeatCurve.Zone.JACKET)
        private set

    /**
     * Build the driver a zone should have.
     *
     * A configured MAC gets the real one; an unconfigured zone keeps the inert
     * stand-in, so a half-set-up app says "not connected" rather than throwing.
     */
    private fun driverFor(zone: HeatCurve.Zone): KeisDevice {
        val mac = Settings.heatMac(zone)
        return if (mac.isBlank()) UnimplementedKeisDevice(zone) else KeisBleDevice(zone, mac)
    }

    /** Re-read the assignments and reconnect. Called after settings change. */
    fun reconfigure(context: Context) {
        legs.disconnect()
        jacket.disconnect()
        legs = driverFor(HeatCurve.Zone.LEGS)
        jacket = driverFor(HeatCurve.Zone.JACKET)
        legs.connect(context)
        jacket.connect(context)
        notifyObservers()
    }

    /**
     * The controller telling us where it actually is.
     *
     * Adopted rather than corrected: a rider may have pressed the button on the
     * controller itself, and an app that immediately overrode that would be
     * fighting the hardware in the rider's hand.
     */
    fun onDeviceReport(zone: HeatCurve.Zone, level: HeatCurve.Level) {
        // Marshalled onto the main thread, because everything else that touches
        // these maps already runs there.
        //
        // This arrives on a BLE callback thread while onBikeUpdate() reads the
        // same plain HashMaps from the main one, with nothing between them. The
        // rider presses HIGH on the controller, this sets manual += zone — and
        // the automatic loop, never having been told, computes MED and writes it
        // four seconds later. That is in the ride log twice, once per zone,
        // three seconds apart: reports HIGH, then -> MED.
        //
        // A lock would also work. One thread is simpler, and cannot be got wrong
        // later by someone adding a fifth field and forgetting to guard it.
        main.post {
            val hadAsked = requested.containsKey(zone)
            requested[zone] = level
            changedAt[zone] = System.currentTimeMillis()

            // Only a report that contradicts something we asked for means the
            // rider pressed the button. The first report after a connect is the
            // driver asking what state the hardware is in, and adopting that is
            // not an override — treating it as one put both zones into manual
            // the moment they connected.
            if (hadAsked) {
                manual += zone
                wanted[zone] = level
            }
            notifyObservers()
        }
    }

    /** Per-zone: the level the app last asked for, and when. */
    private val requested = mutableMapOf<HeatCurve.Zone, HeatCurve.Level>()
    private val changedAt = mutableMapOf<HeatCurve.Zone, Long>()

    /** Why each zone was last written, for the ride log. */
    private val lastReason = mutableMapOf<HeatCurve.Zone, String>()

    /** Zones the rider has taken manual control of this session. */
    private val manual = mutableSetOf<HeatCurve.Zone>()

    /** Felt temperature from the last bike update, for the page to show. */
    @Volatile var feltC: Double? = null
        private set

    /** What the bike's electrical system currently allows. */
    @Volatile var supply: HeatCurve.Supply = HeatCurve.Supply.FINE
        private set

    /**
     * A supply reading has to hold for this long before it counts.
     *
     * Starting the engine drags the battery through both thresholds and the rpm
     * through the running/stopped line, all within a couple of seconds. Acting
     * on each crossing switched the jacket off, to low, to medium and off again
     * in twenty seconds — visible in the ride log, and indistinguishable from
     * the automatic control having lost its mind.
     *
     * The cap exists to protect the battery over minutes. A two-second dip
     * while the starter turns threatens nothing, so nothing needs to happen
     * until a reading has been the same for a while. Chatter at a threshold
     * resets the timer and therefore never takes effect at all, which is the
     * behaviour wanted.
     */
    private const val SUPPLY_DWELL_MS = 6_000L

    private var supplyCandidate = HeatCurve.Supply.FINE
    private var supplyCandidateSince = 0L

    /** True when the cap is actually holding a zone below what was asked for. */
    fun capped(zone: HeatCurve.Zone): Boolean {
        val wanted = wanted[zone] ?: return false
        return wanted.ordinal > HeatCurve.cap(supply).ordinal
    }

    /** What was asked for, before the electrical cap was applied. */
    private val wanted = mutableMapOf<HeatCurve.Zone, HeatCurve.Level>()

    /**
     * The level asked for, cap or no cap.
     *
     * Exposed so the page can show both figures at once when they differ. A
     * capped zone that displayed only what it settled for would look like it had
     * ignored the rider, which is the one impression the cap must not give.
     */
    fun wantedLevel(zone: HeatCurve.Zone): HeatCurve.Level? = wanted[zone]

    private var started = false

    /** Idempotent: a rotation recreates the activity but not the connections. */
    fun start(context: Context) {
        BikeRepository.addObserver(this)
        if (started) return
        started = true
        legs = driverFor(HeatCurve.Zone.LEGS)
        jacket = driverFor(HeatCurve.Zone.JACKET)
        legs.connect(context)
        jacket.connect(context)
    }

    /**
     * Look for the garments properly, because someone is watching.
     *
     * Runs an active scan and logs every Keis controller it sees, whether or not
     * it is one of ours. That log line is the point as much as the connection
     * is: if a controller that has only just been plugged in turns up here, its
     * radio was on all along and the background scan was simply too lazy to
     * catch it — which is the open question this is meant to settle.
     */
    fun scanHarderNow(context: Context) {
        if (Settings.heatMac(HeatCurve.Zone.LEGS).isBlank() &&
            Settings.heatMac(HeatCurve.Zone.JACKET).isBlank()) return

        // Only zones that are not already connected. A connected peripheral
        // stops advertising, so scanning for one is guaranteed to find nothing
        // and would fill the log with a result that means the opposite of what
        // it reads like.
        val wanted = HeatCurve.Zone.entries
            .filter { !deviceFor(it).connected && Settings.heatMac(it).isNotBlank() }
            .associateBy { Settings.heatMac(it).uppercase() }
        if (wanted.isEmpty()) return

        KeisScanner.scanFor(context, wanted.keys.toList()) { address ->
            val zone = wanted[address.uppercase()] ?: return@scanFor
            RideLog.add("keis: $zone is advertising — its radio is on")
            deviceFor(zone).connectSeenNow(context)
        }
    }

    fun addObserver(o: Observer) { observers.addIfAbsent(o) }
    fun removeObserver(o: Observer) { observers.remove(o) }

    /** The reason for the level about to be written, for the driver's log line. */
    fun reasonFor(zone: HeatCurve.Zone): String =
        lastReason[zone]?.let { "  ($it)" } ?: ""

    fun deviceFor(zone: HeatCurve.Zone) = if (zone == HeatCurve.Zone.LEGS) legs else jacket
    fun requestedLevel(zone: HeatCurve.Zone): HeatCurve.Level? = requested[zone]
    fun isAutomatic(zone: HeatCurve.Zone) = Settings.heatAuto && zone !in manual

    /**
     * Why automatic is not acting on this zone, or null when it is.
     *
     * Four separate conditions can stop the loop, and until now the panel
     * explained exactly one of them. The worst was the global switch: with
     * "manual only" set, a long-press removes the zone from [manual] and
     * changes nothing visible, because isAutomatic() still answers false. The
     * rider holds, sees no change, and holds again — there is no way out of
     * that from the screen, and nothing on it says why.
     *
     * A control that stops working should say which of its preconditions failed.
     */
    fun blockedReason(zone: HeatCurve.Zone): String? = when {
        !Settings.heatAuto -> "automatic is off — turn it on in settings"
        zone in manual -> null                      // deliberate; the panel says MANUAL
        !deviceFor(zone).connected -> null          // the panel already says this
        !BikeRepository.isLive -> "no link to the bike — holding the last level"
        feltC == null -> "no temperature from the bike"
        // Belt and braces: the steppers cannot produce this any more, but a
        // value stored by an older build still can, and it would otherwise stop
        // automatic without a word.
        Settings.heatOffAt(zone) <= Settings.heatFullAt(zone) ->
            "the curve is inverted — \"off at\" must be warmer than \"full at\""
        else -> null
    }

    /** The rider taking over. Sticks until they hand it back. */
    fun setManual(zone: HeatCurve.Zone, level: HeatCurve.Level) {
        manual += zone
        wanted[zone] = level
        apply(zone, capTo(level), "manual")
        notifyObservers()
    }

    /** Never above what the bike can feed, whoever asked. */
    private fun capTo(level: HeatCurve.Level): HeatCurve.Level {
        val ceiling = HeatCurve.cap(supply)
        return if (level.ordinal > ceiling.ordinal) ceiling else level
    }

    /**
     * Handing a zone back to the app.
     *
     * Needed a way in: manual mode was reachable and permanent, which is half a
     * feature. Automatic recomputes on the next bike update, so nothing has to
     * be applied here.
     */
    fun returnToAuto(zone: HeatCurve.Zone) {
        manual -= zone

        // Act now, rather than on the next temperature change.
        //
        // shouldApply() holds a single-step change for 45 seconds to stop the
        // level flapping around a boundary. That is right for automatic drift
        // and wrong here: handing control back is a deliberate act, and a
        // control that appears to do nothing for the best part of a minute is
        // one the rider decides is broken. Clearing the timestamp lets the very
        // next update apply, which arrives within a second.
        changedAt[zone] = 0L

        val computed = HeatCurve.levelFor(zone, feltC, wanted[zone])
        if (computed != null && deviceFor(zone).connected) {
            wanted[zone] = computed
            apply(zone, capTo(computed), "resume")
        }
        notifyObservers()
    }

    /**
     * The rider moved a curve endpoint. Recompute without waiting.
     *
     * shouldApply() holds a single-step change for 45 seconds so the level does
     * not flap around a boundary. That is right for temperature drifting past a
     * threshold, and wrong for a hand on a stepper: turn the knob and nothing
     * happens for most of a minute, then a change arrives — and if several
     * settings were touched, they arrive one per interval, long after the hand
     * has moved on.
     *
     * The ride log of 3 September reads as a garment cycling at random through
     * every level at a constant 17 degrees. It was not random and it was not
     * wrong; each write matched the curve as it stood when that write was
     * finally allowed through. But nobody watching could have known that, and a
     * control whose effect arrives a minute late is one nobody can learn from.
     */
    fun curveChanged(zone: HeatCurve.Zone) {
        RideLog.add("keis: $zone curve set to %.0f/%.0f".format(
            Settings.heatOffAt(zone), Settings.heatFullAt(zone)))
        changedAt[zone] = 0L
        onBikeUpdate()
    }

    /** What automatic would choose right now, for the page to show alongside. */
    fun autoWouldChoose(zone: HeatCurve.Zone): HeatCurve.Level? =
        HeatCurve.levelFor(zone, feltC, wanted[zone])

    override fun onBikeUpdate() {
        // Ambient and speed both come from the bike, so a dead link means the
        // inputs are unknown. Holding the last level is the only safe answer:
        // computing from stale weather would be wrong, and falling to zero would
        // turn a lost Bluetooth link into a cold hour.
        if (!BikeRepository.isLive) return

        val state = BikeRepository.state
        val felt = HeatCurve.feelsLike(state?.ambientC, BikeRepository.fast?.speedKmh)
        feltC = felt

        // The supply is checked before anything is decided, and it constrains
        // manual and automatic alike — but only once it has settled.
        val now = System.currentTimeMillis()
        val raw = HeatCurve.supply(state?.batteryV, BikeRepository.fast?.rpm)
        if (raw != supplyCandidate) {
            supplyCandidate = raw
            supplyCandidateSince = now
        }
        val before = supply
        if (supplyCandidate != supply && now - supplyCandidateSince >= SUPPLY_DWELL_MS) {
            supply = supplyCandidate
        }
        if (supply != before) {
            RideLog.add("keis: supply $before -> $supply")
            // A tightening cap has to take effect at once rather than waiting
            // for the next temperature change, which might be minutes away.
            for (zone in HeatCurve.Zone.entries) {
                wanted[zone]?.let { w ->
                    val allowed = capTo(w)
                    // Only when it actually differs, and only to a connected
                    // garment. Writing a level to a controller that is not there
                    // achieves nothing but a log line.
                    if (allowed != requested[zone] && deviceFor(zone).connected) {
                        apply(zone, allowed, "cap $supply")
                    }
                }
            }
        }

        if (Settings.heatAuto) {
            for (zone in HeatCurve.Zone.entries) {
                if (zone in manual) continue
                // A garment left at home has no level to hold and nothing to
                // send to; computing for it would only fill the log.
                if (!deviceFor(zone).connected) continue
                // The current level is an input, not just an output: the
                // boundaries move against the direction of travel, which is what
                // stops three levels flapping over one degree.
                val computed = HeatCurve.levelFor(zone, felt, wanted[zone]) ?: continue
                wanted[zone] = computed
                val allowed = capTo(computed)
                if (HeatCurve.shouldApply(requested[zone], allowed, changedAt[zone] ?: 0L)) {
                    // felt and supply printed with it: if the computed level is
                    // wrong, the answer is in its inputs, and reconstructing
                    // them afterwards from a temperature that has since moved is
                    // not possible.
                    // The curve goes in the log with the temperature.
                    //
                    // Three times now a log has shown a level that the curve
                    // could not produce, and twice the answer was that the
                    // rider had a hand on the stepper — which nothing in the
                    // log could have told either of us. felt was recorded and
                    // the two numbers it is compared against were not, so the
                    // one input that changed was the one input missing.
                    apply(zone, allowed, "auto felt=%.0f curve=%.0f/%.0f %s".format(
                        felt ?: Double.NaN,
                        Settings.heatOffAt(zone), Settings.heatFullAt(zone), supply))
                }
            }
        }
        notifyObservers()
    }

    /**
     * Send a level, and record why.
     *
     * The reason is the point. A ride log full of "LEGS -> OFF" followed a
     * second later by "LEGS -> LOW" says something is fighting itself and
     * nothing about which two things — and with four call sites able to write,
     * reading the log becomes guesswork. One word turns the next capture into
     * an answer.
     */
    private fun apply(zone: HeatCurve.Zone, level: HeatCurve.Level, why: String) {
        lastReason[zone] = why
        requested[zone] = level
        changedAt[zone] = System.currentTimeMillis()
        deviceFor(zone).setLevel(level)
    }

    private fun notifyObservers() {
        main.post { observers.forEach { it.onKeisUpdate() } }
    }
}

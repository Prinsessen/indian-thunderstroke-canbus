package dk.agesen.springfield

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one place the current bike state lives.
 *
 * The BLE connection is owned by a foreground service, not by the UI, so the
 * pages cannot hold it themselves — they come and go as the user swipes and as
 * Android destroys and recreates fragments. This singleton sits between: the
 * service writes, the visible page reads, and nothing breaks when a page is not
 * there to receive an update.
 */
object BikeRepository {

    interface Observer {
        /** Called on the main thread whenever any of the fields below changed. */
        fun onBikeUpdate()
    }

    /**
     * How long without a packet before the numbers stop being trusted.
     *
     * The fast characteristic arrives at ~10 Hz, so two and a half seconds of
     * silence is a lost link, not a gap.
     */
    private const val STALE_MS = 2500L

    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()

    @Volatile private var lastFastAt = 0L

    /**
     * False once the link has gone quiet.
     *
     * This matters more than it looks. When BLE drops mid-ride the last packet
     * simply stays on screen: needles hold, the speed reads 80, and nothing says
     * otherwise. A frozen number presented as a live one is worse than no number
     * — so stale is folded into the same "unknown" the protocol's sentinels
     * already produce, and every view already knows how to draw that.
     */
    val isLive: Boolean
        get() = lastFastAt != 0L && System.currentTimeMillis() - lastFastAt < STALE_MS

    /**
     * Nothing arrives to trigger a repaint when a link dies — that is the whole
     * problem — so a slow tick drives the transition into the stale state.
     */
    private val staleTicker = object : Runnable {
        override fun run() {
            if (observers.isNotEmpty()) notifyObservers()
            main.postDelayed(this, 1000)
        }
    }

    init {
        main.postDelayed(staleTicker, 1000)
    }

    @Volatile var fast: FastPacket? = null
        private set

    @Volatile var state: BikeJsonState? = null
        private set

    @Volatile var status: String = "Starting…"
        private set

    /**
     * The last raw payloads, kept for the diagnostics screen.
     *
     * When the app and openHAB disagree, the question is always whether the
     * decode is wrong or the bytes are — and that cannot be answered from three
     * machines away without the bytes.
     */
    @Volatile var lastFastRaw: ByteArray? = null
        private set

    @Volatile var lastStateJson: String? = null
        private set

    fun setRaw(fastBytes: ByteArray?, stateJson: String?) {
        fastBytes?.let { lastFastRaw = it }
        stateJson?.let { lastStateJson = it }
    }

    /** Link signal strength in dBm, or null when not connected. */
    @Volatile var rssi: Int? = null
        private set

    /** Negotiated ATT MTU; the state JSON is truncated below ~500. */
    @Volatile var mtu: Int? = null
        private set

    fun setMtu(value: Int) {
        mtu = value
    }

    fun setRssi(value: Int?) {
        rssi = value
        notifyObservers()
    }

    /** True once a paired, subscribed link is delivering packets. */
    @Volatile var connected: Boolean = false
        private set

    fun addObserver(o: Observer) { observers.addIfAbsent(o) }
    fun removeObserver(o: Observer) { observers.remove(o) }

    fun setStatus(text: String) {
        status = text
        // "Disconnected" and "Scanning" both mean the numbers on screen are no
        // longer live — the pages grey out on this rather than keep showing a
        // frozen speed as though the bike were still reporting it.
        if (text.startsWith("Disconnected") || text.startsWith("Scanning")) {
            connected = false
        }
        notifyObservers()
    }

    fun setFast(p: FastPacket) {
        fast = p
        lastFastAt = System.currentTimeMillis()
        connected = true
        RideStats.feed(p)
        notifyObservers()
    }

    fun setState(s: BikeJsonState) {
        state = s
        RideTrip.onState(s)      // starts a new ride after a long enough sleep
        connected = true
        TyreMemory.remember(s)
        notifyObservers()
    }

    private fun notifyObservers() {
        main.post { observers.forEach { it.onBikeUpdate() } }
    }
}

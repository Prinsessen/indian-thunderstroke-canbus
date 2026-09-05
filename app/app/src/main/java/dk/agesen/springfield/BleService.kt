package dk.agesen.springfield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Owns the BLE connection for as long as the app is meant to be listening.
 *
 * This is a foreground service and not an activity-held connection for one
 * blunt reason: Android tears down a plain activity's GATT link the moment the
 * screen turns off or the user switches apps. On a ride that is every time the
 * phone locks — which is most of the ride. A foreground service with an ongoing
 * notification is the only supported way to keep a device connection alive.
 *
 * The service writes into BikeRepository; the UI reads from it. Nothing in the
 * connection path depends on a page being visible.
 */
class BleService : Service(), BikeBleClient.Listener {

    companion object {
        private const val CHANNEL_ID = "bike_link"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dk.agesen.springfield.STOP"

        /** startForegroundService, not startService — required from API 26. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, BleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleService::class.java))
        }

        /**
         * The running service, so a screen can reach the link without owning it.
         *
         * Set in onCreate and cleared in onDestroy. Null means there is no link
         * to talk to, which callers have to handle anyway — the bike is off more
         * than it is on.
         */
        @Volatile
        private var running: BleService? = null

        /**
         * Null when it was sent; otherwise why it was not.
         *
         * The null case here is the *service* being absent, not the link. Saying
         * "the bike link is not running" for that sent the rider looking at
         * Bluetooth while the app showed a live connection — the two are
         * different things and the message has to say which one is missing.
         */
        fun recordService(km: Int): String? =
            running?.client?.writeServiceKm(km)
                ?: "the app's background service is not running — reopen the app and try again"

        /**
         * The rider is looking at the cluster — stop scanning politely.
         *
         * Safe to call on every foreground: the client ignores it unless the
         * scan has already backed off to low power.
         */
        fun scanHarderNow() { running?.client?.scanHarderNow() }
    }

    private var client: BikeBleClient? = null
    private var lastNotificationText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        RideLog.init(this)
        TyreMemory.init(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        client = BikeBleClient(this, this).also { it.start() }
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's Stop action is the only way out of a START_STICKY
        // service. Without it the link — and the scanning behind it — would run
        // until Android or a force-stop killed it, which is a battery bill the
        // rider never agreed to.
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Otherwise restart if Android kills us for memory — surviving is the point.
        return START_STICKY
    }

    override fun onDestroy() {
        running = null
        client?.stop()
        client = null
        super.onDestroy()
    }

    // ------------------------------------------------------------- listener

    override fun onStatus(text: String) {
        BikeRepository.setStatus(text)
        updateNotification(text)
    }

    override fun onFast(packet: FastPacket) {
        BikeRepository.setFast(packet)
        // The notification is glanceable state, not telemetry — rewriting it ten
        // times a second would be wasteful and unreadable, so it only changes
        // when the text actually would.
        val text = packet.rpm?.let { "$it rpm · gear ${packet.gear ?: "—"}" } ?: "Connected"
        updateNotification(text)
    }

    override fun onState(state: BikeJsonState) {
        BikeRepository.setState(state)
        // Fed here rather than from a page: the filter needs every reading the
        // bike sends, and a page only exists while someone is looking at it.
        FuelLevel.feed(state.fuelPct, BikeRepository.fast?.speedKmh ?: state.speedKmh)
    }

    // --------------------------------------------------------- notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bike link",
            NotificationManager.IMPORTANCE_LOW   // no sound, no heads-up
        ).apply {
            description = "Keeps the Bluetooth connection to the bike alive"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SpringCommand")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "Stop", stop
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}

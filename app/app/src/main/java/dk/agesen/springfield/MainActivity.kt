package dk.agesen.springfield

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import android.view.View
import android.animation.ObjectAnimator
import android.content.Intent
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hosts the three pages and owns nothing else.
 *
 * The BLE link deliberately does not live here — it lives in BleService, so it
 * survives the screen turning off, the app going to the background, and every
 * rotation. This activity only starts that service and renders what the
 * repository holds.
 */
class MainActivity : AppCompatActivity(), BikeRepository.Observer {

    private lateinit var pager: ViewPager2
    private lateinit var statusView: LinkStripView
    private lateinit var tabs: List<TextView>
    private lateinit var splash: SplashView

    private val permissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            // The foreground service notification needs this from Android 13.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list += Manifest.permission.POST_NOTIFICATIONS
            }
            return list.toTypedArray()
        }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Notifications being refused is survivable; Bluetooth is not.
            val bluetoothOk = granted[Manifest.permission.BLUETOOTH_SCAN] != false &&
                    granted[Manifest.permission.BLUETOOTH_CONNECT] != false
            if (bluetoothOk) {
                BleService.start(this)
            } else {
                statusView.status = "Bluetooth permission denied — cannot connect"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        TyreMemory.init(this)
        Keis.start(this)
        // A mount holds the phone one way up, and auto-rotate on a bike answers
        // to bumps and lean angle as readily as to intent.
        requestedOrientation = Settings.requestedOrientation
        setContentView(R.layout.activity_main)
        applyImmersive()

        statusView = findViewById(R.id.status)
        pager = findViewById(R.id.pager)
        splash = findViewById(R.id.splash)
        tabs = listOf(
            findViewById(R.id.tab0), findViewById(R.id.tab1),
            findViewById(R.id.tab2), findViewById(R.id.tab3)
        )

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 4
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> RideFragment()
                1 -> TyresFragment()
                2 -> MachineFragment()
                else -> HeatFragment()
            }
        }
        // Keep all three alive: swiping back to a page mid-ride should show the
        // current numbers, not a rebuild.
        pager.offscreenPageLimit = 3

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = highlightTab(position)
        })
        tabs.forEachIndexed { i, tab -> tab.setOnClickListener { pager.currentItem = i } }
        highlightTab(0)

        findViewById<TextView>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Only on a cold start. A rotation recreates the activity, and sitting
        // through the identity screen again every time the phone turns in a
        // pocket would be maddening rather than premium.
        if (savedInstanceState == null) {
            splash.play {
                ObjectAnimator.ofFloat(splash, "alpha", 1f, 0f).apply {
                    duration = 420
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            splash.visibility = View.GONE
                            // Only now can anyone see the instruments, so only
                            // now is it worth them running their self-test.
                            Cluster.unlockIntros()
                        }
                    })
                    start()
                }
            }
        } else {
            // A rotation skips the splash; nothing is covering the cluster.
            splash.visibility = View.GONE
            Cluster.unlockIntros()
        }
    }

    override fun onStart() {
        super.onStart()
        // A heat gesture on the handlebar turns the app to the heat page, so the
        // rider sees the level land. Page 3 is HeatFragment in the adapter above.
        HandlebarButtons.onHeatGesture = { pager.currentItem = 3 }
        // Both re-applied on return from settings, where either may have changed.
        requestedOrientation = Settings.requestedOrientation
        window.attributes = window.attributes.apply {
            screenBrightness = if (Settings.forceBright) 1.0f
                               else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        BikeRepository.addObserver(this)
        onBikeUpdate()

        val missing = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) requestPermissions.launch(permissions) else BleService.start(this)

        // Opening the app is the clearest signal there is that the link is
        // wanted now. Without this the scan stays in low power from a search
        // that started while the phone was in a pocket, and the rider waits
        // minutes beside a bike that is advertising the whole time.
        BleService.scanHarderNow()
        // The garments get the same treatment: an active look, once, when the
        // rider opens the app.
        Keis.scanHarderNow(this)
    }

    override fun onStop() {
        HandlebarButtons.onHeatGesture = null
        BikeRepository.removeObserver(this)
        super.onStop()
        // The service is NOT stopped here — that is the whole point of it. The
        // rider dismisses the notification, or uses the app's own exit, when the
        // ride is over.
    }

    /**
     * The cluster takes the whole screen, both ways up.
     *
     * Landscape is where it was found. Measured on the X70, 393 dp tall on its
     * side: the status bar and the navigation bar take 72 of it, the tab row 46,
     * the link strip 26, the fragment padding 16 and the tell-tale row 66 —
     * leaving about 167 dp for the two dials the page exists for. Both gauges
     * size on min(width, height) and draw every caption off that size, so a
     * short dial does not just look small, it crowds. Reclaiming the two bars
     * puts the dials near 240, and costs no layout at all.
     *
     * Portrait was left alone at first on the grounds that it is not squeezed,
     * which was answering the wrong question. It is the same app doing the same
     * job: a phone on a handlebar mount running an instrument cluster with the
     * screen forced on is not a phone at that moment, and a notification bar
     * across the top of it is clutter whichever way up it is. Two behaviours is
     * also one more thing to have learnt. So both.
     *
     * TRANSIENT_BARS_BY_SWIPE rather than a hard hide, which is what makes that
     * defensible: a swipe from the edge still brings the bars back for a few
     * seconds, so the clock and the back gesture are a gesture away rather than
     * gone. They then hide themselves again, which is what onWindowFocusChanged
     * below is for — returning from settings or the notification shade otherwise
     * leaves them up. Settings, diagnostics and about are separate activities
     * and keep their bars, because those ARE a phone.
     */
    private fun applyImmersive() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onBikeUpdate() {
        // Signal strength alongside the status: a link that is about to drop
        // looks exactly like a healthy one until it does, and -90 dBm on the
        // status line is the only warning available.
        // The strip draws the number and the bars itself, so it is handed the
        // facts rather than a sentence someone has already formatted.
        statusView.rssi = BikeRepository.rssi
        statusView.live = BikeRepository.isLive
        statusView.status = if (BikeRepository.isLive) "Linked" else BikeRepository.status
    }

    private fun highlightTab(active: Int) {
        tabs.forEachIndexed { i, tab ->
            tab.setTextColor(
                ContextCompat.getColor(this, if (i == active) R.color.accent else R.color.muted)
            )
        }
    }
}

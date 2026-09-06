package dk.agesen.springfield

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * What this thing is, reached by pressing the maker's plate in settings.
 *
 * The owner asked for it when the plate came off the machine page: it showed on
 * the splash, at the foot of the machine page and at the foot of settings, which
 * was three times in one app. One of those is a signature; three is a habit.
 *
 * Everything here is either fixed fact or read live from the motorcycle. Nothing
 * is decoration -- the hardware line answers "what is fitted", the bus line
 * answers "how does it read it", and the listen-only line answers the question
 * that matters most to anyone else thinking of building one.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val appVer = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }

        // No VIN here. It is deliberately MQTT-only in the firmware and never
        // reaches this app, so asking for it would not compile -- and printing an
        // identifier on a screen anyone can be handed is not obviously wanted.
        val fw = BikeRepository.state?.fw ?: "not reported"

        findViewById<TextView>(R.id.about).text = buildString {
            appendLine("SPRINGFIELD")
            appendLine("© Nanna Agesen 2026")
            appendLine()
            appendLine("MIT licence. Use it, change it, fit it to your own")
            appendLine("motorcycle. It comes with no warranty of any kind.")
            appendLine()
            appendLine("──────────────────────────────────────")
            appendLine("THE MACHINE")
            appendLine("  2017 Indian Springfield")
            appendLine("  Thunder Stroke 111, air-cooled V-twin")
            appendLine()
            appendLine("THE HARDWARE")
            appendLine("  LilyGO T-2CANFD")
            appendLine("  ESP32-S3 with an MCP2518FD CAN controller")
            appendLine("  Wired to the bus, powered from the bike")
            appendLine()
            appendLine("IT CANNOT TALK BACK")
            appendLine("  The CAN controller runs in hardware")
            appendLine("  listen-only mode. It is not that the code")
            appendLine("  chooses not to transmit -- the peripheral is")
            appendLine("  configured so that it cannot, and it does not")
            appendLine("  even acknowledge frames. Nothing here can send")
            appendLine("  a command, clear a fault or change a setting.")
            appendLine()
            appendLine("THE BUS")
            appendLine("  SAE J1939 at 250 kbit/s")
            appendLine("  Decoded on the ESP32, published as one JSON")
            appendLine("  state message")
            appendLine()
            appendLine("THE LINK")
            appendLine("  Bluetooth LE straight to this phone")
            appendLine("  MQTT over WiFi to openHAB at home")
            appendLine()
            appendLine("──────────────────────────────────────")
            appendLine("VERSIONS")
            appendLine("  app       $appVer")
            appendLine("  firmware  $fw")
            appendLine()
            appendLine("  github.com/Prinsessen/")
            appendLine("      indian-thunderstroke-canbus")
        }
    }
}

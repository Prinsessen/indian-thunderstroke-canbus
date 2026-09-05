package dk.agesen.springfield

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * The real Keis driver.
 *
 * The protocol came from the manufacturer's own app rather than from guesswork:
 * every constant below is lifted from `KeisDevice` in Keis iControl, so the
 * bytes sent here are ones the controller is known to accept. That is a stronger
 * warrant than a packet capture — a capture shows what was sent once, the
 * constants show what the firmware was written to take.
 *
 * The protocol is a single byte written to one characteristic, and the values
 * are ASCII digits: `'0'` off, `'2'` low, `'4'` medium, `'6'` high. Whoever
 * designed it clearly meant it to be readable on a terminal, and it is.
 *
 * **There is no BLE bonding.** The "press the button on the controller" step in
 * the manual is an application-level handshake (`0xA0` / `0xA1`) used only when
 * a controller is first added in their app. An already-added controller accepts
 * a plain connection, which is why this driver has no pairing code at all.
 */
class KeisBleDevice(
    override val zone: HeatCurve.Zone,
    private val address: String
) : KeisDevice {

    companion object {
        val SERVICE: UUID = UUID.fromString("00035b03-58e6-07dd-021a-08123a000300")
        val CHARACTERISTIC: UUID = UUID.fromString("00035b03-58e6-07dd-021a-08123a000301")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Every Keis controller so far. Their own app filters on this. */
        const val MAC_PREFIX = "00:1E:C0:5"
        const val ADVERTISED_NAME = "KEIS HEATED CLOTHING"

        /** Ask the controller what level it is on. */
        private const val CMD_GET_STATE: Byte = 0x37

        /** 0xFF in a state reply means off, which is not one of the level bytes. */
        private const val STATE_OFF: Int = 0xFF

        /** Only used while Bluetooth itself is off; the link needs no polling. */
        private const val ADAPTER_RETRY_MS = 8_000L

        fun byteFor(level: HeatCurve.Level): Byte = when (level) {
            HeatCurve.Level.OFF -> 0x30
            HeatCurve.Level.LOW -> 0x32
            HeatCurve.Level.MEDIUM -> 0x34
            HeatCurve.Level.HIGH -> 0x36
        }

        fun levelFor(b: Int): HeatCurve.Level? = when (b and 0xFF) {
            0x30 -> HeatCurve.Level.OFF
            0x32 -> HeatCurve.Level.LOW
            0x34 -> HeatCurve.Level.MEDIUM
            0x36 -> HeatCurve.Level.HIGH
            else -> null
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var appContext: Context? = null

    @Volatile override var connected: Boolean = false
        private set

    @Volatile override var level: HeatCurve.Level? = null
        private set

    /** The controller does not report a battery level; the app must not invent one. */
    override val batteryPct: Int? = null

    @SuppressLint("MissingPermission")
    override fun connect(context: Context) {
        appContext = context.applicationContext
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) {
            main.postDelayed({ appContext?.let { connect(it) } }, ADAPTER_RETRY_MS)
            return
        }
        if (gatt != null) return          // already waiting or connected
        val device = try { adapter.getRemoteDevice(address) } catch (e: Exception) { null } ?: return
        RideLog.add("keis: waiting for $zone at $address")

        // autoConnect = true, and this is the whole reason.
        //
        // The garments are not always worn — sometimes the jacket, sometimes the
        // trousers, often neither — so a driver that polls would spend a ride
        // failing to reach a jacket hanging in a wardrobe, once every few
        // seconds, for nothing. autoConnect hands that to the Bluetooth stack as
        // a standing request: connect whenever this device appears. It costs
        // almost nothing while the garment is absent and connects by itself when
        // it is switched on, which is exactly the behaviour wanted.
        gatt = device.connectGatt(context, true, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Connect now, having just seen this controller advertise.
     *
     * autoConnect is a standing request served by the stack's low duty-cycle
     * background scan. It is the right thing for a garment that might be
     * switched on halfway through a ride — it costs almost nothing while the
     * garment is absent. It is the wrong thing for a controller that has just
     * been plugged in, because a short advertising window can pass entirely
     * between two sweeps of a scan that is mostly asleep.
     *
     * That is the likeliest reason a freshly powered controller could not be
     * reached until its button was pressed: pressing it makes it advertise
     * continuously, so the lazy scan eventually catches it. The radio was
     * probably on the whole time. Reported by the rider, who was right to doubt
     * the explanation that it was not.
     *
     * A direct connect (autoConnect = false) is fast and decisive, and only
     * worth spending when something has actually been seen.
     */
    @SuppressLint("MissingPermission")
    override fun connectSeenNow(context: Context) {
        if (connected) return
        appContext = context.applicationContext
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val device = try { manager.adapter?.getRemoteDevice(address) } catch (e: Exception) { null } ?: return
        // The pending autoConnect request has to go, or the stack is left with
        // two intentions for one device.
        gatt?.close()
        RideLog.add("keis: $zone seen advertising — connecting directly")
        gatt = device.connectGatt(context, false, callback,
                                  android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        gatt?.close()
        gatt = null
        connected = false
    }

    @SuppressLint("MissingPermission")
    override fun setLevel(level: HeatCurve.Level) {
        val g = gatt ?: return
        val chr = g.getService(SERVICE)?.getCharacteristic(CHARACTERISTIC) ?: return
        val payload = byteArrayOf(byteFor(level))

        // Only ever one of the four constants taken from their own app. Nothing
        // computes a byte here, and nothing interpolates — a heating element is
        // not somewhere to find out that a scale was not linear.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(chr, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            run {
                chr.value = payload
                chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(chr)
            }
        }
        // Remember what we sent. Without this the controller's own notification
        // of the level we just wrote looks like a level the app did not ask for
        // — which is how the rider gets credited with a button press they never
        // made, and the zone latches into manual behind their back.
        this.level = level
        RideLog.add("keis: $zone -> ${level.label}${Keis.reasonFor(zone)}")
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connected = true
                g.discoverServices()
            } else {
                connected = false
                level = null
                // Deliberately NOT closed. Closing cancels the standing
                // autoConnect request, and with it the whole point: the link
                // should re-establish by itself when the garment is switched on
                // again, without the app doing anything.
                RideLog.add("keis: $zone not present ($status) — waiting")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val chr = g.getService(SERVICE)?.getCharacteristic(CHARACTERISTIC)
            if (chr == null) {
                RideLog.add("keis: $zone has no Keis service — wrong device?")
                return
            }
            g.setCharacteristicNotification(chr, true)
            chr.getDescriptor(CCCD)?.let { cccd ->
                val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, enable)
                } else {
                    @Suppress("DEPRECATION")
                    run { cccd.value = enable; g.writeDescriptor(cccd) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // Subscribed: ask what level it is already on, so the app adopts the
            // controller's state rather than imposing one the moment it connects.
            val chr = g.getService(SERVICE)?.getCharacteristic(CHARACTERISTIC) ?: return
            val payload = byteArrayOf(CMD_GET_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(chr, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                run { chr.value = payload; g.writeCharacteristic(chr) }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) = handle(value)

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) handle(c.value ?: return)
        }
    }

    /**
     * A reply carries the level in byte 0, and for a state query the current
     * level in byte 1 — where `0xFF` means off rather than one of the level
     * bytes, which is the one irregularity in an otherwise tidy protocol.
     */
    private fun handle(value: ByteArray) {
        if (value.isEmpty()) return
        val reported = when {
            value.size >= 2 && (value[0].toInt() and 0xFF) == 0x37 ->
                if ((value[1].toInt() and 0xFF) == STATE_OFF) HeatCurve.Level.OFF
                else levelFor(value[1].toInt())
            else -> levelFor(value[0].toInt())
        }
        if (reported != null && reported != level) {
            level = reported
            RideLog.add("keis: $zone reports ${reported.label}")
            Keis.onDeviceReport(zone, reported)
        }
    }
}

/**
 * Finding the controllers.
 *
 * Filtered in code rather than by service UUID, because the controllers do not
 * advertise their service — their own app finds them by MAC prefix, and so does
 * this. Both a jacket and a pair of trousers look identical over the air, so
 * the rider assigns which is which and the app remembers the addresses.
 */
object KeisScanner {

    data class Found(val address: String, val name: String?)

    private const val SCAN_MS = 8_000L

    /**
     * Look for these exact addresses, and nothing else.
     *
     * [scan] guesses at what a Keis controller looks like, because when a rider
     * is assigning one the app does not yet know its address. That guess is a
     * manufacturer MAC prefix and a name — and the prefix is wrong for at least
     * one batch of hardware: these controllers are 70:B3:D5, not 00:1E:C0:5. So
     * the discovery heuristic was quietly finding nothing, and an attempt to
     * settle whether a freshly powered controller advertises at all returned
     * "scan found nothing" every time while proving precisely nothing.
     *
     * Once the addresses are known there is no reason to guess. A ScanFilter on
     * the address is exact, and low-latency mode is what a scan started because
     * someone opened the app should be using.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun scanFor(context: Context, addresses: List<String>, onSeen: (String) -> Unit) {
        if (addresses.isEmpty()) return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return

        val filters = addresses.map {
            android.bluetooth.le.ScanFilter.Builder().setDeviceAddress(it.uppercase()).build()
        }
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val hits = mutableSetOf<String>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, result: android.bluetooth.le.ScanResult) {
                result.device.address?.let { if (hits.add(it.uppercase())) onSeen(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                RideLog.add("keis: scan failed ($errorCode)")
            }
        }
        scanner.startScan(filters, settings, cb)
        Handler(Looper.getMainLooper()).postDelayed({
            try { scanner.stopScan(cb) } catch (e: Exception) { }
            if (hits.isEmpty()) RideLog.add("keis: no controller advertising")
        }, SCAN_MS)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun scan(context: Context, onDone: (List<Found>) -> Unit) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) { onDone(emptyList()); return }

        val found = linkedMapOf<String, Found>()
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, result: android.bluetooth.le.ScanResult) {
                val address = result.device.address ?: return
                val name = result.device.name ?: result.scanRecord?.deviceName
                val looksRight = address.uppercase().startsWith(KeisBleDevice.MAC_PREFIX) ||
                        name?.contains("KEIS", ignoreCase = true) == true
                if (looksRight) found[address] = Found(address, name)
            }
        }

        val handler = Handler(Looper.getMainLooper())
        scanner.startScan(callback)
        handler.postDelayed({
            try { scanner.stopScan(callback) } catch (e: Exception) { }
            onDone(found.values.toList())
        }, SCAN_MS)
    }
}

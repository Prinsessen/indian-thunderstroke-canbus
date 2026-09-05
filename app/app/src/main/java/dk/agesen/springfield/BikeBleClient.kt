package dk.agesen.springfield

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * BLE client for the Springfield CAN interface.
 *
 * Spec: indian-canbus/PROTOCOL.md. Two things in here are not optional and are
 * the usual reasons a hand-rolled client "connects but shows nothing":
 *
 *  1. **The MTU must be raised before subscribing to `state`.** The default ATT
 *     MTU is 23 bytes, capping a notification at 20 — the ~400-byte JSON would
 *     arrive silently truncated, as a fragment, with no error anywhere.
 *  2. **GATT operations must be serialised.** Android runs one at a time; firing
 *     two descriptor writes back to back means the second is dropped on the
 *     floor, so one characteristic notifies and the other never does. Hence the
 *     small operation queue below.
 *
 * Permissions (BLUETOOTH_SCAN / BLUETOOTH_CONNECT) are the caller's job — see
 * MainActivity. Every call here is annotated accordingly.
 */
class BikeBleClient(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatus(text: String)
        fun onFast(packet: FastPacket)
        fun onState(state: BikeJsonState)
    }

    companion object {
        private const val TAG = "BikeBle"

        val SERVICE_UUID: UUID = UUID.fromString("5f6d0000-9b2a-4c31-8f0e-2a7c1d3e4b50")
        val CHR_FAST_UUID: UUID = UUID.fromString("5f6d0001-9b2a-4c31-8f0e-2a7c1d3e4b50")
        val CHR_STATE_UUID: UUID = UUID.fromString("5f6d0002-9b2a-4c31-8f0e-2a7c1d3e4b50")

        /**
         * The one writable characteristic: the odometer at the last service.
         *
         * Four bytes, little-endian int32. Binary rather than text because
         * there is exactly one way to read four bytes and several ways to read
         * "12 000" — and this is the only value the phone can change on the
         * bike, so it is the only one where a misread would persist.
         */
        val CHR_SERVICE_UUID: UUID = UUID.fromString("5f6d0003-9b2a-4c31-8f0e-2a7c1d3e4b50")

        /** Standard Client Characteristic Configuration Descriptor. */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MTU_REQUEST = 517

        /**
         * Scanning is the expensive part of this app, not the connection.
         * LOW_LATENCY finds the bike in a second or two and is right while you
         * are walking up to it — but left running it is the most power-hungry
         * mode the radio has, and a phone in a jacket pocket a mile from the
         * garage would scan that way all day. After this long without a link we
         * drop to LOW_POWER and space the retries out.
         */
        private const val AGGRESSIVE_SCAN_MS = 45_000L
        private const val RETRY_NEAR_MS = 2_000L
        private const val RETRY_FAR_MS = 20_000L

        /** How often to ask for the link's signal strength while connected. */
        private const val RSSI_INTERVAL_MS = 2_000L

        /**
         * Pairing failures before the app stops blaming itself and says what is
         * actually wrong.
         *
         * One failure is a mistyped passkey. Repeated ones almost always mean
         * the two ends disagree about a stored link key — the phone kept a bond
         * the board lost, usually to a factory flash — and no amount of retrying
         * fixes that. Only forgetting the device does.
         */
        private const val PAIRING_FAILURES_BEFORE_ADVICE = 2
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var scanning = false

    /** When we last had a working link — drives the scan backoff. */
    private var lastConnectedAt = System.currentTimeMillis()

    private var pairingFailures = 0

    private val rssiPoll = object : Runnable {
        // The annotation belongs on the function; it is not applicable to a bare
        // expression, which is what it was sitting on.
        @SuppressLint("MissingPermission")
        override fun run() {
            gatt?.let {
                it.readRemoteRssi()
                main.postDelayed(this, RSSI_INTERVAL_MS)
            }
        }
    }

    private val searchingLongEnough: Boolean
        get() = System.currentTimeMillis() - lastConnectedAt > AGGRESSIVE_SCAN_MS

    /**
     * Someone is looking at the screen — scan hard again.
     *
     * After 45 seconds the scan drops to low power to save battery, which is
     * right while the phone is in a pocket and the bike is in a garage. It is
     * wrong the moment the rider opens the app: low-power scanning has a duty
     * cycle measured in seconds off for every fraction of a second on, and a
     * ride log showed seven minutes between "Scanning" and "Found the bike".
     *
     * Nobody stands beside a motorcycle for seven minutes. Treating the app
     * coming to the foreground as a fresh start costs one aggressive window and
     * buys back the case the whole link exists for.
     */
    fun scanHarderNow() {
        if (!searchingLongEnough) return
        lastConnectedAt = System.currentTimeMillis()
        if (gatt == null) { stop(); start() }
    }

    /** Pending GATT operations. Android services exactly one at a time. */
    private val pending = ArrayDeque<() -> Unit>()
    private var busy = false

    private fun enqueue(op: () -> Unit) {
        pending.addLast(op)
        if (!busy) next()
    }

    private fun next() {
        val op = pending.removeFirstOrNull()
        if (op == null) {
            busy = false
            return
        }
        busy = true
        op()
    }

    /**
     * Named `report`, not `status`: several BluetoothGattCallback methods take an
     * Int parameter called `status`, which would shadow a same-named method and
     * fail to compile at every call site inside them.
     */
    private fun report(text: String) {
        Log.i(TAG, text)
        RideLog.add(text)
        main.post { listener.onStatus(text) }
    }

    // ---------------------------------------------------------------- scanning

    @SuppressLint("MissingPermission")
    fun start() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            // Keep asking. Without this the app was dead until it was restarted:
            // nothing else calls start() when there is no connection to lose, so
            // turning Bluetooth off once — or launching with it off — was
            // permanent as far as the app was concerned.
            report("Bluetooth is off — waiting")
            main.postDelayed({ start() }, RETRY_FAR_MS)
            return
        }
        if (scanning) return

        // Filter on the service UUID rather than the name: the firmware puts the
        // UUID in the advertising packet, and a name filter breaks the moment
        // BLE_DEVICE_NAME is changed.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(
                if (searchingLongEnough) ScanSettings.SCAN_MODE_LOW_POWER
                else ScanSettings.SCAN_MODE_LOW_LATENCY
            )
            .build()

        scanning = true
        report(if (searchingLongEnough) "Searching (low power)…" else "Scanning…")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        main.removeCallbacks(rssiPoll)
        BikeRepository.setRssi(null)
        if (scanning) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            scanning = false
        }
        gatt?.close()
        gatt = null
        pending.clear()
        busy = false
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device
            adapter?.bluetoothLeScanner?.stopScan(this)
            scanning = false
            lastConnectedAt = System.currentTimeMillis()
            // The MAC address means nothing to a rider; there is only ever one
            // bike, and the scan filter is on the service UUID.
            report("Found the bike, connecting…")
            // TRANSPORT_LE explicitly: without it Android may try BR/EDR on a
            // dual-mode-looking address and simply fail on an S3, which is
            // BLE-only.
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            report("Scan failed ($errorCode)")
        }
    }

    // ------------------------------------------------------------------- gatt

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                report("Connected, discovering services…")
                g.discoverServices()
            } else {
                // 0x216 (534) is our own drop after a failed pairing; the
                // firmware disconnects rather than leaving an unencrypted link
                // open. Two of those in a row is a key mismatch, not a typo.
                if (status == 0x216) pairingFailures++
                report(
                    if (pairingFailures >= PAIRING_FAILURES_BEFORE_ADVICE)
                        "Pairing refused — forget \"Springfield\" in Bluetooth settings"
                    else "Disconnected (status $status)"
                )
                main.removeCallbacks(rssiPoll)
                BikeRepository.setRssi(null)
                pending.clear()
                busy = false
                g.close()
                gatt = null
                // Come back on our own: the bike goes out of range constantly.
                // The gap widens once it is clear the bike simply is not there.
                main.postDelayed({ start() }, if (searchingLongEnough) RETRY_FAR_MS else RETRY_NEAR_MS)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                report("Service discovery failed ($status)")
                return
            }
            // MTU first, subscriptions after — see the class comment.
            enqueue { g.requestMtu(MTU_REQUEST) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            BikeRepository.setMtu(mtu)
            report("MTU $mtu")
            if (mtu < 64) {
                // Not fatal for `fast`, but `state` JSON will arrive truncated
                // and BikeJsonState.parse() will return null on every notify.
                report("MTU $mtu is too small — state JSON will be truncated")
            }
            next()

            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                report("Service not found")
                return
            }
            service.getCharacteristic(CHR_FAST_UUID)?.let { subscribe(g, it) }
            service.getCharacteristic(CHR_STATE_UUID)?.let { subscribe(g, it) }

            main.removeCallbacks(rssiPoll)
            main.postDelayed(rssiPoll, RSSI_INTERVAL_MS)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) BikeRepository.setRssi(rssi)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // GATT_INSUFFICIENT_AUTHENTICATION (5) here is expected on the
                // very first connection: the characteristics require an
                // encrypted+authenticated link, so Android raises the pairing
                // prompt and the write is retried once bonded. If the prompt
                // does not appear, look in the notification shade.
                report("CCCD write failed ($status) — pairing may be required")
            } else {
                // A subscription got through, so the link is genuinely paired.
                pairingFailures = 0
            }
            next()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                report("service write refused by the link ($status)")
            }
            // Releases the queue either way: a failed write that never called
            // next() would wedge every operation behind it.
            next()
        }

        // --- notifications: two overrides, because the signature changed -----
        // API 33+ delivers the value as a parameter; below that it lives on the
        // characteristic. Implementing only one silently misses data on half the
        // phones out there.

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            dispatch(characteristic.uuid, value)
        }

        // Pre-API-33 signature, still the one delivered on Android 12.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                dispatch(characteristic.uuid, characteristic.value ?: return)
            }
        }
    }

    /**
     * Tell the bike it has been serviced at this odometer reading.
     *
     * Queued like everything else — Android runs one GATT operation at a time,
     * and a write fired while a subscription is still settling is a write that
     * quietly never happens.
     *
     * Nothing is reported back from here. The bike republishes `svcKm` in its
     * next state frame, and the app reads the answer from there: if the write
     * was refused as implausible, what comes back is what the bike still holds,
     * not what the phone asked for. Confirming a write by echoing the value you
     * sent proves only that you sent it.
     */
    @SuppressLint("MissingPermission")
    fun writeServiceKm(km: Int): String? {
        val g = gatt ?: return "not connected to the bike right now"
        // Android caches a bonded device's service database and does not
        // rediscover it on its own. The characteristic was added by a firmware
        // update after this phone bonded, so the phone is still working from a
        // list that predates it — the link is up, the service is there, and this
        // one entry is simply missing. Forgetting the device in Bluetooth
        // settings and pairing again is the cure, and it is worth saying so
        // rather than reporting "not reachable" for a bike that plainly is.
        val chr = g.getService(SERVICE_UUID)?.getCharacteristic(CHR_SERVICE_UUID)
            ?: return "the phone's Bluetooth cache predates this firmware — " +
                      "forget \"Springfield\" in Bluetooth settings and pair again"
        val payload = byteArrayOf(
            (km and 0xFF).toByte(),
            ((km shr 8) and 0xFF).toByte(),
            ((km shr 16) and 0xFF).toByte(),
            ((km shr 24) and 0xFF).toByte()
        )
        enqueue {
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
        }
        report("service recorded at $km km — sent to the bike")
        return null
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(g: BluetoothGatt, chr: BluetoothGattCharacteristic) {
        enqueue {
            g.setCharacteristicNotification(chr, true)
            val cccd = chr.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                report("No CCCD on ${chr.uuid}")
                next()
                return@enqueue
            }
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = enable
                    g.writeDescriptor(cccd)
                }
            }
        }
    }

    private fun dispatch(uuid: UUID, value: ByteArray) {
        when (uuid) {
            CHR_FAST_UUID -> {
                BikeRepository.setRaw(value, null)
                FastPacket.parse(value)?.let { p -> main.post { listener.onFast(p) } }
            }
            CHR_STATE_UUID -> {
                val text = String(value, Charsets.UTF_8)
                BikeRepository.setRaw(null, text)
                val parsed = BikeJsonState.parse(text)
                if (parsed == null) {
                    report("Unparseable state JSON (${value.size} B) — MTU too small?")
                } else {
                    main.post { listener.onState(parsed) }
                }
            }
        }
    }
}

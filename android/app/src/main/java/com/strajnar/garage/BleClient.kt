package com.strajnar.garage

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class BleException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DeviceState(val relayOn: Boolean, val garageBusy: Boolean)

/**
 * Talks to the garage server over BLE. One connection is kept open while the app is visible.
 * Each command reads a fresh challenge and writes cmd|arg|tag on the same connection.
 */
@SuppressLint("MissingPermission") // The UI asks for BLUETOOTH_SCAN/CONNECT before calling in.
class GarageBle(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val lock = Mutex()
    private var link: GattLink? = null
    private var device: BluetoothDevice? = null // last scan result, reused to skip scanning

    val isConnected: Boolean get() = link?.isOpen == true

    /** Sends one authenticated command and returns the state afterwards. */
    suspend fun command(pairing: Pairing, cmd: Byte, arg: Byte): DeviceState = lock.withLock {
        val (l, challenge) = linkWithChallenge(pairing)
        val payload = Protocol.command(pairing.key, challenge, cmd, arg)
        val status = try {
            l.write(COMMAND, payload)
        } catch (e: BleException) {
            dropLink()
            // The write may or may not have reached the Pi, so never retry it automatically
            // (a second garage pulse would reverse the door).
            throw BleException("Connection lost while sending; check whether it worked", e)
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw BleException(describeStatus(status))
        readStateFrom(l)
    }

    suspend fun readState(pairing: Pairing): DeviceState = lock.withLock {
        val reused = link?.takeIf { it.isOpen }
        if (reused != null) {
            try {
                return@withLock readStateFrom(reused)
            } catch (e: BleException) {
                dropLink()
            }
        }
        readStateFrom(connect(pairing))
    }

    /** Forget the cached device (after re-pairing with a possibly different Pi). */
    suspend fun reset() = lock.withLock {
        dropLink()
        device = null
    }

    suspend fun disconnect() = lock.withLock { dropLink() }

    fun closeNow() {
        link?.close()
        link = null
    }

    /** Reads a challenge, reconnecting once if a reused connection turned out to be stale. */
    private suspend fun linkWithChallenge(pairing: Pairing): Pair<GattLink, ByteArray> {
        val reused = link?.takeIf { it.isOpen }
        if (reused != null) {
            try {
                return reused to readChallenge(reused)
            } catch (e: BleException) {
                dropLink() // Nothing was sent yet, so retrying on a new connection is safe.
            }
        }
        val l = connect(pairing)
        return l to readChallenge(l)
    }

    private suspend fun readChallenge(l: GattLink): ByteArray {
        val (status, value) = l.read(CHALLENGE)
        if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.size != 16) {
            throw BleException("Could not get a challenge from the garage (status $status)")
        }
        return value
    }

    private suspend fun readStateFrom(l: GattLink): DeviceState {
        val (status, value) = l.read(STATE)
        if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.size < 2) {
            throw BleException("Could not read the relay state (status $status)")
        }
        return DeviceState(relayOn = value[0].toInt() != 0, garageBusy = value[1].toInt() != 0)
    }

    private suspend fun connect(pairing: Pairing): GattLink {
        val adapter = manager?.adapter ?: throw BleException("This phone has no Bluetooth")
        if (!adapter.isEnabled) throw BleException("Bluetooth is off")
        val target = device ?: scan(pairing)
        val l = GattLink(context, target)
        try {
            l.open()
        } catch (e: BleException) {
            device = null // Rescan next time; the cached device may be stale.
            throw e
        }
        device = target
        link = l
        return l
    }

    private suspend fun scan(pairing: Pairing): BluetoothDevice {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: throw BleException("Bluetooth is off")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).apply {
            pairing.address?.let { setDeviceAddress(it) }
        }.build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val found = CompletableDeferred<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(BleException("Bluetooth scan failed (error $errorCode)"))
            }
        }
        scanner.startScan(listOf(filter), settings, callback)
        try {
            return withTimeoutOrNull(SCAN_TIMEOUT_MS) { found.await() }
                ?: throw BleException("Garage not found. Is it in range and running?")
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    private fun dropLink() {
        link?.close()
        link = null
    }

    companion object {
        // Must match server/ble.go.
        val SERVICE: UUID = UUID.fromString("5f6d0001-8c2b-4e3a-9a41-2b7c0e9d4a10")
        val CHALLENGE: UUID = UUID.fromString("5f6d0002-8c2b-4e3a-9a41-2b7c0e9d4a10")
        val COMMAND: UUID = UUID.fromString("5f6d0003-8c2b-4e3a-9a41-2b7c0e9d4a10")
        val STATE: UUID = UUID.fromString("5f6d0004-8c2b-4e3a-9a41-2b7c0e9d4a10")
        private const val SCAN_TIMEOUT_MS = 10_000L

        fun describeStatus(status: Int): String = when (status) {
            0x80 -> "Rejected: authentication failed. Scan the pairing QR again."
            0x81 -> "The garage door is already moving"
            0x82 -> "The garage controller reported a hardware error"
            0x06, 0x0d -> "App and server versions don't match (status $status)"
            else -> "Command failed (status $status)"
        }
    }
}

/** One GATT connection. Callbacks run on a private thread; one operation at a time. */
@SuppressLint("MissingPermission")
private class GattLink(private val context: Context, private val device: BluetoothDevice) {
    private val thread = HandlerThread("garage-gatt").apply { start() }
    private val handler = Handler(thread.looper)
    private val connected = CompletableDeferred<Unit>()
    private val opLock = Mutex()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var pending: CompletableDeferred<Pair<Int, ByteArray?>>? = null
    @Volatile private var closed = false
    private val chars = mutableMapOf<UUID, BluetoothGattCharacteristic>()

    val isOpen: Boolean get() = connected.isCompleted && !closed

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val e = BleException("Disconnected from the garage (status $status)")
                connected.completeExceptionally(e)
                pending?.completeExceptionally(e)
                close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(status to null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(status to value.copyOf())
        }

        @Deprecated("Used before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) pending?.complete(status to c.value?.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status to null)
        }
    }

    suspend fun open() {
        try {
            @Suppress("DEPRECATION") // The settings-object overload is API 37+.
            val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
                ?: throw BleException("Bluetooth refused to connect")
            gatt = g
            withTimeout(10_000) { connected.await() }
            val (status, _) = operation { g.discoverServices() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw BleException("Service discovery failed (status $status)")
            val svc = g.getService(GarageBle.SERVICE) ?: throw BleException("That device is not a garage controller")
            for (u in listOf(GarageBle.CHALLENGE, GarageBle.COMMAND, GarageBle.STATE)) {
                chars[u] = svc.getCharacteristic(u) ?: throw BleException("Garage service is missing $u")
            }
        } catch (e: Throwable) {
            close()
            throw when (e) {
                is TimeoutCancellationException -> BleException("Timed out connecting to the garage", e)
                is SecurityException -> BleException("Bluetooth permission missing", e)
                else -> e
            }
        }
    }

    suspend fun read(uuid: UUID): Pair<Int, ByteArray?> {
        val g = gatt ?: throw BleException("Not connected")
        val c = chars.getValue(uuid)
        return operation { g.readCharacteristic(c) }
    }

    suspend fun write(uuid: UUID, value: ByteArray): Int {
        val g = gatt ?: throw BleException("Not connected")
        val c = chars.getValue(uuid)
        return operation {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }.first
    }

    /** Runs one GATT operation; [start] returns false if Android refused to start it. */
    private suspend fun operation(start: () -> Boolean): Pair<Int, ByteArray?> = opLock.withLock {
        if (closed) throw BleException("Not connected")
        val result = CompletableDeferred<Pair<Int, ByteArray?>>()
        pending = result
        try {
            if (!start()) throw BleException("Bluetooth was busy")
            withTimeout(5_000) { result.await() }
        } catch (e: TimeoutCancellationException) {
            throw BleException("The garage did not respond", e)
        } finally {
            pending = null
        }
    }

    fun close() {
        if (closed) return
        closed = true
        val g = gatt
        gatt = null
        pending?.completeExceptionally(BleException("Disconnected"))
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        thread.quitSafely()
    }
}

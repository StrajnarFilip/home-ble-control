package com.strajnar.garage

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BleException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DeviceState(val relayOn: Boolean, val garageBusy: Boolean)

/**
 * Talks to the garage server over BLE. [keepConnected] (run by [GarageService]) holds one
 * connection open whenever the garage is in range, so a command is just a challenge read and a
 * write. The server pushes state changes over that connection, whoever caused them.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_SCAN/CONNECT first.
class GarageBle(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val lock = Mutex() // One connection setup or command exchange at a time.
    @Volatile private var link: GattLink? = null
    private val background = AtomicReference<GattLink?>(null) // waiting for the garage to be in range
    private var device: BluetoothDevice? = null // last device we connected to

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _state = MutableStateFlow<DeviceState?>(null)

    /** Relay and garage state while connected, null otherwise. */
    val state: StateFlow<DeviceState?> = _state.asStateFlow()

    init {
        // When Bluetooth goes off, Android may never report the end of a pending background
        // connection, which would leave keepConnected waiting forever. Close everything instead.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (s == BluetoothAdapter.STATE_TURNING_OFF || s == BluetoothAdapter.STATE_OFF) closeNow()
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Sends one authenticated command. The new state arrives through [state]. */
    suspend fun command(pairing: Pairing, cmd: Byte, arg: Byte): Unit = lock.withLock {
        val (l, challenge) = linkWithChallenge(pairing)
        val payload = Protocol.command(pairing.key, challenge, cmd, arg)
        val status = try {
            l.write(COMMAND, payload)
        } catch (e: BleException) {
            dropLink()
            // The write may or may not have reached the Pi, so never retry it automatically
            // (a second garage pulse would reverse the door).
            throw BleException(text(R.string.err_lost_while_sending), e)
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw BleException(describeStatus(status))
        if (!l.notifies) readStateFrom(l) // Older server without notifications.
    }

    suspend fun readState(pairing: Pairing): Unit = lock.withLock {
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

    /**
     * Keeps a connection open until cancelled. While there is none, Android is asked to connect
     * in the background, which it does within a second or two of the garage coming into range.
     */
    suspend fun keepConnected(pairing: Pairing): Nothing {
        while (true) {
            try {
                val adapter = manager?.adapter
                if (adapter == null || !adapter.isEnabled) {
                    delay(RETRY_MS)
                    continue
                }
                val open = link?.takeIf { it.isOpen }
                if (open != null) {
                    open.awaitClosed()
                    continue
                }
                val target = device ?: pairing.address?.let { leDevice(adapter, it) }
                if (target == null) {
                    // No address to wait for, so look for the garage now and then instead.
                    try {
                        readState(pairing)
                    } catch (e: BleException) {
                        delay(SCAN_RETRY_MS)
                    }
                } else if (!backgroundConnect(target)) {
                    delay(RETRY_MS)
                }
            } catch (e: SecurityException) {
                delay(RETRY_MS) // Permission revoked; the UI asks for it again.
            }
        }
    }

    /** Returns false when the attempt failed (rather than being taken over by a command). */
    private suspend fun backgroundConnect(target: BluetoothDevice): Boolean {
        val l = lock.withLock {
            if (link?.isOpen == true) return true // A command connected in the meantime.
            newLink(target).also { background.set(it) }
        }
        var claimed = false
        var adopted = false
        try {
            l.connect(autoConnect = true, timeoutMs = null)
            lock.withLock {
                // A command that needed the connection sooner has replaced this attempt.
                if (!background.compareAndSet(l, null)) return true
                claimed = true
                l.setUp()
                if (!l.notifies) readStateFrom(l)
                adopt(l)
                adopted = true
            }
            return true
        } catch (e: BleException) {
            return !claimed && background.get() !== l
        } finally {
            if (!adopted) {
                background.compareAndSet(l, null)
                l.close()
            }
        }
    }

    /** Forget the cached device (after re-pairing with a possibly different Pi). */
    suspend fun reset() = lock.withLock {
        background.getAndSet(null)?.close()
        dropLink()
        device = null
    }

    fun closeNow() {
        background.getAndSet(null)?.close()
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
            throw BleException(text(R.string.err_no_challenge, status))
        }
        return value
    }

    private suspend fun readStateFrom(l: GattLink) {
        val (status, value) = l.read(STATE)
        val state = parseState(value)
        if (status != BluetoothGatt.GATT_SUCCESS || state == null) {
            throw BleException(text(R.string.err_read_state, status))
        }
        _state.value = state
    }

    /** Connects right away. Called with [lock] held, when a command can't wait for the background attempt. */
    private suspend fun connect(pairing: Pairing): GattLink {
        background.getAndSet(null)?.close()
        val adapter = manager?.adapter ?: throw BleException(text(R.string.err_no_bluetooth))
        if (!adapter.isEnabled) throw BleException(text(R.string.err_bluetooth_off))
        // With a known address, connect directly: Android picks up the advertisement itself, so
        // there is no separate scan to start, wait for and stop first.
        val direct = device ?: pairing.address?.let { leDevice(adapter, it) }
        if (direct != null) {
            try {
                return open(direct)
            } catch (e: BleException) {
                device = null
                // A timeout means the garage is out of reach; a scan would just time out as well.
                if (e.cause is TimeoutCancellationException) throw e
            }
        }
        return open(scan(pairing))
    }

    private suspend fun open(target: BluetoothDevice): GattLink {
        val l = newLink(target)
        l.connect(autoConnect = false, timeoutMs = CONNECT_TIMEOUT_MS)
        l.setUp()
        adopt(l)
        return l
    }

    private fun newLink(target: BluetoothDevice) = GattLink(
        context,
        target,
        onState = { _state.value = it },
        onClosed = { closed ->
            if (link === closed) {
                _connected.value = false
                _state.value = null
            }
        },
    )

    private fun adopt(l: GattLink) {
        device = l.device
        link = l
        _connected.value = l.isOpen // Not just true: it may have dropped already.
    }

    // The server advertises with the adapter's public address, which is what the QR carries.
    private fun leDevice(adapter: BluetoothAdapter, address: String): BluetoothDevice =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            adapter.getRemoteLeDevice(address, BluetoothDevice.ADDRESS_TYPE_PUBLIC)
        } else {
            adapter.getRemoteDevice(address)
        }

    private suspend fun scan(pairing: Pairing): BluetoothDevice {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: throw BleException(text(R.string.err_bluetooth_off))
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
                found.completeExceptionally(BleException(text(R.string.err_scan_failed, errorCode)))
            }
        }
        scanner.startScan(listOf(filter), settings, callback)
        try {
            return withTimeoutOrNull(SCAN_TIMEOUT_MS) { found.await() }
                ?: throw BleException(text(R.string.err_not_found))
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
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val RETRY_MS = 2_000L
        private const val SCAN_RETRY_MS = 30_000L

        fun parseState(value: ByteArray?): DeviceState? =
            if (value == null || value.size < 2) null
            else DeviceState(relayOn = value[0].toInt() != 0, garageBusy = value[1].toInt() != 0)
    }

    private fun describeStatus(status: Int): String = when (status) {
        0x80 -> text(R.string.err_auth)
        0x81 -> text(R.string.err_busy)
        0x82 -> text(R.string.err_hardware)
        0x06, 0x0d -> text(R.string.err_version, status)
        else -> text(R.string.err_command, status)
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)
}

/** One GATT connection. Callbacks run on a private thread; one operation at a time. */
@SuppressLint("MissingPermission")
private class GattLink(
    private val context: Context,
    val device: BluetoothDevice,
    private val onState: (DeviceState) -> Unit,
    private val onClosed: (GattLink) -> Unit,
) {
    private val thread = HandlerThread("garage-gatt").apply { start() }
    private val handler = Handler(thread.looper)
    private val connected = CompletableDeferred<Unit>()
    private val closedSignal = CompletableDeferred<Unit>()
    private val opLock = Mutex()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var pending: CompletableDeferred<Pair<Int, ByteArray?>>? = null
    private val closing = AtomicBoolean(false)
    private val closed: Boolean get() = closing.get()
    private val chars = mutableMapOf<UUID, BluetoothGattCharacteristic>()

    /** Whether the server pushes state changes (servers before notifications didn't). */
    var notifies = false
        private set

    val isOpen: Boolean get() = connected.isCompleted && !closed

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val e = BleException(text(R.string.err_disconnected_status, status))
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

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status to null)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == GarageBle.STATE) GarageBle.parseState(value)?.let(onState)
        }

        @Deprecated("Used before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && c.uuid == GarageBle.STATE) {
                GarageBle.parseState(c.value)?.let(onState)
            }
        }
    }

    /**
     * Opens the link. With [autoConnect], Android waits for the garage to come into range
     * (a cheap background scan), so pass no timeout; otherwise it connects as fast as it can.
     */
    suspend fun connect(autoConnect: Boolean, timeoutMs: Long?) {
        try {
            @Suppress("DEPRECATION") // The settings-object overload is API 37+.
            val g = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
                ?: throw BleException(text(R.string.err_refused))
            gatt = g
            if (closed) { // close() ran before gatt was set, so it couldn't close this one.
                runCatching { g.close() }
                throw BleException(text(R.string.err_disconnected))
            }
            if (timeoutMs == null) connected.await() else withTimeout(timeoutMs) { connected.await() }
        } catch (e: Throwable) {
            close()
            throw translate(e)
        }
    }

    /** Discovers the garage service and subscribes to state changes. */
    suspend fun setUp() {
        try {
            val g = gatt ?: throw BleException(text(R.string.not_connected))
            // Android's default connection interval is 30-50 ms. Keep it short for the whole
            // connection: discovery and every command take several round trips.
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            val (status, _) = operation { g.discoverServices() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw BleException(text(R.string.err_discovery, status))
            val svc = g.getService(GarageBle.SERVICE) ?: throw BleException(text(R.string.err_not_garage))
            for (u in listOf(GarageBle.CHALLENGE, GarageBle.COMMAND, GarageBle.STATE)) {
                chars[u] = svc.getCharacteristic(u) ?: throw BleException(text(R.string.err_missing_characteristic, u))
            }
            val state = chars.getValue(GarageBle.STATE)
            val cccd = state.getDescriptor(GarageBle.CCCD)
            if (state.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 && cccd != null) {
                g.setCharacteristicNotification(state, true)
                val (s, _) = operation { writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                notifies = s == BluetoothGatt.GATT_SUCCESS
            }
        } catch (e: Throwable) {
            close()
            throw translate(e)
        }
    }

    suspend fun awaitClosed() = closedSignal.await()

    suspend fun read(uuid: UUID): Pair<Int, ByteArray?> {
        val g = gatt ?: throw BleException(text(R.string.not_connected))
        val c = chars.getValue(uuid)
        return operation { g.readCharacteristic(c) }
    }

    suspend fun write(uuid: UUID, value: ByteArray): Int {
        val g = gatt ?: throw BleException(text(R.string.not_connected))
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

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }

    /** Runs one GATT operation; [start] returns false if Android refused to start it. */
    private suspend fun operation(start: () -> Boolean): Pair<Int, ByteArray?> = opLock.withLock {
        if (closed) throw BleException(text(R.string.not_connected))
        val result = CompletableDeferred<Pair<Int, ByteArray?>>()
        pending = result
        try {
            if (!start()) throw BleException(text(R.string.err_bluetooth_busy))
            withTimeout(5_000) { result.await() }
        } catch (e: TimeoutCancellationException) {
            throw BleException(text(R.string.err_no_response), e)
        } finally {
            pending = null
        }
    }

    private fun translate(e: Throwable): Throwable = when (e) {
        is TimeoutCancellationException -> BleException(text(R.string.err_connect_timeout), e)
        is SecurityException -> BleException(text(R.string.permission_missing), e)
        else -> e
    }

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        val g = gatt
        gatt = null
        val e = BleException(text(R.string.err_disconnected))
        connected.completeExceptionally(e)
        pending?.completeExceptionally(e)
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        thread.quitSafely()
        closedSignal.complete(Unit)
        onClosed(this)
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)
}

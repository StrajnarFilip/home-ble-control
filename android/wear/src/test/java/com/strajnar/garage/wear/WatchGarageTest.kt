package com.strajnar.garage.wear

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import com.strajnar.garage.GarageBle
import com.strajnar.garage.Pairing
import com.strajnar.garage.Protocol
import com.strajnar.garage.shared.R as SharedR
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothLeScanner
import java.time.Duration
import java.util.UUID

private const val KEY_HEX = "000102030405060708090a0b0c0d0e0f"
private const val ADDRESS = "AA:BB:CC:DD:EE:FF"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [FakeDevice::class, FakeGatt::class])
class WatchGarageTest {
    private val app = RuntimeEnvironment.getApplication()
    private val adapter: BluetoothAdapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private lateinit var garage: WatchGarage

    @Before
    fun setUp() {
        FakeServer.reset()
        shadowOf(adapter).setEnabled(true)
        garage = WatchGarage(app)
    }

    private fun pairWithAddress() =
        assertNull(garage.pair("""{"v":1,"key":"$KEY_HEX","addr":"$ADDRESS","name":"Garage"}"""))

    /** Runs the main thread until the press has finished. Bluetooth callbacks arrive on their own thread. */
    private fun awaitResult(of: WatchGarage = garage): Pulse {
        val end = System.nanoTime() + 5_000_000_000
        while (of.status.value.pulse == Pulse.Sending && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        return of.status.value.pulse
    }

    private fun scanner() = Shadow.extract<ShadowBluetoothLeScanner>(adapter.bluetoothLeScanner)

    @Test
    fun `a press writes one authenticated pulse and disconnects`() {
        pairWithAddress()
        garage.pulseGarage()

        assertEquals(Pulse.Sent, awaitResult())
        val written = FakeServer.writes.single()
        val expected = Protocol.command(KEY_HEX.hexToByteArray(), FakeServer.challenge, Protocol.CMD_PULSE_GARAGE, 0)
        assertArrayEquals(expected, written)
        assertEquals(listOf(ADDRESS), FakeServer.connections)
        assertTrue("the connection must not stay open", FakeServer.allClosed)
    }

    @Test
    fun `a lost connection during the write is reported and not retried`() {
        // A second pulse would reverse the door.
        pairWithAddress()
        FakeServer.dropOnWrite = true
        garage.pulseGarage()

        val result = awaitResult()
        assertEquals(Pulse.Failed(app.getString(SharedR.string.err_lost_while_sending)), result)
        assertEquals(1, FakeServer.writes.size)
        assertEquals(1, FakeServer.connections.size)
        assertTrue(FakeServer.allClosed)
    }

    @Test
    fun `a rejected pulse shows the reason and disconnects`() {
        pairWithAddress()
        FakeServer.writeStatus = 0x80
        garage.pulseGarage()

        assertEquals(Pulse.Failed(app.getString(SharedR.string.err_auth)), awaitResult())
        assertEquals(1, FakeServer.writes.size)
        assertTrue(FakeServer.allClosed)
    }

    @Test
    fun `a second press while sending is ignored`() {
        pairWithAddress()
        // The connection stays pending, so the second press is certain to land during the first.
        FakeServer.holdConnection = true
        garage.pulseGarage()
        assertEquals(Pulse.Sending, garage.status.value.pulse)
        garage.pulseGarage()
        FakeServer.releaseConnection()

        assertEquals(Pulse.Sent, awaitResult())
        assertEquals(1, FakeServer.writes.size)
        assertEquals(1, FakeServer.connections.size)
    }

    @Test
    fun `presses after the first each make their own connection`() {
        pairWithAddress()
        garage.pulseGarage()
        assertEquals(Pulse.Sent, awaitResult())
        garage.pulseGarage()
        assertEquals(Pulse.Sent, awaitResult())

        assertEquals(2, FakeServer.writes.size)
        assertEquals(2, FakeServer.connections.size)
        assertTrue(FakeServer.allClosed)
    }

    @Test
    fun `a key without an address scans once and remembers what it found`() {
        assertNull(garage.pair(KEY_HEX))
        assertNull(Pairing.load(app)!!.address)

        garage.pulseGarage()
        val end = System.nanoTime() + 5_000_000_000
        var callback: ScanCallback? = null
        while (callback == null && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            callback = scanner().scanCallbacks.firstOrNull()
            Thread.sleep(2)
        }
        assertNotNull("expected a scan", callback)
        @Suppress("DEPRECATION") // The replacement takes a dozen advertising details we don't use.
        callback!!.onScanResult(1, ScanResult(adapter.getRemoteDevice(ADDRESS), null, -50, 0))

        assertEquals(Pulse.Sent, awaitResult())
        assertEquals(ADDRESS, Pairing.load(app)!!.address)
        assertTrue("the scan must stop", scanner().scanCallbacks.isEmpty())

        // Also after the app restarts, when nothing is cached in memory.
        val restarted = WatchGarage(app)
        restarted.pulseGarage()
        assertEquals(Pulse.Sent, awaitResult(restarted))
        assertEquals(2, FakeServer.writes.size)
        assertTrue("no second scan", scanner().scanCallbacks.isEmpty())
    }

    @Test
    fun `the wrist buzzes once when sent and repeatedly when it failed`() {
        val vibrator = shadowOf(app.getSystemService(Vibrator::class.java))
        pairWithAddress()

        garage.pulseGarage()
        awaitResult()
        assertEquals(150L, vibrator.milliseconds)

        FakeServer.writeStatus = 0x80
        garage.pulseGarage()
        awaitResult()
        assertArrayEquals(longArrayOf(0, 150, 120, 150, 120, 150), vibrator.pattern)
    }

    @Test
    fun `the result clears after a few seconds`() {
        pairWithAddress()
        garage.pulseGarage()
        assertEquals(Pulse.Sent, awaitResult())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        assertEquals(Pulse.Idle, garage.status.value.pulse)
    }

    @Test
    fun `an invalid code is refused and keeps the existing pairing`() {
        assertFalse(garage.status.value.paired)
        assertEquals(SharedR.string.pairing_not_garage, garage.pair("not a key"))
        assertFalse(garage.status.value.paired)

        pairWithAddress()
        assertEquals(SharedR.string.pairing_bad_key_length, garage.pair("abcd"))
        assertTrue(garage.status.value.paired)
        assertEquals(ADDRESS, Pairing.load(app)!!.address)
    }

    @Test
    fun `pairing again forgets the old garage`() {
        pairWithAddress()
        garage.pulseGarage()
        assertEquals(Pulse.Sent, awaitResult())

        assertNull(garage.pair("""{"v":1,"key":"$KEY_HEX","addr":"11:22:33:44:55:66"}"""))
        shadowOf(Looper.getMainLooper()).idle()
        garage.pulseGarage()
        assertEquals(Pulse.Sent, awaitResult())

        assertEquals(listOf(ADDRESS, "11:22:33:44:55:66"), FakeServer.connections)
    }

    @Test
    fun `a press with no pairing does nothing`() {
        garage.pulseGarage()
        assertEquals(Pulse.Idle, garage.status.value.pulse)
        assertTrue(FakeServer.connections.isEmpty())
    }
}

/** The garage's GATT service. Each read of the challenge characteristic returns fresh bytes. */
object FakeServer {
    val writes = mutableListOf<ByteArray>()
    val connections = mutableListOf<String>()
    private val open = mutableSetOf<Any>()
    var challenge = ByteArray(16)
    var writeStatus = BluetoothGatt.GATT_SUCCESS
    var dropOnWrite = false
    var holdConnection = false
    private var held: (() -> Unit)? = null

    val allClosed: Boolean get() = synchronized(this) { open.isEmpty() }

    fun opened(gatt: Any, address: String) = synchronized(this) {
        open += gatt
        connections += address
    }

    fun closed(gatt: Any) = synchronized(this) { open -= gatt }

    fun reset() = synchronized(this) {
        writes.clear()
        connections.clear()
        open.clear()
        writeStatus = BluetoothGatt.GATT_SUCCESS
        dropOnWrite = false
        holdConnection = false
        held = null
    }

    /** Reports the connection as up, now or when the test says so if [holdConnection] is set. */
    fun connect(report: () -> Unit) {
        if (holdConnection) held = report else report()
    }

    fun releaseConnection() = held!!.invoke()

    fun service(): BluetoothGattService = BluetoothGattService(GarageBle.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(BluetoothGattCharacteristic(GarageBle.CHALLENGE, BluetoothGattCharacteristic.PROPERTY_READ, 0))
        addCharacteristic(BluetoothGattCharacteristic(GarageBle.COMMAND, BluetoothGattCharacteristic.PROPERTY_WRITE, 0))
        addCharacteristic(
            BluetoothGattCharacteristic(
                GarageBle.STATE,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0,
            ).apply { addDescriptor(BluetoothGattDescriptor(GarageBle.CCCD, 0)) },
        )
    }
}

@Implements(BluetoothDevice::class)
class FakeDevice {
    @RealObject
    private lateinit var real: BluetoothDevice

    @Implementation
    fun connectGatt(
        context: Context,
        autoConnect: Boolean,
        callback: BluetoothGattCallback,
        transport: Int,
        phy: Int,
        handler: Handler,
    ): BluetoothGatt {
        val gatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        Shadow.extract<FakeGatt>(gatt).attach(gatt, callback, handler)
        FakeServer.opened(gatt, real.address)
        FakeServer.connect {
            handler.post { callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED) }
        }
        return gatt
    }
}

@Implements(BluetoothGatt::class)
class FakeGatt {
    private lateinit var gatt: BluetoothGatt
    private lateinit var callback: BluetoothGattCallback
    private lateinit var handler: Handler
    private val service = FakeServer.service()

    fun attach(gatt: BluetoothGatt, callback: BluetoothGattCallback, handler: Handler) {
        this.gatt = gatt
        this.callback = callback
        this.handler = handler
    }

    @Implementation
    fun requestConnectionPriority(priority: Int) = true

    @Implementation
    fun discoverServices(): Boolean {
        handler.post { callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS) }
        return true
    }

    @Implementation
    fun getService(uuid: UUID): BluetoothGattService? = service.takeIf { it.uuid == uuid }

    @Implementation
    fun setCharacteristicNotification(c: BluetoothGattCharacteristic, enable: Boolean) = true

    @Implementation
    fun writeDescriptor(d: BluetoothGattDescriptor, value: ByteArray): Int {
        handler.post { callback.onDescriptorWrite(gatt, d, BluetoothGatt.GATT_SUCCESS) }
        return BluetoothStatusCodes.SUCCESS
    }

    @Implementation
    fun readCharacteristic(c: BluetoothGattCharacteristic): Boolean {
        val value = ByteArray(16) { (it * 7 + FakeServer.writes.size).toByte() }
        if (c.uuid == GarageBle.CHALLENGE) FakeServer.challenge = value
        handler.post { callback.onCharacteristicRead(gatt, c, value, BluetoothGatt.GATT_SUCCESS) }
        return true
    }

    @Implementation
    fun writeCharacteristic(c: BluetoothGattCharacteristic, value: ByteArray, writeType: Int): Int {
        synchronized(FakeServer) { FakeServer.writes += value }
        handler.post {
            if (FakeServer.dropOnWrite) {
                callback.onConnectionStateChange(gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
            } else {
                callback.onCharacteristicWrite(gatt, c, FakeServer.writeStatus)
            }
        }
        return BluetoothStatusCodes.SUCCESS
    }

    @Implementation
    fun disconnect() = Unit

    @Implementation
    fun close() {
        FakeServer.closed(gatt)
    }
}

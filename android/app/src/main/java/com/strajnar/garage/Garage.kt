package com.strajnar.garage

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the app screen and the widget show. */
data class GarageStatus(
    val paired: Boolean,
    val connected: Boolean = false,
    val relayOn: Boolean? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

class GarageApp : Application() {
    val garage by lazy { Garage(this) }
}

val Context.garage: Garage get() = (applicationContext as GarageApp).garage

val BLE_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

fun hasBlePermissions(context: Context) =
    BLE_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/**
 * App-wide garage state, shared by the screen, [GarageService] and [GarageWidget]. It lives as
 * long as the process, which the foreground service keeps alive.
 */
class Garage(private val app: Application) {
    val ble = GarageBle(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _pairing = MutableStateFlow(Pairing.load(app))
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    private data class Feedback(val busy: Boolean = false, val message: String? = null, val isError: Boolean = false)

    private val feedback = MutableStateFlow(Feedback())
    private var clearMessage: Job? = null

    val status: StateFlow<GarageStatus> =
        combine(_pairing, ble.connected, ble.state, feedback) { p, connected, state, f ->
            GarageStatus(
                paired = p != null,
                connected = connected,
                relayOn = state?.relayOn,
                busy = f.busy,
                message = f.message,
                isError = f.isError,
            )
        }.stateIn(scope, SharingStarted.Eagerly, GarageStatus(paired = _pairing.value != null))

    init {
        scope.launch { status.collect { GarageWidget.renderAll(app, it) } }
    }

    /** Starts the service that keeps the connection open. Safe to call repeatedly. */
    fun startService(context: Context) {
        if (_pairing.value == null || !hasBlePermissions(context)) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, GarageService::class.java))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Started from the background without an exemption; the next app open or widget tap will.
        }
    }

    /** Returns true if the scanned text was a valid pairing code. */
    fun onScanned(text: String): Boolean {
        val p = try {
            Pairing.parse(text)
        } catch (e: IllegalArgumentException) {
            showError(e.message ?: "Invalid pairing code")
            return false
        }
        p.save(app)
        scope.launch { ble.reset() }
        _pairing.value = p
        show(Feedback(message = "Paired with ${p.name}"))
        return true
    }

    fun forget() {
        Pairing.clear(app)
        scope.launch { ble.reset() }
        _pairing.value = null // GarageService stops itself.
        show(Feedback())
    }

    fun pulseGarage() = run("Garage door triggered") { ble.command(it, Protocol.CMD_PULSE_GARAGE, 0) }

    fun setRelay(on: Boolean) =
        run(if (on) "Relay switched on" else "Relay switched off") { ble.command(it, Protocol.CMD_SET_RELAY, if (on) 1 else 0) }

    fun refresh() = run(null) { ble.readState(it) }

    fun showError(message: String) = show(Feedback(message = message, isError = true))

    private fun run(okMessage: String?, block: suspend (Pairing) -> Unit) {
        val p = _pairing.value ?: return
        if (feedback.value.busy) return
        clearMessage?.cancel()
        // The connection row says "Connecting…" by itself, so a plain refresh shows no message.
        feedback.value = Feedback(busy = true, message = okMessage?.let { "Sending…" })
        scope.launch {
            val result = try {
                block(p)
                Feedback(message = okMessage)
            } catch (e: BleException) {
                Feedback(message = e.message, isError = true)
            } catch (e: SecurityException) {
                Feedback(message = "Bluetooth permission missing", isError = true)
            }
            show(result)
        }
    }

    /** Shows [f], then clears its message after a few seconds so the widget doesn't show stale news. */
    private fun show(f: Feedback) {
        clearMessage?.cancel()
        feedback.value = f
        if (f.message != null) {
            clearMessage = scope.launch {
                delay(MESSAGE_MS)
                feedback.update { it.copy(message = null, isError = false) }
            }
        }
    }

    private companion object {
        const val MESSAGE_MS = 6_000L
    }
}

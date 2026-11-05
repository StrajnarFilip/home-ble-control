package com.strajnar.garage.wear

import android.app.Application
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.StringRes
import com.strajnar.garage.BleException
import com.strajnar.garage.GarageBle
import com.strajnar.garage.Pairing
import com.strajnar.garage.PairingException
import com.strajnar.garage.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the button shows. */
sealed interface Pulse {
    data object Idle : Pulse
    data object Sending : Pulse
    data object Sent : Pulse
    data class Failed(val message: String) : Pulse
}

data class WatchStatus(val paired: Boolean, val pulse: Pulse = Pulse.Idle)

class GarageApp : Application() {
    val garage by lazy { WatchGarage(this) }
}

val Context.garage: WatchGarage get() = (applicationContext as GarageApp).garage

/**
 * Connects when the button is pressed and disconnects right after, because holding a connection
 * open like the phone app does would cost a watch too much battery. It lives in the application
 * rather than the screen, so a press still finishes if the screen goes off.
 */
class WatchGarage(private val app: Application) {
    private val ble = GarageBle(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val vibrator = app.getSystemService(Vibrator::class.java)
    private var pairing = Pairing.load(app)
    private var clearResult: Job? = null

    private val _status = MutableStateFlow(WatchStatus(paired = pairing != null))
    val status: StateFlow<WatchStatus> = _status.asStateFlow()

    /** Pairs using the QR code's JSON or a hex key. Returns what's wrong with [code], or null if it's valid. */
    @StringRes
    fun pair(code: String): Int? {
        val p = try {
            Pairing.parse(code)
        } catch (e: PairingException) {
            return e.reason
        }
        p.save(app)
        pairing = p
        scope.launch { ble.reset() } // Forget the address of a garage we were paired with before.
        show(Pulse.Idle)
        return null
    }

    fun pulseGarage() {
        val p = pairing ?: return
        if (_status.value.pulse == Pulse.Sending) return
        show(Pulse.Sending)
        scope.launch {
            val result = try {
                ble.command(p, Protocol.CMD_PULSE_GARAGE, 0)
                rememberAddress(p)
                Pulse.Sent
            } catch (e: BleException) {
                Pulse.Failed(e.message ?: app.getString(R.string.pulse_failed))
            } catch (e: SecurityException) {
                Pulse.Failed(app.getString(R.string.permission_missing))
            } finally {
                ble.closeNow()
            }
            vibrate(result)
            show(result)
        }
    }

    fun showError(message: String) = show(Pulse.Failed(message))

    /** A pairing without an address scans for the garage. Save what it found so the next press needn't. */
    private fun rememberAddress(p: Pairing) {
        if (p.address != null) return
        val address = ble.deviceAddress ?: return
        pairing = Pairing(p.key, address, p.name).also { it.save(app) }
    }

    private fun vibrate(result: Pulse) {
        val effect = if (result == Pulse.Sent) {
            VibrationEffect.createOneShot(SENT_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            VibrationEffect.createWaveform(FAILED_VIBRATION_MS, -1)
        }
        vibrator?.vibrate(effect)
    }

    /** Shows [pulse], then goes back to idle after a few seconds so the button doesn't show stale news. */
    private fun show(pulse: Pulse) {
        clearResult?.cancel()
        _status.value = WatchStatus(paired = pairing != null, pulse = pulse)
        if (pulse is Pulse.Sent || pulse is Pulse.Failed) {
            clearResult = scope.launch {
                delay(RESULT_MS)
                _status.update { it.copy(pulse = Pulse.Idle) }
            }
        }
    }

    private companion object {
        const val RESULT_MS = 6_000L
        const val SENT_VIBRATION_MS = 150L
        val FAILED_VIBRATION_MS = longArrayOf(0, 150, 120, 150, 120, 150)
    }
}

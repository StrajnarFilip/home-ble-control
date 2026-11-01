package com.strajnar.garage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val paired: Boolean,
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val relayOn: Boolean? = null,
    val connected: Boolean = false,
)

class GarageViewModel(app: Application) : AndroidViewModel(app) {
    private val ble = GarageBle(app)
    private var pairing: Pairing? = Pairing.load(app)
    private val _ui = MutableStateFlow(UiState(paired = pairing != null))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Returns true if the scanned text was a valid pairing code. */
    fun onScanned(text: String): Boolean {
        val p = try {
            Pairing.parse(text)
        } catch (e: IllegalArgumentException) {
            showError(e.message ?: "Invalid pairing code")
            return false
        }
        p.save(getApplication())
        pairing = p
        _ui.value = UiState(paired = true, message = "Paired with ${p.name}")
        viewModelScope.launch { ble.reset() }
        return true
    }

    fun forget() {
        Pairing.clear(getApplication())
        pairing = null
        _ui.value = UiState(paired = false)
        viewModelScope.launch { ble.reset() }
    }

    fun pulseGarage() = run("Garage door triggered") { ble.command(it, Protocol.CMD_PULSE_GARAGE, 0) }

    fun setRelay(on: Boolean) =
        run(if (on) "Relay switched on" else "Relay switched off") { ble.command(it, Protocol.CMD_SET_RELAY, if (on) 1 else 0) }

    fun refresh() = run(null) { ble.readState(it) }

    fun disconnect() {
        viewModelScope.launch {
            ble.disconnect()
            _ui.update { it.copy(connected = false) }
        }
    }

    fun showError(message: String) = _ui.update { it.copy(message = message, isError = true) }

    override fun onCleared() = ble.closeNow()

    private fun run(okMessage: String?, block: suspend (Pairing) -> DeviceState) {
        val p = pairing ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, message = if (okMessage == null) "Connecting…" else "Sending…", isError = false) }
        viewModelScope.launch {
            try {
                val s = block(p)
                _ui.update {
                    it.copy(busy = false, relayOn = s.relayOn, connected = true, message = okMessage ?: "Connected", isError = false)
                }
            } catch (e: BleException) {
                _ui.update { it.copy(busy = false, connected = ble.isConnected, message = e.message, isError = true) }
            } catch (e: SecurityException) {
                _ui.update { it.copy(busy = false, connected = false, message = "Bluetooth permission missing", isError = true) }
            }
        }
    }
}

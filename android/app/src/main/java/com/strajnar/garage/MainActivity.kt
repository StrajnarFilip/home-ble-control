package com.strajnar.garage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { GarageScreen(garage) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!hasBlePermissions(this)) return
        // The service keeps the connection open from now on (and after reboots). If it isn't
        // connected yet, connect right away instead of waiting for its background attempt.
        garage.startService(this)
        if (garage.status.value.paired && !garage.status.value.connected) garage.refresh()
    }
}

@Composable
private fun GarageScreen(vm: Garage) {
    val ui by vm.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionNeeded = stringResource(R.string.permission_needed)
    val scanPrompt = stringResource(R.string.scan_prompt)

    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action = afterPermission
        afterPermission = null
        if (result.values.all { it }) {
            vm.startService(context)
            action?.invoke()
        } else {
            vm.showError(permissionNeeded)
        }
    }
    fun withBle(action: () -> Unit) {
        if (hasBlePermissions(context)) {
            action()
        } else {
            afterPermission = action
            permissionLauncher.launch(BLE_PERMISSIONS)
        }
    }

    var rePairing by rememberSaveable { mutableStateOf(false) }

    /** Returns true if [code] was a valid pairing code. */
    fun pair(code: String): Boolean {
        if (!vm.onPairingCode(code)) return false
        rePairing = false
        withBle {
            vm.startService(context) // Already running if Bluetooth was allowed before; harmless.
            vm.refresh()
        }
        return true
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        pair(result.contents ?: return@rememberLauncherForActivityResult)
    }
    fun scanQr() = scanLauncher.launch(
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(scanPrompt)
            .setBeepEnabled(false)
            .setOrientationLocked(false),
    )

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

            if (!ui.paired || rePairing) {
                PairingCard(
                    title = stringResource(if (ui.paired) R.string.re_pair else R.string.not_paired_title),
                    onScan = ::scanQr,
                    onKey = ::pair,
                    onCancel = if (ui.paired) ({ rePairing = false }) else null,
                )
                StatusLine(ui)
                return@Column
            }

            ConnectionRow(ui)
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))

            Button(
                onClick = { withBle(vm::pulseGarage) },
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth().height(112.dp),
            ) {
                Text(stringResource(R.string.garage_door), style = MaterialTheme.typography.headlineSmall)
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.relay), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(
                                when (ui.relayOn) {
                                    true -> R.string.relay_on
                                    false -> R.string.relay_off
                                    null -> R.string.relay_unknown
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (ui.relayOn == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { withBle { vm.setRelay(true) } },
                            enabled = !ui.busy,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) { Text(stringResource(R.string.turn_on)) }
                        OutlinedButton(
                            onClick = { withBle { vm.setRelay(false) } },
                            enabled = !ui.busy,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) { Text(stringResource(R.string.turn_off)) }
                    }
                }
            }

            StatusLine(ui)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { withBle(vm::refresh) }, enabled = !ui.busy) { Text(stringResource(R.string.refresh)) }
                TextButton(onClick = { rePairing = true }) { Text(stringResource(R.string.re_pair)) }
                TextButton(onClick = vm::forget) { Text(stringResource(R.string.forget)) }
            }
        }
    }
}

/** Pairs by scanning the QR code or by pasting the key into a masked field. */
@Composable
private fun PairingCard(title: String, onScan: () -> Unit, onKey: (String) -> Boolean, onCancel: (() -> Unit)?) {
    // Plain remember: rememberSaveable would write the secret into the saved instance state.
    var key by remember { mutableStateOf("") }
    fun submit() {
        if (key.isNotBlank() && onKey(key)) key = ""
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.not_paired_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_pairing_qr)) }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.pairing_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            OutlinedButton(
                onClick = ::submit,
                enabled = key.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.pair_with_key)) }
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun ConnectionRow(ui: GarageStatus) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (ui.connected) Color(0xFF2E9D5B) else MaterialTheme.colorScheme.outline),
        )
        Text(
            stringResource(
                when {
                    ui.connected -> R.string.connected_over_bluetooth
                    ui.busy -> R.string.connecting
                    else -> R.string.not_connected
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusLine(ui: GarageStatus) {
    val msg = ui.message ?: return
    Text(
        msg,
        style = MaterialTheme.typography.bodyMedium,
        color = if (ui.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

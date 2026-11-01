package com.strajnar.garage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private val BLE_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

private fun hasBlePermissions(context: Context) =
    BLE_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

class MainActivity : ComponentActivity() {
    private val vm: GarageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { GarageScreen(vm) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Connect early so the first button press is quick and the relay state is shown.
        if (vm.ui.value.paired && hasBlePermissions(this)) vm.refresh()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.disconnect()
    }
}

@Composable
private fun GarageScreen(vm: GarageViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action = afterPermission
        afterPermission = null
        if (result.values.all { it }) action?.invoke() else vm.showError("Bluetooth permission is needed to reach the garage")
    }
    fun withBle(action: () -> Unit) {
        if (hasBlePermissions(context)) {
            action()
        } else {
            afterPermission = action
            permissionLauncher.launch(BLE_PERMISSIONS)
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        if (vm.onScanned(text)) withBle { vm.refresh() }
    }
    fun scanQr() = scanLauncher.launch(
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the QR code on the garage web page")
            .setBeepEnabled(false)
            .setOrientationLocked(false),
    )

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Garage", style = MaterialTheme.typography.headlineMedium)

            if (!ui.paired) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Not paired yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Open the garage controller's web page (http://<pi-address>:8080) and scan its QR code.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = ::scanQr, modifier = Modifier.fillMaxWidth()) { Text("Scan pairing QR") }
                    }
                }
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
                Text("Garage door", style = MaterialTheme.typography.headlineSmall)
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Relay", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            when (ui.relayOn) {
                                true -> "On"
                                false -> "Off"
                                null -> "Unknown"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (ui.relayOn == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { withBle { vm.setRelay(true) } },
                            enabled = !ui.busy,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) { Text("On") }
                        OutlinedButton(
                            onClick = { withBle { vm.setRelay(false) } },
                            enabled = !ui.busy,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) { Text("Off") }
                    }
                }
            }

            StatusLine(ui)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { withBle(vm::refresh) }, enabled = !ui.busy) { Text("Refresh") }
                TextButton(onClick = ::scanQr) { Text("Re-pair") }
                TextButton(onClick = vm::forget) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun ConnectionRow(ui: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (ui.connected) Color(0xFF2E9D5B) else MaterialTheme.colorScheme.outline),
        )
        Text(
            if (ui.connected) "Connected over Bluetooth" else "Not connected",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusLine(ui: UiState) {
    val msg = ui.message ?: return
    Text(
        msg,
        style = MaterialTheme.typography.bodyMedium,
        color = if (ui.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

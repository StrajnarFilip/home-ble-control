package com.strajnar.garage.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton

private val BLE_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

private fun hasBlePermissions(context: Context) =
    BLE_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    GarageScreen(garage)
                }
            }
        }
    }
}

@Composable
private fun GarageScreen(vm: WatchGarage) {
    val ui by vm.status.collectAsStateWithLifecycle()
    var rePairing by remember { mutableStateOf(false) }

    if (!ui.paired || rePairing) {
        PairingScreen(
            onCode = { code -> vm.pair(code).also { if (it == null) rePairing = false } },
            onCancel = if (ui.paired) ({ rePairing = false }) else null,
        )
    } else {
        GarageButton(ui.pulse, onPress = vm::pulseGarage, onRePair = { rePairing = true }, onDenied = vm::showError)
    }
}

/** Fills the round screen, so it can be hit without looking. Its long press is the way to re-pair. */
@Composable
private fun GarageButton(pulse: Pulse, onPress: () -> Unit, onRePair: () -> Unit, onDenied: (String) -> Unit) {
    val context = LocalContext.current
    val permissionNeeded = stringResource(R.string.permission_needed)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) onPress() else onDenied(permissionNeeded)
    }

    val colors = MaterialTheme.colorScheme
    Button(
        onClick = { if (hasBlePermissions(context)) onPress() else permissionLauncher.launch(BLE_PERMISSIONS) },
        onLongClick = onRePair,
        onLongClickLabel = stringResource(R.string.re_pair),
        enabled = pulse != Pulse.Sending,
        shape = CircleShape,
        colors = if (pulse is Pulse.Failed) {
            ButtonDefaults.buttonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer)
        } else {
            ButtonDefaults.buttonColors()
        },
        contentPadding = PaddingValues(20.dp),
        modifier = Modifier.fillMaxSize().padding(6.dp),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (pulse) {
                Pulse.Sending -> CircularProgressIndicator(Modifier.size(64.dp))
                is Pulse.Failed -> Unit // Errors can be long, so they get all the room.
                else -> Icon(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(88.dp))
            }
            Text(
                when (pulse) {
                    Pulse.Idle -> stringResource(R.string.garage_door)
                    Pulse.Sending -> stringResource(R.string.sending)
                    Pulse.Sent -> stringResource(R.string.garage_triggered)
                    is Pulse.Failed -> pulse.message
                },
                textAlign = TextAlign.Center,
                style = if (pulse is Pulse.Failed) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Takes the hex key or the JSON from the server's /api/pairing. A watch has no camera for the QR code. */
@Composable
private fun PairingScreen(onCode: (String) -> Int?, onCancel: (() -> Unit)?) {
    // Plain remember: rememberSaveable would write the secret into the saved instance state.
    var key by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<Int?>(null) }
    fun submit() {
        if (key.isBlank()) return
        problem = onCode(key)
        if (problem == null) key = ""
    }

    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.pairing_title), textAlign = TextAlign.Center)
        BasicTextField(
            value = key,
            onValueChange = { key = it; problem = null },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            decorationBox = { field ->
                Box(
                    Modifier.fillMaxWidth().border(1.dp, colors.outline, CircleShape).padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (key.isEmpty()) Text(stringResource(R.string.pairing_key), color = colors.onSurfaceVariant)
                    field()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        problem?.let {
            Text(stringResource(it), color = colors.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = ::submit, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pair), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
}

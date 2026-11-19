package com.example.pulselog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pulselog.ble.ConnectionController
import com.example.pulselog.ble.ConnectionUi
import com.example.pulselog.core.Phase
import com.example.pulselog.core.PressureUnit
import com.example.pulselog.core.StopReason
import com.example.pulselog.sync.SyncLoop
import com.example.pulselog.sync.SyncUi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseLogScreen(
    controller: ConnectionController,
    sync: SyncLoop,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val syncUi by sync.ui.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Pulse Log") }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding),
        ) {
            if (!permissionsGranted) {
                item {
                    Notice("Pulse Log needs Bluetooth permission to find and connect to your device.") {
                        Button(onClick = onRequestPermissions) { Text("Allow Bluetooth") }
                    }
                }
            }
            if (!ui.bluetoothOn) {
                item { Notice("Bluetooth is off. Pulse Log reconnects when it's back on.") }
            }
            item { StatusCard(ui, onDisconnect = controller::disconnect) }
            item { ReadingsCard(ui) }
            item { SyncCard(syncUi) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = permissionsGranted && ui.bluetoothOn && !ui.scanning,
                        onClick = { controller.startScan() },
                    ) { Text(if (ui.scanning) "Scanning" else "Find devices") }
                    Spacer(Modifier.width(12.dp))
                    Text("Heart rate and blood pressure devices", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(ui.found, key = { it.address }) { device ->
                Card(Modifier.fillMaxWidth().clickable { onConnect(device.address) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(device.name ?: "Unnamed device", style = MaterialTheme.typography.titleMedium)
                        Text("${device.address}  ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: @Composable () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text)
            action()
        }
    }
}

@Composable
private fun StatusCard(ui: ConnectionUi, onDisconnect: () -> Unit) {
    val r = ui.reconnect
    val status = when (r.phase) {
        Phase.IDLE -> "Not connected"
        Phase.CONNECTING -> "Connecting"
        Phase.DISCOVERING -> "Setting up"
        Phase.STREAMING -> "Connected"
        Phase.WAITING_TO_RETRY -> "Connection lost. Retry ${r.attempt} scheduled"
        Phase.BLUETOOTH_OFF -> "Waiting for Bluetooth"
        Phase.STOPPED -> if (r.stopReason == StopReason.UNAUTHORIZED) "Stopped: Bluetooth permission was removed" else "Stopped after too many failed attempts"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(status, style = MaterialTheme.typography.titleMedium)
            r.deviceId?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            ui.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (r.phase != Phase.IDLE && r.phase != Phase.STOPPED) {
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun ReadingsCard(ui: ConnectionUi) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ui.heartRate?.let { "${it.bpm} bpm" } ?: "No heart rate yet", style = MaterialTheme.typography.headlineMedium)
            ui.heartRate?.rrMillis?.lastOrNull()?.let { Text("Last RR interval %.0f ms".format(it)) }
            ui.bloodPressure?.let { bp ->
                val unit = if (bp.unit == PressureUnit.KPA) "kPa" else "mmHg"
                Text("Blood pressure %.0f/%.0f %s".format(bp.systolic, bp.diastolic, unit))
                bp.pulseRate?.let { Text("Pulse %.0f".format(it)) }
                if (bp.irregularPulse) Text("Device flagged an irregular pulse", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SyncCard(sync: SyncUi) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                sync.cloudProject?.let { "Syncing to Firebase project $it" } ?: "Local only. Add a Firebase project to sync.",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("Waiting to upload: ${sync.pending}" + if (sync.uploading) " (uploading)" else "")
            if (sync.dropped > 0) Text("Dropped when the queue was full: ${sync.dropped}")
            sync.lastError?.let { Text("Last upload error: $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

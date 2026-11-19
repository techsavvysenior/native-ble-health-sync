package com.example.pulselog.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.pulselog.PulseLogApp
import com.example.pulselog.ble.BlePermissions
import com.example.pulselog.ble.ConnectionService

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as PulseLogApp).graph
        refreshPermissions()
        setContent {
            MaterialTheme {
                PulseLogScreen(
                    controller = graph.controller,
                    sync = graph.sync,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = {
                        requestPermissions.launch(BlePermissions.required + BlePermissions.optional)
                    },
                    onConnect = { address ->
                        graph.controller.connect(address)
                        ConnectionService.start(this)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions() // the user may have revoked access in Settings (UC-07)
    }

    private fun refreshPermissions() {
        permissionsGranted = BlePermissions.granted(this)
        (application as PulseLogApp).graph.controller.onPermissionsChanged()
    }
}

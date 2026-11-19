package com.example.pulselog.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.example.pulselog.core.BloodPressureMeasurement
import com.example.pulselog.core.GattParseException
import com.example.pulselog.core.GattParsers
import com.example.pulselog.core.GattUuids
import com.example.pulselog.core.HeartRateMeasurement
import com.example.pulselog.core.Phase
import com.example.pulselog.core.Reconnect
import com.example.pulselog.core.ReconnectConfig
import com.example.pulselog.core.ReconnectEffect
import com.example.pulselog.core.ReconnectEvent
import com.example.pulselog.core.ReconnectState
import com.example.pulselog.core.ReadingKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

data class FoundDevice(val address: String, val name: String?, val rssi: Int)

data class ConnectionUi(
    val reconnect: ReconnectState = ReconnectState(),
    val nextRetryAtMs: Long? = null,
    val bluetoothOn: Boolean = true,
    val scanning: Boolean = false,
    val found: List<FoundDevice> = emptyList(),
    val heartRate: HeartRateMeasurement? = null,
    val bloodPressure: BloodPressureMeasurement? = null,
    val lastError: String? = null,
)

object BlePermissions {
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    val optional: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    fun granted(context: Context) = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Drives the shared reconnect state machine with real Bluetooth.
 *
 * Every event is handled on the main thread: GATT callbacks, timers and the adapter broadcast all post
 * here first, so the state is never touched from two threads. The controller only carries out effects;
 * every decision about when to retry lives in [Reconnect.reduce].
 */
@SuppressLint("MissingPermission") // every Bluetooth call below is behind BlePermissions.granted()
class ConnectionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPayload: (deviceId: String, kind: ReadingKind, payload: ByteArray) -> Unit,
    private val config: ReconnectConfig = ReconnectConfig(),
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _ui = MutableStateFlow(ConnectionUi(bluetoothOn = adapter?.isEnabled == true))
    val ui: StateFlow<ConnectionUi> = _ui.asStateFlow()

    private var state = ReconnectState()
    private var client: GattClient? = null
    private var retryJob: Job? = null
    private var scanCallback: ScanCallback? = null

    fun start() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(context, adapterReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun connect(address: String) {
        stopScan()
        dispatch(ReconnectEvent.ConnectRequested(address))
    }

    fun disconnect() = dispatch(ReconnectEvent.DisconnectRequested)

    /** Called on every resume. Only a connection in progress is stopped; an idle app has nothing to stop. */
    fun onPermissionsChanged() {
        if (!BlePermissions.granted(context) && isActive) dispatch(ReconnectEvent.PermissionDenied)
    }

    // ---- scanning (UC-01) ----

    fun startScan(durationMs: Long = 15_000) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (!BlePermissions.granted(context) || scanCallback != null) return
        val filters = listOf(GattUuids.HEART_RATE_SERVICE, GattUuids.BLOOD_PRESSURE_SERVICE).map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(it)).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val found = FoundDevice(result.device.address, result.scanRecord?.deviceName, result.rssi)
                main.post {
                    _ui.update { ui -> ui.copy(found = (ui.found.filter { it.address != found.address } + found).sortedByDescending { it.rssi }) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post { scanCallback = null; _ui.update { it.copy(scanning = false, lastError = "Scan failed ($errorCode)") } }
            }
        }
        scanCallback = callback
        _ui.update { it.copy(scanning = true, found = emptyList(), lastError = null) }
        scanner.startScan(filters, settings, callback)
        main.postDelayed({ stopScan() }, durationMs)
    }

    fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        if (BlePermissions.granted(context)) adapter?.bluetoothLeScanner?.stopScan(callback)
        _ui.update { it.copy(scanning = false) }
    }

    // ---- the state machine ----

    private fun dispatch(event: ReconnectEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { dispatch(event) }
            return
        }
        val transition = Reconnect.reduce(state, event, config) { Random.nextDouble() }
        state = transition.state
        _ui.update { it.copy(reconnect = state) }
        transition.effects.forEach(::run)
    }

    private fun run(effect: ReconnectEffect) {
        when (effect) {
            is ReconnectEffect.Connect -> {
                if (!BlePermissions.granted(context)) {
                    main.post { dispatch(ReconnectEvent.PermissionDenied) }
                    return
                }
                val device = adapter?.getRemoteDevice(effect.deviceId) ?: return
                client?.close()
                client = GattClient(context, device, gattListener(effect.deviceId)).also { it.connect() }
            }
            ReconnectEffect.DiscoverServices -> client?.discoverServices()
            is ReconnectEffect.ScheduleRetry -> {
                retryJob?.cancel()
                _ui.update { it.copy(nextRetryAtMs = System.currentTimeMillis() + effect.delayMs) }
                retryJob = scope.launch {
                    delay(effect.delayMs)
                    _ui.update { it.copy(nextRetryAtMs = null) }
                    dispatch(ReconnectEvent.RetryTimerFired)
                }
            }
            ReconnectEffect.CancelRetry -> {
                retryJob?.cancel()
                retryJob = null
                _ui.update { it.copy(nextRetryAtMs = null) }
            }
            ReconnectEffect.Disconnect -> {
                client?.close()
                client = null
            }
        }
    }

    private fun gattListener(deviceId: String) = object : GattClient.Listener {
        private val heartRate = UUID.fromString(GattUuids.HEART_RATE_MEASUREMENT)
        private val bloodPressure = UUID.fromString(GattUuids.BLOOD_PRESSURE_MEASUREMENT)

        override fun onConnected() = dispatch(ReconnectEvent.Connected)
        override fun onReady() = dispatch(ReconnectEvent.Ready)

        override fun onDisconnected(status: Int) {
            main.post {
                client = null
                dispatch(ReconnectEvent.Disconnected(GattStatus.cause(status)))
            }
        }

        override fun onValue(characteristic: UUID, value: ByteArray) {
            main.post {
                try {
                    when (characteristic) {
                        heartRate -> {
                            _ui.update { it.copy(heartRate = GattParsers.heartRate(value)) }
                            onPayload(deviceId, ReadingKind.HEART_RATE, value)
                        }
                        bloodPressure -> {
                            _ui.update { it.copy(bloodPressure = GattParsers.bloodPressure(value)) }
                            onPayload(deviceId, ReadingKind.BLOOD_PRESSURE, value)
                        }
                    }
                } catch (e: GattParseException) {
                    // A malformed packet is dropped and shown; it never reaches the upload queue.
                    _ui.update { it.copy(lastError = "Ignored a malformed reading (${e.error})") }
                }
            }
        }
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    _ui.update { it.copy(bluetoothOn = false) }
                    stopScan()
                    client?.close()
                    client = null
                    dispatch(ReconnectEvent.BluetoothOff)
                }
                BluetoothAdapter.STATE_ON -> {
                    _ui.update { it.copy(bluetoothOn = true) }
                    dispatch(ReconnectEvent.BluetoothOn)
                }
            }
        }
    }

    val isActive: Boolean
        get() = state.phase != Phase.IDLE && state.phase != Phase.STOPPED
}

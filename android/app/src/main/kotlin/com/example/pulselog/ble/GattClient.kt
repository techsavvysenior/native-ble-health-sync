package com.example.pulselog.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.example.pulselog.core.DisconnectCause
import com.example.pulselog.core.GattUuids
import java.util.UUID

/** Maps the status code Android reports with a disconnect to the cause the state machine uses. */
object GattStatus {
    const val SUCCESS = 0
    const val CONNECTION_TIMEOUT = 8 // link supervision timeout: the device went out of range
    const val PEER_TERMINATED = 19
    const val LOCAL_HOST_TERMINATED = 22
    const val FAILED_TO_ESTABLISH = 62
    const val GATT_ERROR = 133 // the infamous generic error, often a stale client or a busy stack
    const val ESTABLISH_TIMEOUT = 147

    fun cause(status: Int): DisconnectCause = when (status) {
        SUCCESS, CONNECTION_TIMEOUT, PEER_TERMINATED, LOCAL_HOST_TERMINATED -> DisconnectCause.LINK_LOSS
        ESTABLISH_TIMEOUT, FAILED_TO_ESTABLISH -> DisconnectCause.TIMEOUT
        else -> DisconnectCause.GATT_ERROR
    }
}

/**
 * One BluetoothGatt connection.
 *
 * Android allows a single outstanding GATT operation per connection, so descriptor writes go through
 * a small queue: the next write starts only after onDescriptorWrite for the previous one. The client
 * always calls close() after a disconnect; leaking BluetoothGatt objects is the usual cause of
 * status 133 on the next connect.
 *
 * Callbacks arrive on a binder thread. The listener is expected to hop to its own thread.
 */
@SuppressLint("MissingPermission") // ConnectionController checks BLUETOOTH_CONNECT before creating a client.
class GattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onReady()
        fun onDisconnected(status: Int)
        fun onValue(characteristic: UUID, value: ByteArray)
    }

    private val notifyUuids = mapOf(
        UUID.fromString(GattUuids.HEART_RATE_MEASUREMENT) to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
        UUID.fromString(GattUuids.BLOOD_PRESSURE_MEASUREMENT) to BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
    )
    private val cccd = UUID.fromString(GattUuids.CLIENT_CHARACTERISTIC_CONFIG)

    private var gatt: BluetoothGatt? = null
    private val pendingWrites = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()
    @Volatile private var closed = false

    fun connect() {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun discoverServices() {
        if (gatt?.discoverServices() != true) fail(GattStatus.GATT_ERROR)
    }

    /** Ends the connection on purpose. No onDisconnected follows. */
    fun close() {
        closed = true
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                listener.onConnected()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                closed = true
                g.close()
                gatt = null
                listener.onDisconnected(status)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (closed) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(status)
                return
            }
            val wanted = g.services.flatMap { it.characteristics }.filter { it.uuid in notifyUuids }
            if (wanted.isEmpty()) {
                // Neither heart rate nor blood pressure: not a device this app can use.
                fail(GattStatus.GATT_ERROR)
                return
            }
            wanted.forEach { characteristic ->
                g.setCharacteristicNotification(characteristic, true)
                characteristic.getDescriptor(cccd)?.let { pendingWrites += it to notifyUuids.getValue(characteristic.uuid) }
            }
            writeNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (closed) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(status)
                return
            }
            writeNext(g)
        }

        // Android 13 and later deliver the value as a parameter.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (!closed) listener.onValue(characteristic.uuid, value)
        }

        // Android 12 and earlier read it from the characteristic.
        @Deprecated("Replaced by the overload with a value parameter on Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (closed || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            listener.onValue(characteristic.uuid, value.copyOf())
        }
    }

    private fun writeNext(g: BluetoothGatt) {
        val next = pendingWrites.removeFirstOrNull()
        if (next == null) {
            listener.onReady()
            return
        }
        val (descriptor, value) = next
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
        if (!started) fail(GattStatus.GATT_ERROR)
    }

    /** Setup failed with the link still up: tear it down, then report it like a drop so the retry logic runs. */
    private fun fail(status: Int) {
        if (closed) return
        closed = true
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        listener.onDisconnected(status)
    }
}

package com.example.pulselog.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.pulselog.PulseLogApp
import com.example.pulselog.R
import com.example.pulselog.core.Phase
import com.example.pulselog.ui.MainActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a device is connected or being reconnected, so readings keep
 * arriving with the screen off (UC-12). The connection itself lives in [ConnectionController];
 * this service only holds the foreground notification and stops itself once the controller is idle.
 */
class ConnectionService : Service() {
    private val scope = MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_description)
            },
        )
    }

    // Without POST_NOTIFICATIONS the notification is hidden and notify() does nothing; the service still runs.
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Connecting"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        val controller = (application as PulseLogApp).graph.controller
        scope.launch {
            controller.ui.collect { ui ->
                if (ui.reconnect.phase == Phase.IDLE || ui.reconnect.phase == Phase.STOPPED) {
                    stopSelf()
                    return@collect
                }
                val text = when (ui.reconnect.phase) {
                    Phase.STREAMING -> ui.heartRate?.let { "${it.bpm} bpm" } ?: "Connected"
                    Phase.WAITING_TO_RETRY -> "Reconnecting, attempt ${ui.reconnect.attempt}"
                    Phase.BLUETOOTH_OFF -> "Waiting for Bluetooth"
                    else -> "Connecting"
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }
    }
}

package com.strajnar.garage

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the BLE connection to the garage open for as long as the phone is paired, so the app and
 * the widget never wait for a connection. Widget buttons are delivered here as intent actions.
 */
class GarageService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() needs a matching startForeground(), even when already running.
        if (!enterForeground(garage.ble.connected.value)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            keepConnected()
        }
        when (intent?.action) {
            ACTION_PULSE -> garage.pulseGarage()
            ACTION_RELAY -> garage.setRelay(intent.getBooleanExtra(EXTRA_ON, false))
            ACTION_REFRESH -> garage.refresh()
        }
        return START_STICKY
    }

    private fun keepConnected() {
        val garage = garage
        scope.launch {
            // Restarts with the new pairing after a re-pair; stops after Forget.
            garage.pairing.collectLatest { p ->
                if (p == null) stopSelf() else garage.ble.keepConnected(p)
            }
        }
        scope.launch {
            garage.ble.connected.collect { enterForeground(it) }
        }
    }

    private fun enterForeground(connected: Boolean): Boolean = try {
        startForeground(NOTIFICATION_ID, notification(connected), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
    } catch (e: ForegroundServiceStartNotAllowedException) {
        false // E.g. a sticky restart from the background. The next app open or widget tap restarts it.
    } catch (e: SecurityException) {
        false // Bluetooth permission was revoked.
    }

    private fun notification(connected: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Garage connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the garage connected so the app and widget respond instantly"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (connected) "Connected to the garage" else "Waiting for the garage")
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        garage.ble.closeNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PULSE = "com.strajnar.garage.PULSE"
        const val ACTION_RELAY = "com.strajnar.garage.RELAY"
        const val ACTION_REFRESH = "com.strajnar.garage.REFRESH"
        const val EXTRA_ON = "on"
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
    }
}

/** Reconnects after a reboot or an app update, without waiting for the app to be opened. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> context.garage.startService(context)
        }
    }
}

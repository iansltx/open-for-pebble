package dev.ian.openpebble.pebble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.ian.openpebble.DoorStore

/**
 * Foreground service that keeps the bridge process alive while the watch app
 * is being used, and does the actual work for watch requests:
 *
 *  - ACTION_SYNC:   push the curated door list to the watch
 *  - ACTION_UNLOCK: trigger the unlock via the Alta Open app and report back
 *
 * The service stops itself after [IDLE_STOP_MS] without traffic from the
 * watch.
 */
class BridgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        scheduleStop()

        val action = intent?.action ?: return START_NOT_STICKY
        when (action) {
            ACTION_SYNC -> syncDoors()
            ACTION_UNLOCK -> {
                val doorId = intent.getIntExtra(EXTRA_DOOR_ID, Int.MIN_VALUE)
                val doorType = intent.getIntExtra(EXTRA_DOOR_TYPE, PebbleBridge.DOOR_TYPE_ENTRY)
                val requestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0L)
                handleUnlock(doorId, doorType, requestId)
            }
        }
        return START_NOT_STICKY
    }

    private fun syncDoors() {
        val doors = DoorStore.load(this)
        Log.i(TAG, "syncing ${doors.size} doors to watch")
        PebbleBridge.sendDoors(this, doors)
    }

    private fun handleUnlock(doorId: Int, doorType: Int, requestId: Long) {
        val door = DoorStore.findByKey(this, doorId, doorType)
        if (door == null) {
            Log.w(TAG, "unlock requested for unknown door id=$doorId type=$doorType")
            PebbleBridge.sendUnlockResult(this, requestId, PebbleBridge.RESULT_ERROR, "Door not in bridge list")
            return
        }

        val status = dev.ian.openpebble.alta.AltaTrigger.trigger(this, door, requestId)
        val message = when (status) {
            dev.ian.openpebble.alta.AltaTrigger.STATUS_OK -> ""
            dev.ian.openpebble.alta.AltaTrigger.STATUS_BLOCKED -> "Confirm on phone"
            else -> "Alta Open app missing"
        }
        PebbleBridge.sendUnlockResult(this, requestId, status, message)
    }

    // ------------------------------------------------------------------ FGS

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Pebble bridge", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification: Notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("Alta Pebble bridge")
                    .setContentText("Connected to the Alta Doors watch app")
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("Alta Pebble bridge")
                    .setContentText("Connected to the Alta Doors watch app")
                    .build()
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    private fun scheduleStop() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { stopSelf() }
        stopRunnable = r
        handler.postDelayed(r, IDLE_STOP_MS)
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL = "bridge"
        private const val NOTIFICATION_ID = 42
        private const val IDLE_STOP_MS = 10 * 60 * 1000L

        const val ACTION_SYNC = "dev.ian.openpebble.action.SYNC"
        const val ACTION_UNLOCK = "dev.ian.openpebble.action.UNLOCK"
        const val EXTRA_DOOR_ID = "door_id"
        const val EXTRA_DOOR_TYPE = "door_type"
        const val EXTRA_REQUEST_ID = "request_id"

        fun start(context: Context, action: String, doorId: Int? = null, doorType: Int? = null, requestId: Long? = null) {
            val intent = Intent(context, BridgeService::class.java).setAction(action)
            doorId?.let { intent.putExtra(EXTRA_DOOR_ID, it) }
            doorType?.let { intent.putExtra(EXTRA_DOOR_TYPE, it) }
            requestId?.let { intent.putExtra(EXTRA_REQUEST_ID, it) }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.e(TAG, "failed to start BridgeService", it) }
        }
    }
}
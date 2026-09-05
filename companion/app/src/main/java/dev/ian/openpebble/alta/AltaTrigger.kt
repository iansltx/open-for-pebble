package dev.ian.openpebble.alta

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.ian.openpebble.Door
import dev.ian.openpebble.UnlockActivity
import java.util.concurrent.atomic.AtomicLong

/**
 * Triggers a door unlock in the Avigilon Alta Open app ("Alta Open",
 * package com.openpath.mobile, the rebranded Openpath app).
 *
 * How it works
 * ------------
 * Alta Open's `MainActivity` is exported (singleTask) and its
 * `ReactAppShortcutsModule` reads three intent extras on `onNewIntent` /
 * cold start ("popInitialAction"):
 *
 *   SHORTCUT_ID        (long)   — must be unique per request
 *   SHORTCUT_TYPE      (String) — e.g. "Unlock"
 *   SHORTCUT_USER_INFO (String) — JSON, handler reads userInfo.url
 *
 * The JS `quickActionShortcut` handler parses `userInfo.url` as an item key
 * ("entry-<id>" / "reader-<id>") and dispatches
 * `requestBatchUnlock(itemType, itemId, 'home_screen_shortcut')` — the same
 * path a home-screen shortcut takes — which calls the native SDK's
 * `unlock(itemType, itemId, ...)`.
 *
 * The Alta app then unlocks the door the same way it would if you had tapped
 * it in the app: over BLE to the reader when in range, or via the controller
 * over Wi-Fi/MQTT otherwise (remote unlocks may show a confirmation dialog
 * *inside* the Alta app — that's their security design, not something we can
 * or should bypass).
 */
object AltaTrigger {

    private const val TAG = "AltaTrigger"

    const val ALTA_PACKAGE = "com.openpath.mobile"
    const val ALTA_ACTIVITY = "com.openpath.mobile.MainActivity"

    private const val EXTRA_SHORTCUT_ID = "SHORTCUT_ID"
    private const val EXTRA_SHORTCUT_TYPE = "SHORTCUT_TYPE"
    private const val EXTRA_SHORTCUT_USER_INFO = "SHORTCUT_USER_INFO"

    // Mirrors TriggerStatus on the watch; keep in sync with main.c
    const val STATUS_OK = 0
    const val STATUS_ERROR = 1
    const val STATUS_BLOCKED = 2

    private val lastShortcutId = AtomicLong(System.currentTimeMillis())

    fun isAltaInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(ALTA_PACKAGE, 0)
            true
        }.getOrDefault(false)

    /**
     * Whether we may start an activity from the background. Android 10+
     * blocks background activity starts; apps holding the "Display over
     * other apps" (SYSTEM_ALERT_WINDOW) special access are exempt.
     */
    fun canTriggerFromBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    /**
     * Fire the unlock intent at the Alta Open app.
     *
     * @param fromBackground true when called from [BridgeService]/a receiver
     *   (Android 10+ blocks background activity starts without the overlay
     *   exemption). Pass false from foreground UI ([UnlockActivity], the
     *   "Unlock now" test button) where the start is always legal — otherwise
     *   a tap on the fallback notification would just post another
     *   notification instead of unlocking.
     * @return STATUS_OK if the intent was sent, STATUS_BLOCKED if background
     *   activity starts are not allowed (a fallback notification was posted),
     *   STATUS_ERROR if the Alta app is missing.
     */
    fun trigger(context: Context, door: Door, requestId: Long, fromBackground: Boolean = true): Int {
        val appContext = context.applicationContext
        if (!isAltaInstalled(appContext)) {
            Log.e(TAG, "Alta Open app is not installed")
            return STATUS_ERROR
        }

        val userInfo = """{"url":"${door.itemKey()}"}"""

        val intent = Intent()
            .setClassName(ALTA_PACKAGE, ALTA_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_SHORTCUT_ID, lastShortcutId.incrementAndGet())
            .putExtra(EXTRA_SHORTCUT_TYPE, "Unlock")
            .putExtra(EXTRA_SHORTCUT_USER_INFO, userInfo)

        if (fromBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "background activity start not permitted; posting fallback notification")
            postFallbackNotification(appContext, door, requestId)
            return STATUS_BLOCKED
        }

        return try {
            appContext.startActivity(intent)
            Log.i(TAG, "unlock trigger sent for ${door.itemKey()}")
            STATUS_OK
        } catch (e: Exception) {
            Log.e(TAG, "failed to start Alta activity", e)
            STATUS_ERROR
        }
    }

    /**
     * When the phone can't grant us background activity starts, show a
     * notification whose PendingIntent unlocks on tap (tapping makes the
     * activity start legitimate). This is also useful as a conscious
     * extra confirmation step.
     */
    private fun postFallbackNotification(context: Context, door: Door, requestId: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "unlock",
                    "Door unlock confirmation",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val tap = Intent(context, UnlockActivity::class.java).apply {
            putExtra(UnlockActivity.EXTRA_DOOR_ID, door.id)
            putExtra(UnlockActivity.EXTRA_DOOR_TYPE, door.type)
            putExtra(UnlockActivity.EXTRA_DOOR_NAME, door.name)
            putExtra(UnlockActivity.EXTRA_REQUEST_ID, requestId)
        }
        val pending = PendingIntent.getActivity(
            context,
            ((door.id * 31 + requestId.toInt()) and 0xFFFFFFF),
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build the notification without any support library (minSdk 21 friendly).
        @Suppress("DEPRECATION")
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, "unlock")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Unlock ${door.name}?")
                .setContentText("Tap to confirm — or grant the bridge 'Display over other apps' for one-tap unlocks.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        } else {
            android.app.Notification.Builder(context)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Unlock ${door.name}?")
                .setContentText("Tap to confirm")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        }
        nm.notify(((door.id * 31 + requestId.toInt()) and 0xFFFFFFF), notification)
    }
}
package dev.ian.openpebble

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dev.ian.openpebble.alta.AltaTrigger
import dev.ian.openpebble.pebble.PebbleBridge

/**
 * Invisible trampoline used as the notification-tap PendingIntent when
 * background activity starts are blocked. Re-fires the Alta Open trigger
 * (now legal — we're in the foreground), notifies the watch, and finishes.
 */
class UnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val doorId = intent.getIntExtra(EXTRA_DOOR_ID, Int.MIN_VALUE)
        val doorType = intent.getIntExtra(EXTRA_DOOR_TYPE, PebbleBridge.DOOR_TYPE_ENTRY)
        val doorName = intent.getStringExtra(EXTRA_DOOR_NAME) ?: "door"
        val requestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0L)

        val door = if (doorId != Int.MIN_VALUE) Door(doorName, doorId, doorType) else null
        val status = if (door != null) {
            // Foreground activity: the activity start is legal regardless of
            // the overlay exemption (see AltaTrigger.trigger).
            AltaTrigger.trigger(this, door, requestId, fromBackground = false)
        } else {
            AltaTrigger.STATUS_ERROR
        }
        if (door != null && requestId > 0) {
            PebbleBridge.sendUnlockResult(
                this, requestId, status,
                if (status == AltaTrigger.STATUS_OK) "" else "Failed"
            )
        }
        Log.i("UnlockActivity", "trampoline result=$status door=${door?.itemKey()}")

        finish()
    }

    companion object {
        const val EXTRA_DOOR_ID = "door_id"
        const val EXTRA_DOOR_TYPE = "door_type"
        const val EXTRA_DOOR_NAME = "door_name"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
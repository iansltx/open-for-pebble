package dev.ian.openpebble.pebble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.ian.openpebble.DoorStore
import java.util.UUID

/**
 * Manifest-declared receiver for AppMessages routed by the official Pebble
 * Android app (action com.getpebble.action.app.RECEIVE). The Pebble app
 * targets us via the `com.getpebble.android.kit.UUID` meta-data in the
 * manifest; this also works when our process is not running.
 *
 * Every received AppMessage is acked so the watch doesn't retry.
 */
class PebbleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PebbleBridge.ACTION_RECEIVE) return
        val data = intent.getByteArrayExtra(PebbleBridge.EXTRA_MSG_DATA) ?: return
        if (data.size < 16) return

        val payloadUuid = runCatching { PebbleBridge.uuidFromBytes(data.copyOfRange(0, 16)) }.getOrNull() ?: return
        val expected: UUID = runCatching { PebbleBridge.appUuid(context) }.getOrNull() ?: return
        if (payloadUuid != expected) {
            Log.w("PebbleReceiver", "AppMessage for a different watch app ($payloadUuid) ignored")
            return
        }

        val transactionId = intent.getIntExtra(PebbleBridge.EXTRA_TRANSACTION_ID, -1)
        PebbleBridge.ack(context, transactionId)

        val dict = PebbleBridge.parseDict(data.copyOfRange(16, data.size))

        when {
            dict.containsKey(PebbleBridge.KEY_HELLO) -> {
                Log.i("PebbleReceiver", "hello from watch")
                BridgeService.start(context, BridgeService.ACTION_SYNC)
            }

            dict.containsKey(PebbleBridge.KEY_UNLOCK_ID) -> {
                val id = (dict[PebbleBridge.KEY_UNLOCK_ID]?.value as? PebbleBridge.TupleValue.UInt)?.value?.toInt() ?: return
                val type = ((dict[PebbleBridge.KEY_UNLOCK_TYPE]?.value as? PebbleBridge.TupleValue.UInt)?.value
                    ?: PebbleBridge.DOOR_TYPE_ENTRY.toLong()).toInt()
                val requestId = (dict[PebbleBridge.KEY_UNLOCK_REQID]?.value as? PebbleBridge.TupleValue.UInt)?.value ?: 0L
                Log.i("PebbleReceiver", "unlock request id=$id type=$type req=$requestId")
                BridgeService.start(context, BridgeService.ACTION_UNLOCK, id, type, requestId)
            }
        }
    }
}

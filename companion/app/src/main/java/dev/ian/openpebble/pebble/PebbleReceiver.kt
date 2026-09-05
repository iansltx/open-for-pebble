package dev.ian.openpebble.pebble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.UUID

/**
 * Receiver for AppMessages routed by the Pebble phone app (action
 * com.getpebble.action.app.RECEIVE). Two dialects, negotiated by extras:
 *
 *  - Legacy (com.getpebble.android): `msg_data` is bytes — 16-byte app UUID
 *    followed by binary dictionary tuples.
 *  - Core (coredevices.coreapp): `msg_data` is a JSON string (array of
 *    {"key","type","length","value"}) and `uuid` is a UUID Serializable
 *    extra. Verified against the Core app's AppMessage codec.
 *
 * Every received AppMessage is acked so the watch doesn't retry.
 */
class PebbleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PebbleBridge.ACTION_RECEIVE) return

        val expected: UUID = runCatching { PebbleBridge.appUuid(context) }.getOrNull() ?: return

        // Core format first: String msg_data + UUID extra.
        val jsonMsg = intent.getStringExtra(PebbleBridge.EXTRA_MSG_DATA)
        if (jsonMsg != null) {
            val uuidExtra: UUID? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getSerializableExtra(PebbleBridge.EXTRA_UUID, UUID::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(PebbleBridge.EXTRA_UUID) as? UUID
            }
            if (uuidExtra == null) {
                Log.w(TAG, "Core AppMessage without uuid extra ignored")
                return
            }
            if (uuidExtra != expected) {
                Log.w(TAG, "AppMessage for a different watch app ($uuidExtra) ignored")
                return
            }
            val transactionId = intent.getIntExtra(PebbleBridge.EXTRA_TRANSACTION_ID, -1)
            PebbleBridge.ack(context, transactionId)
            handleDict(context, PebbleBridge.parseJsonDict(jsonMsg))
            return
        }

        // Legacy format: binary msg_data with UUID prefix.
        val data = intent.getByteArrayExtra(PebbleBridge.EXTRA_MSG_DATA) ?: return
        if (data.size < 16) return

        val payloadUuid = runCatching { PebbleBridge.uuidFromBytes(data.copyOfRange(0, 16)) }.getOrNull() ?: return
        if (payloadUuid != expected) {
            Log.w(TAG, "AppMessage for a different watch app ($payloadUuid) ignored")
            return
        }

        val transactionId = intent.getIntExtra(PebbleBridge.EXTRA_TRANSACTION_ID, -1)
        PebbleBridge.ack(context, transactionId)

        handleDict(context, PebbleBridge.parseDict(data.copyOfRange(16, data.size)))
    }

    private fun handleDict(context: Context, dict: Map<Int, PebbleBridge.Tuple>) {
        when {
            dict.containsKey(PebbleBridge.KEY_HELLO) -> {
                Log.i(TAG, "hello from watch")
                BridgeService.start(context, BridgeService.ACTION_SYNC)
            }

            dict.containsKey(PebbleBridge.KEY_UNLOCK_ID) -> {
                val id = (dict[PebbleBridge.KEY_UNLOCK_ID]?.value as? PebbleBridge.TupleValue.UInt)?.value?.toInt() ?: return
                val type = ((dict[PebbleBridge.KEY_UNLOCK_TYPE]?.value as? PebbleBridge.TupleValue.UInt)?.value
                    ?: PebbleBridge.DOOR_TYPE_ENTRY.toLong()).toInt()
                val requestId = (dict[PebbleBridge.KEY_UNLOCK_REQID]?.value as? PebbleBridge.TupleValue.UInt)?.value ?: 0L
                Log.i(TAG, "unlock request id=$id type=$type req=$requestId")
                BridgeService.start(context, BridgeService.ACTION_UNLOCK, id, type, requestId)
            }
        }
    }

    companion object {
        private const val TAG = "PebbleReceiver"
    }
}

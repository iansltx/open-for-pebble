package dev.ian.openpebble.pebble

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Vendored implementation of the small subset of PebbleKit Android that this
 * app needs. This is the classic broadcast protocol spoken by the official
 * Pebble Android app (com.getpebble.android.kit); it avoids depending on the
 * archived `com.getpebble.android:pebblekit` artifact.
 *
 * Wire format of the `msg_data` extra (for both SEND and RECEIVE):
 *
 *   [16 bytes: watch-app UUID, big-endian]
 *   followed by a sequence of dictionary tuples:
 *     [2 bytes: key, big-endian] [1 byte: tuple type] [2 bytes: length, big-endian] [data]
 *
 * Tuple type ids: 0 = byte array, 1 = cstring (length includes the NUL
 * terminator), 2 = unsigned int, 3 = signed int. Integers are big-endian with
 * 1/2/4-byte widths (uint8 = 1 byte, uint32 = 4 bytes).
 *
 * The watch side of this protocol lives in pebble/src/main.c.
 */
object PebbleBridge {

    private const val TAG = "PebbleBridge"

    const val PEBBLE_PACKAGE = "com.getpebble.android"
    const val ACTION_START = "com.getpebble.action.app.START"
    const val ACTION_STOP = "com.getpebble.action.app.STOP"
    const val ACTION_ACK = "com.getpebble.action.app.ACK"
    const val ACTION_SEND = "com.getpebble.action.app.SEND"
    const val ACTION_RECEIVE = "com.getpebble.action.app.RECEIVE"

    const val EXTRA_APP_UUID = "app_uuid"
    const val EXTRA_MSG_DATA = "msg_data"
    const val EXTRA_TRANSACTION_ID = "transaction_id"

    // Tuple types
    const val TYPE_BYTES = 0
    const val TYPE_CSTRING = 1
    const val TYPE_UINT = 2
    const val TYPE_INT = 3

    // --- AppMessage keys: must match pebble/src/main.c -----------------------
    const val KEY_HELLO = 0
    const val KEY_DOORS_COUNT = 1
    const val KEY_DOORS_END = 2
    const val KEY_DOOR_ID = 3
    const val KEY_DOOR_NAME = 4
    const val KEY_DOOR_TYPE = 5
    const val KEY_UNLOCK_ID = 10
    const val KEY_UNLOCK_TYPE = 11
    const val KEY_UNLOCK_REQID = 12
    const val KEY_RESULT_REQID = 15
    const val KEY_RESULT_STATUS = 16
    const val KEY_RESULT_TEXT = 17

    // --- Unlock result statuses (sent to the watch) ---------------------------
    const val RESULT_OK = 0
    const val RESULT_ERROR = 1
    const val RESULT_BLOCKED = 2

    const val DOOR_TYPE_ENTRY = 0
    const val DOOR_TYPE_READER = 1

    // ---------------------------------------------------------------- values

    sealed class TupleValue {
        data class Bytes(val bytes: ByteArray) : TupleValue()
        data class Str(val value: String) : TupleValue()
        data class UInt(val value: Long) : TupleValue()
        data class IntV(val value: Long) : TupleValue()
    }

    data class Tuple(val key: Int, val type: Int, val value: TupleValue)

    // ------------------------------------------------------------- UUID bits

    fun uuidBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

    fun uuidFromBytes(bytes: ByteArray): UUID {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return UUID(bb.long, bb.long)
    }

    // ----------------------------------------------------------- dict writer

    class Dict {
        private val out = ByteArrayOutputStream()

        private fun header(key: Int, type: Int, length: Int) {
            val h = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            h.putShort(key.toShort())
            h.put(type.toByte())
            h.putShort(length.toShort())
            out.write(h.array())
        }

        fun u8(key: Int, value: Int): Dict {
            header(key, TYPE_UINT, 1)
            out.write(value and 0xFF)
            return this
        }

        fun u32(key: Int, value: Long): Dict {
            header(key, TYPE_UINT, 4)
            val b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
            out.write(b)
            return this
        }

        fun string(key: Int, value: String): Dict {
            val bytes = value.toByteArray(Charsets.ISO_8859_1)
            header(key, TYPE_CSTRING, bytes.size + 1) // NUL terminator included
            out.write(bytes)
            out.write(0)
            return this
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    // ----------------------------------------------------------- dict parser

    fun parseDict(data: ByteArray): Map<Int, Tuple> {
        val result = mutableMapOf<Int, Tuple>()
        var i = 0
        while (i + 5 <= data.size) {
            val key = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            val type = data[i + 2].toInt() and 0xFF
            val len = ((data[i + 3].toInt() and 0xFF) shl 8) or (data[i + 4].toInt() and 0xFF)
            if (i + 5 + len > data.size) break
            val payload = data.copyOfRange(i + 5, i + 5 + len)
            val value: TupleValue = when (type) {
                TYPE_CSTRING -> TupleValue.Str(
                    String(payload, Charsets.ISO_8859_1).trimEnd { it == '\u0000' }
                )
                TYPE_UINT -> TupleValue.UInt(readUInt(payload))
                TYPE_INT -> TupleValue.IntV(readUInt(payload))
                else -> TupleValue.Bytes(payload)
            }
            result[key] = Tuple(key, type, value)
            i += 5 + len
        }
        return result
    }

    private fun readUInt(b: ByteArray): Long {
        var v: Long = 0
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }

    // ---------------------------------------------------------- transports

    fun appUuid(context: Context): UUID {
        val raw = dev.ian.openpebble.DoorStore.pebbleUuid(context)
        return runCatching { UUID.fromString(raw) }.getOrElse {
            Log.e(TAG, "invalid pebble uuid '$raw', using default")
            UUID.fromString(dev.ian.openpebble.DoorStore.DEFAULT_PEBBLE_UUID)
        }
    }

    fun send(context: Context, dict: Dict) {
        val uuid = appUuid(context).let { uuidBytes(it) }
        val msgData = uuid + dict.bytes()
        val intent = Intent(ACTION_SEND).setPackage(PEBBLE_PACKAGE).putExtra(EXTRA_MSG_DATA, msgData)
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.e(TAG, "send failed — Pebble app installed?", it) }
    }

    fun startWatchApp(context: Context) {
        val intent = Intent(ACTION_START).setPackage(PEBBLE_PACKAGE)
            .putExtra(EXTRA_APP_UUID, appUuid(context).let { uuidBytes(it) })
        runCatching { context.sendBroadcast(intent) }
    }

    fun stopWatchApp(context: Context) {
        val intent = Intent(ACTION_STOP).setPackage(PEBBLE_PACKAGE)
            .putExtra(EXTRA_APP_UUID, appUuid(context).let { uuidBytes(it) })
        runCatching { context.sendBroadcast(intent) }
    }

    /** Acknowledge an inbound AppMessage; the watch side retries until this arrives. */
    fun ack(context: Context, transactionId: Int) {
        if (transactionId < 0) return
        val intent = Intent(ACTION_ACK).setPackage(PEBBLE_PACKAGE)
            .putExtra(EXTRA_TRANSACTION_ID, transactionId)
        runCatching { context.sendBroadcast(intent) }
    }

    fun isPebbleAppInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(PEBBLE_PACKAGE, 0)
            true
        }.getOrDefault(false)

    // ------------------------------------------------------------ messages

    fun sendDoors(context: Context, doors: List<dev.ian.openpebble.Door>, onDone: (() -> Unit)? = null) {
        // Count, then one message per door, then end marker. Small pacing so the
        // watch inbox never overruns; the Pebble app queues each AppMessage.
        send(context, Dict().u32(KEY_DOORS_COUNT, doors.size.toLong()))
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        doors.forEachIndexed { index, door ->
            handler.postDelayed({
                send(context, Dict()
                    .u32(KEY_DOOR_ID, door.id.toLong())
                    .string(KEY_DOOR_NAME, door.name.take(32))
                    .u8(KEY_DOOR_TYPE, door.type))
            }, 120L * (index + 1))
        }
        handler.postDelayed({
            send(context, Dict().u8(KEY_DOORS_END, 1))
            onDone?.invoke()
        }, 120L * (doors.size + 1))
    }

    fun sendUnlockResult(context: Context, requestId: Long, status: Int, text: String) {
        send(context, Dict()
            .u32(KEY_RESULT_REQID, requestId)
            .u8(KEY_RESULT_STATUS, status)
            .string(KEY_RESULT_TEXT, text.take(64)))
    }
}

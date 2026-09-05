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
 * app needs, in two dialects:
 *
 *  - Legacy (official Pebble app, com.getpebble.android): `msg_data` is bytes
 *    — 16-byte watch-app UUID (big-endian) followed by binary tuples
 *    ([u16 key BE][u8 type][u16 len BE][data], types 0=bytes, 1=cstring with
 *    NUL, 2=uint, 3=int, integers big-endian).
 *  - Core (Core Devices app, coredevices.coreapp): `msg_data` is a JSON
 *    string — array of {"key","type","length","value"} with type one of
 *    "bytes" (Base64-NO_WRAP), "string", "uint", "int" — and `uuid` is a
 *    UUID Serializable extra. Reverse-engineered from the Core APK's
 *    AppMessage codec and verified field-for-field.
 *
 * The watch side of this protocol lives in pebble/src/c/main.c.
 */
object PebbleBridge {

    private const val TAG = "PebbleBridge"

    const val PEBBLE_PACKAGE_LEGACY = "com.getpebble.android"
    const val PEBBLE_PACKAGE_CORE = "coredevices.coreapp"
    const val ACTION_START = "com.getpebble.action.app.START"
    const val ACTION_STOP = "com.getpebble.action.app.STOP"
    const val ACTION_ACK = "com.getpebble.action.app.ACK"
    const val ACTION_SEND = "com.getpebble.action.app.SEND"
    const val ACTION_RECEIVE = "com.getpebble.action.app.RECEIVE"

    const val EXTRA_APP_UUID = "app_uuid" // legacy SEND/START/STOP
    const val EXTRA_UUID = "uuid" // Core SEND/START/STOP/RECEIVE (UUID Serializable)
    const val EXTRA_MSG_DATA = "msg_data" // legacy: bytes; Core: JSON string
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
    //
    // Dict records fields once and renders in either wire format:
    //  - legacy (com.getpebble.android): binary tuples after a 16-byte UUID
    //  - Core (coredevices.coreapp): JSON array of
    //    {"key","type":"bytes"|"string"|"uint"|"int","length","value"}
    //    (verified against the Core app's AppMessage codec: bytes are
    //    Base64-NO_WRAP, strings raw UTF-8, ints plain numbers, length is
    //    the byte width 0/1/2/4).

    sealed class Field {
        data class U8(val key: Int, val value: Int) : Field()
        data class U32(val key: Int, val value: Long) : Field()
        data class Str(val key: Int, val value: String) : Field()
    }

    class Dict {
        private val out = ByteArrayOutputStream()
        private val fields = mutableListOf<Field>()

        private fun header(key: Int, type: Int, length: Int) {
            val h = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            h.putShort(key.toShort())
            h.put(type.toByte())
            h.putShort(length.toShort())
            out.write(h.array())
        }

        fun u8(key: Int, value: Int): Dict {
            fields.add(Field.U8(key, value))
            header(key, TYPE_UINT, 1)
            out.write(value and 0xFF)
            return this
        }

        fun u32(key: Int, value: Long): Dict {
            fields.add(Field.U32(key, value))
            header(key, TYPE_UINT, 4)
            val b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
            out.write(b)
            return this
        }

        fun string(key: Int, value: String): Dict {
            fields.add(Field.Str(key, value))
            // Pebble cstrings are UTF-8.
            val bytes = value.toByteArray(Charsets.UTF_8)
            header(key, TYPE_CSTRING, bytes.size + 1) // NUL terminator included
            out.write(bytes)
            out.write(0)
            return this
        }

        fun bytes(): ByteArray = out.toByteArray()

        fun json(): String {
            val arr = org.json.JSONArray()
            for (f in fields) {
                val o = org.json.JSONObject()
                when (f) {
                    is Field.U8 -> {
                        o.put("key", f.key)
                        o.put("type", "uint")
                        o.put("length", 1)
                        o.put("value", f.value and 0xFF)
                    }
                    is Field.U32 -> {
                        o.put("key", f.key)
                        o.put("type", "uint")
                        o.put("length", 4)
                        o.put("value", (f.value and 0xFFFFFFFFL).toInt())
                    }
                    is Field.Str -> {
                        o.put("key", f.key)
                        o.put("type", "string")
                        o.put("length", f.value.toByteArray(Charsets.UTF_8).size)
                        o.put("value", f.value)
                    }
                }
                arr.put(o)
            }
            return arr.toString()
        }

        fun summary(): String = fields.joinToString(",") {
            when (it) {
                is Field.U8 -> "${it.key}:u8=${it.value}"
                is Field.U32 -> "${it.key}:u32=${it.value}"
                is Field.Str -> "${it.key}:str='${it.value.take(24)}'"
            }
        }
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
                    String(payload, Charsets.UTF_8).trimEnd { it == '\u0000' }
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

    /**
     * Parse the Core phone app's JSON dict (see [Dict.json]): a JSON array of
     * {"key","type","length","value"} where type is one of
     * "bytes" (Base64-NO_WRAP), "string", "uint", "int".
     */
    fun parseJsonDict(json: String): Map<Int, Tuple> {
        val result = mutableMapOf<Int, Tuple>()
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return result
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optInt("key", -1)
            if (key < 0) continue
            val value: TupleValue = when (o.optString("type")) {
                "string" -> TupleValue.Str(o.optString("value", ""))
                "bytes" -> runCatching {
                    TupleValue.Bytes(
                        android.util.Base64.decode(o.optString("value", ""), android.util.Base64.NO_WRAP)
                    )
                }.getOrNull() ?: continue
                "uint" -> TupleValue.UInt(o.optLong("value", 0L) and 0xFFFFFFFFL)
                "int" -> TupleValue.IntV(o.optLong("value", 0L))
                else -> continue
            }
            val type = when (value) {
                is TupleValue.Str -> TYPE_CSTRING
                is TupleValue.Bytes -> TYPE_BYTES
                is TupleValue.UInt -> TYPE_UINT
                is TupleValue.IntV -> TYPE_INT
            }
            result[key] = Tuple(key, type, value)
        }
        return result
    }

    // ---------------------------------------------------------- transports

    /**
     * The phone app that relays AppMessages to/from the watch. The legacy app
     * is `com.getpebble.android`; the current Core Devices app is
     * `coredevices.coreapp` (verified: it speaks the same
     * `com.getpebble.action.app.*` broadcast protocol but declares no
     * manifest receiver, so it must be addressed explicitly). Resolve:
     *
     *  1. legacy package, if installed;
     *  2. Core package, if installed;
     *  3. any visible app declaring a receiver for the PebbleKit SEND
     *     broadcast (see the `<intent>` query in the manifest), preferring
     *     names containing "pebble";
     *  4. any installed (visible) package with "pebble" in its name.
     *
     * Returns null when nothing looks like a Pebble phone app.
     */
    fun phoneAppPackage(context: Context): String? {
        val pm = context.packageManager
        if (isPackageInstalled(pm, PEBBLE_PACKAGE_LEGACY)) return PEBBLE_PACKAGE_LEGACY
        if (isPackageInstalled(pm, PEBBLE_PACKAGE_CORE)) return PEBBLE_PACKAGE_CORE

        val receivers = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryBroadcastReceivers(
                    Intent(ACTION_SEND),
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryBroadcastReceivers(Intent(ACTION_SEND), 0)
            }
        }.getOrDefault(emptyList())
        val candidates = receivers.map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()
        candidates.firstOrNull { it.contains("pebble", ignoreCase = true) }?.let { return it }
        candidates.firstOrNull()?.let { return it }

        return runCatching {
            pm.getInstalledPackages(0).map { it.packageName }
        }.getOrDefault(emptyList()).firstOrNull {
            it.contains("pebble", ignoreCase = true) && it != context.packageName
        }
    }

    private fun isPackageInstalled(pm: android.content.pm.PackageManager, pkg: String): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
            true
        }.getOrDefault(false)

    fun appUuid(context: Context): UUID {
        val raw = dev.ian.openpebble.DoorStore.pebbleUuid(context)
        return runCatching { UUID.fromString(raw) }.getOrElse {
            Log.e(TAG, "invalid pebble uuid '$raw', using default")
            UUID.fromString(dev.ian.openpebble.DoorStore.DEFAULT_PEBBLE_UUID)
        }
    }

    fun send(context: Context, dict: Dict) {
        val target = phoneAppPackage(context) ?: PEBBLE_PACKAGE_LEGACY
        val intent = if (target == PEBBLE_PACKAGE_CORE) {
            // Core format: UUID Serializable + JSON dict.
            Intent(ACTION_SEND).setPackage(target)
                .putExtra(EXTRA_UUID, appUuid(context))
                .putExtra(EXTRA_MSG_DATA, dict.json())
        } else {
            val msgData = uuidBytes(appUuid(context)) + dict.bytes()
            Intent(ACTION_SEND).setPackage(target).putExtra(EXTRA_MSG_DATA, msgData)
        }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.e(TAG, "send failed (target=$target)", it) }
        Log.i(TAG, "sent to $target: ${dict.summary()}")
    }

    fun startWatchApp(context: Context) {
        val target = phoneAppPackage(context) ?: PEBBLE_PACKAGE_LEGACY
        val intent = if (target == PEBBLE_PACKAGE_CORE) {
            Intent(ACTION_START).setPackage(target)
                .putExtra(EXTRA_UUID, appUuid(context))
        } else {
            Intent(ACTION_START).setPackage(target)
                .putExtra(EXTRA_APP_UUID, uuidBytes(appUuid(context)))
        }
        runCatching { context.sendBroadcast(intent) }
    }

    fun stopWatchApp(context: Context) {
        val target = phoneAppPackage(context) ?: PEBBLE_PACKAGE_LEGACY
        val intent = if (target == PEBBLE_PACKAGE_CORE) {
            Intent(ACTION_STOP).setPackage(target)
                .putExtra(EXTRA_UUID, appUuid(context))
        } else {
            Intent(ACTION_STOP).setPackage(target)
                .putExtra(EXTRA_APP_UUID, uuidBytes(appUuid(context)))
        }
        runCatching { context.sendBroadcast(intent) }
    }

    /** Acknowledge an inbound AppMessage; the watch side retries until this arrives. */
    fun ack(context: Context, transactionId: Int) {
        if (transactionId < 0) return
        val target = phoneAppPackage(context) ?: PEBBLE_PACKAGE_LEGACY
        val intent = Intent(ACTION_ACK).setPackage(target)
            .putExtra(EXTRA_TRANSACTION_ID, transactionId)
        runCatching { context.sendBroadcast(intent) }
    }

    fun isPebbleAppInstalled(context: Context): Boolean =
        phoneAppPackage(context) != null

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
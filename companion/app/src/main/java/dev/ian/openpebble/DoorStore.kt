package dev.ian.openpebble

import android.content.Context
import org.json.JSONArray

/** Tiny persistence layer over SharedPreferences (JSON). */
object DoorStore {

    private const val PREFS = "doors"
    private const val KEY_DOORS = "doors"
    private const val KEY_PEBBLE_UUID = "pebble_uuid"
    private const val KEY_API_TOKEN = "cloud_api_token"

    fun load(context: Context): MutableList<Door> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DOORS, null) ?: return mutableListOf()
        return runCatching { Door.listFromJsonArray(JSONArray(raw)) }.getOrDefault(mutableListOf())
    }

    fun save(context: Context, doors: List<Door>) {
        val a = JSONArray()
        doors.forEach { a.put(it.toJson()) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DOORS, a.toString()).apply()
    }

    fun findByKey(context: Context, id: Int, type: Int): Door? =
        load(context).firstOrNull { it.id == id && it.type == type }

    /**
     * UUID of the Pebble watch app this bridge serves. Defaults to the UUID in
     * pebble/appinfo.json; long-press the UUID row in the app to override it
     * (e.g. if you let CloudPebble generate its own UUID).
     */
    fun pebbleUuid(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PEBBLE_UUID, DEFAULT_PEBBLE_UUID)!!
    }

    fun setPebbleUuid(context: Context, uuid: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PEBBLE_UUID, uuid.trim().lowercase()).apply()
    }

    fun cloudApiToken(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_TOKEN, null)

    fun setCloudApiToken(context: Context, token: String?) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (token == null) remove(KEY_API_TOKEN) else putString(KEY_API_TOKEN, token)
            apply()
        }
    }

    const val DEFAULT_PEBBLE_UUID = "2f9a7c41-5e3b-4d88-a6c2-7b1e0d5f4a33"
}
package dev.ian.openpebble

import org.json.JSONArray
import org.json.JSONObject

/**
 * A curated door (entry or reader) from the user's Avigilon Alta organization.
 *
 * [id] and [type] are what the Alta Open app's unlock flow understands:
 * selecting this door triggers the first-party app with the item key
 * "<type>-<id>", which maps to the JS action `requestBatchUnlock`.
 */
data class Door(val name: String, val id: Int, val type: Int) {

    val typeLabel: String get() = if (type == TYPE_READER) "Reader" else "Entry"

    /** Item key understood by the Alta Open app's shortcut handler ("entry-123"). */
    fun itemKey(): String = "${if (type == TYPE_READER) "reader" else "entry"}-$id"

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("id", id)
        .put("type", type)

    companion object {
        const val TYPE_ENTRY = 0
        const val TYPE_READER = 1

        fun fromJson(o: JSONObject): Door? {
            val id = o.optInt("id", Int.MIN_VALUE)
            if (id == Int.MIN_VALUE) return null
            val name = o.optString("name").ifBlank { "Door $id" }
            val type = if (o.optInt("type", TYPE_ENTRY) == TYPE_READER) TYPE_READER else TYPE_ENTRY
            return Door(name, id, type)
        }

        fun listFromJsonArray(a: JSONArray): MutableList<Door> {
            val out = mutableListOf<Door>()
            for (i in 0 until a.length()) {
                fromJson(a.optJSONObject(i) ?: continue)?.let { out.add(it) }
            }
            return out
        }
    }
}
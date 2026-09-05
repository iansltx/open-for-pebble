package dev.ian.openpebble.cloud

import android.util.Log
import dev.ian.openpebble.Door
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for the (undocumented) Avigilon Alta "helium" REST API that
 * the Alta Open app itself uses. This exists only to help DISCOVER the doors
 * (entries/readers with their numeric ids and names) so the user can curate
 * the watch list. Unlocking never goes through this client — the first-party
 * app performs unlocks.
 *
 * Endpoints (verified against helium.prod.openpath.com, Sept 2026):
 *
 *   POST /auth/determineLoginCandidateNamespaces   {"email": "..."}
 *     → {"data": [{"id", "nickname", "namespaceType"{...}, "org"{id,name,...},
 *                  "matchedIdentity"{...}, ...}], "meta": ..., ...}
 *   POST /auth/login   {"namespaceId", "email", "password", "forMobileLogin": true}
 *     → {"data": {"token": "<jwt>", "tokenScopeList": [
 *           {"org": {"id","name","opal",...}, "user": {"id","opal"},
 *            "scope": [...]}, ...], ...}}
 *   GET  /orgs/{orgId}/users/{userId}/entries
 *     → {"data": [{"id", "name", "zone": {"id","name",
 *                  "site": {"id","name"}}, ...}], ...}   (48 entries on test org)
 *   GET  /orgs/{orgId}/users/{userId}/acus
 *     → {"data": [{"id", "name", "opal"}], ...}          (40 ACUs on test org)
 *   GET  /orgs/{orgId}/users/{userId}/acus/{acuId}?options=withShadows
 *   GET  /orgs/{orgId}/users/{userId}/credentials
 *   GET  /orgs/{orgId}/users/{userId}
 *
 * Auth is the raw login JWT in the `Authorization` header (no Bearer prefix).
 * No provisioning, no apiTokens map, no pulsar calls are needed for discovery.
 * (The app's `apiTokens` map is a client-side file populated during mobile
 * provisioning — irrelevant here since unlocking is delegated to the app.)
 */
object OpenCloud {

    private const val TAG = "OpenCloud"

    const val HELIUM = "https://helium.prod.openpath.com"

    // Mimic the first-party app's client header.
    private const val X_APP_VERSION = "Openpath Titanium/7.15.0.111 Android 15"

    val debugLog = StringBuilder()

    private fun log(s: String) {
        Log.d(TAG, s)
        if (debugLog.length > 100_000) debugLog.setLength(0)
        debugLog.append(s).append('\n')
    }

    class CloudException(message: String, val code: Int = -1) : Exception(message)

    // ------------------------------------------------------------------ HTTP

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        apiToken: String?,
    ): JSONObject {
        val url = URL(HELIUM + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-App-Version", X_APP_VERSION)
            apiToken?.let { setRequestProperty("Authorization", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Length", body.toString().toByteArray().size.toString())
            }
        }
        try {
            if (body != null) {
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            log("→ $method $path → $code")
            if (code >= 400) {
                log("   body: ${text.take(600)}")
                throw CloudException("HTTP $code for $path", code)
            }
            if (text.isBlank()) return JSONObject()
            val trimmed = text.trim()
            // All helium responses observed so far are a single JSON object; be
            // tolerant of arrays too.
            return if (trimmed.startsWith("[")) JSONObject().put("_array", JSONArray(trimmed)) else JSONObject(trimmed)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ auth

    /** Step 1: which namespace(s)/orgs does this email belong to? */
    fun determineNamespaces(email: String): JSONArray {
        val resp = request(
            "POST", "/auth/determineLoginCandidateNamespaces",
            JSONObject().put("email", email), null
        )
        // Verified shape: {"data": [...], "meta": ..., "totalCount": ...}.
        resp.optJSONArray("data")?.let {
            log("   namespaces: ${it.length()} candidate(s)")
            return it
        }
        val data = resp.optJSONObject("data") ?: resp
        // Unknown shape fallback: candidates could be under several keys.
        log("   determineLoginCandidateNamespaces keys: ${data.keys().asSequence().joinToString()}")
        for (key in listOf("namespaces", "loginCandidateNamespaces", "candidates", "results")) {
            data.optJSONArray(key)?.let { return it }
        }
        data.optJSONArray("_array")?.let { return it }
        // Fall back: whatever array is in there.
        val keys = data.keys().asSequence().toList()
        for (k in keys) {
            val arr = data.optJSONArray(k) ?: continue
            if (arr.length() > 0 && arr.optJSONObject(0) != null) {
                log("   guessing namespace list lives under '$k'")
                return arr
            }
        }
        throw CloudException("Could not find namespaces in response; see debug log")
    }

    /**
     * Step 2: log in. Returns [LoginResult] with orgId/userId/userOpal and the
     * raw api token. If multiple namespaces exist, pass the chosen
     * namespaceId (omit it entirely when null — a stale 0 breaks login).
     */
    fun login(email: String, password: String, namespaceId: Int?, totpCode: String?): LoginResult {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("forMobileLogin", true)
        if (namespaceId != null && namespaceId > 0) body.put("namespaceId", namespaceId)
        if (!totpCode.isNullOrBlank()) {
            body.put("mfa", JSONObject().put("totpCode", totpCode.trim()))
        }

        val resp = request("POST", "/auth/login", body, null)
        val data = resp.optJSONObject("data") ?: resp
        log("   login keys: ${data.keys().asSequence().joinToString()}")

        // Verified shape: data.token (JWT) + data.tokenScopeList[] whose
        // org-level entry carries org{id}, user{id, opal}. Identity-level
        // scopes carry no org/user — skip those.
        var token: String? = data.optString("token").takeIf { it.isNotBlank() }
        var orgId = 0
        var userId = 0
        var userOpal = ""
        val scopes = data.optJSONArray("tokenScopeList")
        if (scopes != null) {
            log("   token scopes: ${scopes.length()}")
            for (i in 0 until scopes.length()) {
                val s = scopes.optJSONObject(i) ?: continue
                val org = s.optJSONObject("org") ?: continue
                val user = s.optJSONObject("user") ?: continue
                orgId = org.optInt("id")
                userId = user.optInt("id")
                userOpal = user.optString("opal")
                if (orgId != 0 && userId != 0) break
            }
        }

        // Legacy fallback: some account types may still return the apiTokens
        // map (userOpal -> token) the first-party app persists after
        // provisioning, plus a nested user/org object.
        if (token.isNullOrBlank()) {
            val user = data.optJSONObject("user") ?: data
            val org = user.optJSONObject("org")
            if (orgId == 0) orgId = org?.optInt("id") ?: user.optInt("orgId")
            if (userId == 0) userId = user.optInt("id")
            if (userOpal.isBlank()) userOpal = user.optString("opal")
            val tokens = data.optJSONObject("apiTokens")
                ?: user.optJSONObject("apiTokens")
                ?: org?.optJSONObject("apiTokens")
            if (tokens != null) {
                token = if (userOpal.isNotBlank()) tokens.optString(userOpal).takeIf { it.isNotBlank() } else null
                if (token.isNullOrBlank() && tokens.keys().hasNext()) {
                    val first = tokens.keys().asSequence().first()
                    token = tokens.optString(first)
                    log("   using apiToken keyed by '$first'")
                }
            }
        }
        if (token.isNullOrBlank()) {
            log("   no token in login response — body: ${resp.toString().take(2000)}")
            throw CloudException("No token in login response; see debug log")
        }
        if (userId == 0 || orgId == 0) {
            log("   user/org ids missing — body: ${resp.toString().take(2000)}")
            throw CloudException("Missing user/org ids in login response; see debug log")
        }
        return LoginResult(orgId, userId, userOpal, token)
    }

    data class LoginResult(
        val orgId: Int,
        val userId: Int,
        val userOpal: String,
        val apiToken: String,
    )

    /**
     * Read an "id" that may arrive as a JSON number or a numeric string
     * (helium is inconsistent here). Returns null when absent/non-numeric.
     */
    fun optIdAsInt(obj: JSONObject?, key: String = "id"): Int? {
        if (obj == null || !obj.has(key)) return null
        // optInt returns 0 both for missing and for "0"; strings that are
        // numeric need an explicit conversion, so try the string form first.
        obj.optString(key, "").takeIf { it.isNotBlank() }?.toIntOrNull()?.let { return it }
        return obj.optInt(key, 0).takeIf { it != 0 }
    }

    // ------------------------------------------------------------- discovery

    /**
     * Door discovery, verified live (Sept 2026, 48 entries / 40 ACUs):
     *
     *  1. `GET .../entries` — the user's entries as `{id, name,
     *     zone: {name, site: {name}}}`. This is the primary source.
     *  2. `GET .../acus` — ACU `{id, name}` list; each ACU is described with
     *     `?options=withShadows` and walked for entries/readers maps (the
     *     shapes the app's native SDK parses as `acu_config.entries`).
     *  3. Credentials + user fetches as extra walk sources (defensive).
     */
    fun discoverDoors(login: LoginResult): List<Door> {
        val doors = mutableListOf<Door>()
        val acuIds = mutableSetOf<Int>()
        val base = "/orgs/${login.orgId}/users/${login.userId}"

        runCatching {
            request("GET", "$base/entries", null, login.apiToken)
        }.onFailure { log("   list entries failed: ${it.message}") }
            .getOrNull()?.let {
                val items = it.optJSONArray("data")
                log("   entries: ${items?.length() ?: 0}")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val e = items.optJSONObject(i) ?: continue
                        val id = e.optInt("id")
                        if (id == 0) continue
                        val name = e.optString("name").takeIf { it.isNotBlank() } ?: "Entry $id"
                        doors.add(Door(name, id, Door.TYPE_ENTRY))
                    }
                } else {
                    walkDoors(it, doors)
                }
            }

        runCatching {
            request("GET", "$base/acus", null, login.apiToken)
        }.onFailure { log("   list acus failed: ${it.message}") }
            .getOrNull()?.let {
                log("   acus: ${it.optJSONArray("data")?.length() ?: 0}")
                collectAcuIds(it, acuIds)
                walkDoors(it, doors)
            }

        val credsResp = runCatching {
            request("GET", "$base/credentials", null, login.apiToken)
        }.onFailure { log("   list credentials failed: ${it.message}") }.getOrNull()
        if (credsResp != null) {
            collectAcuIds(credsResp, acuIds)
            walkDoors(credsResp, doors)
        }

        // Sometimes the ACU list hangs off a user fetch — cheap to try.
        runCatching {
            request("GET", base, null, login.apiToken)
        }.getOrNull()?.let {
            collectAcuIds(it, acuIds)
            walkDoors(it, doors)
        }

        log("   describing ${acuIds.size} acu(s)")
        for (acuId in acuIds) {
            runCatching {
                request(
                    "GET",
                    "$base/acus/$acuId?options=withShadows",
                    null, login.apiToken
                )
            }.onFailure { log("   describe acu $acuId failed: ${it.message}") }
                .getOrNull()?.let {
                    walkDoors(it, doors)
                }
        }

        // De-duplicate (id+type) keeping the first (most human) name.
        val seen = mutableSetOf<Pair<Int, Int>>()
        return doors.filter { seen.add(it.id to it.type) }
    }

    /** Look for anything that looks like an ACU reference and note its id. */
    private fun collectAcuIds(node: Any?, out: MutableSet<Int>) {
        when (node) {
            is JSONObject -> {
                node.optInt("acuId").takeIf { it != 0 && it != -1 }?.let { out.add(it) }
                node.optJSONObject("acu")?.optInt("id")?.let { if (it != 0 && it != -1) out.add(it) }
                for (k in node.keys().asSequence()) collectAcuIds(node.opt(k), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectAcuIds(node.opt(i), out)
        }
    }

    /**
     * Recursively find "entries"/"readers" objects. The Alta app's acu_config
     * represents these as maps keyed by numeric id whose values hold at least
     * a name; be tolerant of arrays of {id, name} as well.
     */
    private fun walkDoors(node: Any?, out: MutableList<Door>) {
        when (node) {
            is JSONObject -> {
                for (key in listOf("entries", "readers")) {
                    val container = node.optJSONObject(key) ?: continue
                    val type = if (key == "readers") Door.TYPE_READER else Door.TYPE_ENTRY
                    for (idStr in container.keys().asSequence().toList()) {
                        val id = idStr.toIntOrNull() ?: continue
                        val obj = container.optJSONObject(idStr)
                        val name = obj?.optString("name")?.takeIf { it.isNotBlank() } ?: "$key $id"
                        out.add(Door(name, id, type))
                    }
                }
                // Also handle arrays of {id, name} under entries/readers keys.
                for (key in listOf("entries", "readers")) {
                    val arr = node.optJSONArray(key) ?: continue
                    val type = if (key == "readers") Door.TYPE_READER else Door.TYPE_ENTRY
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optInt("id")
                        if (id == 0) continue
                        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: "$key $id"
                        out.add(Door(name, id, type))
                    }
                }
                for (k in node.keys().asSequence()) walkDoors(node.opt(k), out)
            }
            is JSONArray -> for (i in 0 until node.length()) walkDoors(node.opt(i), out)
        }
    }
}
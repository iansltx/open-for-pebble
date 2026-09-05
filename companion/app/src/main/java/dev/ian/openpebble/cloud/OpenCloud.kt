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
 * Endpoints reconstructed from the app's React Native bundle:
 *
 *   POST /auth/determineLoginCandidateNamespaces   {"email": "..."}
 *   POST /auth/login   {"namespaceId", "email", "password", "forMobileLogin": true}
 *   GET  /orgs/{orgId}/users/{userId}/credentials
 *   GET  /orgs/{orgId}/users/{userId}/acus/{acuId}?options=withShadows
 *
 * The exact response shapes are not fully known; every step is logged into
 * [debugLog] so the first live sign-in (with the user's own account) tells us
 * exactly what to parse. Door discovery walks any returned JSON for
 * entries/readers keyed by numeric ids with names (the same structures the
 * app's native SDK parses as `acu_config.entries`).
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
        val data = resp.optJSONObject("data") ?: resp
        // Unknown shape: candidates could be under several keys — log everything.
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
     * namespaceId.
     */
    fun login(email: String, password: String, namespaceId: Int?, totpCode: String?): LoginResult {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("forMobileLogin", true)
        namespaceId?.let { body.put("namespaceId", it) }
        if (!totpCode.isNullOrBlank()) {
            body.put("mfa", JSONObject().put("totpCode", totpCode.trim()))
        }

        val resp = request("POST", "/auth/login", body, null)
        val data = resp.optJSONObject("data") ?: resp
        log("   login keys: ${data.keys().asSequence().joinToString()}")

        val user = data.optJSONObject("user") ?: data
        val org = user.optJSONObject("org")
        val orgId = org?.optInt("id") ?: user.optInt("orgId")
        val userId = user.optInt("id")
        val userOpal = user.optString("opal")

        // apiTokens is expected to be a map userOpal -> token (this matches how
        // the app's JS reads them via getApiTokenForUserOpal).
        var token: String? = null
        val tokens = data.optJSONObject("apiTokens")
            ?: user.optJSONObject("apiTokens")
            ?: org?.optJSONObject("apiTokens")
        if (tokens != null) {
            token = if (userOpal.isNotBlank()) tokens.optString(userOpal, null) else null
            if (token.isNullOrBlank() && tokens.keys().hasNext()) {
                val first = tokens.keys().asSequence().first()
                token = tokens.optString(first)
                log("   using apiToken keyed by '$first'")
            }
        }
        if (token.isNullOrBlank()) {
            log("   apiTokens missing — full login response: ${resp.toString().take(2000)}")
            throw CloudException("No apiTokens in login response; see debug log")
        }
        if (userId == 0 || orgId == 0) {
            log("   user/org ids missing — full login response: ${resp.toString().take(2000)}")
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

    // ------------------------------------------------------------- discovery

    /**
     * Best-effort door discovery. Walks listCredentials and (for any acu ids
     * we can find) the describeUserAcu responses, extracting every
     * "entries"/"readers" map of numeric id -> object with a name.
     *
     * This is deliberately defensive: exact response shapes get locked down
     * the first time it runs against a real account (see debugLog).
     */
    fun discoverDoors(login: LoginResult): List<Door> {
        val doors = mutableListOf<Door>()
        val acuIds = mutableSetOf<Int>()

        val credsResp = runCatching {
            request(
                "GET", "/orgs/${login.orgId}/users/${login.userId}/credentials",
                null, login.apiToken
            )
        }.onFailure { log("   list credentials failed: ${it.message}") }.getOrNull()
        if (credsResp != null) {
            log("   credentials keys: ${credsResp.keys().asSequence().joinToString()}")
            collectAcuIds(credsResp, acuIds)
            walkDoors(credsResp, doors)
        }

        // Sometimes the ACU list hangs off a user fetch — cheap to try.
        runCatching {
            request("GET", "/orgs/${login.orgId}/users/${login.userId}", null, login.apiToken)
        }.getOrNull()?.let {
            log("   user keys: ${it.keys().asSequence().joinToString()}")
            collectAcuIds(it, acuIds)
            walkDoors(it, doors)
        }

        for (acuId in acuIds) {
            runCatching {
                request(
                    "GET",
                    "/orgs/${login.orgId}/users/${login.userId}/acus/$acuId?options=withShadows",
                    null, login.apiToken
                )
            }.onFailure { log("   describe acu $acuId failed: ${it.message}") }
                .getOrNull()?.let {
                    log("   acu $acuId keys: ${it.keys().asSequence().joinToString()}")
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

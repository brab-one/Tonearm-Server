package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Who is calling: the apps send their usual Subsonic login (u + t/s, u + p, or apiKey) and this asks
 * Navidrome whether it's valid, and whether the user is one of its admins. Valid logins are remembered
 * for a few minutes; a user with many failed attempts from one client address is turned away for a while
 * (and so is an address trying many users).
 */
class NavidromeAuth(navidromeUrl: String, private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
    private val base = navidromeUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val valid = ConcurrentHashMap<String, Pair<Result.Ok, Long>>()
    /**
     * Failed logins of the last [FAILURE_WINDOW_MS], by client address and user and by address alone: when each
     * different login last failed, so a device retrying an old password counts once instead of as many guesses.
     */
    private val failures = ConcurrentHashMap<String, MutableMap<String, Long>>()
    /** When [failures] were last gone through for the ones that are over. */
    private val lastSweep = AtomicLong(0)

    sealed interface Result {
        data class Ok(val user: String, val admin: Boolean = false) : Result
        data object Missing : Result
        data object Rejected : Result
        data object TooMany : Result
        data class Unreachable(val message: String) : Result
    }

    fun check(query: Map<String, String>, client: String): Result {
        val auth = loginOf(query)
        if (!(auth.containsKey("apiKey") || (auth.containsKey("u") && (auth.containsKey("p") || (auth.containsKey("t") && auth.containsKey("s")))))) {
            return Result.Missing
        }
        val now = System.currentTimeMillis()
        val key = sha256(auth.toSortedMap().entries.joinToString("&"))
        // A login known to be good gets in, whatever failed from the same address.
        valid[key]?.takeIf { now < it.second }?.let { return it.first }
        // Counted per user: one device with an old password doesn't lock out everyone behind the same address.
        val who = client + "|" + (auth["u"]?.lowercase()?.take(64) ?: ("apiKey:" + sha256(auth["apiKey"].orEmpty()).take(16)))
        if (recentFailures(who, now) >= MAX_FAILURES || recentFailures(client, now) >= MAX_CLIENT_FAILURES) return Result.TooMany

        val endpoint = if (auth.containsKey("apiKey")) "tokenInfo" else "ping"
        val response = try {
            subsonic(endpoint, auth)
        } catch (e: Unreachable) {
            return Result.Unreachable(e.message.orEmpty())
        }
        if (response["status"]?.jsonPrimitive?.contentOrNull != "ok") {
            failed(who, key, now)
            failed(client, key, now)
            return Result.Rejected
        }
        val name = (if (endpoint == "tokenInfo") response["tokenInfo"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull else auth["u"])
            ?: return Result.Rejected
        // Users may look themselves up; Navidrome says there whether they're an admin. When it can't be asked, that
        // isn't a no: nothing is remembered, and the app tries again.
        val admin = try {
            subsonic("getUser", auth + ("username" to name))["user"]?.jsonObject?.get("adminRole")?.jsonPrimitive?.contentOrNull == "true"
        } catch (e: Unreachable) {
            return Result.Unreachable(e.message.orEmpty())
        }
        val ok = Result.Ok(name.lowercase(), admin)
        valid[key] = ok to now + VALID_MS
        if (valid.size > 10_000) valid.entries.removeIf { now >= it.value.second }
        return ok
    }

    /** How many different logins failed under [key] lately; the older ones are forgotten, and the key with them. */
    private fun recentFailures(key: String, now: Long): Int {
        var count = 0
        failures.computeIfPresent(key) { _, logins ->
            logins.values.removeIf { now - it > FAILURE_WINDOW_MS }
            count = logins.size
            logins.ifEmpty { null }
        }
        return count
    }

    private fun failed(key: String, login: String, now: Long) {
        failures.compute(key) { _, logins -> (logins ?: HashMap()).apply { put(login, now) } }
        // Addresses that stopped trying are let go of now and then, so the map can't keep growing. Not on every failure:
        // with many still trying, going through them all each time would keep everyone's logins waiting.
        val last = lastSweep.get()
        if (failures.size > MAX_TRACKED && now - last > SWEEP_MS && lastSweep.compareAndSet(last, now)) failures.keys.forEach { recentFailures(it, now) }
    }

    private class Unreachable(message: String) : Exception(message)

    private fun subsonic(method: String, params: Map<String, String>): kotlinx.serialization.json.JsonObject {
        val url = "$base/rest/$method.view?" + (params + mapOf("v" to "1.16.1", "c" to "tonearm-server", "f" to "json"))
            .entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val body = try {
            http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
        } catch (e: Exception) {
            throw Unreachable("Can't reach Navidrome at $base: ${e.message ?: e.javaClass.simpleName}")
        }
        return runCatching { json.parseToJsonElement(body).jsonObject["subsonic-response"]!!.jsonObject }.getOrNull()
            ?: throw Unreachable("Navidrome at $base didn't answer like a Subsonic server")
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private val AUTH_PARAMS = listOf("u", "t", "s", "p", "apiKey")

        /** The Subsonic login among a request's parameters. */
        fun loginOf(query: Map<String, String>): Map<String, String> = AUTH_PARAMS.mapNotNull { k -> query[k]?.let { k to it } }.toMap()
        private const val VALID_MS = 10 * 60_000L
        private const val FAILURE_WINDOW_MS = 5 * 60_000L
        /** Different failed logins for one user from one address. */
        private const val MAX_FAILURES = 20
        /** Different failed logins from one address, whoever for: trying one user after another. */
        private const val MAX_CLIENT_FAILURES = 200
        private const val MAX_TRACKED = 10_000
        /** How often at most the failures are gone through once there are more than [MAX_TRACKED]. */
        private const val SWEEP_MS = 30_000L
    }
}

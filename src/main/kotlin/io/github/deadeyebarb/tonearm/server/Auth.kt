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

/**
 * Who is calling: the apps send their usual Subsonic login (u + t/s, u + p, or apiKey) and this asks
 * Navidrome whether it's valid, and whether the user is one of its admins. Valid logins are remembered
 * for a few minutes; a client address with many failed attempts is turned away for a while.
 */
class NavidromeAuth(navidromeUrl: String, private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
    private val base = navidromeUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val valid = ConcurrentHashMap<String, Pair<Result.Ok, Long>>()
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()

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
        val recent = failures[client]?.let { list -> synchronized(list) { list.removeIf { now - it > FAILURE_WINDOW_MS }; list.size } } ?: 0
        if (recent >= MAX_FAILURES) return Result.TooMany
        val key = sha256(auth.toSortedMap().entries.joinToString("&"))
        valid[key]?.takeIf { now < it.second }?.let { return it.first }

        val endpoint = if (auth.containsKey("apiKey")) "tokenInfo" else "ping"
        val response = try {
            subsonic(endpoint, auth)
        } catch (e: Unreachable) {
            return Result.Unreachable(e.message.orEmpty())
        }
        if (response["status"]?.jsonPrimitive?.contentOrNull != "ok") {
            failures.computeIfAbsent(client) { mutableListOf() }.let { synchronized(it) { it += now } }
            return Result.Rejected
        }
        val name = (if (endpoint == "tokenInfo") response["tokenInfo"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull else auth["u"])
            ?: return Result.Rejected
        // Users may look themselves up; Navidrome says there whether they're an admin.
        val admin = runCatching {
            subsonic("getUser", auth + ("username" to name))["user"]?.jsonObject?.get("adminRole")?.jsonPrimitive?.contentOrNull == "true"
        }.getOrDefault(false)
        val ok = Result.Ok(name.lowercase(), admin)
        valid[key] = ok to now + VALID_MS
        if (valid.size > 10_000) valid.entries.removeIf { now >= it.value.second }
        return ok
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
        private const val MAX_FAILURES = 20
    }
}

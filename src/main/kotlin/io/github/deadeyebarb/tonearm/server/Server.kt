package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The Tonearm server's HTTP side. Everything lives under [basePath] (where the proxy sends it):
 * `api/<op>` for Connect, `lidarr/…` and `maloja/…` for those services with the server's keys,
 * `health` for Docker, and a short status text at the root.
 */
class TonearmServer(
    private val port: Int,
    basePath: String,
    private val auth: NavidromeAuth,
    dataDir: File,
    private val version: String,
    private val lidarr: LidarrProxy? = null,
    private val maloja: MalojaProxy? = null,
    private val recommendations: Recommendations? = null,
    private val discovery: Discovery? = null,
) {
    private val base = "/" + basePath.trim('/')
    private val hub = Hub()
    private val store = Store(File(dataDir, "store"))
    private lateinit var http: HttpServer

    fun start(): TonearmServer {
        http = HttpServer.create(InetSocketAddress(port), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    try {
                        handle(it)
                    } catch (e: Throwable) {
                        // An answer instead of a dropped connection (which proxies show as a bare 502). No query: it has the login.
                        System.err.println("${it.requestMethod} ${it.requestURI.rawPath} failed: $e")
                        e.printStackTrace()
                        runCatching { error(it, 500, "The Tonearm server failed: ${e.message ?: e.javaClass.simpleName}") }
                    }
                }
            }
            executor = ThreadPoolExecutor(4, 400, 60, TimeUnit.SECONDS, SynchronousQueue())
            start()
        }
        return this
    }

    val boundPort: Int get() = http.address.port

    fun stop() = http.stop(0)

    private fun handle(exchange: HttpExchange) {
        // Proxies differ in whether they pass the location's prefix on, so both ways work.
        val raw = exchange.requestURI.rawPath
        val path = (if (raw == base || raw.startsWith("$base/")) raw.removePrefix(base) else raw).trim('/')
        when {
            path == "health" -> return text(exchange, 200, "ok")
            path.isEmpty() -> return text(exchange, 200, "Tonearm server $version is running. The Tonearm apps use it at $base/api/.")
            !path.startsWith("api/") && !path.startsWith("lidarr/") && !path.startsWith("maloja/") -> return text(exchange, 404, "Not found")
        }
        val query = parseQuery(exchange.requestURI.rawQuery)
        val client = exchange.requestHeaders.getFirst("X-Real-IP") ?: exchange.requestHeaders.getFirst("X-Forwarded-For")?.substringBefore(',')?.trim()
            ?: exchange.remoteAddress.address.hostAddress
        val login = when (val result = auth.check(query, client)) {
            is NavidromeAuth.Result.Ok -> result
            NavidromeAuth.Result.Missing -> return error(exchange, 401, "Sign in with your Navidrome login (u + t/s, u + p, or apiKey)")
            NavidromeAuth.Result.Rejected -> return error(exchange, 401, "Navidrome didn't accept that login")
            NavidromeAuth.Result.TooMany -> return error(exchange, 429, "Too many failed logins; try again in a few minutes")
            is NavidromeAuth.Result.Unreachable -> return error(exchange, 502, result.message)
        }
        val user = login.user
        val bytes = exchange.requestBody.use { it.readNBytes(MAX_PAYLOAD + 1) }
        if (bytes.size > MAX_PAYLOAD) return error(exchange, 413, "Payload too large")
        if (!path.startsWith("api/")) {
            val call = ProxyCall(exchange.requestMethod.uppercase(), path.substringAfter('/'), exchange.requestURI.rawQuery, exchange.requestHeaders.getFirst("Content-Type"), bytes)
            val answer = when {
                path.startsWith("lidarr/") -> lidarr?.handle(call, login.admin) ?: Upstream.error(404, "This Tonearm server has no Lidarr set up")
                else -> maloja?.handle(call, user, login.admin) ?: Upstream.error(404, "This Tonearm server has no Maloja set up")
            }
            return relay(exchange, answer)
        }
        val op = path.removePrefix("api/")
        val payload = bytes.decodeToString()
        val device = query["device"]
        val users = hub.of(user)
        val response: JsonObject = when (op) {
            "hello" -> buildJsonObject {
                put("server", "tonearm")
                put("protocol", PROTOCOL)
                put("version", version)
                put("user", user)
                put("admin", login.admin)
                put("lidarr", lidarr?.available(login.admin) == true)
                put("lidarrAdmin", lidarr != null && login.admin)
                put("maloja", maloja?.available(user, login.admin) == true)
                put("recommendations", recommendations != null)
                put("discovery", discovery != null)
            }
            "publish" -> {
                if (device.isNullOrEmpty() || payload.isEmpty()) return error(exchange, 400, "device and payload are required")
                buildJsonObject { put("ok", true); put("seq", users.publish(device, payload)) }
            }
            "devices" -> buildJsonObject {
                put("devices", JsonArray(users.list().map { d ->
                    buildJsonObject {
                        put("id", d.id)
                        put("online", d.online)
                        put("secondsSinceSeen", d.secondsSinceSeen)
                        put("state", d.state?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }))
            }
            "send" -> {
                val target = query["target"]
                if (device.isNullOrEmpty() || target.isNullOrEmpty() || payload.isEmpty()) return error(exchange, 400, "device, target and payload are required")
                buildJsonObject { put("ok", true); put("seq", users.send(device, target, payload)) }
            }
            "poll" -> {
                if (device.isNullOrEmpty()) return error(exchange, 400, "device is required")
                val after = query["after"]?.toLongOrNull() ?: 0
                val wait = (query["wait"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_WAIT_SECONDS)
                val (commands, seq) = users.poll(device, after, wait * 1000L)
                buildJsonObject {
                    put("commands", JsonArray(commands.map { c -> buildJsonObject { put("seq", c.seq); put("from", c.from); put("payload", c.payload) } }))
                    put("seq", seq)
                }
            }
            "forget" -> {
                if (!device.isNullOrEmpty()) users.forget(device)
                buildJsonObject { put("ok", true) }
            }
            "get" -> {
                val key = query["key"]
                if (!Store.validKey(key)) return error(exchange, 400, "a valid key is required")
                val entry = store.get(user, key!!)
                buildJsonObject { put("value", entry.value?.let(::JsonPrimitive) ?: JsonNull); put("version", entry.version) }
            }
            "put" -> {
                val key = query["key"]
                val ifVersion = query["ifVersion"]?.toLongOrNull()
                if (!Store.validKey(key) || ifVersion == null || payload.isEmpty()) return error(exchange, 400, "key, ifVersion and payload are required")
                val (ok, current) = store.put(user, key!!, payload, ifVersion)
                buildJsonObject {
                    put("ok", ok)
                    put("conflict", !ok)
                    put("value", if (ok) JsonNull else current.value?.let(::JsonPrimitive) ?: JsonNull)
                    put("version", current.version)
                }
            }
            "recommendations" -> {
                val engine = recommendations ?: return error(exchange, 404, "This Tonearm server has no Ollama set up")
                val seed = query["seed"]?.trim()?.take(300)?.takeIf { it.isNotEmpty() }
                engine.toJson(engine.get(user, NavidromeAuth.loginOf(query), refresh = query["refresh"] == "true", seed = seed))
            }
            "search" -> {
                val engine = discovery ?: return error(exchange, 404, "Discovery is off on this Tonearm server")
                val q = query["q"]?.trim()?.take(200)?.takeIf { it.isNotEmpty() } ?: return error(exchange, 400, "q is required")
                try {
                    engine.toJson(engine.search(q))
                } catch (e: Exception) {
                    return error(exchange, 502, "Deezer: ${e.message}")
                }
            }
            "aisearch" -> {
                val engine = recommendations ?: return error(exchange, 404, "This Tonearm server has no Ollama set up")
                val q = query["q"]?.trim()?.take(200)?.takeIf { it.isNotEmpty() } ?: return error(exchange, 400, "q is required")
                engine.toJson(engine.search(q))
            }
            "discover" -> {
                val engine = discovery ?: return error(exchange, 404, "Discovery is off on this Tonearm server")
                engine.toJson(engine.picks(user, NavidromeAuth.loginOf(query), refresh = query["refresh"] == "true"))
            }
            "similar" -> {
                val engine = discovery ?: return error(exchange, 404, "Discovery is off on this Tonearm server")
                val artist = query["artist"]?.trim()?.takeIf { it.isNotEmpty() } ?: return error(exchange, 400, "artist is required")
                val (name, similar) = try {
                    engine.similar(user, NavidromeAuth.loginOf(query), artist)
                } catch (e: Exception) {
                    return error(exchange, 502, "Couldn't look up similar artists: ${e.message}")
                }
                engine.toJson(name, similar)
            }
            else -> return error(exchange, 404, "Unknown op")
        }
        send(exchange, 200, response)
    }

    private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
        val k = part.substringBefore('=')
        val v = part.substringAfter('=', "")
        URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
    }

    private fun error(exchange: HttpExchange, code: Int, message: String) = send(exchange, code, buildJsonObject { put("error", message) })

    private fun send(exchange: HttpExchange, code: Int, body: JsonElement) {
        val bytes = body.toString().encodeToByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun relay(exchange: HttpExchange, answer: ProxyAnswer) {
        exchange.responseHeaders.set("Content-Type", answer.contentType ?: "application/octet-stream")
        exchange.responseHeaders.set("Cache-Control", answer.cacheControl ?: "no-store")
        exchange.sendResponseHeaders(answer.status, if (answer.body.isEmpty()) -1 else answer.body.size.toLong())
        exchange.responseBody.use { if (answer.body.isNotEmpty()) it.write(answer.body) }
    }

    private fun text(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val PROTOCOL = 2
        const val MAX_PAYLOAD = 2_000_000
        const val MAX_WAIT_SECONDS = 25
    }
}

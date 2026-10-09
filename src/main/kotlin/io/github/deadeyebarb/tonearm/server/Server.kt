package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.ZoneId
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The Tonearm server's HTTP side. Everything lives under [basePath] (where the proxy sends it):
 * `api/<op>` for Connect, picks and listening history, `lidarr/…` for Lidarr with the server's key,
 * `health` for Docker, and a short status text at the root.
 */
class TonearmServer(
    private val port: Int,
    basePath: String,
    private val auth: NavidromeAuth,
    dataDir: File,
    private val version: String,
    private val lidarr: LidarrProxy? = null,
    private val recommendations: Recommendations? = null,
    private val discovery: Discovery? = null,
    private val history: History = History(dataDir),
    /** Brings a Maloja's history over once, for the first user it's meant for. */
    private val malojaImport: ((user: String, admin: Boolean) -> Unit)? = null,
    /** Proxies whose word on who they forwarded a request for is taken (X-Forwarded-For, X-Real-IP). */
    private val trustedProxies: List<AddressRange> = AddressRange.LOCAL,
    /** Radio, mixes from a sentence and daily mixes; none in tests that don't need them. */
    private val mixes: Mixes? = null,
    /** Where each device left off, for Continue from the desktop. */
    private val handoffs: Handoffs = Handoffs(dataDir),
) {
    private val base = "/" + basePath.trim('/')
    private val json = Json { ignoreUnknownKeys = true }
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
            !path.startsWith("api/") && !path.startsWith("lidarr/") -> return text(exchange, 404, "Not found")
        }
        val query = parseQuery(exchange.requestURI.rawQuery)
        val login = when (val result = auth.check(query, clientOf(exchange))) {
            is NavidromeAuth.Result.Ok -> result
            NavidromeAuth.Result.Missing -> return error(exchange, 401, "Sign in with your Navidrome login (u + t/s, u + p, or apiKey)")
            NavidromeAuth.Result.Rejected -> return error(exchange, 401, "Navidrome didn't accept that login")
            NavidromeAuth.Result.TooMany -> return error(exchange, 429, "Too many failed logins; try again in a few minutes")
            is NavidromeAuth.Result.Unreachable -> return error(exchange, 502, result.message)
        }
        val user = login.user
        malojaImport?.invoke(user, login.admin)
        val op = path.removePrefix("api/")
        // Device states and commands are held in memory, so they get less room than the rest.
        val limit = when (op) {
            "publish" -> MAX_STATE
            "send" -> MAX_COMMAND
            "put" -> MAX_DOCUMENT
            else -> MAX_PAYLOAD
        }
        val bytes = exchange.requestBody.use { it.readNBytes(limit + 1) }
        if (bytes.size > limit) return error(exchange, 413, "Payload too large (at most ${limit / 1000} KB here)")
        if (!path.startsWith("api/")) {
            val call = ProxyCall(exchange.requestMethod.uppercase(), path.substringAfter('/'), exchange.requestURI.rawQuery, exchange.requestHeaders.getFirst("Content-Type"), bytes)
            return relay(exchange, lidarr?.handle(call, login.admin, user) ?: Upstream.error(404, "This Tonearm server has no Lidarr set up"))
        }
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
                put("recommendations", recommendations != null)
                put("discovery", discovery != null)
                put("history", true)
                put("dislikes", true)
                put("stats", true)
                put("resume", true)
                put("radio", mixes != null)
                put("mix", mixes?.hasAi == true)
                put("dailyMixes", mixes != null)
                recommendations?.let { put("ai", it.label) }
                // Where this user's weekly picks download to, when each user has their own.
                lidarr?.takeIf { it.available(login.admin) }?.picksFolder(user)?.let { put("picksFolder", it) }
            }
            "picksfolder" -> {
                val proxy = lidarr?.takeIf { it.available(login.admin) } ?: return error(exchange, 404, "This Tonearm server has no Lidarr for you")
                val folder = try {
                    proxy.ensurePicksFolder(user)
                } catch (e: Exception) {
                    return error(exchange, 502, "Lidarr: ${e.message}")
                } ?: return error(exchange, 404, "This Tonearm server has no picks folders (PICKS_FOLDER)")
                buildJsonObject {
                    put("path", folder.path)
                    put("ready", folder.ready)
                    folder.problem?.let { put("problem", it) }
                }
            }
            "publish" -> {
                if (device.isNullOrEmpty() || payload.isEmpty()) return error(exchange, 400, "device and payload are required")
                val seq = users.publish(device, payload)
                handoffs.record(user, device, payload)
                buildJsonObject { put("ok", true); put("seq", seq) }
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
                val seq = users.send(device, target, payload) ?: return error(exchange, 404, "That device isn't connected to the Tonearm server")
                buildJsonObject { put("ok", true); put("seq", seq) }
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
                if (!device.isNullOrEmpty()) {
                    users.forget(device)
                    handoffs.gone(user, device)
                }
                buildJsonObject { put("ok", true) }
            }
            "resume" -> handoffs.toJson(handoffs.latest(user, device?.ifEmpty { null }) { users.isOnline(it) })
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
                    ?: return error(exchange, 413, "That's more than the Tonearm server keeps for you (${Store.MAX_KEYS} documents, ${Store.MAX_CHARS / 1_000_000} MB)")
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
                // The answer is shared by everyone who searches the same; what this user disliked is left out here.
                val found = engine.search(q)
                engine.toJson(found.copy(hits = found.hits.filterNot { hit ->
                    history.isDismissed(user, hit.artist) || hit.title?.let { history.isSongDisliked(user, hit.artist, it) } == true
                }))
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
            "played" -> {
                val plays = try {
                    json.decodeFromString(ListSerializer(Played.serializer()), payload)
                } catch (e: Exception) {
                    return error(exchange, 400, "payload has to be a list of plays")
                }
                if (plays.size > 1_000) return error(exchange, 413, "At most 1000 plays at once")
                buildJsonObject { put("ok", true); put("added", history.add(user, plays)) }
            }
            "listening" -> {
                val now = System.currentTimeMillis()
                // A calendar week or month from the app (from, to), or the last few days.
                val days = query["days"]?.toLongOrNull()?.takeIf { it > 0 }
                val since = query["from"]?.toLongOrNull() ?: days?.let { now - it * 24 * 3_600_000L } ?: 0
                val to = query["to"]?.toLongOrNull() ?: now
                fun limit(name: String, default: Int) = (query[name]?.toIntOrNull() ?: default).coerceIn(0, 1_000)
                history.toJson(history.listening(
                    user, since, limit("artists", 50), limit("songs", 0), limit("recent", 0),
                    to = to, albums = limit("albums", 0), before = query["before"]?.toLongOrNull(),
                ))
            }
            "radio" -> {
                val engine = mixes ?: return error(exchange, 404, "This Tonearm server has no radio")
                val seed = query["seed"]
                if (seed != Mixes.SONG && seed != Mixes.PICKS) return error(exchange, 400, "seed has to be song or picks")
                fun param(name: String) = query[name]?.trim()?.take(300)?.ifEmpty { null }
                val id = param("id")
                val artist = param("artist")
                val title = param("title")
                if (seed == Mixes.SONG && id == null && (artist == null || title == null)) return error(exchange, 400, "seed=song needs id, or artist and title")
                // The queue, oldest first, as the apps key songs.
                val exclude = if (payload.isBlank()) emptyList() else try {
                    (json.parseToJsonElement(payload).jsonObject["exclude"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                } catch (e: Exception) {
                    return error(exchange, 400, "the body has to be {\"exclude\": [song keys]}")
                }
                val count = (query["count"]?.toIntOrNull() ?: 25).coerceIn(5, 50)
                try {
                    engine.toJson(engine.radio(user, NavidromeAuth.loginOf(query), seed, id, artist, title, exclude.takeLast(Mixes.MAX_EXCLUDE), count))
                } catch (e: LibraryException) {
                    return error(exchange, 502, "Couldn't read your library: ${e.message}")
                }
            }
            "mix" -> {
                val engine = mixes?.takeIf { it.hasAi } ?: return error(exchange, 404, "This Tonearm server has no AI set up")
                val prompt = query["prompt"]?.trim()?.take(300)?.takeIf { it.isNotEmpty() } ?: return error(exchange, 400, "prompt is required")
                engine.toJson(engine.mix(user, NavidromeAuth.loginOf(query), prompt, refresh = query["refresh"] == "true"))
            }
            "dailymixes" -> {
                val engine = mixes ?: return error(exchange, 404, "This Tonearm server has no daily mixes")
                val tz = query["tz"]?.trim()?.takeIf { it.isNotEmpty() }
                val zone = if (tz == null) ZoneId.systemDefault() else runCatching { ZoneId.of(tz) }.getOrNull() ?: return error(exchange, 400, "tz isn't a time zone")
                engine.toJson(engine.daily(user, NavidromeAuth.loginOf(query), zone))
            }
            "dismiss" -> {
                val artist = query["artist"]?.trim()?.take(300)?.takeIf { it.isNotEmpty() } ?: return error(exchange, 400, "artist is required")
                history.dismiss(user, artist, query["album"]?.trim()?.take(300))
                buildJsonObject { put("ok", true) }
            }
            "dislike" -> {
                val artist = query["artist"]?.trim()?.take(300)?.takeIf { it.isNotEmpty() } ?: return error(exchange, 400, "artist is required")
                val title = query["title"]?.trim()?.take(300)?.takeIf { it.isNotEmpty() } ?: return error(exchange, 400, "title is required")
                history.dislike(user, artist, title, query["album"]?.trim()?.take(300), on = query["on"] != "false")
                buildJsonObject { put("ok", true) }
            }
            "disliked" -> buildJsonObject {
                put("songs", JsonArray(history.dislikedSongs(user).map { json.encodeToJsonElement(DislikedSong.serializer(), it) }))
                put("artists", JsonArray(history.dismissed(user).filter { it.album == null }.map { JsonPrimitive(it.artist) }))
            }
            else -> return error(exchange, 404, "Unknown op")
        }
        send(exchange, 200, response)
    }

    /** Addresses that sent forwarding headers without being trusted, said in the log once. */
    private val ignoredProxies = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Who's asking, for counting failed logins: the address the request came from or, when that's a trusted proxy, the
     * one the proxy got it from. Anyone else's forwarding headers are theirs to make up, so they don't count.
     */
    private fun clientOf(exchange: HttpExchange): String {
        val peer = exchange.remoteAddress.address
        if (trustedProxies.none { it.contains(peer) }) {
            // Everyone behind a proxy that isn't trusted counts as one client: the log says so, once for each of the first few.
            val header = listOf("X-Forwarded-For", "X-Real-IP").firstOrNull { exchange.requestHeaders.containsKey(it) }
            if (header != null && ignoredProxies.size < 20 && ignoredProxies.add(peer.hostAddress)) {
                println("Ignoring $header from ${peer.hostAddress}, which isn't a trusted proxy; if it's your proxy, add it to TRUSTED_PROXIES")
            }
            return peer.hostAddress
        }
        // Each proxy adds the address it heard from at the end: the last one that isn't a trusted proxy is the client.
        val hops = exchange.requestHeaders["X-Forwarded-For"].orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val forwarded = hops.lastOrNull { hop -> AddressRange.literal(hop)?.let { a -> trustedProxies.none { it.contains(a) } } ?: true } ?: hops.firstOrNull()
        return (forwarded ?: exchange.requestHeaders.getFirst("X-Real-IP")?.trim()?.ifEmpty { null } ?: peer.hostAddress).take(64)
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
        /** A device's published state: the desktop's has at most 300 songs of its queue. */
        const val MAX_STATE = 512_000
        /** A command: the phone hands its whole queue over, which may be several thousand songs. */
        const val MAX_COMMAND = MAX_PAYLOAD
        /** A document in the store. */
        const val MAX_DOCUMENT = 512_000
        const val MAX_WAIT_SECONDS = 25
    }
}

/** An address or a block of them ("192.168.1.11", "172.16.0.0/12", "::1"), for proxies to trust. */
class AddressRange private constructor(private val network: ByteArray, private val bits: Int) {
    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false
        for (i in bytes.indices) {
            val left = bits - i * 8
            if (left <= 0) return true
            val mask = if (left >= 8) 0xff else 0xff shl (8 - left) and 0xff
            if ((bytes[i].toInt() xor network[i].toInt()) and mask != 0) return false
        }
        return true
    }

    companion object {
        /** Loopback, and Docker's own networks (172.16.0.0/12), which a proxy on the same host connects from. */
        val LOCAL = parseList("127.0.0.0/8, ::1, 172.16.0.0/12")

        /** A list like TRUSTED_PROXIES ("192.168.1.11, 10.0.0.0/8"); throws on an entry that isn't an address or block. */
        fun parseList(text: String): List<AddressRange> = text.split(',', ' ').filter { it.isNotBlank() }.map { entry ->
            parse(entry) ?: throw IllegalArgumentException("TRUSTED_PROXIES has \"$entry\"; give IP addresses or blocks like 172.16.0.0/12, separated by commas")
        }

        fun parse(text: String): AddressRange? {
            val address = literal(text.substringBefore('/')) ?: return null
            val max = address.address.size * 8
            val bits = if ('/' in text) text.substringAfter('/').toIntOrNull()?.takeIf { it in 0..max } ?: return null else max
            return AddressRange(address.address, bits)
        }

        /**
         * The gateway of this container's network, from [routes] (/proc/net/route), or null. A proxy on the same host
         * connects from there, also when Docker gave the network a block outside 172.16.0.0/12 (192.168.16.0/20, say).
         */
        fun gateway(routes: File = File("/proc/net/route")): InetAddress? = runCatching {
            // Iface, Destination, Gateway, Flags, RefCnt, Use, Metric, Mask…: the default route goes everywhere through a gateway.
            val route = routes.readLines().drop(1).map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size > 7 && it[1] == "00000000" && it[7] == "00000000" && it[2] != "00000000" } ?: return null
            // A number in the kernel's byte order: little-endian on amd64 and arm64.
            val number = route[2].toLong(16)
            InetAddress.getByAddress(ByteArray(4) { (number shr 8 * it).toByte() })
        }.getOrNull()

        /** An IP address as written, or null; never a name, which would be looked up. */
        fun literal(text: String): InetAddress? {
            val parts = text.split('.')
            if (parts.size == 4 && parts.all { p -> p.length in 1..3 && p.all { it in '0'..'9' } && p.toInt() <= 255 }) {
                return InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
            }
            // Hex digits and colons, starting with one of them: Java reads that as an IPv6 address, never as a name.
            fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' || c == ':'
            if (':' !in text || !hex(text[0]) || !text.all { hex(it) || it == '.' }) return null
            return runCatching { InetAddress.getByName(text) }.getOrNull()
        }
    }
}

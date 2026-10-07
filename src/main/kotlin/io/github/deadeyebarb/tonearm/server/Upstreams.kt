package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Plain API paths only: no "." or ".." segments that the upstream would resolve elsewhere. */
fun validPath(pattern: Regex, path: String) = pattern.matches(path) && path.split('/').none { segment -> segment.all { it == '.' } }

/** A call the apps make to Lidarr or Maloja through this server, and what comes back. */
data class ProxyCall(val method: String, val path: String, val rawQuery: String?, val contentType: String?, val body: ByteArray)

data class ProxyAnswer(val status: Int, val contentType: String?, val body: ByteArray, val cacheControl: String? = null)

/**
 * Lidarr or Maloja as this server reaches them, with their API key, which never leaves the server.
 * The apps' own login parameters are taken off before calls go on.
 */
class Upstream(
    val name: String,
    baseUrl: String,
    private val keyHeader: String?,
    private val key: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    private val base = baseUrl.trimEnd('/')

    fun send(call: ProxyCall, extraQuery: List<Pair<String, String>> = emptyList(), body: ByteArray = call.body): ProxyAnswer {
        val query = (keptQuery(call.rawQuery) + extraQuery.map { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, Charsets.UTF_8) })
            .joinToString("&")
        val request = HttpRequest.newBuilder(URI.create("$base/${call.path}" + if (query.isEmpty()) "" else "?$query"))
            .timeout(Duration.ofSeconds(90))
            .method(call.method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
            .apply {
                keyHeader?.let { header(it, key) }
                header("Accept", "application/json, image/*;q=0.9, */*;q=0.5")
                if (body.isNotEmpty()) header("Content-Type", call.contentType ?: "application/json")
            }
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            ProxyAnswer(
                response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(null),
                response.body(),
                // Lidarr marks missing covers as cacheable for a year; only pass that on for real answers.
                response.headers().firstValue("Cache-Control").orElse(null).takeIf { response.statusCode() in 200..299 },
            )
        } catch (e: Exception) {
            error(502, "Can't reach $name: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** JSON from a GET, for the server's own checks. */
    fun getJson(path: String): String? {
        val answer = send(ProxyCall("GET", path, null, null, ByteArray(0)))
        return answer.body.decodeToString().takeIf { answer.status in 200..299 }
    }

    private fun keptQuery(raw: String?): List<String> = raw.orEmpty().split('&').filter { part ->
        part.isNotEmpty() && java.net.URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8).lowercase() !in DROPPED
    }

    companion object {
        /** The apps' Subsonic login and anything that could pass another key. */
        private val DROPPED = setOf("u", "t", "s", "p", "apikey", "v", "c", "f", "key")

        fun error(status: Int, message: String) =
            ProxyAnswer(status, "application/json", JsonObject(mapOf("error" to JsonPrimitive(message))).toString().encodeToByteArray())
    }
}

/**
 * Lidarr for everyone on Navidrome. Admins get all of it (Brainarr, weekly picks, removing music).
 * Everyone else can look things up, see what's downloading or wanted, and request artists and albums,
 * which land in one of Lidarr's own root folders with no tags; nothing gets deleted or reconfigured.
 */
class LidarrProxy(private val lidarr: Upstream, private val requestsForEveryone: Boolean) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var roots: Pair<Set<String>, Long>? = null

    fun available(admin: Boolean) = admin || requestsForEveryone

    fun handle(call: ProxyCall, admin: Boolean): ProxyAnswer {
        if (!validPath(PATH, call.path)) return Upstream.error(404, "Not a Lidarr API path")
        if (!available(admin)) return Upstream.error(403, "Requests through the Tonearm server are for Navidrome admins here")
        if (admin) return lidarr.send(call)
        val endpoint = call.path.removePrefix("api/v1/")
        val body = when (call.method) {
            "GET" -> if (READ.any { it.matches(endpoint) }) call.body else return denied()
            "POST" -> when (endpoint) {
                "artist" -> artistRequest(call.body) ?: return badRoot()
                "album" -> albumRequest(call.body) ?: return badRoot()
                "command" -> if (jsonObject(call.body)?.string("name") in COMMANDS) call.body else return denied()
                else -> return denied()
            }
            "PUT" -> if (endpoint == "album/monitor" && jsonObject(call.body)?.get("monitored")?.jsonPrimitive?.contentOrNull == "true") call.body else return denied()
            else -> return denied()
        }
        return lidarr.send(call, body = body)
    }

    private fun artistRequest(body: ByteArray): ByteArray? = jsonObject(body)?.let(::safeArtist)?.toString()?.encodeToByteArray()

    private fun albumRequest(body: ByteArray): ByteArray? {
        val album = jsonObject(body) ?: return null
        val artist = (album["artist"] as? JsonObject)?.let(::safeArtist) ?: return null
        return JsonObject(album + ("artist" to artist)).toString().encodeToByteArray()
    }

    /** An artist to add: into one of Lidarr's root folders (no path of its own), without tags. */
    private fun safeArtist(artist: JsonObject): JsonObject? {
        val root = artist.string("rootFolderPath") ?: return null
        if (root.trimEnd('/') !in rootFolders()) return null
        return JsonObject(artist - "path" + ("tags" to JsonArray(emptyList())))
    }

    private fun rootFolders(): Set<String> {
        val now = System.currentTimeMillis()
        roots?.takeIf { now - it.second < 10 * 60_000L }?.let { return it.first }
        val paths = lidarr.getJson("api/v1/rootfolder")?.let { text ->
            runCatching { json.parseToJsonElement(text).jsonArray.mapNotNull { it.jsonObject.string("path")?.trimEnd('/') }.toSet() }.getOrNull()
        }.orEmpty()
        if (paths.isNotEmpty()) roots = paths to now
        return paths
    }

    /** The album in Lidarr's metadata with this artist and title (any edition), or null; throws when the search fails. */
    fun findAlbum(pick: AiPick): AiPick? {
        val term = java.net.URLEncoder.encode("${pick.artist} ${pick.album}", Charsets.UTF_8)
        val answer = lidarr.send(ProxyCall("GET", "api/v1/album/lookup", "term=$term", null, ByteArray(0)))
        if (answer.status != 200) throw IllegalStateException("Lidarr's album search answered ${answer.status}")
        val hit = json.parseToJsonElement(answer.body.decodeToString()).jsonArray.map { it.jsonObject }.firstOrNull { album ->
            Recommendations.bareTitle(album.string("title").orEmpty()) == Recommendations.bareTitle(pick.album) &&
                Recommendations.normalize((album["artist"] as? JsonObject)?.string("artistName").orEmpty()) == Recommendations.normalize(pick.artist)
        } ?: return null
        return AiPick(
            (hit["artist"] as JsonObject).string("artistName") ?: pick.artist,
            hit.string("title") ?: pick.album,
            hit.string("releaseDate")?.take(4)?.toIntOrNull() ?: pick.year,
            pick.why,
        )
    }

    private fun jsonObject(body: ByteArray) = runCatching { json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun denied() = Upstream.error(403, "Only Navidrome admins can do that through the Tonearm server")

    private fun badRoot() = Upstream.error(400, "Requests have to go into one of Lidarr's root folders")

    companion object {
        val PATH = Regex("^api/v1/[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$")
        private val READ = listOf(
            "system/status", "rootfolder", "qualityprofile", "metadataprofile", "tag",
            "artist", "artist/\\d+", "artist/lookup", "album", "album/\\d+", "album/lookup", "search",
            "queue", "queue/details", "wanted/missing", "wanted/cutoff", "command/\\d+", "track",
            "mediacover/(artist|album)/\\d+/[A-Za-z0-9_.-]+",
        ).map { Regex("^$it$") }
        private val COMMANDS = setOf("AlbumSearch", "ArtistSearch")
    }
}

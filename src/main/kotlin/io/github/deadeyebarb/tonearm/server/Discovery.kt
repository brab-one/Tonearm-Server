package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** An artist the library doesn't have, with the album to start with. */
@Serializable
data class DiscoveryPick(
    val artist: String,
    val album: String? = null,
    val year: Int? = null,
    val imageUrl: String? = null,
    val coverUrl: String? = null,
    /** The user's artists that led here, most telling first. */
    val because: List<String> = emptyList(),
)

@Serializable
data class DiscoveryPicks(val picks: List<DiscoveryPick> = emptyList(), val madeAt: Long = 0, val running: Boolean = false, val problem: String? = null)

/** What Deezer finds for a search: songs, albums and artists, best first. */
@Serializable
data class WebSearch(val songs: List<WebSong> = emptyList(), val albums: List<WebAlbum> = emptyList(), val artists: List<SimilarArtist> = emptyList())

@Serializable
data class WebSong(val title: String, val artist: String, val album: String? = null, val duration: Int? = null, val coverUrl: String? = null)

@Serializable
data class WebAlbum(val title: String, val artist: String, val coverUrl: String? = null, val type: String? = null)

/** An artist like another one, and whether the library has them. */
@Serializable
data class SimilarArtist(val artist: String, val imageUrl: String? = null, val fans: Long = 0, val inLibrary: Boolean = false)

/**
 * Discovery without an AI: the artists a user plays and likes most on Navidrome (asked as them, with their
 * login), their related artists on Deezer's public API, ranked by how many of the user's artists point to
 * them, without what the library has. Each comes with its best-known studio album. Kept for a day, and made
 * in the background: a slow Deezer or Navidrome never keeps a request (and a proxy in front) waiting.
 */
class Discovery(
    private val navidromeUrl: String,
    dataDir: File,
    private val deezer: String = "https://api.deezer.com",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    private val dir = File(dataDir, "discovery").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** Deezer answers by URL, for a week: artists' relations and albums barely change. */
    private val cache = ConcurrentHashMap<String, Pair<JsonObject, Long>>()
    private val pool = Executors.newFixedThreadPool(4) { Thread(it, "discovery").apply { isDaemon = true } }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "discovery-picks").apply { isDaemon = true } }
    private val running = ConcurrentHashMap.newKeySet<String>()
    /** Each user's library artists for a few minutes, for "similar" on artist pages. */
    private val libraries = ConcurrentHashMap<String, Pair<Set<String>, Long>>()

    /** The user's picks; new ones are started when asked to or when they're a day old ([DiscoveryPicks.running] until then). */
    fun picks(user: String, login: Map<String, String>, refresh: Boolean): DiscoveryPicks {
        val stored = load(user)
        val due = refresh || stored.madeAt == 0L || System.currentTimeMillis() - stored.madeAt >= MAX_AGE_MS
        if (due && running.add(user)) {
            worker.execute {
                try {
                    val made = DiscoveryPicks(make(login), System.currentTimeMillis())
                    save(user, if (made.picks.isEmpty() && stored.picks.isNotEmpty()) stored.copy(madeAt = made.madeAt, problem = "Nothing new to discover this time") else made)
                } catch (e: Exception) {
                    save(user, load(user).copy(problem = "Discovery: ${e.message ?: e.javaClass.simpleName}"))
                } finally {
                    running.remove(user)
                }
            }
        }
        return load(user).copy(running = user in running)
    }

    /** Artists like [artist] (by name), marked when the user's library has them. */
    fun similar(user: String, login: Map<String, String>, artist: String): Pair<String, List<SimilarArtist>> {
        val found = findArtist(artist) ?: return artist to emptyList()
        val now = System.currentTimeMillis()
        val library = libraries[user]?.takeIf { now - it.second < LIBRARY_MS }?.first ?: library(login).also { libraries[user] = it to now }
        return found.str("name") to related(found.long("id")).map { a ->
            SimilarArtist(a.str("name"), a.str("picture_medium").ifEmpty { null }, a.long("nb_fan"), normalize(a.str("name")) in library)
        }
    }

    /** Songs, albums and artists matching [query] on Deezer (answers kept for a week like the rest). */
    fun search(query: String): WebSearch {
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        val songs = pool.submit<List<WebSong>> {
            get("/search?q=$q&limit=15")["data"]?.jsonArray.orEmpty().map { it.jsonObject }.map { t ->
                val album = t["album"] as? JsonObject
                WebSong(t.str("title"), (t["artist"] as? JsonObject)?.str("name").orEmpty(), album?.str("title")?.ifEmpty { null }, t.long("duration").toInt().takeIf { it > 0 }, album?.str("cover_medium")?.ifEmpty { null })
            }
        }
        val albums = pool.submit<List<WebAlbum>> {
            get("/search/album?q=$q&limit=10")["data"]?.jsonArray.orEmpty().map { it.jsonObject }.map { a ->
                WebAlbum(a.str("title"), (a["artist"] as? JsonObject)?.str("name").orEmpty(), a.str("cover_medium").ifEmpty { null }, a.str("record_type").ifEmpty { null })
            }
        }
        val artists = pool.submit<List<SimilarArtist>> {
            get("/search/artist?q=$q&limit=6")["data"]?.jsonArray.orEmpty().map { it.jsonObject }.map { a ->
                SimilarArtist(a.str("name"), a.str("picture_medium").ifEmpty { null }, a.long("nb_fan"))
            }
        }
        return WebSearch(songs.get(), albums.get(), artists.get())
    }

    fun toJson(search: WebSearch): JsonObject = json.encodeToJsonElement(WebSearch.serializer(), search).jsonObject

    private fun make(login: Map<String, String>): List<DiscoveryPick> {
        val library = library(login)
        val seeds = seeds(login)
        if (seeds.isEmpty()) return emptyList()
        // Each seed's related artists, in parallel (Deezer allows 50 calls per 5 s).
        val related = seeds.map { (name, weight) -> Triple(name, weight, pool.submit<List<JsonObject>> { findArtist(name)?.let { related(it.long("id")) }.orEmpty() }) }
        val scores = HashMap<String, Double>()
        val names = HashMap<String, JsonObject>()
        val reasons = HashMap<String, MutableMap<String, Double>>()
        val seedKeys = seeds.map { normalize(it.first) }.toSet()
        for ((seed, weight, future) in related) {
            for ((i, artist) in runCatching { future.get() }.getOrDefault(emptyList()).withIndex()) {
                val key = normalize(artist.str("name"))
                if (key.isEmpty() || key in library || key in seedKeys) continue
                val score = weight * (1.0 - i / 25.0)
                scores.merge(key, score, Double::plus)
                names.putIfAbsent(key, artist)
                reasons.getOrPut(key) { HashMap() }.merge(seed, score, Double::plus)
            }
        }
        val top = scores.entries.sortedByDescending { it.value }.take(PICKS).map { it.key }
        val albums = top.associateWith { key -> pool.submit<JsonObject?> { bestAlbum(names.getValue(key).long("id")) } }
        return top.map { key ->
            val artist = names.getValue(key)
            val album = runCatching { albums.getValue(key).get() }.getOrNull()
            DiscoveryPick(
                artist = artist.str("name"),
                album = album?.str("title")?.ifEmpty { null },
                year = album?.str("release_date")?.take(4)?.toIntOrNull(),
                imageUrl = artist.str("picture_medium").ifEmpty { null },
                coverUrl = album?.str("cover_medium")?.ifEmpty { null },
                because = reasons.getValue(key).entries.sortedByDescending { it.value }.take(2).map { it.key },
            )
        }
    }

    /** The user's artists with a weight: liked ones count most, then what's played most, then lately. */
    private fun seeds(login: Map<String, String>): List<Pair<String, Double>> {
        val weights = LinkedHashMap<String, Pair<String, Double>>()
        fun add(name: String, weight: Double) {
            if (name.isBlank()) return
            val key = normalize(name)
            weights[key] = name to (weights[key]?.second ?: 0.0) + weight
        }
        fun albums(type: String, size: Int) = subsonic("getAlbumList2", login + mapOf("type" to type, "size" to "$size"))["albumList2"]
            ?.jsonObject?.get("album")?.jsonArray.orEmpty().map { it.jsonObject.str("artist") }
        albums("frequent", 40).forEachIndexed { i, artist -> add(artist, 2.0 - i / 40.0) }
        albums("recent", 25).forEach { add(it, 0.6) }
        val starred = subsonic("getStarred2", login)["starred2"]?.jsonObject
        starred?.get("artist")?.jsonArray.orEmpty().forEach { add(it.jsonObject.str("name"), 3.0) }
        starred?.get("album")?.jsonArray.orEmpty().forEach { add(it.jsonObject.str("artist"), 1.5) }
        starred?.get("song")?.jsonArray.orEmpty().forEach { add(it.jsonObject.str("artist"), 0.5) }
        return weights.values.sortedByDescending { it.second }.take(SEEDS)
    }

    private fun library(login: Map<String, String>): Set<String> =
        subsonic("getArtists", login)["artists"]?.jsonObject?.get("index")?.jsonArray.orEmpty()
            .flatMap { it.jsonObject["artist"]?.jsonArray.orEmpty() }.map { normalize(it.jsonObject.str("name")) }.toSet()

    private fun findArtist(name: String): JsonObject? {
        val hits = get("/search/artist?q=" + URLEncoder.encode(name, Charsets.UTF_8) + "&limit=5")["data"]?.jsonArray.orEmpty().map { it.jsonObject }
        return hits.firstOrNull { normalize(it.str("name")) == normalize(name) } ?: hits.firstOrNull()?.takeIf { normalize(it.str("name")).startsWith(normalize(name)) }
    }

    private fun related(id: Long): List<JsonObject> = get("/artist/$id/related?limit=25")["data"]?.jsonArray.orEmpty().map { it.jsonObject }

    /** The artist's studio album with the most fans (live albums and remasters left out when there's a choice). */
    private fun bestAlbum(id: Long): JsonObject? {
        val albums = get("/artist/$id/albums?limit=100")["data"]?.jsonArray.orEmpty().map { it.jsonObject }
        val studio = albums.filter { it.str("record_type") == "album" }
        val plain = studio.filterNot { LIVE.containsMatchIn(it.str("title")) }
        return (plain.ifEmpty { studio }.ifEmpty { albums }).maxByOrNull { it.long("fans") }
    }

    private fun get(path: String): JsonObject {
        val now = System.currentTimeMillis()
        cache[path]?.takeIf { now - it.second < CACHE_MS }?.let { return it.first }
        val request = HttpRequest.newBuilder(URI.create(deezer.trimEnd('/') + path)).timeout(Duration.ofSeconds(15)).GET().build()
        val body = json.parseToJsonElement(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).jsonObject
        body["error"]?.let { throw IllegalStateException("Deezer: " + ((it as? JsonObject)?.str("message") ?: it.toString())) }
        if (cache.size > 20_000) cache.clear()
        cache[path] = body to now
        return body
    }

    private fun subsonic(method: String, params: Map<String, String>): JsonObject {
        val url = navidromeUrl.trimEnd('/') + "/rest/$method.view?" + (params + mapOf("v" to "1.16.1", "c" to "tonearm-server", "f" to "json"))
            .entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val body = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
        val response = json.parseToJsonElement(body).jsonObject["subsonic-response"]!!.jsonObject
        if (response.str("status") != "ok") throw IllegalStateException(response["error"]?.jsonObject?.str("message") ?: "Navidrome error")
        return response
    }

    @Synchronized
    private fun load(user: String): DiscoveryPicks =
        runCatching { json.decodeFromString(DiscoveryPicks.serializer(), file(user).readText()) }.getOrDefault(DiscoveryPicks())

    @Synchronized
    private fun save(user: String, picks: DiscoveryPicks) {
        val f = file(user)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(json.encodeToString(DiscoveryPicks.serializer(), picks.copy(running = false)))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun file(user: String) = File(dir, URLEncoder.encode(user, Charsets.UTF_8) + ".json")

    fun toJson(picks: DiscoveryPicks): JsonObject = json.encodeToJsonElement(DiscoveryPicks.serializer(), picks).jsonObject

    fun toJson(artist: String, similar: List<SimilarArtist>): JsonObject = JsonObject(
        mapOf("artist" to JsonPrimitive(artist), "similar" to JsonArray(similar.map { json.encodeToJsonElement(SimilarArtist.serializer(), it) })),
    )

    companion object {
        private const val MAX_AGE_MS = 24 * 3_600_000L
        private const val CACHE_MS = 7 * 24 * 3_600_000L
        private const val LIBRARY_MS = 10 * 60_000L
        private const val SEEDS = 15
        private const val PICKS = 16
        private val LIVE = Regex("""\b(live|remaster(ed)?|deluxe|anniversary|demo)\b""", RegexOption.IGNORE_CASE)

        private fun normalize(text: String) = Recommendations.normalize(text)
        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
    }
}

package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.random.Random

/**
 * A song as Navidrome gave it to the user: [raw] goes to the apps as it is (their Song model reads it, size and
 * replay gain included); the rest is what the mixes read of it.
 */
class LibrarySong(val raw: JsonObject) {
    val id: String = raw.str("id")
    val title: String get() = raw.str("title")
    val artist: String get() = raw.str("artist")
    val album: String? get() = raw.str("album").ifEmpty { null }
    val albumId: String? get() = raw.str("albumId").ifEmpty { null }
    val artistId: String? get() = raw.str("artistId").ifEmpty { null }
    val genre: String? get() = raw.str("genre").ifEmpty { null }
    val year: Int? get() = (raw["year"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    /** When it was liked (ISO 8601), or null. */
    val starred: String? get() = raw.str("starred").ifEmpty { null }
    val playCount: Long get() = (raw["playCount"] as? JsonPrimitive)?.longOrNull ?: 0
    /** Its genre and the others Navidrome lists for it. */
    val genres: List<String> get() = listOfNotNull(genre) + (raw["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name")?.ifEmpty { null } }
    /** The apps' song key, so a dislike of any copy of it counts. */
    val key: String = SongKey.of(artist, title)
    /** The artist it counts for, as the history counts them. */
    val artistKey: String = Recommendations.loose(Played.mainArtist(artist))

    override fun equals(other: Any?) = other is LibrarySong && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = "$artist – $title ($id)"
}

/** Navidrome couldn't be asked, or said no. */
class LibraryException(message: String, val code: Int? = null) : Exception(message)

/**
 * The user's library through Navidrome, asked as the user (their own login, as the apps send it): only what they may
 * see, picks folders included. Each call waits [TIMEOUT_S] at most, so a radio answers in seconds. Only the artist
 * index is kept, for a few minutes and for the last few users (by name; never by login).
 */
class LibraryReader(
    private val navidromeUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A library artist: Navidrome's id and the name as it's written there. */
    data class ArtistRef(val id: String, val name: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val indexes = LinkedHashMap<String, Pair<Map<String, ArtistRef>, Long>>(16, 0.75f, true)

    /** The user's artists by [Recommendations.loose] name. */
    fun artists(user: String, login: Map<String, String>): Map<String, ArtistRef> {
        val now = clock()
        synchronized(indexes) { indexes[user]?.takeIf { now - it.second < INDEX_MS }?.let { return it.first } }
        val index = HashMap<String, ArtistRef>()
        call("getArtists", login)["artists"]?.obj()?.get("index").array().forEach { letter ->
            (letter as? JsonObject)?.get("artist").array().forEach { a ->
                val o = a as? JsonObject ?: return@forEach
                val name = o.str("name")
                val key = Recommendations.loose(name)
                if (key.isNotEmpty() && o.str("id").isNotEmpty()) index.putIfAbsent(key, ArtistRef(o.str("id"), name))
            }
        }
        synchronized(indexes) {
            indexes[user] = index to now
            while (indexes.size > INDEXES) indexes.remove(indexes.keys.first())
        }
        return index
    }

    /** An artist's albums as getArtist lists them (name, genre, playCount…). */
    fun albumsOf(login: Map<String, String>, artistId: String): List<JsonObject> =
        call("getArtist", login + ("id" to artistId))["artist"]?.obj()?.get("album").array().mapNotNull { it as? JsonObject }

    /**
     * Songs from up to [albums] of an artist's albums: the one played most, then others at [random]. Empty when the
     * artist is gone.
     */
    fun songsOf(login: Map<String, String>, artistId: String, albums: Int = 3, random: Random = Random.Default): List<LibrarySong> {
        val all = try {
            albumsOf(login, artistId)
        } catch (e: LibraryException) {
            if (e.code == NOT_FOUND) return emptyList() else throw e
        }
        val top = all.maxByOrNull { (it["playCount"] as? JsonPrimitive)?.longOrNull ?: 0 }
        val chosen = (listOfNotNull(top) + (all - setOfNotNull(top)).shuffled(random)).take(albums)
        return chosen.flatMap { album(login, it.str("id")) }
    }

    /** An album's songs; empty when it's gone. */
    fun album(login: Map<String, String>, id: String): List<LibrarySong> = try {
        songs(call("getAlbum", login + ("id" to id))["album"]?.obj()?.get("song"))
    } catch (e: LibraryException) {
        if (e.code == NOT_FOUND) emptyList() else throw e
    }

    /** One song, or null when the library hasn't got it (anymore). */
    fun song(login: Map<String, String>, id: String): LibrarySong? = try {
        call("getSong", login + ("id" to id))["song"]?.obj()?.let(::LibrarySong)
    } catch (e: LibraryException) {
        if (e.code == NOT_FOUND) null else throw e
    }

    /** Navidrome's songs like this one (its agents, Last.fm say, have to be set up for that); empty on any failure. */
    fun similarSongs(login: Map<String, String>, id: String, count: Int = 50): List<LibrarySong> = runCatching {
        songs(call("getSimilarSongs2", login + mapOf("id" to id, "count" to "$count"))["similarSongs2"]?.obj()?.get("song"))
    }.getOrDefault(emptyList())

    /** Artists like this one that the library has, by Navidrome's agents; empty on any failure. */
    fun similarArtists(login: Map<String, String>, artistId: String): List<ArtistRef> = runCatching {
        call("getArtistInfo2", login + mapOf("id" to artistId, "count" to "20"))["artistInfo2"]?.obj()?.get("similarArtist").array().mapNotNull { a ->
            val o = a as? JsonObject ?: return@mapNotNull null
            // Artists the library doesn't have come without an id (or Navidrome's "-1").
            o.str("id").takeIf { it.isNotEmpty() && it != "-1" }?.let { ArtistRef(it, o.str("name")) }
        }
    }.getOrDefault(emptyList())

    /** Random songs, of [genre] and from [fromYear] to [toYear] when given (and only those, whatever the server did with that). */
    fun random(login: Map<String, String>, genre: String?, fromYear: Int?, toYear: Int?, size: Int): List<LibrarySong> {
        val params = listOfNotNull(genre?.let { "genre" to it }, fromYear?.let { "fromYear" to "$it" }, toYear?.let { "toYear" to "$it" }, "size" to "$size").toMap()
        return songs(call("getRandomSongs", login + params)["randomSongs"]?.obj()?.get("song")).filter { song ->
            (genre == null || song.genres.any { Recommendations.loose(it) == Recommendations.loose(genre) }) &&
                (song.year == null || (fromYear == null || song.year!! >= fromYear) && (toYear == null || song.year!! <= toYear))
        }
    }

    /** The library's genres with how many songs each has, most first. */
    fun genres(login: Map<String, String>): List<Pair<String, Int>> =
        call("getGenres", login)["genres"]?.obj()?.get("genre").array().mapNotNull { g ->
            val o = g as? JsonObject ?: return@mapNotNull null
            o.str("value").ifEmpty { null }?.let { it to ((o["songCount"] as? JsonPrimitive)?.intOrNull ?: 0) }
        }.sortedByDescending { it.second }

    /** What the user liked: songs (with when) and artists' names. */
    fun starred(login: Map<String, String>): Pair<List<LibrarySong>, List<String>> {
        val starred = call("getStarred2", login)["starred2"]?.obj()
        val artists = starred?.get("artist").array().mapNotNull { (it as? JsonObject)?.str("name")?.ifEmpty { null } }
        return songs(starred?.get("song")) to artists
    }

    /** The library's copy of a song (a YouTube Music one, say): search3 on the title, the hit with the same [SongKey]. */
    fun find(login: Map<String, String>, artist: String, title: String): LibrarySong? {
        val key = SongKey.of(artist, title)
        val query = SongKey.cleanTitle(title).ifEmpty { title }
        val hits = songs(call("search3", login + mapOf("query" to query, "songCount" to "20", "artistCount" to "0", "albumCount" to "0"))["searchResult3"]?.obj()?.get("song"))
        return hits.firstOrNull { it.key == key }
    }

    /**
     * The songs of the user's newest weekly picks that are ready: a playlist of theirs (Navidrome lists an admin everyone's)
     * whose comment carries [WEEKLY_MARKER] with `"status":"ready"`. Empty when there's none.
     */
    fun weeklyPicksSongs(user: String, login: Map<String, String>): List<LibrarySong> {
        val newest = call("getPlaylists", login)["playlists"]?.obj()?.get("playlist").array().mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val owner = o.str("owner")
            if (owner.isNotEmpty() && !owner.equals(user, ignoreCase = true)) return@mapNotNull null
            val state = o.str("comment").substringAfter(WEEKLY_MARKER, "").trim().takeIf { it.isNotEmpty() }
                ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return@mapNotNull null
            if (state.str("status") != "ready") return@mapNotNull null
            o.str("id") to ((state["created"] as? JsonPrimitive)?.longOrNull ?: 0)
        }.maxByOrNull { it.second } ?: return emptyList()
        return try {
            songs(call("getPlaylist", login + ("id" to newest.first))["playlist"]?.obj()?.get("entry"))
        } catch (e: LibraryException) {
            if (e.code == NOT_FOUND) emptyList() else throw e
        }
    }

    private fun songs(element: JsonElement?): List<LibrarySong> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.takeIf { o -> o.str("id").isNotEmpty() }?.let(::LibrarySong) }

    private fun call(method: String, params: Map<String, String>): JsonObject {
        val url = navidromeUrl.trimEnd('/') + "/rest/$method.view?" + (params + mapOf("v" to "1.16.1", "c" to "tonearm-server", "f" to "json"))
            .entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val body = try {
            http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(TIMEOUT_S)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            throw LibraryException("Navidrome didn't answer (${e.message ?: e.javaClass.simpleName})")
        }
        val response = runCatching { json.parseToJsonElement(body).jsonObject["subsonic-response"]!!.jsonObject }.getOrNull()
            ?: throw LibraryException("Navidrome didn't answer like a Subsonic server")
        if (response.str("status") != "ok") {
            val error = response["error"] as? JsonObject
            throw LibraryException(error?.str("message")?.ifEmpty { null } ?: "Navidrome said no to $method", (error?.get("code") as? JsonPrimitive)?.intOrNull)
        }
        return response
    }

    companion object {
        /** The apps' WeeklyPicks.MARKER, letter for letter. */
        const val WEEKLY_MARKER = "[tonearm-weekly]"
        const val TIMEOUT_S = 10L
        private const val INDEX_MS = 10 * 60_000L
        /** Users whose artist index is kept. */
        private const val INDEXES = 8
        /** Subsonic's "not found". */
        private const val NOT_FOUND = 70

    }
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonElement.obj() = this as? JsonObject
private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray).orEmpty()

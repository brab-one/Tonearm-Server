package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** One song as an app played it: from the library, YouTube Music or a file on the desktop; also when skipped. */
@Serializable
data class Played(
    /** When it started (ms). */
    val at: Long,
    val artist: String,
    val title: String,
    val album: String? = null,
    val durationMs: Long = 0,
    val listenedMs: Long = 0,
    /** "library", "youtube", "local", or "maloja" for history brought over from there. */
    val source: String = "library",
) {
    /** Listened to long enough to count, like a scrobble. */
    val counts: Boolean get() = source == "maloja" || listenedMs >= 10_000 && listenedMs >= minOf(if (durationMs > 0) durationMs / 2 else 30_000, 240_000)

    /** Left within half a minute (and before half of it). */
    val skipped: Boolean get() = !counts && listenedMs < 30_000

    /** The artist the play counts for: the first one named, without featured guests. */
    val mainArtist: String get() = mainArtist(artist)

    companion object {
        fun mainArtist(artist: String) = artist.split(FEATURING).first().trim()

        private val FEATURING = Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)
    }
}

/** An artist (or one album of theirs) the user said no to. */
@Serializable
data class Dismissed(val artist: String, val album: String? = null, val at: Long = 0)

@Serializable
data class ArtistCount(val artist: String, val plays: Int, val skips: Int = 0, val lastPlayed: Long = 0)

@Serializable
data class SongCount(val artist: String, val title: String, val album: String? = null, val plays: Int)

@Serializable
data class RecentPlay(val artist: String, val title: String, val at: Long)

/** Someone's listening over a time span, most played first. */
@Serializable
data class Listening(
    val since: Long = 0,
    val plays: Int = 0,
    val artists: List<ArtistCount> = emptyList(),
    val songs: List<SongCount> = emptyList(),
    val recent: List<RecentPlay> = emptyList(),
)

/**
 * What the server knows about each listener beyond Navidrome: every song the apps played (YouTube Music
 * ones too, and the ones skipped) and what they said no to. One file per user in DATA_DIR/history.
 */
class History(dataDir: File) {
    private val dir = File(dataDir, "history").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val plays = ConcurrentHashMap<String, MutableList<Played>>()
    private val dismissals = ConcurrentHashMap<String, MutableList<Dismissed>>()

    /** Adds what an app played; the same song at the same moment only once (apps retry). Returns how many were new. */
    fun add(user: String, played: List<Played>): Int = synchronized(lock(user)) {
        val list = playsOf(user)
        val known = list.takeLast(2_000).map { it.at to it.title }.toHashSet()
        val new = played.filter { it.artist.isNotBlank() && it.title.isNotBlank() && known.add(it.at to it.title) }.sortedBy { it.at }
        if (new.isEmpty()) return 0
        list += new
        list.sortBy { it.at }
        if (list.size > MAX_PLAYS) {
            repeat(list.size - MAX_PLAYS) { list.removeAt(0) }
            rewrite(user, list)
        } else {
            playsFile(user).appendText(new.joinToString("") { json.encodeToString(Played.serializer(), it) + "\n" })
        }
        // Playing an artist again after saying no to them takes the no back.
        val dismissed = dismissedOf(user)
        val back = dismissed.filter { d -> d.album == null && list.count { it.counts && it.at > d.at && same(it.mainArtist, d.artist) } >= 3 }
        if (back.isNotEmpty()) {
            dismissed -= back.toSet()
            saveDismissed(user, dismissed)
        }
        new.size
    }

    fun played(user: String): List<Played> = synchronized(lock(user)) { playsOf(user).toList() }

    fun isEmpty(user: String) = played(user).isEmpty()

    /** The user's listening since [since] (all of it for 0): top artists and songs, and the latest plays. */
    fun listening(user: String, since: Long, artists: Int, songs: Int, recent: Int): Listening {
        val all = played(user).filter { it.at >= since }
        val counted = all.filter { it.counts }
        val byArtist = all.groupBy { normalize(it.mainArtist) }.map { (_, plays) ->
            val shown = plays.groupingBy { it.mainArtist }.eachCount().maxBy { it.value }.key
            ArtistCount(shown, plays.count { it.counts }, plays.count { it.skipped }, plays.filter { it.counts }.maxOfOrNull { it.at } ?: 0)
        }.filter { it.plays > 0 }.sortedWith(compareByDescending<ArtistCount> { it.plays }.thenByDescending { it.lastPlayed })
        val bySong = counted.groupBy { normalize(it.mainArtist) + "|" + normalize(it.title) }.map { (_, plays) ->
            val last = plays.last()
            SongCount(last.artist, last.title, last.album, plays.size)
        }.sortedByDescending { it.plays }
        return Listening(
            since = since,
            plays = counted.size,
            artists = byArtist.take(artists),
            songs = bySong.take(songs),
            recent = counted.takeLast(recent).reversed().map { RecentPlay(it.artist, it.title, it.at) },
        )
    }

    fun dismiss(user: String, artist: String, album: String?) = synchronized(lock(user)) {
        val list = dismissedOf(user)
        list.removeAll { same(it.artist, artist) && it.album?.let(::normalize) == album?.let(::normalize) }
        list += Dismissed(artist.trim(), album?.trim()?.ifEmpty { null }, System.currentTimeMillis())
        saveDismissed(user, list)
    }

    fun dismissed(user: String): List<Dismissed> = synchronized(lock(user)) { dismissedOf(user).toList() }

    /** Whether the user said no to this artist, or to this album of theirs. */
    fun isDismissed(user: String, artist: String, album: String? = null): Boolean = dismissed(user).any { d ->
        same(d.artist, artist) && (d.album == null || album != null && Recommendations.bareTitle(d.album) == Recommendations.bareTitle(album))
    }

    /**
     * Brings a Maloja's scrobbles into [user]'s history, once per server: what's left of Maloja after the apps
     * moved here. Returns how many plays came over, or null when it ran before.
     */
    fun importMaloja(user: String, url: String, key: String?, http: HttpClient): Int? {
        val marker = File(dir, ".maloja-imported")
        if (marker.exists()) return null
        val query = "perpage=1000000" + (key?.let { "&key=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: "")
        val request = HttpRequest.newBuilder(URI.create(url.trimEnd('/') + "/apis/mlj_1/scrobbles?" + query)).timeout(Duration.ofMinutes(2)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IllegalStateException("Maloja answered HTTP ${response.statusCode()}")
        val scrobbles = json.parseToJsonElement(response.body()).jsonObject["list"]?.jsonArray.orEmpty().mapNotNull { element ->
            val s = element as? JsonObject ?: return@mapNotNull null
            val track = s["track"] as? JsonObject ?: return@mapNotNull null
            val artists = track["artists"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val time = (s["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val length = (track["length"] as? JsonPrimitive)?.longOrNull ?: 0
            Played(
                at = time * 1000,
                artist = artists.firstOrNull() ?: return@mapNotNull null,
                title = (track["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                album = ((track["album"] as? JsonObject)?.get("albumtitle") as? JsonPrimitive)?.contentOrNull,
                durationMs = length * 1000,
                listenedMs = ((s["duration"] as? JsonPrimitive)?.longOrNull ?: length) * 1000,
                source = "maloja",
            )
        }
        val added = add(user, scrobbles)
        marker.writeText("$user $added\n")
        return added
    }

    private fun lock(user: String): Any = plays.computeIfAbsent(user) { load(user) }

    private fun playsOf(user: String): MutableList<Played> = plays.computeIfAbsent(user) { load(user) }

    private fun load(user: String): MutableList<Played> = runCatching {
        playsFile(user).takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
            line.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(Played.serializer(), it) }.getOrNull() }
        }
    }.getOrNull().orEmpty().sortedBy { it.at }.toMutableList()

    private fun rewrite(user: String, list: List<Played>) {
        val f = playsFile(user)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(list.joinToString("") { json.encodeToString(Played.serializer(), it) + "\n" })
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun dismissedOf(user: String): MutableList<Dismissed> = dismissals.computeIfAbsent(user) {
        runCatching { json.decodeFromString(ListSerializer(Dismissed.serializer()), dismissedFile(user).readText()) }.getOrDefault(emptyList()).toMutableList()
    }

    private fun saveDismissed(user: String, list: List<Dismissed>) {
        dismissedFile(user).writeText(json.encodeToString(ListSerializer(Dismissed.serializer()), list))
    }

    private fun playsFile(user: String) = File(dir, URLEncoder.encode(user, Charsets.UTF_8) + ".jsonl")

    private fun dismissedFile(user: String) = File(dir, URLEncoder.encode(user, Charsets.UTF_8) + ".dismissed.json")

    fun toJson(listening: Listening): JsonObject = Json.encodeToJsonElement(Listening.serializer(), listening).jsonObject

    companion object {
        /** About ten years of a lot of listening. */
        private const val MAX_PLAYS = 200_000

        private fun normalize(text: String) = Recommendations.normalize(text)

        fun same(a: String, b: String) = normalize(a) == normalize(b)
    }
}

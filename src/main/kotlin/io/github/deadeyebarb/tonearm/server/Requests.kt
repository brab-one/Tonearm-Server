package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Something a user asked Lidarr for through the server: a whole artist (whatever Lidarr wants of them), or one album.
 * Kept by Lidarr's ids and the metadata's (MusicBrainz) ones, whichever the request had.
 */
@Serializable
data class Requested(
    /** When it was asked for (ms). */
    val at: Long,
    val artistId: Int? = null,
    val foreignArtistId: String? = null,
    val albumId: Int? = null,
    val foreignAlbumId: String? = null,
) {
    val isAlbum: Boolean get() = albumId != null || foreignAlbumId != null

    /** Whether it's the same artist or album as [other]. */
    fun same(other: Requested): Boolean = isAlbum == other.isAlbum && if (isAlbum) {
        albumId != null && albumId == other.albumId || foreignAlbumId != null && foreignAlbumId == other.foreignAlbumId
    } else {
        artistId != null && artistId == other.artistId || foreignArtistId != null && foreignArtistId == other.foreignArtistId
    }

    /** This one, with the ids only [older] had. */
    fun merged(older: Requested) = copy(
        artistId = artistId ?: older.artistId,
        foreignArtistId = foreignArtistId ?: older.foreignArtistId,
        albumId = albumId ?: older.albumId,
        foreignAlbumId = foreignAlbumId ?: older.foreignAlbumId,
    )
}

/**
 * Who asked Lidarr for what through the server, so that everyone but admins sees their own requests in the queue and the
 * wanted list, and admins see who asked. One file per user in DATA_DIR/requests, with what they asked for in the last
 * year (at most [MAX_REQUESTS]). Requests from before the server kept them have no one's name on them.
 */
class Requests(dataDir: File) {
    private val dir = File(dataDir, "requests").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(Requested.serializer())
    private val users = ConcurrentHashMap<String, MutableList<Requested>>()

    /**
     * Notes what [user] asked for; asking for the same again only makes it newer. Throws when it can't be saved, and
     * nothing changes then.
     */
    fun add(user: String, asked: List<Requested>, now: Long = System.currentTimeMillis()) {
        if (asked.isEmpty()) return
        val list = requestsOf(user)
        synchronized(list) {
            var next: List<Requested> = list
            for (new in asked) {
                val (same, other) = next.partition { it.same(new) }
                next = other + same.fold(new) { merged, old -> merged.merged(old) }
            }
            next = next.filter { now - it.at < MAX_AGE_MS }.sortedBy { it.at }.takeLast(MAX_REQUESTS)
            DataFiles.write(file(user), json.encodeToString(serializer, next))
            list.clear()
            list.addAll(next)
        }
    }

    /** What [user] asked for in the last year. */
    fun of(user: String, now: Long = System.currentTimeMillis()): List<Requested> =
        requestsOf(user).let { list -> synchronized(list) { list.filter { now - it.at < MAX_AGE_MS } } }

    /** What everyone asked for in the last year, by user. */
    fun everyone(now: Long = System.currentTimeMillis()): Map<String, List<Requested>> {
        val names = dir.list().orEmpty().filter { it.endsWith(".json") }.map { URLDecoder.decode(it.removeSuffix(".json"), Charsets.UTF_8) }
        return (names + users.keys).toSet().associateWith { of(it, now) }.filterValues { it.isNotEmpty() }
    }

    private fun requestsOf(user: String): MutableList<Requested> = users.computeIfAbsent(user) { load(it) }

    /** The user's file; one that can't be read is put aside (as `.broken`) rather than written over. */
    private fun load(user: String): MutableList<Requested> {
        val f = file(user)
        if (!f.exists()) return mutableListOf()
        return runCatching { json.decodeFromString(serializer, f.readText()).toMutableList() }.getOrElse {
            f.copyTo(File(dir, f.name + ".broken"), overwrite = true)
            mutableListOf()
        }
    }

    private fun file(user: String) = File(dir, URLEncoder.encode(user, Charsets.UTF_8) + ".json")

    companion object {
        /** A year: what's older is forgotten. */
        const val MAX_AGE_MS = 365 * 24 * 3_600_000L
        /** A user's requests kept at most; the oldest go first. */
        const val MAX_REQUESTS = 5_000
    }
}

/** Who asked for the artists and albums Lidarr's records name, from what each user asked for. */
class Askers(byUser: Map<String, List<Requested>>) {
    private val albums = HashMap<Int, MutableSet<String>>()
    private val foreignAlbums = HashMap<String, MutableSet<String>>()
    private val artists = HashMap<Int, MutableSet<String>>()
    private val foreignArtists = HashMap<String, MutableSet<String>>()

    init {
        for ((user, asked) in byUser) for (r in asked) {
            if (r.isAlbum) {
                r.albumId?.let { albums.getOrPut(it) { HashSet() } += user }
                r.foreignAlbumId?.let { foreignAlbums.getOrPut(it) { HashSet() } += user }
            } else {
                r.artistId?.let { artists.getOrPut(it) { HashSet() } += user }
                r.foreignArtistId?.let { foreignArtists.getOrPut(it) { HashSet() } += user }
            }
        }
    }

    /** Everyone who asked for this album, or for its whole artist, by name. */
    fun of(albumId: Int?, foreignAlbumId: String?, artistId: Int?, foreignArtistId: String?): List<String> = buildSet {
        albumId?.let { albums[it]?.let(::addAll) }
        foreignAlbumId?.let { foreignAlbums[it]?.let(::addAll) }
        artistId?.let { artists[it]?.let(::addAll) }
        foreignArtistId?.let { foreignArtists[it]?.let(::addAll) }
    }.sorted()
}

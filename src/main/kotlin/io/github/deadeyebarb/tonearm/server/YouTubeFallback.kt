package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Wanted albums from YouTube Music when nothing better came in time (YOUTUBE_FALLBACK_MINUTES), through Tubifarry's
 * YouTube indexer in Lidarr. That indexer keeps Lidarr's own automatic search off, because a delay profile can't hold
 * YouTube back (Tubifarry dates its releases by the album's release date), so on its own Lidarr only grabs torrents.
 *
 * Every [PASS_MS] the fallback reads Lidarr's monitored wanted albums. An album is due once it has been wanted for
 * [minutes], nothing for it is in Lidarr's queue, and it wasn't tried in the last [RETRY_MS]. The newest due album is
 * searched on all indexers, and the approved release Lidarr ranks best is grabbed: a torrent when a better one came by
 * now, YouTube otherwise. One album a pass, and none while [maxActive] YouTube downloads are running (finished ones
 * waiting for their import don't count). When each album was first seen wanted (or asked for through the server, if
 * that was earlier) and how its last try went are kept in DATA_DIR/youtube-fallback.json, so a restart doesn't start
 * the wait over.
 *
 * [now] is the apps' "YouTube now": one album straight from YouTube, also when the fallback is off.
 */
class YouTubeFallback(
    private val lidarr: Upstream,
    private val requests: Requests,
    dataDir: File,
    /** How long an album is wanted before YouTube is tried, in minutes; 0 is off. */
    val minutes: Int,
    /** YouTube downloads running at most before a pass is left out. */
    private val maxActive: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val file = File(dataDir, "youtube-fallback.json")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

    /** How a try for an album went: when, whether something was grabbed, and what (or why nothing). */
    @Serializable
    data class Tried(val at: Long, val grabbed: Boolean, val outcome: String)

    /** What youtube-fallback.json holds. */
    @Serializable
    private class Kept(
        val looked: Boolean = false,
        val firstSeen: Map<Int, Long> = emptyMap(),
        val backlog: Set<Int> = emptySet(),
        val tried: Map<Int, Tried> = emptyMap(),
    )

    /** When each wanted album (by Lidarr's id) was first seen wanted, or asked for through the server if that was earlier. */
    private val firstSeen = HashMap<Int, Long>()
    /**
     * Albums that were wanted already when the fallback first looked, and that nobody asked for through the server: for
     * how long isn't known, so they go after everything else.
     */
    private val backlog = HashSet<Int>()
    private val tried = HashMap<Int, Tried>()
    /** Whether the fallback has read Lidarr's wanted list before, in this run or an earlier one. */
    private var looked = false
    private var dirty = false
    private var saveFailed = false

    /** Albums being searched for right now, so the fallback and "YouTube now" never grab one twice at once. */
    private val busy = ConcurrentHashMap.newKeySet<Int>()

    /** Lidarr's YouTube indexers (by id) as of the last look, empty when it has none; null before Lidarr could be asked. */
    @Volatile private var indexers: Set<Int>? = null
    /** What the log said last about Lidarr's YouTube indexer and about reading its lists, so each is said once. */
    @Volatile private var saidIndexer: String? = null
    @Volatile private var saidTrouble: String? = null
    @Volatile private var waiting = false

    init {
        if (file.exists()) {
            val kept = runCatching { json.decodeFromString(Kept.serializer(), file.readText()) }.getOrElse {
                // Put aside rather than written over; the waits start again.
                runCatching { file.copyTo(File(dataDir, file.name + ".broken"), overwrite = true) }
                null
            }
            if (kept != null) {
                looked = kept.looked
                firstSeen += kept.firstSeen
                backlog += kept.backlog
                tried += kept.tried
            }
        }
    }

    val on: Boolean get() = minutes > 0

    /** Whether Lidarr has a YouTube indexer that searches, as of the last look. */
    val hasIndexer: Boolean get() = indexers?.isNotEmpty() == true

    /** Whether the fallback takes wanted albums now: it's on, and Lidarr has a YouTube indexer. */
    val active: Boolean get() = on && hasIndexer

    /** Looks every [PASS_MS] in the background, the first time a few seconds from now. */
    fun start() {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "youtube-fallback").apply { isDaemon = true } }.scheduleWithFixedDelay({
            try {
                pass()
            } catch (e: Throwable) {
                System.err.println("YouTube fallback: a pass failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }, START_DELAY_MS, PASS_MS, TimeUnit.MILLISECONDS)
    }

    @Serializable
    private class Indexer(val id: Int = 0, val name: String? = null, val implementation: String? = null, val enableInteractiveSearch: Boolean = true)

    /**
     * Asks Lidarr which of its indexers are Tubifarry's YouTube one and can be searched (Lidarr's release search is its
     * interactive search); what was known before when Lidarr can't be asked.
     */
    fun checkIndexers(): Set<Int>? {
        val (status, body) = lidarr.get("api/v1/indexer")
        val all = if (status == 200) runCatching { json.decodeFromString(ListSerializer(Indexer.serializer()), body) }.getOrNull() else null
        if (all == null) return indexers
        val youtube = all.filter { it.id > 0 && it.implementation.equals(IMPLEMENTATION, ignoreCase = true) }
        val usable = youtube.filter { it.enableInteractiveSearch }
        val message = when {
            usable.isNotEmpty() -> "through Lidarr's indexer " + usable.joinToString { "\"${it.name}\" (${it.id})" }
            youtube.isNotEmpty() -> "Lidarr's YouTube indexer has interactive search off, which YouTube downloads need, so there are none until it's on"
            else -> "Lidarr has no YouTube indexer (Tubifarry's \"YouTube\"), so there are none until it has one"
        }
        if (message != saidIndexer) {
            saidIndexer = message
            println("YouTube downloads: $message")
        }
        return usable.map { it.id }.toSet().also { indexers = it }
    }

    /** What a pass did: albums wanted and due, YouTube downloads running, and the album tried with how that went. */
    class Pass(val wanted: Int, val due: Int, val running: Int, val album: Int? = null, val result: Result? = null)

    /** How a try went: grabbed, with the release's title, or else what kept it from that. */
    class Result(val grabbed: Boolean, val title: String? = null, val problem: String? = null)

    /** A wanted album, as much of it as the fallback needs. */
    private class Wanted(
        val id: Int,
        val title: String,
        val artist: String?,
        val monitored: Boolean,
        val releaseDate: Long?,
        val lastSearch: Long?,
        val foreignAlbumId: String?,
        val artistId: Int?,
        val foreignArtistId: String?,
    ) {
        val label get() = listOfNotNull(artist, title).joinToString(" – ")

        companion object {
            fun of(record: JsonObject): Wanted? {
                val artist = record["artist"] as? JsonObject
                return Wanted(
                    id = record.int("id") ?: return null,
                    title = record.string("title") ?: "album ${record.int("id")}",
                    artist = artist?.string("artistName"),
                    // Lidarr lists only monitored albums as wanted unless asked otherwise.
                    monitored = (record["monitored"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    releaseDate = millis(record.string("releaseDate")),
                    lastSearch = millis(record.string("lastSearchTime")),
                    foreignAlbumId = record.string("foreignAlbumId"),
                    artistId = record.int("artistId") ?: artist?.int("id"),
                    foreignArtistId = artist?.string("foreignArtistId"),
                )
            }
        }
    }

    /** A queue item: its album, whether it's a YouTube download, and whether it's still downloading. */
    private class Queued(val albumId: Int?, val youtube: Boolean, val running: Boolean) {
        companion object {
            fun of(record: JsonObject) = Queued(
                albumId = record.int("albumId") ?: (record["album"] as? JsonObject)?.int("id"),
                youtube = record.string("downloadClient").equals(CLIENT, ignoreCase = true) || record.string("protocol")?.contains("youtube", ignoreCase = true) == true,
                // A finished download waiting for its import (or one that failed) holds nothing up.
                running = record.string("status")?.lowercase() !in setOf("completed", "failed"),
            )
        }
    }

    /**
     * One look at Lidarr's wanted list and queue, and a try for the due album that goes first. Null when the fallback is
     * off, Lidarr has no YouTube indexer, or its lists couldn't be read.
     */
    fun pass(): Pass? {
        val youtube = checkIndexers()
        if (!on || youtube.isNullOrEmpty()) return null
        val wanted = pages("api/v1/wanted/missing", "monitored=true&includeArtist=true", Wanted::of) ?: return trouble("Lidarr's wanted list")
        val queue = pages("api/v1/queue", "includeUnknownArtistItems=true", Queued::of) ?: return trouble("Lidarr's queue")
        saidTrouble = null
        val now = clock()
        val askers = Askers(requests.everyone(now))
        val queued = queue.mapNotNullTo(HashSet()) { it.albumId }
        val running = queue.count { it.youtube && it.running }
        val wait = minutes * 60_000L
        val due = synchronized(this) {
            val ids = wanted.mapTo(HashSet()) { it.id }
            // What isn't wanted anymore is forgotten: wanted again some day, it waits again.
            if (firstSeen.keys.retainAll(ids) or tried.keys.retainAll(ids) or backlog.retainAll(ids)) dirty = true
            for (album in wanted) {
                val asked = askers.since(album.id, album.foreignAlbumId, album.artistId, album.foreignArtistId)
                if (asked != null) {
                    if (backlog.remove(album.id)) dirty = true
                } else if (!looked && album.id !in firstSeen) {
                    backlog += album.id
                    dirty = true
                }
                seen(album.id, asked, now)
            }
            if (!looked) {
                looked = true
                dirty = true
            }
            wanted.filter { album ->
                album.monitored && album.id !in queued && (album.releaseDate ?: 0) <= now &&
                    firstSeen.getValue(album.id) + wait <= now && tried[album.id]?.let { now - it.at < RETRY_MS } != true
            }.sortedWith(
                // Newest first, so a fresh request doesn't wait behind a backlog; that backlog last searched first.
                compareBy<Wanted> { it.id in backlog }
                    .thenByDescending { if (it.id in backlog) 0L else firstSeen.getValue(it.id) }
                    .thenByDescending { it.lastSearch ?: 0L }
                    .thenByDescending { it.id },
            )
        }
        save()
        if (due.isEmpty() || running >= maxActive) {
            if (due.isNotEmpty() && !waiting) println("YouTube fallback: ${due.size} albums due, waiting while $running YouTube downloads run")
            waiting = due.isNotEmpty()
            return Pass(wanted.size, due.size, running)
        }
        waiting = false
        val album = due.first()
        val result = attempt(album.id, album.title, album.label, youtubeOnly = false, who = "YouTube fallback")
        return Pass(wanted.size, due.size, running, album.id, result)
    }

    /** Says once (until it works again) that [what] couldn't be read. */
    private fun trouble(what: String): Pass? {
        val message = "YouTube fallback: couldn't read $what; trying again in ${PASS_MS / 60_000} min"
        if (message != saidTrouble) {
            saidTrouble = message
            System.err.println(message)
        }
        return null
    }

    /**
     * "YouTube now" for Lidarr's album [id] ([title], [label] for the log): its best approved release from Lidarr's
     * YouTube indexer, grabbed at once. `{"grabbed":true,"title":…}` with the release's title, or `{"grabbed":false,
     * "problem":…}`.
     */
    fun now(id: Int, title: String, label: String, user: String): JsonObject {
        if (indexers.isNullOrEmpty()) checkIndexers()
        val result = attempt(id, title, label, youtubeOnly = true, who = "YouTube now for $user")
            ?: Result(false, problem = "Already looking for “$title”")
        return buildJsonObject {
            put("grabbed", result.grabbed)
            if (result.grabbed) put("title", result.title) else put("problem", result.problem)
        }
    }

    /**
     * A wanted album's [record] (as Lidarr lists it) with when YouTube is tried for it ("youtubeAt"), or, once a try
     * grabbed nothing, when that was ("youtubeTried"); [asked] is when it was asked for through the server. As it was
     * when the fallback isn't [active], for albums it doesn't take, and after a grab (the queue has it then).
     */
    fun annotate(record: JsonObject, asked: Long?): JsonObject {
        if (!active) return record
        val album = Wanted.of(record)?.takeIf { it.monitored } ?: return record
        val now = clock()
        val (first, last) = synchronized(this) {
            // Before the first look, nothing is noted: what's wanted then is the backlog.
            (if (looked) seen(album.id, asked, now) else minOf(firstSeen[album.id] ?: now, asked ?: now)) to tried[album.id]
        }
        return when {
            last == null -> JsonObject(record + ("youtubeAt" to JsonPrimitive(maxOf(first + minutes * 60_000L, album.releaseDate ?: 0L))))
            !last.grabbed -> JsonObject(record + ("youtubeTried" to JsonPrimitive(last.at)))
            else -> record
        }
    }

    /** When album [id] was first seen wanted: from [now] when it's new, or from when it was [asked] for, if that was earlier. */
    private fun seen(id: Int, asked: Long?, now: Long): Long {
        val known = firstSeen[id]
        val first = minOf(known ?: now, asked ?: Long.MAX_VALUE)
        if (first != known) {
            firstSeen[id] = first
            dirty = true
        }
        return first
    }

    /**
     * Searches every indexer for album [id] and grabs the approved release Lidarr ranks best (the lowest releaseWeight),
     * or ([youtubeOnly]) the best from YouTube; when Lidarr can't grab one, the next, up to [GRAB_TRIES]. Null when the
     * album is being searched for already. The try is kept while the fallback is on.
     */
    private fun attempt(id: Int, title: String, label: String, youtubeOnly: Boolean, who: String): Result? {
        if (!busy.add(id)) return null
        try {
            val result = search(id, title, label, youtubeOnly, who)
            if (on) {
                synchronized(this) {
                    tried[id] = Tried(clock(), result.grabbed, result.title ?: result.problem.orEmpty())
                    dirty = true
                }
                save()
            }
            return result
        } finally {
            busy.remove(id)
        }
    }

    @Serializable
    private class Release(
        val guid: String = "",
        val indexerId: Int = 0,
        val indexer: String? = null,
        val title: String? = null,
        val approved: Boolean = false,
        val releaseWeight: Int = Int.MAX_VALUE,
    )

    private fun search(id: Int, title: String, label: String, youtubeOnly: Boolean, who: String): Result {
        // Lidarr's errors and its releases carry the indexers' download links, API keys and all: never in the log or an answer.
        val found = lidarr.send(ProxyCall("GET", "api/v1/release", "albumId=$id", null, ByteArray(0)), timeout = SEARCH_TIMEOUT)
        if (found.status != 200) {
            val problem = (if (found.status == 502) ownError(found) else null) ?: "Lidarr's search answered HTTP ${found.status}"
            println("$who: no search for $label ($problem)")
            return Result(false, problem = problem)
        }
        val releases = runCatching { json.decodeFromString(ListSerializer(Release.serializer()), found.body.decodeToString()) }.getOrElse {
            println("$who: Lidarr's search for $label couldn't be read")
            return Result(false, problem = "Lidarr's search answer couldn't be read")
        }
        val youtube = indexers.orEmpty()
        val picks = releases.filter { it.approved && it.guid.isNotEmpty() && it.indexerId > 0 && (!youtubeOnly || it.indexerId in youtube) }.sortedBy { it.releaseWeight }
        if (picks.isEmpty()) {
            val from = if (youtubeOnly) releases.count { it.indexerId in youtube }.let { "$it from YouTube" } else "${releases.size}"
            println("$who: nothing to grab for $label ($from found, none approved)" + if (on) "; trying again in ${RETRY_MS / 3_600_000} h" else "")
            return Result(false, problem = if (youtubeOnly) "No YouTube Music match for “$title”" else "Nothing approved for “$title”")
        }
        var status = 0
        for (release in picks.take(GRAB_TRIES)) {
            val body = buildJsonObject { put("guid", release.guid); put("indexerId", release.indexerId) }.toString().encodeToByteArray()
            val grab = lidarr.send(ProxyCall("POST", "api/v1/release", null, "application/json", body), timeout = GRAB_TIMEOUT)
            val name = "“${release.title ?: title}” from ${release.indexer ?: "indexer ${release.indexerId}"}"
            if (grab.status in 200..299) {
                println("$who: grabbed $name for $label")
                return Result(true, title = release.title ?: title)
            }
            status = grab.status
            println("$who: Lidarr couldn't grab $name for $label (HTTP ${grab.status})")
        }
        return Result(false, problem = "Lidarr couldn't grab “$title” (HTTP $status)")
    }

    /** The message of an error this server made itself (Lidarr not answering), never Lidarr's own. */
    private fun ownError(answer: ProxyAnswer): String? =
        runCatching { (json.parseToJsonElement(answer.body.decodeToString()).jsonObject["error"] as? JsonPrimitive)?.contentOrNull }.getOrNull()

    /**
     * Every record of Lidarr's paged list at [path], made into what [each] makes of it (null leaves it out), read a
     * record at a time as it comes in; null when it can't be read.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun <T : Any> pages(path: String, query: String, each: (JsonObject) -> T?): List<T>? {
        val out = ArrayList<T>()
        var page = 1
        while (true) {
            val reader = LidarrProxy.PageReader { record -> each(record)?.let(out::add) }
            val call = ProxyCall("GET", path, query, null, ByteArray(0))
            val part = lidarr.fetch(call, listOf("page" to "$page", "pageSize" to "$CHUNK")) { json.decodeFromStream(reader, it) }.value ?: return null
            // Fewer than asked for is the end; more is a Lidarr that sent everything at once.
            if (part.records != CHUNK || page.toLong() * CHUNK >= part.totalRecords || page >= MAX_PAGES) return out
            page++
        }
    }

    /** Writes what changed; when that can't be done, it's kept in memory and written with the next change (said once). */
    private fun save() {
        synchronized(this) {
            if (!dirty) return
            try {
                DataFiles.write(file, json.encodeToString(Kept.serializer(), Kept(looked, HashMap(firstSeen), HashSet(backlog), HashMap(tried))))
                dirty = false
                saveFailed = false
            } catch (e: IOException) {
                if (!saveFailed) System.err.println("YouTube fallback: couldn't note what it did: ${e.message}")
                saveFailed = true
            }
        }
    }

    companion object {
        /** Tubifarry's YouTube indexer, as Lidarr names its implementation, and its download client's usual name. */
        const val IMPLEMENTATION = "YouTubeIndexer"
        const val CLIENT = "YouTube"
        const val PASS_MS = 2 * 60_000L
        const val START_DELAY_MS = 5_000L
        /** How long after a try an album is tried again. */
        const val RETRY_MS = 12 * 3_600_000L
        /** Releases tried at most when Lidarr can't grab one (a dead torrent link, say). */
        const val GRAB_TRIES = 3
        private const val CHUNK = 1_000
        private const val MAX_PAGES = 100
        private val SEARCH_TIMEOUT: Duration = Duration.ofMinutes(5)
        private val GRAB_TIMEOUT: Duration = Duration.ofMinutes(3)

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

        /** A Lidarr id (they start at 1). */
        private fun JsonObject.int(key: String) = string(key)?.toIntOrNull()?.takeIf { it > 0 }

        /** A time as Lidarr writes it ("2026-10-18T19:05:00Z"), in ms. */
        private fun millis(text: String?): Long? = text?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        }
    }
}

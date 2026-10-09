package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Song lists from the user's own library, all through [SongPicker] and asked of Navidrome as the user: a radio that
 * goes on as long as there's something new (it keeps nothing: the apps send what's queued). Radio never asks the AI,
 * so it answers in seconds.
 */
class Mixes(
    private val library: LibraryReader,
    private val history: History,
    /** Deezer's related artists (kept to the ones the library has); null with DISCOVERY=off. */
    private val discovery: Discovery?,
    /** Makes the mixes from a sentence; null: no such mixes. */
    private val ai: Ai?,
    dataDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val hasAi: Boolean get() = ai != null

    data class Radio(
        val title: String,
        val songs: List<LibrarySong>,
        val more: Boolean,
        val problem: String? = null,
        /** How many candidates each source had, for the log. */
        val sources: String = "",
    )

    private val pool = Executors.newFixedThreadPool(4) { Thread(it, "mixes").apply { isDaemon = true } }

    // --- Radio ---------------------------------------------------------------------------------------------------

    /**
     * [count] songs to queue next. [seed] is [SONG] ([id] in the library, or [artist] and [title] of a song elsewhere) or
     * [PICKS]. [exclude] is the queue, oldest first, as the apps key songs ("lib/<id>", "yt/<videoId>"): never again,
     * and its last library songs steer, so a long radio follows what's playing. Throws [LibraryException] when Navidrome
     * can't be asked.
     */
    fun radio(user: String, login: Map<String, String>, seed: String, id: String?, artist: String?, title: String?, exclude: List<String>, count: Int): Radio {
        val started = System.currentTimeMillis()
        val deadline = started + BUILD_MS
        val queued = exclude.takeLast(MAX_EXCLUDE).filter { it.startsWith("lib/") }.map { it.removePrefix("lib/") }
        // The same request gets the same answer; the next batch (more queued) another one.
        val random = Random(listOf(user, seed, id, artist, title, queued.size, queued.lastOrNull()).hashCode())
        val index = library.artists(user, login)
        val tail = queued.filter { it != id }.takeLast(3).map { start { library.song(login, it) } }.mapNotNull { it.await(deadline) }
        val radio = if (seed == PICKS) picksRadio(user, login, queued, tail, index, count, random, deadline)
        else songRadio(user, login, id, artist, title, queued, tail, index, count, random, deadline)
        println("Radio for $user (${radio.title}): ${radio.songs.size} songs" + (if (radio.more) "" else ", nothing more after them") +
            " (${radio.sources}), ${System.currentTimeMillis() - started} ms")
        return radio
    }

    private fun songRadio(
        user: String, login: Map<String, String>, id: String?, artist: String?, title: String?, queued: List<String>, tail: List<LibrarySong>,
        index: Map<String, LibraryReader.ArtistRef>, count: Int, random: Random, deadline: Long,
    ): Radio {
        val seed = id?.let { library.song(login, it) } ?: if (artist != null && title != null) library.find(login, artist, title) else null
        val name = "Radio · " + (seed?.title ?: title.orEmpty())
        if (seed == null && artist == null && tail.isEmpty()) return Radio(name, emptyList(), false, "That song isn't in the library")
        // A song the library hasn't got counts as its artist, when the library has them.
        val seedArtist = seed?.let { s -> s.artistId?.let { LibraryReader.ArtistRef(it, s.artist) } } ?: artist?.let { index[Recommendations.loose(Played.mainArtist(it))] }
        val seeds = listOfNotNull(seed) + tail.reversed()
        val artists = (listOfNotNull(seedArtist) + tail.reversed().mapNotNull { s -> s.artistId?.let { LibraryReader.ArtistRef(it, s.artist) } }).distinctBy { it.id }
        val names = (listOfNotNull(seed?.artist ?: artist) + tail.reversed().map { it.artist }).map(Played::mainArtist).distinctBy(Recommendations::loose)

        val similar = seeds.take(4).map { s -> start { library.similarSongs(login, s.id, 50) } }
        val sameArtist = artists.take(2).map { a -> start { library.songsOf(login, a.id, 3, random).shuffled(random) } }
        val genreSeed = seed ?: tail.lastOrNull()
        val genre = genreSeed?.genre?.let { g -> start { library.random(login, g, genreSeed.year?.minus(7), genreSeed.year?.plus(7), 30) } }
        val related = related(login, artists.take(4), names.take(4), index, random, deadline)

        val parts = listOf(
            Part(0.4, SongPicker.interleave(similar.map { it.await(deadline).orEmpty() })),
            Part(0.35, related),
            Part(0.15, SongPicker.interleave(sameArtist.map { it.await(deadline).orEmpty() })),
            Part(0.1, genre?.await(deadline).orEmpty()),
        )
        val avoid = setOfNotNull(seed?.key, if (artist != null && title != null) SongKey.of(artist, title) else null)
        val rules = rules(user, queued.toSet() + listOfNotNull(id, seed?.id), RADIO_PLAYED_MS, avoid)
        val picked = SongPicker.pick(parts, count, rules, random, perArtist = 2, gap = 3, before = lastArtists(queued, tail + listOfNotNull(seed)))
        val sources = "similar ${parts[0].candidates.size}, related ${parts[1].candidates.size}, artist ${parts[2].candidates.size}, genre ${parts[3].candidates.size}"
        return Radio(name, picked.songs, picked.more, if (picked.songs.isEmpty()) NOTHING_LIKE_IT else null, sources)
    }

    /**
     * Picks radio: the user's newest weekly picks (the first batch starts with them), what they liked in the last month,
     * and library artists like both. Without either: what they liked and played lately.
     */
    private fun picksRadio(
        user: String, login: Map<String, String>, queued: List<String>, tail: List<LibrarySong>,
        index: Map<String, LibraryReader.ArtistRef>, count: Int, random: Random, deadline: Long,
    ): Radio {
        val picks = library.weeklyPicksSongs(user, login)
        val starred = library.starred(login).first.mapNotNull { s -> s.starred?.let(::instant)?.let { s to it } }.sortedByDescending { it.second }
        val now = clock()
        val liked = starred.filter { now - it.second <= LIKED_LATELY_MS }.map { it.first }
        val hasPicks = picks.isNotEmpty() || liked.isNotEmpty()
        val (first, second) = if (hasPicks) picks.shuffled(random) to liked else {
            // Nothing picked or liked lately: what they liked any time, and the songs of the artists they played this month.
            val played = history.listening(user, now - LIKED_LATELY_MS, artists = 4, songs = 0, recent = 0).artists.mapNotNull { index[Recommendations.loose(it.artist)] }
            val songs = played.map { a -> start { library.songsOf(login, a.id, 2, random).shuffled(random) } }
            starred.map { it.first }.take(50) to SongPicker.interleave(songs.map { it.await(deadline).orEmpty() })
        }
        val counted = (first + second + tail).groupingBy { it.artistKey }.eachCount()
        val seeds = (first + second + tail.reversed()).distinctBy { it.artistKey }.sortedByDescending { counted[it.artistKey] }.take(4)
        val related = related(
            login, seeds.mapNotNull { s -> s.artistId?.let { LibraryReader.ArtistRef(it, s.artist) } }, seeds.map { Played.mainArtist(it.artist) }, index, random, deadline,
        )
        // The first batch of a picks radio starts with the picks.
        val lead = if (hasPicks && picks.none { it.id in queued }) 8 else 0
        val parts = listOf(Part(0.4, first, lead = lead), Part(0.25, second), Part(0.35, related))
        val picked = SongPicker.pick(parts, count, rules(user, queued.toSet(), RADIO_PLAYED_MS), random, perArtist = 2, gap = 3, before = lastArtists(queued, tail))
        val problem = when {
            picked.songs.isNotEmpty() -> null
            !hasPicks -> NO_PICKS
            else -> NOTHING_LIKE_IT
        }
        return Radio("Picks radio", picked.songs, picked.more, problem, "picks ${picks.size}, liked ${liked.size}, related ${related.size}")
    }

    /**
     * Songs of library artists like [artists] (Navidrome's similar artists) and [names] (Deezer's related ones that the
     * library has), the ones more of them point to first, each artist's songs taking turns.
     */
    private fun related(
        login: Map<String, String>, artists: List<LibraryReader.ArtistRef>, names: List<String>,
        index: Map<String, LibraryReader.ArtistRef>, random: Random, deadline: Long,
    ): List<LibrarySong> {
        val fromNavidrome = artists.map { a -> start { library.similarArtists(login, a.id) } }
        val fromDeezer = discovery?.let { d -> names.map { n -> start { d.relatedArtists(n).mapNotNull { index[Recommendations.loose(it)] } } } }.orEmpty()
        val own = (artists.map { Recommendations.loose(it.name) } + names.map(Recommendations::loose)).toSet()
        val score = LinkedHashMap<String, Pair<LibraryReader.ArtistRef, Double>>()
        for (future in fromNavidrome + fromDeezer) {
            future.await(deadline).orEmpty().forEachIndexed { i, ref ->
                val key = Recommendations.loose(ref.name)
                if (key !in own) score[key] = ref to (score[key]?.second ?: 0.0) + 1.0 - i / 40.0
            }
        }
        val chosen = score.values.sortedByDescending { it.second }.take(RELATED).map { it.first }
        val songs = chosen.map { a -> start { library.songsOf(login, a.id, 2, random).shuffled(random) } }
        return SongPicker.interleave(songs.map { it.await(deadline).orEmpty() })
    }

    /** The artists of the last few songs queued, for spacing the next ones out. */
    private fun lastArtists(queued: List<String>, known: List<LibrarySong>): List<String> =
        queued.takeLast(3).mapNotNull { id -> known.firstOrNull { it.id == id }?.artistKey }

    fun toJson(radio: Radio): JsonObject = buildJsonObject {
        put("title", radio.title)
        put("songs", JsonArray(radio.songs.map { it.raw }))
        put("more", radio.more)
        put("problem", radio.problem)
    }

    // --- Shared ------------------------------------------------------------------------------------------------

    /** What the user's lists leave out: [exclude] (ids), disliked songs and said-no-to's, and what they played in the last [playedWithinMs]. */
    private fun rules(user: String, exclude: Set<String> = emptySet(), playedWithinMs: Long = 0, avoid: Set<String> = emptySet()): Rules {
        val disliked = history.dislikedSongs(user)
        val dismissed = history.dismissed(user)
        val lately = if (playedWithinMs > 0) history.since(user, clock() - playedWithinMs).map { SongKey.of(it.artist, it.title) } else emptyList()
        return Rules(
            excludeIds = exclude,
            avoidKeys = disliked.map { SongKey.of(it.artist, it.title) }.toSet() + lately + avoid,
            dismissedArtists = dismissed.filter { it.album == null }.map { Recommendations.loose(it.artist) }.toSet(),
            dismissedAlbums = dismissed.mapNotNull { d -> d.album?.let { Recommendations.loose(d.artist) + "|" + Recommendations.looseTitle(it) } }.toSet(),
            dislikedArtistCount = disliked.groupingBy { Recommendations.loose(Played.mainArtist(it.artist)) }.eachCount(),
        )
    }

    private fun <T> start(task: () -> T): Future<T> = pool.submit(Callable(task))

    /** The answer, or null when it failed or isn't there by [deadline] (it's given up on then). */
    private fun <T> Future<T>.await(deadline: Long): T? = try {
        get((deadline - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        cancel(true)
        null
    }

    companion object {
        const val SONG = "song"
        const val PICKS = "picks"
        /** Queue keys read from a radio request. */
        const val MAX_EXCLUDE = 500
        /** How long a list waits for Navidrome (and Deezer) before it goes with what it has. */
        private const val BUILD_MS = 15_000L
        private const val DAY_MS = 24 * 3_600_000L
        /** Radio and AI mixes leave out what was played this long ago or since. */
        private const val RADIO_PLAYED_MS = 6 * 3_600_000L
        private const val LIKED_LATELY_MS = 30 * DAY_MS
        /** Related artists a list takes songs from. */
        private const val RELATED = 8
        private const val NOTHING_LIKE_IT = "Nothing like that in your library"
        private const val NO_PICKS = "Picks radio starts from your weekly picks and the songs you like. Like a few songs first."

        /** When something was liked: Subsonic's ISO 8601 time, with or without an offset. */
        private fun instant(text: String): Long? =
            runCatching { Instant.parse(text).toEpochMilli() }.getOrNull() ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }
}

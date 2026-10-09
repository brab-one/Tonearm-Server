package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.ConnectException
import java.net.URLEncoder
import java.net.http.HttpTimeoutException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Song lists from the user's own library, all through [SongPicker] and asked of Navidrome as the user: a radio that
 * goes on as long as there's something new (it keeps nothing: the apps send what's queued), a mix from a sentence
 * (the AI names songs, artists and genres; the library fills it), and up to three daily mixes made once a day from
 * the history. Radio never asks the AI, so it answers in seconds.
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

    data class Mix(
        val id: String,
        val prompt: String? = null,
        val title: String = "",
        val subtitle: String? = null,
        val songs: List<LibrarySong> = emptyList(),
        val running: Boolean = false,
        val problem: String? = null,
        /** The AI that made it. */
        val ai: String? = null,
    )

    data class DailyMixes(val day: String, val running: Boolean = false, val problem: String? = null, val mixes: List<Mix> = emptyList())

    private val dir = File(dataDir, "mixes")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pool = Executors.newFixedThreadPool(4) { Thread(it, "mixes").apply { isDaemon = true } }
    private val aiWorker = Executors.newSingleThreadExecutor { Thread(it, "mixes-ai").apply { isDaemon = true } }
    private val dailyWorker = Executors.newSingleThreadExecutor { Thread(it, "mixes-daily").apply { isDaemon = true } }

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

    // --- Mixes from a sentence ---------------------------------------------------------------------------------

    /** A mix as it was made (its songs as JSON text, which takes less room than parsed) and when. */
    private class Made(val mix: Mix, val songs: String, val at: Long)

    /** Mixes by user and sentence ([Recommendations.searchKey]), the last [KEEP_MIXES]. */
    private val made = LinkedHashMap<String, Made>(64, 0.75f, true)
    /** The sentence each user's mix in the making is for. */
    private val making = HashMap<String, String>()

    /**
     * A mix for [prompt]: the first call asks the AI in the background ([Mix.running] until it's done; the apps ask again).
     * The answer is kept for a day; [refresh] asks again. One mix per user at a time.
     */
    fun mix(user: String, login: Map<String, String>, prompt: String, refresh: Boolean): Mix {
        val ai = ai ?: throw IllegalStateException("No AI")
        val key = user + "\n" + Recommendations.searchKey(prompt)
        val id = "mix-%08x".format(key.hashCode())
        val started = synchronized(made) {
            val now = clock()
            made.values.removeIf { !kept(it, now) }
            made[key]?.takeIf { it.mix.running || !refresh }?.let { return answer(user, it) }
            making[user]?.takeIf { it != key }?.let { other ->
                return Mix(id, prompt, problem = "Still making “${made[other]?.mix?.prompt ?: "another mix"}”; one mix at a time", ai = ai.label)
            }
            Mix(id, prompt, running = true, ai = ai.label).also {
                made[key] = Made(it, "[]", now)
                making[user] = key
                while (made.size > KEEP_MIXES) made.entries.firstOrNull { e -> !e.value.mix.running }?.let { e -> made.remove(e.key) } ?: break
            }
        }
        aiWorker.execute {
            var done = Mix(id, prompt, problem = "The AI couldn't answer", ai = ai.label)
            try {
                done = make(ai, user, login, prompt, id)
            } catch (e: Exception) {
                val problem = when (e) {
                    is LibraryException -> "Couldn't read your library: ${e.message}"
                    is HttpTimeoutException -> "The AI couldn't answer: timed out"
                    is ConnectException -> "The AI couldn't answer: ${ai.name} can't be reached"
                    else -> "The AI couldn't answer: ${e.message ?: e.javaClass.simpleName}"
                }
                System.err.println("AI mix for $user failed: $problem")
                done = done.copy(problem = problem)
            } finally {
                synchronized(made) {
                    made[key] = Made(done.copy(songs = emptyList()), JsonArray(done.songs.map { it.raw }).toString(), clock())
                    making.remove(user)
                }
            }
        }
        return started
    }

    /** A mix is kept for a day, one without songs only until the apps have seen why; one in the making always. */
    private fun kept(made: Made, now: Long) = made.mix.running || now - made.at < if (made.songs == "[]") FAILED_MIX_MS else MIX_MS

    /** The mix with its songs, without the ones the user disliked since. */
    private fun answer(user: String, made: Made): Mix {
        val songs = json.parseToJsonElement(made.songs).jsonArray.mapNotNull { (it as? JsonObject)?.let(::LibrarySong) }
        val rules = rules(user)
        return made.mix.copy(songs = songs.filter(rules::allows))
    }

    private fun make(ai: Ai, user: String, login: Map<String, String>, prompt: String, id: String): Mix {
        val started = System.currentTimeMillis()
        val now = clock()
        val index = library.artists(user, login)
        val genres = library.genres(login).take(150)
        val day = now / DAY_MS
        val played = history.listening(user, now - 180 * DAY_MS, artists = 300, songs = 0, recent = 0).artists.filter { Recommendations.loose(it.artist) in index }.take(150)
        val playedKeys = played.map { Recommendations.loose(it.artist) }.toSet()
        val others = index.filterKeys { it !in playedKeys }.values.map { it.name }.sorted().shuffled(Random(day)).take(150)
        val question = buildString {
            appendLine("Make a playlist of about $MIX_SIZE songs for: \"$prompt\"")
            appendLine("Use the listener's own library below. Name songs only when you're sure of their exact titles; the rest of the list is filled from the artists and genres you choose.")
            appendLine("Give a short title (2–5 words), up to 25 songs (artist and exact title, in a good order), up to 15 artists from the lists and up to 5 genres from the genre list.")
            if (genres.isNotEmpty()) appendLine("\nGenres in the library (songs): " + genres.joinToString(", ") { "${it.first} (${it.second})" })
            if (played.isNotEmpty()) appendLine("\nArtists they play (plays in the last half year): " + played.joinToString(", ") { "${it.artist} (${it.plays})" })
            if (others.isNotEmpty()) appendLine("\nMore artists in the library: " + others.joinToString(", "))
            val disliked = history.dislikedSongs(user).sortedByDescending { it.at }.take(30)
            if (disliked.isNotEmpty()) appendLine("\nSongs they disliked, so avoid music like these: " + disliked.joinToString(", ") { "${it.artist} – ${it.title}" })
            val dismissed = history.dismissed(user).filter { it.album == null }
            if (dismissed.isNotEmpty()) appendLine("\nNever use these artists: " + dismissed.joinToString(", ") { it.artist })
        }
        // The last question asked, to see what the AI gets (no login in it).
        runCatching { DataFiles.write(File(dir, name(user) + ".mix-prompt.txt"), question) }
        val answer = ai.answer(MIX_SYSTEM, question, MIX_SCHEMA, temperature = 0.7, think = false)
        val reply = try {
            json.parseToJsonElement(answer.text).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException(if (answer.text.isBlank()) "no answer" else "the answer isn't complete JSON")
        }
        fun strings(key: String) = (reply[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
        val named = (reply["songs"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val artist = (o["artist"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (artist.isEmpty() || title.isEmpty()) null else artist to title
        }.distinctBy { SongKey.of(it.first, it.second) }.take(25)
        val artists = strings("artists").mapNotNull { index[Recommendations.loose(it)] }.distinctBy { it.id }.take(15)
        val genreNames = strings("genres").mapNotNull { g -> genres.firstOrNull { Recommendations.loose(it.first) == Recommendations.loose(g) }?.first }.distinct().take(5)

        val random = Random(id.hashCode() xor now.hashCode())
        val deadline = System.currentTimeMillis() + BUILD_MS
        // Made-up songs aren't found and drop out here.
        val found = named.map { (artist, title) -> start { library.find(login, artist, title) } }.mapNotNull { it.await(deadline) }.distinctBy { it.id }
        val byArtist = artists.map { a -> start { library.songsOf(login, a.id, 2, random).shuffled(random) } }
        val byGenre = genreNames.map { g -> start { library.random(login, g, null, null, 40) } }
        val parts = listOf(
            Part(0.4, found, lead = found.size, inOrder = true),
            Part(0.35, SongPicker.interleave(byArtist.map { it.await(deadline).orEmpty() })),
            Part(0.25, SongPicker.interleave(byGenre.map { it.await(deadline).orEmpty() })),
        )
        val picked = SongPicker.pick(parts, MIX_SIZE, rules(user, playedWithinMs = RADIO_PLAYED_MS), random, perArtist = 4)
        val shown = if (prompt.length > 40) prompt.take(40).trimEnd() + "…" else prompt
        println("AI mix for $user (\"$shown\"): ${found.size} of ${named.size} songs found, ${artists.size} artists, ${genreNames.size} genres, ${(System.currentTimeMillis() - started) / 1000} s")
        val title = (reply["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(80)?.ifEmpty { null } ?: prompt
        return Mix(id, prompt, title, songs = picked.songs, problem = if (picked.songs.isEmpty()) "Nothing in your library fits that" else null, ai = ai.label)
    }

    fun toJson(mix: Mix): JsonObject = buildJsonObject {
        put("id", mix.id)
        put("prompt", mix.prompt)
        put("title", mix.title)
        put("subtitle", mix.subtitle)
        put("songs", JsonArray(mix.songs.map { it.raw }))
        put("running", mix.running)
        put("problem", mix.problem)
        put("ai", mix.ai)
    }

    // --- Daily mixes -------------------------------------------------------------------------------------------

    @Serializable
    private data class StoredMix(val id: String, val title: String, val subtitle: String? = null, val songs: List<JsonObject> = emptyList())

    /** A user's daily mixes as kept in DATA_DIR/mixes/<user>.daily.json. */
    @Serializable
    private data class Daily(val day: String, val problem: String? = null, val mixes: List<StoredMix> = emptyList())

    /** A build that failed, said for a while before the next call tries again. */
    private class Failed(val day: String, val problem: String, val at: Long)

    private val building = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, Failed>()
    /** Daily mixes that couldn't be written, kept here with why until a write works again. */
    private val unsaved = ConcurrentHashMap<String, Daily>()

    /**
     * Today's daily mixes; a day starts at 04:00 in [zone]. The first call of a day makes them in the background with this
     * call's login ([DailyMixes.running] until then; the apps ask again). Songs disliked since are left out.
     */
    fun daily(user: String, login: Map<String, String>, zone: ZoneId): DailyMixes {
        val day = dayOf(clock(), zone)
        failed[user]?.takeIf { it.day == day && clock() - it.at < RETRY_DAILY_MS }?.let { return DailyMixes(day, problem = it.problem) }
        val stored = loadDaily(user)
        if (stored?.day == day && user !in building) {
            val rules = rules(user)
            return DailyMixes(day, problem = stored.problem, mixes = stored.mixes.map { m ->
                Mix(m.id, title = m.title, subtitle = m.subtitle, songs = m.songs.map(::LibrarySong).filter(rules::allows))
            })
        }
        if (building.add(user)) {
            dailyWorker.execute {
                try {
                    if (loadDaily(user)?.day != day) saveDaily(user, buildDaily(user, login, day))
                    failed.remove(user)
                } catch (e: Exception) {
                    val problem = "Couldn't make your daily mixes: ${e.message ?: e.javaClass.simpleName}"
                    System.err.println("Daily mixes for $user: $problem")
                    failed[user] = Failed(day, problem, clock())
                } finally {
                    building.remove(user)
                }
            }
        }
        return DailyMixes(day, running = true)
    }

    private fun buildDaily(user: String, login: Map<String, String>, day: String): Daily {
        val started = System.currentTimeMillis()
        val now = clock()
        val top = history.listening(user, now - 90 * DAY_MS, artists = 60, songs = 0, recent = 0).artists
        if (top.size < 3) return Daily(day, NO_HISTORY)
        val index = library.artists(user, login)
        val starredArtists = library.starred(login).second
        val plays = top.associate { Recommendations.loose(it.artist) to it.plays }
        val ranked = (top.map { it.artist } + starredArtists).mapNotNull { index[Recommendations.loose(it)] }.distinctBy { it.id }
        // Each artist's main genre: the one most of their albums have.
        var deadline = System.currentTimeMillis() + BUILD_MS
        val genreOf = ranked.map { a -> a to start { library.albumsOf(login, a.id) } }.associate { (a, future) ->
            a.id to future.await(deadline).orEmpty().mapNotNull { (it["genre"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        }
        fun playsOf(a: LibraryReader.ArtistRef) = plays[Recommendations.loose(a.name)] ?: 0
        val families = ranked.filter { genreOf[it.id] != null }.groupBy { family(genreOf.getValue(it.id)!!) }
            .values.filter { it.size >= 2 }.sortedByDescending { group -> group.sumOf(::playsOf) }.take(MAX_DAILY)
        // Hardly any genres in common: the top artists in groups by rank instead.
        val groups = if (families.size >= 2) families else ranked.chunked(CHUNK).take(MAX_DAILY)

        val previous = loadDaily(user)?.mixes.orEmpty().flatMap { m -> m.songs.map { LibrarySong(it).id } }.toSet()
        val heard = history.since(user, now - 90 * DAY_MS).filter { it.counts }.groupingBy { SongKey.of(it.artist, it.title) }.eachCount()
        val base = rules(user, previous, DAILY_PLAYED_MS)
        val used = HashSet<String>()
        val mixes = ArrayList<StoredMix>()
        for ((n, group) in groups.withIndex()) {
            deadline = System.currentTimeMillis() + BUILD_MS
            val members = group.take(8)
            val random = Random((user + day + n).hashCode())
            // Each artist's songs, the ones played most first.
            val own = members.map { a -> start { library.songsOf(login, a.id, 3, random).shuffled(random).sortedByDescending { heard[it.key] ?: 0 } } }
            val related = related(login, members, members.map { it.name }, index, random, deadline)
            val parts = listOf(Part(0.6, SongPicker.interleave(own.map { it.await(deadline).orEmpty() })), Part(0.4, related))
            val picked = SongPicker.pick(parts, MIX_SIZE, base.copy(excludeIds = base.excludeIds + used), random, perArtist = 6)
            if (picked.songs.isEmpty()) continue
            used += picked.songs.map { it.id }
            val names = members.take(2).joinToString(", ") { it.name }
            mixes += StoredMix("daily-$day-${mixes.size + 1}", "Daily Mix ${mixes.size + 1}", "$names and more", picked.songs.map { it.raw })
        }
        println("Daily mixes for $user ($day): ${mixes.size} of ${groups.size} groups (" + (if (families.size >= 2) "by genre" else "by rank") + "), " +
            "${mixes.sumOf { it.songs.size }} songs, ${(System.currentTimeMillis() - started) / 1000} s")
        return Daily(day, if (mixes.isEmpty()) NOTHING_NEW else null, mixes)
    }

    private fun loadDaily(user: String): Daily? = unsaved[user]
        ?: runCatching { json.decodeFromString(Daily.serializer(), dailyFile(user).readText()) }.getOrNull()

    private fun saveDaily(user: String, daily: Daily) {
        try {
            DataFiles.write(dailyFile(user), json.encodeToString(Daily.serializer(), daily))
            unsaved.remove(user)
        } catch (e: Exception) {
            unsaved[user] = daily.copy(problem = daily.problem ?: e.message)
        }
    }

    private fun dailyFile(user: String) = File(dir, name(user) + ".daily.json")

    fun toJson(daily: DailyMixes): JsonObject = buildJsonObject {
        put("day", daily.day)
        put("running", daily.running)
        put("problem", daily.problem)
        put("mixes", buildJsonArray {
            daily.mixes.forEach { m ->
                add(buildJsonObject {
                    put("id", m.id)
                    put("title", m.title)
                    put("subtitle", m.subtitle)
                    put("songs", JsonArray(m.songs.map { it.raw }))
                })
            }
        })
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

    private fun name(user: String) = URLEncoder.encode(user, Charsets.UTF_8)

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
        private const val DAILY_PLAYED_MS = 3 * DAY_MS
        private const val LIKED_LATELY_MS = 30 * DAY_MS
        /** Related artists a list takes songs from. */
        private const val RELATED = 8
        private const val MIX_SIZE = 30
        private const val KEEP_MIXES = 50
        private const val MIX_MS = DAY_MS
        /** A mix that failed is shown this long (longer than the apps take to ask again), then asked for again. */
        private const val FAILED_MIX_MS = 30_000L
        private const val RETRY_DAILY_MS = 5 * 60_000L
        private const val MAX_DAILY = 3
        /** Artists per daily mix when they go by rank. */
        private const val CHUNK = 6
        private const val NOTHING_LIKE_IT = "Nothing like that in your library"
        private const val NO_PICKS = "Picks radio starts from your weekly picks and the songs you like. Like a few songs first."
        private const val NO_HISTORY = "Play some music and your daily mixes show up tomorrow"
        private const val NOTHING_NEW = "Nothing new in your library for today's mixes"
        private const val MIX_SYSTEM = "You put together playlists from someone's own music library. Answer only with the requested JSON."

        private val MIX_SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("songs") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("artist") { put("type", "string") }
                            putJsonObject("title") { put("type", "string") }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("artist")); add(JsonPrimitive("title")) })
                    }
                }
                putJsonObject("artists") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                putJsonObject("genres") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            }
            put("required", buildJsonArray { listOf("title", "songs", "artists", "genres").forEach { add(JsonPrimitive(it)) } })
        }

        /** The day [at] belongs to in [zone]: from 04:00 to 04:00. */
        fun dayOf(at: Long, zone: ZoneId): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone).minusHours(4).toLocalDate().toString()

        /**
         * Families of genres for the daily mixes, the first that fits wins: a word of the genre is one of them, or starts
         * with one ending in "*"; several words have to come in that order.
         */
        private val FAMILIES = listOf(
            "metal" to listOf("metal*", "grindcore", "djent"),
            "punk" to listOf("punk*", "hardcore", "emo", "oi"),
            "rock" to listOf("rock*", "grunge", "shoegaze"),
            "hip hop" to listOf("hip hop", "hiphop", "rap", "trap", "grime", "drill", "boom bap"),
            "soul/r&b" to listOf("soul", "rnb", "r and b", "rhythm and blues", "funk*", "motown", "gospel"),
            "jazz" to listOf("jazz*", "swing", "bebop", "bop", "bossa nova", "big band"),
            "blues" to listOf("blues"),
            "classical" to listOf("classical", "baroque", "opera", "symphon*", "orchestr*", "chamber", "romantic", "contemporary classical"),
            "folk/country" to listOf("folk*", "country", "americana", "bluegrass", "singer songwriter"),
            "ambient" to listOf("ambient", "drone", "new age", "downtempo", "chillout", "chill out"),
            "electronic" to listOf(
                "electro*", "techno*", "house", "trance", "synth*", "edm", "dance", "dubstep", "drum and bass", "dnb", "idm",
                "trip hop", "triphop", "breakbeat", "garage", "jungle", "eurodance", "electronica",
            ),
            "pop" to listOf("pop", "*pop", "europop", "kpop", "jpop", "dream pop"),
            "reggae" to listOf("reggae", "dub", "dancehall", "ska", "roots"),
            "latin" to listOf("latin*", "salsa", "reggaeton", "bachata", "cumbia", "tango", "samba", "bossa"),
            "soundtrack" to listOf("soundtrack", "score", "ost", "film", "video game", "musical", "anime"),
        )

        /** The family [genre] belongs to ("Synthpop" → electronic, "Acid Jazz" → jazz), or the genre itself. */
        fun family(genre: String): String {
            val words = genre.lowercase().replace("r&b", "rnb").replace("&", " and ").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            fun fits(keyword: String): Boolean {
                val parts = keyword.split(' ')
                if (parts.size > 1) return words.windowed(parts.size).any { it == parts }
                return when {
                    keyword.endsWith("*") -> words.any { it.startsWith(keyword.dropLast(1)) }
                    keyword.startsWith("*") -> words.any { it.endsWith(keyword.drop(1)) }
                    else -> keyword in words
                }
            }
            return FAMILIES.firstOrNull { (_, keywords) -> keywords.any(::fits) }?.first ?: Recommendations.loose(genre)
        }

        /** When something was liked: Subsonic's ISO 8601 time, with or without an offset. */
        private fun instant(text: String): Long? =
            runCatching { Instant.parse(text).toEpochMilli() }.getOrNull() ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }
}

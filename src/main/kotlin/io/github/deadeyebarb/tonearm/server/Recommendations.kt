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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.Normalizer
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** One album an LLM suggests. */
@Serializable
data class AiPick(val artist: String, val album: String, val year: Int? = null, val why: String = "")

/** A user's suggestions as stored and handed to the apps. */
@Serializable
data class AiPicks(
    val picks: List<AiPick> = emptyList(),
    /** When the picks were made (ms); 0 if never. */
    val madeAt: Long = 0,
    /** True while new ones are being made. */
    val running: Boolean = false,
    /** Why the last attempt failed (older picks stay). Not "error": the apps read that as a failed call. */
    val problem: String? = null,
    val model: String = "",
    /** What the picks were asked to be like ("the album OK Computer by Radiohead"), if anything. */
    val seed: String? = null,
)

/** A suggested album as Lidarr knows it: the real one with its year, and what kind it is ("Album", "EP", "Live"…). */
data class AlbumMatch(val pick: AiPick, val kinds: Set<String> = setOf("Album"))

/**
 * Looks a suggested album up (in Lidarr): every release by that artist with that title, the likeliest first (the
 * very title, a plain studio album, the year), or none when it doesn't exist. Throws when it can't tell (Lidarr's
 * metadata search fails now and then); the pick is kept unchecked then, after the checked ones.
 */
fun interface AlbumCheck {
    fun find(pick: AiPick): List<AlbumMatch>
}

/** A run that failed after the model was asked, with what's known of it for the log. */
class RunFailed(message: String, val prompt: String, val answer: AiAnswer? = null, cause: Throwable? = null) : Exception(message, cause)

/** One suggestion as it was made, in DATA_DIR/recommendations/<user>.picks.jsonl. */
@Serializable
data class LoggedPick(
    val madeAt: Long,
    /** Its place in the list, from 1. */
    val rank: Int,
    val artist: String,
    val album: String,
    val year: Int? = null,
    val model: String = "",
    val seed: String? = null,
    /** False when Lidarr's search failed for it, so it was kept without knowing whether it exists. */
    val checked: Boolean = true,
)

/** How one run went, in DATA_DIR/recommendations/<user>.runs.jsonl: what the model did and what was left out, and why. */
@Serializable
data class PickRun(
    val at: Long,
    val model: String,
    val seed: String? = null,
    /** Suggestions in the model's answer. */
    val answered: Int = 0,
    val kept: Int = 0,
    /** Suggestions left out, by why ("in the library", "not in Lidarr", "Live"…). */
    val dropped: Map<String, Int> = emptyMap(),
    /** Kept without Lidarr's word that they exist (its search failed). */
    val unchecked: Int = 0,
    val promptTokens: Int? = null,
    val outputTokens: Int? = null,
    /** How long the model thought before answering (characters); 0 when it didn't, null when it can't say. */
    val thinkingChars: Int? = null,
    /** Why the model stopped ("stop", "length"…). */
    val stop: String? = null,
    val seconds: Long = 0,
    val problem: String? = null,
)

/** One thing the AI thinks a search means: a song or an album by an artist. */
@Serializable
data class AiHit(val artist: String, val title: String? = null, val album: String? = null, val why: String = "")

@Serializable
data class AiSearch(val query: String, val hits: List<AiHit> = emptyList(), val running: Boolean = false, val problem: String? = null)

/** What a user listens to: as Navidrome knows it, and from what the apps played (YouTube Music too). */
data class Taste(
    val mostPlayed: List<String>,
    val recent: List<String>,
    val liked: List<String>,
    /** Every artist in the library ([Recommendations.loose]), to leave out of the suggestions. */
    val library: Set<String>,
    /** The last month's artists with their plays, also ones not in the library. */
    val lately: List<ArtistCount> = emptyList(),
    /** Artists mostly skipped lately. */
    val skipped: List<String> = emptyList(),
    /** What they said no to. */
    val dismissed: List<Dismissed> = emptyList(),
    /** Earlier suggestions they took to: played again half a day or more later, or liked. */
    val tookTo: List<String> = emptyList(),
    /** Songs they disliked, latest first. */
    val dislikedSongs: List<String> = emptyList(),
)

/**
 * Album suggestions from an [Ai], made per user from what that user plays and likes on Navidrome (asked as
 * them, with their own login, while their request is open) and what the apps told [history] they played,
 * skipped and didn't want. The picks are kept in DATA_DIR and renewed when they're a week old or the user asks.
 */
class Recommendations(
    private val ai: Ai,
    private val navidromeUrl: String,
    dataDir: File,
    /** Weeds out albums the model made up; without it every suggestion is kept. */
    private val check: AlbumCheck? = null,
    private val history: History? = null,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    private val model get() = ai.model

    /** Which AI answers, for the apps ("Claude claude-opus-5-5"). */
    val label: String get() = (if (ai.name == "The AI") "" else ai.name + " ") + ai.model

    private val dir = File(dataDir, "recommendations").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "recommendations").apply { isDaemon = true } }
    private val searchWorker = Executors.newSingleThreadExecutor { Thread(it, "ai-search").apply { isDaemon = true } }
    /** AI searches by query, for a day: the same search from anyone gets the same answer. */
    private val searches = ConcurrentHashMap<String, Pair<AiSearch, Long>>()

    /**
     * The user's picks; starts new ones when asked to, or when there are none or they're a week old. A
     * [seed] asks for albums like that in particular ("more like this").
     */
    fun get(user: String, login: Map<String, String>, refresh: Boolean, seed: String? = null): AiPicks {
        val stored = load(user)
        val due = refresh || seed != null || stored.madeAt == 0L || System.currentTimeMillis() - stored.madeAt > MAX_AGE_MS
        if (due && running.add(user)) {
            val taste = try {
                taste(user, login)
            } catch (e: Exception) {
                running.remove(user)
                return stored.copy(problem = "Couldn't read your listening from Navidrome: ${e.message}")
            }
            worker.execute {
                val started = System.currentTimeMillis()
                try {
                    val outcome = run(taste, stored.picks, seed)
                    val picks = outcome.picks.filter { history?.isDismissed(user, it.artist, it.album) != true }
                    val madeAt = System.currentTimeMillis()
                    // An answer with nothing usable keeps the picks there are.
                    save(user, if (picks.isEmpty()) load(user).copy(problem = "The AI had nothing usable this time") else AiPicks(picks, madeAt, model = model, seed = seed))
                    log(user, outcome.copy(picks = picks), madeAt, seed, stored)
                } catch (e: Exception) {
                    val problem = "${ai.name}: ${e.message ?: e.javaClass.simpleName}"
                    save(user, load(user).copy(problem = problem))
                    // What the model was asked and how far it got: that's what tells why it failed.
                    val failed = e as? RunFailed
                    failed?.let { runCatching { DataFiles.write(File(dir, name(user) + ".prompt.txt"), it.prompt) } }
                    val answer = failed?.answer
                    log(user, PickRun(
                        started, model, seed, promptTokens = answer?.promptTokens, outputTokens = answer?.outputTokens,
                        thinkingChars = answer?.thinkingChars, stop = answer?.stop, seconds = (System.currentTimeMillis() - started) / 1000, problem = problem,
                    ))
                } finally {
                    running.remove(user)
                }
            }
        }
        return load(user).let { it.copy(picks = it.picks.filter { p -> history?.isDismissed(user, p.artist, p.album) != true }, running = user in running, model = model) }
    }

    /**
     * What the AI makes of a search: songs or albums that fit it, also when it's a description ("dreamy 90s trip
     * hop", "that song with the whistling"). Runs in the background; ask again for the answer.
     */
    fun search(query: String): AiSearch {
        val key = normalize(query)
        val now = System.currentTimeMillis()
        searches[key]?.takeIf { it.first.running || now - it.second < SEARCH_MS }?.let { return it.first }
        val started = AiSearch(query, running = true)
        searches[key] = started to now
        if (searches.size > 500) searches.entries.removeIf { now - it.value.second > SEARCH_MS }
        searchWorker.execute {
            val done = try {
                AiSearch(query, askSearch(query))
            } catch (e: Exception) {
                AiSearch(query, problem = "${ai.name}: ${e.message ?: e.javaClass.simpleName}")
            }
            searches[key] = done to System.currentTimeMillis()
        }
        return started
    }

    private fun askSearch(query: String): List<AiHit> {
        val prompt = "Someone searched a music library for: \"$query\"\n" +
            "Name up to 8 real songs or albums they most likely mean. If it's a name, give the best-known matches; if it describes " +
            "music (a mood, era, genre, a line of lyrics, \"that song with…\"), give songs or albums that fit. For each, the artist, " +
            "the song title or the album title, and a few words on why."
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("results") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("artist") { put("type", "string") }
                            putJsonObject("title") { put("type", "string") }
                            putJsonObject("album") { put("type", "string") }
                            putJsonObject("why") { put("type", "string") }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("artist")); add(JsonPrimitive("why")) })
                    }
                }
            }
            put("required", JsonArray(listOf(JsonPrimitive("results"))))
        }
        // Quick rather than deep: someone is waiting on the answer.
        val content = ai.answer(SYSTEM, prompt, schema, temperature = 0.3, think = false).text
        return json.parseToJsonElement(content).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val hit = AiHit(o.str("artist").trim(), o.str("title").trim().ifEmpty { null }, o.str("album").trim().ifEmpty { null }, o.str("why").trim())
            hit.takeIf { it.artist.isNotEmpty() && (it.title != null || it.album != null) }
        }.distinctBy { normalize(it.artist) + "|" + normalize(it.title ?: it.album.orEmpty()) }.take(8)
    }

    fun toJson(search: AiSearch): JsonObject = json.encodeToJsonElement(AiSearch.serializer(), search).jsonObject

    /** Reads the user's listening from Navidrome with their login, and from what the apps played. */
    fun taste(user: String, login: Map<String, String>): Taste {
        fun albums(type: String, size: Int) = subsonic("getAlbumList2", login + mapOf("type" to type, "size" to "$size"))["albumList2"]
            ?.jsonObject?.get("album")?.jsonArray.orEmpty().map { it.jsonObject }
        fun label(album: JsonObject) = "${album.str("artist")} – ${album.str("name")}"
        val starred = subsonic("getStarred2", login)["starred2"]?.jsonObject
        val liked = buildList {
            starred?.get("artist")?.jsonArray.orEmpty().forEach { add(it.jsonObject.str("name")) }
            starred?.get("album")?.jsonArray.orEmpty().forEach { add(label(it.jsonObject)) }
            starred?.get("song")?.jsonArray.orEmpty().take(60).forEach { add("${it.jsonObject.str("artist")} – ${it.jsonObject.str("title")}") }
        }
        val starredArtists = buildSet {
            starred?.get("artist")?.jsonArray.orEmpty().forEach { add(loose(it.jsonObject.str("name"))) }
            for (kind in listOf("album", "song")) starred?.get(kind)?.jsonArray.orEmpty().forEach { add(loose(Played.mainArtist(it.jsonObject.str("artist")))) }
        } - ""
        val library = subsonic("getArtists", login)["artists"]?.jsonObject?.get("index")?.jsonArray.orEmpty()
            .flatMap { it.jsonObject["artist"]?.jsonArray.orEmpty() }.map { loose(it.jsonObject.str("name")) }.toSet()
        val now = System.currentTimeMillis()
        val played = history?.played(user).orEmpty()
        // Earlier picks count as taken to when they were played again at least half a day apart (and not mostly
        // skipped), or liked. The others are on trial: an auto-downloaded album played through once says nothing yet,
        // so their plays are left out of what the model hears about, or it would chase its own guesses. Artists they
        // played before the model named them aren't its guesses, though: their listening stays in.
        val byArtist by lazy { played.groupBy { loose(it.mainArtist) } }
        val earlierPicks = earlierPicks(user, now)
        val taken = earlierPicks.filter { tookTo(it, byArtist[loose(it.artist)].orEmpty(), starredArtists) }
        val onTrial = earlierPicks.filterNot { pick -> byArtist[loose(pick.artist)].orEmpty().any { it.counts && it.at < pick.madeAt } }
            .map { loose(it.artist) }.toSet() - taken.map { loose(it.artist) }.toSet() - ""
        val lately = history?.listening(user, now - 30 * DAY_MS, artists = 30 + onTrial.size, songs = 0, recent = 0)?.artists.orEmpty()
            .filter { loose(it.artist) !in onTrial }.take(30)
        val quarter = history?.listening(user, now - 90 * DAY_MS, artists = 500, songs = 0, recent = 0)?.artists.orEmpty()
        val tookTo = taken.map { "${it.artist} – ${it.album}" }
        // Skips only say something next to few plays: everyone skips their favourites now and then.
        val skippers = played.filter { it.at > now - 90 * DAY_MS && it.skipped }.groupingBy { it.mainArtist }.eachCount()
            .filter { (artist, skips) -> skips >= 3 && skips > 2 * (quarter.firstOrNull { normalize(it.artist) == normalize(artist) }?.plays ?: 0) }
            .keys.take(20)
        fun mine(albums: List<JsonObject>) = albums.filter { loose(Played.mainArtist(it.str("artist"))) !in onTrial }.map(::label)
        return Taste(
            mine(albums("frequent", 40 + onTrial.size)).take(40), mine(albums("recent", 25 + onTrial.size)).take(25), liked.distinct().take(80), library,
            lately = lately, skipped = skippers.toList(), dismissed = history?.dismissed(user).orEmpty(), tookTo = tookTo,
            dislikedSongs = history?.dislikedSongs(user).orEmpty().sortedByDescending { it.at }.take(40).map { "${it.artist} – ${it.title}" },
        )
    }

    /** Asks the model, and keeps what's new to the library. */
    fun ask(taste: Taste, before: List<AiPick>, seed: String? = null): List<AiPick> = run(taste, before, seed).picks

    /** The picks of one run, with how it went and what the model was asked. */
    data class Outcome(val picks: List<AiPick>, val run: PickRun, val prompt: String, val unchecked: Set<AiPick> = emptySet())

    /**
     * Asks the model (thinking first, where it can: that brings back more of what it knows), and keeps what's new to the
     * library and real: albums Lidarr knows, not singles, EPs, live albums or compilations unless the [seed] is one.
     * Best fit first, one album per artist, the ones Lidarr couldn't check last.
     */
    fun run(taste: Taste, before: List<AiPick>, seed: String? = null): Outcome {
        // What the seed album is itself (live, a soundtrack…), and so which kinds of release are fine this time.
        val seedKinds = seedKinds(seed)
        val allowed = seedKinds intersect UNWANTED
        val prompt = buildString {
            appendLine("Suggest 20 albums this listener would probably love but doesn't have yet, best fit first.")
            if (seed != null) appendLine("Right now they want more like $seed: stay close to its sound and mood, using their taste below only as a guide.")
            val unwanted = listOf("Single" to "singles", "EP" to "EPs", "Live" to "live albums", "Compilation" to "compilations").filter { it.first !in allowed }.map { it.second }
            val no = if (unwanted.size < 2) unwanted.joinToString() else unwanted.dropLast(1).joinToString(", ") + " or " + unwanted.last()
            val kind = when {
                unwanted.isEmpty() -> ""
                seedKinds.isEmpty() -> "a studio album (no $no) "
                else -> "an album (no $no) "
            }
            appendLine("Every album must be ${kind}by an artist who is NOT in their library, must really exist, and every artist only once.")
            appendLine("Mix a few safe bets with some less obvious finds. For each, give the year and one short sentence on why it fits.")
            if (taste.mostPlayed.isNotEmpty()) appendLine("\nMost played albums:\n" + taste.mostPlayed.joinToString("\n"))
            if (taste.recent.isNotEmpty()) appendLine("\nPlayed recently:\n" + taste.recent.joinToString("\n"))
            if (taste.liked.isNotEmpty()) appendLine("\nLiked:\n" + taste.liked.joinToString("\n"))
            if (taste.lately.isNotEmpty()) {
                appendLine("\nWhat they've played this month, wherever they played it (plays in brackets; * = not in their library, so heard on YouTube Music):")
                appendLine(taste.lately.joinToString("\n") { "${it.artist} (${it.plays})" + if (loose(it.artist) in taste.library) "" else " *" })
            }
            if (taste.tookTo.isNotEmpty()) appendLine("\nEarlier suggestions they took to (came back to them, or liked them), so more in this direction works:\n" + taste.tookTo.joinToString("\n"))
            if (taste.skipped.isNotEmpty()) appendLine("\nArtists they mostly skip, so steer away from their sound:\n" + taste.skipped.joinToString("\n"))
            if (taste.dislikedSongs.isNotEmpty()) appendLine("\nSongs they disliked, so avoid music like these:\n" + taste.dislikedSongs.joinToString("\n"))
            if (taste.dismissed.isNotEmpty()) {
                appendLine("\nThey said no to these; never suggest them:")
                appendLine(taste.dismissed.joinToString("\n") { d -> d.album?.let { "${d.artist} – $it" } ?: "${d.artist} (anything by them)" })
            }
            if (before.isNotEmpty()) appendLine("\nAlready suggested before (don't repeat):\n" + before.joinToString("\n") { "${it.artist} – ${it.album}" })
        }
        val started = System.currentTimeMillis()
        val answer = try {
            ai.answer(SYSTEM, prompt, SCHEMA, temperature = 0.8, think = true)
        } catch (e: Exception) {
            throw RunFailed(e.message ?: e.javaClass.simpleName, prompt, cause = e)
        }
        val reply = try {
            json.parseToJsonElement(answer.text).jsonObject
        } catch (e: Exception) {
            // Typically the thinking filled the context and the answer was cut off.
            val why = if (answer.text.isBlank()) "no answer" else "the answer isn't complete JSON"
            val hint = when {
                answer.stop != "length" -> ""
                ai is OllamaAi -> "; a bigger AI_CONTEXT leaves more room"
                else -> "; it ran into the model's length limit"
            }
            throw RunFailed(why + (answer.stop?.let { " (stopped: $it)" } ?: "") + hint, prompt, answer, e)
        }
        val list = reply["recommendations"] as? JsonArray ?: throw RunFailed("the answer has no list of albums", prompt, answer)
        // Read leniently: one oddly made entry is skipped, not the whole answer.
        val picks = list.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            AiPick(o.str("artist").trim(), o.str("album").trim(), (o["year"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(), o.str("why").trim())
                .takeIf { it.artist.isNotEmpty() && it.album.isNotEmpty() }
        }
        val dropped = linkedMapOf<String, Int>()
        fun drop(why: String) {
            dropped.merge(why, 1, Int::plus)
        }
        fun key(pick: AiPick) = loose(pick.artist) + "|" + looseTitle(pick.album)
        val earlier = before.map(::key).toSet()
        val no = taste.dismissed.filter { it.album == null }.map { loose(it.artist) }.toSet()
        val noAlbums = taste.dismissed.mapNotNull { d -> d.album?.let { loose(d.artist) + "|" + looseTitle(it) } }.toSet()
        val usable = picks.filter { pick ->
            when {
                loose(pick.artist) in taste.library -> false.also { drop("in the library") }
                loose(pick.artist) in no || key(pick) in noAlbums -> false.also { drop("said no to") }
                else -> true
            }
        }
        val (repeats, new) = usable.partition { key(it) in earlier }
        val checked = mutableListOf<AiPick>()
        val unchecked = mutableListOf<AiPick>()
        val artists = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        var failing = 0
        fun consider(pick: AiPick) {
            if (!seen.add(key(pick))) return drop("suggested twice")
            if (loose(pick.artist) in artists) return drop("another album by the same artist")
            val check = check
            if (check == null) {
                checked += pick
                artists += loose(pick.artist)
                return
            }
            // Once Lidarr's search keeps failing, the rest go unchecked without waiting on it each time.
            val matches = if (failing >= MAX_FAILED_LOOKUPS) null else try {
                check.find(pick).also { failing = 0 }
            } catch (e: Exception) {
                failing++
                null
            }
            if (matches == null) {
                if (unchecked.any { loose(it.artist) == loose(pick.artist) }) return drop("another album by the same artist")
                unchecked += pick
                return
            }
            if (matches.isEmpty()) return drop("not in Lidarr")
            val match = matches.firstOrNull { m -> m.kinds.none { it in UNWANTED && it !in allowed } }
                ?: return drop(matches.first().kinds.filter { it in UNWANTED }.joinToString("/"))
            if (!artists.add(loose(match.pick.artist))) return drop("another album by the same artist")
            checked += match.pick.copy(why = pick.why)
        }
        fun settle(): List<AiPick> = unchecked.filter { artists.add(loose(it.artist)) || false.also { drop("another album by the same artist") } }
            .also { unchecked.clear() }
        for (pick in new) if (checked.size < KEEP) consider(pick)
        // Unchecked new picks keep their artists (and places) from earlier picks coming back.
        val newLast = settle()
        // Small models repeat themselves; earlier picks only come back when too few new ones made it.
        if (checked.size + newLast.size < MIN_NEW) {
            for (pick in repeats) if (checked.size + newLast.size < KEEP) consider(pick)
        } else {
            repeats.forEach { drop("suggested before") }
        }
        val last = newLast + settle()
        val kept = (checked + last).take(KEEP)
        val run = PickRun(
            at = started, model = model, answered = picks.size, kept = kept.size, dropped = dropped, unchecked = kept.count { it in last },
            promptTokens = answer.promptTokens, outputTokens = answer.outputTokens, thinkingChars = answer.thinkingChars, stop = answer.stop,
            seconds = (System.currentTimeMillis() - started) / 1000,
        )
        return Outcome(kept, run, prompt, kept.filter { it in last }.toSet())
    }

    /** Earlier picks still worth a look (from the last [TRIAL_DAYS] days), each artist once with when it was first suggested. */
    private fun earlierPicks(user: String, now: Long): List<LoggedPick> {
        // Before there was a log: the picks there are.
        val picks = loggedPicks(user).ifEmpty { load(user).let { stored -> stored.picks.mapIndexed { i, p -> LoggedPick(stored.madeAt, i + 1, p.artist, p.album, p.year) } } }
        return picks.filter { now - it.madeAt < TRIAL_DAYS * DAY_MS }.sortedBy { it.madeAt }.distinctBy { loose(it.artist) }
    }

    /**
     * Played again at least [APART_MS] after first (one listen-through doesn't stretch that far), and not mostly skipped;
     * or liked. [plays] are that artist's.
     */
    private fun tookTo(pick: LoggedPick, plays: List<Played>, starredArtists: Set<String>): Boolean {
        val key = loose(pick.artist)
        if (key.isEmpty()) return false
        if (key in starredArtists) return true
        val after = plays.filter { it.at > pick.madeAt }
        val times = after.filter { it.counts }.map { it.at }
        if (times.isEmpty()) return false
        return times.max() - times.min() >= APART_MS && after.count { it.skipped } <= times.size
    }

    /**
     * What the "more like" seed album is besides an album ("Live", "Soundtrack"…; none for a plain studio album, a song,
     * an artist or a playlist), as far as can be told: more like a live album may be live albums.
     */
    private fun seedKinds(seed: String?): Set<String> {
        val (title, artist) = seedAlbum(seed) ?: return emptySet()
        val check = check
        if (check != null && artist != null) {
            val matches = runCatching { check.find(AiPick(artist, title)) }.getOrNull().orEmpty()
            // A single named like the album doesn't say what the album is.
            (matches.firstOrNull { "Single" !in it.kinds } ?: matches.firstOrNull())?.let { return it.kinds - "Album" - "Studio" }
        }
        return kindsInTitle(title)
    }

    /** Writes down a run: the picks it made, how it went, and (for a look at what the model gets) its prompt. */
    private fun log(user: String, outcome: Outcome, madeAt: Long, seed: String?, before: AiPicks) {
        runCatching {
            // The first logged run also writes down the picks from before there was a log, so they keep their trial.
            val earlier = if (before.madeAt > 0 && loggedPicks(user).isEmpty()) before.picks.mapIndexed { i, p ->
                json.encodeToString(LoggedPick.serializer(), LoggedPick(before.madeAt, i + 1, p.artist, p.album, p.year, before.model, before.seed))
            } else emptyList()
            appendLog(picksLog(user), earlier + outcome.picks.mapIndexed { i, p ->
                json.encodeToString(LoggedPick.serializer(), LoggedPick(madeAt, i + 1, p.artist, p.album, p.year, model, seed, p !in outcome.unchecked))
            }, keep = 5_000)
        }
        runCatching { DataFiles.write(File(dir, name(user) + ".prompt.txt"), outcome.prompt) }
        log(user, outcome.run.copy(seed = seed))
    }

    private fun log(user: String, run: PickRun) {
        runCatching { appendLog(File(dir, name(user) + ".runs.jsonl"), listOf(json.encodeToString(PickRun.serializer(), run)), keep = 1_000) }
        println(summary(user, run))
    }

    /** One line for the server's log: "AI picks for alice: kept 12 of 20 (…), thought 5.1k chars, 1830 + 2410 tokens, 96 s". */
    private fun summary(user: String, run: PickRun): String = buildString {
        append("AI picks for $user")
        run.seed?.let { append(" (more like $it)") }
        if (run.problem != null) {
            append(" failed after ${run.seconds} s: ${run.problem}")
            return@buildString
        }
        append(": kept ${run.kept} of ${run.answered}")
        if (run.dropped.isNotEmpty()) append(" (left out: " + run.dropped.entries.joinToString(", ") { "${it.key} ${it.value}" } + ")")
        if (run.unchecked > 0) append(", ${run.unchecked} unchecked by Lidarr")
        when (run.thinkingChars) {
            null -> {}
            0 -> append(", no thinking came back")
            else -> append(", thought ${String.format(Locale.ROOT, "%.1f", run.thinkingChars / 1000.0)}k chars")
        }
        if (run.promptTokens != null || run.outputTokens != null) append(", ${run.promptTokens ?: "?"} + ${run.outputTokens ?: "?"} tokens")
        run.stop?.takeIf { it != "stop" && it != "end_turn" }?.let { append(", stopped: $it") }
        append(", ${run.seconds} s")
    }

    private fun loggedPicks(user: String): List<LoggedPick> = runCatching {
        picksLog(user).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
            line.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(LoggedPick.serializer(), it) }.getOrNull() }
        }
    }.getOrDefault(emptyList())

    /** Adds [lines] to a log, which keeps its last [keep] lines. */
    private fun appendLog(file: File, lines: List<String>, keep: Int) {
        if (lines.isEmpty()) return
        DataFiles.append(file, lines.joinToString("") { it + "\n" })
        if (file.length() > keep * 300L) {
            val all = file.readLines()
            if (all.size > keep) DataFiles.write(file, all.takeLast(keep).joinToString("") { it + "\n" })
        }
    }

    private fun picksLog(user: String) = File(dir, name(user) + ".picks.jsonl")

    private fun subsonic(method: String, params: Map<String, String>): JsonObject {
        val url = navidromeUrl.trimEnd('/') + "/rest/$method.view?" + (params + mapOf("v" to "1.16.1", "c" to "tonearm-server", "f" to "json"))
            .entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val body = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
        val response = json.parseToJsonElement(body).jsonObject["subsonic-response"]!!.jsonObject
        if (response.str("status") != "ok") throw IllegalStateException(response["error"]?.jsonObject?.str("message") ?: "error")
        return response
    }

    @Synchronized
    private fun load(user: String): AiPicks = unsaved[user] ?:
        runCatching { json.decodeFromString(AiPicks.serializer(), file(user).readText()) }.getOrDefault(AiPicks())

    /** Picks that couldn't be written, kept here with why (shown to the apps) until a write works again. */
    private val unsaved = ConcurrentHashMap<String, AiPicks>()

    @Synchronized
    private fun save(user: String, picks: AiPicks) {
        try {
            DataFiles.write(file(user), json.encodeToString(AiPicks.serializer(), picks.copy(running = false)))
            unsaved.remove(user)
        } catch (e: Exception) {
            unsaved[user] = picks.copy(running = false, problem = e.message)
        }
    }

    private fun file(user: String) = File(dir, name(user) + ".json")

    private fun name(user: String) = URLEncoder.encode(user, Charsets.UTF_8)

    fun toJson(picks: AiPicks): JsonObject = json.encodeToJsonElement(AiPicks.serializer(), picks).jsonObject

    companion object {
        private const val DAY_MS = 24 * 3_600_000L
        private const val MAX_AGE_MS = 7L * 24 * 3_600_000
        private const val SYSTEM = "You are a music critic with deep knowledge of albums across all genres. Answer only with the requested JSON."
        private const val MIN_NEW = 3
        private const val SEARCH_MS = 24 * 3_600_000L
        /** Picks shown (and the first few downloaded by weekly picks). */
        private const val KEEP = 12
        /** How long an earlier pick's plays count as trying it out. */
        private const val TRIAL_DAYS = 60L
        /** Plays of a pick this far apart mean they came back to it. */
        private const val APART_MS = 12 * 3_600_000L
        /** Lidarr searches failing in a row before the rest of a run's picks go unchecked without asking. */
        private const val MAX_FAILED_LOOKUPS = 3

        /** Kinds of release an album pick isn't for, unless the "more like" seed is one itself. Lidarr's names. */
        val UNWANTED = setOf("Single", "EP", "Broadcast", "Live", "Compilation", "Demo", "Spokenword", "Interview", "Audiobook", "Audio drama")

        private val ALBUM_SEED = Regex("^the album “(.+?)”(?: by (.+?))?(?: \\([^()]*\\))?$")

        /** The album a "more like" seed names ("the album “Dummy” by Portishead (Trip-Hop)"), or null for songs, artists and playlists. */
        fun seedAlbum(seed: String?): Pair<String, String?>? =
            seed?.trim()?.let(ALBUM_SEED::matchEntire)?.let { it.groupValues[1] to it.groupValues[2].ifEmpty { null } }

        /** The kinds an album's title says it is, when Lidarr can't tell: "Live at Leeds", "Greatest Hits". */
        fun kindsInTitle(title: String): Set<String> {
            val text = title.lowercase()
            return buildSet {
                if (Regex("\\b(live|unplugged)\\b").containsMatchIn(text)) add("Live")
                if (Regex("\\bep\\b").containsMatchIn(text)) add("EP")
                if (Regex("\\b(compilation|best of|greatest hits|anthology|collection)\\b").containsMatchIn(text)) add("Compilation")
                if (Regex("\\b(soundtrack|ost|original score|music from)\\b").containsMatchIn(text)) add("Soundtrack")
            }
        }

        /** The answer has to fit this. */
        private val SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("recommendations") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("artist") { put("type", "string") }
                            putJsonObject("album") { put("type", "string") }
                            putJsonObject("year") { put("type", "integer") }
                            putJsonObject("why") { put("type", "string") }
                        }
                        put("required", buildJsonArray { listOf("artist", "album", "year", "why").forEach { add(JsonPrimitive(it)) } })
                    }
                }
            }
            put("required", JsonArray(listOf(JsonPrimitive("recommendations"))))
        }

        /** An album title without "(Deluxe Edition)", "[Remastered]" and the like, for comparing. */
        fun bareTitle(title: String) = normalize(title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), ""))

        /** Lowercase letters and digits only, so case, accents and punctuation don't matter. */
        fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(MARKS, "").replace(NOT_LATIN, "")

        private val MARKS = Regex("\\p{M}")
        private val NOT_LATIN = Regex("[^a-z0-9]")

        /**
         * A name for comparing: case, accents and punctuation don't matter, "&", "+" and "and" are the same, and a leading
         * "The" doesn't count ("The Beatles" is "Beatles", "The-Dream" is "Dream"). Every script keeps its letters and
         * digits ("乃木坂46" isn't "日向坂46"), and a name of only symbols ("!!!") stays itself.
         */
        fun loose(text: String): String {
            val lower = text.trim().lowercase()
            val plain = lower.replace(LEADING_THE, "").ifBlank { lower }.replace(CONNECTORS, " ")
            // Compatibility forms first ("´" is a space and an accent then), so every accent goes before letters are put back together.
            val folded = Normalizer.normalize(ACCENTS.replace(Normalizer.normalize(plain, Normalizer.Form.NFKD), ""), Normalizer.Form.NFC)
            return folded.filter { it.isLetterOrDigit() || it.category == CharCategory.NON_SPACING_MARK || it.category == CharCategory.COMBINING_SPACING_MARK }
                .ifEmpty { plain.filterNot(Char::isWhitespace) }
        }

        private val LEADING_THE = Regex("^the\\b[^\\p{L}\\p{N}]*")
        private val CONNECTORS = Regex("&|\\+|\\band\\b")
        private val ACCENTS = Regex("\\p{InCombiningDiacriticalMarks}+")

        /** An album title for comparing: [loose], without "(Deluxe Edition)", "[Remastered]" and the like. */
        fun looseTitle(title: String): String = loose(title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), ""))

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}

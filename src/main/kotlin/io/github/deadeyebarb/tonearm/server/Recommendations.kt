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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.Normalizer
import java.time.Duration
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
)

/**
 * Looks a suggested album up (in Lidarr): the real one with its year, or null when it doesn't exist. Throws
 * when it can't tell (Lidarr's metadata search fails now and then); the pick is kept unchecked then.
 */
fun interface AlbumCheck {
    fun find(pick: AiPick): AiPick?
}

/** What a user listens to, as Navidrome knows it. */
data class Taste(
    val mostPlayed: List<String>,
    val recent: List<String>,
    val liked: List<String>,
    /** Every artist in the library, to leave out of the suggestions. */
    val library: Set<String>,
)

/**
 * Album suggestions from Ollama, made per user from what that user plays and likes on Navidrome (asked as
 * them, with their own login, while their request is open). The picks are kept in DATA_DIR and renewed
 * when they're a week old or the user asks.
 */
class Recommendations(
    private val ollamaUrl: String,
    private val model: String,
    private val navidromeUrl: String,
    dataDir: File,
    /** Weeds out albums the model made up; without it every suggestion is kept. */
    private val check: AlbumCheck? = null,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    private val dir = File(dataDir, "recommendations").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "recommendations").apply { isDaemon = true } }

    /** The user's picks; starts new ones when asked to, or when there are none or they're a week old. */
    fun get(user: String, login: Map<String, String>, refresh: Boolean): AiPicks {
        val stored = load(user)
        val due = refresh || stored.madeAt == 0L || System.currentTimeMillis() - stored.madeAt > MAX_AGE_MS
        if (due && running.add(user)) {
            val taste = try {
                taste(login)
            } catch (e: Exception) {
                running.remove(user)
                return stored.copy(problem = "Couldn't read your listening from Navidrome: ${e.message}")
            }
            worker.execute {
                try {
                    val picks = ask(taste, stored.picks)
                    save(user, AiPicks(picks, System.currentTimeMillis(), model = model))
                } catch (e: Exception) {
                    save(user, load(user).copy(problem = "Ollama: ${e.message ?: e.javaClass.simpleName}"))
                } finally {
                    running.remove(user)
                }
            }
        }
        return load(user).copy(running = user in running, model = model)
    }

    /** Reads the user's listening from Navidrome with their login. */
    fun taste(login: Map<String, String>): Taste {
        fun albums(type: String, size: Int) = subsonic("getAlbumList2", login + mapOf("type" to type, "size" to "$size"))["albumList2"]
            ?.jsonObject?.get("album")?.jsonArray.orEmpty().map { it.jsonObject }
        fun label(album: JsonObject) = "${album.str("artist")} – ${album.str("name")}"
        val starred = subsonic("getStarred2", login)["starred2"]?.jsonObject
        val liked = buildList {
            starred?.get("artist")?.jsonArray.orEmpty().forEach { add(it.jsonObject.str("name")) }
            starred?.get("album")?.jsonArray.orEmpty().forEach { add(label(it.jsonObject)) }
            starred?.get("song")?.jsonArray.orEmpty().take(60).forEach { add("${it.jsonObject.str("artist")} – ${it.jsonObject.str("title")}") }
        }
        val library = subsonic("getArtists", login)["artists"]?.jsonObject?.get("index")?.jsonArray.orEmpty()
            .flatMap { it.jsonObject["artist"]?.jsonArray.orEmpty() }.map { normalize(it.jsonObject.str("name")) }.toSet()
        return Taste(albums("frequent", 40).map(::label), albums("recent", 25).map(::label), liked.distinct().take(80), library)
    }

    /** Asks the model, and keeps what's new to the library. */
    fun ask(taste: Taste, before: List<AiPick>): List<AiPick> {
        val prompt = buildString {
            appendLine("Suggest 20 albums this listener would probably love but doesn't have yet.")
            appendLine("Every album must be by an artist who is NOT in their library, must really exist, and the artists should vary.")
            appendLine("Mix a few safe bets with some less obvious finds. For each, give the year and one short sentence on why it fits.")
            if (taste.mostPlayed.isNotEmpty()) appendLine("\nMost played albums:\n" + taste.mostPlayed.joinToString("\n"))
            if (taste.recent.isNotEmpty()) appendLine("\nPlayed recently:\n" + taste.recent.joinToString("\n"))
            if (taste.liked.isNotEmpty()) appendLine("\nLiked:\n" + taste.liked.joinToString("\n"))
            if (before.isNotEmpty()) appendLine("\nAlready suggested before (don't repeat):\n" + before.joinToString("\n") { "${it.artist} – ${it.album}" })
        }
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", "You are a music critic with deep knowledge of albums across all genres. Answer only with the requested JSON.") })
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
            }
            put("format", SCHEMA)
            putJsonObject("options") { put("temperature", 0.8); put("num_ctx", 8192) }
        }
        val request = HttpRequest.newBuilder(URI.create(ollamaUrl.trimEnd('/') + "/api/chat"))
            .timeout(Duration.ofMinutes(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().take(200)}")
        val content = json.parseToJsonElement(response.body()).jsonObject["message"]?.jsonObject?.str("content")
            ?: throw IllegalStateException("no answer")
        val picks = json.parseToJsonElement(content).jsonObject["recommendations"]?.jsonArray.orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            AiPick(o.str("artist").trim(), o.str("album").trim(), o["year"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(), o.str("why").trim())
                .takeIf { it.artist.isNotEmpty() && it.album.isNotEmpty() }
        }
        val earlier = before.map { normalize(it.artist) + "|" + normalize(it.album) }.toSet()
        return picks.filter { normalize(it.artist) !in taste.library && normalize(it.artist) + "|" + normalize(it.album) !in earlier }
            .distinctBy { normalize(it.artist) + "|" + normalize(it.album) }
            .asSequence()
            .mapNotNull { pick ->
                val check = check ?: return@mapNotNull pick
                runCatching { check.find(pick)?.copy(why = pick.why) }.getOrElse { pick }
            }
            .distinctBy { normalize(it.artist) + "|" + normalize(it.album) }
            .take(12)
            .toList()
    }

    private fun subsonic(method: String, params: Map<String, String>): JsonObject {
        val url = navidromeUrl.trimEnd('/') + "/rest/$method.view?" + (params + mapOf("v" to "1.16.1", "c" to "tonearm-server", "f" to "json"))
            .entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val body = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
        val response = json.parseToJsonElement(body).jsonObject["subsonic-response"]!!.jsonObject
        if (response.str("status") != "ok") throw IllegalStateException(response["error"]?.jsonObject?.str("message") ?: "error")
        return response
    }

    @Synchronized
    private fun load(user: String): AiPicks =
        runCatching { json.decodeFromString(AiPicks.serializer(), file(user).readText()) }.getOrDefault(AiPicks())

    @Synchronized
    private fun save(user: String, picks: AiPicks) {
        val f = file(user)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(json.encodeToString(AiPicks.serializer(), picks.copy(running = false)))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun file(user: String) = File(dir, URLEncoder.encode(user, Charsets.UTF_8) + ".json")

    fun toJson(picks: AiPicks): JsonObject = json.encodeToJsonElement(AiPicks.serializer(), picks).jsonObject

    companion object {
        private const val MAX_AGE_MS = 7L * 24 * 3_600_000

        /** Ollama's structured output: the answer has to fit this. */
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
            .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]"), "")

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}

package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Against a fake Navidrome (one user's listening) and a fake Ollama. */
class RecommendationsTest {
    private lateinit var fake: HttpServer
    private var asked: String? = null
    private lateinit var engine: Recommendations

    private fun answer(body: String, ex: com.sun.net.httpserver.HttpExchange) {
        val bytes = body.encodeToByteArray()
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @BeforeTest
    fun start() {
        fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/rest/") { ex ->
                val body = when (ex.requestURI.path.substringAfterLast('/')) {
                    "getAlbumList2.view" -> if ("frequent" in ex.requestURI.query) """{"albumList2":{"album":[{"artist":"Radiohead","name":"OK Computer"}]}}"""
                    else """{"albumList2":{"album":[{"artist":"Portishead","name":"Dummy"}]}}"""
                    "getStarred2.view" -> """{"starred2":{"artist":[{"name":"Massive Attack"}],"song":[{"artist":"Björk","title":"Jóga"}]}}"""
                    "getArtists.view" -> """{"artists":{"index":[{"artist":[{"name":"Radiohead"},{"name":"Portishead"},{"name":"Björk"}]}]}}"""
                    else -> "{}"
                }
                answer("""{"subsonic-response":{"status":"ok",${body.removePrefix("{")}}""", ex)
            }
            createContext("/api/chat") { ex ->
                asked = ex.requestBody.readAllBytes().decodeToString()
                if ("searched a music library" in asked!!) {
                    val hits = """{"results":[{"artist":"Portishead","title":"Glory Box","why":"Trip-hop."},{"artist":"Massive Attack","album":"Mezzanine","why":"Dark."},{"artist":"Nobody","why":"No title."}]}"""
                    return@createContext answer(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
                        put("message", kotlinx.serialization.json.buildJsonObject { put("content", kotlinx.serialization.json.JsonPrimitive(hits)) })
                    }), ex)
                }
                val picks = """{"recommendations":[
                    {"artist":"Mazzy Star","album":"So Tonight That I Might See","year":1993,"why":"Dreamy and dark like Portishead."},
                    {"artist":"bjork","album":"Homogenic","year":1997,"why":"Already in the library."},
                    {"artist":"Tricky","album":"Maxinquaye","year":1995,"why":"Trip-hop."},
                    {"artist":"Mazzy Star","album":"So Tonight That I Might See","year":1993,"why":"Twice."}]}"""
                answer(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
                    put("message", kotlinx.serialization.json.buildJsonObject { put("role", kotlinx.serialization.json.JsonPrimitive("assistant")); put("content", kotlinx.serialization.json.JsonPrimitive(picks)) })
                }), ex)
            }
            start()
        }
        val url = "http://127.0.0.1:${fake.address.port}"
        engine = Recommendations(url, "qwen2.5", url, Files.createTempDirectory("tonearm-recs").toFile())
    }

    @AfterTest
    fun stop() = fake.stop(0)

    private val login = mapOf("u" to "alice", "p" to "wonderland")

    @Test
    fun theTasteComesFromTheUsersOwnListening() {
        val taste = engine.taste(login)
        assertEquals(listOf("Radiohead – OK Computer"), taste.mostPlayed)
        assertEquals(listOf("Portishead – Dummy"), taste.recent)
        assertEquals(listOf("Massive Attack", "Björk – Jóga"), taste.liked)
        assertTrue("bjork" in taste.library)
    }

    @Test
    fun suggestionsLeaveOutTheLibraryAndRepeats() {
        // Only one new one, so the earlier pick comes back after it.
        val picks = engine.ask(engine.taste(login), before = listOf(AiPick("Tricky", "Maxinquaye")))
        assertEquals(listOf("Mazzy Star", "Tricky"), picks.map { it.artist })
        val request = Json.parseToJsonElement(asked!!).jsonObject
        assertEquals("qwen2.5", request["model"]!!.jsonPrimitive.content)
        assertEquals("false", request["stream"]!!.jsonPrimitive.content)
        assertTrue("recommendations" in request["format"].toString())
        assertTrue("Portishead – Dummy" in asked!! && "Tricky – Maxinquaye" in asked!!)
    }

    @Test
    fun aSeedAsksForMoreLikeIt() {
        engine.ask(engine.taste(login), before = emptyList(), seed = "the album “Dummy” by Portishead")
        assertTrue("more like the album “Dummy” by Portishead" in Json.parseToJsonElement(asked!!).toString().replace("\\u201c", "“").replace("\\u201d", "”"))
    }

    @Test
    fun madeUpAlbumsAreDroppedWhenLidarrCanTell() {
        val url = "http://127.0.0.1:${fake.address.port}"
        val checked = Recommendations(url, "qwen2.5", url, Files.createTempDirectory("tonearm-recs").toFile(), AlbumCheck { pick ->
            when (pick.artist) {
                "Mazzy Star" -> pick.copy(album = "So Tonight That I Might See", year = 1993)
                "Tricky" -> null
                else -> error("Lidarr's search failed")
            }
        })
        assertEquals(listOf("Mazzy Star"), checked.ask(checked.taste(login), before = emptyList()).map { it.artist })
        val unsure = Recommendations(url, "qwen2.5", url, Files.createTempDirectory("tonearm-recs").toFile(), AlbumCheck { error("Lidarr's search failed") })
        assertEquals(listOf("Mazzy Star", "Tricky"), unsure.ask(unsure.taste(login), before = emptyList()).map { it.artist })
        assertEquals(Recommendations.bareTitle("OK Computer"), Recommendations.bareTitle("OK Computer (Collector's Edition) [Remastered]"))
    }

    @Test
    fun anAnswerWithNothingUsableKeepsThePicksThereAre() {
        val first = waitFor(engine.get("alice", login, refresh = false))
        assertEquals(2, first.picks.size)
        // Next time everything it suggests is in the library.
        fake.removeContext("/rest/")
        fake.createContext("/rest/") { ex ->
            val body = if ("getArtists" in ex.requestURI.path) """"artists":{"index":[{"artist":[{"name":"Mazzy Star"},{"name":"Tricky"},{"name":"Björk"}]}]}""" else """"albumList2":{}"""
            answer("""{"subsonic-response":{"status":"ok",$body}}""", ex)
        }
        val second = waitFor(engine.get("alice", login, refresh = true))
        assertEquals(first.picks, second.picks)
        assertEquals("The AI had nothing usable this time", second.problem)
    }

    private fun waitFor(start: AiPicks): AiPicks {
        var now = start
        repeat(50) { if (now.running) { Thread.sleep(100); now = engine.get("alice", login, refresh = false) } }
        return now
    }

    @Test
    fun anAiSearchAnswersInTheBackgroundAndIsKept() {
        var search = engine.search("dreamy 90s trip hop")
        repeat(50) { if (search.running) { Thread.sleep(100); search = engine.search("dreamy 90s trip hop") } }
        assertEquals(listOf("Portishead" to "Glory Box", "Massive Attack" to "Mezzanine"), search.hits.map { it.artist to (it.title ?: it.album) })
        asked = null
        assertEquals(search, engine.search("Dreamy 90s Trip-Hop"))
        assertEquals(null, asked)
    }

    @Test
    fun picksAreMadeInTheBackgroundAndKept() {
        val first = engine.get("alice", login, refresh = false)
        assertTrue(first.running || first.picks.isNotEmpty())
        var now = first
        repeat(50) { if (now.running) { Thread.sleep(100); now = engine.get("alice", login, refresh = false) } }
        assertEquals(false, now.running)
        assertEquals(2, now.picks.size)
        assertTrue(now.madeAt > 0)
        // Kept: asking again doesn't start another run until they're old or a refresh is asked for.
        asked = null
        assertEquals(now.picks, engine.get("alice", login, refresh = false).picks)
        assertEquals(null, asked)
    }
}

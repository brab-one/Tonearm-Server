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
    fun suggestionsLeaveOutTheLibraryRepeatsAndEarlierOnes() {
        val picks = engine.ask(engine.taste(login), before = listOf(AiPick("Tricky", "Maxinquaye")))
        assertEquals(listOf("Mazzy Star"), picks.map { it.artist })
        val request = Json.parseToJsonElement(asked!!).jsonObject
        assertEquals("qwen2.5", request["model"]!!.jsonPrimitive.content)
        assertEquals("false", request["stream"]!!.jsonPrimitive.content)
        assertTrue("recommendations" in request["format"].toString())
        assertTrue("Portishead – Dummy" in asked!! && "Tricky – Maxinquaye" in asked!!)
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

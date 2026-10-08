package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Against a fake Navidrome (one user's listening) and a fake Ollama. */
class RecommendationsTest {
    private lateinit var fake: HttpServer
    private var asked: String? = null
    private lateinit var engine: Recommendations
    /** What the fake model suggests instead of the usual four, when set. */
    private var suggests: String? = null
    /** Albums added to Navidrome's most played. */
    private var alsoFrequent = ""

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
                    "getAlbumList2.view" -> if ("frequent" in ex.requestURI.query) """{"albumList2":{"album":[{"artist":"Radiohead","name":"OK Computer"}$alsoFrequent]}}"""
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
                val picks = suggests ?: """{"recommendations":[
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
        engine = Recommendations(OllamaAi(url, "qwen2.5", pull = false), url, Files.createTempDirectory("tonearm-recs").toFile())
    }

    @AfterTest
    fun stop() = fake.stop(0)

    private val login = mapOf("u" to "alice", "p" to "wonderland")

    @Test
    fun theTasteComesFromTheUsersOwnListening() {
        val taste = engine.taste("alice", login)
        assertEquals(listOf("Radiohead – OK Computer"), taste.mostPlayed)
        assertEquals(listOf("Portishead – Dummy"), taste.recent)
        assertEquals(listOf("Massive Attack", "Björk – Jóga"), taste.liked)
        assertTrue("bjork" in taste.library)
    }

    @Test
    fun suggestionsLeaveOutTheLibraryAndRepeats() {
        // Only one new one, so the earlier pick comes back after it.
        val picks = engine.ask(engine.taste("alice", login), before = listOf(AiPick("Tricky", "Maxinquaye")))
        assertEquals(listOf("Mazzy Star", "Tricky"), picks.map { it.artist })
        val request = Json.parseToJsonElement(asked!!).jsonObject
        assertEquals("qwen2.5", request["model"]!!.jsonPrimitive.content)
        assertEquals("false", request["stream"]!!.jsonPrimitive.content)
        assertTrue("recommendations" in request["format"].toString())
        assertTrue("Portishead – Dummy" in asked!! && "Tricky – Maxinquaye" in asked!!)
    }

    @Test
    fun aSeedAsksForMoreLikeIt() {
        engine.ask(engine.taste("alice", login), before = emptyList(), seed = "the album “Dummy” by Portishead")
        assertTrue("more like the album “Dummy” by Portishead" in Json.parseToJsonElement(asked!!).toString().replace("\\u201c", "“").replace("\\u201d", "”"))
    }

    @Test
    fun whatTheAppsPlayedAndWhatTheySaidNoToShapeThePicks() {
        val url = "http://127.0.0.1:${fake.address.port}"
        val history = History(Files.createTempDirectory("tonearm-history").toFile())
        val now = System.currentTimeMillis()
        history.add("alice", (1..4).map { Played(now - it * 60_000L, "Sia", "Song $it", durationMs = 200_000, listenedMs = 200_000, source = "youtube") } +
            (1..2).map { Played(now - 600_000L - it, "Portishead", "Roads $it", durationMs = 300_000, listenedMs = 300_000) })
        history.dismiss("alice", "Mazzy Star", null)
        val engine = Recommendations(OllamaAi(url, "qwen2.5", pull = false), url, Files.createTempDirectory("tonearm-recs").toFile(), history = history)
        assertEquals(listOf("Tricky"), engine.ask(engine.taste("alice", login), before = emptyList()).map { it.artist })
        assertTrue("Sia (4) *" in asked!!)
        assertTrue("Portishead (2)" in asked!! && "Portishead (2) *" !in asked!!)
        assertTrue("Mazzy Star (anything by them)" in asked!!)
        assertEquals("Ollama qwen2.5", engine.label)
    }

    @Test
    fun madeUpAlbumsAreDroppedWhenLidarrCanTell() {
        val url = "http://127.0.0.1:${fake.address.port}"
        val checked = Recommendations(OllamaAi(url, "qwen2.5", pull = false), url, Files.createTempDirectory("tonearm-recs").toFile(), AlbumCheck { pick ->
            when (pick.artist) {
                "Mazzy Star" -> listOf(AlbumMatch(pick.copy(album = "So Tonight That I Might See", year = 1993)))
                "Tricky" -> emptyList()
                else -> error("Lidarr's search failed")
            }
        })
        assertEquals(listOf("Mazzy Star"), checked.ask(checked.taste("alice", login), before = emptyList()).map { it.artist })
        val unsure = Recommendations(OllamaAi(url, "qwen2.5", pull = false), url, Files.createTempDirectory("tonearm-recs").toFile(), AlbumCheck { error("Lidarr's search failed") })
        assertEquals(listOf("Mazzy Star", "Tricky"), unsure.ask(unsure.taste("alice", login), before = emptyList()).map { it.artist })
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

    private fun suggest(vararg picks: Pair<String, String>) {
        suggests = """{"recommendations":[""" + picks.joinToString(",") { (artist, album) -> """{"artist":"$artist","album":"$album","year":2000,"why":"Fits."}""" } + "]}"
    }

    private fun lidarr(vararg kinds: Pair<String, Set<String>>) = AlbumCheck { pick ->
        when (val known = kinds.toMap()[pick.album]) {
            null -> emptyList()
            emptySet<String>() -> error("Lidarr's search failed")
            else -> listOf(AlbumMatch(pick, known))
        }
    }

    private fun engine(dir: java.io.File = Files.createTempDirectory("tonearm-recs").toFile(), check: AlbumCheck? = null, history: History? = null): Recommendations {
        val url = "http://127.0.0.1:${fake.address.port}"
        return Recommendations(OllamaAi(url, "qwen2.5", pull = false), url, dir, check, history)
    }

    private fun Recommendations.finish(): AiPicks {
        var picks = get("alice", login, refresh = false)
        repeat(50) { if (picks.running) { Thread.sleep(100); picks = get("alice", login, refresh = false) } }
        return picks
    }

    @Test
    fun bestFitFirstOneAlbumPerArtistAndTheUncheckedLast() {
        suggest(
            "Low" to "Things We Lost in the Fire", "Slowdive" to "Souvlaki", "Slowdive" to "Pygmalion", "Cocteau Twins" to "Live at Leeds",
            "Simon and Garfunkel" to "Bookends", "Beach House" to "Bloom", "Mojave 3" to "Excuses for Travellers",
        )
        val url = "http://127.0.0.1:${fake.address.port}"
        val engine = Recommendations(OllamaAi(url, "qwen2.5", pull = false), url, Files.createTempDirectory("tonearm-recs").toFile(), lidarr(
            "Things We Lost in the Fire" to emptySet(), "Souvlaki" to setOf("Album"), "Pygmalion" to setOf("Album"), "Live at Leeds" to setOf("Album", "Live"),
            "Bloom" to setOf("Album"), "Excuses for Travellers" to setOf("EP"),
        ))
        val taste = engine.taste("alice", login).let { it.copy(library = it.library + Recommendations.loose("Simon & Garfunkel")) }
        val outcome = engine.run(taste, before = emptyList())
        // Low's album couldn't be checked, so it comes after the ones Lidarr knows.
        assertEquals(listOf("Slowdive", "Beach House", "Low"), outcome.picks.map { it.artist })
        assertEquals(
            mapOf("in the library" to 1, "another album by the same artist" to 1, "Live" to 1, "EP" to 1),
            outcome.run.dropped,
        )
        assertEquals(1, outcome.run.unchecked)
        assertEquals(7, outcome.run.answered)
        assertTrue("best fit first" in asked!!)
    }

    @Test
    fun moreLikeALiveAlbumMayBeLiveAlbums() {
        suggest("Cocteau Twins" to "Live at Leeds")
        val url = "http://127.0.0.1:${fake.address.port}"
        val engine = Recommendations(OllamaAi(url, "qwen2.5", pull = false), url, Files.createTempDirectory("tonearm-recs").toFile(), lidarr("Live at Leeds" to setOf("Album", "Live")))
        assertEquals(listOf("Cocteau Twins"), engine.ask(engine.taste("alice", login), before = emptyList(), seed = "the album “Live at Pompeii” by Pink Floyd").map { it.artist })
        assertEquals(emptyList(), engine.ask(engine.taste("alice", login), before = emptyList(), seed = "the album “Dummy” by Portishead"))
    }

    @Test
    fun aRunIsLoggedWithItsPicksAndPrompt() {
        val dir = Files.createTempDirectory("tonearm-recs").toFile()
        val url = "http://127.0.0.1:${fake.address.port}"
        val engine = Recommendations(OllamaAi(url, "qwen2.5", pull = false), url, dir)
        var picks = engine.get("alice", login, refresh = false)
        repeat(50) { if (picks.running) { Thread.sleep(100); picks = engine.get("alice", login, refresh = false) } }
        val logged = File(dir, "recommendations/alice.picks.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("Mazzy Star", "Tricky"), logged.map { it["artist"]!!.jsonPrimitive.content })
        assertEquals(listOf("1", "2"), logged.map { it["rank"]!!.jsonPrimitive.content })
        val run = Json.parseToJsonElement(File(dir, "recommendations/alice.runs.jsonl").readLines().single()).jsonObject
        assertEquals("2", run["kept"]!!.jsonPrimitive.content)
        assertEquals("4", run["answered"]!!.jsonPrimitive.content)
        assertTrue("Portishead – Dummy" in File(dir, "recommendations/alice.prompt.txt").readText())
    }

    @Test
    fun onlyPicksComeBackToOrLikedCountAsTakenTo() {
        val dir = Files.createTempDirectory("tonearm-recs").toFile()
        val url = "http://127.0.0.1:${fake.address.port}"
        val history = History(Files.createTempDirectory("tonearm-history").toFile())
        suggest("Mazzy Star" to "So Tonight That I Might See", "Tricky" to "Maxinquaye", "Massive Attack" to "Mezzanine", "Hope Sandoval" to "Bavarian Fruit Bread")
        val engine = Recommendations(OllamaAi(url, "qwen2.5", pull = false), url, dir, history = history)
        var picks = engine.get("alice", login, refresh = false)
        repeat(50) { if (picks.running) { Thread.sleep(100); picks = engine.get("alice", login, refresh = false) } }
        val after = picks.madeAt + 60_000
        val day = 24 * 3_600_000L
        fun play(artist: String, at: Long) = Played(at, artist, "$artist song", durationMs = 200_000, listenedMs = 200_000)
        history.add("alice", listOf(
            // Mazzy Star's downloaded album, played through once: three plays on one day.
            play("Mazzy Star", after), play("Mazzy Star", after + 1), play("Mazzy Star", after + 2),
            // Tricky again two days later.
            play("Tricky", after), play("Tricky", after + 2 * day),
        ))
        alsoFrequent = """,{"artist":"Mazzy Star","name":"So Tonight That I Might See"},{"artist":"Tricky","name":"Maxinquaye"}"""
        val taste = engine.taste("alice", login)
        // Massive Attack is starred on Navidrome, so that one counts too.
        assertEquals(listOf("Tricky – Maxinquaye", "Massive Attack – Mezzanine"), taste.tookTo)
        // The one only tried out stays out of what the model hears about.
        assertEquals(listOf("Radiohead – OK Computer", "Tricky – Maxinquaye"), taste.mostPlayed)
        assertEquals(listOf("Tricky"), taste.lately.map { it.artist })
    }

    @Test
    fun artistsTheyPlayedBeforeThePickArentOnTrial() {
        val history = History(Files.createTempDirectory("tonearm-history").toFile())
        val now = System.currentTimeMillis()
        // Sia, heard a lot on YouTube Music before the model named her.
        history.add("alice", (1..20).map { Played(now - it * 3_600_000L, "Sia", "Song $it", durationMs = 200_000, listenedMs = 200_000, source = "youtube") })
        suggest("Sia" to "1000 Forms of Fear", "Tricky" to "Maxinquaye")
        val engine = engine(history = history)
        engine.finish()
        history.add("alice", (1..3).map { Played(System.currentTimeMillis() + it * 60_000L, "Tricky", "Tricky $it", durationMs = 200_000, listenedMs = 200_000) })
        assertEquals(listOf("Sia"), engine.taste("alice", login).lately.map { it.artist })
    }

    @Test
    fun oneListenThroughAroundMidnightIsntComingBack() {
        val history = History(Files.createTempDirectory("tonearm-history").toFile())
        suggest("Tricky" to "Maxinquaye", "Mazzy Star" to "So Tonight That I Might See")
        val engine = engine(history = history)
        val at = engine.finish().madeAt
        // Tricky: one sitting of 40 minutes, whatever the date says. Mazzy Star: again 13 hours later.
        history.add("alice", listOf(
            Played(at + 60_000, "Tricky", "One", durationMs = 200_000, listenedMs = 200_000),
            Played(at + 40 * 60_000, "Tricky", "Two", durationMs = 200_000, listenedMs = 200_000),
            Played(at + 60_000, "Mazzy Star", "Fade Into You", durationMs = 200_000, listenedMs = 200_000),
            Played(at + 13 * 3_600_000L, "Mazzy Star", "Halah", durationMs = 200_000, listenedMs = 200_000),
        ))
        assertEquals(listOf("Mazzy Star – So Tonight That I Might See"), engine.taste("alice", login).tookTo)
    }

    @Test
    fun earlierPicksComeBackWhenTooFewNewOnesMakeIt() {
        val before = (1..10).map { AiPick("Band $it", "Album $it") }
        // Ten repeats and one new album, named three times: the earlier picks fill the list up.
        suggest(*(before.map { it.artist to it.album } + List(3) { "Slowdive" to "Souvlaki" }).toTypedArray())
        val plain = engine()
        val outcome = plain.run(plain.taste("alice", login), before)
        assertEquals(listOf("Slowdive") + before.map { it.artist }, outcome.picks.map { it.artist })
        assertEquals(mapOf("suggested twice" to 2), outcome.run.dropped)
        // Three new albums Lidarr has never heard of: the repeats come back rather than nothing.
        suggest(*(before.map { it.artist to it.album } + listOf("A" to "x", "B" to "y", "C" to "z")).toTypedArray())
        val checked = engine(check = AlbumCheck { pick -> if (pick.artist.startsWith("Band")) listOf(AlbumMatch(pick)) else emptyList() })
        assertEquals(10, checked.run(checked.taste("alice", login), before).picks.size)
    }

    @Test
    fun onlyAnAlbumSeedThatIsOneAllowsItsKind() {
        suggest("Oasis" to "Familiar to Millions", "Blur" to "Parklife")
        val check = lidarr("Familiar to Millions" to setOf("Album", "Live"), "Parklife" to setOf("Album"))
        val song = engine(check = check)
        assertEquals(listOf("Blur"), song.ask(song.taste("alice", login), emptyList(), seed = "the song “Live Forever” by Oasis").map { it.artist })
        assertTrue("a studio album (no singles, EPs, live albums or compilations)" in asked!!)
        // An album Lidarr knows as live: more like it may be live albums, and the prompt says so.
        val live = engine(check = AlbumCheck { pick -> if (pick.album == "Live at Leeds") listOf(AlbumMatch(pick, setOf("Album", "Live"))) else check.find(pick) })
        assertEquals(listOf("Oasis", "Blur"), live.ask(live.taste("alice", login), emptyList(), seed = "the album “Live at Leeds” by The Who (Rock)").map { it.artist })
        assertTrue("an album (no singles, EPs or compilations)" in asked!!)
        // A studio album with "Live" in its title that Lidarr knows: no live albums.
        val studio = engine(check = AlbumCheck { pick -> if (pick.album == "Live Through This") listOf(AlbumMatch(pick)) else check.find(pick) })
        assertEquals(listOf("Blur"), studio.ask(studio.taste("alice", login), emptyList(), seed = "the album “Live Through This” by Hole").map { it.artist })
    }

    @Test
    fun namesCompareLoosely() {
        assertEquals(1, listOf("Florence + the Machine", "Florence & the Machine", "Florence and the Machine").map(Recommendations::loose).toSet().size)
        assertEquals(Recommendations.loose("The-Dream"), Recommendations.loose("Dream"))
        assertEquals("theatreoftragedy", Recommendations.loose("Theatre of Tragedy"))
        assertEquals("andrewbird", Recommendations.loose("Andrew Bird"))
        // Other scripts keep their letters, so two such artists aren't one.
        assertTrue(Recommendations.loose("坂本龍一").isNotEmpty() && Recommendations.loose("坂本龍一") != Recommendations.loose("宇多田ヒカル"))
    }

    @Test
    fun aFailedRunKeepsWhatTheModelGotTo() {
        val dir = Files.createTempDirectory("tonearm-recs").toFile()
        suggests = """{"recommendations":[{"artist":"Mazzy Star","alb"""
        val picks = engine(dir).finish()
        assertTrue(picks.problem!!.contains("isn't complete JSON"), picks.problem)
        // Without picks yet, asking again tries again: the first run is the one to look at.
        val run = Json.parseToJsonElement(File(dir, "recommendations/alice.runs.jsonl").readLines().first()).jsonObject
        assertTrue(run["problem"]!!.jsonPrimitive.content.contains("isn't complete JSON"))
        assertTrue("Portishead – Dummy" in File(dir, "recommendations/alice.prompt.txt").readText())
    }

    @Test
    fun uncheckedPicksKeepTheirPlaceAndCountOncePerArtist() {
        val before = (1..10).map { AiPick("Band $it", "Album $it") }
        // Lidarr can't say for X: two of its albums count as one new pick, so the earlier picks still come back.
        suggest(*(listOf("X" to "a", "X" to "b", "Y" to "c") + before.map { it.artist to it.album }).toTypedArray())
        val engine = engine(check = AlbumCheck { pick -> if (pick.artist == "X") error("Lidarr's search failed") else listOf(AlbumMatch(pick)) })
        val outcome = engine.run(engine.taste("alice", login), before)
        assertEquals(listOf("Y") + before.map { it.artist } + "X", outcome.picks.map { it.artist })
        assertEquals(1, outcome.run.dropped["another album by the same artist"])
        // With twelve earlier picks, the unchecked new one isn't pushed out by them.
        val twelve = (1..12).map { AiPick("Band $it", "Album $it") }
        suggest(*(listOf("X" to "a", "Y" to "c") + twelve.map { it.artist to it.album }).toTypedArray())
        assertTrue("X" in engine.run(engine.taste("alice", login), twelve).picks.map { it.artist })
    }

    @Test
    fun aFailingLidarrIsntAskedAboutEveryPick() {
        suggest(*(1..20).map { "Artist $it" to "Album $it" }.toTypedArray())
        var lookups = 0
        val engine = engine(check = AlbumCheck { lookups++; error("Lidarr's search failed") })
        val outcome = engine.run(engine.taste("alice", login), emptyList())
        assertEquals(12, outcome.picks.size)
        assertEquals(12, outcome.run.unchecked)
        assertEquals(3, lookups)
    }

    @Test
    fun theSeedAlbumsOwnKindDecides() {
        suggest("Somebody Else" to "Hold On Too", "Blur" to "Parklife", "Vangelis" to "Blade Runner")
        val kinds = mapOf("Hold On Too" to setOf("EP"), "Parklife" to setOf("Album"), "Blade Runner" to setOf("Album", "Soundtrack"))
        val check = AlbumCheck { pick ->
            when (pick.album) {
                // An EP whose title track was a single too, the single listed first.
                "Hold On" -> listOf(AlbumMatch(pick, setOf("Single")), AlbumMatch(pick, setOf("EP")))
                "Tron: Legacy" -> listOf(AlbumMatch(pick, setOf("Album", "Soundtrack")))
                else -> kinds[pick.album]?.let { listOf(AlbumMatch(pick, it)) }.orEmpty()
            }
        }
        val engine = engine(check = check)
        assertEquals(listOf("Somebody Else", "Blur", "Vangelis"), engine.ask(engine.taste("alice", login), emptyList(), seed = "the album “Hold On” by Somebody (Indie)").map { it.artist })
        assertTrue("an album (no singles, live albums or compilations)" in asked!!)
        // More like a soundtrack isn't asked for studio albums.
        engine.ask(engine.taste("alice", login), emptyList(), seed = "the album “Tron: Legacy” by Daft Punk (Soundtrack)")
        assertTrue("an album (no singles, EPs, live albums or compilations)" in asked!!)
    }

    @Test
    fun oddEntriesInTheAnswerAreSkipped() {
        suggests = """{"recommendations":[{"artist":"Tricky","album":"Maxinquaye","year":[1995],"why":"Odd year."},"just text",{"artist":"Mazzy Star","album":"So Tonight That I Might See","year":1993}]}"""
        assertEquals(listOf("Tricky" to null, "Mazzy Star" to 1993), engine().ask(engine().taste("alice", login), emptyList()).map { it.artist to it.year })
    }

    @Test
    fun aLengthLimitHintFitsTheAi() {
        val cut = object : Ai {
            override val name = "The AI"
            override val model = "some-model"
            override fun answer(system: String, prompt: String, schema: kotlinx.serialization.json.JsonObject, temperature: Double, think: Boolean) =
                AiAnswer("", stop = "length")
        }
        val url = "http://127.0.0.1:${fake.address.port}"
        val other = Recommendations(cut, url, Files.createTempDirectory("tonearm-recs").toFile())
        val problem = runCatching { other.run(other.taste("alice", login), emptyList()) }.exceptionOrNull()!!.message!!
        assertEquals("no answer (stopped: length); it ran into the model's length limit", problem)
    }

    @Test
    fun namesInOtherScriptsStayApart() {
        assertTrue(Recommendations.loose("乃木坂46") != Recommendations.loose("日向坂46"))
        assertTrue(Recommendations.loose("米米CLUB") != Recommendations.loose("The Club"))
        assertEquals("!!!", Recommendations.loose("!!!"))
        assertEquals("the", Recommendations.loose("The"))
        assertEquals(Recommendations.loose("Sigur Ros"), Recommendations.loose("Sigur Rós"))
        assertTrue(Recommendations.loose("が") != Recommendations.loose("か"))
    }

    @Test
    fun spacingAccentsAreAccentsToo() {
        assertEquals(Recommendations.loose("Guns N' Roses"), Recommendations.loose("Guns N´ Roses"))
        assertEquals("gunsnroses", Recommendations.loose("Guns N´ Roses"))
        assertEquals(Recommendations.loose("Motorhead"), Recommendations.loose("Motörhead"))
    }

    @Test
    fun dismissingOneArtistInAnotherScriptLeavesTheOthers() {
        val history = History(Files.createTempDirectory("tonearm-history").toFile())
        history.dismiss("alice", "ヨルシカ", null)
        history.dismiss("alice", "乃木坂46", null)
        assertTrue(history.isDismissed("alice", "ヨルシカ"))
        assertFalse(history.isDismissed("alice", "米津玄師"))
        assertFalse(history.isDismissed("alice", "日向坂46"))
        assertEquals(2, history.dismissed("alice").size)
    }

    @Test
    fun picksFromBeforeTheLogKeepTheirTrial() {
        val dir = Files.createTempDirectory("tonearm-recs").toFile()
        val old = System.currentTimeMillis() - 3 * 24 * 3_600_000L
        // Picks saved by an older server, with no log yet.
        File(dir, "recommendations").mkdirs()
        File(dir, "recommendations/alice.json").writeText("""{"picks":[{"artist":"Low","album":"Things We Lost in the Fire"}],"madeAt":$old,"model":"qwen2.5"}""")
        val engine = engine(dir)
        engine.get("alice", login, refresh = true)
        engine.finish()
        val logged = File(dir, "recommendations/alice.picks.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("Low", "Mazzy Star", "Tricky"), logged.map { it["artist"]!!.jsonPrimitive.content })
        assertEquals(old.toString(), logged.first()["madeAt"]!!.jsonPrimitive.content)
    }
}

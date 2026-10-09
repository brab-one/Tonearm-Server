package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against a fake Navidrome (a small library as alice sees it), a fake Deezer and a fake Ollama. */
class MixesTest {
    private class Album(val id: String, val name: String, val artistId: String, val genre: String, val year: Int, val songs: List<String>)

    private val artists = mapOf(
        "ar1" to "The Oscillators", "ar2" to "Signal Garden", "ar3" to "Neon Cascade",
        "ar4" to "Midnight Quartet", "ar5" to "Brush Trio", "ar6" to "Kosmische Drift",
    )
    private val albums = listOf(
        Album("al1", "Hi-Res Tones", "ar1", "Electronic", 2024, listOf("Low Hum", "Middle C", "Bright Sine", "Octave Up")),
        Album("al2", "CD Quality", "ar1", "Electronic", 2019, listOf("Forty-Four", "One Kilohertz", "Two Kilohertz")),
        Album("al3", "Studio Masters", "ar2", "Ambient", 2022, listOf("Deep Drone", "Shimmer", "Pure Tone")),
        Album("al4", "Afterglow", "ar3", "Synthpop", 2023, listOf("Chrome Rain", "Afterglow", "Night Drive", "Tail Lights")),
        Album("al5", "Blue Hour", "ar4", "Jazz", 1997, listOf("Slow Rain", "Late Set", "Brushes", "Last Call")),
        Album("al6", "Brushwork", "ar5", "Acid Jazz", 1995, listOf("Sweep", "Ride", "Rimshot")),
        Album("al7", "Orbit I", "ar6", "Ambient", 2019, listOf("Orbit", "Apogee", "Perigee")),
        Album("al8", "Low End", "ar1", "Electronic", 2021, listOf("Sub", "Rumble", "Floor", "Wobble", "Thump", "Hum Again")),
    )
    /** Navidrome's similar artists (its agents); "-1" is how it lists one the library hasn't got. */
    private val similarArtists = mapOf("ar1" to listOf("ar3", "-1"), "ar3" to listOf("ar1"), "ar5" to listOf("ar4"), "ar4" to listOf("ar5"), "ar2" to listOf("ar6"), "ar6" to listOf("ar2"))
    /** Deezer's related artists, by name. */
    private val related = mapOf("The Oscillators" to listOf("Vector Choir", "Signal Garden"))
    private val similarSongs = mapOf("al1-2" to listOf("al4-1", "al3-1", "al1-3"))

    private lateinit var fake: HttpServer
    private lateinit var url: String
    private lateinit var dir: File
    private lateinit var history: History
    /** Requests that reached the fake Navidrome: method and id (or query). */
    private val calls = mutableListOf<Pair<String, String?>>()
    private val starred = mutableMapOf<String, Long>()
    private val playlists = mutableListOf<JsonObject>()
    private var aiAnswer = """{"title":"Low Tones","songs":[{"artist":"The Oscillators","title":"Low Hum"},{"artist":"Nobody","title":"Made Up"}],"artists":["Signal Garden"],"genres":["Ambient"]}"""
    private var aiFails = false
    private var aiGate: CountDownLatch? = null
    private var aiCalls = 0
    /** How long Navidrome's agents (similar songs and artists, Last.fm say) take to answer. */
    private var agentsMs = 0L
    private var now = ZonedDateTime.of(2026, 10, 9, 12, 0, 0, 0, VIENNA).toInstant().toEpochMilli()
    private val login = mapOf("u" to "alice", "p" to "wonderland")

    private fun song(id: String): JsonObject {
        val album = albums.first { id.startsWith(it.id + "-") }
        val n = id.substringAfterLast('-').toInt()
        return buildJsonObject {
            put("id", id)
            put("parent", album.id)
            put("title", album.songs[n - 1])
            put("album", album.name)
            put("artist", artists.getValue(album.artistId))
            put("albumId", album.id)
            put("artistId", album.artistId)
            put("genre", album.genre)
            put("year", album.year)
            put("track", n)
            put("size", 1_000_000 + n)
            put("contentType", "audio/flac")
            putJsonObject("replayGain") { put("trackGain", -3.0 - n); put("albumGain", -4.5) }
            starred[id]?.let { put("starred", Instant.ofEpochMilli(it).toString()) }
        }
    }

    private fun songsOf(album: Album) = album.songs.indices.map { song("${album.id}-${it + 1}") }

    private fun allSongs() = albums.flatMap(::songsOf)

    private fun answer(ex: HttpExchange, body: String, code: Int = 200) {
        val bytes = body.encodeToByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun ok(key: String, value: Any) = """{"subsonic-response":{"status":"ok","$key":$value}}"""

    private fun songList(key: String, inner: String, songs: List<JsonObject>) = ok(key, buildJsonObject { put(inner, JsonArray(songs)) })

    @BeforeTest
    fun start() {
        fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/rest/") { ex ->
                val q = ex.requestURI.rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
                val method = ex.requestURI.path.substringAfterLast('/').removeSuffix(".view")
                synchronized(calls) { calls += method to (q["id"] ?: q["query"] ?: q["genre"]) }
                if (method == "getSimilarSongs2" || method == "getArtistInfo2") Thread.sleep(agentsMs)
                if (q["u"] != "alice" || q["p"] != "wonderland") return@createContext answer(ex, """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""")
                val notFound = """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Not found"}}}"""
                val body = when (method) {
                    "getArtists" -> ok("artists", buildJsonObject {
                        put("index", buildJsonArray { add(buildJsonObject { put("artist", JsonArray(artists.map { (id, name) -> buildJsonObject { put("id", id); put("name", name) } })) }) })
                    })
                    "getArtist" -> artists[q["id"]]?.let { name ->
                        ok("artist", buildJsonObject {
                            put("id", q["id"]); put("name", name)
                            put("album", JsonArray(albums.filter { it.artistId == q["id"] }.map { a -> buildJsonObject { put("id", a.id); put("name", a.name); put("genre", a.genre); put("playCount", 0) } }))
                        })
                    } ?: notFound
                    "getAlbum" -> albums.firstOrNull { it.id == q["id"] }?.let { a -> ok("album", buildJsonObject { put("id", a.id); put("song", JsonArray(songsOf(a))) }) } ?: notFound
                    "getSong" -> runCatching { ok("song", song(q.getValue("id"))) }.getOrDefault(notFound)
                    "getSimilarSongs2" -> songList("similarSongs2", "song", similarSongs[q["id"]].orEmpty().map(::song))
                    "getArtistInfo2" -> ok("artistInfo2", buildJsonObject {
                        put("similarArtist", JsonArray(similarArtists[q["id"]].orEmpty().map { id -> buildJsonObject { put("id", id); put("name", artists[id] ?: "Vector Choir") } }))
                    })
                    "getRandomSongs" -> songList("randomSongs", "song", allSongs().filter { s ->
                        val year = s["year"]!!.jsonPrimitive.content.toInt()
                        (q["genre"] == null || s["genre"]!!.jsonPrimitive.content == q["genre"]) &&
                            (q["fromYear"] == null || year >= q.getValue("fromYear").toInt()) && (q["toYear"] == null || year <= q.getValue("toYear").toInt())
                    }.shuffled().take(q["size"]?.toInt() ?: 10))
                    "getGenres" -> ok("genres", buildJsonObject {
                        put("genre", JsonArray(albums.groupBy { it.genre }.map { (g, a) -> buildJsonObject { put("value", g); put("songCount", a.sumOf { it.songs.size }) } }))
                    })
                    "getStarred2" -> ok("starred2", buildJsonObject { put("song", JsonArray(starred.keys.map(::song))) })
                    "search3" -> songList("searchResult3", "song", allSongs().filter { q.getValue("query").lowercase() in it["title"]!!.jsonPrimitive.content.lowercase() })
                    "getPlaylists" -> ok("playlists", buildJsonObject { put("playlist", JsonArray(playlists.map { p -> JsonObject(p - "songs") })) })
                    "getPlaylist" -> playlists.firstOrNull { it["id"]!!.jsonPrimitive.content == q["id"] }?.let { p ->
                        ok("playlist", buildJsonObject { put("id", q["id"]); put("entry", JsonArray((p["songs"] as JsonArray).map { song(it.jsonPrimitive.content) })) })
                    } ?: notFound
                    else -> """{"subsonic-response":{"status":"ok"}}"""
                }
                answer(ex, body)
            }
            createContext("/deezer/") { ex ->
                val path = ex.requestURI.path.removePrefix("/deezer")
                val q = URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), Charsets.UTF_8).substringAfter("q=").substringBefore('&')
                val names = related.keys.toList()
                val body = when {
                    path == "/search/artist" -> names.indexOfFirst { it.equals(q, ignoreCase = true) }.takeIf { it >= 0 }
                        ?.let { """{"data":[{"id":${it + 1},"name":"${names[it]}"}]}""" } ?: """{"data":[]}"""
                    path.endsWith("/related") -> {
                        val name = names[path.removePrefix("/artist/").substringBefore('/').toInt() - 1]
                        """{"data":[${related.getValue(name).joinToString(",") { """{"id":99,"name":"$it"}""" }}]}"""
                    }
                    else -> """{"data":[]}"""
                }
                answer(ex, body)
            }
            createContext("/api/chat") { ex ->
                synchronized(this@MixesTest) { aiCalls++ }
                ex.requestBody.readAllBytes()
                aiGate?.await(10, TimeUnit.SECONDS)
                if (aiFails) return@createContext answer(ex, """{"error":"model crashed"}""", 500)
                answer(ex, buildJsonObject { putJsonObject("message") { put("role", "assistant"); put("content", aiAnswer) }; put("done_reason", "stop") }.toString())
            }
            start()
        }
        url = "http://127.0.0.1:${fake.address.port}"
        dir = Files.createTempDirectory("tonearm-mixes").toFile()
        history = History(dir)
    }

    @AfterTest
    fun stop() = fake.stop(0)

    private fun mixes(dataDir: File = dir, ai: Ai? = OllamaAi(url, "fake", pull = false)) =
        Mixes(LibraryReader(url), history, Discovery(url, dataDir, deezer = "$url/deezer"), ai, dataDir, clock = { now })

    private fun Mixes.Radio.ids() = songs.map { it.id }
    private fun Mixes.Radio.artists() = songs.map { it.artist }

    /** The ids Navidrome was asked [method] for. */
    private fun asked(method: String) = synchronized(calls) { calls.filter { it.first == method }.map { it.second } }

    @Test
    fun aSongsRadioIsItsSimilarSongsAndLibraryArtistsLikeIt() {
        val radio = mixes().radio("alice", login, Mixes.SONG, "al1-2", null, null, listOf("yt/dQw4w9WgXcQ", "lib/al1-2"), 10)
        assertEquals("Radio · Middle C", radio.title)
        assertFalse("al1-2" in radio.ids())
        // Navidrome's similar artist and Deezer's related one that the library has; nothing by artists not like it.
        assertTrue("Neon Cascade" in radio.artists() && "Signal Garden" in radio.artists(), radio.artists().toString())
        assertTrue(radio.artists().all { it in setOf("The Oscillators", "Neon Cascade", "Signal Garden") }, radio.artists().toString())
        assertTrue(radio.artists().groupingBy { it }.eachCount().values.all { it <= 2 })
        assertEquals(radio.ids().distinct(), radio.ids())
        assertTrue(radio.more)
        // The songs go out as Navidrome gave them.
        assertTrue(radio.songs.all { it.raw == song(it.id) })
        assertTrue(radio.songs.all { it.raw["size"] != null && it.raw["replayGain"] is JsonObject })
    }

    @Test
    fun theNextBatchBringsNewSongsAndFollowsTheTail() {
        val mixes = mixes()
        val first = mixes.radio("alice", login, Mixes.SONG, "al1-2", null, null, listOf("lib/al1-2"), 5)
        synchronized(calls) { calls.clear() }
        val queue = listOf("lib/al1-2") + first.ids().map { "lib/$it" }
        val second = mixes.radio("alice", login, Mixes.SONG, "al1-2", null, null, queue, 5)
        assertTrue(second.songs.isNotEmpty())
        assertTrue(second.ids().none { it in first.ids() }, "${first.ids()} then ${second.ids()}")
        // The last songs queued are seeds too.
        val asked = synchronized(calls) { calls.filter { it.first == "getSimilarSongs2" }.map { it.second } }
        assertTrue(first.ids().takeLast(3).all { it in asked }, asked.toString())
        // Asking until nothing new is left: every library song like it once.
        var all = queue + second.ids().map { "lib/$it" }
        var batch = second
        while (batch.more) {
            batch = mixes.radio("alice", login, Mixes.SONG, "al1-2", null, null, all, 5)
            assertTrue(batch.ids().none { "lib/$it" in all })
            all = all + batch.ids().map { "lib/$it" }
        }
        assertEquals(all.distinct(), all)
    }

    @Test
    fun onlyWhatWasQueuedAfterTheSeedSteers() {
        val mixes = mixes()
        // Radio from a song further down a playlist: the songs played before it don't steer.
        val first = mixes.radio("alice", login, Mixes.SONG, "al1-2", null, null, listOf("lib/al5-1", "lib/al6-1", "lib/al7-1", "lib/al1-2"), 10)
        assertEquals(listOf("al1-2"), asked("getSimilarSongs2"))
        assertTrue(first.artists().all { it in setOf("The Oscillators", "Neon Cascade", "Signal Garden") }, first.artists().toString())
        // The next batches follow what the radio queued after it.
        synchronized(calls) { calls.clear() }
        val queue = listOf("lib/al5-1", "lib/al1-2", "lib/al2-1", "lib/al2-2", "lib/al2-3", "lib/al8-1", "lib/al8-2")
        mixes.radio("alice", login, Mixes.SONG, "al1-2", null, null, queue, 10)
        assertEquals(setOf("al1-2", "al2-3", "al8-1", "al8-2"), asked("getSimilarSongs2").toSet())
    }

    @Test
    fun slowAgentsStillLeaveTimeForTheRelatedArtistsSongs() {
        agentsMs = 10_000
        val started = System.currentTimeMillis()
        val radio = mixes().radio("alice", login, Mixes.SONG, "al1-2", null, null, listOf("lib/al1-2"), 10)
        // Within the apps' 8 s, with Deezer's related artist although Navidrome's similar songs and artists never came.
        assertTrue(System.currentTimeMillis() - started < 8_000, "${System.currentTimeMillis() - started} ms")
        assertTrue("Signal Garden" in radio.artists(), radio.artists().toString())
    }

    @Test
    fun aDislikeOrAPlayBetweenBatchesIsLeftOut() {
        val mixes = mixes()
        val first = mixes.radio("alice", login, Mixes.SONG, "al1-2", null, null, listOf("lib/al1-2"), 10)
        val disliked = first.songs.first()
        history.dislike("alice", disliked.artist + " feat. Someone", disliked.title + " (Remastered)", null, on = true)
        val played = first.songs.last()
        history.add("alice", listOf(Played(now - 3_600_000, played.artist, played.title, durationMs = 200_000, listenedMs = 3_000)))
        val again = mixes.radio("alice", login, Mixes.SONG, "al1-2", null, null, listOf("lib/al1-2"), 10)
        assertFalse(disliked.id in again.ids())
        assertFalse(played.id in again.ids())
    }

    @Test
    fun aSongFromElsewhereStartsFromTheLibrarysCopy() {
        val radio = mixes().radio("alice", login, Mixes.SONG, null, "The Oscillators - Topic", "Middle C (Official Video)", emptyList(), 10)
        assertEquals("Radio · Middle C", radio.title)
        assertFalse("al1-2" in radio.ids())
        assertTrue("Neon Cascade" in radio.artists())
        // Not in the library at all, and by an artist it hasn't got: nothing like it either.
        val unknown = mixes().radio("alice", login, Mixes.SONG, null, "Nobody", "Nothing", emptyList(), 10)
        assertTrue(unknown.songs.isEmpty())
        assertEquals("Nothing like that in your library", unknown.problem)
        assertEquals("That song isn't in the library", mixes().radio("alice", login, Mixes.SONG, "gone-1", null, null, emptyList(), 10).problem)
    }

    @Test
    fun picksRadioReadsOnlyTheUsersOwnNewestReadyPicks() {
        fun weekly(id: String, owner: String, created: Long, status: String, songs: List<String>) = buildJsonObject {
            put("id", id); put("name", "Weekly picks $id"); put("owner", owner)
            put("comment", "AI picks for the week.\n${LibraryReader.WEEKLY_MARKER}{\"created\":$created,\"by\":\"x\",\"status\":\"$status\"}")
            put("songs", JsonArray(songs.map(::JsonPrimitive)))
        }
        playlists += weekly("p1", "alice", 1_000, "ready", listOf("al5-1", "al5-2"))
        playlists += weekly("p2", "alice", 2_000, "ready", listOf("al6-1", "al6-2", "al6-3"))
        playlists += weekly("p3", "alice", 3_000, "running", listOf("al7-1"))
        playlists += weekly("p4", "bob", 4_000, "ready", listOf("al3-2"))
        playlists += buildJsonObject { put("id", "p5"); put("name", "Mine"); put("owner", "alice"); put("songs", JsonArray(listOf(JsonPrimitive("al2-2")))) }
        starred["al4-1"] = now - 2 * DAY
        val radio = mixes().radio("alice", login, Mixes.PICKS, null, null, null, emptyList(), 10)
        assertEquals("Picks radio", radio.title)
        // The picks lead, the like of this month is in, and the other playlists were never read.
        assertEquals("Brush Trio", radio.songs.first().artist)
        assertTrue("al4-1" in radio.ids())
        assertTrue(radio.ids().none { it == "al7-1" || it == "al3-2" })
        assertEquals(listOf("p2"), synchronized(calls) { calls.filter { it.first == "getPlaylist" }.map { it.second } })
        // Further batches don't lead with them again.
        val next = mixes().radio("alice", login, Mixes.PICKS, null, null, null, radio.ids().map { "lib/$it" }, 10)
        assertTrue(next.ids().none { it in radio.ids() })
    }

    @Test
    fun picksRadioWithoutPicksPlaysWhatTheyLikedAndSaysSoOnlyWhenNothingCameOut() {
        assertEquals("Picks radio starts from your weekly picks and the songs you like. Like a few songs first.",
            mixes().radio("alice", login, Mixes.PICKS, null, null, null, emptyList(), 10).problem)
        starred["al5-1"] = now - 200 * DAY
        val radio = mixes().radio("alice", login, Mixes.PICKS, null, null, null, emptyList(), 10)
        assertNull(radio.problem)
        assertTrue("al5-1" in radio.ids())
    }

    @Test
    fun picksRadioGoesOnWithLikedArtistsThatAreLikeEachOther() {
        // Midnight Quartet and Brush Trio are each other's only similar artists: not just the two likes, then nothing.
        starred["al5-1"] = now - 2 * DAY
        starred["al6-1"] = now - 2 * DAY
        val radio = mixes().radio("alice", login, Mixes.PICKS, null, null, null, emptyList(), 10)
        assertTrue(radio.ids().containsAll(listOf("al5-1", "al6-1")))
        assertEquals(4, radio.songs.size, radio.ids().toString())
        assertEquals(setOf("Midnight Quartet", "Brush Trio"), radio.artists().toSet())
        assertTrue(radio.more)
    }

    @Test
    fun navidromeDownIsAnError() {
        val down = Mixes(LibraryReader("http://127.0.0.1:9"), history, null, null, dir, clock = { now })
        assertTrue(runCatching { down.radio("alice", login, Mixes.SONG, "al1-2", null, null, emptyList(), 10) }.exceptionOrNull() is LibraryException)
    }

    // --- Daily mixes ---

    /** Plays over the last few weeks: two artists of each of three genre families, and one song the day before. */
    private fun listen(user: String = "alice", artists: Int = 6) {
        val songs = listOf("al1-2", "al4-1", "al3-2", "al7-1", "al5-1", "al6-1").take(artists)
        history.add(user, songs.withIndex().flatMap { (i, id) ->
            val s = song(id)
            (1..2).map { n -> Played(now - (10 + 10 * i) * DAY - n * 3_600_000L, s["artist"]!!.jsonPrimitive.content, s["title"]!!.jsonPrimitive.content, durationMs = 200_000, listenedMs = 200_000) }
        } + Played(now - DAY, "The Oscillators", "Low Hum", durationMs = 200_000, listenedMs = 200_000))
    }

    private fun Mixes.ready(zone: ZoneId = VIENNA, user: String = "alice"): Mixes.DailyMixes {
        var daily = daily(user, login, zone)
        val until = System.currentTimeMillis() + 10_000
        while (daily.running && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            daily = daily(user, login, zone)
        }
        return daily
    }

    private fun Mixes.DailyMixes.ids() = mixes.flatMap { m -> m.songs.map { it.id } }

    @Test
    fun dailyMixesAreMadeOnceADayByGenreFamily() {
        listen()
        val mixes = mixes()
        assertTrue(mixes.daily("alice", login, VIENNA).running)
        val daily = mixes.ready()
        assertEquals("2026-10-09", daily.day)
        assertNull(daily.problem)
        assertEquals(listOf("Daily Mix 1", "Daily Mix 2", "Daily Mix 3"), daily.mixes.map { it.title })
        assertEquals("daily-2026-10-09-1", daily.mixes.first().id)
        assertTrue(daily.mixes.first().subtitle!!.endsWith(" and more"))
        // Electronic, ambient and jazz each make one; nothing twice, nothing played in the last three days.
        val families = daily.mixes.map { m -> m.songs.map { Mixes.family(it.genre!!) }.groupingBy { it }.eachCount().maxBy { it.value }.key }
        assertEquals(setOf("electronic", "ambient", "jazz"), families.toSet())
        assertEquals(daily.ids().distinct(), daily.ids())
        assertFalse("al1-1" in daily.ids())
        // Asked again the same day: the same, without asking Navidrome.
        synchronized(calls) { calls.clear() }
        assertEquals(daily.ids(), mixes.daily("alice", login, VIENNA).ids())
        assertTrue(synchronized(calls) { calls.isEmpty() })
        assertTrue(File(dir, "mixes/alice.daily.json").exists())
    }

    @Test
    fun aDayStartsAtFourInTheirZone() {
        listen()
        val mixes = mixes()
        val yesterday = mixes.ready()
        // 03:59 the next morning is still that day; 04:01 is the next one, made without yesterday's songs.
        now = ZonedDateTime.of(2026, 10, 10, 3, 59, 0, 0, VIENNA).toInstant().toEpochMilli()
        assertEquals(yesterday.ids(), mixes.daily("alice", login, VIENNA).ids())
        now = ZonedDateTime.of(2026, 10, 10, 4, 1, 0, 0, VIENNA).toInstant().toEpochMilli()
        assertTrue(mixes.daily("alice", login, VIENNA).running)
        val today = mixes.ready()
        assertEquals("2026-10-10", today.day)
        assertTrue(today.ids().isNotEmpty())
        assertTrue(today.ids().none { it in yesterday.ids() }, "${yesterday.ids()} then ${today.ids()}")
        // In UTC it's 02:01, so still the 9th there.
        assertEquals("2026-10-09", Mixes.dayOf(now, ZoneId.of("UTC")))
        assertEquals("2026-10-09", mixes.ready(ZoneId.of("UTC")).day)
    }

    @Test
    fun aDislikeAfterTheyWereMadeIsLeftOutAndARestartReadsThemBack() {
        listen()
        val daily = mixes().ready()
        val gone = daily.mixes.first().songs.first()
        history.dislike("alice", gone.artist, gone.title, null, on = true)
        synchronized(calls) { calls.clear() }
        val again = mixes().daily("alice", login, VIENNA)
        assertFalse(again.running)
        assertEquals(daily.ids() - gone.id, again.ids())
        assertTrue(synchronized(calls) { calls.isEmpty() })
    }

    @Test
    fun dailyMixesThatCantBeSavedStillShowWithWhy() {
        listen()
        val other = Files.createTempDirectory("tonearm-mixes").toFile()
        File(other, "mixes").writeText("not a folder")
        val daily = mixes(other).ready()
        assertEquals(3, daily.mixes.size)
        assertTrue(daily.problem!!.startsWith("Can't save in"), daily.problem)
    }

    @Test
    fun tooLittleHistoryMakesNoDailyMixes() {
        listen(artists = 2)
        val daily = mixes().ready()
        assertTrue(daily.mixes.isEmpty())
        assertEquals("Play some music and your daily mixes show up tomorrow", daily.problem)
    }

    @Test
    fun genresFallIntoFamilies() {
        assertEquals("electronic", Mixes.family("Synthpop"))
        assertEquals("electronic", Mixes.family("Trip-Hop"))
        assertEquals("hip hop", Mixes.family("Hip-Hop"))
        assertEquals("jazz", Mixes.family("Acid Jazz"))
        assertEquals("metal", Mixes.family("Death Metal"))
        assertEquals("punk", Mixes.family("Punk Rock"))
        assertEquals("soul/r&b", Mixes.family("Contemporary R&B"))
        assertEquals("folk/country", Mixes.family("Singer-Songwriter"))
        assertEquals("indie", Mixes.family("Indie"))
    }

    // --- Mixes from a sentence ---

    private fun Mixes.made(prompt: String, refresh: Boolean = false): Mixes.Mix {
        var mix = mix("alice", login, prompt, refresh)
        val until = System.currentTimeMillis() + 10_000
        while (mix.running && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            mix = mix("alice", login, prompt, false)
        }
        return mix
    }

    @Test
    fun theAisSongsComeFirstAndTheLibraryFillsTheRest() {
        val mixes = mixes()
        val started = mixes.mix("alice", login, "low ambient tones", refresh = false)
        assertTrue(started.running)
        assertEquals("", started.title)
        val mix = mixes.made("low ambient tones")
        assertEquals("Low Tones", mix.title)
        assertEquals("Ollama fake", mix.ai)
        assertEquals("Low Hum", mix.songs.first().title)
        assertTrue(mix.songs.none { it.title == "Made Up" })
        // The artist it chose, and its genre from another artist.
        assertTrue("Signal Garden" in mix.songs.map { it.artist })
        assertTrue("Kosmische Drift" in mix.songs.map { it.artist })
        assertTrue(mix.id.startsWith("mix-"))
        assertTrue("Make a playlist" in File(dir, "mixes/alice.mix-prompt.txt").readText())
        // Kept: asked again, the AI isn't.
        val calls = aiCalls
        assertEquals(mix.songs, mixes.mix("alice", login, "Low ambient tones!", refresh = false).songs)
        assertEquals(calls, aiCalls)
        // Asking again does.
        assertTrue(mixes.mix("alice", login, "low ambient tones", refresh = true).running)
        mixes.made("low ambient tones")
        assertEquals(calls + 1, aiCalls)
    }

    @Test
    fun anAiThatFailsGivesAProblemAndNoSongs() {
        aiFails = true
        val mix = mixes().made("low ambient tones")
        assertTrue(mix.songs.isEmpty())
        assertTrue(mix.problem!!.startsWith("The AI couldn't answer"), mix.problem)
        assertFalse(mix.running)
    }

    @Test
    fun oneMixAtATime() {
        val gate = CountDownLatch(1)
        aiGate = gate
        val mixes = mixes()
        assertTrue(mixes.mix("alice", login, "low ambient tones", refresh = false).running)
        val other = mixes.mix("alice", login, "mellow jazz", refresh = false)
        assertFalse(other.running)
        assertEquals("Still making “low ambient tones”; one mix at a time", other.problem)
        gate.countDown()
        assertEquals("Low Tones", mixes.made("low ambient tones").title)
        assertTrue(mixes.mix("alice", login, "mellow jazz", refresh = false).running)
    }

    companion object {
        private val VIENNA = ZoneId.of("Europe/Vienna")
        private const val DAY = 24 * 3_600_000L
    }
}

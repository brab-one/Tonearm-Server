package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryTest {
    private val dir = Files.createTempDirectory("tonearm-history").toFile()
    private val day = 24 * 3_600_000L
    private val now = System.currentTimeMillis()

    private fun play(daysAgo: Int, artist: String, title: String, listenedS: Long = 200, durationS: Long = 200, source: String = "library") =
        Played(now - daysAgo * day - title.hashCode().mod(1000), artist, title, durationMs = durationS * 1000, listenedMs = listenedS * 1000, source = source)

    @Test
    fun playsCountLikeScrobblesAndShortOnesAreSkips() {
        assertTrue(play(0, "a", "b", listenedS = 100, durationS = 200).counts)
        assertTrue(play(0, "a", "b", listenedS = 240, durationS = 900).counts)
        assertFalse(play(0, "a", "b", listenedS = 90, durationS = 200).counts)
        assertFalse(play(0, "a", "b", listenedS = 90, durationS = 200).skipped)
        assertTrue(play(0, "a", "b", listenedS = 5, durationS = 200).skipped)
        assertEquals("Portishead", Played.mainArtist("Portishead feat. Someone"))
        assertEquals("Simon & Garfunkel", Played.mainArtist("Simon & Garfunkel"))
    }

    @Test
    fun listeningIsPerTimeSpanAndSurvivesARestart() {
        History(dir).add("alice", listOf(
            play(2, "Sia", "Rewrite", source = "youtube"), play(3, "Sia", "Sunday"), play(1, "Sia", "Rewrite"),
            play(40, "Portishead", "Roads"), play(1, "Kongos", "Escape", listenedS = 3),
        ))
        val history = History(dir)
        val month = history.listening("alice", now - 30 * day, artists = 10, songs = 10, recent = 10)
        assertEquals(3, month.plays)
        assertEquals(listOf("Sia"), month.artists.map { it.artist })
        assertEquals("Rewrite" to 2, month.songs.first().let { it.title to it.plays })
        assertEquals("Rewrite", month.recent.first().title)
        assertEquals(listOf("Sia", "Portishead"), history.listening("alice", 0, 10, 0, 0).artists.map { it.artist })
        assertTrue(history.listening("bob", 0, 10, 10, 10).artists.isEmpty())
    }

    @Test
    fun aWeekIsFromUpToTo() {
        val start = 1_760_000_000_000L
        val hour = 3_600_000L
        fun at(h: Long, title: String, album: String? = "Hi-Res Tones", listenedS: Long = 120, source: String = "library") =
            Played(start + h * hour, "The Oscillators", title, album, durationMs = 120_000, listenedMs = listenedS * 1000, source = source)
        History(dir).add("alice", listOf(
            at(-1, "Before"), at(0, "Low Hum"), at(1, "Middle C", "Hi-Res Tones (Deluxe Edition)"), at(2, "Skipped", listenedS = 3),
            at(3, "No Album", null), at(4, "Live", "Live at Home", source = "youtube"), at(167, "Last"), at(168, "Next Week"),
        ))
        val history = History(dir)
        val week = history.listening("alice", start, artists = 10, songs = 0, recent = 100, to = start + 168 * hour, albums = 10)
        assertEquals(start + 168 * hour, week.to)
        assertEquals(listOf("Last", "Live", "No Album", "Middle C", "Low Hum"), week.recent.map { it.title })
        assertEquals(5, week.plays)
        assertEquals(10, week.minutes)
        // The deluxe edition is the same album; plays without one aren't in the albums.
        assertEquals(listOf("Hi-Res Tones" to 3, "Live at Home" to 1), week.albums.map { it.album to it.plays })
        assertEquals("Hi-Res Tones (Deluxe Edition)", week.recent.first { it.title == "Middle C" }.album)
        assertEquals("youtube", week.recent.first { it.title == "Live" }.source)
        assertEquals(null, week.recent.first { it.title == "No Album" }.album)
        // Paging: what came before the oldest one shown, without overlap.
        val first = history.listening("alice", start, 0, 0, recent = 2, to = start + 168 * hour)
        val next = history.listening("alice", start, 0, 0, recent = 2, to = start + 168 * hour, before = first.recent.last().at)
        assertEquals(listOf("Last", "Live"), first.recent.map { it.title })
        assertEquals(listOf("No Album", "Middle C"), next.recent.map { it.title })
        assertEquals(5, next.plays)
        assertTrue(history.listening("alice", start, 10, 0, 0).albums.isEmpty())
    }

    @Test
    fun sinceCopiesOnlyTheTail() {
        val history = History(dir)
        history.add("alice", (1..50).map { Played(1_000L * it, "Sia", "Song $it", durationMs = 200_000, listenedMs = 200_000) })
        assertEquals((41..50).map { "Song $it" }, history.since("alice", 41_000).map { it.title })
        assertEquals((41..50).map { "Song $it" }, history.since("alice", 40_500).map { it.title })
        assertEquals(50, history.since("alice", 0).size)
        assertTrue(history.since("alice", 60_000).isEmpty())
        assertTrue(history.since("bob", 0).isEmpty())
    }

    @Test
    fun dismissedAlbumsAndArtists() {
        val history = History(dir)
        history.dismiss("alice", "Tricky", "Maxinquaye")
        history.dismiss("alice", "Mazzy Star", null)
        val again = History(dir)
        assertTrue(again.isDismissed("alice", "TRICKY", "Maxinquaye [Remastered]"))
        assertFalse(again.isDismissed("alice", "Tricky", null))
        assertTrue(again.isDismissed("alice", "Mazzy Star", "Anything"))
        assertEquals(2, again.dismissed("alice").size)
    }

    @Test
    fun playsThatCantBeSavedArentKeptSoTheAppsNextTryAddsThem() {
        val history = History(dir)
        history.add("alice", listOf(play(1, "Sia", "Rewrite")))
        val file = File(dir, "history/alice.jsonl")
        val sunday = play(0, "Sia", "Sunday")
        file.setWritable(false)
        try {
            assertFailsWith<IOException> { history.add("alice", listOf(sunday)) }
            assertEquals(1, history.played("alice").size)
        } finally {
            file.setWritable(true)
        }
        assertEquals(1, history.add("alice", listOf(sunday)))
        assertEquals(listOf("Rewrite", "Sunday"), History(dir).played("alice").map { it.title })
    }

    @Test
    fun aNoThatCantBeSavedIsntKeptEither() {
        val history = History(dir)
        val folder = File(dir, "history").apply { setWritable(false) }
        try {
            assertFailsWith<IOException> { history.dismiss("alice", "Tricky", null) }
            assertFailsWith<IOException> { history.dislike("alice", "Tricky", "Overcome", null, on = true) }
        } finally {
            folder.setWritable(true)
        }
        assertFalse(history.isDismissed("alice", "Tricky"))
        assertTrue(history.dislikedSongs("alice").isEmpty())
    }

    @Test
    fun malojasHistoryComesOverAPageAtATime() {
        val scrobbles = (0 until 4_500).map { """{"time":${1_700_000_000 + it * 300},"track":{"artists":["Artist ${it % 40}"],"title":"Song $it","length":200},"duration":200}""" }
        var pagesKnown = true
        val pages = mutableListOf<Int>()
        val maloja = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/apis/mlj_1/scrobbles") { ex ->
                val q = ex.requestURI.query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                val perPage = q.getValue("perpage").toInt()
                val page = if (pagesKnown) q.getValue("page").toInt() else 0
                synchronized(pages) { pages += q.getValue("page").toInt() }
                val next = if ((page + 1) * perPage < scrobbles.size) "\"?page=${page + 1}\"" else "null"
                val pagination = if (pagesKnown) ""","pagination":{"page":$page,"next_page":$next}""" else ""
                val bytes = """{"status":"ok","list":[${scrobbles.drop(page * perPage).take(perPage).joinToString(",")}]$pagination}""".encodeToByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${maloja.address.port}"
            assertEquals(4_500, History(dir).importMaloja("alice", url, null, HttpClient.newHttpClient()))
            assertEquals(listOf(0, 1, 2), pages)
            assertEquals(4_500, History(dir).played("alice").size)
            // A Maloja without pages answers the same each time: that's all there is from it, not the same plays again and again.
            pagesKnown = false
            pages.clear()
            assertEquals(2_000, History(Files.createTempDirectory("tonearm-history").toFile()).importMaloja("alice", url, null, HttpClient.newHttpClient()))
            assertEquals(listOf(0, 1), pages)
        } finally {
            maloja.stop(0)
        }
    }
}

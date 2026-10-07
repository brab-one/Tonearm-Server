package io.github.deadeyebarb.tonearm.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
}

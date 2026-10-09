package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongPickerTest {
    private fun song(id: String, artist: String, title: String = "Song $id", album: String? = "Album of $artist") = LibrarySong(buildJsonObject {
        put("id", id)
        put("artist", artist)
        put("title", title)
        album?.let { put("album", it) }
    })

    /** [n] songs by each of [artists], ids "<artist initial><n>". */
    private fun songs(n: Int, vararg artists: String) = artists.flatMap { a -> (1..n).map { song("${a.first()}$it", a) } }

    private fun pick(parts: List<Part>, count: Int = 10, rules: Rules = Rules(), seed: Int = 1, perArtist: Int = 2) =
        SongPicker.pick(parts, count, rules, Random(seed), perArtist = perArtist)

    @Test
    fun dislikedSongsAreLeftOutInEveryCopy() {
        val candidates = listOf(
            song("1", "Kongos", "Come with Me Now (Remastered)"),
            song("2", "Kongos feat. Someone", "Come with Me Now"),
            song("3", "Kongos", "I'm Only Joking"),
        )
        val picked = pick(listOf(Part(1.0, candidates)), rules = Rules(avoidKeys = setOf(SongKey.of("Kongos", "Come With Me Now"))))
        assertEquals(listOf("3"), picked.songs.map { it.id })
    }

    @Test
    fun saidNoToArtistsAlbumsAndQueuedSongsStayOut() {
        val candidates = listOf(
            song("1", "Mazzy Star"), song("2", "Tricky", album = "Maxinquaye (Deluxe Edition)"), song("3", "Tricky", album = "Pre-Millennium Tension"),
            song("4", "Portishead"), song("5", "Portishead feat. Someone", title = "Other"),
        )
        val rules = Rules(
            excludeIds = setOf("4"), dismissedArtists = setOf(Recommendations.loose("Mazzy Star")),
            dismissedAlbums = setOf(Recommendations.loose("Tricky") + "|" + Recommendations.looseTitle("Maxinquaye")),
        )
        assertEquals(setOf("3", "5"), pick(listOf(Part(1.0, candidates)), rules = rules).songs.map { it.id }.toSet())
    }

    @Test
    fun atMostTwoPerArtistAndSpacedOutWhenThereIsAChoice() {
        val picked = pick(listOf(Part(1.0, songs(5, "Air", "Bjork", "Cocteau Twins", "Doves", "Elbow"))), count = 10)
        assertEquals(10, picked.songs.size)
        assertTrue(picked.songs.groupingBy { it.artistKey }.eachCount().values.all { it <= 2 })
        picked.songs.windowed(4).forEach { window -> assertEquals(4, window.map { it.artistKey }.distinct().size, window.toString()) }
        assertTrue(picked.more)
        // Without a choice, the order stays.
        val two = pick(listOf(Part(1.0, songs(2, "Air", "Bjork"))), count = 4)
        assertEquals(4, two.songs.size)
        assertFalse(two.more)
    }

    @Test
    fun aPartThatRunsShortHandsItsPlacesOver() {
        val empty = Part(0.5, emptyList())
        val full = Part(0.5, songs(3, "Air", "Bjork", "Cocteau Twins", "Doves"))
        assertEquals(8, pick(listOf(empty, full), count = 8).songs.size)
        assertEquals(listOf(3, 2), SongPicker.quotas(listOf(0.6, 0.4), 5))
        assertEquals(listOf(10, 9, 4, 2), SongPicker.quotas(listOf(0.4, 0.35, 0.15, 0.1), 25))
    }

    @Test
    fun theSameSeedGivesTheSameList() {
        val parts = listOf(Part(0.6, songs(4, "Air", "Bjork", "Cocteau Twins")), Part(0.4, songs(4, "Doves", "Elbow", "Feist")))
        assertEquals(pick(parts, seed = 7).songs, pick(parts, seed = 7).songs)
        assertTrue((1..10).map { pick(parts, seed = it).songs.map { s -> s.id } }.distinct().size > 1)
    }

    @Test
    fun leadSongsComeFirstAsTheyCome() {
        val found = listOf(song("x1", "Air", "Talisman"), song("x2", "Doves", "Pounding"))
        val picked = pick(listOf(Part(0.5, found, lead = found.size, inOrder = true), Part(0.5, songs(3, "Bjork", "Cocteau Twins"))), count = 6)
        assertEquals(listOf("x1", "x2"), picked.songs.take(2).map { it.id })
        // A song in an earlier part isn't taken again from a later one.
        val twice = pick(listOf(Part(0.5, listOf(song("a", "Air"))), Part(0.5, listOf(song("a", "Air"), song("b", "Bjork")))), count = 3)
        assertEquals(listOf("a", "b"), twice.songs.map { it.id }.sorted())
    }

    @Test
    fun artistsWithDislikedSongsComeLessOften() {
        val parts = listOf(Part(1.0, songs(1, "Air", "Bjork")))
        val rules = Rules(dislikedArtistCount = mapOf(Recommendations.loose("Air") to 8))
        val firsts = (1..200).map { pick(parts, count = 1, rules = rules, seed = it).songs.single().artist }
        assertTrue(firsts.count { it == "Air" } < 60, firsts.groupingBy { it }.eachCount().toString())
        assertTrue("Air" in firsts)
    }
}

package io.github.deadeyebarb.tonearm.server

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestsTest {
    @Test
    fun requestsAreKeptAYearAndNoMoreThanSoMany() {
        val dir = Files.createTempDirectory("tonearm-requests").toFile()
        val now = System.currentTimeMillis()
        val store = Requests(dir)
        store.add("bob", listOf(Requested(now - Requests.MAX_AGE_MS - 1, albumId = 1), Requested(now, foreignAlbumId = "mb-2")), now)
        assertEquals(listOf("mb-2"), store.of("bob", now).map { it.foreignAlbumId })
        // Asked again with Lidarr's id: one request, newer, with both ids.
        store.add("bob", listOf(Requested(now + 1, albumId = 2, foreignAlbumId = "mb-2")), now + 1)
        assertEquals(listOf(Requested(now + 1, albumId = 2, foreignAlbumId = "mb-2")), store.of("bob", now + 1))
        // An artist and an album with the same id aren't the same.
        store.add("bob", listOf(Requested(now + 2, artistId = 2)), now + 2)
        assertEquals(2, store.of("bob", now + 2).size)
        assertEquals(store.of("bob", now + 2), Requests(dir).of("bob", now + 2))
        store.add("carol", (1..Requests.MAX_REQUESTS + 10).map { Requested(now + it, albumId = it) }, now)
        val kept = Requests(dir).of("carol", now)
        assertEquals(Requests.MAX_REQUESTS, kept.size)
        assertEquals(11, kept.first().albumId)
        // A year later, they're gone.
        assertEquals(listOf(), store.of("bob", now + Requests.MAX_AGE_MS + 10))
    }

    @Test
    fun aFileThatCantBeReadIsPutAsideNotWrittenOver() {
        val dir = Files.createTempDirectory("tonearm-requests").toFile()
        File(dir, "requests").mkdirs()
        File(dir, "requests/bob.json").writeText("[{\"at\":")
        val store = Requests(dir)
        assertEquals(listOf(), store.of("bob"))
        assertTrue(File(dir, "requests/bob.json.broken").exists())
        store.add("carol", listOf(Requested(System.currentTimeMillis(), albumId = 7)))
        assertEquals(setOf("carol"), Requests(dir).everyone().keys)
    }
}

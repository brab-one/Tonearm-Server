package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Suggested albums looked up in a fake Lidarr's metadata search. */
class LidarrCheckTest {
    private lateinit var fake: HttpServer
    private lateinit var lidarr: LidarrProxy

    @BeforeTest
    fun start() {
        fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/album/lookup") { ex ->
                // Lidarr's secondary types, as names here and as objects with a name there.
                val body = """[
                    {"title":"Bookends","albumType":"Single","secondaryTypes":[],"releaseDate":"1968-04-03T00:00:00Z","artist":{"artistName":"Simon & Garfunkel"}},
                    {"title":"Bookends","albumType":"Album","secondaryTypes":[],"releaseDate":"1968-04-03T00:00:00Z","artist":{"artistName":"Simon & Garfunkel"}},
                    {"title":"Live 1969","albumType":"Album","secondaryTypes":["Live"],"releaseDate":"2008-01-01T00:00:00Z","artist":{"artistName":"Simon & Garfunkel"}},
                    {"title":"The Concert in Central Park","albumType":"Album","secondaryTypes":[{"id":6,"name":"Live"}],"artist":{"artistName":"Simon & Garfunkel"}},
                    {"title":"Abbey Road (Remastered)","albumType":"Album","releaseDate":"1969-09-26T00:00:00Z","artist":{"artistName":"The Beatles"}},
                    {"title":"Hurry Up, We're Dreaming (Remixes)","albumType":"Album","secondaryTypes":["Remix"],"releaseDate":"2012-01-01T00:00:00Z","artist":{"artistName":"M83"}},
                    {"title":"Hurry Up, We're Dreaming","albumType":"Album","secondaryTypes":["Studio"],"releaseDate":"2011-10-18T00:00:00Z","artist":{"artistName":"M83"}},
                    {"title":"Lungs","albumType":"Album","releaseDate":"2009-07-03T00:00:00Z","artist":{"artistName":"Florence + the Machine"}}
                ]""".encodeToByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        lidarr = LidarrProxy(Upstream("Lidarr", "http://127.0.0.1:${fake.address.port}", "X-Api-Key", "k"), requestsForEveryone = true)
    }

    @AfterTest
    fun stop() = fake.stop(0)

    @Test
    fun theAlbumWinsOverItsSingleAndAndIsAmpersand() {
        val match = lidarr.findAlbum(AiPick("Simon and Garfunkel", "Bookends", why = "Fits.")).first()
        assertEquals(AiPick("Simon & Garfunkel", "Bookends", 1968, "Fits."), match.pick)
        assertEquals(setOf("Album"), match.kinds)
    }

    @Test
    fun liveAlbumsSayTheyreLive() {
        assertEquals(setOf("Album", "Live"), lidarr.findAlbum(AiPick("Simon & Garfunkel", "Live 1969")).first().kinds)
        assertEquals(setOf("Album", "Live"), lidarr.findAlbum(AiPick("Simon & Garfunkel", "The Concert in Central Park")).first().kinds)
    }

    @Test
    fun theBeatlesAreBeatlesAndMadeUpAlbumsArentFound() {
        assertEquals("Abbey Road (Remastered)", lidarr.findAlbum(AiPick("Beatles", "Abbey Road")).first().pick.album)
        assertEquals(emptyList(), lidarr.findAlbum(AiPick("Simon & Garfunkel", "Bridge Over Troubled Pickles")))
        assertEquals("Florence + the Machine", lidarr.findAlbum(AiPick("Florence & the Machine", "Lungs")).first().pick.artist)
    }

    @Test
    fun theOriginalComesBeforeItsRemixes() {
        assertEquals(
            listOf("Hurry Up, We're Dreaming", "Hurry Up, We're Dreaming (Remixes)"),
            lidarr.findAlbum(AiPick("M83", "Hurry Up, We're Dreaming")).map { it.pick.album },
        )
    }
}

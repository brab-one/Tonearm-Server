package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Against a fake Navidrome (one user's listening) and a fake Deezer. */
class DiscoveryTest {
    private lateinit var fake: HttpServer
    private lateinit var discovery: Discovery
    private var deezerCalls = 0
    private val login = mapOf("u" to "alice", "p" to "wonderland")

    private fun answer(ex: HttpExchange, body: String) {
        val bytes = body.encodeToByteArray()
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun artist(id: Int, name: String) = """{"id":$id,"name":"$name","picture_medium":"https://img/$id.jpg","nb_fan":${id * 100}}"""

    @BeforeTest
    fun start() {
        fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/rest/") { ex ->
                val query = ex.requestURI.query
                val body = when (ex.requestURI.path.substringAfterLast('/')) {
                    "getAlbumList2.view" -> if ("frequent" in query) """"albumList2":{"album":[{"artist":"Portishead"},{"artist":"Portishead"},{"artist":"Radiohead"}]}"""
                    else """"albumList2":{"album":[{"artist":"Massive Attack"}]}"""
                    "getStarred2.view" -> """"starred2":{"artist":[{"name":"Radiohead"}]}"""
                    "getArtists.view" -> """"artists":{"index":[{"artist":[{"name":"Portishead"},{"name":"Radiohead"},{"name":"Massive Attack"}]}]}"""
                    else -> """"x":1"""
                }
                answer(ex, """{"subsonic-response":{"status":"ok",$body}}""")
            }
            createContext("/deezer/") { ex ->
                deezerCalls++
                val path = ex.requestURI.path.removePrefix("/deezer")
                val q = URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), Charsets.UTF_8).lowercase()
                val body = when {
                    path == "/search/artist" && "q=portishead" in q -> """{"data":[${artist(1, "Portishead")}]}"""
                    path == "/search/artist" && "q=radiohead" in q -> """{"data":[${artist(2, "Radiohead")}]}"""
                    path == "/search/artist" && "q=massive attack" in q -> """{"data":[${artist(3, "Massive Attack")}]}"""
                    path == "/artist/1/related" -> """{"data":[${artist(3, "Massive Attack")},${artist(10, "Tricky")},${artist(11, "Björk")},${artist(12, "Air")}]}"""
                    path == "/artist/2/related" -> """{"data":[${artist(11, "Björk")},${artist(13, "Muse")},${artist(1, "Portishead")}]}"""
                    path == "/artist/3/related" -> """{"data":[${artist(10, "Tricky")},${artist(1, "Portishead")}]}"""
                    path == "/search" -> """{"data":[{"title":"Glory Box","duration":306,"artist":{"name":"Portishead"},"album":{"title":"Dummy","cover_medium":"https://img/dummy.jpg"}}]}"""
                    path == "/search/album" -> """{"data":[{"title":"Dummy","record_type":"album","cover_medium":"https://img/dummy.jpg","artist":{"name":"Portishead"}}]}"""
                    path == "/artist/11/albums" -> """{"data":[
                        {"title":"Homogenic","record_type":"album","fans":500,"release_date":"1997-09-22","cover_medium":"https://img/homogenic.jpg"},
                        {"title":"Live at Royal Opera House","record_type":"album","fans":900,"release_date":"2002-01-01"},
                        {"title":"Army of Me","record_type":"single","fans":2000,"release_date":"1995-04-21"}]}"""
                    path.endsWith("/albums") -> """{"data":[{"title":"Debut","record_type":"album","fans":10,"release_date":"2000-01-01"}]}"""
                    else -> """{"data":[]}"""
                }
                answer(ex, body)
            }
            start()
        }
        val url = "http://127.0.0.1:${fake.address.port}"
        discovery = Discovery(url, Files.createTempDirectory("tonearm-discovery").toFile(), deezer = "$url/deezer")
    }

    @AfterTest
    fun stop() = fake.stop(0)

    @Test
    fun picksAreArtistsSeveralOfYoursPointToWithoutTheLibrary() {
        val picks = discovery.picks("alice", login, refresh = false).picks
        assertEquals(listOf("Björk", "Muse", "Tricky", "Air"), picks.map { it.artist })
        val bjork = picks.first()
        assertEquals(listOf("Radiohead", "Portishead"), bjork.because)
        // Her studio album with the most fans: not the live one, not the single.
        assertEquals("Homogenic", bjork.album)
        assertEquals(1997, bjork.year)
        assertEquals("https://img/homogenic.jpg", bjork.coverUrl)
        assertEquals("https://img/11.jpg", bjork.imageUrl)
    }

    @Test
    fun picksAreKeptForADay() {
        discovery.picks("alice", login, refresh = false)
        val calls = deezerCalls
        discovery.picks("alice", login, refresh = false)
        assertEquals(calls, deezerCalls)
        assertTrue(discovery.picks("alice", login, refresh = true).picks.isNotEmpty())
    }

    @Test
    fun searchFindsSongsAlbumsAndArtists() {
        val found = discovery.search("portishead")
        assertEquals(WebSong("Glory Box", "Portishead", "Dummy", 306, "https://img/dummy.jpg"), found.songs.single())
        assertEquals(WebAlbum("Dummy", "Portishead", "https://img/dummy.jpg", "album"), found.albums.single())
        assertEquals("Portishead", found.artists.single().artist)
    }

    @Test
    fun similarArtistsSayWhatsInTheLibrary() {
        val (name, similar) = discovery.similar("alice", login, "portishead")
        assertEquals("Portishead", name)
        assertEquals(listOf("Massive Attack" to true, "Tricky" to false, "Björk" to false, "Air" to false), similar.map { it.artist to it.inLibrary })
    }
}

package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wanted albums from YouTube when nothing else came in time, against a fake Lidarr with a torrent indexer and Tubifarry's
 * YouTube one (id 7). Each album's releases are a YouTube one Lidarr would take unless a test says otherwise. Time is the
 * test's.
 */
class YouTubeFallbackTest {
    private lateinit var lidarr: HttpServer
    private lateinit var dataDir: File
    private lateinit var requests: Requests
    private lateinit var upstream: Upstream
    private var now = System.currentTimeMillis()
    /** What reached the fake Lidarr (method, path and query), and the bodies of its grabs. */
    private val received = mutableListOf<String>()
    private val grabBodies = mutableListOf<String>()

    /** Low and Slowdive are in the shared library, Mazzy Star in bob's picks, Cocteau Twins in carol's. */
    private val artists = mapOf(1 to ("Low" to "/music/Low"), 2 to ("Slowdive" to "/music/Slowdive"), 3 to ("Mazzy Star" to "/picks/bob/Mazzy Star"), 4 to ("Cocteau Twins" to "/picks/carol/Cocteau Twins"))
    private val artistOf = mapOf(10 to 1, 11 to 1, 12 to 1, 13 to 1, 20 to 2, 30 to 3, 40 to 4)
    private val releaseDates = mutableMapOf<Int, Long>()
    private val lastSearch = mutableMapOf<Int, Long>()
    private val wanted = mutableListOf(10, 20)
    private val queue = mutableListOf<String>()
    private val releases = mutableMapOf<Int, List<String>>()
    /** Releases whose download link is dead: Lidarr can't grab them. */
    private val dead = mutableSetOf<String>()
    private val torrents = """{"id":3,"name":"Knaben","implementation":"Torznab","enableInteractiveSearch":true}"""
    private val youtubeIndexer = """{"id":7,"name":"YouTube","implementation":"YouTubeIndexer","enableAutomaticSearch":false,"enableInteractiveSearch":true}"""
    @Volatile private var indexers = "[$torrents,$youtubeIndexer]"

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun albumJson(id: Int, withArtist: Boolean): String {
        val artistId = artistOf.getValue(id)
        val (name, path) = artists.getValue(artistId)
        return """{"id":$id,"title":"Album $id","foreignAlbumId":"mb-$id","artistId":$artistId,"monitored":true,"releaseDate":"${iso(releaseDates[id] ?: 1_577_836_800_000L)}"""" +
            (lastSearch[id]?.let { ""","lastSearchTime":"${iso(it)}"""" } ?: "") +
            (if (withArtist) ""","artist":{"id":$artistId,"artistName":"$name","foreignArtistId":"mb-artist-$artistId","path":"$path"}""" else "") + "}"
    }

    private fun artistJson(id: Int) = artists.getValue(id).let { (name, path) -> """{"id":$id,"artistName":"$name","foreignArtistId":"mb-artist-$id","path":"$path","rootFolderPath":"${path.substringBeforeLast('/')}"}""" }

    /** A release as Lidarr's search lists it, with the indexer's download link (and its key) in it. */
    private fun release(guid: String, indexerId: Int, weight: Int, approved: Boolean = true, title: String = guid): String {
        val youtube = indexerId == 7
        return """{"guid":"$guid","indexerId":$indexerId,"indexer":"${if (youtube) "YouTube" else "Knaben"}","title":"$title","approved":$approved,""" +
            """"releaseWeight":$weight,"protocol":"${if (youtube) "YoutubeDownloadProtocol" else "TorrentDownloadProtocol"}",""" +
            """"downloadUrl":"http://prowlarr:9696/download?apikey=prowlarr-secret","rejections":${if (approved) "[]" else """["Album match is not close enough"]"""}}"""
    }

    private fun queued(albumId: Int, client: String, status: String = "downloading", protocol: String = if (client == "YouTube") "YoutubeDownloadProtocol" else "TorrentDownloadProtocol") =
        """{"id":${queue.size + 1},"title":"Something","albumId":$albumId,"downloadClient":"$client","protocol":"$protocol","status":"$status"}"""

    @BeforeTest
    fun start() {
        lidarr = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                val path = ex.requestURI.path.removePrefix("/api/v1/")
                val query = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
                    .map { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
                fun param(name: String) = query.firstOrNull { it.first == name }?.second
                synchronized(received) { received += "${ex.requestMethod} $path" + (ex.requestURI.rawQuery?.let { "?$it" } ?: "") }
                fun page(records: List<String>): String {
                    val page = param("page")?.toInt() ?: 1
                    val size = param("pageSize")?.toInt() ?: 10
                    val part = records.drop((page - 1) * size).take(size)
                    return """{"page":$page,"pageSize":$size,"sortKey":"releaseDate","sortDirection":"descending","totalRecords":${records.size},"records":[${part.joinToString(",")}]}"""
                }
                val (code, answer) = when {
                    ex.requestMethod == "POST" && path == "release" -> {
                        synchronized(grabBodies) { grabBodies += body }
                        val guid = Json.parseToJsonElement(body).jsonObject["guid"]!!.jsonPrimitive.content
                        if (guid in dead) 500 to """{"message":"Couldn't reach http://prowlarr:9696/download?apikey=prowlarr-secret"}""" else 200 to body
                    }
                    ex.requestMethod != "GET" -> 404 to """{"message":"NotFound"}"""
                    path == "indexer" -> 200 to indexers
                    path == "rootfolder" -> 200 to """[{"path":"/music"},{"path":"/picks/bob"},{"path":"/picks/carol"}]"""
                    path == "wanted/missing" || path == "wanted/cutoff" -> 200 to page(wanted.toList().map { albumJson(it, param("includeArtist") == "true") })
                    path == "queue" -> 200 to page(queue.toList())
                    path == "release" -> {
                        val id = param("albumId")!!.toInt()
                        200 to (releases[id] ?: listOf(release("yt-$id", 7, 20))).joinToString(",", "[", "]")
                    }
                    path == "artist" -> 200 to artists.keys.joinToString(",", "[", "]") { artistJson(it) }
                    path.startsWith("artist/") -> path.substringAfter('/').toIntOrNull()?.takeIf { it in artists }?.let { 200 to artistJson(it) } ?: (404 to """{"message":"NotFound"}""")
                    path.startsWith("album/") -> path.substringAfter('/').toIntOrNull()?.takeIf { it in artistOf }?.let { 200 to albumJson(it, withArtist = true) } ?: (404 to """{"message":"NotFound"}""")
                    else -> 404 to """{"message":"NotFound"}"""
                }
                val bytes = answer.encodeToByteArray()
                ex.responseHeaders.set("Content-Type", "application/json")
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            executor = Executors.newCachedThreadPool()
            start()
        }
        dataDir = Files.createTempDirectory("tonearm-youtube").toFile()
        requests = Requests(dataDir)
        upstream = Upstream("Lidarr", "http://127.0.0.1:${lidarr.address.port}", "X-Api-Key", "k")
    }

    @AfterTest
    fun stop() {
        lidarr.stop(0)
    }

    private fun fallback(minutes: Int = 20, maxActive: Int = 2) = YouTubeFallback(upstream, requests, dataDir, minutes, maxActive, clock = { now })

    private fun later(minutes: Long) {
        now += minutes * 60_000L
    }

    private fun grabs() = synchronized(grabBodies) { grabBodies.map { Json.parseToJsonElement(it).jsonObject["guid"]!!.jsonPrimitive.content } }

    private fun searches() = synchronized(received) { received.filter { it.startsWith("GET release") } }

    @Test
    fun anAlbumIsTakenOnceItHasBeenWantedLongEnoughNewestFirst() {
        val youtube = fallback()
        lastSearch[10] = now - 3_600_000
        lastSearch[20] = now - 7_200_000
        // The first look: 10 and 20 were wanted before it, for who knows how long, so they wait from now.
        assertEquals(0, youtube.pass()!!.due)
        later(5)
        // Wanted since then; 12 is only out in a week.
        wanted += listOf(11, 12)
        releaseDates[12] = now + 7 * 24 * 3_600_000L
        assertEquals(0, youtube.pass()!!.due)
        later(14)
        assertEquals(0, youtube.pass()!!.due)
        assertEquals(listOf(), searches())
        later(1)
        // Due: 10 and 20, the ones from before the first look, the last searched first; one a pass.
        val first = youtube.pass()!!
        assertEquals(2, first.due)
        assertEquals(10, first.album)
        assertTrue(first.result!!.grabbed)
        assertEquals(listOf("yt-10"), grabs())
        later(5)
        // 11 has waited long enough now, and goes before what was there before the first look.
        assertEquals(11, youtube.pass()!!.album)
        later(2)
        assertEquals(20, youtube.pass()!!.album)
        later(2)
        assertEquals(0, youtube.pass()!!.due)
        assertEquals(listOf("yt-10", "yt-11", "yt-20"), grabs())
        // Lidarr searched all indexers, one album at a time, and the grab named the release and its indexer.
        assertEquals(listOf("GET release?albumId=10", "GET release?albumId=11", "GET release?albumId=20"), searches())
        assertEquals("""{"guid":"yt-10","indexerId":7}""", grabBodies.first())
        assertTrue(received.all { !it.startsWith("GET wanted") || "monitored=true" in it && "pageSize=1000" in it })
    }

    @Test
    fun anythingInTheQueueForAnAlbumHoldsItBack() {
        val youtube = fallback()
        youtube.pass()
        later(20)
        // A torrent downloading for 10, and one for 20 that finished and waits for its import (named by its album).
        queue += queued(10, "qBittorrent")
        queue += """{"id":2,"album":{"id":20},"downloadClient":"qBittorrent","status":"completed","trackedDownloadState":"importPending"}"""
        val held = youtube.pass()!!
        assertEquals(0, held.due)
        assertEquals(listOf(), searches())
        queue.removeAt(0)
        later(2)
        assertEquals(10, youtube.pass()!!.album)
        assertEquals(listOf("yt-10"), grabs())
    }

    @Test
    fun anAlbumThatGotNothingIsTriedAgainOnlyAfterTwelveHours() {
        wanted.clear()
        wanted += 10
        releases[10] = listOf(release("yt-10", 7, 20, approved = false), release("mp3-10", 3, 40, approved = false))
        val youtube = fallback()
        youtube.pass()
        later(20)
        val tried = youtube.pass()!!
        assertEquals(10, tried.album)
        assertFalse(tried.result!!.grabbed)
        assertEquals(listOf(), grabs())
        later(2)
        assertNull(youtube.pass()!!.album)
        later(12 * 60 - 3)
        assertNull(youtube.pass()!!.album)
        assertEquals(1, searches().size)
        later(1)
        assertEquals(10, youtube.pass()!!.album)
        assertEquals(2, searches().size)
    }

    @Test
    fun aBetterTorrentWinsAndYouTubeOtherwise() {
        wanted.clear()
        wanted += listOf(10, 11, 20)
        // 10: a FLAC torrent Lidarr ranks above YouTube (a lower weight is better), and a rejected one ranked higher still.
        releases[10] = listOf(release("yt-10", 7, 30), release("flac-10", 3, 10), release("rejected-10", 3, 1, approved = false))
        // 20: the torrent is worse than YouTube.
        releases[20] = listOf(release("mp3-20", 3, 50), release("yt-20", 7, 20))
        // 11: the best torrent's link is dead, so the next one.
        releases[11] = listOf(release("dead-11", 3, 5), release("yt-11", 7, 20))
        dead += "dead-11"
        val youtube = fallback()
        youtube.pass()
        later(20)
        repeat(3) {
            assertTrue(youtube.pass()!!.result!!.grabbed)
            later(2)
        }
        assertEquals(listOf("yt-20", "dead-11", "yt-11", "flac-10"), grabs())
        assertTrue("""{"guid":"flac-10","indexerId":3}""" in grabBodies)
    }

    @Test
    fun noGrabWhileEnoughYouTubeDownloadsRun() {
        val youtube = fallback(maxActive = 2)
        youtube.pass()
        later(20)
        // Two YouTube downloads for other albums.
        queue += queued(98, "YouTube")
        queue += queued(99, "YouTube")
        val held = youtube.pass()!!
        assertEquals(2, held.due)
        assertEquals(2, held.running)
        assertNull(held.album)
        assertEquals(listOf(), searches())
        // One finished and waits for its import: that holds nothing up.
        queue[1] = queued(99, "YouTube", status = "completed")
        later(2)
        assertEquals(1, youtube.pass()!!.running)
        assertEquals(1, grabs().size)
        // A torrent doesn't count; a YouTube download through a client of another name does, by its protocol.
        queue += queued(97, "qBittorrent")
        queue += queued(96, "Tubifarry", protocol = "YoutubeDownloadProtocol")
        later(2)
        val again = youtube.pass()!!
        assertEquals(2, again.running)
        assertNull(again.album)
        assertEquals(1, grabs().size)
    }

    @Test
    fun whenAnAlbumWasFirstSeenOutlivesARestart() {
        fallback().pass()
        later(15)
        // The server starts again: 10 and 20 have waited 15 minutes, not none.
        var youtube = fallback()
        later(4)
        assertEquals(0, youtube.pass()!!.due)
        later(1)
        assertEquals(20, youtube.pass()!!.album)
        assertTrue(File(dataDir, "youtube-fallback.json").exists())
        // And what was tried isn't tried again.
        youtube = fallback()
        later(2)
        assertEquals(10, youtube.pass()!!.album)
        youtube = fallback()
        later(2)
        assertEquals(0, youtube.pass()!!.due)
        assertEquals(listOf("yt-20", "yt-10"), grabs())
        // An album that isn't wanted anymore is forgotten: wanted again, it waits again.
        wanted.remove(20)
        youtube.pass()
        wanted += 20
        later(2)
        val back = youtube.pass()!!
        assertEquals(0, back.due)
        later(20)
        assertEquals(20, youtube.pass()!!.album)
    }

    @Test
    fun anAlbumAskedForThroughTheServerWaitsFromTheRequest() {
        wanted.clear()
        wanted += listOf(10, 20, 30)
        // Bob asked for 20 25 minutes ago, carol for all of Low (10's artist) 5 minutes ago; nobody for 30.
        requests.add("bob", listOf(Requested(now - 25 * 60_000L, albumId = 20)), now)
        requests.add("carol", listOf(Requested(now - 5 * 60_000L, artistId = 1)), now)
        val youtube = fallback()
        val first = youtube.pass()!!
        assertEquals(1, first.due)
        assertEquals(20, first.album)
        later(14)
        assertNull(youtube.pass()!!.album)
        later(1)
        assertEquals(10, youtube.pass()!!.album)
        later(4)
        assertNull(youtube.pass()!!.album)
        later(1)
        assertEquals(30, youtube.pass()!!.album)
        assertEquals(listOf("yt-20", "yt-10", "yt-30"), grabs())
    }
}

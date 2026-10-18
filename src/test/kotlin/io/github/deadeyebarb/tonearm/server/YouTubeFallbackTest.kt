package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
 * Wanted albums from YouTube when nothing else came in time, and "YouTube now", against a fake Navidrome that knows alice
 * (an admin), bob and carol, and a fake Lidarr (PICKS_FOLDER=/picks) with a torrent indexer and Tubifarry's YouTube one
 * (id 7). Each album's releases are a YouTube one Lidarr would take unless a test says otherwise. Time is the test's.
 */
class YouTubeFallbackTest {
    private lateinit var navidrome: HttpServer
    private lateinit var lidarr: HttpServer
    private var server: TonearmServer? = null
    private lateinit var dataDir: File
    private lateinit var requests: Requests
    private lateinit var upstream: Upstream
    private val http = HttpClient.newHttpClient()
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
        navidrome = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/rest/") { ex ->
                val q = ex.requestURI.rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
                val name = mapOf("alicekey" to "Alice", "bobkey" to "Bob", "carolkey" to "Carol")[q["apiKey"]]
                val body = when {
                    name == null -> """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong API key"}}}"""
                    ex.requestURI.path.endsWith("/tokenInfo.view") -> """{"subsonic-response":{"status":"ok","tokenInfo":{"username":"$name"}}}"""
                    else -> """{"subsonic-response":{"status":"ok","user":{"username":"$name","adminRole":${name == "Alice"}}}}"""
                }
                val bytes = body.encodeToByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
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
        server?.stop()
        navidrome.stop(0)
        lidarr.stop(0)
    }

    private fun fallback(minutes: Int = 20, maxActive: Int = 2) = YouTubeFallback(upstream, requests, dataDir, minutes, maxActive, clock = { now })

    /** The server with [youtube], in place of the one before. */
    private fun serve(youtube: YouTubeFallback) {
        server?.stop()
        youtube.checkIndexers()
        server = TonearmServer(
            0, "/connect-tonearm", NavidromeAuth("http://127.0.0.1:${navidrome.address.port}/"), dataDir, "test",
            LidarrProxy(upstream, requests, requestsForEveryone = true, picksRoot = "/picks", youtube = youtube),
        ).start()
    }

    private fun later(minutes: Long) {
        now += minutes * 60_000L
    }

    private val alice = "apiKey=alicekey"
    private val bob = "apiKey=bobkey"
    private val carol = "apiKey=carolkey"

    private fun call(path: String, login: String, method: String = "GET"): Pair<Int, String> {
        val (route, params) = path.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server!!.boundPort}/connect-tonearm/$route?$login" + (params?.let { "&$it" } ?: "")))
            .method(method, HttpRequest.BodyPublishers.noBody()).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
    }

    private fun json(path: String, login: String, method: String = "GET"): JsonObject = call(path, login, method).let { (code, text) ->
        assertEquals(200, code, text)
        Json.parseToJsonElement(text).jsonObject
    }

    private fun youtubeNow(albumId: Int, login: String) = call("api/youtubenow?albumId=$albumId", login, "POST")

    /** Wanted albums as [login] sees them through the server, by id. */
    private fun wantedAs(login: String, list: String = "missing"): Map<Int, JsonObject> =
        json("lidarr/api/v1/wanted/$list?page=1&pageSize=50&includeArtist=true", login)["records"]!!.jsonArray
            .associate { it.jsonObject["id"]!!.jsonPrimitive.int to it.jsonObject }

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

    @Test
    fun youtubeNowIsForAdminsAndForWhatYouAskedFor() {
        releases[10] = listOf(release("flac-10", 3, 5), release("yt-10", 7, 20, title = "Low - Album 10 [AAC 256]"), release("yt-10-other", 7, 10, approved = false))
        serve(fallback())
        // An admin, any album: YouTube's best, even with a better torrent there.
        val answer = json("api/youtubenow?albumId=10", alice, "POST")
        assertTrue(answer["grabbed"]!!.jsonPrimitive.boolean)
        assertEquals("Low - Album 10 [AAC 256]", answer["title"]!!.jsonPrimitive.content)
        assertEquals(listOf("yt-10"), grabs())
        // Bob only for what he asked for.
        assertEquals(403, youtubeNow(20, bob).first)
        requests.add("bob", listOf(Requested(now, albumId = 20)))
        assertTrue(json("api/youtubenow?albumId=20", bob, "POST")["grabbed"]!!.jsonPrimitive.boolean)
        // A whole artist asked for counts for its albums.
        assertEquals(403, youtubeNow(11, carol).first)
        requests.add("carol", listOf(Requested(now, foreignArtistId = "mb-artist-1")))
        assertTrue(json("api/youtubenow?albumId=11", carol, "POST")["grabbed"]!!.jsonPrimitive.boolean)
        // What's in his own weekly picks is his; carol's isn't, and nobody's to download into, admins' included.
        assertTrue(json("api/youtubenow?albumId=30", bob, "POST")["grabbed"]!!.jsonPrimitive.boolean)
        assertEquals(403, youtubeNow(40, bob).first)
        val (code, text) = youtubeNow(40, alice)
        assertEquals(409, code)
        assertTrue("Cocteau Twins" in text, text)
        assertEquals(listOf("yt-10", "yt-20", "yt-11", "yt-30"), grabs())
        // Nothing on YouTube that Lidarr would take.
        releases[12] = listOf(release("yt-12", 7, 20, approved = false), release("flac-12", 3, 5))
        val none = json("api/youtubenow?albumId=12", alice, "POST")
        assertFalse(none["grabbed"]!!.jsonPrimitive.boolean)
        assertEquals("No YouTube Music match for “Album 12”", none["problem"]!!.jsonPrimitive.content)
        // Lidarr can't grab it: said, without Lidarr's own answer (it has the indexer's key).
        releases[13] = listOf(release("yt-13", 7, 20))
        dead += "yt-13"
        val failed = call("api/youtubenow?albumId=13", alice, "POST")
        assertEquals(200, failed.first)
        assertFalse("prowlarr-secret" in failed.second, failed.second)
        assertFalse(Json.parseToJsonElement(failed.second).jsonObject["grabbed"]!!.jsonPrimitive.boolean)
        // Asked the wrong way, for an album Lidarr hasn't got, or for none.
        assertEquals(405, call("api/youtubenow?albumId=10", alice).first)
        assertEquals(404, youtubeNow(99, alice).first)
        assertEquals(400, call("api/youtubenow", alice, "POST").first)
        // Tried with nothing grabbed: the fallback leaves it for 12 hours.
        wanted.clear()
        wanted += 12
        val youtube = fallback()
        youtube.pass()
        later(20)
        assertEquals(0, youtube.pass()!!.due)
    }

    @Test
    fun helloSaysWhatYouTubeCanDo() {
        serve(fallback())
        for (login in listOf(alice, bob)) {
            val hello = json("api/hello", login)
            assertEquals(20, hello["youtubeFallback"]!!.jsonPrimitive.int)
            assertTrue(hello["youtubeNow"]!!.jsonPrimitive.boolean)
        }
        // Off, YouTube now still works.
        serve(fallback(minutes = 0))
        val off = json("api/hello", bob)
        assertFalse("youtubeFallback" in off)
        assertTrue(off["youtubeNow"]!!.jsonPrimitive.boolean)
        assertTrue(json("api/youtubenow?albumId=10", alice, "POST")["grabbed"]!!.jsonPrimitive.boolean)
        assertNull(fallback(minutes = 0).pass())
        // Without a YouTube indexer that searches, neither; and the fallback doesn't look.
        for (list in listOf("[$torrents]", "[$torrents,${youtubeIndexer.replace("\"enableInteractiveSearch\":true", "\"enableInteractiveSearch\":false")}]")) {
            indexers = list
            val youtube = fallback()
            serve(youtube)
            val hello = json("api/hello", alice)
            assertFalse("youtubeFallback" in hello)
            assertFalse("youtubeNow" in hello)
            assertEquals(404, youtubeNow(10, alice).first)
            assertNull(youtube.pass())
        }
        assertFalse(received.any { it.startsWith("GET wanted") })
        // One that's added later is found on the next look.
        indexers = "[$torrents,$youtubeIndexer]"
        val youtube = fallback()
        assertEquals(0, youtube.pass()!!.due)
    }

    @Test
    fun wantedAlbumsSayWhenYouTubeIsTried() {
        wanted.clear()
        wanted += listOf(10, 11, 12, 20, 30)
        releaseDates[12] = now + 3 * 24 * 3_600_000L
        lastSearch[11] = now - 60_000L
        releases[11] = listOf(release("yt-11", 7, 20, approved = false))
        requests.add("bob", listOf(Requested(now - 5 * 60_000L, albumId = 10)), now)
        val start = now
        val youtube = fallback()
        serve(youtube)
        youtube.pass()
        later(15)
        assertEquals(10, youtube.pass()!!.album)
        later(5)
        // 11 (searched last) grabs nothing.
        assertEquals(11, youtube.pass()!!.album)
        val tried = now
        // Bob asked for 13 a moment ago, before the fallback's next look.
        later(1)
        wanted += 13
        requests.add("bob", listOf(Requested(now - 60_000L, albumId = 13)))
        fun at(record: JsonObject?) = record?.get("youtubeAt")?.jsonPrimitive?.long
        fun triedAt(record: JsonObject?) = record?.get("youtubeTried")?.jsonPrimitive?.long

        val all = wantedAs(alice)
        assertEquals(listOf(10, 11, 12, 20, 30, 13), all.keys.toList())
        // Grabbed: the queue says the rest.
        assertNull(at(all[10]))
        assertNull(triedAt(all[10]))
        assertEquals(listOf("bob"), all[10]!!["requestedBy"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(tried, triedAt(all[11]))
        assertNull(at(all[11]))
        // Not out yet: when it is.
        assertEquals(releaseDates[12], at(all[12]))
        assertEquals(start + 20 * 60_000L, at(all[20]))
        assertEquals(start + 20 * 60_000L, at(all[30]))
        assertEquals(now - 60_000L + 20 * 60_000L, at(all[13]))
        // Bob sees his own, tagged the same way.
        val his = wantedAs(bob)
        assertEquals(listOf(10, 30, 13), his.keys.toList())
        assertNull(at(his[10]))
        assertEquals(start + 20 * 60_000L, at(his[30]))
        assertEquals(at(all[13]), at(his[13]))
        // Carol asked for nothing.
        assertEquals(listOf(), wantedAs(carol).keys.toList())
        // The cutoff list isn't the fallback's, and with the fallback off nothing is tagged.
        assertFalse(wantedAs(alice, "cutoff").values.any { "youtubeAt" in it || "youtubeTried" in it })
        serve(fallback(minutes = 0))
        assertFalse(wantedAs(alice).values.any { "youtubeAt" in it || "youtubeTried" in it })
    }
}

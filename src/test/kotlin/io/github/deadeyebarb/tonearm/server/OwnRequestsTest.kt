package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Everyone's own Lidarr requests, against a fake Navidrome that knows alice (an admin), bob, carol and dave, and a fake Lidarr
 * (PICKS_FOLDER=/picks) whose wanted list and queue hold what bob and carol asked for, their weekly picks, and much
 * nobody asked for through the server.
 */
class OwnRequestsTest {
    private lateinit var navidrome: HttpServer
    private lateinit var lidarr: HttpServer
    private lateinit var server: TonearmServer
    private lateinit var dataDir: File
    private lateinit var requests: Requests
    private val http = HttpClient.newHttpClient()
    /** What reached the fake Lidarr: method, path and query. */
    private val received = mutableListOf<String>()

    private class Artist(val id: Int, val name: String, val mbid: String, val path: String) {
        fun json() = """{"id":$id,"artistName":"$name","foreignArtistId":"$mbid","path":"$path","rootFolderPath":"${path.substringBeforeLast('/')}"}"""
    }

    private class Album(val id: Int, val mbid: String, val artistId: Int, var monitored: Boolean = true)

    private val artists = mutableListOf(
        Artist(1, "Low", "mb-low", "/music/Low"),
        Artist(2, "Slowdive", "mb-slowdive", "/music/Slowdive"),
        Artist(3, "Radiohead", "mb-radiohead", "/music/Radiohead"),
        Artist(4, "Mazzy Star", "mb-mazzy", "/picks/bob/Mazzy Star"),
        Artist(5, "Cocteau Twins", "mb-cocteau", "/picks/carol/Cocteau Twins"),
        Artist(6, "Filler", "mb-filler", "/music/Filler"),
    )

    /** 10 and 11 are Low's, 20 Slowdive's, 30 Radiohead's (not monitored), 40 in bob's picks, 50 in carol's; the rest nobody asked for. */
    private val albums = (listOf(
        Album(10, "mb-10", 1), Album(11, "mb-11", 1), Album(20, "mb-20", 2), Album(30, "mb-30", 3, monitored = false),
        Album(40, "mb-40", 4), Album(50, "mb-50", 5),
    ) + (1000 until 3500).map { Album(it, "mb-$it", 6) }).associateBy { it.id }.toMutableMap()

    /** Lidarr's wanted list: three chunks of 1,000 for the server, with what was asked for spread over them. */
    private val wanted = (1000 until 2200).toList() + 10 + (2200 until 3500) + listOf(11, 20, 30, 40, 50)

    private fun albumJson(album: Album, withArtist: Boolean) =
        """{"id":${album.id},"title":"Album ${album.id}","foreignAlbumId":"${album.mbid}","artistId":${album.artistId},"monitored":${album.monitored}""" +
            (if (withArtist) ""","artist":${artists.first { it.id == album.artistId }.json()}""" else "") + "}"

    /** An artist Lidarr's metadata knows but Lidarr hasn't got, so with no path. */
    private val newcomer = """{"artistName":"Newcomer","foreignArtistId":"mb-new","folder":"Newcomer"}"""

    /** Queue items, without their artist and album (as Lidarr sends them unless asked): one of them Lidarr couldn't match. */
    private val queue = listOf(10, 20, 30, 40, 50).mapIndexed { i, id -> """{"id":${i + 1},"title":"Album $id","albumId":$id,"artistId":${albums[id]!!.artistId}}""" } +
        """{"id":6,"title":"Something unknown"}"""

    @BeforeTest
    fun start() {
        navidrome = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/rest/") { ex ->
                val q = ex.requestURI.rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
                val name = mapOf("alicekey" to "Alice", "bobkey" to "Bob", "carolkey" to "Carol", "davekey" to "Dave")[q["apiKey"]]
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
                val withArtist = param("includeArtist") == "true"
                fun page(records: List<String>): String {
                    val page = param("page")?.toInt() ?: 1
                    val size = param("pageSize")?.toInt() ?: 10
                    val part = records.drop((page - 1) * size).take(size)
                    return """{"page":$page,"pageSize":$size,"sortKey":"releaseDate","sortDirection":"descending","totalRecords":${records.size},"records":[${part.joinToString(",")}]}"""
                }
                val (code, answer) = when {
                    ex.requestMethod == "POST" && path == "album" -> {
                        val mbid = Json.parseToJsonElement(body).jsonObject["foreignAlbumId"]!!.jsonPrimitive.content
                        val album = Album(albums.keys.max() + 1, mbid, 3).also { albums[it.id] = it }
                        201 to albumJson(album, withArtist = true)
                    }
                    ex.requestMethod == "POST" && path == "artist" -> {
                        val mbid = Json.parseToJsonElement(body).jsonObject["foreignArtistId"]!!.jsonPrimitive.content
                        val artist = Artist(artists.maxOf { it.id } + 1, "New", mbid, "/music/New").also { artists += it }
                        201 to artist.json()
                    }
                    ex.requestMethod == "POST" && path == "command" -> 201 to """{"id":1,"status":"queued"}"""
                    ex.requestMethod == "PUT" && path == "album/monitor" -> 202 to "[]"
                    ex.requestMethod != "GET" -> 200 to "{}"
                    path == "rootfolder" -> 200 to """[{"path":"/music"},{"path":"/picks/bob"},{"path":"/picks/carol"}]"""
                    // Lookups and search have what Lidarr has, with its path, and what it hasn't got yet.
                    path == "artist/lookup" -> 200 to (artists.map { it.json() } + newcomer).joinToString(",", "[", "]")
                    path == "album/lookup" -> 200 to (listOf(10, 40, 50).map { albumJson(albums[it]!!, withArtist = true) } +
                        """{"title":"New","foreignAlbumId":"mb-new","artist":$newcomer}""").joinToString(",", "[", "]")
                    path == "search" -> 200 to listOf(
                        """{"foreignId":"mb-cocteau","artist":${artists[4].json()}}""",
                        """{"foreignId":"mb-50","album":${albumJson(albums[50]!!, withArtist = true)}}""",
                        """{"foreignId":"mb-40","album":${albumJson(albums[40]!!, withArtist = true)}}""",
                        """{"foreignId":"mb-new","artist":$newcomer}""",
                    ).joinToString(",", "[", "]")
                    path == "artist" -> 200 to artists.filter { a -> param("mbId")?.let { it == a.mbid } ?: true }.joinToString(",", "[", "]") { it.json() }
                    path.startsWith("artist/") -> artists.firstOrNull { it.id == path.substringAfter('/').toInt() }?.let { 200 to it.json() } ?: (404 to """{"message":"NotFound"}""")
                    path == "album" -> 200 to albums.values.filter { a ->
                        param("artistId")?.let { it.toInt() == a.artistId } ?: param("foreignAlbumId")?.let { it == a.mbid } ?: true
                    }.joinToString(",", "[", "]") { albumJson(it, withArtist = false) }
                    path.startsWith("album/") -> albums[path.substringAfter('/').toInt()]?.let { 200 to albumJson(it, withArtist = false) } ?: (404 to """{"message":"NotFound"}""")
                    path == "track" -> 200 to albums.values.filter { a -> param("artistId")?.let { it.toInt() == a.artistId } ?: (param("albumId")?.toInt() == a.id) }
                        .joinToString(",", "[", "]") { """{"id":${it.id * 10},"title":"Song","albumId":${it.id},"artistId":${it.artistId}}""" }
                    path == "wanted/missing" -> 200 to page(wanted.map { albumJson(albums[it]!!, withArtist) })
                    path == "queue" -> 200 to page(queue)
                    path == "queue/details" -> 200 to queue.joinToString(",", "[", "]")
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
        dataDir = Files.createTempDirectory("tonearm-server").toFile()
        requests = Requests(dataDir)
        server = TonearmServer(
            0, "/connect-tonearm", NavidromeAuth("http://127.0.0.1:${navidrome.address.port}/"), dataDir, "test",
            LidarrProxy(Upstream("Lidarr", "http://127.0.0.1:${lidarr.address.port}", "X-Api-Key", "k"), requests, requestsForEveryone = true, picksRoot = "/picks"),
        ).start()
    }

    @AfterTest
    fun stop() {
        server.stop()
        navidrome.stop(0)
        lidarr.stop(0)
    }

    private val alice = listOf("apiKey" to "alicekey")
    private val bob = listOf("apiKey" to "bobkey")
    private val carol = listOf("apiKey" to "carolkey")
    private val dave = listOf("apiKey" to "davekey")

    private fun call(path: String, login: List<Pair<String, String>>, method: String = "GET", body: String = ""): Pair<Int, String> {
        val query = login.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val (route, params) = path.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.boundPort}/connect-tonearm/lidarr/api/v1/$route?$query" + (params?.let { "&$it" } ?: "")))
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
    }

    private fun page(path: String, login: List<Pair<String, String>>): JsonObject = call(path, login).let { (code, text) ->
        assertEquals(200, code, text)
        Json.parseToJsonElement(text).jsonObject
    }

    private fun ids(page: JsonObject) = page["records"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int }

    private fun list(path: String, login: List<Pair<String, String>>) = call(path, login).let { (code, text) ->
        assertEquals(200, code, text)
        Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
    }

    private fun asked(vararg albumIds: Int) = albumIds.map { Requested(System.currentTimeMillis(), albumId = it) }

    @Test
    fun whatEachUserAsksForIsNotedThroughTheProxy() {
        assertEquals(201, call("album", bob, "POST", """{"title":"x","foreignAlbumId":"mb-new","artist":{"foreignArtistId":"mb-radiohead","rootFolderPath":"/music"}}""").first)
        assertEquals(202, call("album/monitor", bob, "PUT", """{"albumIds":[11],"monitored":true}""").first)
        assertEquals(201, call("command", carol, "POST", """{"name":"AlbumSearch","albumIds":[20]}""").first)
        assertEquals(201, call("artist", carol, "POST", """{"artistName":"New","foreignArtistId":"mb-whole","rootFolderPath":"/music","addOptions":{"monitor":"all"}}""").first)
        // An artist added for one album only is a request for that album.
        assertEquals(201, call("artist", carol, "POST", """{"artistName":"New","foreignArtistId":"mb-one","rootFolderPath":"/music","addOptions":{"monitor":"none","albumsToMonitor":["mb-one-album"]}}""").first)
        // Admins' requests count too; not wanting an album isn't a request, and neither is what's turned down.
        assertEquals(201, call("command", alice, "POST", """{"name":"ArtistSearch","artistId":3}""").first)
        assertEquals(202, call("album/monitor", alice, "PUT", """{"albumIds":[30],"monitored":false}""").first)
        assertEquals(403, call("album/10", bob, "DELETE").first)

        // As saved, a file per user.
        val saved = Requests(dataDir)
        assertTrue(File(dataDir, "requests/bob.json").exists())
        assertEquals(listOf(3500 to "mb-new", 11 to null), saved.of("bob").map { it.albumId to it.foreignAlbumId })
        val theirs = saved.of("carol")
        assertEquals(listOf(20), theirs.mapNotNull { it.albumId })
        assertEquals(listOf("mb-whole"), theirs.filter { !it.isAlbum }.map { it.foreignArtistId })
        assertEquals(7, theirs.first { it.foreignArtistId == "mb-whole" }.artistId)
        assertEquals(listOf("mb-one-album"), theirs.mapNotNull { it.foreignAlbumId })
        assertEquals(listOf(Requested(saved.of("alice").single().at, artistId = 3)), saved.of("alice"))
        assertEquals(setOf("alice", "bob", "carol"), saved.everyone().keys)
    }

    @Test
    fun everyoneButAdminsSeesOnlyWhatTheyAskedFor() {
        requests.add("bob", asked(10) + Requested(System.currentTimeMillis(), foreignAlbumId = "mb-11"))
        requests.add("carol", asked(20))
        // What's in their own picks folder is theirs too, whether or not the server noted it.
        val bobs = page("wanted/missing?page=1&pageSize=10&monitored=true&includeArtist=true", bob)
        assertEquals(listOf(10, 11, 40), ids(bobs))
        assertEquals(3, bobs["totalRecords"]!!.jsonPrimitive.int)
        assertFalse(bobs["records"]!!.jsonArray.any { "requestedBy" in it.jsonObject })
        assertEquals(listOf(20, 50), ids(page("wanted/missing?page=1&pageSize=10&includeArtist=true", carol)))
        // Lidarr was read a chunk at a time, all of it, with what the app asked for.
        val reads = received.filter { it.startsWith("GET wanted/missing") }
        assertEquals(listOf(1, 2, 3, 1, 2, 3), reads.map { Regex("page=(\\d+)").find(it)!!.groupValues[1].toInt() })
        assertTrue(reads.all { "includeArtist=true" in it && "pageSize=1000" in it })
        // Pages of what's theirs.
        val second = page("wanted/missing?page=2&pageSize=2&includeArtist=true", bob)
        assertEquals(listOf(40), ids(second))
        assertEquals(3, second["totalRecords"]!!.jsonPrimitive.int)
        assertEquals(2, second["page"]!!.jsonPrimitive.int)
        assertEquals(2, second["pageSize"]!!.jsonPrimitive.int)
        assertEquals("releaseDate", second["sortKey"]!!.jsonPrimitive.content)
        assertEquals(listOf(), ids(page("wanted/missing?page=3&pageSize=2", bob)))
        // Someone who asked for nothing sees nothing, nobody's requests from before included.
        val none = page("wanted/missing?page=1&pageSize=1000", dave)
        assertEquals(listOf(), ids(none))
        assertEquals(0, none["totalRecords"]!!.jsonPrimitive.int)
        // The queue too: their album, and (by where Lidarr keeps the artist) what's downloading into their picks folder.
        val queue = page("queue?page=1&pageSize=50", bob)["records"]!!.jsonArray.map { it.jsonObject["albumId"]!!.jsonPrimitive.int }
        assertEquals(listOf(10, 40), queue)
        assertEquals(listOf(20, 50), list("queue/details", carol).map { it["albumId"]!!.jsonPrimitive.int })
        assertEquals(listOf(), list("queue/details", dave))
    }

    @Test
    fun adminsSeeEverythingWithWhoAskedForIt() {
        requests.add("bob", asked(10))
        requests.add("carol", asked(20, 10))
        requests.add("dave", listOf(Requested(System.currentTimeMillis(), artistId = 2)))
        val first = page("wanted/missing?page=1&pageSize=2000&includeArtist=true", alice)
        val last = page("wanted/missing?page=2&pageSize=2000&includeArtist=true", alice)
        assertEquals(wanted, ids(first) + ids(last))
        assertEquals(wanted.size, last["totalRecords"]!!.jsonPrimitive.int)
        fun by(id: Int) = (first["records"]!!.jsonArray + last["records"]!!.jsonArray).map { it.jsonObject }
            .first { it["id"]!!.jsonPrimitive.int == id }["requestedBy"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("bob", "carol"), by(10))
        // Dave asked for Slowdive, whose album carol asked for too.
        assertEquals(listOf("carol", "dave"), by(20))
        assertEquals(null, by(30))
        assertEquals(null, by(40))
        // A page further on reads only the chunk it's in, and Lidarr's count holds.
        received.clear()
        val later = page("wanted/missing?page=13&pageSize=100", alice)
        assertEquals(wanted.subList(1200, 1300), ids(later))
        assertEquals(10, ids(later).first())
        assertEquals(wanted.size, later["totalRecords"]!!.jsonPrimitive.int)
        assertEquals(listOf("GET wanted/missing?page=2&pageSize=1000"), received)
        val queue = page("queue?page=1&pageSize=50", alice)["records"]!!.jsonArray.map { it.jsonObject }
        assertEquals(6, queue.size)
        assertEquals(listOf("bob", "carol"), queue.first()["requestedBy"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(false, true, true, true, true), queue.drop(1).map { "requestedBy" !in it })
        assertEquals(listOf("carol", "dave"), list("queue/details", alice)[1]["requestedBy"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun someoneElsesPicksStayOutOfSight() {
        fun names(login: List<Pair<String, String>>) = list("artist", login).map { it["artistName"]!!.jsonPrimitive.content }
        assertEquals(listOf("Low", "Slowdive", "Radiohead", "Mazzy Star", "Filler"), names(bob))
        assertEquals(listOf("Low", "Slowdive", "Radiohead", "Cocteau Twins", "Filler"), names(carol))
        assertEquals(artists.size, names(alice).size)
        assertEquals(404, call("artist/5", bob).first)
        assertEquals(200, call("artist/4", bob).first)
        assertEquals(200, call("artist/5", carol).first)
        assertEquals(404, call("album/50", bob).first)
        assertEquals(200, call("album/40", bob).first)
        assertEquals(200, call("album/50", alice).first)
        assertEquals(listOf(), list("album?artistId=5", bob))
        assertEquals(1, list("album?artistId=5", carol).size)
        assertFalse(list("album", bob).any { it["id"]!!.jsonPrimitive.int == 50 })
        assertEquals(albums.size - 1, list("album", bob).size)
        assertEquals(listOf(), list("track?artistId=5", bob))
        assertEquals(listOf(), list("track?albumId=50", bob))
        assertEquals(1, list("track?albumId=40", bob).size)
        // An artist Lidarr doesn't list (yet) can't be placed, so nothing of theirs shows.
        albums[99] = Album(99, "mb-99", 42)
        assertEquals(404, call("album/99", bob).first)
        // Asking for an artist who's there says where they are, rather than Lidarr's "already added".
        val (code, text) = call("artist", bob, "POST", """{"artistName":"Cocteau Twins","foreignArtistId":"mb-cocteau","rootFolderPath":"/music"}""")
        assertEquals(409, code)
        assertTrue("Cocteau Twins" in text)
        assertFalse(received.any { it.startsWith("POST artist") })
    }

    @Test
    fun lookupsAndSearchLeaveOutSomeoneElsesPicks() {
        fun found(path: String, login: List<Pair<String, String>>, key: String) = list(path, login).map { it[key]!!.jsonPrimitive.content }
        assertEquals(listOf("Low", "Slowdive", "Radiohead", "Mazzy Star", "Filler", "Newcomer"), found("artist/lookup?term=x", bob, "artistName"))
        assertEquals(listOf("Album 10", "Album 40", "New"), found("album/lookup?term=x", bob, "title"))
        assertEquals(listOf("mb-40", "mb-new"), found("search?term=x", bob, "foreignId"))
        assertEquals(listOf("mb-cocteau", "mb-50", "mb-new"), found("search?term=x", carol, "foreignId"))
        assertEquals(listOf("Album 10", "New"), found("album/lookup?term=x", dave, "title"))
        // Bob's own picks show where they are, so the apps know they're his.
        assertEquals("/picks/bob/Mazzy Star", list("album/lookup?term=x", bob)[1]["artist"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        // Admins get Lidarr's answer as it is.
        assertEquals(4, list("search?term=x", alice).size)
        assertEquals(artists.size + 1, list("artist/lookup?term=x", alice).size)
    }

    @Test
    fun anAlbumSomeoneElseAskedForBecomesTheirsToo() {
        requests.add("carol", asked(20))
        fun request(mbid: String, search: Boolean = true) = call("album", bob, "POST", """{"title":"x","foreignAlbumId":"$mbid","monitored":true,""" +
            """"addOptions":{"searchForNewAlbum":$search},"artist":{"foreignArtistId":"mb-slowdive","rootFolderPath":"/music"}}""")
        val (code, text) = request("mb-20")
        assertEquals(200, code, text)
        assertEquals(20, Json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.int)
        // Lidarr has it and wants it already: nothing to tell Lidarr, but it's bob's request now too.
        assertFalse(received.any { !it.startsWith("GET") })
        assertEquals(listOf(20, 40), ids(page("wanted/missing?page=1&pageSize=10", bob)))
        // One Lidarr has and doesn't want yet is wanted from now on, and looked for when the request says so.
        assertEquals(200, request("mb-30").first)
        assertEquals(listOf("PUT album/monitor", "POST command"), received.filter { !it.startsWith("GET") })
        assertEquals(listOf(20, 30, 40), ids(page("wanted/missing?page=1&pageSize=10", bob)))
        // Asking to monitor an album that's wanted already makes it theirs as well.
        assertEquals(202, call("album/monitor", dave, "PUT", """{"albumIds":[10],"monitored":true}""").first)
        assertEquals(listOf(10), ids(page("wanted/missing?page=1&pageSize=10", dave)))
    }
}

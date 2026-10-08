package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each user's weekly picks in a folder of their own (PICKS_FOLDER=/picks), against a fake Navidrome that knows bob
 * (API key "bobkey", not an admin) and a fake Lidarr whose disk has /picks/bob but not yet /picks/carol (the server names users in lowercase).
 */
class PicksFolderTest {
    private lateinit var navidrome: HttpServer
    private lateinit var lidarr: HttpServer
    private lateinit var server: TonearmServer
    private val http = HttpClient.newHttpClient()
    private val roots = mutableListOf("""{"path":"/music","defaultMetadataProfileId":2,"defaultQualityProfileId":3}""")
    private val onDisk = setOf("/music", "/picks/bob")
    /** What reached the fake Lidarr: method and path. */
    private val received = mutableListOf<String>()
    private var added: JsonObject? = null
    /** Bodies and queries that reached the fake Lidarr, by method and path. */
    private val sent = mutableMapOf<String, String>()
    private var lidarrUp = true

    @BeforeTest
    fun start() {
        navidrome = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/rest/") { ex ->
                val q = ex.requestURI.rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
                val name = mapOf("bobkey" to "Bob", "carolkey" to "Carol")[q["apiKey"]]
                val body = when {
                    name == null -> """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong API key"}}}"""
                    ex.requestURI.path.endsWith("/tokenInfo.view") -> """{"subsonic-response":{"status":"ok","tokenInfo":{"username":"$name"}}}"""
                    else -> """{"subsonic-response":{"status":"ok","user":{"username":"$name","adminRole":false}}}"""
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
                val path = ex.requestURI.path
                synchronized(received) {
                    received += "${ex.requestMethod} $path"
                    sent["${ex.requestMethod} $path"] = body.ifEmpty { ex.requestURI.rawQuery.orEmpty() }
                }
                val (code, answer) = when {
                    !lidarrUp -> 503 to """{"message":"restarting"}"""
                    path == "/api/v1/rootfolder" && ex.requestMethod == "POST" -> {
                        val folder = Json.parseToJsonElement(body).jsonObject
                        added = folder
                        if (folder["path"]!!.jsonPrimitive.content in onDisk) 201 to body.also { roots += it }
                        else 400 to """[{"propertyName":"Path","errorMessage":"Path does not exist"}]"""
                    }
                    path == "/api/v1/rootfolder" -> 200 to roots.joinToString(",", "[", "]")
                    // Album 20 is in bob's picks, 21 in carol's, 22 in the shared library.
                    path == "/api/v1/album/20" -> 200 to """{"id":20,"artistId":10}"""
                    path == "/api/v1/album/21" -> 200 to """{"id":21,"artistId":11}"""
                    path == "/api/v1/album/22" -> 200 to """{"id":22,"artistId":12}"""
                    path == "/api/v1/album/23" -> 200 to """{"id":23,"artistId":13}"""
                    path == "/api/v1/album/24" -> 200 to """{"id":24,"artistId":14}"""
                    path == "/api/v1/album/25" -> 500 to """{"message":"database is locked"}"""
                    path == "/api/v1/album/99" -> 404 to """{"message":"NotFound"}"""
                    // Lidarr's own database and metadata say whose album it is, whatever a request claims.
                    path == "/api/v1/album" && ex.requestURI.query.orEmpty().startsWith("foreignAlbumId=") -> when (ex.requestURI.query) {
                        "foreignAlbumId=mb-pyg" -> 200 to """[{"id":21,"title":"Pygmalion","artistId":11}]"""
                        "foreignAlbumId=mb-broken" -> 500 to """{"message":"database is locked"}"""
                        else -> 200 to "[]"
                    }
                    path == "/api/v1/album/lookup" -> 200 to when (ex.requestURI.query) {
                        "term=lidarr%3A$SOUVLAKI", "term=lidarr:$SOUVLAKI" -> """[{"title":"Souvlaki","artist":{"artistName":"Slowdive","foreignArtistId":"mb-slowdive","path":"/picks/carol/Slowdive"}}]"""
                        "term=lidarr%3A$KID_A", "term=lidarr:$KID_A" -> """[{"title":"Kid A","artist":{"artistName":"Radiohead","foreignArtistId":"mb-radiohead"}}]"""
                        else -> "[]"
                    }
                    path == "/api/v1/artist/10" -> 200 to """{"id":10,"artistName":"Low","path":"/picks/bob/Low","rootFolderPath":"/picks/bob"}"""
                    path == "/api/v1/artist/11" -> 200 to """{"id":11,"artistName":"Slowdive","path":"/picks/carol/Slowdive","rootFolderPath":"/picks/carol"}"""
                    path == "/api/v1/artist/12" -> 200 to """{"id":12,"artistName":"Radiohead","path":"/music/Radiohead","rootFolderPath":"/music"}"""
                    // Made with a path of its own that climbs out of bob's folder.
                    path == "/api/v1/artist/13" -> 200 to """{"id":13,"path":"/picks/bob/../../music/Radiohead/OK Computer"}"""
                    // Lidarr set to put artists in a folder by first letter.
                    path == "/api/v1/artist/14" -> 200 to """{"id":14,"path":"/picks/bob/L/Low","rootFolderPath":"/picks/bob"}"""
                    path == "/api/v1/artist" -> 200 to """[{"id":10,"artistName":"Low","foreignArtistId":"mb-low","path":"/picks/bob/Low"},
                        {"id":11,"artistName":"Slowdive","foreignArtistId":"mb-slowdive","path":"/picks/carol/Slowdive"},
                        {"id":12,"artistName":"Radiohead","foreignArtistId":"mb-radiohead","path":"/music/Radiohead"}]"""
                    else -> 200 to """{"ok":true}"""
                }
                val bytes = answer.encodeToByteArray()
                ex.responseHeaders.set("Content-Type", "application/json")
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        server = TonearmServer(
            0, "/connect-tonearm", NavidromeAuth("http://127.0.0.1:${navidrome.address.port}/"), Files.createTempDirectory("tonearm-server").toFile(), "test",
            LidarrProxy(Upstream("Lidarr", "http://127.0.0.1:${lidarr.address.port}", "X-Api-Key", "k"), requestsForEveryone = true, picksRoot = "/picks/"),
        ).start()
    }

    @AfterTest
    fun stop() {
        server.stop()
        navidrome.stop(0)
        lidarr.stop(0)
    }

    private val bob = listOf("apiKey" to "bobkey")
    private val carol = listOf("apiKey" to "carolkey")

    private fun call(path: String, login: List<Pair<String, String>>, method: String = "POST", body: String = ""): Pair<Int, String> {
        val query = login.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.boundPort}$path?$query"))
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
    }

    private fun api(op: String, login: List<Pair<String, String>>) = call("/connect-tonearm/api/$op", login).let { (code, text) ->
        assertEquals(200, code, text)
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun everyoneHasAFolderOfTheirOwnThatLidarrTakesOnWhenItExists() {
        assertEquals("/picks/bob", api("hello", bob)["picksFolder"]!!.jsonPrimitive.content)
        val ready = api("picksfolder", bob)
        assertEquals("true", ready["ready"]!!.jsonPrimitive.content)
        // With the profiles of the first root folder, monitoring nothing by itself.
        assertEquals("3", added!!["defaultQualityProfileId"]!!.jsonPrimitive.content)
        assertEquals("none", added!!["defaultNewItemMonitorOption"]!!.jsonPrimitive.content)
        // Asked again, it's there already.
        received.clear()
        api("picksfolder", bob)
        assertFalse(received.any { it.startsWith("POST") })
        // Carol's folder doesn't exist yet: what to set up.
        val missing = api("picksfolder", carol)
        assertEquals("false", missing["ready"]!!.jsonPrimitive.content)
        assertTrue("Path does not exist" in missing["problem"]!!.jsonPrimitive.content)
        assertTrue("/picks/carol" in missing["problem"]!!.jsonPrimitive.content)
    }

    @Test
    fun picksGoIntoTheirOwnFolderOnly() {
        api("picksfolder", bob)
        fun add(root: String) = call("/connect-tonearm/lidarr/api/v1/artist", bob, body = """{"artistName":"Low","rootFolderPath":"$root"}""").first
        assertEquals(200, add("/picks/bob"))
        assertEquals(200, add("/music"))
        // Someone else's picks folder, even when Lidarr has it.
        roots += """{"path":"/picks/carol"}"""
        assertEquals(400, add("/picks/carol"))
    }

    @Test
    fun theyCanDeleteWhatsInTheirOwnPicksAndNothingElse() {
        assertEquals(200, call("/connect-tonearm/lidarr/api/v1/album/20", bob, "DELETE").first)
        assertEquals(200, call("/connect-tonearm/lidarr/api/v1/artist/10", bob, "DELETE").first)
        assertEquals(403, call("/connect-tonearm/lidarr/api/v1/album/21", bob, "DELETE").first)
        assertEquals(403, call("/connect-tonearm/lidarr/api/v1/album/22", bob, "DELETE").first)
        assertEquals(403, call("/connect-tonearm/lidarr/api/v1/artist/12", bob, "DELETE").first)
        assertEquals(listOf("DELETE /api/v1/album/20", "DELETE /api/v1/artist/10"), received.filter { it.startsWith("DELETE") })
        // A path of its own that only looks like it's in bob's folder.
        assertEquals(403, call("/connect-tonearm/lidarr/api/v1/album/23", bob, "DELETE").first)
        assertEquals(403, call("/connect-tonearm/lidarr/api/v1/artist/13", bob, "DELETE").first)
        // Deeper down in his own folder is still his.
        assertEquals(200, call("/connect-tonearm/lidarr/api/v1/album/24", bob, "DELETE").first)
    }

    @Test
    fun aDeleteNeverAddsAnImportListExclusion() {
        val (code, _) = call("/connect-tonearm/lidarr/api/v1/album/20", bob + listOf("deleteFiles" to "true", "AddImportListExclusion" to "true", "addImportListExclusion" to "true"), "DELETE")
        assertEquals(200, code)
        assertEquals("deleteFiles=true&addImportListExclusion=false", sent["DELETE /api/v1/album/20"])
    }

    @Test
    fun keysLidarrReadsWhateverTheirCaseCantSneakPastTheChecks() {
        api("picksfolder", bob)
        fun add(body: String) = call("/connect-tonearm/lidarr/api/v1/artist", bob, body = body).first
        // A path of its own in another spelling, or the root folder twice: refused.
        assertEquals(400, add("""{"artistName":"Low","rootFolderPath":"/music","RootFolderPath":"/picks/carol"}"""))
        assertEquals(400, add("""{"artistName":"Low","rootFolderPath":"/music","Path":"/picks/carol/Low","path":"/music/Low"}"""))
        // One spelling is fine, and what decides where it goes is set here, whatever came.
        assertEquals(200, add("""{"artistName":"Low","RootFolderPath":"/picks/bob","Path":"/picks/carol/Low","Tags":[3],"Folder":"x"}"""))
        val forwarded = Json.parseToJsonElement(sent["POST /api/v1/artist"]!!).jsonObject
        assertEquals(setOf("artistName", "rootFolderPath", "tags"), forwarded.keys)
        assertEquals("/picks/bob", forwarded["rootFolderPath"]!!.jsonPrimitive.content)
        assertEquals(400, call("/connect-tonearm/lidarr/api/v1/album", bob, body = """{"title":"x","artist":{"rootFolderPath":"/music"},"Artist":{"rootFolderPath":"/picks/carol"}}""").first)
    }

    private companion object {
        /** MusicBrainz ids, which Lidarr's metadata looks up. */
        const val SOUVLAKI = "6d4f9a0e-2a3b-4c5d-8e9f-0a1b2c3d4e5f"
        const val KID_A = "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e"
    }

    @Test
    fun nothingGoesIntoSomeoneElsesPicks() {
        fun album(albumId: String, artistId: String) =
            call("/connect-tonearm/lidarr/api/v1/album", bob, body = """{"title":"x","foreignAlbumId":"$albumId","artist":{"foreignArtistId":"$artistId","rootFolderPath":"/music"}}""")
        // Pygmalion is Slowdive's, who live in carol's picks: it would land there, whatever artist the request names.
        val (code, text) = album("mb-pyg", "mb-slowdive")
        assertEquals(409, code)
        assertTrue("Slowdive" in text && "admin" in text)
        assertEquals(409, album("mb-pyg", "mb-radiohead").first)
        // By its MusicBrainz id, Lidarr's metadata knows a new album's artist, whatever the request says.
        assertEquals(409, album(SOUVLAKI, "mb-radiohead").first)
        assertEquals(200, album(KID_A, "mb-newcomer").first)
        // Other ids (Tubifarry's Deezer ones) can't be looked up: the artist the request names decides.
        assertEquals(200, album("123@deezer", "mb-radiohead").first)
        assertEquals(200, album("mb-new", "mb-newcomer").first)
        assertEquals(409, album("456@deezer", "mb-slowdive").first)
        // When Lidarr can't say, nothing is let through.
        assertEquals(502, album("mb-broken", "mb-radiohead").first)
        // Monitoring or searching for carol's albums would download into her folder too, in any spelling.
        fun put(body: String) = call("/connect-tonearm/lidarr/api/v1/album/monitor", bob, "PUT", body).first
        assertEquals(409, put("""{"albumIds":[21],"monitored":true}"""))
        assertEquals(409, put("""{"AlbumIds":[21],"Monitored":true}"""))
        assertEquals(400, put("""{"albumIds":[22],"monitored":true,"Monitored":false}"""))
        assertEquals(200, put("""{"albumIds":[20,22],"monitored":true}"""))
        assertEquals("""{"albumIds":[20,22],"monitored":true}""", sent["PUT /api/v1/album/monitor"])
        // An album Lidarr can't say about holds the lot back; one it doesn't have is nothing to do.
        assertEquals(502, put("""{"albumIds":[22,25],"monitored":true}"""))
        assertEquals(200, put("""{"albumIds":[22,99],"monitored":true}"""))
        fun command(body: String) = call("/connect-tonearm/lidarr/api/v1/command", bob, body = body).first
        assertEquals(409, command("""{"name":"AlbumSearch","albumIds":[21]}"""))
        assertEquals(409, command("""{"Name":"ArtistSearch","ArtistId":11}"""))
        assertEquals(400, command("""{"name":"AlbumSearch","albumIds":[22],"AlbumIds":[21]}"""))
        assertEquals(502, command("""{"name":"AlbumSearch","albumIds":[25]}"""))
        assertEquals(200, command("""{"name":"AlbumSearch","albumIds":[22],"extra":{"x":1}}"""))
        assertEquals("""{"name":"AlbumSearch","albumIds":[22]}""", sent["POST /api/v1/command"])
    }

    @Test
    fun aFolderAnAdminAddedInLidarrTakesRequestsAtOnce() {
        // Bob's own folder isn't in the (cached) list yet when he first asks for something.
        roots.removeAll { "bob" in it }
        call("/connect-tonearm/lidarr/api/v1/artist", bob, body = """{"artistName":"x","rootFolderPath":"/music"}""")
        roots += """{"path":"/picks/bob"}"""
        assertEquals(200, call("/connect-tonearm/lidarr/api/v1/artist", bob, body = """{"artistName":"Low","rootFolderPath":"/picks/bob"}""").first)
    }

    @Test
    fun aLidarrThatDoesntAnswerIsntAFolderToSetUp() {
        lidarrUp = false
        assertEquals(502, call("/connect-tonearm/api/picksfolder", bob).first)
    }

    @Test
    fun folderNamesAreSafeAndNeverShared() {
        assertEquals("bob.smith", LidarrProxy.folderName("bob.smith"))
        assertEquals("jörg", LidarrProxy.folderName("jörg"))
        assertTrue(LidarrProxy.folderName("anna/maria").startsWith("anna_maria-"))
        assertTrue(LidarrProxy.folderName("..").startsWith("_-"))
        for ((a, b) in listOf("a b" to "a_b", "max." to "max", ".bob" to "bob", "anna@x" to "anna_x")) {
            assertTrue(LidarrProxy.folderName(a) != LidarrProxy.folderName(b), "$a and $b")
        }
    }
}

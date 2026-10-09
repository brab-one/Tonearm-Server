package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The whole server against a fake Navidrome that knows alice (password "wonderland", an admin) and bob
 * (API key "bobkey"), a fake Lidarr that records what reaches it, and a Maloja to bring alice's history from.
 */
class ServerTest {
    private lateinit var navidrome: HttpServer
    private lateinit var server: TonearmServer
    private val http = HttpClient.newHttpClient()
    private var navidromeCalls = 0
    private lateinit var upstream: HttpServer
    /** What reached the fake Lidarr: method, path?query, X-Api-Key, body. */
    private val received = mutableListOf<List<String?>>()
    private lateinit var dataDir: java.io.File
    private lateinit var history: History
    /** Navidrome answering getUser with an error page instead of JSON. */
    private var getUserBroken = false

    @BeforeTest
    fun start() {
        navidrome = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/rest/") { ex ->
                navidromeCalls++
                val q = ex.requestURI.rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
                val ok = when {
                    ex.requestURI.path.endsWith("/tokenInfo.view") -> q["apiKey"] == "bobkey"
                    q["u"]?.lowercase() != "alice" -> false
                    q["p"] != null -> q["p"] == "wonderland" || q["p"] == "enc:" + "wonderland".encodeToByteArray().joinToString("") { "%02x".format(it) }
                    else -> q["t"] == md5("wonderland" + q["s"])
                }
                val body = if (!ok) """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}"""
                else if (ex.requestURI.path.endsWith("/tokenInfo.view")) """{"subsonic-response":{"status":"ok","tokenInfo":{"username":"Bob"}}}"""
                else if (ex.requestURI.path.endsWith("/getUser.view") && getUserBroken) "<html>502 Bad Gateway</html>"
                else if (ex.requestURI.path.endsWith("/getUser.view")) """{"subsonic-response":{"status":"ok","user":{"username":"${q["username"]}","adminRole":${q["username"]?.lowercase() == "alice"}}}}"""
                else """{"subsonic-response":{"status":"ok"}}"""
                val bytes = body.encodeToByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                if (!ex.requestURI.path.startsWith("/apis/")) synchronized(received) {
                    received += listOf(ex.requestMethod, ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: ""), ex.requestHeaders.getFirst("X-Api-Key"), body)
                }
                val (type, bytes) = when (ex.requestURI.path) {
                    "/apis/mlj_1/scrobbles" -> "application/json" to """{"status":"ok","list":[
                        {"time":1759600000,"track":{"artists":["Sia","Diplo"],"title":"Rewrite","album":{"albumtitle":"Rewrite"},"length":200},"duration":200},
                        {"time":1759600300,"track":{"artists":["Kongos"],"title":"Come with Me Now","length":210},"duration":null}]}""".encodeToByteArray()
                    "/api/v1/rootfolder" -> "application/json" to """[{"path":"/music/"}]""".encodeToByteArray()
                    "/api/v1/mediacover/artist/1/poster-250.jpg" -> "image/jpeg" to byteArrayOf(1, 2, 3)
                    else -> "application/json" to """{"ok":true}""".encodeToByteArray()
                }
                ex.responseHeaders.set("Content-Type", type)
                ex.responseHeaders.set("Cache-Control", "public, max-age=31536000")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val up = "http://127.0.0.1:${upstream.address.port}"
        dataDir = Files.createTempDirectory("tonearm-server").toFile()
        history = History(dataDir)
        server = TonearmServer(
            0, "/connect-tonearm", NavidromeAuth("http://127.0.0.1:${navidrome.address.port}/"), dataDir, "test",
            LidarrProxy(Upstream("Lidarr", up, "X-Api-Key", "lidarr-secret"), requestsForEveryone = true),
            history = history,
            malojaImport = { user, admin -> if (admin) history.importMaloja(user, up, "maloja-secret", http) },
        ).start()
    }

    @AfterTest
    fun stop() {
        server.stop()
        navidrome.stop(0)
        upstream.stop(0)
    }

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private fun alice(): List<Pair<String, String>> {
        val salt = (100000..999999).random().toString()
        return listOf("u" to "alice", "t" to md5("wonderland$salt"), "s" to salt, "v" to "1.16.1", "c" to "Tonearm", "f" to "json")
    }

    private fun call(
        path: String,
        params: List<Pair<String, Any>> = emptyList(),
        body: String = "",
        headers: Map<String, String> = emptyMap(),
        method: String = "POST",
    ): Pair<Int, String> {
        val query = params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v.toString(), Charsets.UTF_8) }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.boundPort}$path?$query"))
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
    }

    private val bob = listOf("apiKey" to "bobkey")

    private fun lidarr(method: String, path: String, login: List<Pair<String, String>>, params: List<Pair<String, Any>> = emptyList(), body: String = "") =
        call("/connect-tonearm/lidarr/api/v1/$path", login + params, body, method = method)

    private fun api(op: String, auth: List<Pair<String, String>>, params: List<Pair<String, Any>> = emptyList(), body: String = ""): JsonObject {
        val (code, text) = call("/connect-tonearm/api/$op", auth + params, body)
        assertEquals(200, code, text)
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun healthNeedsNoLogin() {
        assertEquals(200 to "ok", call("/connect-tonearm/health"))
    }

    @Test
    fun theApiNeedsAValidNavidromeLogin() {
        assertEquals(401, call("/connect-tonearm/api/hello").first)
        assertEquals(401, call("/connect-tonearm/api/hello", listOf("u" to "alice", "p" to "nope")).first)
        val hello = api("hello", alice())
        assertEquals(2, hello["protocol"]!!.jsonPrimitive.int)
        assertEquals("alice", hello["user"]!!.jsonPrimitive.content)
        assertEquals("tonearm", hello["server"]!!.jsonPrimitive.content)
        assertEquals("alice", api("hello", listOf("u" to "Alice", "p" to "enc:776f6e6465726c616e64"))["user"]!!.jsonPrimitive.content)
        assertEquals("bob", api("hello", listOf("apiKey" to "bobkey"))["user"]!!.jsonPrimitive.content)
    }

    @Test
    fun devicesAndCommandsStayWithTheirUser() {
        api("publish", alice(), listOf("device" to "desk"), """{"id":"desk","name":"Desk","kind":"desktop"}""")
        val devices = api("devices", alice())["devices"]!!.jsonArray
        assertEquals("desk", devices.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(api("devices", listOf("apiKey" to "bobkey"))["devices"]!!.jsonArray.isEmpty())

        api("send", alice(), listOf("device" to "phone", "target" to "desk"), """{"type":"pause"}""")
        assertTrue(api("poll", listOf("apiKey" to "bobkey"), listOf("device" to "desk", "after" to 0, "wait" to 0))["commands"]!!.jsonArray.isEmpty())
        val poll = api("poll", alice(), listOf("device" to "desk", "after" to 0, "wait" to 0))
        val command = poll["commands"]!!.jsonArray.single().jsonObject
        assertEquals("""{"type":"pause"}""", command["payload"]!!.jsonPrimitive.content)
        assertEquals("phone", command["from"]!!.jsonPrimitive.content)
        assertEquals(command["seq"]!!.jsonPrimitive.long, poll["seq"]!!.jsonPrimitive.long)
    }

    @Test
    fun theStoreTakesVersionedWrites() {
        val empty = api("get", alice(), listOf("key" to "pending-likes"))
        assertEquals(0, empty["version"]!!.jsonPrimitive.long)
        val put = api("put", alice(), listOf("key" to "pending-likes", "ifVersion" to 0), "[1]")
        assertEquals("true", put["ok"]!!.jsonPrimitive.content)
        val conflict = api("put", alice(), listOf("key" to "pending-likes", "ifVersion" to 0), "[2]")
        assertEquals("true", conflict["conflict"]!!.jsonPrimitive.content)
        assertEquals("[1]", conflict["value"]!!.jsonPrimitive.content)
        assertEquals("[1]", api("get", alice(), listOf("key" to "pending-likes"))["value"]!!.jsonPrimitive.content)
        assertEquals(400, call("/connect-tonearm/api/get", alice() + listOf("key" to "../x")).first)
    }

    @Test
    fun aProxyThatDropsThePrefixStillWorks() {
        assertEquals(200, call("/api/hello", alice()).first)
        assertEquals(404, call("/connect-tonearm/elsewhere").first)
    }

    @Test
    fun repeatedFailuresFromOneAddressAreTurnedAway() {
        repeat(20) { call("/connect-tonearm/api/hello", listOf("u" to "alice", "p" to "guess$it"), headers = mapOf("X-Real-IP" to "10.0.0.9")) }
        assertEquals(429, call("/connect-tonearm/api/hello", listOf("u" to "alice", "p" to "wonderland"), headers = mapOf("X-Real-IP" to "10.0.0.9")).first)
        assertEquals(200, call("/connect-tonearm/api/hello", listOf("u" to "alice", "p" to "wonderland"), headers = mapOf("X-Real-IP" to "10.0.0.10")).first)
    }

    @Test
    fun oneUsersFailuresDontLockOutOthersOnTheSameAddress() {
        val known = listOf("u" to "alice", "p" to "wonderland")
        val home = mapOf("X-Real-IP" to "10.0.0.9")
        assertEquals(200, call("/connect-tonearm/api/hello", known, headers = home).first)
        repeat(20) { call("/connect-tonearm/api/hello", listOf("u" to "alice", "p" to "guess$it"), headers = home) }
        assertEquals(429, call("/connect-tonearm/api/hello", alice(), headers = home).first)
        // Her login from before is still good, and bob behind the same address was never the one guessing.
        assertEquals(200, call("/connect-tonearm/api/hello", known, headers = home).first)
        assertEquals(200, call("/connect-tonearm/api/hello", bob, headers = home).first)
    }

    @Test
    fun aDeviceRetryingAnOldPasswordIsntGuessing() {
        val desk = mapOf("X-Real-IP" to "10.0.0.11")
        repeat(30) { assertEquals(401, call("/connect-tonearm/api/hello", listOf("u" to "alice", "p" to "old password"), headers = desk).first) }
        assertEquals(200, call("/connect-tonearm/api/hello", alice(), headers = desk).first)
    }

    @Test
    fun onlyTrustedProxiesSayWhoTheyForwardFor() {
        // Each guess claims another address; from a proxy that isn't trusted, they all count for the one they came from.
        val direct = TonearmServer(0, "/connect-tonearm", NavidromeAuth("http://127.0.0.1:${navidrome.address.port}/"), dataDir, "test", trustedProxies = emptyList()).start()
        try {
            fun hello(login: List<Pair<String, String>>, ip: String) = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${direct.boundPort}/connect-tonearm/api/hello?" + login.joinToString("&") { (k, v) -> "$k=$v" }))
                    .header("X-Real-IP", ip).header("X-Forwarded-For", ip).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).statusCode()
            repeat(20) { hello(listOf("u" to "alice", "p" to "guess$it"), "198.51.100.$it") }
            assertEquals(429, hello(listOf("u" to "alice", "p" to "wonderland"), "198.51.100.99"))
        } finally {
            direct.stop()
        }
        // Through a trusted proxy, the address it heard from counts: the last one it added, not what the client wrote first.
        repeat(20) { call("/connect-tonearm/api/hello", listOf("u" to "alice", "p" to "guess$it"), headers = mapOf("X-Forwarded-For" to "203.0.113.$it, 10.0.0.12")) }
        assertEquals(429, call("/connect-tonearm/api/hello", alice(), headers = mapOf("X-Forwarded-For" to "203.0.113.99, 10.0.0.12")).first)
        assertEquals(200, call("/connect-tonearm/api/hello", alice(), headers = mapOf("X-Forwarded-For" to "10.0.0.12, 10.0.0.13")).first)
    }

    @Test
    fun trustedProxiesAreAddressesNeverNames() {
        val docker = AddressRange.parse("172.16.0.0/12")!!
        assertTrue(docker.contains(java.net.InetAddress.getByName("172.31.255.1")))
        assertFalse(docker.contains(java.net.InetAddress.getByName("172.32.0.1")))
        assertFalse(docker.contains(java.net.InetAddress.getByName("::1")))
        assertTrue(AddressRange.parse("::1")!!.contains(java.net.InetAddress.getByName("::1")))
        assertTrue(AddressRange.parse("192.168.1.11")!!.contains(java.net.InetAddress.getByName("192.168.1.11")))
        assertFalse(AddressRange.parse("192.168.1.11")!!.contains(java.net.InetAddress.getByName("192.168.1.12")))
        for (bad in listOf("localhost", "proxy.lan", "999.1.1.1", "10.0.0.0/33", "dead.beef", "")) assertEquals(null, AddressRange.parse(bad), bad)
        assertEquals(3, AddressRange.parseList("127.0.0.1, 10.0.0.0/8 ::1").size)
        assertTrue(runCatching { AddressRange.parseList("npm") }.isFailure)
    }

    @Test
    fun anAdminIsntTakenForSomeoneElseWhenNavidromeHiccups() {
        val login = alice()
        getUserBroken = true
        assertEquals(502, call("/connect-tonearm/api/hello", login).first)
        getUserBroken = false
        assertEquals("true", api("hello", login)["admin"]!!.jsonPrimitive.content)
    }

    @Test
    fun devicesGetLessRoomThanTheRest() {
        val (code, text) = call("/connect-tonearm/api/publish", alice() + listOf("device" to "desk"), "x".repeat(TonearmServer.MAX_STATE + 1))
        assertEquals(413, code, text)
        assertEquals(404, call("/connect-tonearm/api/send", alice() + listOf("device" to "phone", "target" to "nobody"), """{"type":"pause"}""").first)
        repeat(Store.MAX_KEYS) { api("put", alice(), listOf("key" to "doc-$it", "ifVersion" to 0), "{}") }
        assertEquals(413, call("/connect-tonearm/api/put", alice() + listOf("key" to "one-more", "ifVersion" to 0), "{}").first)
    }

    @Test
    fun validLoginsAreRememberedForAWhile() {
        val login = listOf("u" to "alice", "p" to "wonderland")
        api("hello", login)
        val before = navidromeCalls
        api("devices", login)
        assertEquals(before, navidromeCalls)
    }

    @Test
    fun helloSaysWhatThisUserGets() {
        val alice = api("hello", alice())
        assertEquals("true", alice["admin"]!!.jsonPrimitive.content)
        assertEquals("true", alice["lidarr"]!!.jsonPrimitive.content)
        assertEquals("true", alice["lidarrAdmin"]!!.jsonPrimitive.content)
        assertEquals("true", alice["history"]!!.jsonPrimitive.content)
        assertEquals(null, alice["ai"])
        val bob = api("hello", bob)
        assertEquals("false", bob["admin"]!!.jsonPrimitive.content)
        assertEquals("true", bob["lidarr"]!!.jsonPrimitive.content)
        assertEquals("false", bob["lidarrAdmin"]!!.jsonPrimitive.content)
    }

    @Test
    fun adminsReachAllOfLidarrWithTheServersKeyAndWithoutTheirLogin() {
        assertEquals(200, lidarr("DELETE", "artist/5", alice(), listOf("deleteFiles" to true, "apikey" to "theirs")).first)
        val (method, path, key) = received.single()
        assertEquals("DELETE", method)
        assertEquals("/api/v1/artist/5?deleteFiles=true", path)
        assertEquals("lidarr-secret", key)
    }

    @Test
    fun everyoneElseCanLookAndRequestButNotChangeLidarr() {
        assertEquals(200, lidarr("GET", "queue", bob, listOf("page" to 1)).first)
        assertEquals(200, lidarr("GET", "album/lookup", bob, listOf("term" to "OK Computer")).first)
        assertEquals(200, lidarr("POST", "command", bob, body = """{"name":"AlbumSearch","albumIds":[3]}""").first)
        assertEquals(200, lidarr("PUT", "album/monitor", bob, body = """{"albumIds":[3],"monitored":true}""").first)
        for ((method, path, body) in listOf(
            Triple("DELETE", "artist/5", ""),
            Triple("GET", "importlist", ""),
            Triple("GET", "config/host", ""),
            Triple("POST", "command", """{"name":"ImportListSync","definitionId":1}"""),
            Triple("PUT", "album/monitor", """{"albumIds":[3],"monitored":false}"""),
            Triple("POST", "albumstudio", "{}"),
            Triple("POST", "tag", """{"label":"x"}"""),
        )) {
            val (code, text) = lidarr(method, path, bob, body = body)
            assertEquals(403, code, "$method $path")
            assertTrue("admins" in text)
        }
        assertEquals(4, received.size)
    }

    @Test
    fun requestsLandInLidarrsRootFoldersWithoutTags() {
        val artist = """{"artistName":"Radiohead","rootFolderPath":"/music","path":"/elsewhere/Radiohead","tags":[7],"qualityProfileId":1}"""
        assertEquals(200, lidarr("POST", "artist", bob, body = artist).first)
        val sent = Json.parseToJsonElement(received.last()[3]!!).jsonObject
        assertEquals(null, sent["path"])
        assertTrue(sent["tags"]!!.jsonArray.isEmpty())
        assertEquals(400, lidarr("POST", "artist", bob, body = """{"artistName":"x","rootFolderPath":"/etc"}""").first)
        assertEquals(400, lidarr("POST", "album", bob, body = """{"title":"x","artist":{"rootFolderPath":"/tmp"}}""").first)
        assertEquals(200, lidarr("POST", "album", bob, body = """{"title":"x","artist":{"rootFolderPath":"/music/","tags":[7]}}""").first)
        assertTrue(Json.parseToJsonElement(received.last()[3]!!).jsonObject["artist"]!!.jsonObject["tags"]!!.jsonArray.isEmpty())
    }

    @Test
    fun pathsCantClimbOutOfTheApi() {
        assertEquals(404, lidarr("GET", "../../ping", alice()).first)
        assertEquals(404, call("/connect-tonearm/lidarr/api/v1/mediacover/artist/1/..", bob, method = "GET").first)
        assertEquals(404, call("/connect-tonearm/lidarr/other", alice(), method = "GET").first)
        assertTrue(received.none { it[1]!!.contains("ping") })
    }

    @Test
    fun coversComeThroughAsImages() {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.boundPort}/connect-tonearm/lidarr/api/v1/mediacover/artist/1/poster-250.jpg?apiKey=bobkey")).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, response.statusCode())
        assertEquals("image/jpeg", response.headers().firstValue("Content-Type").get())
        assertEquals(3, response.body().size)
    }

    @Test
    fun playsGoIntoTheHistoryOnceAndComeBackAsListening() {
        val now = System.currentTimeMillis()
        val plays = """[
            {"at":${now - 60_000},"artist":"Kongos","title":"Come with Me Now","durationMs":210000,"listenedMs":210000,"source":"youtube"},
            {"at":${now - 30_000},"artist":"Kongos","title":"I'm Only Joking","durationMs":200000,"listenedMs":4000,"source":"youtube"},
            {"at":${now - 10_000},"artist":"Portishead feat. Someone","title":"Roads","durationMs":300000,"listenedMs":250000}]"""
        assertEquals(3, api("played", bob, body = plays)["added"]!!.jsonPrimitive.content.toInt())
        // An app sending them again (it didn't hear back) adds nothing.
        assertEquals(0, api("played", bob, body = plays)["added"]!!.jsonPrimitive.content.toInt())
        val listening = api("listening", bob, listOf("days" to 30, "songs" to 5, "recent" to 5))
        assertEquals(2, listening["plays"]!!.jsonPrimitive.content.toInt())
        val artists = listening["artists"]!!.jsonArray.map { it.jsonObject }
        // As many plays each: the latest first. Kongos' second song was a skip.
        assertEquals(listOf("Portishead", "Kongos"), artists.map { it["artist"]!!.jsonPrimitive.content })
        assertEquals("1", artists.last()["skips"]!!.jsonPrimitive.content)
        assertEquals("Roads", listening["recent"]!!.jsonArray.first().jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(400, call("/connect-tonearm/api/played", bob, "not a list").first)
    }

    @Test
    fun saidNoStaysUntilTheyPlayThemAgain() {
        api("dismiss", bob, listOf("artist" to "Mazzy Star"))
        assertTrue(history.isDismissed("bob", "mazzy star"))
        assertTrue(history.isDismissed("bob", "Mazzy Star", "So Tonight That I Might See"))
        val now = System.currentTimeMillis()
        api("played", bob, body = (1..3).joinToString(",", "[", "]") { """{"at":${now + it},"artist":"Mazzy Star","title":"Fade Into You $it","durationMs":290000,"listenedMs":290000}""" })
        assertFalse(history.isDismissed("bob", "Mazzy Star"))
        api("dismiss", bob, listOf("artist" to "Tricky", "album" to "Maxinquaye"))
        assertTrue(history.isDismissed("bob", "Tricky", "Maxinquaye (Deluxe Edition)"))
        assertFalse(history.isDismissed("bob", "Tricky", "Pre-Millennium Tension"))
        assertEquals(400, call("/connect-tonearm/api/dismiss", bob).first)
    }

    @Test
    fun dislikedSongsComeBackWithTheArtistsSaidNoTo() {
        api("dislike", bob, listOf("artist" to "Kongos feat. Someone", "title" to "Come with Me Now (Remastered)", "album" to "Lunatic"))
        api("dismiss", bob, listOf("artist" to "Mazzy Star"))
        api("dismiss", bob, listOf("artist" to "Tricky", "album" to "Maxinquaye"))
        val disliked = api("disliked", bob)
        val song = disliked["songs"]!!.jsonArray.single().jsonObject
        assertEquals("Come with Me Now (Remastered)", song["title"]!!.jsonPrimitive.content)
        assertEquals(listOf("Mazzy Star"), disliked["artists"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(history.isSongDisliked("bob", "Kongos", "Come With Me Now"))
        api("dislike", bob, listOf("artist" to "Kongos", "title" to "Come with Me Now", "on" to false))
        assertTrue(api("disliked", bob)["songs"]!!.jsonArray.isEmpty())
        assertEquals(400, call("/connect-tonearm/api/dislike", bob + listOf("artist" to "Kongos")).first)
    }

    @Test
    fun songsAreKeyedLikeTheAppsKeyThem() {
        // A YouTube Music copy is the library's song, so un-disliking either takes the dislike back.
        api("dislike", bob, listOf("artist" to "Queen", "title" to "Bohemian Rhapsody - Remastered 2011"))
        assertTrue(history.isSongDisliked("bob", "Queen", "Bohemian Rhapsody"))
        assertTrue(history.isSongDisliked("bob", "QueenVEVO", "Bohemian Rhapsody (Official Video)"))
        api("dislike", bob, listOf("artist" to "Queen & David Bowie", "title" to "Bohemian Rhapsody", "on" to false))
        assertTrue(history.dislikedSongs("bob").isEmpty())
        // A live version is a song of its own.
        api("dislike", bob, listOf("artist" to "Johnny Cash", "title" to "Hurt (Live)"))
        assertFalse(history.isSongDisliked("bob", "Johnny Cash", "Hurt"))
        api("dislike", bob, listOf("artist" to "Johnny Cash", "title" to "Hurt"))
        assertEquals(2, history.dislikedSongs("bob").size)
    }

    @Test
    fun malojasHistoryComesOverOnceForAnAdmin() {
        api("hello", bob)
        assertTrue(history.isEmpty("bob"))
        api("hello", alice())
        val listening = history.listening("alice", 0, artists = 10, songs = 10, recent = 10)
        assertEquals(listOf("Sia", "Kongos"), listening.artists.map { it.artist }.sorted().reversed())
        assertEquals("Rewrite", listening.songs.first { it.artist == "Sia" }.album)
        assertEquals(null, history.importMaloja("alice", "http://127.0.0.1:${upstream.address.port}", null, http))
    }

    @Test
    fun listeningGoesByWeekWithAlbumsAndPages() {
        val start = 1_760_000_000_000L
        val plays = (0 until 6).joinToString(",", "[", "]") { i ->
            val album = if (i == 5) "" else ""","album":"Tones${if (i % 2 == 0) " (Deluxe Edition)" else ""}""""
            """{"at":${start + i * 3_600_000L},"artist":"The Oscillators","title":"Song $i"$album,"durationMs":120000,"listenedMs":120000,"source":"${if (i == 4) "youtube" else "library"}"}"""
        }
        api("played", bob, body = plays)
        val week = api("listening", bob, listOf("from" to start, "to" to start + 5 * 3_600_000L, "artists" to 10, "albums" to 10, "recent" to 2))
        assertEquals(start + 5 * 3_600_000L, week["to"]!!.jsonPrimitive.long)
        assertEquals(5, week["plays"]!!.jsonPrimitive.int)
        assertEquals(10, week["minutes"]!!.jsonPrimitive.int)
        val album = week["albums"]!!.jsonArray.single().jsonObject
        assertEquals(5, album["plays"]!!.jsonPrimitive.int)
        val recent = week["recent"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Song 4", "Song 3"), recent.map { it["title"]!!.jsonPrimitive.content })
        assertEquals("youtube", recent.first()["source"]!!.jsonPrimitive.content)
        assertEquals("Tones (Deluxe Edition)", recent.first()["album"]!!.jsonPrimitive.content)
        val before = recent.last()["at"]!!.jsonPrimitive.long
        val next = api("listening", bob, listOf("from" to start, "to" to start + 5 * 3_600_000L, "recent" to 2, "before" to before))
        assertEquals(listOf("Song 2", "Song 1"), next["recent"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content })
        // The old way still works: everything there is.
        assertEquals(6, api("listening", bob, listOf("days" to 100_000))["plays"]!!.jsonPrimitive.int)
    }

    @Test
    fun navidromeBeingDownIsABadGateway() {
        navidrome.stop(0)
        assertEquals(502, call("/connect-tonearm/api/hello", alice()).first)
    }
}

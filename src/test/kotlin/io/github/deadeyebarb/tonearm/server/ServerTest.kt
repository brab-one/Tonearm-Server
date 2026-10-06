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
import kotlin.test.assertTrue

/**
 * The whole server against a fake Navidrome that knows alice (password "wonderland", an admin) and bob
 * (API key "bobkey"), and a fake Lidarr and Maloja that record what reaches them.
 */
class ServerTest {
    private lateinit var navidrome: HttpServer
    private lateinit var server: TonearmServer
    private val http = HttpClient.newHttpClient()
    private var navidromeCalls = 0
    private lateinit var upstream: HttpServer
    /** What reached the fake Lidarr/Maloja: method, path?query, X-Api-Key, body. */
    private val received = mutableListOf<List<String?>>()

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
                synchronized(received) {
                    received += listOf(ex.requestMethod, ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: ""), ex.requestHeaders.getFirst("X-Api-Key"), body)
                }
                val (type, bytes) = when (ex.requestURI.path) {
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
        server = TonearmServer(
            0, "/connect-tonearm", NavidromeAuth("http://127.0.0.1:${navidrome.address.port}/"), Files.createTempDirectory("tonearm-server").toFile(), "test",
            LidarrProxy(Upstream("Lidarr", up, "X-Api-Key", "lidarr-secret"), requestsForEveryone = true),
            MalojaProxy(Upstream("Maloja", up, null, "maloja-secret"), "maloja-secret", emptySet()),
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
        assertEquals("true", alice["maloja"]!!.jsonPrimitive.content)
        val bob = api("hello", bob)
        assertEquals("false", bob["admin"]!!.jsonPrimitive.content)
        assertEquals("true", bob["lidarr"]!!.jsonPrimitive.content)
        assertEquals("false", bob["lidarrAdmin"]!!.jsonPrimitive.content)
        assertEquals("false", bob["maloja"]!!.jsonPrimitive.content)
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
    fun malojaGetsItsKeyAndIsOnlyForItsUsers() {
        assertEquals(200, call("/connect-tonearm/maloja/apis/mlj_1/charts/artists", alice() + listOf("max" to 5), method = "GET").first)
        assertTrue(received.last()[1]!!.endsWith("?max=5&key=maloja-secret"))
        assertEquals(200, call("/connect-tonearm/maloja/apis/mlj_1/newscrobble", alice(), """{"artists":["a"],"title":"b","key":""}""").first)
        assertEquals("maloja-secret", Json.parseToJsonElement(received.last()[3]!!).jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals(403, call("/connect-tonearm/maloja/apis/mlj_1/charts/artists", bob, method = "GET").first)
    }

    @Test
    fun navidromeBeingDownIsABadGateway() {
        navidrome.stop(0)
        assertEquals(502, call("/connect-tonearm/api/hello", alice()).first)
    }
}

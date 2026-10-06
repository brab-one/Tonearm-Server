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

/** The whole server against a fake Navidrome that knows alice (password "wonderland") and bob (API key "bobkey"). */
class ServerTest {
    private lateinit var navidrome: HttpServer
    private lateinit var server: TonearmServer
    private val http = HttpClient.newHttpClient()
    private var navidromeCalls = 0

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
                else """{"subsonic-response":{"status":"ok"}}"""
                val bytes = body.encodeToByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        server = TonearmServer(0, "/connect-tonearm", NavidromeAuth("http://127.0.0.1:${navidrome.address.port}/"), Files.createTempDirectory("tonearm-server").toFile(), "test").start()
    }

    @AfterTest
    fun stop() {
        server.stop()
        navidrome.stop(0)
    }

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private fun alice(): List<Pair<String, String>> {
        val salt = (100000..999999).random().toString()
        return listOf("u" to "alice", "t" to md5("wonderland$salt"), "s" to salt, "v" to "1.16.1", "c" to "Tonearm", "f" to "json")
    }

    private fun call(path: String, params: List<Pair<String, Any>> = emptyList(), body: String = "", headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val query = params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v.toString(), Charsets.UTF_8) }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.boundPort}$path?$query"))
            .POST(HttpRequest.BodyPublishers.ofString(body)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
    }

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
    fun navidromeBeingDownIsABadGateway() {
        navidrome.stop(0)
        assertEquals(502, call("/connect-tonearm/api/hello", alice()).first)
    }
}

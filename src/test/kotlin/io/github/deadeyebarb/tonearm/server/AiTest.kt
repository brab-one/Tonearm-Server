package io.github.deadeyebarb.tonearm.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Each kind of AI against a fake of its API. */
class AiTest {
    private lateinit var fake: HttpServer
    private val requests = mutableListOf<Pair<String, JsonObject>>()
    private var openAiTakesSchemas = true
    private var claudeStop = "end_turn"
    private val url get() = "http://127.0.0.1:${fake.address.port}"

    private val schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("answer") { put("type", "string") } }
    }

    private fun answer(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.encodeToByteArray()
        ex.responseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @BeforeTest
    fun start() {
        fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = Json.parseToJsonElement(ex.requestBody.readAllBytes().decodeToString()).jsonObject
                synchronized(requests) { requests += ex.requestURI.path to body }
                when (ex.requestURI.path) {
                    "/v1/chat/completions" -> {
                        val format = body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content
                        if (format == "json_schema" && !openAiTakesSchemas) answer(ex, 400, """{"error":{"message":"response_format json_schema is not supported"}}""")
                        else answer(ex, 200, """{"choices":[{"message":{"role":"assistant","content":"Here you go:\n```json\n{\"answer\":\"yes\"}\n```"}}]}""")
                    }
                    "/v1/messages" -> answer(ex, 200, """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                        "content":[{"type":"thinking","thinking":"","signature":"x"},{"type":"text","text":"{\"answer\":\"yes\"}"}],
                        "stop_reason":"$claudeStop","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}""")
                    "/api/chat" -> answer(ex, 200, """{"message":{"role":"assistant","content":"{\"answer\":\"yes\"}"}}""")
                    else -> answer(ex, 404, "{}")
                }
            }
            start()
        }
    }

    @AfterTest
    fun stop() = fake.stop(0)

    @Test
    fun ollamaGetsTheSchemaAsItsFormat() {
        assertEquals("""{"answer":"yes"}""", OllamaAi(url, "qwen2.5").json("sys", "hi", schema, 0.5))
        val (_, body) = requests.single()
        assertEquals(schema, body["format"])
        assertEquals("0.5", body["options"]!!.jsonObject["temperature"]!!.jsonPrimitive.content)
    }

    @Test
    fun openAiStyleApisGetASchemaOrPlainJsonFromTheOnesThatDontTakeOne() {
        val ai = OpenAiCompatible("$url/v1", "sk-test", "some-model")
        assertEquals("""{"answer":"yes"}""", ai.json("sys", "hi", schema, 0.5))
        val first = requests.single().second
        assertEquals("some-model", first["model"]!!.jsonPrimitive.content)
        val sent = first["response_format"]!!.jsonObject["json_schema"]!!.jsonObject["schema"]!!.jsonObject
        assertEquals("false", sent["additionalProperties"]!!.jsonPrimitive.content)
        assertEquals(null, first["temperature"])
        openAiTakesSchemas = false
        requests.clear()
        assertEquals("""{"answer":"yes"}""", ai.json("sys", "hi", schema, 0.5))
        assertEquals(listOf("json_schema", "json_object"), requests.map { it.second["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun claudeAnswersWithStructuredOutput() {
        val ai = ClaudeAi("sk-ant-test", ClaudeAi.DEFAULT_MODEL, url)
        assertEquals("""{"answer":"yes"}""", ai.json("You are a critic.", "hi", schema, 0.8))
        val body = requests.single().second
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("You are a critic.", body["system"]!!.jsonPrimitive.content)
        val format = body["output_config"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals("false", format["schema"]!!.jsonObject["additionalProperties"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals(null, body["temperature"])
        assertEquals("hi", body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
        claudeStop = "refusal"
        assertFailsWith<IllegalStateException> { ai.json("sys", "hi", schema, 0.8) }
    }

    @Test
    fun theAiComesFromTheEnvironment() {
        fun from(vararg vars: Pair<String, String>) = Ai.fromEnv { name -> vars.toMap()[name] }
        assertEquals(null, from())
        assertEquals("qwen2.5" to "Ollama", from("OLLAMA_URL" to url).let { it!!.model to it.name })
        assertEquals("llama3", from("AI_PROVIDER" to "ollama", "AI_URL" to url, "AI_MODEL" to "llama3")!!.model)
        assertEquals("gpt-x", from("AI_PROVIDER" to "openai", "AI_MODEL" to "gpt-x")!!.model)
        assertEquals("claude-opus-5-5", from("AI_PROVIDER" to "claude", "AI_API_KEY" to "k")!!.model)
        assertTrue(runCatching { from("AI_PROVIDER" to "claude") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { from("AI_PROVIDER" to "openai") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { from("AI_PROVIDER" to "skynet") }.exceptionOrNull() is IllegalArgumentException)
    }
}

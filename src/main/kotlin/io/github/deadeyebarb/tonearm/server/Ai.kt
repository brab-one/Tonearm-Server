package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A language model that answers with JSON fitting a schema: Ollama, anything with OpenAI's API, or Claude. */
interface Ai {
    /** Who answers, for messages ("Ollama", "Claude"). */
    val name: String
    val model: String

    /** The model's answer to [prompt] as JSON text fitting [schema]. [temperature] where the model takes one. */
    fun json(system: String, prompt: String, schema: JsonObject, temperature: Double): String

    companion object {
        /**
         * The AI from the environment: AI_PROVIDER (ollama, openai or claude), AI_URL, AI_API_KEY and AI_MODEL.
         * OLLAMA_URL (+ OLLAMA_MODEL) alone still means Ollama. Null when none is set up; throws on settings
         * that can't work, with what to fix.
         */
        fun fromEnv(env: (String) -> String?): Ai? {
            val provider = env("AI_PROVIDER")?.lowercase() ?: if (env("OLLAMA_URL") != null || env("AI_URL") != null) "ollama" else return null
            val url = env("AI_URL")
            val key = env("AI_API_KEY")
            val model = env("AI_MODEL")
            return when (provider) {
                "ollama" -> OllamaAi(
                    url ?: env("OLLAMA_URL") ?: throw IllegalArgumentException("Set AI_URL to Ollama's address, e.g. http://192.168.1.11:11434"),
                    model ?: env("OLLAMA_MODEL") ?: "qwen2.5",
                )
                "openai" -> OpenAiCompatible(
                    url ?: "https://api.openai.com/v1",
                    key,
                    model ?: throw IllegalArgumentException("Set AI_MODEL to the model to ask (the provider's name for it)"),
                )
                "claude", "anthropic" -> ClaudeAi(
                    key ?: throw IllegalArgumentException("Set AI_API_KEY to an Anthropic API key (console.anthropic.com → API keys)"),
                    model ?: ClaudeAi.DEFAULT_MODEL,
                    url,
                )
                else -> throw IllegalArgumentException("AI_PROVIDER is \"$provider\"; use ollama, openai or claude")
            }
        }

        /** [schema] with `additionalProperties: false` on every object, which strict structured output wants. */
        fun closed(schema: JsonElement): JsonElement = when (schema) {
            is JsonObject -> JsonObject(
                schema.mapValues { (_, v) -> closed(v) } + if (schema["type"]?.jsonPrimitive?.contentOrNull == "object") mapOf("additionalProperties" to JsonPrimitive(false)) else emptyMap(),
            )
            is JsonArray -> JsonArray(schema.map(::closed))
            else -> schema
        }
    }
}

private val aiJson = Json { ignoreUnknownKeys = true }
private val defaultHttp: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

private fun post(http: HttpClient, url: String, body: JsonObject, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(15))
        .header("Content-Type", "application/json")
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build()
    return http.send(request, HttpResponse.BodyHandlers.ofString())
}

/** Ollama's chat API with its structured output (`format`); local, no key. */
class OllamaAi(private val url: String, override val model: String, private val http: HttpClient = defaultHttp) : Ai {
    override val name = "Ollama"

    override fun json(system: String, prompt: String, schema: JsonObject, temperature: Double): String {
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
            }
            put("format", schema)
            putJsonObject("options") { put("temperature", temperature); put("num_ctx", 8192) }
        }
        val response = post(http, url.trimEnd('/') + "/api/chat", body)
        if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().take(200)}")
        return aiJson.parseToJsonElement(response.body()).jsonObject["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("no answer")
    }
}

/**
 * Anything that speaks OpenAI's chat completions API: OpenAI itself, OpenRouter, Gemini, Groq, Mistral, LM Studio,
 * llama.cpp's server, vLLM and the like. Asks for JSON fitting the schema, and for plain JSON from the ones that
 * don't take a schema. No temperature: some models only take their own.
 */
class OpenAiCompatible(
    private val url: String,
    private val key: String?,
    override val model: String,
    private val http: HttpClient = defaultHttp,
) : Ai {
    override val name = "The AI"

    override fun json(system: String, prompt: String, schema: JsonObject, temperature: Double): String {
        val formats = listOf(
            buildJsonObject {
                put("type", "json_schema")
                putJsonObject("json_schema") { put("name", "answer"); put("schema", Ai.closed(schema)) }
            },
            buildJsonObject { put("type", "json_object") },
            null,
        )
        var last = ""
        for (format in formats) {
            val body = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    add(buildJsonObject { put("role", "system"); put("content", "$system\nThe answer has to fit this JSON schema: $schema") })
                    add(buildJsonObject { put("role", "user"); put("content", prompt) })
                }
                if (format != null) put("response_format", format)
            }
            val response = post(http, url.trimEnd('/') + "/chat/completions", body, key?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty())
            last = "HTTP ${response.statusCode()}: ${response.body().take(200)}"
            // A 400 is often just a response_format it doesn't know: try the next, plainer one.
            if (response.statusCode() == 400) continue
            if (response.statusCode() !in 200..299) throw IllegalStateException(last)
            val content = aiJson.parseToJsonElement(response.body()).jsonObject["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("no answer")
            return jsonIn(content)
        }
        throw IllegalStateException(last)
    }

    companion object {
        /** The JSON object in [text], also when a model wraps it in a code fence or a sentence. */
        fun jsonIn(text: String): String {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            return if (start in 0 until end) text.substring(start, end + 1) else text
        }
    }
}

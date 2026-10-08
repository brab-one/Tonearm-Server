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

/** A model's answer, and what it took, for the log. */
data class AiAnswer(
    /** The JSON. */
    val text: String,
    val promptTokens: Int? = null,
    /** Everything it wrote, thinking included. */
    val outputTokens: Int? = null,
    /** How long its thinking was (characters): 0 when it didn't think, null when it can't be told. */
    val thinkingChars: Int? = null,
    /** Why it stopped ("stop", "length", "end_turn"…). */
    val stop: String? = null,
)

/** A language model that answers with JSON fitting a schema: Ollama, anything with OpenAI's API, or Claude. */
interface Ai {
    /** Who answers, for messages ("Ollama", "Claude"). */
    val name: String
    val model: String

    /**
     * The model's answer to [prompt] as JSON text fitting [schema]. [temperature] where the model takes one. With
     * [think], an Ollama model that can think does so before answering (slower, and it remembers more of what it
     * knows); other AIs decide that by themselves.
     */
    fun answer(system: String, prompt: String, schema: JsonObject, temperature: Double, think: Boolean = false): AiAnswer

    /** Just the JSON of [answer], without thinking. */
    fun json(system: String, prompt: String, schema: JsonObject, temperature: Double): String = answer(system, prompt, schema, temperature).text

    /** Gets the model ready ahead of the first question, where that's a thing (Ollama downloads it). */
    fun prepare() {}

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
                    pull = env("AI_PULL")?.lowercase() != "off",
                    context = env("AI_CONTEXT")?.let { it.toIntOrNull()?.takeIf { n -> n >= 2048 } ?: throw IllegalArgumentException("AI_CONTEXT is \"$it\"; give the context length in tokens, e.g. 16384") }
                        ?: OllamaAi.DEFAULT_CONTEXT,
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

/**
 * Ollama's chat API with its structured output (`format`); local, no key. With [pull] the model is downloaded
 * into Ollama when it doesn't have it yet, so changing the model is only a setting. Every question uses the same
 * [context] length (tokens), since another one makes Ollama load the model again.
 */
class OllamaAi(
    private val url: String,
    override val model: String,
    private val http: HttpClient = defaultHttp,
    private val pull: Boolean = true,
    private val context: Int = DEFAULT_CONTEXT,
) : Ai {
    override val name = "Ollama"
    private val base = url.trimEnd('/')
    @Volatile private var present = false
    private val pulling = Any()
    /** False once Ollama said this model can't think: it's asked without from then on. */
    @Volatile private var canThink = true

    override fun prepare() {
        if (pull) Thread({ runCatching { ensureModel() }.onFailure { System.err.println("Ollama: ${it.message}") } }, "ollama-pull").apply { isDaemon = true }.start()
    }

    override fun answer(system: String, prompt: String, schema: JsonObject, temperature: Double, think: Boolean): AiAnswer {
        if (pull) ensureModel()
        fun body(thinking: Boolean?) = buildJsonObject {
            put("model", model)
            put("stream", false)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
            }
            put("format", schema)
            // Said either way: a model that thinks by itself would otherwise also think for a quick search.
            thinking?.let { put("think", it) }
            putJsonObject("options") { put("temperature", temperature); put("num_ctx", context) }
        }
        val thinking = think && canThink
        fun ask() = post(http, "$base/api/chat", body(thinking.takeIf { canThink }))
        var response = ask()
        if (response.statusCode() == 404 && pull) {
            // Removed from Ollama since: get it again.
            present = false
            ensureModel()
            response = ask()
        }
        // Ollama looks for the model before it checks what it can do, so this comes after the download.
        if (response.statusCode() == 400 && canThink && "does not support thinking" in response.body()) {
            // A model that can't think says so; it answers all the same without.
            canThink = false
            response = post(http, "$base/api/chat", body(null))
        }
        if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().take(200)}")
        val reply = aiJson.parseToJsonElement(response.body()).jsonObject
        val message = reply["message"]?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("no answer")
        return AiAnswer(
            OpenAiCompatible.jsonIn(content),
            promptTokens = reply.int("prompt_eval_count"),
            outputTokens = reply.int("eval_count"),
            thinkingChars = (message["thinking"] as? JsonPrimitive)?.contentOrNull?.length ?: if (thinking && canThink) 0 else null,
            stop = (reply["done_reason"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    /** Downloads the model into Ollama unless it's there (one download at a time; a question waits for it). */
    private fun ensureModel() {
        if (present) return
        synchronized(pulling) {
            if (present) return
            val show = post(http, "$base/api/show", buildJsonObject { put("model", model) })
            if (show.statusCode() in 200..299) {
                present = true
                return
            }
            if (show.statusCode() != 404) throw IllegalStateException("Ollama answered HTTP ${show.statusCode()} about $model")
            println("Ollama doesn't have $model yet; downloading it")
            val request = HttpRequest.newBuilder(URI.create("$base/api/pull"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildJsonObject { put("model", model) }.toString()))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
            if (response.statusCode() !in 200..299) throw IllegalStateException("Ollama couldn't download $model (HTTP ${response.statusCode()})")
            var shown = -1L
            response.body().use { lines ->
                for (line in lines) {
                    val status = runCatching { aiJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    status["error"]?.jsonPrimitive?.contentOrNull?.let { throw IllegalStateException("Ollama couldn't download $model: $it") }
                    val total = status["total"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
                    val done = status["completed"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
                    // A line per 10% of each part is plenty for the log.
                    if (total > 100_000_000) {
                        val tenth = done * 10 / total
                        if (tenth != shown) {
                            shown = tenth
                            println(String.format(java.util.Locale.ROOT, "Downloading %s: %.1f of %.1f GB", model, done / 1e9, total / 1e9))
                        }
                    }
                    if (status["status"]?.jsonPrimitive?.contentOrNull == "success") {
                        println("Ollama has $model now")
                        present = true
                        return
                    }
                }
            }
            throw IllegalStateException("Ollama stopped downloading $model before it was done")
        }
    }

    companion object {
        /** Room for the listening the picks are made from, the model's thinking and its answer. */
        const val DEFAULT_CONTEXT = 16_384
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

    /** [think] isn't passed on: models that reason do so by themselves here. */
    override fun answer(system: String, prompt: String, schema: JsonObject, temperature: Double, think: Boolean): AiAnswer {
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
            val reply = aiJson.parseToJsonElement(response.body()).jsonObject
            val choice = reply["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val message = choice?.get("message")?.jsonObject
            val content = message?.get("content")?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("no answer")
            val usage = reply["usage"] as? JsonObject
            // Some providers hand the reasoning back (DeepSeek, OpenRouter); otherwise only its token count says it happened.
            val reasoning = (message["reasoning_content"] ?: message["reasoning"])?.let { (it as? JsonPrimitive)?.contentOrNull }
            return AiAnswer(
                jsonIn(content),
                promptTokens = usage?.int("prompt_tokens"),
                outputTokens = usage?.int("completion_tokens"),
                thinkingChars = reasoning?.length,
                stop = choice["finish_reason"]?.let { (it as? JsonPrimitive)?.contentOrNull },
            )
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

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

package io.github.deadeyebarb.tonearm.server

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Duration

/**
 * Claude, through Anthropic's SDK, with structured output so the answer fits the schema. [baseUrl] only for a
 * proxy in between (and tests). Claude picks its own sampling, so the temperature isn't sent.
 */
class ClaudeAi(apiKey: String, override val model: String, baseUrl: String? = null) : Ai {
    override val name = "Claude"

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .timeout(Duration.ofMinutes(10))
        .build()

    override fun json(system: String, prompt: String, schema: JsonObject, temperature: Double): String {
        val format = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder().apply {
                (Ai.closed(schema) as JsonObject).forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(plain(v))) }
            }.build())
            .build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .addUserMessage(prompt)
            .outputConfig(OutputConfig.builder().format(format).build())
            .apply {
                // Should a safety check turn the request down, the API answers with a model it routes to instead.
                if (model in FALLBACK_MODELS) {
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()
        val message = client.messages().create(params)
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) throw IllegalStateException("Claude declined to answer")
        if (message.stopReason().orElse(null) == StopReason.MAX_TOKENS) throw IllegalStateException("Claude's answer was cut off")
        return message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
            .ifBlank { throw IllegalStateException("no answer") }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"

        /** The models that take the API's `fallbacks: "default"`. */
        private val FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

        /** kotlinx JSON as plain maps, lists and values for the SDK's JsonValue. */
        private fun plain(element: JsonElement): Any? = when (element) {
            is JsonObject -> element.mapValues { plain(it.value) }
            is JsonArray -> element.map(::plain)
            JsonNull -> null
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.booleanOrNull
                element.longOrNull != null -> element.longOrNull
                else -> element.doubleOrNull
            }
        }
    }
}

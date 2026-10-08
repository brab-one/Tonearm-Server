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

    /**
     * [think] isn't passed on: the current models (Opus 5/5.5, Sonnet 5/5.5, Fable) think as much as they see fit by
     * themselves, while older ones (4.x, Haiku 4.5) answer without thinking.
     */
    override fun answer(system: String, prompt: String, schema: JsonObject, temperature: Double, think: Boolean): AiAnswer {
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
        val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
            .ifBlank { throw IllegalStateException("no answer") }
        return AiAnswer(
            text,
            promptTokens = message.usage().inputTokens().toInt(),
            outputTokens = message.usage().outputTokens().toInt(),
            thinkingChars = thinkingChars(message.content().mapNotNull { block -> block.thinking().orElse(null)?.thinking() }),
            stop = message.stopReason().orElse(null)?.toString(),
        )
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"

        /** No thinking blocks: it didn't think. Blocks without their text (the API can leave it out): it did, but how much can't be told. */
        private fun thinkingChars(blocks: List<String>): Int? = when {
            blocks.isEmpty() -> 0
            blocks.all { it.isEmpty() } -> null
            else -> blocks.sumOf { it.length }
        }

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

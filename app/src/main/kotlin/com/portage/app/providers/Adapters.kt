package com.portage.app.providers

import com.portage.app.core.json.networkJson
import com.portage.app.core.net.SseEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

private fun parse(data: String): JsonElement = networkJson.parseToJsonElement(data)
private fun JsonObject.field(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intField(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

// ---------------------------------------------------------------------------

/**
 * OpenAI's `chat/completions` SSE shape, also used for the Ollama local
 * endpoint (which exposes the same protocol at `/v1`).
 */
class OpenAiCompatAdapter(
    client: OkHttpClient,
    override val kind: ProviderKind,
) : HttpProviderAdapter(client) {

    override fun stream(
        config: ProviderConfig,
        apiKey: String?,
        request: ChatRequest,
    ): Flow<ChatEvent> {
        val body = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            putJsonArray("messages") {
                request.system?.let { sys ->
                    addJsonObject {
                        put("role", Role.SYSTEM.wire)
                        put("content", sys)
                    }
                }
                request.messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role.wire)
                        put("content", m.content)
                    }
                }
            }
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            // Both OpenAI and Ollama honour this; without it local models report
            // no token counts, which breaks cost tracking for the free tier.
            putJsonObject("stream_options") { put("include_usage", true) }
        }

        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply { apiKey?.takeIf { it.isNotBlank() }?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

        return sse(httpRequest, decode = { event ->
            val data = event.data
            if (data == "[DONE]") return@sse

            val root = parse(data).jsonObject
            root["choices"]?.jsonArray?.forEach { choice ->
                val delta = choice.jsonObject["delta"]?.jsonObject ?: return@forEach
                delta.field("content")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(ChatEvent.TextDelta(it)) }
                // Ollama calls it `reasoning`, DeepSeek `reasoning_content`.
                (delta.field("reasoning") ?: delta.field("reasoning_content"))
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(ChatEvent.ReasoningDelta(it)) }
            }

            root["usage"]?.jsonObject?.let { usage ->
                val input = usage.intField("prompt_tokens") ?: return@let
                val output = usage.intField("completion_tokens") ?: return@let
                emit(ChatEvent.Usage(input, output))
            }
        })
    }
}

// ---------------------------------------------------------------------------

class AnthropicAdapter(client: OkHttpClient) : HttpProviderAdapter(client) {

    override val kind: ProviderKind = ProviderKind.ANTHROPIC

    override fun stream(
        config: ProviderConfig,
        apiKey: String?,
        request: ChatRequest,
    ): Flow<ChatEvent> {
        val body = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            // Required by the API; there is no "omit and let the model decide".
            put("max_tokens", request.maxTokens ?: 4096)
            request.system?.let { put("system", it) }
            request.temperature?.let { put("temperature", it) }
            putJsonArray("messages") {
                request.messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role.wire)
                        put("content", m.content)
                    }
                }
            }
        }

        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/v1/messages")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                apiKey?.takeIf { it.isNotBlank() }?.let { addHeader("x-api-key", it) }
                addHeader("anthropic-version", "2023-06-01")
            }
            .build()

        var inputTokens = 0

        return sse(httpRequest, decode = { event ->
            val data = event.data
            val root = try {
                parse(data).jsonObject
            } catch (e: Exception) {
                return@sse
            }

            when (root.field("type")) {
                "content_block_delta" -> {
                    val delta = root["delta"]?.jsonObject ?: return@sse
                    if (delta.field("type") == "text_delta") {
                        delta.field("text")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { emit(ChatEvent.TextDelta(it)) }
                    }
                }

                "message_start" -> {
                    root["message"]?.jsonObject?.get("usage")?.jsonObject?.let { usage ->
                        inputTokens = usage.intField("input_tokens") ?: inputTokens
                    }
                }

                "message_delta" -> {
                    val output = root["usage"]?.jsonObject?.intField("output_tokens") ?: 0
                    emit(ChatEvent.Usage(inputTokens, output))
                }

                "error" -> {
                    val message = root["error"]?.jsonObject?.field("message") ?: "Provider error"
                    emit(ChatEvent.Failure(message))
                }
            }
        })
    }
}

// ---------------------------------------------------------------------------

class GoogleAdapter(client: OkHttpClient) : HttpProviderAdapter(client) {

    override val kind: ProviderKind = ProviderKind.GOOGLE

    override fun stream(
        config: ProviderConfig,
        apiKey: String?,
        request: ChatRequest,
    ): Flow<ChatEvent> {
        val body = buildJsonObject {
            request.system?.let { sys ->
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", sys) } }
                }
            }
            putJsonArray("contents") {
                request.messages.forEach { m ->
                    addJsonObject {
                        // Google calls the assistant "model".
                        put("role", if (m.role == Role.ASSISTANT) "model" else "user")
                        putJsonArray("parts") { addJsonObject { put("text", m.content) } }
                    }
                }
            }
            val hasGenerationConfig = request.temperature != null || request.maxTokens != null
            if (hasGenerationConfig) {
                putJsonObject("generationConfig") {
                    request.temperature?.let { put("temperature", it) }
                    request.maxTokens?.let { put("maxOutputTokens", it) }
                }
            }
        }

        val model = request.model.removePrefix("models/")
        val httpRequest = Request.Builder()
            .url(
                config.baseUrl.trimEnd('/') +
                    "/v1beta/models/$model:streamGenerateContent?alt=sse"
            )
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply { apiKey?.takeIf { it.isNotBlank() }?.let { addHeader("x-goog-api-key", it) } }
            .build()

        // Gemini repeats usageMetadata on every chunk with running totals, so
        // hold the latest and report it once when the stream is known complete.
        var latestUsage: ChatEvent.Usage? = null

        return sse(
            httpRequest,
            decode = { event ->
                val root = try {
                    parse(event.data).jsonObject
                } catch (e: Exception) {
                    return@sse
                }

                root["candidates"]?.jsonArray?.forEach { candidate ->
                    candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
                        ?.forEach { part ->
                            part.jsonObject.field("text")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { emit(ChatEvent.TextDelta(it)) }
                        }
                }

                root["usageMetadata"]?.jsonObject?.let { usage ->
                    val input = usage.intField("promptTokenCount") ?: return@let
                    val output = usage.intField("candidatesTokenCount") ?: return@let
                    latestUsage = ChatEvent.Usage(input, output)
                }
            },
            complete = {
                latestUsage?.let { emit(it) }
                latestUsage = null
            },
        )
    }
}

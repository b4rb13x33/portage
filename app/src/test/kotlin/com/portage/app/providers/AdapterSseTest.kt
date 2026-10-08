package com.portage.app.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Exercises the SSE adapters against canned provider streams.
 *
 * No network and no keys: an interceptor short-circuits the request and replays
 * a real provider payload, which lets us assert both the request body we send
 * and the events we emit for all three wire protocols.
 */
class AdapterSseTest {

    private class Stub(val code: Int = 200, val body: String, val contentType: String) {
        @Volatile var captured: String? = null
        @Volatile var authorization: String? = null
        @Volatile var url: String? = null
    }

    private fun client(stub: Stub): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            stub.authorization = request.header("Authorization")
            stub.url = request.url.toString()
            request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                stub.captured = buffer.readUtf8()
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(stub.code)
                .message(if (stub.code == 200) "OK" else "Error")
                .body(stub.body.toResponseBody(stub.contentType.toMediaType()))
                .build()
        }
        .build()

    private fun config(
        kind: ProviderKind,
        baseUrl: String = "https://provider.test/v1",
    ) = ProviderConfig(
        id = "test",
        kind = kind,
        label = "Test",
        baseUrl = baseUrl,
        defaultModel = "test-model",
    )

    private fun request() = ChatRequest(
        model = "test-model",
        messages = listOf(
            ChatMessage(Role.USER, "Hi"),
            ChatMessage(Role.ASSISTANT, "Hello"),
            ChatMessage(Role.USER, "Again"),
        ),
        system = "Be terse.",
    )

    private fun text(events: List<ChatEvent>): String =
        events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // -- OpenAI -------------------------------------------------------------

    @Test
    fun openAiStreamYieldsDeltasThenUsageThenDone() = runBlocking {
        val stub = Stub(
            body = """
                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":" world"}}]}

                data: {"usage":{"prompt_tokens":11,"completion_tokens":5}}

                data: [DONE]

            """.trimIndent(),
            contentType = "text/event-stream",
        )

        val events = OpenAiCompatAdapter(client(stub), ProviderKind.OPENAI)
            .stream(config(ProviderKind.OPENAI), "sk-test", request())
            .toList()

        assertEquals("Hello world", text(events))
        assertEquals(ChatEvent.Usage(11, 5), events.filterIsInstance<ChatEvent.Usage>().single())
        assertTrue(events.last() is ChatEvent.Done)
        assertFalse(events.any { it is ChatEvent.Failure })
    }

    @Test
    fun openAiPutsSystemPromptInMessages() = runBlocking {
        val stub = Stub(body = "data: [DONE]\n\n", contentType = "text/event-stream")
        OpenAiCompatAdapter(client(stub), ProviderKind.OPENAI)
            .stream(config(ProviderKind.OPENAI), "sk-test", request())
            .toList()

        val body = stub.captured!!
        assertEquals("Bearer sk-test", stub.authorization)
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("Be terse."))
        assertTrue(body.contains("\"stream_options\""))
        // History must be replayed in order for multi-turn to mean anything.
        assertTrue(body.indexOf("\"Hi\"") < body.indexOf("Hello"))
        assertTrue(body.indexOf("Hello") < body.indexOf("Again"))
    }

    @Test
    fun ollamaSendsUsageOptionsButNoBearerToken() = runBlocking {
        val stub = Stub(body = "data: [DONE]\n\n", contentType = "text/event-stream")
        OpenAiCompatAdapter(client(stub), ProviderKind.OLLAMA)
            .stream(config(ProviderKind.OLLAMA), null, request())
            .toList()

        val body = stub.captured!!
        // Ollama >= 0.34 honours stream_options; token counts power local cost tracking.
        assertTrue("expected usage accounting", body.contains("stream_options"))
        assertNull("local endpoint must not send a bearer token", stub.authorization)
        assertTrue(body.contains("\"role\":\"system\""))
    }

    @Test
    fun thinkingModelStreamsReasoningSeparatelyFromAnswer() = runBlocking {
        val stub = Stub(
            body = """
                data: {"choices":[{"delta":{"reasoning":"Let me think"}}]}

                data: {"choices":[{"delta":{"reasoning":" about that"}}]}

                data: {"choices":[{"delta":{"content":"OK"}}]}

                data: [DONE]

            """.trimIndent(),
            contentType = "text/event-stream",
        )

        val events = OpenAiCompatAdapter(client(stub), ProviderKind.OPENAI)
            .stream(config(ProviderKind.OPENAI), "sk-test", request())
            .toList()

        assertEquals("Let me think about that", events.filterIsInstance<ChatEvent.ReasoningDelta>()
            .joinToString("") { it.text })
        assertEquals("OK", text(events))
    }

    @Test
    fun openAiErrorResponseBecomesFailureFollowedByDone() = runBlocking {
        val stub = Stub(
            code = 401,
            body = """{"error":"invalid api key"}""",
            contentType = "application/json",
        )

        val events = OpenAiCompatAdapter(client(stub), ProviderKind.OPENAI)
            .stream(config(ProviderKind.OPENAI), "sk-bad", request())
            .toList()

        val failure = events.filterIsInstance<ChatEvent.Failure>().single()
        assertTrue(failure.message, failure.message.contains("HTTP 401"))
        assertTrue(failure.message.contains("invalid api key"))
        assertTrue(events.last() is ChatEvent.Done)
        assertTrue(events.filterIsInstance<ChatEvent.TextDelta>().isEmpty())
    }

    // -- Anthropic ----------------------------------------------------------

    @Test
    fun anthropicStreamReportsTextAndUsage() = runBlocking {
        val stub = Stub(
            body = """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":11}}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":" there"}}

                event: message_delta
                data: {"type":"message_delta","usage":{"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}

            """.trimIndent(),
            contentType = "text/event-stream",
        )

        val events = AnthropicAdapter(client(stub))
            .stream(config(ProviderKind.ANTHROPIC), "sk-ant", request())
            .toList()

        assertEquals("Hi there", text(events))
        assertEquals(ChatEvent.Usage(11, 5), events.filterIsInstance<ChatEvent.Usage>().single())
        assertTrue(events.last() is ChatEvent.Done)
    }

    @Test
    fun anthropicSendsTopLevelSystemAndMaxTokens() = runBlocking {
        val stub = Stub(body = "event: message_stop\ndata: {}\n\n", contentType = "text/event-stream")
        AnthropicAdapter(client(stub))
            .stream(config(ProviderKind.ANTHROPIC), "sk-ant", request())
            .toList()

        val body = stub.captured!!
        assertTrue(body.contains("\"system\":\"Be terse.\""))
        assertTrue(body.contains("\"max_tokens\":4096"))
        // Anthropic rejects a system role inside messages.
        assertFalse(body.contains("\"role\":\"system\""))
    }

    @Test
    fun anthropicInStreamErrorSurfacesFailure() = runBlocking {
        val stub = Stub(
            body = """
                event: error
                data: {"type":"error","error":{"message":"overloaded"}}

            """.trimIndent(),
            contentType = "text/event-stream",
        )

        val events = AnthropicAdapter(client(stub))
            .stream(config(ProviderKind.ANTHROPIC), "sk-ant", request())
            .toList()

        assertEquals("overloaded", events.filterIsInstance<ChatEvent.Failure>().single().message)
        assertTrue(events.last() is ChatEvent.Done)
    }

    // -- Google -------------------------------------------------------------

    @Test
    fun googleStreamSplitsPartsAndReportsUsage() = runBlocking {
        val stub = Stub(
            body = """
                data: {"candidates":[{"content":{"parts":[{"text":"Sure"}]}}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":2}}

                data: {"candidates":[{"content":{"parts":[{"text":", done"},{"text":"!"}]}}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":6}}

            """.trimIndent(),
            contentType = "text/event-stream",
        )

        val events = GoogleAdapter(client(stub))
            .stream(config(ProviderKind.GOOGLE), "g-key", request())
            .toList()

        assertEquals("Sure, done!", text(events))
        assertEquals(ChatEvent.Usage(9, 6), events.filterIsInstance<ChatEvent.Usage>().single())
        assertTrue(events.last() is ChatEvent.Done)
    }

    @Test
    fun googleUsesSystemInstructionAndModelRole() = runBlocking {
        val stub = Stub(body = "data: {}\n\n", contentType = "text/event-stream")
        GoogleAdapter(client(stub))
            .stream(config(ProviderKind.GOOGLE), "g-key", request())
            .toList()

        val body = stub.captured!!
        assertTrue(body.contains("\"systemInstruction\""))
        assertTrue(body.contains("Be terse."))
        assertTrue(body.contains("\"role\":\"model\""))
        assertFalse("Google has no system role", body.contains("\"role\":\"system\""))
        assertTrue("got URL: ${stub.url}", stub.url!!.endsWith("/models/test-model:streamGenerateContent?alt=sse"))
    }

    // -- Live local provider ------------------------------------------------

    @Test
    fun streamsFromLocalOllamaWhenAvailable() = runBlocking {
        val probe = OkHttpClient.Builder().build()
        val reachable = try {
            probe.newCall(Request.Builder().url("http://127.0.0.1:11434/api/tags").build())
                .execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
        Assume.assumeTrue("local Ollama is not running", reachable)

        val config = ProviderConfig(
            id = "ollama",
            kind = ProviderKind.OLLAMA,
            label = "Ollama",
            baseUrl = "http://127.0.0.1:11434/v1",
            defaultModel = "qwen3fast",
        )
        val events = OpenAiCompatAdapter(probe, ProviderKind.OLLAMA)
            .stream(
                config,
                null,
                ChatRequest(
                    model = "qwen3fast",
                    messages = listOf(ChatMessage(Role.USER, "Reply with exactly: OK")),
                ),
            )
            .toList()

        // qwen3 thinks first: the answer may arrive as content, the thought as reasoning.
        val answer = text(events)
        val reasoning = events.filterIsInstance<ChatEvent.ReasoningDelta>().joinToString("") { it.text }
        assertTrue("expected answer or reasoning, got events=$events", answer.isNotBlank() || reasoning.isNotBlank())
        assertTrue("expected usage accounting, got events=$events", events.any { it is ChatEvent.Usage })
        assertTrue(events.last() is ChatEvent.Done)
    }
}

package com.portage.app.providers

import com.portage.app.core.net.SseEvent
import com.portage.app.core.net.forEachSseEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared plumbing for every provider: cancellable HTTP, error surfacing, and
 * SSE-to-[ChatEvent] translation.
 *
 * Every stream terminates with [ChatEvent.Done] — exactly once, whether the
 * request succeeded, failed, or was interrupted — so collectors can always
 * clear their "in flight" state without special-casing errors.
 */
abstract class HttpProviderAdapter(protected val client: OkHttpClient) {

    abstract val kind: ProviderKind

    abstract fun stream(
        config: ProviderConfig,
        apiKey: String?,
        request: ChatRequest,
    ): Flow<ChatEvent>

    protected fun sse(
        request: Request,
        decode: suspend FlowCollector<ChatEvent>.(SseEvent) -> Unit,
        complete: suspend FlowCollector<ChatEvent>.() -> Unit = {},
    ): Flow<ChatEvent> = flow {
        var failure: String? = null
        try {
            val response = client.newCall(request).executeCancellable()
            response.use { r ->
                if (!r.isSuccessful) {
                    failure = describe(r)
                } else {
                    r.body.source().forEachSseEvent { event -> decode(event) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            failure = e.message?.takeIf { it.isNotBlank() } ?: "Network error"
        } catch (e: Exception) {
            failure = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }

        failure?.let { emit(ChatEvent.Failure(it)) }
        // Flush any value the provider only reports once the stream is known to
        // be complete (Google repeats usageMetadata on every chunk).
        if (failure == null) complete()
        emit(ChatEvent.Done)
    }.flowOn(Dispatchers.IO)

    private fun describe(response: Response): String {
        val detail = try {
            response.body.string().take(500).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        return "HTTP ${response.code} ${response.message}" + (detail?.let { ": $it" } ?: "")
    }

    /**
     * [Call.enqueue] so that cancelling the coroutine closes the socket instead
     * of leaving a download running in the background after the user hits Stop.
     */
    private suspend fun Call.executeCancellable(): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { this@executeCancellable.cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }
}

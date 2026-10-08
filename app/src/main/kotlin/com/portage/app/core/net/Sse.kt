package com.portage.app.core.net

import okio.BufferedSource

data class SseEvent(val event: String, val data: String)

/**
 * Pull-based `text/event-stream` reader over an Okio source.
 *
 * All three providers speak SSE, but with different conventions for the event
 * name (OpenAI omits it entirely), multi-line data, and termination (some
 * servers close the socket without a final blank line). This handles all three
 * and yields one [SseEvent] per dispatch.
 *
 * Must be called on a background dispatcher — it blocks the calling thread.
 */
suspend fun BufferedSource.forEachSseEvent(block: suspend (SseEvent) -> Unit) {
    var event = "message"
    val data = StringBuilder()

    while (true) {
        val line = readUtf8Line() ?: break

        when {
            // Blank line dispatches the accumulated event.
            line.isEmpty() -> {
                if (data.isNotEmpty()) {
                    block(SseEvent(event, data.toString()))
                    data.setLength(0)
                }
                event = "message"
            }

            // Comment / keepalive (": ping").
            line.startsWith(":") -> Unit

            line.startsWith("event:") -> event = line.substringAfter(':').trimStart()

            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substringAfter(':').removePrefix(" "))
            }

            // id:, retry:, unknown fields — safely ignored per spec.
            else -> Unit
        }
    }

    // EOF without a terminating blank line still carries a complete payload.
    if (data.isNotEmpty()) block(SseEvent(event, data.toString()))
}

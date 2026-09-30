/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */

package cc.grepon.relais

import java.io.OutputStream
import org.json.JSONObject

/**
 * The ONE `text/event-stream` writer for both streaming paths in [RelaisHttpServer] (issue #173
 * item 4) — the 200 SSE header write and the post-header abort-catch were duplicated between the
 * plain-chat and tool-completion streaming handlers. Not thread-safe; one instance per request.
 *
 * Also backs the Anthropic Messages API's named-event SSE stream (issue #179): unlike OpenAI's bare
 * `data: <json>` framing terminated by a `data: [DONE]` sentinel, Anthropic requires an `event: <type>`
 * line before each `data:` line and has no `[DONE]` terminator (the stream just ends after a
 * `message_stop` event, or emits an `event: error` pair on failure). [send] (bare, OpenAI-shaped) and
 * [done] are UNCHANGED — the Anthropic handler uses [send] (event, json) and [sendError] instead and
 * never calls [done].
 *
 * The 200 header is committed LAZILY (#352): every writer commits it first, once, so no byte can
 * reach the socket on an uncommitted stream and a handler never has to remember to commit. Until the
 * first write the socket is untouched, so a request that fails before its first event — a
 * served-model mismatch under the engine lock ([ModelNotResidentException]) — can still be answered
 * with a real HTTP status. [onCommit] runs once, at commit, and is where a streaming handler records
 * its 200: a stream that never commits never counted as one.
 */
class SseWriter(private val out: OutputStream, private val onCommit: () -> Unit) {

  /** True once the 200 header has been (or is being) written — from then on only SSE events may follow. */
  var committed: Boolean = false
    private set

  /**
   * Writes the 200 SSE response header. Idempotent, and called by every writer below, so an explicit
   * call is never required. [committed] flips BEFORE the write: a write that fails part-way may
   * still have put bytes on the wire, so the stream must never again be treated as uncommitted.
   */
  fun commitHeader() {
    if (committed) return
    committed = true
    onCommit()
    out.write(
      ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
        "Connection: close\r\n\r\n").toByteArray()
    )
    out.flush()
  }

  /** Writes one bare `data: <json>` SSE event (OpenAI shape). */
  fun send(json: JSONObject) {
    commitHeader()
    out.write("data: $json\n\n".toByteArray())
    out.flush()
  }

  /** Writes one named `event: <event>` / `data: <json>` SSE event pair (Anthropic shape). */
  fun send(event: String, json: JSONObject) {
    commitHeader()
    out.write("event: $event\ndata: $json\n\n".toByteArray())
    out.flush()
  }

  /** Writes the terminal `data: [DONE]` SSE event (OpenAI shape only — Anthropic has no sentinel). */
  fun done() {
    commitHeader()
    out.write("data: [DONE]\n\n".toByteArray())
    out.flush()
  }

  /**
   * Best-effort SSE error event for a failure AFTER the 200 header is already committed (the outer
   * HTTP-status catch can't run at that point without double-writing a status/double-counting the
   * request). Swallows any write failure — the connection may already be gone. On a stream that has
   * not committed yet it commits first, so a pre-first-event failure still reads as 200 + error event.
   */
  fun abort(message: String = "stream aborted") {
    runCatching { commitHeader(); out.write("data: {\"error\":\"$message\"}\n\n".toByteArray()); out.flush() }
  }

  /**
   * Anthropic-shaped best-effort SSE error event for a failure AFTER the 200 header is already
   * committed — the [abort] equivalent for the named-event stream. Swallows any write failure (mirrors
   * [abort]'s failure-swallowing and its commit-first; the connection may already be gone).
   */
  fun sendError(errorType: String, message: String) {
    runCatching {
      commitHeader()
      val payload = JSONObject()
        .put("type", "error")
        .put("error", JSONObject().put("type", errorType).put("message", message))
      out.write("event: error\ndata: $payload\n\n".toByteArray())
      out.flush()
    }
  }
}

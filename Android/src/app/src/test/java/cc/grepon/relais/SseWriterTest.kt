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

import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseWriterTest {

  private val header =
    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
      "Connection: close\r\n\r\n"

  private fun capture(onCommit: () -> Unit = {}, block: (SseWriter) -> Unit): String {
    val buf = ByteArrayOutputStream()
    block(SseWriter(buf, onCommit))
    return buf.toString(Charsets.UTF_8.name())
  }

  /** Bytes written AFTER the header — for the event-shape tests, which don't care about the commit. */
  private fun body(block: (SseWriter) -> Unit): String {
    val written = capture(block = block)
    assertTrue("every write commits the 200 header first", written.startsWith(header))
    return written.removePrefix(header)
  }

  @Test fun `commitHeader writes a 200 SSE response header`() {
    val written = capture { it.commitHeader() }
    assertTrue(written.startsWith("HTTP/1.1 200 OK\r\n"))
    assertTrue(written.contains("Content-Type: text/event-stream\r\n"))
    assertTrue(written.endsWith("\r\n\r\n"))
  }

  @Test fun `send writes one data event with a trailing blank line`() {
    val written = body { it.send(JSONObject().put("foo", "bar")) }
    assertEquals("data: {\"foo\":\"bar\"}\n\n", written)
  }

  @Test fun `done writes the terminal DONE event`() {
    val written = body { it.done() }
    assertEquals("data: [DONE]\n\n", written)
  }

  @Test fun `abort writes a data error event with the given message`() {
    val written = body { it.abort("stream aborted") }
    assertEquals("data: {\"error\":\"stream aborted\"}\n\n", written)
  }

  @Test fun `abort swallows a write failure instead of throwing`() {
    val poison = object : java.io.OutputStream() {
      override fun write(b: Int) = throw java.io.IOException("broken pipe")
    }
    SseWriter(poison, {}).abort() // must not throw
  }

  @Test fun `header then multiple sends then done compose as one stream`() {
    val written = capture {
      it.commitHeader()
      it.send(JSONObject().put("i", 1))
      it.send(JSONObject().put("i", 2))
      it.done()
    }
    assertTrue(written.contains("data: {\"i\":1}\n\n"))
    assertTrue(written.contains("data: {\"i\":2}\n\n"))
    assertTrue(written.endsWith("data: [DONE]\n\n"))
  }

  // --- Anthropic-shaped named-event overloads (issue #179) ---

  @Test fun `named send writes an event line before the data line`() {
    val written = body { it.send("message_start", JSONObject().put("type", "message_start")) }
    assertEquals("event: message_start\ndata: {\"type\":\"message_start\"}\n\n", written)
  }

  @Test fun `multiple named sends compose without a DONE sentinel`() {
    val written = capture {
      it.commitHeader()
      it.send("message_start", JSONObject().put("i", 1))
      it.send("message_stop", JSONObject().put("i", 2))
    }
    assertTrue(written.contains("event: message_start\ndata: {\"i\":1}\n\n"))
    assertTrue(written.endsWith("event: message_stop\ndata: {\"i\":2}\n\n"))
    assertTrue("Anthropic stream has no [DONE] sentinel", !written.contains("[DONE]"))
  }

  @Test fun `sendError writes the Anthropic error envelope as a named error event`() {
    val written = body { it.sendError("api_error", "stream aborted") }
    assertTrue(written.startsWith("event: error\ndata: "))
    assertTrue(written.endsWith("\n\n"))
    val json = JSONObject(written.substringAfter("data: ").trim())
    assertEquals("error", json.getString("type"))
    assertEquals("api_error", json.getJSONObject("error").getString("type"))
    assertEquals("stream aborted", json.getJSONObject("error").getString("message"))
  }

  @Test fun `sendError swallows a write failure instead of throwing`() {
    val poison = object : java.io.OutputStream() {
      override fun write(b: Int) = throw java.io.IOException("broken pipe")
    }
    SseWriter(poison, {}).sendError("api_error", "stream aborted") // must not throw
  }

  // --- lazy, idempotent commit (#352) ---

  @Test fun `nothing is written before the first event`() {
    // A request that fails before its first token (a served-model mismatch under the engine lock)
    // must leave the socket untouched, so the handler can still answer with a real HTTP status.
    var commits = 0
    val written = capture(onCommit = { commits++ }) { }
    assertEquals("", written)
    assertEquals(0, commits)
  }

  @Test fun `commitHeader twice writes one header and fires onCommit once`() {
    var commits = 0
    val written = capture(onCommit = { commits++ }) {
      it.commitHeader()
      it.commitHeader()
    }
    assertEquals(header, written)
    assertEquals(1, commits)
  }

  @Test fun `a send without an explicit commit writes the header then the event`() {
    var commits = 0
    val written = capture(onCommit = { commits++ }) { it.send(JSONObject().put("i", 1)) }
    assertEquals(header + "data: {\"i\":1}\n\n", written)
    assertEquals(1, commits)
  }

  @Test fun `every writer commits first, and only once across a whole stream`() {
    var commits = 0
    val written = capture(onCommit = { commits++ }) {
      it.send("message_start", JSONObject().put("i", 1))
      it.send(JSONObject().put("i", 2))
      it.done()
      it.abort()
      it.sendError("api_error", "x")
    }
    assertTrue(written.startsWith(header))
    assertEquals("one header only", written.indexOf("HTTP/1.1"), written.lastIndexOf("HTTP/1.1"))
    assertEquals(1, commits)
  }

  @Test fun `abort and sendError each commit a header on an uncommitted stream`() {
    // A non-mismatch failure before the first token keeps today's wire shape: 200 + an error event.
    assertTrue(capture { it.abort() }.startsWith(header))
    assertTrue(capture { it.sendError("api_error", "x") }.startsWith(header))
  }
}

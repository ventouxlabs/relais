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

/**
 * #352: `message_start` is emitted lazily, on the first event, so a request that fails before its
 * first token (a served-model mismatch) has written nothing and can still be answered with a 503.
 */
class AnthropicStreamSequencerTest {

  private val start = JSONObject().put("type", "message_start")

  private fun events(written: String): List<String> =
    Regex("^event: (\\S+)$", RegexOption.MULTILINE).findAll(written).map { it.groupValues[1] }.toList()

  @Test fun `nothing is written before the first delta`() {
    val buf = ByteArrayOutputStream()
    AnthropicStreamSequencer(SseWriter(buf, {}), start)
    assertEquals(0, buf.size())
  }

  @Test fun `message_start precedes the first text block`() {
    val buf = ByteArrayOutputStream()
    AnthropicStreamSequencer(SseWriter(buf, {}), start).onTextDelta("hi")
    assertEquals(listOf("message_start", "content_block_start", "content_block_delta"), events(buf.toString()))
  }

  @Test fun `message_start precedes a reasoning block that arrives first, and is sent once`() {
    val buf = ByteArrayOutputStream()
    val seq = AnthropicStreamSequencer(SseWriter(buf, {}), start)
    seq.onReasoningDelta("think")
    seq.onTextDelta("hi")
    seq.finish("end_turn", 1)
    val names = events(buf.toString())
    assertEquals("message_start", names.first())
    assertEquals(1, names.count { it == "message_start" })
    assertEquals("message_stop", names.last())
  }

  @Test fun `a zero-token stream still emits message_start before its terminal events`() {
    val buf = ByteArrayOutputStream()
    AnthropicStreamSequencer(SseWriter(buf, {}), start).finish("end_turn", 0)
    assertEquals(listOf("message_start", "message_delta", "message_stop"), events(buf.toString()))
    assertTrue(buf.toString().startsWith("HTTP/1.1 200 OK"))
  }
}

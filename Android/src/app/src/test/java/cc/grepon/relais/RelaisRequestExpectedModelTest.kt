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

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #352 wiring: both chat lanes' request parsers carry the id the classifier decided on into
 * [RelaisRequest.expectedModelId], which [RelaisEngine.generate] checks under its lock. A parser that
 * dropped it would fail OPEN silently — every request would read as "whatever is resident" — so this
 * pins the assignment itself, not just [requestedModelId]. Robolectric because the parsers are
 * server members that need a Context (prompt templates) and android.util.Base64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelaisRequestExpectedModelTest {

  private val server by lazy {
    RelaisHttpServer(ApplicationProvider.getApplicationContext<android.app.Application>())
  }

  private fun openAi(model: Any?): JSONObject =
    JSONObject()
      .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
      .also { if (model != null) it.put("model", model) }

  private fun anthropic(model: Any?): JSONObject = openAi(model).put("max_tokens", 16)

  @Test fun `OpenAI parser carries a named model into expectedModelId`() {
    assertEquals("gemma-y", server.parseOpenAiRequest(openAi("gemma-y")).expectedModelId)
  }

  @Test fun `OpenAI parser leaves expectedModelId null for an absent or blank model`() {
    assertNull(server.parseOpenAiRequest(openAi(null)).expectedModelId)
    assertNull(server.parseOpenAiRequest(openAi("")).expectedModelId)
    assertNull(server.parseOpenAiRequest(openAi("  ")).expectedModelId)
  }

  @Test fun `Anthropic parser carries a named model into expectedModelId`() {
    assertEquals("gemma-y", server.parseAnthropicRequest(anthropic("gemma-y")).expectedModelId)
  }

  @Test fun `Anthropic parser leaves expectedModelId null for an absent or blank model`() {
    assertNull(server.parseAnthropicRequest(anthropic(null)).expectedModelId)
    assertNull(server.parseAnthropicRequest(anthropic("")).expectedModelId)
    assertNull(server.parseAnthropicRequest(anthropic("  ")).expectedModelId)
  }
}

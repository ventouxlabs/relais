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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #352: the under-the-lock served-model check and the request-side normalisation it compares. */
class RelaisServedModelTest {

  // --- servedModelMismatch ---

  @Test fun `an omitted model never mismatches, whatever is resident`() {
    assertFalse(servedModelMismatch(expected = null, resident = "gemma-x"))
    assertFalse(servedModelMismatch(expected = null, resident = null))
  }

  @Test fun `the model the request was classified for is not a mismatch`() {
    assertFalse(servedModelMismatch(expected = "gemma-y", resident = "gemma-y"))
  }

  @Test fun `a different resident model is a mismatch`() {
    // The #352 race: classified for the configured Y, a swap made X resident before the lock.
    assertTrue(servedModelMismatch(expected = "gemma-y", resident = "gemma-x"))
  }

  @Test fun `no resident model with an expected one is a mismatch`() {
    assertTrue(servedModelMismatch(expected = "gemma-y", resident = null))
  }

  // --- requestedModelId: the ONE normalisation the classifier and the check share ---

  @Test fun `a non-blank model field is the requested id, verbatim`() {
    assertEquals("gemma-y", requestedModelId(JSONObject().put("model", "gemma-y")))
  }

  @Test fun `an absent or blank model field means whatever is resident`() {
    assertNull(requestedModelId(JSONObject()))
    assertNull(requestedModelId(JSONObject().put("model", "")))
    assertNull(requestedModelId(JSONObject().put("model", "   ")))
  }

  // --- modelNotResidentBody: each lane's own envelope ---

  @Test fun `the Anthropic endpoint gets the Anthropic overloaded envelope`() {
    val body = modelNotResidentBody("/v1/messages", "msg")
    assertEquals("error", body.getString("type"))
    assertEquals("overloaded_error", body.getJSONObject("error").getString("type"))
    assertEquals("msg", body.getJSONObject("error").getString("message"))
  }

  @Test fun `the OpenAI endpoint gets the OpenAI service-unavailable envelope`() {
    val body = modelNotResidentBody("/v1/chat/completions", "msg")
    assertFalse(body.has("type"))
    assertEquals(RelaisError.SERVICE_UNAVAILABLE, body.getJSONObject("error").getString("type"))
    assertEquals("msg", body.getJSONObject("error").getString("message"))
  }

  // --- isUncommittedModelMismatch: which stream failures unwind to the 503 ---

  @Test fun `a mismatch on an uncommitted stream is rethrown for the 503`() {
    assertTrue(isUncommittedModelMismatch(ModelNotResidentException("y", "x"), committed = false))
  }

  @Test fun `a mismatch after the header went out stays with the stream's own handling`() {
    assertFalse(isUncommittedModelMismatch(ModelNotResidentException("y", "x"), committed = true))
  }

  @Test fun `any other failure stays with the stream's own handling, committed or not`() {
    assertFalse(isUncommittedModelMismatch(IllegalStateException("engine"), committed = false))
    assertFalse(isUncommittedModelMismatch(IllegalStateException("engine"), committed = true))
  }

  @Test fun `the exception message names both models`() {
    val e = ModelNotResidentException(expected = "gemma-y", resident = "gemma-x")
    assertTrue(e.message!!.contains("gemma-y"))
    assertTrue(e.message!!.contains("gemma-x"))
  }
}

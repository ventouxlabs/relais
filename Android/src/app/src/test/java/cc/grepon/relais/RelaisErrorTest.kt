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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [RelaisError.json]: the one OpenAI-compatible error envelope every Relais HTTP
 * route now shares (issue #173 item 3). Guards the exact nested shape — `{"error":{"message","type"}}`
 * — so a future edit can't silently regress a route back to the old flat `{"error":"str"}` shape.
 */
class RelaisErrorTest {

  @Test fun `json nests message and type under error`() {
    val envelope = RelaisError.json("bad request", RelaisError.INVALID_REQUEST)

    assertTrue(envelope.has("error"))
    val error = envelope.getJSONObject("error")
    assertEquals("bad request", error.getString("message"))
    assertEquals(RelaisError.INVALID_REQUEST, error.getString("type"))
  }

  @Test fun `json never puts a raw string under error`() {
    // The regression this whole issue exists to prevent: `error` must always be an object, never a
    // bare string, so every OpenAI-compatible client can parse it the same way on every route.
    val envelope = RelaisError.json("oops", RelaisError.INTERNAL_ERROR)

    assertTrue(envelope.get("error") is org.json.JSONObject)
  }

  @Test fun `envelope composes with extra top-level fields`() {
    // Several routes (thermal 503, queue-full 429, unknown-template 400) attach extra fields like
    // retry_after_seconds or code alongside the error envelope — json() must return a JSONObject that
    // still supports chained .put() the same way the old flat builders did.
    val envelope = RelaisError.json("thermal backpressure; retry later", RelaisError.SERVICE_UNAVAILABLE)
      .put("retry_after_seconds", 8)

    assertEquals(8, envelope.getInt("retry_after_seconds"))
    assertEquals("thermal backpressure; retry later", envelope.getJSONObject("error").getString("message"))
  }

  @Test fun `type constants match the OpenAI-compatible vocabulary already in use`() {
    // Regression guard on the literal values themselves — RATE_LIMIT_EXCEEDED in particular must stay
    // "rate_limit_exceeded" (not "rate_limit_error") to match the precedent already shipped on the
    // /v1/embeddings batch-queue-full path.
    assertEquals("invalid_request_error", RelaisError.INVALID_REQUEST)
    assertEquals("authentication_error", RelaisError.AUTHENTICATION)
    assertEquals("rate_limit_exceeded", RelaisError.RATE_LIMIT_EXCEEDED)
    assertEquals("not_found", RelaisError.NOT_FOUND)
    assertEquals("not_implemented", RelaisError.NOT_IMPLEMENTED)
    assertEquals("service_unavailable", RelaisError.SERVICE_UNAVAILABLE)
    assertEquals("internal_error", RelaisError.INTERNAL_ERROR)
    assertEquals("corpus_full", RelaisError.CORPUS_FULL)
    // PERMISSION must stay distinct from AUTHENTICATION: the 403 it labels means the credential was
    // VALID and the request context was rejected, so a client reading it as an auth failure may
    // enter a credential-refresh loop against a request that can never succeed.
    assertEquals("permission_error", RelaisError.PERMISSION)
  }
}

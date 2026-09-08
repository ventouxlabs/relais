/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.grepon.relais

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the OpenAI usage-block helpers (Feature 02).
 *
 * All tests call [buildUsageObject] / [estimatePromptTokens] directly — no socket, no Android
 * Context, no Robolectric. The functions live in RelaisHttpServer.kt as internal top-level fns.
 *
 * completion_tokens = exact engine counter (onMessage callback count from RelaisResult).
 * prompt_tokens     = word-boundary ESTIMATE (LiteRT-LM exposes no detached prompt tokenizer
 *                     via sendMessageAsync; see SPIKE-FINDINGS.md / Q1 comment in RelaisEngine).
 * total_tokens      = prompt_tokens + completion_tokens (invariant, always exact).
 *
 * The `usage` object returned by [buildUsageObject] is OpenAI-schema-clean: it contains ONLY
 * the three standard keys. The estimation signal is surfaced as `x_relais_usage_note` at the
 * top level of the enclosing response/chunk — NOT inside the `usage` sub-object.
 */
class RelaisUsageBlockTest {

  // ---------------------------------------------------------------------------
  // Test 1 — usage sub-object is schema-clean (exactly 3 standard keys)
  // ---------------------------------------------------------------------------

  @Test
  fun `buildUsageObject returns exactly the three standard OpenAI keys`() {
    val usage = buildUsageObject("hello world", 42)

    assertEquals(
      "completion_tokens must equal the engine counter",
      42,
      usage.getInt("completion_tokens"),
    )
    assertEquals(
      "total_tokens must equal prompt_tokens + completion_tokens",
      usage.getInt("prompt_tokens") + 42,
      usage.getInt("total_tokens"),
    )
    // usage_note must NOT be inside the usage object — it would trip strict OpenAI validators.
    assertFalse(
      "usage_note must NOT appear inside the usage sub-object",
      usage.has("usage_note"),
    )
    // x_relais_usage_note belongs on the enclosing response, not here.
    assertFalse(
      "x_relais_usage_note must NOT appear inside the usage sub-object",
      usage.has("x_relais_usage_note"),
    )
    assertEquals(
      "usage object must have exactly 3 keys (prompt_tokens, completion_tokens, total_tokens)",
      3,
      usage.length(),
    )
  }

  // ---------------------------------------------------------------------------
  // Test 2 — prompt token estimator, basic cases
  // ---------------------------------------------------------------------------

  @Test
  fun `estimatePromptTokens splits on whitespace runs correctly`() {
    assertEquals("two words", 2, estimatePromptTokens("hello world"))
    assertEquals("four words", 4, estimatePromptTokens("one two three four"))
    assertEquals("leading/trailing spaces collapse to one token", 1, estimatePromptTokens("  spaces  "))
    assertEquals("empty string yields zero", 0, estimatePromptTokens(""))
    assertEquals("all-whitespace string yields zero", 0, estimatePromptTokens("   "))
  }

  // ---------------------------------------------------------------------------
  // Test 3 — total_tokens invariant: always equals prompt + completion
  // ---------------------------------------------------------------------------

  @Test
  fun `total_tokens invariant holds for arbitrary inputs`() {
    for ((text, n) in listOf(
      "short" to 0,
      "one two three" to 17,
      "  spaces everywhere  " to 100,
      "" to 999,
    )) {
      val usage = buildUsageObject(text, n)
      val p = usage.getInt("prompt_tokens")
      val c = usage.getInt("completion_tokens")
      val t = usage.getInt("total_tokens")
      assertEquals(
        "total_tokens must equal prompt_tokens + completion_tokens for text='$text' n=$n",
        p + c,
        t,
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Test 4 — zero completion tokens (AICore / unknown path)
  // ---------------------------------------------------------------------------

  @Test
  fun `zero completionTokens (AICore path) yields total equal to prompt estimate`() {
    val usage = buildUsageObject("some prompt", 0)
    assertEquals("completion_tokens must be zero", 0, usage.getInt("completion_tokens"))
    assertEquals(
      "total_tokens must equal prompt estimate when completion is zero",
      usage.getInt("prompt_tokens"),
      usage.getInt("total_tokens"),
    )
    // Schema-clean: no estimation signal inside usage.
    assertFalse(
      "usage_note must not be inside usage even for the zero-completion (AICore) case",
      usage.has("usage_note"),
    )
  }

  // ---------------------------------------------------------------------------
  // Test 5 — x_relais_usage_note belongs on the enclosing object, not inside usage
  // ---------------------------------------------------------------------------

  @Test
  fun `x_relais_usage_note is absent from the usage sub-object returned by buildUsageObject`() {
    // The extension field is the caller's responsibility to attach to the response/chunk envelope.
    // This test pins that buildUsageObject itself never adds it — so callers can't accidentally
    // double-emit it or put it in the wrong place.
    val usage = buildUsageObject("any text here", 10)
    assertFalse(
      "x_relais_usage_note must never appear inside the usage sub-object",
      usage.has("x_relais_usage_note"),
    )
    assertTrue(
      "usage sub-object must still have prompt_tokens",
      usage.has("prompt_tokens"),
    )
    assertTrue(
      "usage sub-object must still have completion_tokens",
      usage.has("completion_tokens"),
    )
    assertTrue(
      "usage sub-object must still have total_tokens",
      usage.has("total_tokens"),
    )
  }

  // ---------------------------------------------------------------------------
  // Test 6 — stream_options.include_usage parsing (#175)
  // ---------------------------------------------------------------------------

  @Test
  fun `streamIncludeUsage true only when stream_options include_usage is true`() {
    assertTrue(streamIncludeUsage(JSONObject("""{"stream_options":{"include_usage":true}}""")))
    assertFalse(streamIncludeUsage(JSONObject("""{"stream_options":{"include_usage":false}}""")))
    // Absent stream_options, absent field, and a whole absent body-shape all default to false.
    assertFalse(streamIncludeUsage(JSONObject("""{"stream_options":{}}""")))
    assertFalse(streamIncludeUsage(JSONObject("""{}""")))
    // Tolerant of a malformed stream_options (not an object) — no throw, defaults false.
    assertFalse(streamIncludeUsage(JSONObject("""{"stream_options":"nope"}""")))
  }

  // ---------------------------------------------------------------------------
  // Tests 7-11 — attachRelaisExtras (feature-20)
  //
  // This is the seam every live emission site routes through. It exists so the TTFT field's
  // placement and its omit-on-null rule are pinned by a JVM test instead of by four inline
  // .put() chains inside private socket handlers, which no device-free test can reach.
  // ---------------------------------------------------------------------------

  private fun resultWithTtft(sec: Double?) =
    RelaisResult(
      text = "hi",
      backend = RelaisBackend.GPU_LITERTLM,
      decodeTokensPerSec = 5.0,
      completionTokens = 2,
      timeToFirstTokenSec = sec,
    )

  @Test
  fun `attachRelaisExtras puts x_relais_ttft_ms at the top level when a TTFT was measured`() {
    val obj = JSONObject().put("usage", buildUsageObject("hello world", 2))

    attachRelaisExtras(obj, resultWithTtft(2.481))

    // Placement is asserted BEFORE presence so that nesting the field inside `usage` fails with a
    // message naming that defect, rather than tripping the presence assertion first.
    assertFalse(
      "x_relais_ttft_ms must NOT be nested inside the usage sub-object",
      obj.getJSONObject("usage").has("x_relais_ttft_ms"),
    )
    assertTrue("x_relais_ttft_ms must be present when a TTFT exists", obj.has("x_relais_ttft_ms"))
    assertEquals(
      "seconds must be converted to whole milliseconds",
      2481,
      obj.getInt("x_relais_ttft_ms"),
    )
    assertEquals(
      "usage must still be exactly the three standard OpenAI keys",
      3,
      obj.getJSONObject("usage").length(),
    )
  }

  @Test
  fun `attachRelaisExtras omits x_relais_ttft_ms entirely when there is no measurement`() {
    // The blocking tool lane runs no per-token callback, so timeToFirstTokenSec is structurally
    // null there. A strict client must see no key at all — never 0, never JSON null.
    val obj = JSONObject().put("usage", buildUsageObject("hello world", 0))

    attachRelaisExtras(obj, resultWithTtft(null))

    assertFalse(
      "x_relais_ttft_ms must be ABSENT, not 0 and not null, when unmeasured",
      obj.has("x_relais_ttft_ms"),
    )
    assertTrue("the usage note must still be attached", obj.has("x_relais_usage_note"))
  }

  @Test
  fun `attachRelaisExtras always attaches the usage note, measured or not`() {
    val withTtft = attachRelaisExtras(JSONObject(), resultWithTtft(1.0))
    val withoutTtft = attachRelaisExtras(JSONObject(), resultWithTtft(null))

    assertEquals("prompt_tokens_estimated", withTtft.getString("x_relais_usage_note"))
    assertEquals("prompt_tokens_estimated", withoutTtft.getString("x_relais_usage_note"))
  }

  @Test
  fun `attachRelaisExtras returns the same object it was given, for chaining`() {
    val obj = JSONObject().put("id", "chatcmpl-1")
    assertTrue("must return the same instance so call sites can chain", attachRelaisExtras(obj, resultWithTtft(0.5)) === obj)
    assertEquals("pre-existing fields must survive", "chatcmpl-1", obj.getString("id"))
  }

  @Test
  fun `attachRelaisExtras truncates sub-millisecond precision rather than rounding up to a lie`() {
    val obj = attachRelaisExtras(JSONObject(), resultWithTtft(0.0004))
    assertEquals("400 microseconds reports as 0 ms, and the key is still present", 0, obj.getInt("x_relais_ttft_ms"))
    assertTrue("a real sub-millisecond measurement is still a measurement", obj.has("x_relais_ttft_ms"))
  }
}

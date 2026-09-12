/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hermetic tests for the Feature #10 observability increments on [RelaisMetrics]:
 *  1. per-endpoint inference-latency histogram (labeled `endpoint`), with the unlabeled global series
 *     still present and un-double-counted;
 *  2. the `relais_completion_tokens` histogram (buckets + sum + count, negative guard);
 *  3. `relais_thermal_events_total{level}` + the [RelaisMetrics.thermalLabel] whitelist;
 *  4. the [RelaisMetrics.endpointLabel] cardinality guard (unknown -> "other").
 *
 * Uses Robolectric only for a Context (renderProm needs one for build_info); the metric logic itself
 * is process-global and reset per test via the dedicated test seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelaisMetricsIncrementsTest {

  private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

  @Before
  fun reset() {
    RelaisMetrics.resetIncrementsForTest()
  }

  // --- 1. per-endpoint latency -------------------------------------------------------------------

  @Test
  fun `per-endpoint latency emits labeled buckets per endpoint with correct cumulative counts`() {
    // /generate: one fast (<=1s) and one slow (<=10s) sample.
    RelaisMetrics.recordEndpointLatency("/generate", 0.4)
    RelaisMetrics.recordEndpointLatency("/generate", 7.0)
    // /v1/chat/completions: a single mid sample (<=5s).
    RelaisMetrics.recordEndpointLatency("/v1/chat/completions", 3.0)

    val prom = RelaisMetrics.renderProm(context)

    // Labeled series present for both endpoints.
    assertTrue(
      "generate le=1.0 bucket must be cumulative-1",
      prom.contains("relais_inference_duration_seconds_bucket{endpoint=\"/generate\",le=\"1.0\"} 1"),
    )
    assertTrue(
      "generate le=10.0 bucket must be cumulative-2 (both samples <=10s)",
      prom.contains("relais_inference_duration_seconds_bucket{endpoint=\"/generate\",le=\"10.0\"} 2"),
    )
    assertTrue(
      "generate +Inf bucket must equal total count 2",
      prom.contains("relais_inference_duration_seconds_bucket{endpoint=\"/generate\",le=\"+Inf\"} 2"),
    )
    assertTrue(
      "generate _count must be 2",
      prom.contains("relais_inference_duration_seconds_count{endpoint=\"/generate\"} 2"),
    )
    assertTrue(
      "chat le=5.0 bucket must be cumulative-1",
      prom.contains("relais_inference_duration_seconds_bucket{endpoint=\"/v1/chat/completions\",le=\"5.0\"} 1"),
    )
    assertTrue(
      "chat le=1.0 bucket must be 0 (the 3.0s sample is above 1s)",
      prom.contains("relais_inference_duration_seconds_bucket{endpoint=\"/v1/chat/completions\",le=\"1.0\"} 0"),
    )
    assertTrue(
      "chat _count must be 1",
      prom.contains("relais_inference_duration_seconds_count{endpoint=\"/v1/chat/completions\"} 1"),
    )
  }

  @Test
  fun `global unlabeled latency series stays present and is not double-counted by per-endpoint records`() {
    RelaisMetrics.recordLatency(0.4) // the engine's global tail guarantee — the ONLY global writer
    RelaisMetrics.recordEndpointLatency("/generate", 0.4) // per-endpoint must NOT touch the global

    val prom = RelaisMetrics.renderProm(context)

    // Unlabeled (global) series still emitted, count == 1 (the per-endpoint record didn't inflate it).
    assertTrue(
      "unlabeled global bucket must still be present",
      prom.contains("relais_inference_duration_seconds_bucket{le=\"+Inf\"} 1"),
    )
    assertTrue(
      "global count must be exactly 1 — per-endpoint records must not double-count the global",
      prom.contains("relais_inference_duration_seconds_count 1"),
    )
  }

  @Test
  fun `per-endpoint latency routes unknown endpoints through the whitelist to other`() {
    RelaisMetrics.recordEndpointLatency("/etc/passwd", 0.4)
    val prom = RelaisMetrics.renderProm(context)
    assertTrue(
      "an unknown endpoint must be normalized to other (no unbounded label cardinality)",
      prom.contains("relais_inference_duration_seconds_count{endpoint=\"other\"} 1"),
    )
  }

  // --- 2. completion-token histogram -------------------------------------------------------------

  @Test
  fun `completion-token histogram has correct buckets sum and count`() {
    RelaisMetrics.recordCompletionTokens(10)   // <=16
    RelaisMetrics.recordCompletionTokens(100)  // <=128
    RelaisMetrics.recordCompletionTokens(2000) // > 1024 -> +Inf only

    val prom = RelaisMetrics.renderProm(context)

    assertTrue(
      "le=16 bucket must be cumulative-1 (only the 10-token sample)",
      prom.contains("relais_completion_tokens_bucket{le=\"16\"} 1"),
    )
    assertTrue(
      "le=128 bucket must be cumulative-2 (10 and 100)",
      prom.contains("relais_completion_tokens_bucket{le=\"128\"} 2"),
    )
    assertTrue(
      "+Inf bucket must be cumulative-3 (all samples)",
      prom.contains("relais_completion_tokens_bucket{le=\"+Inf\"} 3"),
    )
    assertTrue("sum must be 2110", prom.contains("relais_completion_tokens_sum 2110"))
    assertTrue("count must be 3", prom.contains("relais_completion_tokens_count 3"))
  }

  @Test
  fun `completion-token histogram guards negative input`() {
    RelaisMetrics.recordCompletionTokens(-5) // must be ignored (no count, no sum contribution)
    RelaisMetrics.recordCompletionTokens(8)

    val prom = RelaisMetrics.renderProm(context)
    assertTrue("negative input must not inflate count", prom.contains("relais_completion_tokens_count 1"))
    assertTrue("negative input must not inflate sum", prom.contains("relais_completion_tokens_sum 8"))
  }

  // --- 3. thermal events -------------------------------------------------------------------------

  @Test
  fun `thermal events increment the right level and renderProm emits it`() {
    RelaisMetrics.recordThermalEvent(3) // severe
    RelaisMetrics.recordThermalEvent(3) // severe again
    RelaisMetrics.recordThermalEvent(4) // critical

    val prom = RelaisMetrics.renderProm(context)
    assertTrue(
      "two severe events must be counted",
      prom.contains("relais_thermal_events_total{level=\"severe\"} 2"),
    )
    assertTrue(
      "one critical event must be counted",
      prom.contains("relais_thermal_events_total{level=\"critical\"} 1"),
    )
  }

  @Test
  fun `thermalLabel maps the fixed whitelist and folds out-of-range to unknown`() {
    assertEquals("none", RelaisMetrics.thermalLabel(0))
    assertEquals("light", RelaisMetrics.thermalLabel(1))
    assertEquals("moderate", RelaisMetrics.thermalLabel(2))
    assertEquals("severe", RelaisMetrics.thermalLabel(3))
    assertEquals("critical", RelaisMetrics.thermalLabel(4))
    assertEquals("shutdown", RelaisMetrics.thermalLabel(6))
    assertEquals("unknown", RelaisMetrics.thermalLabel(7))
    assertEquals("unknown", RelaisMetrics.thermalLabel(-1))
  }

  // --- 4. endpoint-label cardinality guard -------------------------------------------------------

  @Test
  fun `endpointLabel folds unknown paths to other and keeps known endpoints`() {
    assertEquals("other", RelaisMetrics.endpointLabel("/etc/passwd"))
    assertEquals("other", RelaisMetrics.endpointLabel("/v1/chat/completions/../../secret"))
    assertEquals("/generate", RelaisMetrics.endpointLabel("/generate"))
    assertEquals("/v1/chat/completions", RelaisMetrics.endpointLabel("/v1/chat/completions"))
    assertEquals("/v1/embeddings", RelaisMetrics.endpointLabel("/v1/embeddings"))
    assertEquals("/v1/models", RelaisMetrics.endpointLabel("/v1/models"))
    assertEquals("/metrics", RelaisMetrics.endpointLabel("/metrics"))
    assertEquals("/health", RelaisMetrics.endpointLabel("/health"))
    assertEquals("/", RelaisMetrics.endpointLabel("/"))
  }

  /**
   * The CA export (feature-18) needs its own arm here, not just in the server's normalizer: the
   * server produces the `/ca.crt` label, and this function re-normalizes it at `recordRequest`.
   * Without an arm the route would silently report as `"other"` in `/metrics` — which is exactly
   * the series an operator reads when clients start failing to fetch the CA.
   */
  @Test
  fun `endpointLabel gives the CA export its own series and still folds near-misses to other`() {
    assertEquals("/ca.crt", RelaisMetrics.endpointLabel("/ca.crt"))
    // Exact `==`, like every arm here. Loosening it to `startsWith` would let an attacker mint an
    // unbounded number of real metric series by varying the suffix — the cardinality guard this
    // function exists to be.
    assertEquals("other", RelaisMetrics.endpointLabel("/ca.crtXYZ"))
    assertEquals("other", RelaisMetrics.endpointLabel("/ca.crt?x=1"))
  }

  // --- 5. Ring-buffer opt-out (feature-09 Task 1) -------------------------------------------------

  /**
   * The dashboard opts its own `200` page-loads out of the recent-request ring buffer, because at a
   * 10s auto-refresh they would crowd out the 20 slots the panel exists to show.
   *
   * Both halves matter and they pull in opposite directions, which is why they are asserted
   * together: the ring buffer must NOT grow, and the aggregate counter MUST still increment. A
   * `return` placed one line too early — before `requestCounts` rather than before the append —
   * passes the first assertion and fails the second, and would silently stop `/metrics` counting
   * the route.
   *
   * **Asserts on CONTENT, not size, and that is deliberate.** The buffer is capped at
   * `REQUEST_LOG_CAPACITY` = 20 and is process-global across the whole lane, so once it is full its
   * size is 20 whether or not an append happened — a size-delta assertion would pass vacuously
   * exactly when the suite is busiest. Each test uses a label unique to itself and asks whether that
   * label is present.
   */
  @Test
  fun `recordRequest with inRecentLog false skips the ring buffer but still counts`() {
    val label = "/__optout_probe"
    RelaisMetrics.recordRequest(label, 200, inRecentLog = false)

    assertTrue(
      "an opted-out request must not enter the recent-request ring buffer",
      RelaisMetrics.recentRequests().none { it.endpoint == label },
    )
    // The counter half pulls the opposite way: a `return` placed one line too early — before
    // `requestCounts` rather than before the append — would pass the assertion above and silently
    // stop /metrics counting the route.
    val line = RelaisMetrics.renderProm(context).lines()
      .firstOrNull { it.startsWith("""relais_requests_total{endpoint="$label",status="200"}""") }
    assertTrue(
      "the aggregate counter must still be incremented for an opted-out request (line=$line)",
      line != null && line.substringAfterLast(' ').trim().toLong() >= 1L,
    )
  }

  /** The default is unchanged: every other call site keeps appending to the buffer. */
  @Test
  fun `recordRequest still appends to the ring buffer by default`() {
    val label = "/__default_probe"
    RelaisMetrics.recordRequest(label, 200)
    assertTrue(
      "the default must still append — the opt-out is opt-in, not opt-out-by-default",
      RelaisMetrics.recentRequests().any { it.endpoint == label },
    )
  }
}

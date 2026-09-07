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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hermetic tests for the feature-20 time-to-first-token histograms on [RelaisMetrics]:
 *  1. `relais_time_to_first_token_seconds` — cumulative buckets, sum, count;
 *  2. `relais_decode_start_latency_seconds` — the same bounds as an INDEPENDENT series, so a
 *     request that records one but not the other cannot skew the other's count;
 *  3. the `resetIncrementsForTest` seam clears both;
 *  4. `ttft_p50_seconds` in the JSON HUD view.
 *
 * Uses Robolectric only for a Context (renderProm needs one for build_info), matching
 * [RelaisMetricsIncrementsTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelaisTtftMetricsTest {

  private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

  @Before
  fun reset() {
    RelaisMetrics.resetIncrementsForTest()
  }

  // --- 1. the TTFT histogram ---------------------------------------------------------------------

  @Test
  fun `ttft samples land in cumulative buckets with sum and count`() {
    RelaisMetrics.recordTimeToFirstToken(0.5)
    RelaisMetrics.recordTimeToFirstToken(2.0)

    val prom = RelaisMetrics.renderProm(context)

    assertTrue(
      "the series must be declared a histogram",
      prom.contains("# TYPE relais_time_to_first_token_seconds histogram"),
    )
    assertTrue(
      "le=0.25 must be 0 — neither sample is that fast",
      prom.contains("relais_time_to_first_token_seconds_bucket{le=\"0.25\"} 0"),
    )
    assertTrue(
      "le=0.5 must be cumulative-1 (bucket bounds are inclusive)",
      prom.contains("relais_time_to_first_token_seconds_bucket{le=\"0.5\"} 1"),
    )
    assertTrue(
      "le=2.0 must be cumulative-2",
      prom.contains("relais_time_to_first_token_seconds_bucket{le=\"2.0\"} 2"),
    )
    assertTrue(
      "+Inf must equal the total count",
      prom.contains("relais_time_to_first_token_seconds_bucket{le=\"+Inf\"} 2"),
    )
    assertTrue("_sum must be 2.5", prom.contains("relais_time_to_first_token_seconds_sum 2.5"))
    assertTrue("_count must be 2", prom.contains("relais_time_to_first_token_seconds_count 2"))
  }

  @Test
  fun `a ttft above the largest bound lands only in the +Inf bucket`() {
    RelaisMetrics.recordTimeToFirstToken(90.0)

    val prom = RelaisMetrics.renderProm(context)

    assertTrue(
      "le=60.0 must stay 0 — 90s is above the largest explicit bound",
      prom.contains("relais_time_to_first_token_seconds_bucket{le=\"60.0\"} 0"),
    )
    assertTrue(
      "+Inf must capture it",
      prom.contains("relais_time_to_first_token_seconds_bucket{le=\"+Inf\"} 1"),
    )
    assertTrue("_count must be 1", prom.contains("relais_time_to_first_token_seconds_count 1"))
  }

  // --- 2. the decode-start series is independent --------------------------------------------------

  @Test
  fun `decode-start latency is a separate series that ttft samples do not touch`() {
    // A request that recorded only the TTFT (decode-start unmeasured) must leave the second series
    // empty — the two are what an operator subtracts to get prefill, so cross-contamination would
    // silently corrupt that difference.
    RelaisMetrics.recordTimeToFirstToken(2.0)

    val prom = RelaisMetrics.renderProm(context)

    assertTrue(
      "ttft recorded the sample",
      prom.contains("relais_time_to_first_token_seconds_count 1"),
    )
    assertTrue(
      "decode-start must still be empty",
      prom.contains("relais_decode_start_latency_seconds_count 0"),
    )

    RelaisMetrics.recordDecodeStartLatency(0.25)
    val prom2 = RelaisMetrics.renderProm(context)

    assertTrue(
      "decode-start le=0.25 must be 1",
      prom2.contains("relais_decode_start_latency_seconds_bucket{le=\"0.25\"} 1"),
    )
    assertTrue(
      "decode-start _sum must be 0.25",
      prom2.contains("relais_decode_start_latency_seconds_sum 0.25"),
    )
    assertTrue(
      "the ttft count must be unchanged by the decode-start sample",
      prom2.contains("relais_time_to_first_token_seconds_count 1"),
    )
  }

  @Test
  fun `the two series are label-free`() {
    RelaisMetrics.recordTimeToFirstToken(1.0)
    RelaisMetrics.recordDecodeStartLatency(0.5)

    val prom = RelaisMetrics.renderProm(context)

    // Security M6: the only label either series may ever carry is the histogram's own `le`.
    for (line in prom.lines().filter { it.startsWith("relais_time_to_first_token_seconds") }) {
      assertFalse("unexpected label on: $line", line.contains("{") && !line.contains("{le="))
    }
    for (line in prom.lines().filter { it.startsWith("relais_decode_start_latency_seconds") }) {
      assertFalse("unexpected label on: $line", line.contains("{") && !line.contains("{le="))
    }
  }

  // --- 3. the reset seam --------------------------------------------------------------------------

  @Test
  fun `resetIncrementsForTest clears both series`() {
    RelaisMetrics.recordTimeToFirstToken(5.0)
    RelaisMetrics.recordDecodeStartLatency(1.0)

    RelaisMetrics.resetIncrementsForTest()
    val prom = RelaisMetrics.renderProm(context)

    assertTrue("ttft count cleared", prom.contains("relais_time_to_first_token_seconds_count 0"))
    assertTrue("ttft sum cleared", prom.contains("relais_time_to_first_token_seconds_sum 0.0"))
    assertTrue(
      "decode-start count cleared",
      prom.contains("relais_decode_start_latency_seconds_count 0"),
    )
    assertTrue(
      "decode-start sum cleared",
      prom.contains("relais_decode_start_latency_seconds_sum 0.0"),
    )
  }

  // --- 4. the JSON HUD view -----------------------------------------------------------------------

  @Test
  fun `renderJson exposes ttft_p50_seconds beside the inference latency quantiles`() {
    RelaisMetrics.recordTimeToFirstToken(0.5)
    RelaisMetrics.recordTimeToFirstToken(2.0)

    val json = RelaisMetrics.renderJson(context)

    assertTrue("HUD must expose a TTFT p50", json.has("ttft_p50_seconds"))
    assertEquals(
      "p50 of {0.5, 2.0} is the 0.5 bucket bound",
      0.5,
      json.getDouble("ttft_p50_seconds"),
      1e-9,
    )
  }

  @Test
  fun `ttft_p50_seconds is zero when nothing has been recorded`() {
    val json = RelaisMetrics.renderJson(context)
    assertEquals(
      "an empty histogram reports 0.0, not a bucket bound",
      0.0,
      json.getDouble("ttft_p50_seconds"),
      1e-9,
    )
  }
}

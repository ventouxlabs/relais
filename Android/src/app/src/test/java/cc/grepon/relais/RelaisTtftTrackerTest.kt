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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [RelaisTtftTracker] — no engine, no Context, no Robolectric.
 *
 * These pin the rule that a TTFT counts only a first visible token the client could actually
 * observe, and that "observable" means different things on the streaming and non-streaming paths.
 * The regression they exist for: the decode callback sets its throughput clock and appends to the
 * reply buffer BEFORE two cooperative-cancel early returns, so a streaming request could report a
 * TTFT for a token that never reached the socket.
 *
 * Timestamps are plain nanosecond longs so the assertions are exact.
 */
class RelaisTtftTrackerTest {

  private val convStartNs = 1_000_000_000L // t=1.0s
  private val sendStartNs = 3_000_000_000L // t=3.0s
  private val firstTokenNs = 5_500_000_000L // t=5.5s

  // --- streaming: only an accepted delta counts -----------------------------------------------

  @Test
  fun `streaming decode that is never delivered yields no TTFT`() {
    // The two cancel paths in RelaisEngine's callback: a cancel already decided during the
    // reasoning phase, and a thermal shouldCancel firing on this very first visible token. Both
    // append the token and return before onToken, so the client sees nothing.
    val tracker = RelaisTtftTracker(streaming = true)

    tracker.onVisibleTokenDecoded(firstTokenNs)

    assertNull(
      "a streaming token that never reached the consumer must not produce a TTFT",
      tracker.timeToFirstTokenSec(convStartNs),
    )
    assertNull(
      "the decode-start series must be null for the same reason",
      tracker.decodeStartLatencySec(sendStartNs),
    )
  }

  @Test
  fun `streaming delivery produces both figures against their own baselines`() {
    val tracker = RelaisTtftTracker(streaming = true)

    tracker.onVisibleTokenDecoded(firstTokenNs)
    tracker.onVisibleTokenDelivered(firstTokenNs)

    assertEquals(
      "TTFT is measured from conversation creation",
      4.5,
      tracker.timeToFirstTokenSec(convStartNs)!!,
      1e-9,
    )
    assertEquals(
      "decode-start latency is measured from sendMessageAsync",
      2.5,
      tracker.decodeStartLatencySec(sendStartNs)!!,
      1e-9,
    )
  }

  @Test
  fun `a broken pipe on the first delta leaves no TTFT, and a later token cannot rescue it`() {
    // onToken throwing means the write failed — nothing reached the client. The engine then
    // cancels, so no later delta is delivered either.
    val tracker = RelaisTtftTracker(streaming = true)

    tracker.onVisibleTokenDecoded(firstTokenNs) // decoded, append happened
    // no onVisibleTokenDelivered — the invoke threw
    tracker.onVisibleTokenDecoded(firstTokenNs + 100_000_000L) // next callback, still undelivered

    assertNull(
      "no delivered token means no TTFT, however many were decoded",
      tracker.timeToFirstTokenSec(convStartNs),
    )
  }

  // --- non-streaming: the append itself is delivery ---------------------------------------------

  @Test
  fun `non-streaming decode counts even when a cancel returns before onToken`() {
    // onToken is null here, and the token is already in the reply buffer, so it comes back in the
    // response body regardless of the thermal truncate that follows. Gating on "onToken was
    // invoked" would wrongly erase TTFT for every non-streaming request.
    val tracker = RelaisTtftTracker(streaming = false)

    tracker.onVisibleTokenDecoded(firstTokenNs)

    assertNotNull(
      "the non-streaming path delivers via the response body, not via onToken",
      tracker.timeToFirstTokenSec(convStartNs),
    )
    assertEquals(4.5, tracker.timeToFirstTokenSec(convStartNs)!!, 1e-9)
  }

  // --- shared invariants --------------------------------------------------------------------------

  @Test
  fun `the FIRST observable token wins, not the last`() {
    val tracker = RelaisTtftTracker(streaming = true)

    tracker.onVisibleTokenDelivered(firstTokenNs)
    tracker.onVisibleTokenDelivered(firstTokenNs + 2_000_000_000L)

    assertEquals(
      "a later token must not move the TTFT",
      4.5,
      tracker.timeToFirstTokenSec(convStartNs)!!,
      1e-9,
    )
  }

  @Test
  fun `a run with no visible token at all yields null on both series`() {
    // e.g. a cancel during the thinking phase — reasoning streamed, no visible answer.
    val tracker = RelaisTtftTracker(streaming = true)

    assertNull(tracker.timeToFirstTokenSec(convStartNs))
    assertNull(tracker.decodeStartLatencySec(sendStartNs))
  }

  @Test
  fun `an unset baseline yields null rather than a value measured from zero`() {
    // sendStartNs is 0 until the send is reached; a TTFT measured from epoch-zero would be a
    // ~decades-long sample quietly landing in the +Inf bucket.
    val tracker = RelaisTtftTracker(streaming = false)
    tracker.onVisibleTokenDecoded(firstTokenNs)

    assertNull(
      "an unset sendStartNs must not produce a decode-start sample",
      tracker.decodeStartLatencySec(0L),
    )
    assertNotNull(
      "the conversation baseline is set, so that series is still reported",
      tracker.timeToFirstTokenSec(convStartNs),
    )
  }
}

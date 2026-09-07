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

/**
 * Decides whether a request has a time-to-first-token at all, and what it is (feature-20).
 *
 * The rule this exists to enforce: **a TTFT counts only a first visible token the client could
 * actually observe.** That is not the same as "a visible token was decoded" — the decode callback
 * in [RelaisEngine.generate] sets its throughput clock and appends to the reply buffer BEFORE two
 * cooperative-cancel early returns, so a token can be decoded and counted for throughput while the
 * streaming consumer never receives it.
 *
 * What counts as observable differs by path, which is why this is a small state machine rather
 * than a subtraction:
 *  - **non-streaming** ([streaming] false): appending to the reply buffer IS delivery — the token
 *    comes back in the response body even if a thermal cancel truncates the run immediately after.
 *  - **streaming** ([streaming] true): only a delta the consumer accepted counts. A cancel that
 *    returns before the write, or a broken pipe that throws during it, means nothing reached the
 *    client and there is no TTFT to report.
 *
 * Both figures are null — never 0.0 — when nothing was delivered. A zero would be a fabricated
 * measurement, and in a histogram it is indistinguishable from a real sub-250 ms TTFT.
 *
 * Not thread-safe: the decode callback is sequential, and the post-await read is safe via the
 * latch's happens-before (the same discipline the surrounding token counters rely on).
 */
internal class RelaisTtftTracker(private val streaming: Boolean) {

  private var deliveredNs = 0L

  /**
   * A visible token was decoded and appended to the reply buffer. On the non-streaming path this
   * is the delivery point; on the streaming path it is not, and this call is deliberately inert.
   */
  fun onVisibleTokenDecoded(nowNs: Long) {
    if (!streaming) record(nowNs)
  }

  /** The streaming consumer accepted the delta (`onToken` returned without throwing). */
  fun onVisibleTokenDelivered(nowNs: Long) = record(nowNs)

  private fun record(nowNs: Long) {
    if (deliveredNs == 0L && nowNs > 0L) deliveredNs = nowNs
  }

  /** Seconds from conversation creation to the first observable token; null if there was none. */
  fun timeToFirstTokenSec(convStartNs: Long): Double? = secondsSince(convStartNs)

  /** Seconds from `sendMessageAsync` to the first observable token; null if there was none. */
  fun decodeStartLatencySec(sendStartNs: Long): Double? = secondsSince(sendStartNs)

  private fun secondsSince(baselineNs: Long): Double? =
    if (deliveredNs > 0L && baselineNs > 0L) (deliveredNs - baselineNs) / 1e9 else null
}

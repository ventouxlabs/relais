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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hermetic unit tests for [RelaisHttpGate] — the auth / rate-limit / body-cap decision that every
 * request passes through (#314). Pure JVM: no Context, no sockets, no `RelaisHttpServer` instance.
 *
 * The point of extracting [RelaisHttpGate.decide] out of `RelaisHttpServer.handle()` was to make
 * exactly these assertions reachable from a JVM test — before the extraction the gate lived inside a
 * `private fun` and nothing here could be written at all.
 */
class RelaisHttpGateTest {

  /** Mirrors `MAX_BODY_BYTES` (`RelaisHttpServer.kt:73`). Passed in explicitly to keep `decide` pure. */
  private val cap = 32 * 1024 * 1024

  /**
   * Defaults describe a boring, allowed request: an authenticated GET with an empty body that is
   * within its rate-limit budget. Each test overrides only the one axis it is about.
   */
  private fun decide(
    method: String = "GET",
    path: String = "/v1/models",
    authorized: Boolean = true,
    rateLimitOk: Boolean = true,
    exemptRateLimitOk: Boolean = true,
    contentLength: Int = 0,
  ): Int? =
    RelaisHttpGate.decide(
        method,
        path,
        { authorized },
        { rateLimitOk },
        { exemptRateLimitOk },
        contentLength,
        cap,
      )
      ?.status

  /** As [decide], but returns the reason rather than the status — the two 429s differ only there. */
  private fun reason(
    method: String = "GET",
    path: String = "/v1/models",
    authorized: Boolean = true,
    rateLimitOk: Boolean = true,
    exemptRateLimitOk: Boolean = true,
    contentLength: Int = 0,
  ): RelaisHttpGate.Reject? =
    RelaisHttpGate.decide(
      method,
      path,
      { authorized },
      { rateLimitOk },
      { exemptRateLimitOk },
      contentLength,
      cap,
    )

  // -------------------------------------------------------------------------
  // Auth exemptions — which paths may be reached without a bearer token
  // -------------------------------------------------------------------------

  @Test
  fun `an authenticated in-budget request passes`() {
    assertNull(decide())
  }

  @Test
  fun `an unauthenticated request to a normal route is 401`() {
    assertEquals(401, decide(path = "/v1/models", authorized = false))
  }

  @Test
  fun `GET health is auth-exempt`() {
    assertNull(decide(method = "GET", path = "/health", authorized = false))
  }

  /**
   * `/health` is matched with `startsWith`, so a query string still reaches the exemption. This is
   * the pre-existing contract (`RelaisHttpServer.kt:266` and the dispatch `when` both use
   * `startsWith`), pinned here so the restructure cannot narrow it by accident.
   */
  @Test
  fun `GET health with a query string is auth-exempt`() {
    assertNull(decide(method = "GET", path = "/health?verbose=1", authorized = false))
  }

  /** The exemption is GET-only: a POST to the same path still needs the key. */
  @Test
  fun `POST health is not auth-exempt`() {
    assertEquals(401, decide(method = "POST", path = "/health", authorized = false))
  }

  // -------------------------------------------------------------------------
  // /ca.crt — exact match, unlike /health's prefix match
  //
  // The route itself lands in feature-18 T6; the exemption is pre-placed here so its exact-match
  // semantics are pinned by test before any handler exists. Until T6, an unauthenticated
  // GET /ca.crt falls through the dispatch `when` to `404 not found` (RelaisHttpServer.kt:497).
  // These assert gate exemption, NOT route existence.
  // -------------------------------------------------------------------------

  @Test
  fun `GET ca_crt is auth-exempt`() {
    assertNull(decide(method = "GET", path = "/ca.crt", authorized = false))
  }

  /**
   * The `startsWith` trap: `/ca.crt` must be an exact match. A `startsWith("/ca.crt")` would hand an
   * auth exemption to every path that merely begins with it.
   */
  @Test
  fun `GET ca_crt with a trailing suffix is not auth-exempt`() {
    assertEquals(401, decide(method = "GET", path = "/ca.crtXYZ", authorized = false))
  }

  /**
   * A query string does NOT reach the `/ca.crt` exemption, deliberately: the dispatch `when` matches
   * the same raw path string, so `/ca.crt?x=1` would not route to the handler either. The exemption
   * and the route agree.
   */
  @Test
  fun `GET ca_crt with a query string is not auth-exempt`() {
    assertEquals(401, decide(method = "GET", path = "/ca.crt?x=1", authorized = false))
  }

  @Test
  fun `POST ca_crt is not auth-exempt`() {
    assertEquals(401, decide(method = "POST", path = "/ca.crt", authorized = false))
  }

  // -------------------------------------------------------------------------
  // Rate limiting — now unconditional (the #314 behavior change), against one of two budgets
  // -------------------------------------------------------------------------

  /**
   * BEHAVIOR CHANGE (#314): `/health` used to be exempt from rate limiting as a side effect of
   * sharing one negated condition with the auth exemption. It is auth-exempt and metered now — on
   * the auth-exempt budget, not the inference one.
   */
  @Test
  fun `a rate-limited GET health is 429`() {
    assertEquals(
      429,
      decide(method = "GET", path = "/health", authorized = false, exemptRateLimitOk = false),
    )
  }

  @Test
  fun `a rate-limited GET ca_crt is 429`() {
    assertEquals(
      429,
      decide(method = "GET", path = "/ca.crt", authorized = false, exemptRateLimitOk = false),
    )
  }

  @Test
  fun `a rate-limited authenticated request is 429`() {
    assertEquals(429, decide(path = "/v1/models", rateLimitOk = false))
  }

  // -------------------------------------------------------------------------
  // Budget selection — the two limiters must not be swappable
  //
  // These are the assertions that fail if the standard and auth-exempt budgets are exchanged. Each
  // exhausts ONE budget and leaves the other full, so a swap flips the outcome.
  // -------------------------------------------------------------------------

  /** An exempt route is charged the exempt budget: exhausting the standard one must not touch it. */
  @Test
  fun `GET health passes when only the standard budget is exhausted`() {
    assertNull(decide(method = "GET", path = "/health", authorized = false, rateLimitOk = false))
  }

  @Test
  fun `GET ca_crt passes when only the standard budget is exhausted`() {
    assertNull(decide(method = "GET", path = "/ca.crt", authorized = false, rateLimitOk = false))
  }

  /** And the converse: a normal route is charged the standard budget, never the exempt one. */
  @Test
  fun `a normal route passes when only the auth-exempt budget is exhausted`() {
    assertNull(decide(path = "/v1/models", exemptRateLimitOk = false))
  }

  /** A monitoring flood on `/health` must not lock out authenticated inference. */
  @Test
  fun `an exhausted auth-exempt budget does not reject an authenticated request`() {
    assertNull(decide(method = "POST", path = "/v1/chat/completions", exemptRateLimitOk = false))
  }

  /** The two 429s are distinguishable, so the body can quote the budget the caller actually hit. */
  @Test
  fun `the reject reason names which budget was exhausted`() {
    assertEquals(
      RelaisHttpGate.Reject.EXEMPT_RATE_LIMITED,
      reason(method = "GET", path = "/health", authorized = false, exemptRateLimitOk = false),
    )
    assertEquals(
      RelaisHttpGate.Reject.RATE_LIMITED,
      reason(path = "/v1/models", rateLimitOk = false),
    )
  }

  /** Both reasons answer 429 — the status is carried by the enum, not repeated at the call site. */
  @Test
  fun `both rate-limit reasons carry status 429`() {
    assertEquals(429, RelaisHttpGate.Reject.RATE_LIMITED.status)
    assertEquals(429, RelaisHttpGate.Reject.EXEMPT_RATE_LIMITED.status)
    assertEquals(401, RelaisHttpGate.Reject.UNAUTHORIZED.status)
    assertEquals(413, RelaisHttpGate.Reject.BODY_TOO_LARGE.status)
  }

  /**
   * Δ7, asserted so it cannot change silently: the 401 deliberately precedes the rate limiter, so a
   * failed-auth request is never counted against the per-IP budget. Reordering would let an
   * unauthenticated flood eat a legitimate client's budget from behind the same NAT address.
   * Metering failed auth separately is a tracked follow-up, not this change.
   */
  @Test
  fun `an unauthorized request is 401 even when the rate limit is also exhausted`() {
    assertEquals(401, decide(path = "/v1/models", authorized = false, rateLimitOk = false))
  }

  // -------------------------------------------------------------------------
  // Body cap — now unconditional (the #314 behavior change)
  // -------------------------------------------------------------------------

  @Test
  fun `an authenticated request over the body cap is 413`() {
    assertEquals(413, decide(method = "POST", path = "/v1/chat/completions", contentLength = cap + 1))
  }

  @Test
  fun `a request exactly at the body cap passes`() {
    assertNull(decide(method = "POST", path = "/v1/chat/completions", contentLength = cap))
  }

  /** BEHAVIOR CHANGE (#314): the body cap used to be skipped for `/health` along with auth. */
  @Test
  fun `a GET health over the body cap is 413`() {
    assertEquals(413, decide(method = "GET", path = "/health", authorized = false, contentLength = cap + 1))
  }

  /** Auth is checked before the body cap: an unauthenticated oversized body reveals only the 401. */
  @Test
  fun `an unauthorized oversized request is 401 not 413`() {
    assertEquals(401, decide(method = "POST", path = "/v1/models", authorized = false, contentLength = cap + 1))
  }

  /** The rate limiter is checked before the body cap, matching the pre-change ordering. */
  @Test
  fun `a rate-limited oversized request is 429 not 413`() {
    assertEquals(429, decide(method = "POST", path = "/v1/models", rateLimitOk = false, contentLength = cap + 1))
  }

  // -------------------------------------------------------------------------
  // Effect laziness — Δ7. `rateLimiter.allow(ip)` CONSUMES budget when called, so *whether* it is
  // called is behavior, not an optimization. A boolean parameter would evaluate both effects eagerly
  // and silently meter every failed-auth request; these count the calls.
  // -------------------------------------------------------------------------

  private class Counting(private val result: Boolean) : () -> Boolean {
    var calls = 0
      private set

    override fun invoke(): Boolean {
      calls++
      return result
    }
  }

  /**
   * Δ7: a 401 must not consume the caller's per-IP budget. Otherwise an unauthenticated flood would
   * exhaust a legitimate client's budget from behind the same NAT address.
   */
  @Test
  fun `no rate limiter is consulted when auth fails`() {
    val auth = Counting(false)
    val rate = Counting(true)
    val exempt = Counting(true)
    assertEquals(
      RelaisHttpGate.Reject.UNAUTHORIZED,
      RelaisHttpGate.decide("GET", "/v1/models", auth, rate, exempt, 0, cap),
    )
    assertEquals("auth must be evaluated", 1, auth.calls)
    assertEquals("a failed-auth request must stay unmetered", 0, rate.calls)
    assertEquals("a failed-auth request must not touch the exempt budget either", 0, exempt.calls)
  }

  /** An auth-exempt path skips the key comparison entirely, exactly as the pre-change gate did. */
  @Test
  fun `the key comparison is not run for an auth-exempt path`() {
    val auth = Counting(false)
    val rate = Counting(true)
    val exempt = Counting(true)
    assertNull(RelaisHttpGate.decide("GET", "/health", auth, rate, exempt, 0, cap))
    assertEquals("an exempt path must not run the key comparison", 0, auth.calls)
    assertEquals("an exempt path is still metered", 1, exempt.calls)
  }

  /**
   * Exactly one budget is charged per request, and it is the one matching the exemption. Counting
   * the calls — not just reading the status — is what makes a swap of the two limiters detectable
   * even when both happen to be under their ceiling.
   */
  @Test
  fun `an exempt route charges the exempt budget and only that one`() {
    val rate = Counting(true)
    val exempt = Counting(true)
    assertNull(RelaisHttpGate.decide("GET", "/health", Counting(false), rate, exempt, 0, cap))
    assertEquals("the exempt budget must be charged", 1, exempt.calls)
    assertEquals("the standard budget must be untouched", 0, rate.calls)
  }

  @Test
  fun `a normal route charges the standard budget and only that one`() {
    val rate = Counting(true)
    val exempt = Counting(true)
    assertNull(RelaisHttpGate.decide("GET", "/v1/models", Counting(true), rate, exempt, 0, cap))
    assertEquals("the standard budget must be charged", 1, rate.calls)
    assertEquals("the exempt budget must be untouched", 0, exempt.calls)
  }
}

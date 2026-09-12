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

/**
 * The request gate: auth exemption, rate limiting, and the body-size cap, as one pure decision
 * (extracted from `RelaisHttpServer.handle()` — #314).
 *
 * Before the extraction all three checks shared a single negated condition, so exempting `/health`
 * from auth silently exempted it from rate limiting and the body cap too. They are independent now:
 * only the *auth* check consults the exemption list.
 *
 * [decide] is deliberately pure and hermetic — `handle()` keeps ownership of the side effects
 * (`authorized(...)`, `rateLimiter.allow(ip)`) and passes their results in, and keeps ownership of
 * every response body, so the `RelaisError` envelopes stay at their original call sites.
 */
internal object RelaisHttpGate {

  /**
   * Why the gate rejected a request, and the HTTP status `handle()` answers with. [status] is the
   * single source of that code — `handle()` passes it to `reply()` rather than repeating a literal,
   * so a change here cannot leave the server answering something the tests do not see.
   *
   * The two rate-limit reasons share a status and differ only in which budget was exhausted, which
   * the `429` body quotes back to the caller.
   */
  enum class Reject(val status: Int) {
    UNAUTHORIZED(401),
    CROSS_SITE(403),
    RATE_LIMITED(429),
    EXEMPT_RATE_LIMITED(429),
    BODY_TOO_LARGE(413),
  }

  /**
   * Null = let the request through; otherwise the reason to reject it.
   *
   * Ordering is load-bearing and unchanged from the pre-extraction gate:
   *  1. **Auth**, which the exempt paths skip.
   *  1b. **Cross-site**, for Basic-authenticated requests only (feature-09). Sits here — after auth,
   *     **before both budgets** — for the same reason the 401 does: an attacker page runs inside the
   *     operator's own browser and therefore on the operator's own IP, so metering these rejects
   *     would let a hostile page burn the operator's budget. It is also the correct response
   *     ordering: a 429 or 413 must not mask the 403.
   *  2. **Rate limit** — the 401 deliberately precedes this, so a failed-auth request is never
   *     counted against the per-IP budget. Reordering would let an unauthenticated flood consume a
   *     legitimate client's budget from behind the same NAT address. Metering failed auth separately
   *     is a tracked follow-up; brute force against the `/v1` routes remains unlimited today.
   *  3. **Body cap.**
   *
   * Step 2 meters against one of *two* budgets, chosen by the same [authExempt] predicate that
   * decided step 1. Deriving both from one predicate is the point: an exemption can never acquire a
   * budget it was not meant to have, because there is no second place to edit. Auth-exempt routes
   * are cheap, unauthenticated, and what monitoring polls, so they get the larger budget — sharing
   * the inference budget would let a poller starve the work the node exists to do.
   *
   * Every effect is a supplier, not a boolean, and that is **load-bearing**: `RateLimiter.allow`
   * *consumes* budget as a side effect (it appends to the per-IP window), so evaluating eagerly
   * would meter every failed-auth request and let an unauthenticated flood eat a legitimate client's
   * budget from behind the same NAT address — the precise outcome step 2 above exists to avoid.
   * Laziness reproduces the original short-circuit order exactly: an exempt path never runs the key
   * comparison, a 401 never touches a rate limiter, and **exactly one** of the two budgets is ever
   * charged for a given request.
   *
   * @param authorized the credential check; returns the scheme that authenticated, or null. It
   *   reports the **scheme** rather than a boolean so this function can decide whether the
   *   cross-site guard applies without re-parsing the header — keeping the auth outcome in exactly
   *   one place, which is what the #314/#317 extraction bought.
   * @param rejectsAsCrossSite whether the request reads as cross-site. Consulted **only** when the
   *   scheme is [AuthScheme.BASIC]. A supplier, so the `Origin`/`Referer` work never runs for the
   *   Bearer traffic that is the overwhelming majority. Note this gate learns the *outcome* and
   *   never the header values: the comparison algorithm lives beside the auth code, so `decide`
   *   stays an ordering function rather than hosting a second algorithm.
   * @param rateLimitOk the standard per-IP budget, charged for non-exempt routes.
   * @param exemptRateLimitOk the larger auth-exempt budget, charged for exempt routes only.
   * @param contentLength the parsed `Content-Length`, and [maxBody] `MAX_BODY_BYTES` — both `Int`,
   *   matching the types at the call site rather than widening at the boundary.
   */
  fun decide(
    method: String,
    path: String,
    authorized: () -> AuthScheme?,
    rejectsAsCrossSite: () -> Boolean,
    rateLimitOk: () -> Boolean,
    exemptRateLimitOk: () -> Boolean,
    contentLength: Int,
    maxBody: Int,
  ): Reject? {
    val exempt = authExempt(method, path)
    // Nested, not flattened: an exempt path must never run `authorized()`, so it has no scheme and
    // the cross-site guard CANNOT fire on it — true by construction here rather than by argument.
    if (!exempt) {
      val scheme = authorized() ?: return Reject.UNAUTHORIZED
      if (scheme == AuthScheme.BASIC && rejectsAsCrossSite()) return Reject.CROSS_SITE
    }
    if (exempt) {
      if (!exemptRateLimitOk()) return Reject.EXEMPT_RATE_LIMITED
    } else {
      if (!rateLimitOk()) return Reject.RATE_LIMITED
    }
    if (contentLength > maxBody) return Reject.BODY_TOO_LARGE
    return null
  }

  /**
   * Does [path] address the health endpoint?
   *
   * **This is the single definition, and that is load-bearing.** Three sites must agree on it:
   *  1. the auth exemption and budget selection in [decide],
   *  2. the dispatch `when` in `RelaisHttpServer.handle()`, which routes to `handleHealth`,
   *  3. `RelaisHttpServer.endpointLabel`, which names the metrics series.
   *
   * Drift between 1 and 2 would let a request take the auth exemption *and* the larger exempt budget
   * while routing into a protected handler — `/health/../v1/models` is the shape to think about.
   * Drift between either and 3 mislabels the metrics for the one route whose rate-limiting behavior
   * changed in #314, which is exactly the series an operator reads when they start seeing `429`s.
   * All three call this function, so an edit here moves all three at once. **Do not reintroduce a
   * local copy** — feature-18 T6 (which adds the `/ca.crt` route) and feature-09 (which moves
   * `handleDashboard`) both touch these sites.
   *
   * `startsWith`, not `==`, so a query string still matches — the pre-existing contract.
   */
  fun isHealthPath(path: String): Boolean = path.startsWith("/health")

  /**
   * Does [path] address the CA-certificate export?
   *
   * **Exact** match, deliberately asymmetric with [isHealthPath]: a `startsWith("/ca.crt")` would
   * hand the auth exemption to `/ca.crtXYZ` and anything else merely beginning with it.
   *
   * The route lands in feature-18 T6; until then this has one caller ([authExempt]) and an
   * unauthenticated `GET /ca.crt` falls through the dispatch `when` to `404 not found`. T6 must add
   * its dispatch branch and its metrics label through *this* predicate, not a fresh literal.
   */
  fun isCaCertPath(path: String): Boolean = path == "/ca.crt"

  /** The paths reachable without a bearer token, and the ones charged the auth-exempt budget. */
  private fun authExempt(method: String, path: String): Boolean =
    method == "GET" && (isHealthPath(path) || isCaCertPath(path))
}

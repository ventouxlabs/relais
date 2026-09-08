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

  /** Why the gate rejected a request, and the HTTP status `handle()` answers with. */
  enum class Reject(val status: Int) {
    UNAUTHORIZED(401),
    RATE_LIMITED(429),
    BODY_TOO_LARGE(413),
  }

  /**
   * Null = let the request through; otherwise the reason to reject it.
   *
   * Ordering is load-bearing and unchanged from the pre-extraction gate:
   *  1. **Auth**, which the exempt paths skip.
   *  2. **Rate limit** — the 401 deliberately precedes this, so a failed-auth request is never
   *     counted against the per-IP budget. Reordering would let an unauthenticated flood consume a
   *     legitimate client's budget from behind the same NAT address. Metering failed auth separately
   *     is a tracked follow-up; brute force against the `/v1` routes remains unlimited today.
   *  3. **Body cap.**
   *
   * [authorized] and [rateLimitOk] are suppliers, not booleans, and that is **load-bearing**:
   * `rateLimiter.allow(ip)` *consumes* budget as a side effect (it appends to the per-IP window), so
   * evaluating it eagerly would meter every failed-auth request and let an unauthenticated flood eat
   * a legitimate client's budget from behind the same NAT address — the precise outcome step 2 above
   * exists to avoid. Laziness reproduces the original short-circuit order exactly: an exempt path
   * never runs the key comparison, and a 401 never touches the rate limiter.
   *
   * @param contentLength the parsed `Content-Length`, and [maxBody] `MAX_BODY_BYTES` — both `Int`,
   *   matching the types at the call site rather than widening at the boundary.
   */
  fun decide(
    method: String,
    path: String,
    authorized: () -> Boolean,
    rateLimitOk: () -> Boolean,
    contentLength: Int,
    maxBody: Int,
  ): Reject? {
    if (!authExempt(method, path) && !authorized()) return Reject.UNAUTHORIZED
    if (!rateLimitOk()) return Reject.RATE_LIMITED
    if (contentLength > maxBody) return Reject.BODY_TOO_LARGE
    return null
  }

  /**
   * The paths reachable without a bearer token. Note the deliberate asymmetry:
   *
   * - `/health` uses `startsWith`, matching both the pre-existing gate and the dispatch `when`, so a
   *   query string still reaches it.
   * - `/ca.crt` is an **exact** match. A `startsWith("/ca.crt")` would hand the exemption to
   *   `/ca.crtXYZ` and anything else merely beginning with it.
   *
   * The `/ca.crt` route itself lands in feature-18 T6; the exemption is pre-placed so its
   * exact-match semantics are pinned by `RelaisHttpGateTest` before any handler exists. Until then
   * an unauthenticated `GET /ca.crt` falls through the dispatch `when` to `404 not found`.
   */
  private fun authExempt(method: String, path: String): Boolean =
    method == "GET" && (path.startsWith("/health") || path == "/ca.crt")
}

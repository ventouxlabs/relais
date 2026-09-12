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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64 as JvmBase64

/**
 * Hermetic unit tests for the feature-09 auth + CSRF helpers: [extractApiKey], [authenticate],
 * [challengeHeaders] and [rejectsAsCrossSite].
 *
 * **This is the first JVM coverage the credential check has ever had.** Before this change the logic
 * lived in a `private fun authorized(header:)` member and nothing here could reach it; grep confirmed
 * no test in the tree called it. That gap is why the scheme-less-key acceptance below survived
 * unnoticed for as long as it did.
 *
 * Everything under test is `android.*`-free by design, not by accident. `isReturnDefaultValues = true`
 * (build.gradle.kts) makes unmocked Android calls return defaults in this lane, so an
 * `android.util.Base64` decode inside [extractApiKey] would return nothing and make every NEGATIVE
 * assertion below pass for the wrong reason — the malformed and colon-less rows assert `null`, which
 * is exactly what a defaulted decode produces.
 */
class RelaisHttpAuthTest {

  private val key = "TEST-KEY-123"

  private fun basic(userAndPass: String): String =
    "Basic " + JvmBase64.getEncoder().encodeToString(userAndPass.toByteArray())

  // ---------------------------------------------------------------------------
  // 1. extractApiKey — scheme parsing
  // ---------------------------------------------------------------------------

  @Test
  fun `8a Bearer is still accepted and reports BEARER`() {
    assertEquals(AuthScheme.BEARER to key, extractApiKey("Bearer $key"))
  }

  @Test
  fun `8b Basic with a username is accepted and the username is dropped`() {
    assertEquals(AuthScheme.BASIC to key, extractApiKey(basic("anyuser:$key")))
  }

  @Test
  fun `8c Basic with a blank username is accepted`() {
    assertEquals(AuthScheme.BASIC to key, extractApiKey(basic(":$key")))
  }

  @Test
  fun `8d Basic splits on the FIRST colon so a key containing colons survives`() {
    assertEquals(AuthScheme.BASIC to "K:EY", extractApiKey(basic("u:K:EY")))
  }

  @Test
  fun `8e malformed base64 returns null and does not throw`() {
    assertNull(extractApiKey("Basic !!!!"))
    assertNull(extractApiKey("Basic ????"))
  }

  @Test
  fun `8f an unknown scheme, a null header and an empty header are all null`() {
    assertNull(extractApiKey("Digest abc"))
    assertNull(extractApiKey(null))
    assertNull(extractApiKey(""))
    assertNull(extractApiKey("Bearer"))
  }

  /**
   * The wire-visible tightening. `header.removePrefix("Bearer ")` returned the header UNCHANGED when
   * the prefix was absent, so a bare scheme-less key authenticated. Nothing documents that — the
   * README, SECURITY.md and every `*-api.md` specify `Bearer`. It was an artefact of `removePrefix`,
   * not a contract.
   */
  @Test
  fun `8g a bare scheme-less key is rejected`() {
    assertNull(extractApiKey(key))
  }

  @Test
  fun `8h the scheme is reported, not just the key`() {
    assertEquals(AuthScheme.BEARER, extractApiKey("Bearer $key")?.first)
    assertEquals(AuthScheme.BASIC, extractApiKey(basic(":$key"))?.first)
  }

  /**
   * **The same hole as 8g, wearing the new carrier.** Kotlin's one-arg `substringAfter` defaults
   * `missingDelimiterValue` to the RECEIVER, so a colon-less payload would come back unchanged and
   * `Basic base64(rawkey)` would authenticate — reintroducing through Basic the exact scheme-less
   * acceptance this change closes.
   *
   * Proven RED: implemented with a bare `substringAfter(":")` first, this returned
   * `(BASIC, "TEST-KEY-123")` and the assertion failed.
   */
  @Test
  fun `8k Basic with no colon at all is rejected`() {
    assertNull(extractApiKey(basic(key)))
    assertNull(extractApiKey(basic("nocolonhere")))
  }

  /**
   * **The decoded password is DATA and is taken literally; the base64 blob is SYNTAX and is trimmed.**
   * That asymmetry is the whole content of this test, and it is the one a tidy-up will try to remove.
   *
   * The plan justified trimming both branches as "pre-existing Bearer behaviour; dropping it would be
   * a second silent tightening". True of Bearer, and it does not transfer: Basic ships for the first
   * time in this change, so there is no pre-existing behaviour to tighten and nothing to be silent
   * about. Trimming the password means `base64(":KEY ")` and `base64(":KEY")` are the same credential
   * — a second spelling of the key that no client sends and no documentation mentions.
   */
  @Test
  fun `8n the decoded Basic password is taken literally, not trimmed`() {
    assertNull(
      "a trailing space inside the credential is part of the password and must not authenticate",
      authenticate(basic(":$key "), key),
    )
    assertNull(
      "a leading space likewise",
      authenticate(basic(": $key"), key),
    )
    assertEquals(
      "the exact credential still authenticates",
      AuthScheme.BASIC,
      authenticate(basic(":$key"), key),
    )
    // The base64 blob itself is header syntax; surrounding whitespace there is still tolerated.
    assertEquals(
      "whitespace around the base64 blob is syntax, not data, and stays tolerated",
      AuthScheme.BASIC,
      authenticate(basic(":$key").replaceFirst("Basic ", "Basic   "), key),
    )
    // Bearer is untouched: trimming there IS pre-existing behaviour and dropping it would be the
    // silent tightening the plan warned about.
    assertEquals(
      "Bearer keeps its pre-existing trim",
      AuthScheme.BEARER,
      authenticate("Bearer $key  ", key),
    )
  }

  /**
   * Deliberately narrower than RFC 7235, which makes the auth-scheme token case-INSENSITIVE.
   * Accepting `bearer`/`basic` would be a widening shipped in the same change as an advertised
   * tightening, and nothing in this repo or its docs sends a lowercase scheme. Decided, not
   * overlooked — this row exists so a reviewer who reads the RFC does not file it as a bug.
   */
  @Test
  fun `8l the scheme token is matched case-sensitively, deliberately`() {
    assertNull(extractApiKey("bearer $key"))
    assertNull(extractApiKey(basic(":$key").replaceFirst("Basic", "basic")))
  }

  // ---------------------------------------------------------------------------
  // 2. authenticate — the parse/compare SEAM
  // ---------------------------------------------------------------------------

  /**
   * **The single most consequential assertion in this change.** [extractApiKey] is tested pure above
   * and the gate is tested with an injected fake in `RelaisHttpGateTest`; nothing else checks that a
   * successful Basic comparison still REPORTS Basic. An implementation returning `BEARER` after any
   * successful compare passes every other test in this change while every real Basic request skips
   * the CSRF guard — the feature would not exist, with a green suite.
   *
   * Proven RED: with the compare returning `AuthScheme.BEARER` unconditionally, this failed with
   * `expected:<BASIC> but was:<BEARER>` while the whole of `RelaisHttpGateTest` stayed green.
   */
  @Test
  fun `8s authenticate preserves the scheme that actually authenticated`() {
    assertEquals(
      "a valid Basic credential must report BASIC, or the CSRF guard never fires",
      AuthScheme.BASIC,
      authenticate(basic(":$key"), key),
    )
    assertEquals(
      "a valid Bearer credential must report BEARER",
      AuthScheme.BEARER,
      authenticate("Bearer $key", key),
    )
  }

  @Test
  fun `authenticate rejects a wrong key under either scheme`() {
    assertNull(authenticate("Bearer WRONG", key))
    assertNull(authenticate(basic(":WRONG"), key))
    assertNull(authenticate(null, key))
  }

  /** A key that is a prefix of the real one must not authenticate (constant-time compare, not startsWith). */
  @Test
  fun `authenticate rejects a prefix of the real key`() {
    assertNull(authenticate("Bearer ${key.dropLast(1)}", key))
    assertNull(authenticate("Bearer $key-extra", key))
  }

  // ---------------------------------------------------------------------------
  // 3. challengeHeaders — 401 + HTML only
  //
  // Every `emptyList()` row below shares a @Test with a positive row, deliberately. An
  // `assertEquals(emptyList(), ...)` passes against an implementation that returns empty for
  // EVERYTHING, so on its own it asserts nothing. Its positive twin is what makes it discriminating
  // — and the twin has to be in the same test method: split across two tests, an always-empty
  // implementation fails only one of them, which reads as a single unrelated failure rather than as
  // the contract being gone. Do not "tidy" these into one-assertion-per-test.
  // ---------------------------------------------------------------------------

  private val challenge = """WWW-Authenticate: Basic realm="Relais", charset="UTF-8""""

  @Test
  fun `8m the challenge rides a 401 to an HTML client and nothing else`() {
    assertEquals(listOf(challenge), challengeHeaders(401, "text/html,application/xhtml+xml"))

    assertEquals("a 429 must not carry a challenge", emptyList<String>(), challengeHeaders(429, "text/html"))
    assertEquals("a 413 must not carry a challenge", emptyList<String>(), challengeHeaders(413, "text/html"))
    // A 403 means the credential was ACCEPTED and the context rejected. Challenging would tell the
    // browser to re-prompt for a key that is already correct.
    assertEquals("a 403 must not carry a challenge", emptyList<String>(), challengeHeaders(403, "text/html"))

    // An unconditional challenge would put a browser auth prompt in front of every SDK's error path.
    assertEquals("a non-HTML 401 stays bare", emptyList<String>(), challengeHeaders(401, "application/json"))
    assertEquals("a 401 with no Accept stays bare", emptyList<String>(), challengeHeaders(401, null))
  }

  /**
   * `Accept` is a list of media RANGES with parameters, not a string to search. Substring matching
   * answers a question nobody asked — "do these nine characters appear anywhere in the header" —
   * which is a different question from "does this client accept HTML", and the two disagree in both
   * directions.
   *
   * The effect of getting it wrong is cosmetic (a challenge header on a JSON 401 that no SDK reads),
   * so this is not a security fix. It is here because the KDoc above promises "an HTML client and
   * nothing else", and a promise the code does not keep is what the next person will build on.
   */
  @Test
  fun `8o Accept is parsed as media ranges, not substring-matched`() {
    // False positive: text/html appears only inside a parameter value of a JSON range.
    assertEquals(
      "text/html inside a parameter value does not make this an HTML client",
      emptyList<String>(),
      challengeHeaders(401, """application/json; profile="text/html""""),
    )
    // False positive: a client explicitly REFUSING html still matched `contains`.
    assertEquals(
      "q=0 is an explicit refusal and must not draw a challenge",
      emptyList<String>(),
      challengeHeaders(401, "application/json, text/html;q=0"),
    )
    // The ordinary browser string still works, parameters and all.
    assertEquals(
      "the real Chrome/Firefox Accept must still be served a challenge",
      listOf(challenge),
      challengeHeaders(401, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,*/*;q=0.8"),
    )
    // A weighted but non-zero preference is still acceptance.
    assertEquals(
      "q=0.1 is a weak preference, not a refusal",
      listOf(challenge),
      challengeHeaders(401, "application/json, text/html;q=0.1"),
    )
    // Whitespace and case are insignificant in a media range.
    assertEquals(
      "media ranges are case-insensitive and space-tolerant",
      listOf(challenge),
      challengeHeaders(401, "  TEXT/HTML ;  Q=1 "),
    )
    // A wildcard is not a request for HTML; curl sends `*/*` and must not get a browser prompt.
    assertEquals(
      "*/* is what curl sends, and it must stay bare",
      emptyList<String>(),
      challengeHeaders(401, "*/*"),
    )
  }

  // ---------------------------------------------------------------------------
  // 4. rejectsAsCrossSite — the guard's rule
  //
  // Run as a TABLE, deliberately. Individually, every reject row also passes an "always reject"
  // implementation and every allow row passes an "always allow" one — only the combination
  // discriminates. Over five review rounds this table was extended four times by four different
  // axes (port stripping, asymmetric normalization, missing scheme comparison, bracket blindness,
  // default-port polarity), each of which passed every row that existed before it.
  // ---------------------------------------------------------------------------

  private fun cross(
    method: String = "POST",
    sfs: String? = null,
    origin: String? = null,
    referer: String? = null,
    host: String? = "node:8443",
    tls: Boolean = true,
  ) = rejectsAsCrossSite(
    // Named for the same reason the production call site is: parameters 2-5 are four consecutive
    // `String?` and any permutation of them compiles.
    method = method, secFetchSite = sfs, origin = origin, referer = referer, host = host, tls = tls,
  )

  @Test
  fun `8i Sec-Fetch-Site values, with none and same-origin allowed`() {
    // `none` is what an address-bar navigation or bookmark sends and an attacker page cannot produce
    // it. Rejecting it would 403 the operator's very first page load — the feature itself.
    assertEquals("absent must be allowed on GET", false, cross(method = "GET", sfs = null))
    assertEquals("none must be allowed", false, cross(sfs = "none"))
    assertEquals("same-origin must be allowed", false, cross(sfs = "same-origin"))
    assertEquals("cross-site must reject", true, cross(sfs = "cross-site"))
    assertEquals("same-site must reject", true, cross(sfs = "same-site"))
    assertEquals("an unrecognised value must be allowed", false, cross(sfs = "garbage"))
    // Case and whitespace come off the wire unpredictably; the header loop lowercases, and this
    // pins that the predicate does not depend on that alone.
    assertEquals("case-insensitive", true, cross(sfs = "CROSS-SITE"))
  }

  @Test
  fun `8j the non-GET Origin fallback, as a table`() {
    // (i) nothing to check => reject. A state-changing request with no provenance at all.
    assertEquals("i: no Origin and no Referer", true, cross())
    // (ii) same authority => allow.
    assertEquals("ii: same authority", false, cross(origin = "https://node:8443"))
    // (iii) different host => reject.
    assertEquals("iii: foreign host", true, cross(origin = "https://evil.example"))
    // (iv) same host, DIFFERENT PORT => reject. With (ii), catches port-stripping.
    assertEquals("iv: same host different port", true, cross(origin = "https://node:9999"))
    // (v) bracketed IPv6 literal, explicit port. With (ii), catches string-slicing.
    assertEquals(
      "v: bracketed IPv6 with explicit port",
      false,
      cross(origin = "https://[::1]:8443", host = "[::1]:8443"),
    )
    // (vi) no Host header at all => reject (nothing to compare against).
    assertEquals("vi: absent Host", true, cross(origin = "https://node:8443", host = null))
    // (vii) Referer-only fallback — the second limb, otherwise never exercised.
    assertEquals("vii: Referer only", false, cross(referer = "https://node:8443/dashboard"))
    assertEquals("vii-b: foreign Referer only", true, cross(referer = "https://evil.example/x"))
    // (viii) malformed URL must REJECT, not throw and not pass.
    assertEquals("viii: malformed Origin", true, cross(origin = "ht!tp://[[["))
    // (ix) default port on BOTH sides. Catches asymmetric normalization: fails unless both the
    // Origin and the Host side fill their absent port.
    assertEquals("ix: https default port", false, cross(origin = "https://node", host = "node"))
    // (x) same authority, DIFFERENT SCHEME. Every other row shares a scheme, so only this one
    // catches a missing scheme comparison — `tls` alone supplies a default port, nothing more.
    assertEquals("x: scheme mismatch", true, cross(origin = "http://node:8443"))
    // (xi) parses, but names no host.
    assertEquals("xi: no host", true, cross(origin = "https:///path"))
    // (xii) same-origin RFC1918. Catches an SSRF-guard copy: WebhookGuard.classify blocks
    // isSiteLocalAddress, which is the ONLY network this dashboard is reached on.
    assertEquals(
      "xii: RFC1918 same-origin must be ALLOWED",
      false,
      cross(origin = "https://192.168.1.2:8443", host = "192.168.1.2:8443"),
    )
    // (xiii) portless IPv6. Catches "contains ':' => has a port", true of every bracketed literal.
    assertEquals(
      "xiii: portless IPv6 default port",
      false,
      cross(origin = "https://[::1]", host = "[::1]"),
    )
    // (xiv) PLAINTEXT default port. (ix) and (xiii) are both tls=true, so a mutation that always
    // fills 443 passes them; this is the only row where the correct fill is 80.
    assertEquals(
      "xiv: http default port",
      false,
      cross(origin = "http://node", host = "node", tls = false),
    )
    // (xv) and (xvi) are METHOD rows in a table about the non-GET fallback, and that is the point:
    // they pin the boundary between the two checks rather than anything inside either one. Every
    // other row here runs at the default POST, and 8i's only GET row passes `sfs = null`, so before
    // these two nothing in the suite paired an explicit Sec-Fetch-Site with a non-POST method.
    //
    // (xv) A cross-site GET must STILL be rejected. Hoisting the GET short-circuit above the
    // Sec-Fetch-Site check — "test the cheap predicate first", an ordinary tidy-up that reads as a
    // no-op reordering — deletes cross-site-GET protection outright, and passed every row that
    // existed before this one.
    assertEquals("xv: a cross-site GET is still rejected", true, cross(method = "GET", sfs = "cross-site"))
    // (xvi) HEAD is not GET, so it takes the fallback and fails closed. Pins the KDoc's documented
    // "403 rather than 404" claim, which `!= "POST"` in place of `== "GET"` would otherwise break
    // silently.
    assertEquals("xvi: HEAD is not GET and fails closed", true, cross(method = "HEAD"))
  }

  /**
   * GET keeps the old behaviour regardless of Origin/Referer: a GET is not the state change this
   * guard exists to stop, and the address-bar and meta-refresh cases have no Origin to check either.
   */
  @Test
  fun `a GET with no Sec-Fetch-Site is allowed whatever its Origin`() {
    assertEquals(false, cross(method = "GET"))
    assertEquals(false, cross(method = "GET", origin = "https://evil.example"))
    assertEquals(false, cross(method = "get", origin = "https://evil.example"))
  }

  /** An explicit Sec-Fetch-Site always wins; the Origin fallback is only for its absence. */
  @Test
  fun `an explicit Sec-Fetch-Site short-circuits the Origin fallback`() {
    // Foreign Origin, but the browser says same-origin: trust the browser-set header.
    assertEquals(false, cross(sfs = "same-origin", origin = "https://evil.example"))
    // Matching Origin, but the browser says cross-site: still reject.
    assertEquals(true, cross(sfs = "cross-site", origin = "https://node:8443"))
  }

  /** Origin is preferred over Referer when both are present. */
  @Test
  fun `Origin wins over Referer when both are present`() {
    assertEquals(
      "a good Origin is not overridden by a foreign Referer",
      false,
      cross(origin = "https://node:8443", referer = "https://evil.example/x"),
    )
    assertEquals(
      "a foreign Origin is not rescued by a matching Referer",
      true,
      cross(origin = "https://evil.example", referer = "https://node:8443/x"),
    )
  }

  /** `Origin: null` — what a sandboxed iframe or a cross-origin redirect sends — must not pass. */
  @Test
  fun `an opaque null Origin is rejected`() {
    assertTrue("Origin: null must never be treated as same-origin", cross(origin = "null"))
  }
}

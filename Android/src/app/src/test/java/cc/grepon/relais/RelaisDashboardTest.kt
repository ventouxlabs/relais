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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the pure dashboard functions (no Context, no Android).
 *
 * Tests:
 *  1. Status-label mapping (LIVE / STARTING / OFFLINE)
 *  2. Thermal-label mapping (0..6 -> name, out-of-range -> UNKNOWN)
 *  3. Field pass-through (assembler doesn't drop or reorder values)
 *  4. escapeHtml helper (XSS guard: < > & " ' all escaped)
 *  5. Render smoke (RELAIS wordmark, amber #FFB000, scriptless, no model-switch endpoint injected)
 */
class RelaisDashboardTest {

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private val sampleRequests = listOf(
    RequestLogEntry(endpoint = "/v1/chat/completions", status = 200, ageSeconds = 5L),
    RequestLogEntry(endpoint = "/generate", status = 503, ageSeconds = 12L),
  )

  private fun liveStatus() = assembleDashboardStatus(
    engineReady = true,
    listenersUp = true,
    startupInProgress = false,
    thermalStatus = 0,
    decodeTokensPerSec = 5.63,
    currentModelId = "litert-community/gemma-4-E4B-it-litert-lm",
    uptimeSeconds = 120.0,
    queueDepth = 0,
    errorsTotal = 0L,
    shedTotal = 0L,
    recentRequests = sampleRequests,
    baseUrl = "https://192.168.1.42:8443/v1",
    apiKeyMasked = "abcd…wxyz",
    capabilities = "multimodal,tools,reasoning",
  )

  private fun startingStatus() = assembleDashboardStatus(
    engineReady = false,
    listenersUp = false,
    startupInProgress = true,
    thermalStatus = 0,
    decodeTokensPerSec = 0.0,
    currentModelId = "litert-community/gemma-4-E4B-it-litert-lm",
    uptimeSeconds = 10.0,
    queueDepth = 0,
    errorsTotal = 0L,
    shedTotal = 0L,
    recentRequests = emptyList(),
    baseUrl = "https://192.168.1.42:8443/v1",
    apiKeyMasked = "abcd…wxyz",
    capabilities = "tools,reasoning",
  )

  private fun offlineStatus() = assembleDashboardStatus(
    engineReady = false,
    listenersUp = false,
    startupInProgress = false,
    thermalStatus = 0,
    decodeTokensPerSec = 0.0,
    currentModelId = "litert-community/gemma-4-E4B-it-litert-lm",
    uptimeSeconds = 0.0,
    queueDepth = 0,
    errorsTotal = 0L,
    shedTotal = 0L,
    recentRequests = emptyList(),
    baseUrl = "https://192.168.1.42:8443/v1",
    apiKeyMasked = "abcd…wxyz",
    capabilities = "tools,reasoning",
  )

  // ---------------------------------------------------------------------------
  // 1. Status-label mapping
  // ---------------------------------------------------------------------------

  @Test
  fun `engineReady true and not starting yields LIVE and live=true`() {
    val s = liveStatus()
    assertEquals("LIVE", s.statusLabel)
    assertTrue("live must be true when engine is ready", s.live)
  }

  @Test
  fun `engineReady false and startupInProgress true yields STARTING and live=false`() {
    val s = startingStatus()
    assertEquals("STARTING", s.statusLabel)
    assertFalse("live must be false when not ready", s.live)
  }

  @Test
  fun `engineReady false and startupInProgress false yields OFFLINE and live=false`() {
    val s = offlineStatus()
    assertEquals("OFFLINE", s.statusLabel)
    assertFalse("live must be false when offline", s.live)
  }

  @Test
  fun `engineReady true with listeners down is not LIVE`() {
    // The dashboard was the fourth surface keying LIVE on engine readiness alone. A bind failure
    // leaves the engine resident with both listeners torn down, so this page would report a healthy
    // node while the endpoints it prints refuse connections. Reachability, not residency.
    val s = assembleDashboardStatus(
      engineReady = true,
      listenersUp = false,
      startupInProgress = false,
      thermalStatus = 0,
      decodeTokensPerSec = 0.0,
      currentModelId = "litert-community/gemma-4-E4B-it-litert-lm",
      uptimeSeconds = 120.0,
      queueDepth = 0,
      errorsTotal = 0L,
      shedTotal = 0L,
      recentRequests = emptyList(),
      baseUrl = "https://192.168.1.42:8443/v1",
      apiKeyMasked = "abcd…wxyz",
      capabilities = "tools,reasoning",
    )
    assertNotEquals("LIVE", s.statusLabel)
    assertFalse("the beacon must not pulse for an unreachable node", s.live)
  }

  @Test
  fun `engineReady true with listeners still binding reads STARTING`() {
    // The engine initialises before either listener binds — an ordinary window of a healthy start.
    val s = assembleDashboardStatus(
      engineReady = true,
      listenersUp = false,
      startupInProgress = true,
      thermalStatus = 0,
      decodeTokensPerSec = 0.0,
      currentModelId = "litert-community/gemma-4-E4B-it-litert-lm",
      uptimeSeconds = 5.0,
      queueDepth = 0,
      errorsTotal = 0L,
      shedTotal = 0L,
      recentRequests = emptyList(),
      baseUrl = "https://192.168.1.42:8443/v1",
      apiKeyMasked = "abcd…wxyz",
      capabilities = "tools,reasoning",
    )
    assertEquals("STARTING", s.statusLabel)
    assertFalse(s.live)
  }

  @Test
  fun `engineReady true wins over startupInProgress true (impossible state, but robust)`() {
    // If somehow both are true, engineReady wins → LIVE.
    val s = assembleDashboardStatus(
      engineReady = true,
      listenersUp = true,
      startupInProgress = true,
      thermalStatus = 0,
      decodeTokensPerSec = 0.0,
      currentModelId = "x",
      uptimeSeconds = 0.0,
      queueDepth = 0,
      errorsTotal = 0L,
      shedTotal = 0L,
      recentRequests = emptyList(),
      baseUrl = "https://192.168.1.42:8443/v1",
      apiKeyMasked = "abcd…wxyz",
      capabilities = "tools,reasoning",
    )
    assertEquals("LIVE", s.statusLabel)
    assertTrue(s.live)
  }

  // ---------------------------------------------------------------------------
  // 2. Thermal-label mapping
  // ---------------------------------------------------------------------------

  @Test
  fun `thermalLabel maps all standard Android THERMAL_STATUS values`() {
    assertEquals("NONE", thermalLabel(0))
    assertEquals("LIGHT", thermalLabel(1))
    assertEquals("MODERATE", thermalLabel(2))
    assertEquals("SEVERE", thermalLabel(3))
    assertEquals("CRITICAL", thermalLabel(4))
    assertEquals("EMERGENCY", thermalLabel(5))
    assertEquals("SHUTDOWN", thermalLabel(6))
  }

  @Test
  fun `thermalLabel returns UNKNOWN for out-of-range values without crashing`() {
    assertEquals("UNKNOWN", thermalLabel(99))
    assertEquals("UNKNOWN", thermalLabel(-1))
    assertEquals("UNKNOWN", thermalLabel(7))
    assertEquals("UNKNOWN", thermalLabel(Int.MAX_VALUE))
  }

  // ---------------------------------------------------------------------------
  // 3. Field pass-through
  // ---------------------------------------------------------------------------

  @Test
  fun `assembler passes all scalar fields through unmodified`() {
    val s = assembleDashboardStatus(
      engineReady = true,
      listenersUp = true,
      startupInProgress = false,
      thermalStatus = 3,
      decodeTokensPerSec = 7.77,
      currentModelId = "litert-community/test-model",
      uptimeSeconds = 999.5,
      queueDepth = 2,
      errorsTotal = 4L,
      shedTotal = 1L,
      recentRequests = sampleRequests,
      baseUrl = "https://10.0.0.5:8443/v1",
      apiKeyMasked = "1234…5678",
      capabilities = "tools,reasoning",
    )
    assertEquals(7.77, s.decodeTokensPerSec, 0.001)
    assertEquals("litert-community/test-model", s.currentModelId)
    assertEquals(999.5, s.uptimeSeconds, 0.001)
    assertEquals(2, s.queueDepth)
    assertEquals(4L, s.errorsTotal)
    assertEquals(1L, s.shedTotal)
    assertEquals("https://10.0.0.5:8443/v1", s.baseUrl)
    assertEquals("1234…5678", s.apiKeyMasked)
    assertEquals("tools,reasoning", s.capabilities)
    assertEquals(2, s.recentRequests.size)
    assertEquals("/v1/chat/completions", s.recentRequests[0].endpoint)
    assertEquals(200, s.recentRequests[0].status)
    assertEquals(5L, s.recentRequests[0].ageSeconds)
    assertEquals("/generate", s.recentRequests[1].endpoint)
    assertEquals(503, s.recentRequests[1].status)
  }

  @Test
  fun `thermalLabel derived from thermalStatus in assembled status`() {
    val s = assembleDashboardStatus(
      engineReady = true, listenersUp = true, startupInProgress = false, thermalStatus = 2,
      decodeTokensPerSec = 0.0, currentModelId = "x", uptimeSeconds = 0.0,
      queueDepth = 0, errorsTotal = 0L, shedTotal = 0L, recentRequests = emptyList(),
      baseUrl = "https://192.168.1.42:8443/v1", apiKeyMasked = "abcd…wxyz", capabilities = "tools,reasoning",
    )
    assertEquals("MODERATE", s.thermalLabel)
  }

  // ---------------------------------------------------------------------------
  // 4. escapeHtml — XSS guard
  // ---------------------------------------------------------------------------

  @Test
  fun `escapeHtml escapes all five dangerous characters`() {
    // Test each character in isolation so escaped entities don't alias raw characters.
    assertEquals("&amp;", escapeHtml("&"))
    assertEquals("&lt;", escapeHtml("<"))
    assertEquals("&gt;", escapeHtml(">"))
    assertEquals("&quot;", escapeHtml("\""))
    assertEquals("&#39;", escapeHtml("'"))

    // Combined: verify the combined output contains all five entity forms.
    val input = """a"b'c<d>e&f"""
    val escaped = escapeHtml(input)
    assertTrue("&amp; present", escaped.contains("&amp;"))
    assertTrue("&lt; present", escaped.contains("&lt;"))
    assertTrue("&gt; present", escaped.contains("&gt;"))
    assertTrue("&quot; present", escaped.contains("&quot;"))
    assertTrue("&#39; present", escaped.contains("&#39;"))
    // The raw dangerous chars must not appear as unescaped sequences.
    assertFalse("raw < must not appear", escaped.contains("<"))
    assertFalse("raw > must not appear", escaped.contains(">"))
    assertFalse("raw \" must not appear", escaped.contains("\""))
    assertFalse("raw ' must not appear", escaped.contains("'"))
    // & appears only as part of &amp;, &lt;, etc. — verify no bare & followed by a non-entity char.
    // The simplest check: strip all known entities and assert no & remains.
    val stripped = escaped
      .replace("&amp;", "").replace("&lt;", "").replace("&gt;", "")
      .replace("&quot;", "").replace("&#39;", "")
    assertFalse("no bare & after removing all entities", stripped.contains("&"))
  }

  @Test
  fun `escapeHtml converts a script-injection model id to fully escaped output`() {
    val malicious = """a"/><script>alert(1)</script>"""
    val escaped = escapeHtml(malicious)
    assertFalse("raw < must not appear in escaped output", escaped.contains("<"))
    assertFalse("raw > must not appear in escaped output", escaped.contains(">"))
    assertFalse("raw \" must not appear in escaped output", escaped.contains("\""))
    assertFalse("escaped output must not contain <script", escaped.contains("<script"))
  }

  @Test
  fun `escapeHtml is a no-op on safe plain text`() {
    val safe = "litert-community/gemma-4-E4B-it-litert-lm"
    assertEquals(safe, escapeHtml(safe))
  }

  @Test
  fun `escapeHtml on empty string returns empty string`() {
    assertEquals("", escapeHtml(""))
  }

  // ---------------------------------------------------------------------------
  // 5. Render smoke — DESIGN.md compliance + scriptless invariant
  // ---------------------------------------------------------------------------

  @Test
  fun `renderDashboardHtml contains RELAIS wordmark`() {
    val html = renderDashboardHtml(liveStatus())
    assertTrue("RELAIS wordmark must appear in rendered HTML", html.contains("RELAIS"))
  }

  @Test
  fun `renderDashboardHtml contains amber accent color from DESIGN dot md`() {
    val html = renderDashboardHtml(liveStatus())
    assertTrue("amber accent #FFB000 must appear (DESIGN.md token)", html.contains("#FFB000"))
  }

  @Test
  fun `renderDashboardHtml contains near-black background from DESIGN dot md`() {
    val html = renderDashboardHtml(liveStatus())
    assertTrue("near-black background #0B0B0D must appear (DESIGN.md token)", html.contains("#0B0B0D"))
  }

  @Test
  fun `renderDashboardHtml contains the status label`() {
    assertTrue(renderDashboardHtml(liveStatus()).contains("LIVE"))
    assertTrue(renderDashboardHtml(startingStatus()).contains("STARTING"))
    assertTrue(renderDashboardHtml(offlineStatus()).contains("OFFLINE"))
  }

  @Test
  fun `renderDashboardHtml contains the current model id (escaped)`() {
    val html = renderDashboardHtml(liveStatus())
    // The model id is safe text; escaped version equals original.
    assertTrue("model id must appear in rendered page", html.contains("gemma-4-E4B-it-litert-lm"))
  }

  @Test
  fun `renderDashboardHtml contains no script tag (scriptless invariant)`() {
    val html = renderDashboardHtml(liveStatus())
    assertFalse("page must be scriptless: no <script tag allowed", html.contains("<script"))
  }

  @Test
  fun `renderDashboardHtml with injected model id does not contain raw script tag`() {
    val maliciousStatus = assembleDashboardStatus(
      engineReady = true,
      listenersUp = true,
      startupInProgress = false,
      thermalStatus = 0,
      decodeTokensPerSec = 1.0,
      currentModelId = """</td><script>alert(1)</script>""",
      uptimeSeconds = 0.0,
      queueDepth = 0,
      errorsTotal = 0L,
      shedTotal = 0L,
      recentRequests = listOf(
        RequestLogEntry("""<script>evil()</script>""", 200, 1L),
      ),
      baseUrl = """https://x"/><script>alert(2)</script>:8443/v1""",
      apiKeyMasked = """ab<script>cd""",
      capabilities = """<script>caps()</script>""",
    )
    val html = renderDashboardHtml(maliciousStatus)
    assertFalse("XSS: <script must not appear in rendered output", html.contains("<script"))
  }

  @Test
  fun `renderDashboardHtml uses monospace font (DESIGN dot md typography)`() {
    val html = renderDashboardHtml(liveStatus())
    assertTrue("monospace font must be declared (DESIGN.md)", html.contains("monospace"))
  }

  @Test
  fun `renderDashboardHtml contains no model-switch form or select-model action (read-only scope)`() {
    val html = renderDashboardHtml(liveStatus())
    // Per task scope: no model-switch endpoint. The page is read-only display.
    assertFalse(
      "no /select-model form: model switching is deferred to a separate PR",
      html.contains("/select-model"),
    )
  }

  // ---------------------------------------------------------------------------
  // 6. Status-class branch (palette discipline, DESIGN.md): salience by brightness, not hue.
  //    non-2xx (4xx/5xx) → paper-bright (no muted qualifier); 2xx → muted. No stop-red / off-palette
  //    warn anywhere — #FF5247 is Stop-only and #FFCC44 is not in the palette.
  // ---------------------------------------------------------------------------

  @Test
  fun `renderDashboardHtml mutes 2xx and keeps 4xx and 5xx request log entries paper-bright with no off-palette hues`() {
    val status = assembleDashboardStatus(
      engineReady = true,
      listenersUp = true,
      startupInProgress = false,
      thermalStatus = 0,
      decodeTokensPerSec = 1.0,
      currentModelId = "litert-community/test",
      uptimeSeconds = 0.0,
      queueDepth = 0,
      errorsTotal = 0L,
      shedTotal = 0L,
      recentRequests = listOf(
        RequestLogEntry("/generate", 503, 2L),
        RequestLogEntry("/v1/chat/completions", 429, 5L),
        RequestLogEntry("/v1/chat/completions", 200, 8L),
      ),
      baseUrl = "https://192.168.1.42:8443/v1",
      apiKeyMasked = "abcd…wxyz",
      capabilities = "tools,reasoning",
    )
    val html = renderDashboardHtml(status)

    // Palette discipline: the off-palette / Stop-only hues must not appear anywhere in the output.
    assertFalse("no Stop-red #FF5247 (Stop-only per DESIGN.md; no Stop control here)", html.contains("#FF5247"))
    assertFalse("no off-palette warn #FFCC44", html.contains("#FFCC44"))
    assertFalse("no legacy stop class", html.contains("""class="value stop""""))
    assertFalse("no legacy warn class", html.contains("""class="value warn""""))

    // Salience by brightness: 2xx is the quiet baseline (muted); 4xx/5xx stay paper-bright (no muted).
    // Extract the region after </style> to avoid matching CSS rule definitions.
    val bodyRegion = html.substringAfter("</style>")
    assertTrue("200 entry must be muted (quiet baseline)", bodyRegion.contains("""class="value muted">200"""))
    assertFalse("503 entry must stay paper-bright (not muted)", bodyRegion.contains("""class="value muted">503"""))
    assertFalse("429 entry must stay paper-bright (not muted)", bodyRegion.contains("""class="value muted">429"""))
    // All three status codes still render in the body.
    assertTrue("503 status text present", bodyRegion.contains(">503<"))
    assertTrue("429 status text present", bodyRegion.contains(">429<"))
    assertTrue("200 status text present", bodyRegion.contains(">200<"))
  }

  // ---------------------------------------------------------------------------
  // 7. CLIENT CONFIG panel (Feature #11) — base URL, masked key, caps, hint
  // ---------------------------------------------------------------------------

  @Test
  fun `renderDashboardHtml contains the CLIENT CONFIG panel with base url and capabilities`() {
    val html = renderDashboardHtml(liveStatus())
    assertTrue("CLIENT CONFIG panel title must appear", html.contains("Client Config"))
    assertTrue("base url must appear", html.contains("https://192.168.1.42:8443/v1"))
    assertTrue("capabilities must appear", html.contains("multimodal,tools,reasoning"))
    assertTrue(
      "hint must point to GET /v1/clientconfig",
      html.contains("/v1/clientconfig"),
    )
  }

  @Test
  fun `renderDashboardHtml renders the masked key and never a raw key`() {
    // The dashboard is fed a MASKED key (assembler does the masking at the call site). Confirm the
    // masked form renders and that a raw-looking sentinel key never reaches the HTML.
    val rawSentinel = "deadbeefcafef00d1234567890abcdef"
    val status = assembleDashboardStatus(
      engineReady = true,
      listenersUp = true,
      startupInProgress = false,
      thermalStatus = 0,
      decodeTokensPerSec = 1.0,
      currentModelId = "litert-community/test",
      uptimeSeconds = 0.0,
      queueDepth = 0,
      errorsTotal = 0L,
      shedTotal = 0L,
      recentRequests = emptyList(),
      baseUrl = "https://192.168.1.42:8443/v1",
      apiKeyMasked = maskApiKey(rawSentinel),
      capabilities = "tools,reasoning",
    )
    val html = renderDashboardHtml(status)
    assertFalse("raw API key must never appear in the dashboard HTML", html.contains(rawSentinel))
    assertTrue("masked key form must appear", html.contains(maskApiKey(rawSentinel)))
    assertFalse("page must remain scriptless", html.contains("<script"))
  }

  @Test
  fun `maskApiKey shows first and last four characters of a long key`() {
    val masked = maskApiKey("deadbeefcafef00d1234567890abcdef")
    assertTrue("must start with first 4 chars", masked.startsWith("dead"))
    assertTrue("must end with last 4 chars", masked.endsWith("cdef"))
    assertFalse("must not contain the full middle", masked.contains("cafef00d"))
  }

  @Test
  fun `maskApiKey fully masks a short key`() {
    assertEquals("********", maskApiKey("12345678"))
    assertEquals("***", maskApiKey("abc"))
    assertEquals("", maskApiKey(""))
  }

  // ---------------------------------------------------------------------------
  // Certificate panel (feature-18)
  // ---------------------------------------------------------------------------

  private fun certInfo(
    caFingerprint: String = "sha256/CAxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx=",
    nodeKeyPin: String = "sha256//LEAFxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx=",
    sanList: List<String> = listOf("127.0.0.1", "192.168.1.42"),
  ) = RelaisCertInfo(
    caFingerprint = caFingerprint,
    nodeKeyPin = nodeKeyPin,
    sanList = sanList,
    leafNotAfter = System.currentTimeMillis() + 80L * 86_400_000L,
    caPem = "-----BEGIN CERTIFICATE-----\nQUJD\n-----END CERTIFICATE-----\n",
  )

  @Test
  fun `certificate panel renders both fingerprints under distinguishing labels`() {
    val html = renderDashboardHtml(liveStatus().copy(cert = certInfo()))

    assertTrue("panel must be present", html.contains("Certificate"))
    assertTrue("CA fingerprint must render", html.contains("sha256/CAxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx="))
    assertTrue("node key pin must render", html.contains("sha256//LEAFxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx="))
    // The two values look identical in shape; only the labels tell a user which goes in
    // --pinnedpubkey. Rendering them unlabelled would be worse than not rendering them.
    assertTrue("must label the CA fingerprint", html.contains("ca fingerprint"))
    assertTrue("must label the node key pin", html.contains("node key pin"))
    assertTrue("SANs must render", html.contains("192.168.1.42"))
  }

  /**
   * The served page must not tell a user to verify the CA against a fingerprint it serves itself.
   *
   * This is the **third** surface the claim appeared on — it was removed from `TLS_NOTE` and
   * `SECURITY.md` a round earlier and survived here, which is why the retraction is now pinned by
   * test on every surface rather than fixed where it was spotted. And this was the worst place to
   * leave it: an attacker who can substitute the certificate can substitute this page, so their CA
   * *will* match the fingerprint shown. The instruction manufactures confidence exactly when it is
   * unwarranted and can talk a user into permanently trusting an attacker's CA.
   *
   * `--pinnedpubkey` guidance is fine and stays — that is a real, usable check.
   */
  @Test
  fun `the certificate panel does not claim its own fingerprint verifies the download`() {
    val html = renderDashboardHtml(liveStatus().copy(cert = certInfo())).lowercase()

    assertFalse(
      "must not tell users to check the CA against a fingerprint served over the same connection",
      html.contains("check the downloaded ca against"),
    )
    assertFalse("must not imply the served fingerprint confers trust", html.contains("before trusting it"))
    // The honest replacement, so this cannot be satisfied by deleting the sentence and saying
    // nothing — silence would leave the fingerprint on the page looking like a check.
    assertTrue(
      "must say the served fingerprint does not verify the download",
      html.contains("does not verify"),
    )
    assertTrue("must point at a trusted network instead", html.contains("network you already trust"))
    // The genuinely useful guidance survives.
    assertTrue("--pinnedpubkey guidance must remain", html.contains("pinnedpubkey"))
  }

  @Test
  fun `certificate panel is absent entirely before the node has minted`() {
    val html = renderDashboardHtml(liveStatus())

    // A panel of blanks would read as a broken certificate rather than an absent one.
    assertFalse("no certificate panel before the first mint", html.contains("ca fingerprint"))
    assertFalse(html.contains("node key pin"))
  }

  @Test
  fun `certificate values are HTML-escaped`() {
    val html =
      renderDashboardHtml(
        liveStatus().copy(cert = certInfo(caFingerprint = "<script>alert(1)</script>", sanList = listOf("<img src=x>")))
      )

    assertFalse("raw script tag must never reach the page", html.contains("<script>alert(1)</script>"))
    assertFalse("raw img tag must never reach the page", html.contains("<img src=x>"))
    assertTrue("escaped form must be present", html.contains("&lt;script&gt;"))
  }

  @Test
  fun `the certificate panel never renders a private key or the PEM body`() {
    val html = renderDashboardHtml(liveStatus().copy(cert = certInfo()))

    // The panel links to /ca.crt rather than inlining the certificate, and must never be a route
    // to key material of any kind.
    assertFalse(html.contains("PRIVATE KEY"))
    assertFalse("the PEM belongs at /ca.crt, not inlined here", html.contains("BEGIN CERTIFICATE"))
  }
}

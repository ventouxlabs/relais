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

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe: the dashboard's security headers **as they arrive on the wire** (feature-09).
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.DashboardHeadersProbe \
 *     -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *
 * ## Why this exists when [RelaisHttpDashboardTest] already asserts the same strings
 *
 * That test calls `dashboardSecurityHeaders()` directly. It therefore proves the LIST is right and
 * proves nothing about whether `handleDashboard` still calls it: delete the call, inline a different
 * list, and every JVM assertion still passes. This probe is the only thing in the tree that fails on
 * that rewiring, which is the whole reason the `Referrer-Policy` narrowing is a gate and not a
 * comment.
 *
 * **Prove it RED before trusting it:** remove `dashboardSecurityHeaders()` from `handleDashboard`
 * and re-run. `RelaisHttpDashboardTest` stays green; this must fail.
 *
 * ## Not CI
 *
 * `RelaisHttpServer` needs a `Context`, so this must be an instrumented test — and the workflow
 * compiles the probe suite without executing it (build_android.yaml). It is a discipline gate: it
 * fails only when someone runs it. Hardware-verified-or-not-done.
 *
 * `tls = false` is deliberate and perturbs nothing here: both headers under test are constants in
 * the list, and the only `https://` on the page is the hardcoded `baseUrl` string.
 */
@RunWith(AndroidJUnit4::class)
class DashboardHeadersProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()
  private val port = 18097 // high loopback port; distinct from the other probes' ports
  private var server: RelaisHttpServer? = null

  @Before
  fun setUp() {
    assumeTrue("On-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    server = RelaisHttpServer(context, port = port, tls = false, bindAddr = "127.0.0.1").also { it.start() }
    Thread.sleep(300) // let the accept loop bind before the first connect
  }

  @After
  fun tearDown() {
    server?.stop()
    server = null
  }

  @Test
  fun dashboardResponseCarriesTheNarrowedReferrerPolicyAndFormAction() {
    val key = RelaisConfig.apiKey(context)
    // The dashboard is auth-gated, so the probe must authenticate or it measures the 401 instead.
    val res = request(
      "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $key\r\n" +
        "Accept: text/html\r\nConnection: close\r\n\r\n"
    )
    assertTrue("dashboard must answer 200, got: ${res.statusLine}", res.statusLine.contains(" 200 "))

    val headers = res.headers.lowercase()
    assertTrue(
      "Referrer-Policy: same-origin must be ON THE RESPONSE, not merely in the helper. Its absence " +
        "means handleDashboard stopped calling dashboardSecurityHeaders() — which no JVM test can " +
        "see. Headers were:\n${res.headers}",
      headers.contains("referrer-policy: same-origin"),
    )
    assertFalse(
      "no-referrer nulls the Origin on this page's own form POST and 403s the SET MODEL button on " +
        "any UA that omits Sec-Fetch-Site. Headers were:\n${res.headers}",
      headers.contains("no-referrer"),
    )
    assertTrue(
      "the CSP must carry form-action 'self'. Headers were:\n${res.headers}",
      headers.contains("form-action 'self'"),
    )
    assertTrue("CSP must still be scriptless", headers.contains("default-src 'none'"))
    assertFalse("the dashboard must not gain a script-src", headers.contains("script-src"))
  }

  @Test
  fun experimentsKeepsNoReferrerAndForbidsForms() {
    // The narrowing is deliberately scoped to `/`. /experiments has no form, its fetch() calls are
    // CORS-mode so the Origin-nulling rule does not touch them, and they carry Bearer — so the
    // Basic-only guard never fires there. Pinned so nobody "harmonizes" the two pages.
    val key = RelaisConfig.apiKey(context)
    val res = request(
      "GET /experiments HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $key\r\n" +
        "Accept: text/html\r\nConnection: close\r\n\r\n"
    )
    assertTrue("/experiments must answer 200, got: ${res.statusLine}", res.statusLine.contains(" 200 "))
    val headers = res.headers.lowercase()
    assertTrue("/experiments keeps no-referrer:\n${res.headers}", headers.contains("referrer-policy: no-referrer"))
    assertTrue("/experiments forbids forms:\n${res.headers}", headers.contains("form-action 'none'"))
  }

  private data class HttpResult(val statusLine: String, val headers: String, val body: String)

  /** Minimal raw-socket HTTP/1.1 request to the loopback server; keeps headers and body apart. */
  private fun request(raw: String): HttpResult {
    Socket().use { sock ->
      sock.connect(InetSocketAddress("127.0.0.1", port), 5000)
      sock.soTimeout = 5000
      sock.getOutputStream().apply { write(raw.toByteArray()); flush() }
      val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
      val statusLine = reader.readLine() ?: ""
      val headers = StringBuilder()
      while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) break
        headers.append(line).append('\n')
      }
      return HttpResult(statusLine, headers.toString(), reader.readText())
    }
  }
}

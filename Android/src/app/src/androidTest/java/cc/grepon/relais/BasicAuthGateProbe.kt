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
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for the feature-09 Basic-auth + cross-site gate. `assumeTrue`-gated, so it never
 * runs in the JVM unit lane or unattended CI.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.BasicAuthGateProbe \
 *     -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *
 * **This probe is the ONLY coverage two of the three seams get, and that is why it exists.** The JVM
 * lane tests the pure helpers directly ([RelaisHttpAuthTest]) and tests the gate with injected fakes
 * ([RelaisHttpGateTest]). Nothing in that lane can reach `RelaisHttpServer.handle()`, which is where
 * the two are wired together — and wiring is exactly where this repo has shipped broken code with a
 * green suite before (the in-app chat screen that lost its `(Application)` constructor; the report
 * Worker that could not boot).
 *
 * Seams, and which request covers each:
 *  - **S1** `extractApiKey` -> the constant-time compare reports the right scheme. Covered in the JVM
 *    lane by `RelaisHttpAuthTest.8s`; requests 1-3 here confirm it end to end.
 *  - **S2** the header loop -> the `rejectsAsCrossSite` supplier: are all four headers parsed and
 *    passed into the right slots? **No JVM test can see this.** Omit the `host` arm entirely and
 *    every unit test still passes. Request 4.
 *  - **S3a** `authorized()` is actually called: requests 1-3.
 *  - **S3b** `challengeHeaders()` is actually called: **request 5 only.** Requests 1-4 are all
 *    authenticated, so none of them produces a challenge — delete
 *    `challengeHeaders(reject.status, accept)` from the reply and every other row here still passes.
 *
 * **This is not CI.** It needs hardware and an explicit `-e RELAIS_PROBE 1`. An unrun probe means
 * S2 and S3b shipped unverified.
 */
@RunWith(AndroidJUnit4::class)
class BasicAuthGateProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()
  private val port = 18098 // high loopback port; distinct from ClientConfigEndpointProbe's 18099
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

  /**
   * Basic credential for the node's real key, blank username — the shape a browser sends when the
   * operator leaves the username field empty at the auth prompt.
   */
  private fun basicHeader(): String {
    val key = RelaisConfig.apiKey(context)
    return "Basic " + Base64.getEncoder().encodeToString(":$key".toByteArray())
  }

  @Test
  fun basicIsAcceptedAndTheCrossSiteGuardFiresEndToEnd() {
    val basic = basicHeader()
    val bearer = "Bearer ${RelaisConfig.apiKey(context)}"
    // Host carries the port, as a real browser sends it for a non-default port. The authority
    // comparison is against THIS value, so getting it wrong here would make row 4 fail for a reason
    // that has nothing to do with the code under test.
    val host = "127.0.0.1:$port"

    // 1. Basic + cross-site -> 403. Proves the whole chain: header parsed, scheme preserved through
    //    the compare, supplier wired, gate ordered, CROSS_SITE arm reached and rendered.
    val crossSite = request(
      "GET /v1/models HTTP/1.1\r\nHost: $host\r\nAuthorization: $basic\r\n" +
        "Sec-Fetch-Site: cross-site\r\nConnection: close\r\n\r\n"
    )
    assertTrue(
      "Basic + cross-site must be 403, got: ${crossSite.statusLine}",
      crossSite.statusLine.contains(" 403 "),
    )
    assertTrue(
      "the 403 must carry a real reason phrase, not the `else -> ERR` fall-through: ${crossSite.statusLine}",
      crossSite.statusLine.contains("Forbidden"),
    )
    assertTrue(
      "the 403 envelope must be permission_error, not authentication_error: ${crossSite.body}",
      crossSite.body.contains("permission_error"),
    )

    // 2. Basic + same-origin -> 200. Proves the guard is not simply rejecting all Basic.
    val sameOrigin = request(
      "GET /v1/models HTTP/1.1\r\nHost: $host\r\nAuthorization: $basic\r\n" +
        "Sec-Fetch-Site: same-origin\r\nConnection: close\r\n\r\n"
    )
    assertTrue(
      "Basic + same-origin must be 200, got: ${sameOrigin.statusLine}",
      sameOrigin.statusLine.contains(" 200 "),
    )

    // 3. Bearer + cross-site -> 200. The guard is scheme-scoped END TO END, not merely in the gate's
    //    injected view. This is the row that still fails if `authenticate` reports the wrong scheme
    //    in production, even with the JVM seam test mutated away.
    val bearerCrossSite = request(
      "GET /v1/models HTTP/1.1\r\nHost: $host\r\nAuthorization: $bearer\r\n" +
        "Sec-Fetch-Site: cross-site\r\nConnection: close\r\n\r\n"
    )
    assertTrue(
      "Bearer must be untouched by the guard (no SDK may regress), got: ${bearerCrossSite.statusLine}",
      bearerCrossSite.statusLine.contains(" 200 "),
    )

    // 4. Basic POST, no Sec-Fetch-Site, matching Origin -> not 403. The ONLY place the `host` header
    //    arm and the authority comparison are observable together (S2). `http`, not `https`: the
    //    probe server is tls = false, so an https Origin would mismatch on scheme under a CORRECT
    //    implementation and invite a "fix" that breaks the real rule.
    val originFallback = request(
      "POST /v1/models HTTP/1.1\r\nHost: $host\r\nAuthorization: $basic\r\n" +
        "Origin: http://$host\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    )
    assertFalse(
      "a same-origin POST with no Sec-Fetch-Site must NOT be 403 — if it is, `host` is probably " +
        "unparsed or the authority comparison is asymmetric: ${originFallback.statusLine}",
      originFallback.statusLine.contains(" 403 "),
    )

    // 4b. The negative of the same axis: a foreign Origin on the same shape must reject.
    val foreignOrigin = request(
      "POST /v1/models HTTP/1.1\r\nHost: $host\r\nAuthorization: $basic\r\n" +
        "Origin: http://evil.example\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    )
    assertTrue(
      "a foreign Origin on a non-GET must be 403, got: ${foreignOrigin.statusLine}",
      foreignOrigin.statusLine.contains(" 403 "),
    )

    // 5. Unauthenticated + Accept: text/html -> 401 WITH the challenge. The only cover for S3b.
    //    Every row above is authenticated, so deleting challengeHeaders(...) from the single reply()
    //    would pass all of them.
    val challenge = request(
      "GET / HTTP/1.1\r\nHost: $host\r\nAccept: text/html\r\nConnection: close\r\n\r\n"
    )
    assertTrue(
      "an unauthenticated HTML navigation must be 401, got: ${challenge.statusLine}",
      challenge.statusLine.contains(" 401 "),
    )
    assertTrue(
      "the 401 must carry the Basic challenge or no browser can ever prompt; headers=${challenge.headers}",
      challenge.headers.any {
        it.startsWith("WWW-Authenticate:", ignoreCase = true) &&
          it.contains("Basic") && it.contains("realm=\"Relais\"")
      },
    )

    // 5b. The same request without the HTML Accept must stay bare — an unconditional challenge puts
    //     a browser auth prompt in front of every SDK's error path.
    val bareUnauthed = request(
      "GET /v1/models HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
    )
    assertTrue(
      "an unauthenticated API request must still be 401, got: ${bareUnauthed.statusLine}",
      bareUnauthed.statusLine.contains(" 401 "),
    )
    assertFalse(
      "a non-HTML 401 must NOT carry a challenge; headers=${bareUnauthed.headers}",
      bareUnauthed.headers.any { it.startsWith("WWW-Authenticate:", ignoreCase = true) },
    )
  }

  private data class HttpResult(val statusLine: String, val headers: List<String>, val body: String)

  /**
   * Minimal raw-socket HTTP/1.1 request. Unlike [ClientConfigEndpointProbe]'s helper this KEEPS the
   * response headers — `WWW-Authenticate` is the thing request 5 exists to assert.
   */
  private fun request(raw: String): HttpResult {
    Socket().use { sock ->
      sock.connect(InetSocketAddress("127.0.0.1", port), 5000)
      sock.soTimeout = 5000
      sock.getOutputStream().apply { write(raw.toByteArray()); flush() }
      val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
      val statusLine = reader.readLine() ?: ""
      val headers = mutableListOf<String>()
      while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) break
        headers += line
      }
      val body = reader.readText()
      return HttpResult(statusLine, headers, body)
    }
  }
}

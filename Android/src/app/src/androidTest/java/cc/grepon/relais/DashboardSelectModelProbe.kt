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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for `POST /select-model` and the mDNS TXT re-publish it triggers (feature-09).
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.DashboardSelectModelProbe \
 *     -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *
 * ## What only hardware can answer here
 *
 * The rejection paths below are wire-level checks of pure logic the JVM lane already covers, and
 * they are cheap. The rows that matter are the ones no unit test can reach:
 *
 *  - **The route is metered.** `respondText` does not record a metric, unlike `reply`, so all four
 *    response paths depend on the `recordRequest` inside the router arm's lambda. Miss it and the
 *    `/select-model` series contains only the gate's 403s — the route looks 100% broken while every
 *    success is invisible. Asserted by reading the dashboard's own RECENT REQUESTS panel back.
 *  - **`reason(303)`.** `reason` is private and there is no `RelaisHttpServer` unit test, so the
 *    status line is the only place the phrase is observable. A missing arm reads `303 ERR`.
 *  - **`endpointLabel`.** Also private. The panel must show `/select-model`, not `other`.
 *  - **The id and the PATH are persisted together** (the codex P1). `applyManualId` must be handed
 *    `resolvedPath = target?.path`, because a targeted swap skips `resolveModel` — the only caller
 *    of `RelaisModelProvisioner.remember` — and persisting the id alone leaves the cached and
 *    durable path naming the OUTGOING model. **Measured: neutering this to `resolvedPath = null`
 *    passes all 1355 JVM tests in every flavor.** The suite cannot see it; only manual check 11 can.
 *
 * ## What this probe does NOT cover, deliberately
 *
 * `bug 6`'s TXT re-publish and its `swapped` guard. Both need a REAL swap on a real node plus
 * `dumpsys nsd` and a cleared logcat window, which is a manual procedure — see the RUNBOOK and the
 * plan's manual check 10. In particular the guard cannot be asserted from the TXT value: after a
 * rollback the resident model is restored, so the published id is the old one whether `updateModel`
 * was wrongly called or correctly skipped. **Assert zero occurrences of each of the four NSD
 * callbacks in a cleared window instead** — an absence-of-a-*pair* check passes when an unregister
 * succeeds and the re-register fails, which is exactly the violation it was meant to catch.
 *
 * **Manual check 11 — the id/path pairing (P1).** Also manual, for a reason worth stating: the
 * defect only becomes observable ACROSS a reload, and both reload triggers are slow or destructive.
 *
 *  1. Note the configured model (A) and switch to a different provisioned model (B) in the dashboard.
 *  2. Wait for the swap to finish, then force the idle unload (or `adb shell am force-stop` and
 *     restart the node — the restart route exercises the persisted path, the unload route the
 *     in-memory cache; they fail independently, so **do both**).
 *  3. `GET /v1/models` and run one inference. The served model must BE B.
 *
 * The failure is silent by construction: before the fix the node answered as B while running A's
 * weights, with no error, no log line, and a green test suite. If step 3 looks right, confirm it by
 * reading the persisted path — a node that reports B while its stored path still ends in A's
 * filename has the bug back.
 *
 * ## Not CI
 *
 * Instrumented, so the workflow compiles it and does not run it. Hardware-verified-or-not-done.
 */
@RunWith(AndroidJUnit4::class)
class DashboardSelectModelProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()
  private val port = 18096
  private var server: RelaisHttpServer? = null

  @Before
  fun setUp() {
    assumeTrue("On-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    server = RelaisHttpServer(context, port = port, tls = false, bindAddr = "127.0.0.1").also { it.start() }
    Thread.sleep(300)
  }

  @After
  fun tearDown() {
    server?.stop()
    server = null
  }

  @Test
  fun unknownModelIdIsRejectedAndNothingIsPersisted() {
    val before = RelaisConfig.modelId(context)
    val res = post("model=definitely-not-a-real-model")
    assertTrue("unknown id must be 400, got: ${res.statusLine}", res.statusLine.contains(" 400 "))
    assertTrue("the page must say what went wrong", res.body.contains("unknown model id"))
    assertEquals("NOTHING may be persisted on a rejection", before, RelaisConfig.modelId(context))
  }

  @Test
  fun anEmptyBodyIsRejectedAndNothingIsPersisted() {
    val before = RelaisConfig.modelId(context)
    val res = post("")
    assertTrue("an absent field must be 400, got: ${res.statusLine}", res.statusLine.contains(" 400 "))
    assertEquals("NOTHING may be persisted", before, RelaisConfig.modelId(context))
  }

  @Test
  fun theRouteIsMeteredAndLabelled() {
    // Drive one request through the route, then read the dashboard's own panel back. This is the
    // only observation point for THREE private things at once: the recordRequest in the router
    // lambda, endpointLabel's new arm, and that the label is not "other".
    post("model=definitely-not-a-real-model")
    val key = RelaisConfig.apiKey(context)
    val page = request(
      "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $key\r\n" +
        "Accept: text/html\r\nConnection: close\r\n\r\n"
    )
    assertTrue(
      "the /select-model row must appear in RECENT REQUESTS. Its absence means respondText's " +
        "responses went unmetered (respondText, unlike reply, does not record).",
      page.body.contains(">/select-model<"),
    )
    assertTrue(
      "the label must not fall through to \"other\" — endpointLabel needs its exact-match arm",
      !page.body.contains(">other<"),
    )
  }

  @Test
  fun aValidSwitchAnswers303SeeOtherWithALocationHeader() {
    // Uses the CONFIGURED model as the target: always eligible (the dropdown unions it in), and
    // re-selecting it is the least disruptive thing this probe can ask a live node to do.
    val current = RelaisConfig.modelId(context)
    val res = post("model=" + java.net.URLEncoder.encode(current, "UTF-8"))
    // 503 is a legitimate outcome if a swap is already running — the CAS is the arbiter, and the
    // probe must not pretend that is a failure.
    assertTrue(
      "expected 303 or 503 (swap busy), got: ${res.statusLine}",
      res.statusLine.contains(" 303 ") || res.statusLine.contains(" 503 "),
    )
    if (res.statusLine.contains(" 303 ")) {
      assertTrue(
        "reason() must have a 303 arm or the wire reads \"303 ERR\" — it is private, so this " +
          "status line is the only place that is observable. Got: ${res.statusLine}",
        res.statusLine.contains("See Other"),
      )
      assertTrue("a 303 must carry Location: /", res.headers.lowercase().contains("location: /"))
    } else {
      assertTrue("a busy swap must tell the client when to retry", res.headers.contains("Retry-After"))
    }
  }

  private fun post(body: String): HttpResult {
    val key = RelaisConfig.apiKey(context)
    return request(
      "POST /select-model HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $key\r\n" +
        "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.toByteArray().size}\r\n" +
        "Connection: close\r\n\r\n$body"
    )
  }

  private data class HttpResult(val statusLine: String, val headers: String, val body: String)

  private fun request(raw: String): HttpResult {
    Socket().use { sock ->
      sock.connect(InetSocketAddress("127.0.0.1", port), 5000)
      sock.soTimeout = 15000
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

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
 * Hermetic JVM tests for the Experiments control panel renderer ([renderExperimentsHtml]).
 * No Context, no Android — pure functions in, strings out.
 */
class RelaisExperimentsTest {

  private val nonce = "dGVzdC1ub25jZQ=="

  private fun status(
    engineReady: Boolean = true,
    listenersUp: Boolean = true,
    startupInProgress: Boolean = false,
    modelId: String = "gemma-3n-e4b",
    capabilities: String = "multimodal,tools,reasoning",
  ) = assembleExperimentsStatus(engineReady, listenersUp, startupInProgress, modelId, capabilities)

  // ---- assembler: status mapping mirrors the dashboard (DESIGN.md) ----

  @Test
  fun `assembleExperimentsStatus maps engineReady to LIVE`() {
    val s = status(engineReady = true, startupInProgress = false)
    assertEquals("LIVE", s.statusLabel)
    assertTrue(s.live)
  }

  @Test
  fun `assembleExperimentsStatus maps startup in progress to STARTING`() {
    val s = status(engineReady = false, startupInProgress = true)
    assertEquals("STARTING", s.statusLabel)
    assertFalse(s.live)
  }

  @Test
  fun `assembleExperimentsStatus maps idle to OFFLINE`() {
    val s = status(engineReady = false, startupInProgress = false)
    assertEquals("OFFLINE", s.statusLabel)
    assertFalse(s.live)
  }

  @Test
  fun `assembleExperimentsStatus does not report LIVE when the listeners are down`() {
    // A bind failure leaves the engine resident and both listeners torn down, so engineReady alone
    // labelled a node LIVE that nothing could reach — the same defect fixed in the control panel and
    // the QS tile. The beacon dot keys off `live`, so it must go quiet too, not just the label.
    val s = status(engineReady = true, listenersUp = false, startupInProgress = false)
    assertNotEquals("LIVE", s.statusLabel)
    assertFalse(s.live)
  }

  @Test
  fun `assembleExperimentsStatus still reads STARTING while the listeners are binding`() {
    // The engine is initialised before either listener binds, so ready-without-listeners is an
    // ordinary window of every healthy start — it must not read OFFLINE.
    val s = status(engineReady = true, listenersUp = false, startupInProgress = true)
    assertEquals("STARTING", s.statusLabel)
    assertFalse(s.live)
  }

  // ---- palette guards (DESIGN.md: amber-on-charcoal, no new hues) ----

  @Test
  fun `renderExperimentsHtml declares the DESIGN palette tokens`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue("signal amber missing", html.contains("#FFB000"))
    assertTrue("charcoal background missing", html.contains("#0B0B0D"))
    assertTrue("surface missing", html.contains("#16171A"))
    assertTrue("hairline missing", html.contains("#2A2B30"))
    assertTrue("paper text missing", html.contains("#EDEAE3"))
    assertTrue("muted missing", html.contains("#8A8780"))
    assertTrue("monospace missing", html.contains("monospace"))
  }

  @Test
  fun `renderExperimentsHtml uses no off-palette hues`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertFalse("stop red is Stop-button-only, never on this page", html.contains("#FF5247"))
    assertFalse("off-palette warn hue", html.contains("#FFCC44"))
  }

  @Test
  fun `link result starts as the muted quiet baseline`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue(html.contains("""<td class="value muted" id="link-result">not verified</td>"""))
  }

  // ---- status beacon mapping ----

  @Test
  fun `beacon is full amber when LIVE and pulses`() {
    val html = renderExperimentsHtml(status(engineReady = true), nonce)
    assertTrue(html.contains("background: #FFB000;"))
    assertTrue(html.contains("beacon dot-pulse"))
  }

  @Test
  fun `beacon is amber 60 when STARTING and muted when OFFLINE, no pulse`() {
    val starting = renderExperimentsHtml(status(engineReady = false, startupInProgress = true), nonce)
    assertTrue(starting.contains("background: rgba(255,176,0,0.6);"))
    assertFalse(starting.contains("dot-pulse\"></span>"))
    val offline = renderExperimentsHtml(status(engineReady = false, startupInProgress = false), nonce)
    assertTrue(offline.contains("background: #8A8780;"))
  }

  // ---- script hygiene: nonce on every script, no storage, no key echo ----

  @Test
  fun `every script tag carries the CSP nonce`() {
    val html = renderExperimentsHtml(status(), nonce)
    val scriptCount = Regex("<script").findAll(html).count()
    val noncedCount = Regex("""<script nonce="${Regex.escape(nonce)}">""").findAll(html).count()
    assertTrue("page must contain at least one script", scriptCount > 0)
    assertEquals("every <script> must carry the request nonce", scriptCount, noncedCount)
  }

  @Test
  fun `nonce is HTML-escaped before interpolation`() {
    val html = renderExperimentsHtml(status(), "abc\"><script>alert(1)</script>")
    assertFalse(html.contains("\"><script>alert(1)</script>"))
    assertTrue(html.contains("abc&quot;&gt;&lt;script&gt;"))
  }

  @Test
  fun `api key stays in memory only`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue("key field must be a password input", html.contains("""<input type="password" id="api-key""""))
    assertTrue("no autofill of the node key", html.contains("""autocomplete="off""""))
    assertFalse("never persist the key", html.contains("localStorage"))
    assertFalse("never persist the key", html.contains("sessionStorage"))
    assertFalse("never cookie the key", html.contains("document.cookie"))
  }

  // ---- endpoint wiring ----

  @Test
  fun `verify link calls v1 models with a bearer header`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue(html.contains("'/v1/models'"))
    assertTrue(html.contains("'Authorization': 'Bearer ' + key"))
  }

  // ---- XSS guard: dynamic values are escaped ----

  @Test
  fun `model id and capabilities are HTML-escaped`() {
    val payload = "</td><script>alert('x')</script>"
    val html = renderExperimentsHtml(
      status(modelId = payload, capabilities = payload),
      nonce,
    )
    assertFalse(html.contains(payload))
    assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"))
  }

  // ---- field pass-through ----

  @Test
  fun `model id and capabilities render into the Node panel`() {
    val html = renderExperimentsHtml(status(modelId = "gemma-3n-e4b", capabilities = "tools,reasoning"), nonce)
    assertTrue(html.contains("gemma-3n-e4b"))
    assertTrue(html.contains("tools,reasoning"))
    assertTrue(html.contains("RELAIS"))
    assertTrue(html.contains("EXPERIMENTS"))
  }

  // ---- audio transcription module ----

  @Test
  fun `audio module renders a file picker and transcribe control`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue("audio file input", html.contains("""<input type="file" accept="audio/*" id="audio-file">"""))
    assertTrue("transcribe button", html.contains("""id="transcribe""""))
    assertTrue("result starts as the muted idle baseline",
      html.contains("""<div class="result muted" id="transcribe-result">idle</div>"""))
  }

  @Test
  fun `audio module posts multipart to the transcriptions endpoint with a bearer header`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue(html.contains("'/v1/audio/transcriptions'"))
    assertTrue("uses FormData so the browser sets the multipart boundary", html.contains("new FormData()"))
    assertTrue("file field name is 'file'", html.contains("fd.append('file', file)"))
    assertTrue("sends the model id", html.contains("fd.append('model'"))
    assertTrue(html.contains("'Authorization': 'Bearer ' + key"))
  }

  @Test
  fun `multipart modules never hand-set a Content-Type, only the image-gen JSON fetch does`() {
    val html = renderExperimentsHtml(status(), nonce)
    // A manual multipart Content-Type would omit the boundary and break the FormData upload; the
    // audio + vision fetches must let the browser set it. The JSON image-generation fetch is the
    // sole exception. So the ONLY Content-Type header on the page is application/json — guard the
    // count, not prose in comments.
    val ctCount = Regex("'Content-Type'").findAll(html).count()
    assertEquals("exactly one Content-Type on the page (the image-gen JSON fetch)", 1, ctCount)
    assertTrue("that one Content-Type is application/json",
      html.contains("'Content-Type': 'application/json'"))
  }

  // ---- vision / photo module ----

  @Test
  fun `vision module renders a file picker, prompt field, analyze control and result`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue("image file input", html.contains("""<input type="file" accept="image/*" id="vision-file">"""))
    assertTrue("prompt text input", html.contains("""id="vision-prompt""""))
    assertTrue("prompt placeholder", html.contains("""placeholder="what's in this photo?""""))
    assertTrue("analyze button", html.contains("""id="analyze""""))
    assertTrue("result starts as the muted idle baseline",
      html.contains("""<div class="result muted" id="vision-result">idle</div>"""))
  }

  @Test
  fun `vision module posts multipart to the chat completions endpoint with a bearer header`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue(html.contains("'/v1/chat/completions'"))
    assertTrue("uses FormData so the browser sets the multipart boundary", html.contains("new FormData()"))
    assertTrue("file field name is 'file'", html.contains("fd.append('file', file)"))
    assertTrue("sends the prompt field", html.contains("fd.append('prompt'"))
    assertTrue("sends the model id", html.contains("fd.append('model'"))
    assertTrue(html.contains("'Authorization': 'Bearer ' + key"))
  }

  @Test
  fun `vision module renders the OpenAI choice content via textContent, never innerHTML`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue("reads choices[0].message.content", html.contains("body.choices[0].message"))
    assertTrue("renders through textContent", html.contains("visionResult.textContent"))
    assertFalse("never innerHTML anywhere on the page", html.contains("innerHTML"))
  }

  // ---- image generation module ----

  @Test
  fun `image generation module renders a prompt field, generate control, result and hidden img`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue("prompt text input", html.contains("""id="imagegen-prompt""""))
    assertTrue("prompt placeholder", html.contains("""placeholder="a red bicycle on a beach""""))
    assertTrue("generate button", html.contains("""id="generate""""))
    assertTrue("img result element", html.contains("""id="imagegen-image""""))
    assertTrue("img starts hidden until an image arrives", html.contains("display:none"))
    assertTrue("result starts as the muted idle baseline",
      html.contains("""<div class="result muted" id="imagegen-result">idle</div>"""))
  }

  @Test
  fun `image generation module posts JSON to the images endpoint with a bearer header`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue(html.contains("'/v1/images/generations'"))
    assertTrue("method is POST", html.contains("method: 'POST'"))
    assertTrue("bearer header", html.contains("'Authorization': 'Bearer ' + key"))
    assertTrue("sends the prompt", html.contains("prompt: prompt"))
    assertTrue("requests the only supported size", html.contains("size: '512x512'"))
    assertTrue("requests base64 output", html.contains("response_format: 'b64_json'"))
    assertTrue("single image", html.contains("n: 1"))
  }

  @Test
  fun `image generation renders the returned b64 as an img src data URI, never innerHTML`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue("reads data[0].b64_json", html.contains("body.data[0].b64_json"))
    assertTrue("assigns a PNG data URI", html.contains("'data:image/png;base64,'"))
    assertTrue("renders the image via .src", html.contains("imagegenImage.src"))
    assertTrue("status via textContent", html.contains("imagegenResult.textContent"))
    assertFalse("never innerHTML anywhere on the page", html.contains("innerHTML"))
  }

  @Test
  fun `image generation surfaces 503 warming and 501 unavailable without auto-retry`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue("503 branch", html.contains("r.status === 503"))
    assertTrue("503 warming status",
      html.contains("warming up — the image model is loading, try again in a few seconds"))
    assertTrue("501 branch", html.contains("r.status === 501"))
    assertTrue("501 unavailable status", html.contains("image generation not available on this build"))
    assertTrue("generic failure status", html.contains("GENERATE FAILED — HTTP '"))
    // No auto-retry: the only fetch to the images endpoint is the operator-triggered one.
    assertEquals("no auto-retry loop to the images endpoint", 1,
      Regex("/v1/images/generations").findAll(html).count())
  }
}

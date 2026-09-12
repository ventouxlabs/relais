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
 * Render model for the Experiments control panel (`GET /experiments`).
 * Immutable value type — pure data, no Android types (mirrors [DashboardStatus]).
 */
data class ExperimentsStatus(
  /** True only when the engine is initialized AND the node's listeners are up — i.e. reachable. */
  val live: Boolean,
  /** "LIVE" | "STARTING" | "OFFLINE" — same DESIGN.md status mapping as the dashboard. */
  val statusLabel: String,
  val currentModelId: String,
  /** Comma-joined enabled capability names (e.g. "multimodal,tools,reasoning"). */
  val capabilities: String,
)

/**
 * Pure assembler for [ExperimentsStatus]; keeps the LIVE/STARTING/OFFLINE mapping identical to
 * [assembleDashboardStatus] without dragging the full dashboard render model along.
 * No I/O, no Context, no Android — fully unit-testable on the JVM.
 */
fun assembleExperimentsStatus(
  engineReady: Boolean,
  listenersUp: Boolean,
  startupInProgress: Boolean,
  currentModelId: String,
  capabilities: String,
): ExperimentsStatus =
  ExperimentsStatus(
    // Reachability, not engine residency — see [assembleDashboardStatus], which owns the rationale
    // this mapping mirrors.
    live = engineReady && listenersUp,
    statusLabel = when {
      engineReady && listenersUp -> "LIVE"
      startupInProgress -> "STARTING"
      else -> "OFFLINE"
    },
    currentModelId = currentModelId,
    capabilities = capabilities,
  )

/**
 * Renders the Experiments control panel to a complete HTML string.
 *
 * Unlike [renderDashboardHtml] (scriptless by contract), this page carries client-side JS: it is
 * the interactive surface for exercising the node's /v1 endpoints from a browser on the LAN.
 * Constraints:
 *  - DESIGN.md palette only: amber #FFB000 accent on #0B0B0D, paper #EDEAE3 for notable text,
 *    muted #8A8780 for the quiet baseline, monospace throughout. No new hues.
 *  - Every `<script>` tag carries [scriptNonce]; the HTTP layer sends a matching
 *    `script-src 'nonce-…'` CSP so no other script can execute.
 *  - The API key is typed by the operator, kept in JS memory only, and never rendered into the
 *    page, persisted to storage, or echoed back.
 *  - All dynamic values are HTML-escaped via [escapeHtml] before interpolation.
 */
fun renderExperimentsHtml(status: ExperimentsStatus, scriptNonce: String): String {
  val dotColor = when (status.statusLabel) {
    "LIVE" -> "#FFB000"
    "STARTING" -> "rgba(255,176,0,0.6)"
    else -> "#8A8780"
  }
  val dotPulse = if (status.live) " dot-pulse" else ""
  val nonce = escapeHtml(scriptNonce)

  return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RELAIS — Experiments</title>
<style>
/* DESIGN.md tokens */
:root {
  --bg:       #0B0B0D;
  --surface:  #16171A;
  --hairline: #2A2B30;
  --text:     #EDEAE3;
  --muted:    #8A8780;
  --amber:    #FFB000;
  font-family: monospace;
}
*,*::before,*::after { box-sizing: border-box; margin: 0; padding: 0; }
body { background: var(--bg); color: var(--text); min-height: 100vh; padding: 24px 16px; }
.wordmark {
  font-size: 22px; font-weight: bold; letter-spacing: 5px;
  color: var(--amber); text-transform: uppercase; margin-bottom: 20px;
}
.beacon {
  display: inline-block; width: 10px; height: 10px; border-radius: 50%;
  background: ${dotColor}; margin-right: 8px; vertical-align: middle;
}
.dot-pulse { animation: pulse 900ms ease-in-out infinite alternate; }
@keyframes pulse { from { opacity: 0.3; } to { opacity: 1.0; } }
.panel {
  background: var(--surface); border: 1px solid var(--hairline);
  border-radius: 6px; margin-bottom: 16px; overflow: hidden;
}
.panel-title {
  font-size: 11px; color: var(--muted); text-transform: uppercase;
  letter-spacing: 2px; padding: 10px 14px; border-bottom: 1px solid var(--hairline);
}
table { width: 100%; border-collapse: collapse; }
td { padding: 9px 14px; font-size: 13px; border-bottom: 1px solid var(--hairline); }
tr:last-child td { border-bottom: none; }
.label { color: var(--muted); font-size: 12px; width: 45%; }
.value { color: var(--text); text-align: right; }
.amber { color: var(--amber); }
.muted { color: var(--muted); }
.field { padding: 9px 14px; }
input[type="password"], input[type="text"], textarea {
  width: 100%; background: var(--bg); color: var(--text);
  border: 1px solid var(--hairline); border-radius: 6px;
  padding: 8px 10px; font-family: monospace; font-size: 13px;
}
input[type="file"] { width: 100%; color: var(--muted); font-family: monospace; font-size: 12px; }
.result {
  padding: 9px 14px; font-size: 13px; white-space: pre-wrap; word-break: break-word;
}
button {
  background: var(--amber); color: var(--bg); border: none; border-radius: 6px;
  padding: 8px 14px; font-family: monospace; font-size: 12px; font-weight: bold;
  letter-spacing: 1px; text-transform: uppercase; cursor: pointer;
}
button:hover { opacity: 0.85; }
button:disabled { background: var(--hairline); color: var(--muted); cursor: default; }
</style>
</head>
<body>
<div class="wordmark">&#x25CF; RELAIS &mdash; EXPERIMENTS</div>

<div class="panel">
  <div class="panel-title">Node</div>
  <table>
    <tr>
      <td class="label">status</td>
      <td class="value amber"><span class="beacon${dotPulse}"></span>${escapeHtml(status.statusLabel)}</td>
    </tr>
    <tr>
      <td class="label">model</td>
      <td class="value" id="node-model">${escapeHtml(status.currentModelId)}</td>
    </tr>
    <tr>
      <td class="label">capabilities</td>
      <td class="value">${escapeHtml(status.capabilities)}</td>
    </tr>
  </table>
</div>

<div class="panel">
  <div class="panel-title">Link</div>
  <div class="field">
    <input type="password" id="api-key" autocomplete="off" placeholder="node api key (kept in memory only)">
  </div>
  <div class="field">
    <button id="verify" type="button">Verify Link</button>
  </div>
  <table>
    <tr>
      <td class="label">link</td>
      <td class="value muted" id="link-result">not verified</td>
    </tr>
  </table>
</div>

<div class="panel">
  <div class="panel-title">Audio Transcription</div>
  <div id="modules">
    <div class="field">
      <input type="file" accept="audio/*" id="audio-file">
    </div>
    <div class="field">
      <button id="transcribe" type="button">Transcribe</button>
    </div>
    <div class="result muted" id="transcribe-result">idle</div>
  </div>
</div>

<div class="panel">
  <div class="panel-title">Vision / Photo</div>
  <div class="field">
    <input type="file" accept="image/*" id="vision-file">
  </div>
  <div class="field">
    <input type="text" id="vision-prompt" autocomplete="off" placeholder="what's in this photo?">
  </div>
  <div class="field">
    <button id="analyze" type="button">Analyze</button>
  </div>
  <div class="result muted" id="vision-result">idle</div>
</div>

<div class="panel">
  <div class="panel-title">Image Generation</div>
  <div class="field">
    <input type="text" id="imagegen-prompt" autocomplete="off" placeholder="a red bicycle on a beach">
  </div>
  <div class="field">
    <button id="generate" type="button">Generate</button>
  </div>
  <div class="result muted" id="imagegen-result">idle</div>
  <div class="field">
    <img id="imagegen-image" alt="generated image" style="display:none; max-width:100%; border-radius:6px;">
  </div>
</div>

<script nonce="$nonce">
(function () {
  'use strict';
  var keyInput = document.getElementById('api-key');
  var linkResult = document.getElementById('link-result');

  // Shared by experiment modules: the operator-entered key, memory only — never persisted.
  window.relaisApiKey = function () { return keyInput.value.trim(); };

  // Palette discipline mirrors the request log: amber = linked (the live signal), paper-bright =
  // failure (notable), muted = the quiet in-progress baseline. No new hues.
  function setLink(text, state) {
    linkResult.textContent = text;
    linkResult.className = state === 'ok' ? 'value amber' : state === 'err' ? 'value' : 'value muted';
  }

  document.getElementById('verify').addEventListener('click', function () {
    var key = window.relaisApiKey();
    if (!key) { setLink('enter the node api key', 'err'); return; }
    setLink('checking…', 'busy');
    fetch('/v1/models', { headers: { 'Authorization': 'Bearer ' + key } })
      .then(function (r) {
        if (!r.ok) { throw new Error('HTTP ' + r.status); }
        return r.json();
      })
      .then(function (body) {
        var ids = (body.data || []).map(function (m) { return m.id; }).join(', ');
        setLink('LINKED — ' + (ids || 'no models reported'), 'ok');
      })
      .catch(function (err) { setLink('LINK FAILED — ' + err.message, 'err'); });
  });

  // --- Audio Transcription module: POST multipart/form-data to /v1/audio/transcriptions ---
  var audioInput = document.getElementById('audio-file');
  var transcribeResult = document.getElementById('transcribe-result');
  var modelCell = document.getElementById('node-model');

  // Same palette discipline as setLink: amber = the transcription (live signal), paper-bright =
  // failure (notable), muted = the quiet in-progress/idle baseline. No new hues.
  function setTranscribe(text, state) {
    transcribeResult.textContent = text;
    transcribeResult.className = state === 'ok' ? 'result amber' : state === 'err' ? 'result' : 'result muted';
  }

  document.getElementById('transcribe').addEventListener('click', function () {
    var key = window.relaisApiKey();
    if (!key) { setTranscribe('enter the node api key', 'err'); return; }
    var file = audioInput.files && audioInput.files[0];
    if (!file) { setTranscribe('choose an audio file', 'err'); return; }
    setTranscribe('transcribing…', 'busy');
    var fd = new FormData();
    fd.append('file', file);
    // Current model id read from the Node panel cell (no injection: browser-decoded text, not markup).
    fd.append('model', (modelCell ? modelCell.textContent : '').trim());
    // Deliberately no Content-Type header: the browser sets multipart/form-data + boundary itself.
    fetch('/v1/audio/transcriptions', { method: 'POST', headers: { 'Authorization': 'Bearer ' + key }, body: fd })
      .then(function (r) {
        if (!r.ok) { throw new Error('HTTP ' + r.status); }
        return r.json();
      })
      .then(function (body) { setTranscribe(body.text || '(no text)', 'ok'); })
      .catch(function (err) { setTranscribe('LINK FAILED — ' + err.message, 'err'); });
  });

  // --- Vision / Photo module: POST multipart/form-data to /v1/chat/completions ---
  // The node adapts the multipart upload into the SAME synthetic OpenAI vision request the JSON path
  // uses (a user message carrying the image as a data URI), so this is just a convenience client.
  var visionInput = document.getElementById('vision-file');
  var visionPrompt = document.getElementById('vision-prompt');
  var visionResult = document.getElementById('vision-result');

  // Same palette discipline as setLink/setTranscribe: amber = the answer (live signal), paper-bright =
  // failure (notable), muted = the quiet in-progress/idle baseline. No new hues.
  function setVision(text, state) {
    visionResult.textContent = text;
    visionResult.className = state === 'ok' ? 'result amber' : state === 'err' ? 'result' : 'result muted';
  }

  document.getElementById('analyze').addEventListener('click', function () {
    var key = window.relaisApiKey();
    if (!key) { setVision('enter the node api key', 'err'); return; }
    var file = visionInput.files && visionInput.files[0];
    if (!file) { setVision('choose an image file', 'err'); return; }
    setVision('analyzing…', 'busy');
    var fd = new FormData();
    fd.append('file', file);
    fd.append('prompt', visionPrompt ? visionPrompt.value : '');
    // Current model id read from the Node panel cell (no injection: browser-decoded text, not markup).
    fd.append('model', (modelCell ? modelCell.textContent : '').trim());
    // Deliberately no Content-Type header: the browser sets multipart/form-data + boundary itself.
    fetch('/v1/chat/completions', { method: 'POST', headers: { 'Authorization': 'Bearer ' + key }, body: fd })
      .then(function (r) {
        if (!r.ok) { throw new Error('HTTP ' + r.status); }
        return r.json();
      })
      .then(function (body) {
        var msg = body && body.choices && body.choices[0] && body.choices[0].message;
        setVision((msg && msg.content) || '(no content)', 'ok');
      })
      .catch(function (err) { setVision('LINK FAILED — ' + err.message, 'err'); });
  });

  // --- Image Generation module: POST JSON to the node's images-generations endpoint ---
  // The generated PNG is rendered ONLY through an <img src="data:image/png;base64,…"> URL; the page
  // CSP allows img-src data: for exactly this. Status text is always set via textContent, so no
  // untrusted markup is ever parsed.
  var imagegenPrompt = document.getElementById('imagegen-prompt');
  var imagegenResult = document.getElementById('imagegen-result');
  var imagegenImage = document.getElementById('imagegen-image');

  // Same palette discipline as the other modules: amber = the finished image (live signal),
  // paper-bright = failure (notable), muted = the quiet in-progress/idle baseline. No new hues.
  function setImagegen(text, state) {
    imagegenResult.textContent = text;
    imagegenResult.className = state === 'ok' ? 'result amber' : state === 'err' ? 'result' : 'result muted';
  }

  document.getElementById('generate').addEventListener('click', function () {
    var key = window.relaisApiKey();
    if (!key) { setImagegen('enter the node api key', 'err'); return; }
    var prompt = (imagegenPrompt ? imagegenPrompt.value : '').trim();
    if (!prompt) { setImagegen('enter a prompt', 'err'); return; }
    setImagegen('generating…', 'busy');
    imagegenImage.style.display = 'none';
    fetch('/v1/images/generations', {
      method: 'POST',
      headers: { 'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt: prompt, size: '512x512', response_format: 'b64_json', n: 1 }),
    })
      .then(function (r) {
        // 503 (warming/thermal) and 501 (no backend on this build) are expected states, not crashes:
        // report them and stop — no auto-retry.
        if (r.status === 503) {
          setImagegen('warming up — the image model is loading, try again in a few seconds', 'busy');
          return null;
        }
        if (r.status === 501) { setImagegen('image generation not available on this build', 'err'); return null; }
        if (!r.ok) { setImagegen('GENERATE FAILED — HTTP ' + r.status, 'err'); return null; }
        return r.json();
      })
      .then(function (body) {
        if (!body) { return; }
        var b64 = body && body.data && body.data[0] && body.data[0].b64_json;
        if (!b64) { setImagegen('(no image returned)', 'err'); return; }
        imagegenImage.src = 'data:image/png;base64,' + b64;
        imagegenImage.style.display = 'block';
        setImagegen('done', 'ok');
      })
      .catch(function (err) { setImagegen('GENERATE FAILED — ' + err.message, 'err'); });
  });
})();
</script>
</body>
</html>"""
}

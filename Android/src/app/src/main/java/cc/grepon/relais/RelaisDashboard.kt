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
 * One row in the bounded recent-request log.
 *
 * Fields carry only the normalized endpoint label (never a raw path, IP, key, or FS path —
 * security M6 posture mirrors [RelaisMetrics.recordRequest]) plus the HTTP status and how many
 * seconds ago the request completed.
 */
data class RequestLogEntry(val endpoint: String, val status: Int, val ageSeconds: Long)

/**
 * Everything the dashboard renders, assembled from metrics already collected elsewhere.
 * Immutable value type — pure data, no Android types.
 */
data class DashboardStatus(
  /** True when the engine is initialized and the service is running. */
  val live: Boolean,
  /** Human label for the node state: "LIVE" | "STARTING" | "OFFLINE" (DESIGN.md status mapping). */
  val statusLabel: String,
  /** Human label for Android thermal level: "NONE".."SHUTDOWN" or "UNKNOWN". */
  val thermalLabel: String,
  val decodeTokensPerSec: Double,
  val currentModelId: String,
  val uptimeSeconds: Double,
  val queueDepth: Int,
  val errorsTotal: Long,
  val shedTotal: Long,
  val recentRequests: List<RequestLogEntry>,
  /** LAN HTTPS base URL clients connect to, e.g. "https://192.168.1.42:8443/v1". */
  val baseUrl: String,
  /** API key MASKED for display (first/last 4 chars) — the raw key is NEVER rendered. */
  val apiKeyMasked: String,
  /** Comma-joined enabled capability names (e.g. "tools,reasoning" or "multimodal,tools,reasoning"). */
  val capabilities: String,
  /**
   * The node's certificate identity (feature-18), or null before the node has ever minted — in
   * which case the Certificate panel is omitted entirely rather than rendered with blanks.
   *
   * Public certificate material only; see [RelaisCertInfo].
   */
  val cert: RelaisCertInfo? = null,
)

/**
 * Masks a secret for display: shows the first and last 4 characters with the middle elided. Short
 * keys (<= 8 chars) are fully masked (all asterisks) so the masking never reveals most of a short
 * key. Pure; the raw key must never reach the rendered HTML — only this masked form does.
 */
fun maskApiKey(key: String): String {
  if (key.length <= 8) return "*".repeat(key.length)
  return key.take(4) + "…" + key.takeLast(4)
}

/**
 * Pure assembler: maps raw status inputs to the [DashboardStatus] render model.
 *
 * Status label follows DESIGN.md:
 *  - LIVE     = engineReady (engine fully initialized and running)
 *  - STARTING = !engineReady && startupInProgress (first-run provision/download in progress)
 *  - OFFLINE  = neither
 *
 * No I/O, no Context, no Android — fully unit-testable on the JVM.
 */
fun assembleDashboardStatus(
  engineReady: Boolean,
  startupInProgress: Boolean,
  thermalStatus: Int,
  decodeTokensPerSec: Double,
  currentModelId: String,
  uptimeSeconds: Double,
  queueDepth: Int,
  errorsTotal: Long,
  shedTotal: Long,
  recentRequests: List<RequestLogEntry>,
  baseUrl: String,
  apiKeyMasked: String,
  capabilities: String,
  /** The node's certificate identity (feature-18), or null before the node has ever minted. */
  cert: RelaisCertInfo? = null,
): DashboardStatus {
  val live = engineReady
  val statusLabel = when {
    engineReady -> "LIVE"
    startupInProgress -> "STARTING"
    else -> "OFFLINE"
  }
  return DashboardStatus(
    live = live,
    statusLabel = statusLabel,
    thermalLabel = thermalLabel(thermalStatus),
    decodeTokensPerSec = decodeTokensPerSec,
    currentModelId = currentModelId,
    uptimeSeconds = uptimeSeconds,
    queueDepth = queueDepth,
    errorsTotal = errorsTotal,
    shedTotal = shedTotal,
    recentRequests = recentRequests,
    baseUrl = baseUrl,
    apiKeyMasked = apiKeyMasked,
    capabilities = capabilities,
    cert = cert,
  )
}

/**
 * Maps Android [android.os.PowerManager] THERMAL_STATUS_* integers (0..6) to human labels.
 * Out-of-range values return "UNKNOWN" without throwing — defensive against future OS extensions.
 * Pure function; internal visibility (tested via [RelaisDashboardTest]).
 */
internal fun thermalLabel(status: Int): String =
  when (status) {
    0 -> "NONE"
    1 -> "LIGHT"
    2 -> "MODERATE"
    3 -> "SEVERE"
    4 -> "CRITICAL"
    5 -> "EMERGENCY"
    6 -> "SHUTDOWN"
    else -> "UNKNOWN"
  }

/**
 * HTML-escapes a string for safe interpolation into HTML content or attribute values.
 *
 * Escapes: & < > " '
 * Order matters: & must be first so existing escape sequences are not double-escaped.
 *
 * Do NOT reuse the Prometheus [RelaisMetrics] escaper — Prometheus and HTML have different
 * escape rules. This function is the sole HTML escaping point; every dynamic value rendered
 * by [renderDashboardHtml] must pass through here.
 *
 * Pure function; unit-tested in RelaisDashboardTest (XSS guard).
 */
fun escapeHtml(text: String): String =
  text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

/**
 * Renders [DashboardStatus] to a complete HTML string.
 *
 * Design constraints (all from DESIGN.md):
 *  - Amber #FFB000 on near-black #0B0B0D, monospace font family, RELAIS wordmark
 *  - Dark-only, label-left / value-right rows on hairline dividers
 *  - Status dot pulses (CSS @keyframes) only when LIVE — pure CSS, no JS
 *  - SCRIPTLESS — the page contains no <script> tags; CSP script-src 'none' is enforced by
 *    the HTTP layer via extraHeaders on the GET / response
 *  - READ-ONLY — no model-switch form or /select-model action (deferred to a separate PR)
 *  - All dynamic values are HTML-escaped via [escapeHtml] before interpolation
 */
fun renderDashboardHtml(status: DashboardStatus): String {
  // dotColor: derived from a closed `when` over the fixed literal set {"LIVE","STARTING","OFFLINE"} —
  // always one of three compile-time CSS color strings. Not user content; HTML-escaping is wrong here
  // (CSS context, not HTML text/attribute) and unnecessary.
  val dotColor = when (status.statusLabel) {
    "LIVE" -> "#FFB000"
    "STARTING" -> "rgba(255,176,0,0.6)"
    else -> "#8A8780"
  }
  // dotPulse: derived from a boolean — either literal " dot-pulse" or "". Closed set, CSS class name,
  // no user content; no escaping needed or appropriate.
  val dotPulse = if (status.live) " dot-pulse" else ""
  val uptimeFormatted = formatUptime(status.uptimeSeconds)
  val decodeFmt = if (status.decodeTokensPerSec > 0.0) "%.2f tok/s".format(status.decodeTokensPerSec) else "—"

  // Certificate panel (feature-18). TEXT ONLY, and deliberately so: the QR lives on the in-app
  // CONFIGURE screen because this page is served under `default-src 'none'` with no `img-src`
  // (RelaisHttpServer's CSP), and widening a hardened security header to show a picture is a bad
  // trade. Omitted wholesale when the node has never minted — a panel of blanks would read as a
  // broken certificate rather than an absent one.
  val certPanel =
    status.cert?.let { cert ->
      val daysLeft = (cert.leafNotAfter - System.currentTimeMillis()) / 86_400_000L
      val expiry = if (daysLeft >= 0) "in $daysLeft days" else "EXPIRED"
      // The one certificate event a user cannot diagnose from the client side: their imported CA
      // stopped working and nothing told them why. Rendered as a row rather than a log line.
      val replacedRow =
        if (!cert.caWasReplaced) "" else {
          """
    <tr>
      <td class="label">ca replaced</td>
      <td class="value">${escapeHtml(
            "the previous CA could not be read, so a new one was minted — every client must " +
              "re-import relais-ca.crt"
          )}</td>
    </tr>"""
        }
      """
<div class="panel">
  <div class="panel-title">Certificate</div>
  <table>$replacedRow
    <tr>
      <td class="label">ca fingerprint</td>
      <td class="value">${escapeHtml(cert.caFingerprint)}</td>
    </tr>
    <tr>
      <td class="label">node key pin</td>
      <td class="value">${escapeHtml(cert.nodeKeyPin)}</td>
    </tr>
    <tr>
      <td class="label">expires</td>
      <td class="value muted">${escapeHtml(expiry)}</td>
    </tr>
    <tr>
      <td class="label">covers</td>
      <td class="value muted">${escapeHtml(cert.sanList.joinToString(", "))}</td>
    </tr>
    <tr>
      <td class="label" colspan="2" style="color:#8A8780;font-size:11px;line-height:1.5">${escapeHtml(
        "GET /ca.crt (no bearer key needed) to download the CA, then curl --cacert relais-ca.crt. " +
          "Check the downloaded CA against the ca fingerprint above before trusting it. " +
          "Use the node key pin — not the ca fingerprint — with curl --pinnedpubkey.",
      )}</td>
    </tr>
  </table>
</div>
"""
    } ?: ""

  val recentRows = buildString {
    if (status.recentRequests.isEmpty()) {
      append(
        """<tr><td class="label" colspan="3" style="color:#8A8780;text-align:center">no recent requests</td></tr>"""
      )
    } else {
      for (entry in status.recentRequests) {
        // Palette discipline (DESIGN.md): amber is the only accent and #FF5247 is Stop-only, so the
        // log signals error salience by brightness, not hue — non-2xx stays paper-bright (notable),
        // 2xx is muted (the quiet baseline). The status code itself distinguishes 4xx from 5xx.
        val statusClass = if (entry.status >= 400) "" else "muted"
        append(
          """<tr>""" +
          """<td class="label">${escapeHtml(entry.endpoint)}</td>""" +
          """<td class="value $statusClass">${escapeHtml(entry.status.toString())}</td>""" +
          """<td class="value muted">${escapeHtml(entry.ageSeconds.toString())}s ago</td>""" +
          """</tr>"""
        )
      }
    }
  }

  return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RELAIS — Node Status</title>
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
td:last-child { border-bottom: none; }
tr:last-child td { border-bottom: none; }
.label { color: var(--muted); font-size: 12px; width: 45%; }
.value { color: var(--text); text-align: right; }
.amber { color: var(--amber); }
.muted { color: var(--muted); }
</style>
</head>
<body>
<div class="wordmark">&#x25CF; RELAIS</div>

<div class="panel">
  <div class="panel-title">Node Status</div>
  <table>
    <tr>
      <td class="label">status</td>
      <td class="value amber"><span class="beacon${dotPulse}"></span>${escapeHtml(status.statusLabel)}</td>
    </tr>
    <tr>
      <td class="label">model</td>
      <td class="value">${escapeHtml(status.currentModelId)}</td>
    </tr>
    <tr>
      <td class="label">thermal</td>
      <td class="value">${escapeHtml(status.thermalLabel)}</td>
    </tr>
    <tr>
      <td class="label">decode</td>
      <td class="value">${escapeHtml(decodeFmt)}</td>
    </tr>
    <tr>
      <td class="label">uptime</td>
      <td class="value">${escapeHtml(uptimeFormatted)}</td>
    </tr>
    <tr>
      <td class="label">queue depth</td>
      <td class="value">${status.queueDepth}</td>
    </tr>
    <tr>
      <td class="label">errors total</td>
      <td class="value ${if (status.errorsTotal > 0L) "" else "muted"}">${status.errorsTotal}</td>
    </tr>
    <tr>
      <td class="label">shed total</td>
      <td class="value ${if (status.shedTotal > 0L) "" else "muted"}">${status.shedTotal}</td>
    </tr>
  </table>
</div>

<div class="panel">
  <div class="panel-title">Client Config</div>
  <table>
    <tr>
      <td class="label">base url</td>
      <td class="value">${escapeHtml(status.baseUrl)}</td>
    </tr>
    <tr>
      <td class="label">api key</td>
      <td class="value muted">${escapeHtml(status.apiKeyMasked)}</td>
    </tr>
    <tr>
      <td class="label">capabilities</td>
      <td class="value">${escapeHtml(status.capabilities)}</td>
    </tr>
    <tr>
      <td class="label" colspan="2" style="color:#8A8780;font-size:11px;line-height:1.5">${escapeHtml(
        "GET /v1/clientconfig (with your bearer key) for paste-ready Open WebUI / Continue.dev / Aider configs.",
      )}</td>
    </tr>
  </table>
</div>

$certPanel
<div class="panel">
  <div class="panel-title">Recent Requests</div>
  <table>
    $recentRows
  </table>
</div>

</body>
</html>"""
}

/** Formats uptime seconds as a human-readable string (e.g. "2h 5m 30s"). Pure. */
private fun formatUptime(seconds: Double): String {
  val total = seconds.toLong().coerceAtLeast(0L)
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return when {
    h > 0 -> "${h}h ${m}m ${s}s"
    m > 0 -> "${m}m ${s}s"
    else -> "${s}s"
  }
}

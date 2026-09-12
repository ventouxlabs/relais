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

/**
 * HTML page handlers that are NOT part of [RelaisHttpServer]'s class body.
 *
 * Why a separate file: `RelaisHttpServer.kt` is far past this repo's size target, and CLAUDE.md's
 * rule is to extract NEW code rather than grow it further. The shipped handlers deliberately stay
 * where they are — relocating working code would force `RequestContext`, `readBody`, `respondText`
 * and `provisionedOnDisk` to widen, which is feature-17's change to make, not this one's.
 */

/**
 * Handles `POST /select-model` — the dashboard's model switch.
 *
 * Takes primitives rather than `RequestContext` so this file needs ZERO visibility changes in
 * `RelaisHttpServer.kt`: the router arm reads the body, computes the two model lists, and passes a
 * responder that closes over the private `respondText`.
 *
 * ## Order is load-bearing
 *
 * 1. **Membership** — is this one of the ids the page was allowed to offer?
 * 2. **Compatibility** — is it known not to load on the pinned runtime?
 * 3. **Dispatch** the swap, and only then
 * 4. **persist**, iff the dispatch won the engine's CAS.
 *
 * Membership precedes compatibility because [RelaisModelSwap] records why the reverse was a bug: an
 * ABSENT known-bad id then answered "incompatible", telling the operator the file was unloadable
 * when the real problem was that it was missing.
 *
 * Dispatch precedes persist because [RelaisEngine.ensureModelSwapInBackground]'s CAS is the only
 * atomic arbiter. Persisting first and then dispatching lets a second submit mid-swap write config
 * while the swap no-ops, leaving config naming a model nothing is bringing up and a `303` that reads
 * as success.
 *
 * ## What this deliberately does NOT do
 *
 * No `Sec-Fetch-Site` check: [RelaisHttpGate.decide] applies the cross-site guard to every
 * Basic-authenticated request, and a second per-route check would be a place for the two to drift.
 * The handler therefore takes no `secFetchSite` parameter — an unused one is an invitation to add it.
 *
 * No "already resident, skip the dispatch" short-circuit. It is tempting ([RelaisModelSwap] has
 * exactly that check) and it is unsafe HERE: this runs on a request thread while three other paths
 * write `residentModelId` (a swap, an idle reload, an ordinary request's init), so the comparison
 * can be invalidated between the read and the persist — leaving config stranded against the engine.
 * The precedent is right; this destination does not preserve what made it safe.
 *
 * @param available ids the page may offer — re-derived at POST time by the caller, never carried
 *   over from the render, because the submitting page may be arbitrarily stale.
 * @param provisioned registry entries, for resolving the swap target. Null from [swapTargetFor] only
 *   when the id is the configured-but-unrecorded one, where the engine's own fallback is correct.
 * @param respond `(status, html, extraHeaders)`. The caller records the metric inside this lambda —
 *   `respondText` does not record, unlike `reply`.
 */
internal fun handleSelectModel(
  context: Context,
  body: String,
  available: List<String>,
  provisioned: List<ProvisionedModel>,
  respond: (status: Int, html: String, extraHeaders: List<String>) -> Unit,
) {
  val requested = parseFormField(body, "model")

  // 1. Membership. Nothing is persisted on any rejection path below.
  val id = validateModelChoice(requested, available)
  if (id == null) {
    respond(400, selectModelErrorPage("unknown model id — no change applied"), selectModelHeaders())
    return
  }

  // 2. Compatibility, re-checked server-side. Not redundant with the dropdown filter: a page
  // rendered before a model became known-bad can still POST it, and the dropdown is client input.
  // Both gates call the same predicate so they cannot drift apart.
  val incompatible = RelaisRuntimeCompat.incompatibleReason(id)
  if (incompatible != null) {
    respond(400, selectModelErrorPage("$id cannot be loaded — $incompatible"), selectModelHeaders())
    return
  }

  // 3. Dispatch. False means another swap holds the CAS; answer the same 503 + Retry-After the
  // request path already uses for this exact state rather than inventing a second status code.
  if (!RelaisEngine.ensureModelSwapInBackground(context, swapTargetFor(id, provisioned))) {
    respond(
      503,
      selectModelErrorPage("a model swap is already running — retry shortly"),
      selectModelHeaders() + "Retry-After: 25",
    )
    return
  }

  // 4. Persist, only now. Through ModelSwitch, never RelaisConfig.setModelId: applyManualId clears
  // the model ref unconditionally, which setModelId does not, and ModelSwitch is the declared single
  // source of truth for an operator model pick.
  ModelSwitch.applyManualId(context, id)
  // 303, not 302, so the browser reloads with GET and a refresh does not re-POST the form.
  respond(303, "", selectModelHeaders() + "Location: /")
}

/**
 * Security headers for `/select-model` responses.
 *
 * Mirrors the dashboard's set for consistency. Note that `form-action` here is boilerplate, not the
 * mechanism: the directive that governs where a form may submit belongs to the page HOSTING the
 * form, and a CSP on this response cannot authorize a submission already made.
 */
private fun selectModelHeaders(): List<String> = dashboardSecurityHeaders()

/**
 * A minimal error page for the three rejection paths, in the dashboard's palette.
 *
 * Carries a same-origin `‹ BACK` link — which is why the dashboard's "this page links nowhere"
 * property is a property of `/`, not of every page this feature adds. Same-origin, so the
 * `Referrer-Policy: same-origin` narrowing still omits every cross-origin referrer.
 */
private fun selectModelErrorPage(message: String): String =
  """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RELAIS — Model Switch</title>
<style>
:root { --bg:#0B0B0D; --surface:#16171A; --hairline:#2A2B30; --text:#EDEAE3; --muted:#8A8780; --amber:#FFB000; font-family: monospace; }
*,*::before,*::after { box-sizing: border-box; margin: 0; padding: 0; }
body { background: var(--bg); color: var(--text); min-height: 100vh; padding: 24px 16px; }
.wordmark { font-size: 22px; font-weight: bold; letter-spacing: 5px; color: var(--amber); text-transform: uppercase; margin-bottom: 20px; }
.panel { background: var(--surface); border: 1px solid var(--hairline); border-radius: 6px; padding: 14px; }
.msg { font-size: 13px; line-height: 1.6; }
.back { display: inline-block; margin-top: 14px; color: var(--amber); font-size: 12px; text-decoration: none; letter-spacing: 1px; }
</style>
</head>
<body>
<div class="wordmark">&#x25CF; RELAIS</div>
<div class="panel">
  <div class="msg">${escapeHtml(message)}</div>
  <a class="back" href="/">&#x2039; BACK</a>
</div>
</body>
</html>"""

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
 * @param provisioned registry entries, re-read at POST time by the caller and never carried over
 *   from the render, because the submitting page may be arbitrarily stale. Serves double duty: it
 *   is both what [validateSelection] derives the legal id set from and what [swapTargetFor] resolves
 *   the swap target against. Null from [swapTargetFor] only when the id is the
 *   configured-but-unrecorded one, where the engine's own fallback is correct.
 * @param configured the currently-configured model id, re-read at POST time for the same reason.
 *   Unioned into the selectable set so the operator can re-pick it during the pre-recording window.
 * @param respond `(status, html, extraHeaders)`. The caller records the metric inside this lambda —
 *   `respondText` does not record, unlike `reply`.
 */
internal fun handleSelectModel(
  context: Context,
  body: String,
  provisioned: List<ProvisionedModel>,
  configured: String,
  respond: (status: Int, html: String, extraHeaders: List<String>) -> Unit,
) {
  // 1+2. Membership and compatibility, in one pure decision. Nothing is persisted on either
  // rejection path. Deriving the selectable set inside validateSelection is what keeps the
  // compatibility arm reachable — see selectableModelIdsFor.
  val id =
    when (val outcome =
      validateSelection(
        parseFormField(body, "model"),
        provisioned,
        configured,
        RelaisRuntimeCompat::incompatibleReason,
      )) {
      is SelectionOutcome.Unknown -> {
        respond(400, selectModelErrorPage(400, "BAD REQUEST", "unknown model id — no change applied"), selectModelHeaders())
        return
      }
      is SelectionOutcome.Incompatible -> {
        respond(
          400,
          selectModelErrorPage(400, "BAD REQUEST", "${outcome.id} cannot be loaded — ${outcome.reason}"),
          selectModelHeaders(),
        )
        return
      }
      is SelectionOutcome.Ok -> outcome.id
    }

  // 3. Dispatch. False means another swap holds the CAS; answer the same 503 + Retry-After the
  // request path already uses for this exact state rather than inventing a second status code.
  val target = swapTargetFor(id, provisioned)
  if (!RelaisEngine.ensureModelSwapInBackground(context, target)) {
    respond(
      503,
      selectModelErrorPage(503, "SERVICE UNAVAILABLE", "a model swap is already running — retry shortly"),
      selectModelHeaders() + "Retry-After: 25",
    )
    return
  }

  // 4. Persist, only now. Through ModelSwitch, never RelaisConfig.setModelId: applyManualId clears
  // the model ref unconditionally, which setModelId does not, and ModelSwitch is the declared single
  // source of truth for an operator model pick.
  //
  // [target]?.path is NOT optional decoration. A targeted swap deliberately skips `resolveModel` —
  // "the registry already holds the on-disk path" — and `resolveModel` is the only thing that calls
  // RelaisModelProvisioner.remember(). Persisting the id alone therefore left the cached and durable
  // PATH pointing at the OUTGOING model, so the next reload after an idle unload loaded the old
  // weights stamped with the new id and served them silently. Whoever bypasses resolution owns
  // updating the cache; this is that owner. A null target means the id is the configured-but-
  // unrecorded one, where the swap resolves normally and `remember` runs on its own.
  ModelSwitch.applyManualId(context, id, resolvedPath = target?.path)
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
private fun selectModelErrorPage(status: Int, statusLabel: String, message: String): String =
  """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RELAIS — error</title>
<style>
:root { --bg:#0B0B0D; --surface:#16171A; --hairline:#2A2B30; --text:#EDEAE3; --muted:#8A8780; --amber:#FFB000; font-family: monospace; }
*,*::before,*::after { box-sizing: border-box; margin: 0; padding: 0; }
body { background: var(--bg); color: var(--text); min-height: 100vh; padding: 24px 16px; }
.wordmark { font-size: 22px; font-weight: bold; letter-spacing: 5px; color: var(--amber); text-transform: uppercase; margin-bottom: 20px; }
.panel { background: var(--surface); border: 1px solid var(--hairline); border-radius: 6px; padding: 14px; }
.heading { display: flex; gap: 10px; margin-bottom: 10px; font-size: 11px; letter-spacing: 1px; }
.heading-label { color: var(--muted); }
.heading-value { color: var(--text); }
.msg { font-size: 13px; line-height: 1.6; }
.back { display: inline-block; margin-top: 14px; color: var(--amber); font-size: 12px; text-decoration: none; letter-spacing: 1px; }
</style>
</head>
<body>
<div class="wordmark">&#x25CF; RELAIS</div>
<div class="panel">
  <div class="heading"><span class="heading-label">$status</span><span class="heading-value">$statusLabel</span></div>
  <div class="msg">${escapeHtml(message)}</div>
  <a class="back" href="/">&#x2039; BACK</a>
</div>
</body>
</html>"""

// ---------------------------------------------------------------------------
// Dashboard helpers (feature-09 Tasks 4-5)
//
// TOP-LEVEL, NOT MEMBERS — asserted directly from RelaisHttpDashboardTest in the JVM lane, which
// cannot construct a RelaisHttpServer (that needs a Context). An `internal` MEMBER would still
// require an instance, so moving any of these inside a class breaks its tests at compile time.
//
// Top-level is the constraint; the FILE is not. They live here rather than in RelaisHttpServer.kt
// only because that file is far past this repo's size target and these are new lines — same
// package, so `handleDashboard` calls them unqualified and nothing widened to make this work.
// ---------------------------------------------------------------------------

/**
 * Security headers for `GET /` — extracted from [RelaisHttpServer.handleDashboard] so they can be
 * ASSERTED. That is the whole reason this function exists: as an inline `listOf(...)` inside a
 * private member, nothing in the tree could reach these values, and two of them are load-bearing.
 *
 * `Referrer-Policy: same-origin`, NOT `no-referrer`: the CSRF fallback for user agents that omit
 * `Sec-Fetch-Site` requires this page's own POSTs to carry a real `Origin`, and `no-referrer` makes
 * the browser send `Origin: null` on a form submission (Fetch: a non-CORS, non-GET request under
 * that policy). A revert therefore silently disables the model-switch form on those clients — which
 * is why it is pinned by a test whose message says so. Nothing is leaked by the narrowing: this page
 * has no subresource (`default-src 'none'`), links nowhere, and is scriptless, so the only requests
 * it can originate are same-origin, and `same-origin` still omits `Referer` cross-origin.
 *
 * `form-action 'self'` bounds where the model-switch form may submit. It does not FALL BACK to
 * `default-src`, so without it the directive is simply absent and submissions are unrestricted; it
 * is a tightening, not what permits the form.
 *
 * Note what a test on this function does NOT prove — that [RelaisHttpServer.handleDashboard] still
 * calls it. That half is `DashboardHeadersProbe` (androidTest), which asserts the real response.
 */
internal fun dashboardSecurityHeaders(): List<String> =
  listOf(
    // Scriptless page — no script-src at all; default-src 'none' blocks everything else.
    "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; " +
      "frame-ancestors 'none'; form-action 'self'",
    "X-Content-Type-Options: nosniff",
    "X-Frame-Options: DENY",
    "Referrer-Policy: same-origin",
  )

/**
 * The model ids the dashboard's switch form offers: the provisioned registry UNIONED with the
 * configured id, minus anything the runtime-compat table rejects, sorted.
 *
 * Three properties, each of which a test pins by deleting it:
 *  - **Union with [configured]** — [RelaisModelSwap.resolveModelRequest] keeps the configured id
 *    swap-eligible on its own so the operator's selection works before it has been recorded. Drop
 *    this and, in that window, the currently-selected model is missing from its own dropdown.
 *  - **Compat filter** — the targeted swap path skips `resolveModel` and therefore every compat
 *    check, so an unfiltered dropdown can offer a model that takes the node down on first inference.
 *  - **Sorted** — [provisionedIds] returns a Set, whose iteration order is filesystem enumeration
 *    order. `.sorted()` supplies both the List and an order a test can assert.
 *
 * [incompatibleReason] is injected and has **no default** deliberately. `resolveModelRequest`
 * defaults the same parameter to `{ null }`, which is right for callers with no table to consult and
 * wrong here: filtering is this function's only job, and a defaulted predicate would make the filter
 * test pass while filtering nothing.
 *
 * Pure; no Context, no Android.
 */
internal fun availableModelIdsFor(
  provisioned: List<ProvisionedModel>,
  configured: String,
  incompatibleReason: (String) -> String?,
): List<String> = selectableModelIdsFor(provisioned, configured).filter { incompatibleReason(it) == null }

/**
 * Every model id a `POST /select-model` may legally NAME: the provisioned registry unioned with the
 * configured id, sorted. **Deliberately unfiltered by runtime compatibility.**
 *
 * This is NOT [availableModelIdsFor] and the two are not interchangeable — that mistake is the defect
 * this function exists to make unrepresentable. "What the dropdown may OFFER" is a strict subset of
 * "what a POST may NAME", and the difference is exactly the provisioned-but-incompatible models:
 *
 *  - **Offering** one is a real hazard: the targeted swap skips `resolveModel` and therefore every
 *    compat check, so the node would take itself down on first inference.
 *  - **Rejecting the POST as "unknown model id"** is a lie. The file is on disk and the operator can
 *    see it; they are owed the runtime reason. Validating membership against the filtered list made
 *    the compatibility branch in [handleSelectModel] unreachable — dead code whose own comment
 *    asserted it was live.
 *
 * [availableModelIdsFor] is DEFINED in terms of this function rather than duplicating the union, so
 * the two can never drift into agreeing again.
 *
 * Pure; no Context, no Android.
 */
internal fun selectableModelIdsFor(provisioned: List<ProvisionedModel>, configured: String): List<String> =
  (provisionedIds(provisioned) + configured).sorted()

/** The three outcomes of validating a `POST /select-model` body. Exhaustive; no else branch. */
internal sealed interface SelectionOutcome {
  /** [id] is selectable and loadable. The only outcome that dispatches a swap. */
  data class Ok(val id: String) : SelectionOutcome

  /** Absent, blank, or naming nothing on disk. Never reveals whether some other id exists. */
  data object Unknown : SelectionOutcome

  /** On disk, but the runtime-compat table refuses it. [reason] is shown to the operator verbatim. */
  data class Incompatible(val id: String, val reason: String) : SelectionOutcome
}

/**
 * Validates a submitted model id. **Takes the registry, not a pre-computed id list** — that is the
 * fix for the defect above, and it is structural rather than behavioral: with no list parameter there
 * is no wrong list a caller can hand in. The caller supplies what it already has (`provisionedOnDisk()`
 * and the configured id) and cannot express the bug.
 *
 * Membership is checked against [selectableModelIdsFor] and compatibility SECOND, so a provisioned
 * model the compat table refuses reports the actual reason instead of "unknown model id".
 *
 * [incompatibleReason] is injected with no default, for the same reason [availableModelIdsFor] injects
 * it: a defaulted `{ null }` would make every compatibility assertion pass while checking nothing.
 *
 * Pure; no Context, no Android.
 */
internal fun validateSelection(
  requested: String?,
  provisioned: List<ProvisionedModel>,
  configured: String,
  incompatibleReason: (String) -> String?,
): SelectionOutcome {
  val id =
    validateModelChoice(requested, selectableModelIdsFor(provisioned, configured))
      ?: return SelectionOutcome.Unknown
  val reason = incompatibleReason(id) ?: return SelectionOutcome.Ok(id)
  return SelectionOutcome.Incompatible(id, reason)
}

/**
 * The configured model id when the engine is not serving it, else null.
 *
 * Null when [resident] is null — before any successful init there is nothing to be behind, and a
 * hint claiming otherwise on a cold node would be noise. Non-null means config is ahead of the
 * engine: a swap is in flight, OR one ran and did not take effect (its target file was missing, or
 * engine-create failed and rolled back). Those are indistinguishable from here, which is why the
 * rendered hint states the fact and predicts nothing.
 *
 * Pure; no Context, no Android.
 */
internal fun pendingModelIdFor(configured: String, resident: String?): String? =
  configured.takeIf { resident != null && it != resident }

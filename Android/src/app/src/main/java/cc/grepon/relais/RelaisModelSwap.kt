/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */

package cc.grepon.relais

/*
 * Single-slot swap-on-mismatch (#180): should the resident engine be closed and reloaded to serve a
 * DIFFERENT model than the one currently resident?
 *
 * This node has exactly one resident engine slot (no LRU, no multi-model cache — see [RelaisEngine]'s
 * KDoc), so a "swap" is always a strict close-then-load, never a hitless load-before-close (no
 * evidence litertlm supports two simultaneously-resident `Engine` objects, and phone RAM is the whole
 * reason this feature exists).
 *
 * WHERE THE SAFETY BOUNDARY LIVES. An HTTP client's `model` field is arbitrary, untrusted input. If a
 * swap fired purely on "requested != resident", any client on the LAN could force the node to
 * download and load a model it names, entirely unattended — disk fill, bandwidth, a multi-GB fetch
 * with no operator involvement. The first cut bought that safety by only ever swapping to
 * [RelaisConfig.modelId], the operator's own staged selection. That guard is GONE: the property is
 * now carried by [RelaisModelRegistry], which only gains an entry when a provision SUCCEEDS LOCALLY.
 * A request can therefore still only complete a swap the operator already initiated, never originate
 * one — but it may now name any model actually on the device, not just the configured one. Do not
 * widen eligibility past registry membership without replacing that property with another.
 *
 * Pure JVM (no Context, no Engine, no [RelaisEngine]) so the decision is unit-testable in isolation —
 * mirrors [shouldUnloadIdleEngine] in RelaisIdleTtl.kt. The actual concurrency/lock-ordering safety
 * (never swapping mid-inference, watchdog not mistaking the swap's not-ready window for a crash) is
 * NOT expressed here — it lives in [RelaisEngine.ensureModelSwapInBackground], which reuses
 * [RelaisLiveness.startupInProgress] (the same "still coming up, not dead" signal every existing
 * not-ready window already relies on — see that function's KDoc and [RelaisWatchdogReceiver]).
 */

/**
 * What to do with a request's `model` field (#180, full feature).
 *
 * Replaces the first cut's boolean because the answer is genuinely three-way: serve, swap, or
 * refuse. A boolean could not express "the client named a model this node does not have", so an
 * unknown id silently fell through and was answered by whatever happened to be resident — the
 * drop-in-fidelity gap this issue exists to close.
 */
sealed interface ModelRequestOutcome {
  /** Serve the resident model: no `model` field, not ready yet, or it already matches. */
  data object ServeResident : ModelRequestOutcome

  /** [targetModelId] is provisioned locally — kick a single-slot swap and have the client retry. */
  data class SwapThenRetry(val targetModelId: String) : ModelRequestOutcome

  /** The client named a model that is not on this device. Answer 404 `model_not_found`. */
  data class NotProvisioned(val requestedModelId: String) : ModelRequestOutcome

  /**
   * The client named a model that IS on this device but is measured not to load on the pinned
   * runtime (#220). Distinct from [NotProvisioned] because the cause and the fix differ: the file is
   * present and re-downloading it will not help. Answer 404 with [reason].
   */
  data class Incompatible(val requestedModelId: String, val reason: String) : ModelRequestOutcome
}

/**
 * Decide what a request's `model` field means for this node.
 *
 * [requestedModelId] MUST be the RAW field — `null` when the client omitted it. Do **not** pass
 * `RelaisHttpServer.DEFAULT_MODEL` in its place: that cosmetic alias matches no real id, so
 * substituting it would turn every omitted-`model` request into a 404. (The first cut tolerated the
 * substitution because an unmatched id merely meant "don't swap"; under [NotProvisioned] the same
 * value becomes a hard client-visible error. This is the one behavioural landmine in the change.)
 *
 * [provisionedModelIds] comes from [RelaisModelRegistry] — models actually on disk. Membership is
 * what makes a swap legal, and it is why an arbitrary client string still cannot trigger a
 * download: the registry only grows on a locally-successful provision. [configuredModelId] stays
 * swap-eligible on its own so the operator's current selection works before it has been recorded.
 *
 * [incompatibleReason] answers "is this model measured not to load on the pinned runtime, and why"
 * (#220). Passed in rather than read from [RelaisRuntimeCompat] directly so this stays a pure
 * function *of its arguments* — a test can supply a hypothetical table instead of being stuck with
 * whatever the shipped one happens to say today. Defaults to "nothing is known-bad", which keeps
 * every caller that predates #220 behaving exactly as before.
 *
 * Pure JVM (no Context, no Engine) so the whole matrix is unit-tested in isolation — mirrors
 * [shouldUnloadIdleEngine] in RelaisIdleTtl.kt.
 */
fun resolveModelRequest(
  residentModelId: String?,
  requestedModelId: String?,
  configuredModelId: String,
  provisionedModelIds: Set<String>,
  isReady: Boolean,
  incompatibleReason: (String) -> String? = { null },
): ModelRequestOutcome {
  // Not ready: the normal not-ready path (503) owns this. Refusing here would answer 404 for a
  // model the node may well have, purely because it hasn't finished coming up.
  if (!isReady) return ModelRequestOutcome.ServeResident
  val requested = requestedModelId?.takeIf { it.isNotBlank() } ?: return ModelRequestOutcome.ServeResident
  // Deliberately BEFORE the compat check: if a model is somehow resident and answering, observed
  // reality outranks the static table. The table's job is to stop us loading something, not to
  // refuse something already demonstrably working.
  if (requested == residentModelId) return ModelRequestOutcome.ServeResident
  if (requested == configuredModelId || requested in provisionedModelIds) {
    // On disk (or the operator's own selection) — but on-disk proves the file downloaded, NOT that
    // the engine can create against it. Refuse before attempting a swap, otherwise the client gets
    // 503 + Retry-After and the swap then dies deep in engine init explaining nothing.
    incompatibleReason(requested)?.let {
      return ModelRequestOutcome.Incompatible(requested, it)
    }
    return ModelRequestOutcome.SwapThenRetry(requested)
  }
  // Deliberately AFTER the on-disk check: for a model that is not here at all, "not provisioned" is
  // the more actionable diagnosis and it comes from real state rather than a static table. An
  // earlier revision checked compatibility first, so an ABSENT known-bad id answered Incompatible —
  // telling the operator the file was unloadable when the real problem was that it was missing.
  return ModelRequestOutcome.NotProvisioned(requested)
}

/*
 * The two 404 body texts, kept here rather than inline in [RelaisHttpServer.rejectIfModelUnavailable].
 *
 * That function is private, takes a [java.net.Socket], and reads RelaisEngine/RelaisConfig, so the
 * strings it composed were unreachable from a JVM test: [RelaisModelSwapTest] pinned the DECISION
 * (which outcome resolveModelRequest returns) while the rendered body went unasserted, and deleting
 * the reason from the 404 broke nothing. Same decision-vs-wiring gap [RelaisDownloadRepositoryGateTest]
 * exists to close on the download lane.
 *
 * Lives in this file, not RelaisHttpServer.kt: this is pure text derived from [ModelRequestOutcome],
 * which is defined here, and CLAUDE.md asks for extraction over growing that 2200-line file. Mirrors
 * the `internal` top-level `buildUsageObject`/`estimatePromptTokens` pattern that
 * [RelaisUsageBlockTest] already relies on.
 *
 * These are the API-client wording, deliberately NOT [RelaisRuntimeCompat.refusalMessage]: that
 * sentence ends "Choose a different model", which is operator-UI advice an HTTP client cannot act
 * on. Sibling 404s stay in one voice here instead.
 */

/**
 * The 404 body for a model that IS on this device but is measured not to load (#220).
 *
 * Says what is wrong rather than "model_not_found": the file is present and re-downloading it will
 * not help, so the generic missing-model advice would send the caller down the wrong path.
 */
internal fun incompatibleModelMessage(modelId: String, reason: String): String =
  "model '$modelId' is $reason"

/** The 404 body for a model this node does not have at all — points at the discovery endpoint. */
internal fun notProvisionedModelMessage(modelId: String): String =
  "model '$modelId' is not provisioned on this node; see GET /v1/models for what is available"

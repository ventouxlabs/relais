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

import android.content.Context
import cc.grepon.relais.data.RelaisModelRef
import kotlinx.coroutines.delay

/**
 * The single source of truth for "the operator picked a model". Every surface that lets the user
 * change the served model — the in-chat selector sheet ([RelaisChatActivity]), the MODELS shell
 * destination ([ModelsScreen]), and the web dashboard's `POST /select-model` — MUST persist through
 * here so they can't drift.
 *
 * The divergence this consolidates: the chat sheet used to route a curated ref through
 * `switchModel(ref.modelId)`, which persisted only the id and silently dropped the ref, while
 * ModelsScreen persisted the ref. Now both call [applyRef]/[applyManualId], and both observe the
 * lazy engine reload via [awaitReload].
 *
 * The dashboard is the third surface and the one with the tightest ordering constraint: it
 * DISPATCHES the swap before calling [applyManualId], and persists only if the dispatch won
 * [RelaisEngine.ensureModelSwapInBackground]'s CAS. Persisting first would let a second submit
 * mid-swap write config while the swap no-ops, leaving config naming a model nothing is loading.
 */
object ModelSwitch {
  const val RELOAD_POLL_INTERVAL_MS = 500L
  const val MAX_RELOAD_POLL_ITERATIONS = 120 // 60s cap

  /** Persist a curated ref pick. Keeps the legacy id coherent and clears the staged path (see [RelaisConfig.setModelRef]). */
  fun applyRef(context: Context, ref: RelaisModelRef) {
    RelaisConfig.setModelRef(context, ref)
  }

  /**
   * Persist a raw manual-id pick. Drops any curated ref first so the entered id resolves via the
   * allowlist instead of a now-stale ref overriding it (mirrors [RelaisConfig] semantics).
   *
   * [resolvedPath] has **no default, deliberately.** Omitting it is what fails OPEN — silently, and
   * in a way no test observes — so the signature makes every caller state which case it is in:
   *
   *  - **A path** — the caller already knows where the model lives and is BYPASSING
   *    [RelaisModelProvisioner.resolveModel] (the dashboard's targeted swap does exactly this).
   *    `resolveModel` is the only caller of [RelaisModelProvisioner.remember], so a bypass that
   *    persists the id alone leaves the cached and durable path naming the OUTGOING model. The next
   *    reload after an idle unload then loads the old weights under the new id and serves them with
   *    no error anywhere. **Whoever bypasses resolution owns updating the cache.**
   *  - **null** — no bypass. The reload re-resolves and `remember` runs on its own. The in-app
   *    surfaces ([ChatViewModel], [ModelsScreen]) are in this case: their models arrive through the
   *    download/provision funnel, which already persists the path.
   *
   * **Order is load-bearing.** [RelaisConfig.setModelId] must run BEFORE `remember`, because
   * `remember` persists only when [RelaisModelProvisioner.shouldPersistPath] finds `persistForId`
   * equal to the id it reads back from config. Call them the other way round and the ids mismatch,
   * the drift guard (issue #11, an operator changing model mid-download) correctly refuses, and the
   * durable path is silently not written — while the in-memory cache IS, which would hide the
   * idle-reload symptom and leave the restart symptom alive. Do not "simplify" this ordering, and do
   * not defeat the guard by passing a null id: the guard is not the problem here.
   */
  fun applyManualId(context: Context, id: String, resolvedPath: String?) {
    RelaisConfig.clearModelRef(context)
    RelaisConfig.setModelId(context, id)
    if (resolvedPath != null) {
      RelaisModelProvisioner.remember(context, resolvedPath, persistForId = id)
    }
  }

  /**
   * Best-effort observation of the engine picking up the newly-selected model: polls
   * [RelaisLivenessState.snapshot] until startup settles (or a ~60s cap), then reports whether the node
   * is serving. The reload is lazy (the resident engine reloads on next use), so this reflects a
   * reload already underway rather than initiating one. Returns `true` iff [RelaisEngine.isReady].
   */
  suspend fun awaitReload(): Boolean {
    var iterations = 0
    while (RelaisLivenessState.snapshot.startupInProgress && iterations < MAX_RELOAD_POLL_ITERATIONS) {
      delay(RELOAD_POLL_INTERVAL_MS)
      iterations++
    }
    return RelaisEngine.isReady
  }
}

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The single source of truth for "the operator picked a model". Every surface that lets the user
 * change the served model — the in-chat selector sheet ([RelaisChatActivity]), the MODELS shell
 * destination ([ModelsScreen]), and the web dashboard's `POST /select-model` — MUST persist through
 * here so they can't drift.
 *
 * The divergence this consolidates: the chat sheet used to route a curated ref through
 * `switchModel(ref.modelId)`, which persisted only the id and silently dropped the ref, while
 * ModelsScreen persisted the ref. Now both call [applyRef]/[applyManualId], and both reflect a
 * load in progress via [observeReload].
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
   *    [RelaisModelProvisioner.remember] runs only inside [RelaisModelProvisioner.ensureModel]'s
   *    provisioning branches and here, so a bypass that persists the id alone leaves the cached and
   *    durable path naming the OUTGOING model. Before #337's resolver fix the next idle reload then
   *    served the old weights under the new id; `pathFor` is id-bound now, so it falls through to
   *    the registry or fails closed instead — but the durable fast path a restart boots from would
   *    still be empty. **Whoever bypasses resolution owns updating the cache.**
   *  - **null** — no bypass. The next START provisions the id through `ensureModel`, which runs
   *    `remember` itself; an idle reload before then only resolves, never remembers. The in-app
   *    surfaces ([ChatViewModel], [ModelsScreen]) are in this case. [ModelsScreen]'s download runs
   *    `ensureModel` and so persists the path; [ChatViewModel]'s pick does not, and its model is
   *    found by `pathFor` only if it is already on the device.
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
   * Mirrors "a model load is underway" into [setReloading] after a pick — the one observer both pick
   * surfaces ([ChatViewModel], [ModelsScreen]) use (#364). A pick starts no engine load: it persists,
   * and the next load picks it up (a request's swap or lazy reload, the next START). ModelsScreen's
   * pick does start a DOWNLOAD, which publishes no startup and has its own progress line. So the
   * flag is [RelaisLiveness.startupInProgress], re-read every [RELOAD_POLL_INTERVAL_MS] until no
   * startup is in progress, then false — whether or not the engine came up. A node that is not
   * ready with nothing loading (IDLE, OFF, ERROR) is not reloading; deriving the flag from
   * `!isReady` latched it on every one of those, and the chat screen's SEND with it.
   *
   * No cap: a startup can be a multi-GB download, and the old 60 s cap stopped observing while the
   * load ran on, freezing the flag at its last reading. Cancelling the returned job (a re-pick, the
   * screen or ViewModel going away) is what ends an abandoned observation. [setReloading] runs on
   * [scope]'s dispatcher.
   */
  fun observeReload(scope: CoroutineScope, setReloading: (Boolean) -> Unit): Job =
    scope.launch {
      while (true) {
        val loading = RelaisLivenessState.snapshot.startupInProgress // ONE read: set and exit agree
        setReloading(loading)
        if (!loading) return@launch
        delay(RELOAD_POLL_INTERVAL_MS)
      }
    }
}

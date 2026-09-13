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
 * The single source of truth for "the operator picked a model". Both surfaces that let the user
 * change the served model — the in-chat selector sheet ([RelaisChatActivity]) and the MODELS shell
 * destination ([ModelsScreen]) — MUST persist through here so they can't drift.
 *
 * The divergence this consolidates: the chat sheet used to route a curated ref through
 * `switchModel(ref.modelId)`, which persisted only the id and silently dropped the ref, while
 * ModelsScreen persisted the ref. Now both call [applyRef]/[applyManualId], and both observe the
 * lazy engine reload via [awaitReload].
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
   */
  fun applyManualId(context: Context, id: String) {
    RelaisConfig.clearModelRef(context)
    RelaisConfig.setModelId(context, id)
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

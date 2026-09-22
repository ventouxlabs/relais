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

package cc.grepon.relais.tile

import android.service.quicksettings.TileService
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cc.grepon.relais.RelaisConfig
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.core.RelaisInference
import cc.grepon.relais.core.RelaisNodeController

private const val TAG = "RelaisTileService"

/**
 * Quick Settings tile (Feature #2): live node status + one-tap start/stop, optionally a canned-prompt
 * run on tap. State is DERIVED from [RelaisNodeController.state] (the single
 * OFF/STARTING/LIVE/HOT/ERROR/IDLE source of truth) via the pure [tilePresentation] mapping — the
 * tile never re-implements state logic.
 *
 * Tap semantics are decided purely by [tileAction] from the current [NodeState] + config: OFF→start,
 * STARTING/HOT→stop, IDLE→warm (feature-22: re-warm the idle-released engine in place, no listener
 * bounce), LIVE→stop (default) or run the canned prompt when a template is configured and the engine
 * is ready. Cold-start safety: RUN_PROMPT is only chosen when [RelaisInference.isReady] is already
 * true, so a tap can NEVER kick off inference (and thus never provision a multi-GB model) when the
 * engine isn't resident, and never fires a prompt on the same tap that stops the node. WARM is not a
 * cold start: IDLE implies `shouldRun && listenersUp && idleUnloaded`, i.e. the foreground service
 * that owns the idle-TTL is provably alive behind the reload.
 */
class RelaisTileService : TileService() {

  override fun onStartListening() {
    super.onStartListening()
    render()
  }

  override fun onClick() {
    super.onClick()
    val templateId = RelaisConfig.tileCannedTemplateId(this)
    when (tileAction(RelaisNodeController.state(this), templateId, RelaisInference.isReady())) {
      TileAction.START -> RelaisNodeController.start(this)
      TileAction.STOP -> RelaisNodeController.stop(this)
      TileAction.WARM -> warm()
      // RUN_PROMPT implies a non-blank templateId (see tileAction); let keeps it non-null without `!!`.
      TileAction.RUN_PROMPT -> templateId?.let { enqueueCannedPrompt(it) }
    }
    render() // optimistic refresh; onStartListening re-renders the settled state on next listen
  }

  /**
   * Re-warm the idle-released engine (only reached via [TileAction.WARM], i.e. the node is running
   * with its listeners up). `applicationContext`, never `this`: the reload thread lives for the whole
   * (~20 s) engine load and must not pin a TileService the system may unbind meanwhile. Single-flight,
   * and it publishes `startupInProgress` on THIS thread before returning, so the `render()` that
   * follows already reads STARTING ("Relais · starting…") instead of a stale "idle". The kick can only
   * throw when `Thread.start()` itself fails (the engine has already ended its startup and reset the
   * single-flight guard by then) — caught here because `onClick` runs on the main thread, where an
   * escaping Throwable would take the whole app process down for a tile tap; logged, never swallowed.
   */
  private fun warm() {
    runCatching { RelaisEngine.ensureInitializedInBackground(applicationContext) }
      .onFailure { Log.e(TAG, "warm kick failed; tile tap dropped", it) }
  }

  /** Enqueue the canned prompt (only reached via [TileAction.RUN_PROMPT], i.e. engine already resident). */
  private fun enqueueCannedPrompt(templateId: String) {
    val request =
      OneTimeWorkRequestBuilder<CannedPromptWorker>()
        .setInputData(CannedPromptWorker.inputData(templateId))
        .build()
    // KEEP: repeated taps don't stack inferences (one engine lock; a second run would just queue/contend).
    WorkManager.getInstance(applicationContext)
      .enqueueUniqueWork(CANNED_WORK_NAME, ExistingWorkPolicy.KEEP, request)
  }

  private fun render() {
    val tile = qsTile ?: return
    val presentation = tilePresentation(RelaisNodeController.state(this))
    tile.state = presentation.tileState // value-aligned with Tile.STATE_* (see TilePresentation)
    tile.label = presentation.label
    tile.subtitle = presentation.subtitle // API 29+; minSdk is 31
    tile.updateTile()
  }
}

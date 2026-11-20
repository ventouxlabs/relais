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
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Single-flight dispatch of a model swap (#180), extracted from [RelaisEngine] so the #369 R2 race
 * is unit-testable through the injectable [launch]/[resolve] seams.
 *
 * An untargeted swap (the operator's configured model) captures ONE [ConfiguredModelSelection] at
 * dispatch, before the worker thread exists. The id handed to [handover] is that capture's id, and
 * the swap is abandoned if the selection has drifted before handover ([RelaisConfig.isCurrentSelection]);
 * [handover] re-checks it under the engine lock. A targeted swap names its own registry entry and
 * needs no selection. The dispatch flag is released on every exit.
 *
 * Lock order: this class takes no lock; [handover] runs under the engine lock and may only nest
 * the [RelaisConfig] selection monitor (engine lock -> selectionLock).
 */
internal class RelaisModelSwapRunner(
  private val epoch: () -> Long,
  private val handover: (Context, String, String, Long, ConfiguredModelSelection?) -> Boolean,
  private val publish: (Context) -> Unit,
  private val resolve: (Context, ConfiguredModelSelection) -> String = { context, selection ->
    // Blocking, may hit the network (allowlist fetch).
    RelaisModelProvisioner.resolveModel(context, selection).getPath(context)
  },
  private val launch: (() -> Unit) -> Unit = { work -> thread(name = "relais-model-swap", block = work) },
) {
  private val dispatching = AtomicBoolean(false)

  /** @return true iff THIS call won the single-flight flag and queued a worker (not that the swap succeeded). */
  fun dispatch(context: Context, target: ProvisionedModel?): Boolean {
    if (!dispatching.compareAndSet(false, true)) return false
    // Before the worker exists: a STOP landing later must not be followed by this swap loading an
    // engine (shutdownEpoch), and the selection this swap serves is fixed here (#369 R2).
    val startEpoch = epoch()
    try {
      val selection = if (target == null) RelaisConfig.modelSelection(context) else null
      launch {
        var swapped = false
        try {
          RelaisLivenessState.beginStartup() // watchdog: "coming up", not "dead"
          // STOP since dispatch owns the engine: do not even resolve (may hit the network).
          if (epoch() != startEpoch) return@launch
          val id = target?.modelId ?: requireNotNull(selection).modelId
          val path = target?.path ?: resolve(context, requireNotNull(selection))
          if (!File(path).exists()) {
            Log.w(TAG, "model swap target not provisioned on disk: $path — leaving resident engine untouched")
            return@launch
          }
          if (selection != null && !RelaisConfig.isCurrentSelection(context, selection)) {
            Log.w(TAG, "model swap skipped: selection changed since dispatch")
            return@launch
          }
          swapped = handover(context, path, id, startEpoch, selection)
          // #bug6: re-publish the mDNS TXT only once the engine actually transitioned.
          if (swapped) publish(context)
        } catch (t: Throwable) {
          Log.w(TAG, "model swap failed: ${t.message}")
        } finally {
          RelaisLivenessState.endStartup()
          dispatching.set(false)
        }
      }
    } catch (t: Throwable) {
      dispatching.set(false)
      Log.w(TAG, "could not dispatch model swap: ${t.message}")
      return false
    }
    return true
  }

  private companion object {
    const val TAG = "RelaisEngine"
  }
}

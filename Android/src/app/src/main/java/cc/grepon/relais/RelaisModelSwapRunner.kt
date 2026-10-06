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
 * Single-flight swap preparation. Provisioning runs outside the engine lock, so the old engine
 * remains usable until handover. Transfer ownership remains entirely in the existing provisioner.
 * The supplied handover must atomically recheck the epoch/selection and close+load under its lock.
 */
internal class RelaisModelSwapRunner(
  private val epoch: () -> Long,
  private val handover: (Context, String, String, Long, ConfiguredModelSelection?) -> Boolean,
  private val publish: (Context) -> Unit,
  private val provision: (Context, ConfiguredModelSelection) -> String = { context, selection ->
    RelaisModelProvisioner.ensureModel(context, selection)
  },
  private val launch: (() -> Unit) -> Unit = { work -> thread(name = "relais-model-swap", block = work) },
) {
  private val dispatching = AtomicBoolean(false)

  fun dispatch(context: Context, target: ProvisionedModel?): Boolean {
    if (!dispatching.compareAndSet(false, true)) return false
    val startEpoch = epoch()
    var startupOwned = false
    try {
      val selection = if (target == null) RelaisConfig.modelSelection(context) else null
      RelaisLivenessState.beginStartup()
      startupOwned = true
      launch {
        try {
          // STOP before the worker starts must prevent even a new transfer.
          if (epoch() != startEpoch) return@launch
          if (selection != null && !RelaisConfig.isCurrentSelection(context, selection)) return@launch
          val id = target?.modelId ?: requireNotNull(selection).modelId
          val path = target?.path ?: provision(context, requireNotNull(selection))
          if (!File(path).exists()) {
            Log.w("RelaisEngine", "model swap target missing: $path — retaining resident engine")
            return@launch
          }
          if (epoch() != startEpoch) return@launch
          if (selection != null && !RelaisConfig.isCurrentSelection(context, selection)) return@launch
          if (handover(context, path, id, startEpoch, selection)) publish(context)
        } catch (t: Throwable) {
          // A native or dispatch Error must not kill the process after preserving/rolling back X.
          Log.w("RelaisEngine", "model swap failed: ${t.message}")
        } finally {
          dispatching.set(false)
          RelaisLivenessState.endStartup()
        }
      }
    } catch (t: Throwable) {
      dispatching.set(false)
      if (startupOwned) RelaisLivenessState.endStartup()
      Log.w("RelaisEngine", "could not dispatch model swap: ${t.message}")
      return false
    }
    return true
  }
}

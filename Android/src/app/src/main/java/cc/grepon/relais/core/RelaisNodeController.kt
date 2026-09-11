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

package cc.grepon.relais.core

import android.content.Context
import cc.grepon.relais.RelaisConfig
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.RelaisListenerState
import cc.grepon.relais.RelaisNodeService
import cc.grepon.relais.ThermalGovernor

/**
 * Thin node lifecycle + state read-model over the existing statics. UI surfaces (tile/widget) use
 * [state] for a single, consistent OFF/STARTING/LIVE/HOT/ERROR readout instead of each re-deriving it
 * (which would drift). [start]/[stop] delegate to the existing [RelaisNodeService] companion methods.
 */
object RelaisNodeController {

  /** Operator intent-to-run latch (survives process death; what the watchdog keys off). */
  fun isRunning(context: Context): Boolean = RelaisConfig.shouldRun(context)

  fun state(context: Context): NodeState =
    computeNodeState(
      shouldRun = RelaisConfig.shouldRun(context),
      ready = RelaisEngine.isReady,
      listenersUp = RelaisListenerState.listenersUp,
      startupInProgress = RelaisEngine.startupInProgress,
      lastInitFailed = RelaisEngine.lastInitFailed,
      thermalStatus = ThermalGovernor.statusValue,
    )

  fun start(context: Context) = RelaisNodeService.start(context)

  fun stop(context: Context) = RelaisNodeService.stop(context)
}

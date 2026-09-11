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

/** Unified node state for UI surfaces (QS tile #2, widget #3). */
enum class NodeState { OFF, STARTING, LIVE, HOT, ERROR }

/** thermalStatus at/above this reads as HOT (PowerManager.THERMAL_STATUS_SEVERE == 3). Informational —
 *  the authoritative shed decision is [cc.grepon.relais.ThermalGovernor.shouldShed]. */
private const val THERMAL_HOT_THRESHOLD = 3

/**
 * Pure mapping of the node's raw signals to a single display state. Precedence is deliberate:
 *  - a resident engine **whose listeners are up** reads LIVE (or HOT when the device is
 *    throttling), even if a *prior* init failed (a stale [lastInitFailed] never masks a working
 *    engine);
 *  - **[ready] alone is not LIVE, and that is the point.** A `:8443` bind failure leaves the engine
 *    deliberately resident while both listeners are torn down; keying LIVE on engine readiness made
 *    every surface report a healthy node that nothing could reach. Worse, `shouldDispatchStartup`
 *    and the watchdog read the same signal, so the false LIVE suppressed the retry that would have
 *    fixed it — the state needing recovery was the state preventing it. [ready] answers "did the
 *    engine initialise?"; only [listenersUp] answers "can anyone reach this node?";
 *  - an in-progress startup reads STARTING even after a prior failure (an active retry is not an error);
 *  - only a node asked-to-run whose last init failed and is NOT currently retrying reads ERROR.
 */
fun computeNodeState(
  shouldRun: Boolean,
  ready: Boolean,
  listenersUp: Boolean,
  startupInProgress: Boolean,
  lastInitFailed: Boolean,
  thermalStatus: Int,
): NodeState = when {
  ready && listenersUp && thermalStatus >= THERMAL_HOT_THRESHOLD -> NodeState.HOT
  ready && listenersUp -> NodeState.LIVE
  startupInProgress -> NodeState.STARTING
  shouldRun && lastInitFailed -> NodeState.ERROR
  shouldRun -> NodeState.STARTING
  else -> NodeState.OFF
}

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

/**
 * Unified node state for UI surfaces (QS tile #2, widget #3) and `/health`. [IDLE] (feature-22) is
 * "running and reachable, engine gracefully released by idle-TTL (#178), warms on the next request"
 * — distinct from [STARTING] (an init is in flight or expected) and from [ERROR] (the last init
 * failed and nothing is retrying).
 */
enum class NodeState { OFF, STARTING, LIVE, HOT, ERROR, IDLE }

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
 *  - an in-progress startup reads STARTING even after a prior failure (an active retry is not an error)
 *    — and since every real init attempt publishes its own startup, a request-driven reload after an
 *    idle unload reads STARTING here too, not IDLE;
 *  - only a node asked-to-run whose last init failed and is NOT currently retrying reads ERROR —
 *    and ERROR stays **above** IDLE deliberately, not as defence against an impossible state:
 *    `lastInitFailed && [idleUnloaded]` alone IS reachable (a bind-failed node whose service-side
 *    catch sets [lastInitFailed] *after* the attempt cleared idle, later evicted by the TTL —
 *    `releaseIfIdle` never touches [lastInitFailed] — always with `listenersUp=false`). Only the
 *    three-way `lastInitFailed && [idleUnloaded] && [listenersUp]` is unreachable. Either way this
 *    row is load-bearing, not redundant: it is what makes that node read ERROR instead of falling
 *    through to STARTING, because IDLE is exactly the state the watchdog leaves alone;
 *  - a node asked-to-run **whose listeners are up** and whose engine was released by idle-TTL reads
 *    IDLE. It requires [listenersUp] for the same reason LIVE does: IDLE promises "reachable, will
 *    warm on the next request", and an unloaded engine behind torn-down listeners is not that — it
 *    falls through to STARTING, which is what lets the watchdog's shield drop and the retry dispatch.
 *
 * Read order for callers: take `RelaisLivenessState.snapshot` ONCE, first, then the engine flags
 * ([ready], [lastInitFailed]). `endStartup()` is the last write of every init attempt, so a reader
 * that observes `startupInProgress=false` for an attempt observes every flag write of that attempt.
 * The property that holds — and the only one claimed — is: **no interleaving invents LIVE or HOT;
 * a failing node may lag one poll as STARTING or stale-IDLE, never longer.** The residual tears are
 * enumerable against the precedence above: a stale `ready=true` → LIVE (the engine IS resident);
 * `ready=false` + stale `lastInitFailed` + `idleUnloaded=false` → STARTING for one poll;
 * `ready=false` + stale `idleUnloaded=true` → IDLE for one poll.
 */
fun computeNodeState(
  shouldRun: Boolean,
  ready: Boolean,
  listenersUp: Boolean,
  startupInProgress: Boolean,
  lastInitFailed: Boolean,
  thermalStatus: Int,
  idleUnloaded: Boolean,
): NodeState = when {
  ready && listenersUp && thermalStatus >= THERMAL_HOT_THRESHOLD -> NodeState.HOT
  ready && listenersUp -> NodeState.LIVE
  startupInProgress -> NodeState.STARTING
  shouldRun && lastInitFailed -> NodeState.ERROR
  shouldRun && listenersUp && idleUnloaded -> NodeState.IDLE
  shouldRun -> NodeState.STARTING
  else -> NodeState.OFF
}

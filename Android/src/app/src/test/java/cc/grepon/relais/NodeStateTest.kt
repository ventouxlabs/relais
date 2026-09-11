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

import cc.grepon.relais.core.NodeState
import cc.grepon.relais.core.computeNodeState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM truth table for [computeNodeState] — the unified node state the QS tile/widget consume. */
class NodeStateTest {

  private fun state(
    shouldRun: Boolean = false,
    ready: Boolean = false,
    startupInProgress: Boolean = false,
    lastInitFailed: Boolean = false,
    listenersUp: Boolean = true,
    thermalStatus: Int = 0,
  ) = computeNodeState(shouldRun, ready, listenersUp, startupInProgress, lastInitFailed, thermalStatus)

  /**
   * A resident engine is not a reachable node (codex round 18).
   *
   * When `:8443` is occupied, the service tears both listeners down but deliberately keeps the
   * engine resident — correct in itself, since it initialised fine and reloading costs seconds. The
   * defect was that every user-visible surface keyed on engine readiness, so the panel, the tile and
   * the widget all read LIVE and offered STOP while nothing could be reached.
   *
   * That is the mirror of the H2 failure this branch treated as release-blocking — there, every
   * surface said OFF while a listener was up — and it is worse in one respect: the false LIVE also
   * blocks the retry that would fix it, so the node cannot recover on its own even after the port
   * clears.
   *
   * The root is a proxy, the same one fixed a level down when `httpsServer != null` became
   * `isListening`: `ready` answers "did the engine initialise?", never "can anyone reach this node?"
   */
  @Test fun `not live when the engine is ready but no listener is up`() {
    assertEquals(
      NodeState.ERROR,
      state(shouldRun = true, ready = true, listenersUp = false, lastInitFailed = true),
    )
  }

  @Test fun `not hot either, when nothing can be reached`() {
    // HOT is a flavour of LIVE. Reporting thermal throttling on an unreachable node would be a
    // second surface telling the truth about the device and the wrong thing about the node.
    assertEquals(
      NodeState.ERROR,
      state(shouldRun = true, ready = true, listenersUp = false, lastInitFailed = true, thermalStatus = 3),
    )
  }

  @Test fun `starting, not live, while a retry is bringing listeners back`() {
    // An active retry must not read ERROR; the existing startupInProgress precedence still holds
    // once `ready` alone can no longer short-circuit to LIVE.
    assertEquals(
      NodeState.STARTING,
      state(shouldRun = true, ready = true, listenersUp = false, startupInProgress = true),
    )
  }

  @Test fun `off when nothing is running`() {
    assertEquals(NodeState.OFF, state())
  }

  @Test fun `starting when shouldRun but not ready`() {
    assertEquals(NodeState.STARTING, state(shouldRun = true))
  }

  @Test fun `starting when startupInProgress even if shouldRun is false`() {
    assertEquals(NodeState.STARTING, state(startupInProgress = true))
  }

  @Test fun `live when engine ready`() {
    assertEquals(NodeState.LIVE, state(shouldRun = true, ready = true))
  }

  @Test fun `hot when ready and thermal severe`() {
    // PowerManager.THERMAL_STATUS_SEVERE == 3.
    assertEquals(NodeState.HOT, state(shouldRun = true, ready = true, thermalStatus = 3))
  }

  @Test fun `hot takes precedence over live at critical`() {
    assertEquals(NodeState.HOT, state(shouldRun = true, ready = true, thermalStatus = 6))
  }

  @Test fun `error when shouldRun and last init failed and not retrying`() {
    assertEquals(NodeState.ERROR, state(shouldRun = true, lastInitFailed = true))
  }

  @Test fun `ready beats a stale init-failed flag`() {
    assertEquals(NodeState.LIVE, state(shouldRun = true, ready = true, lastInitFailed = true))
  }

  @Test fun `stopped node never shows error even with a stale init-failed flag`() {
    // ERROR requires shouldRun; a stopped node reads OFF regardless of a leftover lastInitFailed.
    assertEquals(NodeState.OFF, state(shouldRun = false, lastInitFailed = true))
  }

  @Test fun `active retry shows starting not error`() {
    // A retry in progress (startupInProgress) after a prior failure should read STARTING, not ERROR.
    assertEquals(NodeState.STARTING, state(shouldRun = true, startupInProgress = true, lastInitFailed = true))
  }
}

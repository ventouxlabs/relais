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
import cc.grepon.relais.tile.TileAction
import cc.grepon.relais.tile.tileAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure truth table for [tileAction] — the single tap decision. Pins the cold-start guard, the
 * device-safety (never run a prompt while HOT), that a prompt never fires on a stop-tap, and that an
 * idle node is WARMed in place rather than restarted (feature-22 PR-B).
 */
class TileActionTest {

  @Test fun `OFF and ERROR start the node regardless of template or readiness`() {
    assertEquals(TileAction.START, tileAction(NodeState.OFF, templateId = "t", ready = true))
    assertEquals(TileAction.START, tileAction(NodeState.OFF, templateId = null, ready = false))
    assertEquals(TileAction.START, tileAction(NodeState.ERROR, templateId = "t", ready = true))
  }

  @Test fun `STARTING stops (cancels) even with a configured ready template`() {
    // Never RUN_PROMPT before the engine is resident — STARTING is not yet ready.
    assertEquals(TileAction.STOP, tileAction(NodeState.STARTING, templateId = "t", ready = true))
  }

  @Test fun `HOT stops and never runs a prompt — never add inference heat while throttling`() {
    assertEquals(TileAction.STOP, tileAction(NodeState.HOT, templateId = "t", ready = true))
  }

  @Test fun `IDLE warms the engine in place — never START (a listener bounce) and never STOP`() {
    // feature-22 PR-B (task 4(c)): the node is running with its engine released by idle-TTL. START
    // would be a full service re-init — stopListeners() → pool.shutdownNow() → rebind → NSD
    // re-register — killing any in-flight LAN request; STOP would throw away a node the operator
    // asked for. WARM re-warms the engine behind the live listeners, whatever the template says.
    assertEquals(TileAction.WARM, tileAction(NodeState.IDLE, templateId = "t", ready = false))
    assertEquals(TileAction.WARM, tileAction(NodeState.IDLE, templateId = null, ready = false))
    // The tile reads state and isReady() in two separate reads, so IDLE + ready=true is reachable
    // by tear; it must still WARM (an idempotent no-op once the engine is in fact ready), never
    // RUN_PROMPT — that stays the LIVE arm's alone.
    assertEquals(TileAction.WARM, tileAction(NodeState.IDLE, templateId = "t", ready = true))
  }

  @Test fun `LIVE with a configured template and a ready engine runs the canned prompt`() {
    assertEquals(TileAction.RUN_PROMPT, tileAction(NodeState.LIVE, templateId = "terse-coder", ready = true))
  }

  @Test fun `LIVE but not ready stops — the cold-start guard (a tap can never kick off inference)`() {
    assertEquals(TileAction.STOP, tileAction(NodeState.LIVE, templateId = "terse-coder", ready = false))
  }

  @Test fun `LIVE with no or blank template is a plain stop toggle`() {
    assertEquals(TileAction.STOP, tileAction(NodeState.LIVE, templateId = null, ready = true))
    assertEquals(TileAction.STOP, tileAction(NodeState.LIVE, templateId = "   ", ready = true))
  }
}

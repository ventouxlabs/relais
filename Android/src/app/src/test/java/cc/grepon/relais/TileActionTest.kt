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
 * device-safety (never run a prompt while HOT), and that a prompt never fires on a stop-tap.
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

  @Test fun `IDLE stops — parity with the STARTING an idle node used to read (PR-B makes it WARM)`() {
    // feature-22 PR-A: the node is running, so the tap cancels the intent-to-run exactly as it did
    // when an idle node read STARTING. Never RUN_PROMPT — the engine is not resident.
    assertEquals(TileAction.STOP, tileAction(NodeState.IDLE, templateId = "t", ready = false))
    assertEquals(TileAction.STOP, tileAction(NodeState.IDLE, templateId = null, ready = false))
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

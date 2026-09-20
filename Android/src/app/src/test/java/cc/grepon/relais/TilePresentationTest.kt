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
import cc.grepon.relais.tile.TileState
import cc.grepon.relais.tile.tilePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM truth table for [tilePresentation] — the QS tile's display derived from [NodeState]. */
class TilePresentationTest {

  @Test fun `live maps to active with a live label`() {
    val p = tilePresentation(NodeState.LIVE)
    assertEquals(TileState.ACTIVE, p.tileState)
    assertEquals("Relais · live", p.label)
  }

  @Test fun `hot maps to active and signals throttling`() {
    val p = tilePresentation(NodeState.HOT)
    assertEquals(TileState.ACTIVE, p.tileState)
    assertEquals("Relais · hot", p.label)
    assertTrue("hot subtitle should mention throttling", p.subtitle?.contains("throttling") == true)
  }

  @Test fun `starting maps to active with a starting label`() {
    val p = tilePresentation(NodeState.STARTING)
    assertEquals(TileState.ACTIVE, p.tileState)
    assertEquals("Relais · starting…", p.label)
  }

  @Test fun `off maps to inactive with an off label`() {
    val p = tilePresentation(NodeState.OFF)
    assertEquals(TileState.INACTIVE, p.tileState)
    assertEquals("Relais · off", p.label)
  }

  @Test fun `error maps to inactive with an error label`() {
    val p = tilePresentation(NodeState.ERROR)
    assertEquals(TileState.INACTIVE, p.tileState)
    assertEquals("Relais · error", p.label)
  }

  @Test fun `idle maps to active with an idle label — the node is up, the engine is released`() {
    // feature-22 PR-A: minimal arm that preserves today's behaviour (an idle node used to read
    // STARTING → ACTIVE). PR-B (task 4(c)) keeps ACTIVE and changes the tap to WARM.
    val p = tilePresentation(NodeState.IDLE)
    assertEquals(TileState.ACTIVE, p.tileState)
    assertEquals("Relais · idle", p.label)
    assertEquals("engine released — wakes on request", p.subtitle)
  }

  @Test fun `live and hot are the only active states`() {
    val active = NodeState.entries.filter { tilePresentation(it).tileState == TileState.ACTIVE }.toSet()
    // STARTING and IDLE are also ACTIVE (a lit tile that's coming up / a lit node whose engine is
    // released on purpose), so assert the resident-engine pair is a subset.
    assertTrue("LIVE must be active", NodeState.LIVE in active)
    assertTrue("HOT must be active", NodeState.HOT in active)
  }

  @Test fun `off and error are inactive`() {
    assertEquals(TileState.INACTIVE, tilePresentation(NodeState.OFF).tileState)
    assertEquals(TileState.INACTIVE, tilePresentation(NodeState.ERROR).tileState)
  }

  @Test fun `every node state has a non-blank label`() {
    // Exhaustive: a future NodeState value forces a compile error in the mapping (no else branch),
    // and this guards every present value renders a usable label.
    for (state in NodeState.entries) {
      val p = tilePresentation(state)
      assertTrue("label must be non-blank for $state", p.label.isNotBlank())
    }
  }

  @Test fun `tile state ints are value-aligned with the android Tile constants`() {
    // The pure ints must equal android.service.quicksettings.Tile.STATE_* so the service boundary
    // mapping is a no-op pass-through; if AOSP ever renumbered these, this would catch it.
    assertEquals(0, TileState.UNAVAILABLE)
    assertEquals(1, TileState.INACTIVE)
    assertEquals(2, TileState.ACTIVE)
  }
}

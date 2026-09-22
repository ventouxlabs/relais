/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure [nextRung] stepper-move tests (feature-22 PR-B, task 3): the Configure screen's IDLE AFTER
 * stepper moves along [IDLE_TTL_LADDER], never by a fixed increment. Hermetic — no Context, no
 * Android types — mirrors [RelaisIdleTtlTest]'s style.
 *
 * Two rows here are deliberate mutation-kill fixtures for algorithms considered and rejected
 * 2026-09-07: "first rung >= current, then +1" would send 10 up to 30, skipping 15 (killed by the
 * off-ladder 10-up row below); "else the top/bottom rung" instead of "else current, unchanged"
 * would send a stored 90 up *down* to 60 — the one-way trip in the direction the operator least
 * expects (killed by the above-ceiling 90-up row below).
 */
class RelaisIdleTtlLadderTest {

  private val ladder = IDLE_TTL_LADDER

  // -------------------------------------------------------------------------
  // Every adjacent on-ladder transition, both directions: [1, 5, 15, 30, 60]
  // -------------------------------------------------------------------------

  @Test fun `1 up moves to 5`() {
    assertEquals(5, nextRung(1, ladder, up = true))
  }

  @Test fun `5 up moves to 15`() {
    assertEquals(15, nextRung(5, ladder, up = true))
  }

  @Test fun `15 up moves to 30`() {
    assertEquals(30, nextRung(15, ladder, up = true))
  }

  @Test fun `30 up moves to 60`() {
    assertEquals(60, nextRung(30, ladder, up = true))
  }

  @Test fun `5 down moves to 1`() {
    assertEquals(1, nextRung(5, ladder, up = false))
  }

  @Test fun `15 down moves to 5`() {
    assertEquals(5, nextRung(15, ladder, up = false))
  }

  @Test fun `30 down moves to 15`() {
    assertEquals(15, nextRung(30, ladder, up = false))
  }

  @Test fun `60 down moves to 30`() {
    assertEquals(30, nextRung(60, ladder, up = false))
  }

  // -------------------------------------------------------------------------
  // Ladder ends: no move past either boundary
  // -------------------------------------------------------------------------

  @Test fun `1 down stays at 1, the bottom rung`() {
    assertEquals(1, nextRung(1, ladder, up = false))
  }

  @Test fun `60 up stays at 60, the top rung`() {
    assertEquals(60, nextRung(60, ladder, up = true))
  }

  // -------------------------------------------------------------------------
  // Off-ladder starting value (e.g. 10, preserved from an older config by
  // RelaisConfig.sanitizeIdleTtlMinutes's 1..1440 clamp): nearest neighbour, never a skip.
  // -------------------------------------------------------------------------

  @Test fun `off-ladder 10 up moves to 15, not 30 (no skip)`() {
    assertEquals(15, nextRung(10, ladder, up = true))
  }

  @Test fun `off-ladder 10 down moves to 5`() {
    assertEquals(5, nextRung(10, ladder, up = false))
  }

  // -------------------------------------------------------------------------
  // Above the ladder's ceiling (60) but still inside IDLE_TTL_MAX_MINUTES (24*60) — not reachable
  // from the UI today (no production caller writes above 60 via this stepper), but a stored value
  // could already be there: up = no move (never silently pulled down to 60), down = to the top rung.
  // -------------------------------------------------------------------------

  @Test fun `above-ceiling 90 up stays at 90 (no move, not clamped down to 60)`() {
    assertEquals(90, nextRung(90, ladder, up = true))
  }

  @Test fun `above-ceiling 90 down moves to 60, the top rung`() {
    assertEquals(60, nextRung(90, ladder, up = false))
  }
}

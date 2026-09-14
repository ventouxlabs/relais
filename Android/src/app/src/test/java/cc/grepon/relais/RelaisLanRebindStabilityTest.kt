/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

package cc.grepon.relais

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The service itself needs Android's real callback delivery, but its safety decision is pure: a
 * callback is never permission to reissue immediately. These tests pin the temporal distinction
 * that SAN comparison alone cannot express — a brief loss versus a real, sustained network move.
 */
class RelaisLanRebindStabilityTest {

  @Test
  fun `a new nonempty address set waits for the full stability window`() {
    val gate = RelaisLanRebindStability(stableForMs = 15_000)

    assertEquals(15_000L, gate.observeLiterals(listOf("192.0.2.10"), nowMs = 100))
    assertEquals(1L, gate.observeLiterals(listOf("192.0.2.10"), nowMs = 15_099))
    assertEquals(0L, gate.observeLiterals(listOf("192.0.2.10"), nowMs = 15_100))
  }

  @Test
  fun `a changed DHCP address restarts the stability window`() {
    val gate = RelaisLanRebindStability(stableForMs = 15_000)

    assertEquals(15_000L, gate.observeLiterals(listOf("192.0.2.10"), nowMs = 0))
    assertEquals(15_000L, gate.observeLiterals(listOf("198.51.100.8"), nowMs = 14_999))
    assertEquals(1L, gate.observeLiterals(listOf("198.51.100.8"), nowMs = 29_998))
  }

  @Test
  fun `a transient empty observation clears the candidate rather than applying stale addresses`() {
    val gate = RelaisLanRebindStability(stableForMs = 15_000)

    assertEquals(15_000L, gate.observeLiterals(listOf("192.0.2.10"), nowMs = 0))
    assertNull(gate.observeLiterals(emptyList(), nowMs = 5_000))
    assertEquals(15_000L, gate.observeLiterals(listOf("192.0.2.10"), nowMs = 5_001))
  }
}

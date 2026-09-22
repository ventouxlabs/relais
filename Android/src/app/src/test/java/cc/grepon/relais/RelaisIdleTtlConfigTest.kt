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

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins the [RelaisConfig.idleTtlMinutes] default + clamp contract (#178), including the special
 * "0 = disabled" sentinel that — unlike the shed thresholds it's modeled after — is a legitimate
 * in-band operator choice rather than a device-safety hazard to clamp away.
 */
@RunWith(RobolectricTestRunner::class)
class RelaisIdleTtlConfigTest {

  private val context: Context get() = RuntimeEnvironment.getApplication()

  @Test fun `defaults to IDLE_TTL_DEFAULT_MINUTES on a fresh install`() {
    assertEquals(IDLE_TTL_DEFAULT_MINUTES, RelaisConfig.idleTtlMinutes(context))
  }

  @Test fun `zero means disabled and round-trips as zero, not the floor`() {
    RelaisConfig.setIdleTtlMinutes(context, 0)
    assertEquals(IDLE_TTL_DISABLED_MINUTES, RelaisConfig.idleTtlMinutes(context))
  }

  @Test fun `negative values collapse to disabled, not a clamp artifact`() {
    RelaisConfig.setIdleTtlMinutes(context, -5)
    assertEquals(IDLE_TTL_DISABLED_MINUTES, RelaisConfig.idleTtlMinutes(context))
  }

  @Test fun `clamps to the max ceiling`() {
    RelaisConfig.setIdleTtlMinutes(context, 100_000)
    assertEquals(IDLE_TTL_MAX_MINUTES, RelaisConfig.idleTtlMinutes(context))
  }

  @Test fun `the smallest enabled value (the min floor) round-trips exactly, not as disabled`() {
    // IDLE_TTL_MIN_MINUTES is the smallest *enabled* value — distinct from the 0 sentinel — so it
    // must survive sanitization unchanged, not collapse to IDLE_TTL_DISABLED_MINUTES.
    RelaisConfig.setIdleTtlMinutes(context, IDLE_TTL_MIN_MINUTES)
    assertEquals(IDLE_TTL_MIN_MINUTES, RelaisConfig.idleTtlMinutes(context))
  }

  @Test fun `enabled value round-trips unclamped within band`() {
    RelaisConfig.setIdleTtlMinutes(context, 30)
    assertEquals(30, RelaisConfig.idleTtlMinutes(context))
  }

  // -------------------------------------------------------------------------
  // KEY_IDLE_TTL_LAST_NONZERO_MINUTES: the Configure-screen IDLE UNLOAD toggle's memory (feature-22
  // PR-B, task 3). The toggle itself is Compose-local and not JVM-testable; these pin the
  // RelaisConfig contract it's built on.
  // -------------------------------------------------------------------------

  @Test fun `disabling idle-ttl remembers the current value as last-non-zero`() {
    RelaisConfig.setIdleTtlMinutes(context, 30)
    // Mirrors the Configure-screen toggle's off path: save current, then disable.
    RelaisConfig.setIdleTtlLastNonZeroMinutes(context, RelaisConfig.idleTtlMinutes(context))
    RelaisConfig.setIdleTtlMinutes(context, IDLE_TTL_DISABLED_MINUTES)
    assertEquals(IDLE_TTL_DISABLED_MINUTES, RelaisConfig.idleTtlMinutes(context))
    assertEquals(30, RelaisConfig.idleTtlLastNonZeroMinutes(context))
  }

  @Test fun `re-enabling restores the remembered last-non-zero value, not the default`() {
    RelaisConfig.setIdleTtlLastNonZeroMinutes(context, 45)
    RelaisConfig.setIdleTtlMinutes(context, IDLE_TTL_DISABLED_MINUTES)
    // Mirrors the Configure-screen toggle's on path: restore the remembered value.
    val restored = RelaisConfig.idleTtlLastNonZeroMinutes(context)
    RelaisConfig.setIdleTtlMinutes(context, restored)
    assertEquals(45, RelaisConfig.idleTtlMinutes(context))
  }

  @Test fun `re-enabling with nothing ever remembered falls back to the default`() {
    assertEquals(IDLE_TTL_DEFAULT_MINUTES, RelaisConfig.idleTtlLastNonZeroMinutes(context))
  }

  @Test fun `setIdleTtlLastNonZeroMinutes ignores an attempt to remember the disabled sentinel`() {
    RelaisConfig.setIdleTtlLastNonZeroMinutes(context, 20)
    RelaisConfig.setIdleTtlLastNonZeroMinutes(context, IDLE_TTL_DISABLED_MINUTES) // must be a no-op
    assertEquals(20, RelaisConfig.idleTtlLastNonZeroMinutes(context))
  }

  @Test fun `stepper decrement at the floor never reaches the disabled sentinel`() {
    // Pins the toggle/stepper boundary: the ladder's bottom rung is IDLE_TTL_MIN_MINUTES, never 0 —
    // only the IDLE UNLOAD toggle may write IDLE_TTL_DISABLED_MINUTES.
    val next = nextRung(IDLE_TTL_MIN_MINUTES, IDLE_TTL_LADDER, up = false)
    assertEquals(IDLE_TTL_MIN_MINUTES, next)
    RelaisConfig.setIdleTtlMinutes(context, next)
    assertEquals(IDLE_TTL_MIN_MINUTES, RelaisConfig.idleTtlMinutes(context))
  }
}

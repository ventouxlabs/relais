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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the adb-trampoline token gate used by [RelaisControlActivity]. */
class RelaisControlTokenTest {

  @Test
  fun `matching token is accepted`() {
    assertTrue(controlTokenMatches("secret-key", "secret-key"))
  }

  @Test
  fun `wrong, prefix, and different-length tokens are rejected`() {
    assertFalse(controlTokenMatches("wrong-key!", "secret-key"))
    assertFalse(controlTokenMatches("secret", "secret-key"))
    assertFalse(controlTokenMatches("secret-key-extra", "secret-key"))
  }

  @Test
  fun `missing or empty token is rejected`() {
    assertFalse(controlTokenMatches(null, "secret-key"))
    assertFalse(controlTokenMatches("", "secret-key"))
  }
}

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
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RelaisApiKeyRotationTest {

  // Plain prefs stand in for the encrypted store (the AndroidKeystore is unavailable under Robolectric).
  private val store: SharedPreferences
    get() = RuntimeEnvironment.getApplication().getSharedPreferences("rotation_test", Context.MODE_PRIVATE)

  @Test fun `rotate replaces the key, persists it and invalidates the old one`() {
    val old = RelaisConfig.apiKeyIn(store)

    val fresh = RelaisConfig.rotateApiKeyIn(store)

    assertNotEquals(old, fresh)
    assertEquals(fresh, RelaisConfig.apiKeyIn(store))
    assertNull(authenticate("Bearer $old", RelaisConfig.apiKeyIn(store)))
    assertEquals(AuthScheme.BEARER, authenticate("Bearer $fresh", RelaisConfig.apiKeyIn(store)))
  }
}

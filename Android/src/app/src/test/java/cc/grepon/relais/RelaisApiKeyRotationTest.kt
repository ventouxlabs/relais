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
import org.junit.Assert.assertThrows
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

  @Test fun `rotate is written synchronously so a process death cannot revive the old key`() {
    val durable = DurabilityFake()
    val fresh = RelaisConfig.rotateApiKeyIn(durable)

    // Only data that reached disk survives a process death; apply() would leave this null.
    assertEquals(fresh, durable.onDisk["api_key"])
  }

  @Test fun `rotate fails loudly when the key cannot be persisted`() {
    assertThrows(IllegalStateException::class.java) {
      RelaisConfig.rotateApiKeyIn(DurabilityFake(commitSucceeds = false))
    }
  }

  /** In-memory prefs: commit() reaches [onDisk]; apply() only queues, as if the process died before flush. */
  private class DurabilityFake(private val commitSucceeds: Boolean = true) : SharedPreferences {
    val onDisk = mutableMapOf<String, String?>()
    private val memory = mutableMapOf<String, String?>()

    override fun getString(key: String, defValue: String?): String? = memory[key] ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
      private val pending = mutableMapOf<String, String?>()
      override fun putString(key: String, value: String?) = apply { pending[key] = value }
      override fun commit(): Boolean {
        if (!commitSucceeds) return false
        memory.putAll(pending)
        onDisk.putAll(pending)
        return true
      }
      override fun apply() { memory.putAll(pending) }
      override fun putStringSet(key: String, values: MutableSet<String>?) = this
      override fun putInt(key: String, value: Int) = this
      override fun putLong(key: String, value: Long) = this
      override fun putFloat(key: String, value: Float) = this
      override fun putBoolean(key: String, value: Boolean) = this
      override fun remove(key: String) = this
      override fun clear() = this
    }
    override fun getAll(): MutableMap<String, *> = memory
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun getBoolean(key: String, defValue: Boolean) = defValue
    override fun contains(key: String) = memory.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
  }
}

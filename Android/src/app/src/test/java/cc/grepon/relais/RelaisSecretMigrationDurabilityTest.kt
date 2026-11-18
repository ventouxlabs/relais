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

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Secrets must survive process death: only commit() is durable in the fake. */
class RelaisSecretMigrationDurabilityTest {

  @Test fun `migration copies each legacy secret durably then removes the plain value durably`() {
    val plain = DurableFake(mapOf("api_key" to "k", "tls_keystore_pass" to "t", "hf_token" to "h"))
    val secure = DurableFake()

    RelaisConfig.migrateSecretsBetween(plain, secure)

    assertEquals("k", secure.onDisk["api_key"])
    assertEquals("t", secure.onDisk["tls_keystore_pass"])
    assertEquals("h", secure.onDisk["hf_token"])
    assertTrue(plain.onDisk.isEmpty())
  }

  @Test fun `migration keeps the legacy value when the secure write fails`() {
    val plain = DurableFake(mapOf("api_key" to "k", "hf_token" to "h"))
    val secure = DurableFake(commitSucceeds = false)

    RelaisConfig.migrateSecretsBetween(plain, secure)

    assertEquals("k", plain.onDisk["api_key"])
    assertEquals("h", plain.onDisk["hf_token"])
    assertTrue(secure.onDisk.isEmpty())
  }

  @Test fun `migration retries next time and drops plain once secure is committed`() {
    val plain = DurableFake(mapOf("api_key" to "k"))
    val secure = DurableFake(commitSucceeds = false)
    RelaisConfig.migrateSecretsBetween(plain, secure)
    secure.commitSucceeds = true

    RelaisConfig.migrateSecretsBetween(plain, secure)

    assertEquals("k", secure.onDisk["api_key"])
    assertNull(plain.onDisk["api_key"])
  }

  @Test fun `migration keeps an existing secure value and still clears the plain copy`() {
    val plain = DurableFake(mapOf("api_key" to "old"))
    val secure = DurableFake(mapOf("api_key" to "new"))

    RelaisConfig.migrateSecretsBetween(plain, secure)

    assertEquals("new", secure.onDisk["api_key"])
    assertNull(plain.onDisk["api_key"])
  }

  @Test fun `hf token is written durably and reports success`() {
    val sp = DurableFake()
    assertTrue(RelaisConfig.setHfTokenIn(sp, "tok"))
    assertEquals("tok", sp.onDisk["hf_token"])
  }

  @Test fun `hf token clear is durable`() {
    val sp = DurableFake(mapOf("hf_token" to "tok"))
    assertTrue(RelaisConfig.setHfTokenIn(sp, null))
    assertNull(sp.onDisk["hf_token"])
  }

  @Test fun `hf token reports failure when not committed`() {
    assertFalse(RelaisConfig.setHfTokenIn(DurableFake(commitSucceeds = false), "tok"))
  }

  @Test fun `webhook secret is generated durably and stable`() {
    val sp = DurableFake()
    val s = RelaisConfig.webhookHmacSecretIn(sp)
    assertEquals(s, sp.onDisk["webhook_hmac_secret"])
    assertEquals(s, RelaisConfig.webhookHmacSecretIn(sp))
  }

  @Test fun `webhook secret fails loudly without leaking the secret when not persisted`() {
    val sp = DurableFake(commitSucceeds = false)
    val e = assertThrows(IllegalStateException::class.java) { RelaisConfig.webhookHmacSecretIn(sp) }
    assertFalse(e.message.orEmpty().matches(Regex(".*[0-9a-f]{32}.*")))
  }

  private class DurableFake(
    initial: Map<String, String> = emptyMap(),
    var commitSucceeds: Boolean = true,
  ) : SharedPreferences {
    val onDisk = initial.toMutableMap<String, String?>()
    private val memory = initial.toMutableMap<String, String?>()

    override fun getString(key: String, defValue: String?): String? = memory[key] ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
      private val puts = mutableMapOf<String, String?>()
      private val removes = mutableSetOf<String>()
      private fun applyTo(m: MutableMap<String, String?>) {
        removes.forEach { m.remove(it) }
        puts.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
      }
      override fun putString(key: String, value: String?) = apply { puts[key] = value }
      override fun remove(key: String) = apply { removes += key }
      override fun commit(): Boolean {
        if (!commitSucceeds) return false
        applyTo(memory); applyTo(onDisk)
        return true
      }
      override fun apply() { applyTo(memory) }
      override fun putStringSet(key: String, values: MutableSet<String>?) = this
      override fun putInt(key: String, value: Int) = this
      override fun putLong(key: String, value: Long) = this
      override fun putFloat(key: String, value: Float) = this
      override fun putBoolean(key: String, value: Boolean) = this
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

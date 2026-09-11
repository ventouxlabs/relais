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

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The durability rule for generated secrets: **key material must never be more durable than the
 * secret that opens it** ([RelaisConfig.persistNewSecret], feature-18 codex round 12).
 *
 * The bug this pins is not a wrong value, it is a wrong *ordering*. `apply()` updates memory and
 * schedules the disk write; the caller then writes a PKCS12 file immediately. A process kill in
 * that window leaves a readable keystore whose password was never persisted — and on the next start
 * `RelaisTls`'s recovery rule sees `UnrecoverableKeyException`, correctly concludes the material is
 * provably unrecoverable, and rotates. The CA rotation invalidates every client's import.
 *
 * So the recovery rule and this rule are not independent: [RelaisTlsRecoveryRuleTest] pins what to
 * do when a secret is genuinely lost, and this pins that a secret is never *reported* lost when it
 * was merely un-flushed. Pinning either alone leaves the cascade reachable.
 *
 * A fake is used rather than Robolectric because the decision under test is not Android behaviour —
 * it is whether this code demands a durable write and refuses to continue without one.
 */
class RelaisSecretDurabilityTest {

  @Test
  fun `the secret is committed, not merely scheduled`() {
    val prefs = FakePrefs(commitResult = true)

    RelaisConfig.persistNewSecret(prefs, "ca_pass")

    // The whole defect is apply()-vs-commit(), so the assertion has to be able to tell them apart.
    // Asserting only "a value came back" passes on the broken code.
    assertTrue("the write must be durable before returning", prefs.editor.commitCalled)
    assertFalse("apply() only schedules the write — that is the bug", prefs.editor.applyCalled)
  }

  @Test
  fun `the value returned is the value persisted`() {
    val prefs = FakePrefs(commitResult = true)

    val returned = RelaisConfig.persistNewSecret(prefs, "ca_pass")

    // Returning one secret while storing another would open every keystore exactly once and then
    // never again — the same end state as not persisting at all.
    assertEquals(returned, prefs.editor.written["ca_pass"])
    assertTrue("a secret must not be empty", returned.isNotEmpty())
  }

  @Test
  fun `a failed commit throws instead of returning an unpersisted secret`() {
    val prefs = FakePrefs(commitResult = false)

    try {
      RelaisConfig.persistNewSecret(prefs, "ca_pass")
      fail("a secret that could not be persisted must not be returned")
    } catch (e: IllegalStateException) {
      // Loud on purpose. Returning here would hand the caller a password it is about to protect a
      // keystore with, having just been told that password will not survive a restart — which
      // manufactures precisely the state this rule exists to prevent.
      assertTrue(e.message.orEmpty().contains("ca_pass"))
    }
  }

  @Test
  fun `each generated secret is distinct`() {
    val first = RelaisConfig.persistNewSecret(FakePrefs(commitResult = true), "ca_pass")
    val second = RelaisConfig.persistNewSecret(FakePrefs(commitResult = true), "tls_pass")

    // The CA and leaf keystores are separate files and must not share a password: one compromised
    // or lost secret must not open both.
    assertTrue("generated secrets must not be constant", first != second)
  }

  private class FakeEditor(private val commitResult: Boolean) : SharedPreferences.Editor {
    val written = mutableMapOf<String, String?>()
    var commitCalled = false
    var applyCalled = false

    override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
      written[key] = value
    }

    override fun commit(): Boolean {
      commitCalled = true
      return commitResult
    }

    override fun apply() {
      applyCalled = true
    }

    override fun putStringSet(key: String, values: MutableSet<String>?) = this

    override fun putInt(key: String, value: Int) = this

    override fun putLong(key: String, value: Long) = this

    override fun putFloat(key: String, value: Float) = this

    override fun putBoolean(key: String, value: Boolean) = this

    override fun remove(key: String) = this

    override fun clear() = this
  }

  private class FakePrefs(commitResult: Boolean) : SharedPreferences {
    val editor = FakeEditor(commitResult)

    override fun edit(): SharedPreferences.Editor = editor

    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()

    override fun getString(key: String, defValue: String?): String? = editor.written[key] ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues

    override fun getInt(key: String, defValue: Int) = defValue

    override fun getLong(key: String, defValue: Long) = defValue

    override fun getFloat(key: String, defValue: Float) = defValue

    override fun getBoolean(key: String, defValue: Boolean) = defValue

    override fun contains(key: String) = editor.written.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(
      listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
      listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
  }
}

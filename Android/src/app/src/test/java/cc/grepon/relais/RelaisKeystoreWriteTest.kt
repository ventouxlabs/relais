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

import java.io.File
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [RelaisTls.storeAtomically] — the keystore write path (feature-18 codex rounds 9 and 14).
 *
 * **What this does NOT test, said plainly: durability.** Round 14's defect was that the bytes
 * reached only the OS page cache before the rename, so a power loss could publish a name pointing
 * at contents that never landed. Proving that fix requires cutting power mid-write; no unit test
 * and no instrumentation probe can do it. The `fsync` calls are the guarantee, and their presence
 * is verified by reading the code, not by this file.
 *
 * Claiming otherwise is the specific trap this branch keeps hitting — a surface that asserts a
 * property it does not check. So what is pinned here is the part that *is* observable and that a
 * refactor can genuinely break: the target ends up complete and loadable, an overwrite leaves the
 * new contents rather than a mixture, and no temp file survives either outcome.
 *
 * The leftover-temp assertions matter more than they look. A stale `relais_tls.p12.tmp` is the
 * litter the next write trips over, and on the failure path it is the difference between a clean
 * retry and an accumulating mess in the app's private dir.
 */
class RelaisKeystoreWriteTest {

  @get:Rule val folder = TemporaryFolder()

  @Test
  fun `the written keystore is complete and loadable`() {
    val target = File(folder.root, "relais_tls.p12")

    RelaisTls.storeAtomically(keystoreWith("first"), target, PASS)

    assertTrue("the target must exist after a write", target.exists())
    assertEquals("first", aliasOf(target))
    assertFalse("no temp file may survive a successful write", tempFor(target).exists())
  }

  @Test
  fun `an overwrite replaces the contents entirely`() {
    val target = File(folder.root, "relais_tls.p12")

    RelaisTls.storeAtomically(keystoreWith("first"), target, PASS)
    RelaisTls.storeAtomically(keystoreWith("second"), target, PASS)

    // A write that appended, or that left the old entry alongside the new one, would still produce
    // a loadable keystore — so the assertion has to look at what is actually inside it.
    assertEquals("second", aliasOf(target))
    assertEquals("the old entry must be gone, not merely shadowed", 1, aliasCount(target))
    assertFalse(tempFor(target).exists())
  }

  @Test
  fun `a failed write cleans up its temp and does not create the target`() {
    // A directory standing where the temp file must go, so the write cannot succeed. Every
    // assertion below is about THIS file — an earlier draft asserted about an unrelated target the
    // test never wrote, which passes against any implementation and checks nothing.
    val blocked = File(folder.root, "blocked.p12")
    assertTrue(tempFor(blocked).mkdir())

    runCatching { RelaisTls.storeAtomically(keystoreWith("first"), blocked, PASS) }

    assertFalse("a failed write must not leave its temp behind", tempFor(blocked).exists())
    assertFalse("a failed write must not create the target", blocked.exists())
  }

  @Test
  fun `an existing keystore survives a failed overwrite`() {
    val target = File(folder.root, "relais_tls.p12")
    RelaisTls.storeAtomically(keystoreWith("first"), target, PASS)

    // The property the whole temp-file dance exists for: a write that dies partway must leave the
    // PREVIOUS keystore intact and loadable, never a truncated one. This is also the safe-direction
    // outcome the best-effort directory fsync falls back to.
    assertTrue(tempFor(target).mkdir())
    runCatching { RelaisTls.storeAtomically(keystoreWith("second"), target, PASS) }

    assertEquals("the old keystore must still be readable", "first", aliasOf(target))
  }

  private fun keystoreWith(alias: String): KeyStore {
    val ca = RelaisCertMint.mintCa()
    return KeyStore.getInstance("PKCS12").apply {
      load(null, PASS)
      setKeyEntry(alias, ca.keyPair.private, PASS, arrayOf<java.security.cert.Certificate>(ca.certificate))
    }
  }

  private fun loaded(target: File): KeyStore =
    KeyStore.getInstance("PKCS12").apply { target.inputStream().use { load(it, PASS) } }

  private fun aliasOf(target: File): String = loaded(target).aliases().toList().single()

  private fun aliasCount(target: File): Int = loaded(target).aliases().toList().size

  private fun tempFor(target: File): File = File(target.parentFile, "${target.name}.tmp")

  private companion object {
    val PASS = "test-password".toCharArray()
  }
}

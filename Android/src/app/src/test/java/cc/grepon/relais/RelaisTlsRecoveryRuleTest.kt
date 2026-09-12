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

import java.io.EOFException
import java.io.IOException
import java.security.UnrecoverableKeyException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one pure decision in the keystore-recovery rule: **may this failure rotate key material?**
 *
 * Rotating on the wrong failure and refusing on the wrong failure are both real bugs this branch
 * shipped, in opposite directions and a round apart:
 *  - rotating on *any* read failure silently moved the NODE KEY PIN, breaking every
 *    `--pinnedpubkey` client with no log line and nothing in the UI;
 *  - refusing on *every* read failure turned a lost keystore password — which AndroidKeyStore
 *    invalidation causes on ordinary devices, via a lockscreen or biometric change — into a node
 *    that could never start again, with no in-app way out.
 *
 * The rule that satisfies both: rotate **only** when the old material is provably unrecoverable.
 * A wrong password is proof (nothing can ever decrypt that file). A truncated read, an IO error, or
 * an unknown cause is not, and must fail loudly instead.
 *
 * `RelaisTls` needs a `Context` and so is unreachable from this lane, but the predicate is pure —
 * which makes the distinction the whole rule turns on testable, unlike the paths that use it.
 */
class RelaisTlsRecoveryRuleTest {

  @Test
  fun `a wrong keystore password is provably unrecoverable`() {
    // What `KeyStore.getKey` throws directly when the password does not match.
    assertTrue(RelaisTls.isKeyMaterialUnrecoverable(UnrecoverableKeyException("bad password")))
  }

  @Test
  fun `the IOException KeyStore load wraps it in also counts`() {
    // `KeyStore.load` reports a wrong password as an IOException *caused by*
    // UnrecoverableKeyException, so matching only the top-level type would miss the common path.
    val wrapped = IOException("keystore password was incorrect", UnrecoverableKeyException("bad"))

    assertTrue(RelaisTls.isKeyMaterialUnrecoverable(wrapped))
  }

  @Test
  fun `a plain IO failure is NOT unrecoverable and must never rotate`() {
    // The case that must keep failing loudly: retrying may succeed, so rotating here would destroy
    // a recoverable identity — the silent-pin-change bug.
    assertFalse(RelaisTls.isKeyMaterialUnrecoverable(IOException("disk went away")))
    assertFalse(RelaisTls.isKeyMaterialUnrecoverable(EOFException("truncated read")))
  }

  @Test
  fun `a corrupt keystore is NOT treated as a lost password`() {
    // A truncated or corrupt PKCS12 also surfaces as IOException from `load`, but with a parse
    // cause rather than UnrecoverableKeyException. Corruption is not proof the password is gone,
    // and the atomic write exists precisely so this state should not arise — treating it as
    // "rotate" would give back the silent rotation that fix removed.
    val corrupt = IOException("stream does not represent a PKCS12 key store", IllegalStateException("bad tag"))

    assertFalse(RelaisTls.isKeyMaterialUnrecoverable(corrupt))
  }

  @Test
  fun `the cause chain is walked to any depth`() {
    // Providers wrap differently across implementations; the rule is about the underlying cause,
    // not about how many layers a particular stack happened to add.
    val nested = IOException("outer", RuntimeException("middle", UnrecoverableKeyException("root")))

    assertTrue(RelaisTls.isKeyMaterialUnrecoverable(nested))
  }
}

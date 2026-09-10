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

import java.net.InetAddress
import java.security.cert.X509Certificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for [RelaisCertMint.needsReissue] and the leaf-key-reuse invariant
 * (feature-18 T3).
 *
 * [`the leaf key survives a re-mint, so the node key pin does not move`] is a **contract, not an
 * optimization.** feature-23's multi-node router pins the leaf SPKI; if a refactor ever regenerates
 * the leaf key on re-issue, every pinned client breaks the first time DHCP moves the phone, and it
 * breaks silently — the node comes up fine and only the clients fail. This test is the thing
 * standing between that refactor and a shipped regression.
 */
class RelaisCertReissueTest {

  private val now = System.currentTimeMillis()

  @Test
  fun `an unchanged address set on a fresh leaf needs no re-issue`() {
    val sans = RelaisCertMint.buildSanList(addrs("192.168.1.40"))
    val leaf = mint(sans)

    assertFalse(RelaisCertMint.needsReissue(leaf, sans, now))
  }

  @Test
  fun `adding an address triggers a re-issue`() {
    val leaf = mint(RelaisCertMint.buildSanList(addrs("192.168.1.40")))
    val grown = RelaisCertMint.buildSanList(addrs("192.168.1.40", "10.0.0.7"))

    assertTrue(RelaisCertMint.needsReissue(leaf, grown, now))
  }

  @Test
  fun `losing an address triggers a re-issue`() {
    val leaf = mint(RelaisCertMint.buildSanList(addrs("192.168.1.40", "10.0.0.7")))
    val shrunk = RelaisCertMint.buildSanList(addrs("192.168.1.40"))

    assertTrue(RelaisCertMint.needsReissue(leaf, shrunk, now))
  }

  @Test
  fun `a leaf inside the fifteen-day window is re-issued even with an unchanged address set`() {
    val sans = RelaisCertMint.buildSanList(addrs("192.168.1.40"))
    val leaf = mint(sans)
    // The leaf is minted for 90 days; look at it from 80 days in the future, leaving 10.
    val tenDaysLeft = now + 80L * 86_400_000L

    assertTrue(RelaisCertMint.needsReissue(leaf, sans, tenDaysLeft))
  }

  @Test
  fun `a leaf with more than fifteen days left is left alone`() {
    val sans = RelaisCertMint.buildSanList(addrs("192.168.1.40"))
    val leaf = mint(sans)
    val twentyDaysLeft = now + 70L * 86_400_000L

    assertFalse(RelaisCertMint.needsReissue(leaf, sans, twentyDaysLeft))
  }

  @Test
  fun `an IPv6 address does not thrash the re-issue check`() {
    // IPv6 is no longer certified at all (the listeners are IPv4-only), so a node that acquires an
    // IPv6 address must produce the SAME SAN set as before and NOT re-issue. Getting this wrong
    // would churn the certificate on every start for any node with IPv6 connectivity — which, on a
    // modern carrier network, is most of them.
    val withIpv6 = RelaisCertMint.buildSanList(addrs("192.168.1.40", "2001:db8::1"))
    val ipv4Only = RelaisCertMint.buildSanList(addrs("192.168.1.40"))
    // GeneralName compares by DER encoding, so this is an exact set-and-order match.
    assertEquals("an IPv6 address must not change the certified set", ipv4Only, withIpv6)

    val leaf = mint(withIpv6)
    assertFalse(RelaisCertMint.needsReissue(leaf, ipv4Only, now))
  }

  @Test
  fun `the leaf key survives a re-mint, so the node key pin does not move`() {
    val ca = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()

    val before =
      RelaisCertMint.mintLeaf(
        ca.keyPair.private,
        ca.certificate,
        leafKey.public,
        RelaisCertMint.buildSanList(addrs("192.168.1.40")),
      )
    // The address changed — exactly the DHCP event that forces a re-mint.
    val after =
      RelaisCertMint.mintLeaf(
        ca.keyPair.private,
        ca.certificate,
        leafKey.public,
        RelaisCertMint.buildSanList(addrs("10.0.0.7")),
      )

    // The certificate is a new document...
    assertNotEquals(before.serialNumber, after.serialNumber)
    assertFalse(before.encoded contentEquals after.encoded)
    // ...over byte-identical key material, so `curl --pinnedpubkey` keeps working.
    assertArrayEquals(before.publicKey.encoded, after.publicKey.encoded)
    assertEquals(
      RelaisCertFingerprint.spkiSha256Base64(before.publicKey),
      RelaisCertFingerprint.spkiSha256Base64(after.publicKey),
    )
  }

  @Test
  fun `the CA is unchanged across a leaf re-mint, so an imported CA keeps verifying`() {
    val ca = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()

    val before =
      RelaisCertMint.mintLeaf(
        ca.keyPair.private, ca.certificate, leafKey.public,
        RelaisCertMint.buildSanList(addrs("192.168.1.40")),
      )
    val after =
      RelaisCertMint.mintLeaf(
        ca.keyPair.private, ca.certificate, leafKey.public,
        RelaisCertMint.buildSanList(addrs("10.0.0.7")),
      )

    before.verify(ca.keyPair.public)
    after.verify(ca.keyPair.public)
  }

  /**
   * A leaf is only reusable if the **current** CA signed it (codex P2).
   *
   * `RelaisTls` recovers from a `relais_ca.p12` that is absent, or whose key material is provably
   * unrecoverable, by minting a replacement CA. The
   * leaf on disk survives that, with its SAN set and expiry untouched — so [needsReissue] alone
   * says "keep it", and the node would serve a leaf chained to a CA that cannot have signed it.
   * Nothing about the certificate looks wrong; it simply fails to validate at every client.
   *
   * The predicate cannot detect this on its own (it is not given the CA, deliberately — it answers
   * one question), so `RelaisTls` filters the loaded leaf through a signature check first. This
   * pins the property that filter exists for: a leaf and a foreign CA must not verify.
   */
  @Test
  fun `a leaf does not verify against a CA that did not sign it`() {
    val original = RelaisCertMint.mintCa()
    val replacement = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()
    val sans = RelaisCertMint.buildSanList(addrs("192.168.1.40"))
    val leaf =
      RelaisCertMint.mintLeaf(original.keyPair.private, original.certificate, leafKey.public, sans)

    // The predicate on its own is blind to the swap: same SANs, same expiry, so it says "keep".
    assertFalse(
      "needsReissue cannot see a CA swap — which is exactly why RelaisTls checks the signature",
      RelaisCertMint.needsReissue(leaf, sans, now),
    )
    // The signature check is what catches it.
    leaf.verify(original.keyPair.public) // throws if this ever stops holding
    assertFalse(
      "a leaf must not verify against a replacement CA",
      runCatching { leaf.verify(replacement.keyPair.public) }.isSuccess,
    )
  }

  /**
   * The pin-survival contract stated as the property a *caller* must not break (M2).
   *
   * `RelaisTls.loadLeafKeyPair` used to swallow every failure and generate a fresh key, so a
   * truncated read or a password mismatch silently rotated the NODE KEY PIN. It now throws rather
   * than rotating, and this pins the reason that matters: a regenerated key is a *different* pin,
   * so a client's `--pinnedpubkey` stops matching with no error the user can act on.
   *
   * Not reachable through `RelaisTls` from the JVM lane (it needs a `Context`), so this asserts the
   * property over the minter directly — which is where the value actually is, since it is the thing
   * feature-23 depends on.
   */
  @Test
  fun `a regenerated leaf key changes the node key pin, which is why rotation must never be silent`() {
    val ca = RelaisCertMint.mintCa()
    val sans = RelaisCertMint.buildSanList(addrs("192.168.1.40"))

    val original = RelaisCertMint.generateLeafKeyPair()
    val regenerated = RelaisCertMint.generateLeafKeyPair()

    val pinBefore = RelaisCertFingerprint.spkiSha256Base64(original.public)
    val pinAfter = RelaisCertFingerprint.spkiSha256Base64(regenerated.public)

    // Both mint perfectly valid certificates under the same CA — which is exactly the problem: the
    // node looks healthy, the chain verifies, and only the pinned client breaks.
    RelaisCertMint.mintLeaf(ca.keyPair.private, ca.certificate, original.public, sans)
      .verify(ca.keyPair.public)
    RelaisCertMint.mintLeaf(ca.keyPair.private, ca.certificate, regenerated.public, sans)
      .verify(ca.keyPair.public)

    assertNotEquals(
      "a regenerated key is a different pin — silently doing this breaks every --pinnedpubkey client",
      pinBefore,
      pinAfter,
    )
  }

  private fun addrs(vararg s: String): List<InetAddress> = s.map { InetAddress.getByName(it) }

  private fun mint(sans: List<org.bouncycastle.asn1.x509.GeneralName>): X509Certificate {
    val ca = RelaisCertMint.mintCa()
    return RelaisCertMint.mintLeaf(
      ca.keyPair.private,
      ca.certificate,
      RelaisCertMint.generateLeafKeyPair().public,
      sans,
    )
  }
}

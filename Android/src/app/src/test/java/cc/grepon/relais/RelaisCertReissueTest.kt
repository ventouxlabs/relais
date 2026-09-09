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
  fun `an IPv6 SAN does not thrash the re-issue check`() {
    // getSubjectAlternativeNames renders ::1 back as 0:0:0:0:0:0:0:1, so a string comparison would
    // report a difference here on every single start and re-mint the certificate forever. The
    // check compares DER, which is why this holds.
    val sans = RelaisCertMint.buildSanList(addrs("2001:db8::1"))
    val leaf = mint(sans)

    assertFalse(RelaisCertMint.needsReissue(leaf, sans, now))
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

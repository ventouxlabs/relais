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

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for [RelaisCertFingerprint] (feature-18 T3).
 *
 * The load-bearing test here is [`the CA fingerprint and the node key pin are different values`]:
 * the node publishes two `sha256/...` strings that look identical in shape, and pasting the wrong
 * one into `curl --pinnedpubkey` fails with an error that names neither. Every surface labels them
 * distinctly; this is what stops a refactor from quietly making them the same value.
 */
class RelaisCertFingerprintTest {

  @Test
  fun `spki digest is the sha256 of the DER SubjectPublicKeyInfo, base64, sha256-prefixed`() {
    val key = RelaisCertMint.generateLeafKeyPair().public

    // Recomputed independently of the implementation, the way a user would with
    // `openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64`.
    val expected =
      "sha256/" +
        Base64.getEncoder()
          .encodeToString(MessageDigest.getInstance("SHA-256").digest(key.encoded))

    assertEquals(expected, RelaisCertFingerprint.spkiSha256Base64(key))
  }

  @Test
  fun `the digest is over SubjectPublicKeyInfo, so a re-parsed key hashes identically`() {
    val key = RelaisCertMint.generateLeafKeyPair().public
    // Round-trip through DER: only a digest over the encoded SubjectPublicKeyInfo survives this
    // unchanged. A digest over, say, the raw RSA modulus would too — hence the previous test pins
    // the exact bytes, and this one pins that the value is a property of the key, not the object.
    val reparsed = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(key.encoded))

    assertEquals(
      RelaisCertFingerprint.spkiSha256Base64(key),
      RelaisCertFingerprint.spkiSha256Base64(reparsed),
    )
  }

  @Test
  fun `the CA fingerprint and the node key pin are different values`() {
    val ca = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()

    val caFingerprint = RelaisCertFingerprint.spkiSha256Base64(ca.keyPair.public)
    val nodeKeyPin = RelaisCertFingerprint.spkiSha256Base64(leafKey.public)

    // Conflating these is the single most likely support failure this feature creates: the CA value
    // in --pinnedpubkey produces an opaque error and no security.
    assertNotEquals(caFingerprint, nodeKeyPin)
  }

  @Test
  fun `the SPKI digest and the certificate digest are over different bytes`() {
    val ca = RelaisCertMint.mintCa()

    // Compared as raw bytes, not as the two rendered forms: base64 and colon-hex can never be
    // string-equal, so comparing the renderings would be an assertion incapable of failing.
    val spkiDigest =
      Base64.getDecoder()
        .decode(RelaisCertFingerprint.spkiSha256Base64(ca.certificate.publicKey).removePrefix("sha256/"))
    val certDigest =
      RelaisCertFingerprint.certSha256Hex(ca.certificate).split(":").map { it.toInt(16).toByte() }
        .toByteArray()

    assertEquals("both are SHA-256", 32, spkiDigest.size)
    assertEquals(32, certDigest.size)
    // Only the SPKI digest is usable as a pin: the certificate digest changes on every re-mint,
    // while the key — and therefore the pin — does not.
    assertFalse("the key digest must not equal the certificate digest", spkiDigest contentEquals certDigest)
  }

  @Test
  fun `the cert hash is colon-separated uppercase hex, 32 groups`() {
    val ca = RelaisCertMint.mintCa()

    val hex = RelaisCertFingerprint.certSha256Hex(ca.certificate)
    val groups = hex.split(":")

    assertEquals(32, groups.size)
    assertTrue("not uppercase hex pairs: $hex", groups.all { it.matches(Regex("[0-9A-F]{2}")) })
  }
}

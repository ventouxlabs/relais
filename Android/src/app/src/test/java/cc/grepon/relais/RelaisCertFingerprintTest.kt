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

  /**
   * The NODE KEY PIN must be a **complete, pasteable `--pinnedpubkey` argument** (codex round 16).
   *
   * It was published as `sha256/<b64>`, the HPKP spelling. curl's grammar is `sha256//<b64>`, and a
   * single-slash value is not rejected as malformed — it is taken as a **path to a public-key
   * file**, which does not exist, so the pin never matches.
   *
   * That failure is indistinguishable from a real key mismatch: all of our format, a correct format
   * with a wrong hash, and a nonexistent path produce the identical
   * `curl: (90) SSL: public key does not match pinned public key`. A user pasting the published pin
   * would read that as "this node's key changed" — and be sent to the very dashboard row round 10
   * added to explain a moved pin, for a reason that is not true.
   *
   * So the assertion is on the exact separator, not on "contains sha256": the broken value contains
   * it too.
   */
  @Test
  fun `the node key pin is a complete curl pinnedpubkey argument`() {
    val leafKey = RelaisCertMint.generateLeafKeyPair()

    val pin = RelaisCertFingerprint.curlPin(leafKey.public)

    assertTrue("curl's grammar is sha256// — one slash is parsed as a filename", pin.startsWith("sha256//"))
    assertFalse("a third slash is not curl's grammar either", pin.startsWith("sha256///"))
    // Nothing for the user to prepend, edit, or strip: SECURITY.md told them to write
    // `sha256//<value>` while the value already began `sha256/`, which composes to
    // `sha256//sha256/…`. The value must be the whole argument, and what follows the prefix must be
    // a complete SHA-256 rather than a truncated or re-prefixed one.
    //
    // Counted slashes here at first. That is wrong twice over: base64's alphabet CONTAINS '/', so
    // the count depends on the random key and the test failed about half the time — and the
    // property was never "how many slashes", it was "the prefix is exact and the rest is a digest".
    assertEquals(32, Base64.getDecoder().decode(pin.removePrefix("sha256//")).size)
  }

  @Test
  fun `the pin and the CA fingerprint carry the same digest in different spellings`() {
    val key = RelaisCertMint.generateLeafKeyPair().public

    val display = RelaisCertFingerprint.spkiSha256Base64(key)
    val pin = RelaisCertFingerprint.curlPin(key)

    // Same bytes, two spellings. If these ever diverge, one of the two published values is a digest
    // of something the other is not, and no error message would say which.
    assertEquals(display.removePrefix("sha256/"), pin.removePrefix("sha256//"))
    assertNotEquals("the two spellings must not be interchangeable by accident", display, pin)
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

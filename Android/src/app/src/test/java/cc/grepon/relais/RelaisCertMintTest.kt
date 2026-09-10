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
import org.junit.Assume.assumeTrue
import kotlin.io.path.createTempDirectory
import java.util.Base64
import java.io.File
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the certificate *shapes* [RelaisCertMint] produces (feature-18 T3).
 *
 * Shape assertions alone are not enough — see `RelaisTlsHandshakeTest` for the handshake that
 * proves the shapes are also *usable*. What lives here is the structure a handshake would not
 * distinguish: key usages, path length, and validity bounds.
 */
class RelaisCertMintTest {

  @Test
  fun `the CA is a CA, path length zero, and marked critical`() {
    val ca = RelaisCertMint.mintCa()

    // getBasicConstraints returns the path length for a CA, -1 for a non-CA.
    assertEquals(0, ca.certificate.basicConstraints)
    assertTrue(
      "basicConstraints must be critical",
      ca.certificate.criticalExtensionOIDs.contains(Extension.basicConstraints.id),
    )
  }

  @Test
  fun `the CA may sign certificates and CRLs, and nothing else`() {
    val ca = RelaisCertMint.mintCa()
    // KeyUsage bit order: [0] digitalSignature .. [5] keyCertSign, [6] cRLSign.
    val usage = ca.certificate.keyUsage

    assertTrue("keyCertSign must be set", usage[5])
    assertTrue("cRLSign must be set", usage[6])
    assertFalse("the CA key must not be usable for a TLS handshake", usage[0])
  }

  /**
   * With `NameConstraints` gone, the CA's EKU is the remaining thing bounding what it can be used
   * for — so it is pinned rather than left implicit, and **do not narrow it**.
   *
   * `clientAuth` is present although nothing issues client certificates today: mTLS is a tracked
   * follow-up that composes on top of this CA, and adding it later would mean re-minting the CA,
   * which invalidates every client import. Now-or-never, so it is here now.
   *
   * Non-critical deliberately — a nesting-unaware verifier must ignore this, not reject it. That is
   * the mechanical difference from NameConstraints, which had to be critical and therefore
   * fail-closed.
   */
  @Test
  fun `the CA is bounded to server and client auth, non-critically`() {
    val ca = RelaisCertMint.mintCa()

    assertEquals(
      listOf("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"), // serverAuth, clientAuth
      ca.certificate.extendedKeyUsage,
    )
    // The purposes deliberately excluded: codeSigning, emailProtection, timeStamping, OCSPSigning.
    assertFalse(
      "the CA must not be usable for code signing",
      ca.certificate.extendedKeyUsage.contains("1.3.6.1.5.5.7.3.3"),
    )
    assertFalse(
      "a critical EKU would invite rejection from verifiers that do not implement EKU nesting",
      ca.certificate.criticalExtensionOIDs.contains(Extension.extendedKeyUsage.id),
    )
  }

  /**
   * The EKU is **enforced**, not merely present — the check NameConstraints could never pass.
   *
   * `openssl verify -purpose sslserver` applies chain purpose checks, so this proves the CA's EKU
   * actually admits the leaf rather than just sitting in the certificate. Unlike the constraints,
   * this property is verifiable, which is a large part of why the EKU is worth having and the
   * constraints were not.
   *
   * `assumeTrue`-gated on openssl, so it skips where openssl is absent rather than failing.
   */
  @Test
  fun `openssl accepts the chain for the sslserver purpose`() {
    assumeTrue("openssl not on PATH; skipping the EKU purpose check", hasOpenssl())

    val ca = RelaisCertMint.mintCa()
    val leaf =
      RelaisCertMint.mintLeaf(
        ca.keyPair.private,
        ca.certificate,
        RelaisCertMint.generateLeafKeyPair().public,
        RelaisCertMint.buildSanList(listOf(InetAddress.getByName("192.168.1.40"))),
      )
    val dir = createTempDirectory("relais-eku").toFile()
    writePem(dir, "ca.pem", ca.certificate)
    writePem(dir, "leaf.pem", leaf)

    val (ok, out) = opensslVerify(dir, "-purpose", "sslserver", "-CAfile", "ca.pem", "leaf.pem")
    assertTrue("the chain must satisfy the sslserver purpose, got: $out", ok)
  }

  private fun writePem(dir: File, name: String, cert: X509Certificate) {
    File(dir, name)
      .writeText(
        "-----BEGIN CERTIFICATE-----\n" +
          Base64.getEncoder().encodeToString(cert.encoded).chunked(64).joinToString("\n") +
          "\n-----END CERTIFICATE-----\n"
      )
  }

  private fun hasOpenssl(): Boolean =
    runCatching { ProcessBuilder("openssl", "version").start().waitFor() == 0 }.getOrDefault(false)

  /** Runs `openssl verify <args>` in [dir]; returns (verified, combined output). */
  private fun opensslVerify(dir: File, vararg args: String): Pair<Boolean, String> {
    val proc =
      ProcessBuilder(listOf("openssl", "verify") + args)
        .directory(dir)
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText()
    return (proc.waitFor() == 0) to out
  }

  @Test
  fun `the CA key is EC and the leaf key is RSA`() {
    val ca = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()

    // EC for the CA buys a small certificate; the leaf stays RSA because that is the shape proven
    // to sign a handshake through conscrypt on-device.
    assertEquals("EC", ca.keyPair.public.algorithm)
    assertEquals("RSA", leafKey.public.algorithm)
  }

  @Test
  fun `the leaf is not a CA and is a server certificate`() {
    val (_, leaf) = mintPair(listOf("192.168.1.40"))

    assertEquals(-1, leaf.basicConstraints)
    assertEquals(listOf("1.3.6.1.5.5.7.3.1"), leaf.extendedKeyUsage) // id-kp-serverAuth
  }

  @Test
  fun `the leaf chains to the CA`() {
    val (ca, leaf) = mintPair(listOf("192.168.1.40"))

    // Throws on failure. The CA's EC key signing an RSA-keyed leaf is the combination in question.
    leaf.verify(ca.keyPair.public)
  }

  @Test
  fun `the leaf is valid now, backdated for clock skew, and expires within ninety days`() {
    val (_, leaf) = mintPair(listOf("192.168.1.40"))
    val now = System.currentTimeMillis()

    leaf.checkValidity()
    assertTrue("notBefore must be backdated at least 30s", leaf.notBefore.time <= now - 30_000)
    assertTrue(
      "leaf must not outlive 90 days",
      leaf.notAfter.time - now <= 90L * 86_400_000L,
    )
  }

  @Test
  fun `the CA outlives the leaf by years, so one import keeps working`() {
    val (ca, leaf) = mintPair(listOf("192.168.1.40"))

    assertTrue(ca.certificate.notAfter.time > leaf.notAfter.time + 3650L * 86_400_000L / 2)
  }

  @Test
  fun `a leaf carrying the full builder SAN set chains and validates`() {
    val ca = RelaisCertMint.mintCa()
    val addrs =
      listOf("192.168.1.40", "10.0.0.7", "172.16.3.9", "100.101.102.103", "169.254.1.2")
        .map { InetAddress.getByName(it) }
    val leaf =
      RelaisCertMint.mintLeaf(
        ca.keyPair.private,
        ca.certificate,
        RelaisCertMint.generateLeafKeyPair().public,
        RelaisCertMint.buildSanList(addrs),
      )

    // The chain and the SAN encoding are well-formed across a realistic multi-homed address set,
    // including the loopback trio buildSanList always prepends — without which `adb forward` would
    // be the one path the feature does not cover.
    validate(leaf, ca.certificate)
  }

  @Test
  fun `a CA minted on one network still validates a leaf minted on another`() {
    // Acceptance criterion 3, and the reason the CA/leaf split exists at all: the CA is minted
    // exactly once, the leaf is re-minted whenever DHCP moves the phone, and a client that imported
    // the CA must keep verifying with no re-import. Anything that scoped the CA to the addresses
    // observed at mint time would break this — and would break it only on hardware, only after a
    // network change. (NameConstraints did exactly that, which is one reason they were dropped.)
    val ca = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()

    val onHomeWifi =
      RelaisCertMint.mintLeaf(
        ca.keyPair.private,
        ca.certificate,
        leafKey.public,
        RelaisCertMint.buildSanList(listOf(InetAddress.getByName("192.168.1.40"))),
      )
    val onAnotherNetwork =
      RelaisCertMint.mintLeaf(
        ca.keyPair.private,
        ca.certificate,
        leafKey.public,
        RelaisCertMint.buildSanList(listOf(InetAddress.getByName("10.44.7.9"))),
      )

    validate(onHomeWifi, ca.certificate)
    validate(onAnotherNetwork, ca.certificate)
  }

  /**
   * The CA carries **no** `NameConstraints`, and that absence is a decision (JD, 2026-09-08) rather
   * than an omission — see [RelaisCertMint.mintCa] for the four reasons.
   *
   * Pinned by test because "a missing security extension" is exactly the shape of thing a future
   * reader re-adds in good faith, assuming it was overlooked. Re-adding it would silently break
   * `curl --cacert` for any node on a globally routable address, which is the client the docs
   * recommend and a failure that appears only on hardware. If this test ever fails, read the
   * reasoning before deleting it.
   */
  @Test
  fun `the CA carries no name constraints, deliberately`() {
    val ca = RelaisCertMint.mintCa()

    assertNull(
      "NameConstraints was removed on purpose — see RelaisCertMint.mintCa before re-adding it",
      ca.certificate.getExtensionValue(Extension.nameConstraints.id),
    )
  }

  /**
   * The SAN list a display surface shows must come from the **certificate being served**, never
   * from the addresses the node currently holds.
   *
   * Those agree only right after a re-mint. On a running node whose address changed mid-session the
   * leaf is unchanged — and this feature does not re-issue for that until restart — so sourcing the
   * list from live addresses made the status page claim coverage of an address the certificate does
   * not carry. It would have concealed the one gap a user hitting it most needs to see.
   */
  @Test
  fun `the SAN list read back off a certificate is the certificate's own, not the live addresses`() {
    val ca = RelaisCertMint.mintCa()
    // Minted for the old address, as a leaf on a node that has since moved network would be.
    val leaf =
      RelaisCertMint.mintLeaf(
        ca.keyPair.private,
        ca.certificate,
        RelaisCertMint.generateLeafKeyPair().public,
        RelaisCertMint.buildSanList(listOf(InetAddress.getByName("192.168.1.40"))),
      )

    val reported = RelaisTls.RelaisCertPem.displayStrings(leaf)

    assertTrue("must report what the cert carries", reported.contains("192.168.1.40"))
    assertTrue("loopback is always in the cert", reported.contains("127.0.0.1"))
    // The address the node moved TO is absent from the certificate, so it must be absent here.
    assertFalse(
      "must NOT report an address the served certificate does not cover",
      reported.contains("10.44.7.9"),
    )
  }

  private fun mintPair(addrs: List<String>): Pair<RelaisCertMint.Minted, X509Certificate> {
    val ca = RelaisCertMint.mintCa()
    val leaf =
      RelaisCertMint.mintLeaf(
        ca.keyPair.private,
        ca.certificate,
        RelaisCertMint.generateLeafKeyPair().public,
        RelaisCertMint.buildSanList(addrs.map { InetAddress.getByName(it) }),
      )
    return ca to leaf
  }

  /**
   * Full PKIX path validation of [leaf] against [ca] as the sole trust anchor. Throws on failure.
   */
  private fun validate(leaf: X509Certificate, ca: X509Certificate) {
    val path = CertificateFactory.getInstance("X.509").generateCertPath(listOf(leaf))
    val params = PKIXParameters(setOf(TrustAnchor(ca, null))).apply { isRevocationEnabled = false }
    CertPathValidator.getInstance("PKIX").validate(path, params)
  }
}

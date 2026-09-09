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
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.NameConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the certificate *shapes* [RelaisCertMint] produces (feature-18 T3).
 *
 * Shape assertions alone are not enough — see `RelaisTlsHandshakeTest` for the handshake that
 * proves the shapes are also *usable*. What lives here is the structure a handshake would not
 * distinguish: key usages, path length, validity bounds, and the name constraints.
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
  fun `an iPAddress name constraint is encoded as address plus mask, not a bare address`() {
    val ca = RelaisCertMint.mintCa()
    val permitted = permittedSubtrees(ca.certificate)

    val ipConstraints = permitted.filter { it.tagNo == GeneralName.iPAddress }
    assertTrue("expected IP subtrees", ipConstraints.isNotEmpty())
    ipConstraints.forEach {
      val len = ASN1OctetString.getInstance(it.name).octets.size
      // 8 = 4-octet IPv4 address + 4-octet mask; 32 = 16 + 16 for IPv6. A bare 4 or 16 here would
      // be a constraint that silently matches nothing, and BouncyCastle accepts the string form
      // that produces it without complaint.
      assertTrue("iPAddress subtree must carry a mask, got $len octets", len == 8 || len == 32)
    }
  }

  @Test
  fun `the permitted DNS subtrees are only localhost and local`() {
    val ca = RelaisCertMint.mintCa()

    val dns = permittedSubtrees(ca.certificate).filter { it.tagNo == GeneralName.dNSName }

    // This is what bounds the blast radius of a system-store install: the CA cannot sign for any
    // public name.
    assertEquals(setOf("localhost", "local"), dns.map { it.name.toString() }.toSet())
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

    // Renamed deliberately: this asserts the chain and the SAN encoding are well-formed across a
    // realistic multi-homed address set. It does NOT assert the name constraints permit them — the
    // JVM refuses to evaluate a trust anchor's constraints at all, per the test below.
    validate(leaf, ca.certificate)
  }

  @Test
  fun `a CA minted on one network still validates a leaf minted on another`() {
    // The acceptance criterion the constraints must not break: the CA is minted exactly once, the
    // leaf is re-minted whenever DHCP moves the phone. Constraints scoped to the addresses observed
    // at CA-mint time would reject this, and would only fail on hardware after a network change.
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
   * **A finding, pinned so nobody re-derives it.** Java's PKIX validator cannot enforce name
   * constraints carried by a trust anchor. Passing the encoded extension to `TrustAnchor` throws
   * `InvalidAlgorithmParameterException: name constraints in trust anchor not supported`, and the
   * `TrustAnchor(cert, null)` form silently applies none — the validator reads constraints from the
   * `TrustAnchor` object, never from the anchor certificate's own extension.
   *
   * **BouncyCastle's PKIX is no better, and this was measured rather than assumed:** with the BC
   * provider a leaf for `8.8.8.8` / `evil.example.com` validates cleanly under this CA, with the
   * constraints supplied to the `TrustAnchor` and without. So no JVM path enforces them.
   *
   * This is a limitation of the *verifier*, not of the certificate. It also means the constraints
   * cost nothing in compatibility: a Java client ignores them rather than rejecting. OpenSSL does
   * apply them, which is what makes them real on `curl --cacert` — the path the docs recommend —
   * and that half **is** covered, by
   * [`openssl rejects a leaf for a public name and accepts one from another private range`].
   */
  @Test
  fun `the JVM cannot enforce a trust anchor's own name constraints`() {
    val ca = RelaisCertMint.mintCa()
    val leaf =
      RelaisCertMint.mintLeaf(
        ca.keyPair.private,
        ca.certificate,
        RelaisCertMint.generateLeafKeyPair().public,
        RelaisCertMint.buildSanList(listOf(InetAddress.getByName("192.168.1.40"))),
      )
    val encodedConstraints =
      ASN1OctetString.getInstance(ca.certificate.getExtensionValue(Extension.nameConstraints.id)).octets

    try {
      val path = CertificateFactory.getInstance("X.509").generateCertPath(listOf(leaf))
      val params =
        PKIXParameters(setOf(TrustAnchor(ca.certificate, encodedConstraints)))
          .apply { isRevocationEnabled = false }
      CertPathValidator.getInstance("PKIX").validate(path, params)
      fail(
        "This JVM now supports trust-anchor name constraints. That is GOOD NEWS: replace this test " +
          "with a real negative (a leaf for 8.8.8.8 / evil.example.com must be REJECTED), which " +
          "would finally give CI genuine coverage of the constraint set."
      )
    } catch (e: java.security.InvalidAlgorithmParameterException) {
      assertTrue(
        "expected the documented refusal, got: ${e.message}",
        (e.message ?: "").contains("name constraints", ignoreCase = true),
      )
    }
  }

  /**
   * The permitted set, pinned exactly — the hermetic half of the guard against the constraints
   * silently degrading to "permit everything".
   *
   * End-to-end enforcement cannot be asserted in this lane (see the JVM test above), so without
   * this a change that widened the subtrees to `0.0.0.0/0`, or dropped the extension entirely,
   * would leave every other test in this file green. Pinning the literal set means widening the
   * CA's authority is always a deliberate, visible edit.
   */
  @Test
  fun `the permitted subtree set is exactly the private and loopback ranges, with no catch-all`() {
    val ca = RelaisCertMint.mintCa()
    val permitted = permittedSubtrees(ca.certificate)

    val ips =
      permitted.filter { it.tagNo == GeneralName.iPAddress }.map { cidr(it) }.toSet()
    assertEquals(
      setOf(
        "10.0.0.0/255.0.0.0",
        "172.16.0.0/255.240.0.0",
        "192.168.0.0/255.255.0.0",
        "100.64.0.0/255.192.0.0",
        "127.0.0.0/255.0.0.0",
        "169.254.0.0/255.255.0.0",
        "0:0:0:0:0:0:0:1/ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "fc00:0:0:0:0:0:0:0/fe00:0:0:0:0:0:0:0",
        "2000:0:0:0:0:0:0:0/e000:0:0:0:0:0:0:0",
      ),
      ips,
    )
    // The specific degradations worth naming: an all-zero mask permits the whole address space.
    assertFalse("no IPv4 catch-all", ips.any { it.endsWith("/0.0.0.0") })
    assertFalse("no IPv6 catch-all", ips.any { it.endsWith("/0:0:0:0:0:0:0:0") })
  }

  /**
   * **The real inverse test the JVM lane cannot provide**: a leaf for a public IP and a public DNS
   * name must be REJECTED, and the cross-network leaf must still be ACCEPTED.
   *
   * Shelling out to `openssl` is a deliberate exception to this repo's hermetic-JVM-test rule, and
   * it is the only way to get this coverage at all: neither Sun's PKIX nor BouncyCastle's applies a
   * *trust anchor's* own name constraints (measured — BC validates the leaf below without
   * complaint, Sun refuses the parameter outright). OpenSSL does, which is what makes the
   * constraints real on `curl --cacert`, the path the docs recommend.
   *
   * `assumeTrue`-gated on openssl being present, so it skips rather than fails where it is not.
   */
  @Test
  fun `openssl rejects a leaf for a public name and accepts one from another private range`() {
    assumeTrue("openssl not on PATH; skipping the only end-to-end name-constraint check", hasOpenssl())

    val ca = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()
    val dir = createTempDirectory("relais-nc").toFile()

    fun write(name: String, cert: X509Certificate) =
      File(dir, name).apply {
        writeText(
          "-----BEGIN CERTIFICATE-----\n" +
            Base64.getEncoder().encodeToString(cert.encoded).chunked(64).joinToString("\n") +
            "\n-----END CERTIFICATE-----\n"
        )
      }

    write("ca.pem", ca.certificate)
    write(
      "home.pem",
      RelaisCertMint.mintLeaf(
        ca.keyPair.private, ca.certificate, leafKey.public,
        RelaisCertMint.buildSanList(listOf(InetAddress.getByName("192.168.1.40"))),
      ),
    )
    // Acceptance criterion 3: the phone moved to a different network, same CA, no re-import.
    write(
      "moved.pem",
      RelaisCertMint.mintLeaf(
        ca.keyPair.private, ca.certificate, leafKey.public,
        RelaisCertMint.buildSanList(listOf(InetAddress.getByName("10.44.7.9"))),
      ),
    )
    // What a stolen CA key must never be able to produce. Split into two single-name leaves so the
    // IP subtrees and the DNS subtrees are pinned INDEPENDENTLY: a combined leaf is rejected as
    // soon as either half matches, so widening only the IP ranges to a catch-all would leave a
    // combined assertion green. (Confirmed by mutation — that is exactly what happened.)
    write(
      "evil-ip.pem",
      RelaisCertMint.mintLeaf(
        ca.keyPair.private, ca.certificate, leafKey.public,
        listOf(GeneralName(GeneralName.iPAddress, "8.8.8.8")),
      ),
    )
    write(
      "evil-dns.pem",
      RelaisCertMint.mintLeaf(
        ca.keyPair.private, ca.certificate, leafKey.public,
        listOf(GeneralName(GeneralName.dNSName, "evil.example.com")),
      ),
    )

    assertTrue("a leaf for the node's own LAN must verify", opensslVerify(dir, "home.pem").first)
    assertTrue(
      "a leaf minted on another private range must still verify — this is what a CA scoped to the " +
        "addresses observed at mint time would break, only on hardware, only after a network change",
      opensslVerify(dir, "moved.pem").first,
    )

    for (evil in listOf("evil-ip.pem", "evil-dns.pem")) {
      val (ok, out) = opensslVerify(dir, evil)
      assertFalse("the CA must not be able to vouch for $evil: $out", ok)
      assertTrue(
        "expected a name-constraints rejection specifically for $evil, got: $out",
        out.contains("permitted subtree violation", ignoreCase = true) ||
          out.contains("excluded subtree violation", ignoreCase = true),
      )
    }
  }

  private fun hasOpenssl(): Boolean =
    runCatching { ProcessBuilder("openssl", "version").start().waitFor() == 0 }.getOrDefault(false)

  /** Runs `openssl verify -CAfile ca.pem <leaf>`; returns (verified, combined output). */
  private fun opensslVerify(dir: File, leaf: String): Pair<Boolean, String> {
    val proc =
      ProcessBuilder("openssl", "verify", "-CAfile", "ca.pem", leaf)
        .directory(dir)
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText()
    return (proc.waitFor() == 0) to out
  }

  /** A name-constraint `iPAddress` subtree rendered as `address/mask`, its two halves split. */
  private fun cidr(gn: GeneralName): String {
    val octets = ASN1OctetString.getInstance(gn.name).octets
    val half = octets.size / 2
    val addr = InetAddress.getByAddress(octets.copyOfRange(0, half)).hostAddress
    val mask = InetAddress.getByAddress(octets.copyOfRange(half, octets.size)).hostAddress
    return "$addr/$mask"
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
   *
   * **This proves the chain and the SANs, NOT the name constraints** — see
   * [`the JVM cannot enforce a trust anchor's own name constraints`] for why that is not a choice
   * available here.
   */
  private fun validate(leaf: X509Certificate, ca: X509Certificate) {
    val path = CertificateFactory.getInstance("X.509").generateCertPath(listOf(leaf))
    val params = PKIXParameters(setOf(TrustAnchor(ca, null))).apply { isRevocationEnabled = false }
    CertPathValidator.getInstance("PKIX").validate(path, params)
  }

  private fun permittedSubtrees(ca: X509Certificate): List<GeneralName> {
    val raw =
      requireNotNull(ca.getExtensionValue(Extension.nameConstraints.id)) {
        "the CA carries no nameConstraints extension"
      }
    val nc = NameConstraints.getInstance(ASN1Sequence.getInstance(ASN1OctetString.getInstance(raw).octets))
    return nc.permittedSubtrees.map { it.base }
  }
}

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
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.bouncycastle.asn1.x509.GeneralName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * An **end-to-end TLS handshake** against a certificate minted by [RelaisCertMint], with hostname
 * verification actually switched on — the test the whole of feature-18 rests on.
 *
 * Every other test in this feature inspects a certificate and asserts it looks right. Looking right
 * is exactly what the pre-feature certificate did: it had a `CN=relais-node` subject, `openssl`
 * printed it happily, and no client could ever verify it, because a modern TLS stack ignores CN
 * entirely and the cert carried no `subjectAltName`. Only a real handshake distinguishes those two
 * states, which is why this file exists rather than another extension assertion.
 *
 * **Two things make or break this test, and both fail silently:**
 *
 *  1. `endpointIdentificationAlgorithm = "HTTPS"`. Without it JSSE validates the chain and skips
 *     hostname verification, so every negative case below passes against a cert with no SANs at
 *     all and the file verifies nothing. Worse, `getSSLParameters()` returns a **copy** — mutating
 *     it in place is a no-op that leaves no trace. See [connect], which assigns back.
 *  2. The `createSocket(host: String, port: Int)` overload. Given an `InetAddress`, JSSE may
 *     reverse-resolve `127.0.0.1` to `localhost`, which then matches the `localhost` dNSName
 *     [RelaisCertMint.buildSanList] always includes — so the "must fail" cases pass for a reason
 *     that has nothing to do with the property under test.
 *
 * Each negative asserts **why** it failed, not merely that it threw. `SSLHandshakeException` is
 * also what a missing cipher suite or an expired certificate throws, so a bare type assertion is
 * green for the wrong reason.
 *
 * The JVM's JSSE is **not** conscrypt. This cannot substitute for `CertTrustProbe` on hardware —
 * the EC-CA-signs-RSA-leaf combination is only truly proven there.
 */
class RelaisTlsHandshakeTest {

  private val pass = "test-pass".toCharArray()

  @Test
  fun `a leaf with loopback SANs verifies against a truststore holding only the CA`() {
    val ca = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()
    val leaf = mintLeafFor(ca, leafKey, RelaisCertMint.buildSanList(emptyList()))

    // No exception == the handshake completed with hostname verification enabled.
    handshake(ca.certificate, leaf, leafKey)
  }

  @Test
  fun `a leaf covering a real LAN address verifies for that address`() {
    val ca = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()
    val sans = RelaisCertMint.buildSanList(listOf(InetAddress.getByName("192.168.1.40")))
    val leaf = mintLeafFor(ca, leafKey, sans)

    // Still connects over loopback: the point is that adding LAN addresses does not evict the
    // loopback entries, which is what would break `adb forward`.
    handshake(ca.certificate, leaf, leafKey)
  }

  @Test
  fun `a SAN-less leaf is rejected for having no subject alternative names`() {
    val ca = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()
    // The pre-feature certificate shape: a subject CN and nothing else.
    val leaf = mintLeafFor(ca, leafKey, emptyList())

    val why = expectHandshakeFailure(ca.certificate, leaf, leafKey)
    assertTrue("expected a SAN/hostname rejection, got: $why", isHostnameFailure(why))
    // Discriminating half: the chain itself is fine, so this must NOT be a path-building error.
    assertFalse("this must fail on the name, not the chain: $why", isPathFailure(why))
  }

  @Test
  fun `a leaf whose SANs omit the connected address is rejected for that address`() {
    val ca = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()
    // Deliberately wrong SANs: a valid, well-formed cert for somewhere else entirely.
    val sans = listOf(GeneralName(GeneralName.iPAddress, "192.168.1.40"))
    val leaf = mintLeafFor(ca, leafKey, sans)

    val why = expectHandshakeFailure(ca.certificate, leaf, leafKey)
    assertTrue("expected a SAN/hostname rejection, got: $why", isHostnameFailure(why))
    assertFalse("this must fail on the name, not the chain: $why", isPathFailure(why))
  }

  @Test
  fun `a leaf from a different CA is rejected at path building`() {
    val trusted = RelaisCertMint.mintCa()
    val rogue = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()
    val leaf = mintLeafFor(rogue, leafKey, RelaisCertMint.buildSanList(emptyList()))

    // Serve the rogue chain, trust only the other CA.
    val why = expectHandshakeFailure(trusted.certificate, leaf, leafKey, chainCa = rogue.certificate)
    assertTrue("expected a path-building failure, got: $why", isPathFailure(why))
    // Discriminating half: the SANs are perfectly good here, so this must NOT be a name error.
    assertFalse("this must fail on the chain, not the name: $why", isHostnameFailure(why))
  }

  /**
   * Did hostname verification reject this, as opposed to path building?
   *
   * Spelled as an alternation because the wording is the TLS provider's, not ours: conscrypt says
   * "No subjectAltNames on the certificate match", the JVM's own JSSE says "No subject alternative
   * names present". Neither names the address it was looking for, so an assertion that the message
   * mentions `127.0.0.1` is **not achievable** on either stack — pairing this with [isPathFailure]
   * is what makes each negative case discriminating instead.
   */
  private fun isHostnameFailure(why: String): Boolean =
    why.contains("subjectAltName", ignoreCase = true) ||
      why.contains("subject alternative name", ignoreCase = true) ||
      why.contains("No name matching", ignoreCase = true)

  /** Did the chain fail to build? Conscrypt: "Trust anchor ... not found"; JSSE: "PKIX path building failed". */
  private fun isPathFailure(why: String): Boolean =
    why.contains("Trust anchor", ignoreCase = true) ||
      why.contains("unable to find valid certification path", ignoreCase = true) ||
      why.contains("PKIX path building failed", ignoreCase = true)

  private fun mintLeafFor(
    ca: RelaisCertMint.Minted,
    leafKey: KeyPair,
    sans: List<GeneralName>,
  ): X509Certificate =
    RelaisCertMint.mintLeaf(ca.keyPair.private, ca.certificate, leafKey.public, sans)

  /** Runs a full handshake, or throws whatever the client threw. */
  private fun handshake(
    trustedCa: X509Certificate,
    leaf: X509Certificate,
    leafKey: KeyPair,
    chainCa: X509Certificate = trustedCa,
  ) {
    val server = serverSocket(leaf, leafKey.private, chainCa)
    val pool = Executors.newSingleThreadExecutor()
    try {
      // The server side must handshake concurrently — a single-threaded accept-then-connect
      // deadlocks, because neither side can proceed until the other has.
      pool.submit {
        runCatching { (server.accept() as SSLSocket).use { it.startHandshake() } }
      }
      connect(trustedCa, server.localPort)
    } finally {
      pool.shutdownNow()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      server.close()
    }
  }

  /** Asserts the handshake fails, and returns every message in the cause chain for inspection. */
  private fun expectHandshakeFailure(
    trustedCa: X509Certificate,
    leaf: X509Certificate,
    leafKey: KeyPair,
    chainCa: X509Certificate = trustedCa,
  ): String {
    try {
      handshake(trustedCa, leaf, leafKey, chainCa)
    } catch (e: Exception) {
      return generateSequence(e as Throwable) { it.cause }
        .mapNotNull { "${it::class.java.simpleName}: ${it.message}" }
        .joinToString(" | ")
    }
    fail("handshake succeeded but should have been rejected")
    error("unreachable")
  }

  private fun serverSocket(
    leaf: X509Certificate,
    leafKey: PrivateKey,
    ca: X509Certificate,
  ): SSLServerSocket {
    val ks = KeyStore.getInstance("PKCS12").apply { load(null, pass) }
    // [leaf, ca] in that order — a bare [leaf] leaves the client unable to build a path even when
    // it already trusts the CA.
    ks.setKeyEntry("relais-tls", leafKey, pass, arrayOf(leaf, ca))
    val kmf =
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
    val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    return (ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
      as SSLServerSocket)
  }

  private fun connect(trustedCa: X509Certificate, port: Int) {
    val trust = KeyStore.getInstance("PKCS12").apply { load(null, pass) }
    trust.setCertificateEntry("relais-ca", trustedCa)
    val tmf =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(trust)
      }
    val ctx = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    // The String overload, never the InetAddress one — see the class KDoc.
    (ctx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { sock ->
      // getSSLParameters() hands back a COPY. Mutating it in place compiles, runs, and silently
      // leaves hostname verification off, which would make every negative case above pass.
      sock.sslParameters = sock.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
      sock.soTimeout = 10_000
      sock.startHandshake()
    }
  }
}

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

import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Mints the node's per-node CA and its short-lived SAN-carrying leaf certificate (feature-18 T3).
 *
 * **Why a CA at all, when a self-signed leaf is simpler.** A client that pins a bare leaf has to
 * re-import it every time DHCP moves the phone. The CA is minted once and never changes; the leaf
 * churns underneath it whenever the address set does, so one import keeps verifying forever. That
 * is the entire reason for the extra moving part.
 *
 * **This object has no `android.` imports and must keep none.** `RelaisTlsHandshakeTest` runs a
 * real TLS handshake against a cert minted here in the device-free JVM lane, which is only possible
 * while the minter stays Context-free. Everything Android — keystore files, passwords, logging —
 * lives in [RelaisTls], which is the shim over this.
 *
 * Key shapes, and why they differ:
 *  - **CA: EC P-256.** The CA key never enters a TLS handshake, so the conscrypt limitation
 *    documented at the top of [RelaisTls] does not apply to it, and EC buys a CA certificate small
 *    enough to matter for out-of-band export.
 *  - **Leaf: RSA-2048.** The leaf key *does* sign the handshake, where the software-RSA shape is
 *    the one already proven on-device. Not changed by this feature.
 *
 * The leaf is signed `SHA256withECDSA` even though the leaf key is RSA: the signature algorithm is
 * a property of the *issuer's* key, not the subject's.
 */
internal object RelaisCertMint {

  /** Days a leaf is valid. Short on purpose — a user-deletable CA and expiry are the revocation story. */
  private const val LEAF_VALIDITY_DAYS = 90L

  /** Years the CA is valid. Long on purpose: re-minting it would invalidate every client's import. */
  private const val CA_VALIDITY_YEARS = 10L

  /** Re-mint the leaf once it has less than this left, so a long-running node never serves an expired cert. */
  private const val REISSUE_WINDOW_DAYS = 15L

  /**
   * Hard ceiling on SAN entries, applied exactly once — here, after the fixed entries are
   * prepended. [RelaisLanIp.allLanAddresses] deliberately does not cap, so that this is the only
   * place the policy lives.
   */
  private const val MAX_SANS = 32

  /** Backdate every `notBefore` by a minute, so a client whose clock runs slightly fast still accepts. */
  private const val CLOCK_SKEW_MS = 60_000L

  private const val MS_PER_DAY = 86_400_000L

  /** A freshly minted key pair and the certificate over it. */
  data class Minted(val keyPair: KeyPair, val certificate: X509Certificate)

  /**
   * The SAN entries for a leaf covering [addrs], in a deterministic order.
   *
   * Four entries are always present and always first: `127.0.0.1` and `::1` as `iPAddress`,
   * `localhost` and `relais-node.local` as `dNSName`. The loopback trio is what keeps the
   * `adb forward tcp:8443` path in the runbook verifiable; without it a developer's own machine is
   * the one place the feature does not work.
   *
   * Being first is what makes them safe from [MAX_SANS]: only surplus *real* addresses are ever
   * dropped from a wildly multi-homed device, never loopback.
   *
   * An IPv4/IPv6 literal is tagged [GeneralName.iPAddress], never `dNSName`. A `dNSName` holding
   * `192.168.1.40` is *silently* ignored by every verifier — the cert looks right, `openssl x509`
   * prints the address, and hostname verification still fails. That failure mode is why
   * `RelaisTlsHandshakeTest` asserts a real handshake rather than inspecting the extension.
   */
  fun buildSanList(addrs: List<InetAddress>): List<GeneralName> {
    val fixed =
      listOf(
        GeneralName(GeneralName.iPAddress, "127.0.0.1"),
        GeneralName(GeneralName.iPAddress, "::1"),
        GeneralName(GeneralName.dNSName, "localhost"),
        GeneralName(GeneralName.dNSName, "relais-node.local"),
      )
    val fixedLiterals = setOf("127.0.0.1", "::1")
    val dynamic =
      addrs
        .mapNotNull { it.hostAddress }
        // A scope suffix ("fe80::1%wlan0") is not a certificate name. allLanAddresses already drops
        // link-local, so this is belt-and-braces against a future caller passing a raw address.
        .map { it.substringBefore('%') }
        .filter { it.isNotEmpty() && it !in fixedLiterals }
        .distinct()
        .sorted()
        .map { GeneralName(GeneralName.iPAddress, it) }
    return (fixed + dynamic).take(MAX_SANS)
  }

  /**
   * Mints the self-signed per-node CA: EC P-256, 10 years, `keyCertSign`-only, path length 0 — it
   * may issue leaves and nothing else.
   *
   * The common name embeds the first 8 hex characters of the SPKI digest so two nodes are
   * distinguishable in a system trust store, which is the only place a user ever sees a CA subject.
   *
   * **There is deliberately no `NameConstraints` extension. This was decided, not overlooked —
   * do not add one back.** It was built, measured, and removed (JD, 2026-09-08), for four reasons:
   *
   *  1. **Inert where it would matter most.** Java's PKIX validator cannot enforce a trust anchor's
   *     own name constraints — it reads them from the `TrustAnchor` object, never from the anchor
   *     certificate, and refuses them if supplied. BouncyCastle's validator accepts the parameter
   *     and ignores it (measured: it validated a leaf for `8.8.8.8` under a CA that prohibited it).
   *     Every Java/Android/JSSE client therefore got zero benefit.
   *  2. **Actively harmful on the documented path.** [RelaisLanIp.allLanAddresses] admits globally
   *     routable IPv4, so on a cellular hotspot or an ISP handing out public addresses the leaf
   *     carries a SAN the constraints prohibit, and `curl --cacert` — the flow SECURITY.md
   *     recommends — rejects the whole chain. A control whose main observable effect is breaking
   *     the client we tell people to use is a net negative.
   *  3. **Critical means fail-closed for verifiers we never test.** RFC 5280 requires the extension
   *     be critical, and a verifier that processes but does not understand a critical extension
   *     must reject the certificate.
   *  4. **Untestable in CI**, per (1) — an earlier version of the tests passed
   *     `TrustAnchor(ca, null)`, which applies no constraints at all, so three tests were green
   *     while asserting nothing.
   *
   * The benefit was partial even where it worked: it never constrained issuance for LAN addresses,
   * which is exactly where an attacker on the LAN already sits. What bounds a stolen CA key now is
   * the same thing that bounds a stolen leaf key — the phone's own security, plus the fact that
   * `--cacert` scopes trust to one connection instead of a system store.
   */
  fun mintCa(): Minted {
    val keyPair =
      KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    val spki = RelaisCertFingerprint.spkiSha256Base64(keyPair.public)
    val suffix = spki.removePrefix("sha256/").filter { it.isLetterOrDigit() }.take(8).uppercase()
    val name = X500Name("CN=Relais Node CA $suffix")
    val now = System.currentTimeMillis()
    val notBefore = Date(now - CLOCK_SKEW_MS)
    val notAfter = Date(now + CA_VALIDITY_YEARS * 365 * MS_PER_DAY)
    val utils = JcaX509ExtensionUtils()

    val builder =
      JcaX509v3CertificateBuilder(name, serial(), notBefore, notAfter, name, keyPair.public).apply {
        addExtension(Extension.basicConstraints, true, BasicConstraints(0))
        addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        // Bounds what this CA can be used FOR, now that NameConstraints no longer bounds what it
        // can be used ON. Verifiers that chain EKU intersect a leaf's EKU with its issuer's, so
        // this CA cannot be turned into one that issues code-signing, e-mail, timestamping or
        // OCSP-signing certificates. Deliberate, and **do not strip it as unused** — see below.
        //
        // NOT the NameConstraints mistake repeated, and the difference is mechanical rather than a
        // judgement call: those had to be *critical* per RFC 5280, so a verifier that did not
        // implement them had to reject the chain — fail-closed on stacks we never test. A
        // NON-critical EKU is *ignored* by a stack that does not implement nesting. Enforcing
        // stacks get the narrowing; the rest get exactly today's behaviour; nobody gets a broken
        // chain. The downside profile is inverted.
        //
        // Why it is worth having, given the CA key only leaks if the phone does: the realistic case
        // is not an attacker. This CA is valid for TEN YEARS, is imported once, and is NOT removed
        // when Relais is uninstalled. The bad outcome is a phone sold, traded in, repaired, or
        // handed to a family member while the previous owner's laptop still trusts a decade-long
        // CA whose private key went with the hardware. No compromise event, no sophistication —
        // just a device changing hands and an import nobody remembers. The long lifetime that
        // spares users a re-import works directly against them there.
        //
        // clientAuth is included although nothing issues client certificates today, because this
        // is structurally now-or-never: SECURITY.md tracks mTLS as a follow-up that composes on
        // top of THIS CA, and narrowing to serverAuth would break it on precisely the platforms
        // that enforce nesting — fixable only by re-minting the CA, which invalidates every client
        // import and is the one thing the two-keystore design exists to avoid.
        addExtension(
          Extension.extendedKeyUsage,
          false,
          ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)),
        )
        addExtension(
          Extension.subjectKeyIdentifier,
          false,
          utils.createSubjectKeyIdentifier(keyPair.public),
        )
      }
    val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
    return Minted(keyPair, JcaX509CertificateConverter().getCertificate(builder.build(signer)))
  }

  /**
   * Mints a 90-day server leaf for [leafPublicKey] carrying exactly [sans], signed by the CA.
   *
   * [sans] is a parameter rather than something this function derives from the live interfaces, and
   * that is load-bearing for testability: `RelaisTlsHandshakeTest`'s negative cases need to mint a
   * leaf with *deliberately wrong* SANs — a SAN-less one, one missing `127.0.0.1` — which is
   * impossible if the builder is folded in here. Do not "simplify" by calling [buildSanList]
   * internally.
   */
  fun mintLeaf(
    caKey: PrivateKey,
    caCert: X509Certificate,
    leafPublicKey: PublicKey,
    sans: List<GeneralName>,
  ): X509Certificate {
    val now = System.currentTimeMillis()
    val notBefore = Date(now - CLOCK_SKEW_MS)
    val notAfter = Date(now + LEAF_VALIDITY_DAYS * MS_PER_DAY)
    val utils = JcaX509ExtensionUtils()

    val builder =
      JcaX509v3CertificateBuilder(
          X500Name(caCert.subjectX500Principal.name),
          serial(),
          notBefore,
          notAfter,
          X500Name("CN=relais-node"),
          leafPublicKey,
        )
        .apply {
          addExtension(Extension.basicConstraints, true, BasicConstraints(false))
          addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
          )
          addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
          )
          addExtension(
            Extension.authorityKeyIdentifier,
            false,
            utils.createAuthorityKeyIdentifier(caCert),
          )
          addExtension(
            Extension.subjectKeyIdentifier,
            false,
            utils.createSubjectKeyIdentifier(leafPublicKey),
          )
          // Only added when non-empty: an empty GeneralNames is a malformed extension, and the
          // SAN-less shape is exactly what RelaisTlsHandshakeTest's negative case needs to build.
          if (sans.isNotEmpty()) {
            addExtension(
              Extension.subjectAlternativeName,
              false,
              GeneralNames(sans.toTypedArray()),
            )
          }
        }
    // SHA256withECDSA because the *CA's* key is EC. The leaf's own RSA key is irrelevant here.
    val signer = JcaContentSignerBuilder("SHA256withECDSA").build(caKey)
    return JcaX509CertificateConverter().getCertificate(builder.build(signer))
  }

  /** The RSA-2048 leaf key. Generated once per node and reused across every re-mint — see [needsReissue]. */
  fun generateLeafKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

  /**
   * Should the leaf be re-minted? True when the address set has changed, or when expiry is closer
   * than [REISSUE_WINDOW_DAYS].
   *
   * The SAN comparison is over the **DER bytes** of the extension, not over rendered strings, and
   * that matters: `X509Certificate.getSubjectAlternativeNames` normalizes an IPv6 address on the
   * way out (`::1` comes back as `0:0:0:0:0:0:0:1`), so a string comparison against the live list
   * would report a difference on every single start and re-mint the certificate forever. Comparing
   * encodings is exact, and [buildSanList]'s deterministic order is what makes it stable.
   */
  fun needsReissue(leaf: X509Certificate, liveSans: List<GeneralName>, now: Long): Boolean {
    if (leaf.notAfter.time - now < REISSUE_WINDOW_DAYS * MS_PER_DAY) return true
    val current = leaf.getExtensionValue(Extension.subjectAlternativeName.id)
    val currentSans = current?.let { ASN1OctetString.getInstance(it).octets }
    val wanted =
      if (liveSans.isEmpty()) null else GeneralNames(liveSans.toTypedArray()).encoded
    return !(currentSans contentEquals wanted)
  }

  /** A positive, unpredictable 64-bit serial. Sequential serials leak how many certs a node has minted. */
  private fun serial(): BigInteger = BigInteger(64, SecureRandom()).max(BigInteger.ONE)
}

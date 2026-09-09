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
import org.bouncycastle.asn1.x509.GeneralSubtree
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.NameConstraints
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
   * Mints the self-signed per-node CA: EC P-256, 10 years, `keyCertSign`-only, path length 0 (it
   * may issue leaves and nothing else), and name-constrained.
   *
   * The common name embeds the first 8 hex characters of the SPKI digest so two nodes are
   * distinguishable in a system trust store, which is the only place a user ever sees a CA subject.
   *
   * **On the NameConstraints scope.** The constraints permit the *categories* of address a node can
   * ever hold — RFC1918, CGNAT, loopback, IPv4 link-local, IPv6 ULA and global unicast, plus the
   * `localhost` and `local` DNS subtrees — and deliberately **not** the addresses this node happens
   * to hold right now. Constraining to the observed set would be tighter, and would break the
   * feature's central promise: the CA is minted once and never re-minted, while the leaf's
   * addresses change with DHCP, so a CA permitting only `192.168.1.0/24` would reject its own
   * node's leaf after a move to a `10.0.0.0/8` network. That failure appears only on hardware,
   * only after a network change. `RelaisCertMintTest` pins the surviving case explicitly.
   *
   * **Be precise about what this buys, because the name of the extension promises more than it
   * delivers here.** It blocks issuance for *public* names: a CA a user installed system-wide
   * cannot sign for `google.com` or a public IP, because the permitted `dNSName` subtrees are only
   * `localhost` and `local`. It does **not** constrain issuance *within* the private ranges at all
   * — whoever holds this key can still sign for any RFC1918, CGNAT or loopback address, which is
   * every address on the user's own network. Against an attacker already on that LAN it buys
   * nothing; its value is bounding a stolen key's reach to the user's own networks instead of the
   * whole internet.
   *
   * **How much that is worth depends entirely on the verifier, and it is measured, not assumed.**
   * OpenSSL — and so `curl --cacert`, the documented path — applies a root's name constraints.
   * Java's PKIX validator does **not**: it takes constraints from the `TrustAnchor` object rather
   * than the anchor certificate, and outright refuses them there
   * (`name constraints in trust anchor not supported`). `RelaisCertMintTest` pins that refusal, so
   * nobody re-derives it and nobody writes a test that appears to prove enforcement and does not.
   * Defence in depth on the documented path; inert on a Java client.
   *
   * Marked critical per RFC 5280, which says conforming CAs MUST. That is the RFC-correct choice
   * and it costs nothing on the verifiers above (a trust anchor's own extensions are generally not
   * processed), but a verifier that *did* process it and did not understand it would reject the
   * chain outright — which is one of the things `CertTrustProbe` exists to catch on conscrypt.
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
        addExtension(Extension.nameConstraints, true, nameConstraints())
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

  /**
   * The permitted subtrees described on [mintCa]. An `iPAddress` subtree is **address plus mask**
   * (8 octets for IPv4, 32 for IPv6), not the bare 4/16 octets a SAN entry uses — BouncyCastle
   * derives that from the CIDR string form, and a bare `"192.168.0.0"` here would encode a
   * constraint that silently matches nothing. `RelaisCertMintTest` asserts the encoded lengths so
   * that stays true.
   */
  private fun nameConstraints(): NameConstraints {
    val permitted =
      listOf(
        // RFC1918 private ranges — the ordinary home/office LAN.
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        // RFC6598 CGNAT: where Tailscale and similar overlays put their addresses.
        "100.64.0.0/10",
        // Loopback, so `adb forward` + https://localhost:8443 still verifies.
        "127.0.0.0/8",
        // IPv4 link-local (APIPA / some tethering fallbacks).
        "169.254.0.0/16",
        // IPv6 loopback, unique-local, and global unicast (a phone with native IPv6 holds one).
        "::1/128",
        "fc00::/7",
        "2000::/3",
      )
        .map { GeneralSubtree(GeneralName(GeneralName.iPAddress, it)) } +
        // The only two DNS subtrees the node ever answers to. This is the constraint that stops a
        // system-store-installed CA from being usable against the rest of the internet.
        listOf("localhost", "local").map {
          GeneralSubtree(GeneralName(GeneralName.dNSName, it))
        }
    return NameConstraints(permitted.toTypedArray(), null)
  }

  /** A positive, unpredictable 64-bit serial. Sequential serials leak how many certs a node has minted. */
  private fun serial(): BigInteger = BigInteger(64, SecureRandom()).max(BigInteger.ONE)
}

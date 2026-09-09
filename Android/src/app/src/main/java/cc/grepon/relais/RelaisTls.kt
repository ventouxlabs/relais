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

import android.content.Context
import android.util.Log
import java.io.File
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x509.GeneralName

/**
 * The Android shim over [RelaisCertMint]: keystore files, passwords, logging, and the TLS server
 * socket (extracted from `RelaisHttpServer` — #173; rebuilt onto a per-node CA for feature-18).
 *
 * **What changed and why.** The node used to serve a single self-signed certificate with a
 * `CN=relais-node` subject and **no `subjectAltName` at all**, which made verification not merely
 * inconvenient but impossible: a modern TLS client ignores CN entirely, so `--cacert` failed even
 * when the certificate was trusted, and every quickstart in the repo ended in `curl -k`. Now the
 * node mints a per-node CA once and a short-lived leaf under it that carries every live address as
 * a SAN. A client that imports the CA once keeps verifying across DHCP churn, because only the leaf
 * is re-minted.
 *
 * **Two keystore files, deliberately.** `relais_ca.p12` holds the CA; `relais_tls.p12` holds the
 * leaf key and the `[leaf, ca]` chain. Only the latter is ever handed to a [KeyManagerFactory] —
 * a CA key entry in the same store would give the key manager two entries to choose between, and
 * the CA private key has no business being reachable from the handshake path at all.
 *
 * **The leaf key pair is generated exactly once and reused across every re-mint.** Only the
 * certificate changes. This is a contract, not an optimization: it is what keeps
 * `curl --pinnedpubkey` valid after the phone's address changes, and feature-23 pins that same
 * leaf SPKI. `RelaisCertReissueTest` asserts it.
 *
 * Migration from the pre-feature single-certificate keystore is safe precisely because **nobody was
 * trusting the old certificate** — every documented client passed `-k`. There is no established
 * trust to break.
 */
internal object RelaisTls {
  private const val TAG = "RelaisTls"
  private const val TLS_KEY_ALIAS = "relais-tls"
  private const val TLS_KEYSTORE_FILE = "relais_tls.p12"
  private const val CA_KEY_ALIAS = "relais-ca"
  private const val CA_KEYSTORE_FILE = "relais_ca.p12"

  /**
   * Set when an unreadable CA keystore forced a replacement CA to be minted, so the surfaces that
   * show certificate state can say so.
   *
   * Process-lifetime only, deliberately: it exists to explain "why did every client suddenly stop
   * verifying" during the session in which it happened, not to persist a warning forever. Recovery
   * is a re-import, and once the user has done that the message would be actively misleading.
   */
  @Volatile private var caWasReplaced = false

  /**
   * Plain (tls=false) or TLS server socket.
   *
   * A **software** RSA leaf key is used deliberately: AndroidKeyStore keys (RSA and EC) cannot sign
   * the TLS server handshake through conscrypt's native upcall on-device, so the key is generated
   * in software and stored in the app's private files dir. The *CA* key is EC and never signs a
   * handshake, so that limitation does not reach it.
   *
   * Minting happens lazily here rather than at app init: it is hundreds of milliseconds of RSA
   * keygen on a cold start, and the plain (`tls = false`) loopback listener must not pay it — hence
   * the short-circuit on the first line, which predates this feature and must stay.
   */
  fun buildServerSocket(context: Context, tls: Boolean): ServerSocket {
    if (!tls) return ServerSocket()
    val pass = RelaisConfig.tlsKeystorePassword(context).toCharArray()
    val ks = loadOrMint(context, allowMintCa = true, allowMintLeaf = true).keystore
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
    val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    return ctx.serverSocketFactory.createServerSocket()
  }

  /**
   * Load-**or-mint** the node's certificate state. Reachable only from the node start path.
   *
   * Never call this from the UI: it generates key material, so opening a settings screen would
   * silently create a CA for a node the user has never started. The UI calls [certInfoOrNull].
   *
   * Its only callers are the on-device probes (`CertTrustProbe`, `CertReissueProbe`), which need
   * load-or-mint because they run against a node that may never have started. **If you are wiring a
   * new UI or HTTP surface, you want [certInfoOrNull].** `loadOrMint`'s two permissions are
   * required parameters with no defaults precisely so that reaching for the minting path has to be
   * a deliberate act rather than the shape you get by typing nothing.
   */
  fun certInfo(context: Context): RelaisCertInfo =
    loadOrMint(context, allowMintCa = true, allowMintLeaf = true).info

  /**
   * Load-**only**. Returns null when the node has never been started, so no keystore exists yet.
   *
   * This is the reader the CONFIGURE screen calls, and the nullable return is the whole point:
   * opening a settings screen must never mint. It is still filesystem and crypto work, so callers
   * must stay off the main thread.
   */
  fun certInfoOrNull(context: Context): RelaisCertInfo? {
    val caFile = File(context.filesDir, CA_KEYSTORE_FILE)
    val tlsFile = File(context.filesDir, TLS_KEYSTORE_FILE)
    if (!caFile.exists() || !tlsFile.exists()) return null
    return runCatching { loadOrMint(context, allowMintCa = false, allowMintLeaf = false).info }.getOrNull()
  }

  /**
   * Does the current leaf lack any LAN address it could now carry? A pure check, safe to call from
   * a `NetworkCallback` without touching the running listener.
   *
   * True only in the boot race: `BOOT_COMPLETED` starts the service before DHCP completes, so the
   * first mint sees no interfaces and produces a loopback-only certificate. Without a re-mint the
   * node would serve that certificate for its entire uptime and every LAN client would fail
   * hostname verification until a human restarted it — in exactly the unattended-appliance mode the
   * README advertises. [RelaisNodeService] watches for this; see `reissueForLan`.
   */
  fun needsLanReissue(context: Context): Boolean {
    val live = RelaisLanIp.allLanAddresses()
    // Nothing to re-issue *for* yet. The boot race is handled by the caller arming on this same
    // emptiness, not by this predicate.
    if (live.isEmpty()) return false
    val state = runCatching { loadOrMint(context, allowMintCa = false, allowMintLeaf = false) }.getOrNull()
    // Unreadable or not yet written — and "unknown" must answer TRUE, not false. `start()` mints on
    // the accept thread, so a caller running just after it can legitimately find no keystore at
    // all; answering false there would skip the watch on the strength of a race. Over-answering is
    // cheap because the acting caller re-checks this before it rebinds anything.
      ?: return true
    return RelaisCertMint.needsReissue(state.leaf, RelaisCertMint.buildSanList(live), System.currentTimeMillis())
  }

  /**
   * Re-mints the leaf certificate for the addresses live right now, reusing the existing leaf key
   * so the NODE KEY PIN does not move, and rewrites `relais_tls.p12`.
   *
   * This only changes what a **newly constructed** listener will serve. A bound `SSLServerSocket`
   * cannot pick up a new certificate, so the caller must stop and reconstruct the listener —
   * [RelaisNodeService] owns `httpsServer` and does exactly that.
   *
   * **Returns whether anything was actually re-issued, and the caller must honour it.** This used
   * to swallow the failure and return `Unit`, so a caller tore down and rebuilt a listener having
   * changed nothing — pure downside. The realistic failure is not exotic: on a fresh install this
   * runs with `allowMintCa = false` while the accept thread is still generating the CA, so the
   * load throws, and the guard upstream cannot prevent it because `needsLanReissue` deliberately
   * answers "true" when it cannot read the keystore, which is exactly that state.
   */
  fun reissueForLan(context: Context): Boolean =
    runCatching {
        loadOrMint(context, allowMintCa = false, allowMintLeaf = true, forceLeafReissue = true)
      }
      .onFailure { Log.w(TAG, "LAN re-issue failed; keeping the existing certificate", it) }
      .isSuccess

  /** The loaded keystore plus everything the callers above need, so a load happens once per call. */
  private class State(
    val keystore: KeyStore,
    val leaf: X509Certificate,
    val info: RelaisCertInfo,
  )

  /**
   * The single load/mint path. Reads the CA and leaf keystores, mints whatever is missing, and
   * re-mints the leaf when [RelaisCertMint.needsReissue] says the address set moved or expiry is
   * near.
   *
   * **[allowMintCa] and [allowMintLeaf] are two separate permissions on purpose.** They were one
   * flag at first, and that was a bug: a read-only caller could be refused a CA and still fall
   * through into re-minting the *leaf* and rewriting the keystore. `GET /` and `/v1/clientconfig`
   * both read this on every request, so a node whose address had changed would have re-minted its
   * certificate on every page load. Read-only means read-only, for both.
   *
   * @param allowMintCa may create the CA. False for every read path.
   * @param allowMintLeaf may create or re-mint the leaf **and write the keystore**. False for every
   *   read path; true for the node start path and for the boot-race re-issue.
   * @param forceLeafReissue re-mint even if [RelaisCertMint.needsReissue] says otherwise, because
   *   the caller has already decided (the boot race). Requires [allowMintLeaf].
   */
  private fun loadOrMint(
    context: Context,
    allowMintCa: Boolean,
    allowMintLeaf: Boolean,
    forceLeafReissue: Boolean = false,
  ): State {
    val caPass = RelaisConfig.caKeystorePassword(context).toCharArray()
    val tlsPass = RelaisConfig.tlsKeystorePassword(context).toCharArray()
    val caFile = File(context.filesDir, CA_KEYSTORE_FILE)
    val tlsFile = File(context.filesDir, TLS_KEYSTORE_FILE)

    val (caKey, caCert) = loadOrMintCa(caFile, caPass, allowMintCa)
    val leafKey = loadLeafKeyPair(tlsFile, tlsPass)
    val liveSans = RelaisCertMint.buildSanList(RelaisLanIp.allLanAddresses())

    // A leaf counts as reusable only if the CURRENT CA actually signed it. If `relais_ca.p12` was
    // deleted or corrupted, loadOrMintCa quietly mints a replacement — and the leaf on disk is then
    // signed by a CA that no longer exists. The SAN set and the expiry are both unchanged in that
    // case, so the re-issue predicate would happily keep it, and the node would serve a leaf
    // chained to a CA that cannot have signed it: a chain no client can validate, produced by a
    // path whose whole job is recovering cleanly.
    val existingLeaf =
      if (leafKey == null) null
      else loadLeaf(tlsFile, tlsPass)?.takeIf { leaf ->
        runCatching { leaf.verify(caCert.publicKey) }.isSuccess
      }
    val reissue =
      allowMintLeaf &&
        (forceLeafReissue ||
          existingLeaf == null ||
          RelaisCertMint.needsReissue(existingLeaf, liveSans, System.currentTimeMillis()))

    // A read-only caller with nothing usable on disk has nothing to report. Throwing rather than
    // fabricating a State is what makes certInfoOrNull return null instead of a half-answer.
    // Spelled with `allowMintLeaf` rather than `reissue` so it states its own condition: a minting
    // caller always proceeds (it will create what is missing), a read-only one needs a leaf to
    // already exist. "No usable leaf" includes a pre-feature 1-element chain, which loadLeaf
    // deliberately reports as absent.
    check(allowMintLeaf || existingLeaf != null) { "no usable leaf certificate and minting is disabled" }

    val pair = leafKey ?: RelaisCertMint.generateLeafKeyPair()
    val leaf =
      if (!reissue && existingLeaf != null) {
        existingLeaf
      } else {
        RelaisCertMint.mintLeaf(caKey, caCert, pair.public, liveSans).also {
          Log.i(
            TAG,
            "Minted leaf certificate: ${liveSans.size} SANs, CA ${RelaisCertFingerprint.spkiSha256Base64(caCert.publicKey)}",
          )
        }
      }

    val ks = KeyStore.getInstance("PKCS12").apply { load(null, tlsPass) }
    // [leaf, ca] in that order — a bare [leaf] leaves clients unable to build the path.
    ks.setKeyEntry(TLS_KEY_ALIAS, pair.private, tlsPass, arrayOf<java.security.cert.Certificate>(leaf, caCert))
    if (reissue) tlsFile.outputStream().use { ks.store(it, tlsPass) }

    return State(ks, leaf, buildInfo(caCert, leaf, liveSans))
  }

  /**
   * Loads the CA, minting it on first use. A **corrupt or unreadable** keystore re-mints rather
   * than throwing: a listener that will not start at all is a worse outcome than a CA the user has
   * to re-import, and the old CA was unreadable to us so it was equally unusable to them.
   */
  private fun loadOrMintCa(
    file: File,
    pass: CharArray,
    allowMint: Boolean,
  ): Pair<PrivateKey, X509Certificate> {
    if (file.exists()) {
      val loaded =
        runCatching {
          val ks = KeyStore.getInstance("PKCS12")
          file.inputStream().use { ks.load(it, pass) }
          val key = ks.getKey(CA_KEY_ALIAS, pass) as PrivateKey
          val cert = ks.getCertificate(CA_KEY_ALIAS) as X509Certificate
          key to cert
        }
      loaded.getOrNull()?.let { return it }
      // The single most consequential event this class can produce: a new CA invalidates EVERY
      // client's imported `relais-ca.crt` at once, and every one of them starts failing
      // verification with no indication of why. It must not be a `Log.w` nobody reads — the flag
      // is surfaced on `GET /` (and, once feature-18 PR B lands the CONFIGURE section, in the UI),
      // so a user who suddenly cannot connect has somewhere to find the reason.
      Log.e(TAG, "CA keystore unreadable; minting a REPLACEMENT CA — every client must re-import", loaded.exceptionOrNull())
      caWasReplaced = true
    }
    check(allowMint) { "no CA keystore and minting is disabled" }
    val minted = RelaisCertMint.mintCa()
    val ks = KeyStore.getInstance("PKCS12").apply { load(null, pass) }
    ks.setKeyEntry(
      CA_KEY_ALIAS,
      minted.keyPair.private,
      pass,
      arrayOf<java.security.cert.Certificate>(minted.certificate),
    )
    file.outputStream().use { ks.store(it, pass) }
    Log.i(
      TAG,
      "Minted per-node CA ${RelaisCertFingerprint.spkiSha256Base64(minted.keyPair.public)} at ${file.path}",
    )
    return minted.keyPair.private to minted.certificate
  }

  /**
   * The existing leaf key pair, or null if there is no usable keystore.
   *
   * **Upgrade path.** A pre-feature `relais_tls.p12` holds an RSA key and a lone self-signed
   * certificate with no CA in the chain. The key is still a perfectly good leaf key and is reused,
   * so the pin an early adopter may have recorded survives; only the certificate is replaced. If it
   * cannot be read, a fresh key is generated.
   */
  private fun loadLeafKeyPair(file: File, pass: CharArray): KeyPair? {
    // Absent is the ONLY case that may return null. Everything else throws.
    //
    // This used to be `runCatching { ... }.getOrNull()`, which turned *every* failure — a truncated
    // read, a password mismatch, a transient IO error — into "generate a fresh key", which was then
    // minted and written over the old one. That silently changes the NODE KEY PIN, and the leaf key
    // pair surviving every re-mint is a **contract**: feature-23 pins that SPKI, and a user who put
    // `curl --pinnedpubkey` in a script gets an opaque failure with no log line and nothing in the
    // UI to explain it. Failing loudly here is strictly better than rotating quietly: a listener
    // that will not start is visible and recoverable, a rotated pin is neither.
    if (!file.exists()) return null
    val ks = KeyStore.getInstance("PKCS12")
    file.inputStream().use { ks.load(it, pass) }
    val key = ks.getKey(TLS_KEY_ALIAS, pass) as PrivateKey
    val cert = ks.getCertificate(TLS_KEY_ALIAS) as X509Certificate
    return KeyPair(cert.publicKey, key)
  }

  private fun loadLeaf(file: File, pass: CharArray): X509Certificate? =
    runCatching {
      val ks = KeyStore.getInstance("PKCS12")
      file.inputStream().use { ks.load(it, pass) }
      // A pre-feature keystore has a one-element chain: treat it as "no CA-issued leaf" so the
      // re-issue path replaces it.
      val chain = ks.getCertificateChain(TLS_KEY_ALIAS)
      if (chain == null || chain.size < 2) null else chain[0] as X509Certificate
    }.getOrNull()

  private fun buildInfo(
    caCert: X509Certificate,
    leaf: X509Certificate,
    sans: List<GeneralName>,
  ): RelaisCertInfo =
    RelaisCertInfo(
      caFingerprint = RelaisCertFingerprint.spkiSha256Base64(caCert.publicKey),
      nodeKeyPin = RelaisCertFingerprint.spkiSha256Base64(leaf.publicKey),
      sanList = sans.map { RelaisCertPem.renderSan(it) },
      leafNotAfter = leaf.notAfter.time,
      caPem = RelaisCertPem.toPem(caCert),
      caWasReplaced = caWasReplaced,
    )

  /** Base64 line width for PEM, fixed by RFC 7468. */
  private const val PEM_LINE = 64

  /**
   * PEM encoding and SAN rendering. Kept here rather than in [RelaisCertMint] so the minter stays a
   * pure certificate factory, and because the PEM is only ever needed alongside a keystore read.
   */
  internal object RelaisCertPem {

    /**
     * The certificate as PEM, wrapped at 64 characters with a trailing newline. Unwrapped base64 is
     * rejected by some tools that will happily accept anything else, so the wrapping is not
     * cosmetic.
     */
    fun toPem(cert: X509Certificate): String {
      val b64 = Base64.getEncoder().encodeToString(cert.encoded)
      val body = b64.chunked(PEM_LINE).joinToString("\n")
      return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }

    /** A SAN entry as the literal a user would recognise, for the UI and the served status page. */
    fun renderSan(gn: GeneralName): String =
      when (gn.tagNo) {
        GeneralName.iPAddress ->
          runCatching {
            java.net.InetAddress.getByAddress(
                org.bouncycastle.asn1.ASN1OctetString.getInstance(gn.name).octets
              )
              .hostAddress ?: ""
          }
            .getOrDefault("")
        else -> gn.name.toString()
      }
  }
}

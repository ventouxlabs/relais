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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
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
 * a SAN. A client imports the CA once and does not have to re-import when the leaf is re-issued,
 * because only the leaf changes.
 *
 * **Re-issue happens at node start — not on a live address change.** A node that moves network mid-session keeps serving a certificate
 * that no longer covers its address until it restarts. Do not describe this as surviving DHCP
 * churn: that claim was made, shipped into client-facing copy, and had to be retracted after
 * hardware showed the stale-leaf case.
 *
 * **Two keystore files, deliberately.** `relais_ca.p12` holds the CA; `relais_tls.p12` holds the
 * leaf key and the `[leaf, ca]` chain. Only the latter is ever handed to a [KeyManagerFactory] —
 * a CA key entry in the same store would give the key manager two entries to choose between, and
 * the CA private key has no business being reachable from the handshake path at all.
 *
 * **The leaf key pair is generated exactly once and reused across every re-mint.** Only the
 * certificate changes. This is a contract, not an optimization: it is what keeps
 * `curl --pinnedpubkey` valid *across a re-issue*, and feature-23 pins that same leaf SPKI.
 * `RelaisCertReissueTest` asserts it. (Note the scope, per the paragraph above: a re-issue is not
 * the same thing as an address change, which only takes effect at the next start.)
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
   * Set when a replacement CA had to be minted, so the surfaces that show certificate state can
   * say so.
   *
   * **Two routes reach it, and they must both set it** — the key material was provably
   * unrecoverable, or the keystore was gone while the leaf survived
   * ([mintWouldReplaceExistingIdentity]). They are indistinguishable to a client: an imported
   * `relais-ca.crt` stops verifying either way. Set only on a path that actually mints, never on a
   * read-only one.
   *
   * Process-lifetime only, deliberately: it exists to explain "why did every client suddenly stop
   * verifying" during the session in which it happened, not to persist a warning forever. Recovery
   * is a re-import, and once the user has done that the message would be actively misleading.
   */
  @Volatile private var caWasReplaced = false

  /**
   * Set when a **new leaf key** had to be minted, so the NODE KEY PIN moved. Surfaced for the same
   * reason as [caWasReplaced]: a `--pinnedpubkey` client fails with an error naming nothing, and
   * the user needs somewhere to find out why.
   *
   * Same two routes as [caWasReplaced] — password provably gone, or the keystore missing while the
   * CA survived — and the same rule: mint paths only.
   */
  @Volatile private var leafKeyWasReplaced = false

  /**
   * Is [e] proof that the stored key material can **never** be read again, as opposed to a failure
   * that might not recur?
   *
   * This is the companion to the rule that a read failure must never silently rotate key material.
   * That rule was right and is kept — a silent rotation moves the pin invisibly, which is worse
   * than a visible failure — but on its own it conflated two situations needing opposite handling:
   *
   *  - **Transient or ambiguous** (IO error, partial read, unknown cause): retrying may work, so
   *    rotating would destroy a recoverable identity. Must fail loudly and change nothing.
   *  - **Provably unrecoverable** (the password is gone, so nothing can ever decrypt this file):
   *    retrying is pointless *by construction*, and refusing to re-mint turns a recoverable
   *    situation into a permanently unstartable node.
   *
   * `UnrecoverableKeyException` — thrown directly by `getKey`, or wrapped in the `IOException` that
   * `KeyStore.load` raises for a wrong password — is the provable case. It happens when
   * `EncryptedSharedPreferences` is reset after AndroidKeyStore invalidation, which lockscreen and
   * biometric enrolment changes and some backup/restore paths cause on real devices. A corrupt file
   * throws `IOException` with a *different* cause and is deliberately NOT matched here: corruption
   * is not proof that the password is gone.
   */
  internal fun isKeyMaterialUnrecoverable(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.any { it is UnrecoverableKeyException }

  /**
   * Does minting this component now **replace an identity clients may already hold**, rather than
   * create one for the first time?
   *
   * **A missing file is not proof of a first install.** The CA and the leaf are two halves of one
   * identity, so if one is gone and the other survives, this is a node that already had an identity
   * and lost half of it — and whatever gets minted here breaks clients exactly as a rotation does.
   * The user-visible outcome is identical to the unrecoverable-password case: `--cacert` clients
   * stop verifying, or `--pinnedpubkey` clients stop matching. What differs is only the route taken
   * to get there, which is no reason to explain one and stay silent about the other.
   *
   * The sibling's presence is the evidence, and it is the best available: nothing else on disk
   * distinguishes "fresh install" from "half the identity was deleted". Both files absent is a
   * genuine first mint and must stay silent, or every new node would warn about a rotation that
   * never happened.
   */
  internal fun mintWouldReplaceExistingIdentity(componentExists: Boolean, siblingExists: Boolean): Boolean =
    !componentExists && siblingExists

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
   * Load-**only**. Returns null when there is nothing readable to report.
   *
   * This is the reader the CONFIGURE screen calls, and the nullable return is the whole point:
   * opening a settings screen must never mint. It is still filesystem and crypto work, so callers
   * must stay off the main thread.
   *
   * **Null is not proof that the node has never started.** It also covers an unreadable keystore,
   * because swallowing here is safe for the one property that matters — both permissions are off,
   * so no path below can rotate or mint key material — but it is *not* safe to render as "no
   * certificate yet". A UI that says that when the truth is "your key material is gone" sends the
   * user looking for a START button instead of the replacement warning on `GET /`. Distinguish the
   * two cases before building a message on this, rather than treating null as first-run.
   */
  fun certInfoOrNull(context: Context): RelaisCertInfo? {
    val caFile = File(context.filesDir, CA_KEYSTORE_FILE)
    val tlsFile = File(context.filesDir, TLS_KEYSTORE_FILE)
    if (!caFile.exists() || !tlsFile.exists()) return null
    return runCatching { loadOrMint(context, allowMintCa = false, allowMintLeaf = false).info }.getOrNull()
  }

  /**
   * Writes [ks] to [target] via a temp file and an **atomic** rename, so [target] is never observed
   * half-written.
   *
   * `File.outputStream()` truncates before `store` fills it, leaving a window in which a power loss
   * or write error yields an unreadable keystore. **That pattern predates this branch** — it is
   * verbatim from the pre-feature `loadOrCreateKeystore` — but this branch made its consequence far
   * worse, and the interaction is the reason to fix the write rather than relax the read:
   *
   *  - [loadLeafKeyPair] now throws on anything except "file absent", deliberately, so a transient
   *    read failure can never silently regenerate the leaf key and move the NODE KEY PIN.
   *  - With a non-atomic write, a truncated keystore is therefore **fatal at next start** instead of
   *    being silently replaced.
   *
   * Two individually correct decisions composing into "the node cannot start without discarding its
   * pinned identity". Keeping the strict read and making the write leave no state that can trigger
   * it is the combination that satisfies both.
   *
   * The temp file is a sibling so the rename stays within one filesystem, which is what makes
   * `ATOMIC_MOVE` possible; it is deleted on any failure so a crashed write leaves no litter for the
   * next one to trip over.
   */
  private fun storeAtomically(ks: KeyStore, target: File, pass: CharArray) {
    val tmp = File(target.parentFile, "${target.name}.tmp")
    try {
      tmp.outputStream().use { ks.store(it, pass) }
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
      runCatching { tmp.delete() }
      throw e
    }
  }

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
   * **`@Synchronized` makes the whole load → mint → store sequence one transaction, and that is
   * the point rather than a precaution.** This has two genuinely concurrent callers that share no
   * other lock: the accept thread inside `buildServerSocket`, and the LAN re-issue thread. When
   * DHCP lands during the initial HTTPS startup, both could observe an absent TLS keystore and
   * each mint a *different* leaf key pair — one of which then gets served while the other is
   * what the store, and therefore the reported NODE KEY PIN, contains. Or neither, and a half
   * written PKCS12.
   *
   * The invariant at stake is the same one [loadLeafKeyPair] refuses to break quietly: the leaf key
   * is generated once and reused forever, because feature-23 pins its SPKI.
   *
   * @param allowMintCa may create the CA. False for every read path.
   * @param allowMintLeaf may create or re-mint the leaf **and write the keystore**. False for every
   *   read path; true for the node start path.
   */
  @Synchronized
  private fun loadOrMint(
    context: Context,
    allowMintCa: Boolean,
    allowMintLeaf: Boolean,
  ): State {
    val caPass = RelaisConfig.caKeystorePassword(context).toCharArray()
    val tlsPass = RelaisConfig.tlsKeystorePassword(context).toCharArray()
    val caFile = File(context.filesDir, CA_KEYSTORE_FILE)
    val tlsFile = File(context.filesDir, TLS_KEYSTORE_FILE)

    // Snapshot BOTH before either loader runs. loadOrMintCa writes `caFile` on the mint path, so
    // reading `caFile.exists()` afterwards to decide about the leaf would see the file this call
    // just created and conclude "the CA survived" — the evidence has to be captured while it still
    // describes the state the node started in.
    val caExisted = caFile.exists()
    val tlsExisted = tlsFile.exists()

    val (caKey, caCert) = loadOrMintCa(caFile, caPass, allowMintCa, siblingExists = tlsExisted)
    val leafKey = loadLeafKeyPair(tlsFile, tlsPass, allowMint = allowMintLeaf, siblingExists = caExisted)
    val liveSans = RelaisCertMint.buildSanList(RelaisLanIp.allLanAddresses())

    // A leaf counts as reusable only if the CURRENT CA actually signed it. If `relais_ca.p12` was
    // deleted, or its key material is unrecoverable, loadOrMintCa mints a replacement — and the
    // leaf on disk is then
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
        (existingLeaf == null ||
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
    if (reissue) storeAtomically(ks, tlsFile, tlsPass)

    return State(ks, leaf, buildInfo(caCert, leaf))
  }

  /**
   * Loads the CA, minting it on first use, and re-minting **only** when the existing key material
   * is provably unrecoverable ([isKeyMaterialUnrecoverable]).
   *
   * Replacing the CA invalidates every client's import at once, so the bar for doing it is proof
   * that the old one is already unusable to them — not merely that this read failed. A transient
   * IO error propagates and is retried on the next start; that is the whole point of the
   * distinction, and this doc previously described the opposite rule.
   */
  private fun loadOrMintCa(
    file: File,
    pass: CharArray,
    allowMint: Boolean,
    siblingExists: Boolean,
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
      // Same rule as the leaf, and it had the OPPOSITE defect: this rotated on *any* failure, so a
      // transient IO error would replace a perfectly good CA and invalidate every client's import.
      // Only provable unrecoverability may rotate; everything else propagates and is retried.
      val cause = loaded.exceptionOrNull()
      if (cause != null && !isKeyMaterialUnrecoverable(cause)) throw cause
      // The check precedes the flag: a read-only caller changes nothing, so announcing a
      // replacement it is not about to perform would be a false warning that outlives the call.
      check(allowMint) { "CA keystore is unreadable and minting is disabled" }
      // The single most consequential event this class can produce: a new CA invalidates EVERY
      // client's imported `relais-ca.crt` at once, and every one of them starts failing
      // verification with no indication of why. It must not be a `Log.w` nobody reads — the flag
      // is surfaced on `GET /` (and, once feature-18 PR B lands the CONFIGURE section, in the UI),
      // so a user who suddenly cannot connect has somewhere to find the reason.
      Log.e(TAG, "CA keystore password is gone; minting a REPLACEMENT CA — every client must re-import", cause)
      caWasReplaced = true
    } else {
      check(allowMint) { "no CA keystore and minting is disabled" }
      // A DELETED CA reaches the same outcome as an unreadable one — every client's import stops
      // verifying — so it must produce the same warning. Without this, the two routes to an
      // identical breakage were explained and silent respectively.
      if (mintWouldReplaceExistingIdentity(componentExists = false, siblingExists = siblingExists)) {
        Log.e(
          TAG,
          "CA keystore is MISSING while leaf state survives; minting a REPLACEMENT CA — every client must re-import",
        )
        caWasReplaced = true
      }
    }
    val minted = RelaisCertMint.mintCa()
    val ks = KeyStore.getInstance("PKCS12").apply { load(null, pass) }
    ks.setKeyEntry(
      CA_KEY_ALIAS,
      minted.keyPair.private,
      pass,
      arrayOf<java.security.cert.Certificate>(minted.certificate),
    )
    storeAtomically(ks, file, pass)
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
  private fun loadLeafKeyPair(
    file: File,
    pass: CharArray,
    allowMint: Boolean,
    siblingExists: Boolean,
  ): KeyPair? {
    // Null means "there is no key to reuse" — either the file is absent, or its password is
    // provably gone. Every OTHER failure throws.
    //
    // The throwing half is the important half: this was once `runCatching { }.getOrNull()`, which
    // turned a truncated read or a transient IO error into "generate a fresh key", silently moving
    // the NODE KEY PIN. The leaf key surviving every re-mint is a **contract** — feature-23 pins
    // that SPKI, and a user with `curl --pinnedpubkey` in a script would get an opaque failure with
    // nothing to explain it.
    //
    // The null half exists because that rule, alone, bricked the node. If
    // `EncryptedSharedPreferences` is reset by AndroidKeyStore invalidation the password is gone
    // while the file remains, so the load throws, startup aborts, and every retry reads the same
    // undecryptable file — forever, with no in-app way out. Retrying is pointless *by
    // construction* there, so re-minting is the only path forward and refusing it converts a
    // recoverable state into a permanent one.
    if (!file.exists()) {
      // A DELETED leaf keystore moves the NODE KEY PIN exactly as a lost password does: the key is
      // regenerated below and every `--pinnedpubkey` client stops matching. Warning on one route
      // and not the other made the quieter route the more damaging one.
      if (allowMint && mintWouldReplaceExistingIdentity(componentExists = false, siblingExists = siblingExists)) {
        Log.e(
          TAG,
          "Leaf keystore is MISSING while the CA survives; minting a NEW leaf key — the NODE KEY " +
            "PIN changes and every --pinnedpubkey client must re-pin. The CA is unchanged.",
        )
        leafKeyWasReplaced = true
      }
      return null
    }
    return try {
      val ks = KeyStore.getInstance("PKCS12")
      file.inputStream().use { ks.load(it, pass) }
      val key = ks.getKey(TLS_KEY_ALIAS, pass) as PrivateKey
      val cert = ks.getCertificate(TLS_KEY_ALIAS) as X509Certificate
      KeyPair(cert.publicKey, key)
    } catch (e: Exception) {
      if (!isKeyMaterialUnrecoverable(e)) throw e
      // Gated on allowMint for the same reason as the CA and the missing-file branch above: a
      // read-only caller mints nothing, so it must not announce a pin change that has not happened.
      if (allowMint) {
        Log.e(
          TAG,
          "Leaf keystore password is gone (AndroidKeyStore reset?); the old key cannot be recovered. " +
            "Minting a NEW leaf key — the NODE KEY PIN changes and every --pinnedpubkey client must re-pin.",
          e,
        )
        leafKeyWasReplaced = true
      }
      null
    }
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

  /**
   * The snapshot every display surface reads — built **entirely from the certificate that is
   * actually being served**, never from what the node currently wishes it had.
   *
   * The SAN list used to be the freshly enumerated `liveSans`, which is right only when the leaf
   * was just re-minted from them. For a read-only caller on an already-running node whose address
   * changed mid-session, the leaf on disk is unchanged while `liveSans` is not — so the panel
   * claimed coverage of an address the served certificate does not carry, and hostname
   * verification would fail against exactly the address the node was advertising as covered.
   *
   * That is the mid-session DHCP scenario this feature does NOT handle (re-issue happens at node
   * start, plus the one boot-time shot), and reporting it from `liveSans` would have hidden the
   * gap from the person looking straight at it.
   */
  private fun buildInfo(caCert: X509Certificate, leaf: X509Certificate): RelaisCertInfo =
    RelaisCertInfo(
      caFingerprint = RelaisCertFingerprint.spkiSha256Base64(caCert.publicKey),
      nodeKeyPin = RelaisCertFingerprint.spkiSha256Base64(leaf.publicKey),
      sanList = RelaisCertPem.displayStrings(leaf),
      leafNotAfter = leaf.notAfter.time,
      caPem = RelaisCertPem.toPem(caCert),
      caWasReplaced = caWasReplaced,
      leafKeyWasReplaced = leafKeyWasReplaced,
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

    /**
     * The SAN literals **the given certificate actually carries**, for a human to read.
     *
     * **Display only — never compare these.** The JDK's IPv6 rendering here is provider-dependent
     * (`::1` under one configuration, `0:0:0:0:0:0:0:1` under another), so two runs can disagree
     * about strings describing an identical certificate. Any comparison of SAN sets must key on
     * bytes — see [RelaisCertMint.needsReissue], which compares DER for exactly this reason.
     *
     * Deliberately sourced from the certificate rather than from the address list that was used to
     * mint it: those two agree only immediately after a re-mint, and the case where they disagree
     * is precisely the one a user needs to see (a mid-session address change the node has not
     * re-issued for). Java normalises IPv6 on the way out — `::1` reads back as
     * `0:0:0:0:0:0:0:1` — which is honest about what a verifier will match on.
     *
     * Empty on a malformed or absent extension rather than throwing: this feeds display surfaces,
     * and a status page that fails to render tells the user less than one showing no SANs.
     */
    fun displayStrings(cert: X509Certificate): List<String> =
      runCatching {
        cert.subjectAlternativeNames.orEmpty().mapNotNull { entry ->
          (entry.getOrNull(1) as? String)?.takeIf { it.isNotBlank() }
        }
      }
        .getOrDefault(emptyList())

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

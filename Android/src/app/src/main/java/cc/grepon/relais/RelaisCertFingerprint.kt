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

import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Certificate and public-key fingerprint formatters for the trusted-LAN cert (feature-18 T3).
 *
 * Kept in its own file, separate from [RelaisCertMint], so the UI and the dashboard renderer can
 * format a fingerprint without pulling BouncyCastle's minting machinery into their dependency
 * graph. No `android.` imports, so the JVM test lane reaches all of it.
 *
 * **The node has two different fingerprints and they are not interchangeable.** Confusing them is
 * the single most likely support question this feature creates, so every surface labels them:
 *
 *  - **CA FINGERPRINT** — [spkiSha256Base64] of the *CA* public key. This is the out-of-band value
 *    a user checks after fetching `/ca.crt`, to prove the file they downloaded is the node's real
 *    CA and not an interceptor's.
 *  - **NODE KEY PIN** — [curlPin] of the *leaf* public key. This is what
 *    `curl --pinnedpubkey <value>` wants, already carrying curl's `sha256//` prefix so there is
 *    nothing to prepend; curl pins the end-entity key, never the issuer's.
 *
 * Pasting the CA value into `--pinnedpubkey` fails with an opaque error, which is why
 * `RelaisCertFingerprintTest` asserts the two values differ for a real minted pair.
 */
internal object RelaisCertFingerprint {

  /**
   * `sha256/<base64>` over the DER `SubjectPublicKeyInfo` of [key] — the **display** spelling, for
   * the CA fingerprint a user compares by eye against the `openssl` one-liner in `SECURITY.md`.
   *
   * **Not a `--pinnedpubkey` argument.** Use [curlPin] for that; the single slash is a filename to
   * curl. This KDoc once claimed the value "can be pasted straight into it" and then noted, in the
   * same sentence's parenthesis, that curl spells the separator with a second slash — the fact that
   * falsified the claim was sitting inside the claim.
   *
   * `key.encoded` **is** the `SubjectPublicKeyInfo` structure, algorithm identifier included — not
   * the bare RSA modulus or the raw EC point. Hashing anything narrower produces a value that looks
   * plausible and matches nothing.
   */
  fun spkiSha256Base64(key: PublicKey): String =
    "sha256/" + Base64.getEncoder().encodeToString(sha256(key.encoded))

  /**
   * The complete `curl --pinnedpubkey` argument for [key]: `sha256//<base64>`, **two slashes**.
   *
   * Not a cosmetic difference from [spkiSha256Base64]. curl's grammar is "a path to a public-key
   * file, **or** hashes preceded by `sha256//`" — so a single-slash value is not rejected as
   * malformed, it is taken as a **filename**. The file does not exist, the pin never matches, and
   * the connection fails with:
   *
   *     curl: (90) SSL: public key does not match pinned public key
   *
   * which is **the same message a genuine key mismatch produces**. That is what makes the wrong
   * spelling dangerous rather than merely broken: a user pasting the published pin reads that error
   * as "this node's key changed", and goes looking for the moved-pin explanation on `GET /` for a
   * reason that is not true.
   *
   * The value is the *whole* argument — nothing to prepend. `SECURITY.md` used to say to write
   * `sha256//<value>` while the value already began `sha256/`, which composes to
   * `sha256//sha256/…`; both halves of that instruction were individually defensible and together
   * produced nonsense.
   */
  fun curlPin(key: PublicKey): String =
    "sha256//" + Base64.getEncoder().encodeToString(sha256(key.encoded))

  /**
   * Colon-separated uppercase hex of the SHA-256 over the certificate's own DER — the form
   * `openssl x509 -fingerprint -sha256` prints, for users cross-checking against openssl output.
   *
   * Distinct from [spkiSha256Base64] over the same certificate's key: this digests the whole signed
   * certificate, so it changes on every re-mint, while the SPKI hash survives one. Only the SPKI
   * hash is usable as a pin.
   */
  fun certSha256Hex(cert: X509Certificate): String =
    sha256(cert.encoded).joinToString(":") { "%02X".format(it) }

  private fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)
}

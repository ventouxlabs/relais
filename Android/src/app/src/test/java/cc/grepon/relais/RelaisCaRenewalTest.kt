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
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RelaisCertMint.needsCaRenewal] — the CA's missing lifecycle (feature-18 codex round 15).
 *
 * The CA was given a birth and no renewal. On a successful load it was returned regardless of
 * `notAfter`, so once it expired every leaf re-issue would be signed by an **expired trust anchor**
 * and every verifying client would fail TLS permanently, with no path back. The leaf has had expiry
 * handling since T3 via [RelaisCertMint.needsReissue]; the CA had none.
 *
 * **Ten years is far away; a wrong clock is not.** The reachable route here is not the calendar, it
 * is a device whose RTC jumps — which Android devices do, and which a reboot does not clear.
 *
 * That cuts both ways, and the two directions are deliberately NOT symmetric:
 *
 *  - **Backwards** past the CA's own `notBefore` is *provably* a bad clock: nothing can legitimately
 *    observe a certificate before it was minted. Renewal must not fire, or a node with a flat RTC
 *    would rotate its CA on every boot and invalidate every client's import each time. This mirrors
 *    [RelaisTls.isKeyMaterialUnrecoverable] — act only on proof.
 *  - **Forwards** cannot be disproved on-device; there is no upper bound to check a clock against.
 *    So the clock is taken at its word there, and that is a deliberate choice rather than an
 *    oversight: NOT rotating an expired CA is a permanent failure, while rotating on a wrong clock
 *    is recoverable — the user re-imports, and `caWasReplaced` tells them to. Given one
 *    unrecoverable option and one recoverable one, this fails toward the recoverable.
 */
class RelaisCaRenewalTest {

  @Test
  fun `a fresh CA does not need renewal`() {
    val ca = RelaisCertMint.mintCa()

    assertFalse(
      "a CA minted seconds ago must not rotate",
      RelaisCertMint.needsCaRenewal(ca.certificate, now()),
    )
  }

  @Test
  fun `a CA inside the renewal window needs renewal`() {
    val ca = RelaisCertMint.mintCa()
    // One day before expiry: inside any sane window, and the case that must fire BEFORE the CA is
    // actually expired — renewing only after expiry would mean the node is already broken when it
    // notices.
    val justBeforeExpiry = ca.certificate.notAfter.time - DAY

    assertTrue(RelaisCertMint.needsCaRenewal(ca.certificate, justBeforeExpiry))
  }

  @Test
  fun `an already expired CA needs renewal`() {
    val ca = RelaisCertMint.mintCa()

    assertTrue(
      "an expired trust anchor signs leaves nothing will verify",
      RelaisCertMint.needsCaRenewal(ca.certificate, ca.certificate.notAfter.time + DAY),
    )
  }

  @Test
  fun `a clock before the CA existed never triggers renewal`() {
    val ca = RelaisCertMint.mintCa()

    // A device with a dead RTC reporting 1970. Note what this does and does not prove: with the
    // shipped ten-year CA it passes with the guard REMOVED too, because 1970 is also "far from
    // expiry". It is here for the real-world case, not as the guard's proof — see the next test.
    assertFalse(
      "a clock predating the CA is provably wrong; rotating would invalidate every import",
      RelaisCertMint.needsCaRenewal(ca.certificate, 0L),
    )
  }

  @Test
  fun `a backwards clock does not rotate even when expiry appears near`() {
    // The ONLY shape in which the notBefore guard changes the answer: a certificate whose whole
    // validity is shorter than the renewal window, so "before it existed" and "nearly expired" are
    // true at the same instant. With the shipped 10-year CA and a 30-day window that is arithmetically
    // unreachable, which is exactly why the test has to construct it — the guard is defence against
    // a future validity/window change, and an untested guard is one a refactor deletes as dead.
    val minted = RelaisCertMint.mintCa()
    val issuedAt = System.currentTimeMillis()
    val shortLived = selfSigned(minted.keyPair, notBefore = issuedAt, notAfter = issuedAt + 10 * DAY)

    val beforeItExisted = issuedAt - DAY

    // Without the guard: notAfter - now = 11 days < the 30-day window, so this returns true and a
    // node with a slow clock rotates its CA — invalidating every client's import over a bad RTC.
    assertFalse(
      "a clock predating the certificate must never rotate it",
      RelaisCertMint.needsCaRenewal(shortLived, beforeItExisted),
    )
    // The same certificate at a legitimate instant inside the window still renews, so the guard
    // suppresses only the impossible case and not renewal itself.
    assertTrue(
      "renewal must still fire for a clock the certificate can legitimately observe",
      RelaisCertMint.needsCaRenewal(shortLived, issuedAt + DAY),
    )
  }

  /** A self-signed certificate over [keyPair] with arbitrary validity, for clock cases mintCa cannot make. */
  private fun selfSigned(keyPair: java.security.KeyPair, notBefore: Long, notAfter: Long): java.security.cert.X509Certificate {
    val name = X500Name("CN=Relais Renewal Test CA")
    val builder =
      JcaX509v3CertificateBuilder(
        name,
        BigInteger.valueOf(1),
        Date(notBefore),
        Date(notAfter),
        name,
        keyPair.public,
      )
    val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
    return JcaX509CertificateConverter().getCertificate(builder.build(signer))
  }

  @Test
  fun `a clock just inside the CA's validity does not rotate on the notBefore boundary`() {
    val ca = RelaisCertMint.mintCa()

    // Exactly at notBefore the certificate is valid and nearly ten years from expiry. A guard
    // written as `<=` instead of `<`, or one that mistook notBefore for the renewal reference,
    // would rotate a brand-new CA here.
    assertFalse(RelaisCertMint.needsCaRenewal(ca.certificate, ca.certificate.notBefore.time))
  }

  private fun now(): Long = System.currentTimeMillis()

  private companion object {
    const val DAY = 86_400_000L
  }
}

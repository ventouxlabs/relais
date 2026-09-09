/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe answering **open question 1**: can an AndroidKeyStore-backed EC key act as a
 * BouncyCastle `ContentSigner`, so the CA private key never exists as a file?
 *
 * This probe **does not gate the feature.** The shipped design (decision 1) uses a software CA key,
 * and works. What is being measured here is whether a future hardening pass could move the CA key
 * into hardware — a genuinely better place for the one key whose compromise lets an attacker sign
 * for the node's names.
 *
 * The question exists because `RelaisTls`'s own KDoc records that AndroidKeyStore keys "cannot sign
 * the TLS server handshake through conscrypt's native upcall on-device". That limitation is about
 * the **handshake**; the CA key does occasional JCA signing instead, an entirely different path.
 * Extrapolating the one to the other is exactly the failure shape this repo has recorded before, so
 * it is measured rather than reasoned about.
 *
 * Reports PASS/FAIL to logcat and **does not fail the build either way** — a negative result is a
 * finding, not a regression.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.CaKeystoreProbe \
 *     -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s RelaisCaKeystoreProbe:*
 */
@RunWith(AndroidJUnit4::class)
class CaKeystoreProbe {

  private val args = InstrumentationRegistry.getArguments()

  @Before
  fun setUp() {
    assumeTrue("On-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
  }

  @Test
  fun androidKeyStoreEcKeyAsContentSigner() {
    val alias = "relais-ca-probe"
    val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    runCatching { ks.deleteEntry(alias) } // leave no state behind between runs

    val generated =
      runCatching {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
          .apply {
            initialize(
              KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            )
          }
          .generateKeyPair()
      }
    val keyPair =
      generated.getOrElse {
        Log.i(TAG, "RESULT: FAIL — could not generate an AndroidKeyStore EC key: $it")
        return
      }
    Log.i(TAG, "generated AndroidKeyStore EC key, private key class = ${keyPair.private::class.java.name}")

    val signed =
      runCatching {
        val now = System.currentTimeMillis()
        val name = X500Name("CN=Relais CA Probe")
        val builder =
          JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 60_000),
            Date(now + 86_400_000L),
            name,
            keyPair.public,
          )
        // The question in one line: BC has to route the signing operation through the JCA
        // Signature it can reach, with a key whose material it can never see.
        val signer =
          JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(ANDROID_KEYSTORE)
            .build(keyPair.private as PrivateKey)
        JcaX509CertificateConverter().getCertificate(builder.build(signer))
      }

    signed.fold(
      onSuccess = { cert ->
        // Verify with the PUBLIC key: a signature that does not verify is a fail even if it built.
        val verified = runCatching { cert.verify(keyPair.public) }.isSuccess
        Log.i(
          TAG,
          "RESULT: ${if (verified) "PASS" else "FAIL"} — ContentSigner built; " +
            "self-signature verifies = $verified. If PASS, the CA private key can live in " +
            "hardware and never exist as a file (open question 1).",
        )
      },
      onFailure = {
        // The likeliest negative: BC asks the provider for a Signature it does not expose, or
        // rejects a key whose encoding is null (AndroidKeyStore keys have no extractable material).
        Log.i(TAG, "RESULT: FAIL — JcaContentSignerBuilder rejected the AndroidKeyStore key: $it")
      },
    )

    // Retry once WITHOUT pinning the provider: BC may find a usable Signature via the default
    // provider chain even when the explicit setProvider call fails.
    val unpinned =
      runCatching {
        JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private as PrivateKey)
      }
    Log.i(TAG, "RESULT (no setProvider): ${if (unpinned.isSuccess) "PASS" else "FAIL — ${unpinned.exceptionOrNull()}"}")

    runCatching { ks.deleteEntry(alias) }
  }

  private companion object {
    const val TAG = "RelaisCaKeystoreProbe"
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
  }
}

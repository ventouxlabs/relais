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

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for the **partial-state** axis of key-material recovery (feature-18 codex round
 * 13): half the node's identity is deleted while the other half survives.
 *
 * **Why this cannot be a JVM test.** [RelaisTls.mintWouldReplaceExistingIdentity] is the decision,
 * and `RelaisTlsPartialStateTest` pins it exhaustively — but the *wiring* is what round 13 actually
 * broke: the predicate was absent, so the flags simply never fired on the deleted-file route. That
 * wiring runs through `Context.filesDir`, two PKCS12 files and `EncryptedSharedPreferences`, none of
 * which exist in the device-free lane. A JVM test would assert against a copy of the logic rather
 * than the shipped path, which is the failure `RelaisCertSanTest` already documents for the `isUp`
 * filter.
 *
 * **DESTRUCTIVE — it deletes the node's keystores.** It backs both files up first and restores them
 * in a `finally`, but a crash mid-run leaves the node with a new identity: every client must
 * re-import the CA and re-pin. Run it on the SPARE device, never on a live node. It is gated behind
 * a second flag on top of `RELAIS_PROBE` so a normal probe sweep cannot trigger it by accident.
 *
 * The process-lifetime flags are sticky by design, so the CA case runs first and the leaf case
 * asserts the flag that the CA case does not set.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.CertPartialStateProbe \
 *     -e RELAIS_PROBE 1 -e RELAIS_DESTRUCTIVE 1 \
 *     com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s RelaisCertPartialStateProbe:*
 */
@RunWith(AndroidJUnit4::class)
class CertPartialStateProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()

  private val caFile: File
    get() = File(context.filesDir, "relais_ca.p12")

  private val tlsFile: File
    get() = File(context.filesDir, "relais_tls.p12")

  @Before
  fun setUp() {
    assumeTrue("On-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    assumeTrue(
      "DESTRUCTIVE: deletes this node's keystores. Pass -e RELAIS_DESTRUCTIVE 1 and use the spare device",
      args.getString("RELAIS_DESTRUCTIVE") == "1",
    )
  }

  @Test
  fun aDeletedHalfIsReportedAsAReplacement() {
    val baseline = RelaisTls.certInfo(context)
    Log.i(TAG, "baseline CA=${baseline.caFingerprint} pin=${baseline.nodeKeyPin}")
    assertTrue("both keystores must exist before this probe runs", caFile.exists() && tlsFile.exists())

    val caBackup = caFile.readBytes()
    val tlsBackup = tlsFile.readBytes()

    try {
      // ---- Case 1: CA deleted, leaf survives ----------------------------------------------
      // Before round 13 this took the fresh-install mint path in silence: a brand new CA, every
      // imported relais-ca.crt broken, and nothing on GET / saying why.
      assertTrue("could not delete the CA keystore", caFile.delete())
      val afterCaLoss = RelaisTls.certInfo(context)
      Log.i(TAG, "after CA loss: CA=${afterCaLoss.caFingerprint} replaced=${afterCaLoss.caWasReplaced}")

      assertNotEquals(
        "a deleted CA must actually be replaced, not silently reused",
        baseline.caFingerprint,
        afterCaLoss.caFingerprint,
      )
      assertTrue(
        "the CA changed, so caWasReplaced must be set — this is the round-13 defect",
        afterCaLoss.caWasReplaced,
      )

      // ---- Case 2: leaf deleted, CA survives ----------------------------------------------
      // Restore a coherent identity first, so this case starts from "both present" exactly as a
      // real node would, rather than from the wreckage of case 1.
      caFile.writeBytes(caBackup)
      tlsFile.writeBytes(tlsBackup)
      val restored = RelaisTls.certInfo(context)
      assertEquals("restore failed; case 2 would test nothing", baseline.caFingerprint, restored.caFingerprint)
      assertEquals("restore failed; case 2 would test nothing", baseline.nodeKeyPin, restored.nodeKeyPin)

      assertTrue("could not delete the leaf keystore", tlsFile.delete())
      val afterLeafLoss = RelaisTls.certInfo(context)
      Log.i(TAG, "after leaf loss: pin=${afterLeafLoss.nodeKeyPin} moved=${afterLeafLoss.leafKeyWasReplaced}")

      assertNotEquals(
        "a deleted leaf keystore means a new leaf key, so the pin must move",
        baseline.nodeKeyPin,
        afterLeafLoss.nodeKeyPin,
      )
      assertTrue(
        "the pin moved, so leafKeyWasReplaced must be set — the other half of the round-13 defect",
        afterLeafLoss.leafKeyWasReplaced,
      )
      assertEquals(
        "the CA must NOT be replaced when only the leaf was lost — the user must not be told to re-import",
        restored.caFingerprint,
        afterLeafLoss.caFingerprint,
      )

      Log.i(TAG, "RESULT: PASS — both deleted-half routes report their replacement")
    } finally {
      // Best effort: leave the device with the identity it started with. A failure here is worth
      // shouting about, because the node is now serving something its clients do not trust.
      runCatching {
        caFile.writeBytes(caBackup)
        tlsFile.writeBytes(tlsBackup)
      }
        .onFailure { Log.e(TAG, "RESTORE FAILED — this node's identity has changed; re-import on every client", it) }
    }
  }

  private companion object {
    const val TAG = "RelaisCertPartialStateProbe"
  }
}

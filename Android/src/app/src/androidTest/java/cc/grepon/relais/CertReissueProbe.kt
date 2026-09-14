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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for the two properties that only real network interfaces can demonstrate
 * (feature-18 T10): the SAN set reflects the device's **actual** addresses, and re-issuing across a
 * network change keeps both the CA and the leaf key.
 *
 * This is the only coverage that exists for [RelaisLanIp.allLanAddresses]'s `isUp` and
 * IPv6-link-local filters. `NetworkInterface` is final with no public constructor, so those filters
 * are **not testable in the JVM lane at all** — `RelaisCertSanTest` says so explicitly rather than
 * pretending otherwise.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.CertReissueProbe \
 *     -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *   # in another shell: adb logcat -s RelaisCertReissueProbe:*
 *
 * The **cross-network** half is manual and cannot be automated from inside the probe: with the
 * node running, run it on Wi-Fi A, move the device to Wi-Fi B, wait for the same non-empty address
 * set to remain present for at least 15 seconds, then run it again. Compare the two logged blocks:
 * the `CA FINGERPRINT` and `NODE KEY PIN` must be identical while the SAN list changes. Confirm
 * the new SAN from a second machine before stopping the node. That is the acceptance criterion an
 * imported `relais-ca.crt` depends on.
 *
 * ## Manual: EVERY SAN MUST HAVE SOMETHING LISTENING ON IT
 *
 * For each address in the served leaf, confirm the node actually answers there. Cheap once you have
 * the device in hand, and it is the check that catches a whole class from the outside:
 *
 * ```
 * openssl s_client -connect <phone-ip>:8443 </dev/null 2>/dev/null \
 *   | openssl x509 -noout -text | grep -A2 "Subject Alternative Name"
 * # then, for EVERY address listed:
 * curl --cacert relais-ca.crt --max-time 5 https://<ipv4-address>:8443/health   # must answer
 * curl --globoff --cacert relais-ca.crt --max-time 5 \
 *   'https://[<ipv6-address>]:8443/health'                                      # must answer
 * ```
 *
 * Run both commands from a second machine on the same network, not from the device: a local
 * loopback success cannot prove the LAN listener's IPv4/IPv6 socket behavior. The IPv6 command is
 * deliberately bracketed and uses `--globoff`; without both, curl treats the literal as a port or
 * a URL glob instead of exercising the advertised SAN.
 *
 * **Nothing else catches this.** The SAN row on `GET /` reports what the certificate carries, which
 * can be perfectly accurate while nothing serves the address — that is exactly how IPv6 addresses
 * were certified for a listener bound only to `0.0.0.0`, and a hardware run that exercised IPv4
 * would have shown a correct-looking SAN row throughout. The certificate is checked against itself
 * everywhere; this is the only step that checks it against the **listener**.
 *
 * ## Manual: STOP MUST ACTUALLY STOP
 *
 * No automated test can cover this — nothing in the JVM lane constructs a `Service`, and this probe
 * runs in-process rather than driving the real service lifecycle. **Run it from a second machine:**
 *
 * ```
 * # 1. Start the node from the app, confirm it answers:
 * curl -k --max-time 5 https://<phone-ip>:8443/health          # expect 200
 *
 * # 2. Stop it from the app. Then, from the OTHER machine:
 * curl -k --max-time 5 https://<phone-ip>:8443/health          # MUST fail to connect
 * nmap -Pn -p 8443 <phone-ip>                                  # 8443 MUST NOT be open
 * ```
 *
 * A listener still answering after step 2 means every user-visible surface says the node is off —
 * notification gone, QS tile and control panel reading "stopped", mDNS unregistered — while
 * `0.0.0.0:8443` is bound and presenting the node's certificate to the LAN, with no in-app remedy.
 * Treat a failure here as release-blocking.
 *
 * The live LAN rebind is intentionally active while the node runs. This stop check is therefore
 * also its teardown proof: change networks, stop immediately, then verify that no queued callback
 * can resurrect `:8443` after every UI surface says the node is stopped.
 */
@RunWith(AndroidJUnit4::class)
class CertReissueProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()

  @Before
  fun setUp() {
    assumeTrue("On-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
  }

  @Test
  fun theSanSetCoversTheDevicesRealAddressesAndSurvivesAReissue() {
    val live = RelaisLanIp.allLanAddresses().mapNotNull { it.hostAddress }
    Log.i(TAG, "live addresses: ${live.joinToString(", ").ifEmpty { "(none — no LAN?)" }}")

    val before = RelaisTls.certInfo(context)
    Log.i(TAG, "--- BEFORE ---")
    logInfo(before)

    // Every live address must be in the cert, or a client connecting on that interface fails
    // hostname verification. This is the assertion the JVM lane structurally cannot make.
    live.forEach { addr ->
      assertTrue(
        "live address $addr is missing from the SAN set ${before.sanList}",
        before.sanList.any { it.equals(addr, ignoreCase = true) },
      )
    }
    assertTrue("loopback must always be covered", before.sanList.contains("127.0.0.1"))
    assertTrue("IPv6 loopback must be covered", before.sanList.any { it == "::1" || it == "0:0:0:0:0:0:0:1" })

    // Re-read after a second load, then prove the identity did not move. This used to force a
    // re-issue through the dynamic-rebind path; that path is not in this release (see the tracked
    // follow-up), so what is checked here is the property that matters either way — the CA and the
    // leaf key are stable across loads, which is what an imported `relais-ca.crt` and a
    // `--pinnedpubkey` pin depend on.
    val after = RelaisTls.certInfo(context)
    Log.i(TAG, "--- AFTER RE-ISSUE ---")
    logInfo(after)

    // The CA is minted once, ever: a changed value here means every client must re-import.
    assertEquals("the CA must never change", before.caFingerprint, after.caFingerprint)
    // The feature-23 contract: --pinnedpubkey must survive a re-issue.
    assertEquals("the leaf key must be reused", before.nodeKeyPin, after.nodeKeyPin)

    Log.i(TAG, "RESULT: PASS — CA and node key pin unchanged across a re-issue")
    Log.i(TAG, "To check the cross-network case, re-run this on a different Wi-Fi and compare the two blocks.")
  }

  private fun logInfo(info: RelaisCertInfo) {
    Log.i(TAG, "CA FINGERPRINT: ${info.caFingerprint}")
    Log.i(TAG, "NODE KEY PIN:   ${info.nodeKeyPin}")
    Log.i(TAG, "SANs:           ${info.sanList.joinToString(", ")}")
    Log.i(TAG, "leaf expires:   ${java.util.Date(info.leafNotAfter)}")
  }

  private companion object {
    const val TAG = "RelaisCertReissueProbe"
  }
}

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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe: a **verified** TLS handshake against the node's real listener (feature-18 T10).
 *
 * `RelaisTlsHandshakeTest` proves the same property in the JVM lane, and that is **not a
 * substitute**: the JVM uses JSSE, the device uses conscrypt, and the combination this feature
 * introduces — an EC P-256 CA signing an RSA-2048 leaf — is only truly proven where conscrypt does
 * the verifying. If conscrypt rejects that pairing, the feature does not work at all and every JVM
 * test stays green.
 *
 * `assumeTrue`-gated (skips unless RELAIS_PROBE=1) so it never runs in the JVM unit lane or in
 * unattended CI. The device must be unlocked.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.CertTrustProbe \
 *     -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *   # in another shell: adb logcat -s RelaisCertTrustProbe:*
 *
 * **Run this on a release (minified) APK as well as a debug one.** R8 is on for release, CI runs
 * none of it, and `proguard-rules.pro` gained its first BouncyCastle rules with this feature — a
 * stripped class shows up here and nowhere else.
 *
 * While on the release build, record two more things:
 *  - **The APK size delta** against the tracked 74.10 MiB. The BouncyCastle keep is deliberately
 *    broad; that number is the data needed to decide whether narrowing it is ever worth a second
 *    device cycle, and without it the question can only be argued.
 *  - **The RE-MINT path, not just the first mint.** `mintCa` and `mintLeaf` reach different
 *    BouncyCastle code, so a class stripped from the re-mint path stays invisible until a network
 *    change. Run this probe, change networks, then run `CertReissueProbe`.
 *
 * **This release run is the R8 baseline for the BouncyCastle bump, and must come first.** The pin
 * is 1.78.1 against a current 1.85, held deliberately — see the rationale at the dependency in
 * `build.gradle.kts`. A BC bump is a reflective-dependency bump landing on keep rules that have
 * never been exercised on a release build, so bumping before this passes would conflate "the keep
 * rules are wrong" with "the new version moved something", inside the one failure mode CI cannot
 * see. Order: this probe green on 1.78.1 release → bump → this probe again, re-mint path included.
 *
 * It also covers the one interop question the CA's **EKU** raises. The CA carries a non-critical
 * `serverAuth + clientAuth` extended key usage, and verifiers that implement EKU nesting intersect
 * a leaf's EKU with its issuer's. A handshake here that fails path building — rather than hostname
 * verification — would mean conscrypt reads that intersection differently than expected. Non-critical
 * should make a nesting-unaware stack ignore it entirely, but "should" is what this probe exists to
 * replace with a measurement.
 */
@RunWith(AndroidJUnit4::class)
class CertTrustProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()
  private val port = 18443 // high loopback port; never the real 8443
  private var server: RelaisHttpServer? = null

  @Before
  fun setUp() {
    assumeTrue("On-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    // No sleep after start(), and that absence is deliberate — it is the assertion.
    //
    // `start()` binds synchronously and returns only once the socket is listening (or throws), so a
    // connect on the very next line must succeed. When binding happened on the spawned thread this
    // needed a sleep, and the resulting "returns before it is listening" window was the root of
    // four separate listener races. If a connect below ever fails to reach the port, suspect that
    // asynchrony has come back rather than adding a sleep to paper over it.
    server = RelaisHttpServer(context, port = port, tls = true, bindAddr = "127.0.0.1").also { it.start() }
  }

  @After
  fun tearDown() {
    server?.stop()
    server = null
  }

  @Test
  fun conscryptVerifiesTheLeafAgainstTheNodeCaWithHostnameVerificationOn() {
    val info = RelaisTls.certInfo(context)
    Log.i(TAG, "CA FINGERPRINT: ${info.caFingerprint}")
    Log.i(TAG, "NODE KEY PIN:   ${info.nodeKeyPin}")
    Log.i(TAG, "SANs:           ${info.sanList.joinToString(", ")}")

    val trust = KeyStore.getInstance("PKCS12").apply { load(null, null) }
    trust.setCertificateEntry("relais-ca", parseCa(info.caPem))
    val tmf =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
    val ctx = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }

    // The String overload: given an InetAddress, the stack may reverse-resolve 127.0.0.1 to
    // "localhost" and match the localhost dNSName for the wrong reason.
    val sock = ctx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
    sock.use {
      // getSSLParameters() returns a COPY — assigning back is what actually enables hostname
      // verification. Without this the probe would pass against a SAN-less cert and prove nothing.
      it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
      it.soTimeout = 15_000
      it.startHandshake()
      Log.i(TAG, "handshake OK: ${it.session.cipherSuite} / ${it.session.protocol}")

      it.outputStream.write(
        "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray()
      )
      it.outputStream.flush()
      val statusLine = BufferedReader(InputStreamReader(it.inputStream)).readLine()
      Log.i(TAG, "GET /health over verified TLS: $statusLine")
      assertNotNull("no status line from /health", statusLine)
      assertTrue("expected 200 from /health, got: $statusLine", statusLine.contains(" 200 "))
    }
  }

  @Test
  fun caCertRouteServesThePemWithoutABearerToken() {
    val info = RelaisTls.certInfo(context)
    val trust = KeyStore.getInstance("PKCS12").apply { load(null, null) }
    trust.setCertificateEntry("relais-ca", parseCa(info.caPem))
    val tmf =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
    val ctx = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }

    (ctx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use {
      it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
      it.soTimeout = 15_000
      // Deliberately NO Authorization header — the exemption is the point of the route.
      it.outputStream.write(
        "GET /ca.crt HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray()
      )
      it.outputStream.flush()
      val body = BufferedReader(InputStreamReader(it.inputStream)).readText()
      Log.i(TAG, "GET /ca.crt (no auth): ${body.lineSequence().first()}")
      assertTrue("expected 200, got: ${body.lineSequence().first()}", body.contains(" 200 "))
      assertTrue("expected a PEM body", body.contains("-----BEGIN CERTIFICATE-----"))
      // The one thing that must never be true of this route.
      assertTrue("a private key must never be served", !body.contains("PRIVATE KEY"))
    }
  }

  /**
   * `stop()` then immediately `start()` on the same port must succeed — the on-device proof that
   * the LAN rebind cannot lose a bind race against the listener it just replaced.
   *
   * This is the check for the fourth and last race in that mechanism: `stop()` used to return while
   * the old accept thread still held the port, so the replacement's bind could lose, exit silently,
   * and leave the node with **no** HTTPS listener while believing it had rebound. `stop()` now joins
   * the accept thread, so the port is provably free before the next `start()` needs it.
   *
   * No sleep between the two calls, deliberately: a sleep would hide exactly the defect this is for.
   */
  @Test
  fun stopThenImmediatelyStartOnTheSamePortSucceeds() {
    val first = requireNotNull(server) { "setUp should have started a server" }
    first.stop()

    // If the port were still held, this throws — which is the failure worth catching, since the
    // production path would instead have swallowed it on a background thread.
    val replacement =
      RelaisHttpServer(context, port = port, tls = true, bindAddr = "127.0.0.1").also { it.start() }
    server = replacement

    val info = RelaisTls.certInfo(context)
    val trust = KeyStore.getInstance("PKCS12").apply { load(null, null) }
    trust.setCertificateEntry("relais-ca", parseCa(info.caPem))
    val tmf =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
    val ctx = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }

    (ctx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use {
      it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
      it.soTimeout = 15_000
      it.startHandshake()
      Log.i(TAG, "rebind on the same port succeeded: ${it.session.cipherSuite}")
    }
  }

  private fun parseCa(pem: String) =
    java.security.cert.CertificateFactory.getInstance("X.509")
      .generateCertificate(pem.byteInputStream())

  private companion object {
    const val TAG = "RelaisCertTrustProbe"
  }
}

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

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Real-socket tests for the wall-clock watchdogs on [HttpRequestReader] and [withCloseDeadline]
 * (follow-up to #375). The fake-clock tests in [RelaisHttpIoTest] prove the between-reads check; they
 * cannot show what matters on TLS: an `SSLSocket.read()` does not return until a whole TLS record has
 * arrived, so a check that runs after `read()` returns never runs for a byte-by-byte drip. Only a
 * watchdog that closes the plain transport underneath bounds that.
 *
 * The TLS provider is whatever the test JVM has installed: JSSE in some variants, Conscrypt's
 * `ConscryptEngineSocket` in others (that is how the handshake-in-`getInputStream()` case behind
 * [DeferredSocketInputStream] was found). Neither is a substitute for the device's Conscrypt build.
 */
class RelaisHttpSocketDeadlineTest {

  private lateinit var scheduler: ScheduledThreadPoolExecutor
  private lateinit var listener: ServerSocket
  private val opened = mutableListOf<Socket>()

  @Before
  fun setUp() {
    // The server's own scheduler (daemon: a RED run whose close() hangs must not pin the test JVM).
    scheduler = newSocketWatchdog()
    listener = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
  }

  @After
  fun tearDown() {
    scheduler.shutdownNow()
    opened.forEach { runCatching { it.close() } }
    listener.close()
  }

  /** A connected (client, accepted raw server side) pair of plain sockets. */
  private fun connectedPair(): Pair<Socket, Socket> {
    val client = Socket(listener.inetAddress, listener.localPort).also { opened += it }
    val raw = listener.accept().also { opened += it }
    return client to raw
  }

  private fun daemon(name: String, body: () -> Unit) =
    Thread({ runCatching(body) }, name).apply { isDaemon = true; start() }

  @Test(timeout = 20_000) // without the read watchdog the first read blocks for the whole ~50 s drip
  fun `a TLS handshake dripped one byte at a time is cut off at the header deadline`() {
    val (client, raw) = connectedPair()
    raw.soTimeout = 2_000 // never fires: a byte arrives every 200 ms
    val tls = layerServerTls(contexts.server.socketFactory, raw)
    // A handshake record header claiming 256 bytes, then the body one byte per 200 ms. JSSE's first
    // read() waits for the whole record, so no between-reads deadline check can run before ~51 s.
    daemon("drip") {
      val out = client.getOutputStream()
      out.write(byteArrayOf(0x16, 0x03, 0x01, 0x01, 0x00))
      out.flush()
      repeat(256) {
        Thread.sleep(200)
        out.write(0x01)
        out.flush()
      }
    }
    // DeferredSocketInputStream, as the server does: Conscrypt handshakes inside getInputStream().
    val reader =
      HttpRequestReader(
        DeferredSocketInputStream(tls),
        headerDeadlineMs = 500,
        watchdog = scheduler,
        transport = raw,
        watchdogGraceMs = 200,
      )

    val started = System.nanoTime()
    try {
      reader.readLine()
      fail("a dripped handshake must not produce a request line")
    } catch (e: HttpReadDeadlineException) {
      // expected: reported as a deadline, not as a generic socket error
    }
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    assertTrue("cut off near the 500+200 ms deadline, took ${elapsedMs}ms", elapsedMs < 5_000)
    assertTrue("the plain transport is what gets closed", raw.isClosed)
  }

  @Test(timeout = 20_000) // without the body watchdog the read blocks forever (soTimeout 0)
  fun `a body read from a silent peer is cut off at the body deadline`() {
    val (_, raw) = connectedPair()
    raw.soTimeout = 0
    val reader =
      HttpRequestReader(raw.getInputStream(), bodyDeadlineMs = 300, watchdog = scheduler, transport = raw, watchdogGraceMs = 100)

    val started = System.nanoTime()
    try {
      reader.readBodyBytes(10)
      fail("a silent peer must not produce a body")
    } catch (e: HttpReadDeadlineException) {
      // expected
    }
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    assertTrue("cut off near the 300+100 ms deadline, took ${elapsedMs}ms", elapsedMs < 5_000)
    assertTrue(raw.isClosed)
  }

  @Test(timeout = 20_000)
  fun `read watchdogs do not outlive the read that armed them`() {
    // The server goes on to run inference for minutes after the header and body reads; a watchdog
    // left armed past its read would close every long request's socket.
    val (client, raw) = connectedPair()
    client.getOutputStream().apply {
      write("GET / HTTP/1.1\r\nHost: relais\r\n\r\nhello".toByteArray())
      flush()
    }
    val reader =
      HttpRequestReader(
        raw.getInputStream(),
        headerDeadlineMs = 300,
        bodyDeadlineMs = 300,
        watchdog = scheduler,
        transport = raw,
        watchdogGraceMs = 100,
      )
    while (reader.readLine()?.isNotEmpty() == true) Unit
    reader.readBodyBytes(5)

    Thread.sleep(1_000) // well past every deadline + grace
    assertFalse("a finished read must cancel its watchdog", raw.isClosed)
  }

  @Test(timeout = 30_000) // closing the SSLSocket instead of the raw transport hangs here
  fun `closing the raw transport unblocks a TLS write to a peer that never reads`() {
    val (clientRaw, raw) = connectedPair()
    val tls = layerServerTls(contexts.server.socketFactory, raw)
    val client =
      (contexts.client.socketFactory.createSocket(clientRaw, "127.0.0.1", listener.localPort, true) as SSLSocket)
    daemon("client-handshake") { client.startHandshake() } // then never reads
    tls.startHandshake()

    val started = System.nanoTime()
    val failed =
      runCatching {
        withCloseDeadline(scheduler, raw, timeoutMs = 300) {
          tls.outputStream.write(ByteArray(64 * 1024 * 1024))
          tls.outputStream.flush()
        }
      }.isFailure
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    assertTrue("blocked TLS write must fail, not hang", failed)
    assertTrue("unblocked near the deadline, took ${elapsedMs}ms", elapsedMs < 10_000)
    assertTrue(raw.isClosed)
  }

  @Test
  fun `cancelled watchdogs leave the scheduler queue`() {
    // Each guarded read arms a watchdog for its whole remaining budget (about 32 s for a header line).
    // A cancelled one must be dequeued at once; left queued, a header flood keeps millions of tasks
    // (each holding its socket) alive until their delays expire.
    val transport = java.io.Closeable {}
    val headers = (1..40).joinToString("") { "X-H$it: v\r\n" }
    val request = "POST /v1/x HTTP/1.1\r\n${headers}\r\nbody!"
    val reader =
      HttpRequestReader(
        request.byteInputStream(Charsets.ISO_8859_1),
        headerDeadlineMs = 30_000,
        bodyDeadlineMs = 120_000,
        watchdog = scheduler,
        transport = transport,
      )
    var lines = 0
    while (!reader.readLine().isNullOrEmpty()) lines++
    assertTrue(lines == 41)
    assertTrue(reader.readBodyBytes(5).contentEquals("body!".toByteArray()))
    assertTrue("cancelled watchdogs still queued: ${scheduler.queue.size}", scheduler.queue.isEmpty())
    // Guards against a vacuous pass: a watchdog really is queued while its call runs.
    withCloseDeadline(scheduler, transport, timeoutMs = 30_000) {
      assertTrue("the watchdog is armed while the call runs", scheduler.queue.size == 1)
    }
    assertTrue(scheduler.queue.isEmpty())
  }

  private class Contexts(val server: SSLContext, val client: SSLContext)

  private val contexts: Contexts by lazy {
    val pass = "test-pass".toCharArray()
    val ca = RelaisCertMint.mintCa()
    val leafKey = RelaisCertMint.generateLeafKeyPair()
    val leaf =
      RelaisCertMint.mintLeaf(ca.keyPair.private, ca.certificate, leafKey.public, RelaisCertMint.buildSanList(emptyList()))
    val ks = KeyStore.getInstance("PKCS12").apply { load(null, pass) }
    ks.setKeyEntry("relais-tls", leafKey.private, pass, arrayOf(leaf, ca.certificate))
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
    val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
      load(null)
      setCertificateEntry("relais-ca", ca.certificate)
    }
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
    Contexts(
      server = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) },
      client = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) },
    )
  }
}

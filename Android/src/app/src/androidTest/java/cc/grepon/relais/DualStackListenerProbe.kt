/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */

package cc.grepon.relais

import android.content.Context
import java.net.BindException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hardware-only proof that the TLS listener can serve both loopback families with the same leaf.
 *
 * `ServerSocket` IPv6 wildcard behavior is kernel/runtime dependent: some devices allow a distinct
 * IPv4 socket, while others have the IPv6 wildcard claim IPv4-mapped traffic. This probe accepts
 * the latter only after a trusted IPv4 handshake; either shape must also verify `::1`.
 */
@RunWith(AndroidJUnit4::class)
class DualStackListenerProbe {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()

  @Before
  fun setUp() {
    assumeTrue("On-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
  }

  @Test
  fun bothAddressFamiliesReachTheCurrentLeaf() {
    val factories = RelaisTls.socketFactories(context)
    val ipv6 = RelaisHttpServer(context, port = PORT, tls = true, bindAddr = "::", socketFactory = factories.server)
    val ipv4 = RelaisHttpServer(context, port = PORT, tls = true, bindAddr = "0.0.0.0", socketFactory = factories.server)
    val started = mutableListOf<RelaisHttpServer>()

    try {
      ipv6.start()
      started += ipv6
      try {
        ipv4.start()
        started += ipv4
      } catch (_: BindException) {
        // The IPv6 wildcard may already serve IPv4; the assertion below proves that rather than
        // inferring it from EADDRINUSE.
        ipv4.stop()
      }
      assertTrue("IPv4 must complete a trusted TLS handshake", RelaisTls.verifiesCurrentLeafAt(factories.localClient, "127.0.0.1", PORT))
      assertTrue("IPv6 must complete a trusted TLS handshake", RelaisTls.verifiesCurrentLeafAt(factories.localClient, "::1", PORT))
    } finally {
      started.asReversed().forEach { server -> runCatching { server.stop() } }
    }
  }

  private companion object {
    const val PORT = 9443
  }
}

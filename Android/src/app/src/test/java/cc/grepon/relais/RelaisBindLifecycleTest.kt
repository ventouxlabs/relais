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

import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [bindOrClose] — the socket a failed bind must not leave behind (feature-18 codex round 17).
 *
 * The socket was created, then bound inside an `apply` block. A bind failure threw before anything
 * held a reference, so nothing closed it and the file descriptor stayed open until finalization —
 * which is not a schedule anything can depend on.
 *
 * **What made a one-off leak matter is a different fix.** Round 6 made a bind failure propagate and
 * the node retry, which was correct and is what turns one leaked descriptor into an unbounded
 * series: every START burns another, until the process cannot open sockets at all and the recovery
 * path is itself unrecoverable. The leak is old; the pressure that makes it fatal is new.
 *
 * The generalisable form, and the third instance on this branch: **a fix that adds repetition
 * should be followed by asking what the repeated path leaks.** Round 9's strict read made
 * corruption fatal, round 10's rotation rule made a lost password fatal, round 6's retry makes a
 * leaked descriptor fatal — each a correct fix supplying the pressure that promoted an adjacent
 * defect.
 *
 * Plain `java.net.ServerSocket` throughout: the property is ownership on the failure path, which is
 * not an Android behaviour, so it needs neither a `Context` nor the TLS factory to be real.
 */
class RelaisBindLifecycleTest {

  @Test
  fun `a successful bind returns an open, bound socket`() {
    val socket = ServerSocket()

    val result = bindOrClose(socket) { it.bind(InetSocketAddress("127.0.0.1", 0)) }

    try {
      assertTrue("the caller needs a bound socket back", result.isBound)
      assertFalse("a successful bind must not close the socket", result.isClosed)
    } finally {
      result.close()
    }
  }

  @Test
  fun `a failed bind closes the socket it was given`() {
    // Occupy a port, then bind a second socket to the same address. This is the real failure the
    // node hits when :8443 is already taken, not a simulated one.
    ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { occupied ->
      val taken = InetSocketAddress("127.0.0.1", occupied.localPort)
      val leaked = ServerSocket()

      try {
        bindOrClose(leaked) { it.bind(taken) }
        fail("binding to an occupied port must throw")
      } catch (expected: Exception) {
        // The assertion the whole fix exists for. Without it this socket stays open, and round 6's
        // retry loop repeats the leak on every START.
        assertTrue("a failed bind must close the socket it created", leaked.isClosed)
      }
    }
  }

  @Test
  fun `the original failure still reaches the caller`() {
    ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { occupied ->
      val taken = InetSocketAddress("127.0.0.1", occupied.localPort)
      val socket = ServerSocket()

      val thrown = runCatching { bindOrClose(socket) { it.bind(taken) } }.exceptionOrNull()

      // Cleaning up must not swallow or replace the cause: round 6's whole point was that a bind
      // failure is observable to the caller that decides whether to retry. A close() that threw its
      // own exception over the top would undo that.
      assertTrue("the bind failure must propagate", thrown is java.io.IOException)
    }
  }
}

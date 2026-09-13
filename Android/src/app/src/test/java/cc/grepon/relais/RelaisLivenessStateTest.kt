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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaisLivenessStateTest {

  @Test fun `healthy startup publishes only real starting and live snapshots`() {
    val publisher = RelaisLivenessPublisher()

    publisher.beginStartup()
    val starting = publisher.snapshot
    publisher.publishListenersUp(true)
    val bound = publisher.snapshot
    publisher.endStartup()
    val live = publisher.snapshot

    assertEquals(RelaisLiveness(listenersUp = false, startupInProgress = true), starting)
    assertEquals(RelaisLiveness(listenersUp = true, startupInProgress = true), bound)
    assertEquals(RelaisLiveness(listenersUp = true, startupInProgress = false), live)
    assertFalse(listOf(starting, bound, live).contains(RelaisLiveness()))
  }

  @Test fun `each publisher update preserves the other liveness component`() {
    val publisher = RelaisLivenessPublisher(RelaisLiveness(listenersUp = true))

    publisher.beginStartup()
    assertTrue(publisher.snapshot.listenersUp)
    assertTrue(publisher.snapshot.startupInProgress)

    publisher.publishListenersUp(false)
    assertFalse(publisher.snapshot.listenersUp)
    assertTrue(publisher.snapshot.startupInProgress)
  }

  @Test fun `overlapping startup owners keep the node starting until the final owner ends`() {
    val publisher = RelaisLivenessPublisher()

    publisher.beginStartup()
    publisher.beginStartup()
    publisher.endStartup()

    assertTrue(publisher.snapshot.startupInProgress)

    publisher.endStartup()
    assertFalse(publisher.snapshot.startupInProgress)
  }

  @Test fun `listener publication preserves a concurrent startup owner`() {
    val publisher = RelaisLivenessPublisher()
    val started = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val worker = thread {
      publisher.beginStartup()
      started.countDown()
      check(finish.await(5, TimeUnit.SECONDS))
      publisher.endStartup()
    }

    assertTrue("startup writer did not begin", started.await(5, TimeUnit.SECONDS))
    publisher.publishListenersUp(true)
    assertEquals(RelaisLiveness(listenersUp = true, startupInProgress = true), publisher.snapshot)

    finish.countDown()
    worker.join(5_000)
    assertFalse("startup writer did not finish", worker.isAlive)
    assertEquals(RelaisLiveness(listenersUp = true, startupInProgress = false), publisher.snapshot)
  }
}

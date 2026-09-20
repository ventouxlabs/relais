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

  // ---- feature-22: idleUnloaded is the third lifecycle fact, published in the same snapshot ----

  @Test fun `a real init attempt clears idle-unloaded and starts in ONE snapshot`() {
    // ensureInitialized's real-init branch is the only caller of clearIdleUnloaded = true. Both
    // facts flip in the same replaced snapshot — a reader can never see startupInProgress=true
    // beside a stale idleUnloaded=true (which computeNodeState would tolerate, slot 3 > 5, but a
    // separate-read design would let a poll combine the old idle with the new startup).
    val publisher = RelaisLivenessPublisher(RelaisLiveness(listenersUp = true, idleUnloaded = true))

    publisher.beginStartup(clearIdleUnloaded = true)

    assertEquals(
      RelaisLiveness(listenersUp = true, startupInProgress = true, idleUnloaded = false),
      publisher.snapshot,
    )
  }

  @Test fun `a plain beginStartup preserves idle-unloaded`() {
    // The swap thread begins startup BEFORE it has resolved a target; a missing file or a resolve
    // failure ends startup with no init attempted. Clearing idle there would make a healthy idle
    // node read STARTING, trip the stall detector, and get restarted for a failed operator action.
    val publisher = RelaisLivenessPublisher(RelaisLiveness(listenersUp = true, idleUnloaded = true))

    publisher.beginStartup()
    assertEquals(
      RelaisLiveness(listenersUp = true, startupInProgress = true, idleUnloaded = true),
      publisher.snapshot,
    )

    publisher.endStartup()
    assertEquals(
      RelaisLiveness(listenersUp = true, startupInProgress = false, idleUnloaded = true),
      publisher.snapshot,
    )
  }

  @Test fun `publishIdleUnloaded preserves the other two liveness components`() {
    val publisher = RelaisLivenessPublisher(RelaisLiveness(listenersUp = true, startupInProgress = true))

    publisher.publishIdleUnloaded(true)
    assertEquals(
      RelaisLiveness(listenersUp = true, startupInProgress = true, idleUnloaded = true),
      publisher.snapshot,
    )

    publisher.publishIdleUnloaded(false)
    assertEquals(
      RelaisLiveness(listenersUp = true, startupInProgress = true, idleUnloaded = false),
      publisher.snapshot,
    )
  }

  @Test fun `listener and idle publication each preserve the other`() {
    val publisher = RelaisLivenessPublisher()

    publisher.publishIdleUnloaded(true)
    publisher.publishListenersUp(true)
    assertEquals(RelaisLiveness(listenersUp = true, idleUnloaded = true), publisher.snapshot)

    publisher.publishListenersUp(false)
    assertTrue(publisher.snapshot.idleUnloaded)
    assertFalse(publisher.snapshot.listenersUp)
  }

  @Test fun `a nested clearing begin keeps the node starting until the OUTER owner ends`() {
    // The service's init thread and the swap thread each wrap ensureInitialized's own pair; the
    // inner clear must not end the outer owner's startup, and the clear must survive the inner end.
    val publisher = RelaisLivenessPublisher(RelaisLiveness(idleUnloaded = true))

    publisher.beginStartup() // outer owner (service / swap thread)
    publisher.beginStartup(clearIdleUnloaded = true) // inner: ensureInitialized's real-init branch
    publisher.endStartup() // inner end

    assertEquals(RelaisLiveness(startupInProgress = true, idleUnloaded = false), publisher.snapshot)

    publisher.endStartup() // outer end
    assertEquals(RelaisLiveness(startupInProgress = false, idleUnloaded = false), publisher.snapshot)
  }

  @Test fun `a fresh process starts with all three facts false`() {
    assertEquals(RelaisLiveness(listenersUp = false, startupInProgress = false, idleUnloaded = false), RelaisLiveness())
  }
}

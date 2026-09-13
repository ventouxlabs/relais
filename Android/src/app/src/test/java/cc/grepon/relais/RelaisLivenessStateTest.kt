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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaisLivenessStateTest {

  @Test fun `healthy startup publishes only real starting and live snapshots`() {
    val publisher = RelaisLivenessPublisher()

    publisher.publishStartupInProgress(true)
    val starting = publisher.snapshot
    publisher.publishListenersUp(true)
    val bound = publisher.snapshot
    publisher.publishStartupInProgress(false)
    val live = publisher.snapshot

    assertEquals(RelaisLiveness(listenersUp = false, startupInProgress = true), starting)
    assertEquals(RelaisLiveness(listenersUp = true, startupInProgress = true), bound)
    assertEquals(RelaisLiveness(listenersUp = true, startupInProgress = false), live)
    assertFalse(listOf(starting, bound, live).contains(RelaisLiveness()))
  }

  @Test fun `each publisher update preserves the other liveness component`() {
    val publisher = RelaisLivenessPublisher(RelaisLiveness(listenersUp = true))

    publisher.publishStartupInProgress(true)
    assertTrue(publisher.snapshot.listenersUp)
    assertTrue(publisher.snapshot.startupInProgress)

    publisher.publishListenersUp(false)
    assertFalse(publisher.snapshot.listenersUp)
    assertTrue(publisher.snapshot.startupInProgress)
  }
}

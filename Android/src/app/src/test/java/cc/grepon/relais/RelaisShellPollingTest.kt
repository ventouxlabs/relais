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

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the #145 "polling pauses when backgrounded" behavior (issue #171).
 *
 * [pollingStateFlow] shares a 1 Hz cold loop via `SharingStarted.WhileSubscribed(stopTimeoutMs)`, so
 * it must: run while a collector is present, STOP within the grace window after the last collector
 * unsubscribes (app backgrounded → no perpetual 1 Hz work), and RESUME on re-subscribe. A refactor
 * that dropped `WhileSubscribed` (e.g. a plain `viewModelScope` while-loop) would keep emitting while
 * "backgrounded" and fail the middle assertion — which is the whole point of this test.
 *
 * Uses virtual time (`runTest`) with a counting `produce`, so it's a fast JVM unit test — no Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelaisShellPollingTest {

  @Test
  fun `poll runs while subscribed, stops after the grace when unsubscribed, and resumes`() = runTest {
    val calls = AtomicInteger(0)
    val flow =
      pollingStateFlow(
        scope = backgroundScope,
        produce = { calls.incrementAndGet() },
        intervalMs = 1000L,
        stopTimeoutMs = 5000L,
      )

    // --- Phase 1: subscribed → emits at ~1 Hz ---
    val job1 = launch { flow.collect {} }
    advanceTimeBy(10_001) // ~11 ticks over 10s
    runCurrent()
    val afterSubscribed = calls.get()
    assertTrue("poll should run ~1 Hz while subscribed (got $afterSubscribed)", afterSubscribed >= 10)

    // --- Phase 2: unsubscribe → keeps ticking only through the 5s grace, then STOPS ---
    job1.cancel()
    runCurrent()
    advanceTimeBy(5_000) // grace window still active
    runCurrent()
    val afterGrace = calls.get()
    advanceTimeBy(120_000) // long idle "backgrounded" stretch
    runCurrent()
    val afterIdle = calls.get()
    assertEquals(
      "poll MUST stop after the WhileSubscribed grace — no 1 Hz work while backgrounded",
      afterGrace,
      afterIdle,
    )

    // --- Phase 3: re-subscribe → RESUMES ---
    val job2 = launch { flow.collect {} }
    advanceTimeBy(5_001)
    runCurrent()
    val afterResume = calls.get()
    assertTrue("poll MUST resume on re-subscribe (idle=$afterIdle resume=$afterResume)", afterResume > afterIdle)
    job2.cancel()
  }

  @Test
  fun `with no subscriber ever, the loop never runs past the eager seed`() = runTest {
    val calls = AtomicInteger(0)
    pollingStateFlow(scope = backgroundScope, produce = { calls.incrementAndGet() }, intervalMs = 1000L, stopTimeoutMs = 5000L)
    // The StateFlow seed calls produce() exactly once; with no collector the loop must not start.
    advanceTimeBy(60_000)
    runCurrent()
    assertEquals("no collector → only the eager seed produce(), no polling", 1, calls.get())
  }

  // ---------------------------------------------------------------------------
  // #217 stall predicate × feature-22 idle-unload. The detector's premise — "running with no init
  // in flight means the service died" — predates idle-unload: an idle node is running, not ready,
  // and has nothing in flight BY DESIGN. Without the exclusion it read "node not running · press
  // START" after three polls, and START is a full service re-init with a listener bounce.
  // ---------------------------------------------------------------------------

  @Test
  fun `an idle-unloaded node never looks stalled while a dead one still does`() {
    // Both rows in ONE test: the exclusion only discriminates beside its positive twin.
    assertFalse(
      "idle-unloaded is a healthy state, not a stall",
      looksStalled(running = true, ready = false, startupInProgress = false, idleUnloaded = true),
    )
    assertTrue(
      "the original #217 case — service died under a persisted shouldRun — must still count",
      looksStalled(running = true, ready = false, startupInProgress = false, idleUnloaded = false),
    )
  }

  @Test
  fun `looksStalled needs running, not ready, and nothing in flight`() {
    assertFalse("stopped", looksStalled(running = false, ready = false, startupInProgress = false, idleUnloaded = false))
    assertFalse("ready", looksStalled(running = true, ready = true, startupInProgress = false, idleUnloaded = false))
    assertFalse("init in flight", looksStalled(running = true, ready = false, startupInProgress = true, idleUnloaded = false))
  }
}

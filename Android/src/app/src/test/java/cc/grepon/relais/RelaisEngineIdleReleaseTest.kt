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

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * feature-22: the idle-release write order. `releaseIfIdle` publishes `idleUnloaded = true` BEFORE
 * it closes the engine, so the writer's state sequence never contains an instant with
 * `!ready && !startupInProgress && !idleUnloaded` — the one combination that drops the watchdog's
 * shield and dispatches a full restart (`shouldDispatchStartup(ready = false, …)` is true).
 *
 * A JVM test never has a real engine, and `Engine` is final with a JNI `close()`, so the resident
 * engine is a Robolectric-shadowed instance: `isInitialized()` answers true and `close()` is a
 * no-op, which is all `releaseIfIdle` and `closeEngine` need from it. Injected through reflection
 * into `RelaisEngine`'s private field — the only seam, and the test's, not production's.
 *
 * The two-thread test is best-effort at KILLING the wrong order (an observer must land inside a
 * window of a few volatile writes) but deterministic-safe under the right one: a sample is kept
 * only when the cycle counter and both snapshot reads agree, and within one cycle the snapshot
 * `(!startupInProgress, !idleUnloaded)` exists only between the test's `endStartup()` and the
 * release's publish — an interval during which the engine is resident, so `isReady` is true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
  shadows = [RelaisEngineIdleReleaseTest.ShadowLitertlmEngine::class],
  instrumentedPackages = ["com.google.ai.edge.litertlm"],
)
class RelaisEngineIdleReleaseTest {

  @Implements(Engine::class)
  class ShadowLitertlmEngine {
    @Implementation fun isInitialized(): Boolean = true
    @Implementation fun close() = Unit
  }

  private val engineField = RelaisEngine::class.java.getDeclaredField("engine").apply { isAccessible = true }

  private fun injectResidentEngine() = engineField.set(RelaisEngine, Engine(EngineConfig(modelPath = "/nonexistent/spike.litertlm")))

  @Before fun precondition() {
    assertFalse("precondition: no resident engine", RelaisEngine.isReady)
    assertFalse("precondition: no startup in flight", RelaisLivenessState.snapshot.startupInProgress)
    RelaisLivenessState.publishIdleUnloaded(false)
  }

  @After fun tearDown() {
    engineField.set(RelaisEngine, null)
    RelaisLivenessState.publishIdleUnloaded(false)
  }

  @Test fun `a shadowed engine reads as resident and shutdown closes it and clears idle`() {
    injectResidentEngine()
    RelaisLivenessState.publishIdleUnloaded(true)
    assertTrue(RelaisEngine.isReady)

    RelaisEngine.shutdown()

    assertFalse(RelaisEngine.isReady)
    assertFalse("every non-idle shutdown clears the flag", RelaisLivenessState.snapshot.idleUnloaded)
  }

  @Test fun `releaseIfIdle returns true, leaves idle-unloaded published and the engine gone`() {
    injectResidentEngine()
    // lastActivityAtMs is private and stays at Task 2's 0L sentinel here — injectResidentEngine
    // sets the engine field directly via reflection and never stamps it. The arithmetic still
    // holds: nowMs (now + 60s) minus the 0L sentinel is far past any 1ms ttlMs, so
    // shouldUnloadIdleEngine is satisfied for a resident engine with nothing in flight regardless.
    val released = RelaisEngine.releaseIfIdle(ttlMs = 1L, nowMs = System.currentTimeMillis() + 60_000L)

    assertTrue("a resident, idle engine must be released", released)
    assertFalse(RelaisEngine.isReady)
    assertTrue(RelaisLivenessState.snapshot.idleUnloaded)
    assertFalse(RelaisLivenessState.snapshot.startupInProgress)
  }

  @Test fun `a second thread never observes an unshielded instant across many releases`() {
    // Every cycle boundary must be the real post-release state (!ready, !starting, idle=true) —
    // including the boundary BEFORE cycle 1. @Before leaves idle=false, and with that as the
    // starting state the gap between the cycle increment and the inject is an unshielded instant
    // the TEST creates: it passed alone (the observer thread was not yet running) and failed in
    // the full suite (warm JVM, observer up in time). Found 2026-09-20; the proof in the KDoc
    // assumes this line.
    RelaisLivenessState.publishIdleUnloaded(true)
    val cycle = AtomicInteger(0)
    val stop = java.util.concurrent.atomic.AtomicBoolean(false)
    var kept = 0
    var bad: Triple<Int, Boolean, RelaisLiveness>? = null
    val observer = thread(name = "idle-release-observer") {
      while (!stop.get()) {
        val c1 = cycle.get()
        val s1 = RelaisLivenessState.snapshot
        val ready = RelaisEngine.isReady
        val s2 = RelaisLivenessState.snapshot
        val c2 = cycle.get()
        if (c1 != c2 || s1 != s2) continue // torn sample — the reader's own gap, not the writer's
        kept++
        if (!ready && !s1.startupInProgress && !s1.idleUnloaded) {
          bad = Triple(c1, ready, s1)
          break
        }
      }
    }
    try {
      repeat(CYCLES) {
        cycle.incrementAndGet()
        injectResidentEngine() // (ready, !starting, idle)
        RelaisLivenessState.beginStartup(clearIdleUnloaded = true) // (ready, starting, !idle) — the real reset path
        RelaisLivenessState.endStartup() // (ready, !starting, !idle): the only interval with that snapshot
        assertTrue(RelaisEngine.releaseIfIdle(ttlMs = 1L, nowMs = System.currentTimeMillis() + 60_000L))
        // → publish: (ready, !starting, idle) → close: (!ready, !starting, idle). No unshielded instant.
      }
    } finally {
      stop.set(true)
      observer.join(5_000)
    }
    assertFalse("observer thread did not stop", observer.isAlive)
    assertTrue("observer kept no samples — the test is not observing anything", kept > 0)
    assertEquals(
      "an observer saw !ready && !startupInProgress && !idleUnloaded — the idle path exposed an unshielded instant",
      null,
      bad,
    )
  }

  private companion object {
    const val CYCLES = 5_000
  }
}

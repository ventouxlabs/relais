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

import android.content.ContextWrapper
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * feature-22 task 4(b): the state model a request-driven reload publishes. With no resident engine
 * (a JVM test never has one) every init attempt fails at `require(File(modelPath).exists())` —
 * BEFORE any native code — which is exactly the failure path whose flag writes this pins:
 *
 *  - `ensureInitialized`'s real-init branch clears `idleUnloaded` and sets `startupInProgress` at
 *    ATTEMPT START, records `lastInitFailed = true` on a throw, and `endStartup()`s in its
 *    `finally` so a failed attempt never leaves `startupInProgress` latched — a latched flag would
 *    shield the watchdog forever.
 *  - `ensureInitializedInBackground` publishes `startupInProgress` on the CALLER before the thread
 *    exists, so a "kick then wait" caller sees STARTING on return instead of racing the thread.
 *
 * Process-wide singletons ([RelaisLivenessState], [RelaisEngine.lastInitFailed]) are restored in
 * [tearDown] so nothing leaks into a sibling test sharing the sandbox.
 */
@RunWith(RobolectricTestRunner::class)
class RelaisEngineReloadPublishTest {

  private val ctx get() = RuntimeEnvironment.getApplication()

  @Before fun precondition() {
    assertFalse("precondition: no resident engine in a unit test", RelaisEngine.isReady)
    assertFalse("precondition: no startup in flight", RelaisLivenessState.snapshot.startupInProgress)
    // The background path resolves the default model path; it must NOT exist, or the attempt would
    // reach native engine-create instead of failing fast at `require`.
    val defaultPath = RelaisModelProvisioner.cachedPathOrDefault(ctx)
    assertFalse("precondition: default model path must not exist ($defaultPath)", File(defaultPath).exists())
    RelaisEngine.lastInitFailed = false
    RelaisLivenessState.publishIdleUnloaded(false)
    RelaisNodeProgress.reset() // so the phase assertion below cannot pass off a sibling test's write
  }

  @After fun tearDown() {
    awaitStartupEnded()
    RelaisEngine.lastInitFailed = false
    RelaisLivenessState.publishIdleUnloaded(false)
  }

  @Test fun `a failing synchronous reload clears idle at attempt start, records the failure, and balances its startup pair`() {
    RelaisLivenessState.publishIdleUnloaded(true)
    RelaisEngine.lastInitFailed = false

    val missing = File(ctx.cacheDir, "no-such-model.litertlm").absolutePath
    assertThrows(IllegalArgumentException::class.java) { RelaisEngine.ensureInitialized(ctx, modelPath = missing) }

    val after = RelaisLivenessState.snapshot
    assertFalse("idleUnloaded is cleared at attempt start, not on success", after.idleUnloaded)
    assertFalse("endStartup() must run in the finally — a leaked begin shields the watchdog forever", after.startupInProgress)
    assertTrue("a throwing attempt records lastInitFailed so the node reads ERROR, not IDLE", RelaisEngine.lastInitFailed)
  }

  /**
   * Observes the flags INSIDE a real init attempt, deterministically: with the model file present,
   * `require` passes and the very next call is `context.getExternalFilesDir(null)` — before any
   * litertlm class is touched — so a [ContextWrapper] that records the flags there and throws a
   * sentinel is a probe placed exactly between the attempt-start writes and the attempt's failure.
   */
  @Test fun `inside a real attempt — starting is published, idle and lastInitFailed are already clear`() {
    RelaisLivenessState.publishIdleUnloaded(true)
    RelaisEngine.lastInitFailed = true // a prior failure; this attempt is the retry

    val present = File(ctx.cacheDir, "present-model.litertlm").apply { writeBytes(byteArrayOf(0x00)) }
    var inside: Pair<RelaisLiveness, Boolean>? = null
    val probing = object : ContextWrapper(ctx) {
      override fun getExternalFilesDir(type: String?): File? {
        inside = RelaisLivenessState.snapshot to RelaisEngine.lastInitFailed
        throw ProbeStop()
      }
    }
    try {
      assertThrows(ProbeStop::class.java) { RelaisEngine.ensureInitialized(probing, modelPath = present.absolutePath) }
    } finally {
      present.delete()
    }

    val (liveness, failedFlag) = requireNotNull(inside) { "the probe never ran — require() failed before it, or the code moved" }
    assertTrue("a synchronous reload reads STARTING for its whole load (slot 3), not IDLE", liveness.startupInProgress)
    assertFalse("idleUnloaded is cleared in the same snapshot that begins the attempt", liveness.idleUnloaded)
    assertFalse("lastInitFailed is cleared at ATTEMPT START — a retry must not read as failed while it loads", failedFlag)
    assertEquals("the panel's phase line names the phase, never a bare 'starting'", ProvisionPhase.LOADING_ENGINE, RelaisNodeProgress.phase)
    // …and the failure of this attempt is recorded once it throws, with the pair balanced.
    assertTrue(RelaisEngine.lastInitFailed)
    assertFalse(RelaisLivenessState.snapshot.startupInProgress)
  }

  private class ProbeStop : RuntimeException("probe: stop the attempt here")

  @Test fun `the background reload publishes STARTING on the caller before returning`() {
    RelaisLivenessState.publishIdleUnloaded(true)

    RelaisEngine.ensureInitializedInBackground(ctx)

    // Observed on the CALLER, immediately: a kick-then-wait caller (ModelSwitch.awaitReload, the
    // widget worker) must never race the spawned thread's first statement.
    val onReturn = RelaisLivenessState.snapshot
    assertTrue("startupInProgress must be published before ensureInitializedInBackground returns", onReturn.startupInProgress)

    awaitStartupEnded()
    val settled = RelaisLivenessState.snapshot
    assertFalse("the thread's finally must end the caller's begin", settled.startupInProgress)
    assertFalse("the inner real-init attempt clears idleUnloaded", settled.idleUnloaded)
    assertTrue("a failed background reload is recorded — ERROR, not IDLE forever", RelaisEngine.lastInitFailed)

    // The single-flight CAS was released: a second kick dispatches again rather than no-oping.
    RelaisEngine.ensureInitializedInBackground(ctx)
    assertTrue("CAS must be reset after the thread finishes", RelaisLivenessState.snapshot.startupInProgress)
    awaitStartupEnded()
  }

  @Test fun `a background kick while a startup is already in flight is a no-op`() {
    RelaisLivenessState.beginStartup() // some other owner (service / swap / a synchronous reload)
    try {
      RelaisEngine.ensureInitializedInBackground(ctx)
      // If it had dispatched, its caller-side begin would nest and the outer end below would leave
      // startupInProgress true until the thread ended; instead the pair count is unchanged.
    } finally {
      RelaisLivenessState.endStartup()
    }
    assertFalse("no second owner was begun", RelaisLivenessState.snapshot.startupInProgress)
  }

  @Test fun `every shutdown clears idle-unloaded — STOP after an idle release must not read idle`() {
    // With no resident engine `engine?.close()` is a no-op, so this is the flag write alone. The
    // flag means exactly "the last close was an idle release": releaseIfIdle publishes true before
    // its own close (RelaisEngineIdleReleaseTest); every other shutdown (onDestroy's STOP, the swap
    // thread's close-before-reload) must leave it false, or RelaisInference's self-heal would
    // spawn a multi-GB reload with no foreground service behind it after idle → STOP.
    RelaisLivenessState.publishIdleUnloaded(true)

    RelaisEngine.shutdown()

    assertFalse(RelaisLivenessState.snapshot.idleUnloaded)
    assertFalse("shutdown() is not a startup owner", RelaisLivenessState.snapshot.startupInProgress)
  }

  private fun awaitStartupEnded() {
    val deadline = System.currentTimeMillis() + 5_000
    while (RelaisLivenessState.snapshot.startupInProgress && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assertFalse("background reload did not end its startup within 5s", RelaisLivenessState.snapshot.startupInProgress)
  }
}

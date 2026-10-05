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
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 * Request-driven reload publication and #368's missing-model routing. JVM tests never build a
 * native engine: absent-file synchronous init fails at require, provisioning is stopped by a
 * Context probe, and present-file init is stopped at getExternalFilesDir before the native SDK.
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
    // A sibling test's `remember` under the default id would otherwise answer pathFor below through
    // the cache (rung 1) with a file that exists, and the attempt would reach native init.
    RelaisModelProvisioner.resetPathCacheForTest()
    // The background path resolves the default model path; it must NOT exist, or the attempt would
    // reach native engine-create instead of failing fast at `require`.
    // Non-null here because a Robolectric context reports the DEFAULT model id, which is the one
    // id the default path is allowed to answer for (#337's rung-4 gate).
    val defaultPath =
      requireNotNull(RelaisModelProvisioner.pathFor(ctx, RelaisConfig.modelId(ctx))) {
        "expected the default model id to resolve to the default path"
      }
    assertFalse("precondition: default model path must not exist ($defaultPath)", File(defaultPath).exists())
    RelaisEngine.lastInitFailed = false
    RelaisLivenessState.publishIdleUnloaded(false)
    RelaisNodeProgress.reset() // so the phase assertion below cannot pass off a sibling test's write
  }

  @After fun tearDown() {
    awaitStartupEnded()
    RelaisEngine.startBackgroundStartup = { name, work -> kotlin.concurrent.thread(name = name, block = work) }
    RelaisEngine.lastInitFailed = false
    RelaisLivenessState.publishIdleUnloaded(false)
    RelaisLivenessState.publishListenersUp(false)
    RelaisNodeProgress.reset()
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
   * `ensureInitialized`'s `modelId` actually steers resolution (#337).
   *
   * Without this, the parameter the KDoc calls load-bearing is a no-op at every call site in the
   * tree: every caller that omits `modelPath` also omits `modelId`, and every caller that passes a
   * non-default `modelId` passes `modelPath` too, skipping resolution entirely. Replacing
   * `pathFor(context, modelId)` with `pathFor(context, RelaisConfig.modelId(context))` would pass
   * the whole suite.
   *
   * The discriminator is the failure: the configured model has a path on disk that EXISTS, and the
   * asked-about model has nothing. Resolving for the configured id would therefore succeed and run
   * on to native engine-create; resolving for the asked-about id fails first, and the message names
   * which model could not be located.
   */
  @Test fun `the modelId argument steers resolution, not the configured id`() {
    val configured = File(ctx.cacheDir, "configured-model.litertlm").apply { writeBytes(byteArrayOf(0x00)) }
    try {
      RelaisConfig.setModelId(ctx, "configured/id")
      RelaisConfig.setModelPath(ctx, configured.absolutePath) // set AFTER the id: setModelId clears it

      val thrown = assertThrows(IllegalArgumentException::class.java) {
        RelaisEngine.ensureInitialized(ctx, modelId = "asked/about")
      }
      assertTrue(
        "the failure must name the model that was ASKED about, not the configured one — " +
          "message was: ${thrown.message}",
        thrown.message?.contains("asked/about") == true,
      )
    } finally {
      configured.delete()
    }
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

  /**
   * `ensureInitializedInBackground` publishes `startupInProgress` on the CALLER before the spawned
   * thread exists — but a failing provision can settle before the caller reads the snapshot back.
   * Hold the worker at its first Context access (RelaisConfig.modelId's getSharedPreferences),
   * then stop provisioning with a deliberate sentinel. This pins publication independently of
   * scheduler timing without downloading weights or entering native code.
   */
  @Test fun `the background reload publishes STARTING on the caller before returning`() {
    RelaisLivenessState.publishIdleUnloaded(true)

    val releaseLatch = CountDownLatch(1)
    val probing = object : ContextWrapper(ctx) {
      override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        releaseLatch.await(5, TimeUnit.SECONDS) // bounded: a stuck release must not hang the suite
        if (RelaisNodeProgress.phase == ProvisionPhase.RESOLVING) throw ProbeStop()
        return super.getSharedPreferences(name, mode)
      }
    }

    RelaisEngine.ensureInitializedInBackground(probing)

    // Observed on the CALLER, immediately: a kick-then-wait caller (the widget worker) must never
    // race the spawned thread's first statement. Deterministic here because the worker is held above
    // before it can run any part of ensureInitialized, including its own begin/end pair or the outer
    // thread's finally.
    val onReturn = RelaisLivenessState.snapshot
    assertTrue("startupInProgress must be published before ensureInitializedInBackground returns", onReturn.startupInProgress)

    releaseLatch.countDown()
    awaitStartupEnded()
    val settled = RelaisLivenessState.snapshot
    assertFalse("the thread's finally must end the caller's begin", settled.startupInProgress)
    assertFalse("the inner real-init attempt clears idleUnloaded", settled.idleUnloaded)
    assertTrue("a failed background reload is recorded — ERROR, not IDLE forever", RelaisEngine.lastInitFailed)

    // The single-flight CAS was released: a second kick dispatches again rather than no-oping.
    // Same race as the first kick (the worker fails fast at `require` and can end the pair before
    // the next line reads the snapshot), so it gets its own held context, not the raw `ctx`.
    val releaseSecond = CountDownLatch(1)
    val probingSecond = object : ContextWrapper(ctx) {
      override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        releaseSecond.await(5, TimeUnit.SECONDS)
        if (RelaisNodeProgress.phase == ProvisionPhase.RESOLVING) throw ProbeStop()
        return super.getSharedPreferences(name, mode)
      }
    }
    RelaisEngine.ensureInitializedInBackground(probingSecond)
    assertTrue("CAS must be reset after the thread finishes", RelaisLivenessState.snapshot.startupInProgress)
    releaseSecond.countDown()
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

  @Test fun `missing weights enter provision without ERROR and duplicate reloads share the startup`() {
    RelaisLivenessState.publishIdleUnloaded(true)
    RelaisEngine.lastInitFailed = true
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val calls = AtomicInteger()
    val probing = provisioningProbe(entered, release, calls)
    try {
      RelaisEngine.ensureInitializedInBackground(probing)
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      assertTrue(RelaisLivenessState.snapshot.startupInProgress)
      assertFalse("missing weights are being provisioned, not a failed init", RelaisEngine.lastInitFailed)
      assertFalse(RelaisLivenessState.snapshot.idleUnloaded)
      repeat(5) { RelaisEngine.ensureInitializedInBackground(probing) }
      RelaisLivenessState.publishListenersUp(true)
      val retry = assertThrows(ModelNotOnDiskException::class.java) {
        RelaisEngine.generate(ctx, RelaisRequest("hello"), onToken = {})
      }
      assertTrue("text lane shares the already-running background provision", retry.provisioning)
      assertEquals("all duplicate kicks attach to the same startup", 1, calls.get())
      assertEquals(ProvisionPhase.RESOLVING, RelaisNodeProgress.phase)
    } finally {
      release.countDown()
      awaitStartupEnded()
    }
    assertTrue("a genuine provisioning failure records ERROR", RelaisEngine.lastInitFailed)
    assertEquals("failed provisioning must clear stale progress", ProvisionPhase.IDLE, RelaisNodeProgress.phase)
  }

  @Test fun `STOP during provisioning remains prompt and prevents init after provision succeeds`() {
    RelaisConfig.setModelId(ctx, "test/model")
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val loading = AtomicBoolean(false)
    val present = File(ctx.cacheDir, "provisioned-after-stop.litertlm")
    val probing = provisioningProbe(entered, release, AtomicInteger(), failProvision = false, loading = loading)
    try {
      RelaisEngine.ensureInitializedInBackground(probing)
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      // STOP acquires the engine lock; if provisioning held it this could not return until release.
      RelaisEngine.shutdown()
      present.writeBytes(byteArrayOf(0))
      RelaisConfig.setModelPath(ctx, present.absolutePath)
      release.countDown()
      awaitStartupEnded()
      assertFalse("the original shutdown epoch must be retained through provision", loading.get())
      assertFalse("STOP owns the outcome; skipped init is not a failure", RelaisEngine.lastInitFailed)
      assertFalse(RelaisLivenessState.snapshot.idleUnloaded)
    } finally {
      release.countDown()
      awaitStartupEnded()
      present.delete()
      RelaisModelProvisioner.resetPathCacheForTest()
    }
  }

  @Test fun `provisioned missing weights continue into init under the same startup owner`() {
    RelaisConfig.setModelId(ctx, "test/model")
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val loading = AtomicBoolean(false)
    val startupInsideInit = AtomicBoolean(false)
    val present = File(ctx.cacheDir, "just-provisioned.litertlm")
    val probing = provisioningProbe(
      entered, release, AtomicInteger(), failProvision = false,
      loading = loading, startupInsideInit = startupInsideInit,
    )
    try {
      RelaisEngine.ensureInitializedInBackground(probing)
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      present.writeBytes(byteArrayOf(0))
      RelaisConfig.setModelPath(ctx, present.absolutePath)
      release.countDown()
      awaitStartupEnded()
      assertTrue("a missing-model reload must proceed past provisioning to init", loading.get())
      assertTrue("startup remains published inside init after provisioning", startupInsideInit.get())
      assertTrue("the deliberate init probe failure is genuine ERROR", RelaisEngine.lastInitFailed)
    } finally {
      release.countDown()
      awaitStartupEnded()
      present.delete()
      RelaisModelProvisioner.resetPathCacheForTest()
    }
  }

  @Test fun `present weights initialize directly without entering provision`() {
    val present = File(ctx.cacheDir, "already-present.litertlm").apply { writeBytes(byteArrayOf(0)) }
    RelaisConfig.setModelPath(ctx, present.absolutePath)
    val provisioned = AtomicBoolean(false)
    val loading = AtomicBoolean(false)
    val probing = object : ContextWrapper(ctx) {
      override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        if (RelaisNodeProgress.phase == ProvisionPhase.RESOLVING) provisioned.set(true)
        return super.getSharedPreferences(name, mode)
      }
      override fun getExternalFilesDir(type: String?): File? {
        if (RelaisNodeProgress.phase == ProvisionPhase.LOADING_ENGINE) {
          loading.set(true)
          throw ProbeStop()
        }
        return super.getExternalFilesDir(type)
      }
    }
    try {
      RelaisEngine.ensureInitializedInBackground(probing)
      awaitStartupEnded()
      assertTrue(loading.get())
      assertFalse("existing weights must take the direct init path", provisioned.get())
    } finally {
      present.delete()
    }
  }

  @Test fun `thread start Error rolls back lazy liveness and leaves guard available for retry`() {
    RelaisLivenessState.publishIdleUnloaded(true)
    RelaisEngine.lastInitFailed = true
    RelaisNodeProgress.onDownloadProgress(10, 100)
    RelaisEngine.startBackgroundStartup = { _, _ -> throw OutOfMemoryError("no native thread") }
    RelaisEngine.ensureInitializedInBackground(ctx) // Error must not escape an HTTP/UI caller
    assertFalse(RelaisLivenessState.snapshot.startupInProgress)
    assertTrue("a dispatch failure is not an attempted reload", RelaisLivenessState.snapshot.idleUnloaded)
    assertTrue("the previous init outcome must be preserved", RelaisEngine.lastInitFailed)
    assertEquals(ProvisionPhase.IDLE, RelaisNodeProgress.phase)
    assertEquals(0L, RelaisNodeProgress.downloadReceivedBytes)
    var dispatched = false
    RelaisEngine.startBackgroundStartup = { _, _ -> dispatched = true; throw OutOfMemoryError("again") }
    RelaisEngine.ensureInitializedInBackground(ctx)
    assertTrue("the failed dispatch must release single-flight", dispatched)
  }

  @Test fun `STOP between dispatch and worker entry skips presence and provision`() {
    var queued: (() -> Unit)? = null
    RelaisEngine.startBackgroundStartup = { _, work -> queued = work }
    val accessed = AtomicBoolean(false)
    val probing = object : ContextWrapper(ctx) {
      override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        accessed.set(true)
        throw ProbeStop()
      }
    }
    RelaisEngine.ensureInitializedInBackground(probing)
    assertTrue(RelaisLivenessState.snapshot.startupInProgress)
    RelaisEngine.shutdown()
    requireNotNull(queued).invoke()
    assertFalse("STOP must be checked before any model resolution", accessed.get())
    assertFalse(RelaisLivenessState.snapshot.startupInProgress)
    assertFalse(RelaisEngine.lastInitFailed)
  }

  @Test fun `provision dispatch Error rolls back idle and init outcome and answers retryable absence`() {
    RelaisLivenessState.publishIdleUnloaded(true)
    RelaisLivenessState.publishListenersUp(true)
    RelaisEngine.startBackgroundStartup = { _, _ -> throw OutOfMemoryError("no native thread") }
    val retry = assertThrows(ModelNotOnDiskException::class.java) {
      RelaisEngine.generate(ctx, RelaisRequest("hello"), onToken = {})
    }
    assertFalse("no startup was actually dispatched", retry.provisioning)
    assertTrue("a failed dispatch must restore healthy idle", RelaisLivenessState.snapshot.idleUnloaded)
    assertFalse("no init/provision attempt ran, so do not invent ERROR", RelaisEngine.lastInitFailed)
    assertFalse(RelaisLivenessState.snapshot.startupInProgress)
    assertEquals(ProvisionPhase.IDLE, RelaisNodeProgress.phase)
  }

  private fun provisioningProbe(
    entered: CountDownLatch,
    release: CountDownLatch,
    calls: AtomicInteger,
    failProvision: Boolean = true,
    loading: AtomicBoolean = AtomicBoolean(),
    startupInsideInit: AtomicBoolean = AtomicBoolean(),
  ): ContextWrapper = object : ContextWrapper(ctx) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
      if (RelaisNodeProgress.phase == ProvisionPhase.RESOLVING && calls.getAndIncrement() == 0) {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "provision probe was not released" }
        if (failProvision) throw ProbeStop()
      }
      return super.getSharedPreferences(name, mode)
    }
    override fun getExternalFilesDir(type: String?): File? {
      if (RelaisNodeProgress.phase == ProvisionPhase.LOADING_ENGINE) {
        loading.set(true)
        startupInsideInit.set(RelaisLivenessState.snapshot.startupInProgress)
        throw ProbeStop()
      }
      return super.getExternalFilesDir(type)
    }
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

/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package cc.grepon.relais

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.core.NodeState
import cc.grepon.relais.core.computeNodeState
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real native-engine probe for #368's shared background reload (HTTP/widget/quick-tile/poll).
 *
 * adb shell am instrument -w -e class cc.grepon.relais.BackgroundReloadProbe \
 *   -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 * # adb logcat -s RelaisBackgroundReloadProbe:*
 *
 * Run unlocked on the spare device with the configured model already provisioned. Each test starts
 * the real service, cancels its watchdog, and synthetically idle-releases the native engine. It
 * RENAMES (never deletes) the unloaded weights to a unique adjacent backup, then pauses the reload
 * worker at ensureModel's first preferences access after RESOLVING. The original path is restored
 * BEFORE that worker resumes: ensureModel takes its offline adoption path, so no download is needed.
 * Tests verify both native recovery and a real service STOP during provisioning. A finally block
 * restores the weights and prior run intent; restoration collisions fail with the backup path and
 * abort the worker before it could download. Do not interrupt instrumentation while weights are moved.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundReloadProbe {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun missingWeightsProvisionThenReloadAndInfer() = withPausedProvision { gate, original, backup ->
    assertEquals("missing weights must read STARTING during provisioning", NodeState.STARTING, nodeState())
    assertFalse("absence must not be published as an init failure", RelaisEngine.lastInitFailed)
    assertEquals(ProvisionPhase.RESOLVING, RelaisNodeProgress.phase)
    restoreWeights(original, backup)
    gate.release.countDown()
    await("background provisioning and native init to settle", 180_000) {
      RelaisEngine.isReady && !RelaisLivenessState.snapshot.startupInProgress
    }
    assertFalse(RelaisEngine.lastInitFailed)
    val tokenSeen = AtomicBoolean(false)
    RelaisEngine.generate(
      context,
      RelaisRequest(text = "Reply with a single word."),
      onToken = { tokenSeen.set(true) },
      shouldCancel = { tokenSeen.get() },
    )
    assertTrue("recovered native engine must produce a token", tokenSeen.get())
    Log.i(TAG, "PASS: missing weights entered provisioning, restored offline, native reload and inference succeeded")
  }

  @Test
  fun stopDuringProvisionDoesNotReinitialize() = withPausedProvision { gate, original, backup ->
    val epoch = RelaisEngine.currentShutdownEpoch()
    RelaisNodeService.stop(context)
    await("real service STOP to shut listeners and advance shutdown epoch", 30_000) {
      !RelaisConfig.shouldRun(context) && !RelaisLivenessState.snapshot.listenersUp &&
        RelaisEngine.currentShutdownEpoch() != epoch
    }
    restoreWeights(original, backup)
    gate.release.countDown()
    await("stopped background worker to settle", 30_000) {
      !RelaisLivenessState.snapshot.startupInProgress
    }
    assertFalse("STOP must prevent native reinitialization", RelaisEngine.isReady)
    assertFalse("STOP is not an init failure", RelaisEngine.lastInitFailed)
    assertEquals(NodeState.OFF, nodeState())
    Log.i(TAG, "PASS: real service STOP during provisioning prevented reinit and left OFF without failure")
  }

  private fun withPausedProvision(test: (ProvisionGate, File, File) -> Unit) {
    assumeTrue("Pass -e RELAIS_PROBE 1", InstrumentationRegistry.getArguments().getString("RELAIS_PROBE") == "1")
    val modelId = RelaisConfig.modelId(context)
    val path = RelaisModelProvisioner.pathFor(context, modelId)
    assumeTrue("Configured weights must already exist", path != null && File(path).isFile && File(path).length() > 0L)
    val original = File(requireNotNull(path))
    val backup = File(original.parentFile, "${original.name}.background-reload-probe-${UUID.randomUUID()}.backup")
    val wasRunning = RelaisConfig.shouldRun(context)
    val gate = ProvisionGate(context)
    var moved = false
    try {
      RelaisNodeService.start(context)
      await("real node startup", 180_000) {
        RelaisEngine.isReady && RelaisLivenessState.snapshot.listenersUp &&
          !RelaisLivenessState.snapshot.startupInProgress
      }
      RelaisWatchdog.cancel(context)
      assertTrue("synthetic idle release must unload weights before rename", RelaisEngine.releaseIfIdle(1_000, System.currentTimeMillis() + 120_000))
      assertEquals(NodeState.IDLE, nodeState())
      check(!backup.exists()) { "Backup collision: ${backup.absolutePath}" }
      check(original.renameTo(backup)) { "Cannot recoverably rename ${original.absolutePath} to ${backup.absolutePath}" }
      moved = true
      assertTrue(
        "resolved configured weights must be genuinely absent before kick",
        configuredModelMissing(RelaisEngine.isReady, RelaisModelProvisioner.pathFor(context, modelId)) { File(it).exists() },
      )
      RelaisEngine.ensureInitializedInBackground(gate)
      assertTrue("background worker did not reach ensureModel RESOLVING", gate.entered.await(20, TimeUnit.SECONDS))
      test(gate, original, backup)
    } finally {
      try {
        if (moved) restoreWeights(original, backup)
      } catch (failure: Throwable) {
        gate.abort = failure
        throw failure
      } finally {
        gate.release.countDown()
        // Drain the worker before restoring service intent, so a restart cannot race its epoch.
        try {
          await("probe background worker cleanup", 180_000) { !RelaisLivenessState.snapshot.startupInProgress }
        } finally {
          RelaisNodeService.stop(context)
          await("probe service cleanup", 30_000) { !RelaisLivenessState.snapshot.listenersUp }
          if (wasRunning) RelaisNodeService.start(context) // also restores the watchdog schedule
        }
      }
    }
  }

  private fun restoreWeights(original: File, backup: File) {
    if (!backup.exists()) {
      check(original.isFile) { "Weights missing at both ${original.absolutePath} and ${backup.absolutePath}" }
      return
    }
    check(!original.exists()) { "Restore collision: original exists; backup preserved at ${backup.absolutePath}" }
    check(backup.renameTo(original)) { "Restore failed; weights preserved at ${backup.absolutePath}" }
    check(original.isFile && original.length() > 0L) { "Restored weights invalid at ${original.absolutePath}" }
  }

  private class ProvisionGate(base: Context) : ContextWrapper(base) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val paused = AtomicBoolean(false)
    @Volatile var abort: Throwable? = null

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
      if (Thread.currentThread().name == "relais-idle-reload" &&
        RelaisNodeProgress.phase == ProvisionPhase.RESOLVING && paused.compareAndSet(false, true)
      ) {
        entered.countDown()
        check(release.await(180, TimeUnit.SECONDS)) { "Provision gate timed out; refusing any download" }
        abort?.let { throw IllegalStateException("Weights restoration failed; refusing any download", it) }
      }
      return super.getSharedPreferences(name, mode)
    }
  }

  private fun nodeState(): NodeState {
    val live = RelaisLivenessState.snapshot
    return computeNodeState(
      shouldRun = RelaisConfig.shouldRun(context), ready = RelaisEngine.isReady,
      listenersUp = live.listenersUp, startupInProgress = live.startupInProgress,
      lastInitFailed = RelaisEngine.lastInitFailed, thermalStatus = ThermalGovernor.statusValue,
      idleUnloaded = live.idleUnloaded,
    )
  }

  private fun await(description: String, timeoutMs: Long, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    assertTrue("Timed out waiting for $description", condition())
  }

  private companion object {
    const val TAG = "RelaisBackgroundReloadProbe"
  }
}

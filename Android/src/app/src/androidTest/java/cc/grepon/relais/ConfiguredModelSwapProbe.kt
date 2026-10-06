/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package cc.grepon.relais

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
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
 * Real resident-X -> missing-configured-Y provisioning and native swap probe for #369.
 *
 * adb shell am instrument -w -e class cc.grepon.relais.ConfiguredModelSwapProbe \
 *   -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 * # adb logcat -s RelaisConfiguredSwapProbe:*
 *
 * Requires an unlocked spare device with a real configured ref/path and a registered local
 * device/gemma3-1b-it-int4 model. Each test starts Gemma3 X, selects the original configured Y,
 * RENAMES only Y's unloaded file to a unique adjacent backup, and pauses the real swap worker
 * during RESOLVING. X stays resident until the test restores Y BEFORE resuming provisioning.
 * This exercises offline adoption, not a fresh download. No model is fabricated or downloaded.
 *
 * Before running, record original model prefs and Y's inode/size on the HOST. The BACKUP log records
 * the exact recovery path before any rename; if the app process dies, restore that adjacent file
 * before starting the app, then restore the recorded prefs. Do not interrupt while weights move.
 * Cleanup drains the worker and stops the service BEFORE restoring model preferences/registry.
 * Restoring collisions preserve the backup and abort the worker rather than allowing a download.
 * The host also owns reinstalling the pre-probe APK after verification; instrumentation cannot.
 */
@RunWith(AndroidJUnit4::class)
class ConfiguredModelSwapProbe {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun missingConfiguredWeightsProvisionThenSwapAndInfer() = withPausedProvision { gate, original, backup, yId ->
    assertResidentXWhileProvisioning()
    restoreWeights(original, backup)
    gate.release.countDown()
    await("configured native swap", 180_000) {
      RelaisEngine.isReady && RelaisEngine.residentModelId == yId &&
        !RelaisLivenessState.snapshot.startupInProgress
    }
    assertEquals(original.absolutePath, RelaisEngine.residentModelPath)
    assertFalse(RelaisEngine.lastInitFailed)
    generateOneToken()
    Log.i(TAG, "PASS: X remained resident during provisioning; restored Y adopted offline, swapped and generated")
  }

  @Test
  fun stopDuringConfiguredProvisionDoesNotReinitialize() = withPausedProvision { gate, original, backup, _ ->
    assertResidentXWhileProvisioning()
    val epoch = RelaisEngine.currentShutdownEpoch()
    RelaisNodeService.stop(context)
    await("real service STOP", 30_000) {
      !RelaisConfig.shouldRun(context) && !RelaisLivenessState.snapshot.listenersUp &&
        RelaisEngine.currentShutdownEpoch() != epoch
    }
    restoreWeights(original, backup)
    gate.release.countDown()
    await("stopped swap worker", 180_000) { !RelaisLivenessState.snapshot.startupInProgress }
    assertFalse("STOP must prevent native reinitialization", RelaisEngine.isReady)
    assertFalse("STOP is not an init failure", RelaisEngine.lastInitFailed)
    val live = RelaisLivenessState.snapshot
    assertEquals(
      NodeState.OFF,
      computeNodeState(
        shouldRun = RelaisConfig.shouldRun(context), ready = RelaisEngine.isReady,
        listenersUp = live.listenersUp, startupInProgress = live.startupInProgress,
        lastInitFailed = RelaisEngine.lastInitFailed, thermalStatus = ThermalGovernor.statusValue,
        idleUnloaded = live.idleUnloaded,
      ),
    )
    Log.i(TAG, "PASS: STOP during configured provisioning prevented stale handover/reinit and left OFF")
  }

  private fun withPausedProvision(test: (ProvisionGate, File, File, String) -> Unit) {
    assumeTrue("Pass -e RELAIS_PROBE 1", InstrumentationRegistry.getArguments().getString("RELAIS_PROBE") == "1")
    val originalPrefs = captureModelPrefs()
    val wasRunning = RelaisConfig.shouldRun(context)
    val ref = RelaisConfig.modelRef(context)
    val yId = RelaisConfig.modelId(context)
    val savedPath = RelaisConfig.modelPath(context)
    assumeTrue("Configured Y must have a matching real ref and persisted local path", ref != null && ref.modelId == yId && savedPath != null)
    val original = File(requireNotNull(savedPath))
    assumeTrue("Configured Y must already exist", original.isFile && original.length() > 0L)
    val yRef = requireNotNull(ref)
    val resolvedPath = RelaisModelProvisioner.modelFromRef(yRef).getPath(context)
    assumeTrue("Configured Y's ref must resolve to its original file", File(resolvedPath).canonicalPath == original.canonicalPath)
    val x = RelaisConfig.provisionedModels(context).firstOrNull { it.modelId == X_ID }
    assumeTrue("Registered X must be a different, present local model", x != null && x.modelId != yId && File(x.path).length() > 0L)
    val residentX = requireNotNull(x)
    val backup = File(original.parentFile, "${original.name}.configured-swap-probe-${UUID.randomUUID()}.backup")
    val originalLength = original.length()
    val originalInode = Os.stat(original.absolutePath).st_ino
    val gate = ProvisionGate(context)
    var moved = false
    var weightsRestored = false
    try {
      RelaisNodeService.stop(context)
      await("original service stop", 30_000) { !RelaisLivenessState.snapshot.listenersUp && !RelaisEngine.isReady }
      ModelSwitch.applyManualId(context, X_ID, residentX.path)
      RelaisNodeService.start(context)
      await("real X startup", 180_000) {
        RelaisEngine.isReady && RelaisEngine.residentModelId == X_ID &&
          RelaisLivenessState.snapshot.listenersUp && !RelaisLivenessState.snapshot.startupInProgress
      }
      RelaisWatchdog.cancel(context)
      generateOneToken()
      ModelSwitch.applyRef(context, yRef)
      check(!backup.exists()) { "Backup collision: ${backup.absolutePath}" }
      Log.i(TAG, "BACKUP: original=${original.absolutePath} backup=${backup.absolutePath} inode=$originalInode size=$originalLength")
      check(original.renameTo(backup)) { "Cannot recoverably rename ${original.absolutePath} to ${backup.absolutePath}" }
      moved = true
      assertEquals("rename must preserve the original file", originalInode, Os.stat(backup.absolutePath).st_ino)
      assertFalse("configured Y must be genuinely absent before dispatch", original.exists())
      assertTrue("configured swap must win dispatch", RelaisEngine.ensureModelSwapInBackground(gate))
      assertTrue("swap did not reach real provisioning gate", gate.entered.await(20, TimeUnit.SECONDS))
      assertEquals("provisioning must not replace X's resident path", residentX.path, RelaisEngine.residentModelPath)
      test(gate, original, backup, yId)
    } finally {
      try {
        if (moved) {
          restoreWeights(original, backup)
          check(original.length() == originalLength) { "Restored weights changed size: ${original.absolutePath}" }
          check(Os.stat(original.absolutePath).st_ino == originalInode) { "Restored weights changed inode: ${original.absolutePath}" }
        }
        weightsRestored = true
      } catch (failure: Throwable) {
        gate.abort = failure
        throw failure
      } finally {
        gate.release.countDown()
        // If dispatch fails before reaching the gate, also abort any later attempt to use it.
        gate.abort = gate.abort ?: IllegalStateException("Probe finished; refusing late provisioning")
        var workerDrained = false
        try {
          await("swap worker cleanup before model prefs restoration", 180_000) {
            !RelaisLivenessState.snapshot.startupInProgress
          }
          workerDrained = true
        } finally {
          // Even a drain timeout stops the temporary service, but must not restore preferences
          // underneath an outstanding worker. Host recovery handles that exceptional case.
          RelaisNodeService.stop(context)
          await("probe service cleanup", 30_000) { !RelaisLivenessState.snapshot.listenersUp && !RelaisEngine.isReady }
          if (workerDrained) {
            restoreModelPrefs(originalPrefs)
            RelaisModelProvisioner.resetPathCacheForTest()
            if (wasRunning && weightsRestored) RelaisNodeService.start(context)
          }
        }
      }
    }
  }

  private fun assertResidentXWhileProvisioning() {
    assertTrue("X must stay ready while Y provisions", RelaisEngine.isReady)
    assertEquals(X_ID, RelaisEngine.residentModelId)
    assertTrue(RelaisLivenessState.snapshot.startupInProgress)
    assertEquals(ProvisionPhase.RESOLVING, RelaisNodeProgress.phase)
    assertFalse("missing Y must not fail the resident engine", RelaisEngine.lastInitFailed)
  }

  private fun generateOneToken() {
    val tokenSeen = AtomicBoolean(false)
    RelaisEngine.generate(
      context, RelaisRequest(text = "Reply with a single word."),
      onToken = { tokenSeen.set(true) }, shouldCancel = { tokenSeen.get() },
    )
    assertTrue("native engine must generate a token", tokenSeen.get())
  }

  private fun captureModelPrefs(): Map<String, String?> = RelaisConfig.withModelSelection {
    val prefs = context.getSharedPreferences("relais", Context.MODE_PRIVATE)
    MODEL_KEYS.associateWith { prefs.getString(it, null) }
  }

  private fun restoreModelPrefs(values: Map<String, String?>) = RelaisConfig.withModelSelection {
    val edit = context.getSharedPreferences("relais", Context.MODE_PRIVATE).edit()
    values.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putString(key, value) }
    check(edit.commit()) { "Cannot restore original model preferences" }
  }

  private fun restoreWeights(original: File, backup: File) {
    if (!backup.exists()) {
      check(original.isFile && original.length() > 0L) { "Weights missing at ${original.absolutePath} and ${backup.absolutePath}" }
      return
    }
    check(!original.exists()) { "Restore collision: backup preserved at ${backup.absolutePath}" }
    check(backup.renameTo(original)) { "Restore failed: backup preserved at ${backup.absolutePath}" }
    check(original.isFile && original.length() > 0L) { "Restored weights invalid at ${original.absolutePath}" }
  }

  private class ProvisionGate(base: Context) : ContextWrapper(base) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val paused = AtomicBoolean(false)
    @Volatile var abort: Throwable? = null

    // Snapshot provisioning needs no prefs re-read before its fast paths. Pause at model.getPath's
    // real Context access instead, outside RelaisConfig's selection monitor, before file/sibling
    // checks. STOP must be free to acquire all production locks while this worker is paused.
    override fun getExternalFilesDir(type: String?): File? {
      if (Thread.currentThread().name == "relais-model-swap" &&
        RelaisNodeProgress.phase == ProvisionPhase.RESOLVING && paused.compareAndSet(false, true)
      ) {
        entered.countDown()
        check(release.await(180, TimeUnit.SECONDS)) { "Provision gate timed out; refusing a download" }
        abort?.let { throw IllegalStateException("Probe aborted; refusing a download", it) }
      }
      return super.getExternalFilesDir(type)
    }
  }

  private fun await(description: String, timeoutMs: Long, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    assertTrue("Timed out waiting for $description", condition())
  }

  private companion object {
    const val TAG = "RelaisConfiguredSwapProbe"
    const val X_ID = "device/gemma3-1b-it-int4"
    val MODEL_KEYS = listOf("model_id", "model_ref", "model_path", "provisioned_models")
  }
}

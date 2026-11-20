/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package cc.grepon.relais

import android.content.Context
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real resident-X -> configured-but-missing-Y probe for #369 (truthful 503, no swap, no download).
 *
 * adb shell am instrument -w -e class cc.grepon.relais.ConfiguredModelSwapProbe \
 *   -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 * # adb logcat -s RelaisConfiguredSwapProbe:*
 *
 * Requires an unlocked spare device with a real configured ref/path and a registered local
 * device/gemma3-1b-it-int4 model. The test starts Gemma3 X, selects the original configured Y, then
 * RENAMES only Y's unloaded file to a unique adjacent backup. With Y absent and X resident,
 * [resolveModelRequest] must answer [ModelRequestOutcome.NotOnDevice] on every retry: no swap is
 * dispatched, no provisioning or download starts, and X stays resident and serving. Nothing is
 * fabricated or downloaded.
 *
 * Before running, record original model prefs and Y's inode/size on the HOST. The BACKUP log records
 * the exact recovery path before any rename; if the app process dies, restore that adjacent file
 * before starting the app, then restore the recorded prefs. Do not interrupt while weights move.
 * The host also owns reinstalling the pre-probe APK after verification; instrumentation cannot.
 *
 * Compile-only in CI (`compileDebugAndroidTestKotlin`); never run on a device by the pipeline.
 */
@RunWith(AndroidJUnit4::class)
class ConfiguredModelSwapProbe {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun missingConfiguredModelAnswersNotOnDeviceInsteadOfSwapping() = withMissingConfiguredModel { yId ->
    repeat(RETRIES) { attempt ->
      val outcome = requestOutcome(yId)
      assertEquals("retry $attempt must stay NotOnDevice, never a swap", ModelRequestOutcome.NotOnDevice(yId), outcome)
      assertEquals(X_ID, RelaisEngine.residentModelId)
      assertTrue("X must stay ready", RelaisEngine.isReady)
      assertFalse("no swap may be started by a client request", RelaisLivenessState.snapshot.startupInProgress)
      assertFalse("no provisioning may start", RelaisNodeProgress.phase == ProvisionPhase.RESOLVING)
      assertFalse(RelaisEngine.lastInitFailed)
    }
    assertTrue(notOnDeviceModelMessage(yId).contains("not on this device"))
    generateOneToken()
    Log.i(TAG, "PASS: $RETRIES retries answered NotOnDevice; X stayed resident and generated; no swap, no download")
  }

  @Test
  fun resolvedRequestForResidentModelStillServes() = withMissingConfiguredModel { _ ->
    // X is the resident model: naming it must keep serving even though configured Y is missing.
    assertEquals(ModelRequestOutcome.ServeResident, requestOutcome(X_ID))
    Log.i(TAG, "PASS: resident X still served while configured Y is missing")
  }

  private fun requestOutcome(requestedModelId: String): ModelRequestOutcome {
    // Same read order as RelaisHttpServer: startupInProgress BEFORE isReady.
    val loadInFlight = RelaisLivenessState.snapshot.startupInProgress
    val ready = RelaisEngine.isReady
    val configuredId = RelaisConfig.modelId(context)
    val onDisk = RelaisConfig.provisionedModels(context).filter { File(it.path).exists() }
    val configuredOnDisk = RelaisModelProvisioner.pathFor(context, configuredId)?.let { File(it).exists() } == true
    return resolveModelRequest(
      residentModelId = RelaisEngine.residentModelId,
      requestedModelId = requestedModelId,
      configuredModelId = configuredId,
      provisionedModelIds = onDisk.map { it.modelId }.toSet(),
      isReady = ready,
      loadInFlight = loadInFlight,
      configuredModelOnDisk = configuredOnDisk,
    )
  }

  private fun withMissingConfiguredModel(test: (String) -> Unit) {
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
    val x = RelaisConfig.provisionedModels(context).firstOrNull { it.modelId == X_ID }
    assumeTrue("Registered X must be a different, present local model", x != null && x.modelId != yId && File(x.path).length() > 0L)
    val residentX = requireNotNull(x)
    val backup = File(original.parentFile, "${original.name}.configured-swap-probe-${UUID.randomUUID()}.backup")
    val originalLength = original.length()
    val originalInode = Os.stat(original.absolutePath).st_ino
    var moved = false
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
      assertFalse("configured Y must be genuinely absent", original.exists())
      test(yId)
    } finally {
      try {
        if (moved) restoreWeights(original, backup)
        if (moved) {
          check(original.length() == originalLength) { "Restored weights changed size: ${original.absolutePath}" }
          check(Os.stat(original.absolutePath).st_ino == originalInode) { "Restored weights changed inode: ${original.absolutePath}" }
        }
      } finally {
        try {
          await("no worker running before model prefs restoration", 180_000) {
            !RelaisLivenessState.snapshot.startupInProgress
          }
        } finally {
          RelaisNodeService.stop(context)
          await("probe service cleanup", 30_000) { !RelaisLivenessState.snapshot.listenersUp && !RelaisEngine.isReady }
          restoreModelPrefs(originalPrefs)
          RelaisModelProvisioner.resetPathCacheForTest()
          if (wasRunning) RelaisNodeService.start(context)
        }
      }
    }
  }

  private fun generateOneToken() {
    val tokenSeen = AtomicBoolean(false)
    RelaisEngine.generate(
      context, RelaisRequest(text = "Reply with a single word."),
      onToken = { tokenSeen.set(true) }, shouldCancel = { tokenSeen.get() },
    )
    assertTrue("native engine must generate a token", tokenSeen.get())
  }

  private fun captureModelPrefs(): Map<String, String?> {
    val prefs = context.getSharedPreferences("relais", Context.MODE_PRIVATE)
    return MODEL_KEYS.associateWith { prefs.getString(it, null) }
  }

  private fun restoreModelPrefs(values: Map<String, String?>) {
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

  private fun await(description: String, timeoutMs: Long, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    assertTrue("Timed out waiting for $description", condition())
  }

  private companion object {
    const val TAG = "RelaisConfiguredSwapProbe"
    const val X_ID = "device/gemma3-1b-it-int4"
    const val RETRIES = 3
    val MODEL_KEYS = listOf("model_id", "model_ref", "model_path", "provisioned_models")
  }
}

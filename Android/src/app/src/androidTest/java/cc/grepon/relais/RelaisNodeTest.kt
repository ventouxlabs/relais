/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.grepon.relais

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.BackendSelector
import cc.grepon.relais.RelaisBackend
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.RelaisModelProvisioner
import cc.grepon.relais.RelaisNodeService
import cc.grepon.relais.RelaisRequest
import cc.grepon.relais.RequestModalities
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate validation for the Relais node. Results under logcat tag "RelaisBench".
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.RelaisNodeTest \
 *     com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class RelaisNodeTest {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()

  /**
   * Starts the node and keeps the process + foreground service + endpoint alive for `holdSeconds`
   * (default 600). Lets a host driver validate G3 (LAN curl) and G2 (forced-Doze survival) against
   * a live service. Run in the background:
   *   adb shell am instrument -w -e class ...#holdNode -e holdSeconds 480 ...
   */
  @Test
  fun holdNode() {
    val modelPath = RelaisEngine.defaultModelPath(context)
    assumeTrue("Model not present at $modelPath", File(modelPath).exists())
    val binder = startAndBindService()
    val readyDeadline = System.currentTimeMillis() + 180_000
    while (!binder.isReady && System.currentTimeMillis() < readyDeadline) Thread.sleep(2000)
    assertTrue("engine not ready", binder.isReady)
    val holdSeconds = args.getString("holdSeconds")?.toLongOrNull() ?: 600L
    Log.i(TAG, "HOLD node ready (endpoint :8080); holding ${holdSeconds}s for G3/G2 validation")
    Thread.sleep(holdSeconds * 1000)
    Log.i(TAG, "HOLD window ended")
    // Intentionally do not unbind/stop: leave the started foreground service running.
  }

  /** G4 — modality-aware selector logic (pure, no device needed). */
  @Test
  fun g4_modalityAwareSelector() {
    // audio always -> GPU, even when AICore is available
    assertEquals(
      RelaisBackend.GPU_LITERTLM,
      BackendSelector.select(RequestModalities(hasImage = false, hasAudio = true), aicoreAvailable = true),
    )
    assertEquals(
      RelaisBackend.GPU_LITERTLM,
      BackendSelector.select(RequestModalities(hasImage = true, hasAudio = true), aicoreAvailable = true),
    )
    // image/text + AICore available -> NPU
    assertEquals(
      RelaisBackend.NPU_AICORE,
      BackendSelector.select(RequestModalities(hasImage = true, hasAudio = false), aicoreAvailable = true),
    )
    assertEquals(
      RelaisBackend.NPU_AICORE,
      BackendSelector.select(RequestModalities(hasImage = false, hasAudio = false), aicoreAvailable = true),
    )
    // image/text + no AICore -> GPU
    assertEquals(
      RelaisBackend.GPU_LITERTLM,
      BackendSelector.select(RequestModalities(hasImage = true, hasAudio = false), aicoreAvailable = false),
    )
    // This device (Pixel 9): AICore must be unavailable -> everything resolves to GPU. UNVERIFIED on Pixel 10.
    assertFalse("AICore must be gated off on this device", BackendSelector.aicoreAvailable(context))
    Log.i(TAG, "G4 selector OK; aicoreAvailable(this device)=${BackendSelector.aicoreAvailable(context)}")
  }

  /**
   * NPU/AICore path: runs the real Gemini-Nano path on a Pixel 10+; auto-skips as UNVERIFIED on
   * this Pixel 9 (excluded from AICore device groups). This is how the deferred gate closes once a
   * Pixel 10 is connected — no code change, just a device that passes the probe.
   */
  @Test
  fun g4b_npuAicorePathOrSkip() {
    val available = cc.grepon.relais.RelaisAicore.available(context)
    Log.i(TAG, "AICore/NPU available=$available on ${Build.MODEL}")
    assumeTrue("AICore/NPU UNVERIFIED here (Pixel 9 excluded); runs on Pixel 10+", available)
    // Pixel 10+ only below:
    assertEquals(
      "image/text must route to NPU when AICore is available",
      RelaisBackend.NPU_AICORE,
      BackendSelector.select(RequestModalities(hasImage = true, hasAudio = false), aicoreAvailable = true),
    )
    val out = cc.grepon.relais.RelaisAicore.generate(RelaisRequest(text = "Reply with one word: ping"))
    Log.i(TAG, "NPU(AICore) text -> \"${out.take(60)}\"")
    assertTrue("empty NPU response", out.isNotBlank())
  }

  /**
   * Self-provisioning — resolves the configured model from the allowlist and, if it is **not**
   * already on disk, downloads it via the real [DownloadWorker] path and confirms the engine
   * initializes from the freshly downloaded file. Skips (does not re-download) when the model is
   * already present, so it never deletes or refetches a multi-GB file in CI.
   *
   *   adb shell am instrument -w -e class \
   *     cc.grepon.relais.RelaisNodeTest#g_provisionDownloadsModel \
   *     com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
   */
  @Test
  fun g_provisionDownloadsModel() {
    val model = RelaisModelProvisioner.resolveModel(context)
    val path = model.getPath(context)
    Log.i(TAG, "Provision resolved ${model.name} url=${model.url} -> $path")
    assumeTrue("Model already present at $path — skipping download", !File(path).exists())

    val out = RelaisModelProvisioner.ensureModel(context) { pct -> Log.i(TAG, "download $pct%") }
    assertTrue("provisioned file missing at $out", File(out).exists())

    RelaisEngine.ensureInitialized(context, out, RelaisConfig.modelId(context))
    assertTrue("engine not ready after provisioning from $out", RelaisEngine.isReady)
    Log.i(TAG, "Provisioned + engine ready from $out")
  }

  /** G1 — resident multimodal engine hosted IN the foreground service; text+image+audio; decode>4 tok/s. */
  @Test
  fun g1_residentMultimodalViaService() {
    val modelPath = RelaisEngine.defaultModelPath(context)
    assumeTrue("Model not present at $modelPath", File(modelPath).exists())

    val binder = startAndBindService()
    try {
      // Wait for the resident engine to come up inside the service.
      val readyDeadline = System.currentTimeMillis() + 180_000
      while (!binder.isReady && System.currentTimeMillis() < readyDeadline) Thread.sleep(2000)
      assertTrue("Resident engine did not become ready in the service", binder.isReady)
      Log.i(TAG, "G1 service engine resident=${binder.isReady}")

      val text = binder.generate(RelaisRequest(text = "Reply with exactly one word: ping"))
      Log.i(TAG, "G1 TEXT  -> \"${text.text.take(60)}\"  ${text.decodeTokensPerSec} tok/s  ${text.backend}")
      assertTrue("empty text response", text.text.isNotBlank())

      val image =
        binder.generate(RelaisRequest(text = "What color fills this image? One word.", imagePng = redPng()))
      Log.i(TAG, "G1 IMAGE -> \"${image.text.take(80)}\"  ${image.decodeTokensPerSec} tok/s")
      assertTrue("empty image response", image.text.isNotBlank())

      val audio = binder.generate(RelaisRequest(text = "Describe this audio briefly.", audioWav = sineWav(1)))
      Log.i(TAG, "G1 AUDIO -> \"${audio.text.take(80)}\"  backend=${audio.backend}")
      assertTrue("empty audio response", audio.text.isNotBlank())
      assertEquals("audio must route to GPU", RelaisBackend.GPU_LITERTLM, audio.backend)

      // Throughput floor on a long-enough generation (a 1-word reply decodes too few tokens to rate).
      val perf =
        binder.generate(
          RelaisRequest(text = "Write a detailed paragraph of at least 80 words about tide pools.")
        )
      val decode = perf.decodeTokensPerSec
      Log.i(TAG, "G1 PERF decode=$decode tok/s over ${perf.text.length} chars (floor 4.0)")
      assertTrue("decode $decode below floor 4.0 — regression off GPU", decode > 4.0)
    } finally {
      context.unbindService(connection!!)
    }
  }

  // --- service bind plumbing ---

  private var connection: ServiceConnection? = null

  private fun startAndBindService(): RelaisNodeService.LocalBinder {
    RelaisNodeService.start(context)
    val latch = CountDownLatch(1)
    var bound: RelaisNodeService.LocalBinder? = null
    val conn =
      object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
          bound = service as RelaisNodeService.LocalBinder
          latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {}
      }
    connection = conn
    context.bindService(Intent(context, RelaisNodeService::class.java), conn, Context.BIND_AUTO_CREATE)
    assertTrue("service did not bind", latch.await(20, TimeUnit.SECONDS))
    return bound!!
  }

  // --- generated test media (no bundled assets) ---

  private fun redPng(): ByteArray {
    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawColor(Color.RED)
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  }

  private fun sineWav(seconds: Int, freq: Int = 440, rate: Int = 16000): ByteArray {
    val n = seconds * rate
    val dataBytes = n * 2
    val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray()); buf.putInt(36 + dataBytes); buf.put("WAVE".toByteArray())
    buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
    buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
    buf.put("data".toByteArray()); buf.putInt(dataBytes)
    for (i in 0 until n) {
      buf.putShort((Math.sin(2.0 * Math.PI * freq * i / rate) * 0.6 * Short.MAX_VALUE).toInt().toShort())
    }
    return buf.array()
  }

  private companion object {
    const val TAG = "RelaisBench"
  }
}

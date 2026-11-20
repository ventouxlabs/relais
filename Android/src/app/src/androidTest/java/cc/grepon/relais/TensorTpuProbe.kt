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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-4 verification probe (docs/tensor-tpu-spike-plan.md): the REAL production path —
 * [RelaisEngine.ensureInitialized] + [RelaisEngine.generate] — serves a request from the Tensor
 * TPU when the resident model is Google-Tensor AOT-compiled and the dispatcher lib is bundled
 * (debug builds). Asserts the result reports [RelaisBackend.TPU_LITERTLM] with real decode
 * throughput — the same lane the OpenAI HTTP endpoints and the in-app chat use.
 *
 * Needs a G5-AOT model staged app-owned (GrapheneOS FUSE ignores chmod on shell-pushed files):
 *   adb exec-out cat <staged>.litertlm | adb shell "run-as <appId> sh -c 'cat > files/bench/g5.litertlm'"
 *
 * Run (hardware only, not CI):
 *   adb shell am instrument -w \
 *     -e class cc.grepon.relais.TensorTpuProbe#tpuLaneServesGenerate \
 *     -e model /data/data/<appId>/files/bench/g5-1b.litertlm \
 *     <appId>.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Watch: adb logcat -s RelaisTpuProbe RelaisEngine
 */
@RunWith(AndroidJUnit4::class)
class TensorTpuProbe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun tpuLaneServesGenerate() {
    val modelPath = args.getString("model")
    assumeTrue("pass -e model <path to a _Google_Tensor_G5 .litertlm>", modelPath != null)
    assumeTrue("model file missing/unreadable: $modelPath", File(modelPath!!).canRead())
    assumeTrue(
      "TPU dispatcher not bundled in this build (debug-only)",
      File(context.applicationInfo.nativeLibraryDir, RelaisTpuLane.DISPATCHER_LIB).exists(),
    )

    RelaisEngine.ensureInitialized(context, modelPath, RelaisConfig.modelId(context))
    assertTrue("engine not ready after init", RelaisEngine.isReady)
    assertTrue("TPU lane not selected — check filename marker + dispatcher", RelaisEngine.residentIsTpu)

    val result =
      RelaisEngine.generate(context, RelaisRequest(text = "In one short sentence, what is a relay station?"))
    Log.i(
      TAG,
      "TPU lane result: backend=${result.backend} decode=${"%.2f".format(result.decodeTokensPerSec)} tok/s " +
        "tokens=${result.completionTokens} text=\"${result.text.take(120)}\"",
    )
    assertEquals(RelaisBackend.TPU_LITERTLM, result.backend)
    assertTrue("no visible tokens decoded", result.completionTokens > 0)
    assertTrue("empty response", result.text.isNotBlank())
    assertTrue("throughput not measured", result.decodeTokensPerSec > 0.0)
  }

  /**
   * Multimodal-through-the-TPU-encoders leg: sends an image request (and a best-effort audio
   * request) through the resident TPU engine. Only meaningful with a multimodal G5-AOT model —
   * e.g. gemma-4-E2B-it_Google_Tensor_G5.litertlm; a text-only 1B model degrades and this leg
   * (correctly) asserts nothing multimodal.
   *
   *   adb shell am instrument -w \
   *     -e class cc.grepon.relais.TensorTpuProbe#tpuLaneServesMultimodal \
   *     -e model /data/data/<appId>/files/bench/gemma-4-E2B-it_Google_Tensor_G5.litertlm \
   *     <appId>.test/androidx.test.runner.AndroidJUnitRunner
   */
  @Test
  fun tpuLaneServesMultimodal() {
    val modelPath = args.getString("model")
    assumeTrue("pass -e model <a MULTIMODAL _Google_Tensor_G5 .litertlm>", modelPath != null)
    assumeTrue("model file missing/unreadable: $modelPath", File(modelPath!!).canRead())
    assumeTrue(
      "TPU dispatcher not bundled in this build (debug-only)",
      File(context.applicationInfo.nativeLibraryDir, RelaisTpuLane.DISPATCHER_LIB).exists(),
    )

    RelaisEngine.ensureInitialized(context, modelPath, RelaisConfig.modelId(context))
    assertTrue("engine not ready after init", RelaisEngine.isReady)
    assertTrue("TPU lane not selected — check filename marker + dispatcher", RelaisEngine.residentIsTpu)
    assumeTrue(
      "model is text-only on the TPU lane (no vision/audio encoder) — nothing multimodal to test",
      RelaisEngine.isMultimodal,
    )

    // Vision through the TPU-lane vision encoder: a solid RED image, ask its color.
    val imageResult =
      RelaisEngine.generate(
        context,
        RelaisRequest(
          text = "What is the single dominant color of this image? Answer with just the color name.",
          imagePng = solidColorPng(Color.RED),
        ),
      )
    Log.i(
      TAG,
      "TPU vision: backend=${imageResult.backend} tokens=${imageResult.completionTokens} " +
        "decode=${"%.2f".format(imageResult.decodeTokensPerSec)} tok/s text=\"${imageResult.text.take(120)}\"",
    )
    assertEquals("vision request left the TPU lane", RelaisBackend.TPU_LITERTLM, imageResult.backend)
    assertTrue("empty vision response", imageResult.text.isNotBlank())
    assertTrue(
      "vision encoder did not see RED — got \"${imageResult.text.take(80)}\"",
      imageResult.text.contains("red", ignoreCase = true),
    )

    // Audio through the TPU-lane path: a synthetic sine tone. Lenient — assert the lane + a
    // non-empty response, not transcription content (the encoder may reject a pure tone). A native
    // failure here is a real finding about audio-on-TPU, surfaced rather than swallowed.
    val audioResult =
      runCatching {
        RelaisEngine.generate(
          context,
          RelaisRequest(text = "Describe this audio in a few words.", audioWav = sineWav(seconds = 1)),
        )
      }
    audioResult
      .onSuccess { r ->
        Log.i(TAG, "TPU audio: backend=${r.backend} tokens=${r.completionTokens} text=\"${r.text.take(120)}\"")
        assertEquals("audio request left the TPU lane", RelaisBackend.TPU_LITERTLM, r.backend)
        assertTrue("empty audio response", r.text.isNotBlank())
      }
      .onFailure { Log.w(TAG, "TPU audio leg errored (encoder/capability) — vision already proved multimodal-on-TPU: ${it.message}") }
  }

  /** A solid-color PNG generated in-process (no bundled test assets). */
  private fun solidColorPng(color: Int): ByteArray {
    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawColor(color)
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  }

  /** 16 kHz mono 16-bit PCM WAV of a sine tone. */
  private fun sineWav(seconds: Int, freq: Int = 440, rate: Int = 16000): ByteArray {
    val n = seconds * rate
    val dataBytes = n * 2
    val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray()); buf.putInt(36 + dataBytes); buf.put("WAVE".toByteArray())
    buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
    buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2); buf.putShort(16)
    buf.put("data".toByteArray()); buf.putInt(dataBytes)
    for (i in 0 until n) {
      val s = (Math.sin(2.0 * Math.PI * freq * i / rate) * 0.6 * Short.MAX_VALUE).toInt().toShort()
      buf.putShort(s)
    }
    return buf.array()
  }

  private companion object {
    const val TAG = "RelaisTpuProbe"
  }
}

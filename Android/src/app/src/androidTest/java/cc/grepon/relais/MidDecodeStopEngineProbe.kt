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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification for issue #165 — the native mid-decode cancel wired into the REAL serving
 * path (`RelaisEngine.generate`), not just the low-level litertlm API (`MidDecodeStopProbe` covers
 * that). Drives the resident engine exactly as the OpenAI HTTP endpoints and in-app chat do, then
 * trips the cooperative `shouldCancel` seam mid-stream and asserts the wiring holds:
 *
 *   1. `generate()` RETURNS (no thrown error) — the `onError("Process cancelled.")` terminal that
 *      `cancelProcess()` produces must be folded into a clean result, not surfaced as an error turn.
 *   2. `finishReason == "length"` — a thermal/cooperative cancel is a truncation, per issue #22.
 *   3. Decode HALTED early — `completionTokens` is a small multiple of the cancel threshold, far below
 *      `maxNumTokens` (4096). Without the wiring, native decode would run on to the token bound.
 *   4. It returned promptly (well under the 120 s inference timeout).
 *
 * Backend-agnostic: point `-e model` at either a generic GPU `.litertlm` or a `_Google_Tensor_G5`
 * AOT model (the TPU lane is picked automatically when the dispatcher lib is present). The underlying
 * cancel latency per lane is measured by `MidDecodeStopProbe`; this probe proves the RelaisEngine glue.
 *
 * Run (rango / Pixel 10 / G5 — adjust -e model to a staged path):
 *   adb shell am instrument -w -e class cc.grepon.relais.MidDecodeStopEngineProbe \
 *     -e model /storage/emulated/0/Android/data/<pkg>/files/bench/gemma-4-E2B-it_Google_Tensor_G5.litertlm \
 *     <pkg>.test/androidx.test.runner.AndroidJUnitRunner
 * Watch: adb logcat -s RelaisStopEngineProbe RelaisEngine
 */
@RunWith(AndroidJUnit4::class)
class MidDecodeStopEngineProbe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun cooperativeCancelHaltsResidentDecode() {
    val modelPath = args.getString("model")
    assumeTrue("pass -e model <path to a staged .litertlm>", modelPath != null)
    assumeTrue("model file missing/unreadable: $modelPath", File(modelPath!!).canRead())

    RelaisEngine.ensureInitialized(context, modelPath, RelaisConfig.modelId(context))
    assumeTrue("engine not ready after init", RelaisEngine.isReady)

    val streamed = AtomicInteger(0)
    // Trip the cooperative cancel once the stream is clearly under way — this is the same seam the
    // ThermalGovernor / client-disconnect paths use to ask for a stop.
    val shouldCancel = { streamed.get() >= CANCEL_AFTER_TOKENS }

    val startMs = System.currentTimeMillis()
    val result =
      RelaisEngine.generate(
        context,
        // A prompt that yields a long, steady stream so there's a decode to interrupt.
        RelaisRequest(
          text =
            "Write a long, detailed story about a lighthouse keeper, many paragraphs, at least a " +
              "thousand words. Do not stop early.",
        ),
        onToken = { streamed.incrementAndGet() },
        shouldCancel = shouldCancel,
      )
    val elapsedMs = System.currentTimeMillis() - startMs

    Log.i(
      TAG,
      "RESULT backend=${result.backend} finishReason=${result.finishReason} " +
        "completionTokens=${result.completionTokens} streamedToClient=${streamed.get()} " +
        "elapsedMs=$elapsedMs textLen=${result.text.length}",
    )

    // 1 + 2: returned cleanly (we're here, no throw) with the truncation finish_reason.
    assertEquals(
      "cooperative cancel must report finish_reason=length (truncated), not stop",
      RelaisFinishReason.LENGTH,
      result.finishReason,
    )
    // 3: decode halted near the cancel point — NOT run on to maxNumTokens (4096). Generous ceiling to
    // absorb in-flight slop + the tokens decoded before the threshold was crossed.
    assertTrue(
      "decode did not halt on cancel: completionTokens=${result.completionTokens} (expected << 4096)",
      result.completionTokens in 1..HALT_TOKEN_CEILING,
    )
    // 4: it came back promptly, nowhere near the 120 s inference timeout.
    assertTrue("cancel did not return promptly: ${elapsedMs}ms", elapsedMs < PROMPT_RETURN_CEILING_MS)
    Log.i(TAG, "VERDICT: PASS (RelaisEngine cooperative cancel halts native decode + returns length)")
  }

  private companion object {
    const val TAG = "RelaisStopEngineProbe"
    const val CANCEL_AFTER_TOKENS = 24
    // maxNumTokens is 4096; a working cancel stops within a small multiple of the threshold.
    const val HALT_TOKEN_CEILING = 200
    const val PROMPT_RETURN_CEILING_MS = 60_000L
  }
}

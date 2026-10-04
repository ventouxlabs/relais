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

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [ModelSwitch]: its persisted state (needs a real `Context`, since those
 * assertions are on what [RelaisConfig] reads back) and the post-pick reload observer, on virtual time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelSwitchTest {

  private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

  @Test
  fun `a manual pick of the ref's own id drops the ref build's persisted path`() {
    // One id can name two builds: the G5 TPU ref shares its id with the allowlist GPU build. Typing
    // that id by hand means "resolve this id via the allowlist", so the TPU file persisted for the
    // ref must go — otherwise the id is unchanged, setModelId keeps KEY_MODEL_PATH, and the restart
    // Configure asks for re-adopts the TPU file through ensureModel's first fast path.
    val tpu = RelaisModelCatalog.G5_TPU_REFS.first()
    RelaisConfig.setModelRef(ctx, tpu)
    RelaisConfig.setModelPath(ctx, "/data/models/${tpu.modelFile}")

    ModelSwitch.applyManualId(ctx, tpu.modelId, resolvedPath = null)

    assertEquals(tpu.modelId, RelaisConfig.modelId(ctx))
    assertNull(RelaisConfig.modelRef(ctx))
    assertNull(
      "the ref build's path must not survive the ref, or a restart serves that build again",
      RelaisConfig.modelPath(ctx),
    )
  }

  @Test
  fun `re-picking a plain id with no ref keeps its persisted path`() {
    // The twin that rules out "always clear": with no ref there is no other build to fall back from,
    // and dropping the path would force an allowlist fetch on the next boot — an offline node that
    // booted fine before the re-pick would then fail to start.
    val id = "acme/plain-litert-lm"
    RelaisConfig.setModelId(ctx, id)
    RelaisConfig.setModelPath(ctx, "/data/models/plain.litertlm")

    ModelSwitch.applyManualId(ctx, id, resolvedPath = null)

    assertEquals("/data/models/plain.litertlm", RelaisConfig.modelPath(ctx))
  }

  @Test
  fun `a manual pick of the default id counts as an explicit choice (#355)`() {
    // Fresh prefs: nothing written, so a Pixel 10 may substitute its G5 default.
    assertFalse(RelaisConfig.hasExplicitModelId(ctx))
    // The operator types the default E4B id. modelId() reads the same value before and after —
    // only explicitness distinguishes them, and deviceDefaultRef keys on it.
    ModelSwitch.applyManualId(ctx, RelaisConfig.DEFAULT_MODEL_ID, resolvedPath = null)
    assertEquals(RelaisConfig.DEFAULT_MODEL_ID, RelaisConfig.modelId(ctx))
    assertTrue(RelaisConfig.hasExplicitModelId(ctx))
  }

  // ---- observeReload (#364) ----

  @Test
  fun `a pick with nothing loading never shows reloading`() = runTest {
    // IDLE, OFF and ERROR: the engine is not resident and nothing is starting. A pick starts no load
    // (it applies on the next one), so nothing is reloading. Reading `!isReady` latched the flag true
    // here, and the chat screen's SEND (`canSend = !reloadingModel`) stayed disabled with it.
    assertFalse("precondition: a leaked startup from another test", RelaisLivenessState.snapshot.startupInProgress)
    assertFalse("precondition: no engine in a JVM test", RelaisEngine.isReady)
    val seen = mutableListOf<Boolean>()
    ModelSwitch.observeReload(backgroundScope) { seen += it }
    runCurrent()
    assertTrue("the observer must publish a value", seen.isNotEmpty())
    assertFalse("nothing is loading, so it must never read reloading: $seen", true in seen)
    advanceTimeBy(10 * ModelSwitch.RELOAD_POLL_INTERVAL_MS)
    assertFalse("and must not flip to reloading later: $seen", true in seen)
  }

  @Test
  fun `reloading follows a running startup past the old 60 s cap and clears when it settles`() = runTest {
    RelaisLivenessState.beginStartup() // some owner's load: the service's START, a swap, a request's reload
    var ended = false
    try {
      var reloading: Boolean? = null
      ModelSwitch.observeReload(backgroundScope) { reloading = it }
      runCurrent()
      assertEquals(true, reloading)
      // A startup can be a multi-GB download. The old wait gave up at 500 ms × 120 = 60 s and froze the
      // flag at its last reading, so it never cleared even after the load finished.
      advanceTimeBy(90_000)
      assertEquals("still loading at 90 s: still reloading", true, reloading)
      RelaisLivenessState.endStartup()
      ended = true
      advanceTimeBy(ModelSwitch.RELOAD_POLL_INTERVAL_MS + 1)
      // Settled with no engine resident (a failed load, or a startup that ended without one): the node
      // shows that as ERROR/IDLE elsewhere; the pick is not reloading any more.
      assertEquals("settled: not reloading", false, reloading)
    } finally {
      if (!ended) RelaisLivenessState.endStartup()
    }
  }
}

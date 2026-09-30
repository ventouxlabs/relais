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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [ModelSwitch]'s persisted state — needs a real `Context` because every
 * assertion is on what [RelaisConfig] reads back.
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
}

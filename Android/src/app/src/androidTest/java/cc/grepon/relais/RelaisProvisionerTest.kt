/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provisioner adoption guard: a model an operator pre-staged at [RelaisEngine.defaultModelPath]
 * (the conventional side-load location) must be adopted as-is, so a fresh install whose model is
 * already on disk boots LIVE instead of re-downloading multiple GB. Regression test for the bug
 * where [RelaisModelProvisioner.ensureModel] only checked the persisted and allowlist-resolved
 * paths and so re-downloaded a model that was already side-loaded.
 *
 * Hermetic + offline: fast path 1 is forced to miss by pointing the persisted path at a file that
 * does not exist, and the staged file makes fast path 2 hit before any allowlist fetch or download,
 * so this never touches the network.
 */
@RunWith(AndroidJUnit4::class)
class RelaisProvisionerTest {

  @Test
  fun adoptsPreStagedModelWithoutDownloading() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    // targetContext is the real app's prefs, so snapshot everything we touch and restore it in
    // finally — an operator's configured model id / path must survive this test unchanged.
    val savedModelId = RelaisConfig.modelId(ctx)
    // modelId() reads DEFAULT when nothing was written; writing it back would make the id explicit
    // and permanently turn off a Pixel 10's G5 default (#355). Restore absence as absence.
    val savedExplicit = RelaisConfig.hasExplicitModelId(ctx)
    val savedPath = RelaisConfig.modelPath(ctx)

    // Eligibility: fast path 2 is gated to the default model id.
    RelaisConfig.setModelId(ctx, RelaisConfig.DEFAULT_MODEL_ID)
    // Force fast path 1 (persisted path) to miss so adoption is what's under test.
    RelaisConfig.setModelPath(ctx, File(ctx.cacheDir, "no-such-model.litertlm").absolutePath)

    // Stage a model at the conventional side-load location. If the real model is already there
    // (a provisioned device), reuse it untouched; otherwise drop a tiny stand-in and clean it up.
    val staged = File(RelaisEngine.defaultModelPath(ctx))
    val preexisting = staged.exists()
    if (!preexisting) {
      staged.parentFile?.mkdirs()
      staged.writeBytes(byteArrayOf(0x00))
    }

    try {
      val resolved = RelaisModelProvisioner.ensureModel(ctx)
      assertEquals("staged model must be adopted, not re-downloaded", staged.absolutePath, resolved)
    } finally {
      if (!preexisting) staged.delete()
      // Restore id first (a change clears KEY_MODEL_PATH), then the path, so both end as found.
      RelaisConfig.setModelId(ctx, savedModelId)
      if (!savedExplicit) RelaisConfig.clearModelIdForTest(ctx)
      savedPath?.let { RelaisConfig.setModelPath(ctx, it) }
    }
  }
}

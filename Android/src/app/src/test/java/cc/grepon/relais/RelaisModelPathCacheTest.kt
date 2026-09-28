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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the WRITE side of the model-path cache (#337) — that
 * [RelaisModelProvisioner.remember] tags the cache with the id whose weights the file actually
 * holds, and that [RelaisModelProvisioner.pathFor] therefore refuses it for any other id.
 *
 * `RelaisModelPathResolutionTest` covers the pure resolution order device-free; this class exists
 * because the defect lived in the *pairing* of the writer's id with the reader's, which a pure test
 * cannot observe — the tag could be internally consistent and still be the wrong id.
 *
 * Needs a real `Context`: `remember` reads the configured id to run the issue-#11 drift gate, and
 * `pathFor` reads the registry and the path pref back out of `RelaisConfig`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelaisModelPathCacheTest {

  private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

  private companion object {
    const val OPERATOR_ID = "operator/typed-this"

    /**
     * The prefs read to flip the model id on: one call after `ensureModel`'s `idAtStart` capture
     * (modelRef, modelId, idAtStart, then modelPath for fast path 1). Asserted in the test, never
     * assumed — a read-order change must fail loudly rather than silently flipping too late.
     */
    const val FLIP_ON_PREFS_CALL = 4
  }

  /** A real file, because every resolution step below the default is gated on the file existing. */
  private fun stage(name: String): String =
    File(ctx.cacheDir, name).apply { parentFile?.mkdirs() }.also { it.writeText("weights") }.absolutePath

  @Before
  fun clearModelState() {
    // Deterministic start without reaching into RelaisConfig's private prefs: setting a fresh id
    // clears KEY_MODEL_PATH (that is setModelId's own documented behaviour on a change), and the
    // registry has a public setter.
    RelaisConfig.setModelId(ctx, "test/reset-${System.nanoTime()}")
    RelaisConfig.setProvisionedModels(ctx, emptyList())
  }

  @Test
  fun `a remembered path is served back for the id it was provisioned for`() {
    val path = stage("alpha.litertlm")
    RelaisConfig.setModelId(ctx, "alpha")
    RelaisModelProvisioner.remember(ctx, path, persistForId = "alpha")

    assertEquals(path, RelaisModelProvisioner.pathFor(ctx, "alpha"))
  }

  @Test
  fun `switching models does not serve the outgoing model's file under the new id`() {
    // The #337 defect, at the level it actually occurred: provision alpha, switch config to beta,
    // then ask for beta the way an idle reload does. The old bare cache returned alpha's file here.
    val alpha = stage("alpha.litertlm")
    RelaisConfig.setModelId(ctx, "alpha")
    RelaisModelProvisioner.remember(ctx, alpha, persistForId = "alpha")

    RelaisConfig.setModelId(ctx, "beta")

    assertNotEquals(
      "an idle reload for beta must not be handed alpha's weights",
      alpha,
      RelaisModelProvisioner.pathFor(ctx, "beta"),
    )
  }

  @Test
  fun `a path provisioned under drift is not served to the id that superseded it`() {
    // Issue #11's race: the operator switches models mid-download, so the download completes for an
    // id that is no longer configured. shouldPersistPath already refuses to PERSIST that path; the
    // cache is still written (it is what this boot fetched), so it must carry the id it was fetched
    // for or it becomes the same defect by another route.
    val superseded = stage("superseded.litertlm")
    RelaisConfig.setModelId(ctx, "incoming")
    RelaisModelProvisioner.remember(ctx, superseded, persistForId = "outgoing")

    assertNotEquals(
      "a path fetched for the outgoing id must not answer for the incoming one",
      superseded,
      RelaisModelProvisioner.pathFor(ctx, "incoming"),
    )
    assertEquals(
      "and it must still answer for the id it WAS fetched for",
      superseded,
      RelaisModelProvisioner.pathFor(ctx, "outgoing"),
    )
  }

  @Test
  fun `the staged default file is not adopted for a non-default id`() {
    // The window is an id change landing between ensureModel's `idAtStart` capture and the
    // staged-adoption gate. Driving it needs no concurrency: RelaisConfig.prefs is a plain per-call
    // getSharedPreferences, so every RelaisConfig.xxx(context) is one observable call on a wrapper,
    // and flipping the id from inside the Nth call is synchronously equivalent to a racing write.
    //
    // The dangerous direction is non-default -> default: a gate that re-read the id would see
    // DEFAULT and adopt, while `remember` tags with the captured `idAtStart`, binding the default
    // model's file to the operator's id. `pathFor`'s rung 1 then trusts that tag.
    val staged = File(RelaisEngine.defaultModelPath(ctx)).apply { parentFile?.mkdirs() }
    staged.writeText("default model weights")
    RelaisConfig.setModelId(ctx, OPERATOR_ID)
    // Fast path 1 must miss, or it returns before the staged branch is ever reached.
    RelaisConfig.setModelPath(ctx, File(ctx.cacheDir, "absent.litertlm").absolutePath)

    var prefsCalls = 0
    var flippedAt = -1
    val flipping = object : android.content.ContextWrapper(ctx) {
      override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
        val real = super.getSharedPreferences(name, mode)
        prefsCalls++
        // One call AFTER idAtStart is captured. Asserted below rather than trusted: if a future
        // read-order change moves the capture, the flip would land after the gate and this test
        // would go quietly vacuous instead of failing.
        if (prefsCalls == FLIP_ON_PREFS_CALL) {
          flippedAt = prefsCalls
          real.edit().putString("model_id", RelaisConfig.DEFAULT_MODEL_ID).commit()
        }
        return real
      }
    }

    try {
      // With the gate reading idAtStart this falls through to allowlist resolution, which cannot
      // reach the network under Robolectric. The throw is expected; the binding is the assertion.
      runCatching { RelaisModelProvisioner.ensureModel(flipping) }

      assertEquals("the flip must land after idAtStart is captured, not later", FLIP_ON_PREFS_CALL, flippedAt)
      assertEquals(
        "the id really did change mid-call, or the test proves nothing",
        RelaisConfig.DEFAULT_MODEL_ID,
        RelaisConfig.modelId(ctx),
      )
      assertNotEquals(
        "the default model's file must never be remembered under the operator's id",
        staged.absolutePath,
        RelaisModelProvisioner.pathFor(ctx, OPERATOR_ID),
      )
    } finally {
      staged.delete()
    }
  }

  @Test
  fun `a model still in the registry is found after the cache moves on`() {
    // The registry is the id-keyed fallback that makes a switch BACK cheap, and the reason a swap
    // target that was never the configured model can still be located.
    val alpha = stage("alpha.litertlm")
    val beta = stage("beta.litertlm")
    RelaisConfig.setModelId(ctx, "alpha")
    RelaisModelProvisioner.remember(ctx, alpha, persistForId = "alpha")
    RelaisConfig.setModelId(ctx, "beta")
    RelaisModelProvisioner.remember(ctx, beta, persistForId = "beta")
    assertEquals("alpha is still provisioned, so it resolves from the registry", alpha, RelaisModelProvisioner.pathFor(ctx, "alpha"))
    assertEquals(beta, RelaisModelProvisioner.pathFor(ctx, "beta"))
  }
}

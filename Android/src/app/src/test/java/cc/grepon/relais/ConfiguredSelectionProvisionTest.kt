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

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import cc.grepon.relais.data.RelaisModelRef
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The provisioner runs on ONE captured [ConfiguredModelSelection] (#369, design R1/R4).
 *
 * T1 holds `ensureModel` at its first prefs access AFTER the capture, flips the selection with the
 * real [ModelSwitch.applyRef], and releases. Nothing may leak across the flip: the return value is
 * the file for the captured model, and no cache, registry or prefs entry binds the OTHER model's
 * file to anything. Mutation check (done by hand, recorded in the PR): re-inserting a fresh
 * `RelaisConfig.modelId` read in `resolveModel` makes the return assertion fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfiguredSelectionProvisionTest {

  private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
  private val staged = mutableListOf<File>()

  private fun ref(id: String, file: String = "$id.litertlm", commit: String = "c1") =
    RelaisModelRef(id, file, commit, 7L, id, RelaisModelRef.SOURCE_HUGGINGFACE)

  /** Writes the file [ref] resolves to, so provisioning takes the "already present" branch. */
  private fun stageFor(ref: RelaisModelRef): File =
    File(RelaisModelProvisioner.modelFromRef(ref).getPath(ctx)).also {
      it.parentFile?.mkdirs()
      it.writeText("weights-${ref.modelId}")
      staged += it
    }

  @Before
  fun reset() {
    RelaisConfig.setModelId(ctx, "test/reset-${System.nanoTime()}")
    RelaisConfig.setProvisionedModels(ctx, emptyList())
    RelaisModelProvisioner.resetPathCacheForTest()
  }

  @After fun cleanup() = staged.forEach { it.delete() }

  /** Blocks the Nth RelaisConfig prefs access (before it returns, i.e. before any monitor). */
  private class HoldingContext(base: android.content.Context, private val holdOnCall: Int) :
    ContextWrapper(base) {
    val reached = CountDownLatch(1)
    val release = CountDownLatch(1)
    private var calls = 0

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
      if (name == "relais" && ++calls == holdOnCall) {
        reached.countDown()
        release.await(10, TimeUnit.SECONDS)
      }
      return super.getSharedPreferences(name, mode)
    }
  }

  @Test
  fun `T1 a pick landing mid-provision leaks nothing of the other model`() {
    val a = ref("acme/model-a")
    val b = ref("acme/model-b")
    val fileA = stageFor(a)
    val fileB = stageFor(b)
    ModelSwitch.applyRef(ctx, a)

    // Call 1 is the capture; call 2 is the first access after it.
    val holding = HoldingContext(ctx, holdOnCall = 2)
    val pool = Executors.newSingleThreadExecutor()
    try {
      val result = pool.submit<String> { RelaisModelProvisioner.ensureModel(holding) }
      assertTrue("provisioner never reached its post-capture access", holding.reached.await(10, TimeUnit.SECONDS))
      ModelSwitch.applyRef(ctx, b)
      holding.release.countDown()
      val returned = result.get(10, TimeUnit.SECONDS)

      assertEquals("the return value is the file for the CAPTURED selection", fileA.absolutePath, returned)
      assertNotEquals(fileB.absolutePath, RelaisModelProvisioner.pathFor(ctx, "acme/model-a"))
      assertNull("no cache entry answers for A", RelaisModelProvisioner.pathFor(ctx, "acme/model-a"))
      assertNull("B was never provisioned", RelaisModelProvisioner.pathFor(ctx, "acme/model-b"))
      assertEquals("drift publishes no registry entry", emptyList<ProvisionedModel>(), RelaisConfig.provisionedModels(ctx))
      assertNull("drift persists no path", RelaisConfig.modelPath(ctx))
    } finally {
      holding.release.countDown()
      pool.shutdownNow()
    }
  }

  @Test
  fun `a same-id different-build pick is drift and publishes nothing`() {
    val build1 = ref("acme/model-a", file = "g.litertlm", commit = "c1")
    val build2 = ref("acme/model-a", file = "t.litertlm", commit = "c2")
    val file1 = stageFor(build1)
    ModelSwitch.applyRef(ctx, build1)

    val holding = HoldingContext(ctx, holdOnCall = 2)
    val pool = Executors.newSingleThreadExecutor()
    try {
      val result = pool.submit<String> { RelaisModelProvisioner.ensureModel(holding) }
      assertTrue(holding.reached.await(10, TimeUnit.SECONDS))
      ModelSwitch.applyRef(ctx, build2)
      holding.release.countDown()
      assertEquals(file1.absolutePath, result.get(10, TimeUnit.SECONDS))
      assertEquals(emptyList<ProvisionedModel>(), RelaisConfig.provisionedModels(ctx))
      assertNull(RelaisConfig.modelPath(ctx))
    } finally {
      holding.release.countDown()
      pool.shutdownNow()
    }
  }

  @Test
  fun `publish acquires prefs only before the monitor never inside it`() {
    val a = ref("acme/model-a")
    val fileA = stageFor(a)
    ModelSwitch.applyRef(ctx, a)
    val selection = RelaisConfig.modelSelection(ctx)

    // Registry stat read (1) + ifCurrentSelection's handle (2). Any further access would be a
    // context-based accessor running while selectionLock is held.
    val counting = CountingContext(ctx)
    RelaisModelProvisioner.remember(counting, fileA.absolutePath, selection)

    assertEquals("no getSharedPreferences inside the selection monitor", 2, counting.calls)
    assertEquals(fileA.absolutePath, RelaisConfig.modelPath(ctx))
    assertEquals(1, RelaisConfig.provisionedModels(ctx).size)
  }

  private class CountingContext(base: android.content.Context) : ContextWrapper(base) {
    var calls = 0

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
      if (name == "relais") calls++
      return super.getSharedPreferences(name, mode)
    }
  }

  @Test
  fun `T3 pathFor takes configured id and persisted path from one capture`() {
    val a = ref("acme/model-a")
    val fileA = stageFor(a)
    // Config now holds B with its own staged path; the captured selection is still (A, fileA).
    RelaisConfig.setModelId(ctx, "acme/model-b")
    val fileB = File(ctx.cacheDir, "b-persisted.litertlm").also { it.writeText("b"); staged += it }
    RelaisConfig.setModelPath(ctx, fileB.absolutePath)
    val captured = ConfiguredModelSelection("acme/model-a", a, fileA.absolutePath)

    assertEquals(
      "A's persisted path answers for A, from the capture, not the live prefs",
      fileA.absolutePath,
      RelaisModelProvisioner.pathFor(ctx, "acme/model-a", captured),
    )
    assertNull(
      "the live B path must never answer for the captured-A selection's other ids",
      RelaisModelProvisioner.pathFor(ctx, "acme/model-b", captured),
    )
  }
}

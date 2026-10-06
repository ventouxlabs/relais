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

import android.content.Context
import cc.grepon.relais.data.RelaisModelRef
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Exercises the production orchestration; only the native handover and transfer boundary are fake. */
@RunWith(RobolectricTestRunner::class)
class RelaisModelSwapRunnerTest {
  private val ctx get() = RuntimeEnvironment.getApplication()
  private val ref = RelaisModelRef("test/y", "y.litertlm", "yyy", 1L, "Y", RelaisModelRef.SOURCE_HUGGINGFACE)
  private var epoch = 0L
  private var resident: String? = "X"
  private var queued: (() -> Unit)? = null
  private var provisions = 0
  private var handovers = 0
  private var publications = 0
  private val files = mutableListOf<File>()

  @After fun cleanup() {
    assertFalse("every branch must balance startup ownership", RelaisLivenessState.snapshot.startupInProgress)
    files.forEach { it.delete() }
    RelaisModelProvisioner.resetPathCacheForTest()
    RelaisNodeProgress.reset()
  }

  private fun file(name: String = "target.litertlm") = File(ctx.cacheDir, name).also {
    it.writeBytes(byteArrayOf(1)); files.add(it)
  }

  private fun runner(
    provision: (Context, ConfiguredModelSelection) -> String = { _, _ -> error("unexpected download") },
    launch: (() -> Unit) -> Unit = { queued = it },
  ) = RelaisModelSwapRunner(
    epoch = { epoch },
    handover = { context, path, id, originalEpoch, selection ->
      handovers++
      if (epoch != originalEpoch || selection != null && !RelaisConfig.isCurrentSelection(context, selection)) false
      else { resident = id; assertTrue(File(path).exists()); true }
    },
    publish = { publications++ },
    provision = { context, selection -> provisions++; provision(context, selection) },
    launch = launch,
  )

  @Test fun `missing configured Y provisions once while X remains resident then hands over the captured ID`() {
    RelaisConfig.setModelRef(ctx, ref)
    val missing = File(RelaisModelProvisioner.modelFromRef(ref).getPath(ctx))
    files.add(missing)
    assertFalse(missing.exists())
    val runner = runner(provision = { context, selection ->
      assertEquals("X", resident)
      assertTrue(RelaisLivenessState.snapshot.startupInProgress)
      assertEquals(ref.modelId, selection.modelId)
      missing.parentFile!!.mkdirs()
      missing.writeBytes(byteArrayOf(1))
      // Actual provisioner adoption/publication. This models transfer completion, not HTTP IO.
      RelaisModelProvisioner.ensureModel(context, selection)
    })
    assertTrue(runner.dispatch(ctx, null))
    repeat(5) { assertFalse(runner.dispatch(ctx, null)) }
    assertEquals("X", resident)
    assertTrue(RelaisLivenessState.snapshot.startupInProgress)
    requireNotNull(queued).invoke()
    assertEquals(1, provisions)
    assertEquals(1, handovers)
    assertEquals(1, publications)
    assertEquals(ref.modelId, resident)
    assertEquals(missing.path, RelaisConfig.modelPath(ctx))
  }

  @Test fun `provision failure retains X and releases ownership for a retry`() {
    RelaisConfig.setModelRef(ctx, ref)
    val runner = runner(provision = { _, _ -> throw OutOfMemoryError("transfer failed") })
    assertTrue(runner.dispatch(ctx, null))
    requireNotNull(queued).invoke()
    assertEquals("X", resident)
    assertEquals(0, handovers)
    assertEquals(0, publications)
    assertFalse(RelaisLivenessState.snapshot.startupInProgress)
    assertTrue(runner.dispatch(ctx, null))
    requireNotNull(queued).invoke()
    assertEquals(2, provisions)
  }

  @Test fun `STOP before the worker runs does not start provisioning or reinitialize`() {
    RelaisConfig.setModelRef(ctx, ref)
    val runner = runner()
    assertTrue(runner.dispatch(ctx, null))
    epoch++
    resident = null
    requireNotNull(queued).invoke()
    assertEquals(0, provisions)
    assertEquals(0, handovers)
    assertNull(resident)
  }

  @Test fun `STOP during transfer completion prevents stale handover`() {
    RelaisConfig.setModelRef(ctx, ref)
    val target = file()
    val runner = runner(provision = { _, _ -> epoch++; resident = null; target.path })
    assertTrue(runner.dispatch(ctx, null))
    requireNotNull(queued).invoke()
    assertEquals(1, provisions)
    assertEquals(0, handovers)
    assertEquals(0, publications)
    assertNull(resident)
  }

  @Test fun `a selection change before worker start cancels transfer`() {
    RelaisConfig.setModelRef(ctx, ref)
    val runner = runner()
    assertTrue(runner.dispatch(ctx, null))
    RelaisConfig.setModelId(ctx, "test/z")
    requireNotNull(queued).invoke()
    assertEquals(0, provisions)
    assertEquals("X", resident)
  }

  @Test fun `same ID ref drift during provisioning cancels handover`() {
    RelaisConfig.setModelRef(ctx, ref)
    val target = file()
    val runner = runner(provision = { _, _ -> RelaisConfig.setModelRef(ctx, ref.copy(commitHash = "new")); target.path })
    assertTrue(runner.dispatch(ctx, null))
    requireNotNull(queued).invoke()
    assertEquals(1, provisions)
    assertEquals(0, handovers)
    assertEquals("X", resident)
  }

  @Test fun `targeted swaps never invoke provision even when the target file is missing`() {
    val runner = runner()
    val target = ProvisionedModel("target/z", file().path, "Z")
    assertTrue(runner.dispatch(ctx, target))
    requireNotNull(queued).invoke()
    assertEquals("target/z", resident)
    assertEquals(0, provisions)
    assertEquals(1, publications)
    val missing = target.copy(path = File(ctx.cacheDir, "absent.litertlm").path)
    assertTrue(runner.dispatch(ctx, missing))
    requireNotNull(queued).invoke()
    assertEquals(0, provisions)
    assertEquals(1, handovers)
    assertEquals(1, publications)
  }

  @Test fun `thread launch Error returns false and restores startup and CAS ownership`() {
    val runner = runner(launch = { throw OutOfMemoryError("thread exhausted") })
    repeat(2) {
      assertFalse(runner.dispatch(ctx, ProvisionedModel("target/z", "/unused", "Z")))
      assertFalse(RelaisLivenessState.snapshot.startupInProgress)
    }
    assertEquals(0, provisions)
    assertEquals(0, handovers)
    assertEquals("X", resident)
  }
}

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

/** T4 (#369 R2): the untargeted swap runs on the selection captured at dispatch. */
@RunWith(RobolectricTestRunner::class)
class RelaisModelSwapRunnerTest {
  private val ctx get() = RuntimeEnvironment.getApplication()
  private val ref = RelaisModelRef("test/y", "y.litertlm", "yyy", 1L, "Y", RelaisModelRef.SOURCE_HUGGINGFACE)
  private var epoch = 0L
  private var queued: (() -> Unit)? = null
  private var resolves = 0
  private var handovers = 0
  private var handedId: String? = null
  private var publications = 0
  private val files = mutableListOf<File>()

  @After fun cleanup() {
    assertFalse("every branch must balance startup ownership", RelaisLivenessState.snapshot.startupInProgress)
    files.forEach { it.delete() }
    RelaisModelProvisioner.resetPathCacheForTest()
  }

  private fun file(name: String = "target.litertlm") =
    File(ctx.cacheDir, name).also { it.writeBytes(byteArrayOf(1)); files.add(it) }

  private fun runner(
    resolve: (Context, ConfiguredModelSelection) -> String = { _, _ -> file().path },
    launch: (() -> Unit) -> Unit = { queued = it },
  ) =
    RelaisModelSwapRunner(
      epoch = { epoch },
      handover = { _, _, id, _, _ -> handovers++; handedId = id; true },
      publish = { publications++ },
      resolve = { c, s -> resolves++; resolve(c, s) },
      launch = launch,
    )

  @Test fun `selection flipped between dispatch and run hands over no mismatched model`() {
    RelaisConfig.setModelRef(ctx, ref)
    val runner = runner()
    assertTrue(runner.dispatch(ctx, null))
    RelaisConfig.setModelId(ctx, "test/z") // operator pick lands after dispatch
    requireNotNull(queued).invoke()
    assertEquals(0, handovers)
    assertEquals(0, publications)
  }

  @Test fun `selection flipped while resolving cancels handover`() {
    RelaisConfig.setModelRef(ctx, ref)
    val runner = runner(resolve = { _, _ -> RelaisConfig.setModelId(ctx, "test/z"); file().path })
    assertTrue(runner.dispatch(ctx, null))
    requireNotNull(queued).invoke()
    assertEquals(1, resolves)
    assertEquals(0, handovers)
  }

  @Test fun `unchanged selection hands over the captured id and releases the dispatch flag`() {
    RelaisConfig.setModelRef(ctx, ref)
    val runner = runner()
    assertTrue(runner.dispatch(ctx, null))
    assertFalse("single-flight while queued", runner.dispatch(ctx, null))
    requireNotNull(queued).invoke()
    assertEquals(ref.modelId, handedId)
    assertEquals(1, publications)
    assertTrue("flag released so a second dispatch is accepted", runner.dispatch(ctx, null))
    requireNotNull(queued).invoke()
  }

  @Test fun `flag is released after an abort so a retry dispatches`() {
    RelaisConfig.setModelRef(ctx, ref)
    val runner = runner()
    assertTrue(runner.dispatch(ctx, null))
    RelaisConfig.setModelId(ctx, "test/z")
    requireNotNull(queued).invoke()
    assertTrue(runner.dispatch(ctx, null))
    requireNotNull(queued).invoke()
    assertEquals("test/z", handedId)
  }

  @Test fun `targeted swap never resolves and ignores selection`() {
    val runner = runner()
    assertTrue(runner.dispatch(ctx, ProvisionedModel("target/z", file().path, "Z")))
    requireNotNull(queued).invoke()
    assertEquals(0, resolves)
    assertEquals("target/z", handedId)
  }

  @Test fun `STOP before the worker runs does nothing`() {
    RelaisConfig.setModelRef(ctx, ref)
    val runner = runner()
    assertTrue(runner.dispatch(ctx, null))
    epoch++
    requireNotNull(queued).invoke()
    assertEquals(0, resolves)
    assertEquals(0, handovers)
  }

  @Test fun `missing resolved file leaves the resident engine untouched`() {
    RelaisConfig.setModelRef(ctx, ref)
    val runner = runner(resolve = { _, _ -> File(ctx.cacheDir, "absent.litertlm").path })
    assertTrue(runner.dispatch(ctx, null))
    requireNotNull(queued).invoke()
    assertEquals(0, handovers)
  }

  @Test fun `launch Error restores ownership`() {
    val runner = runner(launch = { throw OutOfMemoryError("thread exhausted") })
    repeat(2) { assertFalse(runner.dispatch(ctx, ProvisionedModel("t/z", "/x", "Z"))) }
  }
}

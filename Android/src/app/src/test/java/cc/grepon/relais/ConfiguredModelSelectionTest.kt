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
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Selection snapshot and coalesced writers (#369 PR1): T2 (writer tear, R5) and T5 (default-ref
 * check-then-write, R6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfiguredModelSelectionTest {

  private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
  private val tpu = RelaisModelCatalog.G5_TPU_REFS.first()

  /** Counts `edit()` calls and runs [onContains] after each `contains(key)` answer. */
  private class SpyPrefs(
    private val d: SharedPreferences,
    val edits: AtomicInteger,
    val onContains: (String) -> Unit,
  ) : SharedPreferences by d {
    override fun edit(): SharedPreferences.Editor {
      edits.incrementAndGet()
      return d.edit()
    }

    override fun contains(key: String): Boolean = d.contains(key).also { onContains(key) }
  }

  private fun spyContext(edits: AtomicInteger, onContains: (String) -> Unit = {}): Context =
    object : ContextWrapper(app) {
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        SpyPrefs(super.getSharedPreferences(name, mode), edits, onContains)
    }

  @Test
  fun `modelSelection captures id, matching ref and path together`() {
    RelaisConfig.setModelRef(app, tpu)
    RelaisConfig.setModelPath(app, "/m/${tpu.modelFile}")
    val sel = RelaisConfig.modelSelection(app)
    assertEquals(tpu.modelId, sel.modelId)
    assertEquals(tpu, sel.ref)
    assertEquals("/m/${tpu.modelFile}", sel.persistedPath)
    assertTrue(RelaisConfig.isCurrentSelection(app, sel))
    RelaisConfig.setModelPath(app, "/other") // path is output, not identity
    assertTrue(RelaisConfig.isCurrentSelection(app, sel))
    RelaisConfig.applyManualSelection(app, "x/y")
    assertFalse(RelaisConfig.isCurrentSelection(app, sel))
  }

  @Test
  fun `applyManualSelection is one editor and drops ref and its path`() {
    RelaisConfig.setModelRef(app, tpu)
    RelaisConfig.setModelPath(app, "/m/${tpu.modelFile}")
    val edits = AtomicInteger()
    RelaisConfig.applyManualSelection(spyContext(edits), "x/y")
    assertEquals("ref clear + id write must be ONE editor (R5)", 1, edits.get())
    assertEquals("x/y", RelaisConfig.modelId(app))
    assertNull(RelaisConfig.modelRef(app))
    assertNull(RelaisConfig.modelPath(app))
  }

  @Test
  fun `applyManualSelection of the same id with no ref keeps the path`() {
    RelaisConfig.applyManualSelection(app, "x/y")
    RelaisConfig.setModelPath(app, "/m/y")
    RelaisConfig.applyManualSelection(app, "x/y")
    assertEquals("/m/y", RelaisConfig.modelPath(app))
  }

  @Test
  fun `a concurrent capture never sees the old id with its ref already gone`() {
    val failure = AtomicReference<String?>(null)
    val stop = java.util.concurrent.atomic.AtomicBoolean(false)
    val reader = Thread {
      while (!stop.get() && failure.get() == null) {
        val s = RelaisConfig.modelSelection(app)
        if (s.modelId == tpu.modelId && s.ref == null) failure.set("torn: old id, null ref")
        if (s.ref != null && s.ref.modelId != s.modelId) failure.set("ref id != modelId")
      }
    }
    reader.start()
    repeat(300) {
      RelaisConfig.setModelRef(app, tpu)
      RelaisConfig.applyManualSelection(app, "x/y")
    }
    stop.set(true)
    reader.join()
    assertNull(failure.get())
  }

  @Test
  fun `an operator pick landing between the default check and write wins`() {
    val picked = AtomicInteger()
    val operator = AtomicReference<Thread?>(null)
    val ctx =
      spyContext(AtomicInteger()) { key ->
        if (key == "model_id" && picked.getAndIncrement() == 0) {
          // The operator pick from another thread; it must not complete inside our critical section.
          val t = Thread { RelaisConfig.applyManualSelection(app, "operator/pick") }
          operator.set(t)
          t.start()
          t.join(300)
        }
      }
    RelaisModelProvisioner.applyDeviceDefaultIfFresh(ctx, isPixel10 = true)
    operator.get()!!.join()
    assertEquals("operator/pick", RelaisConfig.modelId(app))
    assertNull("default ref must not survive the operator pick", RelaisConfig.modelRef(app))
  }

  @Test
  fun `default ref applies atomically only when nothing is chosen`() {
    assertEquals(
      RelaisModelProvisioner.G5_DEFAULT_REF,
      RelaisConfig.applyDefaultModelRefIfFresh(app, RelaisModelProvisioner.G5_DEFAULT_REF),
    )
    assertNull(RelaisConfig.applyDefaultModelRefIfFresh(app, RelaisModelProvisioner.G5_DEFAULT_REF))
  }
}

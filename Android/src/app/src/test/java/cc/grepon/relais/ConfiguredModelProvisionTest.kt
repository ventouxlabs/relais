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
import cc.grepon.relais.data.RelaisModelRef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ConfiguredModelProvisionTest {
  @Test fun `a same ID ref change prevents publishing an old build to path cache and registry`() {
    val ctx = RuntimeEnvironment.getApplication()
    RelaisModelProvisioner.resetPathCacheForTest()
    val old = RelaisModelRef("test/build", "old.litertlm", "old", 1L, "Old", RelaisModelRef.SOURCE_HUGGINGFACE)
    val next = old.copy(modelFile = "new.litertlm", commitHash = "new")
    val file = File(RelaisModelProvisioner.modelFromRef(old).getPath(ctx))
    file.parentFile!!.mkdirs()
    file.writeBytes(byteArrayOf(1))
    RelaisConfig.setModelRef(ctx, old)
    val selection = RelaisConfig.modelSelection(ctx)
    val probing = object : ContextWrapper(ctx) {
      override fun getExternalFilesDir(type: String?): File? {
        RelaisConfig.setModelRef(ctx, next)
        return ctx.getExternalFilesDir(type)
      }
    }
    try {
      assertEquals(file.path, RelaisModelProvisioner.ensureModel(probing, selection))
      assertNull(RelaisConfig.modelPath(ctx))
      assertTrue(RelaisConfig.provisionedModels(ctx).isEmpty())
      assertNull("same-ID stale result must not overwrite cache", RelaisModelProvisioner.pathFor(ctx, old.modelId))
      assertFalse(RelaisConfig.isCurrentSelection(ctx, selection))
    } finally {
      file.delete()
      RelaisModelProvisioner.resetPathCacheForTest()
    }
  }

  @Test fun `persisting a path does not change selection identity but a ref edit does`() {
    val ctx = RuntimeEnvironment.getApplication()
    val ref = RelaisModelRef("test/identity", "x.litertlm", "abc", 1L, "X", RelaisModelRef.SOURCE_HUGGINGFACE)
    RelaisConfig.setModelRef(ctx, ref)
    val selection = RelaisConfig.modelSelection(ctx)
    RelaisConfig.setModelPath(ctx, "/new/output")
    assertTrue(RelaisConfig.isCurrentSelection(ctx, selection))
    RelaisConfig.setModelRef(ctx, ref.copy(commitHash = "other"))
    assertFalse(RelaisConfig.isCurrentSelection(ctx, selection))
    RelaisConfig.setModelRef(ctx, ref)
    assertTrue("returning to the exact same input is safe", RelaisConfig.isCurrentSelection(ctx, selection))
  }

  @Test fun `Y to Z to Y during resolution cannot record Z weights under Y`() {
    val ctx = RuntimeEnvironment.getApplication()
    val y = RelaisModelRef("test/y", "y.litertlm", "yyy", 1L, "Y", RelaisModelRef.SOURCE_HUGGINGFACE)
    val z = y.copy(modelId = "test/z", modelFile = "z.litertlm", commitHash = "zzz", displayName = "Z")
    val yFile = File(RelaisModelProvisioner.modelFromRef(y).getPath(ctx))
    val zFile = File(RelaisModelProvisioner.modelFromRef(z).getPath(ctx))
    yFile.parentFile!!.mkdirs(); yFile.writeBytes(byteArrayOf(1))
    zFile.parentFile!!.mkdirs(); zFile.writeBytes(byteArrayOf(2))
    RelaisConfig.setModelRef(ctx, y)
    var flipped = false
    val probing = object : ContextWrapper(ctx) {
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val original = ctx.getSharedPreferences(name, mode)
        return object : SharedPreferences by original {
          override fun getString(key: String?, defValue: String?): String? {
            val value = original.getString(key, defValue)
            if (key == "model_path" && !flipped) {
              flipped = true
              RelaisConfig.setModelRef(ctx, z)
            }
            return value
          }
        }
      }
      override fun getExternalFilesDir(type: String?): File? {
        if (flipped) RelaisConfig.setModelRef(ctx, y)
        return ctx.getExternalFilesDir(type)
      }
    }
    try {
      val path = RelaisModelProvisioner.ensureModel(probing)
      assertEquals("resolution must use Y's captured ref, not the transient Z", yFile.path, path)
      assertEquals(yFile.path, RelaisConfig.modelPath(ctx))
      assertEquals(yFile.path, RelaisConfig.provisionedModels(ctx).single().path)
    } finally {
      yFile.delete(); zFile.delete()
      RelaisModelProvisioner.resetPathCacheForTest()
    }
  }
}

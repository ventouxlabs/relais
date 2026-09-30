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

import cc.grepon.relais.data.RelaisModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM unit tests for [RelaisModelProvisioner.deviceDefaultRef] and [G5_DEFAULT_REF].
 *
 * Verifies that a fresh Pixel 10 — no persisted ref, and a model id nobody ever wrote — receives the
 * G5-compatible E2B default, while every other combination is left unchanged (#355: an id the
 * operator wrote is respected even when it equals DEFAULT_MODEL_ID).
 *
 * No Context / Robolectric needed: deviceDefaultRef is a pure function.
 */
class Pixel10DefaultModelTest {

  @Test
  fun `fresh Pixel 10 with default model id returns G5_DEFAULT_REF`() {
    val ref =
      RelaisModelProvisioner.deviceDefaultRef(
        isPixel10 = true,
        hasPersistedRef = false,
        hasExplicitModelId = false,
      )
    assertNotNull("fresh Pixel 10 + default id must return a non-null G5 ref", ref)
    assertEquals(
      "modelId must match G5_DEFAULT_REF",
      "litert-community/gemma-4-E2B-it-litert-lm",
      ref!!.modelId,
    )
    assertEquals(
      "modelFile must match G5_DEFAULT_REF",
      "gemma-4-E2B-it.litertlm",
      ref.modelFile,
    )
    assertEquals(
      "commitHash must match G5_DEFAULT_REF",
      "361a4010ad6d88fc5c86e148e333c0342b99763d",
      ref.commitHash,
    )
    assertEquals(
      "sizeInBytes must match G5_DEFAULT_REF",
      2_588_147_712L,
      ref.sizeInBytes,
    )
    assertEquals(
      "displayName must match G5_DEFAULT_REF",
      "Gemma 4 E2B-it (Tensor G5)",
      ref.displayName,
    )
    assertEquals(
      "source must match G5_DEFAULT_REF",
      RelaisModelRef.SOURCE_HUGGINGFACE,
      ref.source,
    )
  }

  @Test
  fun `non-Pixel-10 device returns null (existing behavior unchanged)`() {
    assertNull(
      "non-Pixel-10 must not receive the G5 override",
      RelaisModelProvisioner.deviceDefaultRef(
        isPixel10 = false,
        hasPersistedRef = false,
        hasExplicitModelId = false,
      ),
    )
  }

  @Test
  fun `Pixel 10 with persisted ref returns null (operator choice respected)`() {
    assertNull(
      "an operator-chosen ref must not be overridden",
      RelaisModelProvisioner.deviceDefaultRef(
        isPixel10 = true,
        hasPersistedRef = true,
        hasExplicitModelId = false,
      ),
    )
  }

  @Test
  fun `Pixel 10 with an operator-written model id returns null, even the default one (#355)`() {
    // The operator typed an id — any id, including DEFAULT_MODEL_ID (E4B). A manual pick clears the
    // ref, so hasPersistedRef is false here; the id comparison the old signature used could not tell
    // "typed E4B on purpose" from "never configured" and swapped E4B for E2B on every START.
    assertNull(
      "an operator-written model id must not be overridden",
      RelaisModelProvisioner.deviceDefaultRef(
        isPixel10 = true,
        hasPersistedRef = false,
        hasExplicitModelId = true,
      ),
    )
    // The twin in the same test: with the id never written, the same device DOES get the default,
    // so explicitness is what decides.
    assertNotNull(
      RelaisModelProvisioner.deviceDefaultRef(
        isPixel10 = true,
        hasPersistedRef = false,
        hasExplicitModelId = false,
      ),
    )
  }

  @Test
  fun `G5_DEFAULT_REF round-trips through toJson and fromJson`() {
    val ref = RelaisModelProvisioner.G5_DEFAULT_REF
    val json = ref.toJson()
    val decoded = RelaisModelRef.fromJson(json)
    assertNotNull("fromJson must not return null for a valid ref", decoded)
    assertEquals("modelId must survive round-trip", ref.modelId, decoded!!.modelId)
    assertEquals("modelFile must survive round-trip", ref.modelFile, decoded.modelFile)
    assertEquals("commitHash must survive round-trip", ref.commitHash, decoded.commitHash)
    assertEquals("sizeInBytes must survive round-trip", ref.sizeInBytes, decoded.sizeInBytes)
    assertEquals("displayName must survive round-trip", ref.displayName, decoded.displayName)
    assertEquals("source must survive round-trip", ref.source, decoded.source)
  }
}

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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [RelaisModelProvisioner.resolveModelPath] (#337) — which file the engine loads
 * for a given model id.
 *
 * The defect this pins: the old `cachedPathOrDefault(context)` answered "which path?" with no id in
 * the question, so after a model switch it handed back the OUTGOING model's file, which
 * `ensureInitialized` then paired with the INCOMING id and wrote as resident. The old weights were
 * served under the new id with no error anywhere, and #180's mismatch check saw
 * `residentModelId == configured` and never swapped.
 *
 * Every case below is therefore "does the answer belong to the id that was asked about", and the
 * `fileExists` predicate is a REQUIRED parameter of the function under test — a defaulted predicate
 * would let this whole table run against the real filesystem without any row noticing.
 */
class RelaisModelPathResolutionTest {

  private val default = "/data/default/gemma-4-E4B-it.litertlm"

  /** The id whose file [default] literally is. Distinct from every other id used below. */
  private val DEFAULT_ID = "vendor/the-default-model"

  private fun m(id: String, path: String) = ProvisionedModel(id, path, id)

  /** Every path in this suite "exists" unless a test names the missing ones. */
  private fun exists(vararg missing: String): (String) -> Boolean = { it !in missing }

  // ---- the in-memory cache is readable only for the id it was provisioned for ----

  @Test
  fun `cache hit when the cached id is the id being asked about`() {
    assertEquals(
      "/data/a.litertlm",
      RelaisModelProvisioner.resolveModelPath(
        modelId = "a",
        cached = RelaisModelProvisioner.CachedModelPath(modelId = "a", path = "/data/a.litertlm"),
        configuredId = "a",
        persistedPath = null,
        provisioned = emptyList(),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists(),
      ),
    )
  }

  @Test
  fun `cache MISS when the cached path belongs to another id - the #337 defect`() {
    // The switch just happened: cache still holds the outgoing model, config names the incoming one.
    // Returning "/data/old.litertlm" here is precisely the bug — old weights under the new id.
    assertEquals(
      "/data/new.litertlm",
      RelaisModelProvisioner.resolveModelPath(
        modelId = "new",
        cached = RelaisModelProvisioner.CachedModelPath(modelId = "old", path = "/data/old.litertlm"),
        configuredId = "new",
        persistedPath = null,
        provisioned = listOf(m("new", "/data/new.litertlm")),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists(),
      ),
    )
  }

  @Test
  fun `an empty cache is never readable`() {
    // Nothing provisioned this process. There is deliberately no "path without an id" case to test:
    // CachedModelPath carries both or the cache is null, so an untagged path cannot be expressed.
    assertNull(
      RelaisModelProvisioner.resolveModelPath(
        modelId = "a",
        cached = null,
        configuredId = "a",
        persistedPath = null,
        provisioned = emptyList(),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists(),
      ),
    )
  }

  @Test
  fun `a cache hit whose file has been deleted falls through`() {
    assertEquals(
      "/data/registry-a.litertlm",
      RelaisModelProvisioner.resolveModelPath(
        modelId = "a",
        cached = RelaisModelProvisioner.CachedModelPath(modelId = "a", path = "/data/gone.litertlm"),
        configuredId = "a",
        persistedPath = null,
        provisioned = listOf(m("a", "/data/registry-a.litertlm")),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists("/data/gone.litertlm"),
      ),
    )
  }

  // ---- the registry is the id-keyed source, so it outranks the un-keyed pref ----

  @Test
  fun `the registry entry for the asked-about id wins over the persisted pref`() {
    // The pref holds a single un-keyed path; the registry is keyed by id. When they disagree about
    // a NON-configured id, only the registry can be right.
    assertEquals(
      "/data/b.litertlm",
      RelaisModelProvisioner.resolveModelPath(
        modelId = "b",
        cached = null,
        configuredId = "a",
        persistedPath = "/data/a.litertlm",
        provisioned = listOf(m("a", "/data/a.litertlm"), m("b", "/data/b.litertlm")),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists(),
      ),
    )
  }

  @Test
  fun `a registry entry whose file is gone falls through instead of being returned`() {
    assertEquals(
      "/data/a-pref.litertlm",
      RelaisModelProvisioner.resolveModelPath(
        modelId = "a",
        cached = null,
        configuredId = "a",
        persistedPath = "/data/a-pref.litertlm",
        provisioned = listOf(m("a", "/data/pruned.litertlm")),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists("/data/pruned.litertlm"),
      ),
    )
  }

  // ---- the persisted pref carries no id, so it is only usable for the CONFIGURED id ----

  @Test
  fun `the persisted pref is used when the asked-about id is the configured one`() {
    assertEquals(
      "/data/a-pref.litertlm",
      RelaisModelProvisioner.resolveModelPath(
        modelId = "a",
        cached = null,
        configuredId = "a",
        persistedPath = "/data/a-pref.litertlm",
        provisioned = emptyList(),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists(),
      ),
    )
  }

  @Test
  fun `the persisted pref is REFUSED for any other id`() {
    // KEY_MODEL_PATH is cleared on an id change, so when it is set it describes the configured
    // model. Handing it to a swap target's id would re-create #337 one fallback further down.
    assertNull(
      RelaisModelProvisioner.resolveModelPath(
        modelId = "b",
        cached = null,
        configuredId = "a",
        persistedPath = "/data/a-pref.litertlm",
        provisioned = emptyList(),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists(),
      ),
    )
  }

  @Test
  fun `a persisted pref whose file is gone leaves nothing to serve`() {
    assertNull(
      RelaisModelProvisioner.resolveModelPath(
        modelId = "a",
        cached = null,
        configuredId = "a",
        persistedPath = "/data/gone.litertlm",
        provisioned = emptyList(),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists("/data/gone.litertlm"),
      ),
    )
  }

  // ---- the floor ----

  @Test
  fun `nothing known about a non-default id yields null, never another model's file`() {
    assertNull(
      RelaisModelProvisioner.resolveModelPath(
        modelId = "a",
        cached = null,
        configuredId = "a",
        persistedPath = null,
        provisioned = emptyList(),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists(),
      ),
    )
  }

  @Test
  fun `the default is returned unchecked for its OWN id - it is the pre-provision location`() {
    // The default is where a provision will WRITE, so it must be returned even when absent;
    // ensureInitialized's own require() is what reports a missing file.
    assertEquals(
      default,
      RelaisModelProvisioner.resolveModelPath(
        modelId = DEFAULT_ID,
        cached = null,
        configuredId = "a",
        persistedPath = null,
        provisioned = emptyList(),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists(default),
      ),
    )
  }

  // ---- rung 4 is id-bound too: the default path is ONE model's file name ----

  @Test
  fun `the default path is refused for an id that is not the default model`() {
    // The sideload-adoption gate in ensureModel makes this exact distinction, and for the same
    // reason: that file name is the default model's file. Before this gate existed, an operator who
    // typed a raw model id got the default model's weights stamped with their id on the next
    // reload — #337 surviving one rung below where it was found.
    assertNull(
      RelaisModelProvisioner.resolveModelPath(
        modelId = "operator/typed-this-by-hand",
        cached = null,
        configuredId = "operator/typed-this-by-hand",
        persistedPath = null,
        provisioned = emptyList(),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists(),
      ),
    )
  }

  @Test
  fun `a pre-staged sideload still boots the default model`() {
    // The case the default rung exists for: a fresh install whose model was pushed to the
    // conventional location by adb, with nothing cached, registered or persisted yet.
    assertEquals(
      default,
      RelaisModelProvisioner.resolveModelPath(
        modelId = DEFAULT_ID,
        cached = null,
        configuredId = DEFAULT_ID,
        persistedPath = null,
        provisioned = emptyList(),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists(),
      ),
    )
  }

  // ---- precedence, asserted as an order rather than one winner at a time ----

  @Test
  fun `cache outranks registry outranks pref for the configured id`() {
    val cachedFile = "/data/cached.litertlm"
    val registry = "/data/registry.litertlm"
    val pref = "/data/pref.litertlm"
    fun resolve(vararg missing: String) =
      RelaisModelProvisioner.resolveModelPath(
        modelId = "a",
        cached = RelaisModelProvisioner.CachedModelPath(modelId = "a", path = cachedFile),
        configuredId = "a",
        persistedPath = pref,
        provisioned = listOf(m("a", registry)),
        defaultPath = default,
        defaultModelId = DEFAULT_ID,
        fileExists = exists(*missing),
      )
    assertEquals(cachedFile, resolve())
    assertEquals(registry, resolve(cachedFile))
    assertEquals(pref, resolve(cachedFile, registry))
    // Not `default`: "a" is not the default model, and the default file holds the default model's
    // weights. With every id-bound source exhausted there is nothing honest left to return.
    assertNull(resolve(cachedFile, registry, pref))
  }
}

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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #362: a request while the configured model is not on disk answers 503 + Retry-After, not 500 + ERROR. */
class RelaisModelPresenceTest {

  private val nothingExists: (String) -> Boolean = { false }
  private val everythingExists: (String) -> Boolean = { true }

  // --- configuredModelMissing ---

  @Test fun `no path known for the configured model is missing`() {
    // A non-default id with nothing in cache, registry or prefs: resolveModelPath returns null.
    assertTrue(configuredModelMissing(isReady = false, resolvedPath = null, fileExists = everythingExists))
  }

  @Test fun `the unchecked default path to a file that is not there is missing`() {
    // The default id: resolveModelPath's rung 4 returns the conventional path UNCHECKED.
    assertTrue(configuredModelMissing(isReady = false, resolvedPath = "/x/gemma.litertlm", fileExists = nothingExists))
  }

  @Test fun `a resolved path whose file exists is not missing`() {
    assertFalse(configuredModelMissing(isReady = false, resolvedPath = "/x/gemma.litertlm", fileExists = everythingExists))
  }

  @Test fun `the file is checked at the resolved path, not some other one`() {
    val onlyThis: (String) -> Boolean = { it == "/x/present.litertlm" }
    assertFalse(configuredModelMissing(isReady = false, resolvedPath = "/x/present.litertlm", fileExists = onlyThis))
    assertTrue(configuredModelMissing(isReady = false, resolvedPath = "/x/absent.litertlm", fileExists = onlyThis))
  }

  @Test fun `a ready engine is never missing, whatever the disk says`() {
    // Resident and serving: the request must not 503 because a path no longer resolves (e.g. a
    // pending same-id pick, #354) — the engine in memory is what answers.
    assertFalse(configuredModelMissing(isReady = true, resolvedPath = null, fileExists = nothingExists))
    assertFalse(configuredModelMissing(isReady = true, resolvedPath = "/x/gemma.litertlm", fileExists = nothingExists))
  }

  // --- isUncommittedRetryable: which failures the dispatcher answers 503 + Retry-After ---

  @Test fun `a model not on disk before anything was written is retryable`() {
    assertTrue(isUncommittedRetryable(ModelNotOnDiskException("gemma-y"), committed = false))
  }

  @Test fun `a model not on disk after the header went out stays with the stream's own handling`() {
    assertFalse(isUncommittedRetryable(ModelNotOnDiskException("gemma-y"), committed = true))
  }

  @Test fun `a served-model mismatch before anything was written is retryable`() {
    assertTrue(isUncommittedRetryable(ModelNotResidentException("y", "x"), committed = false))
  }

  @Test fun `a served-model mismatch after the header went out stays with the stream's own handling`() {
    assertFalse(isUncommittedRetryable(ModelNotResidentException("y", "x"), committed = true))
  }

  @Test fun `an unrelated failure is never retryable, committed or not`() {
    assertFalse(isUncommittedRetryable(IllegalStateException("engine"), committed = false))
    assertFalse(isUncommittedRetryable(IllegalStateException("engine"), committed = true))
  }

  @Test fun `the not-on-disk message names the model`() {
    assertTrue(ModelNotOnDiskException("gemma-y").message!!.contains("gemma-y"))
  }
}

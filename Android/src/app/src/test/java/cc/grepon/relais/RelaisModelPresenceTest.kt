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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    assertTrue(isUncommittedRetryable(ModelNotOnDiskException("gemma-y", provisioning = true), committed = false))
  }

  @Test fun `a model not on disk after the header went out stays with the stream's own handling`() {
    assertFalse(isUncommittedRetryable(ModelNotOnDiskException("gemma-y", provisioning = true), committed = true))
  }

  @Test fun `a served-model mismatch before anything was written is retryable`() {
    assertTrue(isUncommittedRetryable(ModelNotResidentException("y", "x"), committed = false))
  }

  @Test fun `a served-model mismatch after the header went out stays with the stream's own handling`() {
    assertFalse(isUncommittedRetryable(ModelNotResidentException("y", "x"), committed = true))
  }

  @Test fun `a model not on disk with nothing starting is retryable too`() {
    assertTrue(isUncommittedRetryable(ModelNotOnDiskException("gemma-y", provisioning = false), committed = false))
  }

  @Test fun `an unrelated failure is never retryable, committed or not`() {
    assertFalse(isUncommittedRetryable(IllegalStateException("engine"), committed = false))
    assertFalse(isUncommittedRetryable(IllegalStateException("engine"), committed = true))
  }

  @Test fun `the not-on-disk message names the model`() {
    assertTrue(ModelNotOnDiskException("gemma-y", provisioning = true).message!!.contains("gemma-y"))
  }

  // --- modelStateMessage: the dispatcher's ONE "503 or 500, and which text" decision ---

  @Test fun `a served-model mismatch answers the resident-changed text`() {
    assertEquals(MODEL_NOT_RESIDENT_MESSAGE, modelStateMessage(ModelNotResidentException("y", "x")))
  }

  @Test fun `a missing model with a startup in progress answers the starting text`() {
    assertEquals(MODEL_NOT_ON_DISK_STARTING_MESSAGE, modelStateMessage(ModelNotOnDiskException("m", provisioning = true)))
  }

  @Test fun `a missing model with nothing starting answers the not-starting text`() {
    assertEquals(MODEL_NOT_ON_DISK_IDLE_MESSAGE, modelStateMessage(ModelNotOnDiskException("m", provisioning = false)))
  }

  @Test fun `the two not-on-disk texts differ, so the reason reaches the client`() {
    assertTrue(MODEL_NOT_ON_DISK_STARTING_MESSAGE != MODEL_NOT_ON_DISK_IDLE_MESSAGE)
  }

  @Test fun `an unrelated failure has no model-state text, so the dispatcher answers 500`() {
    assertNull(modelStateMessage(IllegalStateException("engine")))
    assertNull(modelStateMessage(RuntimeException("boom")))
  }

  // --- shouldKickProvision: kick only when a live service owns the outcome and nothing else retries ---
  // Every row of the 2x2x2 table.

  @Test fun `listeners down never kicks - the node is OFF or tearing down`() {
    for (failed in listOf(false, true)) for (idle in listOf(false, true)) {
      assertFalse("failed=$failed idle=$idle", shouldKickProvision(lastInitFailed = failed, listenersUp = false, idleUnloaded = idle))
    }
  }

  @Test fun `listeners up and nothing failed kicks`() {
    assertTrue(shouldKickProvision(lastInitFailed = false, listenersUp = true, idleUnloaded = false))
    assertTrue(shouldKickProvision(lastInitFailed = false, listenersUp = true, idleUnloaded = true))
  }

  @Test fun `ERROR with listeners up does not kick - the watchdog revive owns retries`() {
    assertFalse(shouldKickProvision(lastInitFailed = true, listenersUp = true, idleUnloaded = false))
  }

  @Test fun `a failed flag on a healthy-idle node kicks - the watchdog would never revive it`() {
    assertTrue(shouldKickProvision(lastInitFailed = true, listenersUp = true, idleUnloaded = true))
  }

  // --- shouldRecordProvisionFailure: a kick's late failure lands only on a node still the kick's ---

  @Test fun `a failure on a node still not ready, not idle, not shut down is recorded`() {
    assertTrue(shouldRecordProvisionFailure(ready = false, idleUnloaded = false, shutdownSinceKick = false))
  }

  @Test fun `a failure after something else loaded an engine is not recorded`() {
    assertFalse(shouldRecordProvisionFailure(ready = true, idleUnloaded = false, shutdownSinceKick = false))
  }

  @Test fun `a failure on a node that is now healthy idle is not recorded`() {
    // Operator START loaded the engine mid-download, the TTL released it, then the kick failed.
    assertFalse(shouldRecordProvisionFailure(ready = false, idleUnloaded = true, shutdownSinceKick = false))
  }

  @Test fun `a failure after a STOP or swap shut the engine down is not recorded`() {
    assertFalse(shouldRecordProvisionFailure(ready = false, idleUnloaded = false, shutdownSinceKick = true))
  }
}

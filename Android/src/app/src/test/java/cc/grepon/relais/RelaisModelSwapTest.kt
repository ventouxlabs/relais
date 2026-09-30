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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic unit tests for the pure `model`-field decision in [RelaisModelSwap.kt] (#180, full
 * feature). No Context, no Android types, no [RelaisEngine] — pure JVM, mirrors [RelaisIdleTtlTest].
 *
 * The real concurrency/lock-ordering safety (never swapping mid-inference, the watchdog not
 * mistaking the swap's not-ready window for a crash) is NOT covered here — it lives in
 * [RelaisEngine.ensureModelSwapInBackground] and needs an on-device probe instead.
 */
class RelaisModelSwapTest {

  private val resident = "litert-community/gemma-4-E4B-it-litert-lm"
  private val configured = "litert-community/qwen3-4b-it-litert-lm"
  private val alsoOnDisk = "litert-community/Qwen2.5-1.5B-Instruct"
  private val onDisk = setOf(resident, configured, alsoOnDisk)

  private fun outcome(
    requested: String?,
    residentId: String? = resident,
    configuredId: String = configured,
    provisioned: Set<String> = onDisk,
    isReady: Boolean = true,
    loadInFlight: Boolean = false,
    incompatibleReason: (String) -> String? = { null },
  ) =
    resolveModelRequest(
      residentModelId = residentId,
      requestedModelId = requested,
      configuredModelId = configuredId,
      provisionedModelIds = provisioned,
      isReady = isReady,
      loadInFlight = loadInFlight,
      incompatibleReason = incompatibleReason,
    )

  // ---- serve the resident model ----

  @Test fun `an omitted model field serves the resident model`() {
    // The single most common request shape. Under a 404-on-unknown policy this MUST stay
    // ServeResident or every client that omits `model` breaks.
    assertEquals(ModelRequestOutcome.ServeResident, outcome(null))
    assertEquals(ModelRequestOutcome.ServeResident, outcome(""))
    assertEquals(ModelRequestOutcome.ServeResident, outcome("   "))
  }

  @Test fun `requesting the already-resident model serves it without a swap`() {
    assertEquals(ModelRequestOutcome.ServeResident, outcome(resident))
  }

  // ---- not ready (#347) ----
  //
  // There is no "503 not-ready path" on the chat lanes: RelaisEngine.generate lazily re-inits the
  // CONFIGURED model under its lock and serves. So while nothing is loading (idle-unloaded, or a
  // failed load with nothing retrying), the model that will answer is the configured one — decide
  // against THAT, not against a stale residentModelId the idle unload left behind.

  @Test fun `while idle-unloaded the configured model is what will serve`() {
    assertEquals(
      ModelRequestOutcome.ServeResident,
      outcome(configured, residentId = null, isReady = false),
    )
    assertEquals(ModelRequestOutcome.ServeResident, outcome(null, isReady = false))
  }

  @Test fun `while idle-unloaded a stale resident id does not answer for itself`() {
    // residentModelId survives the unload; the lazy reload loads `configured`, not `resident`. A
    // request naming the stale id must swap, or it is served by the configured model under its name.
    assertEquals(
      ModelRequestOutcome.SwapThenRetry(resident),
      outcome(resident, residentId = resident, configuredId = configured, isReady = false),
    )
  }

  @Test fun `while idle-unloaded another on-disk model swaps rather than being served by the configured one`() {
    assertEquals(ModelRequestOutcome.SwapThenRetry(alsoOnDisk), outcome(alsoOnDisk, isReady = false))
  }

  @Test fun `while idle-unloaded an unknown model is still a 404`() {
    // The registry is on disk; it does not depend on the engine being loaded, so "not ready" is no
    // reason to answer a model this node does not have.
    assertEquals(
      ModelRequestOutcome.NotProvisioned("anything-at-all"),
      outcome("anything-at-all", isReady = false),
    )
  }

  @Test fun `while idle-unloaded the configured model is served even if the table calls it incompatible`() {
    // Unchanged ordering: the configured model is what the lazy reload loads either way; refusing it
    // here would 404 a model that was serving before the unload.
    assertEquals(
      ModelRequestOutcome.ServeResident,
      outcome(configured, isReady = false, incompatibleReason = { "known bad" }),
    )
  }

  @Test fun `while a load is in flight an on-disk model waits instead of dispatching a swap`() {
    // A START may be minutes into downloading the configured model. A swap now would make that
    // model's own ensureInitialized see isReady and return, so it would never load.
    assertEquals(
      ModelRequestOutcome.RetryAfterLoad(alsoOnDisk),
      outcome(alsoOnDisk, isReady = false, loadInFlight = true),
    )
    // The twin: the same request with nothing loading DOES swap — so the gate is what decides.
    assertEquals(
      ModelRequestOutcome.SwapThenRetry(alsoOnDisk),
      outcome(alsoOnDisk, isReady = false, loadInFlight = false),
    )
  }

  @Test fun `while a load is in flight unknown and configured ids are answered as when idle`() {
    assertEquals(
      ModelRequestOutcome.NotProvisioned("anything-at-all"),
      outcome("anything-at-all", isReady = false, loadInFlight = true),
    )
    assertEquals(
      ModelRequestOutcome.ServeResident,
      outcome(configured, isReady = false, loadInFlight = true),
    )
  }

  @Test fun `during a load an incompatible model is refused outright, not told to retry`() {
    // Precedence: Incompatible is permanent. Answering RetryAfterLoad instead would send the client
    // into a 503 + Retry-After loop for the whole of a START's download, then 404 it anyway.
    assertEquals(
      ModelRequestOutcome.Incompatible(alsoOnDisk, "bad"),
      outcome(alsoOnDisk, isReady = false, loadInFlight = true, incompatibleReason = { "bad" }),
    )
  }

  @Test fun `during a load a stale resident id waits like any other on-disk model`() {
    assertEquals(
      ModelRequestOutcome.RetryAfterLoad(resident),
      outcome(resident, residentId = resident, isReady = false, loadInFlight = true),
    )
  }

  // ---- rollback of a failed swap (#347 review) ----

  @Test fun `a failed swap from a ready engine restores the model that was serving`() {
    assertEquals("/m/old.litertlm" to resident, swapRollbackTarget(wasReady = true, "/m/old.litertlm", resident))
  }

  @Test fun `a failed swap from an idle node restores nothing`() {
    // The idle unload left previousPath/previousId naming the model it CLOSED. Reloading it would
    // cold-load a model nobody asked for; the next request loads the configured one lazily.
    assertEquals(null, swapRollbackTarget(wasReady = false, "/m/old.litertlm", resident))
    // Twin in the same test: with a ready engine the same inputs DO restore, so readiness decides.
    assertEquals("/m/old.litertlm" to resident, swapRollbackTarget(wasReady = true, "/m/old.litertlm", resident))
  }

  @Test fun `a failed swap with nothing recorded restores nothing`() {
    assertEquals(null, swapRollbackTarget(wasReady = true, null, resident))
    assertEquals(null, swapRollbackTarget(wasReady = true, "/m/old.litertlm", null))
  }

  @Test fun `a ready engine ignores the in-flight flag`() {
    // Ready is ground truth: a startup that bounces listeners with the engine resident is not a load.
    assertEquals(
      ModelRequestOutcome.SwapThenRetry(alsoOnDisk),
      outcome(alsoOnDisk, isReady = true, loadInFlight = true),
    )
  }

  // ---- swap ----

  @Test fun `the operator's configured model is swap-eligible`() {
    assertEquals(ModelRequestOutcome.SwapThenRetry(configured), outcome(configured))
  }

  @Test fun `any OTHER model already on disk is swap-eligible`() {
    // The whole point of the full feature: the first cut could only swap to the configured id, so a
    // client naming a different downloaded model was silently answered by the wrong one.
    assertEquals(ModelRequestOutcome.SwapThenRetry(alsoOnDisk), outcome(alsoOnDisk))
  }

  @Test fun `the configured model is swap-eligible even before it reaches the registry`() {
    // A fresh selection may not be recorded yet; the operator's own choice must still work.
    assertEquals(
      ModelRequestOutcome.SwapThenRetry(configured),
      outcome(configured, provisioned = emptySet()),
    )
  }

  // ---- refuse: measured-incompatible with the pinned runtime (#220) ----

  /** A stand-in table, so these assertions don't depend on what the shipped one happens to say. */
  private val brokenOnThisRuntime = { id: String ->
    if (id == alsoOnDisk) "not loadable by this node's runtime (engine-create fails)" else null
  }

  @Test fun `a model on disk that cannot load is refused instead of swapped to`() {
    // Without this the client gets 503 + Retry-After, waits, retries, and the swap dies deep in
    // engine init — the #220 experience, just relocated from the download to the request.
    assertEquals(
      ModelRequestOutcome.Incompatible(
        alsoOnDisk,
        "not loadable by this node's runtime (engine-create fails)",
      ),
      outcome(alsoOnDisk, incompatibleReason = brokenOnThisRuntime),
    )
  }

  @Test fun `an incompatible verdict does not leak onto other models`() {
    assertEquals(
      ModelRequestOutcome.SwapThenRetry(configured),
      outcome(configured, incompatibleReason = brokenOnThisRuntime),
    )
  }

  @Test fun `a resident model answering requests outranks the compatibility table`() {
    // Observed reality beats a static table: the table exists to stop us LOADING something, not to
    // refuse something that is demonstrably already serving.
    assertEquals(
      ModelRequestOutcome.ServeResident,
      outcome(resident, residentId = resident, incompatibleReason = { "claims to be broken" }),
    )
  }

  @Test fun `an unprovisioned model is NotProvisioned even when also flagged incompatible`() {
    // Not-on-disk is the more actionable diagnosis, and it is checked against real state rather
    // than a table — so it must not be masked by the compat verdict.
    //
    // NOTE: an earlier revision of this test passed `incompatibleReason = { null }`, so it never
    // flagged the model at all and passed no matter which check ran first — it did not test its own
    // name. The verdict here MUST come back incompatible for the assertion to mean anything.
    val absent = "meta-llama/Llama-3-70B"
    assertEquals(
      ModelRequestOutcome.NotProvisioned(absent),
      outcome(
        absent,
        provisioned = emptySet(),
        configuredId = configured,
        incompatibleReason = { "flagged unloadable by the table" },
      ),
    )
  }

  @Test fun `an on-disk incompatible model still reports Incompatible, not NotProvisioned`() {
    // The other half of the ordering: once the model IS on disk, the compat verdict is what the
    // operator needs — re-downloading will not help. Guards against over-correcting the ordering.
    assertEquals(
      ModelRequestOutcome.Incompatible(alsoOnDisk, "not loadable by this node's runtime (engine-create fails)"),
      outcome(alsoOnDisk, incompatibleReason = brokenOnThisRuntime),
    )
  }

  @Test fun `by default nothing is treated as incompatible`() {
    // The parameter defaults to "nothing is known-bad" so every pre-#220 caller is unaffected.
    assertEquals(ModelRequestOutcome.SwapThenRetry(alsoOnDisk), outcome(alsoOnDisk))
  }

  // ---- refuse ----

  @Test fun `a model that is not on this device is refused, not silently substituted`() {
    assertEquals(
      ModelRequestOutcome.NotProvisioned("meta-llama/Llama-3-70B"),
      outcome("meta-llama/Llama-3-70B"),
    )
  }

  @Test fun `an unprovisioned model is refused even when it looks plausible`() {
    // Near-miss on the resident id: still not on disk, still a refusal. No fuzzy matching.
    assertEquals(
      ModelRequestOutcome.NotProvisioned("litert-community/gemma-4-E4B-it"),
      outcome("litert-community/gemma-4-E4B-it"),
    )
  }

  @Test fun `an empty registry still refuses an unknown model rather than serving the resident one`() {
    assertEquals(
      ModelRequestOutcome.NotProvisioned("something-else"),
      outcome("something-else", provisioned = emptySet()),
    )
  }

  // ---- the security boundary ----

  @Test fun `a client-named model never becomes a swap target unless it is already on disk`() {
    // The safety property the first cut's narrow guard provided, now carried by the registry: an
    // arbitrary LAN client cannot make the node fetch anything.
    listOf("../../etc/passwd", "http://evil/model", "org/enormous-70b", "").forEach { id ->
      val result = outcome(id, provisioned = setOf(resident))
      assertTrue(
        "must never swap to unprovisioned id '$id' (got $result)",
        result !is ModelRequestOutcome.SwapThenRetry,
      )
    }
  }

  @Test fun `a null resident id before first init does not break the decision`() {
    assertEquals(
      ModelRequestOutcome.SwapThenRetry(configured),
      outcome(configured, residentId = null),
    )
  }

  // --- The rendered 404 bodies -----------------------------------------------------------------
  //
  // Everything above pins which OUTCOME is chosen. None of it pins what the client is actually
  // TOLD: the bodies were composed inline inside RelaisHttpServer's private, socket-taking
  // rejectIfModelUnavailable, so the reason could have been dropped from the 404 and every test
  // here would still have passed.

  /**
   * The whole point of [ModelRequestOutcome.Incompatible] existing as a case separate from
   * [ModelRequestOutcome.NotProvisioned] is that the client is told the file is present but
   * unloadable. If the reason stops reaching the body, the case has no observable effect and the
   * caller is back to a generic model_not_found for a problem re-downloading cannot fix.
   */
  @Test fun `the incompatible 404 body carries the measured reason, not a generic not-found`() {
    val reason = "not loadable by this node's LiteRT-LM 0.12.0 runtime (engine-create fails)"
    val body = incompatibleModelMessage("litert-community/Qwen2.5-1.5B-Instruct", reason)

    assertEquals("model 'litert-community/Qwen2.5-1.5B-Instruct' is $reason", body)
    assertFalse(
      "must not read as a missing model — the file IS here, re-downloading will not help",
      body.contains("not provisioned"),
    )
  }

  /** The sibling body: absent model → point the caller at the discovery endpoint. */
  @Test fun `the not-provisioned 404 body points at the discovery endpoint`() {
    val body = notProvisionedModelMessage("someone/absent-model")

    assertEquals(
      "model 'someone/absent-model' is not provisioned on this node; " +
        "see GET /v1/models for what is available",
      body,
    )
  }
}

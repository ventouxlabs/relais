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

/*
 * The configured-model presence check (#362). A request for the configured model (or with `model`
 * omitted) is classified ServeResident, and [RelaisEngine.generate] used to cold-start it with
 * `ensureInitialized` — which, with the file not on disk, threw, recorded `lastInitFailed`, and put
 * the node in ERROR. The client got a 500 (or a 200 + SSE error), and the watchdog's revive was the
 * only thing that ever provisioned the model. Now `generate` checks first, under its lock: a missing
 * model kicks [RelaisEngine.ensureProvisionedInBackground] and throws [ModelNotOnDiskException], which
 * the HTTP dispatcher answers 503 + Retry-After exactly like a [ModelNotResidentException].
 *
 * Pure JVM (no Context, no Engine) — mirrors RelaisServedModel.kt.
 */

/**
 * True iff the engine is not resident AND the configured model's weights are not on disk:
 * [resolvedPath] is `RelaisModelProvisioner.pathFor(context, configuredId)`, which is null when
 * nothing on the device is known to hold the model, and otherwise MAY be the conventional default
 * path returned UNCHECKED (its rung 4 — where a provision will write). Both shapes are "missing".
 *
 * A ready engine is never missing: whatever is resident answers, and the disk is irrelevant to it.
 *
 * [fileExists] has no default, deliberately — the same reason `resolveModelPath`'s has none: with one,
 * every test row would silently run against the real filesystem and stop discriminating.
 */
internal fun configuredModelMissing(isReady: Boolean, resolvedPath: String?, fileExists: (String) -> Boolean): Boolean =
  !isReady && (resolvedPath == null || !fileExists(resolvedPath))

/**
 * Thrown by [RelaisEngine.generate] under the engine lock when the configured model is not resident
 * and its file is not on disk (#362). Carries no liveness side effects: by the time it is thrown a
 * background provision has been kicked (or was already running, or the node is already ERROR and the
 * watchdog owns retries), and the request itself must not record an init failure.
 */
class ModelNotOnDiskException(val modelId: String) :
  IllegalStateException("configured model '$modelId' is not on this device yet")

/**
 * Whether a failure must unwind to the HTTP dispatcher, which answers 503 + Retry-After: a
 * [ModelNotResidentException] (#352) or a [ModelNotOnDiskException] (#362), on a stream that has
 * written nothing. Anything else, or either after the header went out (not reachable today — the
 * engine throws both before any token), stays with the handler's existing post-commit handling.
 *
 * The ONE predicate every streaming rethrow site uses, so a new retryable type is added here once
 * rather than at each lane.
 */
internal fun isUncommittedRetryable(e: Throwable, committed: Boolean): Boolean =
  (e is ModelNotResidentException || e is ModelNotOnDiskException) && !committed

/** The client-facing 503 text for a [ModelNotOnDiskException]: it is being fetched; a retry is decided afresh. */
internal const val MODEL_NOT_ON_DISK_MESSAGE =
  "the configured model is not on this device yet; downloading — retry shortly"

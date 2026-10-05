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
 * model kicks a background provision in [RelaisEngine] (when [shouldKickProvision] says a live
 * service will own it) and throws [ModelNotOnDiskException], which
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
 * The model-state failures the HTTP dispatcher answers 503 + Retry-After instead of 500: the request
 * hit a model state that a retry can outlive. Sealed so the ONE place that picks the client text,
 * [modelStateMessage], is an exhaustive `when` — a new subtype does not compile until it has a message —
 * and so [isUncommittedRetryable] and the dispatcher test one type, not a list each site re-states.
 */
sealed class RetryableModelUnavailableException(message: String) : IllegalStateException(message)

/**
 * Thrown by [RelaisEngine.generate] under the engine lock when the configured model is not resident
 * and its file is not on disk (#362). Carries no liveness side effects: the request itself must not
 * record an init failure.
 *
 * [provisioning] is what the request could truthfully observe: a startup is in progress (or was just
 * kicked) — the service's own first download, a provision kick, a swap or a reload. False when nothing
 * is starting: the node is OFF (in-app chat only — no listeners, so no kick, see
 * [shouldKickProvision]), or the last attempt failed and the watchdog owns the retry. No default: the
 * message the client sees depends on it, so every throw site must decide.
 */
class ModelNotOnDiskException(val modelId: String, val provisioning: Boolean) :
  RetryableModelUnavailableException("configured model '$modelId' is not on this device yet")

/**
 * Whether a failure must unwind to the HTTP dispatcher, which answers 503 + Retry-After: any
 * [RetryableModelUnavailableException] ([ModelNotResidentException] #352, [ModelNotOnDiskException]
 * #362), on a stream that has written nothing. Anything else, or either after the header went out (not
 * reachable today — the engine throws both before any token), stays with the handler's existing
 * post-commit handling. Every streaming rethrow site uses this; the dispatcher uses [modelStateMessage],
 * keyed on the same sealed type — so a new retryable type is added in one place.
 */
internal fun isUncommittedRetryable(e: Throwable, committed: Boolean): Boolean =
  e is RetryableModelUnavailableException && !committed

/**
 * The client-facing 503 text for a retryable model state, or null when [e] is not one (the dispatcher
 * then falls through to its 500). The ONLY place the dispatcher decides "503 or not" and "which text".
 */
internal fun modelStateMessage(e: Throwable): String? =
  (e as? RetryableModelUnavailableException)?.let {
    when (it) {
      is ModelNotResidentException -> MODEL_NOT_RESIDENT_MESSAGE
      is ModelNotOnDiskException -> if (it.provisioning) MODEL_NOT_ON_DISK_STARTING_MESSAGE else MODEL_NOT_ON_DISK_IDLE_MESSAGE
    }
  }

/**
 * Whether [RelaisEngine.generate] should kick a background provision for a missing
 * configured model (#362) — i.e. whether a live service will own the outcome and nothing else will
 * retry it. All three inputs come off ONE liveness snapshot plus `lastInitFailed` read after it.
 *
 *  - [listenersUp] false → never. No live service: in-app chat with the node OFF reaches `generate`
 *    in-process, and a kick there would download and load a multi-GB model with no foreground service
 *    (RelaisInference's never-blind-cold-start contract), read STARTING on an OFF node for the whole
 *    download, and a failure would never be retried (the watchdog returns early when !shouldRun).
 *  - [lastInitFailed] false → kick: nothing has failed, nothing else is fetching.
 *  - [lastInitFailed] true and NOT [idleUnloaded] → no: that is ERROR with listeners up, and the
 *    watchdog's revive (with backoff) owns retries, not every request.
 *  - [lastInitFailed] true and [idleUnloaded] → kick. DEFENSIVE: with [listenersUp] this is the
 *    combination `computeNodeState`'s KDoc calls unreachable (and [shouldRecordProvisionFailure] keeps
 *    the kick from producing it). If it ever occurs, the watchdog treats `idleUnloaded && listenersUp`
 *    as healthy idle and returns BEFORE its revive, so a no-kick here would 503 forever.
 */
internal fun shouldKickProvision(lastInitFailed: Boolean, listenersUp: Boolean, idleUnloaded: Boolean): Boolean =
  listenersUp && (!lastInitFailed || idleUnloaded)

/**
 * Whether a provision kick's failure (its catch) may set `lastInitFailed`. Only if the node is still in
 * the state the kick put it in — so a stale failure never lands on a node something else has since
 * brought up or handed off:
 *  - [ready] → no: something else (an operator START, a swap) loaded an engine; it is not failed.
 *  - [idleUnloaded] → no: the kick's own begin CLEARED idle, and only `releaseIfIdle` (needs a resident
 *    engine) or a swap's idle restore set it again — either way the node is healthy idle now, and
 *    `lastInitFailed && idleUnloaded && listenersUp` is the combination NodeState calls unreachable.
 *  - [shutdownSinceKick] → no: a STOP (owns the outcome: OFF) or a swap (owns the engine) ran
 *    `RelaisEngine.shutdown()` after the kick started.
 * (A real init failure inside the kick is recorded by `ensureInitialized` itself, under the lock, at a
 * moment none of these can have happened; this decides only the PROVISIONING failure.)
 */
internal fun shouldRecordProvisionFailure(ready: Boolean, idleUnloaded: Boolean, shutdownSinceKick: Boolean): Boolean =
  !ready && !idleUnloaded && !shutdownSinceKick

/** 503 text for a [ModelNotOnDiskException] while a startup is in progress. */
internal const val MODEL_NOT_ON_DISK_STARTING_MESSAGE =
  "the configured model is not on this device yet; the node is starting — retry shortly"

/** 503 text for a [ModelNotOnDiskException] while nothing is starting (last attempt failed; the watchdog owns the retry). */
internal const val MODEL_NOT_ON_DISK_IDLE_MESSAGE =
  "the configured model is not on this device yet and the node is not starting — retry later"

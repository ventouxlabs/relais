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

package cc.grepon.relais.widget

import cc.grepon.relais.core.NodeState
import kotlinx.coroutines.delay

/** Bound on persisted model output in the launcher (a public surface). Mirrors the tile's cap. */
const val RESPONSE_CAP = 600

/** The four display phases the home-screen widget (#3) can render. */
enum class WidgetPhase { IDLE, LOADING, DONE, ERROR }

/**
 * Pure value object the [RelaisWidget] renders from (persisted via Glance Preferences state). Kept
 * free of Android/Glance types so the transition machine + cap are JVM-unit-testable.
 *
 * - [prompt]: the canned prompt that produced the current state (null in IDLE).
 * - [response]: the (capped) answer in DONE, or the error message in ERROR (null in IDLE/LOADING).
 *
 * Build states only through the transition helpers ([loading]/[done]/[error]/[idle]) so the cap is
 * always applied and a phase never leaks another phase's fields (e.g. LOADING never carries a stale
 * response).
 */
data class WidgetUiState(
  val phase: WidgetPhase,
  val prompt: String?,
  val response: String?,
) {
  /** Enters LOADING for [prompt], dropping any prior answer so a stale response can't flash. */
  fun loading(prompt: String): WidgetUiState =
    WidgetUiState(WidgetPhase.LOADING, prompt = prompt, response = null)

  /** Settles to DONE, keeping the originating [prompt] and carrying the capped [response]. */
  fun done(response: String): WidgetUiState =
    copy(phase = WidgetPhase.DONE, response = capResponse(response))

  /** Settles to ERROR, keeping the originating [prompt] and carrying the capped [message]. */
  fun error(message: String): WidgetUiState =
    copy(phase = WidgetPhase.ERROR, response = capResponse(message))

  /** CLEAR: back to the empty IDLE state (no prompt, no response). */
  fun cleared(): WidgetUiState = idle()

  companion object {
    /** The cleared/initial state. */
    fun idle(): WidgetUiState = WidgetUiState(WidgetPhase.IDLE, prompt = null, response = null)
  }
}

/** Caps [text] to [RESPONSE_CAP] — a no-op at/under the cap. Pure; unit-tested. */
fun capResponse(text: String): String = text.take(RESPONSE_CAP)

/**
 * What a widget tap resolves to (feature-22, task 4(c)):
 *  - [RUN]: the engine is resident — run the prompt now.
 *  - [WARM_THEN_RUN]: the node is up but its engine was released by idle-TTL — kick a reload, then
 *    run once it is back.
 *  - [IGNORE]: nothing to run and nothing to warm.
 */
enum class WidgetTapAction { RUN, WARM_THEN_RUN, IGNORE }

/**
 * Cold-start guard (pure, unit-tested), keyed on the COMPUTED [nodeState] — the single source of
 * truth ([cc.grepon.relais.core.computeNodeState]) — never on raw engine flags:
 *  - a null/blank [prompt] is [WidgetTapAction.IGNORE] in every state (nothing to run, nothing to
 *    warm for);
 *  - LIVE → [WidgetTapAction.RUN] (the engine is resident and the device is not throttling);
 *  - HOT → [WidgetTapAction.IGNORE] (engine resident but the device is throttling; never add
 *    inference heat while hot — the tile's policy, and the policy [RelaisWidget]'s `canRun` already
 *    renders. This is the one row the boolean `isReady` gate this replaced got wrong);
 *  - IDLE → [WidgetTapAction.WARM_THEN_RUN]. IDLE already implies `shouldRun && listenersUp &&
 *    idleUnloaded`, a STRONGER proof that the foreground service is alive than the `wasIdleUnloaded`
 *    flag [cc.grepon.relais.core.RelaisInference]'s self-heal keys on — so the warm is a reload
 *    behind a live service, not the cold start this guard exists to prevent;
 *  - OFF / STARTING / ERROR → [WidgetTapAction.IGNORE]. A stale tap on a node the operator has since
 *    stopped is `OFF` (`shutdown()` clears `idleUnloaded`), so it can never warm — the second
 *    defence after the flag itself.
 */
fun shouldRunWidgetPrompt(nodeState: NodeState, prompt: String?): WidgetTapAction = when {
  prompt.isNullOrBlank() -> WidgetTapAction.IGNORE
  else -> when (nodeState) {
    NodeState.LIVE -> WidgetTapAction.RUN
    NodeState.IDLE -> WidgetTapAction.WARM_THEN_RUN
    NodeState.HOT, NodeState.OFF, NodeState.STARTING, NodeState.ERROR -> WidgetTapAction.IGNORE
  }
}

/**
 * Whether [WidgetPromptWorker] should wait for a reload before re-checking readiness (pure,
 * unit-tested): only when the engine is not [ready] AND either the tap that enqueued it [warm]ed the
 * node or a reload is in flight ([startupInProgress]) for some other reason. `warm` is an INPUT, not
 * a flag re-read: by the time WorkManager runs `doWork` a fast reload may have begun and ended, so
 * `startupInProgress` alone would let the worker fall straight through to "node off".
 */
fun shouldAwaitWarm(ready: Boolean, warm: Boolean, startupInProgress: Boolean): Boolean =
  !ready && (warm || startupInProgress)

/**
 * Whether the warm the worker is waiting on has FAILED, so the wait can settle now instead of
 * running out the cap (pure, unit-tested): no reload in flight ([startupInProgress] false) AND the
 * last attempt recorded a failure ([lastInitFailed]). Sound because the kick publishes
 * `startupInProgress` on the caller before returning and `ensureInitialized` clears `lastInitFailed`
 * at attempt START with `endStartup()` as its LAST write — so a reader that sees the flag clear sees
 * the verdict of the attempt that just ended, and a stale `lastInitFailed` beside an in-flight retry
 * keeps polling (slot 3 > 4, exactly as `computeNodeState` orders them). The `!isReady` conjunct is
 * the [awaitEngineReady] check that precedes it. Readers take the liveness snapshot FIRST, then the
 * engine flag (`core/NodeState.kt`'s read-order rule).
 */
fun shouldGiveUpWarm(startupInProgress: Boolean, lastInitFailed: Boolean): Boolean =
  !startupInProgress && lastInitFailed

/**
 * Polls [isReady] every [intervalMs] for at most [maxIterations] sleeps and returns the final answer
 * — the ENGINE's readiness, never a liveness flag the reload may already have cleared. Returns
 * immediately (no sleep) when already ready. [giveUp] is consulted AFTER [isReady] on every
 * iteration, so a reload that just finished always wins over a same-poll give-up; when it fires the
 * wait settles `false` at once instead of running out the cap. Pure over its inputs; unit-tested on
 * virtual time.
 */
suspend fun awaitEngineReady(
  isReady: () -> Boolean,
  giveUp: () -> Boolean,
  intervalMs: Long,
  maxIterations: Int,
): Boolean {
  repeat(maxIterations) {
    if (isReady()) return true
    if (giveUp()) return false // AFTER isReady: a reload that just finished wins
    delay(intervalMs)
  }
  return isReady()
}

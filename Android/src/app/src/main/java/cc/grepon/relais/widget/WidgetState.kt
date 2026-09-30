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
 *    inference heat while hot — the tile's policy, and the policy [widgetCanRun] already renders.
 *    This is the one row the boolean `isReady` gate this replaced got wrong);
 *  - IDLE while thermally hot ([thermalHot] true, Codex P2) → [WidgetTapAction.IGNORE].
 *    [cc.grepon.relais.core.computeNodeState] can only report HOT for a RESIDENT engine (`ready &&
 *    listenersUp`); an idle-unloaded node reads IDLE regardless of raw thermal status, so without
 *    this row a tap warmed the engine and ran inference throttled — the exact heat HOT's policy
 *    exists to refuse. Mirrors HOT's IGNORE, extended to an engine that isn't resident yet;
 *  - IDLE otherwise → [WidgetTapAction.WARM_THEN_RUN]. IDLE already implies `shouldRun && listenersUp &&
 *    idleUnloaded`, a STRONGER proof that the foreground service is alive than the `wasIdleUnloaded`
 *    flag [cc.grepon.relais.core.RelaisInference]'s self-heal keys on — so the warm is a reload
 *    behind a live service, not the cold start this guard exists to prevent;
 *  - OFF / STARTING / ERROR → [WidgetTapAction.IGNORE]. A stale tap on a node the operator has since
 *    stopped is `OFF` (`shutdown()` clears `idleUnloaded`), so it can never warm — the second
 *    defence after the flag itself.
 */
fun shouldRunWidgetPrompt(nodeState: NodeState, thermalHot: Boolean, prompt: String?): WidgetTapAction = when {
  prompt.isNullOrBlank() -> WidgetTapAction.IGNORE
  nodeState == NodeState.IDLE && thermalHot -> WidgetTapAction.IGNORE
  else -> when (nodeState) {
    NodeState.LIVE -> WidgetTapAction.RUN
    NodeState.IDLE -> WidgetTapAction.WARM_THEN_RUN
    NodeState.HOT, NodeState.OFF, NodeState.STARTING, NodeState.ERROR -> WidgetTapAction.IGNORE
  }
}

/**
 * Whether the widget's prompt buttons should accept a tap (pure, unit-tested) — the VISUAL half of
 * the cold-start guard; [shouldRunWidgetPrompt] remains the authoritative gate re-checked inside
 * [RunPromptAction] at tap time (this is only what [RelaisWidget] renders as enabled/disabled).
 *  - a [phase] already [WidgetPhase.LOADING] never re-triggers a second tap, in any state;
 *  - LIVE → runnable (the engine is resident and, by construction, not thermally hot — `HOT` would
 *    have fired first in [cc.grepon.relais.core.computeNodeState]);
 *  - IDLE while thermally hot ([thermalHot] true, Codex P2 fixwave round 2) → NOT runnable. Without
 *    this row the buttons stayed enabled and the status line still said "tap to warm" while a tap
 *    silently no-opped via [shouldRunWidgetPrompt]'s `IGNORE` — the same defect as the tile's stale
 *    "tap to warm" label, on the widget's render instead of its subtitle;
 *  - IDLE otherwise → runnable (mirrors [shouldRunWidgetPrompt]'s `WARM_THEN_RUN`);
 *  - HOT / OFF / STARTING / ERROR → never runnable (mirrors [shouldRunWidgetPrompt]'s `IGNORE` rows).
 */
/**
 * What the widget DISPLAYS for [nodeState] (#358): STARTING with nothing actually starting — no
 * startup in flight, no listener bound, no engine, and not idle-unloaded — reads OFF. The same
 * condition as the control panel's `looksStalled` (#217), plus [listenersUp]; an idle-unloaded node
 * whose listeners dropped (a failed HTTPS rebind) still has a live service that retries, and the
 * panel keeps that at STARTING too.
 *
 * [cc.grepon.relais.core.computeNodeState] maps `shouldRun` with nothing running to STARTING on
 * purpose: it keeps the watchdog's shield down so its revive dispatches. But a force-stop or an app
 * update cancels that alarm and kills the service, so nothing is starting and nothing will. The
 * control panel detects exactly this (#217's stalledStart) and says OFFLINE; the widget kept saying
 * `starting…` with disabled buttons — a dead end that also disagreed with the panel. A healthy
 * start publishes `startupInProgress` before any work (including a multi-GB download), so the only
 * healthy moment this can misread is the sliver between the service's creation and that publish;
 * the service's refresher corrects it within one tick (5 s). Widget-only: the watchdog and every
 * other surface keep computeNodeState's STARTING. This only changes what a render SHOWS; after a
 * force-stop nothing renders on its own, so the app process also re-renders once at start
 * (`RelaisApplication`).
 */
fun widgetDisplayState(
  nodeState: NodeState,
  startupInProgress: Boolean,
  listenersUp: Boolean,
  ready: Boolean,
  idleUnloaded: Boolean,
): NodeState =
  if (nodeState == NodeState.STARTING && !startupInProgress && !listenersUp && !ready && !idleUnloaded) {
    NodeState.OFF
  } else {
    nodeState
  }

fun widgetCanRun(nodeState: NodeState, thermalHot: Boolean, phase: WidgetPhase): Boolean =
  phase != WidgetPhase.LOADING &&
    when (nodeState) {
      NodeState.LIVE -> true
      NodeState.IDLE -> !thermalHot
      NodeState.HOT, NodeState.OFF, NodeState.STARTING, NodeState.ERROR -> false
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

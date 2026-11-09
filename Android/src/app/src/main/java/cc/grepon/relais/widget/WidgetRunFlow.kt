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

import androidx.datastore.preferences.core.MutablePreferences
import androidx.work.Data
import java.util.UUID
import kotlinx.coroutines.CancellationException

// #353: the decisions of a widget prompt run, extracted from the Android worker/action so the
// heartbeat steps, the token each write uses and what a tap stores are unit-testable on the JVM.

internal const val KEY_TEMPLATE_ID = "template_id"
internal const val KEY_APP_WIDGET_ID = "app_widget_id"
internal const val KEY_WARM = "warm"
internal const val KEY_RUN_TOKEN = "run_token"

internal fun widgetWorkInput(appWidgetId: Int, templateId: String?, warm: Boolean, runToken: String): Data =
  Data.Builder()
    .putInt(KEY_APP_WIDGET_ID, appWidgetId)
    .putBoolean(KEY_WARM, warm)
    .putString(KEY_RUN_TOKEN, runToken)
    .apply { templateId?.let { putString(KEY_TEMPLATE_ID, it) } }
    .build()

/** The run a worker owns, as enqueued by the tap; null for work enqueued before tokens existed. */
internal fun runTokenFrom(input: Data): String? = input.getString(KEY_RUN_TOKEN)

/** What one tap persists and enqueues: both carry the same [runToken]. */
internal data class TapRun(val runToken: String, val state: WidgetUiState)

internal fun startTapRun(nowMs: Long, runToken: String = UUID.randomUUID().toString()): TapRun =
  TapRun(runToken, tapLoadingState(runToken, nowMs))

/**
 * A worker's token-bound access to the widget's stored state. [edit] is one state edit
 * (`updateAppWidgetState`); every write goes through [ownedRunPrefs] with this session's token, so a
 * superseded, cleared or stale-settled run writes nothing.
 */
internal class WidgetRunSession(
  private val runToken: String?,
  private val nowMs: () -> Long,
  private val edit: suspend (suspend (MutablePreferences) -> Unit) -> Unit,
  private val render: suspend () -> Unit,
) {
  /** Moves the heartbeat to now; false when this run no longer owns the stored LOADING. */
  suspend fun beat(): Boolean {
    var owned = false
    edit { prefs -> owned = ownedRunPrefs(prefs, runToken) { it.heartbeat(nowMs()) } }
    return owned
  }

  /** Writes [state] and re-renders, only while this run still owns the LOADING. */
  suspend fun settle(state: WidgetUiState): Boolean {
    var wrote = false
    edit { prefs -> wrote = ownedRunPrefs(prefs, runToken) { state } }
    if (wrote) render()
    return wrote
  }
}

internal enum class RunResult { SUCCESS, FAILURE }

/** The effects a run needs; the worker implements them over WorkManager, the engine and Glance. */
internal interface WidgetRunIo {
  suspend fun beat(): Boolean
  suspend fun settle(state: WidgetUiState): Boolean
  /** Waits for a warming engine when [shouldAwaitWarm] says so. */
  suspend fun awaitWarmIfNeeded()
  fun engineReady(): Boolean
  fun thermalHot(): Boolean
  /** Runs the prompt (takes the engine lock; may take minutes). */
  suspend fun complete(): String
  fun isNodeNotReady(t: Throwable): Boolean
  fun log(message: String, t: Throwable? = null)
}

/**
 * The worker's whole decision sequence. A beat precedes every step that can wait (start, after the
 * warm wait, before the engine lock + inference); a `false` beat means the run was superseded,
 * cleared or settled stale, and it ends quietly without touching the state.
 */
internal suspend fun runWidgetPrompt(io: WidgetRunIo): RunResult {
  if (!io.beat()) return RunResult.SUCCESS
  io.awaitWarmIfNeeded()
  if (!io.beat()) return RunResult.SUCCESS
  if (!io.engineReady()) {
    io.log("engine not resident at run time; skipping widget prompt")
    io.settle(WidgetUiState.idle().error("node off — open app to start"))
    return RunResult.FAILURE
  }
  if (io.thermalHot()) {
    // Codex P2: the engine came up (or was already up) but the device is thermally hot NOW — the
    // ~20 s reload window is enough time for that to change since the tap-time gate passed. Never
    // add inference heat while hot, same as the HOT NodeState's policy for a resident engine.
    io.log("device thermally hot at run time; skipping widget prompt")
    io.settle(WidgetUiState.idle().error("hot — throttling, try later"))
    return RunResult.FAILURE
  }
  if (!io.beat()) return RunResult.SUCCESS // before the engine lock + inference
  val answer = try {
    io.complete()
  } catch (t: CancellationException) {
    throw t // never swallow cancellation
  } catch (t: Throwable) {
    val message = if (io.isNodeNotReady(t)) "node went off" else "inference failed"
    io.log("widget prompt failed: $message", t)
    io.settle(WidgetUiState.idle().loading(WIDGET_PROMPT).error(message))
    return RunResult.FAILURE
  }
  io.settle(WidgetUiState.idle().loading(WIDGET_PROMPT).done(answer))
  return RunResult.SUCCESS
}

/** Runs [settle] (a [settleStaleWidget] call) so a failure is logged and never stops the caller. */
internal suspend fun settleStaleWidgetSafely(settle: suspend () -> Boolean, warn: (Throwable) -> Unit): Boolean =
  try {
    settle()
  } catch (t: CancellationException) {
    throw t
  } catch (t: Throwable) {
    warn(t)
    false
  }

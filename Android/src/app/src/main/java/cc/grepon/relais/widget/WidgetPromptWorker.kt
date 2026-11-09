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

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.grepon.relais.ModelSwitch
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.RelaisLivenessState
import cc.grepon.relais.core.RelaisInference
import cc.grepon.relais.templates.WorkflowRegistry

private const val TAG = "WidgetPromptWorker"

/** The fixed canned prompt the widget runs (the template only supplies the system prompt). */
const val WIDGET_PROMPT = "Give me a one-line status check."

// Glance Preferences keys backing the persisted [WidgetUiState]. DataStore-backed and separate from
// the node's EncryptedSharedPreferences — output here is non-secret, capped, and operator-clearable.
private val PHASE_KEY = stringPreferencesKey("relais_widget_phase")
private val PROMPT_KEY = stringPreferencesKey("relais_widget_prompt")
private val RESPONSE_KEY = stringPreferencesKey("relais_widget_response")
private val HEARTBEAT_KEY = longPreferencesKey("relais_widget_heartbeat")
private val RUN_TOKEN_KEY = stringPreferencesKey("relais_widget_run_token")

/** Serializes [state] into Glance's [MutablePreferences] (the persisted widget store). */
fun writeState(prefs: MutablePreferences, state: WidgetUiState) {
  prefs[PHASE_KEY] = state.phase.name
  state.prompt?.let { prefs[PROMPT_KEY] = it } ?: prefs.remove(PROMPT_KEY)
  state.response?.let { prefs[RESPONSE_KEY] = it } ?: prefs.remove(RESPONSE_KEY)
  state.heartbeatMs?.let { prefs[HEARTBEAT_KEY] = it } ?: prefs.remove(HEARTBEAT_KEY)
  state.runToken?.let { prefs[RUN_TOKEN_KEY] = it } ?: prefs.remove(RUN_TOKEN_KEY)
}

/** Reconstructs the [WidgetUiState] from Glance's [Preferences]; missing/garbled phase reads as IDLE. */
fun readState(prefs: Preferences): WidgetUiState {
  val phase = prefs[PHASE_KEY]?.let { name -> WidgetPhase.entries.firstOrNull { it.name == name } }
    ?: WidgetPhase.IDLE
  return WidgetUiState(
    phase = phase,
    prompt = prefs[PROMPT_KEY],
    response = prefs[RESPONSE_KEY],
    runToken = prefs[RUN_TOKEN_KEY],
    heartbeatMs = prefs[HEARTBEAT_KEY],
  )
}

/**
 * Runs the widget's canned prompt off the broadcast thread (long inference must outlive the ~10 s
 * ActionCallback/broadcast limit — hence a Worker). Re-asserts [RelaisInference.isReady] (defense in
 * depth: the action already gated, but the node can stop between enqueue and run — this worker never
 * kicks a reload itself), resolves the template's system prompt via [WorkflowRegistry], runs the
 * in-process inference, and writes DONE/ERROR (with a capped response) back into the widget's Glance
 * state, then re-renders. When the enqueuing tap warmed an idle node (`warm` input, feature-22) or a
 * reload is otherwise in flight, it first waits for the engine — [awaitEngineReady] on
 * [ModelSwitch]'s poll constants, a 60 s cap, giving up within one poll once [shouldGiveUpWarm] says
 * the reload failed — and settles the existing "node off" error if the engine never comes back.
 * Once the engine IS ready, re-asserts [thermalHot] too (Codex P2, defense in depth): the ~20 s
 * reload window is long enough for the device to heat up after the tap-time gate already passed, so
 * this is the same "never add inference heat while hot" HOT already enforces for a resident engine,
 * checked again just before the prompt actually runs. Enqueued with `enqueueUniqueWork(REPLACE)` and a per-tap run token (#353): a
 * superseded worker's result is discarded rather than overwriting newer state.
 */
class WidgetPromptWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val appWidgetId = inputData.getInt(KEY_APP_WIDGET_ID, -1).takeIf { it >= 0 }
      ?: return Result.failure()
    val templateId = inputData.getString(KEY_TEMPLATE_ID)?.takeIf { it.isNotBlank() }
    val glanceId = runCatching {
      GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
    }.getOrElse {
      Log.w(TAG, "no GlanceId for appWidgetId=$appWidgetId; widget removed?", it)
      return Result.failure()
    }
    val session = WidgetRunSession(
      runToken = runTokenFrom(inputData),
      nowMs = System::currentTimeMillis,
      edit = { f -> updateAppWidgetState(applicationContext, glanceId) { f(it) } },
      render = { RelaisWidget().update(applicationContext, glanceId) },
    )
    val warm = inputData.getBoolean(KEY_WARM, false)
    val io = object : WidgetRunIo {
      override suspend fun beat() = session.beat()
      override suspend fun settle(state: WidgetUiState) = session.settle(state)

      override suspend fun awaitWarmIfNeeded() {
        // Read order: the liveness snapshot FIRST, then the engine flag (computeNodeState's rule).
        val liveness = RelaisLivenessState.snapshot
        val mustWait = shouldAwaitWarm(
          ready = RelaisInference.isReady(),
          warm = warm,
          startupInProgress = liveness.startupInProgress,
        )
        if (!mustWait) return
        // The tap already kicked the reload (or another caller did); wait for the ENGINE, not a flag —
        // by the time WorkManager runs this, a fast reload may have begun AND ended. Bounded at 60 s
        // (500 ms × 120); the reload measured 19.4–19.8 s on rango/E2B (feature-22 Task 5). A reload
        // that FAILS settles within one poll instead (giveUp: snapshot first, then the engine flag).
        Log.i(TAG, "engine warming; waiting for it to come up")
        awaitEngineReady(
          isReady = RelaisInference::isReady,
          giveUp = {
            val s = RelaisLivenessState.snapshot
            shouldGiveUpWarm(
              startupInProgress = s.startupInProgress,
              lastInitFailed = RelaisEngine.lastInitFailed,
            )
          },
          intervalMs = ModelSwitch.RELOAD_POLL_INTERVAL_MS,
          maxIterations = ModelSwitch.MAX_RELOAD_POLL_ITERATIONS,
        )
      }

      override fun engineReady() = RelaisInference.isReady()
      override fun thermalHot() = cc.grepon.relais.core.thermalHot()

      override suspend fun complete(): String {
        val system = WorkflowRegistry.resolve(applicationContext, templateId)?.system
        return RelaisInference.completeText(applicationContext, WIDGET_PROMPT, system = system)
      }

      override fun isNodeNotReady(t: Throwable) = t is RelaisInference.NodeNotReadyException
      override fun log(message: String, t: Throwable?) {
        if (t == null) Log.i(TAG, message) else Log.e(TAG, message, t)
      }
    }
    return when (runWidgetPrompt(io)) {
      RunResult.SUCCESS -> Result.success()
      RunResult.FAILURE -> Result.failure()
    }
  }

  companion object {
    /** Enqueues a per-widget unique run (REPLACE: a re-tap supersedes any older run; see [WIDGET_WORK_POLICY]). */
    fun enqueue(context: Context, glanceId: GlanceId, templateId: String?, warm: Boolean, runToken: String) {
      val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
      val request = OneTimeWorkRequestBuilder<WidgetPromptWorker>()
        .setInputData(widgetWorkInput(appWidgetId, templateId, warm, runToken))
        .build()
      WorkManager.getInstance(context.applicationContext)
        .enqueueUniqueWork(widgetWorkName(appWidgetId), WIDGET_WORK_POLICY, request)
    }
  }
}

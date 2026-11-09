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
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.grepon.relais.ModelSwitch
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.RelaisLivenessState
import cc.grepon.relais.core.RelaisInference
import cc.grepon.relais.core.thermalHot
import cc.grepon.relais.templates.WorkflowRegistry

private const val TAG = "WidgetPromptWorker"
private const val KEY_TEMPLATE_ID = "template_id"
private const val KEY_APP_WIDGET_ID = "app_widget_id"
private const val KEY_WARM = "warm"
private const val KEY_RUN_TOKEN = "run_token"

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
    val runToken = inputData.getString(KEY_RUN_TOKEN)
    val glanceId = runCatching {
      GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
    }.getOrElse {
      Log.w(TAG, "no GlanceId for appWidgetId=$appWidgetId; widget removed?", it)
      return Result.failure()
    }

    // Heartbeats (#353): the stored LOADING is only declared dead after a silence well above the
    // longest single step, so beat before each step. A false return means this run was superseded
    // (re-tap), cleared, or already settled stale: do nothing, the owner of the state moved on.
    if (!beat(glanceId, runToken)) return Result.success()

    // Read order: the liveness snapshot FIRST, then the engine flag (computeNodeState's rule).
    val warm = inputData.getBoolean(KEY_WARM, false)
    val liveness = RelaisLivenessState.snapshot
    val mustWait = shouldAwaitWarm(
      ready = RelaisInference.isReady(),
      warm = warm,
      startupInProgress = liveness.startupInProgress,
    )
    if (mustWait) {
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
    if (!beat(glanceId, runToken)) return Result.success()
    if (!RelaisInference.isReady()) {
      Log.i(TAG, "engine not resident at run time; skipping widget prompt")
      settle(glanceId, runToken, WidgetUiState.idle().error("node off — open app to start"))
      return Result.failure()
    }
    if (thermalHot()) {
      // Codex P2: the engine came up (or was already up) but the device is thermally hot NOW — the
      // ~20 s reload window is enough time for that to change since the tap-time gate passed. Never
      // add inference heat while hot, same as the HOT NodeState's policy for a resident engine.
      Log.i(TAG, "device thermally hot at run time; skipping widget prompt")
      settle(glanceId, runToken, WidgetUiState.idle().error("hot — throttling, try later"))
      return Result.failure()
    }

    if (!beat(glanceId, runToken)) return Result.success() // before the engine lock + inference
    val system = WorkflowRegistry.resolve(applicationContext, templateId)?.system
    val answer = runCatching {
      RelaisInference.completeText(applicationContext, WIDGET_PROMPT, system = system)
    }.getOrElse {
      if (it is kotlinx.coroutines.CancellationException) throw it // never swallow cancellation
      if (it is RelaisInference.NodeNotReadyException) {
        Log.i(TAG, "node went down mid-run", it)
        settle(glanceId, runToken, WidgetUiState.idle().loading(WIDGET_PROMPT).error("node went off"))
      } else {
        Log.e(TAG, "widget prompt failed", it) // never silently swallow
        settle(glanceId, runToken, WidgetUiState.idle().loading(WIDGET_PROMPT).error("inference failed"))
      }
      return Result.failure()
    }

    settle(glanceId, runToken, WidgetUiState.idle().loading(WIDGET_PROMPT).done(answer))
    return Result.success()
  }

  /**
   * Persists [state] and re-renders — only if this run still owns the stored LOADING ([ownedRunPrefs]);
   * a late worker never overwrites an ERROR, a CLEAR or a newer tap's LOADING.
   */
  private suspend fun settle(glanceId: GlanceId, runToken: String?, state: WidgetUiState) {
    var wrote = false
    updateAppWidgetState(applicationContext, glanceId) { prefs ->
      wrote = ownedRunPrefs(prefs, runToken) { state }
    }
    if (wrote) RelaisWidget().update(applicationContext, glanceId) else Log.i(TAG, "run superseded; result discarded")
  }

  /** Moves this run's heartbeat to now; false when the run no longer owns the stored LOADING. */
  private suspend fun beat(glanceId: GlanceId, runToken: String?): Boolean {
    var owned = false
    updateAppWidgetState(applicationContext, glanceId) { prefs ->
      owned = ownedRunPrefs(prefs, runToken) { it.heartbeat(System.currentTimeMillis()) }
    }
    return owned
  }

  companion object {
    /** Enqueues a per-widget unique run (REPLACE: a re-tap supersedes any older run; see [WIDGET_WORK_POLICY]). */
    fun enqueue(context: Context, glanceId: GlanceId, templateId: String?, warm: Boolean, runToken: String) {
      val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
      val request = OneTimeWorkRequestBuilder<WidgetPromptWorker>()
        .setInputData(inputData(appWidgetId, templateId, warm, runToken))
        .build()
      WorkManager.getInstance(context.applicationContext)
        .enqueueUniqueWork(widgetWorkName(appWidgetId), WIDGET_WORK_POLICY, request)
    }

    private fun inputData(appWidgetId: Int, templateId: String?, warm: Boolean, runToken: String): Data =
      Data.Builder()
        .putInt(KEY_APP_WIDGET_ID, appWidgetId)
        .putBoolean(KEY_WARM, warm)
        .putString(KEY_RUN_TOKEN, runToken)
        .apply { templateId?.let { putString(KEY_TEMPLATE_ID, it) } }
        .build()
  }
}

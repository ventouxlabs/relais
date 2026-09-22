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
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
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

/** The fixed canned prompt the widget runs (the template only supplies the system prompt). */
const val WIDGET_PROMPT = "Give me a one-line status check."

// Glance Preferences keys backing the persisted [WidgetUiState]. DataStore-backed and separate from
// the node's EncryptedSharedPreferences — output here is non-secret, capped, and operator-clearable.
private val PHASE_KEY = stringPreferencesKey("relais_widget_phase")
private val PROMPT_KEY = stringPreferencesKey("relais_widget_prompt")
private val RESPONSE_KEY = stringPreferencesKey("relais_widget_response")

/** Serializes [state] into Glance's [MutablePreferences] (the persisted widget store). */
fun writeState(prefs: MutablePreferences, state: WidgetUiState) {
  prefs[PHASE_KEY] = state.phase.name
  state.prompt?.let { prefs[PROMPT_KEY] = it } ?: prefs.remove(PROMPT_KEY)
  state.response?.let { prefs[RESPONSE_KEY] = it } ?: prefs.remove(RESPONSE_KEY)
}

/** Reconstructs the [WidgetUiState] from Glance's [Preferences]; missing/garbled phase reads as IDLE. */
fun readState(prefs: Preferences): WidgetUiState {
  val phase = prefs[PHASE_KEY]?.let { name -> WidgetPhase.entries.firstOrNull { it.name == name } }
    ?: WidgetPhase.IDLE
  return WidgetUiState(phase = phase, prompt = prefs[PROMPT_KEY], response = prefs[RESPONSE_KEY])
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
 * checked again just before the prompt actually runs. Enqueued with `enqueueUniqueWork(KEEP)` so
 * rapid taps don't stack inferences on the single engine.
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
    if (!RelaisInference.isReady()) {
      Log.i(TAG, "engine not resident at run time; skipping widget prompt")
      settle(glanceId, WidgetUiState.idle().error("node off — open app to start"))
      return Result.failure()
    }
    if (thermalHot()) {
      // Codex P2: the engine came up (or was already up) but the device is thermally hot NOW — the
      // ~20 s reload window is enough time for that to change since the tap-time gate passed. Never
      // add inference heat while hot, same as the HOT NodeState's policy for a resident engine.
      Log.i(TAG, "device thermally hot at run time; skipping widget prompt")
      settle(glanceId, WidgetUiState.idle().error("hot — throttling, try later"))
      return Result.failure()
    }

    val system = WorkflowRegistry.resolve(applicationContext, templateId)?.system
    val answer = runCatching {
      RelaisInference.completeText(applicationContext, WIDGET_PROMPT, system = system)
    }.getOrElse {
      if (it is kotlinx.coroutines.CancellationException) throw it // never swallow cancellation
      if (it is RelaisInference.NodeNotReadyException) {
        Log.i(TAG, "node went down mid-run", it)
        settle(glanceId, WidgetUiState.idle().loading(WIDGET_PROMPT).error("node went off"))
      } else {
        Log.e(TAG, "widget prompt failed", it) // never silently swallow
        settle(glanceId, WidgetUiState.idle().loading(WIDGET_PROMPT).error("inference failed"))
      }
      return Result.failure()
    }

    settle(glanceId, WidgetUiState.idle().loading(WIDGET_PROMPT).done(answer))
    return Result.success()
  }

  /** Persists [state] into the widget's Glance store and re-renders it. */
  private suspend fun settle(glanceId: GlanceId, state: WidgetUiState) {
    updateAppWidgetState(applicationContext, glanceId) { prefs -> writeState(prefs, state) }
    RelaisWidget().update(applicationContext, glanceId)
  }

  companion object {
    /** Enqueues a per-widget unique run (KEEP coalesces rapid taps onto the single engine lock). */
    fun enqueue(context: Context, glanceId: GlanceId, templateId: String?, warm: Boolean) {
      val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
      val request = OneTimeWorkRequestBuilder<WidgetPromptWorker>()
        .setInputData(inputData(appWidgetId, templateId, warm))
        .build()
      WorkManager.getInstance(context.applicationContext)
        .enqueueUniqueWork("relais-widget-$appWidgetId", ExistingWorkPolicy.KEEP, request)
    }

    private fun inputData(appWidgetId: Int, templateId: String?, warm: Boolean): Data =
      Data.Builder()
        .putInt(KEY_APP_WIDGET_ID, appWidgetId)
        .putBoolean(KEY_WARM, warm)
        .apply { templateId?.let { putString(KEY_TEMPLATE_ID, it) } }
        .build()
  }
}

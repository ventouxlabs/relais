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
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Re-tapping must start a fresh run, never be coalesced onto an older, unfinished worker (#353): a
 * tap is only possible once the phase has left LOADING, so any worker still under this unique name
 * is superseded, and its late result is discarded by [ownedRunPrefs] anyway.
 */
internal val WIDGET_WORK_POLICY = ExistingWorkPolicy.REPLACE

internal fun widgetWorkName(appWidgetId: Int) = "relais-widget-$appWidgetId"

/** The LOADING state a tap writes: owned by [runToken], first heartbeat [nowMs]. */
internal fun tapLoadingState(runToken: String, nowMs: Long): WidgetUiState =
  WidgetUiState.idle().loading(WIDGET_PROMPT, runToken = runToken, nowMs = nowMs)

/**
 * Compare-and-set for a worker's writes: applies [next] only while the stored state is still the
 * LOADING owned by [runToken]. A worker that was superseded (re-tap), cleared (CLEAR) or already
 * settled stale to ERROR finds a different state and writes nothing. Runs inside the caller's single
 * state edit, so the check and the write are one step. Returns whether it wrote.
 */
internal fun ownedRunPrefs(
  prefs: MutablePreferences,
  runToken: String?,
  next: (WidgetUiState) -> WidgetUiState,
): Boolean {
  val state = readState(prefs)
  if (state.phase != WidgetPhase.LOADING || state.runToken != runToken) return false
  writeState(prefs, next(state))
  return true
}

/** Settles a stale LOADING in [prefs] (see [settleStaleLoading]); returns whether it changed. */
internal fun settleStalePrefs(prefs: MutablePreferences, nowMs: Long, work: WidgetWork): Boolean {
  val state = readState(prefs)
  val next = settleStaleLoading(state, nowMs, work)
  if (next == state) return false
  writeState(prefs, next)
  return true
}

internal fun widgetWorkOf(states: List<WorkInfo.State>): WidgetWork = when {
  WorkInfo.State.RUNNING in states -> WidgetWork.RUNNING
  states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> WidgetWork.ENQUEUED
  else -> WidgetWork.NONE
}

/**
 * Settles [glanceId]'s stored LOADING if no live worker is behind it. Needs no service: called from
 * the service's refresher tick, from the app-process-start render (after a force-stop nothing else
 * runs) and from the widget's own draw, so a stuck LOADING heals whenever the widget is shown.
 * Returns whether it settled. WorkManager is only asked when the stored phase is LOADING.
 */
internal suspend fun settleStaleWidget(context: Context, glanceId: GlanceId): Boolean {
  val stored = readState(getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId))
  if (stored.phase != WidgetPhase.LOADING) return false
  val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
  val work = withContext(Dispatchers.IO) {
    widgetWorkOf(
      WorkManager.getInstance(context.applicationContext)
        .getWorkInfosForUniqueWork(widgetWorkName(appWidgetId)).get().map { it.state },
    )
  }
  var settled = false
  updateAppWidgetState(context, glanceId) { prefs ->
    settled = settleStalePrefs(prefs, System.currentTimeMillis(), work)
  }
  return settled
}

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
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.core.RelaisNodeController
import cc.grepon.relais.core.thermalHot

private const val TAG = "WidgetActions"

/** Action parameter key: which template's canned prompt to run (its system prompt is resolved later). */
val TemplateIdKey = ActionParameters.Key<String>("template_id")

/**
 * RUN: fires the canned prompt for the supplied template — but ONLY behind the cold-start guard.
 *
 * Re-derives the [cc.grepon.relais.core.NodeState] HERE via [RelaisNodeController.state] (the tap is
 * the gate, not the render): if the node went OFF since the widget last rendered,
 * [shouldRunWidgetPrompt] is IGNORE and we DO NOT enqueue inference — a tap can never cold-start the
 * multi-GB engine. On IDLE (feature-22) it is WARM_THEN_RUN — UNLESS the device is already
 * thermally hot ([thermalHot], Codex P2), which is IGNORE: an idle-unloaded engine reads IDLE, never
 * HOT, at any thermal status, so this is the only place that guard applies before a reload starts.
 * WARM_THEN_RUN kicks the single-flight reload ([RelaisEngine.ensureInitializedInBackground], which
 * publishes `startupInProgress` on this thread before returning) and tells the worker it did
 * (`warm = true`) so the worker waits for the ENGINE rather than a flag the reload may already have
 * cleared. Either way the persisted state flips to LOADING immediately (responsive UI) and the long
 * inference goes to [WidgetPromptWorker] (a Worker, so it survives the ~10 s broadcast limit).
 */
class RunPromptAction : ActionCallback {
  override suspend fun onAction(
    context: Context,
    glanceId: GlanceId,
    parameters: ActionParameters,
  ) {
    val templateId = parameters[TemplateIdKey]?.takeIf { it.isNotBlank() }
    val nodeState = RelaisNodeController.state(context)
    val warm = when (shouldRunWidgetPrompt(nodeState, thermalHot(), WIDGET_PROMPT)) {
      WidgetTapAction.IGNORE -> {
        Log.i(TAG, "node $nodeState; widget tap ignored (cold-start guard)")
        // Re-render the (now off/starting/error) status without enqueuing inference; leave any
        // prior state intact.
        RelaisWidget().update(context, glanceId)
        return
      }
      WidgetTapAction.RUN -> false
      WidgetTapAction.WARM_THEN_RUN -> {
        // applicationContext: the reload thread outlives this broadcast. Publishes startupInProgress
        // on THIS thread before returning, so the worker enqueued below is guaranteed to observe
        // either the reload in flight or its outcome — never a not-yet-begun reload. The kick can
        // only throw when Thread.start() itself fails (the engine has already ended its startup and
        // reset the single-flight guard): drop the tap rather than enqueue a worker that would wait
        // the full cap for a reload that never began.
        val kicked = runCatching {
          RelaisEngine.ensureInitializedInBackground(context.applicationContext)
        }.onFailure { Log.e(TAG, "warm kick failed; widget tap dropped", it) }.isSuccess
        if (!kicked) {
          RelaisWidget().update(context, glanceId)
          return
        }
        true
      }
    }
    updateAppWidgetState(context, glanceId) { prefs ->
      writeState(prefs, WidgetUiState.idle().loading(WIDGET_PROMPT))
    }
    RelaisWidget().update(context, glanceId)
    WidgetPromptWorker.enqueue(context, glanceId, templateId, warm = warm)
  }
}

/** CLEAR: resets the persisted widget state to IDLE (drops any stored answer from the launcher). */
class ClearAction : ActionCallback {
  override suspend fun onAction(
    context: Context,
    glanceId: GlanceId,
    parameters: ActionParameters,
  ) {
    updateAppWidgetState(context, glanceId) { prefs -> writeState(prefs, WidgetUiState.idle()) }
    RelaisWidget().update(context, glanceId)
  }
}

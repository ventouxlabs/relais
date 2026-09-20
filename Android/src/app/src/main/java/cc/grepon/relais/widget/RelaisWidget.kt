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
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import cc.grepon.relais.Amber
import cc.grepon.relais.Charcoal
import cc.grepon.relais.Muted
import cc.grepon.relais.Panel
import cc.grepon.relais.core.NodeState
import cc.grepon.relais.core.RelaisNodeController
import cc.grepon.relais.templates.PromptTemplate
import cc.grepon.relais.templates.WorkflowRegistry

/** Below this height the response collapses to fewer lines so nothing clips on a 1-cell widget. */
private val COMPACT_HEIGHT = 140.dp
private val DEFAULT_TEMPLATE = PromptTemplate("default", "Status check", "", builtin = true)

/** Max prompt buttons rendered. A widget is a glanceable surface — a plain (non-scrolling) Column of
 * a bounded handful avoids the clip/measure risk of a LazyColumn nested in the widget's Column. */
private const val MAX_PROMPT_BUTTONS = 4

/**
 * Home-screen widget (#3): a quick-prompt surface. Renders from the persisted Glance Preferences
 * state ([currentState]) — the live node status line, the canned-prompt buttons (one per available
 * [PromptTemplate]; a single default when none), a LOADING indicator, the capped response, and a
 * CLEAR action. Amber-on-charcoal via [RelaisWidgetTheme], per DESIGN.md.
 *
 * Cold-start safety: a button tap routes through [RunPromptAction], which re-checks
 * [cc.grepon.relais.core.RelaisInference.isReady] and refuses to enqueue inference when the node is
 * off — a tap can NEVER cold-start the engine. When off, the status line reads "open app to start"
 * and the prompt buttons are rendered disabled (no click action attached).
 */
class RelaisWidget : GlanceAppWidget() {

  // Exact: re-lay-out for the actual size the launcher gives us (size-adaptive, no clipping).
  override val sizeMode: SizeMode = SizeMode.Exact

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    provideContent {
      GlanceTheme(colors = RelaisWidgetTheme.colors) {
        WidgetContent(readState(currentState<Preferences>()))
      }
    }
  }
}

@Composable
private fun WidgetContent(state: WidgetUiState) {
  val context = LocalContext.current
  val nodeState = RelaisNodeController.state(context)
  val compact = LocalSize.current.height < COMPACT_HEIGHT
  // Buttons are tappable only on a LIVE node that isn't already running — the visual half of the
  // cold-start guard (RunPromptAction enforces the authoritative gate).
  val canRun = nodeState == NodeState.LIVE && state.phase != WidgetPhase.LOADING

  Column(modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background).padding(12.dp)) {
    StatusLine(nodeState)
    Spacer(GlanceModifier.height(8.dp))
    PromptButtons(context, enabled = canRun)
    when (state.phase) {
      WidgetPhase.IDLE -> Unit
      WidgetPhase.LOADING -> {
        Spacer(GlanceModifier.height(8.dp))
        LoadingRow()
      }
      WidgetPhase.DONE, WidgetPhase.ERROR -> {
        Spacer(GlanceModifier.height(8.dp))
        ResponseBlock(state, compact)
      }
    }
  }
}

/** The node status line, derived from the single [NodeState] source of truth. */
@Composable
private fun StatusLine(nodeState: NodeState) {
  val (label, accent) = when (nodeState) {
    NodeState.LIVE -> "● live" to Amber
    NodeState.HOT -> "● hot — throttling" to Amber
    NodeState.STARTING -> "○ starting…" to Muted
    NodeState.ERROR -> "○ error — open app" to Muted
    NodeState.OFF -> "○ off — open app to start" to Muted
    // feature-22 PR-B (task 4(c)): becomes WARM ("tap to warm"; canRun includes IDLE)
    NodeState.IDLE -> "○ idle — engine released" to Muted
  }
  Text(text = "relais  $label", style = TextStyle(color = ColorProvider(accent), fontSize = 13.sp))
}

/**
 * One button per available template (the canned prompt is fixed; the template supplies the system
 * prompt). Falls back to a single default "status check" button when no templates are configured.
 * When [enabled] is false (off or loading) buttons carry no click action — a tap can't fire.
 */
@Composable
private fun PromptButtons(context: Context, enabled: Boolean) {
  val templates =
    WorkflowRegistry.templates(context).ifEmpty { listOf(DEFAULT_TEMPLATE) }.take(MAX_PROMPT_BUTTONS)
  // Plain Column (not LazyColumn): the set is small + bounded, and a scrolling list nested in the
  // widget's non-scrolling Column can clip/mis-measure on some launchers.
  Column(modifier = GlanceModifier.fillMaxWidth()) {
    for (template in templates) {
      PromptButton(template, enabled)
      Spacer(GlanceModifier.height(6.dp))
    }
  }
}

@Composable
private fun PromptButton(template: PromptTemplate, enabled: Boolean) {
  val base = GlanceModifier
    .fillMaxWidth()
    .background(ColorProvider(if (enabled) Amber else Panel))
    .cornerRadius(8.dp)
    .padding(horizontal = 12.dp, vertical = 8.dp)
  val modifier =
    if (enabled) {
      base.clickable(actionRunCallback<RunPromptAction>(actionParametersOf(TemplateIdKey to template.id)))
    } else {
      base
    }
  Text(
    text = "▸ ${template.name}",
    style = TextStyle(color = ColorProvider(if (enabled) Charcoal else Muted), fontSize = 13.sp),
    modifier = modifier,
  )
}

/** The LOADING indicator row (a textual beacon — Glance Text is the lowest-risk cross-size element). */
@Composable
private fun LoadingRow() {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(text = "◌ running…", style = TextStyle(color = ColorProvider(Amber), fontSize = 13.sp))
  }
}

/** The capped response (or error message) plus the CLEAR action. */
@Composable
private fun ResponseBlock(state: WidgetUiState, compact: Boolean) {
  val isError = state.phase == WidgetPhase.ERROR
  val color = if (isError) GlanceTheme.colors.error else GlanceTheme.colors.onSurface
  Box(
    modifier = GlanceModifier
      .fillMaxWidth()
      .background(GlanceTheme.colors.surface)
      .cornerRadius(8.dp)
      .padding(10.dp),
  ) {
    Text(
      text = state.response.orEmpty(),
      maxLines = if (compact) 3 else 8,
      style = TextStyle(color = color, fontSize = 13.sp),
    )
  }
  Spacer(GlanceModifier.height(6.dp))
  Text(
    text = "clear",
    style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp),
    modifier = GlanceModifier
      .background(ColorProvider(Panel))
      .cornerRadius(8.dp)
      .padding(horizontal = 12.dp, vertical = 6.dp)
      .clickable(actionRunCallback<ClearAction>()),
  )
}

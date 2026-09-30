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
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import cc.grepon.relais.RelaisConfig
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.RelaisLivenessState
import cc.grepon.relais.ThermalGovernor
import cc.grepon.relais.core.NodeState
import cc.grepon.relais.core.computeNodeState
import cc.grepon.relais.core.RelaisNodeController
import cc.grepon.relais.core.thermalHot
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

private const val TAG = "RelaisWidgetRefresh"

/**
 * How often the service re-derives the widget's live inputs. Reads are in-memory (liveness
 * snapshot, engine flags, one cached pref); a render happens only on a change.
 */
private const val WIDGET_REFRESH_INTERVAL_MS = 5_000L

/**
 * The node-derived inputs of the widget's render (#343): the status line reads the node state and
 * the thermal reading, and [widgetCanRun] gates the buttons on the same two plus the widget's own
 * prompt phase — which only the widget's own actions change, and they re-render it themselves.
 * Not in the key: the template list the buttons are built from, so a template edit shows on the
 * next node change or tap rather than at once.
 */
internal data class WidgetRenderKey(val nodeState: NodeState, val thermalHot: Boolean) {
  /** What [WidgetStateRefresher] writes into the widget's Glance state. */
  fun stamp(): String = "${nodeState.name}|$thermalHot"
}

/**
 * The per-widget Glance state entry the refresher writes (#343). Its only job is to CHANGE: an open
 * Glance session (it stays open ~45 s after a render) does not re-run `provideGlance` on update(),
 * it only recomposes readers of state that changed — so without this write a refresh inside that
 * window re-renders nothing, and a STARTING render stays dead after the node goes LIVE.
 */
private val NODE_STAMP_KEY = stringPreferencesKey("relais_widget_node_stamp")

/**
 * The node state the widget shows: [computeNodeState] and [widgetDisplayState] from ONE liveness
 * snapshot (#358), read first, engine flags after (computeNodeState's read-order rule). Going
 * through [RelaisNodeController.state] and then reading the snapshot again for the mapping would pair
 * one snapshot's state with another's evidence. Shared by the render and the refresher's key so the
 * change detector keys on exactly what is drawn.
 */
internal fun widgetNodeState(context: Context): NodeState {
  val liveness = RelaisLivenessState.snapshot
  val ready = RelaisEngine.isReady
  val state = computeNodeState(
    shouldRun = RelaisConfig.shouldRun(context),
    ready = ready,
    listenersUp = liveness.listenersUp,
    startupInProgress = liveness.startupInProgress,
    lastInitFailed = RelaisEngine.lastInitFailed,
    thermalStatus = ThermalGovernor.statusValue,
    idleUnloaded = liveness.idleUnloaded,
  )
  return widgetDisplayState(
    nodeState = state,
    startupInProgress = liveness.startupInProgress,
    listenersUp = liveness.listenersUp,
    ready = ready,
    idleUnloaded = liveness.idleUnloaded,
  )
}

/**
 * Renders every placed widget once from the node's current state (#358), off the caller's thread.
 * For the moments nothing else renders: the app process starting after a force-stop or an update,
 * when the service (and its refresher) is gone. A fresh process has no open Glance session, but this
 * goes through the same stamp-then-update path as the refresher anyway.
 */
internal fun refreshWidgetsOnceAsync(context: Context) {
  thread(name = "relais-widget-launch") {
    runCatching { renderWidgets(context, WidgetRenderKey(widgetNodeState(context), thermalHot())) }
      .onFailure { Log.w(TAG, "launch widget render failed", it) }
  }
}

/**
 * Stamps [key] into every placed widget's Glance state, then updates it — the write is what makes
 * an open session recompose (see [NODE_STAMP_KEY]). No widget placed: nothing to do.
 */
private fun renderWidgets(context: Context, key: WidgetRenderKey) = runBlocking {
  val widget = RelaisWidget()
  // A nonce so every write is a change: the stamp is persisted and outlives a service instance, so
  // a first tick (or the final render) can write the value already stored, and an unchanged value
  // would not recompose an open session.
  val stamp = "${key.stamp()}@${SystemClock.elapsedRealtimeNanos()}"
  for (id in GlanceAppWidgetManager(context).getGlanceIds(RelaisWidget::class.java)) {
    // Per widget: one that throws must not keep the others on a stale render.
    runCatching {
      updateAppWidgetState(context, id) { it[NODE_STAMP_KEY] = stamp }
      widget.update(context, id)
    }.onFailure { Log.w(TAG, "widget $id refresh failed", it) }
  }
}

/**
 * True when the widget must re-render. Null [lastRendered] (the first observation of a service
 * instance) always renders: the widget may be holding a render from before this instance existed.
 */
internal fun widgetNeedsRefresh(lastRendered: WidgetRenderKey?, now: WidgetRenderKey): Boolean =
  lastRendered != now

/**
 * Keeps the home-screen widget following the node (#343).
 *
 * The widget used to re-render only on its own taps and worker runs, so it showed the node as of
 * the last tap — and a render that left its buttons disabled attached no click action, so nothing
 * could ever bring it back. This polls rather than hooking the writers: the render depends on
 * [RelaisNodeController.state]'s seven inputs plus the thermal reading, and those have more writers
 * (liveness, engine init/unload/swap, `shouldRun`, thermal) than any list of hooks would stay in
 * step with. Owned by `RelaisNodeService`, so it runs exactly while there is a node to follow.
 */
internal class WidgetStateRefresher(private val context: Context) {
  private var executor: ScheduledExecutorService? = null
  /** Touched only on [executor]'s single thread. */
  private var lastRendered: WidgetRenderKey? = null

  fun start() {
    val ex = Executors.newSingleThreadScheduledExecutor { Thread(it, "relais-widget-refresh") }
    executor = ex
    ex.scheduleWithFixedDelay(
      { runCatching { tick() }.onFailure { Log.w(TAG, "widget refresh tick failed", it) } },
      0L,
      WIDGET_REFRESH_INTERVAL_MS,
      TimeUnit.MILLISECONDS,
    )
  }

  /**
   * Stops polling and renders once more, off the caller's thread (this runs from `onDestroy` on
   * the main thread). Runs after the service's teardown, so it shows the state teardown left: OFF
   * after an operator STOP (which clears `shouldRun` first). A system-initiated destroy leaves
   * `shouldRun` set, so the widget then reads whatever that state derives to until the service is
   * re-created and its first tick renders again.
   */
  fun stopAndRenderFinal() {
    executor?.shutdownNow()
    executor = null
    thread(name = "relais-widget-final") {
      runCatching { renderAll(currentKey()) }.onFailure { Log.w(TAG, "final widget render failed", it) }
    }
  }

  private fun currentKey() = WidgetRenderKey(widgetNodeState(context), thermalHot())

  private fun tick() {
    val now = currentKey()
    if (!widgetNeedsRefresh(lastRendered, now)) return
    renderAll(now)
    lastRendered = now
  }

  private fun renderAll(key: WidgetRenderKey) = renderWidgets(context, key)
}

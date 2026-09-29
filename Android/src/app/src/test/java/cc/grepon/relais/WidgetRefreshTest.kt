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

package cc.grepon.relais

import cc.grepon.relais.core.NodeState
import cc.grepon.relais.widget.WidgetRenderKey
import cc.grepon.relais.widget.widgetNeedsRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The change detector behind the widget's publish-side refresh (#343). The ticker that calls it
 * lives in a Service and is covered by the on-device smoke, not here.
 */
class WidgetRefreshTest {

  private val liveCool = WidgetRenderKey(NodeState.LIVE, thermalHot = false)

  @Test fun `the first observation always renders`() {
    // A widget can be holding a render from before this service instance existed — the dead-end
    // `starting…` render #343 was filed for. The first tick must replace it, whatever it shows.
    assertTrue(widgetNeedsRefresh(lastRendered = null, now = liveCool))
  }

  @Test fun `an unchanged state does not re-render`() {
    assertFalse(widgetNeedsRefresh(lastRendered = liveCool, now = WidgetRenderKey(NodeState.LIVE, thermalHot = false)))
  }

  @Test fun `a node state change re-renders`() {
    assertTrue(widgetNeedsRefresh(lastRendered = liveCool, now = WidgetRenderKey(NodeState.IDLE, thermalHot = false)))
    // The dead-end case itself: a render left at STARTING must follow the node once it settles.
    assertTrue(
      widgetNeedsRefresh(
        lastRendered = WidgetRenderKey(NodeState.STARTING, thermalHot = false),
        now = WidgetRenderKey(NodeState.IDLE, thermalHot = false),
      )
    )
  }

  @Test fun `every render key writes a distinct stamp`() {
    // The stamp is what makes an OPEN Glance session recompose: two keys sharing a stamp leave the
    // widget's state unchanged, so that transition re-renders nothing — #343's dead end again.
    val keys = NodeState.entries.flatMap { s -> listOf(false, true).map { WidgetRenderKey(s, it) } }
    assertEquals(keys.size, keys.map { it.stamp() }.toSet().size)
  }

  @Test fun `a thermal change alone re-renders`() {
    // IDLE's copy and its buttons both flip on thermalHot ("tap to warm" vs "hot, cooling") with the
    // node state unchanged, so a key without the thermal reading would leave that render stale.
    assertTrue(
      widgetNeedsRefresh(
        lastRendered = WidgetRenderKey(NodeState.IDLE, thermalHot = false),
        now = WidgetRenderKey(NodeState.IDLE, thermalHot = true),
      )
    )
  }
}

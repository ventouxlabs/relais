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

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import cc.grepon.relais.widget.HEARTBEAT_CEILING_MS
import cc.grepon.relais.widget.WIDGET_WORK_POLICY
import cc.grepon.relais.widget.WidgetPhase
import cc.grepon.relais.widget.WidgetUiState
import cc.grepon.relais.widget.WidgetWork
import cc.grepon.relais.widget.ownedRunPrefs
import cc.grepon.relais.widget.readState
import cc.grepon.relais.widget.settleStalePrefs
import cc.grepon.relais.widget.tapLoadingState
import cc.grepon.relais.widget.widgetWorkOf
import cc.grepon.relais.widget.writeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #353 wiring: the persisted-state functions the tap, worker, tick, app-start and draw paths share. */
class WidgetRunGuardTest {

  private val t0 = 1_000_000L

  @Test fun `state round-trips through preferences including token and heartbeat`() {
    val state = WidgetUiState.idle().loading("ask", runToken = "tok", nowMs = t0)
    val prefs = mutablePreferencesOf()
    writeState(prefs, state)
    assertEquals(state, readState(prefs))
    writeState(prefs, state.done("answer")) // leaving LOADING drops the stamps
    assertNull(readState(prefs).runToken)
    assertNull(readState(prefs).heartbeatMs)
  }

  @Test fun `a tap stamps its token and first heartbeat`() {
    val s = tapLoadingState("tok-1", t0)
    assertEquals(WidgetPhase.LOADING, s.phase)
    assertEquals("tok-1", s.runToken)
    assertEquals(t0, s.heartbeatMs)
  }

  @Test fun `a worker beat moves the heartbeat only for the owning token`() {
    val prefs = mutablePreferencesOf().also { writeState(it, tapLoadingState("tok", t0)) }
    assertTrue(ownedRunPrefs(prefs, "tok") { it.heartbeat(t0 + 60_000) })
    assertEquals(t0 + 60_000, readState(prefs).heartbeatMs)
    assertFalse(ownedRunPrefs(prefs, "other") { it.heartbeat(t0 + 99_000) })
    assertEquals(t0 + 60_000, readState(prefs).heartbeatMs)
  }

  @Test fun `a late worker result is discarded when the token no longer matches`() {
    val prefs = mutablePreferencesOf().also { writeState(it, tapLoadingState("new-tap", t0)) }
    val wrote = ownedRunPrefs(prefs, "old-tap") { WidgetUiState.idle().loading("ask").done("stale answer") }
    assertFalse(wrote)
    assertEquals(WidgetPhase.LOADING, readState(prefs).phase)
    assertEquals("new-tap", readState(prefs).runToken)
  }

  @Test fun `a late worker does not overwrite an ERROR or a CLEAR`() {
    val prefs = mutablePreferencesOf().also { writeState(it, tapLoadingState("tok", t0)) }
    settleStalePrefs(prefs, t0 + HEARTBEAT_CEILING_MS + 1, WidgetWork.NONE)
    assertEquals(WidgetPhase.ERROR, readState(prefs).phase)
    assertFalse(ownedRunPrefs(prefs, "tok") { WidgetUiState.idle().loading("ask").done("late") })
    assertEquals(WidgetPhase.ERROR, readState(prefs).phase)
    writeState(prefs, WidgetUiState.idle())
    assertFalse(ownedRunPrefs(prefs, "tok") { WidgetUiState.idle().loading("ask").done("late") })
    assertEquals(WidgetPhase.IDLE, readState(prefs).phase)
  }

  @Test fun `re-tap after an ERROR starts a new run that owns the state`() {
    val prefs = mutablePreferencesOf().also { writeState(it, tapLoadingState("old", t0)) }
    settleStalePrefs(prefs, t0 + HEARTBEAT_CEILING_MS + 1, WidgetWork.NONE)
    writeState(prefs, tapLoadingState("new", t0 + 200_000)) // the re-tap
    assertTrue(ownedRunPrefs(prefs, "new") { WidgetUiState.idle().loading("ask").done("fresh") })
    assertEquals(WidgetPhase.DONE, readState(prefs).phase)
    // and the new tap's worker replaces the old one rather than being dropped behind it
    assertEquals(ExistingWorkPolicy.REPLACE, WIDGET_WORK_POLICY)
  }

  @Test fun `the settle path used by tick, app start and draw heals a stuck or legacy LOADING`() {
    val stuck = mutablePreferencesOf().also { writeState(it, tapLoadingState("tok", t0)) }
    assertTrue(settleStalePrefs(stuck, t0 + HEARTBEAT_CEILING_MS + 1, WidgetWork.NONE))
    assertEquals(WidgetPhase.ERROR, readState(stuck).phase)

    val legacy = mutablePreferencesOf().also { writeState(it, WidgetUiState.idle().loading("ask")) }
    assertTrue(settleStalePrefs(legacy, t0, WidgetWork.NONE))
    assertEquals(WidgetPhase.ERROR, readState(legacy).phase)

    val live = mutablePreferencesOf().also { writeState(it, tapLoadingState("tok", t0)) }
    assertFalse(settleStalePrefs(live, t0 + 10 * HEARTBEAT_CEILING_MS, WidgetWork.RUNNING))
    assertEquals(WidgetPhase.LOADING, readState(live).phase)
  }

  @Test fun `WorkManager states map to the widget's view of its work`() {
    assertEquals(WidgetWork.RUNNING, widgetWorkOf(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.RUNNING)))
    assertEquals(WidgetWork.ENQUEUED, widgetWorkOf(listOf(WorkInfo.State.ENQUEUED)))
    assertEquals(WidgetWork.NONE, widgetWorkOf(listOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)))
    assertEquals(WidgetWork.NONE, widgetWorkOf(emptyList()))
  }
}

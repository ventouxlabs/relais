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

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import cc.grepon.relais.widget.RunResult
import cc.grepon.relais.widget.WidgetPhase
import cc.grepon.relais.widget.WidgetRunIo
import cc.grepon.relais.widget.WidgetRunSession
import cc.grepon.relais.widget.WidgetUiState
import cc.grepon.relais.widget.readState
import cc.grepon.relais.widget.runTokenFrom
import cc.grepon.relais.widget.runWidgetPrompt
import cc.grepon.relais.widget.settleStaleWidgetSafely
import cc.grepon.relais.widget.startTapRun
import cc.grepon.relais.widget.widgetWorkInput
import cc.grepon.relais.widget.writeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #353 wiring: the worker's step sequence, its token binding, the tap's stored state, the draw guard. */
class WidgetRunFlowTest {

  private class FakeIo(
    var ready: Boolean = true,
    var hot: Boolean = false,
    var owned: Boolean = true,
    var failure: Throwable? = null,
  ) : WidgetRunIo {
    val events = mutableListOf<String>()
    override suspend fun beat(): Boolean = owned.also { events += "beat" }
    override suspend fun settle(state: WidgetUiState): Boolean {
      events += "settle:${state.phase}:${state.response}"
      return true
    }
    override suspend fun awaitWarmIfNeeded() { events += "warm" }
    override fun engineReady() = ready
    override fun thermalHot() = hot
    override suspend fun complete(): String {
      events += "complete"
      failure?.let { throw it }
      return "answer"
    }
    override fun isNodeNotReady(t: Throwable) = t is IllegalStateException
    override fun log(message: String, t: Throwable?) = Unit
  }

  @Test fun `a successful run beats at start, after the warm wait and before the engine lock`() = runBlocking {
    val io = FakeIo()
    assertEquals(RunResult.SUCCESS, runWidgetPrompt(io))
    assertEquals(
      listOf("beat", "warm", "beat", "beat", "complete", "settle:DONE:answer"),
      io.events,
    )
  }

  @Test fun `a run that lost ownership at start does nothing else`() = runBlocking {
    val io = FakeIo(owned = false)
    assertEquals(RunResult.SUCCESS, runWidgetPrompt(io))
    assertEquals(listOf("beat"), io.events)
  }

  @Test fun `not ready and hot runs settle to ERROR without running inference`() = runBlocking {
    val off = FakeIo(ready = false)
    assertEquals(RunResult.FAILURE, runWidgetPrompt(off))
    assertEquals(listOf("beat", "warm", "beat", "settle:ERROR:node off — open app to start"), off.events)
    val hot = FakeIo(hot = true)
    assertEquals(RunResult.FAILURE, runWidgetPrompt(hot))
    assertTrue(hot.events.none { it == "complete" })
  }

  @Test fun `inference failures settle to ERROR with the matching message`() = runBlocking {
    val down = FakeIo(failure = IllegalStateException("x"))
    assertEquals(RunResult.FAILURE, runWidgetPrompt(down))
    assertEquals("settle:ERROR:node went off", down.events.last())
    val broken = FakeIo(failure = RuntimeException("x"))
    runWidgetPrompt(broken)
    assertEquals("settle:ERROR:inference failed", broken.events.last())
  }

  // ---- the session binds EVERY write to the run's token ----

  private fun session(token: String?, prefs: MutablePreferences, renders: MutableList<Int>) =
    WidgetRunSession(token, nowMs = { 42L }, edit = { f -> f(prefs) }, render = { renders += 1 })

  @Test fun `a session settles only the LOADING its token owns, and re-renders only then`() = runBlocking {
    val tap = startTapRun(nowMs = 1L, runToken = "tok")
    val prefs = mutablePreferencesOf().also { writeState(it, tap.state) }
    val renders = mutableListOf<Int>()

    assertFalse(session(null, prefs, renders).settle(WidgetUiState.idle().loading("a").done("late")))
    assertFalse(session("other", prefs, renders).beat())
    assertTrue(renders.isEmpty())
    assertEquals(WidgetPhase.LOADING, readState(prefs).phase)

    assertTrue(session("tok", prefs, renders).beat())
    assertEquals(42L, readState(prefs).heartbeatMs)
    assertTrue(session("tok", prefs, renders).settle(WidgetUiState.idle().loading("a").done("ok")))
    assertEquals(listOf(1), renders)
    assertEquals(WidgetPhase.DONE, readState(prefs).phase)
  }

  @Test fun `the token a tap enqueues reaches the worker and matches the stored state`() {
    val tap = startTapRun(nowMs = 7L)
    assertEquals(tap.runToken, tap.state.runToken)
    assertEquals(7L, tap.state.heartbeatMs)
    assertEquals(WidgetPhase.LOADING, tap.state.phase)
    val input = widgetWorkInput(appWidgetId = 3, templateId = null, warm = true, runToken = tap.runToken)
    assertEquals(tap.runToken, runTokenFrom(input))
    assertNull(runTokenFrom(widgetWorkInput(3, null, false, "x").let { androidx.work.Data.EMPTY }))
  }

  // ---- the draw/tick guard ----

  @Test fun `a failing stale check is logged and swallowed so the draw proceeds`() = runBlocking {
    val warned = mutableListOf<Throwable>()
    assertFalse(settleStaleWidgetSafely({ throw IllegalStateException("work query failed") }) { warned += it })
    assertEquals(1, warned.size)
    assertTrue(settleStaleWidgetSafely({ true }) { warned += it })
    assertEquals(1, warned.size)
  }
}

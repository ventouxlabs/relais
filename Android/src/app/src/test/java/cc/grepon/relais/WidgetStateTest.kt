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
import cc.grepon.relais.widget.RESPONSE_CAP
import cc.grepon.relais.widget.WidgetPhase
import cc.grepon.relais.widget.WidgetTapAction
import cc.grepon.relais.widget.WidgetUiState
import cc.grepon.relais.widget.awaitEngineReady
import cc.grepon.relais.widget.capResponse
import cc.grepon.relais.widget.shouldAwaitWarm
import cc.grepon.relais.widget.shouldGiveUpWarm
import cc.grepon.relais.widget.shouldRunWidgetPrompt
import cc.grepon.relais.widget.widgetCanRun
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM truth table for the widget's [WidgetUiState] machine, the [capResponse] bound, the
 * [NodeState]-keyed [shouldRunWidgetPrompt] tap decision, the [widgetCanRun] button-enable policy,
 * and the worker's warm-wait ([shouldAwaitWarm] / [shouldGiveUpWarm] + [awaitEngineReady], on virtual
 * time). No Android/Glance types — these run as plain unit tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetStateTest {

  @Test fun `loading carries the prompt and drops any prior response`() {
    val state = WidgetUiState.idle().loading("status check")
    assertEquals(WidgetPhase.LOADING, state.phase)
    assertEquals("status check", state.prompt)
    assertNull("a fresh load shows no stale answer", state.response)
  }

  @Test fun `done keeps the originating prompt and carries the capped response`() {
    val state = WidgetUiState.idle().loading("ask").done("answer")
    assertEquals(WidgetPhase.DONE, state.phase)
    assertEquals("ask", state.prompt) // round-trips the prompt that produced this answer
    assertEquals("answer", state.response)
  }

  @Test fun `error keeps the prompt and carries the message as the response slot`() {
    val state = WidgetUiState.idle().loading("ask").error("node not ready")
    assertEquals(WidgetPhase.ERROR, state.phase)
    assertEquals("ask", state.prompt)
    assertEquals("node not ready", state.response)
  }

  @Test fun `idle is the cleared state — no prompt, no response`() {
    val state = WidgetUiState.idle()
    assertEquals(WidgetPhase.IDLE, state.phase)
    assertNull(state.prompt)
    assertNull(state.response)
  }

  @Test fun `clearing a done state returns to idle`() {
    val cleared = WidgetUiState.idle().loading("ask").done("answer").cleared()
    assertEquals(WidgetUiState.idle(), cleared)
  }

  @Test fun `capResponse is a no-op under the cap`() {
    val short = "x".repeat(RESPONSE_CAP - 1)
    assertEquals(short, capResponse(short))
  }

  @Test fun `capResponse is a no-op exactly at the cap`() {
    val exact = "x".repeat(RESPONSE_CAP)
    assertEquals(exact, capResponse(exact))
    assertEquals(RESPONSE_CAP, capResponse(exact).length)
  }

  @Test fun `capResponse truncates over the cap to exactly the cap`() {
    val long = "x".repeat(RESPONSE_CAP + 500)
    assertEquals(RESPONSE_CAP, capResponse(long).length)
  }

  @Test fun `done caps an oversized response`() {
    val long = "y".repeat(RESPONSE_CAP + 100)
    val state = WidgetUiState.idle().done(long)
    assertEquals(RESPONSE_CAP, state.response?.length)
  }

  @Test fun `error caps an oversized message`() {
    val long = "z".repeat(RESPONSE_CAP + 100)
    val state = WidgetUiState.idle().error(long)
    assertEquals(RESPONSE_CAP, state.response?.length)
  }

  @Test fun `shouldRunWidgetPrompt keys on NodeState and thermalHot — LIVE runs, IDLE warms unless hot, the rest ignore`() {
    // feature-22 PR-B (task 4(c)): the decision takes the COMPUTED state, not raw flags. IDLE already
    // implies shouldRun && listenersUp && idleUnloaded (computeNodeState slot 5), which is a stronger
    // proof that the foreground service is alive than the `wasIdleUnloaded` flag RelaisInference's
    // self-heal keys on — so warming from a tap is a reload behind a live service, never a cold start.
    // HOT is IGNORE (review ruling R1): the engine is resident but the device is throttling, and the
    // tile's "never add inference heat while hot" is also what the widget's widgetCanRun renders.
    // Codex P2 (PR-B fixwave): an IDLE tap while the device is ALREADY thermally hot must also
    // IGNORE — computeNodeState can only report HOT for a resident engine, so an idle-unloaded node
    // reads IDLE at any thermal status, and without this row the tap warmed the engine and ran it
    // throttled. (LIVE, thermalHot=true) is unreachable by construction — computeNodeState's HOT arm
    // fires first whenever ready && listenersUp — asserted here only for totality, matching today's
    // LIVE-not-hot verdict (thermalHot is otherwise inert outside the IDLE arm).
    val expected = mapOf(
      (NodeState.LIVE to false) to WidgetTapAction.RUN,
      (NodeState.LIVE to true) to WidgetTapAction.RUN,
      (NodeState.HOT to false) to WidgetTapAction.IGNORE,
      (NodeState.HOT to true) to WidgetTapAction.IGNORE,
      (NodeState.IDLE to false) to WidgetTapAction.WARM_THEN_RUN,
      (NodeState.IDLE to true) to WidgetTapAction.IGNORE,
      (NodeState.OFF to false) to WidgetTapAction.IGNORE,
      (NodeState.OFF to true) to WidgetTapAction.IGNORE,
      (NodeState.STARTING to false) to WidgetTapAction.IGNORE,
      (NodeState.STARTING to true) to WidgetTapAction.IGNORE,
      (NodeState.ERROR to false) to WidgetTapAction.IGNORE,
      (NodeState.ERROR to true) to WidgetTapAction.IGNORE,
    )
    val everyCombination = NodeState.entries.flatMap { state -> listOf(state to false, state to true) }.toSet()
    assertEquals("every NodeState × thermalHot combination needs a row here", everyCombination, expected.keys)
    for ((key, action) in expected) {
      val (state, hot) = key
      assertEquals("tap on $state, thermalHot=$hot", action, shouldRunWidgetPrompt(state, hot, "status check"))
    }
  }

  @Test fun `shouldRunWidgetPrompt refuses to warm an IDLE node while the device is thermally hot`() {
    // Codex P2 (PR-B fixwave): without this guard the widget's cold-start gate let a tap on an
    // idle-unloaded node kick a reload and then run inference while THERMAL_STATUS_SEVERE+ — the
    // guard HOT already provides for a resident engine, bypassed because computeNodeState can only
    // ever report HOT when the engine is resident. Mirrors the tile's IDLE+hot -> STOP.
    assertEquals(WidgetTapAction.IGNORE, shouldRunWidgetPrompt(NodeState.IDLE, true, "status check"))
    assertEquals(WidgetTapAction.WARM_THEN_RUN, shouldRunWidgetPrompt(NodeState.IDLE, false, "status check"))
  }

  @Test fun `shouldRunWidgetPrompt ignores a stale tap on a stopped or throttling node — the cold-start guard`() {
    // The single most important assertion: a tap can never kick off anything on a node that is OFF
    // (the widget rendered IDLE, the operator pressed STOP, the tap landed afterwards — shutdown()
    // cleared idleUnloaded, so the state is OFF, and OFF is IGNORE). Same for a node that is still
    // coming up or whose last init failed: nothing to run, nothing to warm.
    assertEquals(WidgetTapAction.IGNORE, shouldRunWidgetPrompt(NodeState.OFF, false, "status check"))
    assertEquals(WidgetTapAction.IGNORE, shouldRunWidgetPrompt(NodeState.STARTING, false, "status check"))
    assertEquals(WidgetTapAction.IGNORE, shouldRunWidgetPrompt(NodeState.ERROR, false, "status check"))
    // HOT: the engine IS resident, so this is the one row the old isReady gate got wrong — a tap that
    // lands while the device throttles (rendered LIVE, tapped after the thermal flip) must not run.
    assertEquals(WidgetTapAction.IGNORE, shouldRunWidgetPrompt(NodeState.HOT, false, "status check"))
  }

  @Test fun `shouldRunWidgetPrompt warms an IDLE node — neither a plain run nor an ignored tap`() {
    assertEquals(WidgetTapAction.WARM_THEN_RUN, shouldRunWidgetPrompt(NodeState.IDLE, false, "status check"))
  }

  @Test fun `shouldRunWidgetPrompt ignores a null or blank prompt in every state, IDLE included`() {
    // A blank prompt beats every state (and the thermal guard): nothing to run means nothing to warm
    // for either.
    for (state in NodeState.entries) {
      for (hot in listOf(false, true)) {
        for (prompt in listOf(null, "", "   ")) {
          assertEquals(
            "$state × thermalHot=$hot × '$prompt'",
            WidgetTapAction.IGNORE,
            shouldRunWidgetPrompt(state, hot, prompt),
          )
        }
      }
    }
  }

  @Test fun `widgetCanRun keys on NodeState, thermalHot, and phase — LIVE runs, IDLE runs unless hot, LOADING never re-triggers`() {
    // Codex P2 fixwave round 2: widgetCanRun is the pure decision behind RelaisWidget's button-enable
    // state. (IDLE, hot=true) must be NOT runnable — without this row the buttons stayed enabled and
    // the status line still said "tap to warm" while a tap silently no-opped via
    // shouldRunWidgetPrompt's IGNORE. (LIVE, hot=true) is unreachable by construction
    // (computeNodeState's HOT arm fires first whenever ready && listenersUp) but asserted for
    // totality, same verdict as (LIVE, hot=false).
    val expected = mapOf(
      Triple(NodeState.LIVE, false, WidgetPhase.IDLE) to true,
      Triple(NodeState.LIVE, true, WidgetPhase.IDLE) to true,
      Triple(NodeState.HOT, false, WidgetPhase.IDLE) to false,
      Triple(NodeState.HOT, true, WidgetPhase.IDLE) to false,
      Triple(NodeState.IDLE, false, WidgetPhase.IDLE) to true,
      Triple(NodeState.IDLE, true, WidgetPhase.IDLE) to false,
      Triple(NodeState.OFF, false, WidgetPhase.IDLE) to false,
      Triple(NodeState.OFF, true, WidgetPhase.IDLE) to false,
      Triple(NodeState.STARTING, false, WidgetPhase.IDLE) to false,
      Triple(NodeState.STARTING, true, WidgetPhase.IDLE) to false,
      Triple(NodeState.ERROR, false, WidgetPhase.IDLE) to false,
      Triple(NodeState.ERROR, true, WidgetPhase.IDLE) to false,
    )
    val everyCombination =
      NodeState.entries.flatMap { state -> listOf(Triple(state, false, WidgetPhase.IDLE), Triple(state, true, WidgetPhase.IDLE)) }
        .toSet()
    assertEquals("every NodeState × thermalHot combination needs a row here", everyCombination, expected.keys)
    for ((key, runnable) in expected) {
      val (state, hot, phase) = key
      assertEquals("state=$state, thermalHot=$hot, phase=$phase", runnable, widgetCanRun(state, hot, phase))
    }
  }

  @Test fun `widgetCanRun refuses an IDLE tap while the device is thermally hot`() {
    assertFalse(widgetCanRun(NodeState.IDLE, thermalHot = true, phase = WidgetPhase.IDLE))
    assertTrue(widgetCanRun(NodeState.IDLE, thermalHot = false, phase = WidgetPhase.IDLE))
  }

  @Test fun `widgetCanRun never re-triggers while a run is already LOADING, in every state and thermal reading`() {
    for (state in NodeState.entries) {
      for (hot in listOf(false, true)) {
        assertFalse(
          "state=$state, thermalHot=$hot should not run while LOADING",
          widgetCanRun(state, hot, WidgetPhase.LOADING),
        )
      }
    }
  }

  @Test fun `shouldAwaitWarm waits only when not ready and either the tap warmed or a reload is in flight`() {
    // Each true row kills one mutant: "gate on startupInProgress only" (row 1 — by the time
    // WorkManager runs doWork the reload may already have cleared the flag, which is why the tap
    // passes `warm`), and "gate on warm only" (row 2 — a reload some other caller kicked).
    assertTrue(shouldAwaitWarm(ready = false, warm = true, startupInProgress = false))
    assertTrue(shouldAwaitWarm(ready = false, warm = false, startupInProgress = true))
    assertFalse(shouldAwaitWarm(ready = false, warm = false, startupInProgress = false))
    // A ready engine never waits, whatever the inputs say.
    assertFalse(shouldAwaitWarm(ready = true, warm = true, startupInProgress = true))
  }

  @Test fun `shouldGiveUpWarm fires only when no reload is in flight AND the last attempt failed`() {
    // Review ruling R2. Each false row kills one mutant: `!startupInProgress` alone (row 3 — nothing
    // failed, the reload simply has not begun or a tear landed between attempts: keep polling) and
    // `lastInitFailed` alone (row 2 — a stale failure while a retry is in flight: slot 3 > 4, keep
    // polling until that attempt ends and writes its own verdict).
    assertTrue(shouldGiveUpWarm(startupInProgress = false, lastInitFailed = true))
    assertFalse(shouldGiveUpWarm(startupInProgress = true, lastInitFailed = true))
    assertFalse(shouldGiveUpWarm(startupInProgress = false, lastInitFailed = false))
    assertFalse(shouldGiveUpWarm(startupInProgress = true, lastInitFailed = false))
  }

  @Test fun `awaitEngineReady returns true as soon as the engine reports ready`() = runTest {
    var checks = 0
    val ready = awaitEngineReady(
      isReady = { ++checks >= 3 },
      giveUp = { false },
      intervalMs = 500L,
      maxIterations = 120,
    )
    assertTrue(ready)
    assertEquals("two sleeps, ready on the third check", 1000L, currentTime)
  }

  @Test fun `awaitEngineReady does not sleep when the engine is already ready`() = runTest {
    assertTrue(awaitEngineReady(isReady = { true }, giveUp = { false }, intervalMs = 500L, maxIterations = 120))
    assertEquals(0L, currentTime)
  }

  @Test fun `awaitEngineReady settles early when told to give up — a failed reload costs one poll, not the cap`() =
    runTest {
      // Review ruling R2: the third check finds !startupInProgress && lastInitFailed → false after
      // 2 × 500 ms, never the 60 s cap.
      var asked = 0
      val ready = awaitEngineReady(
        isReady = { false },
        giveUp = { ++asked >= 3 },
        intervalMs = 500L,
        maxIterations = 120,
      )
      assertFalse(ready)
      assertEquals("gave up on the third check, after two sleeps", 1000L, currentTime)
    }

  @Test fun `awaitEngineReady lets a reload that just finished win over a same-iteration give-up`() = runTest {
    // isReady is consulted BEFORE giveUp on every iteration: on the iteration where both flip true
    // (endStartup() is the last write of an attempt, so a reader can see a stale lastInitFailed=true
    // beside a fresh isReady=true for one poll) the answer is the engine's, not the flag's.
    var readyChecks = 0
    var giveUpChecks = 0
    val ready = awaitEngineReady(
      isReady = { ++readyChecks >= 3 },
      giveUp = { ++giveUpChecks >= 3 },
      intervalMs = 500L,
      maxIterations = 120,
    )
    assertTrue(ready)
    assertEquals(1000L, currentTime)
  }

  @Test fun `awaitEngineReady gives up at the cap — 60 s with ModelSwitch's poll constants`() = runTest {
    // The cap is what bounds a widget tap on a node whose reload neither succeeds nor records a
    // failure (giveUp never fires): 500 ms × 120 = 60 s, and the reload measured 19.4–19.8 s on
    // rango/E2B (feature-22 Task 5), so a healthy warm fits with room.
    val ready = awaitEngineReady(
      isReady = { false },
      giveUp = { false },
      intervalMs = ModelSwitch.RELOAD_POLL_INTERVAL_MS,
      maxIterations = ModelSwitch.MAX_RELOAD_POLL_ITERATIONS,
    )
    assertFalse(ready)
    assertEquals(60_000L, currentTime)
  }

  @Test fun `every phase round-trips through the helpers without leaking another phase's fields`() {
    // LOADING never carries a response; DONE/ERROR always do; IDLE carries neither.
    val loading = WidgetUiState.idle().loading("p")
    assertNull(loading.response)
    val done = loading.done("r")
    assertEquals("r", done.response)
    val error = loading.error("e")
    assertEquals("e", error.response)
  }
}

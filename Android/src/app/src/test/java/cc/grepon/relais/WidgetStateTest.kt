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
import cc.grepon.relais.widget.shouldRunWidgetPrompt
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
 * [NodeState]-keyed [shouldRunWidgetPrompt] tap decision, and the worker's warm-wait
 * ([shouldAwaitWarm] + [awaitEngineReady], on virtual time). No Android/Glance types — these run as
 * plain unit tests.
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

  @Test fun `shouldRunWidgetPrompt keys on NodeState — LIVE and HOT run, IDLE warms first, the rest ignore`() {
    // feature-22 PR-B (task 4(c)): the decision takes the COMPUTED state, not raw flags. IDLE already
    // implies shouldRun && listenersUp && idleUnloaded (computeNodeState slot 5), which is a stronger
    // proof that the foreground service is alive than the `wasIdleUnloaded` flag RelaisInference's
    // self-heal keys on — so warming from a tap is a reload behind a live service, never a cold start.
    val expected = mapOf(
      NodeState.LIVE to WidgetTapAction.RUN,
      NodeState.HOT to WidgetTapAction.RUN,
      NodeState.IDLE to WidgetTapAction.WARM_THEN_RUN,
      NodeState.OFF to WidgetTapAction.IGNORE,
      NodeState.STARTING to WidgetTapAction.IGNORE,
      NodeState.ERROR to WidgetTapAction.IGNORE,
    )
    assertEquals("every NodeState value needs a row here", NodeState.entries.toSet(), expected.keys)
    for ((state, action) in expected) {
      assertEquals("tap on $state", action, shouldRunWidgetPrompt(state, "status check"))
    }
  }

  @Test fun `shouldRunWidgetPrompt ignores a stale tap on a stopped node — the cold-start guard`() {
    // The single most important assertion: a tap can never kick off anything on a node that is OFF
    // (the widget rendered IDLE, the operator pressed STOP, the tap landed afterwards — shutdown()
    // cleared idleUnloaded, so the state is OFF, and OFF is IGNORE). Same for a node that is still
    // coming up or whose last init failed: nothing to run, nothing to warm.
    assertEquals(WidgetTapAction.IGNORE, shouldRunWidgetPrompt(NodeState.OFF, "status check"))
    assertEquals(WidgetTapAction.IGNORE, shouldRunWidgetPrompt(NodeState.STARTING, "status check"))
    assertEquals(WidgetTapAction.IGNORE, shouldRunWidgetPrompt(NodeState.ERROR, "status check"))
  }

  @Test fun `shouldRunWidgetPrompt warms an IDLE node — neither a plain run nor an ignored tap`() {
    assertEquals(WidgetTapAction.WARM_THEN_RUN, shouldRunWidgetPrompt(NodeState.IDLE, "status check"))
  }

  @Test fun `shouldRunWidgetPrompt ignores a null or blank prompt in every state, IDLE included`() {
    // A blank prompt beats every state: nothing to run means nothing to warm for either.
    for (state in NodeState.entries) {
      for (prompt in listOf(null, "", "   ")) {
        assertEquals("$state × '$prompt'", WidgetTapAction.IGNORE, shouldRunWidgetPrompt(state, prompt))
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

  @Test fun `awaitEngineReady returns true as soon as the engine reports ready`() = runTest {
    var checks = 0
    val ready = awaitEngineReady(isReady = { ++checks >= 3 }, intervalMs = 500L, maxIterations = 120)
    assertTrue(ready)
    assertEquals("two sleeps, ready on the third check", 1000L, currentTime)
  }

  @Test fun `awaitEngineReady does not sleep when the engine is already ready`() = runTest {
    assertTrue(awaitEngineReady(isReady = { true }, intervalMs = 500L, maxIterations = 120))
    assertEquals(0L, currentTime)
  }

  @Test fun `awaitEngineReady gives up at the cap — 60 s with ModelSwitch's poll constants`() = runTest {
    // The cap is what bounds a widget tap on a node whose reload failed: 500 ms × 120 = 60 s, and the
    // reload measured 19.4–19.8 s on rango/E2B (feature-22 Task 5), so a healthy warm fits with room.
    val ready = awaitEngineReady(
      isReady = { false },
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

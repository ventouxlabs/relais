/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the redesigned control-panel's pure state/formatting functions (no
 * Context, no Android, no Compose) — see AUDIT.md §4 for the spec these implement.
 */
class RelaisControlPanelStateTest {

  // ---------------------------------------------------------------------------
  // computeControlPanelState — top-level status + single primary action
  // ---------------------------------------------------------------------------

  @Test
  fun `ready true yields LIVE status and STOP primary action`() {
    val s = computeControlPanelState(
      ready = true, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = true,
    )
    assertEquals(NodeStatus.LIVE, s.status)
    assertEquals("LIVE", s.statusWord)
    assertEquals(PrimaryAction.STOP, s.primaryAction)
  }

  @Test
  fun `running true and ready false yields STARTING status and CANCEL primary action`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.RESOLVING,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = true,
    )
    assertEquals(NodeStatus.STARTING, s.status)
    assertEquals("STARTING", s.statusWord)
    assertEquals(PrimaryAction.CANCEL, s.primaryAction)
  }

  @Test
  fun `running false and ready false yields OFFLINE status and START primary action`() {
    val s = computeControlPanelState(
      ready = false, running = false, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = true,
    )
    assertEquals(NodeStatus.OFFLINE, s.status)
    assertEquals("OFFLINE", s.statusWord)
    assertEquals(PrimaryAction.START, s.primaryAction)
  }

  @Test
  fun `never more than one primary action across all three states`() {
    val actions = listOf(
      computeControlPanelState(true, true, "m", false, ProvisionPhase.IDLE, 0, 0, listenersUp = true).primaryAction,
      computeControlPanelState(false, true, "m", false, ProvisionPhase.IDLE, 0, 0, listenersUp = true).primaryAction,
      computeControlPanelState(false, false, "m", false, ProvisionPhase.IDLE, 0, 0, listenersUp = true).primaryAction,
    )
    assertEquals(setOf(PrimaryAction.STOP, PrimaryAction.CANCEL, PrimaryAction.START), actions.toSet())
  }

  // ---------------------------------------------------------------------------
  // Detail line — P8 (single status line) + P6 (phase line) + P7 (thermal sub-state)
  // ---------------------------------------------------------------------------

  @Test
  fun `LIVE detail line reads engine resident with model name when not shedding`() {
    val s = computeControlPanelState(
      ready = true, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = true,
    )
    assertEquals("engine resident · Gemma 4 E2B", s.detailLine)
  }

  @Test
  fun `LIVE detail line reads thermal shedding load when thermalShedding is true`() {
    val s = computeControlPanelState(
      ready = true, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = true, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = true,
    )
    assertEquals("thermal · shedding load", s.detailLine)
    // Still LIVE — thermal shed is a sub-state, not a fourth top-level status (§4.4).
    assertEquals(NodeStatus.LIVE, s.status)
    assertEquals("LIVE", s.statusWord)
  }

  @Test
  fun `OFFLINE detail line reads node stopped with model name`() {
    val s = computeControlPanelState(
      ready = false, running = false, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = true,
    )
    assertEquals("node stopped · Gemma 4 E2B", s.detailLine)
  }

  @Test
  fun `STARTING detail line during RESOLVING phase reads resolving model`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.RESOLVING,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = true,
    )
    assertEquals("resolving model…", s.detailLine)
  }

  @Test
  fun `STARTING detail line during LOADING_ENGINE phase reads loading engine`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.LOADING_ENGINE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = true,
    )
    assertEquals("loading engine…", s.detailLine)
  }

  @Test
  fun `STARTING detail line during DOWNLOADING with known total shows percent and GB fraction`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.DOWNLOADING,
      downloadReceivedBytes = 1_200_000_000L, downloadTotalBytes = 2_800_000_000L,
      listenersUp = true,
    )
    assertEquals("downloading model · 42% · 1.2/2.8 GB", s.detailLine)
  }

  @Test
  fun `STARTING detail line during DOWNLOADING with unknown total omits percent and GB`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.DOWNLOADING,
      downloadReceivedBytes = 500_000_000L, downloadTotalBytes = 0L,
      listenersUp = true,
    )
    assertEquals("downloading model…", s.detailLine)
  }

  @Test
  fun `STARTING detail line is never a bare starting ellipsis regardless of phase`() {
    ProvisionPhase.entries.forEach { phase ->
      val s = computeControlPanelState(
        ready = false, running = true, modelDisplayName = "m",
        thermalShedding = false, phase = phase,
        downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = true,
      )
      assertFalse("phase=$phase must not render bare starting text", s.detailLine == "starting…")
      assertTrue("phase=$phase detail line must be non-blank", s.detailLine.isNotBlank())
    }
  }

  // ---------------------------------------------------------------------------
  // Model-row lockout (P6) — nodeBusy = running && !ready
  // ---------------------------------------------------------------------------

  @Test
  fun `model row is enabled and uncaptioned when OFFLINE`() {
    val s = computeControlPanelState(false, false, "m", false, ProvisionPhase.IDLE, 0, 0, listenersUp = true)
    assertTrue(s.modelRowEnabled)
    assertNull(s.modelLockedCaption)
  }

  @Test
  fun `model row is enabled and uncaptioned when LIVE`() {
    val s = computeControlPanelState(true, true, "m", false, ProvisionPhase.IDLE, 0, 0, listenersUp = true)
    assertTrue(s.modelRowEnabled)
    assertNull(s.modelLockedCaption)
  }

  @Test
  fun `model row is disabled with explanation caption when STARTING`() {
    val s = computeControlPanelState(false, true, "m", false, ProvisionPhase.RESOLVING, 0, 0, listenersUp = true)
    assertFalse(s.modelRowEnabled)
    assertEquals("model locked while starting", s.modelLockedCaption)
  }

  // ---------------------------------------------------------------------------
  // Endpoint row visibility (§4.1-4.3) — LOCAL only LIVE; LAN Paper only LIVE
  // ---------------------------------------------------------------------------

  @Test
  fun `LOCAL endpoint row is hidden unless LIVE`() {
    assertFalse(computeControlPanelState(false, false, "m", false, ProvisionPhase.IDLE, 0, 0, listenersUp = true).showLocalEndpoint)
    assertFalse(computeControlPanelState(false, true, "m", false, ProvisionPhase.IDLE, 0, 0, listenersUp = true).showLocalEndpoint)
    assertTrue(computeControlPanelState(true, true, "m", false, ProvisionPhase.IDLE, 0, 0, listenersUp = true).showLocalEndpoint)
  }

  @Test
  fun `LAN endpoint renders Paper only when LIVE, Muted otherwise`() {
    assertFalse(computeControlPanelState(false, false, "m", false, ProvisionPhase.IDLE, 0, 0, listenersUp = true).lanEndpointLive)
    assertFalse(computeControlPanelState(false, true, "m", false, ProvisionPhase.IDLE, 0, 0, listenersUp = true).lanEndpointLive)
    assertTrue(computeControlPanelState(true, true, "m", false, ProvisionPhase.IDLE, 0, 0, listenersUp = true).lanEndpointLive)
  }

  // ---------------------------------------------------------------------------
  // Determinate download progress bar (§5) — DOWNLOADING phase + known total only
  // ---------------------------------------------------------------------------

  @Test
  fun `progress bar is visible only during DOWNLOADING with a known total`() {
    val downloading = computeControlPanelState(
      false, true, "m", false, ProvisionPhase.DOWNLOADING, 1_000_000_000L, 2_000_000_000L,
      listenersUp = true,
    )
    assertTrue(downloading.showProgressBar)
    assertEquals(0.5f, requireNotNull(downloading.progressFraction), 0.0001f)

    val unknownTotal = computeControlPanelState(
      false, true, "m", false, ProvisionPhase.DOWNLOADING, 1_000_000_000L, 0L,
      listenersUp = true,
    )
    assertFalse(unknownTotal.showProgressBar)
    assertNull(unknownTotal.progressFraction)

    val resolving = computeControlPanelState(false, true, "m", false, ProvisionPhase.RESOLVING, 0L, 0L, listenersUp = true)
    assertFalse(resolving.showProgressBar)

    val live = computeControlPanelState(true, true, "m", false, ProvisionPhase.IDLE, 0L, 0L, listenersUp = true)
    assertFalse(live.showProgressBar)
  }

  @Test
  fun `progress fraction is coerced into 0 to 1`() {
    val overReceived = computeControlPanelState(
      false, true, "m", false, ProvisionPhase.DOWNLOADING, 5_000_000_000L, 2_000_000_000L,
      listenersUp = true,
    )
    assertEquals(1f, requireNotNull(overReceived.progressFraction), 0.0001f)
  }

  // ---------------------------------------------------------------------------
  // formatGigabytes / provisionPhaseLine — direct unit coverage of the helpers
  // ---------------------------------------------------------------------------

  @Test
  fun `formatGigabytes renders one decimal place`() {
    assertEquals("1.2", formatGigabytes(1_200_000_000L))
    assertEquals("2.8", formatGigabytes(2_800_000_000L))
    assertEquals("0.0", formatGigabytes(0L))
  }

  @Test
  fun `provisionPhaseLine covers every phase explicitly`() {
    // #217: IDLE must NOT borrow the resolver's copy. Folding them together made "nothing is
    // happening" render identically to real progress, which is how a dead node showed
    // "resolving model…" indefinitely with no provisioner running behind it.
    assertEquals("starting node…", provisionPhaseLine(ProvisionPhase.IDLE, 0, 0))
    assertEquals("resolving model…", provisionPhaseLine(ProvisionPhase.RESOLVING, 0, 0))
    assertEquals("loading engine…", provisionPhaseLine(ProvisionPhase.LOADING_ENGINE, 0, 0))
    assertEquals("downloading model…", provisionPhaseLine(ProvisionPhase.DOWNLOADING, 10, 0))
    assertEquals(
      "downloading model · 50% · 1.0/2.0 GB",
      provisionPhaseLine(ProvisionPhase.DOWNLOADING, 1_000_000_000L, 2_000_000_000L),
    )
  }

  // ---------------------------------------------------------------------------
  // Stalled start (#217) — "running" with no init in flight is a DEAD node, not progress
  // ---------------------------------------------------------------------------

  @Test
  fun `a stalled start renders the honest failed state, not indefinite progress`() {
    // The regression: service killed (OS, crash, or an APK reinstall) under a persisted
    // shouldRun=true. Nothing sets lastInitFailed, so the panel used to show STARTING forever.
    val s =
      computeControlPanelState(
        ready = false, running = true, modelDisplayName = "m",
        thermalShedding = false, phase = ProvisionPhase.IDLE,
        downloadReceivedBytes = 0, downloadTotalBytes = 0,
        initFailed = false, stalledStart = true,
      listenersUp = true,
      )
    assertEquals(NodeStatus.OFFLINE, s.status)
    assertEquals(PrimaryAction.START, s.primaryAction)
    assertEquals("node not running · press START", s.detailLine)
    assertTrue(s.detailLineBright)
  }

  @Test
  fun `a stalled start does not borrow the failed-init copy`() {
    // "check model/token" is misleading advice when the node simply is not running.
    val stalled =
      computeControlPanelState(
        false, true, "m", false, ProvisionPhase.IDLE, 0, 0,
        initFailed = false, stalledStart = true,
      listenersUp = true,
      )
    val failedInit =
      computeControlPanelState(
        false, true, "m", false, ProvisionPhase.IDLE, 0, 0,
        initFailed = true, stalledStart = false,
      listenersUp = true,
      )
    assertEquals("node not running · press START", stalled.detailLine)
    assertEquals("start failed · check model/token, then START again", failedInit.detailLine)
  }

  @Test
  fun `a failed init keeps its actionable copy once the stall debounce also fires`() {
    // BOTH flags true is not a corner case — it is where EVERY failed init ends up. RelaisNodeService's
    // `finally` clears startupInProgress while lastInitFailed stays true, so ~3 polls (~3s) after any
    // failure the stall debounce fires too. With the stalled arm checked first and unguarded, that
    // silently replaced "check model/token" with the generic "node not running" three seconds after
    // every failure — burying the only actionable advice for the dominant real failure, a
    // license-gated repo 401 (#220).
    val s =
      computeControlPanelState(
        false, true, "m", false, ProvisionPhase.IDLE, 0, 0,
        initFailed = true, stalledStart = true,
      listenersUp = true,
      )
    assertEquals(NodeStatus.OFFLINE, s.status)
    assertEquals(PrimaryAction.START, s.primaryAction)
    assertEquals("start failed · check model/token, then START again", s.detailLine)
  }

  @Test
  fun `a genuine in-flight start is still STARTING`() {
    // The guard must never fire during a real start — including a slow multi-GB download.
    val s =
      computeControlPanelState(
        false, true, "m", false, ProvisionPhase.DOWNLOADING, 500, 1000,
        initFailed = false, stalledStart = false,
      listenersUp = true,
      )
    assertEquals(NodeStatus.STARTING, s.status)
    assertEquals(PrimaryAction.CANCEL, s.primaryAction)
    assertTrue(s.showProgressBar)
  }

  @Test
  fun `a stopped node is never reported as stalled`() {
    // stalledStart is only meaningful while running; an intentional STOP must read plainly.
    val s =
      computeControlPanelState(
        false, false, "m", false, ProvisionPhase.IDLE, 0, 0,
        initFailed = false, stalledStart = true,
      listenersUp = true,
      )
    assertEquals(NodeStatus.OFFLINE, s.status)
    assertEquals("node stopped · m", s.detailLine)
  }

  @Test
  fun `the stalled debounce needs three consecutive polls`() {
    // One tick must not accuse a start that simply has not reached startupInProgress yet.
    assertEquals(false, isStalledStart(0))
    assertEquals(false, isStalledStart(1))
    assertEquals(false, isStalledStart(2))
    assertEquals(true, isStalledStart(3))
    assertEquals(true, isStalledStart(10))
  }

  // ---------------------------------------------------------------------------
  // Access-key masking (Q2) — masked by default, SHOW toggle reveals full key
  // ---------------------------------------------------------------------------

  @Test
  fun `maskAccessKey shows a bullet run then ellipsis then the last four characters`() {
    val masked = maskAccessKey("deadbeefcafef00d1234567890abcdef")
    assertEquals("••••…cdef", masked)
  }

  @Test
  fun `maskAccessKey fully masks a key of four characters or fewer`() {
    assertEquals("••••", maskAccessKey("abcd"))
    assertEquals("•••", maskAccessKey("abc"))
    assertEquals("", maskAccessKey(""))
  }

  @Test
  fun `displayApiKey reveals the full key only when revealed is true`() {
    val key = "deadbeefcafef00d1234567890abcdef"
    assertEquals(key, displayApiKey(key, revealed = true))
    assertEquals(maskAccessKey(key), displayApiKey(key, revealed = false))
  }

  // ---------------------------------------------------------------------------
  // M1 — failed-init honesty. RelaisNodeService leaves shouldRun=true and never resets
  // RelaisEngine.lastInitFailed after a failed init (e.g. a first-run gated-repo 401), so without
  // this the panel would show a perpetual "STARTING · resolving model…" with a CANCEL button and
  // nothing actually running. Scenario: running=true, ready=false, initFailed=true.
  // ---------------------------------------------------------------------------

  @Test
  fun `initFailed with running true and ready false renders as OFFLINE with an honest failed detail line`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.RESOLVING,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L, initFailed = true,
      listenersUp = true,
    )
    assertEquals(NodeStatus.OFFLINE, s.status)
    assertEquals("OFFLINE", s.statusWord)
    assertEquals("start failed · check model/token, then START again", s.detailLine)
    // Retry action, never stranded without one.
    assertEquals(PrimaryAction.START, s.primaryAction)
    // The likely fix is changing model/token — the row must not be locked.
    assertTrue(s.modelRowEnabled)
    assertNull(s.modelLockedCaption)
    // Never a phase line/progress bar for a phase that isn't actually happening.
    assertFalse(s.showProgressBar)
    assertNull(s.progressFraction)
    assertFalse(s.showLocalEndpoint)
    assertFalse(s.lanEndpointLive)
  }

  @Test
  fun `initFailed with running false (already stopped) does not resurface the failed message`() {
    // Once the operator has explicitly stopped, a stale lastInitFailed from a prior attempt must
    // not keep claiming "start failed" — that's now a plain, honest OFFLINE.
    val s = computeControlPanelState(
      ready = false, running = false, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L, initFailed = true,
      listenersUp = true,
    )
    assertEquals(NodeStatus.OFFLINE, s.status)
    assertEquals("node stopped · Gemma 4 E2B", s.detailLine)
    assertEquals(PrimaryAction.START, s.primaryAction)
    assertFalse(s.detailLineBright)
  }

  @Test
  fun `initFailed is irrelevant once ready (LIVE always wins)`() {
    val s = computeControlPanelState(
      ready = true, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L, initFailed = true,
      listenersUp = true,
    )
    assertEquals(NodeStatus.LIVE, s.status)
    assertEquals("engine resident · Gemma 4 E2B", s.detailLine)
  }

  @Test
  fun `initFailed false with running true and ready false is unaffected — still STARTING`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.RESOLVING,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L, initFailed = false,
      listenersUp = true,
    )
    assertEquals(NodeStatus.STARTING, s.status)
    assertEquals("resolving model…", s.detailLine)
    assertEquals(PrimaryAction.CANCEL, s.primaryAction)
  }

  @Test
  fun `initFailed defaults to false when omitted (back-compat with existing call sites)`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "m",
      thermalShedding = false, phase = ProvisionPhase.RESOLVING,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = true,
    )
    assertEquals(NodeStatus.STARTING, s.status)
  }

  // ---------------------------------------------------------------------------
  // L4 — thermal shedding must never leak into a detail line for a state where it isn't LIVE.
  // ---------------------------------------------------------------------------

  @Test
  fun `thermalShedding true is ignored while STARTING`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "m",
      thermalShedding = true, phase = ProvisionPhase.RESOLVING,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L, initFailed = false,
      listenersUp = true,
    )
    assertEquals("resolving model…", s.detailLine)
    assertFalse("thermalShedding must not leak into a non-LIVE detail line", s.detailLine.contains("thermal"))
    assertFalse(s.detailLineBright)
  }

  @Test
  fun `thermalShedding true is ignored while plain OFFLINE`() {
    val s = computeControlPanelState(
      ready = false, running = false, modelDisplayName = "m",
      thermalShedding = true, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L, initFailed = false,
      listenersUp = true,
    )
    assertEquals("node stopped · m", s.detailLine)
    assertFalse(s.detailLine.contains("thermal"))
    assertFalse(s.detailLineBright)
  }

  @Test
  fun `thermalShedding true is ignored in the failed-init state`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "m",
      thermalShedding = true, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L, initFailed = true,
      listenersUp = true,
    )
    assertEquals("start failed · check model/token, then START again", s.detailLine)
    assertFalse(s.detailLine.contains("thermal"))
    // Bright because it's the failed message, not because of thermal.
    assertTrue(s.detailLineBright)
  }

  // ---------------------------------------------------------------------------
  // L5 — detailLineBright is derived in the pure function; the composable must not re-derive it.
  // ---------------------------------------------------------------------------

  @Test
  fun `detailLineBright is true only for LIVE-plus-thermal-shed and the failed state`() {
    val liveNormal = computeControlPanelState(true, true, "m", false, ProvisionPhase.IDLE, 0, 0, initFailed = false, listenersUp = true)
    val liveShed = computeControlPanelState(true, true, "m", true, ProvisionPhase.IDLE, 0, 0, initFailed = false, listenersUp = true)
    val starting = computeControlPanelState(false, true, "m", false, ProvisionPhase.RESOLVING, 0, 0, initFailed = false, listenersUp = true)
    val offline = computeControlPanelState(false, false, "m", false, ProvisionPhase.IDLE, 0, 0, initFailed = false, listenersUp = true)
    val failed = computeControlPanelState(false, true, "m", false, ProvisionPhase.IDLE, 0, 0, initFailed = true, listenersUp = true)

    assertFalse(liveNormal.detailLineBright)
    assertTrue(liveShed.detailLineBright)
    assertFalse(starting.detailLineBright)
    assertFalse(offline.detailLineBright)
    assertTrue(failed.detailLineBright)
  }

  // ---------------------------------------------------------------------------
  // Reachability — LIVE means "someone can reach this node", not "the engine loaded".
  //
  // A `:8443` (or loopback `:8080`) bind failure lands in RelaisNodeService's catch, which tears
  // both listeners down and DELIBERATELY leaves the engine resident. So the real failure carries
  // ready=true, listenersUp=false AND initFailed=true simultaneously — every case below models
  // that combination rather than a tidier one.
  // ---------------------------------------------------------------------------

  /** The hardware-reproduced bind failure: engine resident, both listeners torn down, retry wanted. */
  private fun unreachable(initFailed: Boolean = true) = computeControlPanelState(
    ready = true, running = true, modelDisplayName = "Gemma 4 E2B",
    thermalShedding = false, phase = ProvisionPhase.IDLE,
    downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
    listenersUp = false, initFailed = initFailed, startupInProgress = false,
  )

  @Test
  fun `LIVE requires listenersUp, not engine readiness alone`() {
    assertEquals(NodeStatus.LIVE, computeControlPanelState(
      ready = true, running = true, modelDisplayName = "m",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = true,
    ).status)
    // Same engine, nothing listening: anything but LIVE. A node nothing can reach is not live.
    assertNotEquals(NodeStatus.LIVE, unreachable().status)
    assertNotEquals("LIVE", unreachable().statusWord)
  }

  @Test
  fun `unreachable node offers START so the retry is reachable`() {
    // The sharp edge: the catch block clears the listeners precisely so a later START can retry,
    // but while the panel read LIVE it only ever offered STOP — the intended one-press retry could
    // not be pressed. STOP here is not merely the wrong label, it is the defect.
    assertEquals(PrimaryAction.START, unreachable().primaryAction)
    assertNotEquals(PrimaryAction.STOP, unreachable().primaryAction)
  }

  @Test
  fun `unreachable node advertises neither endpoint`() {
    // Both endpoints refuse connections in this state; showing either is a false address.
    assertFalse(unreachable().showLocalEndpoint)
    assertFalse(unreachable().lanEndpointLive)
  }

  @Test
  fun `unreachable detail line is bright and says none of the three misleading things`() {
    val s = unreachable()
    assertTrue("an unreachable node is an attention state", s.detailLineBright)
    // Asserted as distinctness rather than a substring match: the point is which claim the line does
    // NOT make. "engine resident" is true-but-irrelevant, "node stopped" denies the operator's intent,
    // and "check model/token" is the #217 mistake — the model loaded fine, the socket is the problem.
    assertNotEquals("engine resident · Gemma 4 E2B", s.detailLine)
    assertNotEquals("node stopped · Gemma 4 E2B", s.detailLine)
    assertNotEquals("start failed · check model/token, then START again", s.detailLine)
    assertNotEquals("node not running · press START", s.detailLine)
  }

  @Test
  fun `startup window with listeners not yet bound reads STARTING, never OFFLINE`() {
    // Anti-flicker, and the reason startupInProgress is a separate input rather than an inference:
    // dispatchStartupIfNeeded initialises the engine BEFORE binding either listener, so ready=true
    // with listenersUp=false is an ordinary sub-second window of every healthy start. Rendering it
    // OFFLINE would flash a START button mid-startup and invite a second dispatch.
    val s = computeControlPanelState(
      ready = true, running = true, modelDisplayName = "m",
      thermalShedding = false, phase = ProvisionPhase.LOADING_ENGINE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = false, initFailed = false, startupInProgress = true,
    )
    assertEquals(NodeStatus.STARTING, s.status)
    assertEquals(PrimaryAction.CANCEL, s.primaryAction)
    assertNotEquals(PrimaryAction.START, s.primaryAction)
  }

  @Test
  fun `startupInProgress is what separates the startup window from the unreachable state`() {
    // The two differ in exactly one input. If they ever render the same, one of them is lying.
    val comingUp = computeControlPanelState(
      ready = true, running = true, modelDisplayName = "m",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = false, initFailed = false, startupInProgress = true,
    )
    val stuck = computeControlPanelState(
      ready = true, running = true, modelDisplayName = "m",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = false, initFailed = false, startupInProgress = false,
    )
    assertNotEquals(comingUp.status, stuck.status)
    assertNotEquals(comingUp.primaryAction, stuck.primaryAction)
  }

  @Test
  fun `stopped node with a still-resident engine reads stopped, not LIVE`() {
    // STOP tears the listeners down but can leave the engine resident, so ready=true outlives the
    // operator's intent to run. The panel must follow the intent, not the leftover engine.
    val s = computeControlPanelState(
      ready = true, running = false, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      listenersUp = false,
    )
    assertEquals(NodeStatus.OFFLINE, s.status)
    assertEquals(PrimaryAction.START, s.primaryAction)
    assertEquals("node stopped · Gemma 4 E2B", s.detailLine)
    assertFalse("a deliberate stop is not an attention state", s.detailLineBright)
  }
}

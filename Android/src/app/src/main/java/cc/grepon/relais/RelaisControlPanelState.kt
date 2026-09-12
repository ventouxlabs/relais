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

import java.util.Locale

/** The single state-appropriate primary action (AUDIT.md §4.0) — never more than one per screen. */
enum class PrimaryAction { START, CANCEL, STOP }

/** The three top-level home-screen states. THERMAL SHED is a LIVE sub-state, not a fourth value (§4.4). */
enum class NodeStatus { OFFLINE, STARTING, LIVE }

/** The provisioning phase behind STARTING (§4.2), so the phase line is never a bare "starting…". */
enum class ProvisionPhase { IDLE, RESOLVING, DOWNLOADING, LOADING_ENGINE }

/**
 * Pure, JVM-testable snapshot of everything the redesigned control-panel Compose layer renders for
 * one poll tick. The composable stays a thin projection of this — no state derivation in the UI
 * layer (AUDIT.md §4).
 */
data class RelaisControlPanelState(
  val status: NodeStatus,
  val statusWord: String,
  val detailLine: String,
  /**
   * True => the detail line renders Paper (bright = attention by brightness, no new color): LIVE
   * while thermally shedding, or the failed-init message. False => Muted, the quiet default.
   * Derived here, once, so the Compose layer never re-derives this decision (review L5).
   */
  val detailLineBright: Boolean,
  val primaryAction: PrimaryAction,
  val modelRowEnabled: Boolean,
  val modelLockedCaption: String?,
  val showLocalEndpoint: Boolean,
  /** True => the LAN endpoint value renders Paper (the screen's peak, LIVE only); false => Muted preview. */
  val lanEndpointLive: Boolean,
  val showProgressBar: Boolean,
  /** 0f..1f while [showProgressBar]; null otherwise (indeterminate or not downloading). */
  val progressFraction: Float?,
)

/**
 * Assembles [RelaisControlPanelState] from raw engine/provisioner signals. [ready]/[running] mirror
 * [RelaisEngine.isReady] / [RelaisConfig.shouldRun]; [thermalShedding] mirrors
 * [ThermalGovernor.shouldShed]; [phase] and the download byte counts mirror [RelaisNodeProgress];
 * [initFailed] mirrors [RelaisEngine.lastInitFailed] (already consumed by the QS tile);
 * [listenersUp] mirrors [RelaisListenerState.listenersUp]; [startupInProgress] mirrors
 * [RelaisEngine.startupInProgress].
 *
 * [listenersUp] is deliberately **required** while every other added signal is defaulted, and the
 * asymmetry is the safety property: omitting [startupInProgress] can only under-report (STARTING
 * degrades to OFFLINE, an honest state that still offers the retry), whereas omitting [listenersUp]
 * would fabricate reachability — a false LIVE for a node nothing can reach, which is exactly the
 * defect this parameter exists to prevent. A new surface must not be able to opt into that by
 * saying nothing. (Better still, a new surface should read [RelaisNodeController.state] instead of
 * re-deriving this at all.)
 *
 * [initFailed] only produces the failed-init message while [running] is still true (review M1):
 * `RelaisNodeService` sets `lastInitFailed=true` on a failed attempt (e.g. a first-run gated-repo
 * 401) but never clears `shouldRun`/`lastInitFailed` itself — only the next init attempt (a fresh
 * START) resets `lastInitFailed`. So once the operator has explicitly STOPped, `running` is false
 * and any stale `initFailed` is ignored — the panel reads a plain, honest "node stopped", not a
 * message about an attempt that's no longer in flight.
 */
fun computeControlPanelState(
  ready: Boolean,
  running: Boolean,
  modelDisplayName: String,
  thermalShedding: Boolean,
  phase: ProvisionPhase,
  downloadReceivedBytes: Long,
  downloadTotalBytes: Long,
  listenersUp: Boolean,
  initFailed: Boolean = false,
  stalledStart: Boolean = false,
  startupInProgress: Boolean = false,
): RelaisControlPanelState {
  // Still "running" (shouldRun=true) but the engine never came up and won't on its own: an honest
  // failed state, not a perpetual "STARTING · resolving model…" with nothing happening behind it.
  //
  // TWO ways that happens, and [initFailed] only ever covered the first (#217):
  //  - an init attempt ran and failed (e.g. a gated-repo 401)      -> initFailed
  //  - no init attempt is running at all: the service was killed   -> stalledStart
  //    (OS kill, crash, or an APK reinstall under a persisted shouldRun=true). Nothing sets
  //    lastInitFailed in that case, so the panel used to show indefinite fake progress.
  // Engine readiness answers "did the model load?"; only [listenersUp] answers "can anyone reach
  // this node?" — and the panel's whole job is the second question. A bind failure leaves the engine
  // deliberately resident with both listeners torn down, so keying LIVE on [ready] advertised two
  // endpoints that refuse connections. Mirrors [cc.grepon.relais.core.computeNodeState] and
  // [RelaisWatchdogReceiver], which is the point: one predicate, not four that drift.
  val reachable = ready && listenersUp
  // A THIRD way to be running-but-dead, alongside [initFailed] and [stalledStart]: the engine came
  // up, nothing is listening, and no attempt is in flight that would change that. [startupInProgress]
  // is what keeps the ordinary startup window out of here — the engine is initialised before either
  // listener binds, so ready-without-listeners is a normal sub-second state of every healthy start.
  val unreachable = running && ready && !listenersUp && !startupInProgress
  val failed = running && !reachable && (initFailed || stalledStart || unreachable)
  // The two signals are NOT mutually exclusive, and treating them as such was a real regression:
  // after ANY failed init, RelaisNodeService's `finally` clears startupInProgress while
  // lastInitFailed stays true, so the stall debounce fires ~3 polls later and BOTH are set. The init
  // failure is the more specific diagnosis and must win — "check model/token" is the actionable
  // advice for the dominant real failure (a license-gated repo 401, see #220), and letting the
  // generic "node not running" copy replace it three seconds after every failure would bury it.
  val stalledOnly = stalledStart && !initFailed
  val status = when {
    reachable -> NodeStatus.LIVE
    // Before the failed arm: the engine is up and the listeners are still binding. Rendering this
    // OFFLINE would flash a START button through the tail of every healthy start.
    ready && startupInProgress -> NodeStatus.STARTING
    failed -> NodeStatus.OFFLINE // OFFLINE-rendered on purpose (§ review M1): retry via START, never CANCEL-locked.
    running -> NodeStatus.STARTING
    else -> NodeStatus.OFFLINE
  }
  // Blocked while the node is provisioning (running but not yet ready, and NOT already failed out):
  // a mid-download model change could resurrect a superseded path once the in-flight ensureModel()
  // resolves. A failed attempt is not "provisioning" — the row must stay open so the likely fix
  // (a different model/token) is reachable without a detour.
  val nodeBusy = status == NodeStatus.STARTING
  val progressVisible = status == NodeStatus.STARTING && phase == ProvisionPhase.DOWNLOADING && downloadTotalBytes > 0
  val thermalShed = status == NodeStatus.LIVE && thermalShedding
  return RelaisControlPanelState(
    status = status,
    statusWord = status.name,
    detailLine = controlPanelDetailLine(status, failed, stalledOnly, unreachable, modelDisplayName, thermalShedding, phase, downloadReceivedBytes, downloadTotalBytes),
    detailLineBright = thermalShed || failed,
    primaryAction = when (status) {
      NodeStatus.LIVE -> PrimaryAction.STOP
      NodeStatus.STARTING -> PrimaryAction.CANCEL
      NodeStatus.OFFLINE -> PrimaryAction.START // also the retry action for the failed sub-state
    },
    modelRowEnabled = !nodeBusy,
    modelLockedCaption = if (nodeBusy) "model locked while starting" else null,
    showLocalEndpoint = status == NodeStatus.LIVE,
    lanEndpointLive = status == NodeStatus.LIVE,
    showProgressBar = progressVisible,
    progressFraction = if (progressVisible) downloadProgressFraction(downloadReceivedBytes, downloadTotalBytes) else null,
  )
}

/** The one-line elaboration under the header (Caption tier) — merges old STATUS row + tagline (P8, P12). */
internal fun controlPanelDetailLine(
  status: NodeStatus,
  failed: Boolean,
  /** Stalled AND not a failed init — see [computeControlPanelState]'s `stalledOnly`. */
  stalledOnly: Boolean,
  /** Engine resident, nothing listening, no attempt in flight — see [computeControlPanelState]. */
  unreachable: Boolean,
  modelDisplayName: String,
  thermalShedding: Boolean,
  phase: ProvisionPhase,
  downloadReceivedBytes: Long,
  downloadTotalBytes: Long,
): String =
  when {
    // FIRST, ahead of the failed-init arm, because the real bind failure sets BOTH: the catch that
    // tears the listeners down also sets lastInitFailed. "check model/token" is then actively wrong
    // — the model loaded, the socket is what failed — and this is the same mistake #217 split the
    // stalled copy out to avoid. [unreachable] is the strictly more specific diagnosis: we know
    // nothing is listening, rather than inferring that something went wrong.
    status == NodeStatus.OFFLINE && unreachable -> "endpoints down · press START to retry"
    // A stalled start and a failed init are both "OFFLINE + press START", but they are NOT the same
    // problem and must not share copy: "check model/token" is actively misleading advice when the
    // node simply isn't running. Checked before the generic failed arm (#217) — safe to put first
    // ONLY because [stalledOnly] already excludes the failed-init case, which reaches BOTH states.
    status == NodeStatus.OFFLINE && failed && stalledOnly -> "node not running · press START"
    // Checked first: an in-flight (still "running") attempt that already failed renders OFFLINE
    // above, but must never be confused with a plain, intentional stop.
    // The "START again" promise is only honest because RelaisNodeService.onStartCommand
    // re-dispatches the init attempt against an already-alive service — don't drop that dispatch
    // guard without also revisiting this copy.
    status == NodeStatus.OFFLINE && failed -> "start failed · check model/token, then START again"
    // Thermal shed is expressed in-line, in Paper, rather than a new color (§4.4) — the Compose
    // layer is responsible for the color choice; this function only owns the text.
    status == NodeStatus.LIVE && thermalShedding -> "thermal · shedding load"
    status == NodeStatus.LIVE -> "engine resident · $modelDisplayName"
    status == NodeStatus.STARTING -> provisionPhaseLine(phase, downloadReceivedBytes, downloadTotalBytes)
    else -> "node stopped · $modelDisplayName"
  }

/** One of resolve / download(+progress) / engine-load — never a bare "starting…" (P6). */
internal fun provisionPhaseLine(phase: ProvisionPhase, downloadReceivedBytes: Long, downloadTotalBytes: Long): String =
  when (phase) {
    ProvisionPhase.DOWNLOADING ->
      if (downloadTotalBytes > 0) {
        val pct = ((downloadReceivedBytes * 100) / downloadTotalBytes).coerceIn(0, 100)
        "downloading model · $pct% · ${formatGigabytes(downloadReceivedBytes)}/${formatGigabytes(downloadTotalBytes)} GB"
      } else {
        "downloading model…" // total unknown (e.g. HF didn't report a size) — phase name alone, still non-bare.
      }
    ProvisionPhase.LOADING_ENGINE -> "loading engine…"
    ProvisionPhase.RESOLVING -> "resolving model…"
    // IDLE is NOT folded into RESOLVING (#217): the start has been dispatched but the provisioner
    // hasn't begun, and borrowing the resolver's copy made "nothing is happening" indistinguishable
    // from real progress. This is not the bare "starting…" P6 forbids — it names a real phase, and a
    // start that is dispatched-but-stalled now renders the failed state instead (see [stalledStart]).
    ProvisionPhase.IDLE -> "starting node…"
  }

internal fun downloadProgressFraction(receivedBytes: Long, totalBytes: Long): Float? {
  if (totalBytes <= 0) return null
  return (receivedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

internal fun formatGigabytes(bytes: Long): String =
  String.format(Locale.US, "%.1f", bytes / 1_000_000_000.0)

/**
 * Masks [key] as `••••…last-4` (Q2) for the control-panel access-key chip: a fixed 4-bullet run, an
 * ellipsis, then the last four characters — never a length-revealing bullet count. Keys of 4
 * characters or fewer are masked fully (no "last 4" would be safe to reveal). Distinct from
 * [maskApiKey] in RelaisDashboard.kt (first4…last4), which serves the read-only web dashboard.
 */
fun maskAccessKey(key: String): String =
  if (key.length <= 4) "•".repeat(key.length) else "••••…${key.takeLast(4)}"

/** The access-key chip's rendered text: full key when [revealed] (SHOW toggle), else [maskAccessKey]. */
fun displayApiKey(key: String, revealed: Boolean): String = if (revealed) key else maskAccessKey(key)

/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.grepon.relais

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Poll cadence + how long the shared loop keeps running after the last collector leaves. */
internal const val SHELL_POLL_INTERVAL_MS = 1000L
internal const val SHELL_POLL_STOP_TIMEOUT_MS = 5000L

/**
 * Builds the shell's lifecycle-scoped node-status poll: a cold loop that emits `produce()` every
 * [intervalMs], shared via `SharingStarted.WhileSubscribed([stopTimeoutMs])`. It runs ONLY while a
 * collector is present and stops [stopTimeoutMs] ms after the last one leaves (app backgrounded),
 * resuming on re-subscribe — the #145 "polling pauses when backgrounded" behavior.
 *
 * Extracted as a pure, injectable seam so that pause/resume is guarded by a fast JVM test (#171) with
 * a counting `produce` + virtual time — no Android/Robolectric needed. [produce] is also called once
 * eagerly to seed the StateFlow's initial value (so the first composed frame isn't empty).
 */
internal fun <T> pollingStateFlow(
  scope: CoroutineScope,
  intervalMs: Long = SHELL_POLL_INTERVAL_MS,
  stopTimeoutMs: Long = SHELL_POLL_STOP_TIMEOUT_MS,
  produce: () -> T,
): StateFlow<T> =
  flow {
      while (true) {
        emit(produce())
        delay(intervalMs)
      }
    }
    .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMs), produce())

/** One polling tick's worth of node-status state, snapshotted together so both derived
 * [RelaisShellViewModel.panelState] and [RelaisShellViewModel.modelDisplay] StateFlows come from
 * the same read rather than racing across two separate polling loops. */
private data class RelaisShellSnapshot(
  val panelState: RelaisControlPanelState,
  val modelDisplay: String,
)

/**
 * Hoists the node-status polling loop that used to live as a `LaunchedEffect` inside
 * `RelaisControlActivity`, so every shell destination that needs to render the control panel
 * (dashboard, and any future entry point) observes one shared, single-poll state source instead of
 * each composable running its own independent 1s timer.
 *
 * The polling loop is a cold `flow` shared via `stateIn(..., SharingStarted.WhileSubscribed(5000))`
 * rather than a `viewModelScope`-tied `while` loop: it only runs while at least one composable is
 * collecting, and pauses ~5–10s after the last collector goes away (e.g. app backgrounded) — the
 * derived flows and the shared `snapshots` flow each carry an independent 5s `WhileSubscribed`
 * grace — resuming immediately on re-subscribe. [panelState] and [modelDisplay] are seeded
 * synchronously with an initial snapshot so the first composed frame isn't empty/default.
 */
class RelaisShellViewModel(app: Application) : AndroidViewModel(app) {
  private val snapshots: StateFlow<RelaisShellSnapshot> =
      pollingStateFlow(viewModelScope) {
        RelaisShellSnapshot(panelState = snapshotPanelState(), modelDisplay = currentModelDisplay())
      }

  val panelState: StateFlow<RelaisControlPanelState> =
      snapshots
          .map { it.panelState }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHELL_POLL_STOP_TIMEOUT_MS), snapshots.value.panelState)

  val modelDisplay: StateFlow<String> =
      snapshots
          .map { it.modelDisplay }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHELL_POLL_STOP_TIMEOUT_MS), snapshots.value.modelDisplay)

  /** Dispatches the single state-appropriate primary action (START / CANCEL / STOP). */
  fun onPrimaryAction() {
    val ctx = getApplication<Application>()
    when (panelState.value.primaryAction) {
      PrimaryAction.START -> RelaisNodeService.start(ctx)
      PrimaryAction.CANCEL,
      PrimaryAction.STOP -> RelaisNodeService.stop(ctx)
    }
  }

  private fun currentModelDisplay(): String {
    val ctx = getApplication<Application>()
    val modelId = RelaisConfig.modelId(ctx)
    val modelRef = RelaisConfig.modelRef(ctx)
    return modelRef?.takeIf { it.modelId == modelId }?.displayName ?: modelId
  }

  /**
   * Consecutive polls that looked stalled. Debounced rather than reported on the first tick: a
   * legitimate start has a brief window between `shouldRun=true` and `RelaisNodeService` setting
   * `startupInProgress`, and flashing "node not running" through that window would be its own lie.
   */
  private var stalledTicks = 0

  private fun snapshotPanelState(): RelaisControlPanelState {
    val ctx = getApplication<Application>()
    val ready = RelaisEngine.isReady
    val running = RelaisConfig.shouldRun(ctx)

    // #217: "running" with no init actually in flight means the service died (OS kill, crash, or an
    // APK reinstall under a persisted shouldRun). `startupInProgress` is the right signal because
    // RelaisNodeService holds it across the WHOLE init — including a multi-GB download — so a slow
    // first start can never be mistaken for a stall.
    stalledTicks =
      if (running && !ready && !RelaisEngine.startupInProgress) stalledTicks + 1 else 0

    return computeControlPanelState(
      ready = ready,
      running = running,
      modelDisplayName = currentModelDisplay(),
      thermalShedding = ThermalGovernor.shouldShed(),
      phase = RelaisNodeProgress.phase,
      downloadReceivedBytes = RelaisNodeProgress.downloadReceivedBytes,
      downloadTotalBytes = RelaisNodeProgress.downloadTotalBytes,
      // Read BEFORE listenersUp — the order of these two reads is load-bearing. They are separate
      // volatile fields, so a poll descheduled between them composes a state that never existed at
      // any instant. The invariant that makes THIS order the safe one: startup is never published
      // as finished before the listeners it started are published. Under it, a stale read here can
      // only over-report STARTING, which the next tick corrects; the opposite order could take
      // "listeners down" and "startup finished" from either side of that publish and render
      // OFFLINE — a START button on a node that just came up healthy.
      startupInProgress = RelaisEngine.startupInProgress,
      listenersUp = RelaisListenerState.listenersUp,
      initFailed = RelaisEngine.lastInitFailed,
      stalledStart = isStalledStart(stalledTicks),
    )
  }
}

/** Polls (~1 Hz) a start must look dead for before the panel says so. */
internal const val STALLED_START_TICKS = 3

/** Pure so the debounce threshold is unit-testable without a ViewModel or a Context (#217). */
internal fun isStalledStart(consecutiveStalledPolls: Int): Boolean =
  consecutiveStalledPolls >= STALLED_START_TICKS

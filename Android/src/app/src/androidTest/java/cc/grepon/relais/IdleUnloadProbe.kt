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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Debug
import android.os.IBinder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.core.NodeState
import cc.grepon.relais.core.computeNodeState
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * On-device probe for feature-22 idle-TTL auto-unload (#178) — the properties [RelaisEngine],
 * [RelaisWatchdogReceiver], and `/health` compose to only on a REAL resident engine and a REAL
 * `RelaisNodeService`. Nothing here needs a real minute to pass: [RelaisEngine.releaseIfIdle] takes
 * a synthetic `nowMs`, so every unload in this file is instantaneous.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.IdleUnloadProbe \
 *     -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *   # in another shell: adb logcat -s RelaisIdleUnloadProbe:*
 *
 * Requires a staged model at [RelaisModelProvisioner.cachedPathOrDefault] (`assumeTrue`-skipped
 * otherwise) and destructive cycles belong on the spare Pixel 10 (`rango`), never the live node — a
 * keyguard/asleep device fakes mass failures across every test in this class, so unlock first.
 *
 * ## Watchdog ownership
 * [setUp] starts the real node via [RelaisNodeService.start], which schedules a REAL 60s
 * `AlarmManager` heartbeat ([RelaisWatchdog.schedule]). [setUp] immediately cancels it
 * ([RelaisWatchdog.cancel]) so nothing but this file's own explicit
 * `RelaisWatchdogReceiver().onReceive(...)` calls can run watchdog logic during the probe — a real
 * alarm firing mid-assertion (especially during the forced-ERROR window in
 * `c_forcedInitFailureReadsErrorThenWatchdogRecoversToLive`) would race every state check below.
 * [tearDown] restores the node's prior run state, which restores
 * the watchdog schedule as a side effect of [RelaisNodeService.start]/[RelaisNodeService.stop].
 *
 * ## What each test pins
 *  - [a_idleReleaseUnloadsEngineAndPublishesLiveness] — 4(a): `releaseIfIdle` past a synthetic TTL
 *    unloads the engine, publishes `idleUnloaded`, increments `relais_engine_unloads_total`, and
 *    both `computeNodeState` and `/health` read IDLE.
 *  - [b_reloadTimingWatchdogShieldAndNoSecondLoad] — 4(b): wall-clock reload-to-first-token time (the
 *    number task 6 depends on) logged prominently; `/health` transitions IDLE -> STARTING -> LIVE
 *    across a request-driven reload; 4(c): the watchdog, invoked directly WHILE that reload holds
 *    `RelaisEngine`'s lock, neither bumps the failure step nor dispatches a competing `relais-init`
 *    thread; and the "cannot test on the JVM" property from task 1: a second `ensureInitialized` call
 *    on an already-ready engine records no second `relais_engine_load_duration_seconds_count`.
 *  - [c_forcedInitFailureReadsErrorThenWatchdogRecoversToLive] — 4(c'): a forced init failure
 *    (`modelPath` that doesn't exist) throws, leaves `lastInitFailed && !idleUnloaded`,
 *    `computeNodeState` and `/health` both read ERROR (inside the watchdog's owned window — see
 *    above), and an explicit watchdog tick recovers the node to LIVE.
 *  - [d_repeatedIdleUnloadReloadCyclesDoNotRegress] — 4(d): ~5 unload/reload cycles, each exercising
 *    the multiplied-first-inference path, asserting [RelaisEngine.consecutiveCloseFailures] never
 *    trips (the #178 circuit-breaker — a genuine native leak on `Engine.close()` would show up here
 *    first) and logging the native heap size per cycle for a human to eyeball the trend.
 *
 * ## What this probe does NOT cover, deliberately
 *  - **Swap-then-rollback double failure -> ERROR.** Forcing it needs the swap target's load to fail
 *    AND the rollback-to-previous load to ALSO fail (`RelaisEngine.kt:568-585`) — e.g. by renaming the
 *    previous model's file away and back. But both attempts happen back-to-back inside ONE
 *    `synchronized(lock)` block on a background thread with no exposed hook between them; the rename
 *    would have to race that thread's own internal timing with a sleep, which is not a deterministic
 *    trigger. Skipped rather than faked with a sleep-guessed race — see `mutation-testing` project
 *    memory on tests whose pass/fail depends on timing luck.
 *  - **The LAN-rebind gate clearing IDLE.** Needs a REAL network change (Wi-Fi A -> B) mid-idle-window,
 *    which no in-process probe step can synthesize. Manual: idle the node out (leave it untouched
 *    past its configured idle-TTL), toggle the device to a different Wi-Fi network, then from a
 *    second machine `curl --cacert relais-ca.crt https://<new-ip>:8443/health` and confirm it answers
 *    once a request has re-warmed the engine — see [CertReissueProbe] for the cross-network runbook
 *    this mirrors.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class IdleUnloadProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()
  private var nodeConnection: ServiceConnection? = null
  private var nodeBound = false
  private var nodeWasRunning = false

  @Before
  fun setUp() {
    assumeTrue("On-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    val modelPath = RelaisModelProvisioner.cachedPathOrDefault(context)
    assumeTrue("no staged model at $modelPath — provision one before running this probe", File(modelPath).exists())

    nodeWasRunning = RelaisConfig.shouldRun(context)
    try {
      val binder = startAndBindNode()
      val readyDeadline = System.currentTimeMillis() + 180_000
      while (!binder.isReady && System.currentTimeMillis() < readyDeadline) Thread.sleep(500)
      assertTrue("node engine did not become ready within 180s", binder.isReady)
      assertTrue("node http listener did not open on :8080", waitForListener())
      // isReady flips before the service tears listeners down and rebinds (RelaisNodeService's
      // init thread) — wait for the whole attempt to settle, not just the socket accepting.
      awaitNodeSettled()

      // See the class KDoc "Watchdog ownership" section: take exclusive control of watchdog timing.
      RelaisWatchdog.cancel(context)
    } catch (t: Throwable) {
      // JUnit skips @After when @Before throws — restore node-off/default-TTL ourselves so a
      // readiness/settle timeout doesn't leave rango running with shouldRun=true against the
      // operator's prior intent.
      tearDown()
      throw t
    }
  }

  @After
  fun tearDown() {
    if (nodeBound) nodeConnection?.let { context.unbindService(it) }
    nodeBound = false
    nodeConnection = null
    // This probe owns the node it started for the duration of the run. RelaisNodeService.stop()
    // cancels the watchdog itself; only restart (which reschedules it) if the node was running
    // before this probe began, restoring the operator's prior intent rather than this probe's.
    RelaisNodeService.stop(context)
    if (nodeWasRunning) RelaisNodeService.start(context)
    RelaisEngine.lastInitFailed = false
    nodeWasRunning = false
  }

  @Test
  fun a_idleReleaseUnloadsEngineAndPublishesLiveness() {
    ensureReady()
    serveOneRequest()

    val unloadsBefore = promCounter(METRIC_UNLOADS_TOTAL)
    val released = RelaisEngine.releaseIfIdle(ttlMs = SYNTHETIC_TTL_MS, nowMs = farFutureNowMs())
    assertTrue("releaseIfIdle must release a ready, idle-past-TTL engine", released)
    assertFalse("engine must not be ready immediately after an idle release", RelaisEngine.isReady)
    assertTrue(
      "RelaisLivenessState must record the idle release before the readable false-ready state",
      RelaisLivenessState.snapshot.idleUnloaded,
    )

    val unloadsAfter = promCounter(METRIC_UNLOADS_TOTAL)
    assertEquals(
      "relais_engine_unloads_total must increment by exactly 1",
      unloadsBefore + 1.0,
      unloadsAfter,
      COUNTER_DELTA,
    )
    assertEquals("computeNodeState must read IDLE after a graceful idle release", NodeState.IDLE, currentNodeState())
    assertEquals("/health must read IDLE after a graceful idle release", "IDLE", healthState())
    Log.i(TAG, "4(a) PASS: idle release unloaded the engine; unloads_total $unloadsBefore -> $unloadsAfter")
  }

  @Test
  fun b_reloadTimingWatchdogShieldAndNoSecondLoad() {
    ensureReady()
    assertTrue("precondition: idle release must succeed", RelaisEngine.releaseIfIdle(SYNTHETIC_TTL_MS, farFutureNowMs()))
    assertEquals("precondition: /health must read IDLE right after the release", "IDLE", healthState())

    val loadCountBeforeReload = promCounter(METRIC_LOAD_COUNT)

    // 4(b): a request-driven reload, timed from dispatch to the first visible token — the reload
    // itself (RelaisEngine.ensureInitialized) is NOT included in RelaisResult.timeToFirstTokenSec,
    // which only measures from conversation creation onward. This wall-clock span is the number
    // task 6's metric needs and which does not exist as a Prometheus series today.
    val firstTokenAtNs = AtomicLong(0)
    val startNs = System.nanoTime()
    val reloadThread =
      Thread({
        RelaisEngine.generate(
          context,
          RelaisRequest(text = "Reply with a single word."),
          onToken = { if (firstTokenAtNs.get() == 0L) firstTokenAtNs.set(System.nanoTime()) },
          shouldCancel = { firstTokenAtNs.get() != 0L },
        )
      }, "probe-reload-request")
    reloadThread.start()

    // Wait for the reload to actually be under way (inside ensureInitialized, holding the engine
    // lock) before probing it — a fixed sleep would be a race against real model-load latency.
    val startupDeadline = System.currentTimeMillis() + 10_000
    while (!RelaisLivenessState.snapshot.startupInProgress && System.currentTimeMillis() < startupDeadline) {
      Thread.sleep(20)
    }
    assertTrue(
      "4(b): startupInProgress must be true while a request's synchronous reload is in flight",
      RelaisLivenessState.snapshot.startupInProgress,
    )
    assertEquals("/health must read STARTING mid-reload", "STARTING", healthState())

    // 4(c): trip the watchdog directly WHILE the reload holds RelaisEngine's lock. A "no bump across
    // N reloads" check would be probabilistic against a real alarm; this is deterministic instead.
    val stepBaseline = readWatchdogStep()
    assertEquals("baseline failure step must be 0 (RelaisWatchdog.cancel in setUp)", 0, stepBaseline)
    val relaisInitThreadsBefore = countThreadsNamed("relais-init")
    RelaisWatchdogReceiver().onReceive(context, Intent())
    // Give a WRONGLY-dispatched relais-init thread a moment to appear, if the shield were missing.
    Thread.sleep(300)
    assertEquals(
      "4(c): the watchdog must not escalate the failure step during a synchronous reload " +
        "(startupInProgress must shield it, mirroring the idle-unload shield in " +
        "RelaisWatchdogReceiver)",
      0,
      readWatchdogStep(),
    )
    assertEquals(
      "4(c): the watchdog must not dispatch a competing relais-init thread during a synchronous reload",
      relaisInitThreadsBefore,
      countThreadsNamed("relais-init"),
    )

    assertTrue("reload thread did not finish within 120s", reloadThread.let { it.join(120_000); !it.isAlive })
    assertTrue("engine must be ready once the request-driven reload completes", RelaisEngine.isReady)
    assertEquals("/health must read LIVE once the reload completes", "LIVE", healthState())

    val reloadToFirstTokenMs = (firstTokenAtNs.get() - startNs) / 1_000_000.0
    // The number task 6's relais_reload_to_first_token_seconds metric depends on. Logged prominently.
    Log.i(TAG, "RELOAD_TO_FIRST_TOKEN_MS=$reloadToFirstTokenMs")

    val loadCountAfterReload = promCounter(METRIC_LOAD_COUNT)
    assertEquals(
      "the request-driven reload must record exactly one engine load",
      loadCountBeforeReload + 1.0,
      loadCountAfterReload,
      COUNTER_DELTA,
    )

    // The property task 1 could not test on the JVM: a second ensureInitialized call against an
    // already-ready engine is a fast no-op (RelaisEngine.kt:403 `if (isReady) return`) and must not
    // record a second load observation.
    RelaisEngine.ensureInitialized(context)
    val loadCountAfterRedundantCall = promCounter(METRIC_LOAD_COUNT)
    assertEquals(
      "a second ensureInitialized call on an already-ready engine must record no second load",
      loadCountAfterReload,
      loadCountAfterRedundantCall,
      COUNTER_DELTA,
    )
    Log.i(TAG, "4(b)/4(c) PASS: reload=${reloadToFirstTokenMs}ms, watchdog shielded, no duplicate load recorded")
  }

  @Test
  fun c_forcedInitFailureReadsErrorThenWatchdogRecoversToLive() {
    ensureReady()
    assertTrue("precondition: idle release must succeed", RelaisEngine.releaseIfIdle(SYNTHETIC_TTL_MS, farFutureNowMs()))

    var threw = false
    try {
      // RelaisEngine.kt:357's require(File(modelPath).exists()) is the first statement that can
      // throw — nothing mutates engine/residentModelId/lastActivityAtMs before it.
      RelaisEngine.ensureInitialized(context, modelPath = "/data/local/tmp/relais-probe-does-not-exist.litertlm")
    } catch (e: Exception) {
      threw = true
      Log.i(TAG, "forced init failure threw as expected: ${e.message}")
    }
    assertTrue("ensureInitialized must throw for a nonexistent model path", threw)

    // Snapshot ONCE, then engine flags — the read-order rule from computeNodeState's KDoc.
    val snapshot = RelaisLivenessState.snapshot
    assertTrue("precondition: node must still be asked to run", RelaisConfig.shouldRun(context))
    assertFalse("startup must have ended — endStartup() is the last write of every attempt", snapshot.startupInProgress)
    assertTrue("lastInitFailed must be set by the failed attempt", RelaisEngine.lastInitFailed)
    assertFalse(
      "idleUnloaded must have been cleared by beginStartup(clearIdleUnloaded = true) at attempt start",
      snapshot.idleUnloaded,
    )
    assertEquals(
      "computeNodeState must read ERROR: shouldRun && lastInitFailed, not currently retrying",
      NodeState.ERROR,
      currentNodeState(),
    )
    // Inside the watchdog's owned window (see class KDoc): nothing but this test can flip this.
    assertEquals("/health must read ERROR before any recovery", "ERROR", healthState())

    // Let the watchdog recover it: this dispatches a REAL relais-init thread via
    // RelaisNodeService.start(), which re-provisions and reloads the CONFIGURED (good) model.
    RelaisWatchdogReceiver().onReceive(context, Intent())
    // A watchdog-driven restart is service-driven — wait for the whole attempt to settle, not
    // just isReady, or this reads a stale STARTING while the service is still tearing down and
    // rebinding listeners.
    awaitNodeSettled()
    assertEquals("/health must read LIVE once the watchdog recovers the node", "LIVE", healthState())

    // Reset before 4(d), or its cycle loop would run under a stale ERROR read for one poll. The
    // successful recovery above already clears this (RelaisNodeService.kt:270), but restate it
    // explicitly per the plan brief rather than relying on that path having run.
    RelaisEngine.lastInitFailed = false
    Log.i(TAG, "4(c') PASS: forced failure read ERROR; watchdog recovered the node to LIVE")
  }

  @Test
  fun d_repeatedIdleUnloadReloadCyclesDoNotRegress() {
    ensureReady()
    repeat(CYCLE_COUNT) { i ->
      val released = RelaisEngine.releaseIfIdle(SYNTHETIC_TTL_MS, farFutureNowMs())
      assertTrue("cycle $i: idle release must succeed", released)
      assertEquals(
        "cycle $i: consecutiveCloseFailures must stay 0 — a repeated close failure is the #178 " +
          "circuit breaker's own signal of a leaking native teardown",
        0,
        RelaisEngine.consecutiveCloseFailures,
      )

      val firstTokenSeen = AtomicBoolean(false)
      RelaisEngine.generate(
        context,
        RelaisRequest(text = "Reply with a single word."),
        onToken = { firstTokenSeen.set(true) },
        shouldCancel = { firstTokenSeen.get() },
      )
      assertTrue("cycle $i: engine must come back up — the multiplied first-inference-after-reload path", RelaisEngine.isReady)
      assertTrue("cycle $i: the request must have produced at least one token", firstTokenSeen.get())

      val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
      Log.i(TAG, "cycle $i: native heap allocated = %.1f MiB".format(nativeHeapMb))
    }
    assertEquals("/health must read LIVE after the final cycle", "LIVE", healthState())
    Log.i(TAG, "4(d) PASS: $CYCLE_COUNT unload/reload cycles completed with no close failures")
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** Idempotent: reloads the engine if a prior test left it unloaded, and clears forced-failure residue. */
  private fun ensureReady() {
    RelaisEngine.lastInitFailed = false // undo any prior test's forced-failure residue (4(c'))
    if (!RelaisEngine.isReady) RelaisEngine.ensureInitialized(context)
    assertTrue("engine must be ready before this test can proceed", RelaisEngine.isReady)
  }

  private fun serveOneRequest() {
    val firstTokenSeen = AtomicBoolean(false)
    RelaisEngine.generate(
      context,
      RelaisRequest(text = "Reply with a single word."),
      onToken = { firstTokenSeen.set(true) },
      shouldCancel = { firstTokenSeen.get() },
    )
  }

  /** Far enough past any plausible `ttlMs` that a real scheduling delay can never mask the release. */
  private fun farFutureNowMs(): Long = System.currentTimeMillis() + FAR_FUTURE_OFFSET_MS

  /**
   * Waits for a SERVICE-driven start (not a request-driven reload) to fully settle. In
   * `RelaisNodeService`'s init thread, [RelaisEngine.isReady] flips first (`RelaisNodeService.kt:281`)
   * and only after that does the thread tear listeners down, rebind HTTP/HTTPS, register discovery,
   * and finally republish `listenersUp`/clear `startupInProgress` (`endStartup()`, the attempt's
   * last write). Polling `isReady` alone lands inside that up-to-~1s window and reads a stale
   * STARTING. Tolerant of the pre-dispatch instant right after `onReceive()` returns, where
   * `startupInProgress` is already false but so is `isReady` — the `isReady` conjunct handles it.
   * Request-driven reload waits do not need this: they publish `startupInProgress` themselves and
   * never bounce listeners.
   */
  private fun awaitNodeSettled(timeoutMs: Long = 180_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val liveness = RelaisLivenessState.snapshot
      if (RelaisEngine.isReady && liveness.listenersUp && !liveness.startupInProgress) return
      Thread.sleep(500)
    }
    val liveness = RelaisLivenessState.snapshot
    fail(
      "node did not settle within ${timeoutMs}ms: isReady=${RelaisEngine.isReady}, " +
        "listenersUp=${liveness.listenersUp}, startupInProgress=${liveness.startupInProgress}",
    )
  }

  private fun currentNodeState(): NodeState {
    // Snapshot ONCE, then engine flags — computeNodeState's own read-order rule.
    val liveness = RelaisLivenessState.snapshot
    return computeNodeState(
      shouldRun = RelaisConfig.shouldRun(context),
      ready = RelaisEngine.isReady,
      listenersUp = liveness.listenersUp,
      startupInProgress = liveness.startupInProgress,
      lastInitFailed = RelaisEngine.lastInitFailed,
      thermalStatus = ThermalGovernor.statusValue,
      idleUnloaded = liveness.idleUnloaded,
    )
  }

  /** `/health` is unauthenticated (RelaisHttpServer.kt) — plain loopback GET, no bearer key. */
  private fun healthState(): String {
    val conn = URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection
    conn.connectTimeout = 5_000
    conn.readTimeout = 5_000
    return try {
      val body = conn.inputStream.bufferedReader().readText()
      JSONObject(body).getString("state")
    } finally {
      conn.disconnect()
    }
  }

  /** Reads one counter/gauge value off the live `RelaisMetrics.renderProm` text (in-process — no HTTP hop needed). */
  private fun promCounter(name: String): Double {
    val text = RelaisMetrics.renderProm(context)
    val match =
      Regex("^$name (\\S+)$", RegexOption.MULTILINE).find(text)
        ?: error("metric $name not found in /metrics output:\n$text")
    return match.groupValues[1].toDouble()
  }

  /** `RelaisWatchdog.step()` is private; read the same pref file/key it persists to directly. */
  private fun readWatchdogStep(): Int =
    context.getSharedPreferences(WATCHDOG_PREFS, Context.MODE_PRIVATE).getInt(WATCHDOG_KEY_STEP, 0)

  private fun countThreadsNamed(name: String): Int = Thread.getAllStackTraces().keys.count { it.name == name }

  private fun startAndBindNode(): RelaisNodeService.LocalBinder {
    RelaisNodeService.start(context)
    val latch = CountDownLatch(1)
    var bound: RelaisNodeService.LocalBinder? = null
    val conn =
      object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
          bound = service as RelaisNodeService.LocalBinder
          latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {}
      }
    val accepted = context.bindService(Intent(context, RelaisNodeService::class.java), conn, Context.BIND_AUTO_CREATE)
    if (accepted) {
      nodeConnection = conn
      nodeBound = true
    }
    assertTrue("node service refused bind", accepted)
    assertTrue("node service did not bind within 20s", latch.await(20, TimeUnit.SECONDS))
    return bound!!
  }

  private fun waitForListener(): Boolean {
    val deadline = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < deadline) {
      if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 8080), 1_000) } }.isSuccess) return true
      Thread.sleep(250)
    }
    return false
  }

  private companion object {
    const val TAG = "RelaisIdleUnloadProbe"
    const val METRIC_UNLOADS_TOTAL = "relais_engine_unloads_total"
    const val METRIC_LOAD_COUNT = "relais_engine_load_duration_seconds_count"
    const val COUNTER_DELTA = 0.0001
    const val SYNTHETIC_TTL_MS = 1_000L
    const val FAR_FUTURE_OFFSET_MS = 120_000L
    const val CYCLE_COUNT = 5
    const val WATCHDOG_PREFS = "relais"
    const val WATCHDOG_KEY_STEP = "watchdog_fail_step"
  }
}

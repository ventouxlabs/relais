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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "RelaisNodeService"
private const val CHANNEL_ID = "relais_node"
private const val NOTIFICATION_ID = 4242

// Idle-TTL poll cadence (#178). Independent of the configured TTL: a short, fixed poll interval
// (vs. e.g. AlarmManager/WorkManager, whose granularity — 15 min minimum for periodic WorkManager —
// is coarser than a sensible idle TTL) so the engine is released reasonably close to the TTL
// deadline rather than up to one whole scheduling period late. Cheap: each tick is a couple of
// volatile reads (RelaisEngine.isReady / RelaisMetrics.queueDepth) unless it actually decides to
// unload, so polling every minute is negligible overhead for a foreground service that's already
// resident and holding a wake lock.
private const val IDLE_TTL_POLL_INTERVAL_MS = 60_000L

/**
 * Pure decision behind [RelaisNodeService]'s startup dispatch guard: should a new init attempt be
 * launched right now? Extracted so the exact onCreate/onStartCommand gating logic is JVM-testable
 * without a Context/Service. The real concurrency guard is an `AtomicBoolean.compareAndSet` in
 * [RelaisNodeService] itself — this predicate is a readable pre-check, not the sole source of
 * atomicity (this file can't observe a CAS race in a plain JVM test).
 *
 * [listenersUp] is why `ready` alone is not enough. A TLS bind failure on `:8443` after loopback
 * `:8080` has already started leaves the engine resident and `isReady` true — so gating only on
 * `ready` meant no later START would retry, and the node reported **LIVE** with no HTTPS listener,
 * permanently, even once the port conflict cleared.
 *
 * That was introduced by making bind failure *propagate* rather than be swallowed. The lesson is
 * worth stating where the fix lives: **making a failure visible is not the same as making it
 * recoverable.** The old code hid the error and left no listener; the new code surfaced it and left
 * a node that claimed to be up. Both leave the user unable to connect; the second is more confident
 * about it.
 */
internal fun shouldDispatchStartup(
  ready: Boolean,
  dispatchInFlight: Boolean,
  listenersUp: Boolean,
): Boolean = (!ready || !listenersUp) && !dispatchInFlight

/**
 * Headless foreground service that hosts the resident multimodal engine (Gate 1) and the LAN
 * endpoint (Gate 3), and holds a partial wake lock so the engine survives screen-off / Doze
 * (Gate 2).
 *
 * Bind to it for in-process access via [LocalBinder]; the engine is also reachable over HTTP.
 */
class RelaisNodeService : Service() {
  private val binder = LocalBinder()
  private var httpServer: RelaisHttpServer? = null
  private var httpsServer: RelaisHttpServer? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var idleTtlExecutor: ScheduledExecutorService? = null

  // Guards the single init path (delta review: onStartCommand used to be a bare START_STICKY, so a
  // retry START against an already-alive-but-failed service — gated-repo 401, bad model id, process
  // never died — silently did nothing; same dead path for the watchdog's revive). CAS makes repeated
  // dispatch calls (onCreate racing onStartCommand, multiple START taps, watchdog + operator racing)
  // launch at most one concurrent "relais-init" thread.
  private val startupDispatchInFlight = AtomicBoolean(false)

  inner class LocalBinder : Binder() {
    val isReady: Boolean
      get() = RelaisEngine.isReady

    fun generate(request: RelaisRequest): RelaisResult =
      RelaisEngine.generate(this@RelaisNodeService, request)
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    createChannel()
    startForeground(NOTIFICATION_ID, buildNotification("Starting…"), foregroundType())

    @Suppress("DEPRECATION")
    wakeLock =
      (getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relais:node")
        .apply { setReferenceCounted(false); acquire() }

    RelaisConfig.incrementRestartCount(applicationContext) // process/service starts; via /metrics (Gate 3)
    ThermalGovernor.register(applicationContext) // thermal-aware backpressure (Gate 3)

    startIdleTtlTicker() // idle-TTL auto-unload (#178) — safe to start before the engine exists;
    // each tick just no-ops via RelaisEngine.releaseIfIdle's own isReady check until there's an
    // engine resident to release.

    dispatchStartupIfNeeded()
  }

  /**
   * Idle-TTL auto-unload (#178): polls [RelaisEngine.releaseIfIdle] on a fixed cadence
   * ([IDLE_TTL_POLL_INTERVAL_MS]) for the lifetime of this service instance. A dedicated
   * single-thread [ScheduledExecutorService] (mirrors [ThermalGovernor]'s own executor) rather than
   * [RelaisWatchdog]'s AlarmManager approach: the watchdog must survive process death (it re-launches
   * a killed service), but the resident engine dies WITH the process anyway, so there is nothing to
   * poll for once this service instance is gone — a plain in-process ticker is sufficient and avoids
   * both AlarmManager's exact-alarm permission dance and WorkManager's 15-minute periodic-work floor
   * (coarser than a sensible idle TTL).
   */
  private fun startIdleTtlTicker() {
    val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "relais-idle-ttl") }
    idleTtlExecutor = executor
    executor.scheduleWithFixedDelay(
      { runCatching { checkIdleUnload() }.onFailure { Log.w(TAG, "idle-TTL tick failed", it) } },
      IDLE_TTL_POLL_INTERVAL_MS,
      IDLE_TTL_POLL_INTERVAL_MS,
      TimeUnit.MILLISECONDS,
    )
  }

  private fun checkIdleUnload() {
    val ttlMinutes = RelaisConfig.idleTtlMinutes(applicationContext)
    if (ttlMinutes <= IDLE_TTL_DISABLED_MINUTES) return // operator disabled auto-unload (0 = never)
    val released = RelaisEngine.releaseIfIdle(ttlMs = ttlMinutes.toLong() * 60_000L)
    if (released) {
      Log.i(TAG, "idle TTL: released resident engine after ${ttlMinutes}m of inactivity")
      updateNotification("Idle — engine released (reloads automatically on the next request)")
    }
  }

  /**
   * The single guarded entry point into the "relais-init" path — called from both [onCreate] (a
   * fresh process/service instance) and [onStartCommand] (every `startForegroundService` call,
   * including one against an already-alive instance). That second call site is the fix: a service
   * that's alive but never came up (failed init, no `stopSelf()`) previously had no way to re-init —
   * `onStartCommand` was a bare `START_STICKY` — so a retry START (control-panel or
   * [RelaisWatchdog]'s revive) silently did nothing.
   *
   * [shouldDispatchStartup] is a readable pre-check; [startupDispatchInFlight]'s `compareAndSet` is
   * what actually makes concurrent calls (onCreate racing onStartCommand, repeated START taps) safe.
   */
  /**
   * Stops and releases both listeners, leaving the fields null and the shared state false.
   *
   * **The rule: never clear or replace a listener reference without stopping what it points at.**
   * A dropped reference to a *live* server is unreachable and still owns its port, so the next bind
   * collides with an orphan nothing can stop — and every retry after that fails identically while
   * the service reports no listeners. That is an unrecoverable node produced by the recovery path
   * itself.
   *
   * This must be unconditional at the start of a retry, not a branch. [RelaisListenerState] is an
   * AND of two independent listeners, so "one down, one up" is not a rare case — it is half the
   * state space, and it is exactly the case a retry meets. Making that state *visible* did not make
   * its transitions safe.
   *
   * `stop()` is safe to call on an already-stopped server (it closes a closed socket under
   * `runCatching`, joins a null thread, and shuts down an idle pool), so the double-stop a caught
   * failure can produce is a no-op rather than something to guard.
   */
  private fun stopListeners() {
    runCatching { httpServer?.stop() }
    runCatching { httpsServer?.stop() }
    httpServer = null
    httpsServer = null
    refreshListenerState()
  }

  /**
   * Recomputes [RelaisListenerState.listenersUp] from the live sockets and returns it.
   *
   * The single place the predicate is written. `isListening`, not `!= null`: a stopped server is
   * still a non-null field, so a null check answered "did someone assign this?" rather than "is a
   * listener up?" — which is how a failed rebind once became permanent, and how a bind failure came
   * to report LIVE. Ask the artefact.
   */
  private fun refreshListenerState(): Boolean {
    val up = httpServer?.isListening == true && httpsServer?.isListening == true
    RelaisListenerState.listenersUp = up
    return up
  }

  private fun dispatchStartupIfNeeded() {
    // One expression for "are the listeners up", shared with every user-visible surface via
    // RelaisListenerState — two copies of this predicate is exactly how the display and the retry
    // gate would drift back apart.
    val listenersUp = refreshListenerState()
    if (!shouldDispatchStartup(RelaisEngine.isReady, startupDispatchInFlight.get(), listenersUp)) return
    if (!startupDispatchInFlight.compareAndSet(false, true)) return // lost the race; another dispatch is already running

    // Provision the model (download if missing) then initialize the resident engine off the main
    // thread; start the endpoint when ready.
    thread(name = "relais-init") {
      RelaisEngine.lastInitFailed = false // new attempt: drop any prior failure so a restart-after-
      // failure doesn't flash NodeState.ERROR in the window before startupInProgress flips.
      RelaisEngine.startupInProgress = true // tell the watchdog "coming up", not "dead" (slow downloads)
      RelaisNodeProgress.reset() // drop any stale phase/bytes from a prior attempt (control-panel phase line)
      try {
        updateNotification("Provisioning model…")
        val modelPath =
          RelaisModelProvisioner.ensureModel(applicationContext) { pct ->
            updateNotification("Downloading model $pct%…")
          }
        RelaisNodeProgress.phase = ProvisionPhase.LOADING_ENGINE
        RelaisEngine.ensureInitialized(applicationContext, modelPath)
        // Register the EmbeddingGemma embedder so /v1/embeddings can report availability + provision
        // on demand. register() is cheap (no download/load). warmIfProvisioned() background-loads an
        // ALREADY-downloaded model (no token, no fetch) so a restart serves embeddings without a first
        // 503; on a fresh node it no-ops, and the endpoint provisions on the first embeddings request.
        cc.grepon.relais.embed.EmbeddingGemmaEmbedder.register()
        cc.grepon.relais.embed.EmbeddingGemmaEmbedder.INSTANCE.warmIfProvisioned(applicationContext)
        // Image-gen (#16): register the flavor's RelaisImageGenerator — full = sd.cpp/Vulkan via the
        // process-isolated :imagegen service; degoogled = no-op (endpoint stays 501). Cheap (no load);
        // the route gates on isAvailable (Vulkan + provisioned) and provisions on demand via 503.
        cc.grepon.relais.imagegen.ImageGenRegistration.register(applicationContext)
        // TTS (#168): register the sherpa-onnx engine on ALL flavors (GMS-free, unlike image-gen/OCR),
        // so `/v1/audio/speech` works on degoogled too. Cheap (no load); the route gates on availability
        // and provisions the Piper voice on demand via 503.
        cc.grepon.relais.tts.TtsRegistration.register(applicationContext)
        // A retry can arrive with one listener still live — `listenersUp` is an AND, so "HTTP died,
        // HTTPS still bound" is an ordinary state, and assigning over a live server would orphan it
        // holding its port. Release both before rebuilding either.
        stopListeners()
        // Security C1: plaintext HTTP is loopback-only (in-device app/dev); the LAN is served only
        // over HTTPS, so the bearer key never crosses the network in cleartext.
        httpServer = RelaisHttpServer(applicationContext, port = 8080, bindAddr = "127.0.0.1").also { it.start() }
        // Through the single owner, so its failure contract is inherited rather than restated.
        // A false here throws into the catch below, which tears the partial startup down so a
        // later START can retry.
        check(startHttpsListener()) { "HTTPS listener failed to bind :8443" }
        RelaisDiscovery.register(applicationContext) // advertise _relais._tcp for zero-config LAN discovery
        // Periodic TTL prune for the optional session memory (Feature #5). Idempotent + no-ops when
        // session memory is disabled, so scheduling it unconditionally is a true no-op by default.
        cc.grepon.relais.worker.SessionPruneWorker.schedule(applicationContext)
        // Drain any batch jobs left queued by a prior crash/restart (Feature #14); a no-op if empty.
        cc.grepon.relais.worker.BatchWorker.kick(applicationContext)
        updateNotification("Resident engine ready · http 127.0.0.1:8080 · https :8443 (LAN)")
        Log.i(TAG, "Node up: engine resident; http loopback :8080, https LAN :8443")
        // Now reachable — surfaces may read LIVE. This must stay ahead of the `finally` that clears
        // startupInProgress: the invariant every polling surface reads against is that startup is
        // never published as finished before the listeners it started are published. Publishing
        // them in the other order lets a poll compose "listeners down" with "startup finished" and
        // render a node that just came up healthy as OFFLINE, offering START. See the read-order
        // comment in RelaisShellViewModel.snapshotPanelState.
        refreshListenerState()
        // Security H3: never log the API key — it is shown in the Relais Node control screen.
      } catch (e: Exception) {
        Log.e(TAG, "Node init failed", e)
        RelaisEngine.lastInitFailed = true // surfaced as NodeState.ERROR (e.g. QS tile)
        // Tear the listeners down rather than leaving whichever one started. A TLS bind failure on
        // :8443 lands here with loopback :8080 already up and the engine resident, and a half-open
        // node is the worst of the three states: it answers on loopback, reports LIVE, and serves
        // no LAN. Clearing both is also what makes `listenersUp` false, which is what lets a later
        // START actually retry — without it the failure was visible and permanent.
        //
        // The engine deliberately stays resident: it initialised fine, reloading it costs seconds
        // of model load, and the init body is `ensure`-shaped so a retry no-ops through it and
        // rebuilds only what is missing.
        // Through the shared teardown, so the stop-before-clear rule is inherited rather than
        // restated — and so this path cannot drift from the retry path that must obey the same rule.
        // It also clears RelaisListenerState: the engine stays resident, so `isReady` remains true,
        // and without that every surface would read LIVE for a node nothing can reach while the
        // false LIVE suppressed the retry that would fix it.
        stopListeners()
        runCatching { RelaisDiscovery.unregister() } // stop advertising a node that is not serving
        updateNotification("Init failed: ${e.message}")
      } finally {
        RelaisEngine.startupInProgress = false
        RelaisNodeProgress.reset()
        startupDispatchInFlight.set(false) // release the guard — a future retry (fresh START) may dispatch again
      }
    }
  }

  /**
   * The **only** place an HTTPS listener is constructed, and the single owner of what happens when
   * one fails to come up.
   *
 * Kept as a single owner even though there is currently one caller, because there were two and
   * will be again: the dynamic LAN rebind (cut from this release, tracked as a follow-up) did
   * stop-then-start-then-publish exactly as startup does, and both grew the identical hole —
   * `it.start()` throwing meant the assignment never ran, so the field kept pointing at a *stopped*
   * server that every liveness check read as healthy. It was fixed on one path and not the other.
   * **Whoever restores the rebind should call this rather than repeat it.**
   *
   * The rule: clear the reference *before* constructing, so a throw can never leave a stale one;
   * report success as a value the caller must handle; and leave state honest — no listener, nothing
   * claiming otherwise — so a retry is possible.
   *
   * @return true when a listener is bound and accepting.
   */
  private fun startHttpsListener(): Boolean {
    // Stop, THEN clear. Clearing alone drops a reference that may point at a live listener still
    // owning :8443 — the replacement bind would then collide with an orphan nothing can reach. The
    // ordering matters as much as the clearing: between here and a successful assignment there must
    // be no window in which the field names something that is not listening.
    runCatching { httpsServer?.stop() }
    httpsServer = null
    return runCatching {
        httpsServer =
          RelaisHttpServer(applicationContext, port = 8443, tls = true, bindAddr = "0.0.0.0")
            .also { it.start() }
      }
      .onFailure {
        Log.e(TAG, "HTTPS listener failed to bind :8443", it)
        httpsServer = null
      }
      .isSuccess
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Re-dispatch on every start command, not just onCreate — this is what lets a retry START (or
    // RelaisWatchdog's revive) actually re-init an already-alive-but-failed service. No-ops via
    // shouldDispatchStartup/the CAS guard when already LIVE or already mid-attempt.
    dispatchStartupIfNeeded()
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // The node is headless and must outlive its control-panel task. If the task is removed (user
    // swipe, or a recents sweep), keep running and make sure the watchdog heartbeat is armed. (A
    // true force-stop still can't be recovered in-app — Android disables a stopped app's alarms and
    // boot-receiver until it's explicitly relaunched; that's why the panel is excludeFromRecents.)
    reArmWatchdogIfShouldRun(applicationContext)
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    idleTtlExecutor?.shutdownNow()
    ThermalGovernor.unregister()
    RelaisDiscovery.unregister()
    httpServer?.stop()
    httpsServer?.stop()
    // After the stops, so it reads the closed sockets rather than the intent to close them. A
    // destroyed service that left this true would have the next process read LIVE before any
    // listener existed.
    refreshListenerState()
    RelaisEngine.shutdown()
    runCatching { wakeLock?.release() }
    super.onDestroy()
    Log.i(TAG, "Node stopped")
  }

  private fun foregroundType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    else 0

  private fun createChannel() {
    val mgr = getSystemService(NotificationManager::class.java)
    mgr.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Relais Node", NotificationManager.IMPORTANCE_LOW)
    )
  }

  private fun buildNotification(text: String): Notification =
    Notification.Builder(this, CHANNEL_ID)
      .setContentTitle("Relais Node")
      .setContentText(text)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setOngoing(true)
      .build()

  private fun updateNotification(text: String) {
    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
  }

  companion object {
    fun start(context: Context) {
      RelaisConfig.setShouldRun(context, true)
      val intent = Intent(context, RelaisNodeService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
      else context.startService(intent)
      RelaisWatchdog.schedule(context) // self-heal after crash/OOM (START_STICKY alone insufficient)
    }

    fun stop(context: Context) {
      RelaisConfig.setShouldRun(context, false)
      RelaisWatchdog.cancel(context)
      context.stopService(Intent(context, RelaisNodeService::class.java))
    }

    /**
     * Re-arm the crash/OOM watchdog heartbeat iff the node is meant to be running. Extracted from
     * [onTaskRemoved] — which can't be invoked on a bare Service instance — so the gate is
     * instrumentable. Starting an already-scheduled watchdog is idempotent.
     */
    internal fun reArmWatchdogIfShouldRun(context: Context) {
      if (RelaisConfig.shouldRun(context)) RelaisWatchdog.schedule(context)
    }
  }
}

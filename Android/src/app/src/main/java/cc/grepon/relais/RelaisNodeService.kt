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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 */
internal fun shouldDispatchStartup(ready: Boolean, dispatchInFlight: Boolean): Boolean =
  !ready && !dispatchInFlight

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

  // feature-18 T5b: the one-shot watch that re-mints + rebinds :8443 when the LAN comes up after a
  // boot-time start. Set before the re-mint, so two interfaces appearing together can't both pass.
  private val lanReissueDone = AtomicBoolean(false)
  private var lanReissueCallback: ConnectivityManager.NetworkCallback? = null

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
  private fun dispatchStartupIfNeeded() {
    if (!shouldDispatchStartup(RelaisEngine.isReady, startupDispatchInFlight.get())) return
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
        // Security C1: plaintext HTTP is loopback-only (in-device app/dev); the LAN is served only
        // over HTTPS, so the bearer key never crosses the network in cleartext.
        httpServer = RelaisHttpServer(applicationContext, port = 8080, bindAddr = "127.0.0.1").also { it.start() }
        httpsServer = RelaisHttpServer(applicationContext, port = 8443, tls = true, bindAddr = "0.0.0.0").also { it.start() }
        armLanReissueIfNeeded() // feature-18: the boot race — see the method KDoc
        RelaisDiscovery.register(applicationContext) // advertise _relais._tcp for zero-config LAN discovery
        // Periodic TTL prune for the optional session memory (Feature #5). Idempotent + no-ops when
        // session memory is disabled, so scheduling it unconditionally is a true no-op by default.
        cc.grepon.relais.worker.SessionPruneWorker.schedule(applicationContext)
        // Drain any batch jobs left queued by a prior crash/restart (Feature #14); a no-op if empty.
        cc.grepon.relais.worker.BatchWorker.kick(applicationContext)
        updateNotification("Resident engine ready · http 127.0.0.1:8080 · https :8443 (LAN)")
        Log.i(TAG, "Node up: engine resident; http loopback :8080, https LAN :8443")
        // Security H3: never log the API key — it is shown in the Relais Node control screen.
      } catch (e: Exception) {
        Log.e(TAG, "Node init failed", e)
        RelaisEngine.lastInitFailed = true // surfaced as NodeState.ERROR (e.g. QS tile)
        updateNotification("Init failed: ${e.message}")
      } finally {
        RelaisEngine.startupInProgress = false
        RelaisNodeProgress.reset()
        startupDispatchInFlight.set(false) // release the guard — a future retry (fresh START) may dispatch again
      }
    }
  }

  /**
   * Closes the boot race: re-mint the leaf certificate and rebind :8443 once a LAN address appears
   * (feature-18 T5b).
   *
   * `RelaisBootReceiver` starts this service on `BOOT_COMPLETED`, well before DHCP completes, so
   * the first mint sees no interfaces and produces a **loopback-only** certificate. Re-issue is
   * otherwise computed only at node start, so an auto-started node would serve a certificate with
   * no LAN SAN for its entire uptime and every LAN client would fail hostname verification until a
   * human restarted it — in exactly the unattended-appliance mode the README advertises.
   *
   * This lives here rather than in [RelaisTls] because **only this class can act on the result.**
   * `RelaisTls` can re-mint the keystore, but a bound `SSLServerSocket` cannot pick up a new
   * certificate; the listener has to be stopped and reconstructed, and `httpsServer` is this
   * service's field. `RelaisTls` mints; the service re-mints *and rebinds*.
   *
   * Rebinds **once**: the watch is unregistered after a rebind actually happens (or after a real
   * failure), so a flapping Wi-Fi link cannot rebuild the listener over and over. [lanReissueDone]
   * is set *before* the re-mint rather than after, so two interfaces coming up together cannot both
   * pass the guard while the first is still minting.
   *
   * No manifest change: `ACCESS_NETWORK_STATE` is already granted.
   */
  private fun armLanReissueIfNeeded() {
    // Two distinct reasons to arm, and the first one is the whole point of the feature:
    //   - no addresses at all — the boot race itself. `needsLanReissue` is FALSE here (there is
    //     nothing yet to re-issue *for*), so gating on it alone would refuse to arm in precisely
    //     the scenario this exists for, and would only ever arm in the narrow case where the LAN
    //     happened to come up between the mint and this call.
    //   - addresses present but the certificate predates them — a start that raced a reconnect.
    //
    // Note the caller starts the listener immediately above, and `start()` mints on its own accept
    // thread, so this runs CONCURRENTLY with the first mint and may see no keystore at all.
    // `needsLanReissue` answers true when it cannot tell, so an unreadable-because-in-flight
    // keystore arms rather than silently skipping; `reissueAndRebind` re-checks before it touches
    // the listener, so arming when it turns out to be unnecessary costs one predicate call.
    val noAddressesYet = RelaisLanIp.allLanAddresses().isEmpty()
    if (!noAddressesYet && !RelaisTls.needsLanReissue(applicationContext)) return
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    val callback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          // onAvailable runs on a binder/handler thread. Everything below touches httpsServer, so
          // it hops to the main thread first — racing an in-flight accept() on the old socket is
          // exactly the kind of bug that only shows up on a real device under real traffic.
          if (!lanReissueDone.compareAndSet(false, true)) return
          Handler(Looper.getMainLooper()).post { reissueAndRebind() }
        }
      }
    lanReissueCallback = callback
    // NET_CAPABILITY_NOT_VPN is present by DEFAULT on a NetworkRequest.Builder, which would make a
    // Tailscale/WireGuard-only network fail to match — the one topology whose addresses this
    // feature goes out of its way to put in the certificate. Removing it is what lets an
    // overlay-only node arm at all.
    val request =
      NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
    runCatching { cm.registerNetworkCallback(request, callback) }
      .onFailure { Log.w(TAG, "Could not watch for LAN availability; cert stays loopback-only", it) }
  }

  /**
   * Re-mints for the live addresses and swaps the listener.
   *
   * Keeps the watch armed when the callback arrived **before** the interface had an address —
   * `onAvailable` routinely fires ahead of address assignment, which is why
   * `onLinkPropertiesChanged` exists at all. Disarming on that early bail would spend the one-shot
   * on a callback that did nothing, and [lanReissueDone] would then block every real one.
   */
  private fun reissueAndRebind() {
    if (RelaisLanIp.allLanAddresses().isEmpty()) {
      // Not a failure and not a rebind: release the guard and stay armed for the next callback.
      lanReissueDone.set(false)
      return
    }
    try {
      // Re-check here, not only at arming time. Arming deliberately over-answers (it cannot read a
      // keystore that `start()` is still writing on the accept thread), and this is what makes that
      // free: if the certificate already covers the live addresses there is nothing to do, and
      // rebinding anyway would drop live connections and churn the cert for no reason.
      if (!RelaisTls.needsLanReissue(applicationContext)) {
        Log.i(TAG, "LAN is up and the certificate already covers it; no rebind needed")
        return
      }
      RelaisTls.reissueForLan(applicationContext)
      httpsServer?.stop()
      // Byte-for-byte the construction in dispatchStartupIfNeeded, just later. In-flight
      // connections on the old listener are dropped — acceptable, since it was serving a
      // loopback-only cert that no LAN client could verify anyway.
      httpsServer =
        RelaisHttpServer(applicationContext, port = 8443, tls = true, bindAddr = "0.0.0.0")
          .also { it.start() }
      Log.i(TAG, "Re-issued the leaf certificate for the LAN and rebound :8443")
    } catch (e: Exception) {
      Log.e(TAG, "LAN re-issue/rebind failed", e)
    } finally {
      // In a finally, not after a success: a throw partway through must still stop the watch, or a
      // flapping link retries the same failure indefinitely. The early "no address yet" bail above
      // returns before this block precisely so it does NOT consume the one-shot.
      unregisterLanReissue()
    }
  }

  private fun unregisterLanReissue() {
    val cb = lanReissueCallback ?: return
    lanReissueCallback = null
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    runCatching { cm.unregisterNetworkCallback(cb) }
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
    unregisterLanReissue()
    httpsServer?.stop()
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

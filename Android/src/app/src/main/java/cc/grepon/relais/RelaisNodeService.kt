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
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

// Retry cadence for a LAN re-issue blocked by the initial mint (feature-18 T5b). Five seconds is
// comfortably longer than a mint; 24 attempts is ~2 minutes, after which a failure is structural
// rather than a race and retrying forever would just burn crypto for the life of the node.
private const val LAN_REISSUE_RETRY_DELAY_MS = 5_000L
private const val LAN_REISSUE_MAX_ATTEMPTS = 24

/**
 * Pure decision behind [RelaisNodeService]'s startup dispatch guard: should a new init attempt be
 * launched right now? Extracted so the exact onCreate/onStartCommand gating logic is JVM-testable
 * without a Context/Service. The real concurrency guard is an `AtomicBoolean.compareAndSet` in
 * [RelaisNodeService] itself — this predicate is a readable pre-check, not the sole source of
 * atomicity (this file can't observe a CAS race in a plain JVM test).
 */
/**
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

  // feature-18 T5b: the one-shot watch that re-mints + rebinds :8443 when the LAN comes up after a
  // boot-time start. Consumed at the commit point in reissueAndRebind, not on entry.
  private val lanReissueDone = AtomicBoolean(false)

  // Volatile: assigned on the "relais-init" thread, read on main by onDestroy. A stale null there
  // makes unregisterLanReissue return early, leaving the NetworkCallback registered forever — and
  // because it is an anonymous inner class it pins this destroyed Service, so a link-properties
  // change hours later can start a listener for a node the user stopped this morning.
  @Volatile private var lanReissueCallback: ConnectivityManager.NetworkCallback? = null

  /**
   * Set as the FIRST statement of [onDestroy], read as the FIRST statement of [reissueAndRebind].
   *
   * `unregisterLanReissue` stops *future* callbacks but cannot recall a `reissueAndRebind` already
   * sitting in the main looper's queue. That queued post would run after `onDestroy` and construct
   * a `RelaisHttpServer` on `applicationContext` — which outlives the service — leaving a
   * LAN-facing TLS listener on `0.0.0.0:8443` owned by a destroyed service, with nothing left able
   * to stop it short of process death.
   *
   * Volatile, and set before [lanReissueExecutor] is shut down: a task already running observes it
   * and returns, and one not yet started never runs. Since the work no longer sits on a drainable
   * looper queue, this flag is the whole of the shutdown guarantee.
   */
  @Volatile private var destroyed = false

  /**
   * Where the LAN re-issue and rebind actually run (M3).
   *
   * A single-thread executor rather than the main looper, for three reasons that turn out to be one
   * change:
   *  - **Off the main thread.** The work is ~six PKCS12 parses with PBE decryption, an EC
   *    signature, a `store()` with PBE encrypt, and the IO — tens of milliseconds, so dropped
   *    frames and a StrictMode violation rather than an ANR. But `securePrefs` also touches
   *    EncryptedSharedPreferences/AndroidKeyStore, whose cost is unbounded and which lands at
   *    `BOOT_COMPLETED`, when margins are thinnest.
   *  - **It keeps the serialisation that let the CAS go.** A single-thread executor serialises
   *    exactly as a looper does, so [reissueAndRebind]'s plain read-then-set is still sound.
   *  - **It gives the rebind path the same lock as the mint path**, which closes the
   *    truncated-keystore-read window: a re-issue can no longer overlap the accept thread's write,
   *    and a truncated read is what would silently rotate the leaf key and break every pinned
   *    client.
   *
   * The trade is losing `removeCallbacksAndMessages`, so shutdown rests entirely on [destroyed] —
   * checked as the first statement of the scheduled work, and set before this is shut down.
   */
  private val lanReissueExecutor: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { Thread(it, "relais-lan-reissue") }

  /**
   * Guards the stop/construct sequence against [onDestroy], which holds it while setting
   * [destroyed]. Not the same as thread-confinement — see the invariant note at the construction
   * site for why that argument does not apply here and must not be reintroduced.
   */
  private val teardownLock = Any()

  /** Bounded retries for a re-issue blocked by the initial mint; see [scheduleReissueRetry]. */
  private val lanReissueAttempts = AtomicInteger(0)

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
    // `isListening`, NOT `!= null`. A stopped server is still non-null, so a null check answered
    // "did someone assign a field?" rather than "is a listener up?" — and read a dead listener as
    // healthy, which is how a failed rebind became permanent. Ask the artefact.
    val listenersUp = httpServer?.isListening == true && httpsServer?.isListening == true
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
        // Security C1: plaintext HTTP is loopback-only (in-device app/dev); the LAN is served only
        // over HTTPS, so the bearer key never crosses the network in cleartext.
        httpServer = RelaisHttpServer(applicationContext, port = 8080, bindAddr = "127.0.0.1").also { it.start() }
        // Same helper the rebind uses, so both inherit one failure contract rather than each
        // growing its own. A false here throws into the catch below, which tears the partial
        // startup down so a later START can retry.
        check(startHttpsListener()) { "HTTPS listener failed to bind :8443" }
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
        // Tear the listeners down rather than leaving whichever one started. A TLS bind failure on
        // :8443 lands here with loopback :8080 already up and the engine resident, and a half-open
        // node is the worst of the three states: it answers on loopback, reports LIVE, and serves
        // no LAN. Clearing both is also what makes `listenersUp` false, which is what lets a later
        // START actually retry — without it the failure was visible and permanent.
        //
        // The engine deliberately stays resident: it initialised fine, reloading it costs seconds
        // of model load, and the init body is `ensure`-shaped so a retry no-ops through it and
        // rebuilds only what is missing.
        runCatching { httpServer?.stop() }
        runCatching { httpsServer?.stop() }
        httpServer = null
        httpsServer = null
        unregisterLanReissue()
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
   * Startup and the LAN rebind both do stop-then-start-then-publish, and both had the identical
   * hole: `it.start()` throwing meant the assignment never ran, so the field kept pointing at a
   * *stopped* server that every liveness check read as healthy — node permanently without its LAN
   * listener, reporting live. It was fixed on the startup path first, and the rebind path had it
   * unchanged. **Three times on this branch a fix went where the bug was found rather than where
   * the mistake is made**, so the recovery rule lives here once and a third caller inherits it.
   *
   * The rule: clear the reference *before* constructing, so a throw can never leave a stale one;
   * report success as a value the caller must handle; and leave state honest — no listener, nothing
   * claiming otherwise — so a retry is possible.
   *
   * @return true when a listener is bound and accepting.
   */
  private fun startHttpsListener(): Boolean {
    // Cleared first, not in the failure branch: between here and a successful assignment there must
    // be no window in which the field names something that is not listening.
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

  /** Stops the current HTTPS listener and starts a fresh one. Same failure contract as [startHttpsListener]. */
  private fun replaceHttpsListener(): Boolean {
    httpsServer?.stop()
    return startHttpsListener()
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
    // A retry START after a failed init re-enters this method (startupDispatchInFlight is released
    // in a finally), and the assignment below would then overwrite a live registration — leaking
    // every callback but the last, each pinning this Service. Unregister first; it is a no-op when
    // nothing is registered.
    unregisterLanReissue()
    val noAddressesYet = RelaisLanIp.allLanAddresses().isEmpty()
    if (!noAddressesYet && !RelaisTls.needsLanReissue(applicationContext)) return
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    val callback =
      object : ConnectivityManager.NetworkCallback() {
        // BOTH callbacks, and that is the fix for the third incarnation of this bug. `onAvailable`
        // fires when a network becomes usable, which is routinely BEFORE DHCP has assigned an
        // address — and it does not fire again when the address later arrives. Listening only for
        // it meant the one callback we got was spent on a moment with nothing to re-mint for, and
        // the node then served its loopback-only certificate for its entire uptime. Address
        // assignment surfaces as a link-properties change, so that is the event that actually
        // carries the information this feature needs.
        override fun onAvailable(network: Network) = schedule()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
          schedule()

        /**
         * Callbacks arrive on a binder/handler thread. Hand off to [lanReissueExecutor], which both
         * serialises the work — so [reissueAndRebind] needs no CAS — and keeps the crypto and
         * keystore IO off the main thread. `execute` throws `RejectedExecutionException` once the
         * executor is shut down, which is the normal state after `onDestroy`, so the hand-off is
         * wrapped rather than allowed to crash a framework callback.
         */
        private fun schedule() {
          runCatching { lanReissueExecutor.execute { reissueAndRebind() } }
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
   * **Driven by observed addresses and by whether the work actually succeeded, not by which
   * callback fired.** Three states are kept distinct, and conflating any two of them has already
   * produced a bug in this method:
   *  - *not yet* — no addresses, or the re-issue could not run. Return, stay armed, leave the
   *    one-shot unspent; the next link-properties change retries.
   *  - *nothing to do* — the certificate already covers the LAN. Terminal: spend the one-shot and
   *    unregister.
   *  - *committed* — a new certificate exists. Spend the one-shot, rebind exactly once, and
   *    unregister however that goes.
   */
  private fun reissueAndRebind() {
    // FIRST statement, deliberately: a post queued before onDestroy still runs after it, and the
    // work below would otherwise start a listener that outlives the service.
    if (destroyed) return
    // Everything here runs on [lanReissueExecutor]'s single thread (see the callback's `schedule`),
    // which serialises it and is why a plain read-then-set suffices where a CAS used to be.
    if (lanReissueDone.get()) return

    // Nothing to re-mint FOR yet. `onAvailable` routinely precedes DHCP, so this is the common
    // case rather than an error — stay armed for the link-properties change carrying the address.
    if (RelaisLanIp.allLanAddresses().isEmpty()) return

    // Re-check rather than trust arming: arming deliberately over-answers, because it cannot read
    // a keystore the accept thread is still writing.
    if (!RelaisTls.needsLanReissue(applicationContext)) {
      Log.i(TAG, "LAN is up and the certificate already covers it; no rebind needed")
      lanReissueDone.set(true)
      unregisterLanReissue()
      return
    }

    // A failed re-issue leaves the certificate on disk unchanged, so rebinding would drop live
    // connections to serve exactly what was being served before. It is also RECOVERABLE and must
    // not spend the one-shot: on a fresh install this runs with CA minting disabled while the
    // accept thread is still generating the CA.
    //
    // Retry on our OWN schedule rather than waiting for another callback. "Stay armed" is not a
    // plan when the callbacks already delivered were the last ones — a node whose Wi-Fi came up
    // during the initial mint would then serve the loopback-only certificate until something
    // unrelated changed the network, or until a restart.
    if (!RelaisTls.reissueForLan(applicationContext)) {
      scheduleReissueRetry()
      return
    }

    // NOT marked done yet. The one-shot is spent only once a replacement is actually listening —
    // spending it here and having the rebind fail is what left the node permanently without its
    // LAN listener, since nothing would retry.
    try {
      // ── INVARIANT: once teardown has begun, nothing may construct a RelaisHttpServer. ──
      //
      // This is the ONLY place outside dispatchStartupIfNeeded that builds one. The listener binds
      // `0.0.0.0:8443` on `applicationContext`, which OUTLIVES this service — so a construction
      // that slips past teardown leaves a LAN-facing TLS listener nothing can stop short of killing
      // the process, while the notification is gone, the QS tile reads "stopped", and mDNS has been
      // unregistered. The user sees a node that is off and cannot turn off what is still listening.
      //
      // **WHAT THIS RESTS ON — check it against the code, do not trust this sentence.** The guard
      // is `destroyed` re-read *inside* [teardownLock], which `onDestroy` also holds while setting
      // it. Both of those are load-bearing:
      //  - **Inside the lock**, because the check at the top of this method is separated from this
      //    construction by a re-issue costing hundreds of milliseconds. A teardown landing in that
      //    gap passes the early check and still reaches here.
      //  - **A lock rather than a thread-confinement argument**, because this method does not run
      //    on `onDestroy`'s thread. An earlier version of this comment claimed it did — true when
      //    the work was a `mainHandler.post`, and made false by the later swap to
      //    [lanReissueExecutor] without the comment noticing. Do not restore a
      //    "same thread as onDestroy" argument unless you have re-derived it: **whether these two
      //    sequences share a thread is a property of how the work is scheduled, and that has
      //    already changed once underneath this reasoning.**
      synchronized(teardownLock) {
        if (destroyed || Thread.currentThread().isInterrupted) {
          Log.i(TAG, "Teardown began during the re-issue; not rebinding :8443")
          return
        }
        // In-flight connections on the old listener are dropped — acceptable, since it was serving
        // a loopback-only cert no LAN client could verify anyway.
        if (!replaceHttpsListener()) {
          // The certificate was re-issued but nothing is listening — e.g. another process took
          // 8443 between the stop and the bind. Leave the one-shot UNSPENT and stay registered so
          // a later callback retries; `startHttpsListener` has already cleared the stale reference,
          // so `listenersUp` reads false and a START can retry too.
          Log.w(TAG, "Re-issued the certificate but the rebind failed; staying armed for a retry")
          scheduleReissueRetry()
          return
        }
      }
      // Only now: a replacement is bound and accepting, so the watch has done its job.
      lanReissueDone.set(true)
      Log.i(TAG, "Re-issued the leaf certificate for the LAN and rebound :8443")
      unregisterLanReissue()
    } catch (e: Exception) {
      // Anything unexpected: leave the one-shot unspent and the watch armed rather than ending a
      // mechanism whose whole purpose is to recover.
      Log.e(TAG, "LAN rebind failed after a successful re-issue", e)
      scheduleReissueRetry()
    }
  }

  /**
   * Re-runs [reissueAndRebind] shortly, for the one failure that is expected and self-resolving:
   * the initial mint still holding the keystore while this path runs with CA minting forbidden.
   *
   * Bounded, because a retry loop that never gives up on a *structural* failure would re-run crypto
   * every few seconds for the life of the node. [LAN_REISSUE_MAX_ATTEMPTS] × the delay is about two
   * minutes, which is far longer than a mint and far shorter than an annoyance. Giving up logs at
   * WARN and leaves the certificate as it is — the node still works, it is simply loopback-only
   * until restarted, which is the pre-existing behaviour rather than a new failure.
   */
  private fun scheduleReissueRetry() {
    if (destroyed) return
    val attempt = lanReissueAttempts.incrementAndGet()
    if (attempt > LAN_REISSUE_MAX_ATTEMPTS) {
      Log.w(
        TAG,
        "LAN re-issue still not possible after $LAN_REISSUE_MAX_ATTEMPTS attempts; " +
          "the certificate stays loopback-only until the node is restarted",
      )
      return
    }
    Log.i(TAG, "LAN re-issue not possible yet (attempt $attempt); retrying shortly")
    // Rejected once the executor is shut down, which is the normal state after onDestroy.
    runCatching {
      lanReissueExecutor.schedule(
        { reissueAndRebind() },
        LAN_REISSUE_RETRY_DELAY_MS,
        TimeUnit.MILLISECONDS,
      )
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
    // Under [teardownLock], so this cannot interleave with the rebind's stop/construct sequence.
    // Setting the flag alone is NOT enough: the rebind reads it, then spends hundreds of
    // milliseconds re-issuing before it constructs, and a teardown landing in that gap used to be
    // able to stop the old listener and have a new one built behind it. The lock makes the two
    // sequences mutually exclusive rather than merely unlikely to overlap.
    synchronized(teardownLock) {
      destroyed = true
      lanReissueExecutor.shutdownNow()
    }
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

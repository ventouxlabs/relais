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

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import cc.grepon.relais.BuildConfig

private const val TAG = "RelaisDiscovery"
const val RELAIS_SERVICE_TYPE = "_relais._tcp."

/**
 * Zero-config LAN discovery via mDNS/NSD. Advertises the node as `_relais._tcp` so clients find it
 * by name instead of a hard-coded IP — fixes the IP-change problem the wifi soak surfaced.
 */
object RelaisDiscovery {
  private var nsdManager: NsdManager? = null
  private var listener: NsdManager.RegistrationListener? = null
  private var lifecycle = DiscoveryLifecycle()
  private var pendingRegistration: RegistrationRequest? = null
  private val lock = Any()

  private data class RegistrationRequest(
    val context: Context,
    val httpPort: Int,
    val httpsPort: Int,
  )

  /**
   * Snapshots the live capabilities for the TXT record. `tools` + `reasoning` are node-level (always
   * supported via the native LiteRT-LM API); `multimodal` is model-dependent and read from the
   * engine's truthful [RelaisEngine.isMultimodal] flag.
   */
  private fun liveCaps(): RelaisClientConfig.Capabilities =
    RelaisClientConfig.Capabilities(
      multimodal = RelaisEngine.isMultimodal,
      tools = true,
      reasoning = true,
    )

  /**
   * Builds the [NsdServiceInfo] with a dynamic TXT record reflecting the live model, app version,
   * and capabilities (via [RelaisClientConfig.buildDiscoveryTxt]). The TXT is cleartext LAN
   * broadcast — it carries only routing metadata, never the API key.
   */
  private fun buildServiceInfo(context: Context, httpPort: Int, httpsPort: Int): NsdServiceInfo {
    val txt =
      RelaisClientConfig.buildDiscoveryTxt(
        modelId = advertisedModelId(RelaisEngine.residentModelId, RelaisConfig.modelId(context)),
        version = BuildConfig.VERSION_NAME,
        httpsPort = httpsPort,
        caps = liveCaps(),
      )
    return NsdServiceInfo().apply {
      serviceName = "relais-node"
      serviceType = RELAIS_SERVICE_TYPE
      port = httpPort
      txt.forEach { (k, v) -> setAttribute(k, v) }
    }
  }

  fun register(context: Context, httpPort: Int = 8080, httpsPort: Int = 8443) {
    synchronized(lock) {
      val request = RegistrationRequest(context.applicationContext, httpPort, httpsPort)
      val transition = lifecycle.requestRegistration()
      lifecycle = transition.state
      when (transition.action) {
        DiscoveryAction.REGISTER -> startRegistrationLocked(request)
        DiscoveryAction.NONE -> if (lifecycle.pendingRegistration) pendingRegistration = request
        DiscoveryAction.UNREGISTER -> error("register never initiates unregistration")
      }
    }
  }

  /** Starts one NSD registration. The listener is retained until its terminal callback. */
  private fun startRegistrationLocked(request: RegistrationRequest) {
    check(listener == null) { "starting mDNS registration while a listener is still owned" }
    val manager = request.context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val info = buildServiceInfo(request.context, request.httpPort, request.httpsPort)
    lateinit var registrationListener: NsdManager.RegistrationListener
    registrationListener =
      object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
          Log.i(TAG, "mDNS registered: ${info.serviceName}.$RELAIS_SERVICE_TYPE port ${info.port}")
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
          Log.e(TAG, "mDNS registration failed: $errorCode")
          synchronized(lock) {
            if (listener !== registrationListener) return
            // NSD never acquired this registration, so drop only this failed ownership. A future
            // explicit register/update can retry; retaining it would permanently suppress that.
            listener = null
            nsdManager = null
            lifecycle = lifecycle.registrationFailed()
          }
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
          Log.i(TAG, "mDNS unregistered")
          synchronized(lock) {
            if (listener !== registrationListener) return
            // Android permits a RegistrationListener to be retired only after this callback. Do
            // not start the replacement before this point, even when unregisterService returned.
            listener = null
            nsdManager = null
            val transition = lifecycle.serviceUnregistered()
            lifecycle = transition.state
            if (transition.action == DiscoveryAction.REGISTER) {
              val refresh = checkNotNull(pendingRegistration) { "refresh lost its registration request" }
              pendingRegistration = null
              startRegistrationLocked(refresh)
            }
          }
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
          Log.e(TAG, "mDNS unregistration failed: $errorCode")
          synchronized(lock) {
            if (listener !== registrationListener) return
            // The old service may still be advertised. Keep owning its manager/listener so a
            // later refresh or stop can retry instead of orphaning the live registration.
            lifecycle = lifecycle.unregistrationFailed()
          }
        }
      }
    listener = registrationListener
    nsdManager = manager
    try {
      manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    } catch (e: Exception) {
      Log.e(TAG, "mDNS registration threw", e)
      if (listener === registrationListener) {
        listener = null
        nsdManager = null
        lifecycle = lifecycle.registrationFailed()
      }
    }
  }

  /**
   * Re-publishes the TXT record after the live model (or its capabilities) changed. NSD has no
   * portable in-place TXT update, so this unregisters then re-registers with a freshly-built record.
   * Re-entrancy is guarded by [lock]; a no-op if the service isn't currently registered (the next
   * [register] picks up the live values anyway).
   *
   * Called from [RelaisEngine.ensureModelSwapInBackground], on the swap thread, once the engine has
   * actually transitioned — the hot-swap path #180 introduced, which changes the resident model with
   * no process restart. (An earlier version of this doc said a switch "currently requires a process
   * restart … so the TXT is always fresh after a switch". That has been false since #180, and this
   * function had no callers at all until feature-09 added one, so the TXT went stale after every
   * in-process swap.)
   *
   * The replacement is callback-driven: Android only permits the old listener to be retired after
   * [NsdManager.RegistrationListener.onServiceUnregistered], so its callback starts a fresh
   * registration with a new listener. [unregister] cancels this pending replacement, which keeps a
   * service stop from reviving discovery after teardown began.
   */
  fun updateModel(context: Context, httpPort: Int = 8080, httpsPort: Int = 8443) {
    synchronized(lock) {
      if (!lifecycle.ownsListener) return // not registered; nothing to refresh
      pendingRegistration = RegistrationRequest(context.applicationContext, httpPort, httpsPort)
      val transition = lifecycle.requestRefresh()
      lifecycle = transition.state
      when (transition.action) {
        DiscoveryAction.UNREGISTER -> requestUnregistrationLocked()
        DiscoveryAction.NONE -> Unit
        DiscoveryAction.REGISTER -> error("refresh cannot register before retiring its listener")
      }
    }
  }

  fun unregister() {
    synchronized(lock) {
      // A stop wins over a model refresh that was waiting for onServiceUnregistered. We retain the
      // old listener until NSD confirms it is retired, but that callback has no replacement left
      // to launch.
      pendingRegistration = null
      val transition = lifecycle.requestStop()
      lifecycle = transition.state
      when (transition.action) {
        DiscoveryAction.UNREGISTER -> requestUnregistrationLocked()
        DiscoveryAction.NONE -> Unit
        DiscoveryAction.REGISTER -> error("stop never starts mDNS registration")
      }
    }
  }

  private fun requestUnregistrationLocked() {
    val manager = checkNotNull(nsdManager) { "unregister requested without an NSD manager" }
    val current = checkNotNull(listener) { "unregister requested without an NSD listener" }
    try {
      manager.unregisterService(current)
    } catch (e: Exception) {
      Log.e(TAG, "mDNS unregister threw", e)
      // The current service may still be registered. Retain ownership and let the next explicit
      // refresh/stop retry rather than pretending this listener is safe to replace.
      lifecycle = lifecycle.unregistrationFailed()
    }
  }
}

/** Pure lifecycle for NSD's asynchronous registration ownership rules. */
internal data class DiscoveryLifecycle(
  val ownsListener: Boolean = false,
  val unregistering: Boolean = false,
  val pendingRegistration: Boolean = false,
) {
  fun requestRegistration(): DiscoveryTransition =
    when {
      !ownsListener -> DiscoveryTransition(copy(ownsListener = true), DiscoveryAction.REGISTER)
      unregistering -> DiscoveryTransition(copy(pendingRegistration = true), DiscoveryAction.NONE)
      else -> DiscoveryTransition(this, DiscoveryAction.NONE)
    }

  fun requestRefresh(): DiscoveryTransition {
    check(ownsListener) { "cannot refresh mDNS without a listener" }
    val next = copy(pendingRegistration = true)
    return if (unregistering) DiscoveryTransition(next, DiscoveryAction.NONE)
    else DiscoveryTransition(next.copy(unregistering = true), DiscoveryAction.UNREGISTER)
  }

  fun requestStop(): DiscoveryTransition =
    when {
      !ownsListener -> DiscoveryTransition(copy(pendingRegistration = false), DiscoveryAction.NONE)
      unregistering -> DiscoveryTransition(copy(pendingRegistration = false), DiscoveryAction.NONE)
      else -> DiscoveryTransition(copy(unregistering = true, pendingRegistration = false), DiscoveryAction.UNREGISTER)
    }

  fun serviceUnregistered(): DiscoveryTransition {
    check(ownsListener) { "received mDNS unregistration callback without a listener" }
    val shouldRegister = pendingRegistration
    return DiscoveryTransition(
      // The old listener is retired before the replacement starts, but this transition reserves
      // ownership for that fresh listener so a synchronous registration failure is attributable to
      // the correct lifecycle.
      state = if (shouldRegister) DiscoveryLifecycle(ownsListener = true) else DiscoveryLifecycle(),
      action = if (shouldRegister) DiscoveryAction.REGISTER else DiscoveryAction.NONE,
    )
  }

  fun unregistrationFailed(): DiscoveryLifecycle = copy(unregistering = false)

  fun registrationFailed(): DiscoveryLifecycle = DiscoveryLifecycle()
}

internal data class DiscoveryTransition(
  val state: DiscoveryLifecycle,
  val action: DiscoveryAction,
)

internal enum class DiscoveryAction {
  NONE,
  REGISTER,
  UNREGISTER,
}

/**
 * Which model id the mDNS TXT advertises: what the engine is SERVING, falling back to what the
 * operator has CONFIGURED.
 *
 * Reality before intent, and the order is load-bearing. A discovery record answers "what will this
 * node serve me", so sourcing it from configuration alone publishes a lie for the whole duration of
 * a swap — and worse, makes the published value depend on WHEN the re-publish runs relative to the
 * caller's persist, which are on different threads. Reading [resident] first removes that ordering
 * question entirely: whenever the TXT is rebuilt, it names the engine's actual model.
 *
 * [resident] is null only before any successful init — at boot [RelaisNodeService] initializes the
 * engine before it registers, so the fallback is for a node whose init never ran or failed, where
 * the configured id is the only answer available and the honest one.
 *
 * Pure; no Context, no Android — unit-tested alongside the TXT map it feeds.
 */
internal fun advertisedModelId(resident: String?, configured: String): String = resident ?: configured

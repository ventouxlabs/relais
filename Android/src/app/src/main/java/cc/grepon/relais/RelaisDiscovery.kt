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
  private val lock = Any()

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
      if (listener != null) return
      val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
      val info = buildServiceInfo(context, httpPort, httpsPort)
      val l =
        object : NsdManager.RegistrationListener {
          override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "mDNS registered: ${info.serviceName}.$RELAIS_SERVICE_TYPE port ${info.port}")
          }

          override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "mDNS registration failed: $errorCode")
          }

          override fun onServiceUnregistered(info: NsdServiceInfo) {
            Log.i(TAG, "mDNS unregistered")
          }

          override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "mDNS unregistration failed: $errorCode")
          }
        }
      manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
      nsdManager = manager
      listener = l
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
   * The unregister and the re-register are separate asynchronous NSD callbacks, so the two can
   * interleave; both outcomes are logged, which is what makes a spurious call observable in logcat.
   */
  fun updateModel(context: Context, httpPort: Int = 8080, httpsPort: Int = 8443) {
    synchronized(lock) {
      val manager = nsdManager
      val current = listener
      if (manager == null || current == null) return // not registered; nothing to refresh
      runCatching { manager.unregisterService(current) }
      listener = null
      nsdManager = null
    }
    register(context, httpPort, httpsPort)
  }

  fun unregister() {
    synchronized(lock) {
      listener?.let { runCatching { nsdManager?.unregisterService(it) } }
      listener = null
      nsdManager = null
    }
  }
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

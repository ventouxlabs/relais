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

import cc.grepon.relais.RelaisClientConfig.Capabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the mDNS TXT attribute map that [RelaisDiscovery] advertises.
 *
 * `NsdServiceInfo` is an Android type that can't be instantiated in a plain JVM unit test, so we
 * test the pure source of the TXT attributes — [RelaisClientConfig.buildDiscoveryTxt] — which is the
 * exact map [RelaisDiscovery.register]/`updateModel` iterate into `setAttribute(...)`. This pins the
 * secret-leakage invariant and the worst-case length cap at the boundary that actually produces the
 * broadcast values. Note that `buildDiscoveryTxt` takes the model id as a PARAMETER, so these tests
 * are unaffected by where its caller sources that id — which [advertisedModelId] below now decides.
 */
class RelaisDiscoveryTxtTest {

  private val sentinelKey = "SENTINEL_SECRET_abc123def456_DO_NOT_LEAK"

  // ---------------------------------------------------------------------------
  // NSD lifecycle: listener ownership is callback-driven (feature-09 bug 6)
  // ---------------------------------------------------------------------------

  @Test
  fun `refresh waits for service-unregistered before registering a replacement`() {
    val refresh = DiscoveryLifecycle(ownsListener = true).requestRefresh()

    assertEquals(DiscoveryAction.UNREGISTER, refresh.action)
    assertTrue(refresh.state.ownsListener)
    assertTrue(refresh.state.unregistering)
    assertTrue(refresh.state.pendingRegistration)

    val replacement = refresh.state.serviceUnregistered()
    assertEquals(DiscoveryAction.REGISTER, replacement.action)
    assertTrue(replacement.state.ownsListener)
    assertFalse(replacement.state.pendingRegistration)
  }

  @Test
  fun `stop cancels a queued refresh so unregistration cannot revive discovery`() {
    val refreshing = DiscoveryLifecycle(ownsListener = true).requestRefresh().state
    val stopped = refreshing.requestStop()

    assertEquals(DiscoveryAction.NONE, stopped.action)
    assertTrue(stopped.state.ownsListener)
    assertTrue(stopped.state.unregistering)
    assertFalse(stopped.state.pendingRegistration)

    assertEquals(DiscoveryAction.NONE, stopped.state.serviceUnregistered().action)
  }

  @Test
  fun `explicit register while stopping queues replacement after the old listener retires`() {
    val stopping = DiscoveryLifecycle(ownsListener = true).requestStop().state
    val restarting = stopping.requestRegistration()

    assertEquals(DiscoveryAction.NONE, restarting.action)
    assertTrue(restarting.state.pendingRegistration)
    assertEquals(DiscoveryAction.REGISTER, restarting.state.serviceUnregistered().action)
  }

  @Test
  fun `unregistration failure keeps ownership and queued refresh for a later retry`() {
    val refreshing = DiscoveryLifecycle(ownsListener = true).requestRefresh().state
    val failed = refreshing.unregistrationFailed()

    assertTrue(failed.ownsListener)
    assertFalse(failed.unregistering)
    assertTrue(failed.pendingRegistration)
    assertEquals(DiscoveryAction.UNREGISTER, failed.requestRefresh().action)
  }

  @Test
  fun `registration failure releases only the failed registration ownership`() {
    val registering = DiscoveryLifecycle().requestRegistration().state
    val failed = registering.registrationFailed()

    assertFalse(failed.ownsListener)
    assertFalse(failed.unregistering)
    assertFalse(failed.pendingRegistration)
    assertEquals(DiscoveryAction.REGISTER, failed.requestRegistration().action)
  }

  // ---------------------------------------------------------------------------
  // Which id gets advertised: reality before intent (feature-09 bug 6)
  // ---------------------------------------------------------------------------

  @Test
  fun `advertised id is the RESIDENT model, not the configured one`() {
    // A discovery record answers "what will this node serve me". Sourcing it from configuration
    // alone advertises a model the engine is not running for the whole duration of a swap — and
    // makes the published value depend on whether the caller's persist won a race against the swap
    // thread, which is the defect this ordering removes rather than mitigates.
    assertEquals("resident-model", advertisedModelId(resident = "resident-model", configured = "configured-model"))
  }

  @Test
  fun `advertised id falls back to configured only before any successful init`() {
    // At boot RelaisNodeService initialises the engine BEFORE it registers, so this fallback is for
    // a node whose init never ran or failed — where the configured id is the only answer available.
    assertEquals("configured-model", advertisedModelId(resident = null, configured = "configured-model"))
  }

  @Test
  fun `advertised id is stable when config and engine agree`() {
    assertEquals("same", advertisedModelId(resident = "same", configured = "same"))
  }

  @Test
  fun `txt attribute keys are exactly the advertised routing set`() {
    val txt = RelaisClientConfig.buildDiscoveryTxt(
      modelId = "litert-community/gemma-4-E4B-it",
      version = "1.0.15",
      httpsPort = 8443,
      caps = Capabilities(multimodal = true, tools = true, reasoning = true),
    )
    assertEquals(
      setOf("model", "version", "https", "api", "path", "auth", "caps"),
      txt.keys,
    )
  }

  @Test
  fun `no txt value leaks the api key`() {
    // buildDiscoveryTxt has no apiKey parameter — there is no path for the key to enter. Scan all
    // values anyway so a future signature change that threads a secret in is caught immediately.
    val txt = RelaisClientConfig.buildDiscoveryTxt(
      modelId = "litert-community/gemma-4-E4B-it",
      version = "1.0.15",
      httpsPort = 8443,
      caps = Capabilities(multimodal = false, tools = true, reasoning = true),
    )
    txt.values.forEach { v ->
      assertFalse("no TXT value may equal the api key", v == sentinelKey)
      assertFalse("no TXT value may contain the api key", v.contains(sentinelKey))
    }
  }

  @Test
  fun `caps format matches the enabled capabilities`() {
    val textOnly = RelaisClientConfig.buildDiscoveryTxt(
      "m", "v", 8443, Capabilities(multimodal = false, tools = true, reasoning = true),
    )
    assertEquals("tools,reasoning", textOnly["caps"])

    val mm = RelaisClientConfig.buildDiscoveryTxt(
      "m", "v", 8443, Capabilities(multimodal = true, tools = true, reasoning = true),
    )
    assertEquals("multimodal,tools,reasoning", mm["caps"])
  }

  @Test
  fun `worst-case long model id is capped so the txt record stays bounded`() {
    val longId = "litert-community/" + "z".repeat(500)
    val txt = RelaisClientConfig.buildDiscoveryTxt(
      longId, "1.0.15", 8443, Capabilities(multimodal = true, tools = true, reasoning = true),
    )
    assertTrue(
      "every TXT value must be within MAX_TXT_VALUE_BYTES",
      txt.values.all { it.toByteArray(Charsets.UTF_8).size <= RelaisClientConfig.MAX_TXT_VALUE_BYTES },
    )
    assertTrue(
      "the whole TXT record must stay well under the 255-byte DNS-SD ceiling",
      txt.entries.sumOf { it.key.toByteArray(Charsets.UTF_8).size + it.value.toByteArray(Charsets.UTF_8).size } < 255,
    )
  }
}

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the three pure functions feature-09 extracts from [RelaisHttpServer] for
 * the dashboard's model switch.
 *
 * They live here rather than in [RelaisDashboardTest] because their subject is
 * `RelaisHttpServer.kt`'s top-level declarations, not `RelaisDashboard.kt`'s renderer. All three are
 * top-level precisely so this file can reach them — an `internal` MEMBER would need an instance,
 * which needs a Context, and none of this would compile.
 *
 * Tests:
 *  1. dashboardSecurityHeaders — Referrer-Policy and form-action, the two load-bearing values
 *  2. availableModelIdsFor — union, compat filter, ordering
 *  3. pendingModelIdFor — reality-vs-intent, and the cold-node case
 *
 * What these do NOT cover, stated so nobody reads them as covering it: that `handleDashboard`
 * actually CALLS dashboardSecurityHeaders(). Every assertion below passes unchanged if the handler
 * stops calling it and inlines something else. That half is `DashboardHeadersProbe` (androidTest).
 */
class RelaisHttpDashboardTest {

  // ---------------------------------------------------------------------------
  // 1. Dashboard security headers (the only assertions on these values anywhere)
  // ---------------------------------------------------------------------------

  @Test
  fun `dashboard sends Referrer-Policy same-origin, not no-referrer`() {
    val headers = dashboardSecurityHeaders()
    assertTrue(
      "Referrer-Policy MUST be same-origin: the cross-site guard's fallback for user agents that " +
        "omit Sec-Fetch-Site needs this page's own form POST to carry a real Origin, and " +
        "no-referrer makes the browser send Origin: null — which fails closed and 403s the " +
        "node's own SET MODEL button on those clients. Do not revert this to no-referrer.",
      headers.contains("Referrer-Policy: same-origin"),
    )
    assertFalse(
      "no Referrer-Policy header may still say no-referrer",
      headers.any { it.startsWith("Referrer-Policy") && it.contains("no-referrer") },
    )
  }

  @Test
  fun `dashboard CSP carries form-action self`() {
    val csp = dashboardSecurityHeaders().single { it.startsWith("Content-Security-Policy") }
    assertTrue(
      "form-action 'self' bounds where the model-switch form may submit. It does NOT fall back to " +
        "default-src, so an absent directive leaves submissions unrestricted.",
      csp.contains("form-action 'self'"),
    )
    assertFalse(
      "'none' would block the node's own form — /experiments uses 'none' because it has no form",
      csp.contains("form-action 'none'"),
    )
  }

  @Test
  fun `dashboard keeps its scriptless posture`() {
    val csp = dashboardSecurityHeaders().single { it.startsWith("Content-Security-Policy") }
    assertTrue("default-src must stay 'none'", csp.contains("default-src 'none'"))
    assertFalse("the page is scriptless; no script-src may appear", csp.contains("script-src"))
  }

  // ---------------------------------------------------------------------------
  // 2. availableModelIdsFor — the dropdown's contents
  // ---------------------------------------------------------------------------

  private fun provisioned(vararg ids: String) = ids.map { ProvisionedModel(it, "/data/$it.task", it) }

  @Test
  fun `configured id survives the union when the registry has not recorded it`() {
    // The pre-recording window: the operator picked b, the registry only knows a. Without the union
    // the currently-selected model is missing from its own dropdown and they can only switch away.
    val ids = availableModelIdsFor(provisioned("a"), configured = "b") { null }
    assertEquals(listOf("a", "b"), ids)
  }

  @Test
  fun `known-incompatible ids are filtered out`() {
    // The targeted swap path skips resolveModel and therefore every compat check, so an unfiltered
    // dropdown can offer a model that takes the node down on first inference.
    val ids =
      availableModelIdsFor(provisioned("a", "bad"), configured = "a") {
        if (it == "bad") "segfaults on this SoC" else null
      }
    assertEquals(listOf("a"), ids)
  }

  @Test
  fun `the configured id is filtered too when it is known-incompatible`() {
    // The union must not smuggle a known-bad id past the filter by way of the configured slot.
    val ids = availableModelIdsFor(provisioned("a"), configured = "bad") {
      if (it == "bad") "segfaults on this SoC" else null
    }
    assertEquals(listOf("a"), ids)
  }

  @Test
  fun `ids are sorted and de-duplicated`() {
    // provisionedIds returns a Set, whose iteration order is filesystem enumeration order — not
    // stable enough to assert. sorted() supplies both the List and a deterministic order.
    val ids = availableModelIdsFor(provisioned("c", "a", "b"), configured = "b") { null }
    assertEquals(listOf("a", "b", "c"), ids)
  }

  @Test
  fun `an empty registry still offers the configured id`() {
    assertEquals(listOf("only"), availableModelIdsFor(emptyList(), configured = "only") { null })
  }

  // ---------------------------------------------------------------------------
  // 3. pendingModelIdFor — reality vs intent
  // ---------------------------------------------------------------------------

  @Test
  fun `pending is the configured id when the engine is serving something else`() {
    assertEquals("b", pendingModelIdFor(configured = "b", resident = "a"))
  }

  @Test
  fun `nothing is pending when config and engine agree`() {
    assertNull(pendingModelIdFor(configured = "b", resident = "b"))
  }

  @Test
  fun `nothing is pending before any successful init`() {
    // A cold node is not "behind" — there is no resident model for config to be ahead of, and a
    // hint on a node that has never loaded anything would be noise.
    assertNull(pendingModelIdFor(configured = "b", resident = null))
  }
}

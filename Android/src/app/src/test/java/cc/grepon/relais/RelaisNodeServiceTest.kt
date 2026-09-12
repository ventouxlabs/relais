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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM test for [shouldDispatchStartup] — the pure decision extracted from
 * [RelaisNodeService]'s `onCreate`/`onStartCommand` dispatch guard (delta review: a live-but-failed
 * service, e.g. a gated-repo 401 or bad model id, never re-inits because `onStartCommand` used to be
 * a bare `START_STICKY` with no dispatch logic at all — so a retry START, and the watchdog's revive
 * path, were both silent no-ops against an already-alive process).
 *
 * The actual concurrency guard in [RelaisNodeService] is an `AtomicBoolean.compareAndSet` — this
 * predicate is a readable pre-check used by both the production dispatch method and this test, not
 * the sole source of atomicity. It can't observe the CAS race itself (that needs a live Service /
 * instrumentation), so this test only covers the decision table, not thread-safety.
 */
class RelaisNodeServiceTest {

  @Test
  fun `dispatch is needed when not ready and nothing already in flight`() {
    assertTrue(shouldDispatchStartup(ready = false, dispatchInFlight = false, listenersUp = false))
  }

  @Test
  fun `dispatch is skipped when a startup is already in flight`() {
    assertFalse(shouldDispatchStartup(ready = false, dispatchInFlight = true, listenersUp = false))
  }

  @Test
  fun `dispatch is skipped once the engine is already ready`() {
    assertFalse(shouldDispatchStartup(ready = true, dispatchInFlight = false, listenersUp = true))
  }

  /**
   * The case that made a bind failure permanent: engine ready, listeners gone.
   *
   * A TLS bind failure on `:8443` leaves `isReady` true, so gating on `ready` alone meant no later
   * START retried and the node reported LIVE with no HTTPS listener — forever, even after the port
   * conflict cleared. Making the failure *visible* (round 5) did not make it *recoverable*; this is
   * what does.
   */
  @Test
  fun `dispatch is needed when the engine is ready but the listeners are gone`() {
    assertTrue(shouldDispatchStartup(ready = true, dispatchInFlight = false, listenersUp = false))
  }

  @Test
  fun `a retry is still refused while one is already in flight, listeners or not`() {
    // The in-flight guard outranks the new condition — otherwise every START during a slow retry
    // would launch another init thread.
    assertFalse(shouldDispatchStartup(ready = true, dispatchInFlight = true, listenersUp = false))
  }

  @Test
  fun `dispatch is skipped when ready even if dispatchInFlight is stale-true`() {
    assertFalse(shouldDispatchStartup(ready = true, dispatchInFlight = true, listenersUp = true))
  }
}

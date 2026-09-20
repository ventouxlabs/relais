/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */


package cc.grepon.relais

import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * feature-22 task 4(g): the watchdog's idle shield requires `listenersUp`. [RelaisWatchdogReceiver]
 * is a plain `onReceive(context, intent)` and everything it touches is Robolectric-shadowed — the
 * prefs behind `shouldRun` and the backoff step, `AlarmManager` in `schedule`, and
 * `RelaisNodeService.start` becomes a recorded `startForegroundService` — so the branch is pinned
 * here rather than left to a hardware probe.
 *
 * Both rows: `shouldRun = true`, no resident engine (`isReady = false` in a JVM test), and the last
 * close was an idle release (`idleUnloaded = true`). The only thing that differs is whether the
 * listeners are up — the fact the shield must consult, because a `:8443` bind failure tears both
 * listeners down with the engine resident, the TTL then idles it out, and an idle-unloaded node
 * nothing can reach is the ERROR `computeNodeState` reports, not "healthy idle".
 */
@RunWith(RobolectricTestRunner::class)
class RelaisWatchdogReceiverTest {

  private val ctx get() = RuntimeEnvironment.getApplication()

  private fun failStep(): Int = ctx.getSharedPreferences("relais", Context.MODE_PRIVATE).getInt("watchdog_fail_step", 0)

  @Before fun arrange() {
    assertFalse("precondition: no resident engine in a unit test", RelaisEngine.isReady)
    assertFalse("precondition: no startup in flight", RelaisLivenessState.snapshot.startupInProgress)
    RelaisConfig.setShouldRun(ctx, true)
    RelaisWatchdog.reset(ctx)
    RelaisLivenessState.publishIdleUnloaded(true)
    shadowOf(ctx).clearStartedServices()
  }

  @After fun restore() {
    RelaisLivenessState.publishListenersUp(false)
    RelaisLivenessState.publishIdleUnloaded(false)
    RelaisWatchdog.reset(ctx)
    RelaisConfig.setShouldRun(ctx, false)
  }

  @Test fun `idle-unloaded with listeners up is healthy idle — no backoff, no restart`() {
    RelaisLivenessState.publishListenersUp(true)

    RelaisWatchdogReceiver().onReceive(ctx, Intent())

    assertEquals("the shield must reset backoff, not bump it", 0, failStep())
    assertNull("no service start may be dispatched for a healthy idle node", shadowOf(ctx).nextStartedService)
  }

  @Test fun `idle-unloaded with listeners DOWN is not healthy idle — backoff bumps and the node is restarted`() {
    RelaisLivenessState.publishListenersUp(false)

    RelaisWatchdogReceiver().onReceive(ctx, Intent())

    assertEquals("an unreachable idle node must escalate one backoff step", 1, failStep())
    val started = shadowOf(ctx).nextStartedService
    assertNotNull("the tick must (re)start the node so the listeners rebind", started)
    assertEquals(RelaisNodeService::class.java.name, started!!.component?.className)
  }

  @Test fun `not idle-unloaded with listeners up is a dead engine — backoff bumps and the node is restarted`() {
    // The other conjunct: listeners alone must not shield. An engine that is gone for any reason
    // other than an idle release (crash, failed reload — the flag clears at attempt start) behind
    // live listeners is the pre-#178 "dead" case the watchdog exists for.
    RelaisLivenessState.publishIdleUnloaded(false)
    RelaisLivenessState.publishListenersUp(true)

    RelaisWatchdogReceiver().onReceive(ctx, Intent())

    assertEquals(1, failStep())
    assertNotNull(shadowOf(ctx).nextStartedService)
  }
}

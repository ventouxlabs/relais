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

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

private const val TAG = "RelaisWatchdog"
private const val PREFS = "relais"
private const val KEY_STEP = "watchdog_fail_step"
private const val BASE_INTERVAL_MS = 60_000L
private const val MAX_INTERVAL_MS = 15 * 60_000L // 15 min ceiling
private const val MAX_SHIFT = 8 // 60s << 8 == ~4h, clamped to MAX_INTERVAL_MS
private const val NOTIFY_THRESHOLD = 10 // ~1.5h of sustained failure before warning the operator
private const val NOTIF_CHANNEL = "relais_node"
private const val NOTIF_ID = 4243

/**
 * Crash/OOM recovery for the node. `START_STICKY` alone did NOT restart the foreground service
 * after an app-process crash (measured: no recovery in 250s). This self-rescheduling exact alarm
 * fires on a backoff schedule; if [RelaisConfig.shouldRun] is set it (re)starts the service — so a
 * dead process is resurrected on the next tick. Starting an already-running node is idempotent.
 *
 * Backoff (security/Gate-3 M3): a chronically-failing init (corrupt model, persistent OOM) must not
 * restart every 60s forever and drain the battery. The interval doubles per consecutive failure to
 * a 15-min ceiling, resets to 60s once the engine is ready, and after [NOTIFY_THRESHOLD] sustained
 * failures posts a sticky "needs attention" notification instead of silently hammering.
 */
object RelaisWatchdog {
  fun schedule(context: Context) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val at = SystemClock.elapsedRealtime() + intervalFor(step(context))
    val pi = pendingIntent(context)
    // Exact alarms grant a temporary FGS-start exemption when they fire — required because a
    // background receiver otherwise hits ForegroundServiceStartNotAllowed on Android 12+.
    try {
      am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    } catch (e: SecurityException) {
      am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) // fallback if not permitted
    }
  }

  fun cancel(context: Context) {
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(context))
    setStep(context, 0)
  }

  /** Healthy tick — clear the backoff so the heartbeat returns to the base interval. */
  fun reset(context: Context) = setStep(context, 0)

  /** Record one more consecutive failure and return the new step. */
  fun bumpStep(context: Context): Int {
    val s = step(context) + 1
    setStep(context, s)
    return s
  }

  fun notifyStuck(context: Context) {
    val mgr = context.getSystemService(NotificationManager::class.java) ?: return
    mgr.createNotificationChannel(
      NotificationChannel(NOTIF_CHANNEL, "Relais Node", NotificationManager.IMPORTANCE_LOW)
    )
    val n =
      Notification.Builder(context, NOTIF_CHANNEL)
        .setContentTitle("Relais node needs attention")
        .setContentText("The node has failed to come up after repeated retries. Open Relais Node to check.")
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setOngoing(false)
        .build()
    mgr.notify(NOTIF_ID, n)
  }

  private fun intervalFor(step: Int): Long =
    (BASE_INTERVAL_MS shl step.coerceIn(0, MAX_SHIFT)).coerceAtMost(MAX_INTERVAL_MS)

  private fun step(context: Context): Int =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_STEP, 0)

  private fun setStep(context: Context, value: Int) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_STEP, value).apply()
  }

  private fun pendingIntent(context: Context): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      0,
      Intent(context, RelaisWatchdogReceiver::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Receives the watchdog tick (in a fresh process if the app was killed) and revives the node. */
class RelaisWatchdogReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!RelaisConfig.shouldRun(context)) return
    // `isReady && listenersUp`, not `isReady` alone. A bind failure leaves the engine resident on
    // purpose, so engine readiness alone read as healthy for a node nothing could reach — and this
    // is the branch that made that unrecoverable rather than merely mislabelled, because returning
    // here skips the revive below. The state needing recovery was the state preventing it.
    if (RelaisEngine.isReady && RelaisListenerState.listenersUp) {
      RelaisWatchdog.reset(context) // healthy — clear backoff
      RelaisWatchdog.schedule(context) // keep the heartbeat at the base interval
      return
    }
    if (RelaisEngine.startupInProgress) {
      // Provisioning/initializing in-process (e.g. first-run model download) — coming up, not dead.
      // Keep the heartbeat at base; don't escalate backoff or post the "needs attention" alarm.
      RelaisWatchdog.reset(context)
      RelaisWatchdog.schedule(context)
      return
    }
    if (RelaisEngine.wasIdleUnloaded) {
      // Gracefully released by idle-TTL auto-unload (#178), not a crash — the process/service is
      // alive, the engine is just intentionally not resident right now. Without this check the
      // watchdog's own ~60s heartbeat would see !isReady, conclude the node is dead, escalate the
      // failure-backoff step, and force a restart via RelaisNodeService.start() below — undoing the
      // idle-unload within about one poll cycle and eventually firing a false "needs attention"
      // notification after sustained normal idling. Treat exactly like the healthy path: reset
      // backoff, keep the heartbeat at base, and let the NEXT REQUEST reload the engine lazily
      // (RelaisEngine.ensureInitialized), not the watchdog.
      RelaisWatchdog.reset(context)
      RelaisWatchdog.schedule(context)
      return
    }
    // Not ready and not starting: escalate backoff, reschedule FIRST so a failed start never stops the heartbeat,
    // then (re)start. Warn (once past threshold) instead of hammering silently forever.
    val step = RelaisWatchdog.bumpStep(context)
    RelaisWatchdog.schedule(context)
    if (step >= NOTIFY_THRESHOLD) RelaisWatchdog.notifyStuck(context)
    Log.i(TAG, "watchdog tick — node not ready (failure step $step), (re)starting")
    try {
      RelaisNodeService.start(context)
    } catch (e: Exception) {
      Log.e(TAG, "watchdog restart failed: ${e.message}")
    }
  }
}

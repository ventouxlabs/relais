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

/**
 * Idle-TTL auto-unload policy (#178): release the resident engine after a period with no request,
 * so a phone doesn't hold a multi-GB model (RAM + thermal budget) resident indefinitely once loaded.
 * Mirrors Ollama's `OLLAMA_KEEP_ALIVE` / LM Studio's JIT-TTL idea, adapted to this node's
 * single-resident-engine model: the next request reloads lazily ([RelaisEngine.ensureInitialized]
 * already does this), so a short cold-start is an acceptable trade for hours of idle drain avoided.
 *
 * `0` (or negative) TTL means "disabled — never auto-unload", matching the `0 = never` convention
 * already used elsewhere in this codebase (see [RelaisConfig] session-memory-style toggles).
 *
 * Pure JVM (no Android types, no Context, no [RelaisEngine]) so the decision is unit-testable in
 * isolation — mirrors [admit] / [decideBackpressure] in RelaisAdmission.kt. The actual concurrency
 * safety (never unloading mid-inference, never racing a fresh request against an in-progress
 * unload) is NOT expressed here — it lives in [RelaisEngine.releaseIfIdle], which re-evaluates this
 * function while holding the same lock every request dispatch acquires before touching the engine.
 * See the KDoc on [RelaisEngine.releaseIfIdle] and [RelaisEngine.generate] for that argument; it
 * can't be captured as a pure-JVM test because it depends on the real (native, AAR-provided)
 * `Engine`/`Conversation` types, which aren't fakeable in a hermetic JVM test — it needs an
 * on-device/instrumented probe instead.
 */

/** Sentinel meaning "idle-TTL auto-unload is disabled" — the engine is never released for idleness. */
const val IDLE_TTL_DISABLED_MINUTES = 0

/** Default idle window before the resident engine is released. Judgment call — see #178 discussion. */
const val IDLE_TTL_DEFAULT_MINUTES = 15

/** Floor for the configured TTL (anything below, other than the disabled sentinel, clamps up to this). */
const val IDLE_TTL_MIN_MINUTES = 1

/** Ceiling for the configured TTL — a 24h cap keeps a mis-set value from meaning "effectively never". */
const val IDLE_TTL_MAX_MINUTES = 24 * 60

/**
 * Circuit-breaker threshold for [RelaisEngine.consecutiveCloseFailures]: after this many consecutive
 * [com.google.ai.edge.litertlm.Engine.close] failures, idle-TTL stops attempting further auto-unloads
 * (leaving the engine resident) rather than risk repeating a possibly-leaking native teardown forever
 * on a fully-automatic, 60s-polled path (#178 review — a rare `close()` bug that was tolerable at
 * shutdown()'s pre-#178 call frequency — process teardown, explicit model switch — becomes a repeated
 * leak once shutdown() is on a recurring idle-triggered cadence).
 */
const val IDLE_TTL_MAX_CONSECUTIVE_CLOSE_FAILURES = 3

/**
 * Pure idle-unload decision: should the resident engine be released *right now*?
 *
 * @param ready true iff a resident engine is actually loaded ([RelaisEngine.isReady]); nothing to
 *   release if not.
 * @param inFlightDepth current in-flight request count ([RelaisMetrics.queueDepth]) — the
 *   highest-risk guard: any request queued or running (even one still being admitted, since
 *   `incInFlight()` happens before a request touches the engine) blocks the release outright.
 * @param lastActivityAtMs wall-clock ms of the last time the engine became ready OR finished serving
 *   a request ([RelaisEngine]'s activity clock).
 * @param nowMs current wall-clock ms.
 * @param ttlMs configured idle window in ms; `<= 0` means disabled (never unload).
 * @param consecutiveCloseFailures [RelaisEngine.consecutiveCloseFailures] — at or past
 *   [IDLE_TTL_MAX_CONSECUTIVE_CLOSE_FAILURES], auto-unload stops trying (circuit breaker).
 */
fun shouldUnloadIdleEngine(
  ready: Boolean,
  inFlightDepth: Int,
  lastActivityAtMs: Long,
  nowMs: Long,
  ttlMs: Long,
  consecutiveCloseFailures: Int = 0,
): Boolean {
  if (!ready) return false // nothing resident to release
  if (ttlMs <= 0L) return false // disabled (0 = never), matches the codebase's "0 = never" convention
  if (inFlightDepth > 0) return false // NEVER unload mid-inference — the highest-risk invariant (#178)
  if (consecutiveCloseFailures >= IDLE_TTL_MAX_CONSECUTIVE_CLOSE_FAILURES) return false // circuit breaker
  return nowMs - lastActivityAtMs >= ttlMs
}

/**
 * Fixed stepper ladder for the Configure screen's IDLE AFTER control (feature-22 PR-B, task 3;
 * decided by JD 2026-09-07): the stepper moves along these rungs instead of a fixed increment. A
 * fixed increment (e.g. +-5, or the triage screen's +-15) either overshoots the existing
 * [IDLE_TTL_MIN_MINUTES] (1) floor or needs an awkward number of taps to reach it — deliberately
 * non-linear so the floor is always exactly one rung away.
 *
 * The ladder tops at 60 while [IDLE_TTL_MAX_MINUTES] is `24 * 60` (1440) — 61..1440 are
 * unreachable from this stepper today (there is no production caller that writes an idle-TTL value
 * above 60 via the UI). A config value already above 60 — from a future caller, or a manual
 * override — is a one-way trip down the moment the operator taps "-": [nextRung] moves it to the
 * top rung (60) on the way down, but leaves it untouched on the way up (see [nextRung]'s KDoc for
 * why "up" must not clamp it back down to 60).
 */
internal val IDLE_TTL_LADDER = intArrayOf(1, 5, 15, 30, 60)

/**
 * Pure stepper move (feature-22 PR-B, task 3): given the current idle-TTL [current] value and a
 * fixed ascending [ladder] (no duplicates), returns the adjacent rung in the requested direction —
 * never a fixed increment.
 *
 * - `up = true`: the first rung strictly greater than [current]; if none exists (current is at or
 *   above the top rung), returns [current] unchanged. Deliberately NOT "else the top rung" — that
 *   would send a stored value above the ladder's ceiling (e.g. 90) *down* to 60 on an increment
 *   tap, the one-way trip in the direction the operator least expects.
 * - `up = false`: the last rung strictly smaller than [current]; if none exists (current is at or
 *   below the bottom rung), returns [current] unchanged — the stepper never reaches
 *   [IDLE_TTL_DISABLED_MINUTES] (0) this way; only the IDLE UNLOAD toggle may write that sentinel.
 *
 * An off-ladder starting value (e.g. 10, preserved by `RelaisConfig.sanitizeIdleTtlMinutes`'s
 * `1..1440` clamp from an older config) moves to its nearest neighbour in the requested direction —
 * 10 up -> 15, 10 down -> 5 — never skipping past an intermediate rung. An earlier wording
 * ("first/last index whose value is `>=`/`<=` current, then +-1") sent 10 up to 30, skipping 15;
 * considered and rejected 2026-09-07.
 *
 * [RelaisConfig.setIdleTtlMinutes] is still called with the resulting rung value afterward, so its
 * existing `1..1440` clamp remains a no-op safety net here, not the mechanism that keeps this
 * function in band.
 */
fun nextRung(current: Int, ladder: IntArray, up: Boolean): Int =
  if (up) ladder.filter { it > current }.minOrNull() ?: current
  else ladder.filter { it < current }.maxOrNull() ?: current

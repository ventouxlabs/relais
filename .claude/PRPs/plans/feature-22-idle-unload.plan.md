> ## ⚠ READ THIS BEFORE PLANNING ANYTHING
> **Idle model unload with wake-on-request already ships.** #178 landed the pure decision function,
> the release path, the 60 s ticker, the config layer, the watchdog interlock, and a close-failure
> circuit breaker. **This plan does not build it.** It closes **five of the six gaps** that shipped
> alongside it — the largest being that *the operator has no way to change or disable the TTL*. The
> sixth (no breaker on **reload** failure) is deliberately left open: see *Notes → Cut after review*.
> Anyone about to plan "add idle unload" is planning work that already exists; start from the gap
> table in *Problem → Solution* instead.
>
> Branch: `feat/idle-unload-gaps`.

# Plan: Idle Model Unload — Gap Closure for #178

## Summary

`RelaisEngine.releaseIfIdle` drops the resident engine after 15 minutes of inactivity and the next
request reloads it lazily inside the same lock. That core mechanism is correct and must not be
redesigned. What is missing is everything around it: no operator control (the TTL is pinned at its
default in every field deployment), no metrics, a `/health` that cannot distinguish "idle" from
"dead", an inconsistent hold-vs-503 story across endpoints, and a cold-start cost nobody has ever
measured. A sixth gap — no circuit breaker on *reload* failure, only on `close()` failure — is
**named but not closed here**: the counter drafted for it could not detect the crash-idle-crash loop
it targeted, and was cut rather than shipped as false reassurance (*Notes → Cut after review*).

## User Story

**As an** operator running Relais as an always-on appliance on a spare phone,
**I want** to see, tune, and disable the idle-unload behavior — and to tell "idle" apart from
"crashed" in my dashboard,
**So that** I can trade RAM against first-request latency deliberately instead of discovering a
15-minute unload I never chose, and so a graceful unload never pages me as an outage.

## Problem → Solution

| # | Problem (verified) | Solution |
|---|---|---|
| 1 | **The TTL is unreachable.** `RelaisConfig.setIdleTtlMinutes` (`RelaisConfig.kt:417-419`) has **zero production callers** — grep across `Android/src/app/src/main/` finds only the read at `RelaisNodeService.kt:134` and the declarations. Every node in the field runs exactly 15 min, and an operator who wants pre-#178 always-resident behavior cannot get it even though `0 = never` is implemented and tested. | Two rows on the Configure screen: a `ToggleRow` and a `Stepper` pair, using the screens' own private composables. |
| 2 | **No metrics.** No unload counter, no idle gauge, no load-duration metric. `relais_engine_ready` flips to 0 on an idle unload and is indistinguishable from a crash on a dashboard. `lastActivityAtMs` is private (`RelaisEngine.kt:230`) with no accessor. | Three metrics following the existing 3-step counter pattern, plus a narrow public `idleSeconds` accessor. |
| 3 | **`/health` conflates four states.** `handleHealth` (`RelaisHttpServer.kt:707-712`) returns only `{status, ready, thermal_state}`; `ready=false` covers provisioning, cold-starting, idle-unloaded, and dead-after-failed-init. The three flags that separate them — `startupInProgress` (`:237`), `wasIdleUnloaded` (`:249`), `lastInitFailed` (`:268`) — are all public and all unexposed. | Add one coarse `state` field sourced from `computeNodeState`, extended with a new `NodeState.IDLE`. Do **not** change what `ready` means. |
| 4 | **Hold-vs-503 is already decided, inconsistently.** Chat/generate **hold** (`ensureInitialized` runs inside `generate`'s `synchronized(lock)`, `RelaisEngine.kt:605-616`) while audio/embeddings/rerank/images/TTS **kick-and-503**. | Keep the hold for chat/generate, but bound it with a load deadline — decided *after* measurement, not before. |
| 5 | **Cold start has never been measured** and is silently absorbed into request latency (`recordLatency` spans the load). There is no instrumentation of `ensureInitialized` at all. | Instrument it first, so the task-6 decision rests on a number instead of two stale spike figures. |
| 6 | **No breaker on *reload* failure.** `IDLE_TTL_MAX_CONSECUTIVE_CLOSE_FAILURES` (`RelaisIdleTtl.kt:62`) guards `close()` only. A reload that kills the process is watchdog-restarted (~55 s) and retried on the very next idle window — an unbounded crash-idle-crash loop. | **Not addressed here — see *Notes → Cut after review*.** A counter around `ensureInitialized` was drafted and cut: it cannot detect the failure it was designed for. Gap 6 stays open and is recorded as such rather than closed with something that does not work. |

## Metadata

- **Complexity:** Large (6 shipped-code surfaces + a real Prometheus histogram + an on-device probe)
- **Source PRD:** N/A
- **PRD Phase:** N/A
- **Estimated Files:** 14 changed — 1 CREATE (`IdleUnloadProbe.kt`) + 13 UPDATE (9 under `main/`, 3 JVM tests, 1 doc), plus `triage/TriageControlActivity.kt` read-only as the `Stepper` source. (Down from 16: cutting the reload breaker removed `RelaisIdleTtl.kt` and `RelaisIdleTtlTest.kt`. `RelaisIdleTtl.kt` returns to the list only if open question 2 is answered by raising `IDLE_TTL_MIN_MINUTES`.)

## UX Design

### Before — Configure screen (idle unload is invisible)

```
┌──────────────────────────────────────┐
│ RELAIS · CONFIGURE                   │
├──────────────────────────────────────┤
│ HF TOKEN                    ••••••   │
│ ──────────────────────────────────── │
│ SHARE                           on   │
│ NFC                            off   │
│ ──────────────────────────────────── │
│ MODEL          gemma-4-e2b-it     ▸  │
└──────────────────────────────────────┘
    (the engine silently unloads after
     15 min; nothing here says so)
```

### After — two new rows in the same panel idiom

```
┌──────────────────────────────────────┐
│ RELAIS · CONFIGURE                   │
├──────────────────────────────────────┤
│ HF TOKEN                    ••••••   │
│ ──────────────────────────────────── │
│ SHARE                           on   │
│ NFC                            off   │
│ IDLE UNLOAD                     on   │  ← ToggleRow (writes 0 when off)
│ IDLE AFTER        –    15 min    +   │  ← Stepper pair, 5-min step, hidden when off
│ ──────────────────────────────────── │
│ MODEL          gemma-4-e2b-it     ▸  │
└──────────────────────────────────────┘
```

### Interaction Changes

| Touchpoint | Before | After | Notes |
|---|---|---|---|
| Configure screen | No idle-unload control | `IDLE UNLOAD` toggle + `IDLE AFTER` stepper | DESIGN.md: label-left/value-right, hardcoded UPPERCASE monospace, no Material `Switch` anywhere in Relais screens |
| Turning the toggle **off** | impossible | persists `IDLE_TTL_DISABLED_MINUTES` (0) → pre-#178 always-resident | Takes effect within one 60 s tick; `checkIdleUnload` re-reads the pref every tick (`RelaisNodeService.kt:134`) |
| Turning it back **on** | impossible | restores the last non-zero value, else `IDLE_TTL_DEFAULT_MINUTES` | Needs a second pref key to remember the prior value |
| `GET /health` while idle | `{"status":"ok","ready":false,...}` — reads as an outage | adds `"state":"IDLE"`; `ready` unchanged | `/health` is the only unauthenticated route (`RelaisHttpServer.kt:265-270`) — stays coarse, leaks nothing |
| QS tile / widget while idle | "○ off" / "○ starting…" | an IDLE presentation | `TilePresentation.kt:51-57`, `RelaisWidget.kt:117-123` are exhaustive `when`s — the compiler surfaces every site |
| `/metrics` while idle | `relais_engine_ready 0`, indistinguishable from a crash | `relais_engine_unloads_total`, `relais_engine_idle_seconds` disambiguate | |
| First request after unload | unbounded hold on an HTTP worker thread holding 1 of 8 admission permits | bounded hold; past the deadline, canonical 503 + `Retry-After` with the load continuing in background | **Gated on the measured reload time** (task 6) |
| Notification while idle | already correct | unchanged | `RelaisNodeService.kt:139` already says "Idle — engine released (reloads automatically on the next request)" |

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisIdleTtl.kt` | 30-92 | The whole pure decision surface. The KDoc at 30-39 states *why* concurrency is not testable here — read before proposing any test |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | 940-982 | `releaseIfIdle` + its lock-ordering KDoc. **Do not modify the pre-check/lock/re-check structure** |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | 605-616 | The comment block that *is* the race-freedom argument (`incInFlight` before the lock) |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | 230, 237, 249, 260, 268, 324, 332-355 | The activity clock, the four `@Volatile` state flags, `isReady`, and `ensureInitialized` (the instrumentation target) |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisNodeService.kt` | 118-141 | The 60 s ticker and `checkIdleUnload` — why no pref-change listener is needed |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisConfig.kt` | 60, 407-423 | The clamped read/write and the `0 = never` sentinel. The layer already exists — reuse, don't re-add |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisWatchdog.kt` | 130-142 | The `wasIdleUnloaded` interlock — the subtlest part of #178 and the thing a regression would silently break |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/core/NodeState.kt` | 16-42 | The enum and the deliberate precedence order in `computeNodeState`. Adding `IDLE` means changing this truth table |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt` | 36-38, 46, 159, 291-292, 323-329, 403-405, 452-453, 462, 468-484 | The 3-step counter pattern, the `line()` helper, the label-hygiene rule, and the `resetIncrementsForTest` seam that does **not** clear plain counters |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 265-270, 536-548, 700-712 | The auth gate (health is the only open route), the canonical 503 shape, and `handleHealth` |
| **P2** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisConfigureActivity.kt` | 142-148, 333-347 | The `remember { mutableStateOf(RelaisConfig.x(ctx)) }` idiom and `ToggleRow` |
| **P2** | `Android/src/app/src/main/java/cc/grepon/relais/triage/TriageControlActivity.kt` | 358-364 | `Stepper` — the only stepper composable in the repo |
| **P2** | `SPIKE-FINDINGS.md` | 20-60 | The cold-start numbers and the G5 E4B history. **Treat as an order of magnitude, not a measurement** |
| **P2** | `Android/src/app/src/test/java/cc/grepon/relais/RelaisIdleTtlTest.kt` | 19-55 | House test style and the explicit statement of what these tests do *not* cover |

## External Documentation

| Topic | Source URL | Key Takeaway |
|---|---|---|
| Prometheus counter vs gauge naming | https://prometheus.io/docs/practices/naming/ | `_total` suffix for counters; base units (seconds, bytes) for gauges — matches the existing `relais_shed_total` / `relais_queue_depth` names |
| Android foreground-service lifetime | https://developer.android.com/develop/background-work/services/fgs | A `dataSync` FGS stays alive independently of what it holds in memory — releasing the engine does **not** stop the service |
| LiteRT-LM `Engine.close()` semantics | `docs/litertlm-native-api.md` (repo-local, regenerate with `scripts/dump-litertlm-api.sh`) | Verify against the AAR before asserting any capability — this repo's feature plans have repeatedly been wrong about the native surface |

**Research item — Prometheus semantics**
- **KEY_INSIGHT:** An idle-unload event is monotonic (a counter), idle duration is a point-in-time reading (a gauge), and load duration is a distribution (a histogram). Mixing these up makes the dashboard unusable at exactly the moment it matters.
- **APPLIES_TO:** Task 2 and Task 1.
- **GOTCHA:** `RelaisMetrics.resetIncrementsForTest` (`:471-484`) clears only histograms and the thermal map — its own KDoc at `:469` says *"Does not touch the request-count map or counters used elsewhere."* A new plain counter's test **must assert a delta**, or it will pass vacuously when another test in the same JVM already incremented it.

**Research item — FGS lifetime**
- **KEY_INSIGHT:** The wake lock and the notification are held by `RelaisNodeService`, not by the engine. An idle unload frees model RAM while the node stays reachable and keeps advertising over mDNS.
- **APPLIES_TO:** The docs task, and the `/health` semantics decision.
- **GOTCHA:** Because the service stays up, mDNS keeps advertising — which is *correct* (the node will serve on the next request) and must not be "fixed."

## Patterns to Mirror

**NAMING_CONVENTION** — file-level `TAG`, `SCREAMING_SNAKE_CASE` consts with a KDoc that states the *judgment*, not just the value:

```kotlin
// SOURCE: RelaisIdleTtl.kt:42-52
/** Sentinel meaning "idle-TTL auto-unload is disabled" — the engine is never released for idleness. */
const val IDLE_TTL_DISABLED_MINUTES = 0

/** Default idle window before the resident engine is released. Judgment call — see #178 discussion. */
const val IDLE_TTL_DEFAULT_MINUTES = 15
```

```kotlin
// SOURCE: RelaisEngine.kt:43
private const val TAG = "RelaisEngine"
```

**ERROR_HANDLING** — the canonical 503 + `Retry-After` shape. Note `retry_after_seconds` is a **top-level sibling** of `error`, a deliberate deviation from OpenAI (the same top-level-vs-nested inconsistency is documented for `code` at `RelaisError.kt:61-70`):

```kotlin
// SOURCE: RelaisHttpServer.kt:536-548
/** If the device is thermally shedding, answer 503 + Retry-After and return true. */
private fun shedIfHot(reply: (Int, JSONObject, List<String>) -> Unit): Boolean {
  if (!ThermalGovernor.shouldShed()) return false
  RelaisMetrics.recordShed()
  val retry = ThermalGovernor.retryAfterSeconds() + (0..4).random() // jitter avoids retry stampede
  reply(
    503,
    RelaisError.json("thermal backpressure; retry later", RelaisError.SERVICE_UNAVAILABLE)
      .put("retry_after_seconds", retry),
    listOf("Retry-After: $retry"),
  )
  return true
}
```

```kotlin
// SOURCE: RelaisError.kt:57-59
/** `{"error":{"message":[message],"type":[type]}}` — the OpenAI-compatible error envelope. */
fun json(message: String, type: String): JSONObject =
  JSONObject().put("error", JSONObject().put("message", message).put("type", type))
```

**LOGGING_PATTERN** — `Log.i` for a state transition with the deciding numbers inlined; `Log.w` + `runCatching{}.onFailure` for a tolerated failure. Never log a path, key, or IP.

```kotlin
// SOURCE: RelaisEngine.kt:977
Log.i(TAG, "idle TTL exceeded (${nowMs - lastActivityAtMs}ms >= ${ttlMs}ms) — releasing resident engine")
```

```kotlin
// SOURCE: RelaisNodeService.kt:125-130
executor.scheduleWithFixedDelay(
  { runCatching { checkIdleUnload() }.onFailure { Log.w(TAG, "idle-TTL tick failed", it) } },
  IDLE_TTL_POLL_INTERVAL_MS,
  IDLE_TTL_POLL_INTERVAL_MS,
  TimeUnit.MILLISECONDS,
)
```

**HANDLER_PATTERN** — routes are a single `when` block over `method`/`path` dispatching into `private fun handleX(ctx: RequestContext)`. Health is special-cased *out* of the auth gate:

```kotlin
// SOURCE: RelaisHttpServer.kt:265-270
// Health is open; everything else needs the API key + is rate-limited per client IP.
if (!(method == "GET" && path.startsWith("/health"))) {
  if (!authorized(authorization)) {
    reply(401, RelaisError.json("unauthorized", RelaisError.AUTHENTICATION))
    return
  }
```

```kotlin
// SOURCE: RelaisHttpServer.kt:707-712
private fun handleHealth(ctx: RequestContext) {
  ctx.send(
    200,
    JSONObject().put("status", "ok").put("ready", RelaisEngine.isReady).put("thermal_state", ThermalGovernor.statusValue),
  )
}
```

**METRICS_PATTERN** — three edits per counter (declare / record / render twice):

```kotlin
// SOURCE: RelaisMetrics.kt:46
private val shedTotal = AtomicLong(0)

// SOURCE: RelaisMetrics.kt:159
fun recordShed() = shedTotal.incrementAndGet()

// SOURCE: RelaisMetrics.kt:323-325  (Prometheus render; `line` is the local helper at :291-292)
line("# HELP relais_shed_total Requests shed (503) under thermal backpressure.")
line("# TYPE relais_shed_total counter")
line("relais_shed_total ${shedTotal.get()}")

// SOURCE: RelaisMetrics.kt:452  (JSON render)
.put("shed_total", shedTotal.get())
```

Gauge form, for `relais_engine_idle_seconds`:

```kotlin
// SOURCE: RelaisMetrics.kt:403-405
line("# HELP relais_queue_depth In-flight + queued inference requests.")
line("# TYPE relais_queue_depth gauge")
line("relais_queue_depth ${inFlight.get()}")
```

**UI_PATTERN** — the two composables to mirror, verbatim in style (monospace, `Muted` label / `Paper` value, 6dp clip, whole-row `clickable`, no `Switch`, no `strings.xml`):

```kotlin
// SOURCE: RelaisConfigureActivity.kt:333-347
/**
 * A single-row boolean toggle (P9): label left, `on`/`off` value right, the whole row tappable —
 * replaces the old paired readout-row + command-link idiom.
 */
@Composable
private fun ToggleRow(label: String, value: Boolean, onToggle: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onToggle() },
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Text(if (value) "on" else "off", color = Paper, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
  }
}
```

```kotlin
// SOURCE: triage/TriageControlActivity.kt:358-364
@Composable
private fun Stepper(symbol: String, onClick: () -> Unit) {
  Box(
    Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 2.dp)
  ) {
    Text(symbol, color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
  }
}
```

State idiom (read once into `remember`, write-then-assign in the lambda; **no** pref-change listener exists anywhere in `main/`):

```kotlin
// SOURCE: RelaisConfigureActivity.kt:145-147
var shareEnabled by remember { mutableStateOf(RelaisConfig.shareEnabled(ctx)) }
val nfcAvailable = remember { NfcAdapter.getDefaultAdapter(ctx) != null }
var nfcEnabled by remember { mutableStateOf(RelaisConfig.nfcEnabled(ctx)) }
```

**CONFIG_PATTERN** — clamped on both read and write, sentinel preserved:

```kotlin
// SOURCE: RelaisConfig.kt:414-423
fun idleTtlMinutes(context: Context): Int =
  sanitizeIdleTtlMinutes(prefs(context).getInt(KEY_IDLE_TTL_MINUTES, IDLE_TTL_DEFAULT_MINUTES))

fun setIdleTtlMinutes(context: Context, value: Int) {
  prefs(context).edit().putInt(KEY_IDLE_TTL_MINUTES, sanitizeIdleTtlMinutes(value)).apply()
}

private fun sanitizeIdleTtlMinutes(v: Int): Int =
  if (v <= IDLE_TTL_DISABLED_MINUTES) IDLE_TTL_DISABLED_MINUTES
  else v.coerceIn(IDLE_TTL_MIN_MINUTES, IDLE_TTL_MAX_MINUTES)
```

**PURE_LOGIC_PATTERN** — the shape any new pure guard in this area must take. Guards are ordered cheapest-first, each with a one-line *why*. (No task in this plan adds a parameter to this function any more — the reload breaker was cut — but this is the house form if one ever does.)

```kotlin
// SOURCE: RelaisIdleTtl.kt:79-92
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
```

**TEST_STRUCTURE** — JUnit 4, no `@RunWith` for pure logic, static-imported `org.junit.Assert.*` members, backtick names, numbered section-comment banners, and a class KDoc that states what the tests *cannot* cover:

```kotlin
// SOURCE: Android/src/app/src/test/java/cc/grepon/relais/RelaisIdleTtlTest.kt:19-55
package cc.grepon.relais

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic unit tests for the pure idle-unload decision in [RelaisIdleTtl.kt] (#178). No Context,
 * no Android types, no [RelaisEngine] — pure JVM, mirrors [RelaisAdmissionTest].
 *
 * The real concurrency safety (the highest-risk part of #178 — never racing a fresh request against
 * an in-progress unload) is NOT covered here: it lives in [RelaisEngine.releaseIfIdle]'s
 * lock-ordering against [RelaisEngine.generate] ...
 */
class RelaisIdleTtlTest {

  private val ttlMs = 15L * 60_000L // 15 minutes, matches IDLE_TTL_DEFAULT_MINUTES

  // -------------------------------------------------------------------------
  // 1. not ready -> never unload (nothing resident to release)
  // -------------------------------------------------------------------------

  @Test
  fun `never unloads when engine is not ready`() {
    val now = 1_000_000L
    assertFalse(
      shouldUnloadIdleEngine(
        ready = false,
        inFlightDepth = 0,
        lastActivityAtMs = now - ttlMs - 1,
        nowMs = now,
        ttlMs = ttlMs,
      )
    )
  }
```

**PROBE_STRUCTURE** — the class KDoc *is* the runbook: question → decision it drives → the exact `adb` line → the logcat tag to watch.

```kotlin
// SOURCE: Android/src/app/src/androidTest/java/cc/grepon/relais/ToolCallingProbe.kt:35-58
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for feature-04: does the bundled LiteRT-LM expose a WORKING native tool-calling
 * path, and does the resident model actually emit a structured tool call through it?
 *
 * Decides the engine path: native ToolManager (clean, library-parsed ToolCall objects) vs the
 * prompt-injection + output-scraping fallback the original plan assumed was the only option.
 *
 * Run (rango / Pixel 10 / G5, E2B staged):
 *   adb -s <serial> shell am instrument -w \
 *     -e class cc.grepon.relais.ToolCallingProbe \
 *     -e model /storage/emulated/0/Android/data/com.ventouxlabs.relais.izzy/files/.../gemma-4-E2B-it.litertlm \
 *     com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Watch: adb logcat -s RelaisToolProbe
 */
@RunWith(AndroidJUnit4::class)
class ToolCallingProbe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
```

## Files to Change

| File | CREATE/UPDATE | Justification |
|---|---|---|
| `.../RelaisEngine.kt` | UPDATE | `recordEngineUnload()` call site in `releaseIfIdle` (:978-980); public `idleSeconds` accessor for the private `lastActivityAtMs` (:230); load-duration instrumentation around `ensureInitialized` (:332-355); **and move `wasIdleUnloaded = false` from `:353` to the start of the init attempt** (task 4b) |
| `.../RelaisMetrics.kt` | UPDATE | Two counters/gauges × the 3-step pattern **plus one real histogram** (bounds array, lock, `_bucket`/`_sum`/`_count` render, **and** a `resetIncrementsForTest` entry) |
| `.../RelaisConfig.kt` | UPDATE | **One** new key: the remembered last non-zero TTL |
| `.../RelaisConfigureActivity.kt` | UPDATE | `IDLE UNLOAD` `ToggleRow` + `IDLE AFTER` `Stepper` pair |
| `.../triage/TriageControlActivity.kt` | — (read only) | Source of the `Stepper` pattern; **do not modify** |
| `.../core/NodeState.kt` | UPDATE | Add `NodeState.IDLE` and its precedence rule in `computeNodeState` |
| `.../core/RelaisNodeController.kt` | UPDATE | **The only production caller** of `computeNodeState` (`:32`, positional args) — adding a parameter breaks it. Grep-verified: `TilePresentation.kt` and `RelaisWidget.kt` *consume* the enum but do not call the function |
| `.../RelaisHttpServer.kt` | UPDATE | `handleHealth` gains `"state"`; optionally the bounded-hold 503 (task 6), and the `/v1/audio/transcriptions` decision at `:641-648` |
| `.../tile/TilePresentation.kt` | UPDATE | Exhaustive `when` at :51-57 (`tilePresentation`) **and** :77-81 (`tileAction`) must handle `IDLE`; `tileAction` needs an explicit `IDLE -> START` decision against its no-cold-start KDoc |
| `.../widget/RelaisWidget.kt` | UPDATE | Exhaustive `when` at :117-123 (`StatusLine`) must handle `IDLE` — **and `:95` `canRun` is an `==`, not a `when`**, so the compiler will *not* flag it; it must be edited by hand or the RUN button dies on an idle node |
| `.../test/java/cc/grepon/relais/RelaisIdleTtlConfigTest.kt` | UPDATE | Toggle round-trip + stepper-floor tests (already Robolectric) |
| `.../test/java/cc/grepon/relais/RelaisMetricsIncrementsTest.kt` | UPDATE | Delta-asserting counter tests + the histogram render/reset tests |
| `.../test/java/cc/grepon/relais/NodeStateTest.kt` | UPDATE | Truth-table rows for `IDLE` and for `ERROR`-beats-`IDLE`. **Its helper calls `computeNodeState` positionally (`:22-29`)** while `RelaisNodeController.kt:31-38` uses named args — append the new parameter **last** or it silently mis-binds here |
| `Android/src/app/src/androidTest/java/cc/grepon/relais/IdleUnloadProbe.kt` | **CREATE** | The only place the reload time and cross-cycle leak behavior can be measured |
| `docs/RUNBOOK.md` | UPDATE | An idle-unload row; correct "reclaims memory" to **primary engine only** |

## NOT Building

- **A redesign of `releaseIfIdle`'s locking.** The pre-check / lock / re-check structure and the `incInFlight`-before-lock ordering are load-bearing and already argued in KDoc. Touch nothing there beyond the metric call.
- **A pref-change listener.** No `OnSharedPreferenceChangeListener` exists in `main/`, and `checkIdleUnload` re-reads the pref every 60 s tick (`RelaisNodeService.kt:134`). Adding one is pure cost.
- **Idle unload for secondary models.** `EmbeddingGemmaEmbedder.warmIfProvisioned` (`RelaisNodeService.kt:178`) loads a *second* model at startup; grepping `embed/`, `imagegen/`, `tts/` finds only `TtsPlayer.release()` — there is no unload path at all. So "idle unload reclaims memory" is **partial** today. Name it in the docs; do not build it here.
- **Fixing the stale mDNS TXT after a model swap.** `RelaisDiscovery.updateModel` (`RelaisDiscovery.kt:110`) has zero callers, so #180's swap leaves `model=`/`caps=` stale. **That is #180's bug.** An idle unload does not worsen it — the advertisement is registered once and torn down only in `onDestroy`, so it correctly keeps advertising a node that will serve on the next request. File against #180.
- **Changing the kick-and-503 *secondary-model* paths** (`RelaisHttpServer.kt:367-371` TTS, `:1001-1002` embeddings, `:1038-1039` rerank, `:1083-1084` images). Those are genuinely separate engines and are already consistent with each other.
- ⚠ **`/v1/audio/transcriptions` is NOT one of them — it is the primary engine, on the idle-unload path.** `RelaisHttpServer.kt:641-648` guards on `if (!RelaisEngine.isReady)`, calls `RelaisEngine.ensureInitializedInBackground(context)` and 503s with `Retry-After: 10`, and its own comment names the cause: *"e.g. the engine was idle-TTL-unloaded, #178"*. An earlier draft of this plan mis-filed it as a secondary engine. **Task 6 must decide it explicitly**: if chat/generate gain a bounded hold, audio becomes the one primary-engine route that behaves differently for the same cause. Either include it in task 6 or write down why 503 stays right there — do not leave it unstated.
- **Changing the meaning of `ready` in `/health`.** Existing clients and the RUNBOOK depend on it meaning "can serve right now". Idle-unloaded genuinely is not ready.
- **A fourth health derivation.** There are already three (`computeNodeState`, `handleHealth`, `assembleDashboardStatus`).

## Step-by-Step Tasks

### Task 1 — Instrument `ensureInitialized` duration

- **ACTION:** Wrap the real-init branch of `RelaisEngine.ensureInitialized` in a duration measurement and feed a new `relais_engine_load_duration_seconds` histogram.
- **IMPLEMENT:** Inside `synchronized(lock)` after the `if (isReady) return` re-check (`RelaisEngine.kt:338-339`), capture `val startNs = System.nanoTime()`; at the end of the successful branch call `RelaisMetrics.recordEngineLoad((System.nanoTime() - startNs) / 1e9)`. Measure **only** the branch that actually builds an engine — the early `if (isReady) return` at `:337` must record nothing, or the histogram fills with zeros. **This is a real histogram, not a one-liner.** Budget the same four pieces `relais_completion_tokens` needs: (a) its own bounds array + counts `LongArray`, (b) its own lock object, (c) a cumulative `_bucket` render loop plus `_sum` and `_count` lines — mirror `RelaisMetrics.kt:367-379` verbatim in shape, and (d) **an entry in `resetIncrementsForTest` (`:471-484`)**, or the histogram leaks across every test in the process-global object.
- **MIRROR:** METRICS_PATTERN; and specifically the `relais_completion_tokens` histogram at `RelaisMetrics.kt:367-379` (render) + `:479-483` (reset) — that is the shape, not `recordLatency`'s per-endpoint map.
- **IMPORTS:** none new in `RelaisEngine.kt` (`RelaisMetrics` is already referenced at `:970`).
- **GOTCHA:** Cold start is currently **absorbed into request latency** — `recordLatency` spans from `reqStartNs` through the lock, so the existing latency histogram already double-counts a reload. Note this in the metric's HELP text; do not "fix" `recordLatency` in this task. **And do not validate with a name grep:** a `grep` for `relais_engine_load_duration_seconds` passes on a declaration that renders nothing. Assert on rendered `_bucket`/`_sum`/`_count` lines with a real observation fed in.
- **NOT JVM-TESTABLE:** the "recorded only for a real init" property **cannot** be a JVM test — `ensureInitialized` runs `require(File(modelPath).exists())` (`:340`) and then `buildResidentEngine` (`:349`), which needs the native AAR. That assertion belongs in `IdleUnloadProbe.kt` (task 5), and the acceptance criteria say so.
- **VALIDATE:** `./gradlew testFullOpenDebugUnitTest` from `Android/src`; a JVM test feeds `recordEngineLoad(1.5)` directly and asserts the rendered `_bucket`/`_sum`/`_count` lines move, then that `resetIncrementsForTest()` zeroes them.

### Task 2 — Unload counter + idle gauge

- **ACTION:** Add `relais_engine_unloads_total` (counter) and `relais_engine_idle_seconds` (gauge).
- **IMPLEMENT:** Declare `private val engineUnloadsTotal = AtomicLong(0)` beside `shedTotal` (`RelaisMetrics.kt:46`); `fun recordEngineUnload() = engineUnloadsTotal.incrementAndGet()` beside `recordShed()` (`:159`); Prometheus block after `relais_queue_rejected_total` (`:329`); JSON `.put("engine_unloads_total", …)` after `:453`. Call `RelaisMetrics.recordEngineUnload()` in `releaseIfIdle` immediately after `shutdown()` and before `wasIdleUnloaded = true` (`RelaisEngine.kt:978-979`) — inside the lock, so it counts actual releases, not attempts. For the gauge, add to `RelaisEngine` a narrow read-only accessor rather than widening the field: `val idleSeconds: Double get() = (System.currentTimeMillis() - lastActivityAtMs) / 1000.0`, and render it with the queue gauges (`RelaisMetrics.kt:403-405`).
- **MIRROR:** METRICS_PATTERN (counter + gauge forms).
- **IMPORTS:** `java.util.concurrent.atomic.AtomicLong` is already imported in `RelaisMetrics.kt`.
- **GOTCHA:** `resetIncrementsForTest` (`:471-484`) does **not** clear plain counters — its KDoc says so at `:469`. The test must assert a **delta**, not an absolute. Also: label hygiene (`RelaisMetrics.kt:36-38`) — these are plain numbers, no path/id/key, so no new leak surface.
- **VALIDATE:** JVM tests assert both renders move by exactly 1 per `recordEngineUnload()`.

### Task 3 — Configure-screen controls (the headline gap)

- **ACTION:** Add `IDLE UNLOAD` and `IDLE AFTER` rows to `RelaisConfigureActivity`.
- **IMPLEMENT:** Add `KEY_IDLE_TTL_LAST_NONZERO_MINUTES` to `RelaisConfig` plus `idleTtlLastNonZeroMinutes(ctx)` / `setIdleTtlLastNonZeroMinutes(ctx, v)`, mirroring `:414-419`. In the composable, `var idleTtl by remember { mutableStateOf(RelaisConfig.idleTtlMinutes(ctx)) }`. `ToggleRow("IDLE UNLOAD", idleTtl > 0) { … }` writes `IDLE_TTL_DISABLED_MINUTES` when turning off (after saving the current value as last-non-zero), and restores the remembered value — falling back to `IDLE_TTL_DEFAULT_MINUTES` — when turning on. Render the `IDLE AFTER` row only when `idleTtl > 0`: a label, a `Stepper("–")`, a `"$idleTtl min"` readout, a `Stepper("+")`, stepping by **5** and clamped through the existing `setIdleTtlMinutes`. **Clamp the decrement with `.coerceAtLeast(RelaisIdleTtl.IDLE_TTL_MIN_MINUTES)`** — see the GOTCHA; the toggle owns disabling, the stepper never does.
- **MIRROR:** UI_PATTERN (`ToggleRow`, `Stepper`), CONFIG_PATTERN, and the `remember` idiom at `RelaisConfigureActivity.kt:145-147`.
- **IMPORTS:** `Stepper` is `private` in `triage/TriageControlActivity.kt` — **copy the composable into `RelaisConfigureActivity.kt`** (matching how each screen keeps its own private row composables) rather than making it public or introducing a shared UI module. Existing Compose imports in the file already cover `Row`/`Box`/`Text`/`clickable`/`RoundedCornerShape`; add `FontWeight` if absent.
- **GOTCHA:** No Material `Switch` exists anywhere in the Relais screens and strings are hardcoded UPPERCASE monospace (no `strings.xml`) — DESIGN.md is explicit. Do not add either. **The 5-minute step has a trap:** decrementing from 5 lands on 0, and `sanitizeIdleTtlMinutes` (`RelaisConfig.kt:421-423`) treats `v <= IDLE_TTL_DISABLED_MINUTES` as the **disabled sentinel** — so the user reads "turned it down" and gets "turned it off", with the `ToggleRow` still showing `on`. Clamp the decrement at `IDLE_TTL_MIN_MINUTES` (`RelaisIdleTtl.kt:49` = 1). Note the ladder 5/10/15/… then never reaches that documented floor of 1, so **pick one and write it down**: either a non-linear ladder (1/5/15/30/60) that reaches 1, or a 5-minute floor with `IDLE_TTL_MIN_MINUTES` raised to match. Shipping a documented-valid value that the UI cannot produce is the thing to avoid.
- **VALIDATE:** Robolectric round-trip in `RelaisIdleTtlConfigTest`; visually confirm against DESIGN.md's label-left/value-right rule on device.

### Task 4 — `NodeState.IDLE` + `/health` `state` field

- **ACTION:** Let every health surface tell idle from dead.
- **IMPLEMENT (four parts — the precedence is the subtle one):**

  **(a) The enum + the branch, in the *correct* slot.** Add `IDLE` to `enum class NodeState` (`core/NodeState.kt:16`). Thread `wasIdleUnloaded` in as a sixth parameter — **append it last**; the only production caller `core/RelaisNodeController.kt:31-38` uses **named** arguments (so appending is safe there), but the test helper `NodeStateTest.kt:22-29` calls **positionally**, and inserting anywhere but last silently mis-binds it. The branch goes **after** `shouldRun && lastInitFailed` (`core/NodeState.kt:39`), not before `startupInProgress` (`:38`). Resulting order, verified against the shipped function:

  | # | Condition | State | Why this slot |
  |---|---|---|---|
  | 1 | `ready && thermal >= 3` | `HOT` | unchanged |
  | 2 | `ready` | `LIVE` | unchanged — a ready engine is LIVE even if `wasIdleUnloaded` is stale |
  | 3 | `startupInProgress` | `STARTING` | unchanged — an active reload is starting, not idle |
  | 4 | `shouldRun && lastInitFailed` | `ERROR` | **must stay above IDLE.** A node that unloaded and then failed every reload is broken, and IDLE reads as healthy |
  | 5 | `shouldRun && wasIdleUnloaded` | **`IDLE`** | new |
  | 6 | `shouldRun` | `STARTING` | unchanged |
  | 7 | else | `OFF` | unchanged |

  **(b) Move `wasIdleUnloaded = false` to the *start* of the init attempt.** Today it sits at `RelaisEngine.kt:353`, **after** `buildResidentEngine` (`:349`) inside `synchronized(lock)` — so it is reached **only on success**. A throw at the `require(File(modelPath).exists())` guard (`:340`) or inside `buildResidentEngine` skips it, and the flag stays true: with (a) alone, a node whose reloads keep failing would report `"state":"IDLE"` **forever** while actually broken. Move the assignment to just after the `if (isReady) return` re-check (`:338-339`). Its own comment already claims this semantics — *"a real init attempt is underway; restore normal not-ready semantics"* — so moving the line makes the comment true rather than changing intent. Rule 4 above is then belt-and-braces, not the only defense.

  **(c) The two non-`when` consumer sites.** `RelaisWidget.kt:95` is `val canRun = nodeState == NodeState.LIVE && …` — an `==`, **not** an exhaustive `when`, so adding an enum constant does **not** break the build here; it silently leaves the RUN button dead on an idle-unloaded node, which is the exact node a user most wants to poke. Decide explicitly: **`canRun` should include IDLE** (a tap warms the engine, which is the whole point of wake-on-request). `tileAction` (`TilePresentation.kt:77-81`) is a third exhaustive `when` whose KDoc guarantees *"RUN_PROMPT is returned ONLY when `ready` is true … so a tap can never cold-start the engine"* — IDLE is by definition not-ready, so **map `IDLE -> TileAction.START`**, which warms the node without violating the documented no-cold-start-from-RUN_PROMPT invariant. Do not quietly widen the RUN_PROMPT branch.

  **(d) The exhaustive `when`s that *will* fail the build**, and are therefore the easy ones: `tilePresentation` (`TilePresentation.kt:51-57`) and `StatusLine` (`RelaisWidget.kt:117-123`). In `handleHealth`, add `.put("state", <computed>.name)`.
- **MIRROR:** HANDLER_PATTERN; the deliberate precedence comment already in `computeNodeState`'s KDoc (`core/NodeState.kt:23-28`), which this task extends rather than contradicts.
- **IMPORTS:** `cc.grepon.relais.core.computeNodeState` and `NodeState` in `RelaisHttpServer.kt` if not already present.
- **GOTCHA:** `/health` is the **only unauthenticated route** (`RelaisHttpServer.kt:265-270`). Whatever goes in is public: a coarse enum name only — no model id, no path, no client detail. Separately, `assembleDashboardStatus` (defined `RelaisDashboard.kt:76`, called `RelaisHttpServer.kt:720-735`) is a **third** health derivation and is currently being reworked by the feature-09 dashboard plan — reconcile before touching it.
- **VALIDATE:** `NodeStateTest` truth-table rows for slots 2, 4 and 5 above; `curl -k https://<ip>:8443/health` shows `"state":"IDLE"` after an idle window, and `"state":"ERROR"` after an idle window whose reload fails.

### Task 5 — `IdleUnloadProbe.kt` (on-device)

- **ACTION:** Measure what no JVM test can.
- **IMPLEMENT:** New `androidTest` probe. (a) set a 1-minute TTL, serve one request, idle past it, assert `isReady` goes false and `relais_engine_unloads_total` increments; (b) **measure the reload** — issue a request and log wall-clock time to first token (the number task 6 depends on, which does not exist today); **and assert that a second `ensureInitialized` call records no second load observation** — the property task 1 cannot test on the JVM; (c) assert the watchdog does **not** restart the node during the idle window (`RelaisWatchdog.kt:130-142`); (d) repeat the unload/reload cycle ~5 times, checking for a native leak across cycles and exercising the multiplied-first-inference path.
- **MIRROR:** PROBE_STRUCTURE — class KDoc as runbook, `@RunWith(AndroidJUnit4::class)`, `assumeTrue` for a staged model, a `Relais*` logcat tag.
- **IMPORTS:** `androidx.test.ext.junit.runners.AndroidJUnit4`, `androidx.test.platform.app.InstrumentationRegistry`, `org.junit.Assume.assumeTrue`, `android.util.Log`.
- **GOTCHA:** Probes are **not** in CI and need physical hardware. A keyguard/asleep device fakes mass failures — unlock first. Run destructive cycles on the spare Pixel 10 (`rango`), not the live node.
- **VALIDATE:** `./gradlew :app:compileFullOpenDebugAndroidTestKotlin` compiles it; the `adb` line in its own header runs it.

### Task 6 — Bounded hold for chat/generate (**gated on task 5**)

- **ACTION:** Decide, from the measured reload time, whether the synchronous hold needs a deadline.
- **IMPLEMENT:** If measurement shows reload materially exceeds the ~23 s spike figure: add a load deadline in the chat/generate path. Past the deadline, return the canonical 503 + `Retry-After` reusing `shedIfHot`'s shape verbatim (`RelaisHttpServer.kt:536-548`), with the load continuing in the background via `ensureInitializedInBackground` so the retry lands warm. Otherwise, document the hold as deliberate and change nothing.
- **MIRROR:** ERROR_HANDLING; the #180 swap path already does exactly this with `Retry-After: 25` (`RelaisHttpServer.kt:1177`).
- **IMPORTS:** none new.
- **GOTCHA:** The problem is **not** the wait — it is that an unbounded synchronous load holds 1 of 8 shared admission permits and serializes every other request behind `lock`. Also: switching outright to 503 would break every client that does not implement `Retry-After`, which is most of them on a first request. **Do not implement this task before task 5 produces a number.**
- **VALIDATE:** On-device: a client with a 30 s timeout gets a clean 503 rather than a socket timeout, and its retry succeeds.

### Task 7 — Docs

- **ACTION:** Add an idle-unload row to `docs/RUNBOOK.md`; correct any "reclaims memory" phrasing to **primary engine only**.
- **IMPLEMENT:** State the default (15 min), where to change it, that `0 = never`, that the service and mDNS advertisement stay up, and that the first request after an unload pays a cold start.
- **MIRROR:** existing RUNBOOK row style.
- **GOTCHA:** **Do not put the `SPIKE-FINDINGS.md` cold-start numbers in user-facing docs** — they measure process-start init on a different device/model, not the reload path.
- **VALIDATE:** Manual read-through.

## Testing Strategy

### Unit Tests

| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| `disabling idle unload persists zero` | toggle off at 15 | `idleTtlMinutes == 0` | Sentinel |
| `re-enabling restores the previous non-zero value` | 30 → off → on | `30`, not `15` | Yes — naive impl returns the default |
| `re-enabling with no remembered value falls back to the default` | fresh install → off → on | `IDLE_TTL_DEFAULT_MINUTES` | Yes |
| `stepper decrement stops at the minimum and never reaches the disabled sentinel` (`RelaisIdleTtlConfigTest.kt` — Robolectric, it round-trips through `RelaisConfig`) | 5 → `–` | `IDLE_TTL_MIN_MINUTES`, **not** 0 | Yes — `sanitizeIdleTtlMinutes` (`RelaisConfig.kt:421-423`) turns 0 into "disabled" |
| `unload counter increments in the prometheus render` | delta across one `recordEngineUnload()` | `+1` | Yes — must be a **delta**; `resetIncrementsForTest` doesn't clear it |
| `unload counter increments in the json render` | same | `+1` | Both renders, not just one |
| `metrics render carries no path or key` | full render | no `/storage`, no key substring | Mirrors the leak posture (`RelaisMetrics.kt:36-38`) |
| `idle-unloaded node reads IDLE not STARTING` | `computeNodeState(shouldRun=true, ready=false, startupInProgress=false, wasIdleUnloaded=true, lastInitFailed=false)` | `NodeState.IDLE` | Yes — **highest false-pass risk**: the existing table may already return a plausible value |
| `failed init still reads ERROR while idle-unloaded` | same but `lastInitFailed=true` | `NodeState.ERROR` | Yes — idle must never mask a real failure |
| `a ready engine reads LIVE even if wasIdleUnloaded is stale` | `ready=true, wasIdleUnloaded=true` | `NodeState.LIVE` | Yes — mirrors the existing stale-`lastInitFailed` rule |
| `load duration histogram renders bucket, sum and count` | `recordEngineLoad(1.5)` fed directly | `_bucket`/`_sum`/`_count` lines all move | Yes — a name grep passes on a declaration that renders nothing |
| `resetIncrementsForTest clears the load histogram` | observe, reset, render | all three back to zero | Yes — omitting the reset entry leaks into every later test |
| ~~`load duration is recorded only for a real init`~~ | — | — | **Not JVM-testable.** `ensureInitialized` needs a real model file (`:340`) and the native AAR (`:349`). Moved to `IdleUnloadProbe.kt` (task 5) |
| `idle-unloaded node whose reload keeps failing reads ERROR, not IDLE` | `ready=false, startupInProgress=false, lastInitFailed=true, wasIdleUnloaded=true` | `NodeState.ERROR` | Yes — **the regression the precedence fix exists to prevent**; IDLE reads as healthy on a dashboard |

### Edge Cases Checklist

- [ ] TTL toggled off **while an unload is mid-flight** — the next tick must simply not unload; nothing must double-release.
- [ ] Stepper decremented to the `IDLE_TTL_MIN_MINUTES` floor must **not** silently cross into the disabled sentinel.
- [ ] Widget `canRun` (`RelaisWidget.kt:95`) is an `==`, not a `when` — confirm by hand that the RUN button is **live** on an IDLE node. The compiler will not tell you.
- [ ] `wasIdleUnloaded` must be cleared **at the start** of a real init attempt so `IDLE` cannot stick through a failing reload. Today's `RelaisEngine.kt:353` sits *after* `buildResidentEngine` and is reached only on success — task 4(b) moves it. Verify by making `buildResidentEngine` throw and confirming `/health` reads `ERROR`, not `IDLE`.
- [ ] Idle gauge on a node that has **never served** — `lastActivityAtMs` is initialized at construction (`:230`), so a naive gauge reports **process uptime**, which is large and meaningless. Emit `-1` as a sentinel, or omit the series until first activity; do not ship the uptime reading.
- [ ] Concurrent `/health` and an in-progress unload — `state` may be momentarily stale; it must never throw.
- [ ] **Mutation-verify every test**: this repo has shipped two regression tests that passed under the bug they claimed to pin. After each goes GREEN, break the implementation and confirm the test fails.

## Validation Commands

```bash
# JVM unit tests — the CI job. Run from Android/src after ANY change under main/ or test/.
cd Android/src
./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest

# Compile (do not run) the on-device probe suite
./gradlew :app:compileFullOpenDebugAndroidTestKotlin

# One debug APK if a device check is needed (never `assembleDebug` — ambiguous across dist×policy)
./gradlew :app:assembleFullOpenDebug

# Stale-Hilt failures after a branch switch
./gradlew :app:clean
```

```bash
# The new probe (destructive cycles on the SPARE Pixel 10 "rango", not the live node)
adb -s <serial> shell am instrument -w \
  -e class cc.grepon.relais.IdleUnloadProbe \
  com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -s RelaisIdleProbe
```

`report-worker/` is untouched — no `npm` job applies.

### Manual Validation

- [ ] `curl -k https://<phone-ip>:8443/health` → `{"status":"ok","ready":true,...,"state":"LIVE"}` while serving.
- [ ] Set the TTL to 1 min in Configure, wait, re-`curl` → `"ready":false,"state":"IDLE"`, and the notification reads *"Idle — engine released…"*.
- [ ] `curl -k -H "Authorization: Bearer <key>" https://<phone-ip>:8443/metrics | grep relais_engine_` → all three metrics present, unload counter at 1.
- [ ] Time the next chat completion — `time curl -k ... /v1/chat/completions ...` — and record the number in the PR. **This is the figure task 6 depends on.**
- [ ] Watch `adb logcat -s RelaisWatchdog` across the idle window — **no restart** must occur.
- [ ] Toggle `IDLE UNLOAD` off, idle past the old TTL, confirm the engine stays resident.
- [ ] Confirm the QS tile and the home-screen widget both show an idle presentation, not "off".

## Acceptance Criteria

- [ ] An operator can change the idle TTL **and disable it entirely** from the Configure screen; disabling restores pre-#178 always-resident behavior, verified by the round-trip test and on device.
- [ ] `relais_engine_unloads_total`, `relais_engine_idle_seconds`, and `relais_engine_load_duration_seconds` render in **both** the Prometheus and JSON outputs, carry no path/id/key, and are covered by JVM tests that assert **rendered lines** (delta form for the counter; `_bucket`/`_sum`/`_count` for the histogram) — never a name grep. The histogram is entered in `resetIncrementsForTest`.
- [ ] The "load duration is recorded only for a real init" property is asserted **in `IdleUnloadProbe.kt`**, not claimed as a JVM test — `ensureInitialized` needs a real model file and the native AAR.
- [ ] `/health` distinguishes idle-unloaded from crashed **without changing the meaning of `ready`** and **without adding a fourth health derivation**.
- [ ] An idle-unloaded node whose reload **fails** reads `ERROR`, not `IDLE` — verified both in `NodeStateTest` and on device by making the load fail. (Gap 6, the reload-failure breaker, is explicitly **out of scope**: see *Notes → Cut after review*. The PR must say so rather than implying it was closed.)
- [ ] The QS tile and the widget are both usable on an IDLE node: `tileAction` returns `START` and `RelaisWidget.kt:95` `canRun` includes IDLE — the latter checked **by hand**, since it is an `==` the compiler will not flag.
- [ ] The reload time is **measured on device** and recorded in the PR; task 6 is decided from that number, not from `SPIKE-FINDINGS.md`. The `/v1/audio/transcriptions` path (`RelaisHttpServer.kt:641-648`) is decided in the same breath — it is the primary engine, not a secondary one.
- [ ] Every new test **failed before it passed** — mutation-verified, not merely green.
- [ ] Scope cuts are written into the PR: secondary models have no TTL; the stale-TXT bug is filed against #180.
- [ ] `./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest` green; CI green; independent code review APPROVE on the **final** diff (green CI ≠ reviewed).

## Completion Checklist

- [ ] Patterns above followed (metrics 3-step, `ToggleRow`/`Stepper`, clamped config, pure-logic guards, probe KDoc-as-runbook)
- [ ] Error handling: the 503 path reuses `shedIfHot`'s exact shape; a failed reload surfaces as `ERROR` in `/health`, never as `IDLE`
- [ ] Logging: `Log.i` for transitions with the deciding numbers; no path, key, or IP in any new log line
- [ ] Tests written, RED-proven, and mutation-verified
- [ ] No hardcoded values — every threshold (including the stepper step and floor) is a named `const val` with a KDoc stating the judgment
- [ ] Docs updated (`docs/RUNBOOK.md`), and the "reclaims memory" claim corrected to primary-engine-only
- [ ] No scope additions — the NOT Building list held
- [ ] Self-contained: no change required in `report-worker/`, no schema change, no new dependency

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Regressing #178's concurrency argument near `RelaisEngine.kt:605-616` / `:969-982` | Medium | **Critical** — mid-inference unload or use-after-close | Change nothing there beyond the metric call; the on-device probe, not the unit suite, is the gate. This repo has twice had every test layer green while the assembled thing was broken |
| `NodeState.IDLE` has a wider blast radius than it looks (tile, widget, truth table, dashboard) | High | Medium | The exhaustive `when`s surface every site at compile time — that is the argument for doing it properly rather than special-casing `/health`. Reconcile with the in-flight feature-09 dashboard rework first |
| ~~The persisted reload breaker wedges a node permanently~~ | — | — | **Task cut after review** — the breaker could not detect the failure it targeted. See *Notes → Cut after review*. Gap 6 remains open |
| **`IDLE` masks a broken node.** A node that unloads and then fails every reload reports a state that reads as healthy | **High if the precedence is got wrong** | **High** — a monitoring regression, not a cosmetic one | Two independent defenses in task 4: `lastInitFailed` is evaluated **above** `IDLE`, and `wasIdleUnloaded` is cleared at the **start** of an init attempt rather than only on success. Both are covered by truth-table tests, and by an on-device check that a failing load reads `ERROR` |
| The widget's `canRun` is an `==`, not a `when`, so IDLE silently disables the RUN button on the exact node a user wants to poke | **High** if left to the compiler | Medium | `RelaisWidget.kt:95` is named explicitly in task 4(c) and in the Files-to-Change table, and is on the Edge Cases checklist as a by-hand check |
| A new counter's test passes vacuously because `resetIncrementsForTest` doesn't clear plain counters | High | Medium | Assert **deltas**; mutation-verify |
| The load histogram is validated by a name grep and renders nothing | High | Medium | Task 1 mandates asserting on the rendered `_bucket`/`_sum`/`_count` lines, and adding a `resetIncrementsForTest` entry so it does not leak between tests |
| Cold-start figures quoted from `SPIKE-FINDINGS.md` are for a different path (process-start init on E4B/G4, not idle reload) | Certain | Medium | Treat ~23 s / 44.6 s as an order of magnitude. Task 6 is explicitly gated on a measured number; keep both figures out of user-facing docs |
| A future litertlm bump reintroduces a first-inference-only native crash | Medium | **High** | Idle unload multiplies first-inferences by the number of idle cycles — see the note below. **This risk is currently unmitigated**: the reload breaker that was to cover it is cut (it could not detect it — see *Notes → Cut after review*), so gap 6 stays open. The probe's 5-cycle loop is the detector; the existing close-failure breaker does not help here |
| Textual merge conflict with sibling plans in `RelaisMetrics.kt` | High | Low | See the coordination note below |

### The G5 first-inference interaction — state it precisely

Three sources disagree and the reconciliation matters:

- **`RelaisEngine.kt:341-343`** records that the Pixel-10/Tensor-G5 pre-flight gate refusing `gemma-4-E4B` **was removed**: E4B was verified to init and serve (text, sustained decode, vision) on G5 with **no SIGSEGV** on litertlm **0.12.0** (2026-07-12, on `rango`).
- **`libs.versions.toml:23`** pins `litertlm = "0.12.0"` — the version that was verified clean.
- **`SPIKE-FINDINGS.md:32-33`** records the SIGSEGV on **0.11.0 and 0.13.1** — i.e. broken *before* and again *after* the version currently shipped.
- **`RelaisModelProvisioner.deviceDefaultRef` / `G5_DEFAULT_REF`** still pins a fresh Pixel 10 to E2B (covered by `Pixel10DefaultModelTest.kt`).

So: **not a live crash on the version we ship**, but a first-inference-only native bug that has already reappeared once in a later release, and idle unload turns one first-inference-per-process into N. This is the strongest argument for *some* reload-failure brake — but not for the one that was drafted, which fired in the wrong place entirely (*Notes → Cut after review*). The honest position: **a litertlm bump past 0.12.0 could reintroduce this and nothing in this plan would stop the loop**; the probe's 5-cycle loop would at least detect it before release. Do **not** write "the pre-flight gate refuses E4B on G5" anywhere; that sentence is now false.

### Coordination note (sibling plans, checked 2026-09-06)

- `feature-19-power-energy-metrics` (revised) adds ten series to `RelaisMetrics.kt`: `relais_battery_{current_now_milliamps,voltage_volts,temperature_celsius,charging,level_ratio}`, `relais_power_draw_milliwatts` / `relais_power_measurement_valid`, a `relais_decode_window_energy_milliwatt_hours` **histogram** plus `relais_decode_window_energy_per_1k_tokens_milliwatt_hours`, and the `relais_energy_samples_invalid_total` counter (`feature-19-power-energy-metrics.plan.md:125-136`, `:491`). **No metric-name collision** with this plan's three `relais_engine_*` names — the two prefix families are disjoint — but the insertion points are adjacent, so expect a textual merge conflict, not a semantic one. feature-19 also updates `RelaisEngine.kt` (`:491-492`), though in the **decode lanes** (`:673-685`, `:797`, `:829`), disjoint from this plan's `ensureInitialized` / `releaseIfIdle` edits. feature-19 already owns the fix for the stale `RelaisMetricsLeakTest` reference in the file KDoc (`RelaisMetrics.kt:38` → `RelaisMetricsIncrementsTest`, `feature-19…:723`); **do not duplicate it here.**
- `feature-19` (`:722`, `:1343`) and `feature-20` (`:686`, `:1080`) **both extend `resetIncrementsForTest` (`RelaisMetrics.kt:471-484`)**, and task 1 must extend it too. Three plans editing one 14-line function is a guaranteed conflict — sequence them or expect to rebase. feature-20 makes no init-timing claim, so task 1's *metric* is unowned even though its reset seam is contested.
- `feature-18-trusted-lan-cert` **rewrites the route `when` and quotes `handleHealth` (`:707-712`)** (`feature-18-trusted-lan-cert.plan.md:262`, `:268`, `:607`) — the exact block task 4 edits to add `"state"`. It also adds an unauthenticated `GET /ca.crt` (`:112`), which retires the "`/health` is the only unauthenticated route" framing used in task 4's GOTCHA. Re-check that sentence at build time.
- `feature-17-ollama-compat-api` (`:692`) computes `/api/ps` `expires_at` from the idle TTL plus last activity — i.e. it needs the **same `idleSeconds` accessor** task 2 introduces. Coordinate, or the accessor gets written twice with different semantics.
- `feature-09-web-dashboard` is being reworked by a sibling and owns `assembleDashboardStatus` (`RelaisDashboard.kt:76`). Task 4's "consolidate on `computeNodeState`" recommendation targets a surface that is currently moving.
- `feature-21-home-assistant` cites this plan's cold start in its 503 troubleshooting row.

## Notes

### Cut after review

**Task 4, the reload-failure circuit breaker, was drafted and then cut.** It is recorded here rather
than silently dropped, because gap 6 in *Problem → Solution* is still open.

Why it could not work as designed: the counter was to be incremented before the load and cleared on a
successful load, both inside `ensureInitialized` (`RelaisEngine.kt:332-355`). But the failure it
targeted — a G5-class first-inference SIGSEGV — happens inside `generate`, **after**
`ensureInitialized` has already returned and already cleared the counter. The process dies, the
watchdog restarts it, the next `ensureInitialized` succeeds, and the count is zero again. The breaker
measured "can we construct the engine object", which is not what breaks. Worse, a native crash kills
the process before any `apply()` flush is guaranteed, so even a genuine init crash might never be
recorded. Set against that: it added a persisted pref, a new `shouldUnloadIdleEngine` parameter, a
`SharedPreferences` write **inside the engine lock** on every cold start (`ensureInitialized` runs
entirely within `synchronized(lock)`, which `generate` contends on), and a breaker that would trip on
three ordinary boots across three days. New state and a new failure surface in exchange for no
detection.

What stands in its place: the **existing** close-failure breaker
(`IDLE_TTL_MAX_CONSECUTIVE_CLOSE_FAILURES`, `RelaisIdleTtl.kt:62`, consumed by
`shouldUnloadIdleEngine` `:79-92`), which already refuses to unload after three failed `close()`
calls. Gap 6 stays open in the table above.

If someone picks it up later, the shape that *would* work is a persisted **"loaded, but no successful
`generate` yet"** marker, cleared on the first successful generation — that survives the crash and
actually counts crash-loops. It is a different feature from what was drafted, and it needs its own
plan.

**Decisions made**

- **Keep the hold for chat/generate; bound it rather than replacing it with 503.** Most OpenAI clients do not implement `Retry-After` on a first request, and HA's `litellm` integration has a 600 s read timeout that tolerates the load comfortably. The defect is permit-holding and lock serialization, not the wait itself.
- **Extend `NodeState` rather than bolting a boolean onto `/health`.** It is a larger change, and it is the right one — it also fixes the tile and widget showing "off"/"starting" for a node that is healthy and idle.
- **Clear `wasIdleUnloaded` at the start of the init attempt, not the end.** Today's placement (`RelaisEngine.kt:353`, after `buildResidentEngine`) means it is reached only on success, which would let `IDLE` stick forever on a node whose reloads keep failing. The line's own comment already describes the start-of-attempt semantics; moving it makes the comment true.
- **Copy `Stepper` into `RelaisConfigureActivity`** rather than promoting it to a shared UI module — each screen keeping its own private row composables is the observed convention, and one shared-module refactor is out of scope for this plan.

**Alternatives considered and rejected**

- *A `SharedPreferences` change listener* — rejected; none exists in `main/`, and the 60 s ticker already re-reads the pref (`RelaisNodeService.kt:134`). Pure cost.
- *A boolean `idle: true` on `/health`* — rejected; it would be the fourth parallel health derivation and would leave the tile and widget still wrong.
- *Making `lastActivityAtMs` public* — rejected in favor of a read-only `idleSeconds` accessor, keeping the write path private.
- *Also unloading the embedder on idle* — deferred. It would make the memory claim honest, but the embedder has no unload path at all and its reload cost is unmeasured.

**Open questions for the user**

1. **Task 4 vs the in-flight feature-09 dashboard rework** — should this plan take `NodeState.IDLE` and let feature-09 rebase onto it, or wait? They collide on `assembleDashboardStatus`. (feature-18 also rewrites `handleHealth`; see the coordination note.)
2. **Stepper ladder and the 1-minute floor.** A 5-minute step never reaches the documented `IDLE_TTL_MIN_MINUTES = 1` (`RelaisIdleTtl.kt:49`), so a valid value is unreachable from the UI. Either adopt a non-linear ladder (1/5/15/30/60) that reaches it, or raise the documented minimum to 5. Not a decision to leave to the implementer.
3. **Is gap 6 worth a plan of its own?** The reload-failure breaker was cut (above) because it could not detect the crash-idle-crash loop it targeted. A "loaded but never generated" marker would work, but it is a different feature. Leave gap 6 open, or commission it?

**Findings accepted from review, and the one declined**

Every finding in `critic-22.md` (0 CRITICAL · 3 HIGH · 7 MEDIUM · 4 LOW) was re-verified against
source before being applied. All three HIGHs held and are fixed above. Six of the seven MEDIUMs held
and are fixed; **MEDIUM 4 — the `SharedPreferences` write inside the engine lock — dissolved with the
Task 4 cut** rather than being fixed, since the write it described no longer exists anywhere in the
plan. Note also that no task now touches `shouldUnloadIdleEngine` at all, so `RelaisIdleTtl.kt` and
its pure-logic suite `RelaisIdleTtlTest.kt` have both left the Files-to-Change table. Of the four
LOWs: L1 (four off-by-one citations) fixed — `RelaisConfigureActivity.kt` remember idiom is
`:145-147`, `RelaisWidget.kt` `StatusLine` is `:117-123`, `TilePresentation.kt` `tileAction` is
`:77-81`, `releaseIfIdle`'s KDoc starts at `:937`. L2 (idle gauge reads process uptime on a
never-served node) fixed on the Edge Cases checklist. L4 (`NodeStateTest` calls positionally while
the controller uses named args) fixed by mandating "append the parameter **last**".

- **Declined: L3 — fixing the stale `RelaisMetricsLeakTest` reference in the `RelaisMetrics.kt:38`
  KDoc.** feature-19 already owns that correction (noted in the coordination section); doing it here
  too produces a pointless conflict in a file three plans are already contending for.

One critic item was **already covered** rather than accepted as new: the "a litertlm bump past 0.12.0
can reintroduce a first-inference-only crash, which idle unload re-triggers on every reload" caveat
is in the Risks table and expanded in *The G5 first-inference interaction* below it.

---

## Report

- **File:** `.claude/PRPs/plans/feature-22-idle-unload.plan.md`
- **Complexity:** Large
- **Scope:** 14 files (1 CREATE probe, 13 UPDATE), 7 tasks
- **Key Patterns:** the pure-logic + JVM-test pair (`RelaisIdleTtl.shouldUnloadIdleEngine` `:79-92` ↔ `RelaisIdleTtlTest.kt:19-55`); `RelaisEngine.releaseIfIdle` pre-check/lock/re-check (`:969-982`); the 3-step metric idiom (`AtomicLong` `:46` → `record…()` `:159` → Prometheus `line(...)` `:323-325` + JSON `:452`); `ToggleRow` (`RelaisConfigureActivity.kt:333-347`) and the `remember { mutableStateOf(RelaisConfig.x(ctx)) }` idiom (`:142-148`)
- **External Research:** Prometheus metric-naming conventions (`_total` counters vs `_seconds` gauges); Android foreground-service lifetime under Doze; the LiteRT-LM native API inventory (`docs/litertlm-native-api.md`) for what reload actually costs
- **Top Risk:** `NodeState.IDLE` masking a broken node. A node that unloads and then fails every reload would report a state that reads as **healthy** on every dashboard — a monitoring regression, not a cosmetic one. Two independent defenses (precedence below `lastInitFailed`; clearing `wasIdleUnloaded` at the start of an init attempt) plus truth-table and on-device checks. The standing second risk is unchanged: `RelaisEngine`'s init/release concurrency near `:605-616`/`:969-982`, where the on-device probe, not the JVM suite, is the gate
- **Confidence Score:** 5/10 (revised down from 6 after review. The gap analysis and its line-level anchors held up under independent verification, but of eight drafted tasks one was **cut outright** as undetectable, one had inverted state precedence that would have shipped a healthy-looking broken node, and one was sized as a one-liner when it is a full Prometheus histogram. Three surface decisions — the stepper ladder, the `/v1/audio/transcriptions` classification, and sequencing against feature-09/-18 on `handleHealth` — are named but not settled)

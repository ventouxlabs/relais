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
| 1 | **The TTL is unreachable.** `RelaisConfig.setIdleTtlMinutes` (`RelaisConfig.kt:476-478`) has **zero production callers** — grep across `Android/src/app/src/main/` finds only the read at `RelaisNodeService.kt:187` and the declarations. Every node in the field runs exactly 15 min, and an operator who wants pre-#178 always-resident behavior cannot get it even though `0 = never` is implemented and tested. | Two rows on the Configure screen: a `ToggleRow` and a `Stepper` pair, using the screens' own private composables. |
| 2 | **No metrics.** No unload counter, no idle gauge, no load-duration metric. `relais_engine_ready` flips to 0 on an idle unload and is indistinguishable from a crash on a dashboard. `lastActivityAtMs` is private (`RelaisEngine.kt:254`) with no accessor. | Three metrics following the existing 3-step counter pattern, plus a narrow public `idleSeconds` accessor. |
| 3 | **`/health` conflates four states.** `handleHealth` (`RelaisHttpServer.kt:948-954`) returns only `{status, ready, thermal_state}`; `ready=false` covers provisioning, cold-starting, idle-unloaded, and dead-after-failed-init. The three flags that separate them — `RelaisLivenessState.snapshot.startupInProgress` (an immutable snapshot since #327, `RelaisLiveness.kt:19-22`), `wasIdleUnloaded` (`RelaisEngine.kt:266`), `lastInitFailed` (`:285`) — are all public and all unexposed. | Add one coarse `state` field sourced from `computeNodeState`, extended with a new `NodeState.IDLE`. Do **not** change what `ready` means. |
| 4 | **Hold-vs-503 is already decided, inconsistently.** Chat/generate **hold** (`ensureInitialized` runs inside `generate`'s `synchronized(lock)`, `RelaisEngine.kt:645-659`) while audio/embeddings/rerank/images/TTS **kick-and-503**. | Keep the hold for chat/generate, but bound it with a load deadline — decided *after* measurement, not before. |
| 5 | **Cold start has never been measured** and is silently absorbed into request latency (`recordLatency` spans the load). There is no instrumentation of `ensureInitialized` at all. | Instrument it first, so the task-6 decision rests on a number instead of two stale spike figures. |
| 6 | **No breaker on *reload* failure.** `IDLE_TTL_MAX_CONSECUTIVE_CLOSE_FAILURES` (`RelaisIdleTtl.kt:62`) guards `close()` only. A reload that kills the process is watchdog-restarted (~55 s) and retried on the very next idle window — an unbounded crash-idle-crash loop. | **Not addressed here — see *Notes → Cut after review*.** A counter around `ensureInitialized` was drafted and cut: it cannot detect the failure it was designed for. Gap 6 stays open and is recorded as such rather than closed with something that does not work. |

## Metadata

- **Complexity:** Large (6 shipped-code surfaces + a real Prometheus histogram + an on-device probe)
- **Source PRD:** N/A
- **PRD Phase:** N/A
- **Estimated Files:** 17 changed — 2 CREATE (`IdleUnloadProbe.kt`, `RelaisIdleTtlLadderTest.kt`) + 15 UPDATE (11 under `main/` incl. `RelaisDashboard.kt` since feature-09 merged, 5 JVM tests incl. `RelaisDashboardTest.kt`, 1 doc), plus `triage/TriageControlActivity.kt` read-only as the `Stepper` source. (Cutting the reload breaker removed `RelaisIdleTtlTest.kt`'s breaker cases, but **`RelaisIdleTtl.kt` returns to the list**: open question 2 was decided as a non-linear ladder (1/5/15/30/60), which adds `IDLE_TTL_LADDER` + `nextRung` to that file — Task 3 — plus its own test file.)

## Reconciliation against `main` — 2026-09-20 (`2d25736a`, after #316–#332)

This plan is dated 2026-09-07. Between then and now PRs #316 #317 #318 #323 #325 #326 #327 #328 #329
#331 #332 merged and rewrote several seams it names. Every anchor and claim in the sections below was
re-checked against the tree on 2026-09-20 and corrected **in place, at every copy** — so a number you
read further down is current as of this date. Where the shape changed, not just the line, it is
listed here; the task text carries the full argument.

| Seam | 09-07 claim | 2026-09-20 reality | Verdict |
|---|---|---|---|
| `computeNodeState` | 5 booleans; append `wasIdleUnloaded` as the 6th | **6** — #327 added `listenersUp` (3rd). Append as the **7th**, last; controller `:34-41` is named, `NodeStateTest.kt:29` is positional | CHANGED — task 4(a) |
| `startupInProgress` | a public `@Volatile` on `RelaisEngine` (`:237`) | a field of the immutable `RelaisLiveness` snapshot (#327); read once via `RelaisLivenessState.snapshot` | CHANGED — every reader in this plan takes one snapshot |
| `wasIdleUnloaded = false` | `:353`, after `buildResidentEngine` — reached only on success; **move it to the start** | `:370`, still after `:366` — claim holds. But the *fix* was wrong: clearing at the start opens a watchdog race during synchronous reloads (`RelaisWatchdog.kt:135`), and `lastInitFailed` is never written on the request-driven reload path (`:270`/`:328` are its only writers, both in `RelaisNodeService`) | **CHANGED — task 4(b) rewritten** |
| `assembleDashboardStatus` | being reworked by feature-09; reconcile before touching | feature-09 **merged** (#323, #332). `RelaisDashboard.kt:105`, 18 params, derives `LIVE/STARTING/OFFLINE` locally, no `computeNodeState` call. An idle node renders **OFFLINE** | CHANGED — **task 4(e) added**; open question 1 resolved |
| `/health` | the only unauthenticated route | one of two — `/ca.crt` (#318); dispatch via `RelaisHttpGate.isHealthPath`/`isCaCertPath` (`:2147-2149`) | FALSE — corrected at 3 copies |
| `RelaisDiscovery.updateModel` | zero callers; file the stale TXT against #180 | called from `RelaisEngine.kt:507` since #332; #313 closed | FALSE — premise; conclusion (out of scope) unchanged |
| Task 1's histogram mirror | `relais_completion_tokens` (`:367-379`) | #316 landed `relais_time_to_first_token_seconds` — a **seconds** histogram with a reset entry (`:456-467`, `:589-593`) | MOVED + better template |
| `RelaisMetricsLeakTest` KDoc | stale, feature-19 owns the fix | already fixed (`RelaisMetrics.kt:309`) | moot |
| `handleHealth` | `:707-712` | `:948-954`, same three fields, no `state` | MOVED |
| `RelaisHttpServer.kt` size | — | **2809** (`wc -l`); `CLAUDE.md` now says extract-don't-append | new constraint; task 4(d) adds characters, not a handler |
| HANDOFF's "tasks 1–3, 5–8 (4 is cut)" | — | pre-renumbering. The cut was the reload **breaker** (*Notes → Cut after review*); the current Task 4 is `NodeState.IDLE` and is in scope. **All seven tasks are in scope.** | stale numbering |
| Everything else | `setIdleTtlMinutes` zero callers · audio kick-and-503 · `IDLE_TTL_*` · no ladder yet · `Stepper` private in triage · `canRun` is an `==` at `RelaisWidget.kt:95` · `idleSeconds`/`expires_at` absent · no probe yet | all re-verified | HOLDS (lines updated) |

**Findings from the tree that the 09-07 plan could not have had:**

1. **A broken idle node is currently invisible to monitoring *and* shielded from the watchdog** — the
   same shape as the #318 defect quoted in the KDocs (*"the state needing recovery was the state
   preventing it"*). `wasIdleUnloaded` stays `true` through a failed reload, so `RelaisWatchdog.kt:135`
   early-returns forever and nothing writes `lastInitFailed`. Task 4(b) fixes both; the PR must say it
   is a watchdog behaviour change, not only a labelling one.
2. **An idle node reads `STARTING` on the tile and widget today** (slot 6, `shouldRun -> STARTING`) and
   `OFFLINE` on the dashboard. The UX table's "○ off" is wrong for the tile; it is "○ starting…".

---

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
| Turning the toggle **off** | impossible | persists `IDLE_TTL_DISABLED_MINUTES` (0) → pre-#178 always-resident | Takes effect within one 60 s tick; `checkIdleUnload` re-reads the pref every tick (`RelaisNodeService.kt:187`) |
| Turning it back **on** | impossible | restores the last non-zero value, else `IDLE_TTL_DEFAULT_MINUTES` | Needs a second pref key to remember the prior value |
| `GET /health` while idle | `{"status":"ok","ready":false,...}` — reads as an outage | adds `"state":"IDLE"`; `ready` unchanged | `/health` is one of the two unauthenticated routes (with `/ca.crt`, #318 — `RelaisHttpServer.kt:2147-2149`) — stays coarse, leaks nothing |
| QS tile / widget while idle | "○ starting…" (slot 6 — `shouldRun -> STARTING`; never "off" while `shouldRun`) | an IDLE presentation | `TilePresentation.kt:51-57`, `RelaisWidget.kt:116-122` are exhaustive `when`s — the compiler surfaces every site |
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
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisWatchdog.kt` | 118-147 | The `wasIdleUnloaded` interlock — the subtlest part of #178 and the thing a regression would silently break |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/core/NodeState.kt` | 16-50 | The enum and the deliberate precedence order in `computeNodeState`. Adding `IDLE` means changing this truth table |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt` | 37, 46, 114-118, 194, 266-286, 367, 402-404, 456-467, 506-508, 557-558, 571-600 | The 3-step counter pattern, the `line()` helper, the label-hygiene rule, and the `resetIncrementsForTest` seam that does **not** clear plain counters |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 389-393, 739-751, 948-954, 2147-2149 | The auth gate (`/health` **and `/ca.crt`** are the open routes — `RelaisHttpGate.isHealthPath`/`isCaCertPath`), the canonical 503 shape, and `handleHealth` |
| **P2** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisConfigureActivity.kt` | 137-149, 344-358 | The `remember { mutableStateOf(RelaisConfig.x(ctx)) }` idiom and `ToggleRow` |
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
- **GOTCHA:** `RelaisMetrics.resetIncrementsForTest` (`:577-600`) clears only histograms and the thermal map — its own KDoc at `:571-575` says *"Does not touch the request-count map or counters used elsewhere."* A new plain counter's test **must assert a delta**, or it will pass vacuously when another test in the same JVM already incremented it.

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
// SOURCE: RelaisHttpServer.kt:739-751
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
// SOURCE: RelaisHttpServer.kt:389-393 (the gate comment; the decision is RelaisHttpGate.decide)
// Health is open; everything else needs the API key + is rate-limited per client IP.
if (!(method == "GET" && path.startsWith("/health"))) {
  if (!authorized(authorization)) {
    reply(401, RelaisError.json("unauthorized", RelaisError.AUTHENTICATION))
    return
  }
```

```kotlin
// SOURCE: RelaisHttpServer.kt:948-954
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

// SOURCE: RelaisMetrics.kt:194
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
// SOURCE: RelaisMetrics.kt:506-508
line("# HELP relais_queue_depth In-flight + queued inference requests.")
line("# TYPE relais_queue_depth gauge")
line("relais_queue_depth ${inFlight.get()}")
```

**UI_PATTERN** — the two composables to mirror, verbatim in style (monospace, `Muted` label / `Paper` value, 6dp clip, whole-row `clickable`, no `Switch`, no `strings.xml`):

```kotlin
// SOURCE: RelaisConfigureActivity.kt:344-358
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
// SOURCE: RelaisConfigureActivity.kt:137-149
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
| `.../RelaisEngine.kt` | UPDATE | `recordEngineUnload()` call site in `releaseIfIdle` (:1050-1051); public `idleSeconds` accessor for the private `lastActivityAtMs` (:254); load-duration instrumentation around `ensureInitialized` (:349-372); **and the task 4(b) rewrite of the real-init branch (`:357-370`): clear `wasIdleUnloaded` on BOTH outcomes, set `lastInitFailed` on throw, clear it on success** — the histogram measurement and the flag handling share one `try`/`finally` |
| `.../RelaisMetrics.kt` | UPDATE | Two counters/gauges × the 3-step pattern **plus one real histogram** (bounds array, lock, `_bucket`/`_sum`/`_count` render, **and** a `resetIncrementsForTest` entry) |
| `.../RelaisConfig.kt` | UPDATE | **One** new key: the remembered last non-zero TTL |
| `.../RelaisIdleTtl.kt` | UPDATE | **Decided 2026-09-07:** `IDLE_TTL_LADDER = intArrayOf(1,5,15,30,60)` + pure `nextRung()` for Task 3's stepper |
| `.../RelaisConfigureActivity.kt` | UPDATE | `IDLE UNLOAD` `ToggleRow` + `IDLE AFTER` `Stepper` pair, stepping by ladder rung via `nextRung()` |
| `.../triage/TriageControlActivity.kt` | — (read only) | Source of the `Stepper` pattern; **do not modify** |
| `.../core/NodeState.kt` | UPDATE | Add `NodeState.IDLE` and its precedence rule in `computeNodeState` |
| `.../core/RelaisNodeController.kt` | UPDATE | **The only production caller** of `computeNodeState` (`:34-41`, **named** args — so appending a parameter does not break it, but it must be passed or the branch is dead). Grep-verified 2026-09-20: `TilePresentation.kt` and `RelaisWidget.kt` *consume* the enum but do not call the function; `NodeStateTest.kt:29` is the other caller and is **positional** |
| `.../RelaisHttpServer.kt` | UPDATE | `handleHealth` (`:948-954`) gains `"state"`; `handleDashboard` (`:967-971`) reads `wasIdleUnloaded` once and passes it; optionally the bounded-hold 503 (task 6), and the `/v1/audio/transcriptions` decision at `:845-852`. **2809 lines** (`wc -l`, 2026-09-20) — a few characters, not the change that triggers the extraction `CLAUDE.md` now asks for |
| `.../RelaisDashboard.kt` | UPDATE | **Added 2026-09-20 — feature-09 merged.** `assembleDashboardStatus` (`:105-139`) gains a trailing `wasIdleUnloaded: Boolean = false` and an `IDLE` label; `dotColor` (`:264-268`) gets the `STARTING` treatment for it; the `:132` "same predicate" comment is made honest |
| `.../test/java/cc/grepon/relais/RelaisDashboardTest.kt` | UPDATE | **Added 2026-09-20.** Rows for `IDLE`, `LIVE`-beats-stale-flag, and `listenersUp=false` + flag → `OFFLINE` |
| `.../tile/TilePresentation.kt` | UPDATE | Exhaustive `when` at :51-57 (`tilePresentation`) **and** :77-81 (`tileAction`) must handle `IDLE`; `tileAction` needs an explicit `IDLE -> START` decision against its no-cold-start KDoc |
| `.../widget/RelaisWidget.kt` | UPDATE | Exhaustive `when` at :117-123 (`StatusLine`) must handle `IDLE` — **and `:95` `canRun` is an `==`, not a `when`**, so the compiler will *not* flag it; it must be edited by hand or the RUN button dies on an idle node |
| `.../test/java/cc/grepon/relais/RelaisIdleTtlConfigTest.kt` | UPDATE | Toggle round-trip + stepper-floor tests (already Robolectric) |
| `.../test/java/cc/grepon/relais/RelaisIdleTtlLadderTest.kt` | **CREATE** | Pure `nextRung()` coverage: every adjacent transition, floor/ceiling clamps, off-ladder starting values |
| `.../test/java/cc/grepon/relais/RelaisMetricsIncrementsTest.kt` | UPDATE | Delta-asserting counter tests + the histogram render/reset tests |
| `.../test/java/cc/grepon/relais/NodeStateTest.kt` | UPDATE | Truth-table rows for `IDLE` and for `ERROR`-beats-`IDLE`. **Its helper calls `computeNodeState` positionally (`:22-29`)** while `RelaisNodeController.kt:31-38` uses named args — append the new parameter **last** or it silently mis-binds here |
| `Android/src/app/src/androidTest/java/cc/grepon/relais/IdleUnloadProbe.kt` | **CREATE** | The only place the reload time and cross-cycle leak behavior can be measured |
| `docs/RUNBOOK.md` | UPDATE | An idle-unload row; correct "reclaims memory" to **primary engine only** |

## NOT Building

- **A redesign of `releaseIfIdle`'s locking.** The pre-check / lock / re-check structure and the `incInFlight`-before-lock ordering are load-bearing and already argued in KDoc. Touch nothing there beyond the metric call.
- **A pref-change listener.** No `OnSharedPreferenceChangeListener` exists in `main/`, and `checkIdleUnload` re-reads the pref every 60 s tick (`RelaisNodeService.kt:187`). Adding one is pure cost.
- **Idle unload for secondary models.** `EmbeddingGemmaEmbedder.warmIfProvisioned` (`RelaisNodeService.kt:287`) loads a *second* model at startup; grepping `embed/`, `imagegen/`, `tts/` finds only `TtsPlayer.release()` — there is no unload path at all. So "idle unload reclaims memory" is **partial** today. Name it in the docs; do not build it here.
- **The mDNS TXT after a model swap** — ~~`RelaisDiscovery.updateModel` has zero callers~~ **FIXED by #332** (2026-09-16): `RelaisEngine.kt:507` calls `updateModel` after a successful swap and the re-register is callback-driven (`RelaisDiscovery.kt:161-175`); #313 is closed. Still nothing for this plan to do: an idle unload does not touch the advertisement — it is registered once, refreshed on swap, and torn down only in `onDestroy`, so it correctly keeps advertising a node that will serve on the next request.
- **Changing the kick-and-503 *secondary-model* paths** (`RelaisHttpServer.kt:560-569` TTS, `:1263` embeddings, `:1300` rerank, `:1345` images). Those are genuinely separate engines and are already consistent with each other.
- ⚠ **`/v1/audio/transcriptions` is NOT one of them — it is the primary engine, on the idle-unload path.** `RelaisHttpServer.kt:845-852` guards on `if (!RelaisEngine.isReady)`, calls `RelaisEngine.ensureInitializedInBackground(context)` and 503s with `Retry-After: 10`, and its own comment names the cause: *"e.g. the engine was idle-TTL-unloaded, #178"* (re-verified 2026-09-20, unchanged). An earlier draft of this plan mis-filed it as a secondary engine. **Task 6 must decide it explicitly**: if chat/generate gain a bounded hold, audio becomes the one primary-engine route that behaves differently for the same cause. Either include it in task 6 or write down why 503 stays right there — do not leave it unstated.
- **Changing the meaning of `ready` in `/health`.** Existing clients and the RUNBOOK depend on it meaning "can serve right now". Idle-unloaded genuinely is not ready.
- **A fourth health derivation.** There are already three (`computeNodeState`, `handleHealth`, `assembleDashboardStatus`).

## Step-by-Step Tasks

### Task 1 — Instrument `ensureInitialized` duration

- **ACTION:** Wrap the real-init branch of `RelaisEngine.ensureInitialized` in a duration measurement and feed a new `relais_engine_load_duration_seconds` histogram.
- **IMPLEMENT:** Inside `synchronized(lock)` after the `if (isReady) return` re-check (`RelaisEngine.kt:355-356`), capture `val startNs = System.nanoTime()`; at the end of the successful branch call `RelaisMetrics.recordEngineLoad((System.nanoTime() - startNs) / 1e9)`. Measure **only** the branch that actually builds an engine — the early `if (isReady) return` at `:337` must record nothing, or the histogram fills with zeros. **This is a real histogram, not a one-liner.** Budget the same four pieces `relais_time_to_first_token_seconds` (#316) has: (a) its own bounds array + counts `LongArray` (`RelaisMetrics.kt:114-118` — a **seconds** ladder, `0.25…60`; a load histogram wants a coarser one, e.g. `1, 2, 5, 10, 20, 30, 60, 120`), (b) its own lock object, (c) a cumulative `_bucket` render loop plus `_sum` and `_count` lines — mirror `:456-467` verbatim in shape, and (d) **an entry in `resetIncrementsForTest` (`:577-600`, the `ttftHistLock` block at `:589-593` is the template)**, or the histogram leaks across every test in the process-global object.
- **MIRROR:** METRICS_PATTERN; and specifically the `relais_time_to_first_token_seconds` histogram — `recordTimeToFirstToken` `:266-273`, bucket index `:285-286`, render `:456-467`, reset `:589-593` (it landed in #316, after this plan was written, and is the seconds-based sibling of what task 1 adds). Not `relais_completion_tokens` (a token-count ladder) and not `recordLatency`'s per-endpoint map.
- **IMPORTS:** none new in `RelaisEngine.kt` (`RelaisMetrics` is already referenced at `:1042`).
- **GOTCHA:** Cold start is currently **absorbed into request latency** — `recordLatency` spans from `reqStartNs` through the lock, so the existing latency histogram already double-counts a reload. Note this in the metric's HELP text; do not "fix" `recordLatency` in this task. **And do not validate with a name grep:** a `grep` for `relais_engine_load_duration_seconds` passes on a declaration that renders nothing. Assert on rendered `_bucket`/`_sum`/`_count` lines with a real observation fed in.
- **NOT JVM-TESTABLE:** the "recorded only for a real init" property **cannot** be a JVM test — `ensureInitialized` runs `require(File(modelPath).exists())` (`:357`) and then `buildResidentEngine` (`:366`), which needs the native AAR. That assertion belongs in `IdleUnloadProbe.kt` (task 5), and the acceptance criteria say so.
- **VALIDATE:** `./gradlew testFullOpenDebugUnitTest` from `Android/src`; a JVM test feeds `recordEngineLoad(1.5)` directly and asserts the rendered `_bucket`/`_sum`/`_count` lines move, then that `resetIncrementsForTest()` zeroes them.

### Task 2 — Unload counter + idle gauge

- **ACTION:** Add `relais_engine_unloads_total` (counter) and `relais_engine_idle_seconds` (gauge).
- **IMPLEMENT:** Declare `private val engineUnloadsTotal = AtomicLong(0)` beside `shedTotal` (`RelaisMetrics.kt:46`); `fun recordEngineUnload() = engineUnloadsTotal.incrementAndGet()` beside `recordShed()` (`:194`); Prometheus block after `relais_queue_rejected_total` (`:402-404`); JSON `.put("engine_unloads_total", …)` after `:557-558`. Call `RelaisMetrics.recordEngineUnload()` in `releaseIfIdle` immediately after `shutdown()` and before `wasIdleUnloaded = true` (`RelaisEngine.kt:1050-1051`) — inside the lock, so it counts actual releases, not attempts. For the gauge, add to `RelaisEngine` a narrow read-only accessor rather than widening the field: `val idleSeconds: Double get() = (System.currentTimeMillis() - lastActivityAtMs) / 1000.0`, and render it with the queue gauges (`RelaisMetrics.kt:506-508`).
- **MIRROR:** METRICS_PATTERN (counter + gauge forms).
- **IMPORTS:** `java.util.concurrent.atomic.AtomicLong` is already imported in `RelaisMetrics.kt`.
- **GOTCHA:** `resetIncrementsForTest` (`:577-600`) does **not** clear plain counters — its KDoc says so at `:571-575`. The test must assert a **delta**, not an absolute. Also: label hygiene (`RelaisMetrics.kt:37`) — these are plain numbers, no path/id/key, so no new leak surface.
- **VALIDATE:** JVM tests assert both renders move by exactly 1 per `recordEngineUnload()`.

### Task 3 — Configure-screen controls (the headline gap)

- **ACTION:** Add `IDLE UNLOAD` and `IDLE AFTER` rows to `RelaisConfigureActivity`.
- **IMPLEMENT:** Add `KEY_IDLE_TTL_LAST_NONZERO_MINUTES` to `RelaisConfig` plus `idleTtlLastNonZeroMinutes(ctx)` / `setIdleTtlLastNonZeroMinutes(ctx, v)`, mirroring `:414-419`. Add `internal val IDLE_TTL_LADDER = intArrayOf(1, 5, 15, 30, 60)` to `RelaisIdleTtl` (decided by JD, 2026-09-07 — non-linear so it reaches the existing 1-minute floor; see the GOTCHA this replaces). In the composable, `var idleTtl by remember { mutableStateOf(RelaisConfig.idleTtlMinutes(ctx)) }`. `ToggleRow("IDLE UNLOAD", idleTtl > 0) { … }` writes `IDLE_TTL_DISABLED_MINUTES` when turning off (after saving the current value as last-non-zero), and restores the remembered value — falling back to `IDLE_TTL_DEFAULT_MINUTES` — when turning on. Render the `IDLE AFTER` row only when `idleTtl > 0`: a label, a `Stepper("–")`, a `"$idleTtl min"` readout, a `Stepper("+")`. Each tap moves to the adjacent **ladder rung**, not a fixed increment: `fun nextRung(current: Int, ladder: IntArray, up: Boolean): Int` finds the ladder index whose value is `>= current` (or the last index, if none), then steps `+1`/`-1` within `ladder.indices`, clamped — so a value that isn't itself on the ladder (e.g. a config imported from an older build) still moves to the nearest neighbor rather than getting stuck. `setIdleTtlMinutes` is still called with the resulting rung value, so its existing clamp is a no-op safety net, not the mechanism.
- **MIRROR:** UI_PATTERN (`ToggleRow`, `Stepper`), CONFIG_PATTERN, and the `remember` idiom at `RelaisConfigureActivity.kt:137-149`.
- **IMPORTS:** `Stepper` is `private` in `triage/TriageControlActivity.kt` — **copy the composable into `RelaisConfigureActivity.kt`** (matching how each screen keeps its own private row composables) rather than making it public or introducing a shared UI module. Existing Compose imports in the file already cover `Row`/`Box`/`Text`/`clickable`/`RoundedCornerShape`; add `FontWeight` if absent.
- **GOTCHA:** No Material `Switch` exists anywhere in the Relais screens and strings are hardcoded UPPERCASE monospace (no `strings.xml`) — DESIGN.md is explicit. Do not add either. `nextRung` must be a pure, JVM-testable function (put it in `RelaisIdleTtl.kt`, not inline in the composable) — decrementing off the bottom of the ladder must land on `IDLE_TTL_MIN_MINUTES` (1), never on `IDLE_TTL_DISABLED_MINUTES` (0); the toggle owns disabling, the stepper never does. Incrementing off the top must clamp at 60, not overflow into `IDLE_TTL_MAX_MINUTES` if that differs.
- **VALIDATE:** New pure-function test `RelaisIdleTtlLadderTest` (or added to `RelaisIdleTtlConfigTest`): every adjacent-rung transition in `[1,5,15,30,60]`, decrement-at-1 stays 1, increment-at-60 stays 60, and an off-ladder starting value (e.g. 10, from an old config) moves to the nearest neighbor in the expected direction. Robolectric round-trip in `RelaisIdleTtlConfigTest`; visually confirm against DESIGN.md's label-left/value-right rule on device.

### Task 4 — `NodeState.IDLE` + `/health` `state` field

- **ACTION:** Let every health surface tell idle from dead.
- **IMPLEMENT (five parts — the precedence is the subtle one; (e) was added 2026-09-20 when feature-09 merged):**

  **(a) The enum + the branch, in the *correct* slot.** Add `IDLE` to `enum class NodeState` (`core/NodeState.kt:16`). Thread `wasIdleUnloaded` in as a **seventh** parameter (#327 added `listenersUp` as the third — the shipped signature is `shouldRun, ready, listenersUp, startupInProgress, lastInitFailed, thermalStatus`, `:36-43`) — **append it last, required, no default**; the only production caller `core/RelaisNodeController.kt:34-41` uses **named** arguments (so appending is safe there), but the test helper `NodeStateTest.kt:23-29` calls **positionally**, and inserting anywhere but last silently mis-binds it. A default would be fail-closed (an omitted flag reads STARTING, not a false IDLE) and is therefore *permissible*, but with exactly two callers it would only hide the one that forgot — require it. The branch goes **after** `shouldRun && lastInitFailed` (`core/NodeState.kt:47`), not before `startupInProgress` (`:46`), **and it requires `listenersUp`**: IDLE promises "reachable, will warm on the next request", and #327's argument for LIVE ("`ready` alone is not LIVE — only `listenersUp` answers *can anyone reach this node*") applies verbatim. Resulting order, verified against the shipped function:

  | # | Condition | State | Why this slot |
  |---|---|---|---|
  | 1 | `ready && listenersUp && thermal >= 3` | `HOT` | unchanged (#327 added `listenersUp`) |
  | 2 | `ready && listenersUp` | `LIVE` | unchanged — a ready, reachable engine is LIVE even if `wasIdleUnloaded` is stale |
  | 3 | `startupInProgress` | `STARTING` | unchanged — an active reload is starting, not idle |
  | 4 | `shouldRun && lastInitFailed` | `ERROR` | **must stay above IDLE.** A node that unloaded and then failed every reload is broken, and IDLE reads as healthy |
  | 5 | `shouldRun && listenersUp && wasIdleUnloaded` | **`IDLE`** | new — reachable *and* gracefully unloaded; without `listenersUp` it falls to 6 |
  | 6 | `shouldRun` | `STARTING` | unchanged |
  | 7 | else | `OFF` | unchanged |

  **(b) Clear `wasIdleUnloaded` on BOTH outcomes of a real init attempt, and record the failure.** Today it sits at `RelaisEngine.kt:370`, **after** `buildResidentEngine` (`:366`) inside `synchronized(lock)` — so it is reached **only on success**. A throw at the `require(File(modelPath).exists())` guard (`:357`) or inside `buildResidentEngine` skips it, and the flag stays true: with (a) alone, a node whose reloads keep failing would report `"state":"IDLE"` **forever** while actually broken. And rule 4 cannot rescue it: `lastInitFailed` has exactly **two writers, both in `RelaisNodeService`'s init thread** (`:270` clears, `:328` sets) — a request-driven reload that fails (`generate` → `ensureInitialized` at `:658`, or `ensureInitializedInBackground` whose `catch` at `:393-394` only logs) **never sets it**. On that path rule 4 is not belt-and-braces; it is inert.

  **Do NOT do what the 2026-09-07 text said — move the clear to the start of the attempt.** During a *synchronous* reload from `generate` (`:658`, inside `lock`, no `beginStartup()` — that path cannot call it without a nesting counter in `RelaisLivenessPublisher`, because the service's post-init sequence at `:318-322` depends on `startupInProgress` staying true until `refreshListenerState()`), `wasIdleUnloaded=true` is the **only** thing that keeps `RelaisWatchdog` (`:135`) from seeing `!ready && !startupInProgress && !wasIdleUnloaded`, bumping its backoff step and dispatching `RelaisNodeService.start()` → `shouldDispatchStartup(!ready…)=true` → a second `relais-init` thread that blocks on the same `lock`, plus a *"Provisioning model…"* notification flicker. A 20–40 s reload straddles the ~60 s heartbeat in roughly a third to two-thirds of cases; the plan would have shipped a spurious restart on most idle wake-ups.

  Instead, wrap the real-init branch from the `require` through the flag writes (`:357-370`) in `try`/`catch`: on throw, `lastInitFailed = true` **then** `wasIdleUnloaded = false` (that order — see the torn-read note below), rethrow; on success (as today) `lastInitFailed = false` then `wasIdleUnloaded = false`. The task-1 histogram measurement shares this block (success branch only). Consequences, each pinned by a test or the probe: (i) IDLE cannot stick through a failing reload — the failure sets `lastInitFailed`, rule 4 fires, and `ERROR` is **immediate**, not a 60 s watchdog detour; (ii) the watchdog's `wasIdleUnloaded` early-return (`:135-146`) stops shielding a broken node — its next tick restarts through the service path, which is the existing recovery; (iii) clearing `lastInitFailed` on success is load-bearing, not tidy: without it one transient reload failure followed by a request-driven success leaves a stale `true` that turns the **next** idle unload into `ERROR` instead of `IDLE`; (iv) during a synchronous reload the state reads `IDLE` for the duration (it *is* warming on request) — document that in the KDoc rather than fight it. KDoc edits: `wasIdleUnloaded` (`:256-266`) — *"cleared by `ensureInitialized` when the next real init attempt completes, success or failure"*; `lastInitFailed` (`:281-285`) — gains `ensureInitialized` as a second writer. **Probe, not JVM:** `IdleUnloadProbe` (task 5) calls `RelaisEngine.ensureInitialized(ctx, modelPath = "/nonexistent")` after an unload, expects the throw, and asserts `lastInitFailed && !wasIdleUnloaded` and `computeNodeState(...) == ERROR` in the same breath — deterministic, no file games, no watchdog wait.

  **(c) The two non-`when` consumer sites.** `RelaisWidget.kt:95` is `val canRun = nodeState == NodeState.LIVE && …` — an `==`, **not** an exhaustive `when`, so adding an enum constant does **not** break the build here; it silently leaves the RUN button dead on an idle-unloaded node, which is the exact node a user most wants to poke. Decide explicitly: **`canRun` should include IDLE** (a tap warms the engine, which is the whole point of wake-on-request). `tileAction` (`TilePresentation.kt:77-81`) is a third exhaustive `when` whose KDoc guarantees *"RUN_PROMPT is returned ONLY when `ready` is true … so a tap can never cold-start the engine"* — IDLE is by definition not-ready, so **map `IDLE -> TileAction.START`**, which warms the node without violating the documented no-cold-start-from-RUN_PROMPT invariant. Do not quietly widen the RUN_PROMPT branch.

  **(d) The exhaustive `when`s that *will* fail the build**, and are therefore the easy ones: `tilePresentation` (`TilePresentation.kt:51-57`) and `StatusLine` (`RelaisWidget.kt:116-122`). In `handleHealth` (`RelaisHttpServer.kt:948-954`), add `.put("state", <computed>.name)` — read `RelaisLivenessState.snapshot` **once** and pass its two fields, the way `handleDashboard` (`:967-971`) and `RelaisNodeController.state` (`:33-41`) already do. That is a few characters in a 2809-line file `CLAUDE.md` now says to extract from rather than append to; it is not the change that triggers an extraction, and the PR should say so.

  **(e) The dashboard — the non-`when` site feature-09 added.** `assembleDashboardStatus` (`RelaisDashboard.kt:105-139`) derives its own `statusLabel` from `engineReady`/`listenersUp`/`startupInProgress` and renders `OFFLINE` for an idle node — the exact conflation gap 3 describes, on the surface an operator actually looks at. Add `wasIdleUnloaded: Boolean = false` as a trailing parameter (defaulted **because** the omission fails closed — `OFFLINE`, never a false `IDLE` — and because `RelaisDashboardTest.kt` calls it 12× with named args) and an `IDLE` label in the same precedence as (a): `LIVE` → `STARTING` → `listenersUp && wasIdleUnloaded → IDLE` → `OFFLINE`. In `handleDashboard` read `RelaisEngine.wasIdleUnloaded` once into a local beside `liveness` (`:967-971`), per the SINGLE-READ rule written there. `dotColor` (`:264-268`) keys on the label — give `IDLE` the `STARTING` treatment (dimmed amber, no pulse); do not invent a colour, `DESIGN.md` has none for it. Amend the `:132` comment that claims the "same predicate as `computeNodeState`" to say *same LIVE predicate; IDLE mirrors slot 5* — it is already only partly true and this makes it honestly partial rather than silently wrong.
- **MIRROR:** HANDLER_PATTERN; the deliberate precedence comment already in `computeNodeState`'s KDoc (`core/NodeState.kt:23-35`), which this task extends rather than contradicts.
- **WHY `wasIdleUnloaded` STAYS OUTSIDE THE `RelaisLiveness` SNAPSHOT** (a reviewer will ask — #322/#327 exist to kill torn reads across separately-read volatiles): it is written only under the engine `lock`, in a fixed program order relative to `isReady` and `lastInitFailed` — unload: `shutdown()` then `= true` (`:1050-1051`); reload **success**: `engine = …` (ready→true) … `lastInitFailed = false` then `= false`; reload **failure**: `lastInitFailed = true` **then** `= false` — that order is load-bearing: a reader that observes the cleared flag has, by the volatile happens-before, already observed the failure, so a failing node can read stale-`IDLE` or `ERROR` but never `STARTING`. Enumerate the tears against the precedence table: `ready=true` (stale) + `wasIdleUnloaded=true` → slot 2 **LIVE**; `ready=false` + `wasIdleUnloaded=false` (stale, mid-unload) → slot 6 **STARTING**; `ready=false` (stale, mid-reload) + `wasIdleUnloaded=true` → slot 5 **IDLE** (true a moment ago, LIVE a moment later); failure with stale `wasIdleUnloaded=true` → `lastInitFailed` decides — slot 4 **ERROR** if seen, else a momentary IDLE that the next read corrects. Every interleaving lands on a state that under-reports or briefly lags health; none invents a healthy node, because IDLE is the *lowest* non-OFF slot and never beats ERROR. That is why it can be a seventh argument instead of a third snapshot field — and the argument must be written into the parameter's KDoc, not left here.
- **IMPORTS:** `cc.grepon.relais.core.computeNodeState` and `NodeState` in `RelaisHttpServer.kt` if not already present.
- **GOTCHA:** `/health` is an **unauthenticated route** (one of two since #318 added `/ca.crt` — `RelaisHttpGate.isHealthPath`/`isCaCertPath`, `RelaisHttpServer.kt:2147-2149`). Whatever goes in is public: a coarse enum name only — no model id, no path, no client detail. Separately, `assembleDashboardStatus` (defined `RelaisDashboard.kt:105`, called `RelaisHttpServer.kt:972-996`) is the **third** health derivation. feature-09 has **landed** (#323, #332), so it is settled, not moving — and it is now in scope: see 4(e) above.
- **VALIDATE:** `NodeStateTest` truth-table rows for slots 2, 4 and 5 above, plus `listenersUp=false && wasIdleUnloaded=true → STARTING` (the guard in slot 5); `RelaisDashboardTest` rows for `IDLE` and for `LIVE`-beats-stale-`wasIdleUnloaded`; `curl --cacert ca.crt https://<ip>:8443/health` shows `"state":"IDLE"` after an idle window, and `"state":"ERROR"` **on the very next request** after a reload that fails — not after the watchdog's next tick.

### Task 5 — `IdleUnloadProbe.kt` (on-device)

- **ACTION:** Measure what no JVM test can.
- **IMPLEMENT:** New `androidTest` probe. (a) set a 1-minute TTL, serve one request, idle past it, assert `isReady` goes false and `relais_engine_unloads_total` increments; (b) **measure the reload** — issue a request and log wall-clock time to first token (the number task 6 depends on, which does not exist today); **and assert that a second `ensureInitialized` call records no second load observation** — the property task 1 cannot test on the JVM; (c) assert the watchdog does **not** restart the node during the idle window (`RelaisWatchdog.kt:135-146`) **nor during a synchronous reload** — read `RelaisWatchdog`'s persisted step before and after a wake-up and assert it did not bump (this is the regression test for the 4(b) design, and it is the only place it can live); (c′) **force a reload failure**: after an unload, call `ensureInitialized(ctx, modelPath = "/nonexistent")`, expect the throw, assert `lastInitFailed && !wasIdleUnloaded`, and that `/health` reads `ERROR` on the next request; then let the watchdog recover it and assert `LIVE`; (d) repeat the unload/reload cycle ~5 times, checking for a native leak across cycles and exercising the multiplied-first-inference path.
- **MIRROR:** PROBE_STRUCTURE — class KDoc as runbook, `@RunWith(AndroidJUnit4::class)`, `assumeTrue` for a staged model, a `Relais*` logcat tag.
- **IMPORTS:** `androidx.test.ext.junit.runners.AndroidJUnit4`, `androidx.test.platform.app.InstrumentationRegistry`, `org.junit.Assume.assumeTrue`, `android.util.Log`.
- **GOTCHA:** Probes are **not** in CI and need physical hardware — but CI *compiles* them (`:app:compileFullOpenDebugAndroidTestKotlin`, #285), so a probe that no longer builds is caught; one that no longer *passes* is not. A keyguard/asleep device fakes mass failures — unlock first. Run destructive cycles on the spare Pixel 10 (`rango`), not the live node.
- **VALIDATE:** `./gradlew :app:compileFullOpenDebugAndroidTestKotlin` compiles it; the `adb` line in its own header runs it.

### Task 6 — Bounded hold for chat/generate (**gated on task 5**)

- **ACTION:** Decide, from the measured reload time, whether the synchronous hold needs a deadline.
- **IMPLEMENT:** If measurement shows reload materially exceeds the ~23 s spike figure: add a load deadline in the chat/generate path. Past the deadline, return the canonical 503 + `Retry-After` reusing `shedIfHot`'s shape verbatim (`RelaisHttpServer.kt:739-751`), with the load continuing in the background via `ensureInitializedInBackground` so the retry lands warm. Otherwise, document the hold as deliberate and change nothing.
- **MIRROR:** ERROR_HANDLING; the #180 swap path already does exactly this with `Retry-After: 25` (`RelaisHttpServer.kt:1438`).
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
| `stepper decrement stops at the minimum and never reaches the disabled sentinel` (`RelaisIdleTtlConfigTest.kt` — Robolectric, it round-trips through `RelaisConfig`) | 5 → `–` | `IDLE_TTL_MIN_MINUTES`, **not** 0 | Yes — `sanitizeIdleTtlMinutes` (`RelaisConfig.kt:480-482`) turns 0 into "disabled" |
| `unload counter increments in the prometheus render` | delta across one `recordEngineUnload()` | `+1` | Yes — must be a **delta**; `resetIncrementsForTest` doesn't clear it |
| `unload counter increments in the json render` | same | `+1` | Both renders, not just one |
| `metrics render carries no path or key` | full render | no `/storage`, no key substring | Mirrors the leak posture (`RelaisMetrics.kt:37`) |
| `idle-unloaded node reads IDLE not STARTING` | `computeNodeState(shouldRun=true, ready=false, startupInProgress=false, wasIdleUnloaded=true, lastInitFailed=false)` | `NodeState.IDLE` | Yes — **highest false-pass risk**: the existing table may already return a plausible value |
| `failed init still reads ERROR while idle-unloaded` | same but `lastInitFailed=true` | `NodeState.ERROR` | Yes — idle must never mask a real failure |
| `a ready engine reads LIVE even if wasIdleUnloaded is stale` | `ready=true, wasIdleUnloaded=true` | `NodeState.LIVE` | Yes — mirrors the existing stale-`lastInitFailed` rule |
| `load duration histogram renders bucket, sum and count` | `recordEngineLoad(1.5)` fed directly | `_bucket`/`_sum`/`_count` lines all move | Yes — a name grep passes on a declaration that renders nothing |
| `resetIncrementsForTest clears the load histogram` | observe, reset, render | all three back to zero | Yes — omitting the reset entry leaks into every later test |
| ~~`load duration is recorded only for a real init`~~ | — | — | **Not JVM-testable.** `ensureInitialized` needs a real model file (`:357`) and the native AAR (`:366`). Moved to `IdleUnloadProbe.kt` (task 5) |
| `idle-unloaded node whose reload keeps failing reads ERROR, not IDLE` | `ready=false, startupInProgress=false, lastInitFailed=true, wasIdleUnloaded=true` | `NodeState.ERROR` | Yes — **the regression the precedence fix exists to prevent**; IDLE reads as healthy on a dashboard |

### Edge Cases Checklist

- [ ] TTL toggled off **while an unload is mid-flight** — the next tick must simply not unload; nothing must double-release.
- [ ] Stepper decremented to the `IDLE_TTL_MIN_MINUTES` floor must **not** silently cross into the disabled sentinel.
- [ ] Widget `canRun` (`RelaisWidget.kt:95`) is an `==`, not a `when` — confirm by hand that the RUN button is **live** on an IDLE node. The compiler will not tell you.
- [ ] `wasIdleUnloaded` must be cleared on **both outcomes** of a real init attempt, and a failed attempt must set `lastInitFailed` — so `IDLE` cannot stick through a failing reload **and** the watchdog stays quiet during a synchronous one. Today's `RelaisEngine.kt:370` sits *after* `buildResidentEngine` and is reached only on success — task 4(b) rewrites the branch (not "moves the line": see why there). Verify in the probe by calling `ensureInitialized` with a nonexistent path after an unload and confirming `/health` reads `ERROR`, not `IDLE`, on the next request.
- [ ] Idle gauge on a node that has **never served** — `lastActivityAtMs` is initialized at construction (`:254`), so a naive gauge reports **process uptime**, which is large and meaningless. Emit `-1` as a sentinel, or omit the series until first activity; do not ship the uptime reading.
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
- [ ] `/health` **and the dashboard** distinguish idle-unloaded from crashed **without changing the meaning of `ready`** and **without adding a fourth health derivation** (the dashboard's existing third one is extended, not duplicated).
- [ ] An idle-unloaded node whose reload **fails** reads `ERROR`, not `IDLE`, **on the next request** (not a watchdog tick later) — verified both in `NodeStateTest` and on device by the probe's forced-failure step; and a *successful* synchronous reload does **not** bump the watchdog step. (Gap 6, the reload-failure breaker, is explicitly **out of scope**: see *Notes → Cut after review*. The PR must say so rather than implying it was closed.)
- [ ] The QS tile and the widget are both usable on an IDLE node: `tileAction` returns `START` and `RelaisWidget.kt:95` `canRun` includes IDLE — the latter checked **by hand**, since it is an `==` the compiler will not flag.
- [ ] The reload time is **measured on device** and recorded in the PR; task 6 is decided from that number, not from `SPIKE-FINDINGS.md`. The `/v1/audio/transcriptions` path (`RelaisHttpServer.kt:845-852`) is decided in the same breath — it is the primary engine, not a secondary one.
- [ ] Every new test **failed before it passed** — mutation-verified, not merely green.
- [ ] Scope cuts are written into the PR: secondary models have no TTL. (The stale-TXT bug was #313, fixed by #332 — nothing to file.)
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
| Regressing #178's concurrency argument near `RelaisEngine.kt:645-659` / `:1041-1054` | Medium | **Critical** — mid-inference unload or use-after-close | Change nothing there beyond the metric call; the on-device probe, not the unit suite, is the gate. This repo has twice had every test layer green while the assembled thing was broken |
| `NodeState.IDLE` has a wider blast radius than it looks (tile, widget, truth table, dashboard) | High | Medium | The exhaustive `when`s surface every site at compile time — that is the argument for doing it properly rather than special-casing `/health`. The dashboard (`assembleDashboardStatus`, feature-09, **merged**) is a non-`when` site and is named explicitly in task 4(e) |
| ~~The persisted reload breaker wedges a node permanently~~ | — | — | **Task cut after review** — the breaker could not detect the failure it targeted. See *Notes → Cut after review*. Gap 6 remains open |
| **`IDLE` masks a broken node.** A node that unloads and then fails every reload reports a state that reads as healthy | **High if the precedence is got wrong** | **High** — a monitoring regression, not a cosmetic one | Two defenses in task 4 that only work together: `lastInitFailed` is evaluated **above** `IDLE`, and a failing reload now **sets** `lastInitFailed` and clears `wasIdleUnloaded` (before 2026-09-20 no request-driven reload wrote `lastInitFailed` at all, so the precedence rule was inert on exactly this path). Truth-table tests plus the probe's forced-failure check |
| **The 09-07 fix for the row above would have caused a watchdog race.** "Clear `wasIdleUnloaded` at the start of the attempt" removes the only signal that keeps `RelaisWatchdog.kt:135` quiet during a *synchronous* reload (`generate` → `ensureInitialized`, no `beginStartup()`), so most idle wake-ups would have dispatched a spurious service restart | Certain, had it shipped | Medium — notification churn, a second init thread contending for `lock`, backoff bumps | Found by reading the watchdog before writing the fix (2026-09-20). Task 4(b) clears on **completion** of the attempt instead; the probe's 5-cycle loop asserts the watchdog step never bumps across a normal reload |
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

- `feature-19-power-energy-metrics` (revised) adds ten series to `RelaisMetrics.kt`: `relais_battery_{current_now_milliamps,voltage_volts,temperature_celsius,charging,level_ratio}`, `relais_power_draw_milliwatts` / `relais_power_measurement_valid`, a `relais_decode_window_energy_milliwatt_hours` **histogram** plus `relais_decode_window_energy_per_1k_tokens_milliwatt_hours`, and the `relais_energy_samples_invalid_total` counter (`feature-19-power-energy-metrics.plan.md:125-136`, `:491`). **No metric-name collision** with this plan's three `relais_engine_*` names — the two prefix families are disjoint — but the insertion points are adjacent, so expect a textual merge conflict, not a semantic one. feature-19 also updates `RelaisEngine.kt` (`:491-492`), though in the **decode lanes** (`:673-685`, `:797`, `:829`), disjoint from this plan's `ensureInitialized` / `releaseIfIdle` edits. ~~feature-19 already owns the fix for the stale `RelaisMetricsLeakTest` reference~~ — that reference is **already fixed on `main`** (`RelaisMetrics.kt:309`); neither plan needs to touch it.
- `feature-19` (`:722`, `:1343`) and `feature-20` (`:686`, `:1080`) **both extend `resetIncrementsForTest` (`RelaisMetrics.kt:577-600`)**, and task 1 must extend it too. (#316 already landed #20's B1-A share of this: the TTFT and decode-start entries at `:589-598`.) Three plans editing one 14-line function is a guaranteed conflict — sequence them or expect to rebase. feature-20 makes no init-timing claim, so task 1's *metric* is unowned even though its reset seam is contested.
- `feature-18-trusted-lan-cert` **merged** (#317 gate extraction, #318 CA/leaf). `handleHealth` is now `:948-954`, same three fields; the route dispatch canonicalises through `RelaisHttpGate.isHealthPath`/`isCaCertPath` (`:2147-2149`) and `GET /ca.crt` is the second open route. The "only unauthenticated route" sentence has been corrected everywhere in this plan (2026-09-20).
- `feature-17-ollama-compat-api` (`:692`) computes `/api/ps` `expires_at` from the idle TTL plus last activity — i.e. it needs the **same `idleSeconds` accessor** task 2 introduces. Coordinate, or the accessor gets written twice with different semantics.
- `feature-09-web-dashboard` **merged** (#323 2026-09-13, #332 2026-09-16) and owns `assembleDashboardStatus` (`RelaisDashboard.kt:105`, now 18 parameters, `RelaisDashboardTest.kt` calls it 12×). It derives `LIVE`/`STARTING`/`OFFLINE` locally (`:135-139`) and does **not** call `computeNodeState`, though its comment at `:132` claims the "same predicate". Task 4(e) extends it with `IDLE`; it does not consolidate the two derivations (see the decision in *Notes*).
- `feature-21-home-assistant` cites this plan's cold start in its 503 troubleshooting row.

## Notes

### Cut after review

**Task 4, the reload-failure circuit breaker, was drafted and then cut.** It is recorded here rather
than silently dropped, because gap 6 in *Problem → Solution* is still open.

Why it could not work as designed: the counter was to be incremented before the load and cleared on a
successful load, both inside `ensureInitialized` (`RelaisEngine.kt:349-372`). But the failure it
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
- **Clear `wasIdleUnloaded` on both outcomes of the init attempt, and set `lastInitFailed` on the failing one** (revised 2026-09-20; the 09-07 decision was "clear at the start"). Today's placement (`RelaisEngine.kt:370`, after `buildResidentEngine`) is reached only on success, which would let `IDLE` stick forever on a node whose reloads keep failing — and rule 4 could not catch it, because nothing on the request-driven reload path writes `lastInitFailed`. Clearing at the *start* was rejected on reading `RelaisWatchdog.kt:135`: during a synchronous in-lock reload the flag is what keeps the watchdog from dispatching a spurious restart. Full argument in task 4(b).
- **Copy `Stepper` into `RelaisConfigureActivity`** rather than promoting it to a shared UI module — each screen keeping its own private row composables is the observed convention, and one shared-module refactor is out of scope for this plan.

**Alternatives considered and rejected**

- *A `SharedPreferences` change listener* — rejected; none exists in `main/`, and the 60 s ticker already re-reads the pref (`RelaisNodeService.kt:187`). Pure cost.
- *A boolean `idle: true` on `/health`* — rejected; it would be the fourth parallel health derivation and would leave the tile and widget still wrong.
- *Making `lastActivityAtMs` public* — rejected in favor of a read-only `idleSeconds` accessor, keeping the write path private.
- *Also unloading the embedder on idle* — deferred. It would make the memory claim honest, but the embedder has no unload path at all and its reload cost is unmeasured.

**Open questions for the user**

1. ~~**Task 4 vs the in-flight feature-09 dashboard rework**~~ **RESOLVED by events (2026-09-20):** feature-09 (#323, #332) and feature-18 (#318) both merged before this plan starts. `assembleDashboardStatus` is settled and this plan extends it (task 4(e)); `handleHealth` is at `:948-954` unchanged in shape.
2. ~~Stepper ladder and the 1-minute floor.~~ **DECIDED (JD, 2026-09-07): non-linear ladder 1 / 5 / 15
   / 30 / 60 minutes**, keeping the existing 1-minute floor (useful for verifying idle-unload fires
   without a long wait during testing/battery-constrained use). See Task 3's IMPLEMENT below for the
   mechanism.
3. **`/v1/audio/transcriptions` — bounded-hold or keep 503?** (carried from the 09-07 handoff's *still open* list; needs an on-device number, which task 5 produces. Tasks 1–3 do not wait on it; task 6 does.)
4. **Is gap 6 worth a plan of its own?** The reload-failure breaker was cut (above) because it could not detect the crash-idle-crash loop it targeted. A "loaded but never generated" marker would work, but it is a different feature. Leave gap 6 open, or commission it?

**Findings accepted from review, and the one declined**

Every finding in `critic-22.md` (0 CRITICAL · 3 HIGH · 7 MEDIUM · 4 LOW) was re-verified against
source before being applied. All three HIGHs held and are fixed above. Six of the seven MEDIUMs held
and are fixed; **MEDIUM 4 — the `SharedPreferences` write inside the engine lock — dissolved with the
Task 4 cut** rather than being fixed, since the write it described no longer exists anywhere in the
plan. Note also that no task touches `shouldUnloadIdleEngine` itself, so `RelaisIdleTtlTest.kt`'s
existing breaker-adjacent cases stay cut — but **`RelaisIdleTtl.kt` is back in the Files-to-Change
table** (Task 3, `IDLE_TTL_LADDER`/`nextRung`, per the 2026-09-07 stepper-ladder decision), with its
own new `RelaisIdleTtlLadderTest.kt`. Of the four
LOWs: L1 (four off-by-one citations) fixed — `RelaisConfigureActivity.kt` remember idiom is
`:145-147`, `RelaisWidget.kt` `StatusLine` is `:117-123`, `TilePresentation.kt` `tileAction` is
`:77-81`, `releaseIfIdle`'s KDoc starts at `:1009`. L2 (idle gauge reads process uptime on a
never-served node) fixed on the Edge Cases checklist. L4 (`NodeStateTest` calls positionally while
the controller uses named args) fixed by mandating "append the parameter **last**".

- ~~**Declined: L3 — fixing the stale `RelaisMetricsLeakTest` reference in the `RelaisMetrics.kt:38`
  KDoc.**~~ **Moot (2026-09-20):** already fixed on `main` — `RelaisMetrics.kt:309` names
  `RelaisMetricsIncrementsTest`. Nothing for this plan or feature-19 to do.

One critic item was **already covered** rather than accepted as new: the "a litertlm bump past 0.12.0
can reintroduce a first-inference-only crash, which idle unload re-triggers on every reload" caveat
is in the Risks table and expanded in *The G5 first-inference interaction* below it.

---

## Report

- **File:** `.claude/PRPs/plans/feature-22-idle-unload.plan.md`
- **Complexity:** Large
- **Scope:** 16 files (2 CREATE — probe + `RelaisIdleTtlLadderTest.kt`; 14 UPDATE incl. `RelaisDashboard.kt` + `RelaisDashboardTest.kt`), 7 tasks
- **Key Patterns:** the pure-logic + JVM-test pair (`RelaisIdleTtl.shouldUnloadIdleEngine` `:79-92` ↔ `RelaisIdleTtlTest.kt:19-55`); `RelaisEngine.releaseIfIdle` pre-check/lock/re-check (`:1041-1054`); the 3-step metric idiom (`AtomicLong` `:46` → `record…()` `:159` → Prometheus `line(...)` `:323-325` + JSON `:452`); `ToggleRow` (`RelaisConfigureActivity.kt:344-358`) and the `remember { mutableStateOf(RelaisConfig.x(ctx)) }` idiom (`:142-148`)
- **External Research:** Prometheus metric-naming conventions (`_total` counters vs `_seconds` gauges); Android foreground-service lifetime under Doze; the LiteRT-LM native API inventory (`docs/litertlm-native-api.md`) for what reload actually costs
- **Top Risk:** `NodeState.IDLE` masking a broken node. A node that unloads and then fails every reload would report a state that reads as **healthy** on every dashboard — a monitoring regression, not a cosmetic one. Two defenses that only work together (precedence below `lastInitFailed`; clearing `wasIdleUnloaded` **and setting `lastInitFailed`** when a reload fails — the request-driven reload path never wrote `lastInitFailed` before, so the precedence rule alone was inert there) plus truth-table and on-device checks. The standing second risk is unchanged: `RelaisEngine`'s init/release concurrency near `:645-659`/`:1041-1054`, where the on-device probe, not the JVM suite, is the gate
- **Confidence Score:** 6/10 (2026-09-20: up from 5 — the tree reconciliation removed the two sequencing unknowns (feature-09/-18 both merged) and replaced a fix that would have raced the watchdog with one that was read off the watchdog; still gated on an unmeasured reload time. Earlier: revised down from 6 after review. The gap analysis and its line-level anchors held up under independent verification, but of eight drafted tasks one was **cut outright** as undetectable, one had inverted state precedence that would have shipped a healthy-looking broken node, and one was sized as a one-liner when it is a full Prometheus histogram. Three surface decisions — the stepper ladder, the `/v1/audio/transcriptions` classification, and sequencing against feature-09/-18 on `handleHealth` — are named but not settled)

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
- **Estimated Files:** 35 changed — 2 CREATE (`IdleUnloadProbe.kt`, `RelaisIdleTtlLadderTest.kt`) + 33 UPDATE (21 under `main/`, 11 JVM tests, 1 doc). Grew from 17 in the 2026-09-20 review round: Task 4 turned out to be a state-model change with **six** reader surfaces, not a `/health` field — see the PR-split question in *Notes*, plus `triage/TriageControlActivity.kt` read-only as the `Stepper` source. (Cutting the reload breaker removed `RelaisIdleTtlTest.kt`'s breaker cases, but **`RelaisIdleTtl.kt` returns to the list**: open question 2 was decided as a non-linear ladder (1/5/15/30/60), which adds `IDLE_TTL_LADDER` + `nextRung` to that file — Task 3 — plus its own test file.)

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
| **Review round on rev 1** (same day) | rev 1's Task 4(b) "clear on completion"; three health derivations; tile `START`; widget `canRun` | `critic` + `codex`: 20 findings, all verified, all applied — publisher nests (`RelaisLivenessState.kt:30`); **six** derivations incl. the in-app panel's stall detector (`RelaisShellViewModel.kt:129-130`) and `assembleExperimentsStatus`; `START` bounces listeners (`RelaisNodeService.kt:299-303`); the widget tap is gated at `WidgetActions.kt:44` | **rev 2** — Task 4 rewritten, 16 files added; record in *Notes* |

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
│ MODEL          gemma-4-e2b-it     ▸  │
│ HF TOKEN                    ••••••   │
│ ──────────────────────────────────── │
│ POWER                                │
│ AUTO-START ON BOOT              on   │  ← #326, shipped
│ IDLE UNLOAD                     on   │  ← ToggleRow (writes 0 when off)
│ IDLE AFTER        –    15 min    +   │  ← Stepper pair, ladder 1/5/15/30/60, hidden when off
│ ──────────────────────────────────── │
│ INTEGRATIONS                         │
│ SHARE TARGET                    on   │
│ NFC WORKFLOWS                  off   │
└──────────────────────────────────────┘
```

### Interaction Changes

| Touchpoint | Before | After | Notes |
|---|---|---|---|
| Configure screen | No idle-unload control | `IDLE UNLOAD` toggle + `IDLE AFTER` stepper | DESIGN.md: label-left/value-right, hardcoded UPPERCASE monospace, no Material `Switch` anywhere in Relais screens |
| Turning the toggle **off** | impossible | persists `IDLE_TTL_DISABLED_MINUTES` (0) → pre-#178 always-resident | Takes effect within one 60 s tick; `checkIdleUnload` re-reads the pref every tick (`RelaisNodeService.kt:187`) |
| Turning it back **on** | impossible | restores the last non-zero value, else `IDLE_TTL_DEFAULT_MINUTES` | Needs a second pref key to remember the prior value |
| `GET /health` while idle | `{"status":"ok","ready":false,...}` — reads as an outage | adds `"state":"IDLE"`; `ready` unchanged | `/health` is one of the two unauthenticated routes (with `/ca.crt`, #318 — `RelaisHttpServer.kt:2147-2149`) — stays coarse, leaks nothing |
| QS tile / widget while idle | "○ starting…" (slot 6 — `shouldRun -> STARTING`; never "off" while `shouldRun`) | an IDLE presentation; tile tap **warms** (`TileAction.WARM`), widget RUN warms then runs | `TilePresentation.kt:51-57`, `RelaisWidget.kt:116-122` are exhaustive `when`s — the compiler surfaces every site; the tap handlers (`RelaisTileService.kt:45-48`, `WidgetActions.kt:44-49`) are not, and are named in task 4(c) |
| In-app control panel while idle | **"node not running · press START"** after ~3 s (`RelaisShellViewModel.kt:129-130` stall detector), MODEL row locked "while starting" (`RelaisConfigureActivity.kt:135`) | `IDLE` status, "idle · engine released — wakes on the next request", endpoints shown; MODEL row **stays locked** with an idle-specific caption (switch from the dashboard) | Task 4(f). Found 2026-09-20; the worst of the six surfaces because it is the one the operator is holding. Unlocking the row would ship A's weights labelled B — codex C1 |
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
| `.../RelaisLivenessState.kt` | UPDATE | **Added 2026-09-20 (review round).** `RelaisLiveness` gains `idleUnloaded`; `beginStartup(clearIdleUnloaded: Boolean = false)` clears it atomically **only when asked** (the swap thread's bail-outs must not); new `publishIdleUnloaded()`. The nesting counter (`activeStartupOperations`, `:30`) already exists — nothing to add there |
| `.../RelaisEngine.kt` | UPDATE | `ensureInitialized` (`:349-372`) publishes `beginStartup(clearIdleUnloaded = true)`/`endStartup` around the real-init branch, clears `lastInitFailed` at attempt start, sets `LOADING_ENGINE`, catches `Throwable`; `ensureInitializedInBackground` (`:386-400`) publishes startup on the caller and catches `Throwable`; `shutdown()` (`:995-1007`) publishes `idleUnloaded=false`; new `noteActivity()` for Task 6; `wasIdleUnloaded` (`:266`) becomes a delegating read of the snapshot; `releaseIfIdle` (`:1050-1051`) publishes `idleUnloaded=true` and calls `recordEngineUnload()`; `lastActivityAtMs` (`:254`) → `0L` sentinel + nullable `idleSeconds`; load-duration measurement in the same `try` |
| `.../RelaisMetrics.kt` | UPDATE | Two counters/gauges × the 3-step pattern **plus one real histogram** (bounds array, lock, `_bucket`/`_sum`/`_count` render, a `resetIncrementsForTest` entry, **and** a `_p50_seconds` JSON quantile) |
| `.../RelaisConfig.kt` | UPDATE | **One** new key: the remembered last non-zero TTL |
| `.../RelaisIdleTtl.kt` | UPDATE | **Decided 2026-09-07:** `IDLE_TTL_LADDER = intArrayOf(1,5,15,30,60)` + pure `nextRung()` (strictly-greater / strictly-smaller, see Task 3) for Task 3's stepper |
| `.../RelaisConfigureActivity.kt` | UPDATE | `IDLE UNLOAD` `ToggleRow` + `IDLE AFTER` `Stepper` pair in the `POWER` section (`:233`); the `:127` poll also reads `snapshot.idleUnloaded` so the MODEL-row caption (`:176-178`) can say *"model locked while engine released · switch from the dashboard"*. `nodeBusy` (`:135`) is **unchanged** — the row stays locked while idle (codex C1) |
| `.../triage/TriageControlActivity.kt` | — (read only) | Source of the `Stepper` pattern (`:358-364`); **do not modify** |
| `.../core/NodeState.kt` | UPDATE | Add `NodeState.IDLE` and its precedence rule in `computeNodeState` (7th parameter, `listenersUp`-guarded) |
| `.../core/RelaisNodeController.kt` | UPDATE | **The only production caller** of `computeNodeState` (`:34-41`, **named** args — appending does not break it, but it must be passed or the branch is dead); it already takes the snapshot first (`:33`) |
| `.../RelaisWatchdog.kt` | UPDATE | **Added 2026-09-20.** `:135` shield gains `&& liveness.listenersUp`, mirroring `:123` — task 4(g) |
| `.../RelaisNodeService.kt` | UPDATE | **Added 2026-09-20 (round 2).** `:477` LAN-rebind gate becomes `!isReady && !liveness.idleUnloaded` (an idle node must follow an address change); init thread's `:326` catch widened to `Throwable` |
| `.../DashboardScreen.kt` | UPDATE | **Added 2026-09-20 (round 2).** Exhaustive `when (state.status)` at `:84-88` gains `IDLE -> Amber.copy(alpha = 0.6f)`; `:94` pulse stays LIVE-only |
| `.../RelaisHttpServer.kt` | UPDATE | `handleHealth` (`:948-954`) gains `"state"`; `handleDashboard` (`:967-971`) and `handleExperiments` (`:1008`) pass `liveness.idleUnloaded`; optionally the pre-commit bounded init wait (task 6) ahead of `sse.commitHeader()` (`:1575`). **2809 lines** (`wc -l`, 2026-09-20) — a few characters, not the change that triggers the extraction `CLAUDE.md` now asks for |
| `.../RelaisDashboard.kt` | UPDATE | **Added 2026-09-20 — feature-09 merged.** `assembleDashboardStatus` (`:105-139`) gains a trailing `idleUnloaded: Boolean = false` and an `IDLE` label; `dotColor` (`:264-268`) gets the `STARTING` treatment for it; the `:132` "same predicate" comment is made honest |
| `.../RelaisExperiments.kt` | UPDATE | **Added 2026-09-20.** `assembleExperimentsStatus` (`:40-55`) mirrors the dashboard's IDLE label |
| `.../RelaisControlPanelState.kt` | UPDATE | **Added 2026-09-20 (critic P1 #1).** `NodeStatus.IDLE`; `computeControlPanelState` gains required `idleUnloaded`; IDLE arm **below** `failed` (round 2 — rev 2 said above); `showLocalEndpoint`/`lanEndpointLive` (`:148-149`) include IDLE; `controlPanelDetailLine` (`:169-193`) gets an explicit IDLE row (boolean `when`, compiler-silent) |
| `.../RelaisShellViewModel.kt` | UPDATE | **Added 2026-09-20.** Stall predicate (`:129-130`) excludes `liveness.idleUnloaded`; passes it to `computeControlPanelState`; **`:121`/`:123` swapped** so the snapshot is read before `isReady` |
| `.../tile/TilePresentation.kt` | UPDATE | Exhaustive `when` at `:51-57` (`tilePresentation`) **and** `:77-81` (`tileAction`) must handle `IDLE`; new `TileAction.WARM` (`:60`), `IDLE -> WARM` — not `START`, which bounces the listeners |
| `.../tile/RelaisTileService.kt` | UPDATE | **Added 2026-09-20.** `WARM -> RelaisEngine.ensureInitializedInBackground(applicationContext)` beside `:45-48` — never `this` |
| `.../widget/RelaisWidget.kt` | UPDATE | Exhaustive `when` at `:116-122` (`StatusLine`) must handle `IDLE` — **and `:95` `canRun` is an `==`, not a `when`**, so the compiler will *not* flag it; it must be edited by hand |
| `.../widget/WidgetState.kt` | UPDATE | **Added 2026-09-20 (codex F3 = critic P1 #2).** `shouldRunWidgetPrompt` (`:66`) becomes `(nodeState: NodeState, prompt)` → `RUN` / `WARM_THEN_RUN` / `IGNORE` — keyed on the computed state, not raw flags (round 2) |
| `.../widget/WidgetActions.kt` | UPDATE | **Added 2026-09-20.** `RunPromptAction` (`:44-49`): on `WARM_THEN_RUN`, kick `ensureInitializedInBackground`, write `loading`, enqueue |
| `.../widget/WidgetPromptWorker.kt` | UPDATE | **Added 2026-09-20.** `:81` gate: when `!isReady && (warm || startupInProgress)`, poll `isReady` to `MAX_RELOAD_POLL_ITERATIONS` then run; else the existing error. `warm` arrives in the WorkRequest input, not from a flag the reload has already cleared (round 2) |
| `.../test/java/cc/grepon/relais/RelaisLivenessStateTest.kt` | UPDATE | **Added 2026-09-20.** `beginStartup` clears idle; `publishIdleUnloaded` preserves the other fields; nested begin/end |
| `.../test/java/cc/grepon/relais/RelaisIdleTtlConfigTest.kt` | UPDATE | Toggle round-trip + stepper-floor tests (already Robolectric) |
| `.../test/java/cc/grepon/relais/RelaisIdleTtlLadderTest.kt` | **CREATE** | Pure `nextRung()` coverage: every adjacent transition, floor/ceiling clamps, **10 → 15 up and 10 → 5 down** |
| `.../test/java/cc/grepon/relais/RelaisMetricsIncrementsTest.kt` | UPDATE | Delta-asserting counter tests + the histogram render/reset tests + the never-served gauge omission + the `_p50` JSON field |
| `.../test/java/cc/grepon/relais/NodeStateTest.kt` | UPDATE | Truth-table rows for `IDLE`, `ERROR`-beats-`IDLE`, `listenersUp=false`-beats-`IDLE`. **Its helper calls `computeNodeState` positionally (`:29`)** while `RelaisNodeController.kt:34-41` uses named args — append the new parameter **last** or it silently mis-binds here |
| `.../test/java/cc/grepon/relais/RelaisDashboardTest.kt` | UPDATE | **Added 2026-09-20.** Rows for `IDLE`, `LIVE`-beats-stale-flag, and `listenersUp=false` + flag → `OFFLINE` |
| `.../test/java/cc/grepon/relais/RelaisExperimentsTest.kt` | UPDATE | **Added 2026-09-20.** Same three rows |
| `.../test/java/cc/grepon/relais/RelaisControlPanelStateTest.kt` | UPDATE | **Added 2026-09-20.** IDLE; IDLE-below-failed; endpoint flags on IDLE; the detail-line row |
| `.../test/java/cc/grepon/relais/RelaisShellPollingTest.kt` | UPDATE | **Added 2026-09-20.** An idle node does not accumulate stall ticks |
| `.../test/java/cc/grepon/relais/WidgetStateTest.kt` | UPDATE | **Added 2026-09-20.** `NodeState`-keyed `shouldRunWidgetPrompt`, every enum value |
| `.../test/java/cc/grepon/relais/TilePresentationTest.kt` | UPDATE | **Added 2026-09-20.** `IDLE -> WARM`; `IDLE` presentation |
| `Android/src/app/src/androidTest/java/cc/grepon/relais/IdleUnloadProbe.kt` | **CREATE** | The only place the reload time, the synchronous-reload watchdog property, and cross-cycle leak behavior can be measured |
| `docs/RUNBOOK.md` | UPDATE | An idle-unload row; state that memory reclaim is **primary engine only** (`grep -i reclaim` finds nothing to correct today — there is no wrong claim, only a missing one) |

## NOT Building

- **A redesign of `releaseIfIdle`'s locking.** The pre-check / lock / re-check structure and the `incInFlight`-before-lock ordering are load-bearing and already argued in KDoc. Touch nothing there beyond the metric call.
- **A pref-change listener.** No `OnSharedPreferenceChangeListener` exists in `main/`, and `checkIdleUnload` re-reads the pref every 60 s tick (`RelaisNodeService.kt:187`). Adding one is pure cost.
- **Idle unload for secondary models.** `EmbeddingGemmaEmbedder.warmIfProvisioned` (`RelaisNodeService.kt:287`) loads a *second* model at startup; grepping `embed/`, `imagegen/`, `tts/` finds only `TtsPlayer.release()` — there is no unload path at all. So "idle unload reclaims memory" is **partial** today. Name it in the docs; do not build it here.
- **The mDNS TXT after a model swap** — ~~`RelaisDiscovery.updateModel` has zero callers~~ **FIXED by #332** (2026-09-16): `RelaisEngine.kt:507` calls `updateModel` after a successful swap and the re-register is callback-driven (`RelaisDiscovery.kt:161-175`); #313 is closed. Still nothing for this plan to do: an idle unload does not touch the advertisement — it is registered once, refreshed on swap, and torn down only in `onDestroy`, so it correctly keeps advertising a node that will serve on the next request.
- **Changing the kick-and-503 *secondary-model* paths** (`RelaisHttpServer.kt:560-569` TTS, `:1263` embeddings, `:1300` rerank, `:1345` images). Those are genuinely separate engines and are already consistent with each other.
- ⚠ **`/v1/audio/transcriptions` is NOT one of them — it is the primary engine, on the idle-unload path.** `RelaisHttpServer.kt:845-852` guards on `if (!RelaisEngine.isReady)`, calls `RelaisEngine.ensureInitializedInBackground(context)` and 503s with `Retry-After: 10`, and its own comment names the cause: *"e.g. the engine was idle-TTL-unloaded, #178"* (re-verified 2026-09-20, unchanged). An earlier draft of this plan mis-filed it as a secondary engine. **Task 6 must decide it explicitly**: if chat/generate gain a bounded hold, audio becomes the one primary-engine route that behaves differently for the same cause. Either include it in task 6 or write down why 503 stays right there — do not leave it unstated.
- **Changing the meaning of `ready` in `/health`.** Existing clients and the RUNBOOK depend on it meaning "can serve right now". Idle-unloaded genuinely is not ready.
- **A seventh health derivation.** There are already **six** (`computeNodeState`, `handleHealth`, `assembleDashboardStatus`, `assembleExperimentsStatus`, `computeControlPanelState`, and Configure's `nodeBusy`) — the 09-07 text counted three, and the three it missed are the ones an operator looks at. Task 4 extends each from one snapshot field; it adds none.

## Step-by-Step Tasks

### Task 1 — Instrument `ensureInitialized` duration

- **ACTION:** Wrap the real-init branch of `RelaisEngine.ensureInitialized` in a duration measurement and feed a new `relais_engine_load_duration_seconds` histogram.
- **IMPLEMENT:** Inside `synchronized(lock)` after the `if (isReady) return` re-check (`RelaisEngine.kt:355-356`), capture `val startNs = System.nanoTime()`; at the end of the successful branch call `RelaisMetrics.recordEngineLoad((System.nanoTime() - startNs) / 1e9)`. Measure **only** the branch that actually builds an engine — the early `if (isReady) return` at `:337` must record nothing, or the histogram fills with zeros. **This is a real histogram, not a one-liner.** Budget the same four pieces `relais_time_to_first_token_seconds` (#316) has: (a) its own bounds array + counts `LongArray` (`RelaisMetrics.kt:114-118` — a **seconds** ladder, `0.25…60`; a load histogram wants a coarser one, e.g. `1, 2, 5, 10, 20, 30, 60, 120`), (b) its own lock object, (c) a cumulative `_bucket` render loop plus `_sum` and `_count` lines — mirror `:456-467` verbatim in shape, (d) **an entry in `resetIncrementsForTest` (`:577-600`, the `ttftHistLock` block at `:589-593` is the template)**, or the histogram leaks across every test in the process-global object, and (e) **the JSON piece** — the JSON render carries no `_bucket/_sum/_count` shape for any histogram; its mirror is `ttft_p50_seconds` via `bucketQuantile(0.50, …)` (`:549`, `:563`). Add `engine_load_p50_seconds` the same way, or the acceptance criterion "both renders" is unsatisfiable for this metric (critic P2 #10).
- **MIRROR:** METRICS_PATTERN; and specifically the `relais_time_to_first_token_seconds` histogram — `recordTimeToFirstToken` `:266-273`, bucket index `:285-286`, render `:456-467`, reset `:589-593` (it landed in #316, after this plan was written, and is the seconds-based sibling of what task 1 adds). Not `relais_completion_tokens` (a token-count ladder) and not `recordLatency`'s per-endpoint map.
- **IMPORTS:** none new in `RelaisEngine.kt` (`RelaisMetrics` is already referenced at `:1042`).
- **GOTCHA:** Cold start is currently **absorbed into request latency** — `recordLatency` spans from `reqStartNs` through the lock, so the existing latency histogram already double-counts a reload. Note this in the metric's HELP text; do not "fix" `recordLatency` in this task. **And do not validate with a name grep:** a `grep` for `relais_engine_load_duration_seconds` passes on a declaration that renders nothing. Assert on rendered `_bucket`/`_sum`/`_count` lines with a real observation fed in.
- **NOT JVM-TESTABLE:** the "recorded only for a real init" property **cannot** be a JVM test — `ensureInitialized` runs `require(File(modelPath).exists())` (`:357`) and then `buildResidentEngine` (`:366`), which needs the native AAR. That assertion belongs in `IdleUnloadProbe.kt` (task 5), and the acceptance criteria say so.
- **VALIDATE:** `./gradlew testFullOpenDebugUnitTest` from `Android/src`; a JVM test feeds `recordEngineLoad(1.5)` directly and asserts the rendered `_bucket`/`_sum`/`_count` lines move, then that `resetIncrementsForTest()` zeroes them.

### Task 2 — Unload counter + idle gauge

- **ACTION:** Add `relais_engine_unloads_total` (counter) and `relais_engine_idle_seconds` (gauge).
- **IMPLEMENT:** Declare `private val engineUnloadsTotal = AtomicLong(0)` beside `shedTotal` (`RelaisMetrics.kt:46`); `fun recordEngineUnload() = engineUnloadsTotal.incrementAndGet()` beside `recordShed()` (`:194`); Prometheus block after `relais_queue_rejected_total` (`:402-404`); JSON `.put("engine_unloads_total", …)` after `:557-558`. Call `RelaisMetrics.recordEngineUnload()` in `releaseIfIdle` immediately after `shutdown()` and before `wasIdleUnloaded = true` (`RelaisEngine.kt:1050-1051`) — inside the lock, so it counts actual releases, not attempts. For the gauge, add to `RelaisEngine` a narrow read-only accessor rather than widening the field — **nullable**: `val idleSeconds: Double? get() = lastActivityAtMs.takeIf { it > 0L }?.let { (System.currentTimeMillis() - it) / 1000.0 }`, with `lastActivityAtMs` initialised to `0L` (never served) instead of `System.currentTimeMillis()` (`:254`) — `shouldUnloadIdleEngine` only consults it once `isReady`, and every init sets it (`:369`), so the sentinel is never read for an unload decision. Render it with the queue gauges (`RelaisMetrics.kt:506-508`) and **omit the series while null**; the Edge-Cases row on a never-served node and this sentence now agree (critic P2 #9 — the 09-07 text shipped one without the other). HELP texts, both load-bearing for anyone writing an alert: `relais_engine_idle_seconds` — *"Seconds since the resident engine was last active (a request, or a load). Nonzero on a healthy node; the engine is unloaded only while relais_engine_ready is 0"* — "last served a request" would be false: `:369` sets the clock on every init, so a fresh start with zero requests already exposes the gauge and a warm-up resets it (codex C10); `relais_engine_unloads_total` — *"Idle-TTL evictions of the resident engine (engine slot cleared). Counts a release whose native close() failed as well — see consecutiveCloseFailures"*, because `shutdown()` catches close failures and still runs `finally { engine = null }` (`:995-1007`), so a counter placed after it counts logical evictions, not proven native releases (codex F9).
- **MIRROR:** METRICS_PATTERN (counter + gauge forms).
- **IMPORTS:** `java.util.concurrent.atomic.AtomicLong` is already imported in `RelaisMetrics.kt`.
- **GOTCHA:** `resetIncrementsForTest` (`:577-600`) does **not** clear plain counters — its KDoc says so at `:571-575`. The test must assert a **delta**, not an absolute. Also: label hygiene (`RelaisMetrics.kt:37`) — these are plain numbers, no path/id/key, so no new leak surface.
- **VALIDATE:** JVM tests assert both renders move by exactly 1 per `recordEngineUnload()`.

### Task 3 — Configure-screen controls (the headline gap)

- **ACTION:** Add `IDLE UNLOAD` and `IDLE AFTER` rows to `RelaisConfigureActivity`, **in the `POWER` section** (`:233`, under #326's `AUTO-START ON BOOT` toggle at `:248`) — idle unload is a power/memory control, not an integration. The 09-07 mock placed them beside SHARE/NFC (INTEGRATIONS, `:259`) and predates #326.
- **IMPLEMENT:** Add `KEY_IDLE_TTL_LAST_NONZERO_MINUTES` to `RelaisConfig` plus `idleTtlLastNonZeroMinutes(ctx)` / `setIdleTtlLastNonZeroMinutes(ctx, v)`, mirroring `:414-419`. Add `internal val IDLE_TTL_LADDER = intArrayOf(1, 5, 15, 30, 60)` to `RelaisIdleTtl` (decided by JD, 2026-09-07 — non-linear so it reaches the existing 1-minute floor; see the GOTCHA this replaces). In the composable, `var idleTtl by remember { mutableStateOf(RelaisConfig.idleTtlMinutes(ctx)) }`. `ToggleRow("IDLE UNLOAD", idleTtl > 0) { … }` writes `IDLE_TTL_DISABLED_MINUTES` when turning off (after saving the current value as last-non-zero), and restores the remembered value — falling back to `IDLE_TTL_DEFAULT_MINUTES` — when turning on. Render the `IDLE AFTER` row only when `idleTtl > 0`: a label, a `Stepper("–")`, a `"$idleTtl min"` readout, a `Stepper("+")`. Each tap moves to the adjacent **ladder rung**, not a fixed increment: `fun nextRung(current: Int, ladder: IntArray, up: Boolean): Int` — **up = the first rung strictly greater than `current`, else `current` (no move); down = the last rung strictly smaller than `current`, else `current`.** "Else the last rung" would make `+` on a stored 90 move it *down* to 60 — the one-way trip the ladder KDoc warns about, in the direction the operator least expects (critic P3). On-ladder values step to their neighbours; an off-ladder value (10, from an old config — `sanitizeIdleTtlMinutes` preserves any value in `1..1440`, `RelaisConfig.kt:480-482`) goes **10 → 15** up and **10 → 5** down. The 09-07 wording ("first index whose value is `>= current`, then `+1`") sent 10 up to **30** — skipping 15 — while its own test row said "nearest neighbour"; a test written from either sentence pinned the other one wrong (codex F8 = critic P2 #8). Ladder ceiling: the ladder tops at 60 while `IDLE_TTL_MAX_MINUTES = 24*60` (`RelaisIdleTtl.kt:52`), so 61–1440 are unreachable from the UI and a stored value above 60 is a one-way trip down — not live today (gap 1: no callers), but say so in the ladder's KDoc. `setIdleTtlMinutes` is still called with the resulting rung value, so its existing clamp is a no-op safety net, not the mechanism.
- **MIRROR:** UI_PATTERN (`ToggleRow`, `Stepper`), CONFIG_PATTERN, and the `remember` idiom at `RelaisConfigureActivity.kt:137-149`.
- **IMPORTS:** `Stepper` is `private` in `triage/TriageControlActivity.kt` — **copy the composable into `RelaisConfigureActivity.kt`** (matching how each screen keeps its own private row composables) rather than making it public or introducing a shared UI module. Existing Compose imports in the file already cover `Row`/`Box`/`Text`/`clickable`/`RoundedCornerShape`; add `FontWeight` if absent.
- **GOTCHA:** No Material `Switch` exists anywhere in the Relais screens and strings are hardcoded UPPERCASE monospace (no `strings.xml`) — DESIGN.md is explicit. Do not add either. `nextRung` must be a pure, JVM-testable function (put it in `RelaisIdleTtl.kt`, not inline in the composable) — decrementing off the bottom of the ladder must land on `IDLE_TTL_MIN_MINUTES` (1), never on `IDLE_TTL_DISABLED_MINUTES` (0); the toggle owns disabling, the stepper never does. Incrementing off the top must clamp at 60, not overflow into `IDLE_TTL_MAX_MINUTES` if that differs.
- **VALIDATE:** New pure-function test `RelaisIdleTtlLadderTest` (or added to `RelaisIdleTtlConfigTest`): every adjacent-rung transition in `[1,5,15,30,60]`, decrement-at-1 stays 1, increment-at-60 stays 60, and an off-ladder starting value (e.g. 10, from an old config) moves to the nearest neighbor in the expected direction. Robolectric round-trip in `RelaisIdleTtlConfigTest`; visually confirm against DESIGN.md's label-left/value-right rule on device.

### Task 4 — `NodeState.IDLE` on every surface, and the state model that makes it true

- **ACTION:** Let every health surface tell idle from dead — and there are **six** of them, not the three the 09-07 plan counted: `computeNodeState` (tile, widget, controller), `handleHealth`, `assembleDashboardStatus`, `assembleExperimentsStatus`, `computeControlPanelState` (the in-app panel and `DashboardScreen.kt`), and the Configure screen's `nodeBusy` predicate. Today an idle node reads `STARTING` on the tile/widget, `OFFLINE` on the dashboard and `/experiments`, **"node not running · press START"** in the app (`RelaisShellViewModel.kt:129-130` counts it as a stalled start after 3 polls), and locks the MODEL row with **"model locked while starting"** (`RelaisConfigureActivity.kt:135`). Found in the 2026-09-20 review round (critic P1 #1); none of it was in the plan.
- **IMPLEMENT (seven parts; (a)–(b) are the state model, the rest are its readers). Rev 3 — the state model is on its fourth shape; what changed and why is in *Notes → Review round 3*.**

  **(a) `idleUnloaded` joins the `RelaisLiveness` snapshot; `computeNodeState` gains a seventh parameter.** Add `val idleUnloaded: Boolean = false` to `RelaisLiveness` (`RelaisLivenessState.kt:19-22`; its KDoc says "the two lifecycle facts" — make it three). Publisher changes, all `@Synchronized` like their neighbours: `beginStartup(clearIdleUnloaded: Boolean = false)` — one atomic `copy(startupInProgress = true, idleUnloaded = if (clearIdleUnloaded) false else current.idleUnloaded)` — and a new `publishIdleUnloaded(value: Boolean)`. **Only `ensureInitialized`'s real-init branch passes `clearIdleUnloaded = true`.** Rev 2 cleared it on *every* `beginStartup()`, and the swap thread calls `beginStartup()` at `RelaisEngine.kt:442` before it has resolved a target — a missing file `return@thread`s at `:462`, a `resolveModel` throw lands in the `:508` catch, both reach `finally endStartup()` (`:511`) with **no init attempted**: a healthy idle node would have read STARTING, tripped the stall detector, and been restarted by the watchdog within 60 s because of a failed operator action (codex C4 = critic P2 #3). "Every `beginStartup` is a real init attempt" was false. Two more writers: **`shutdown()` publishes `idleUnloaded = false` under `lock`** and `releaseIfIdle` re-publishes `true` right after it (`:1050-1051`, same lock) — so the flag means exactly *"the last shutdown was an idle release"*, and STOP (`onDestroy:565` → `shutdown()`) clears it. Without that, idle survived STOP, and `RelaisInference.kt:69/:83`'s self-heal — whose KDoc `:41-48` says the flag *"PROVES the foreground service is alive"* — would spawn a multi-GB reload with no FGS behind it after idle → STOP (codex C3 = critic P2 #5; pre-existing for `RelaisInference`, and this plan was about to key the tile and widget on the same flag). `RelaisEngine.wasIdleUnloaded` (`:266`) becomes a read-only delegating property, `val wasIdleUnloaded: Boolean get() = RelaisLivenessState.snapshot.idleUnloaded`, so the five read sites keep compiling and its KDoc points at the snapshot. Why the snapshot and not a seventh separately-read volatile: #322/#327 exist precisely so that lifecycle facts are not combined across separate reads, and four of the six readers already take `RelaisLivenessState.snapshot` **first** (`RelaisNodeController.kt:33`, `handleDashboard` `:967`, `handleExperiments` `:1008`, `RelaisWatchdog.kt:118`) — **not** five: `RelaisShellViewModel.kt:121` reads `isReady` *before* its `:123` snapshot (critic P2 #7; rev 2 listed it as compliant without opening the file — swap those two lines, see (f)), and Configure is Compose-local. Then add `IDLE` to `enum class NodeState` (`core/NodeState.kt:16`) and thread `idleUnloaded` into `computeNodeState` as the **seventh** parameter (#327 made `listenersUp` the third; shipped signature `:36-43`) — **append it last, required, no default**: the production caller `core/RelaisNodeController.kt:34-41` uses named args, the test helper `NodeStateTest.kt:23-29` calls **positionally**, and with exactly two callers a default would only hide the one that forgot. The branch goes **after** `shouldRun && lastInitFailed` (`:47`), not before `startupInProgress` (`:46`), and it **requires `listenersUp`**: IDLE promises "reachable, will warm on the next request", and #327's argument for LIVE applies verbatim.

  | # | Condition | State | Why this slot |
  |---|---|---|---|
  | 1 | `ready && listenersUp && thermal >= 3` | `HOT` | unchanged (#327 added `listenersUp`) |
  | 2 | `ready && listenersUp` | `LIVE` | unchanged — a ready, reachable engine is LIVE even if `idleUnloaded` is stale |
  | 3 | `startupInProgress` | `STARTING` | unchanged — and after 4(b) this is what a **synchronous** reload reads too |
  | 4 | `shouldRun && lastInitFailed` | `ERROR` | **must stay above IDLE.** Under rev 3 the combination `lastInitFailed && idleUnloaded` is unreachable (a failure is only ever written after a `beginStartup(clearIdleUnloaded=true)`) — keep the row as defence in depth; the load-bearing defence is the attempt-start clear in (b) |
  | 5 | `shouldRun && listenersUp && idleUnloaded` | **`IDLE`** | new — reachable *and* gracefully unloaded; without `listenersUp` it falls to 6 |
  | 6 | `shouldRun` | `STARTING` | unchanged |
  | 7 | else | `OFF` | unchanged |

  **(b) `ensureInitialized` publishes its own startup, clears `lastInitFailed` at attempt start, catches `Throwable`.** Today the real-init branch (`RelaisEngine.kt:357-370`) clears `wasIdleUnloaded` at `:370`, **after** `buildResidentEngine` (`:366`) — reached only on success — and `lastInitFailed` has exactly two writers, both in `RelaisNodeService`'s init thread (`:270` clears, `:328` sets), so a request-driven reload that fails (`generate` → `ensureInitialized` at `:658`; `ensureInitializedInBackground` whose `catch` at `:393-394` only logs) leaves the flag `true` and never reaches slot 4: `IDLE` forever, and the watchdog's `:135` shield keeps it that way. The synchronous path at `:658` never publishes `startupInProgress`, so the watchdog (`:128`) and every STARTING-aware reader are blind to it; the publisher already nests (`RelaisLivenessPublisher.activeStartupOperations`, `RelaisLivenessState.kt:30, :40-50`), so publish from the one choke point every reload goes through:

  ```kotlin
  synchronized(lock) {
    if (isReady) return
    RelaisLivenessState.beginStartup(clearIdleUnloaded = true) // BEFORE the try: endStartup() `check`s its pairing (:47);
    lastInitFailed = false                                    // a throw between try-entry and an inner begin would turn the
    RelaisNodeProgress.phase = ProvisionPhase.LOADING_ENGINE  // finally into a second exception masking the first.
    try {                                                     // lastInitFailed: cleared at ATTEMPT START, exactly as the
      require(File(modelPath).exists()) { … }                 // service does at :270 — a retry must not read as failed
      … build, residentModelId/Path, lastActivityAtMs …       // while it loads. Phase: the panel's phase line otherwise says
    } catch (t: Throwable) {                                  // "starting node…" (RelaisControlPanelState.kt:211) for a reload.
      lastInitFailed = true                                   // Throwable, not Exception: a native engine-create surfaces
      throw t                                                 // UnsatisfiedLinkError / OutOfMemoryError (the swap path's own
    } finally {                                               // reason, :490-494), and RAM pressure is why idle-unload exists
      RelaisLivenessState.endStartup()                        // the LAST write of every attempt — see the read-order rule
    }
  }
  ```

  Why *attempt start* and not *success* for `lastInitFailed` (rev 2 said success): `computeNodeState` tolerates a stale `true` during a retry (slot 3 > 4) but `computeControlPanelState` does not — its STARTING-over-failure arm is `:124 ready && startupInProgress`, which needs `ready`, so a request-driven retry after one failure would have shown **OFFLINE + "start failed · check model/token" + START** on the panel for the whole 20–40 s load while every other surface said STARTING (codex C5 = critic P2 #4). `:270`'s own comment is the rule. Nesting arithmetic, verified at every existing pair: service `:272/:347` wraps one inner pair; swap `:442/:511` wraps two (`:482` and the rollback at `:487`); background `:391/:396` wraps one; `:354/:356` return before any `beginStartup`; no path can `endStartup` without its `beginStartup`. Lock order engine-`lock` → publisher monitor is safe (leaf `@Synchronized`), and `listenerLifecycleLock` is never nested with `lock`. **Two callers must widen their catch to `Throwable`** or the rethrown `Error` escapes exactly as `:491-494` warns — *"kill the WHOLE node process"*: `ensureInitializedInBackground` `:393` and the service init thread `RelaisNodeService.kt:326` (the swap path already does). The request path (`generate` on an HTTP worker) is **not** widened here: an `Error` there is pre-existing process death that the watchdog restarts, and the plan's `ERROR`-is-observable promise holds for exceptions everywhere and for errors on the two background paths (codex C6). **`ensureInitializedInBackground` publishes `beginStartup()` synchronously on the caller before `thread {}`**, wrapping thread creation so a throw there runs `endStartup()` and resets the CAS; the thread's `finally` still ends it. Every "kick then wait" caller — the widget worker in (c), Task 6's poll, `ModelSwitch.awaitReload:85` — then sees `startupInProgress` on return, instead of racing the spawned thread's `:391` (critic P1 #1(i)). What this buys, each pinned by a test or the probe: (i) a synchronous reload reads **STARTING** on every surface (slot 3), phase line "loading engine", not IDLE and not "stalled"; (ii) the watchdog stays out of **every** reload for the same reason it stays out of the service's (`:128`) — the 09-07 race and the rev-1 next-retry exposure are closed by one mechanism; `ensureInitializedInBackground:387` stops dispatching a redundant thread behind the lock; `switchLocked` engages; `ModelSwitch.awaitReload` actually waits; (iii) a failed reload sets `lastInitFailed` → slot 4 → `ERROR` **immediately**, and the flag is clear again the moment the next attempt starts; (iv) swap-then-rollback-also-fails, which today leaves `lastInitFailed` untouched and reads STARTING forever, now reads ERROR. KDoc edits: `wasIdleUnloaded` (`:256-266`) → delegating; *"set by `releaseIfIdle` after its `shutdown()`; cleared by every `shutdown()` and by `ensureInitialized`'s real-init branch — true iff the last shutdown was an idle release"*; `lastInitFailed` (`:281-285`) → widened: *set by the service's init thread **or** by `ensureInitialized` when a real init attempt throws; cleared at the start of every attempt*. The one behaviour this widens: a bind-failed node (`:328`, engine resident) whose in-process reload starts clears the flag and reads slot 6 STARTING instead of ERROR until the watchdog restarts it (≤ 60 s; `:270` clears it anyway) — the control panel still says "endpoints down" via `unreachable` (`RelaisControlPanelState.kt:111, :175`). Acceptable; say it in the KDoc.

  **Read-order rule, and the claim that is actually true.** `ready` (`isReady`) and `lastInitFailed` remain separate volatiles outside the snapshot, so the tear surface is three reads (ready, lastInitFailed, snapshot-triple), not one. Every reader takes the liveness snapshot **first**, then the engine flags — four of six do today; (f) fixes the shell VM and Configure — because `endStartup()` is the last write of every attempt: a reader that observes `startupInProgress=false` for an attempt observes every flag write of that attempt. The residual tears are enumerable against the table: `ready=true` (stale) + anything → slot 2 LIVE; `ready=false` + `lastInitFailed` stale + `idleUnloaded=false` → slot 6 STARTING for one poll; `ready=false` + `idleUnloaded` stale-true → slot 5 IDLE for one poll. **The property: no interleaving invents LIVE or HOT; a failing node may lag one poll as STARTING or stale-IDLE, never longer.** Do **not** write a stronger claim (rev 1's "never STARTING" was false for the controller's own argument order, `RelaisNodeController.kt:39` — codex F5 / critic P2 #7); name the property, not the mechanism, in the KDoc. One pre-existing tear stays as-is: the watchdog can read `isReady=false`, let a reload finish, read `idleUnloaded=false`, and `bumpStep` once (`RelaisWatchdog.kt:123`); `start()` then no-ops via `shouldDispatchStartup(ready=true…)=false` and the next healthy tick resets the step. Known, benign, not this plan's.

  **(c) The tile and the widget must *warm*, not restart.** `tileAction` (`TilePresentation.kt:77-81`) — the 09-07 text mapped `IDLE -> START`, but `START` is a full service re-init: `RelaisNodeService.start:595` → `dispatchStartupIfNeeded:264` → `shouldDispatchStartup(ready=false, …)=true` → a `relais-init` thread that blocks on `lock`, then **unconditionally** `stopListeners()` → `RelaisHttpServer.kt:2259 pool.shutdownNow()` → rebind → `RelaisDiscovery.register` (`RelaisNodeService.kt:299-311`). Any in-flight LAN request dies and NSD churns. Add **`TileAction.WARM`** (`:60`) → `RelaisEngine.ensureInitializedInBackground(applicationContext)` in `RelaisTileService.kt:45-48` — `applicationContext`, not `this`: a `TileService` must not be captured by a thread that lives for the load — single-flight (`:388`), publishes startup on the caller (b), no listener bounce — and map `IDLE -> WARM`. `RUN_PROMPT` keeps its KDoc invariant untouched (`:73-74`). The widget: `RelaisWidget.kt:95 canRun` only enables `PromptButtons` (`:100`); the tap lands in `RunPromptAction` (`WidgetActions.kt:44-49`), which gates on `shouldRunWidgetPrompt(RelaisInference.isReady(), …)` and logs *"cold-start guard"*; the worker re-checks at `WidgetPromptWorker.kt:81`. Widening `canRun` alone ships an enabled button whose tap is a no-op (codex F3 = critic P1 #2). Rev 2's handshake was wrong too — the worker was to wait on `!isReady && idleUnloaded`, a flag `beginStartup` has cleared by the time WorkManager runs `doWork` (`:70`), and `awaitReload` exits immediately if it lands before the spawned thread's `beginStartup` (codex C2 = critic P1 #1). Rev 3: the pure decision takes the **computed state**, not raw flags — `shouldRunWidgetPrompt(nodeState: NodeState, prompt: String?)` (`WidgetState.kt:66`, `WidgetStateTest.kt`) returning `RUN` on `LIVE`/`HOT`, `WARM_THEN_RUN` on `IDLE`, `IGNORE` otherwise; `IDLE` already implies `shouldRun && listenersUp`, so a stale tap on a stopped node is `OFF -> IGNORE` (second defence after `shutdown()` clearing the flag). `RunPromptAction` on `WARM_THEN_RUN` calls `ensureInitializedInBackground` (which now publishes startup **before returning**), writes the `loading` state, and enqueues the worker with `warm = true` in its input data; the worker, when `!isReady && (warm || snapshot.startupInProgress)`, polls **`isReady`** (not a flag) at `ModelSwitch.RELOAD_POLL_INTERVAL_MS` up to `MAX_RELOAD_POLL_ITERATIONS` (60 s cap — Task 5's number decides whether that is enough) and runs the prompt if ready, else settles the existing `"node off — open app to start"` error. `StatusLine` IDLE → `"○ idle — tap to warm"` (DESIGN.md: muted, no new colour). `canRun` includes IDLE.

  **(d) `handleHealth` (`RelaisHttpServer.kt:948-954`) gains `"state"`.** Take `RelaisLivenessState.snapshot` once, read `isReady`/`lastInitFailed` after it (the read-order rule), call `computeNodeState`, `.put("state", state.name)`. A few characters in a 2809-line file `CLAUDE.md` now says to extract from rather than append to; not the change that triggers an extraction, and the PR says so.

  **(e) The dashboard and `/experiments`.** `assembleDashboardStatus` (`RelaisDashboard.kt:105-139`) and `assembleExperimentsStatus` (`RelaisExperiments.kt:40-55`, which mirrors it by its own comment) each gain a trailing `idleUnloaded: Boolean = false` — defaulted here **only** because the pure assemblers have 12 and N named-arg test callers and their omission fails closed to `OFFLINE`; the same fail-closed fact does not make 4(a)'s two-caller parameter defaulted, and caller count is the single reason for the difference — and an `IDLE` label in the same precedence as (a): `LIVE` → `STARTING` → `listenersUp && idleUnloaded → IDLE` → `OFFLINE`. `handleDashboard` (`:967-971`) and `handleExperiments` (`:1008`) already take the snapshot once; pass `liveness.idleUnloaded`. `dotColor` (`:264-268`): give `IDLE` the `STARTING` treatment (dimmed amber, no pulse); `DESIGN.md` has no idle colour, do not invent one. Amend the `:132` "same predicate as `computeNodeState`" comment to *same LIVE predicate; IDLE mirrors slot 5*. Model selector on an idle node: `RelaisHttpPages.kt:113` has no readiness gate and the swap thread cold-loads under `beginStartup()` (`:442`), so the node reads STARTING → LIVE — sensible, keep it; `pendingModelId` stays correct because `shutdown()` (`:995-1007`) never clears `residentModelId`. State in the page copy or the KDoc: a **failed** swap from IDLE rolls back by cold-loading the previous model (`:486-489`), so the operator's failed action silently undoes the idle unload; and a swap whose target is not on disk bails at `:462` and leaves the node IDLE (rev 3 — rev 2 would have restarted it).

  **(f) The in-app control panel, `DashboardScreen`, and the Configure screen — the surfaces an operator actually looks at.** `computeControlPanelState` (`RelaisControlPanelState.kt:80-92`) gains `idleUnloaded: Boolean` — **required**, like `listenersUp`, and for the reason its KDoc `:65-71` gives — and `NodeStatus` (`:27`) gains `IDLE`. **The arm goes below `failed`, above `running -> STARTING`**: `failed = running && !reachable && (initFailed || stalledStart || unreachable)` (`:112`); with the stall exclusion below, `failed` on an idle node reduces to `initFailed`, which is exactly slot 4 > 5. (Rev 2 said "above `failed`" in two places while its own test row said below — codex C8 = critic P1 #2; an implementer would have shipped a broken idle node reading IDLE on the panel.) The stall detector `RelaisShellViewModel.kt:129-130` must exclude it: `running && !ready && !liveness.startupInProgress && !liveness.idleUnloaded` — the detector (#217, PR #218) post-dates #178 (PR #203) and its premise *"running with no init in flight means the service died"* (`:125-128`) never learned about idle-unload — **and `snapshotPanelState` must take the snapshot before `isReady`** (`:121`/`:123`, swap them). `controlPanelDetailLine` (`:169-193`) is a `when {}` over **booleans**, not the enum — the compiler will not flag a missing IDLE row and it would fall to `else -> "node stopped · $model"`; add the row explicitly, same class as `RelaisWidget.kt:95`. Copy for the IDLE arm follows the existing `:175/:180` rows' shape — *"idle · engine released — wakes on the next request"* (matches the notification copy `RelaisNodeService.kt:139` already uses), muted, primary action **STOP** (the node is running; a START here would be the listener bounce from (c)). `DashboardScreen.kt:84-88` is an exhaustive `when (state.status)` — `IDLE -> Amber.copy(alpha = 0.6f)`, the STARTING treatment; `:94`'s pulse stays LIVE-only per DESIGN.md; and `RelaisControlPanelState.kt:148-149 showLocalEndpoint`/`lanEndpointLive = status == LIVE` must **include IDLE** — the node's whole promise is "reachable", so hiding LOCAL and muting LAN on it (`DashboardScreen.kt:162-167`) contradicts the label (critic P2 #8). One copy wart to accept: a bind-failed node that idled out and is being restarted by (g) reads `:186 "start failed · check model/token"` because `unreachable` needs `ready` (`:111`) — the model loaded fine, the socket failed; copy only, recovery works. **`RelaisConfigureActivity.kt:135` `nodeBusy = running && !ready` stays as it is — the MODEL row remains locked while idle.** Rev 2 unlocked it; codex C1 showed why that ships A's weights labelled B: `onPickRef` (`:297`) only persists the ref, nothing dispatches a swap, and an idle reload pairs `cachedPathOrDefault` with the new configured id — the exact *"loads the old weights under the new id and serves them with no error anywhere"* `ModelSwitch.applyManualId`'s KDoc (`ModelSwitch.kt:52-58`) describes and #332 fixed for the dashboard. The dashboard's targeted swap is the correct mechanism and already works while idle; routing Configure's pick through it is a follow-up, not this plan. Change only the caption: poll `RelaisLivenessState.snapshot.idleUnloaded` into Compose state in the `:127` loop beside `ready`/`running` (a raw read in `nodeBusy` would not invalidate — codex C9), and show *"model locked while engine released · switch from the dashboard"* when idle, the existing *"model locked while starting"* otherwise. Tests: `RelaisControlPanelStateTest.kt` rows for IDLE, IDLE-below-failed (`initFailed=true` + idle → OFFLINE), endpoint flags on IDLE, the detail-line row; the stall-predicate exclusion in `RelaisShellPollingTest.kt`.

  **(g) The watchdog shield and the LAN rebind gain the conjuncts they always needed.** `RelaisWatchdog.kt:135 if (RelaisEngine.wasIdleUnloaded)` → `if (liveness.idleUnloaded && liveness.listenersUp)`, mirroring `:123`. Without it, a `:8443` bind failure (`RelaisNodeService.kt:343 stopListeners()`, engine deliberately resident `:335-337`) followed by the TTL (no request can arrive, so it *will* idle out; `releaseIfIdle` consults nothing about listeners) leaves a node the watchdog calls "healthy idle" forever while `computeNodeState` says ERROR (codex F2 = critic P1 #3). Recovery traced (critic, round 2): conjunct false → `:150` bump → `start()` → `dispatchStartupIfNeeded` (`listenersUp=false` → dispatch) → `:270` clear → `ensureModel`'s offline fast path for an on-disk model (`RelaisModelProvisioner.kt:244+`) → real reload → `stopListeners` → rebind → LIVE once the port clears; backoff while it does not — and each retry after the TTL re-pays a cold start, because the engine is no longer resident through the failure (Risks). **And `RelaisNodeService.kt:477`** — `if (!RelaisEngine.isReady) { scheduleLanRebindObservation(…); return }` — gates the LAN rebind on the *engine*, which an idle node never satisfies: a phone that roams Wi-Fi during a 15-minute idle window keeps a leaf whose SANs name the old address (`RelaisTls.needsLanReissue` `:250-254`), every CA-verifying client fails the handshake, no request reaches `generate`, nothing wakes the engine, and the watchdog is shielded because the listeners *are* listening — stuck until the operator acts (critic P2 #6). Fix: `if (!RelaisEngine.isReady && !liveness.idleUnloaded)` — the rebind touches listeners, not the engine.

- **MIRROR:** HANDLER_PATTERN; the deliberate precedence comment already in `computeNodeState`'s KDoc (`core/NodeState.kt:23-35`), which this task extends rather than contradicts; `RelaisLivenessStateTest.kt` for the publisher's test shape.
- **IMPORTS:** `cc.grepon.relais.core.computeNodeState` and `NodeState` in `RelaisHttpServer.kt` if not already present; `RelaisNodeProgress`/`ProvisionPhase` are already visible to `RelaisEngine`.
- **GOTCHA:** `/health` is an **unauthenticated route** (one of two since #318 added `/ca.crt` — `RelaisHttpGate.isHealthPath`/`isCaCertPath`, `RelaisHttpServer.kt:2147-2149`). Whatever goes in is public: a coarse enum name only — no model id, no path, no client detail. And: this task is now the state model plus six readers — see the PR-split question in *Notes → Open questions*. Cosmetic, not fixed here: `RelaisInference.complete` during another caller's warm-up reads `wasIdleUnloaded=false, startupInProgress=true` and throws its existing `NodeNotReadyException` (a `startupInProgress` check could say "warming").
- **VALIDATE:** `NodeStateTest` rows for slots 2, 4, 5, `listenersUp=false && idleUnloaded → STARTING`; `RelaisLivenessStateTest` rows: `beginStartup(clearIdleUnloaded = true)` clears it in one snapshot, plain `beginStartup()` preserves it, `publishIdleUnloaded` preserves the other two fields, nested begin/end keeps `startupInProgress` true until the outer end; `RelaisDashboardTest`/`RelaisExperimentsTest` rows for IDLE and LIVE-beats-stale-flag; `RelaisControlPanelStateTest` rows as in (f); `WidgetStateTest` for the `NodeState`-keyed `shouldRunWidgetPrompt` incl. `OFF -> IGNORE` and `STARTING -> IGNORE`; `TilePresentationTest` for `IDLE -> WARM`; `curl --cacert ca.crt https://<ip>:8443/health` shows `"state":"IDLE"` after an idle window, `"state":"STARTING"` **during** a synchronous reload, and `"state":"ERROR"` on the request after a reload that fails.

### Task 5 — `IdleUnloadProbe.kt` (on-device)

- **ACTION:** Measure what no JVM test can, deterministically.
- **IMPLEMENT:** New `androidTest` probe. Use `releaseIfIdle(ttlMs, nowMs)` — it takes `nowMs` (`RelaisEngine.kt:1041`), so no step waits a real minute. (a) serve one request, call `releaseIfIdle` with a synthetic `nowMs` past the TTL, assert `isReady` goes false, `RelaisLivenessState.snapshot.idleUnloaded` is true, and `relais_engine_unloads_total` increments. (b) **measure the reload** — issue a request and log wall-clock time to first token (the number task 6 depends on, which does not exist today); **and assert that a second `ensureInitialized` call records no second load observation** — the property task 1 cannot test on the JVM. (c) **the watchdog during a synchronous reload, deterministically** — a "no bump across five reloads" check is probabilistic against a 60 s alarm scheduled at `elapsedRealtime + intervalFor(step)` (`RelaisWatchdog.kt:54`) and `reset() = setStep(0)` (`:71`) hides a `0 → 1 → 0` round-trip: instead, from a second thread **while** a request's reload holds `lock`, invoke `RelaisWatchdogReceiver().onReceive(ctx, Intent())` directly, then read the persisted step (pref `relais` / `watchdog_fail_step`, `:31-32` — `step()` is private, `:98-99`) and assert it is still 0 and no `relais-init` thread was dispatched (`startupDispatchInFlight` is private, `RelaisNodeService.kt:130` — check `Thread.getAllStackTraces()` for a thread named `relais-init`, or the notification text). Also assert `snapshot.startupInProgress` was true during the reload — the 4(b) property. (c′) **force a reload failure**: after an unload, call `ensureInitialized(ctx, modelPath = "/nonexistent")` — `:354/:356` are bare early returns and `:357 require(File(modelPath).exists())` is the first statement that can throw, nothing mutates state before it — expect the throw, take the snapshot, assert the precondition (`shouldRun`, `!startupInProgress`), then assert `lastInitFailed && !idleUnloaded` and `computeNodeState(...) == ERROR`; assert `/health` reads `ERROR` **inside the watchdog's 60 s window** (its restart via `RelaisNodeService.kt:270` clears the flag — either cancel the alarm for the probe or assert promptly); then let the watchdog recover it and assert `LIVE`. **Reset `lastInitFailed` (or reload) before (d)**, or the cycle loop runs under ERROR. (d) repeat the unload/reload cycle ~5 times, checking for a native leak across cycles and exercising the multiplied-first-inference path.
- **MIRROR:** PROBE_STRUCTURE — class KDoc as runbook, `@RunWith(AndroidJUnit4::class)`, `assumeTrue` for a staged model, a `Relais*` logcat tag.
- **IMPORTS:** `androidx.test.ext.junit.runners.AndroidJUnit4`, `androidx.test.platform.app.InstrumentationRegistry`, `org.junit.Assume.assumeTrue`, `android.util.Log`, `android.content.Intent`.
- **GOTCHA:** Probes are **not** in CI and need physical hardware — but CI *compiles* them (`:app:compileFullOpenDebugAndroidTestKotlin`, #285), so a probe that no longer builds is caught; one that no longer *passes* is not. A keyguard/asleep device fakes mass failures — unlock first. Run destructive cycles on the spare Pixel 10 (`rango`), not the live node.
- **VALIDATE:** `./gradlew :app:compileFullOpenDebugAndroidTestKotlin` compiles it; the `adb` line in its own header runs it.

### Task 6 — Bounded hold for chat/generate (**gated on task 5**)

- **ACTION:** Decide, from the measured reload time, whether the synchronous hold needs a deadline.
- **IMPLEMENT:** If measurement shows reload materially exceeds the ~23 s spike figure: add a **bounded initialization wait *before* the response is committed** — not a deadline inside `generate`. Two constraints the 09-07 text missed (codex F4/F6, critic P3): the streaming path commits the 200 with `sse.commitHeader()` at `RelaisHttpServer.kt:1575` **before** `RelaisEngine.generate` (which is where `ensureInitialized` runs), so nothing after that point can answer 503; and `synchronized(lock)` at `RelaisEngine.kt:647` cannot time out, so the wait must be a pre-`generate` poll, not a timed lock. Shape: if `!isReady` on entry, kick `ensureInitializedInBackground` (`:386-400` — single-flight, and after 4(b) it publishes startup **on the caller before returning**, so the poll cannot race the spawned thread) and poll `isReady` up to the deadline (a blocking sibling of `ModelSwitch.awaitReload`, `ModelSwitch.kt:83-90`); on success, **call a new `RelaisEngine.noteActivity()` (touches `lastActivityAtMs`) before `sse.commitHeader()`** — the readiness observation is otherwise unreserved: `incInFlight()` happens only inside `generate` (`RelaisEngine.kt:626`), so a TTL tick between the check and the commit could unload the engine and the unconditional `ensureInitialized` at `:658` would then cold-load *after* the 200 (codex C7); touching the idle clock closes that window for a full TTL; then fall through to the existing path; on timeout return the canonical 503 + `Retry-After` reusing `shedIfHot`'s shape verbatim (`:739-751`) **with the admission permit released by the existing `finally` at `:731`** — the continuation is initialization only, never a `generate`, so no timed-out request can keep decoding after its permit is gone (image-gen's exclusivity at `:1353` depends on that). Otherwise, document the hold as deliberate and change nothing.
- **MIRROR:** ERROR_HANDLING; the #180 swap path already does exactly this with `Retry-After: 25` (`RelaisHttpServer.kt:1438`).
- **IMPORTS:** none new.
- **GOTCHA:** The problem is **not** the wait — it is that an unbounded synchronous load holds 1 of 8 shared admission permits (`QUEUE_CAPACITY = 8`, `RelaisAdmission.kt:57`) and serializes every other request behind `lock`. Also: switching outright to 503 would break every client that does not implement `Retry-After`, which is most of them on a first request. No interaction with `RelaisHttpGate` (auth/rate-limit only). **Do not implement this task before task 5 produces a number.** `/v1/audio/transcriptions` (`:845-852`) is decided here too: with the pre-commit wait in place, audio can share it (it already kicks the background reload and 503s), or keep its 503 — write down which.
- **VALIDATE:** On-device: a client with a 30 s timeout gets a clean 503 rather than a socket timeout, and its retry succeeds; a streaming request that times out gets a 503 status, never a committed 200 followed by an SSE error.

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
| `idle-unloaded node reads IDLE not STARTING` | `computeNodeState(shouldRun=true, ready=false, listenersUp=true, startupInProgress=false, lastInitFailed=false, idleUnloaded=true)` | `NodeState.IDLE` | Yes — **highest false-pass risk**: the existing table may already return a plausible value |
| `failed init still reads ERROR while idle-unloaded` | same but `lastInitFailed=true` | `NodeState.ERROR` | Yes — idle must never mask a real failure |
| `a ready engine reads LIVE even if idleUnloaded is stale` | `ready=true, listenersUp=true, idleUnloaded=true` | `NodeState.LIVE` | Yes — mirrors the existing stale-`lastInitFailed` rule |
| `idle with listeners down reads STARTING, not IDLE` | `ready=false, listenersUp=false, idleUnloaded=true` | `NodeState.STARTING` | Yes — the slot-5 guard; and the watchdog's (g) conjunct is the recovery for this state |
| `beginStartup(clearIdleUnloaded = true) clears idleUnloaded atomically; plain beginStartup() preserves it` (`RelaisLivenessStateTest`) | `publishIdleUnloaded(true)` then each form | one snapshot with `startupInProgress=true`, `idleUnloaded` false / true respectively | Yes — the 4(b) mechanism, and the swap-bail regression rev 2 would have shipped |
| `nested begin/end keeps startupInProgress until the outer end` | begin, begin, end → `true`; end → `false` | as stated | Pins the nesting the 4(b) design depends on |
| `nextRung: 10 up → 15, 10 down → 5; 1 down → 1; 60 up → 60; 15 up → 30` | as stated | as stated | Yes — the 09-07 algorithm gave 10 → 30 |
| `idle gauge is omitted on a never-served node` | `lastActivityAtMs == 0L` | no `relais_engine_idle_seconds` line | Yes — the naive gauge rendered process uptime |
| `control panel reads IDLE, and IDLE never beats failed` | `computeControlPanelState(running=true, ready=false, listenersUp=true, idleUnloaded=true)`; then `initFailed=true` | `NodeStatus.IDLE`; `OFFLINE` | Yes — critic P1 #1; the arm is **below** `failed` |
| `IDLE shows the endpoints` | same, IDLE | `showLocalEndpoint && lanEndpointLive` | Yes — `:148-149` are `== LIVE` today |
| `an idle node does not accumulate stall ticks` (`RelaisShellPollingTest`) | 3+ polls with `idleUnloaded=true` | `stalledStart=false` | Yes — the detector fired "node not running" on every idle node |
| `shouldRunWidgetPrompt is keyed on NodeState` | `IDLE` / `LIVE` / `HOT` / `STARTING` / `ERROR` / `OFF`, with and without a prompt | `WARM_THEN_RUN` / `RUN` / `RUN` / `IGNORE` / `IGNORE` / `IGNORE` | Yes — a two-way version ships a dead button, and a flag-keyed one warms a stopped node |
| `tileAction(IDLE) == WARM` | as stated | `TileAction.WARM` | Yes — `START` bounces the listeners |
| `load duration histogram renders bucket, sum and count` | `recordEngineLoad(1.5)` fed directly | `_bucket`/`_sum`/`_count` lines all move | Yes — a name grep passes on a declaration that renders nothing |
| `resetIncrementsForTest clears the load histogram` | observe, reset, render | all three back to zero | Yes — omitting the reset entry leaks into every later test |
| ~~`load duration is recorded only for a real init`~~ | — | — | **Not JVM-testable.** `ensureInitialized` needs a real model file (`:357`) and the native AAR (`:366`). Moved to `IdleUnloadProbe.kt` (task 5) |
| `idle-unloaded node whose reload keeps failing reads ERROR, not IDLE` | `ready=false, listenersUp=true, startupInProgress=false, lastInitFailed=true, idleUnloaded=true` | `NodeState.ERROR` | Yes — **the regression the precedence fix exists to prevent**; IDLE reads as healthy on a dashboard |

### Edge Cases Checklist

- [ ] TTL toggled off **while an unload is mid-flight** — the next tick must simply not unload; nothing must double-release.
- [ ] Stepper decremented to the `IDLE_TTL_MIN_MINUTES` floor must **not** silently cross into the disabled sentinel.
- [ ] Widget `canRun` (`RelaisWidget.kt:95`) is an `==`, not a `when` — confirm by hand that the RUN button is **live** on an IDLE node. The compiler will not tell you.
- [ ] Every real init attempt — service, background, **and the synchronous in-lock reload** — publishes `beginStartup()`/`endStartup()`, so the watchdog's `:128` early-return covers all of them and no reader ever sees `!ready && !startupInProgress` during a load. A failed attempt sets `lastInitFailed`; a successful one clears it. Verify in the probe: `snapshot.startupInProgress` is true mid-reload; a forced failure reads `ERROR` on the next request, not `IDLE`; a direct `RelaisWatchdogReceiver.onReceive` mid-reload does not bump the step.
- [ ] `beginStartup()` is called **before** the `try` whose `finally` calls `endStartup()` — the `check` in `endStartup` (`RelaisLivenessState.kt:47`) would otherwise throw from the `finally` and mask the original exception, including the OOM the `Throwable` catch exists for.
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
- [ ] All **six** surfaces (`computeNodeState` → tile/widget/controller, `/health`, dashboard, `/experiments`, control panel, Configure `nodeBusy`) distinguish idle-unloaded from crashed **without changing the meaning of `ready`** and **without adding a seventh derivation** — each existing one is extended from the same snapshot field.
- [ ] An idle-unloaded node whose reload **fails** reads `ERROR`, not `IDLE`, **on the next request** (not a watchdog tick later) — verified both in `NodeStateTest` and on device by the probe's forced-failure step; and a *successful* synchronous reload does **not** bump the watchdog step. (Gap 6, the reload-failure breaker, is explicitly **out of scope**: see *Notes → Cut after review*. The PR must say so rather than implying it was closed.)
- [ ] The QS tile and the widget both **warm** an IDLE node: `tileAction` returns `WARM` (never `START`, which bounces the listeners), the widget's RUN tap kicks the reload and then runs the prompt — checked **on device by tapping**, not by reading `canRun`, since an enabled button whose handler ignores the tap passed the 09-07 criterion.
- [ ] The in-app control panel on an idle node reads `IDLE` with the idle copy and shows the endpoints — never "node not running · press START"; the MODEL row stays locked with the idle caption.
- [ ] An idle node follows a LAN address change (re-mints and rebinds) without a request — checked on rango by toggling Wi-Fi networks during an idle window and connecting with `curl --cacert`.
- [ ] STOP on an idle node clears `idleUnloaded`; a stale widget tap after STOP is ignored, not a reload.
- [ ] The reload time is **measured on device** and recorded in the PR; task 6 is decided from that number, not from `SPIKE-FINDINGS.md`. The `/v1/audio/transcriptions` path (`RelaisHttpServer.kt:845-852`) is decided in the same breath — it is the primary engine, not a secondary one.
- [ ] Every new test **failed before it passed** — mutation-verified, not merely green.
- [ ] Scope cuts are written into the PR: secondary models have no TTL. (The stale-TXT bug was #313, fixed by #332 — nothing to file.)
- [ ] `./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest` green; CI green; independent code review APPROVE on the **final** diff (green CI ≠ reviewed).

## Completion Checklist

- [ ] Patterns above followed (metrics 3-step, `ToggleRow`/`Stepper`, clamped config, pure-logic guards, probe KDoc-as-runbook)
- [ ] Error handling: the 503 path reuses `shedIfHot`'s exact shape and is answered **before** `sse.commitHeader()`; a failed reload surfaces as `ERROR` in `/health`, never as `IDLE`; the catch is `Throwable`
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
| **Both previous 4(b) designs were wrong in the same place.** "Clear at the start" (09-07) exposed the synchronous reload to the watchdog; "clear on completion" (2026-09-20 rev 1) protected that reload and exposed the *next retry after a failure* instead — and a dispatched restart is not a flicker, it is `stopListeners()` → `pool.shutdownNow()` (`RelaisNodeService.kt:299-303`, `RelaisHttpServer.kt:2259`) interrupting the very worker that triggered the reload | Certain, had either shipped | **High** — in-flight LAN requests killed on idle wake-up | Rev 2 publishes `startupInProgress` from `ensureInitialized` itself (the counter nests, `RelaisLivenessState.kt:30`), so the watchdog stays out of every reload via the same `:128` branch the service relies on. The probe invokes the receiver **directly** mid-reload (task 5(c)) — a timing-based check can pass with the regression present |
| **Task 4 is now the state model plus six readers**, ~20 files, and the build order says one PR per step | High | Medium — review rounds scale with diff size; feature-18 took 21 | Split question put to JD in *Notes → Open questions*; Tasks 1–3 do not depend on it |
| **Bind-failure retries pay a full cold start per watchdog tick once the TTL has fired** — the engine is no longer resident through the failure, so every backoff retry reloads it | Certain, when a port stays taken | Low — battery/thermal on a node that is already unreachable | Inherent to idle-unload; the watchdog's backoff ladder bounds it. Say so in the RUNBOOK row |
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
- **`ensureInitialized` publishes its own `beginStartup()`/`endStartup()`, catches `Throwable`, and owns `lastInitFailed`; `idleUnloaded` lives in the `RelaisLiveness` snapshot and is cleared by `beginStartup()`** (rev 2, 2026-09-20 — the third shape for this decision). 09-07: "clear at the start" — exposed the synchronous in-lock reload to the watchdog. Rev 1: "clear on completion" — protected that reload and exposed the next retry after a failure (codex F1), on the false premise that the publisher could not nest (critic P2 #4: it does, `activeStartupOperations`). Both reviewers independently arrived at the same mechanism; full argument in task 4(b).
- **The tile warms, it does not START.** `TileAction.WARM` → `ensureInitializedInBackground`; `START` on a running node is a listener bounce (critic P2 #5).
- **The widget warms then runs**, via the `RelaisInference.kt:69` self-heal and `ModelSwitch.awaitReload` — a two-way `canRun` change ships a dead button (codex F3 = critic P1 #2).
- **`idleUnloaded` joins the snapshot rather than becoming a seventh separately-read volatile.** Five of the six readers already take the snapshot first; #322 exists for this. What it does **not** buy: `ready` and `lastInitFailed` are still separate reads, so the honest claim is the weaker one written in task 4(b) — never a false LIVE/HOT, at most one poll of lag.
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
3. **`/v1/audio/transcriptions` — bounded-hold or keep 503?** (carried from the 09-07 handoff's *still open* list; needs an on-device number, which task 5 produces. Tasks 1–3 do not wait on it; task 6 does, and task 6's spec now says the two share the pre-commit wait or audio keeps its 503 — either is a one-line decision once the number exists.)
5. **Split this step into two PRs?** After the 2026-09-20 round Task 4 is 20 files: the state model (`RelaisLivenessState`, `RelaisEngine.ensureInitialized`, `NodeState`, controller, watchdog, `/health`) plus six reader surfaces (dashboard, experiments, control panel, shell VM, Configure, tile, widget). Tasks 1–3 (metrics, config UI) do not depend on it. The build order's rule is one PR per step, and feature-09 and feature-18 both split when they grew like this. Proposed: **PR-A = Tasks 1, 2, 4(a)(b)(d)(g) + the probe (5)** — engine, liveness, `/health`, watchdog, metrics; **PR-B = Tasks 3, 4(c)(e)(f), 7** — every UI surface plus the Configure controls; **Task 6 after PR-A's measurement.** JD's call.
4. **Is gap 6 worth a plan of its own?** The reload-failure breaker was cut (above) because it could not detect the crash-idle-crash loop it targeted. A "loaded but never generated" marker would work, but it is a different feature. Leave gap 6 open, or commission it?

**Review round 3 — 2026-09-20, `critic` (Opus) + `codex` (gpt-6-astra) on rev 2 (`cf3f7776`)**

Codex 6 P1 / 4 P2; critic 2 P1 / 6 P2 / 9 P3; **five found by both** (widget warm-wait handshake; control-panel arm placement; `beginStartup` clearing idle on a swap that bails; stale `lastInitFailed` showing OFFLINE on a retry; idle surviving STOP). All verified against source; all applied except one, recorded as a follow-up. Every P1 sat inside a rev-2 fix — the pattern this repo's memory predicts. What changed: `beginStartup(clearIdleUnloaded = false)` with `true` only from the real-init branch; `lastInitFailed` cleared at attempt start (mirrors `:270`), not on success; `shutdown()` clears `idleUnloaded`; `ensureInitializedInBackground` publishes startup on the caller and catches `Throwable`, as does the service init thread; the widget decision keyed on `NodeState` and the worker polling `isReady` on a `warm` input rather than a flag; control-panel IDLE arm **below** `failed`; `DashboardScreen.kt` and the `:148-149` endpoint flags; the shell VM's read order (the rule's own violator); the LAN-rebind gate at `:477`; `LOADING_ENGINE` from the choke point; `noteActivity()` before the SSE commit; `applicationContext` for the tile; `nextRung` no-move at the ends; the gauge HELP text. **Not applied: unlocking Configure's MODEL row while idle** (rev 2's 4(f)) — codex C1 showed the pick only persists the ref and an idle reload would pair the cached path with the new id; the row stays locked with an idle caption, and routing the pick through the dashboard's targeted swap is filed as a follow-up. Considered and rejected (critic, against an advisor suggestion): restoring `idleUnloaded = true` when a real init throws so in-app callers keep self-healing — that re-shields the watchdog on a broken node, which is the original defect; a failed reload handing off to the watchdog's backoff is the design. **Both reviewers' verdict: after these land the document is P3-only, and round 4 should be build + mutation-test + the probe, not prose** — `RelaisNodeService`, the widget and the tile are unreachable from the JVM, and the remaining risk is assembly.

**Review round 2 — 2026-09-20, `critic` (Opus) + `codex` (gpt-6-astra) on rev 1 (`e36e84fa`)**

20 distinct findings: critic 3 P1 / 8 P2 / 8 P3; codex 4 P1 / 5 P2; **five found by both** (widget dead button, watchdog `listenersUp`, publisher nesting, torn-read wording, `nextRung`). Every one was re-verified against source before being applied; none was declined. Disposition, by where it landed: the state model (4(a)(b): nesting counter, `Throwable`, `lastInitFailed` ownership, snapshot field, read-order rule + weaker torn-read claim, `lastInitFailed` KDoc widening); the readers (4(c) tile `WARM` + widget warm path; 4(e) experiments + failed-swap note; 4(f) control panel + stall detector + Configure `nodeBusy` — critic P1 #1, the largest miss; 4(g) watchdog conjunct); metrics (Task 1 JSON `_p50`; Task 2 nullable `idleSeconds` + both HELP texts; unload counter = logical eviction); config UI (Task 3 `nextRung` strictly-greater/smaller with explicit 10 → 15 / 10 → 5, POWER section, ladder-ceiling note, mock redrawn post-#326); Task 5 (direct receiver invocation, pref read, `lastInitFailed` reset before the loop, 60 s window, precondition assert, `nowMs`); Task 6 (pre-`sse.commitHeader()` bounded **init** wait, permit released, no timed lock, audio decided alongside). The pre-existing watchdog tear (read `isReady=false`, reload completes, read flag → one `bumpStep`) is recorded as known and left alone. The rev-1 transferable lesson: I wrote "cannot call `beginStartup()` without a nesting counter" without opening `RelaisLivenessState.kt`, in the revision whose stated purpose was to stop doing that — and the explore agent's description of the publisher was wrong too, which I checked for the claim I cared about and not for the one I reused.

**Findings accepted from review (2026-09-07), and the one declined**

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
- **Scope:** 35 files (2 CREATE; 33 UPDATE), 7 tasks — Task 4 alone is 22 of them
- **Key Patterns:** the pure-logic + JVM-test pair (`RelaisIdleTtl.shouldUnloadIdleEngine` `:79-92` ↔ `RelaisIdleTtlTest.kt:19-55`); `RelaisEngine.releaseIfIdle` pre-check/lock/re-check (`:1041-1054`); the 3-step metric idiom (`AtomicLong` `:46` → `record…()` `:159` → Prometheus `line(...)` `:323-325` + JSON `:452`); `ToggleRow` (`RelaisConfigureActivity.kt:344-358`) and the `remember { mutableStateOf(RelaisConfig.x(ctx)) }` idiom (`:142-148`)
- **External Research:** Prometheus metric-naming conventions (`_total` counters vs `_seconds` gauges); Android foreground-service lifetime under Doze; the LiteRT-LM native API inventory (`docs/litertlm-native-api.md`) for what reload actually costs
- **Top Risk:** `NodeState.IDLE` masking a broken node. A node that unloads and then fails every reload would report a state that reads as **healthy** on every dashboard — a monitoring regression, not a cosmetic one. The load-bearing defence is `ensureInitialized`'s own `beginStartup(clearIdleUnloaded = true)` — a failure can only ever be written after the idle flag is gone, so the `ERROR`-beats-`IDLE` precedence row guards a combination the machine cannot produce (kept as defence in depth). With it: `lastInitFailed` set on `Throwable` and cleared at the next attempt's start, and the watchdog shield gaining `listenersUp` so a broken idle node is recoverable, not just labelled. Truth-table tests plus the probe's forced-failure and direct-receiver checks. The standing second risk is unchanged: `RelaisEngine`'s init/release concurrency near `:645-659`/`:1041-1054`, where the on-device probe, not the JVM suite, is the gate
- **Confidence Score:** 7/10 (2026-09-20, rev 3, after two review rounds: 20 + 21 findings, 5 + 5 found by both, all verified and applied. The state model is on its fourth shape; rounds 2 and 3 each landed inside the previous round's fix, and both reviewers now say the remaining risk is in assembly, not prose — so the next pass is a build, not a round 4. Still gated on an unmeasured reload time; the probe is the only cover for the 4(b) property and the widget/tile/service paths. Earlier: 7/10 after round 2; 6/10 after the tree reconciliation; 5/10 after the 09-07 critic. The gap analysis and its line-level anchors held up under independent verification, but of eight drafted tasks one was **cut outright** as undetectable, one had inverted state precedence that would have shipped a healthy-looking broken node, and one was sized as a one-liner when it is a full Prometheus histogram. Three surface decisions — the stepper ladder, the `/v1/audio/transcriptions` classification, and sequencing against feature-09/-18 on `handleHealth` — are named but not settled)

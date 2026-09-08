# Plan: Real power / energy readouts in `/metrics` (feature-19)

## Summary

Relais markets itself as an appliance that "draws a few watts" (`docs/index.html:75`,
`README.md:26-27` — the phrase is line-wrapped across those two lines) and has never measured it.
Android's `BatteryManager` exposes instantaneous battery current, voltage, temperature, level and
charge state to any app with **no permission**; this feature turns those into label-free Prometheus
series plus a per-decode energy figure (mWh, and mWh-per-1k-tokens) integrated over the **decode**
window.

Two load-bearing honesty decisions shape everything below:

1. On a **plugged-in** device — which is the primary deployment — battery current is net charge flow
   and device draw is *structurally unobservable*, so the plan reports that as an explicit invalid
   state rather than a plausible-looking wrong number.
2. `BatteryManager` measures the **whole device** — display, radios, every process, baseline SoC
   draw. This plan does **not** subtract an idle baseline (see Notes → *Why no baseline subtraction*),
   so the energy series are *whole-device energy drawn during a decode window*, **not**
   decode-attributable energy. That distinction is carried in the metric names, the `# HELP` text,
   `docs/power-metrics.md`, and the README copy — it is not left for the reader to infer.

## User Story

- **As an** operator running Relais as an always-on shelf appliance,
- **I want** `/metrics` to report real instantaneous power draw, battery temperature, and the energy
  cost of each inference,
- **So that** I can answer "what does a token cost me, in joules", spot an efficiency regression
  (thermal throttling, a backend falling off the TPU lane) before it shows up as a latency complaint,
  and quote a measured watt figure in the README instead of a hedge.

## Problem → Solution

**Problem.** The node has a heat axis (`ThermalGovernor` → `relais_thermal_status`,
`relais_thermal_headroom`) and a throughput axis (`relais_decode_tokens_per_second`) but no **energy**
axis. Every power claim in the product copy is an estimate nobody has measured. Worse, the obvious
naive implementation is actively misleading: `BATTERY_PROPERTY_CURRENT_NOW` reports **net current at
the battery terminal**, which means:

| Regime | What the battery current means | Can we derive device draw? |
|---|---|---|
| **Discharging** (unplugged) | All device consumption comes out of the battery | **Yes** — `P = \|I\| × V` is whole-device draw |
| **Charging** | Net = (charger input) − (device consumption); charger input is **not exposed** by any public API | **No** |
| **Full / plugged, maintenance** | Net ≈ 0; the device runs off the charger | **No** |

Reporting `|net|` as "draw" while charging yields a number near zero and quietly makes Relais look
free. That is precisely the failure mode memory `relais-claim-stronger-than-code` records — a locally
accurate fact extrapolated one step too far.

**Solution.** Split the feature into *measurement* and *derivation*, and mark validity explicitly:

1. `relais_battery_current_now_milliamps` — the **raw signed** reading, unit-normalized only, sign
   untouched. Always emitted, always honest.
2. `relais_power_draw_milliwatts` — the **derived** whole-device draw. A real value **only while
   discharging**; `-1` sentinel otherwise.
3. `relais_power_measurement_valid` — `1` iff discharging *and* the reading is sane. **Every panel
   and alert gates on this.** Without it a `-1` sentinel silently drags a graph average.
4. Per-request energy recorded **only if the entire decode window was discharging and sane**. A
   plug/unplug mid-window invalidates it: drop the window, increment
   `relais_energy_samples_invalid_total`, never interpolate across a regime change.

All conversion logic lives in a new **pure-Kotlin** file with zero Android imports, so the integrator
and the unit/sign normalizer are JVM-unit-testable without Robolectric or a device — matching the
CLAUDE.md style rule that pure logic gets a device-free `*Test.kt`.

## Assumptions (stated, not verified — each has a gate below)

1. **`BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)` needs no permission.** High
   confidence: it is a public, non-`@RequiresPermission` API in the `android-35` stub jar, and
   `BATTERY_STATS` (signature-level) is **not** used here. Gated by Task A8 (aapt permission diff).
2. **Units and sign are OEM-dependent.** Android documents `CURRENT_NOW` as microamperes with a
   *negative* value meaning discharge, but OEMs are widely inconsistent (some report mA, some invert
   the sign, some return `Integer.MIN_VALUE` when unsupported). **This plan hardcodes no convention** —
   the normalizer takes it as a parameter and the probe (Task A6) establishes it per SoC, exactly the
   way `docs/litertlm-native-api.md` §7.5 records its verified on-device table.
3. **`EXTRA_VOLTAGE` is millivolts and `EXTRA_TEMPERATURE` is deci-°C.** Same caveat; probe-established.
4. **`BATTERY_PROPERTY_ENERGY_COUNTER` (nWh, `getLongProperty`) is probably unsupported** on Tensor —
   most devices return `Long.MIN_VALUE`. If it *is* supported it is strictly better than integration
   (see Task A6 Leg 5 and Notes). Probe decides; the plan does not depend on it.
5. **Decode windows never overlap *with each other*** — **verified** for the streaming `generate`
   lane, which serializes on `synchronized(lock)` at `RelaisEngine.kt:605`; see Patterns to Mirror.
   This does **not** mean the device is otherwise idle. The AICore path returns at
   `RelaisEngine.kt:596` **before** ever taking that lock, and the audio / embeddings / rerank / TTS
   lanes do not take it either — so concurrent non-decode work can and does land inside a decode
   window. Combined with the whole-device caveat in §Summary, this is the reason the energy series
   are named `decode_window` and disclosed as *not* decode-attributable.
6. **Whole-device draw is not decomposable from `BatteryManager` alone.** There is no per-process
   energy attribution available without `BATTERY_STATS` (signature-level, unobtainable) or a
   perfetto trace. Task A7's perfetto rail cross-check is the only independent authority, and it is
   used to bound the README figure — not to correct the metric.

Anything the probe contradicts wins over this document.

## Metadata

| Field | Value |
|---|---|
| **Complexity** | **Medium–Large.** Raised from "Medium" after review: A9 brackets **two** engine lanes rather than one, A3 carries two read paths plus a clock seam, and the test count went 16 → 17. Large if you also count the device time in A7 |
| **Source PRD** | N/A |
| **PRD Phase** | N/A |
| **Estimated Files** | **15** — 7 created (`RelaisPower.kt`, `RelaisPowerSampler.kt`, `RelaisPowerTest.kt`, `RelaisPowerMetricsTest.kt`, `PowerReadoutProbe.kt`, `scripts/power-crosscheck.sh`, `docs/power-metrics.md`) + 8 updated (`RelaisMetrics.kt`, `RelaisEngine.kt`, `RelaisNodeService.kt`, dashboard JSON, `relais-alerts.md`, `RUNBOOK.md`, `README.md`, `docs/index.html`) |
| **Branch** | `feat/relais-power-metrics` |
| **New permissions** | none |
| **New dependencies** | none |
| **Gradle config change** | none |

## UX Design

No app UI is added. The visible surfaces are the Prometheus scrape, the JSON HUD payload, and the
Grafana dashboard. `DESIGN.md` is unaffected (no Compose change, no new colour, no new motion).

**Before** — `/metrics` (energy-blind):

```
relais_decode_tokens_per_second 5.80
relais_thermal_status 2
relais_thermal_headroom 0.71
relais_memory_rss_bytes 3221225472
                       ← no answer to "what does this cost to run?"
```

**After** — on battery (measurement valid):

```
relais_decode_tokens_per_second 5.80
relais_thermal_status 2
relais_battery_current_now_milliamps -1180
relais_battery_voltage_volts 3.92
relais_power_draw_milliwatts 4625.6
relais_power_measurement_valid 1
relais_battery_temperature_celsius 34.7
relais_battery_charging 0
relais_battery_level_ratio 0.68
relais_decode_window_energy_milliwatt_hours_bucket{le="50"} 12
relais_decode_window_energy_per_1k_tokens_milliwatt_hours 221.4
relais_energy_samples_invalid_total 0
```

**After** — plugged in (the primary deployment; derivation honestly withheld):

```
relais_battery_current_now_milliamps 340        ← real, signed, still emitted
relais_battery_voltage_volts 4.36
relais_power_draw_milliwatts -1                 ← sentinel, NOT |net|
relais_power_measurement_valid 0                ← the series dashboards gate on
relais_battery_temperature_celsius 36.1
relais_battery_charging 1
relais_energy_samples_invalid_total 47          ← expected to climb; do not alert
```

### Interaction Changes

| Touchpoint | Before | After | Notes |
|---|---|---|---|
| `GET /metrics` (Prometheus text) | 0 power series | 10 new label-free series | Read at scrape time via a 2 s cached binder call; bearer-gated already |
| `GET /metrics` (`Accept: application/json`, in-app HUD) | no power fields | `power_draw_milliwatts`, `power_measurement_valid`, `battery_temperature_celsius` | Next to the existing `thermal_headroom` / `memory_rss_bytes` fields (`RelaisMetrics.kt:460-462`) |
| Grafana dashboard | 10 panels, last row `y:33` | +4 panels in a new row at `y:39` | Power panels carry an `and …_valid == 1` filter and a "blank while plugged in" note |
| `docs/relais-alerts.md` | **8 rules / 7 sections / 136 lines** | +3 rules (→ 11), +1 "do not alert on this counter" note | Same YAML-in-prose format as the existing file |
| Inference request path | — | unchanged externally | Sampler brackets the decode; a sampler failure can never fail an inference |
| Idle node | no background work from metrics | **still no background work** | The ticker exists only between `beginWindow`/`endWindow` |
| Android permissions | (unchanged list) | **byte-identical** | Asserted by Task A8, not assumed |

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | 578-612, **664-712**, 778-810 | The two seams the sampler brackets, and *why* it is the decode window and not the request window (`incInFlight()` at `:584` precedes the lock at `:605`). **`:664-684` is load-bearing for Task A9:** it is the comment block explaining why `cancelRequested`/`stopThread` are declared *outside* `return try {` (`:685`) — "Declared out here (not inside the try body) so the finally can join it" (`:671-672`). `powerWindow` has exactly the same constraint |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | 818-834 | `generateWithToolsLocked`'s KDoc + signature — the second, *blocking* decode lane A9 must also bracket, with its own try/finally and its own instance of the same scoping trap |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt` | 28-60, 101-105, 175-230, 277-300, 460-484 | Label-hygiene rule, histogram implementation shape, `rssBytes()` sentinel pattern, `renderProm`/`renderJson`, and the `resetIncrementsForTest` seam you must extend |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/ThermalGovernor.kt` | 20-32, 70-110, 155-175 | The three patterns this feature copies: cached rate-limited system read, `-1` sentinel, `*ForTest` threshold seam |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisNodeService.kt` | 103, 112-131, 229-239 | Register/unregister lifecycle and the `ScheduledExecutorService` ticker idiom (coroutines are **not** the house style here) |
| **P1** | `Android/src/app/src/test/java/cc/grepon/relais/RagChunkerTest.kt` | 18-57 | The device-free pure-JVM test shape `RelaisPowerTest.kt` must match (injected dependency, backtick test names, no runner annotation) |
| **P1** | `Android/src/app/src/test/java/cc/grepon/relais/RelaisMetricsIncrementsTest.kt` | 21-70 | The Robolectric-only-for-`Context` shape `RelaisPowerMetricsTest.kt` must match, incl. the `@Before` reset |
| **P1** | `Android/src/app/src/androidTest/java/cc/grepon/relais/ToolCallingProbe.kt` | 39-62 | Probe header format: one copy-pasteable `adb shell am instrument` line, `-e model`, the `Relais*` logcat tag, and the **real** on-device model path form |
| **P2** | `.omc/skills/grapheneos-ondevice-verification-expertise.md` | whole file | The only thing in this repo that has actually proven a power claim on these devices (perfetto rails; sysfs is SELinux-sealed) |
| **P2** | `docs/relais-alerts.md` | 1-62 | Alert-doc voice and YAML-in-prose format. **136 lines, 8 `- alert:` rules across 7 `##` sections** (`RelaisSustainedShedding :17`, `RelaisThermalSevere :33`, `RelaisErrorRate :49`, `RelaisThroughputFloor :70`, `RelaisQueueRejecting :89`, `RelaisEndpointLatencyP95 :104`, `RelaisDown :120`, `RelaisRestartLoop :128` — the last section holds two rules). Task A10 takes it to 11 rules |
| **P2** | `docs/relais-grafana-dashboard.json` | whole file | `schemaVersion: 39`, 10 panels, 24-col grid, last row at `y:33 h:6` → new row at `y:39`; every target uses `instance="$instance"` |
| **P2** | `CLAUDE.md` | "Style rules" section | Pure logic → JVM test; IO/capability → `*Probe.kt`; prefer a new file over growing a large one |

## External Documentation

| Topic | Source URL | Key Takeaway |
|---|---|---|
| `BatteryManager` constant ownership, types, values | **Local primary source**: `~/Android/Sdk/platforms/android-35/android.jar` via `javap -constants -cp android.jar android.os.BatteryManager` | **Verified this session.** `EXTRA_VOLTAGE`("voltage"), `EXTRA_TEMPERATURE`("temperature"), `EXTRA_STATUS`("status"), `EXTRA_PLUGGED`("plugged"), `EXTRA_LEVEL`("level"), `EXTRA_SCALE`("scale") are **all declared on `android.os.BatteryManager`**. `BATTERY_PROPERTY_CURRENT_NOW=2`, `CURRENT_AVERAGE=3`, `CHARGE_COUNTER=1`, `ENERGY_COUNTER=5`. `BATTERY_STATUS_DISCHARGING=3`, `CHARGING=2`, `FULL=5`, `NOT_CHARGING=4`, `UNKNOWN=1`. Methods: `getIntProperty(int): int`, `getLongProperty(int): long`. |
| `ACTION_BATTERY_CHANGED` ownership | **Local primary source**: `javap -cp android.jar android.content.Intent` | **Verified this session.** `ACTION_BATTERY_CHANGED` is on **`android.content.Intent`**, *not* on `BatteryManager`. This is the single most likely wrong-import in the whole feature. |
| `CURRENT_NOW` units / sign / permission | https://developer.android.com/reference/android/os/BatteryManager | **NOT FETCHED THIS SESSION** — two WebFetch attempts returned only the site nav, and the AOSP source viewer returned nothing. Recalled: microamperes, negative == discharging, no permission. **Treat as unverified prose; the probe (A6 Leg 1) is the authority and the code takes the convention as a parameter precisely so this recall cannot be load-bearing.** |
| `EXTRA_TEMPERATURE` unit | https://developer.android.com/reference/android/os/BatteryManager#EXTRA_TEMPERATURE | **NOT FETCHED THIS SESSION.** Recalled: tenths of a degree Celsius. Probe Leg 1 logs the raw value; a phone reading `347` confirms deci-°C, `34` would mean whole °C. |
| Perfetto `android.power` rails on GrapheneOS | `.omc/skills/grapheneos-ondevice-verification-expertise.md` (in-repo, evidence-backed) | Per-rail energy counters via `collect_power_rails: true` are **authoritative**; `power.rails.tpu` / `power.S2S_VDD_GPU_uws` are monotonic µWs so slope is mW; known signature **TPU 0 mW idle → ~350–530 mW during LLM decode**. Sysfs (`/sys/class/thermal`, `/sys/class/edgetpu`, iio ODPM) is SELinux-sealed from adb shell — perfetto is the only channel. Start the trace **first**, and require a negative control. |
| Prometheus text exposition | (format already implemented in-repo) | `RelaisMetrics.renderProm` emits `# HELP` / `# TYPE` / value triples; histograms need cumulative `_bucket{le=…}` + `_sum` + `_count`. Mirror `relais_completion_tokens` at `RelaisMetrics.kt:369-378`. |

**Research notes:**

- **KEY_INSIGHT** — every battery `EXTRA_*` this feature reads lives on `BatteryManager`, but the
  *intent action* used to read them lives on `Intent`. **APPLIES_TO** every `IMPORTS` block in
  Task A3. **GOTCHA** — `BatteryManager.ACTION_CHARGING` / `ACTION_DISCHARGING` also exist and are
  *not* what you want; they are broadcast actions for charge-state transitions, not the sticky
  battery-state intent.
- **KEY_INSIGHT** — `BATTERY_PROPERTY_ENERGY_COUNTER` exists and is a `long` in nWh, which would give
  exact windowed energy as a simple delta with no trapezoid at all. **APPLIES_TO** Task A6 Leg 5.
  **GOTCHA** — it is unsupported on most devices and returns `Long.MIN_VALUE`; do **not** build the
  design on it, measure it and treat a working counter as a bonus fast path (see Notes).
- **KEY_INSIGHT** — the units/sign question is *not* resolvable from documentation with any
  confidence, because OEM kernels disagree with the docs. **APPLIES_TO** the whole normalizer.
  **GOTCHA** — this is why `PowerConvention` is a parameter and why the >30 W plausibility guard
  exists: a unit misdetection must fail **loudly** as a sentinel, not silently as a 1000× error.

## Patterns to Mirror

### NAMING_CONVENTION — process-global `object`, `relais_*` metric names, no labels

```kotlin
/**
 * Label hygiene (security M6): only the model *id* and backend name are exposed — never the model
 * filesystem path, the API key, the HF token, or any IP. See `RelaisMetricsLeakTest`.
 */
object RelaisMetrics {
  private val startMs = System.currentTimeMillis()
  private val tokensTotal = AtomicLong(0)
  @Volatile private var lastDecodeTokS = 0.0
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt:36-51`

> Files are `Relais<Thing>.kt` in the flat `cc.grepon.relais` package; process-wide services are
> Kotlin `object`s with `@Volatile` scalars and `Atomic*` counters. **All power series added by this
> plan are label-free**, so M6 is satisfied by construction.
>
> **Incidental defect to fix in this PR (one line):** that KDoc cites `RelaisMetricsLeakTest`, which
> **does not exist**. The real hermetic metrics test is `RelaisMetricsIncrementsTest.kt`. Repoint the
> comment; do not leave a KDoc pointing at a guarantee nothing enforces.
>
> **Provenance — this is not our discovery, and an earlier draft of this plan mis-sourced it.** The
> defect is already tracked at `.claude/HANDOFF.md:69` ("`RelaisMetrics.kt:38` cites
> `RelaisMetricsLeakTest`, which does not exist"), and `feature-22-idle-unload.plan.md:630` already
> assigns the fix to **this** plan and tells its own implementer not to duplicate it. A prior revision
> of this document claimed the only other hit was
> `.claude/PRPs/plans/feature-09-web-dashboard.plan.md:119` and called that *"verified by grep"* —
> that line is an MDN link about HTTP Basic auth, so the claim was both wrong and falsely attributed
> to a check that was never run. Corrected here rather than quietly dropped, because a
> stated-as-verified claim that was not verified is the exact failure mode
> `relais-claim-stronger-than-code` records.

### ERROR_HANDLING — `runCatching` → sentinel, never throw out of a read

```kotlin
  /** Cheap RSS via /proc/self/status (avoids Debug.getPss, which can stall tens of ms). */
  private fun rssBytes(): Long =
    runCatching {
      File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
        ?.filter { it.isDigit() }?.toLongOrNull()?.times(1024) ?: -1L
    }.getOrDefault(-1L)
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt:277-282`

```kotlin
  /** Forecast headroom (>=1.0 == at throttle threshold). Returns -1 sentinel if unavailable/NaN. */
  fun headroomOrSentinel(): Float {
    val pm = powerManager ?: return -1f
    val now = System.currentTimeMillis()
    if (now - headroomCachedAt < HEADROOM_CACHE_MS) return headroomCache
    val hr = runCatching { pm.getThermalHeadroom(FORECAST_SEC) }.getOrDefault(Float.NaN)
    headroomCache = if (hr.isNaN()) -1f else hr
    headroomCachedAt = now
    return headroomCache
  }
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/ThermalGovernor.kt:101-110`

> This is the exact shape `RelaisPowerSampler.readCached()` copies: cached (`HEADROOM_CACHE_MS = 2_000L`,
> `ThermalGovernor.kt:28`) because the underlying call is rate-limited/binder-costly, `runCatching`
> around the platform call, sentinel on failure. A device that returns nothing must degrade to
> "power metrics unavailable" — never break a scrape, never break an inference.

### LOGGING_PATTERN — file-level `private const val TAG`, `Log.i/w/e`, log the throwable

```kotlin
private const val TAG = "ThermalGovernor"
…
      Log.i(TAG, "thermal status -> $status")
    }
    runCatching { pm.addThermalStatusListener(executor, l) }
      .onFailure { Log.e(TAG, "addThermalStatusListener failed", it) }
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/ThermalGovernor.kt:26, 86-89`

```kotlin
      { runCatching { checkIdleUnload() }.onFailure { Log.w(TAG, "idle-TTL tick failed", it) } },
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisNodeService.kt:126`

> `RelaisPowerSampler` uses `private const val TAG = "RelaisPower"` — which is also the logcat tag the
> probe filters on (`adb logcat -s RelaisPower`).

### SERVICE/HANDLER_PATTERN — register/unregister on the service, ticker via `ScheduledExecutorService`, emission inside `renderProm`

```kotlin
    ThermalGovernor.register(applicationContext) // thermal-aware backpressure (Gate 3)
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisNodeService.kt:103`

```kotlin
  override fun onDestroy() {
    idleTtlExecutor?.shutdownNow()
    ThermalGovernor.unregister()
    RelaisDiscovery.unregister()
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisNodeService.kt:229-232`

```kotlin
  private fun startIdleTtlTicker() {
    val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "relais-idle-ttl") }
    idleTtlExecutor = executor
    executor.scheduleWithFixedDelay(
      { runCatching { checkIdleUnload() }.onFailure { Log.w(TAG, "idle-TTL tick failed", it) } },
      IDLE_TTL_POLL_INTERVAL_MS,
      IDLE_TTL_POLL_INTERVAL_MS,
      TimeUnit.MILLISECONDS,
    )
  }
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisNodeService.kt:122-131`

```kotlin
    line("# HELP relais_decode_tokens_per_second Decode throughput of the most recent inference.")
    line("# TYPE relais_decode_tokens_per_second gauge")
    line("relais_decode_tokens_per_second $lastDecodeTokS")

    line("# HELP relais_thermal_headroom Forecast headroom (>=1.0 == throttling); -1 if unavailable.")
    line("# TYPE relais_thermal_headroom gauge")
    line("relais_thermal_headroom ${ThermalGovernor.headroomOrSentinel()}")
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt:387-397`

> Note the second block: `renderProm` **already** calls into a sibling object at scrape time. The
> instantaneous power gauges do exactly the same via `RelaisPowerSampler.readCached()` — **no sampler
> thread is needed for them**. The route handler itself needs no change:
>
> ```kotlin
>   private fun handleMetrics(ctx: RequestContext) {
>     RelaisMetrics.recordRequest(ctx.endpoint, 200)
>     if (ctx.accept?.contains("application/json") == true) {
>       respond(ctx.sock, 200, RelaisMetrics.renderJson(context))
>     } else {
>       respondText(ctx.sock, 200, RelaisMetrics.renderProm(context), "text/plain; version=0.0.4")
>     }
>   }
> ```
> `// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:775-782`

### HISTOGRAM_PATTERN — bounds array, counts array with a trailing `+Inf` slot, dedicated lock

```kotlin
  private val tokenBucketBounds = longArrayOf(16, 32, 64, 128, 256, 512, 1024)
  private val tokenBucketCounts = LongArray(tokenBucketBounds.size + 1) // last == +Inf
  private var completionTokenCount = 0L
  private var completionTokenSum = 0L
  private val tokenHistLock = Any()
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt:101-105`

```kotlin
  fun recordLatency(durationSec: Double) {
    synchronized(histLock) {
      latencyCount++
      latencySum += durationSec
      var idx = bucketBoundsSec.indexOfFirst { durationSec <= it }
      if (idx < 0) idx = bucketBoundsSec.size
      bucketCounts[idx]++
    }
  }
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt:175-183`

```kotlin
  fun resetIncrementsForTest() {
    synchronized(histLock) {
      bucketCounts.fill(0L)
      latencyCount = 0L
      latencySum = 0.0
    }
    synchronized(endpointHistLock) { endpointHists.clear() }
    synchronized(tokenHistLock) {
      tokenBucketCounts.fill(0L)
      completionTokenCount = 0L
      completionTokenSum = 0L
    }
    thermalEventCounts.clear()
  }
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt:471-484`

> `resetIncrementsForTest` is **not optional to extend**. `RelaisMetrics` is a process-global object;
> a histogram not cleared here bleeds across tests in one JVM.

### TEST_STRUCTURE (A) — pure JVM, no runner annotation, injected dependency, backtick names

```kotlin
/**
 * Pure JVM tests for [RagChunker]. Token counting is injected as a word count so the assertions are
 * deterministic without a real tokenizer (the production caller passes the SentencePiece counter).
 */
class RagChunkerTest {

  // Deterministic stand-in for the tokenizer: one "token" per whitespace-separated word.
  private val words: (String) -> Int = { it.split(Regex("\\s+")).filter { w -> w.isNotEmpty() }.size }

  @Test fun `packs sentences greedily up to the token budget`() {
    val text = "One two three. Four five six. Seven eight nine."
    // Each sentence = 3 words; budget 6 → two sentences per chunk.
    val chunks = RagChunker.chunk(text, targetTokens = 6, countTokens = words)
    assertEquals(2, chunks.size)
```
`// SOURCE: Android/src/app/src/test/java/cc/grepon/relais/RagChunkerTest.kt:21-34`

> **This is the shape `RelaisPowerTest.kt` must match** — no `@RunWith`, no Robolectric, no `Context`.
> Everything in `RelaisPower.kt` is deliberately reachable this way.

### TEST_STRUCTURE (B) — Robolectric *only* to obtain a `Context` for `renderProm`

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelaisMetricsIncrementsTest {

  private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

  @Before
  fun reset() {
    RelaisMetrics.resetIncrementsForTest()
  }

  @Test
  fun `per-endpoint latency emits labeled buckets per endpoint with correct cumulative counts`() {
    RelaisMetrics.recordEndpointLatency("/generate", 0.4)
    …
    val prom = RelaisMetrics.renderProm(context)
    assertTrue(
      "generate le=1.0 bucket must be cumulative-1",
      prom.contains("relais_inference_duration_seconds_bucket{endpoint=\"/generate\",le=\"1.0\"} 1"),
    )
```
`// SOURCE: Android/src/app/src/test/java/cc/grepon/relais/RelaisMetricsIncrementsTest.kt:41-67`

> `RelaisPowerMetricsTest.kt` copies this exactly, with its `@Before` calling **both**
> `RelaisMetrics.resetIncrementsForTest()` and `RelaisPowerSampler.resetForTest()`.

### PROBE_STRUCTURE — header with one copy-pasteable command, `-e model`, `assumeTrue` guard

```kotlin
/**
 * On-device probe for feature-04: does the bundled LiteRT-LM expose a WORKING native tool-calling
 * path, and does the resident model actually emit a structured tool call through it?
 *
 * Run (rango / Pixel 10 / G5, E2B staged):
 *   adb -s <serial> shell am instrument -w \
 *     -e class cc.grepon.relais.ToolCallingProbe \
 *     -e model /storage/emulated/0/Android/data/com.ventouxlabs.relais.izzy/files/litert_community_gemma_4_E2B_it_litert_lm/361a4010ad6d88fc5c86e148e333c0342b99763d/gemma-4-E2B-it.litertlm \
 *     com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Watch: adb logcat -s RelaisToolProbe
 */
@RunWith(AndroidJUnit4::class)
class ToolCallingProbe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val modelPath: String
    get() = args.getString("model") ?: DEFAULT_MODEL

  @Test
  fun probeNativeToolCalling() {
    val path = modelPath
    assumeTrue("Model not found at $path (pass -e model <path>)", File(path).exists())
```
`// SOURCE: Android/src/app/src/androidTest/java/cc/grepon/relais/ToolCallingProbe.kt:39-87`

> **Copy the model-path form verbatim** — the app-external-files path, *not* `/data/local/tmp/...`
> (memory `relais-ondevice-verification`: the `/data/local/tmp` staging area is gone). The test
> applicationId is flavor-dependent; `fullOpen` → `com.ventouxlabs.relais.izzy.test`.

## Files to Change

| File | CREATE/UPDATE | Justification |
|---|---|---|
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisPower.kt` | **CREATE** (~200 ln) | Pure Kotlin: conventions, `PowerSample`, immutable `EnergyAccumulator`, normalizer, trapezoid integrator, all invalidation rules. No Android imports → device-free tests |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisPowerSampler.kt` | **CREATE** (~180 ln) | The only file that touches `BatteryManager`/`Intent`. Register/unregister, 2 s cached read, decode-window ticker, test seams |
| `Android/src/app/src/test/java/cc/grepon/relais/RelaisPowerTest.kt` | **CREATE** | Tests 1–11, pure JVM, `RagChunkerTest` shape |
| `Android/src/app/src/test/java/cc/grepon/relais/RelaisPowerMetricsTest.kt` | **CREATE** | Tests 12–17 (17 = the ticker-vs-cache regression), Robolectric-for-`Context`, `RelaisMetricsIncrementsTest` shape |
| `Android/src/app/src/androidTest/java/cc/grepon/relais/PowerReadoutProbe.kt` | **CREATE** | Legs 1–5. Establishes the per-SoC convention that every number in this plan depends on |
| `scripts/power-crosscheck.sh` | **CREATE** | Perfetto `android.power` rail capture + slope, per the GrapheneOS verification skill |
| `docs/power-metrics.md` | **CREATE** | The referenceable artifact: verified per-SoC convention table, series semantics, charging explanation |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt` | **UPDATE** (~70 ln) | Two record functions, the energy histogram, 10 series in `renderProm`, 3 fields in `renderJson`, **extend `resetIncrementsForTest`**, fix the stale `RelaisMetricsLeakTest` KDoc at `:38` |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | **UPDATE** (~30 ln, two lanes) | Streaming lane: `powerWindow` + `energyTokens` declared beside `cancelRequested`/`stopThread` at `:673-674` (**above** `return try {` at `:685` — scoping, see A9 GOTCHA 1), a one-line mirror write in the token callback, `endWindow()` in the `finally` at `:797`. Blocking tool lane: the same bracket around `generateWithToolsLocked` (`:829`), absolute mWh only |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisNodeService.kt` | **UPDATE** (2 ln) | `register` at `:103`, `unregister` in `onDestroy` at `:229` |
| `docs/relais-grafana-dashboard.json` | **UPDATE** | 4 panels in a new row at `y:39` |
| `docs/relais-alerts.md` | **UPDATE** | 3 rules + the "do not alert on `_invalid_total`" note |
| `docs/index.html` (`:75`), `README.md` (`:27`), `docs/RUNBOOK.md` | **UPDATE** | Measured watts figure; how to take an on-battery run. **Blocked on Task A7** |

## NOT Building

- **A `BATTERY_STATS` / `HealthStats` / `UsageStatsManager` path.** `BATTERY_STATS` is signature-level;
  chasing it would add a permission this feature exists to avoid.
- **Per-subsystem attribution** (how much of the draw is GPU vs TPU vs display). Perfetto rails answer
  this offline in the cross-check; `/metrics` does not.
- **Charger-input estimation while plugged in.** No public API exposes it. Declared unobservable and
  reported as such — this is the plan's central honesty commitment, not an omission to be "fixed".
- **A `relais_power_draw_milliwatts{backend=…}` label.** Bounded and interesting, but deferred to v2
  (see Notes) rather than smuggled into v1.
- **Energy accounting for the AICore/NPU path** (`RelaisEngine.kt:593-597`) — it returns before the
  decode seam and has no per-token counter.
- **An always-on background sampler.** Explicitly rejected on **idle-cost** grounds — a permanent
  thread + 1 Hz binder call for no benefit on an idle node. (Not on Doze or idle-TTL grounds; see
  Task A3 GOTCHA 3 for why both of those arguments are false.)
- **Fixing `prompt_tokens` estimation, TTFT, or anything in feature-20.** Separate plan, separate PR.
- **Any Compose/UI change.** The HUD JSON gains fields; no screen changes.

## Step-by-Step Tasks

### Task A1 — `RelaisPower.kt`: pure types, normalizer, integrator

**ACTION** Create `Android/src/app/src/main/java/cc/grepon/relais/RelaisPower.kt`.

**IMPLEMENT**
```kotlin
enum class CurrentUnit { MICROAMPS, MILLIAMPS }
enum class DischargeSign { NEGATIVE, POSITIVE }
enum class ChargeRegime { DISCHARGING, CHARGING, FULL, UNKNOWN }

data class PowerConvention(val unit: CurrentUnit, val dischargeSign: DischargeSign)

/** One immutable reading. [atNanos] comes from a monotonic clock (System.nanoTime), never wall clock. */
data class PowerSample(
  val atNanos: Long,
  val rawCurrent: Int,        // exactly what getIntProperty returned
  val voltageMilliVolts: Int, // exactly what EXTRA_VOLTAGE returned
  val regime: ChargeRegime,
)

/** Immutable accumulator; [accumulate] RETURNS A NEW INSTANCE and never mutates its input. */
data class EnergyAccumulator(
  val milliWattHours: Double = 0.0,
  val lastSample: PowerSample? = null,
  val invalid: Boolean = false,
  val sampleCount: Int = 0,
)

fun currentMilliAmps(raw: Int, c: PowerConvention): Double?
fun signedDrawMilliWatts(mA: Double, mV: Int, c: PowerConvention): Double?
fun accumulate(acc: EnergyAccumulator, s: PowerSample, c: PowerConvention): EnergyAccumulator
fun milliWattHoursPer1kTokens(mWh: Double, tokens: Int): Double?  // null when tokens <= 0
fun EnergyAccumulator.finish(): Double?  // null when invalid or sampleCount < 2
```
Integration is **trapezoidal**: `ΔmWh = (P_prev + P_now) / 2 × Δt_hours`. Invalidation rules, each one
a named test:
- any sample with `regime != DISCHARGING` → `invalid = true` for the whole window (**sticky** — a
  later return to `DISCHARGING` does not clear it);
- `rawCurrent == Int.MIN_VALUE` or `Int.MAX_VALUE`, or `voltageMilliVolts <= 0` → sentinel, window invalid;
- `Δt <= 0` (duplicate or backwards sample) → **ignore the sample**, return `acc` unchanged;
- `Δt > MAX_GAP_NANOS` (5 s — the sampler stalled) → window invalid, rather than integrating a
  fabricated trapezoid across the gap;
- derived magnitude outside `[0.0, 30_000.0]` mW → sentinel + invalid;
- `sampleCount < 2` → `finish()` returns `null`, **never `0.0`**.

**MIRROR** NAMING_CONVENTION (file/type naming); the file-level `private const val` idiom from
`ThermalGovernor.kt:26-32` for `MAX_GAP_NANOS` / `MAX_PLAUSIBLE_MILLIWATTS`.

**IMPORTS** none beyond Kotlin stdlib. **If this file needs an `android.*` import, the design is
wrong** — move that code to `RelaisPowerSampler.kt`.

**GOTCHA** Immutability is a hard project rule (`~/.claude/rules/common/coding-style.md`): `accumulate`
must return a copy. Also: nanosecond `Long` division must go through `Double` — `deltaNs / 3.6e12`
gives hours; integer-dividing first silently yields `0`.

**VALIDATE** `cd Android/src && ./gradlew testFullOpenDebugUnitTest` (with A2's tests present).

---

### Task A2 — `RelaisPowerTest.kt`: tests 1–11, written FIRST (RED)

**ACTION** Create `Android/src/app/src/test/java/cc/grepon/relais/RelaisPowerTest.kt` **before** A1
compiles green.

**IMPLEMENT** Tests 1–11 from the Testing Strategy table below.

**MIRROR** TEST_STRUCTURE (A) — `RagChunkerTest.kt:21-34`. No `@RunWith`, backtick names, a
deterministic fixture builder in place of a device.

**IMPORTS** `org.junit.Test`, `org.junit.Assert.assertEquals`, `assertNull`, `assertTrue`. Nothing else.

**GOTCHA** **Mutation-check every test before trusting it.** Memory `relais-prove-tests-red-first`:
two shipped regression tests in this repo passed *under the bug they claimed to pin*. Concretely:
invert `DischargeSign` handling, delete the sticky-invalid flag, and remove the `<2 samples` guard —
one at a time — and confirm the matching test goes RED each time. A test that stays green is wrong.

**VALIDATE** `cd Android/src && ./gradlew testFullOpenDebugUnitTest` — RED before A1, green after.

---

### Task A3 — `RelaisPowerSampler.kt`: the platform layer

**ACTION** Create `Android/src/app/src/main/java/cc/grepon/relais/RelaisPowerSampler.kt`.

**IMPLEMENT**
```kotlin
private const val TAG = "RelaisPower"
// SCRAPE_CACHE_MS gates the /metrics read path only (mirrors ThermalGovernor.HEADROOM_CACHE_MS: a
// Prometheus scrape must never be able to hammer the binder). The integrator's ticker MUST NOT go
// through it — see the two-read-paths note below.
private const val SCRAPE_CACHE_MS = 2_000L
private const val SAMPLE_INTERVAL_MS = 1_000L

object RelaisPowerSampler {
  fun register(context: Context)
  fun unregister()

  // ---- TWO read paths, deliberately. Do not collapse them. ----
  /** /metrics + HUD path. Cached SCRAPE_CACHE_MS. Never called by the ticker. */
  fun readCached(): PowerSample?
  /** Integrator path. Always hits the binder; bypasses the cache by construction. */
  internal fun readUncached(): PowerSample?

  fun beginWindow(): WindowHandle?                   // starts the ticker; null if reads unavailable
  fun endWindow(h: WindowHandle?, tokens: Int): EnergyResult?

  // ---- test seams ----
  fun setConventionForTest(c: PowerConvention)
  fun setReaderForTest(reader: (() -> PowerSample?)?)
  fun setClockForTest(nanos: (() -> Long)?)          // drives BOTH the cache TTL and sample timestamps
  fun resetForTest()                                 // clears reader, clock, cached sample, convention
}
```
- `register` resolves `context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager` and loads
  the probe-established default `PowerConvention`.
- A read is: `getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)` for current, plus
  `context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))` — the **sticky**
  broadcast, which returns the last value immediately with no permission and **no registration to
  leak** — for `EXTRA_VOLTAGE`, `EXTRA_TEMPERATURE`, `EXTRA_STATUS`, `EXTRA_PLUGGED`, `EXTRA_LEVEL`,
  `EXTRA_SCALE`. Regime maps from `EXTRA_STATUS`: `BATTERY_STATUS_DISCHARGING`(3) → `DISCHARGING`,
  `CHARGING`(2) → `CHARGING`, `FULL`(5) → `FULL`, anything else → `UNKNOWN`.
- `beginWindow` takes **one synchronous `readUncached()` sample immediately**, then schedules the
  1 Hz ticker on `Executors.newSingleThreadScheduledExecutor { Thread(it, "relais-power") }`. The
  ticker body calls `readUncached()`. `endWindow` shuts the executor down and folds the accumulator
  through `finish()`.
- Every platform call wrapped in `runCatching` → sentinel/null.

> **Why two read paths (this was a real defect in an earlier draft of this plan).** A single cached
> `readNow()` with a 2 s TTL, ticked at 1 Hz, returns the **same `PowerSample` with the same
> `atNanos`** on every other tick. A1's own integrator rule — *"`Δt <= 0` (duplicate or backwards
> sample) → ignore the sample"* — then discards half the ticks. The effective sample rate silently
> becomes **0.5 Hz**, every trapezoid spans 2 s instead of 1 s, and the `sampleCount < 2 → null`
> guard fires on roughly twice as many decodes as modelled. Nothing in the original test plan could
> catch it: the pure tests never construct a sampler, and the Robolectric tests inject through
> `setReaderForTest`, which bypasses the cache by construction — the bug lived in exactly the seam
> no layer exercised (`relais-isolation-testing-blindspot`). Test 17 below closes it.

**MIRROR** ERROR_HANDLING (`ThermalGovernor.kt:101-110` cached-read shape),
SERVICE/HANDLER_PATTERN (`RelaisNodeService.kt:122-131` executor ticker), LOGGING_PATTERN.

**IMPORTS**
```kotlin
import android.content.Context
import android.content.Intent          // ACTION_BATTERY_CHANGED lives HERE
import android.content.IntentFilter
import android.os.BatteryManager       // every EXTRA_* and BATTERY_PROPERTY_* lives HERE
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
```

**GOTCHA** Three, in descending order of how long each would cost you:
1. `Intent.ACTION_BATTERY_CHANGED`, **not** `BatteryManager.ACTION_BATTERY_CHANGED` (which does not
   exist — `BatteryManager` has `ACTION_CHARGING`/`ACTION_DISCHARGING`, which are different things).
   Verified against `android-35/android.jar` this session.
2. This is an `object`, so it carries the same **process-global test-bleed hazard** as `RelaisMetrics`.
   The injected reader *and* the 2 s cached sample both survive between tests in one JVM, so a
   "reader returns null" test would otherwise pass or fail depending on test **ordering**.
   `resetForTest()` must clear both.
3. **Sampling is decode-window-scoped, never always-on** — on plain idle-cost grounds. A 1 Hz binder
   call plus a live thread, held forever on a device whose entire value proposition is low idle draw,
   is a power *feature* that costs power to run. Between requests the idle cost must be exactly zero:
   no thread, no timer, no wakeup.

   **Do not justify this with Doze or with idle-TTL — both arguments are false here, and a reviewer
   who checks them will reopen a settled question.** `RelaisNodeService.kt:97-100` already acquires a
   non-reference-counted `PARTIAL_WAKE_LOCK("relais:node")` for the whole service lifetime (released
   only in `onDestroy`, `:236`), so the node is awake regardless and a sampler thread cannot fight a
   Doze transition the wake lock already prevents. And idle-TTL unload gates on
   `RelaisMetrics.queueDepth()` inside `shouldUnloadIdleEngine` (`RelaisEngine.kt:969-975`), not on
   the absence of timer threads — a sampler thread would not block it either. The decision is right;
   an earlier draft of this plan reached it via the wrong reasoning, three times.

**VALIDATE** `cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`

---

### Task A4 — `RelaisMetrics.kt`: emission, histogram, test seam, stale-KDoc fix

**ACTION** Update `Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt`.

**IMPLEMENT**
- `private val energyBucketBounds = doubleArrayOf(1.0, 2.5, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0)`
  + `energyBucketCounts = LongArray(bounds.size + 1)` + `energyHistLock` + sum/count — copying
  `RelaisMetrics.kt:101-105` exactly.

  > **Derive the top bound from this node's actual decode speed, not from a round number.** An
  > earlier draft used `0.1 … 50.0` mWh, which puts a *typical* completion permanently in `+Inf` and
  > makes every quantile query on the histogram meaningless. Working it from this plan's own quoted
  > figures: at the ~4625 mW example draw, a 200-token completion at the node's 3–8 tok/s takes
  > 25–65 s, which is **29–83 mWh** — already past 50. A 500-token answer at 3 tok/s is ~167 s, or
  > **214 mWh**. The bounds above cover ~1 s (1.3 mWh) through ~13 min (1000 mWh) and leave the
  > common case in the middle of the range, which is where a histogram is informative.
  >
  > **Re-derive this after Task A7**, when the measured watts replace the example figure. If A7 comes
  > back at, say, 2 W rather than 4.6 W, the whole range shifts down by ~2×. Sanity check before
  > merging: `_bucket{le="1000"}` should equal `_count` (nothing in `+Inf`) and
  > `_bucket{le="1"}` should be near zero.
- `fun recordRequestEnergy(mWh: Double, perThousandTokens: Double?)` and
  `fun recordEnergySampleInvalid()`.
- The 10 series in `renderProm`, each with its `# HELP` / `# TYPE` pair. Instantaneous gauges read
  `RelaisPowerSampler.readCached()` **once** per scrape and derive all of them from that one sample.
- `power_draw_milliwatts`, `power_measurement_valid`, `battery_temperature_celsius` in `renderJson`,
  next to `thermal_headroom` (`:460`).
- **Extend `resetIncrementsForTest()` (`:471`)** to clear the energy histogram and the last-value gauge.
- Fix the KDoc at `:38`: `RelaisMetricsLeakTest` → `RelaisMetricsIncrementsTest`.

**The `# HELP` text is not decoration — it carries the whole-device disclosure (see §Summary).** Use
exactly this wording for the three derived series; do not shorten it:

```
# HELP relais_power_draw_milliwatts Whole-device power draw (display, radios, all processes, baseline
# SoC), derived as |I|*V from the battery terminal. Real only while discharging; -1 otherwise. Gate on
# relais_power_measurement_valid.
# HELP relais_decode_window_energy_milliwatt_hours Whole-device energy drawn during a decode window.
# NOT decode-attributable energy: no idle baseline is subtracted and concurrent non-decode work on the
# device is included. Recorded only when the entire window was discharging and sane.
# HELP relais_decode_window_energy_per_1k_tokens_milliwatt_hours The above, normalized per 1000 visible
# completion tokens. Same whole-device caveat. Absent until the first valid window.
```

**MIRROR** HISTOGRAM_PATTERN (`:101-105`, `:175-183`, `:369-378`, `:471-484`); SERVICE/HANDLER_PATTERN
for the scrape-time sibling call (`:395-397`).

**IMPORTS** none new (`RelaisPowerSampler` and `RelaisPower` are same-package).

**GOTCHA** Four rules about unrepresentable values. Each one is a place where the obvious choice is
wrong:

1. **`-1000` is the temperature sentinel, not `-1`** — `-1 °C` is a plausible battery reading and
   would be indistinguishable from "unavailable".
2. **`relais_battery_current_now_milliamps` has NO sentinel and is OMITTED when unreadable.** `-1 mA`
   is a legal reading, so no in-band value can mean "unavailable" without lying. When the read fails,
   do not emit the series at all; `relais_power_measurement_valid 0` carries the signal. (An earlier
   draft said the sentinel convention "cannot apply" here without saying what to do instead, which
   made Test 14 unwritable — hence the explicit rule.)
3. **`relais_decode_window_energy_per_1k_tokens_milliwatt_hours` is ABSENT until first set — never
   `-1`, never `0`.** This is the one place where f19's own `-1` habit is actively dangerous: on a
   plugged-in node the gauge is *never* set, and A10's `RelaisEnergyPerTokenRegression` alert compares
   it against `avg_over_time(…[6h])` **of itself**. A `-1` would make that comparison `-1 > 2 * -1`
   → `-1 > -2` → **true**, paging every plugged-in operator forever. With the series absent, the
   expression has no series to evaluate and the alert is inert — which is the correct behaviour and
   must be stated in `docs/relais-alerts.md` (Task A10).
4. Histograms and counters render `0`, which is a real observation count, not a sentinel. Do not
   describe them as sentinels in acceptance criteria.

**VALIDATE** `cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`

---

### Task A5 — `RelaisPowerMetricsTest.kt`: tests 12–17

**ACTION** Create `Android/src/app/src/test/java/cc/grepon/relais/RelaisPowerMetricsTest.kt`.

**IMPLEMENT** Tests 12–17 from the Testing Strategy table, driving `RelaisMetrics.renderProm` with a
fake reader injected through `RelaisPowerSampler.setReaderForTest`. **Test 17 is the H2 regression
test and is the reason `setClockForTest` exists** — it drives the ticker's read function directly
with a fake clock and asserts the integrator sees one sample per tick, not one per two ticks.

**MIRROR** TEST_STRUCTURE (B) — `RelaisMetricsIncrementsTest.kt:41-67`.

**IMPORTS** `androidx.test.core.app.ApplicationProvider`, `org.robolectric.RobolectricTestRunner`,
`org.robolectric.annotation.Config`, JUnit.

**GOTCHA** Two:

1. `@Before` must call **both** `RelaisMetrics.resetIncrementsForTest()` and
   `RelaisPowerSampler.resetForTest()`.
2. **Do not express test 16 as "run test 14 immediately after test 12".** JUnit 4 gives no method
   ordering guarantee without `@FixMethodOrder`, so an ordering-dependent pair is a flake, not a
   test. Assert both scrapes inside a **single `@Test` method** with the reset between them, as the
   Testing Strategy row now specifies.

**MUTATION CHECK (required before this task counts as done)** — per `relais-prove-tests-red-first`,
each of these must turn a named test RED:
- point the ticker at `readCached()` → test 17 red;
- delete the cache-clear from `resetForTest()` → test 16 red;
- emit `-1` instead of omitting `relais_battery_current_now_milliamps` → test 14 red;
- emit `-1` instead of omitting the per-1k-tokens gauge → test 14 red.
A test that stays green under its own mutation is not pinning anything.

**VALIDATE** `cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`

---

### Task A6 — `PowerReadoutProbe.kt` (**GATE**: everything numeric downstream depends on this)

**ACTION** Create `Android/src/app/src/androidTest/java/cc/grepon/relais/PowerReadoutProbe.kt`.

**IMPLEMENT** Five legs, fail-soft (log every partial result in a `finally`; bound the generation;
never throw before logging), per `.omc/skills/litertlm-native-api-probe-first-expertise.md`:

- **Leg 1 — convention discovery (MUST run UNPLUGGED).** Log a table of raw `CURRENT_NOW`,
  `CURRENT_AVERAGE`, `EXTRA_VOLTAGE`, `EXTRA_STATUS`, `EXTRA_PLUGGED`, `EXTRA_TEMPERATURE`,
  `EXTRA_LEVEL`/`EXTRA_SCALE` at idle, then during a sustained decode. Discharge sign = whichever sign
  appears while `EXTRA_PLUGGED == 0`. Unit follows from magnitude: a phone at idle draws hundreds of
  mA, so a reading in the **hundreds** means mA and in the **hundreds of thousands** means µA.
- **Leg 2 — magnitude sanity.** Idle (screen off, unplugged) vs decode. Assert only that decode draw
  **exceeds** idle draw by a plausible margin and both land inside `[0, 30_000]` mW. Do **not** assert
  an absolute watt figure — that is what this run *produces*, not what it checks.
- **Leg 3 — charging invalidation.** Plugged in: confirm `_valid` → `0` and no energy window is
  recorded. This is the honest-semantics guarantee and the leg most likely to catch a later regression.
- **Leg 4 — window arithmetic on real hardware.** One decode; log sample count, per-sample mW, the
  integrated mWh, and mWh/1k tokens. Confirms the 1 Hz cadence yields ≥2 samples for a real decode.
- **Leg 5 — `ENERGY_COUNTER` availability.** `getLongProperty(BATTERY_PROPERTY_ENERGY_COUNTER)` before
  and after a decode. If it returns anything other than `Long.MIN_VALUE` and the delta is plausible,
  record it — it would give exact windowed energy with no integration at all (see Notes).

Header, copying `ToolCallingProbe.kt:46-52` verbatim in form:
```
 * Run (rango / Pixel 10 / G5, E2B staged) — MUST BE UNPLUGGED for legs 1, 2, 4:
 *   adb -s <serial> shell am instrument -w \
 *     -e class cc.grepon.relais.PowerReadoutProbe \
 *     -e model /storage/emulated/0/Android/data/com.ventouxlabs.relais.izzy/files/litert_community_gemma_4_E2B_it_litert_lm/361a4010ad6d88fc5c86e148e333c0342b99763d/gemma-4-E2B-it.litertlm \
 *     com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Watch: adb logcat -s RelaisPower
```

**MIRROR** PROBE_STRUCTURE — `ToolCallingProbe.kt:39-87`.

**IMPORTS** `androidx.test.ext.junit.runners.AndroidJUnit4`,
`androidx.test.platform.app.InstrumentationRegistry`, `android.os.BatteryManager`,
`android.content.Intent`, `android.content.IntentFilter`, `android.util.Log`, `org.junit.Assume.assumeTrue`.

**GOTCHA** The model path must be the app-external-files form shown above, **not**
`/data/local/tmp/...` (memory `relais-ondevice-verification`: that staging area is gone). The test
applicationId is flavor-dependent. Legs 1/2/4 are **invalid if the device is plugged in** — make the
probe log a loud refusal rather than producing numbers, or someone will run it on the charger and
record garbage in `docs/power-metrics.md`.

**VALIDATE**
```bash
cd Android/src && ./gradlew :app:compileFullOpenDebugAndroidTestKotlin   # compiles, does not run
adb -s 57211FDCG0023C shell am instrument -w \
  -e class cc.grepon.relais.PowerReadoutProbe \
  -e model /storage/emulated/0/Android/data/com.ventouxlabs.relais.izzy/files/litert_community_gemma_4_E2B_it_litert_lm/361a4010ad6d88fc5c86e148e333c0342b99763d/gemma-4-E2B-it.litertlm \
  com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
adb -s 57211FDCG0023C logcat -s RelaisPower
```

---

### Task A7 — run the probe on two SoCs + perfetto cross-check → `docs/power-metrics.md`

> **Blocked on Task A6 result.**

**ACTION** Run `PowerReadoutProbe` on **rango** (Pixel 10 / Tensor G5, GrapheneOS, serial
`57211FDCG0023C` — the spare/destructive unit) **and** **comet** (Pixel 9 / Tensor G4, serial
`4A111FDKD0000C` — the live node; read-only legs only). Then run `scripts/power-crosscheck.sh` on
rango. Write `docs/power-metrics.md`.

**IMPLEMENT** `scripts/power-crosscheck.sh` captures a perfetto trace with `collect_power_rails: true`
over `android.power`, extracts `power.rails.tpu` and `power.S2S_VDD_GPU_uws` (monotonic µWs → slope is
mW), and prints idle vs decode slopes. Two SoCs because the convention is per-OEM-per-kernel and one
device proves nothing about the other. `docs/power-metrics.md` records the verified table in the style
of `docs/litertlm-native-api.md` §7.5: unit, discharge sign, voltage unit, idle mW, decode mW,
`ENERGY_COUNTER` supported y/n, perfetto rail deltas.

**MIRROR** `.omc/skills/grapheneos-ondevice-verification-expertise.md`.

**IMPORTS** N/A (shell + markdown).

**GOTCHA** **Start the trace FIRST**, then trigger the decode inside the window — a small on-device
LLM can finish before a 30 s trace even starts. Require a **negative control** (idle window, rails
flat). Do **not** plan any sysfs fallback: `/sys/class/thermal/*`, `/sys/class/edgetpu/*`, iio ODPM
rails and `dmesg` are all SELinux-sealed from adb shell on GrapheneOS.

**And state the expected relationship up front so nobody reads a mismatch as a failure:**
BatteryManager measures **whole-device** draw (display, radios, SoC, everything); the rails measure
**subsystems**. They will *not* be equal and the rails are a **lower bound**. The cross-check
validates (a) the sign is right and (b) the *delta* in BatteryManager mW at decode start is consistent
with the *sum of rail deltas* to within the same order of magnitude. Record the numbers; do not assert
equality.

**VALIDATE** `docs/power-metrics.md` contains a filled table for both SoCs, and
`RelaisPowerSampler`'s default `PowerConvention` matches the measured value on both.

---

### Task A8 — prove no manifest/permission change

> **Blocked on Task A9** — the permission surface cannot change until the feature is actually wired
> in, so the "after" APK must contain A9's wiring. **But the "before" APK must be captured FIRST**
> (see GOTCHA) — do that at the very start of the branch, before A1.

**ACTION** Diff `aapt dump permissions` on the pre-change and post-change APKs.

**IMPLEMENT**
```bash
# STEP 0 — run this BEFORE writing any code on this branch (or use the CI artifact for the merge-base):
git stash -u && cd Android/src && ./gradlew :app:assembleFullOpenDebug && cd -
cp Android/src/app/build/outputs/apk/fullOpen/debug/app-fullOpen-debug.apk /tmp/relais-BEFORE.apk
git stash pop

# STEP 1 — after A9:
cd Android/src && ./gradlew :app:assembleFullOpenDebug && cd -
cp Android/src/app/build/outputs/apk/fullOpen/debug/app-fullOpen-debug.apk /tmp/relais-AFTER.apk

aapt dump permissions /tmp/relais-BEFORE.apk > /tmp/perms-before.txt
aapt dump permissions /tmp/relais-AFTER.apk  > /tmp/perms-after.txt
diff /tmp/perms-before.txt /tmp/perms-after.txt      # must be empty
grep -c BATTERY_STATS /tmp/perms-after.txt           # must be 0
```

**MIRROR** the CI Play-permission scan gate described in `DEVELOPMENT.md`.

**IMPORTS** N/A.

**GOTCHA** **You cannot build the "before" APK after the fact.** Capture it at the start of the
branch (Step 0 above) or pull the CI artifact for the merge-base — otherwise whoever runs this
discovers they no longer have a baseline and ends up asserting the property instead of testing it.
State the result in the PR as a **checked claim with the diff output pasted**, not as an assumption:
Assumption 1 of this plan is exactly the thing being tested here.

**VALIDATE** empty diff; `BATTERY_STATS` absent.

---

### Task A9 — wire into the engine and the service

**ACTION** Update `RelaisEngine.kt` and `RelaisNodeService.kt`.

**IMPLEMENT**

**(a) `RelaisEngine.kt`, streaming lane — declare BOTH new bindings ABOVE `return try {` (`:685`).**
This is a scoping requirement, not a style preference: see GOTCHA 1.

```kotlin
        // …existing declarations at :673-674:
        val cancelRequested = AtomicBoolean(false)
        val stopThread = AtomicReference<Thread?>(null)
        // …existing fun requestNativeStop() at :675-684 …

        // NEW — declared out here for the same reason the two above are (see :671-672):
        // the finally at :797 must be able to read them.
        val powerWindow = runCatching { RelaisPowerSampler.beginWindow() }.getOrNull()
        val energyTokens = AtomicInteger(0)          // mirror of the try-local `tokens`

        return try {
          …
          var tokens = 0                              // :690, unchanged
          …
          // in the per-token callback, after `tokens++`:
          energyTokens.set(tokens)
          …
        } finally {
          // BEFORE conversation.close() at :801, alongside the existing stopThread join at :800:
          runCatching { RelaisPowerSampler.endWindow(powerWindow, energyTokens.get()) }
            .getOrNull()
            ?.let { … recordRequestEnergy(…) or recordEnergySampleInvalid() … }
          stopThread.get()?.let { runCatching { it.join(2_000) } }
          conversation.close()
        }
```

Two equally acceptable shapes for the token count, pick one and say which in the PR:
- the `AtomicInteger` mirror above (shown; matches the file's existing `AtomicBoolean`/
  `AtomicReference` idiom for exactly this problem), or
- hoist `var tokens = 0` itself from `:690` to above `:685`. Fewer moving parts, but it widens the
  mutable scope of a variable the callback thread writes — the `Atomic*` mirror is the safer default
  and is what the surrounding code already reaches for.

**(b) `RelaisEngine.kt`, blocking tool lane.** `generateWithToolsLocked` is declared at `:829` and
dispatched from `:626-627` — inside `synchronized(lock)`, with its own `try`/`finally` and its own
instance of the same scoping trap. Bracket it the same way: `beginWindow()` after the thermal
cool-down at `:834` and **before** its `try`, `endWindow()` in its `finally`. **Absolute mWh only** —
see GOTCHA 3.

**(c) `RelaisNodeService.kt`:** `RelaisPowerSampler.register(applicationContext)` beside
`ThermalGovernor.register(applicationContext)` at `:103`; `RelaisPowerSampler.unregister()` beside
`ThermalGovernor.unregister()` at `:231`.

**MIRROR** SERVICE/HANDLER_PATTERN (`RelaisNodeService.kt:103, 229-232`); ERROR_HANDLING (`runCatching`
around everything so a sampler failure can never fail an inference).

**IMPORTS** `java.util.concurrent.atomic.AtomicInteger` in `RelaisEngine.kt` if you take the mirror
shape (`AtomicBoolean`/`AtomicReference` are already imported); otherwise none new.

**GOTCHA 1 — this task does not compile if you follow the obvious placement.** A `val` declared
inside the `try` body is **not in scope in that `try`'s `finally`**. `return try {` opens at
`RelaisEngine.kt:685`; `var tokens = 0` is at `:690`; the target `finally` is at `:797`. So the
natural reading — "put `beginWindow()` just before `sendMessageAsync(` at `:706`, call
`endWindow(powerWindow, tokens)` in the finally" — leaves **both** identifiers unresolved and fails
the first compile.

The file already solved this exact problem and documents it at `:671-672`:

> `// finally before conversation.close() so cancel and close never race natively. Declared out`
> `// here (not inside the try body) so the finally can join it.`

...which is why `cancelRequested` (`:673`) and `stopThread` (`:674`) sit where they do. Put
`powerWindow` beside them. **Consequence for sizing:** this is a slightly wider diff than a
two-line insert — the Files to Change estimate reflects that, and any reviewer asking "why is this
declared so far from its use" should be pointed at `:671-672`.

**GOTCHA 2 — the correctness decision of the whole feature.** **Bracket the decode window, not the
request window.** `RelaisMetrics.incInFlight()` at `RelaisEngine.kt:584` runs **before**
`synchronized(lock)` at `:605`, so `relais_queue_depth > 1` means requests are **queued, not
concurrent** — a queued request is blocked on the monitor at `:605` burning no GPU. If energy were
attributed to the request span (`reqStartNs` at `:585` → the outer `finally` at `:805`), a queued
request would be **billed for another request's decode**. Equally, do not start at `:605`: the span
`:605`→`:706` includes the thermal cool-down `Thread.sleep` (`:632`) and `createConversation`
(`:663`), which are real time but are not decode.

**GOTCHA 3 — the tool lane must not report per-token energy.** `generateWithToolsLocked` runs no
per-token callback (its KDoc, `:824-826`: *"Throughput is not measured here (no per-token wall
clock), so decodeTokensPerSec/completionTokens are 0"*). Bracket it for absolute mWh, but **never**
emit mWh-per-1k-tokens there — dividing by a known-zero token count fabricates a number. A1's
`milliWattHoursPer1kTokens` already returns `null` for `tokens <= 0` (test 11b); rely on that rather
than adding a second guard at the call site.

**VALIDATE** `cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`

---

### Task A10 — Grafana panels + alert rules

**ACTION** Update `docs/relais-grafana-dashboard.json` and `docs/relais-alerts.md`.

**IMPLEMENT** New row at `y:39` (the existing last row is the `stat` "Uptime / restarts" at `y:33 h:6`),
`schemaVersion: 39`, 24-col grid, every target templated with `instance="$instance"`:
1. **timeseries "Power draw (mW)"** —
   `relais_power_draw_milliwatts{instance="$instance"} and relais_power_measurement_valid{instance="$instance"} == 1`.
   Panel note: *"blank while plugged in — battery current is net charge flow; device draw is not
   observable from BatteryManager."*
2. **timeseries "Energy per 1k tokens (mWh)"** — `relais_decode_window_energy_per_1k_tokens_milliwatt_hours`, with
   `relais_decode_tokens_per_second` on the right axis (throughput collapse under throttling should
   show up as *worse* mWh/token).
3. **timeseries "Battery temperature / level"** — `relais_battery_temperature_celsius` (left) and
   `relais_battery_level_ratio` (right). A cheap **absolute** number complementing
   `relais_thermal_status`/`relais_thermal_headroom`, which are OS verdicts on a scale.
4. **heatmap "Per-request energy distribution"** —
   `sum by (le) (rate(relais_decode_window_energy_milliwatt_hours_bucket{instance="$instance"}[5m]))`.

Three alerts appended to `docs/relais-alerts.md` in its existing YAML-in-prose format:
```yaml
- alert: RelaisBatteryTemperatureHigh
  expr: relais_battery_temperature_celsius > 42
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Relais {{ $labels.instance }} battery above 42 C for 10m"
    description: "Sustained battery heat shortens cell life on an always-on node; check ventilation and load."

- alert: RelaisRunningOnBattery
  expr: relais_battery_charging == 0 and relais_battery_level_ratio < 0.2
  for: 5m
  labels:
    severity: critical
  annotations:
    summary: "Relais {{ $labels.instance }} on battery below 20%"
    description: "The node is unplugged and will shut down; inference will stop without warning."

- alert: RelaisEnergyPerTokenRegression
  expr: relais_decode_window_energy_per_1k_tokens_milliwatt_hours > 2 * avg_over_time(relais_decode_window_energy_per_1k_tokens_milliwatt_hours[6h])
  for: 30m
  labels:
    severity: warning
  annotations:
    summary: "Relais {{ $labels.instance }} energy per token doubled vs its 6h baseline"
    description: "Efficiency regression — usually thermal throttling (cross-check relais_decode_tokens_per_second) or a backend fallback off the TPU lane."
```
Plus **two** prose notes in the file's voice:

> **`relais_energy_samples_invalid_total` rising is normal on a plugged-in node.** Battery current
> while charging is net charge flow, not device consumption, so energy windows are deliberately
> dropped. Do **not** alert on this counter. Gate every power panel on
> `relais_power_measurement_valid == 1`.

> **`RelaisEnergyPerTokenRegression` is inert on a plugged-in node, by design.**
> `relais_decode_window_energy_per_1k_tokens_milliwatt_hours` is **absent** from the scrape until the
> first valid (discharging) window — see A4 GOTCHA 3. A PromQL expression over a series that does not
> exist produces no samples, so the rule simply never fires. That is correct: there is nothing to
> regress against. It is also why the gauge must never be emitted as `-1` — `-1 > 2 * -1` is **true**,
> which would page every plugged-in operator forever.

**MIRROR** `docs/relais-alerts.md:11-41`.

**IMPORTS** N/A.

**GOTCHA** The `and …_valid == 1` filter is load-bearing: without it the `-1` sentinel drags the
graph and the mean. Validate the JSON parses (`python3 -c "import json;json.load(open(...))"`) — a
hand-edited Grafana JSON that fails to parse is a silent dashboard outage.

**VALIDATE** — assert the **four new panels are present**, not a total count. A `== 14` assertion
breaks the moment feature-20 or feature-22 lands its own panel first (see §Cross-plan coordination):

```bash
python3 - <<'PY'
import json
d = json.load(open('docs/relais-grafana-dashboard.json'))          # parses => not a silent outage
titles = {p.get('title') for p in d['panels']}
want = {"Power draw (mW)", "Energy per 1k tokens (mWh)",
        "Battery temperature / level", "Decode-window energy distribution"}
missing = want - titles
assert not missing, f"missing panels: {missing}"
assert all(p['gridPos']['y'] >= 39 for p in d['panels'] if p.get('title') in want)
print(f"OK — {len(want)} new panels present, {len(d['panels'])} total")
PY
```

---

### Task A11 — copy update

> **Blocked on Task A7 result** — the number must come from a deliberate on-battery run
> cross-checked against perfetto, never from a plugged-in production scrape.

**ACTION** Update `docs/index.html:75`, `README.md:26-27` (the claim is wrapped across **both** lines
— editing only `:27` leaves "draws a few" behind), `docs/RUNBOOK.md`.

**IMPLEMENT** Replace "draws a few watts" with the measured figure **and its conditions** — device,
SoC, model, backend, on-battery, *and* the whole-device scope. The figure is whole-device draw while
serving, not decode-attributable power; say so in the same sentence rather than in a footnote. Add
one paragraph to `docs/RUNBOOK.md`: how to take an on-battery measurement run, why the plugged-in
production node will not show a watts number, and why the metric is named `decode_window`.

**MIRROR** `docs/brand-voice.md:100` — *"Precision over enthusiasm. Numbers, endpoints, watts, model
names."* Quoting a measured number is exactly on-brand.

**IMPORTS** N/A.

**GOTCHA** **Leave `docs/brand-voice.md:30, 96, 132` alone.** Those are style DO/DON'T *examples*, not
product claims; rewriting the voice guide's illustrations is scope creep.

**VALIDATE** — **not** `grep -rn "a few watts"`. That phrase is **line-wrapped in the README**
(`:26` ends `…draws a few`, `:27` begins `watts, and sits on a shelf…`), so the naive grep returns
`brand-voice.md` + `index.html` hits and would return the **identical** set with `README.md`
completely untouched — it can never detect a missed README edit. Use instead:

```bash
# 1. the wrapped README claim is gone (matches the actual wrap point)
grep -n "few$" README.md                      # must return nothing
# 2. index.html's inline claim is gone
grep -n "a few watts" docs/index.html         # must return nothing
# 3. a real measured figure landed in both, with its conditions
grep -n "W (measured" README.md docs/index.html   # must return one hit each
# 4. the style guide's DO/DON'T examples were NOT touched
git diff --stat docs/brand-voice.md           # must be empty
```

## Testing Strategy

### Unit Tests — `RelaisPowerTest.kt` (pure JVM, no Robolectric)

| # | Test | Input | Expected Output | Edge Case? |
|---|---|---|---|---|
| 1 | unit conversion | `raw=-1_180_000`, `MICROAMPS` | `-1180.0` mA | no |
| 2 | unit passthrough | `raw=-1180`, `MILLIAMPS` | `-1180.0` mA | no |
| 3 | sentinel rejection | `raw=Int.MIN_VALUE` / `Int.MAX_VALUE` / `mV=0` | `null` each | **yes** |
| 4 | sign normalization | `-1180` mA, `3920` mV, `DischargeSign.NEGATIVE` | **positive** `4625.6` mW | **yes** |
| 5 | inverted-sign OEM | `+1180` mA, `DischargeSign.POSITIVE` | **positive** `4625.6` mW | **yes** |
| 6 | trapezoid | 3 samples 1 s apart, 1000/2000/3000 mW | `((1000+2000)/2 + (2000+3000)/2) / 3600` mWh, ±1e-9 | no |
| 7 | single sample | 1 discharging sample | `finish()` → `null` (**not** `0.0`) | **yes** |
| 8 | non-monotonic clock | second sample with `Δt <= 0` | accumulator returned **unchanged** | **yes** |
| 9 | sampler stall | `Δt = 6 s` (> 5 s `MAX_GAP`) | window `invalid = true` | **yes** |
| 10 | sticky invalidation | DISCHARGING, **CHARGING**, DISCHARGING | `invalid = true` (later valid samples do **not** clear it) | **yes** |
| 11 | implausible magnitude | derived `35_000` mW | sentinel + `invalid` | **yes** |
| 11b | divide-by-zero guard | `milliWattHoursPer1kTokens(5.0, 0)` | `null` (no `Infinity` in a metric) | **yes** |
| 11c | **immutability** | `accumulate(acc, s, c)` | returns a new instance; input `acc` is `==`-unchanged | **yes** |

### Unit Tests — `RelaisPowerMetricsTest.kt` (Robolectric, `@Config(sdk = [34])`)

| # | Test | Input | Expected Output | Edge Case? |
|---|---|---|---|---|
| 12 | discharging scrape | fake reader → discharging sample | `relais_power_draw_milliwatts <real>` and `relais_power_measurement_valid 1` | no |
| 13 | charging scrape | fake reader → charging sample | `_valid 0`, `relais_power_draw_milliwatts -1`, **and** `relais_battery_current_now_milliamps` still the real signed reading | **yes** |
| 14 | platform unavailable | fake reader → `null` | `_valid 0`; `relais_power_draw_milliwatts -1`; `relais_battery_temperature_celsius -1000`; `relais_battery_current_now_milliamps` **absent** (A4 GOTCHA 2); `..._per_1k_tokens_...` **absent** (GOTCHA 3); histogram/counter render `0`; whole scrape is still valid Prometheus text exposition | **yes** |
| 15 | energy histogram | record 12.8, 51.4, 214.0 mWh (the three worked examples from A4's bucket derivation: a 10 s, a 40 s and a 167 s decode at ~4.6 W) | cumulative `_bucket{le=…}` correct, `_sum 278.2`, `_count 3`, **and `_bucket{le="1000"} == _count`** — nothing lands in `+Inf` | **yes** |
| 16 | test-seam hygiene | **one `@Test` method** that renders a discharging scrape, then calls `resetIncrementsForTest()` + `resetForTest()`, then renders an unavailable scrape | second render shows no trace of the first: histogram cleared, injected reader cleared, cached sample cleared | **yes** |
| **17** | **ticker bypasses the scrape cache** (H2 regression) | `setClockForTest` + `setReaderForTest` returning a *fresh* `atNanos` each call; drive the ticker's read function 4× at simulated 1 s spacing | **4 distinct samples accepted**, 3 trapezoid intervals, each spanning 1 s. Mutation check: point the ticker at `readCached()` and this test MUST go red (2 samples, 2 s intervals) | **yes** |

### Edge Cases checklist

- [ ] Device where `getIntProperty` returns `Int.MIN_VALUE` (unsupported) → all sentinels, scrape valid
- [ ] Device reporting mA instead of µA → caught by the >30 W plausibility guard as a loud sentinel
- [ ] OEM with inverted discharge sign → `PowerConvention` parameter, probe-established
- [ ] Plug/unplug **mid-decode** → window dropped, `_invalid_total` incremented, no interpolation
- [ ] Sub-second decode (<2 samples) → no energy reported, **not** `0.0`
- [ ] Zero-token completion (tool path, AICore) → no mWh/1k-tokens emitted
- [ ] Queued second request → billed for **its own** decode only, never the first request's
- [ ] Sampler executor throws → inference still succeeds; energy silently absent
- [ ] Scrape while no inference is running → gauges fresh, no histogram change
- [ ] Idle node → **zero** background work (no thread, no timer, no wakeup)
- [ ] Battery reading exactly `-1` mA → still distinguishable from "unavailable" via `_valid`
- [ ] Battery temperature exactly `-1 °C` → not confused with the `-1000` sentinel
- [ ] Tests run in either order → no process-global bleed (test 16)

## Validation Commands

> **None of these were executed during planning.** CLAUDE.md forbids running Gradle unless asked, and
> this planning pass did not. Treat every command below as an instruction to the implementer, not as
> a verified-green result.

```bash
# JVM unit tests — the CI unit-test job (device-free). Run after A2, A4, A5, A9.
cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest

# Compile (do NOT run) the on-device probe suite. Run after A6.
cd Android/src && ./gradlew :app:compileFullOpenDebugAndroidTestKotlin

# One debug APK (never `assembleDebug` — ambiguous across the dist x policy flavor matrix).
cd Android/src && ./gradlew :app:assembleFullOpenDebug

# Probe — rango (Pixel 10 / G5, GrapheneOS, spare unit). MUST BE UNPLUGGED for legs 1, 2, 4.
adb -s 57211FDCG0023C shell am instrument -w \
  -e class cc.grepon.relais.PowerReadoutProbe \
  -e model /storage/emulated/0/Android/data/com.ventouxlabs.relais.izzy/files/litert_community_gemma_4_E2B_it_litert_lm/361a4010ad6d88fc5c86e148e333c0342b99763d/gemma-4-E2B-it.litertlm \
  com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
adb -s 57211FDCG0023C logcat -s RelaisPower

# Probe — comet (Pixel 9 / G4, the LIVE node: read-only legs 1/3/5 only, never a destructive run).
adb -s 4A111FDKD0000C shell am instrument -w \
  -e class cc.grepon.relais.PowerReadoutProbe#conventionDiscovery \
  com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner

# Perfetto power-rail cross-check (rango only).
scripts/power-crosscheck.sh 57211FDCG0023C

# Dashboard JSON must still parse and must gain the 4 NEW panels (by title, not by total count —
# a sibling plan may land its own panel first). Full script in Task A10's VALIDATE.
python3 -c "import json;d=json.load(open('docs/relais-grafana-dashboard.json'));t={p.get('title') for p in d['panels']};assert 'Power draw (mW)' in t and 'Energy per 1k tokens (mWh)' in t and 'Battery temperature / level' in t and 'Decode-window energy distribution' in t;print('ok',len(d['panels']))"

# report-worker is NOT touched by this feature — do not run its job.
```

### Manual Validation

- [ ] All power series present — **grep the metric names, not the `relais_battery` prefix.** Only 5 of
      the 10 series contain that substring; `relais_power_draw_milliwatts`,
      `relais_power_measurement_valid`, and the three energy series can never match it, so the prefix
      grep passes while half the feature is missing:
      ```bash
      curl -sk -H "Authorization: Bearer <key>" https://<phone-ip>:8443/metrics \
        | grep -E '^relais_(battery_(current_now_milliamps|voltage_volts|temperature_celsius|charging|level_ratio)|power_(draw_milliwatts|measurement_valid)|decode_window_energy_(milliwatt_hours_count|per_1k_tokens_milliwatt_hours)|energy_samples_invalid_total)\b'
      ```
      On battery: 10 lines. Plugged in: 8 — `relais_decode_window_energy_per_1k_tokens_milliwatt_hours`
      is legitimately **absent** (A4 GOTCHA 3), and the histogram `_count` reads `0`.
- [ ] `curl -k -H "Authorization: Bearer <key>" -H "Accept: application/json" https://<phone-ip>:8443/metrics | python3 -m json.tool` —
      the 3 new HUD fields present and JSON-valid
- [ ] Unplug the phone → `relais_power_measurement_valid` flips `0` → `1` within one scrape interval
- [ ] Plug it back in mid-inference → that request records **no** energy;
      `relais_energy_samples_invalid_total` increments by exactly 1
- [ ] Run one inference on battery → `relais_decode_window_energy_milliwatt_hours_count` increments by 1 and
      `relais_decode_window_energy_per_1k_tokens_milliwatt_hours` is > 0
- [ ] Leave the node idle 10 min → `dumpsys` shows no `relais-power` thread alive
- [ ] Promtool: `promtool check metrics < scrape.txt` reports no errors
- [ ] `aapt dump permissions` diff is empty (Task A8)

## Acceptance Criteria

- [ ] On a device where **every** battery read fails, the scrape is still valid Prometheus text
      exposition and each series behaves as specified in A4's GOTCHA: `relais_power_draw_milliwatts`
      is `-1`, `relais_battery_temperature_celsius` is `-1000`, `relais_power_measurement_valid` is
      `0`, `relais_battery_current_now_milliamps` and the per-1k-tokens gauge are **absent**, and the
      histogram/counter render `0`. (Not "all 10 render as sentinels" — a zero-observation histogram
      and a zero counter render `0`, which is a real value, not a sentinel.)
- [ ] The whole-device caveat is stated in all four places it is needed: the `# HELP` text of the
      three derived series, `docs/power-metrics.md`, the README figure, and `docs/index.html` — and
      the metric names say `decode_window`, not `request`
- [ ] `relais_power_measurement_valid` is `0` whenever the device is plugged in, and **no** energy
      window is recorded in that state
- [ ] Per-request energy is attributed to the **decode window** (`RelaisEngine.kt:706` → `:797`),
      demonstrated by a queued second request not being charged for the first request's decode
- [ ] `relais_decode_window_energy_per_1k_tokens_milliwatt_hours` is never emitted from a zero-token completion
- [ ] Zero background work while idle: no thread, no timer, no wakeup between requests
- [ ] `aapt dump permissions` output is byte-identical before and after; `BATTERY_STATS` absent
- [ ] The default `PowerConvention` matches the **probe-measured** value on **both** rango and comet,
      recorded in `docs/power-metrics.md`
- [ ] Every new test fails when its guard is inverted (mutation-checked, per task A2's GOTCHA)
- [ ] `resetIncrementsForTest()` clears the energy histogram; `resetForTest()` clears the injected
      reader and the sample cache; tests pass in any order
- [ ] The stale `RelaisMetricsLeakTest` KDoc reference at `RelaisMetrics.kt:38` is fixed
- [ ] Dashboard JSON parses and every power panel is gated on `relais_power_measurement_valid == 1`
- [ ] `docs/relais-alerts.md` states that `relais_energy_samples_invalid_total` must **not** be alerted on
- [ ] The watts figure in `README.md` / `docs/index.html` is a measured number with its conditions stated

## Completion Checklist

- [ ] **Patterns followed** — `-1` sentinel (`ThermalGovernor.kt:101-110`), 2 s cache (`:28`),
      `*ForTest` seams (`:163-173`), `ScheduledExecutorService` ticker (`RelaisNodeService.kt:122-131`),
      histogram shape (`RelaisMetrics.kt:101-105, 175-183`), pure/Android file split
- [ ] **Error handling** — every platform read in `runCatching`; a sampler failure can never fail an
      inference or a scrape; no error silently swallowed without a sentinel or a counter
- [ ] **Logging** — file-level `private const val TAG = "RelaisPower"`; `Log.w`/`Log.e` carry the throwable
- [ ] **Tests** — 13 pure-JVM (1–11c) + 6 Robolectric (12–17), all mutation-checked RED-first; three
      JVM flavor variants green. **Test 17 specifically must go red when the ticker is pointed at
      `readCached()`** — without that, the H2 defect (1 Hz ticker against a 2 s cache silently
      halving the sample rate) is untestable and will recur
- [ ] **No hardcoded values** — cache interval, sample interval, max gap, plausibility ceiling and
      histogram bounds are named `private const val`s; the unit/sign convention is a parameter
- [ ] **Docs updated** — `docs/power-metrics.md` (new), `docs/relais-alerts.md`,
      `docs/relais-grafana-dashboard.json`, `docs/RUNBOOK.md`, `README.md`, `docs/index.html`
- [ ] **No scope additions** — nothing from the NOT Building list crept in; feature-20 untouched
- [ ] **Self-contained** — no new dependency, no new permission, no Gradle-config change, no R8 keep rule
- [ ] **Immutability** — `accumulate` returns a new `EnergyAccumulator` (test 11c)
- [ ] **File size** — both new files well under the 800-line target; `RelaisMetrics.kt` grows ~70 lines
- [ ] **Independent review** — `code-reviewer` + `critic` consensus before merge; never self-approve

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **The headline watts number is unavailable in the primary (plugged-in) deployment** | **Certain** | **High — product, not code** | Designed for explicitly: `_valid` series, dashboard note, alert-doc note, RUNBOOK paragraph. The marketing figure comes from a deliberate on-battery run. **Do not let a reviewer "fix" this by reporting `\|net\|` while charging** — that is the `relais-claim-stronger-than-code` failure mode |
| Unit/sign differs between G4 and G5, or GrapheneOS vs stock | High | High | Convention is a parameter, probe-established per SoC on two devices; the >30 W plausibility guard turns a misdetection into a loud sentinel instead of a silent 1000× error |
| BatteryManager mW and perfetto rails disagree | High | Low | **Expected** — whole-device vs subsystem scope; rails are a lower bound. The cross-check validates sign and delta-consistency, not equality. Stated up front in Task A7 so it is not read as a failure |
| 1 Hz is too coarse for short decodes | Medium | Medium | Synchronous first sample at `beginWindow`; <2 samples → *no* energy rather than a bad one. Raise to 2 Hz only if A7 shows most decodes dropping. **This risk was previously understated by 2×** because the ticker read through the 2 s scrape cache — fixed by the `readUncached()` split in A3 and pinned by test 17 |
| **The energy series are read as "what this request cost"** when they are whole-device draw during a decode window | **High** | **Medium — product honesty** | Metric names say `decode_window`; the caveat is in `# HELP`, `docs/power-metrics.md`, the README and `docs/index.html`. No baseline subtraction is attempted (see Notes → *Why no baseline subtraction*), and the perfetto cross-check in A7 bounds the README figure |
| Editing the `finally` at `RelaisEngine.kt:797-802` disturbs conversation cleanup | Low | High | The added call is `runCatching`-wrapped and placed **before** `conversation.close()`; the existing join-then-close ordering is untouched. Note this is **not** a two-line diff — the scoping constraint (A9 GOTCHA 1) also moves declarations above `:685`, and the same bracket is repeated in the blocking tool lane |
| Binder read cost inside the decode loop | Low | Medium | Reads run on the sampler's own thread, never the callback/decode thread; 2 s cache; ~1 read/s against multi-second decodes |
| Process-global state bleeding across tests | Medium | Low | `resetIncrementsForTest` extended **and** `resetForTest` added; test 16 asserts ordering-independence |
| Probe run on a plugged-in device produces garbage recorded as truth | Medium | High | The probe refuses legs 1/2/4 when `EXTRA_PLUGGED != 0` and logs a loud refusal |
| R8 in release builds | Low | Low | No reflection, no serialization, no JNI — nothing here earns a keep rule. Called out because every rule in `proguard-rules.pro` was earned from a real on-device failure (memory `relais-R8-minification-CI-blindspot`) and this feature earns none |
| Someone alerts on `relais_energy_samples_invalid_total` and pages nightly | Medium | Low | Explicit "do not alert on this" note in `docs/relais-alerts.md` (Task A10) |

## Cross-plan coordination

This plan is **not** independent of its siblings. Four shared hunks will conflict textually; none
conflict semantically. Checked 2026-09-07 against the sibling plans in `.claude/PRPs/plans/`.

| Shared hunk | feature-19 (this plan) | Collides with | Nature |
|---|---|---|---|
| `RelaisEngine.kt`, the `:663`–`:706` region | `beginWindow()` bracket (A9) | **feature-20** inserts `convStartNs` before `:663` and `sendStartNs` before `:706` | Adjacent inserts in one region — textual |
| `RelaisMetrics.kt:471-484` `resetIncrementsForTest` | extends it (A4) | **feature-20** and **feature-22** both extend it | Three-way textual conflict; all three are additive |
| `RelaisMetrics.kt:101-105` histogram region | new energy histogram (A4) | **feature-20** adds a TTFT histogram | Adjacent inserts — textual |
| `docs/relais-grafana-dashboard.json` | +4 panels at `y:39` (A10) | **feature-20** adds a TTFT panel; **feature-22** adds its own | Grid-position collision if two plans both claim `y:39` |

**Merge order — feature-20's B1-A lands first** (per `.claude/HANDOFF.md`; it is the smaller,
already-approved PR). **This plan rebases onto it**, which means:

- Take feature-20's `convStartNs`/`sendStartNs` declarations as given and place `powerWindow`
  beside them at `:673-674` rather than re-deriving the region.
- Re-read `resetIncrementsForTest` before extending it — it may already have feature-20's TTFT reset.
- **Claim the next free dashboard row, not `y:39` unconditionally.** A10's VALIDATE asserts the four
  new panels are **present** rather than asserting a total panel count, precisely so landing second
  does not break it.

feature-22 already documents its side of this overlap (`feature-22-idle-unload.plan.md:628-631`) and
correctly assigns the `RelaisMetrics.kt:38` KDoc fix to **this** plan. This section is the
reciprocal note it was owed.

## Notes

### Why no baseline subtraction (the H3 decision, made explicitly)

`BatteryManager` reports whole-device draw. The honest options were (a) subtract a measured idle
baseline and report an attributable figure, or (b) report the whole-device figure and disclose it.
**This plan chooses (b).**

Baseline subtraction is not a small addition. It needs its own measurement regime — screen off,
radios in a known state, no other app scheduled — repeated per device and per Android version, and
it needs its own error budget, because subtracting two noisy whole-device numbers produces a
difference noisier than either. A baseline captured under different conditions than the decode
window is worse than no baseline: it yields a confident-looking small number that is wrong by an
unknown amount, which is exactly the failure mode this plan exists to avoid on the charging axis.

The perfetto rail cross-check in Task A7 is already the independent authority for the README figure,
and it bounds the claim from below. If a future revision wants an attributable number, it should
come from perfetto rails on a deliberate measurement run — not from arithmetic on this series.

### Findings from the critic review that were DECLINED

- **Declined: LOW-1 ("`RelaisMetricsIncrementsTest.kt` snippet cited at `:41-67` is off by one at the
  top of the quoted block; `@RunWith` is at `:40`").** Re-checked against the file: `:41` is
  `@RunWith(RobolectricTestRunner::class)`, `:42` is `@Config(sdk = [34])`, `:43` is the class
  declaration. The citation start is correct as written and the finding is itself off by one; no
  change made.

### Decisions

1. **Decode window, not request window.** Forced by `incInFlight()` at `RelaisEngine.kt:584` preceding
   the lock at `:605`: queue depth counts *queued* requests, so request-span attribution would bill a
   queued request for someone else's decode. This is a correctness requirement, not a refinement.
2. **Pure/Android file split.** `RelaisPower.kt` has no Android import at all, so the integrator and
   normalizer — where all the subtle bugs live — are testable with plain JUnit. Matches CLAUDE.md's
   style rule and avoids adding Robolectric surface.
3. **Sentinel + separate `_valid` series**, rather than omitting invalid samples. `-1` is a legal
   value for the raw current gauge, so validity genuinely needs its own series.
4. **Decode-scoped sampler, not always-on.** Idle cost must be zero on a node whose whole pitch is
   low idle draw — a power feature should not itself cost power at rest. **Not** because of Doze
   (a permanent `PARTIAL_WAKE_LOCK` is already held, `RelaisNodeService.kt:97-100`) and **not**
   because of idle-TTL (which gates on `queueDepth()`, `RelaisEngine.kt:969-975`).

### Alternatives considered and rejected

- **Report `|net current|` as draw while charging.** Rejected: produces a near-zero number that makes
  the product look free. This is the single thing a well-meaning reviewer is most likely to "fix";
  the `_valid` series and this note exist to stop that.
- **`BATTERY_STATS` / `HealthStats`.** Rejected: signature-level permission, defeats the
  no-new-permission property that makes this feature cheap and Play-safe.
- **Always-on 1 Hz sampler with a rolling window.** Rejected on idle-cost grounds only (see above);
  the Doze / idle-TTL version of this argument is wrong and must not be repeated.
- **Coroutine-based ticker.** Rejected: `ScheduledExecutorService` is the observed house style for
  these tickers (`RelaisNodeService.kt:122-131`, `ThermalGovernor`'s own executor).
- **Deriving energy purely from `BATTERY_PROPERTY_ENERGY_COUNTER`.** Rejected *as the design*, kept as
  a measured possibility (Task A6 Leg 5): the constant exists (`= 5`, `getLongProperty`, nWh) and
  would give exact windowed energy as a simple delta with no trapezoid and no voltage read — but it
  is unsupported on most devices (`Long.MIN_VALUE`). If Leg 5 shows Tensor supports it, prefer it as
  a fast path and keep the integrator as the fallback; the same charging-invalidation rules still
  apply, because it is still *battery* energy.

### Open questions for the user

1. Should `relais_power_draw_milliwatts` be **omitted** while invalid rather than emitting `-1`?
   Omission is arguably cleaner Prometheus (a missing sample is a real signal) but breaks naive
   `avg()` panels differently. Plan currently says `-1` + `_valid`; revisit if A7 shows the dashboard
   reads badly.
2. Should the blocking tool path get a token count so it can report mWh-per-1k-tokens too? Out of
   scope here — it would need a per-token seam that path lacks.
3. Is a per-**backend** energy label worth the cardinality (`TPU_LITERTLM` vs `GPU_LITERTLM`)? It is
   bounded (4 enum values) and would directly quantify the TPU lane's efficiency win. Deferred to v2,
   but it is the most interesting follow-up given the measured 8.51 vs 3.03 tok/s gap.
4. Is comet (the live node) acceptable for the read-only convention leg, or should both runs be on
   rango only? The plan assumes read-only legs on comet are safe; say otherwise and A7 drops to one SoC
   plus a stated caveat.

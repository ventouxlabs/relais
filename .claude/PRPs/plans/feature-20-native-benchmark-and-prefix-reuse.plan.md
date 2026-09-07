# Plan: Native benchmark metrics + time-to-first-token (feature-20)

## Summary

Relais reports a wall-clock decode estimate and **no time-to-first-token at all**, on a node where
prefill dominates the wait. This plan ships TTFT and, conditionally, swaps the estimate for real
native numbers:

- **B1-A (unconditional, ships first).** Capture two new timestamps in `RelaisEngine.generate` and
  emit an honest TTFT as a Prometheus histogram plus a response field. Needs nothing from the native
  API. Small, self-contained, independently valuable.
- **B0 → B1-B (conditional).** `Conversation.getBenchmarkInfo()` is **a documented dead end on
  litertlm 0.11.0** — the brief that assigned this work said otherwise and was wrong. The repo now
  pins **0.12.0**, so the question is genuinely open and answerable **statically, without a device**.
  B0 is that static gate; B1-B proceeds only if it finds a hook.

**Prefix reuse across HTTP requests (formerly "B2") has been cut from this plan** after review —
its cache key leaked multimodal attachments, its central safety invariant is false on three shipping
paths, and its acceptance gate could not measure its own effect. It is preserved as a deferred
appendix at the end of this document, with the findings that killed it, so the next person does not
rediscover the design and then rediscover the leak. Do not implement from the appendix.

## User Story

- **As an** operator running agent workloads against a Relais node with a large system prompt,
- **I want** `/metrics` and the response body to tell me how long the node spends before the first
  token, and how much of that was prefill,
- **So that** I can tell prefill cost from decode cost when tuning prompts, instead of inferring it
  from a single end-to-end latency number that also includes queue wait.

## Problem → Solution

### The premise correction (read this first — it changes the shape of B1)

**This plan was assigned on a false premise.** The brief said `Conversation.getBenchmarkInfo()` is
"marked *available* in the inventory". `docs/litertlm-native-api.md` contains **two contradictory
claims**:

- **`docs/litertlm-native-api.md:31`** — the `BenchmarkInfo` row in the §1 table: *"Also reachable via
  `Conversation.getBenchmarkInfo()`. **Opportunity:** real prefill/decode tok/s + TTFT vs Relais's
  current wall-clock estimate."*
- **`docs/litertlm-native-api.md:164` + the §8 footnote at `:169`** — **"DEAD END (0.11.0)"**, with a
  measured failure: `ExperimentalFlags.enableBenchmark = true` then `conversation.getBenchmarkInfo()`
  throws `INTERNAL: Benchmark is not enabled. Please make sure the BenchmarkParams is set in the
  EngineSettings.`; `javap` on the AAR shows **no public `BenchmarkParams` and no `EngineSettings`**;
  `EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumTokens, maxNumImages, cacheDir)`
  has **no benchmark hook**. The only populated `BenchmarkInfo` comes from the standalone
  `BenchmarkKt.benchmark(modelPath, backend, …)` one-shot, which **re-loads the model** and therefore
  cannot run against the resident serving engine.

Line 164 is the later, evidence-backed claim, and `:169` says outright *"the table's prior 'available'
was wrong."* Corroborated in code and findings: `RelaisEngine.kt:686-687` (*"BenchmarkInfo only
populates via the library's benchmark() path, not normal conversations"*), `SPIKE-FINDINGS.md:150-151`,
`MainActivity.kt:129` (`ExperimentalFlags.enableBenchmark = false`), and the learned skill
`.omc/skills/litertlm-native-api-probe-first-expertise.md`, which uses this exact case as its worked
example.

**But the dead end was verified on 0.11.0 and the repo now pins 0.12.0**
(`docs/litertlm-native-api.md:14`). So the question is genuinely open — and answerable **statically,
without a device**. That is Task B0.

### B1 — timing

**Problem.** `relais_decode_tokens_per_second` is a wall-clock estimate over visible tokens, and there
is **no TTFT metric at all**, despite TTFT being the number that actually characterises an on-device
node (prefill dominates: a 2k-token prompt spends most of its wall clock before token one). Worse, the
one latency series that exists (`relais_inference_duration_seconds`) is measured from `reqStartNs`
(`RelaisEngine.kt:585`), which is taken **before** the engine lock at `:605` and therefore includes
queue wait — useful as request latency, useless as a model-speed signal.

**Where the clock starts is the whole design question, and an earlier draft of this plan got it
wrong.** That draft proposed a single new `sendStartNs` immediately before
`conversation.sendMessageAsync(` at `:706`, and asserted the resulting figure was *"prefill + first
decode step: the honest TTFT."* **The repo's own comment says the opposite.**
`RelaisEngine.kt:633-638`, sitting directly above `createConversation` at `:663`:

> `// Seed the system prompt + prior history into the conversation at creation via`
> `// ConversationConfig (systemInstruction + initialMessages). LiteRT-LM prefills these as`
> `// context; only the live user message below triggers a decode — so a multi-turn request`
> `// costs ONE generation, not one per history turn.`

If that holds, the system-prompt/history prefill — the dominant term, the ~18 s on a 2k-token prompt —
lands at `:663`, **before** `:706`. A clock started at `:706` would exclude the very thing it was
introduced to measure. Under CLAUDE.md's native-API-first rule, asserting unverified native behaviour
either way is the specific sin, so this plan **does not assert where prefill lands**. It measures.

**Solution — capture two timestamps, not one, and let the first scrape settle the question.**

| Timestamp | Placed | Excludes |
|---|---|---|
| `convStartNs` | immediately before `createConversation` at `:663` | queue wait (`:605` lock), and the thermal cool-down `Thread.sleep` at `:632`, which sits *above* `:663` |
| `sendStartNs` | immediately before `sendMessageAsync(` at `:706` | additionally, conversation creation + whatever prefill happens there |

Both are subtracted from the already-recorded `firstTokenNs` (`:744`), giving three numbers:

- **`relais_time_to_first_token_seconds`** = `firstTokenNs - convStartNs` — **this is the shipped
  TTFT**, and it is the user-visible wait for the first token with queueing and cool-down excluded.
  It is the honest answer to "how long before the node says anything."
- **`relais_decode_start_latency_seconds`** = `firstTokenNs - sendStartNs` — the decode-only figure,
  kept as a separate series rather than folded in.
- **The gap between them** = `sendStartNs - convStartNs` — **the prefill measurement**. It converts
  the assumption above into data on the very first request. If it is large, the `:633-638` comment is
  right and `convStartNs` was the only correct baseline; if it is ~0, prefill is lazy and the two
  series will agree. Either outcome is recorded in Task B1-A4.

None of this needs anything from the native API, and it ships regardless of B0. The native
`BenchmarkInfo` swap (B1-B) is strictly conditional on B0.

> **Correction to a framing error in the earlier draft:** it said TTFT is *"already measured at
> `RelaisEngine.kt:744` and then thrown away."* That is wrong twice. `firstTokenNs` **is** consumed,
> at `:784` (`val decodeSec = if (lastTokenNs > firstTokenNs) …`), and it is a bare timestamp, not a
> TTFT — there is no baseline recorded anywhere to subtract from it. B1-A is therefore not "surface a
> number that already exists"; it is "choose a baseline, defend the choice, and measure it." That is
> the framing error that let the baseline question go unasked in the first place.

### Prefix reuse — cut from this plan

The original assignment paired B1 with reusing a warm `Conversation` across HTTP requests so a long
system prompt is not re-prefilled every time. **That half is deferred and is not in this plan's task
list.** It survives only as the *Deferred: prefix reuse* appendix at the end of this document, which
records the design, the three defects that killed it, and what would have to be true to revive it.
Nothing in the Step-by-Step Tasks, Testing Strategy, Validation Commands, Acceptance Criteria, or
Completion Checklist below depends on it.

## Assumptions (stated, not verified)

1. **`getBenchmarkInfo()` is still a dead end on 0.12.0.** Most likely — the `EngineConfig` arity is
   unchanged as far as anyone has looked. **Not verified**: the AAR is not in the local Gradle cache
   (see Task B0's GOTCHA), so the dump could not be run in this planning pass.
2. **`Session.runPrefill`/`runDecode` are real but unused** (`docs/litertlm-native-api.md:27, 161`,
   marked "unverified"). `Session` has **no chat template** — using it means hand-rolling Gemma-4's
   turn format, which is the exact anti-pattern the native-API-first rule exists to prevent.
3. **Where the system-prompt/history prefill actually happens is UNKNOWN.** `RelaisEngine.kt:633-638`
   says conversation-creation time; nothing in this repo has measured it. This plan is built so that
   the first scrape answers the question rather than depending on the answer — see §B1 — timing.
4. **Requests never overlap in the engine** — verified: everything from `RelaisEngine.kt:605` to the
   `finally` at `:797` runs under `synchronized(lock)`.
5. **A `null` TTFT is a normal outcome, not an error.** The blocking tool lane
   (`generateWithToolsLocked`, dispatched at `:626-627`) runs no per-token callback, so `firstTokenNs`
   is never set there. See Task B1-A3a.

## Metadata

| Field | Value |
|---|---|
| **Complexity** | **Medium** (B1-A is Small and unconditional; B0 is Small; B1-B is Medium and conditional on B0) |
| **Source PRD** | N/A |
| **PRD Phase** | N/A |
| **Estimated Files** | **7** — B1-A: 5 (`RelaisEngine.kt`, `RelaisMetrics.kt`, `RelaisHttpServer.kt`, `RelaisTtftMetricsTest.kt` (new), `RelaisUsageBlockTest.kt`) · B1-A4: +1 (`docs/litertlm-native-api.md`) · B1-B: +1 (`docs/litertlm-native-api.md` again, plus `RelaisEngine.kt`/`RelaisMetrics.kt` already counted) |
| **Branches** | `feat/relais-ttft-metrics` (B1-A + B1-A4, ship first) · `spike/relais-native-benchmark` (B0 → B1-B) |
| **Recommended split** | **Two PRs.** B1-A is small, safe and independently valuable — do **not** hold it behind B0. B0's most likely outcome is "still a dead end," which is itself a doc fix worth landing. |

## UX Design

Not "N/A — internal change": B1-A changes the **client-visible response shape** on five emission sites.

**Before** — non-streaming `chat.completion` body:

```json
{
  "id": "chatcmpl-…", "object": "chat.completion", "model": "gemma-4-e4b-it",
  "choices": [{"index": 0, "message": {…}, "finish_reason": "stop"}],
  "usage": {"prompt_tokens": 214, "completion_tokens": 87, "total_tokens": 301},
  "x_relais_usage_note": "prompt_tokens_estimated"
}
```

**After** — one new top-level extension field; `usage` stays OpenAI-schema-clean:

```json
{
  "id": "chatcmpl-…", "object": "chat.completion", "model": "gemma-4-e4b-it",
  "choices": [{"index": 0, "message": {…}, "finish_reason": "stop"}],
  "usage": {"prompt_tokens": 214, "completion_tokens": 87, "total_tokens": 301},
  "x_relais_usage_note": "prompt_tokens_estimated",
  "x_relais_ttft_ms": 2481
}
```

**After** — `/metrics` gains a TTFT histogram beside the existing throughput gauge:

```
relais_decode_tokens_per_second 5.80
relais_time_to_first_token_seconds_bucket{le="2"} 4
relais_time_to_first_token_seconds_bucket{le="5"} 11
relais_time_to_first_token_seconds_sum 38.4
relais_time_to_first_token_seconds_count 12
```

### Interaction Changes

| Touchpoint | Before | After | Notes |
|---|---|---|---|
There are **five** `x_relais_usage_note` sites but only **four** of them can ever carry a TTFT. The
fifth is structurally excluded, and that is a property to test, not a gap to close.

| Touchpoint | Before | After | Notes |
|---|---|---|---|
| Non-streaming `chat.completion` body (`RelaisHttpServer.kt:1300`) | `x_relais_usage_note` only | `+ x_relais_ttft_ms` | Top level, **not** inside `usage`. **Live site** |
| `handleToolCompletion` non-streaming body (`:1644`) | same | **field ABSENT** | **Dead for TTFT, by construction.** `handleToolCompletion` (`:1622`) reaches it via `generateWithNodeTools` (`:1569`) → `RelaisEngine.generate` **with tools set**, which returns at `:626-627` into `generateWithToolsLocked` — a blocking lane with no per-token callback, so `firstTokenNs` is never assigned and `timeToFirstTokenSec` is `null`. `attachRelaisExtras` omits the field on `null`, so this site is the **absence** test |
| `handleStructuredCompletion` **non-streaming response body** (`:1747`) | same | `+ x_relais_ttft_ms` | **Live site.** Not a "single-chunk branch" — no such path exists. `handleStructuredCompletion` is declared at `:1682`; its `if (stream)` at `:1690` **400s** (`"stream and response_format cannot be combined"`), so `:1747` is simply its non-stream body. It calls plain `RelaisEngine.generate` with no tools, so it does get a value |
| SSE **finish chunk**, `if (!includeUsage)` (`:1349`) | usage on the finish chunk | `+ x_relais_ttft_ms` | **Live site.** Terminal chunk, value is final |
| SSE **usage chunk**, `if (includeUsage)` (`:1356`) | separate empty-choices usage chunk (#175) | `+ x_relais_ttft_ms` | **Live site.** **Mutually exclusive** with `:1349` |
| SSE first delta chunk | — | **unchanged, deliberately** | TTFT is not final there for the reasoning-then-visible case |
| `GET /metrics` Prometheus | no TTFT | `relais_time_to_first_token_seconds` + `relais_decode_start_latency_seconds` histograms | Label-free (security M6) |
| `GET /metrics` JSON HUD | `inference_p50_seconds` | `+ ttft_p50_seconds` | Beside the existing latency field |
| Grafana | 10 panels | +1 TTFT panel | Claim the next free row; do not hardcode a total (see §Cross-plan coordination) |
| AICore / blocking-tool paths | no TTFT | **field omitted, never `0.0`** | No per-token seam exists on those paths |

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| **P0** | `docs/litertlm-native-api.md` | 14, 27, 31, 161, 164, **169** | The pinned version, the `Session` row, the contradictory line 31, and §8's evidence-backed DEAD END verdict. **Line 169 is the whole reason B1-B is gated** |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | 578-612, **626-645**, 647-663, 675-690, 700-712, 738-752, 778-810 | The lock discipline, the tool-lane early return at `:626-627`, the `:633-638` prefill comment that Task B1-A1 exists to test, `createConversation`, the cancel path, the `sendMessageAsync` seam, `firstTokenNs`, and the `finally` that guarantees no conversation ever leaks |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | 818-834 | `generateWithToolsLocked`'s KDoc — *"Runs no per-token callback … decodeTokensPerSec/completionTokens are 0"* — the reason `:1644` can never carry a TTFT |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 1294-1360, 1622-1660, 1682-1700, 1740-1760, 2024-2044 | The **five non-uniform** `x_relais_usage_note` emission sites (four live for TTFT), `handleStructuredCompletion`'s `if (stream) → 400` at `:1690`, and the documented rule at `:2032-2034` that Relais extras are hoisted to the top level |
| **P1** | `.omc/skills/litertlm-native-api-probe-first-expertise.md` | whole file | Doc labels in this repo are wrong **in both directions**; probe before building. This exact feature is its worked example |
| **P1** | `Android/src/app/src/androidTest/java/cc/grepon/relais/RelaisBackendBenchmarkTest.kt` | 43-62, 149-153, 267-274 | Already imports `BenchmarkInfo` and has `logBench`; the B1-B leg extends it |
| **P1** | `Android/src/app/src/androidTest/java/cc/grepon/relais/ToolCallingProbe.kt` | 39-87 | Current probe header form (use **this** model path, not `RelaisBackendBenchmarkTest`'s older `/data/local/tmp` one) |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt` | 101-105, 175-183, 369-378, 460-464, 471-484 | Histogram shape and the `resetIncrementsForTest` seam B1-A2 must extend |
| **P1** | `scripts/dump-litertlm-api.sh` | 1-30 | How B0 is run, and its stated precondition: *"the AAR present in the Gradle cache (build once if not)"* |
| **P2** | `SPIKE-FINDINGS.md` | 100-106, 148-152 | The 111.1 tok/s prefill baseline (the number the new prefill-gap series should land near, if `:633-638` is right); the Q1 benchmark finding |
| **P2** | `Android/src/app/src/main/java/cc/grepon/relais/MainActivity.kt` | 128-130 | `ExperimentalFlags.enableBenchmark = false` — the flag B1-B would flip |
| **P2** | `Android/src/app/src/test/java/cc/grepon/relais/RelaisMetricsIncrementsTest.kt` | 41-50 | The Robolectric-only-for-`Context` shape `RelaisTtftMetricsTest.kt` must match |
| **P2** | `Android/src/app/src/test/java/cc/grepon/relais/RelaisUsageBlockTest.kt` | whole file | The existing usage-block test B1-A3a/A3b extend |

## External Documentation

| Topic | Source URL | Key Takeaway |
|---|---|---|
| LiteRT-LM public API surface | **In-repo primary source**: `docs/litertlm-native-api.md` + `scripts/dump-litertlm-api.sh` (regenerates it from the AAR via `javap`) | The inventory is only as current as the last script run, and it currently **contradicts itself** at `:31` vs `:164`. B0 re-derives ground truth from the 0.12.0 AAR |
| `getBenchmarkInfo()` on 0.11.0 | `docs/litertlm-native-api.md:169` (measured, in-repo) | Throws `INTERNAL: Benchmark is not enabled…`; no public `BenchmarkParams`/`EngineSettings`; `EngineConfig` has 7 args and no benchmark hook; only `BenchmarkKt.benchmark()` populates it, and that reloads the model |
| litertlm 0.14.0 | `docs/litertlm-native-api.md:14` (in-repo, PR #150) | A 0.14.0 AAR is in some caches but **0.12.0 is what ships** — 0.14.0 was A/B-tested on rango and **reverted** for regressing the G5 TPU lane |
| Upstream LiteRT-LM releases / API | https://github.com/google-ai-edge/LiteRT-LM | **NOT FETCHED THIS SESSION.** Do not take an upstream README's word for a capability either way — `.omc/skills/litertlm-native-api-probe-first-expertise.md` exists because both this repo's docs *and* upstream claims have been wrong. `javap` on the shipped AAR is the only authority |
| OpenAI `stream_options.include_usage` | https://platform.openai.com/docs/api-reference/chat | **NOT FETCHED THIS SESSION.** Behaviour already implemented in-repo (#175) at `RelaisHttpServer.kt:1347-1358`: when opted in, usage moves to a **separate terminal chunk with empty `choices`**. Mirror the existing implementation, not recalled spec text |
| Prometheus histogram semantics | (implemented in-repo) | Cumulative `_bucket{le=…}` + `_sum` + `_count`; mirror `relais_completion_tokens` at `RelaisMetrics.kt:369-378` |

**Research notes:**

- **KEY_INSIGHT** — the authoritative source for every native-API claim in this plan is `javap` on the
  **shipped 0.12.0 AAR**, not documentation of any kind. **APPLIES_TO** B0 and B1-B.
  **GOTCHA** — `scripts/dump-litertlm-api.sh` requires the AAR in `~/.gradle/caches/modules-2`, and
  **it is not there** (verified twice this session: `find ~/.gradle/caches/modules-2 -path
  "*litertlm-android*" -name "*.aar"` returns nothing). B0 therefore needs one Gradle build first.
- **KEY_INSIGHT** — a signature is not behaviour. **APPLIES_TO** B1-B1. **GOTCHA** — if B0 finds a
  benchmark hook, the probe must assert the fields are **non-zero**, not merely non-throwing. A
  silently-zeroed `BenchmarkInfo` is exactly what an "it didn't throw" check would miss, and a zero
  fed to `ThermalGovernor.onDecodeThroughput` (`RelaisEngine.kt:788`) breaks the device-safety
  throughput floor in the *safe-looking* direction.
- **KEY_INSIGHT** — the two TTFT series this plan adds are the instrument any *future* prefill work
  will be measured with, so getting the baseline right matters beyond B1-A itself. **APPLIES_TO**
  B1-A1. **GOTCHA** — a single clock at `:706` would have produced a series that cannot see prefill
  at all; the deferred prefix-reuse design was gated on exactly that blind instrument. See the
  appendix, finding 3.

## Patterns to Mirror

### NAMING_CONVENTION — result data class carries optional/`null`-when-unavailable fields with a KDoc saying why

```kotlin
data class RelaisResult(
  val text: String,
  val backend: RelaisBackend,
  val decodeTokensPerSec: Double,
  /**
   * Raw decode token count from the onMessage callback loop (completion_tokens in OpenAI usage).
   * Zero for the NPU/AICore path which does not expose per-token callbacks (UNVERIFIED path).
   * …
   */
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt:146-158`

> `timeToFirstTokenSec: Double?` joins this class with the same treatment: **nullable**, and the KDoc
> states exactly which paths produce `null` (AICore, blocking-tool) and that `0.0` is never a
> stand-in for "unmeasured".

### ERROR_HANDLING — `runCatching` + `.onFailure { Log.w }`, and never let an expected terminal throw

```kotlin
        fun requestNativeStop() {
          if (cancelRequested.compareAndSet(false, true)) {
            stopThread.set(
              thread(start = true, isDaemon = true, name = "relais-decode-cancel") {
                runCatching { conversation.cancelProcess() }
                  .onFailure { Log.w(TAG, "cancelProcess() failed: ${it.message}") }
              }
            )
          }
        }
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt:675-684`

```kotlin
          error[0]?.let { err ->
            if (!(cancelRequested.get() && RelaisFinishReason.isCancellationTerminal(err.message))) throw err
          }
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt:781-783`

> **Why B1-A cares:** on this path the run ends via an *expected* `onError`, and `firstTokenNs` may
> still be `0L` if the cancel landed before the first visible token. `timeToFirstTokenSec` is then
> `null` and nothing is recorded — which is correct, and is what the "no visible token" test pins.
> Do not compute a TTFT from a partial cancel.

### LOGGING_PATTERN / RESOURCE_CLEANUP — the `finally` that currently guarantees no conversation leaks

```kotlin
        } finally {
          // Join the cancel thread (if any) before closing so cancelProcess() and close() never run
          // concurrently against the same native conversation. Bounded — the cancel returns promptly.
          stopThread.get()?.let { runCatching { it.join(2_000) } }
          conversation.close()
        }
      }
    } finally {
      RelaisMetrics.recordLatency((System.nanoTime() - reqStartNs) / 1e9) // every outcome (HIGH-2)
      RelaisMetrics.decInFlight()
      lastActivityAtMs = System.currentTimeMillis() // idle-TTL clock (#178): every outcome counts
    }
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt:797-808`

> **B1-A does not touch this block.** Both new timestamps are captured *above* the `try`, and neither
> is read in the `finally` — the TTFT is computed at `:784-789` alongside `decodeSec`, inside the
> normal result-construction path. The `finally` at `:797-802` is the guarantee that no conversation
> ever leaks; the deferred prefix-reuse design would have made it conditional, which is one of the
> reasons it was cut.

### SERVICE/HANDLER_PATTERN — the five non-uniform response-emission sites

```kotlin
          .put("usage", buildUsageObject(request.text, result.completionTokens))
          .put("x_relais_usage_note", "prompt_tokens_estimated")
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:1299-1300`

```kotlin
      // Backward-compat: usage stays on the finish chunk UNLESS the client opted into the spec form
      // (stream_options.include_usage), where usage is a SEPARATE empty-choices terminal chunk (#175).
      if (!includeUsage) finalChunk.put("usage", usageObj).put("x_relais_usage_note", "prompt_tokens_estimated")
      sse.send(finalChunk)
      if (includeUsage) {
        val usageChunk = JSONObject().put("id", id).put("object", "chat.completion.chunk")
          .put("created", created).put("model", model)
          .put("choices", JSONArray()) // empty choices per the OpenAI include_usage spec
          .put("usage", usageObj)
          .put("x_relais_usage_note", "prompt_tokens_estimated")
        sse.send(usageChunk)
      }
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:1347-1358`

```kotlin
 * The estimation signal is intentionally NOT placed inside this object to avoid tripping
 * strict OpenAI-schema validators. Callers must attach `x_relais_usage_note` as a top-level
 * extension field on the enclosing response or chunk object.
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:2032-2034`

> **The five sites are not uniform — do not treat this as a mechanical repeat.** `:1349` and `:1356`
> are the two **mutually exclusive** branches of `stream_options.include_usage`, so TTFT attaches to
> exactly one of them per request: attaching to both is impossible, attaching to **neither** is the
> bug to watch for. That is why B1-A3 is split into A3a (non-streaming bodies) and A3b (both SSE
> branches).

#### The seam that has to be created first — `attachRelaisExtras`

**There is no pure seam at any of the five sites today, and this is a testability blocker, not a
style preference.** All five are inline `.put()` chains inside private socket handlers:
`handleOpenAi(sock, body, sessionKey)` at `:1206`, `handleToolCompletion` at `:1622`,
`handleStructuredCompletion` at `:1682`. `RelaisUsageBlockTest.kt:28-29` states the constraint that
makes the existing tests possible:

```
 * All tests call [buildUsageObject] / [estimatePromptTokens] directly — no socket, no Android
 * Context, no Robolectric. The functions live in RelaisHttpServer.kt as internal top-level fns.
```

An earlier draft of this plan promised "one test per emission site" against those handlers. **That is
unachievable in a JVM test as scoped**, and the test an implementer would actually write — assemble a
local `JSONObject`, assert the field sits at top level — is green whether or not production was ever
edited. That is exactly the vacuous-test failure memory `relais-prove-tests-red-first` records (two
shipped regression tests in this repo passed under the bug they claimed to pin).

So **B1-A3a's first step is an extraction**, mirroring `buildUsageObject` (`:2038`), which is the
file's own precedent for "pull the pure part out to the top level so it can be tested":

```kotlin
/**
 * Attaches Relais's top-level extension fields to a response or chunk object. Extras live at the
 * TOP level, never inside `usage` — see [buildUsageObject]'s KDoc at :2032-2034.
 *
 * `x_relais_ttft_ms` is OMITTED (not 0, not null) when [RelaisResult.timeToFirstTokenSec] is null:
 * the blocking tool lane runs no per-token callback, so a TTFT genuinely does not exist there and a
 * zero would be a fabricated measurement.
 *
 * Pure function (no Context, no socket, no Android types) — unit-testable on the JVM.
 */
internal fun attachRelaisExtras(obj: org.json.JSONObject, result: RelaisResult): org.json.JSONObject
```

Every live site then calls it, and the test targets the helper. **The extraction is what makes the
mutation check in B1-A3a possible** — delete one call site and a named test must go red.

### HISTOGRAM_PATTERN + TEST-SEAM — see feature-19's copy of the same source

```kotlin
  private val tokenBucketBounds = longArrayOf(16, 32, 64, 128, 256, 512, 1024)
  private val tokenBucketCounts = LongArray(tokenBucketBounds.size + 1) // last == +Inf
  private var completionTokenCount = 0L
  private var completionTokenSum = 0L
  private val tokenHistLock = Any()
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt:101-105`

```kotlin
  fun resetIncrementsForTest() {
    synchronized(histLock) { … }
    synchronized(tokenHistLock) {
      tokenBucketCounts.fill(0L)
      completionTokenCount = 0L
      completionTokenSum = 0L
    }
    thermalEventCounts.clear()
  }
```
`// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt:471-484`

### TEST_STRUCTURE (A) — pure JVM, no runner (the shape for the `attachRelaisExtras` tests)

```kotlin
/**
 * Pure JVM tests for [RagChunker]. Token counting is injected as a word count so the assertions are
 * deterministic without a real tokenizer (the production caller passes the SentencePiece counter).
 */
class RagChunkerTest {

  private val words: (String) -> Int = { it.split(Regex("\\s+")).filter { w -> w.isNotEmpty() }.size }

  @Test fun `packs sentences greedily up to the token budget`() {
    val chunks = RagChunker.chunk(text, targetTokens = 6, countTokens = words)
    assertEquals(2, chunks.size)
```
`// SOURCE: Android/src/app/src/test/java/cc/grepon/relais/RagChunkerTest.kt:21-34`

### TEST_STRUCTURE (B) — Robolectric only for a `Context` (the shape for the TTFT metrics test)

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelaisMetricsIncrementsTest {

  private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

  @Before
  fun reset() {
    RelaisMetrics.resetIncrementsForTest()
  }
```
`// SOURCE: Android/src/app/src/test/java/cc/grepon/relais/RelaisMetricsIncrementsTest.kt:41-50`

### PROBE_STRUCTURE — header with one copy-pasteable command; `logBench` for benchmark fields

Used by **Task B1-B1** (the conditional resident-engine `BenchmarkInfo` leg), which extends
`RelaisBackendBenchmarkTest.kt` rather than creating a new probe file.

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
  @Test
  fun probeNativeToolCalling() {
    assumeTrue("Model not found at $path (pass -e model <path>)", File(path).exists())
```
`// SOURCE: Android/src/app/src/androidTest/java/cc/grepon/relais/ToolCallingProbe.kt:39-87`

```kotlin
  private fun logBench(label: String, info: BenchmarkInfo) {
    Log.i(
      TAG,
      "$label  decode=${info.lastDecodeTokensPerSecond} tok/s  prefill=${info.lastPrefillTokensPerSecond} tok/s  " +
        "ttft=${info.timeToFirstTokenInSecond}s  init=${info.initTimeInSecond}s",
    )
    assertTrue("$label produced no decode tokens", info.lastDecodeTokensPerSecond > 0.0)
  }
```
`// SOURCE: Android/src/app/src/androidTest/java/cc/grepon/relais/RelaisBackendBenchmarkTest.kt:267-274`

> Use `ToolCallingProbe`'s **model path form** (app-external files), not
> `RelaisBackendBenchmarkTest.kt:57-61`'s older `/data/local/tmp/relais/...` — that staging area is
> gone (memory `relais-ondevice-verification`).

## Files to Change

| File | CREATE/UPDATE | Justification |
|---|---|---|
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | **UPDATE** (~14 ln, B1-A) | `convStartNs` before `:663` and `sendStartNs` before `:706` (**both above `return try {` at `:685`** — scoping, see B1-A1 GOTCHA); two new nullable fields on `RelaisResult` (`:146`); computed at `:784-789` |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt` | **UPDATE** (~60 ln, B1-A) | Two histograms (TTFT + decode-start latency), `renderJson` field, extend `resetIncrementsForTest` |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | **UPDATE** (1 new fn + 4 call sites, B1-A) | Extract `internal fun attachRelaisExtras` beside `buildUsageObject` (`:2038`); call it at the four **live** sites `:1300, :1349, :1356, :1747`. `:1644` is deliberately left carrying no TTFT (see Interaction Changes) |
| `Android/src/app/src/test/java/cc/grepon/relais/RelaisUsageBlockTest.kt` | **UPDATE** (B1-A) | Tests for `attachRelaisExtras` — present-when-set, **omitted-when-null**, top-level placement, `usage` untouched |
| `Android/src/app/src/test/java/cc/grepon/relais/RelaisTtftMetricsTest.kt` | **CREATE** (B1-A) | Histogram buckets/sum/count + reset seam |
| `docs/litertlm-native-api.md` | **UPDATE** (B1-A4, then again after B0) | **Fix line 31** (the stale "Opportunity" that contradicts §8); record B0's 0.12.0 verdict in §8; record the measured prefill-gap once B1-A ships |
| `Android/src/app/src/androidTest/java/cc/grepon/relais/RelaisBackendBenchmarkTest.kt` | **UPDATE** (B1-B, conditional on B0) | Resident-engine `BenchmarkInfo` leg |
| `docs/relais-grafana-dashboard.json`, `docs/RUNBOOK.md` | **UPDATE** (B1-A) | TTFT panel (next free row — see §Cross-plan coordination); one RUNBOOK paragraph on reading TTFT vs the prefill gap |

## NOT Building

- **A tokenizer, or exact `prompt_tokens`.** `prompt_tokens` stays a word-boundary estimate
  (`RelaisHttpServer.kt:2028`) and `x_relais_usage_note: prompt_tokens_estimated` stays. **Do not let
  B1-A scope-creep into "fix usage"** — that needs a tokenizer or `Session` token counts.
- **A `Session.runPrefill`/`runDecode` rewrite.** `Session` has no chat template
  (`docs/litertlm-native-api.md:27`); adopting it means hand-rolling Gemma-4's turn format — the exact
  hand-rolled-fallback anti-pattern the native-API-first rule forbids. See Notes Q1.
- **A litertlm version bump to 0.14.0.** Reverted for a G5 TPU regression (PR #150). If B0 finds the
  hook only in 0.14.0, that is **blocked**, not a task.
- **Conversation reuse / prefix caching of any kind.** Cut from this plan — see the *Deferred: prefix
  reuse* appendix. No task here creates a cache, a pool, or a conversation that outlives its request,
  and **the `finally` at `RelaisEngine.kt:797-802` stays unconditional**.
- **Changing `x_relais_ttft_ms` into a `0` on the paths that have no TTFT.** The field is omitted.
  A zero would be a fabricated measurement on the blocking tool lane.
- **Anything from feature-19** (power/energy). Separate plan, separate PR — but the two collide
  textually; see §Cross-plan coordination.

## Step-by-Step Tasks

### Task B0 — **GATE 1 (static)**: re-derive the 0.12.0 API surface

**ACTION** Populate the Gradle cache, run the API dump, grep for the benchmark and KV-rewind symbols,
record the verdict in `docs/litertlm-native-api.md`.

**IMPLEMENT**
```bash
cd Android/src && ./gradlew :app:assembleFullOpenDebug      # ONLY to populate the AAR in the cache
scripts/dump-litertlm-api.sh                                 # pinned version, all classes
scripts/dump-litertlm-api.sh 0.12.0 EngineConfig             # -c bytecode, one class
```
Grep the dump for: `BenchmarkParams`, `EngineSettings`, the `EngineConfig` constructor arity, any
settable benchmark field on `Engine`, plus `Session`, `runPrefill`, `runDecode`, and anything matching
`reset|rewind|truncate|clone|KvCache|checkpoint`.

> **Why the KV-rewind terms are still in the grep even though prefix reuse is cut.** They cost
> nothing — it is the same dump — and the answer is load-bearing for the deferred design. If 0.12.0
> turns out to expose a real KV rewind/truncate/clone, the appendix's whole shape changes: affinity
> on an exact transcript hash exists *only* because there is no way to roll a conversation back. A
> hit here is worth an issue; a miss confirms the appendix's premise. **Record the result either way
> in `docs/litertlm-native-api.md` §8** — that is the point of running it.

**Three outcomes, three different B1s:**

| B0 result | B1 becomes |
|---|---|
| **No benchmark hook in 0.12.0** (most likely — same 7-arg `EngineConfig`) | B1-native is **dead**. Ship **B1-A only**, and fix `docs/litertlm-native-api.md:31` |
| **A hook exists in 0.12.0** | B1-native is live — but still gated on B1-B1. A signature is not behaviour; that is the exact lesson §8 encodes |
| **Hook only in 0.14.0** | **Blocked**, not a task. Write it up as blocked-behind-a-version-bump-with-a-known-regression (PR #150); do not schedule it |

**MIRROR** N/A (tooling).

**IMPORTS** N/A.

**GOTCHA** **The AAR is not in the local Gradle cache.** Verified twice this session:
`find ~/.gradle/caches/modules-2 -path "*litertlm-android*" -name "*.aar"` returns nothing, and
`~/.gradle/caches/modules-2/files-2.1/` has no `com.google.ai.edge.litertlm` entry. The script states
this precondition itself (`scripts/dump-litertlm-api.sh:13, 27-28`). `javap` and `unzip` are both on
PATH. Whoever picks this up runs that one build — this planning pass deliberately did not (CLAUDE.md:
do not run Gradle unless asked).

**VALIDATE**
```bash
scripts/dump-litertlm-api.sh 0.12.0 | tee /tmp/litertlm-0.12.0.txt
grep -nE "BenchmarkParams|EngineSettings" /tmp/litertlm-0.12.0.txt        # expect: no hits
grep -nE "class EngineConfig|EngineConfig\(" /tmp/litertlm-0.12.0.txt     # expect: 7-arg ctor
grep -nEi "reset|rewind|truncate|clone|kvcache|checkpoint" /tmp/litertlm-0.12.0.txt
```
Record the exact output in `docs/litertlm-native-api.md` §8 **whatever it says**.

---

### Task B1-A1 — `convStartNs` + `sendStartNs` + two nullable `RelaisResult` fields

**ACTION** Update `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt`.

**IMPLEMENT**

```kotlin
        // …after the thermal cool-down at :632, immediately BEFORE createConversation at :663:
        val convStartNs = System.nanoTime()
        val conversation = e.createConversation(conversationConfig)      // :663
        …
        // …declared alongside cancelRequested/stopThread (:673-674), ABOVE `return try {` (:685):
        var sendStartNs = 0L
        …
        return try {
          …
          sendStartNs = System.nanoTime()
          conversation.sendMessageAsync(…)                               // :706
```

Then in the result-construction block at `:789-796`, beside the existing `decodeSec` at `:784`:

```kotlin
          val ttftSec = if (firstTokenNs > 0L) (firstTokenNs - convStartNs) / 1e9 else null
          val decodeStartSec =
            if (firstTokenNs > 0L && sendStartNs > 0L) (firstTokenNs - sendStartNs) / 1e9 else null
```

and add both to `RelaisResult` (`:146`), each with a KDoc naming the null paths, matching the file's
existing nullable-with-why convention:

```kotlin
  /**
   * Seconds from conversation creation (:663) to the first VISIBLE token. Null on any path with no
   * per-token callback — the blocking tool lane (generateWithToolsLocked) and AICore. Never 0.0.
   */
  val timeToFirstTokenSec: Double? = null,
  /** Same endpoint, measured from sendMessageAsync (:706). The gap between the two IS the prefill. */
  val decodeStartLatencySec: Double? = null,
```

**MIRROR** NAMING_CONVENTION (`RelaisEngine.kt:146-158` — nullable field with a why-null KDoc).

**IMPORTS** none new.

**GOTCHA 1 — `convStartNs` is the shipped baseline, and it is a *decision*, not an obvious choice.**
`RelaisEngine.kt:633-638` says the system prompt and history are prefilled at `createConversation`
(`:663`). If that is right, a clock started at `:706` misses the dominant term. If it is wrong,
the two series converge and nothing is lost. Measuring both is what makes this safe to ship without
a device — see §B1 — timing. **Do not delete `sendStartNs` as redundant** once the answer is known;
the decode-only figure is the one to compare against `BenchmarkInfo` if B1-B ever lands.

**GOTCHA 2 — scoping.** `sendStartNs` is assigned inside the `try` (which opens at `:685`) but must be
readable where the result is built. Declare it as a `var` **above** `return try {`, beside
`cancelRequested` (`:673`) and `stopThread` (`:674`) — the file documents exactly this constraint at
`:671-672` (*"Declared out here (not inside the try body) so the finally can join it"*). `convStartNs`
is already above `:685` naturally, since `:663` is.

**GOTCHA 3 — do not use `reqStartNs` (`:585`).** It is taken *before* the engine lock at `:605`, so it
includes lock wait and the thermal cool-down `Thread.sleep` (`:632`) — that is *request* latency,
already covered by `relais_inference_duration_seconds`. A queued request must not report the queue
wait as TTFT. Note `:632` sits **above** `:663`, so `convStartNs` correctly excludes the cool-down
too.

**GOTCHA 4 — `firstTokenNs` is deliberately set only on the first *visible* token** (`:742-744`,
reasoning callbacks excluded). Keep it that way: a TTFT that counts `<think>` tokens is a different,
less useful number. Also note `firstTokenNs` is **already consumed** at `:784` for `decodeSec` — you
are adding a second reader, not rescuing an unused value.

**VALIDATE** `cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`

---

### Task B1-A2 — two histograms: `relais_time_to_first_token_seconds` + `relais_decode_start_latency_seconds`

> **Blocked on Task B1-A1.**

**ACTION** Update `Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt`; create
`Android/src/app/src/test/java/cc/grepon/relais/RelaisTtftMetricsTest.kt`.

**IMPLEMENT**
- `private val ttftBucketBounds = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 60.0)` +
  counts array + dedicated lock + sum/count, copying `RelaisMetrics.kt:101-105`. **The same bounds
  serve both series** — reuse the array, keep two independent counts/locks.
- `fun recordTimeToFirstToken(sec: Double)` and `fun recordDecodeStartLatency(sec: Double)`, both
  mirroring `recordLatency` (`:175-183`).
- `# HELP`/`# TYPE`/`_bucket`/`_sum`/`_count` for **both** `relais_time_to_first_token_seconds` and
  `relais_decode_start_latency_seconds` in `renderProm`, mirroring `:369-378`. The `# HELP` for the
  second must say what the difference between the two means:
  ```
  # HELP relais_time_to_first_token_seconds Seconds from conversation creation to the first visible
  # token. Excludes queue wait and thermal cool-down. This is the user-visible wait.
  # HELP relais_decode_start_latency_seconds Seconds from sendMessageAsync to the first visible token.
  # Subtract from relais_time_to_first_token_seconds to get prompt/history prefill time.
  ```
- `ttft_p50_seconds` in `renderJson` beside `inference_p50_seconds`.
- **Extend `resetIncrementsForTest()` (`:471-484`)** to clear both.
- Call both from `RelaisEngine.generate` next to `recordCompletionTokens` (`:787`), **each only when
  its own field is non-null**.

**MIRROR** HISTOGRAM_PATTERN; TEST_STRUCTURE (B) for the test.

**IMPORTS** test: `androidx.test.core.app.ApplicationProvider`, `org.robolectric.RobolectricTestRunner`,
`org.robolectric.annotation.Config`, JUnit.

**GOTCHA** A **histogram**, not the last-value gauge shape `relais_decode_tokens_per_second` uses
(`:387-389`) — TTFT's *tail* is the interesting part, and a last-value gauge hides it. Never record
`0.0` for "unmeasured": a zero-second bucket entry is indistinguishable from a real sub-250 ms TTFT.
Label-free, per security M6 (`:36-38`).

**VALIDATE** `cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`

---

### Task B1-A3a — extract `attachRelaisExtras`, then wire the non-streaming bodies

> **Blocked on Task B1-A2.**

**ACTION** Update `RelaisHttpServer.kt`: create `attachRelaisExtras` beside `buildUsageObject`
(`:2038`), call it at `:1300` and `:1747`, leave `:1644` emitting no TTFT; extend
`RelaisUsageBlockTest.kt`.

**IMPLEMENT**

**Step 1 — the extraction comes first.** See Patterns to Mirror → *The seam that has to be created
first*. Without it there is nothing JVM-testable here and the tests are vacuous.

```kotlin
internal fun attachRelaisExtras(obj: org.json.JSONObject, result: RelaisResult): org.json.JSONObject {
  obj.put("x_relais_usage_note", "prompt_tokens_estimated")
  result.timeToFirstTokenSec?.let { obj.put("x_relais_ttft_ms", (it * 1000).toInt()) }
  return obj
}
```

**Step 2 — replace the inline `.put("x_relais_usage_note", …)`** with a call to it at the live
non-streaming sites: `:1300` (`handleOpenAi`) and `:1747` (`handleStructuredCompletion`'s non-stream
body). **Top level**, never inside `usage`.

**Step 3 — `:1644` also calls it**, and that call correctly emits **only** the usage note: the
result reaching that site always has `timeToFirstTokenSec == null`, so the helper omits the field.
Route it through the helper anyway, for uniformity and so the omission is exercised in production.

**MIRROR** SERVICE/HANDLER_PATTERN (`:1299-1300`), `buildUsageObject`'s top-level-extension rule at
`:2032-2034`, and `RelaisUsageBlockTest.kt:28-29`'s "internal top-level fns, no socket" test shape.

**IMPORTS** none new.

**GOTCHA 1 — `:1644` can never carry a TTFT, and that is a property to *test*, not a gap to fix.**
`handleToolCompletion` (`:1622`) calls `generateWithNodeTools` (`:1569`), which calls
`RelaisEngine.generate` with tools advertised (`:1571` or `:1577`). `generate` returns at `:626-627`
into `generateWithToolsLocked`, whose KDoc (`:824-826`) states it *"Runs no per-token callback… so
decodeTokensPerSec/completionTokens are 0."* `firstTokenNs` is never assigned, so
`timeToFirstTokenSec` is structurally `null`. An earlier draft of this plan asserted the field would
be *"present"* at this site — an implementer following it would write a test that can never pass, or
"fix" it by emitting `0`, which this plan explicitly forbids. **Four live sites, not five.**

**GOTCHA 2 — omit, never `null`, never `0`.** A strict client should see a real number or no key at
all. `JSONObject.put(k, null as Any?)` *removes* the key in `org.json`, but do not rely on that —
guard with `?.let` so the intent is legible.

**MUTATION CHECK (required — this task does not count as done without it).** The extraction exists
precisely so this is possible; memory `relais-prove-tests-red-first` records two shipped tests here
that passed under the bug they claimed to pin.

1. Delete the `attachRelaisExtras` call at `:1300` → a named test asserting the field's presence must
   go **RED**.
2. Change the helper to emit `0` instead of omitting on `null` → the omitted-when-null test must go
   **RED**.
3. Move the `x_relais_ttft_ms` put *inside* the `usage` object → the top-level-placement test must go
   **RED**.

If any of these stays green, the test is not pinning anything — fix the test, not the mutation.

**VALIDATE** `cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`

---

### Task B1-A3b — `x_relais_ttft_ms` on both SSE branches

> **Blocked on Task B1-A3a.**

**ACTION** Update `RelaisHttpServer.kt` at `:1349` and `:1356`.

**IMPLEMENT** Route both branches through `attachRelaisExtras` (created in B1-A3a): the finish chunk
in the `if (!includeUsage)` branch, and the separate empty-choices usage chunk in the
`if (includeUsage)` branch.

**MIRROR** SERVICE/HANDLER_PATTERN (`:1347-1358`).

**IMPORTS** none new.

**GOTCHA 1** These two branches are **mutually exclusive**, so TTFT lands on exactly one chunk per
request — attaching to both is impossible, attaching to **neither** is the bug to watch for. Do
**not** put TTFT on the first delta chunk: for the reasoning-then-visible case the value is not final
there.

**GOTCHA 2 — branch exclusivity is verified by curl, not by a JVM test, and this plan says so
plainly.** The two branches live inside the SSE writer in `handleOpenAi` (`:1206`), which needs a
real socket; there is no seam to reach them from a device-free test, and inventing one would be a
larger change than this task. What **is** JVM-tested is the helper both branches call
(`attachRelaisExtras`, B1-A3a). The claim "exactly one chunk per request carries it" is therefore
supported by the two curl invocations below, run once and pasted into the PR — **not** by a unit
test. Do not write a JVM test that assembles its own chunk objects and claim it proves branch
exclusivity; it proves nothing about production.

**VALIDATE**
```bash
cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest
# manual, both branches:
curl -kN -H "Authorization: Bearer <key>" -H "Content-Type: application/json" \
  -d '{"model":"gemma-4-e4b-it","stream":true,"messages":[{"role":"user","content":"hi"}]}' \
  https://<phone-ip>:8443/v1/chat/completions | grep x_relais_ttft_ms
curl -kN -H "Authorization: Bearer <key>" -H "Content-Type: application/json" \
  -d '{"model":"gemma-4-e4b-it","stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"hi"}]}' \
  https://<phone-ip>:8443/v1/chat/completions | grep x_relais_ttft_ms
```

---

### Task B1-A4 — fix `docs/litertlm-native-api.md:31`, and record the measured prefill gap

> **Blocked on Task B0 result** for the first half (the replacement text depends on the 0.12.0
> verdict). **Blocked on B1-A shipping and one real request** for the second half.

**ACTION** Rewrite the stale "Opportunity" text at `docs/litertlm-native-api.md:31` so it no longer
contradicts §8 at `:164`/`:169`; record B0's verdict **and** the first measured prefill gap.

**IMPLEMENT**

1. Point line 31 at §8. One line — but it prevents the next agent making the same mistake this brief
   did.
2. Add a §8 entry recording B0's dump verdict, **including the KV-rewind grep result** (see B0).
3. Add a §8 entry recording the **measured prefill gap** from the first real run:
   `mean(relais_time_to_first_token_seconds) − mean(relais_decode_start_latency_seconds)`, with the
   prompt size and device. State plainly whether it confirms or refutes `RelaisEngine.kt:633-638`'s
   claim that history is prefilled at `createConversation`. **This is the point of the two-timestamp
   design** — without this step the plan measures the question and then discards the answer.

**MIRROR** the §8 entry format at `:164`.

**IMPORTS** N/A.

**GOTCHA** Fix it **either way** — a "confirmed still dead on 0.12.0" line is as valuable as a
"now available" one, and leaving the contradiction is how this recurs a third time. Same for the
prefill gap: a ~0 gap is a *finding* (prefill is lazy, the comment is misleading), not a failed
measurement, and it should be written up as one.

**VALIDATE**
```bash
grep -n "Opportunity" docs/litertlm-native-api.md          # no hit that contradicts §8
grep -n "0.12.0" docs/litertlm-native-api.md               # B0's verdict is recorded
grep -nE "prefill gap|convStartNs" docs/litertlm-native-api.md   # the measurement is recorded
```

---

### Task B1-B1 — resident-engine `BenchmarkInfo` leg

> **Blocked on Task B0 result — run only if B0 finds a benchmark hook in 0.12.0.**

**ACTION** Extend `Android/src/app/src/androidTest/java/cc/grepon/relais/RelaisBackendBenchmarkTest.kt`.

**IMPLEMENT** A leg that sets the hook on a **resident** engine — the same `Engine`/`Conversation`
shape `RelaisEngine` uses, not a standalone helper — runs one decode, then calls `getBenchmarkInfo()`.
Assert the fields are populated **and non-zero**, and plausible against the wall-clock numbers measured
in the same run. Then a **cost check**: decode tok/s with `ExperimentalFlags.enableBenchmark` on vs off,
≥5 runs each, interleaved.

**MIRROR** PROBE_STRUCTURE — `RelaisBackendBenchmarkTest.kt:267-274` (`logBench` already exists and
already asserts `lastDecodeTokensPerSecond > 0.0`).

**IMPORTS** `com.google.ai.edge.litertlm.BenchmarkInfo`, `ExperimentalFlags`, `ExperimentalApi`
(already imported at `:30-34`).

**GOTCHA** A silently-zeroed `BenchmarkInfo` is the failure mode a naive "it didn't throw" check would
miss — assert **non-zero**. `enableBenchmark` is a process-global `ExperimentalFlags` setter
(`MainActivity.kt:129` currently sets it `false`); if instrumentation costs throughput it becomes an
operator-gated `RelaisConfig` toggle default **off**, not an always-on default — sustained serving is
this node's whole point.

**VALIDATE**
```bash
cd Android/src && ./gradlew :app:compileFullOpenDebugAndroidTestKotlin
adb -s 57211FDCG0023C shell am instrument -w \
  -e class cc.grepon.relais.RelaisBackendBenchmarkTest#residentBenchmarkInfo \
  -e model /storage/emulated/0/Android/data/com.ventouxlabs.relais.izzy/files/litert_community_gemma_4_E2B_it_litert_lm/361a4010ad6d88fc5c86e148e333c0342b99763d/gemma-4-E2B-it.litertlm \
  com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
adb -s 57211FDCG0023C logcat -s RelaisBench
```

---

### Task B1-B2 — swap the throughput source

> **Blocked on Task B1-B1 result — only if its fields come back non-zero and the cost check passes.**

**ACTION** Update `RelaisEngine.kt` and `RelaisMetrics.kt`.

**IMPLEMENT** Swap `decodeTokensPerSec` from the wall-clock estimate (`:784-785`) to
`lastDecodeTokensPerSecond`; add `lastPrefillTokenCount` as a **real** `prompt_tokens`, dropping
`x_relais_usage_note` **on that path only**; emit `relais_prefill_tokens_per_second`. **Keep the
wall-clock path as a per-request fallback** selected when `BenchmarkInfo` returns zeros.

**MIRROR** ERROR_HANDLING (`runCatching` around the native read, fall back rather than throw).

**IMPORTS** `com.google.ai.edge.litertlm.BenchmarkInfo`.

**GOTCHA** `ThermalGovernor.onDecodeThroughput(tokS)` (`RelaisEngine.kt:788`) feeds the device-safety
throughput floor. It must **never** receive a zero from a failed benchmark read, or
`throughputFloorBreached()` (`ThermalGovernor.kt:112-113`, which tests `decodeEwma in 0.001..floor`)
goes wrong in the *safe-looking* direction. Guard explicitly. Also: the usage note is set in **five**
places — dropping it on one path means touching one of five, carefully.

**VALIDATE** `cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`, then an on-device sanity run comparing the new `relais_decode_tokens_per_second` against the wall-clock value it replaces.

## Testing Strategy

**Read this before writing a line of it: what each layer can and cannot prove.**

| Layer | Proves | Cannot prove |
|---|---|---|
| `RelaisUsageBlockTest.kt` (pure JVM, `attachRelaisExtras`) | field placement, presence-when-set, **omission-when-null**, `usage` untouched | that production actually calls the helper — **only the mutation check does** |
| `RelaisTtftMetricsTest.kt` (Robolectric) | histogram bucketing, null-not-recorded, reset seam | anything about the HTTP bodies |
| curl, run once, pasted into the PR | branch exclusivity across the two SSE branches; end-to-end shape | nothing repeatable in CI |

There is deliberately **no** JVM test claiming to cover the SSE branches. See B1-A3b GOTCHA 2.

### Unit Tests — `RelaisUsageBlockTest.kt` extensions (pure JVM, `attachRelaisExtras`)

| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| Field present when set | `RelaisResult(timeToFirstTokenSec = 2.481)` | `x_relais_ttft_ms == 2481` at **top level** | no |
| **Field omitted when null** | `RelaisResult(timeToFirstTokenSec = null)` | key **absent** — `obj.has("x_relais_ttft_ms")` is false. Not `null`, not `0` | **yes** |
| Usage note always attached | either of the above | `x_relais_usage_note == "prompt_tokens_estimated"` | no |
| `usage` schema cleanliness | helper applied to an object carrying a `usage` block | `usage` still has exactly `prompt_tokens`/`completion_tokens`/`total_tokens` — the extension never leaks into it | **yes** |
| Rounding | `0.0004 s` | `0` ms, not a crash or a negative | **yes** |

### Unit Tests — `RelaisTtftMetricsTest.kt` (Robolectric, `@Config(sdk = [34])`)

| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| TTFT baseline is `convStartNs`, not `reqStartNs` | `convStartNs` 3 s after `reqStartNs`, first token 1 s after conv start | `timeToFirstTokenSec == 1.0`, **not** `4.0` | **yes** |
| The two series differ by the prefill gap | `convStartNs` at t=0, `sendStartNs` at t=2, first token at t=3 | `ttft == 3.0`, `decodeStartLatency == 1.0` | **yes** |
| No visible token | decode produced only reasoning, then stopped | both `null`, **never `0.0`** | **yes** |
| AICore path | `RelaisBackend.NPU_AICORE` | both `null` | **yes** |
| Histogram buckets | record 0.3, 1.5, 7.0 s | cumulative `_bucket{le=…}` + `_sum 8.8` + `_count 3` | no |
| Null not recorded | `timeToFirstTokenSec == null` | `_count` unchanged | **yes** |
| Reset seam | `resetIncrementsForTest()` | **both** histograms cleared; tests pass in any order | **yes** |

### Verified by curl only (stated as such in the PR, not dressed up as a test)

| Check | Command | Expected |
|---|---|---|
| Non-streaming body (`:1300`) | plain completion | `x_relais_ttft_ms` top level, absent from `usage` |
| Structured body (`:1747`) | `response_format` non-stream | field present |
| **Tool body (`:1644`)** | tool-call completion | field **ABSENT** — the blocking lane has no TTFT (B1-A3a GOTCHA 1) |
| SSE without `include_usage` (`:1349`) | `stream:true` | field on the **finish** chunk and no other |
| SSE with `include_usage` (`:1356`) | `stream:true, include_usage:true` | field on the **usage** chunk and no other |

### Edge Cases checklist

- [ ] TTFT on a request that was queued behind another → measures from `convStartNs`, **not** queue wait
- [ ] TTFT on a reasoning-only response (no visible token) → `null`, field omitted
- [ ] Tool-call response → field omitted, **and nobody "fixes" it into a `0`**
- [ ] `x_relais_ttft_ms` never appears inside the `usage` object on any site
- [ ] `include_usage` true and false → field on exactly one chunk each, never both, never neither
- [ ] The prefill gap (`ttft − decodeStartLatency`) is recorded in `docs/litertlm-native-api.md` after
      the first real run — this is the measurement that settles Assumption 3
- [ ] `BenchmarkInfo` returns all zeros (B1-B) → wall-clock fallback used, `ThermalGovernor` never fed a zero

## Validation Commands

> **None of these were executed during planning.** CLAUDE.md forbids running Gradle unless asked, and
> this planning pass did not — which is also why B0's dump could not be run (the AAR is not cached).
> Treat every command below as an instruction to the implementer, not as a verified-green result.

```bash
# JVM unit tests — the CI unit-test job (device-free). After every JVM-touching task.
cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest

# Compile (do NOT run) the probe suite. After B1-B1.
cd Android/src && ./gradlew :app:compileFullOpenDebugAndroidTestKotlin

# B0 ONLY: one build, purely to populate the litertlm AAR in the Gradle cache.
cd Android/src && ./gradlew :app:assembleFullOpenDebug
scripts/dump-litertlm-api.sh 0.12.0 | tee /tmp/litertlm-0.12.0.txt
grep -nE "BenchmarkParams|EngineSettings" /tmp/litertlm-0.12.0.txt
grep -nEi "reset|rewind|truncate|clone|kvcache|checkpoint" /tmp/litertlm-0.12.0.txt

# B1-B1 (only if GATE 1 finds a hook) — rango / Pixel 10 / G5 / E2B, never the live node:
adb -s 57211FDCG0023C shell am instrument -w \
  -e class cc.grepon.relais.RelaisBackendBenchmarkTest#residentBenchmarkInfo \
  -e model /storage/emulated/0/Android/data/com.ventouxlabs.relais.izzy/files/litert_community_gemma_4_E2B_it_litert_lm/361a4010ad6d88fc5c86e148e333c0342b99763d/gemma-4-E2B-it.litertlm \
  com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner

# report-worker is NOT touched by this feature — do not run its job.
```

### Manual Validation

- [ ] Non-streaming: `curl -k -H "Authorization: Bearer <key>" -d '{"model":"gemma-4-e4b-it","messages":[{"role":"user","content":"hi"}]}' https://<phone-ip>:8443/v1/chat/completions | python3 -m json.tool`
      → `x_relais_ttft_ms` top-level; `usage` has exactly the three OpenAI fields
- [ ] Streaming, both `include_usage` values (curl lines in Task B1-A3b) → field on exactly one chunk each
- [ ] `curl -k -H "Authorization: Bearer <key>" https://<phone-ip>:8443/metrics | grep time_to_first_token`
      → buckets + `_sum` + `_count` present and monotonic across requests
- [ ] Fire two requests concurrently; confirm the queued one's `x_relais_ttft_ms` is **not** inflated
      by its queue wait (compare against `relais_inference_duration_seconds`)
- [ ] **Tool-call request → `x_relais_ttft_ms` is absent from the response.** Confirm nobody has
      "helpfully" made it a `0`
- [ ] **Read the prefill gap off the first real run:**
      `relais_time_to_first_token_seconds_sum / _count` minus
      `relais_decode_start_latency_seconds_sum / _count`. Compare against
      `SPIKE-FINDINGS.md:100-106`'s 111.1 tok/s prefill rate for the prompt size used. Record the
      result in `docs/litertlm-native-api.md` — this settles Assumption 3

## Acceptance Criteria

**B1-A** (ships regardless of B0)
- [ ] `relais_time_to_first_token_seconds` is measured from `convStartNs` (before
      `RelaisEngine.kt:663`), **not** `reqStartNs` (`:585`) and **not** `sendStartNs` alone
- [ ] `relais_decode_start_latency_seconds` is emitted as a **separate** series from `sendStartNs`,
      and the plan states in `# HELP` that their difference is prefill
- [ ] The plan asserts **nothing** about where prefill happens; the first scrape is the evidence
- [ ] `null`, never `0.0`, on the AICore and blocking-tool paths; nulls are not recorded in either
      histogram; the response field is **omitted** rather than zeroed
- [ ] `attachRelaisExtras` exists as an `internal` top-level fn, every live site calls it, and
      deleting one call site turns a named test **RED**
- [ ] `x_relais_ttft_ms` is top level at the **four live** sites and the `usage` object stays
      OpenAI-schema-clean. `:1644` carries the usage note and **no** TTFT, and there is a test for
      that absence
- [ ] Branch exclusivity across the two SSE branches is claimed as **curl-verified**, with the output
      pasted in the PR — not as a unit-tested property
- [ ] `resetIncrementsForTest` clears **both** new histograms; tests pass in any order

**B0 / B1-B**
- [ ] B0's dump output is recorded in `docs/litertlm-native-api.md` §8 **whatever it says**
- [ ] `docs/litertlm-native-api.md:31` no longer contradicts §8
- [ ] `BenchmarkInfo` fields asserted **non-zero**, not merely non-throwing
- [ ] The wall-clock fallback still works when they are zero, and `ThermalGovernor.onDecodeThroughput`
      never receives a zero from a failed read

**Scope**
- [ ] No conversation, cache, or pool outlives its request; the `finally` at `RelaisEngine.kt:797-802`
      is unchanged and still unconditional. Nothing from the deferred appendix was implemented

## Completion Checklist

- [ ] **Patterns followed** — nullable-with-why-KDoc result fields, histogram shape, `runCatching`
      error handling, top-level extension fields per `RelaisHttpServer.kt:2032-2034`, the
      `buildUsageObject` precedent for extracting a testable pure fn, probe header form
- [ ] **Error handling** — a failed native read falls back rather than throwing; no zero reaches
      `ThermalGovernor`
- [ ] **Logging** — probes log every partial in a `finally` and use a `Relais*` logcat tag
- [ ] **Tests** — 5 pure-JVM (`attachRelaisExtras`) + 7 Robolectric (TTFT metrics), RED first, and the
      three-mutation check in B1-A3a passed (memory `relais-prove-tests-red-first`: two shipped tests
      here passed under the bug they claimed to pin)
- [ ] **Honest verification claims** — the PR distinguishes what is unit-tested (the helper), what is
      mutation-checked (the call sites), and what is curl-verified only (SSE branch exclusivity)
- [ ] **No hardcoded values** — histogram bounds are named constants
- [ ] **Docs updated** — `docs/litertlm-native-api.md` §8 + line 31 + the measured prefill gap,
      dashboard, RUNBOOK
- [ ] **No scope additions** — `prompt_tokens` estimation untouched; no 0.14.0 bump; no `Session`
      rewrite; no prefix reuse
- [ ] **Self-contained** — B1-A adds no dependency and no native-API assumption
- [ ] **B0 recorded** — the dump output is in `docs/litertlm-native-api.md`, not just in a PR comment,
      **including the KV-rewind grep result** for the deferred design
- [ ] **Independent review** — `code-reviewer` + `critic` consensus before merge; never self-approve

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **The shipped TTFT measures the wrong thing** because prefill lands somewhere neither timestamp brackets | Medium | **High** | Two timestamps, not one. `convStartNs` (`:663`) is the outer bound of everything after cool-down and lock; if prefill happens anywhere inside `generate` it is captured. The gap between the two series *is* the measurement, so a surprising answer shows up as data rather than as a wrong number — and Task B1-A4 records it |
| **The plan was assigned on a false premise about `getBenchmarkInfo()`** | Certain (already happened) | High | Premise correction in Problem → Solution; B0 gates it; Task B1-A4 fixes `docs/litertlm-native-api.md:31` so it does not recur a third time |
| **Vacuous tests: green suite, unedited production** | **High without the extraction** | High | The five emission sites have no pure seam, so a naive "one test per site" assembles its own `JSONObject` and passes regardless. B1-A3a extracts `attachRelaisExtras` first and requires a three-mutation check. Memory `relais-prove-tests-red-first` |
| Someone "fixes" the absent `x_relais_ttft_ms` on the tool path into a `0` | Medium | Medium | It looks like a bug and is not: `generateWithToolsLocked` has no per-token callback (`:824-826`). Documented in the Interaction Changes table, B1-A3a GOTCHA 1, and pinned by an omitted-when-null test |
| `enableBenchmark` costs throughput on the serving path | Medium | Medium | Measured in B1-B1 (≥5 runs on/off); becomes an operator toggle default off if it does |
| A zero from a failed `BenchmarkInfo` read reaches `ThermalGovernor` | Medium | Medium | `throughputFloorBreached()` tests `decodeEwma in 0.001..floor` (`ThermalGovernor.kt:112-113`), so a zero fails **safe-looking** — guard explicitly in B1-B2 |
| TTFT attached to neither SSE branch (silent gap) | Medium | Low | Two curl invocations in B1-A3b, output pasted in the PR. Honestly not covered by a unit test — see B1-A3b GOTCHA 2 |
| Textual merge conflicts with features 19, 22 and 17 | **High** | Low | §Cross-plan coordination names every shared hunk and the land order |
| B0 needs a Gradle build the planning pass could not run | Certain | Low | Documented precondition with the exact command; the script itself says so (`scripts/dump-litertlm-api.sh:13, 27-28`) |

## Cross-plan coordination

This plan shares hunks with three siblings. Checked 2026-09-07 against `.claude/PRPs/plans/`.

| Shared hunk | feature-20 (this plan) | Collides with | Nature |
|---|---|---|---|
| `RelaisEngine.kt`, the `:663`–`:706` region | `convStartNs` before `:663`, `sendStartNs` above `:685` | **feature-19** inserts its `beginWindow()` bracket in the same region | Adjacent inserts — textual |
| `RelaisMetrics.kt:471-484` `resetIncrementsForTest` | extends it (B1-A2) | **feature-19** and **feature-22** both extend it | Three-way textual conflict; all three are additive |
| `RelaisMetrics.kt:101-105` histogram region | two new histograms | **feature-19** adds an energy histogram | Adjacent inserts — textual |
| `RelaisHttpServer.kt:1348-1349` (the SSE finish/usage chunks) | routes both through `attachRelaisExtras` | **feature-17** (Ollama-compat) cites `:1348-1349` and `:1363-1366` directly | Same lines, different intent — **resolve by hand, do not take either side wholesale** |
| `docs/relais-grafana-dashboard.json` | +1 TTFT panel | **feature-19** (+4 panels), **feature-22** | Grid-position collision |

**Land order: this plan's B1-A goes first** (per `.claude/HANDOFF.md`), because it is the smallest
reviewed change and every other plan's conflict is easier to resolve against a landed B1-A than the
reverse. Consequences:

- **feature-19 rebases onto B1-A**, and its plan says so.
- **feature-17 will conflict at the SSE chunk lines.** `attachRelaisExtras` should make that *easier*
  — a hand-resolved merge that routes feature-17's additions through the same helper is the right
  outcome. Flag it in the feature-17 PR rather than silently taking one side.
- **Claim the next free dashboard row**; do not assert a total panel count anywhere.

## Notes

### Decisions

1. **Split B1 into A (unconditional) and B (gated).** B1-A needs nothing from the native API and is
   the best effort-to-value ratio here. Holding it behind a native-API investigation would be pure
   loss.
2. **Two timestamps, and `convStartNs` is the shipped TTFT baseline.** `reqStartNs` (`:585`) precedes
   `synchronized(lock)` (`:605`) and so includes queue wait — already covered by
   `relais_inference_duration_seconds`. `sendStartNs` alone risks excluding prefill entirely if
   `RelaisEngine.kt:633-638` is right. Capturing both makes the shipped number the user-visible wait
   *and* turns the open question into a measurement. This replaces an earlier single-`sendStartNs`
   decision that asserted, without evidence, that `:706` was "the honest TTFT."
3. **A histogram for TTFT, not a last-value gauge.** The tail is the signal; a gauge hides it.
4. **Extract `attachRelaisExtras` before touching any emission site.** Not a refactor for tidiness —
   without a pure seam there is nothing JVM-testable and the tests would be vacuous. The file's own
   `buildUsageObject` is the precedent.
5. **Omit, never zero.** A `0.0` TTFT on a path that has no per-token callback is a fabricated
   measurement, and it is indistinguishable from a real sub-millisecond result.
6. **Prefix reuse is deferred, not descoped-and-forgotten.** The appendix keeps the findings so the
   next attempt starts from them.
7. **Two PRs.** B1-A + B1-A4 first, alone.

### Alternatives considered and rejected

- **Trusting `docs/litertlm-native-api.md:31` (the brief's premise).** Rejected: it contradicts the
  later evidence-backed `:164`/`:169`, which even says the table's "available" was wrong.
- **Bumping litertlm to 0.14.0 to get a benchmark hook.** Rejected: reverted for a G5 TPU regression
  (PR #150). If the hook exists only there, this is *blocked*, not schedulable.
- **A single `sendStartNs` clock.** Rejected — see Decision 2. This was the earlier draft's design.
- **Dropping `sendStartNs` once `convStartNs` ships.** Rejected: the difference is the prefill
  measurement, and the decode-only figure is the one comparable to `BenchmarkInfo` if B1-B lands.
- **"One test per emission site" against the private socket handlers.** Rejected as unachievable —
  there is no seam, and the test an implementer would write passes whether or not production changed.
- **Attaching `x_relais_ttft_ms` to the first SSE delta chunk** (earliest possible delivery). Rejected:
  not final for the reasoning-then-visible case.

### Findings from the critic review that were DECLINED

None. Every CRITICAL, HIGH and MEDIUM finding was either fixed above or resolved by cutting prefix
reuse to the appendix.

### Open questions for the user

1. **Is one Gradle build acceptable to unblock B0?** The plan assumes yes (it is the documented
   precondition of `scripts/dump-litertlm-api.sh`); say otherwise and B0 blocks on CI producing the
   artifact instead.
2. **Is `SamplerConfig.seed` defaulting to `0` intentional?** (`RelaisEngine.kt:55` `DEFAULT_SEED = 0`,
   used at `:141`.) It makes every default request deterministic-ish, which is a behaviour nobody has
   documented. Unrelated to B1; surfaced while reading, and worth a one-line answer in review.
3. **Should the deferred prefix-reuse work be revived as part of session-memory (#5), or dropped?**
   See the appendix — the session-scoped shape is materially safer, but it is a different feature.

---

# Deferred: prefix reuse (NOT part of this plan — do not implement from this appendix)

The original brief paired the timing work with reusing a warm `Conversation` across HTTP requests.
This section records the design and **why it was cut**, so the next person to have the idea starts
from the findings instead of rediscovering them. It is deliberately not written as tasks.

### The prize, and it is real

Every request builds a fresh `Conversation` (`RelaisEngine.kt:663`) seeded with the system
instruction + history, and closes it in the `finally` (`:801`). Measured baseline
(`SPIKE-FINDINGS.md:100-106`, E4B / Tensor G4 / GPU): **prefill 111.1 tok/s**. A 2k-token system
prompt is therefore **~18 s of prefill per request, forever**, on a node whose decode is 3–8 tok/s.
That is worth wanting.

### The design that was proposed

Reuse a conversation **iff the incoming request's declared prefix exactly equals the conversation's
accumulated transcript** — "conversation affinity on an exact transcript match", keyed by a SHA-256
hash chain over `canonical(systemPrompt) ‖ canonical(history) ‖ samplerFingerprint ‖ residentModelId
‖ enableThinking ‖ toolsFingerprint`, bound-2 LRU pool, evict on cancel/error/swap/idle, default-OFF
toggle, gated behind an on-device probe with a pre-registered ≥50% median-TTFT threshold.

### Why it was cut — four findings, in order of severity

**1. CRITICAL — the cache key omits attachment bytes, reintroducing the exact leak it exists to
prevent.** `ParsedTurn` carries binary attachments (`RelaisOpenAiParser.kt:50-51`):

```kotlin
val imagePng: ByteArray? = null,
val audioWav: ByteArray? = null,
```

and both `toResidentMessage` (`RelaisEngine.kt:1004-1007`) and the live-turn path (`:599-603`) seed
them as real `Content.ImageBytes` / `Content.AudioBytes` into the conversation — i.e. **real tokens in
the KV cache**. The proposed key had no attachment component, and `canonical(history)` was never
defined. The obvious implementation canonicalises history as text, because `ParsedTurn.text` is what
you reach for. Two requests with identical text and *different images* then hash identically → cache
hit → request N+1 decodes conditioned on request N's image. A cross-client content leak on a privacy
product, produced by the very mechanism introduced to prevent one. Any revival must hash attachment
bytes (or their digests) **at every position**, stated in the key spec rather than hidden inside
`canonical()`.

**2. HIGH — the safety invariant is false on three shipping paths.** The design rests on *"sending
only the new message is semantically identical to a fresh conversation seeded with the full history,
and no foreign content is in the cache."* Not true on `main`:

- **Reasoning channel.** With `enableThinking` (`RelaisRequest:123`), the model's turn carries
  `channels["thought"]` content (`RelaisEngine.kt:715+`) that the client never sees and never
  resends. The cache holds `visible + thought`; the declared prefix holds `visible` only. Keying on
  `enableThinking` prevents *mixing modes*; it does not fix the divergence.
- **Node tools.** `nodeToolsEnabled` (`:129`) has the node execute tools and fold results in
  (`RelaisHttpServer.kt:1571-1577`) — more cache content the client never declared.
- **Structured output.** `RelaisHttpServer.kt:1719` appends `"\nRespond with ONLY valid JSON. No
  prose, no markdown fences."` to the system prompt before inference.

So identical requests would return different results depending on hit/miss.

**3. HIGH — the acceptance gate could not measure its own effect.** The pre-registered ≥50%
median-TTFT threshold was to be evaluated against a wall-clock TTFT measured from `sendStartNs`
(`:706`). But if `RelaisEngine.kt:633-638` is right and prefill happens at `createConversation`
(`:663`), then **both** arms — fresh and reused — exclude prefill from the measured window, the delta
is ~zero even when reuse saves 18 s of real wall clock, and the gate false-negatives a working
feature. The threshold was pre-registered precisely, on a quantity blind to the effect it gated.
**B1-A's two-timestamp design fixes this as a side effect**: once the prefill gap is a real series, a
revival has a valid instrument. That is a reason to land B1-A first regardless.

**4. MEDIUM — effective vs declared content was never disambiguated.** Session memory merges stored
turns into history (`RelaisHttpServer.kt:1249` `parsed.copy(history = merged)`, `:1438`) and
`resolveSystemPrompt` merges a server-side prompt (`:1611`, `:1874`). The design keyed on the
*declared* prefix throughout, which collides distinct prefills.

Beyond the four: the wiring point is the `finally` at `RelaisEngine.kt:797-802` — the single
guarantee that no conversation ever leaks — in a repo that has shipped "every layer green, the thing
doesn't start" **twice** (memory `relais-isolation-testing-blindspot`).

### What would have to be true to revive it

- **Scope it to session memory (#5), not arbitrary HTTP requests.** Sessions already have a stable
  identity and a store, so affinity keys on a real session id instead of a transcript hash. The blast
  radius becomes one feature and the leak question largely evaporates. This was already the plan's
  own Open Question — and it named the better design and then proceeded with the worse one.
- **B0's KV-rewind grep comes back positive.** If 0.12.0 exposes a real rewind/truncate/clone, the
  whole shape changes: exact-transcript affinity exists only because a conversation cannot be rolled
  back. B0 answers this for free — see its GOTCHA.
- **The key hashes attachment bytes and effective (not declared) content**, and the thinking /
  node-tools / structured divergences each have a stated resolution rather than an assumption.
- **A `security-reviewer` pass on the design, before any code.**

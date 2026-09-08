# Plan: Ollama-compatible API shim (`/api/*`)

> Branch: `feat/ollama-compat-api`. **Adds no second inference path** — every `/api/*` route is a pure
> wire translation in front of the handlers that already serve `/v1/chat/completions`,
> `/v1/embeddings`, and `/v1/models`. No new dependency, no new engine call, no new auth carve-out.
>
> All line numbers below were re-measured with `grep -n` / `sed -n` against the working tree on
> **2026-09-06** (`RelaisHttpServer.kt` = **2202 lines**, `wc -l`). Re-measure before editing; this
> repo has been burned twice by numbers copied forward (`CLAUDE.md` § Style rules).
>
> **Revision 2 (2026-09-06)** — reworked against an independent critic pass (1 CRITICAL, 4 HIGH,
> 5 MEDIUM, 1 LOW), each finding re-verified against source before acceptance. The four structural
> changes: `/api/chat` + `/api/generate` now run **inside `withInferenceAdmission`** (§ Task 6, the
> governing invariant below); `RelaisWire` is a **per-request class**, not a singleton (§ Task 5);
> streaming **tool calls** are translated rather than dropped (§ Task 3); and `RelaisOllamaRoutes.kt`
> is a file of **`internal` extension functions on `RelaisHttpServer`** with an explicit
> visibility-widening inventory (§ Files to Change), because every helper it calls is `private`.
>
> **Governing invariant (new, and the answer to "why is only some of `/api/*` gated?"):** *each
> `/api/*` route inherits exactly the admission discipline of the `/v1` route it delegates to* — no
> more, no less. `/v1/chat/completions` is wrapped in `withInferenceAdmission` (`:433-437`), so
> `/api/chat` and `/api/generate` are too. `/v1/embeddings` (`:404`) is **not** wrapped — it takes
> `shedIfHot` only, no queue permit — so `/api/embed` takes `shedIfHot` only. The metadata routes touch
> no engine and take neither. Any deviation from a delegate's discipline is a bug in this feature.

## Summary

A large class of local-LLM clients auto-discovers **Ollama**, not OpenAI, and speaks a different wire
format (NDJSON rather than SSE, `options{}` rather than top-level sampler fields,
`done`/`eval_count`/`total_duration` rather than `usage`, `name:tag` model ids rather than HF paths).
This feature adds `GET /api/tags`, `GET /api/version`, `POST /api/show`, `POST /api/chat`,
`POST /api/generate`, `POST /api/embed`, `POST /api/embeddings` (legacy) and `GET /api/ps` as a **thin
dialect adapter**: the Ollama request is translated into the exact OpenAI JSON body the existing
handler already understands, that handler runs unchanged, and its OpenAI-shaped response objects are
translated back on the way out through a swapped-in writer. Bearer auth is unchanged and mandatory on
every new route.

## User Story

**As a** person running Relais on a spare phone who already uses Open WebUI, Continue, Enchanted or
Raycast against Ollama,
**I want** to point those clients at `https://<phone>:8443` as an *Ollama* server with nothing but the
bearer key,
**So that** the model list, streaming chat, tool calls and vision affordances light up with zero
per-client adapter, config shim, or proxy.

## Problem → Solution

| | |
|---|---|
| **Problem** | Relais speaks only the OpenAI dialect. Clients whose Ollama integration is the *native* path (Open WebUI's Ollama connection, Continue's `ollama` provider, Raycast's Ollama extension, Enchanted, several Obsidian plugins, Home Assistant's Ollama integration) either cannot connect at all or lose features that only exist on their Ollama code path. Each such client is a separate lost integration, and the gap is pure wire format — the node can already serve every one of these requests. |
| **Solution** | One translation layer. Eight routes, three new files, zero new inference paths. Requests are rewritten into the OpenAI bodies the existing handlers parse; responses are rewritten on the way out by a `RelaisWire` seam whose OpenAI implementation is the identity function, so the `/v1/*` behavior is provably unchanged. |
| **Explicitly not the solution** | A second engine call path, a parallel request parser, a relaxed auth gate for "discovery" endpoints, or a vendored Ollama server. |

## Metadata

| Field | Value |
|---|---|
| **Complexity** | **Large** — 7 new files, 5 edited, a seam refactor threaded through three existing handlers whose failure mode is regressing `/v1/*`, and a visibility-widening pass across seven `private` members. |
| **Source PRD** | N/A |
| **PRD Phase** | N/A |
| **Estimated Files** | 12 (7 CREATE, 5 UPDATE) |
| **Estimated effort** | ~2.5 focused days, dominated by Tasks 2/3 (translation tables) and Task 5 (the seam). |

## UX Design

**Before / After diagrams:** N/A — this is a network-protocol change with no in-app UI surface. No
Compose screen, no `DESIGN.md` decision, no new control-panel row. (`docs/ollama-api.md` and the
README are the only human-visible artifacts.)

### Interaction Changes

| Touchpoint | Before | After | Notes |
|---|---|---|---|
| Open WebUI setup | Add an **OpenAI** connection, base URL `https://<ip>:8443/v1` | May instead add an **Ollama** connection, base URL `https://<ip>:8443` (no `/v1`) | Both keep working. The Ollama path additionally reads `/api/show` `capabilities[]` to decide whether to show the image/tool affordances. |
| Continue / Enchanted / Raycast | Only the OpenAI provider | The `ollama` provider works, with `apiKey` set | These clients all have an API-key field; no compromise needed. |
| Home Assistant Ollama integration | Unusable | **Still unusable directly** — the integration exposes no auth field | Documented honestly in `docs/ollama-api.md` with a header-injecting reverse-proxy / overlay hop as the answer. Not a reason to weaken the gate. **`feature-21-home-assistant.plan.md` is being aligned to this statement** and must not claim HA's Ollama integration works against this shim; see § Cross-plan sequencing. |
| Anonymous `GET /api/tags` probe | 404 (no route) | **401** (bearer gate) | A client that probes without a key reports "server unreachable / not an Ollama server", not "auth required". Must be stated plainly in the docs or the first support question becomes "Relais is broken." |
| `README.md` "Works with" (`README.md:126-135`) | Lists OpenAI-compatible clients only | Gains an Ollama-native client list and the base-URL difference | |
| `/v1/*` clients | — | **No change whatsoever** | The identity-wire invariant; the acceptance criteria make it grep-checkable. |

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| **P0** | `CLAUDE.md` | all | New routes go in **new files** — `RelaisHttpServer.kt` is 2202 lines against a repo target under 800. Also: JVM tests for pure logic, `*Probe.kt` for endpoint/IO capability checks, no Gradle unless asked. |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 180-300 (accept loop, auth/rate/body gate, router `when`), **433-437 (the `/v1/chat/completions` router branch — the shape `/api/chat` must copy)**, **518-535 (`withInferenceAdmission`), 537-552 (`shedIfHot`), 554-576 (`rejectIfQueueFull`)**, 685-703 (`RequestContext`), 906-911 (`handleModels`), 975-1019 (`handleEmbeddings`), 1119-1120 (`provisionedOnDisk`), 1138-1200 (`rejectIfModelUnavailable`), 1206-1372 (`handleOpenAi`), 1622-1671 (`handleToolCompletion`), 1682-1776 (`handleStructuredCompletion`), 1777-1811 (`parseOpenAiRequest`), **1821-1822 (`dataUriBytes` — the real data-URL decoder)**, 1877-1899 (`endpointLabel`), 1901-1906 (`authorized`), 1933-1958 (`respond`/`respondText`/`respondBytes`) | Every seam this feature touches. The admission trio (518-576) is **new to revision 2** and is the CRITICAL finding: it is the whole backpressure discipline, and it lives in the router branch, not in `handleOpenAi`. |
| **P0** | `SECURITY.md` | all | The bearer-gate posture the auth recommendation must not weaken. |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/SseWriter.kt` | 1-82 | `NdjsonWriter.kt` mirrors it one-for-one; `SseWriter` becomes the OpenAI `StreamSink` implementation. |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpIo.kt` | 30-60 (`HttpRequestReader`: shared `BufferedInputStream`, single-consumption body) | Why `handleEmbeddings` must be split. **Citation corrected in revision 2:** `RelaisHttpIo.kt:317-325` is `sanitizeImageMime`, an *encode*-side helper for the multipart upload path — it is **not** the data-URL decoder and does not support the `image/jpeg` claim. |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` + `RelaisOpenAiParser.kt` | `RelaisHttpServer.kt:1821-1822` (`dataUriBytes`), `RelaisOpenAiParser.kt:93` (its call site via `buildPromptParts`) | The **actual** decoder, and why `image/jpeg` is a safe constant for Ollama's mime-less `images[]`: `dataUriBytes` is `decode(if (url.startsWith("data:")) url.substringAfter(",") else url)` — everything before the comma, the declared mime included, is discarded before decoding. Verify this line before relying on it. |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisError.kt` | 32-74 | The taxonomy that is deliberately dropped on `/api/*` (Ollama's envelope has nowhere to put it). |
| **P1** | `docs/openapi.yaml` | 174-224 (`/v1/chat/completions`), 272-325 (`/v1/embeddings`), 692-745 (`/v1/models`) | Style to match for the eight new paths. |
| **P2** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | 109-140 (`RelaisRequest`), ~301 (`residentModelId`) | Proves there is no `max_tokens`/`top_k`/`stop` plumbing — bounds the `options{}` mapping table. |
| **P2** | `Android/src/app/src/main/java/cc/grepon/relais/data/RelaisModelRef.kt`, `data/ModelAllowlist.kt` | 1-60 | The `/api/tags` field sources (`modelId`, `commitHash`, `sizeInBytes`, `displayName`). |
| **P2** | `.claude/HANDOFF.md` | latest section | Current repo/PR state before branching. |

## External Documentation

| Topic | Source URL | Key Takeaway |
|---|---|---|
| Ollama REST API (all eight routes) | `https://github.com/ollama/ollama/blob/main/docs/api.md` (raw, re-fetched 2026-09-06 for revision 2) | NDJSON streaming, `options{}` bag, `format`, `think`, `keep_alive`; duration/count fields on the terminal line; `/api/embed` 2-D vs legacy `/api/embeddings` 1-D. The doc **does** state *"All durations are returned in nanoseconds"* and **does** show `capabilities` in the `/api/show` example; it **never states a default for `stream`**. See the corrected split below. |

**RESEARCH ITEM — Ollama streaming framing**
- **KEY_INSIGHT:** Streaming is one JSON object per line (NDJSON). There is **no** `data:` prefix, **no**
  blank-line separator, and **no** `[DONE]` sentinel. The stream terminates with a line carrying
  `"done": true`. Ollama itself labels it `Content-Type: application/json`.
- **APPLIES_TO:** `NdjsonWriter.kt`, `OllamaStreamSink`, tests 17-20.
- **GOTCHA:** Copying `SseWriter` and forgetting to strip the framing produces output that parses as
  "nothing" to every Ollama client while looking fine in a hexdump. `done()` must be a **no-op**.

**RESEARCH ITEM — `stream` default inversion**
- **KEY_INSIGHT:** `/api/chat` and `/api/generate` default `stream` to **`true`**; OpenAI defaults it to
  `false` (`RelaisHttpServer.kt:1226`, `body.optBoolean("stream", false)`).
- **EVIDENCE GRADE — inferred, not documented.** `docs/api.md` never states the default. What it states
  is *"This is a streaming endpoint, so there will be a series of responses"* and *"Streaming can be
  disabled by providing `{"stream": false}`"* — wording that only makes sense if omitting the field
  streams, but which is an inference, not a spec line. Revision 1 asserted this as a doc fact; it is
  not. **Task 10 must produce the empirical proof** (one `curl` with `stream` omitted).
- **APPLIES_TO:** the request translator; test 1.
- **GOTCHA:** `body.optBoolean("stream", false)` in the translator is the single most likely silent
  break in the feature — every client that omits the field gets a blocking response and many hang
  waiting for a stream. Mutation-check test 1.

**RESEARCH ITEM — tool-call arguments type**
- **KEY_INSIGHT:** OpenAI carries `tool_calls[].function.arguments` as a **JSON-encoded string**;
  Ollama carries it as a **JSON object**.
- **APPLIES_TO:** the chat response translator; test 9.
- **GOTCHA:** Passing the string through type-checks, serializes, and breaks every Ollama tool client —
  and is invisible to any smoke test that does not use tools.

**RESEARCH ITEM — `/api/embed` vs `/api/embeddings`**
- **KEY_INSIGHT:** `/api/embed` returns `embeddings: number[][]`; legacy `/api/embeddings` returns
  `embedding: number[]` — singular, flat, un-nested.
- **APPLIES_TO:** two separate translators; test 14.
- **GOTCHA:** Collapsing them into one handler silently hands legacy clients a nested array they parse
  as a single 1-element vector.

**Doc-verification split — CORRECTED in revision 2.** Revision 1 had this backwards in both
directions: it hedged two things the doc states outright and asserted one thing the doc never says.
The critic caught it; the raw doc was re-fetched and each row below re-read.

| # | Item | Status after re-reading `docs/api.md` | What Task 10 must still do |
|---|---|---|---|
| 1 | **Duration units** | **DOCUMENTED.** Verbatim: *"All durations are returned in nanoseconds."* Not an assumption; state it as fact in `docs/ollama-api.md`. | Sanity-check magnitude only (a ~1 s generation reading ~1e9). |
| 2 | **`/api/show` `capabilities[]`** | **PARTLY DOCUMENTED.** The `/api/show` response example shows `"capabilities": ["completion", "vision"]`. `"tools"` and `"thinking"` are **not** in the doc. | Capture the real vocabulary from a live server; drop any value it does not emit. |
| 3 | **`created_at` precision** | **TWO FORMATS DOCUMENTED**, not one: `2023-08-04T08:52:19.385406455-07:00` (ns, offset) **and** `2023-08-04T19:22:45.499127Z` (µs, `Z`). So clients demonstrably tolerate both; "always ns digits" was format-matching a single example. Emit the ns/offset form; note that precision is not load-bearing. | Confirm no client rejects the emitted form. |
| 4 | **Error envelope** | **GENUINELY UNDOCUMENTED.** The doc specifies no error body shape at all. The flat `{"error": "<message>"}` convention comes from server/client source, not the doc. | **The one item that needs real evidence.** Capture an actual error body + status. |
| 5 | **`stream` default = `true`** | **NOT DOCUMENTED — inferred.** Revision 1 asserted this as a doc fact and hung test 1, a mutation check and the top risk row on it. The doc says only that streaming *"can be disabled by providing `{"stream": false}`"`*. Almost certainly right; currently unproven. | **Prove it empirically** — one `curl` with `stream` omitted. |

## Patterns to Mirror

### NAMING_CONVENTION — file header, package, KDoc-with-rationale

```kotlin
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/SseWriter.kt:1-30
/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 * ...
 */

package cc.grepon.relais

import java.io.OutputStream
import org.json.JSONObject

/**
 * The ONE `text/event-stream` writer for both streaming paths in [RelaisHttpServer] (issue #173
 * item 4) — the 200 SSE header write and the post-header abort-catch were duplicated between the
 * plain-chat and tool-completion streaming handlers. Not thread-safe; one instance per request.
 * ...
 */
class SseWriter(private val out: OutputStream) {
```

All seven new files are **net-new Relais code → AGPL header**, not the Apache header carried by
Gallery-derived files. Use the full AGPL form (`This file is part of Relais.` … `see
<https://www.gnu.org/licenses/>`):

```kotlin
// SOURCE: Android/src/app/src/test/java/cc/grepon/relais/RelaisClientConfigTest.kt:1-17
/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * ...
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */
```

Note the contrast: `OpenAiRequestParserTest.kt:1-15` carries the **Apache-2.0 / Google LLC** header
because it descends from Gallery. Do not copy that one into new files.

### ERROR_HANDLING — one envelope function, typed constants, per-route thin wrappers

```kotlin
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisError.kt:32-59
object RelaisError {
  /** Malformed/invalid client request (400/413/422/431 paths). */
  const val INVALID_REQUEST = "invalid_request_error"
  ...
  /** `{"error":{"message":[message],"type":[type]}}` — the OpenAI-compatible error envelope. */
  fun json(message: String, type: String): JSONObject =
    JSONObject().put("error", JSONObject().put("message", message).put("type", type))
}
```

```kotlin
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:1006-1008
if (embedder is EmbeddingGemmaEmbedder && embedder.canProvision(context)) {
  embedder.ensureProvisioningStarted(context)
  ctx.reply(503, buildEmbeddingsError("embeddings model is provisioning; retry shortly", RelaisError.SERVICE_UNAVAILABLE), listOf("Retry-After: 10"))
```

`RelaisOllamaWire.error(status, message)` is the `/api/*` analog: **one** function, every failure path
routed through it, status and `extraHeaders` preserved verbatim.

### LOGGING_PATTERN — one file-level `TAG`, `Log.e` only for genuinely unexpected failures

```kotlin
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:68
private const val TAG = "RelaisHttpServer"

// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:1363-1366
} catch (e: Exception) {
  Log.e(TAG, "stream error after headers committed", e)
  sse.abort()
}
```

Client-caused 4xx are **not** logged (they are metric-counted instead). New code follows that split:
no `Log.w` per bad Ollama request.

### HANDLER_PATTERN — router branch + `RequestContext` + extracted handler

```kotlin
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:402-406
          method == "GET" && path.startsWith("/v1/models") -> handleModels(ctx)

          method == "POST" && path.startsWith("/v1/embeddings") -> handleEmbeddings(ctx)

          method == "POST" && path.startsWith("/v1/rerank") -> handleRerank(ctx)
```

```kotlin
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:690-703
  /** The request-scoped values an extracted handler needs (so signatures stay short). */
  private class RequestContext(
    val sock: java.net.Socket,
    val reader: HttpRequestReader,
    val contentLength: Int,
    val path: String,
    val endpoint: String,
    val accept: String?,
    val sessionEnabled: Boolean,
    val sessionHeader: String?,
    val reply: (Int, JSONObject, List<String>) -> Unit,
  ) {
    /** 2-arg convenience mirroring the handle()-local reply's default (empty headers). */
    fun send(status: Int, body: JSONObject) = reply(status, body, emptyList())
  }
```

```kotlin
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:906-911
  private fun handleModels(ctx: RequestContext) {
    val refs = RelaisModelCatalog.curatedModels()
    val fallback = RelaisConfig.modelId(context)
    val onDisk = provisionedIds(provisionedOnDisk())
    ctx.send(200, buildModelsResponse(refs, fallback, onDisk))
  }
```

Every new `/api/*` handler is this shape: one router line, one thin handler, all real logic in a pure
builder that a JVM test can reach.

### ADMISSION_PATTERN — the inference gate lives in the **router branch**, not in the handler

This is the pattern revision 1 missed entirely, and it is why `/api/chat` must be registered
differently from `/api/tags`:

```kotlin
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:433-437
          method == "POST" && path.startsWith("/v1/chat/completions") ->
            // handleOpenAi may commit the SSE 200 header before returning, so post-commit errors are
            // handled inside handleOpenAi itself; the shared gate + latency are still released/recorded
            // in withInferenceAdmission's finally.
            withInferenceAdmission("/v1/chat/completions", ::reply) {
```

```kotlin
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:518-535
  private inline fun withInferenceAdmission(
    endpoint: String,
    // noinline: `reply` is forwarded to the non-inline shedIfHot/rejectIfQueueFull. `block` stays
    // inline so a `return` inside it is a non-local return from the caller.
    noinline reply: (Int, JSONObject, List<String>) -> Unit,
    block: () -> Unit,
  ) {
    if (shedIfHot(reply)) return         // thermal 503 wins first
    if (rejectIfQueueFull(reply)) return // admission 429 second — shared permit acquired
    val startNs = System.nanoTime()
    try {
      block()
    } finally {
      RelaisMetrics.recordEndpointLatency(endpoint, (System.nanoTime() - startNs) / 1e9)
      admissionGate.releaseShared()
    }
  }
```

Four consequences, all load-bearing for this feature:

1. **It is not optional.** Skipping it means no thermal 503, no queue 429, **no shared permit taken**
   — which does not merely lose backpressure on `/api/*`, it corrupts the gate's accounting for the
   `/v1` clients sharing the same node — and no `recordEndpointLatency`.
2. **Its 503/429 bodies go through the `reply` lambda it is handed**, so an `/api/*` route that passes
   the raw `::reply` answers a thermally-shedding node in the **OpenAI** envelope. That is seam 5
   (§ Notes → the five-seam error checklist).
3. **`block` must stay `inline`** — the existing `/v1/chat/completions` block uses a bare `return` for
   its 400/413 multipart paths (`:451`, `:463`), which is a non-local return from `handle()`. Do not
   "simplify" it to a non-inline function to make it `internal`.
4. **The `endpoint` argument is what `recordEndpointLatency` labels**, independently of
   `wire.endpoint` and of `endpointLabel`. Pass `"/api/chat"` / `"/api/generate"`.

### TEST_STRUCTURE — pure JVM, `org.junit.Test`, backtick names, banner comments, raw-string JSON

```kotlin
// SOURCE: Android/src/app/src/test/java/cc/grepon/relais/OpenAiRequestParserTest.kt:17-50
package cc.grepon.relais

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [buildPromptParts] (Feature 03 — multi-turn messages).
 *
 * All tests are device-free: no Context, no Android SDK, no Robolectric.
 * [buildPromptParts] is an internal top-level function in RelaisOpenAiParser.kt.
 */
class OpenAiRequestParserTest {

  // ---------------------------------------------------------------------------
  // Case 1 — system-only (no user turns)
  // ---------------------------------------------------------------------------

  @Test
  fun `system-only messages returns system prompt and empty history`() {
    val messages = JSONArray("""[{"role":"system","content":"Be concise."}]""")
    val result = buildPromptParts(messages)
    assertEquals("Be concise.", result.systemPrompt)
    assertTrue(result.history.isEmpty())
    ...
  }
```

The KDoc-states-the-security-spine convention is also live and worth copying for the auth-adjacent
assertions:

```kotlin
// SOURCE: Android/src/app/src/test/java/cc/grepon/relais/RelaisClientConfigTest.kt:27-35
/**
 * Hermetic JVM tests for the Feature #11 pure builders (no Context, no Android).
 *
 * The security spine of this feature is "the API key never enters the cleartext mDNS TXT broadcast,
 * and only ever appears in the bearer-gated /v1/clientconfig JSON". A sentinel key is threaded
 * through every builder and scanned for, so a future refactor that leaks the key into the TXT (or
 * unmasks it somewhere) trips a test rather than shipping.
 */
class RelaisClientConfigTest {
```

### PROBE_STRUCTURE — `androidTest/*Probe.kt` with a runnable `am instrument` line in the header

An on-device probe **is** part of this plan (Task 9). Eight new network routes are endpoint/IO code,
which `CLAUDE.md` routes to `androidTest`, and `[[relais-isolation-testing-blindspot]]` is precisely
this feature's failure mode: every JVM test here verifies a *part*, and nothing in the JVM suite
proves a real Ollama client can talk to this node — which is the entire point of the feature.

```kotlin
// SOURCE: Android/src/app/src/androidTest/java/cc/grepon/relais/ToolCallingProbe.kt:17-55
package cc.grepon.relais

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
...
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
 *     -e model /storage/emulated/0/Android/data/com.ventouxlabs.relais.izzy/files/... \
 *     com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Watch: adb logcat -s RelaisToolProbe
 */
@RunWith(AndroidJUnit4::class)
class ToolCallingProbe {
```

## Files to Change

| File | CREATE/UPDATE | Justification |
|---|---|---|
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisOllamaWire.kt` | **CREATE** | All translation: request→OpenAI body, OpenAI response→Ollama object, chunk→Ollama **lines** (0/1/2), name↔id mapping, options mapping, duration math, error envelope, `/api/tags`/`/api/show`/`/api/ps` builders, the `RelaisWire`/`StreamSink` interfaces, `OpenAiWire`, the per-request `OllamaChatWire`/`OllamaGenerateWire`, and **`OllamaStreamSink`** (the fan-out that turns one OpenAI chunk into 0-2 NDJSON lines). Everything here is `JSONObject` in, `JSONObject` out with **one** exception — `OllamaStreamSink` holds an `NdjsonWriter` — so: no `Context`, no socket, no Android type, and JVM-testable throughout. |
| `Android/src/app/src/main/java/cc/grepon/relais/NdjsonWriter.kt` | **CREATE** | The NDJSON streaming writer, mirroring `SseWriter.kt` one-for-one. I/O over an `OutputStream`, so JVM-testable with `ByteArrayOutputStream`. |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisOllamaRoutes.kt` | **CREATE** | The eight thin handlers, written as **`internal` extension functions on `RelaisHttpServer`** (`internal fun RelaisHttpServer.handleOllamaChat(ctx: RequestContext)`). Not top-level free functions: everything they call is a `private` member of that class, and an extension in another file can see `internal` members but **not** `private` ones. See the visibility inventory below. Deliberately thin so almost nothing here needs a device to verify. |
| `Android/src/app/src/test/java/cc/grepon/relais/RelaisOllamaWireTest.kt` | **CREATE** | One test file per subject file (`CLAUDE.md` § Style rules). Tests 1-16, 27, 29, 30. |
| `Android/src/app/src/test/java/cc/grepon/relais/NdjsonWriterTest.kt` | **CREATE** | Tests 17-20. |
| `Android/src/app/src/test/java/cc/grepon/relais/RelaisOllamaRoutesTest.kt` | **CREATE** | Tests 21-26, 28 (the seam, the derived-reply translation, and the admission-gate envelope). |
| `Android/src/app/src/androidTest/java/cc/grepon/relais/OllamaCompatProbe.kt` | **CREATE** | On-device: does a real client-shaped request over the real socket actually work? |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | **UPDATE** | Eight router branches — six one-liners plus **two multi-line `withInferenceAdmission` wrappers** for `/api/chat` and `/api/generate` (~18 lines); eight `endpointLabel` arms; the `wire` seam threaded through three handlers; the `handleEmbeddings` shell/core split; `RequestContext` `private`→`internal` + a `withReply` helper; the visibility widenings below. **Budget: under ~75 net added lines**, measured `wc -l` before/after in the PR body. |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | **UPDATE** | One accessor for `/api/ps`'s `expires_at`. `lastActivityAtMs` is `@Volatile private var` at `:230` and `RelaisIdleTtl.kt` exposes only constants + `shouldUnloadIdleEngine`, so today there is **no reachable source** for the field. **`feature-22-idle-unload.plan.md:438` already proposes a read-only accessor over the same field** — `val idleSeconds: Double get() = (System.currentTimeMillis() - lastActivityAtMs) / 1000.0`, added *"rather than widening the field"*. If feature-22 lands first, `/api/ps` derives `expires_at` from `idleSeconds` + the TTL and **this file drops out of the table entirely**. Check before adding a second accessor. |
| `docs/ollama-api.md` | **CREATE** | Route reference, per-client auth table, every documented deviation. |
| `docs/openapi.yaml` | **UPDATE** | Eight new paths in the existing style (see `:174` `/v1/chat/completions`, `:692` `/v1/models`). |
| `README.md` | **UPDATE** | "Works with" (`:126-135`) gains the Ollama-native clients and the base-URL difference; the Endpoints table (`:138-145`) gains an `/api/*` row. |

### Visibility-widening inventory (new in revision 2 — the implementer hits this on the first compile)

Revision 1 budgeted exactly one widening and put the handlers in a top-level file. That does not
compile: every symbol they call is `private` in `RelaisHttpServer`. The **complete** list, verified by
`grep -n` on 2026-09-06:

| Symbol | Line | Today | Becomes | Needed by |
|---|---|---|---|---|
| `RequestContext` (+ new `withReply`) | 690 | `private class` | `internal class` | every handler's parameter type |
| `handleOpenAi` | 1206 | `private fun` | `internal fun` | `/api/chat`, `/api/generate` |
| `handleEmbeddings(ctx, body)` — the new **core** overload only | 975 | (new) | `internal fun` | `/api/embed`, `/api/embeddings` |
| `readBody` | 1913 | `private fun` | `internal fun` | every POST route |
| `resolveSessionKey` | 1549 | `private fun` | `internal fun` | `/api/chat` session parity with `/v1` |
| `provisionedOnDisk` | 1119 | `private fun` | `internal fun` | `/api/tags`, `/api/show`, `/api/ps` |
| `context` | 171 | `private val` | `internal val` | only if a route needs `RelaisConfig.*(context)`; `/api/tags` as specified does **not** — prefer leaving it private and confirm during Task 6 |

**Deliberately NOT widened — and the reason the design avoids needing to:**
`withInferenceAdmission` (`:518`, `private inline`), `shedIfHot` (`:537`), `rejectIfQueueFull`
(`:554`) and `admissionGate` (`:183`). Making an `internal inline` function reference `private`
members is a compile error, so widening `withInferenceAdmission` would cascade into
`@PublishedApi internal` on the other three. **The wrapper therefore stays in the router branch**
(§ Task 6), exactly where `/v1/chat/completions` already puts it at `:433-437` — which is both the
smaller diff and the closer mirror. Already `internal` top-level and needing nothing:
`provisionedIds` / `ProvisionedModel` (`RelaisModelRegistry.kt:72`, `:36`), `buildModelsResponse`
(`:2067`), `RelaisModelCatalog`, `RelaisMetrics`, `RelaisError`.

## NOT Building

- **No `/api/pull`, `/api/push`, `/api/create`, `/api/copy`, `/api/delete`, `/api/blobs/*`.** Model
  provisioning on Relais is the control panel's job; exposing a LAN endpoint that downloads
  multi-gigabyte models on demand is a resource-exhaustion surface, not a convenience.
- **No `keep_alive` honoring.** Mapping it to `RelaisIdleTtl` would let any LAN client pin a multi-GB
  model resident indefinitely. Accepted and ignored, documented.
- **No `context[]` on `/api/generate`.** Deprecated in Ollama's own doc and there is no token-id
  round-trip available here. Do not fake it; point clients at `/api/chat`.
- **No `raw: true` templating bypass.** LiteRT-LM applies its own template. Accepted and ignored,
  documented — not faked.
- **No path-prefix API key** (`/{key}/api/chat`). See § Notes for the full tradeoff; deferred.
- **No `/api/*` change to the `/health` auth carve-out**, and no "discovery endpoints are public"
  exception for `/api/tags` or `/api/version`.
- **No new sampler plumbing.** `num_predict`, `top_k`, `stop`, `repeat_penalty`, `num_ctx` are dropped
  because `RelaisRequest` has no fields for them. Adding them is a separate feature.
- **No changes to any existing test file.** If an existing `/v1` test needs editing, the seam is wrong.
- **No dimension reduction** for `/api/embed`'s `dimensions` parameter.

## Step-by-Step Tasks

### Task 1 — `RelaisOllamaWire.kt`: name mapping, error envelope, duration math

- **ACTION:** Create `RelaisOllamaWire.kt` with the AGPL header and the pure primitives.
- **IMPLEMENT:**
  ```kotlin
  /** Relais model id -> Ollama-style "name:tag". Deterministic; collisions disambiguated by suffix. */
  fun ollamaName(modelId: String): String
  /** Accepts an Ollama-style name OR a raw Relais id; null when it matches nothing in [known]. */
  fun resolveOllamaName(name: String, known: List<String>): String?
  /** `{"error": message}` — Ollama's flat envelope. Status is carried separately and preserved. */
  fun error(message: String): JSONObject
  /** Nanosecond duration quadruple for a terminal line. */
  fun durations(startNanos: Long, firstTokenNanos: Long?, endNanos: Long): JSONObject
  ```
  Derivation for `ollamaName`: `substringAfterLast('/')` → lowercase → strip `-it-litert-lm` /
  `-litert-lm` → split a trailing parameter token (`e4b`, `e2b`, `1b`, `270m`, …) into the tag →
  `base:tag`, falling back to `:latest` when no parameter token is present. When two catalog ids derive
  the same `name:tag`, the **second** keeps a disambiguating suffix — never silently merged.
  `durations`: `total_duration` = `endNanos - startNanos`; `prompt_eval_duration` =
  `(firstTokenNanos ?: endNanos) - startNanos`; `eval_duration` = `total - prompt_eval`;
  `load_duration` = **0** (the node cannot separate model-load from prompt-eval; zero is honest, an
  invented number is a lie a dashboard would render), accompanied by
  `x_relais_duration_note: "load_duration_unavailable; prompt_eval_duration measured to first token"`.
- **MIRROR:** NAMING_CONVENTION (AGPL header, KDoc-with-rationale); ERROR_HANDLING (one envelope
  function, every path through it).
- **IMPORTS:** `org.json.JSONObject`, `org.json.JSONArray`. **Nothing from `android.*`.**
- **GOTCHA:** `resolveOllamaName` must accept the **raw Relais id** too — a client that read
  `/v1/models`, or a proxy fronting both dialects, sends the HF path, and 404ing it would reject a
  model the node is actively serving.
- **VALIDATE:** Tests 2, 15, 16 RED → GREEN.

### Task 2 — `RelaisOllamaWire.kt`: `/api/chat` + `/api/generate` request translators

- **ACTION:** Add `chatRequest(ollama: JSONObject, resolvedModel: String): JSONObject` and
  `generateRequest(...)`, each returning the OpenAI body `handleOpenAi` already parses.
- **IMPLEMENT:** Mapping table for `/api/chat`:

  | Ollama request | OpenAI body | Note |
  |---|---|---|
  | `model` | `model` | via `resolveOllamaName`; unresolvable → 404 with the Ollama envelope, before the engine |
  | `messages[].role/content` | `messages[].role/content` | `tool` role passes through; `tool_name` → `name` |
  | `messages[].images[]` (bare base64) | content-array part `{type:"image_url", image_url:{url:"data:image/jpeg;base64,…"}}` | Ollama carries no mime; the decoder is `dataUriBytes` (**`RelaisHttpServer.kt:1821-1822`**, called via `buildPromptParts` → `RelaisOpenAiParser.kt:93`) and it discards everything before the comma — the declared type included — so `image/jpeg` is inert. **Verify that line before relying on it;** revision 1 cited `RelaisHttpIo.kt:317-325`, which is `sanitizeImageMime` on the *encode* side and does not support the claim |
  | `messages[].thinking` | dropped | assistant-side thinking is not re-seeded |
  | `tools[]` | `tools[]` | shapes already match (`{type:"function", function:{name, description, parameters}}`). **`stream` is NOT forced off** — see Task 3 for how the single tool chunk is translated, and GOTCHA 4 for why forcing it off was rejected |
  | `think: true` / `"low".."max"` | `reasoning_effort: "low"/"medium"/"high"` | any non-false value → thinking on; the graded strings collapse |
  | `format: "json"` | `response_format:{type:"json_object"}` **+ `stream` forced `false`** | see GOTCHA |
  | `format: {…schema…}` | `response_format:{type:"json_schema", json_schema:{name:"response", schema:{…}}}` **+ `stream` forced `false`** | same |
  | `options.temperature` / `.top_p` / `.seed` | `temperature` / `top_p` / `seed` | the only three `parseOpenAiRequest` reads (`RelaisHttpServer.kt:1777-1811`) |
  | `options.num_predict`, `top_k`, `stop`, `repeat_penalty`, `num_ctx`, … | **dropped** | no plumbing in `RelaisRequest`; names collected into `x_relais_ignored_options` on the response |
  | `stream` (**default `true`**) | `stream` | the default inversion |
  | `keep_alive` | **ignored** | documented, not silent |

  `/api/generate` is identical except the body becomes a one-user-message array (plus a `system`
  message when `system` is set); `template` and `raw` are accepted and ignored.
- **MIRROR:** TEST_STRUCTURE for the accompanying tests; ERROR_HANDLING for the unresolvable-name path.
- **IMPORTS:** `org.json.JSONObject`, `org.json.JSONArray`.
- **GOTCHA (two, both field-breaking):**
  1. **`stream` defaults to `true`.** Write `optBoolean("stream", true)`, not the `false` that
     `RelaisHttpServer.kt:1226` uses for OpenAI.
  2. **`format` × default `stream` is a hard 400 on the `/v1` path.**
     `handleStructuredCompletion` rejects `stream + response_format` with a 400 at
     `RelaisHttpServer.kt:1691-1692` ("stream and response_format cannot be combined"). Ollama's
     `stream` defaults to true and `format:"json"` is one of the most common Ollama request shapes, so
     the naive translation 400s nearly every structured-output request from every Ollama client, for a
     restriction those clients cannot know about. **Fix in the translator:** when `format` is present
     and `stream` was not explicitly `false`, emit `stream:false`. Record the substitution in its
     **own** field, `x_relais_stream_substituted: true` — **not** in `x_relais_ignored_options`, which
     means one specific thing (the `options{}` keys this node has no plumbing for). Overloading one
     field with two unrelated meanings is exactly the ambiguity these `x_relais_*` fields exist to
     remove. The client sees a latency change, never an error.

     **Framing consequence — name it, do not hand-wave it.** With `stream:false` the handler answers
     through `respond(...)`, i.e. a **single JSON object** with `Content-Length` and
     `Content-Type: application/json` — *not* a committed NDJSON stream. That object carries
     `done:true`, so a client that reads the response line-by-line parses it as a one-line stream and
     finishes; a client that requires ≥2 lines, or that switches parsers on
     `Content-Type: application/x-ndjson`, will not. This is a **real, unresolved-by-design
     deviation**, not a proven-safe one: it has a Risk row, a `docs/ollama-api.md` line, and an
     explicit probe assertion in Task 9. It is accepted because the alternative — pre-committing an
     NDJSON header in the route and re-framing `respond`'s output — pushes streaming state into the
     router, which is the shape this plan rejects everywhere else.
  3. **Never emit `stream_options`.** `RelaisHttpServer.kt:1348-1349` reads
     `if (!includeUsage) finalChunk.put("usage", usageObj)` — usage rides the **final chunk** exactly
     when `stream_options.include_usage` is absent. The Ollama terminal line reads
     `eval_count`/`prompt_eval_count` straight off that chunk's `usage`, so emitting `stream_options`
     would silently strip the token counts.
  4. **`tools[]` does NOT force `stream:false`, and the reason is worth recording.** Forcing it would
     be free in perceived streaming — `handleToolCompletion`'s stream branch emits **exactly one**
     chunk (`:1653-1667`: `SseWriter` at `:1654`, the one `chunk` built at `:1659-1665`, `send` at
     `:1666`, `done` at `:1667`), so the "stream" is already a single burst — and it would recover the token
     counts the tool path never emits. It is rejected anyway because it would create a **second**
     instance of the single-JSON-object framing deviation above, on the path most likely to be driven
     by an automated client. Framing correctness beats cosmetic counts. Task 3 translates the chunk
     instead; the missing counts are declared, not hidden.
- **VALIDATE:** Tests 1, 3, 4, 5, 6, 7, 11, 23 RED → GREEN. **Mutation-check tests 1 and 23.**

### Task 3 — `RelaisOllamaWire.kt`: response translators

- **ACTION:** Add `chatResponse`, `generateResponse`, and `chatChunk` (the streaming converter).
- **IMPLEMENT:**

  | OpenAI response | Ollama response |
  |---|---|
  | `choices[0].message.content` | `message.content` (flat `response` string for `/api/generate`) |
  | `choices[0].message.reasoning_content` | `message.thinking` |
  | `choices[0].message.tool_calls[].function.arguments` (**JSON string**) | `message.tool_calls[].function.arguments` (**JSON object** — parsed, not passed through) |
  | `choices[0].finish_reason` | `done_reason` + `done: true` |
  | `usage.prompt_tokens` / `.completion_tokens` | `prompt_eval_count` / `eval_count` |
  | `model` | `model` (re-mapped through `ollamaName`) |
  | — | `created_at` (RFC-3339, ns precision), duration quadruple from Task 1 |
  | `x_relais_usage_note` | carried through verbatim |

  **Streaming — `chatChunk` returns a `List<JSONObject>` (zero, one or two lines), not one object,
  and a named `OllamaStreamSink` performs the fan-out.** `handleOpenAi` calls `sink.send(chunk)` once
  per OpenAI chunk, while `NdjsonWriter.send` writes exactly one line, so something must sit between
  them. That something is a **deliverable, not prose**:

  ```kotlin
  // RelaisOllamaWire.kt — returned by OllamaChatWire.stream(out) / OllamaGenerateWire.stream(out).
  // The ONE class in this file that touches an OutputStream, and only through NdjsonWriter.
  internal class OllamaStreamSink(
    private val out: NdjsonWriter,
    private val wire: OllamaChatWire,   // per-request: model name, start clock, ignored options
  ) : StreamSink {
    override fun send(json: JSONObject) { wire.chatChunk(json).forEach(out::send) }  // 0, 1 or 2 lines
    override fun commitHeader() = out.commitHeader()
    override fun done() = out.done()          // no-op: `done:true` on the last line is the terminator
    override fun abort(message: String) = out.abort(message)
  }
  ```
  **`NdjsonWriter` stays dumb** — one line per `send`, no translation, no knowledge of Ollama
  semantics. All dialect logic is in `chatChunk`, which a JVM test reaches directly. And note the
  contract this establishes on `StreamSink`: **one `send` may produce zero, one or two wire lines.**
  `SseWriter` happens to be 1:1; nothing may assume that.
  Revision 1 assumed one chunk → one line, which silently discards the tool-calling stream. The rule
  is: **a chunk's `delta` and its `finish_reason` are independent, and a chunk may carry both.**

  | Incoming OpenAI chunk | Emitted NDJSON lines |
  |---|---|
  | `delta.content` only | 1 — `{model, created_at, message:{role:"assistant", content:"…"}, done:false}` |
  | `delta.reasoning_content` only | 1 — `message:{role:"assistant", content:"", thinking:"…"}`, `done:false` |
  | `finish_reason` only, empty `delta` (the plain-chat terminal chunk) | 1 — the terminal `done:true` line with `done_reason`, durations and counts |
  | **`delta` non-empty AND `finish_reason` set** (the tool path) | **2** — first the content/`tool_calls` line with `done:false`, then the terminal `done:true` line |

  The last row is the whole fix for the tool path. `handleToolCompletion`'s stream branch emits
  **exactly one** chunk whose `delta` **is the complete assistant message** — `tool_calls` and all —
  *and* which carries `finish_reason` (`RelaisHttpServer.kt:1659-1665`, `.put("delta", message)` and
  `.put("finish_reason", finishReason)` on the same object). Mapping "the chunk carrying
  `finish_reason`" straight to a bare terminal line throws the tool calls away, and since Ollama
  streams by default this would break **every** tool-using Ollama client on the default path — the
  exact failure test 9 exists to prevent, except test 9 only exercises the non-streaming
  `chatResponse`. Splitting into two lines is also strictly more Ollama-shaped than putting content on
  the terminal line: Ollama's own final line carries an empty `message.content`.

  The `arguments` string→object conversion applies on **both** paths — the tool line built here goes
  through the same converter as `chatResponse`.

  **Token counts on the tool stream are absent, and that is declared, not hidden.** That branch builds
  no `usage` object anywhere — no `finalChunk`, no `include_usage` handling (contrast the plain-chat
  path at `:1348-1349`). So on a streaming tool request `eval_count` / `prompt_eval_count` have **no
  source**. Do not emit zeros — a zero is a number a dashboard renders and a client divides by. **Omit
  both keys** and put the reason on the wire as
  `x_relais_usage_note: "token counts unavailable on the streaming tool-calling path"`. Durations are
  still real and still emitted. (Forcing `stream:false` would recover the counts; § Task 2 GOTCHA 4
  records why that trade was refused.)

  `/api/generate` omits `context[]` entirely.
- **MIRROR:** HANDLER_PATTERN (logic in a pure builder a JVM test can reach).
- **IMPORTS:** `org.json.JSONObject`, `org.json.JSONArray`, `java.time.Instant`/`OffsetDateTime` for
  RFC-3339 (JVM-only, no Android desugaring concern at `minSdk 31`).
- **GOTCHA:** The `arguments` string→object conversion. Passing the string through is a one-line
  omission that type-checks and breaks every tool-using client.
- **VALIDATE:** Tests 8, 9, 10, **27**, **30** RED → GREEN. **Mutation-check tests 9 and 27.**
  Test 30 is not optional garnish: test 27 proves `chatChunk` returns two objects, which is a
  different claim from "two lines reach the socket".

### Task 4 — `NdjsonWriter.kt` + `NdjsonWriterTest.kt`

- **ACTION:** Create the NDJSON writer as a one-for-one structural mirror of `SseWriter.kt`.
- **IMPLEMENT:**
  ```kotlin
  class NdjsonWriter(private val out: OutputStream) : StreamSink {
    /** 200 + `Content-Type: application/x-ndjson` + `Cache-Control: no-cache` + `Connection: close`. */
    override fun commitHeader()
    /** One `<json>\n` line. No `data:` prefix, no blank-line separator. */
    override fun send(json: JSONObject)
    /** No-op — Ollama has no `[DONE]` sentinel; `done:true` on the last line is the terminator. */
    override fun done()
    /** Best-effort post-header failure: one `{"error": message}` line. Swallows write failures. */
    override fun abort(message: String)   // NO default value here — see GOTCHA
  }
  ```
  **`abort` takes no default value here.** Kotlin: *"An overriding function is not allowed to specify
  default values for its parameters."* The default `"stream aborted"` is declared **only** on the
  `StreamSink` interface (§ Task 5); overrides inherit it, so every existing bare `sse.abort()` call —
  `RelaisHttpServer.kt:1365`, `:1670` — keeps compiling untouched. Do **not** "fix" this by adding a
  no-arg overload in an implementation; that creates an ambiguous-call error instead of a duplicate-
  default one.
  **Scope boundary:** `NdjsonWriter` writes; it does **not** translate. It never sees an Ollama concept
  and never emits more than one line per `send`. The fan-out that the streaming tool-call fix needs
  lives in `OllamaStreamSink` (§ Task 3), which wraps this class. Keeping the two apart is what lets
  tests 17-20 stay a pure framing check.
- **MIRROR:** `SseWriter.kt:30-66` — same constructor shape, same `runCatching` swallow in `abort`,
  same KDoc density.
- **IMPORTS:** `java.io.OutputStream`, `org.json.JSONObject`.
- **GOTCHA:** Ollama itself sends `Content-Type: application/json`; we send `application/x-ndjson`,
  which is strictly more correct and which every client under consideration accepts. **Document the
  deviation** in `docs/ollama-api.md` and re-check it in Task 10 if a client rejects it. Also: the
  `abort` message is interpolated into JSON — build it with `JSONObject().put("error", message)`
  rather than string concatenation (`SseWriter.abort` concatenates a literal; a caller-supplied message
  must not be able to break the framing).
- **VALIDATE:** Tests 17-20 RED → GREEN.

### Task 5 — The `RelaisWire` / `StreamSink` seam in `RelaisHttpServer.kt`

- **ACTION:** Extract the emit points of the three chat handlers behind an interface whose OpenAI
  implementation is the identity. **No behavior change** — the whole task is proving that.
- **IMPLEMENT:**
  ```kotlin
  // RelaisOllamaWire.kt
  /**
   * One instance PER REQUEST — never a shared `object`. Holds the request's start clock, its
   * first-token clock, the requested Ollama model name, and the `options{}` keys that were dropped,
   * all of which must reach the TERMINAL NDJSON line that `handleOpenAi` builds deep inside
   * `wire.stream(...)` with no other channel back to the translator.
   *
   * Mutable stream-progress state (`firstTokenNanos`) is a deliberate, scoped exception to the
   * repo's immutability rule, and the same one `SseWriter` already takes: "Not thread-safe; one
   * instance per request" (`SseWriter.kt:21`). It is confined to a single request's
   * single thread and never escapes. Everything else on the wire is `val`.
   */
  interface RelaisWire {
    /** Metric label for this dialect's chat route — replaces the hardcoded "/v1/chat/completions". */
    val endpoint: String
    /** Translates a fully built OpenAI response object before it is written. Identity for OpenAI. */
    fun body(json: JSONObject): JSONObject
    /** Error builders for rejectIfModelUnavailable's two lambdas. */
    fun errorBody(message: String): JSONObject
    fun notFoundBody(message: String): JSONObject
    /** Opens the streaming writer for this dialect. */
    fun stream(out: java.io.OutputStream): StreamSink
  }

  /** The named shape SseWriter already had. NOT "unchanged" — see the GOTCHA. */
  interface StreamSink {
    fun commitHeader()
    fun send(json: JSONObject)   // receives an OpenAI-shaped chunk; the impl may translate
    fun done()
    /** The default lives HERE and only here; overrides inherit it and must not restate it. */
    fun abort(message: String = "stream aborted")
  }
  ```

  **`RelaisWire` implementations are per-request classes, not singletons** (revision 2). `OpenAiWire`
  may stay an `object` — it is stateless by definition, being the identity — but `OllamaChatWire` and
  `OllamaGenerateWire` are `class OllamaChatWire(private val requestedName: String, private val
  startNanos: Long, private val ignoredOptions: List<String>) : RelaisWire`, constructed by the route
  handler after the body is parsed. Revision 1 wrote them as bare values (`handleOpenAi(..., OllamaChatWire)`),
  which makes the acceptance criterion *"unsupported `options{}` keys are named back in
  `x_relais_ignored_options`"* **unreachable on the default streaming path** — there is nowhere to put
  the list.

  **Construction-order trap this creates, and its resolution.** The admission wrapper (§ Task 6) runs
  in the **router branch, before the body is read**, so the per-request wire cannot exist yet when
  `withInferenceAdmission` needs a translating `reply` for its 503/429. Do not move the body read into
  the router to fix this, and do not construct the wire twice. The 503/429 bodies need **no**
  per-request state — they are `{"error": "<message>"}` plus the preserved `Retry-After` — so the
  router branch passes a **stateless** error-translating reply built from
  `RelaisOllamaWire.error(...)`, and the stateful wire is constructed inside the handler once the body
  is parsed.

  Signature becomes `handleOpenAi(sock, body, sessionKey, wire: RelaisWire = OpenAiWire)`;
  `handleToolCompletion` and `handleStructuredCompletion` take the same parameter, threaded from their
  `handleOpenAi` call sites. **Measured edit inventory** (all `grep -n`, 2026-09-06):

  | Change | Sites | Line numbers |
  |---|---|---|
  | `respond(sock, s, x)` → `respond(sock, s, wire.body(x))` | **7** | `handleOpenAi` 1231, 1274, 1302 · `handleToolCompletion` 1646 · `handleStructuredCompletion` 1692, 1749, 1755 |
  | `SseWriter(sock.getOutputStream())` → `wire.stream(sock.getOutputStream())` | **2** | 1313 (chat), 1654 (tool) |
  | `recordRequest("/v1/chat/completions", s)` → `recordRequest(wire.endpoint, s)` | **9** | 1230, 1273, 1301, 1312, 1645, 1653, 1691, 1748, 1754 |
  | `rejectIfModelUnavailable(...)` call gains `wire.endpoint`, `wire::errorBody`, `wire::notFoundBody` | **1 call site** | 1216 |

  `OpenAiWire.body` is the identity function, `OpenAiWire.endpoint` is `"/v1/chat/completions"`, and
  `OpenAiWire.stream` returns an `SseWriter`.

  **`SseWriter` is not left unchanged** (revision 1 said it was, which would send an implementer
  looking for a zero-diff file). It gains `: StreamSink` and **four `override` modifiers**, and it
  **must drop the default value on `abort`** — `SseWriter.kt:64` currently reads
  `fun abort(message: String = "stream aborted")`, and Kotlin forbids an overriding function from
  specifying parameter defaults. The default moves up to `StreamSink`, which is where it now lives for
  both writers; the existing bare `sse.abort()` calls at `:1365` and `:1670` are unaffected because an
  override inherits the base declaration's default. `send(event, json)` and `sendError` stay on
  `SseWriter` **outside** the interface — they are the Anthropic named-event API, and putting them on
  `StreamSink` would force `NdjsonWriter` to implement a framing it has no equivalent for. That is
  also why `SseWriter(` at `:1469` must not move to `wire.stream` (GOTCHA 1).
- **MIRROR:** `rejectIfModelUnavailable`'s existing signature (`RelaisHttpServer.kt:1138-1144`) is
  already lambda-parameterized in exactly this style — the seam is an extension of a pattern the file
  already uses, not a new idea:
  ```kotlin
  // SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:1138-1144
  private fun rejectIfModelUnavailable(
    sock: java.net.Socket,
    endpoint: String,
    requestedModel: String?,
    errorBody: (message: String) -> JSONObject,
    notFoundBody: (message: String) -> JSONObject = errorBody,
  ): Boolean {
  ```
- **IMPORTS:** none new in `RelaisHttpServer.kt` (same package).
- **GOTCHA (three find-replace hazards — all confirmed by grep):**
  1. **`SseWriter(` appears at 1313, 1469, and 1654.** `1469` is the **Anthropic `/v1/messages`**
     stream and **must not change** — it uses the named-event `send(event, json)` / `sendError` API
     that `StreamSink` does not expose. A global replace breaks `/v1/messages`.
  2. **`rejectIfModelUnavailable` is called at 1216 and 1381.** Only **1216** (`handleOpenAi`) takes
     the wire lambdas; **1381** is Anthropic and keeps its own error builders.
  3. **`recordRequest("/v1/messages", …)` at 1396, 1459, 1468 must stay literal.** Only the nine
     `/v1/chat/completions` literals move to `wire.endpoint`.
  Fixing `endpointLabel` alone would leave every Ollama chat request counted as
  `/v1/chat/completions` while an acceptance criterion reading "nothing lands in `other`" shows green —
  that is why the endpoint rides the wire rather than being read from `ctx`.
- **VALIDATE:** Tests 22, 24, 25. Then:
  `grep -n 'recordRequest("/v1/chat/completions"' RelaisHttpServer.kt` returns **nothing**;
  `git diff` shows lines 1469, 1381, 1396, 1459, 1468 **untouched**; the full JVM suite passes with **no
  existing test file edited**.

### Task 6 — `RelaisOllamaRoutes.kt`: `/api/chat` + `/api/generate`, router branches, `endpointLabel`

- **ACTION:** Add the two inference routes over the Task-5 seam, **inside the shared inference
  admission gate**, plus their registration.
- **IMPLEMENT:** Each handler: read the body, `resolveOllamaName` the model (unresolvable → 404 with
  `RelaisOllamaWire.error(...)` before any engine touch), build the OpenAI body via Task 2, construct
  the per-request wire, call `handleOpenAi(sock, openAiBody, sessionKey, wire)`.

  **The router branches are NOT the one-liners of `RelaisHttpServer.kt:402-406`.** They mirror the
  `/v1/chat/completions` branch at **`:433-437`**, because that is the delegate whose admission
  discipline they inherit (§ the governing invariant at the top of this plan):
  ```kotlin
  method == "POST" && path == "/api/chat" ->
    // Same discipline as /v1/chat/completions (:433-437): thermal 503, queue 429 + shared permit,
    // endpoint latency. The reply passed here is the OLLAMA one, so the gate's own 503/429 land in
    // the Ollama envelope (seam 5) — a stateless translator; the per-request wire is built inside
    // the handler, after the body is read.
    withInferenceAdmission("/api/chat", ollamaReply(::reply)) {
      // Session memory: exact parity with /v1/chat/completions (:439). Feature-gated, same policy.
      val sessionKey = if (sessionEnabled) resolveSessionKey(sock, sessionHeader) else null
      handleOllamaChat(ctx, sessionKey)
    }

  method == "POST" && path == "/api/generate" ->
    withInferenceAdmission("/api/generate", ollamaReply(::reply)) {
      handleOllamaGenerate(ctx, sessionKey = null)   // deliberately stateless — see GOTCHA 3
    }
  ```
  where `ollamaReply` is a one-line stateless adapter — `{ status, json, headers -> reply(status,
  RelaisOllamaWire.error(json.optJSONObject("error")?.optString("message") ?: "error"), headers) }` —
  that preserves the status and the `Retry-After` header list verbatim and only reshapes the body.
  Note it must be given to **both** `withInferenceAdmission` and `ctx.reply`, or the gate's errors
  and the handler's errors disagree about dialect.

  **Two body fields are dropped by that reshape, deliberately — declaring it because everything else
  in this plan is declared.** `shedIfHot` puts `retry_after_seconds` at the **top level beside**
  `error` (`:544-548`), and `rejectIfQueueFull` adds `"code": "queue_full"` (`:571-575`) — the field
  that distinguishes an admission 429 from the per-IP rate-limit 429. `ollamaReply` rebuilds the body
  from `error.message` alone, so both are lost. The **`Retry-After` header survives verbatim**, which
  is what every Ollama client actually reads, and Ollama's flat envelope has nowhere to put `code`.
  Accepted; documented in `docs/ollama-api.md`; asserted in test 28 so it stays a decision rather than
  drift.

  The six non-inference routes (`/api/tags`, `/api/version`, `/api/show`, `/api/ps`, `/api/embed`,
  `/api/embeddings`) are plain one-liners in the `:402-406` style: the first four touch no engine, and
  `/api/embed` inherits `/v1/embeddings`' discipline, which is `shedIfHot` **only** — no queue permit
  (see § Task 7 and the governing invariant). Do not "improve" on that here: adding a permit to
  `/api/embed` alone would make the Ollama and OpenAI embedding routes behave differently on the same
  node, which is the bug this feature exists to avoid.

  `endpointLabel` (`RelaisHttpServer.kt:1877-1899`) gains one `path.startsWith("/api/…") -> "/api/…"`
  entry per route, in the same style, before the `else -> "other"`.
- **MIRROR:** ADMISSION_PATTERN for the two inference routes (`:433-437` + `:518-535`);
  HANDLER_PATTERN (`:402-406`) for the other six.
- **IMPORTS:** `org.json.JSONObject`; the existing package-internal helpers.
- **GOTCHA:** `startsWith("/api/embed")` **also matches `/api/embeddings`** — placed first it swallows
  the legacy route and silently serves it the 2-D `/api/embed` body, which is exactly the collapse
  test 14 exists to catch. Use exact matches (`path == "/api/embed"` / `path == "/api/embeddings"`) for
  that pair rather than relying on branch order. (No existing branch in the file demonstrates this
  hazard — the `/v1` prefixes at `:402-406` are mutually disjoint — so there is no precedent to copy.)
  Likewise `/api/ps` vs `/api/push`: `/api/push` is not implemented, and an exact match keeps it a clean
  404 rather than a mis-dispatch.

  **GOTCHA 2 — the gate is invisible in a diff review, and NO JVM test can see it either.** Nothing
  about `handleOllamaChat` reveals whether its caller wrapped it. `withInferenceAdmission`,
  `shedIfHot`, `rejectIfQueueFull` and `admissionGate` are all `private` members of a
  `Context`-constructed class, so a device-free test cannot drive the router at all. **Test 28
  therefore guards the `ollamaReply` *adapter*, not the *wiring*** — swapping `ollamaReply(::reply)`
  back to `::reply` in the router leaves test 28 green. Say that out loud rather than letting the test
  count imply coverage it does not have (`[[relais-isolation-testing-blindspot]]`: this repo has twice
  shipped something completely broken with every test layer green). The **primary** guard for the
  wiring is the grep criterion — `grep -n 'withInferenceAdmission("/api/' RelaisHttpServer.kt` must
  return **two** hits — backed by manual curl #10 and the Task-9 probe. Revision 1 shipped a plan in
  which the string `withInferenceAdmission` appeared **zero times**, while carrying a GOTCHA, a test
  and an acceptance criterion for the *same* defect class on the much cheaper `/api/embed` path.

  **GOTCHA 3 — `resolveSessionKey` is not "no header ⇒ no session".** It falls back to a **hashed peer
  IP** (`:1549-1552`: `RelaisSessionStore.hashIp(sock.inetAddress?.hostAddress, apiKey)` passed to
  `RelaisSessionPolicy.resolveSessionKey`). Threading it into `/api/generate` — which Ollama defines
  as a stateless single-prompt endpoint, and whose statelessness this plan leans on in § NOT Building
  when refusing to fake `context[]` — would make it **accidentally stateful**, and every client behind
  one NAT egress would share one hashed-IP session invisibly. `/api/generate` passes
  `sessionKey = null`. `/api/chat` keeps full parity with `/v1/chat/completions`, session memory
  included, because it is the same multi-turn contract. Both choices go in `docs/ollama-api.md`.
- **VALIDATE:** Test 28 RED → GREEN; JVM suite green; `curl` lines in § Validation Commands.
  `grep -n 'withInferenceAdmission("/api/' RelaisHttpServer.kt` returns **two** hits;
  `grep -n 'resolveSessionKey' RelaisHttpServer.kt` shows **no** call inside the `/api/generate`
  branch.

### Task 7 — Split `handleEmbeddings`, then `/api/embed` + `/api/embeddings`

- **ACTION:** Mechanically split `handleEmbeddings` into a body-reading shell and a body-taking core,
  then add the two embedding routes through a derived `RequestContext`.
- **IMPLEMENT:**
  ```kotlin
  // RelaisHttpServer.kt — mechanical split, no logic change
  private fun handleEmbeddings(ctx: RequestContext) =
    handleEmbeddings(ctx, JSONObject(readBody(ctx.reader, ctx.contentLength)))

  private fun handleEmbeddings(ctx: RequestContext, body: JSONObject) {
    // Embedding inference runs the NPU/CPU too, so honor thermal backpressure first (503 + Retry-After).
    if (shedIfHot(ctx.reply)) return          // <- MUST be the CORE's first statement
    val inputs = parseEmbeddingInputs(body)
    /* … remainder unchanged from :979-1019 … */
  }

  // RelaisOllamaRoutes.kt
  val ollamaCtx = ctx.withReply { status, json, headers ->
    ctx.reply(status, RelaisOllamaWire.embedResponse(status, json, model, startNanos), headers)
  }
  handleEmbeddings(ollamaCtx, RelaisOllamaWire.embedRequest(rawBody))
  ```
  `RequestContext` gains one `withReply` helper and is widened `private` → `internal`. Response
  translation: `/api/embed` → `{model, embeddings: [[…]], total_duration, load_duration: 0,
  prompt_eval_count}`; legacy `/api/embeddings` → the flat `{"embedding": [...]}`. `truncate`,
  `dimensions`, `keep_alive`, `options` accepted and ignored.
- **MIRROR:** `RequestContext`'s existing `send` convenience (`RelaisHttpServer.kt:701-702`) — `withReply`
  is the same kind of one-line derivation.
- **IMPORTS:** none new.
- **GOTCHA (two, the second is a live bug in the naive split):**
  1. **The body is consumable exactly once.** `HttpRequestReader` shares one `BufferedInputStream`
     between `readLine` and `readBodyBytes` (`RelaisHttpIo.kt:38-39`). The Ollama route must read the
     body itself (to resolve the model, and mandatorily for legacy `/api/embeddings` where `prompt`
     must become `input`), so a delegated `handleEmbeddings(ctx)` would receive `""` and
     `JSONObject("")` would throw. Hence the split.
  2. **`shedIfHot` is currently the first statement at `:977`, *before* the body read at `:978`.**
     If the split leaves `shedIfHot` in the shell, `/api/embed` delegates straight to the core and
     **skips thermal shedding entirely** — a silent removal of backpressure on a route that runs the
     NPU. `shedIfHot(ctx.reply)` must move into the **core**, as its first statement; the shell does
     nothing but read the body.
  3. **`/api/embed` gets `shedIfHot` and NOT the queue permit, deliberately.** `/v1/embeddings`
     (router branch `:404`) is **not** wrapped in `withInferenceAdmission` — it takes `shedIfHot`
     inside the handler and no shared permit — so by the governing invariant neither does
     `/api/embed`. A reviewer comparing it against `/api/chat` will read the difference as an
     oversight; it is not. If that discipline is judged wrong, the fix is to change `/v1/embeddings`
     in a separate change and let `/api/embed` inherit it, **never** to diverge the two here.

  Leave `handleRerank` (`:1021-1026`, identical `readBody` shape) alone — no Ollama analog, no
  speculative split.
- **VALIDATE:** Tests 14, 21, 26 RED → GREEN; existing `/v1/embeddings` tests unchanged and green.

### Task 8 — Metadata routes: `/api/tags`, `/api/version`, `/api/show`, `/api/ps`

- **ACTION:** Add the four non-inference routes.
- **IMPLEMENT:**
  - **`GET /api/tags`** — built from `RelaisModelCatalog.curatedModels()` +
    `provisionedIds(provisionedOnDisk())`, the same two inputs `handleModels` assembles
    (`RelaisHttpServer.kt:907-909`). **Only provisioned models are listed** — Ollama's `/api/tags`
    means "available locally", and since #180 a request naming an un-provisioned model 404s, so listing
    the full catalog hands every client a menu of ids that fail. (`/v1/models` keeps its full listing
    with the `provisioned` flag; Ollama has no such flag, hence the divergence.) Per entry:
    `name` = `model` = `ollamaName(ref.modelId)`, `size` = `ref.sizeInBytes`,
    `digest` = `ref.commitHash` (an **HF commit hash**, not a blob digest — emitted raw, `sha256:`-prefixed
    only when it is 64 hex chars; deviation noted in the doc), `modified_at` = the provisioned file's
    mtime when available else node start time, `details.family` = base name,
    `details.parameter_size` = derived tag, `details.format` = `"litert-lm"`,
    `details.quantization_level` = `"unknown"` unless the id carries one.
  - **`GET /api/version`** — `{"version": OLLAMA_COMPAT_VERSION, "x_relais": true,
    "x_relais_version": BuildConfig.VERSION_NAME}`. `OLLAMA_COMPAT_VERSION` is a pinned constant
    documented as *the Ollama API version this shim emulates, not a Relais version*; some clients gate
    features on it, which is why a plausible value is required, and the `x_relais*` fields (which
    Ollama clients ignore) keep the response from being a bare lie.
  - **`POST /api/show`** — `{model}` via `resolveOllamaName`, unknown → 404. Returns `details{}` (same
    builder as `/api/tags`), `model_info{}` with honest node facts (`relais.backend`,
    `relais.provisioned`, `relais.runtime_compat` from `RelaisRuntimeCompat`), `template: ""`,
    `parameters: ""`, `modelfile: ""` (we have no Modelfile and must not synthesize one), and
    `capabilities[]` — **reworked in revision 2, see below**.

    **`capabilities[]` is per-**requested**-model, and mostly cannot be known.** Revision 1 derived
    `"vision"` from `RelaisEngine.isMultimodal` (`RelaisEngine.kt:278`, set at `:524`, cleared at
    `:535`), which describes the **currently resident engine** — so `/api/show` on any non-resident
    catalog model would report the resident model's vision support, and the answer would flip after an
    idle unload. `RelaisModelRef` (`data/RelaisModelRef.kt:42-48`: `modelId`, `modelFile`,
    `commitHash`, `sizeInBytes`, `displayName`, `source`) carries **no** multimodal flag, and there is
    no catalog metadata for it, so for a non-resident model the honest answer is "unknown". Emit:
    - `"completion"` — always.
    - `"tools"` — always (native LiteRT-LM tool calling, model-independent on this node). Not in the
      Ollama doc's vocabulary; confirm against a live server in Task 10 and drop if it does not emit it.
    - `"vision"` — **only when the requested model is the resident one AND `isMultimodal`.** For a
      non-resident model, omit it and set
      `x_relais_capabilities_note: "vision unknown for a non-resident model; load it to find out"`.
      Omission is the safe direction: Open WebUI hides an affordance rather than offering one that 400s.
    - `"thinking"` — **do not emit unconditionally.** `RelaisRequest.enableThinking`'s KDoc
      (`RelaisEngine.kt:109-140`) says thinking is *"Honored on the streaming text path only; the tool
      and structured-output paths ignore it (v1)"*, and this feature's own `format`→`stream:false`
      substitution routes into `handleStructuredCompletion`, where it is ignored — so a client sending
      `think:true` **together with** `format` gets no thinking and no error. Emit `"thinking"`, and
      document that exact combination as a deviation in `docs/ollama-api.md`; the alternative
      (dropping the capability) would hide a feature that does work on the ordinary streaming path,
      which is the more common request by a wide margin.
  - **`GET /api/ps`** — zero or one entry from `RelaisEngine.residentModelId` (`:301`) + `isReady`
    (`:324`): `{name, model, size, digest, details{}, expires_at, size_vram: 0}`.
    `size_vram: 0` — the phone has no discrete VRAM and inventing a number is a lie a dashboard renders.

    **`expires_at` needs a new `RelaisEngine` accessor — revision 1 had no reachable data source.**
    `lastActivityAtMs` is `@Volatile private var` (`RelaisEngine.kt:230`) and `RelaisIdleTtl.kt`
    exposes only constants plus `shouldUnloadIdleEngine`, so nothing outside `RelaisEngine` can
    compute the deadline today. Add one read-only accessor returning the absolute expiry
    (`lastActivityAtMs + ttlMs`) or `null` when the TTL is disabled; `expires_at` is **omitted** —
    not `null`, not epoch 0 — in that case. `RelaisEngine.kt` is in § Files to Change for this.
    **Check `feature-22-idle-unload.plan.md:438` first**: it adds
    `val idleSeconds: Double get() = (System.currentTimeMillis() - lastActivityAtMs) / 1000.0` for its
    idle gauge. If that has landed, derive `expires_at` from it plus the TTL and add **nothing** to
    `RelaisEngine.kt`. Two accessors over one private field is the outcome to avoid.
- **MIRROR:** `handleModels` (`RelaisHttpServer.kt:906-911`) verbatim in shape.
- **IMPORTS:** `cc.grepon.relais.data.RelaisModelRef` and the existing catalog helpers.
- **GOTCHA:** Open WebUI reads `capabilities[]` to decide whether to render the image and tool
  affordances — a wrong value here is what makes the integration feel broken rather than native. Derive
  each from real node state; never hardcode the full set. **And "real node state" means state about
  the *requested* model**, which for `vision` exists only when that model is the resident one.
- **VALIDATE:** Tests 12, 13, **29** RED → GREEN.

### Task 9 — `OllamaCompatProbe.kt` (on-device)

- **ACTION:** Create an `androidTest` probe that drives the **real socket** with real client-shaped
  requests. This is the layer that `[[relais-isolation-testing-blindspot]]` says the JVM suite cannot
  cover: every test above verifies a *part*, and nothing proves the assembled route answers.
- **IMPLEMENT:** With the node running and the key read from config, over loopback:
  `GET /api/tags` (200, non-empty `models[]`, every `name` resolvable) · `GET /api/version` (200) ·
  `POST /api/show` for the resident model (200, `capabilities` contains `completion`) ·
  `POST /api/chat` **with `stream` omitted** (asserts NDJSON: ≥2 `\n`-separated JSON lines, no `data:`
  prefix, last line `done:true` with non-zero `eval_count`) · `POST /api/chat` with `stream:false`
  (single JSON object) · `POST /api/chat` with `format:"json"` and no `stream` (200, **not** 400 — the
  Task-2 collision fix, on the real handler; **additionally assert the observed framing**: a single
  JSON object with `done:true` and `Content-Type: application/json`, which is the deviation Task 2
  GOTCHA 2 declares — this probe is the only place it is measured against reality) ·
  `POST /api/chat` **with `tools[]` and `stream` omitted** (asserts the terminal line is preceded by a
  line carrying `message.tool_calls`, with `arguments` an **object**, and that the terminal line omits
  rather than zeroes `eval_count` — the Task-3 fix on the real handler) ·
  `GET /api/tags` **with no `Authorization` header**
  (asserts **401**) · `GET /api/ps` (resident model present). Header must carry a runnable
  `adb shell am instrument -e class cc.grepon.relais.OllamaCompatProbe …` line and a
  `adb logcat -s RelaisOllamaProbe` watch line.
- **MIRROR:** PROBE_STRUCTURE — `ToolCallingProbe.kt:17-55` (`@RunWith(AndroidJUnit4::class)`,
  `assumeTrue` guards, `Log` to a `Relais*` tag, the `am instrument` header block).
- **IMPORTS:** `androidx.test.ext.junit.runners.AndroidJUnit4`,
  `androidx.test.platform.app.InstrumentationRegistry`, `android.util.Log`, `org.junit.Assume.assumeTrue`.
- **GOTCHA:** Probes are **not** in CI and require hardware. `assumeTrue` out cleanly when the node is
  not running rather than failing. Compile it in CI via
  `./gradlew :app:compileFullOpenDebugAndroidTestKotlin` so it cannot rot.
- **VALIDATE:** Compiles; run manually on the live node (Pixel 9 "comet") per § Validation Commands.

### Task 10 — Confirm the undocumented wire details against a real `ollama serve`

- **ACTION:** Run a real Ollama server (any machine, any tiny model) and diff its actual bytes against
  the § External Documentation table. **The list changed in revision 2** — two rows that were "assumed"
  are documented facts, and one row asserted as fact is undocumented and now needs the strongest
  evidence.
- **IMPLEMENT:** In priority order:
  1. **`stream` default (row 5 — the one revision 1 got backwards).**
     `curl -s -N localhost:11434/api/chat -d '{"model":"<real>","messages":[{"role":"user","content":"hi"}]}'`
     with **no `stream` key**. Multiple NDJSON lines ⇒ the default is `true` and test 1, its mutation
     check and the top risk row are all justified. A single object ⇒ **the feature's central premise is
     wrong** and Task 2 must be reworked before anything else lands.
  2. **Error envelope (row 4 — genuinely undocumented).**
     `curl -s -i localhost:11434/api/chat -d '{"model":"nope"}'` → capture the exact body **and**
     status. This is the only row with no doc backing at all.
  3. **`capabilities[]` vocabulary (row 2 — partly documented).**
     `curl -s localhost:11434/api/show -d '{"model":"<real>"}'` → does a real server emit `"tools"` /
     `"thinking"`? The doc's example shows only `["completion","vision"]`. Drop any value it does not
     emit rather than inventing vocabulary.
  4. **Sanity checks only (rows 1 and 3 — documented).** Confirm a ~1 s generation reads ~1e9 (the doc
     already states *"All durations are returned in nanoseconds"*), and note which `created_at` form
     the server emits — both the ns/offset and µs/`Z` forms appear in the doc, so this is
     informational, not blocking.

  Fold every correction into the translator **and** into `docs/ollama-api.md`.
- **MIRROR:** N/A — verification, not code.
- **IMPORTS:** N/A.
- **GOTCHA:** Do not skip this because the shim "works with client X". A client can tolerate a wrong
  error envelope right up until it needs to surface the error message. And do not repeat revision 1's
  mistake in the other direction: **read the doc line before labelling something unverified**, because
  a false "unverified" tag spends verification budget on a settled fact and lends false confidence to
  the genuinely unsettled one next to it.
- **VALIDATE:** Every row in the § External Documentation table ends the task either **confirmed
  against a live server** or **corrected in code**; rows 1 and 3 may end as "documented, magnitude
  spot-checked".

### Task 11 — Documentation

- **ACTION:** Write `docs/ollama-api.md`; extend `docs/openapi.yaml` and `README.md`.
- **IMPLEMENT:** `docs/ollama-api.md` carries the route reference, the **per-client auth table**
  (§ Notes), and **every** deviation in one list: ignored `options{}` keys, ignored `keep_alive`,
  ignored `raw`/`template`, no `context[]`, `load_duration: 0`, `size_vram: 0`, `digest` is an HF commit
  hash, `application/x-ndjson` instead of `application/json`, the `format`→`stream:false` substitution
  **and the single-JSON-object framing it produces**, the 401-reads-as-down behavior, and every
  deviation added in revision 2: **token counts omitted on the streaming tool path**;
  **`"vision"` absent for a non-resident model** (with `x_relais_capabilities_note`); **`think:true`
  combined with `format` produces no thinking and no error**; **`retry_after_seconds` and
  `"code":"queue_full"` dropped from gate error bodies** while the `Retry-After` header survives;
  **`/api/chat` inherits session memory, `/api/generate` is explicitly stateless**; and the
  § External Documentation table with each row's verification status after Task 10.
  `docs/openapi.yaml` gains eight paths matching the existing entries' style (`:174`, `:272`, `:692`).
  `README.md:126-135` gains the Ollama-native clients and the no-`/v1` base URL; `README.md:138-145`
  gains an `/api/*` Endpoints row.
- **MIRROR:** existing `docs/*-api.md` structure; `docs/openapi.yaml:174-224`.
- **IMPORTS:** N/A.
- **GOTCHA:** The doc must state plainly that an anonymous probe gets 401 and that some clients render
  that as "server down" — otherwise it becomes the first support question.
- **VALIDATE:** `docs/openapi.yaml` parses; the README renders; every deviation in the code has a line
  in the doc (review checklist item).

### Task 12 — CI + independent review

- **ACTION:** Full JVM suite green, then two independent review passes on the **final** diff.
- **IMPLEMENT:** `code-reviewer` for quality; `security-reviewer` because this adds eight network
  routes. Reviewers get the explicit grep list from § Acceptance Criteria.
- **MIRROR:** the repo norm (`[[review-before-suggesting-merge]]`, `[[relais-dual-review-disjoint]]`):
  green CI ≠ reviewed, and fix commits are the **highest**-risk diff, not the safest — re-review after
  every fix round.
- **IMPORTS:** N/A.
- **GOTCHA:** `[[relais-dual-review-disjoint]]` measured 0/5 finding overlap between Claude review and
  `/codex review` across three rounds. Run both; the second is not redundant. And per
  `[[relais-review-coverage-skew]]`, **scope one review pass to the fix commits alone** — in this repo
  that exercise immediately found a P1 that was itself the fix for an earlier P1.
- **GOTCHA 2 — this plan is itself a revision.** Revision 1 passed a careful self-review (≈20 accurate
  citations, three real seam bugs found) and still routed the most expensive route around the
  admission gate. The lesson for Task 12 is not "review harder" but "review the *shape*, not the
  citations": the citations were right and the architecture was wrong. Reviewers should start from
  § the governing invariant and the five-seam checklist, then check the diff against them.
- **VALIDATE:** Two APPROVEs on the final SHA, no CRITICAL/HIGH outstanding, **and** a re-run of the
  plan critic against the revised plan before implementation starts.

## Testing Strategy

### Unit Tests

`RelaisOllamaWireTest.kt` (device-free, no Robolectric):

| # | Test | Input | Expected Output | Edge Case? |
|---|---|---|---|---|
| 1 | `stream` default inversion | `/api/chat` body with **no** `stream` key | OpenAI body has `stream == true`; explicit `false` → `false` | **Yes — mutation-check** |
| 2 | Name mapping + round-trip | `litert-community/gemma-4-E4B-it-litert-lm`; a catalog list; a raw Relais id; an unknown name; a colliding pair | `name:tag` with no slash / no `-litert-lm`; `resolveOllamaName(ollamaName(id), ids) == id`; raw id resolves; unknown → `null`; collision → two **distinct** names | Yes |
| 3 | Options mapping | `options{temperature, top_p, seed, num_predict, top_k, stop, repeat_penalty}` | first three become top-level OpenAI fields; the rest absent, their names in `x_relais_ignored_options`; empty `options` → **no** sampler keys at all | Yes (engine defaults) |
| 4 | No `stream_options` | any `/api/chat` body | translated body has no `stream_options` key | Yes (invariant) |
| 5 | `format` mapping | `"json"`; a schema object | `response_format.type == "json_object"`; `json_schema` with the schema nested intact | |
| 6 | `think` mapping | `true`, `"low"`, `"max"`, `false`, absent | non-null `reasoning_effort` for the first three; none for the last two | |
| 7 | Images | message with `images:["<b64>"]` | content-array message, `image_url.url` starts `data:image/jpeg;base64,`, base64 verbatim | |
| 8 | Chat response conversion | OpenAI `chat.completion` with content, `reasoning_content`, `usage` | `message.content`, `message.thinking`, `done:true`, `done_reason` from `finish_reason`, `eval_count`/`prompt_eval_count` from usage | |
| 9 | Tool-call arguments re-parsed | `arguments` = the **string** `"{\"city\":\"Paris\"}"` | `message.tool_calls[0].function.arguments` is a `JSONObject` with `city == "Paris"` — **not** a string | **Yes — mutation-check** |
| 10 | Streaming chunk conversion | content chunk; `reasoning_content` chunk; `finish_reason` chunk | `done:false` with delta in `message.content`; `message.thinking` with empty `content`; `done:true` with non-negative durations | |
| 11 | `/api/generate` shape | a generate response | flat `response` string, **no** `message` object, **no** `context` key | Yes |
| 12 | `/api/tags` | catalog list + provisioned-id set | only provisioned entries; each has `name`, `model`, `size`, `digest`, `modified_at`, populated `details{}` | Yes |
| 13 | `/api/show` capabilities | `isMultimodal` true / false | `"vision"` present / absent; `"completion"` and `"tools"` always present | |
| 14 | Embeddings arity | OpenAI `{data:[{embedding},{embedding}]}` | `/api/embed` `embeddings` is **2-D**, same arity and order; legacy `embedding` is **flat 1-D** of the first vector | **Yes — mutation-check** |
| 15 | Error envelope + status | a 503 + `Retry-After` embeddings body | `{"error": …}`, status still 503, header list unchanged | Yes |
| 16 | Durations | a start/first-token/end triple | ns; `total >= prompt_eval`; `eval == total - prompt_eval`; `load_duration == 0`; all `>= 0` | |

`NdjsonWriterTest.kt` (against a `ByteArrayOutputStream`):

| # | Test | Input | Expected Output | Edge Case? |
|---|---|---|---|---|
| 17 | Header | `commitHeader()` | 200 with `Content-Type: application/x-ndjson`; no `text/event-stream` | |
| 18 | Framing | two `send()` calls | exactly two `\n`-terminated lines, each independently parseable; **no** `data: ` prefix, **no** blank line between | **Yes — the SSE framing that must not leak in** |
| 19 | `done()` | `done()` | writes **nothing** (no `[DONE]`) | Yes |
| 20 | `abort` | `abort("boom")`; then a failing stream | one JSON line containing the message, valid JSON even for a message with quotes; write failure swallowed | Yes |

`RelaisOllamaRoutesTest.kt`:

| # | Test | Input | Expected Output | Edge Case? |
|---|---|---|---|---|
| 21 | Derived-reply seam | fake `reply` capturing `(status, body, headers)`, driven with the real handler's OpenAI bodies incl. the 503 + `Retry-After` | translated bodies; status and headers passed through untouched | Yes |
| 22 | Identity wire | `OpenAiWire` | `body(json) === json` (same instance); `endpoint == "/v1/chat/completions"`; `stream(out) is SseWriter` | **Yes — the `/v1` no-regression invariant** |
| 23 | `format` × default `stream` | `format:"json"` with no `stream`; with `stream:false`; with `format` absent | `response_format` set **and `stream == false`** in the first two, with `x_relais_stream_substituted` set on the first only (the client asked for `stream:false` itself in the second — nothing was substituted); `stream == true` untouched and no substitution flag in the third; `x_relais_ignored_options` **not** used for this | **Yes — mutation-check** |
| 24 | `wire.endpoint` reaches the metric, **and the wire is per-request** | two **separately constructed** `OllamaChatWire(...)` instances with different `startNanos` and different `ignoredOptions`, plus one `OllamaGenerateWire(...)` | `endpoint == "/api/chat"` / `"/api/generate"`; the two chat instances produce **different** terminal lines (distinct durations, distinct `x_relais_ignored_options`) — proving state is per-instance, not shared | **Yes — the H1 fix.** Written as an `object` this cannot pass |
| 25 | `rejectIfModelUnavailable` error shape | a constructed `OllamaChatWire(...)`: `.errorBody("x")`, `.notFoundBody("x")` | both `{"error":"x"}` — no `error.type`/`code` nesting | Yes |
| 26 | Thermal shed survives the split | a hot `ThermalGovernor` state driving the embeddings core | `/api/embed` still replies **503 + `Retry-After`**, translated to the Ollama envelope | **Yes — the Task-7 GOTCHA #2 guard** |
| 27 | **Streaming tool call is not dropped** | one OpenAI chunk carrying **both** a `delta` with `tool_calls` **and** `finish_reason` (the exact shape `RelaisHttpServer.kt:1659-1665` emits) | `chatChunk` returns **two** lines: `[0]` has `message.tool_calls[0].function.arguments` as a `JSONObject` and `done:false`; `[1]` has `done:true` + `done_reason`; **neither** carries `eval_count`/`prompt_eval_count`, and `[1]` carries `x_relais_usage_note` | **Yes — mutation-check.** Revert the two-line split to one terminal line and this must fail |
| 28 | **Gate error bodies reshape correctly** (the *adapter*, not the wiring — see Task 6 GOTCHA 2) | the stateless `ollamaReply` adapter fed the real `shedIfHot` 503 body and the real `rejectIfQueueFull` 429 body (both `RelaisError.json(...)` + `retry_after_seconds`, the 429 also `"code":"queue_full"`) | `{"error": "<the original message>"}`; status **503**/**429** unchanged; `Retry-After: N` header list **verbatim**; `retry_after_seconds` and `code` **absent from the body** — asserted, because that drop is a decision (Task 6) and must not become drift | Yes — but it **cannot** fail if the router reverts to `::reply`; the grep criterion is what guards that |
| 29 | `/api/show` vision is per-requested-model | requested == resident with `isMultimodal` true; requested == resident with it false; requested **≠** resident | `"vision"` present; absent; absent **plus** `x_relais_capabilities_note` | **Yes — the M1 fix.** Guards against re-deriving it from the resident engine |
| 30 | **The fan-out actually reaches the socket** | an `OllamaStreamSink` over an `NdjsonWriter` on a `ByteArrayOutputStream`, sent the one tool chunk from test 27, then sent a plain content chunk | **two** `\n`-terminated lines for the tool chunk, **one** for the content chunk; every line independently parseable; no `data:` prefix | **Yes.** Test 27 proves `chatChunk` returns two objects — a different claim from "two lines were written". A sink that drops `[1]` passes 27 and fails 30 |

### Edge Cases checklist

- [ ] `stream` omitted (Ollama's default `true`) — the highest-probability silent break.
- [ ] `format` present with `stream` omitted — must **not** 400.
- [ ] `options{}` present with only unsupported keys — must **not** 400; names reported back.
- [ ] `options{}` absent entirely — no sampler keys emitted, engine defaults apply.
- [ ] Tool call whose `arguments` is a JSON string — must arrive as an object.
- [ ] Legacy `/api/embeddings` — flat 1-D, not 2-D.
- [ ] Multi-input `/api/embed` — arity and **order** preserved.
- [ ] Model named by raw Relais HF path instead of `name:tag`.
- [ ] Model name that resolves to nothing — 404 in the Ollama envelope, never reaching the engine.
- [ ] Un-provisioned model — the #180 404 path, through `wire::notFoundBody`.
- [ ] Model swap in progress — 503 + `Retry-After`, through `wire::errorBody`.
- [ ] Embedder not provisioned — 501; provisioning — 503 + `Retry-After: 10`.
- [ ] Thermal shed on `/api/chat` **and** `/api/embed` — and on `/api/chat` it must arrive in the
      **Ollama** envelope, since it is produced by `withInferenceAdmission`, not by any handler.
- [ ] Admission queue full during an `/api/chat` request — **429 + `Retry-After`** in the Ollama
      envelope, and the shared permit released afterwards (no leak).
- [ ] Streaming request with `tools[]` — `tool_calls` survive, `arguments` is an object, token counts
      omitted rather than zeroed.
- [ ] `/api/show` for a model that is **not** resident — no `"vision"`, and the note explains why.
- [ ] `think:true` **together with** `format` — no thinking is produced and no error is raised
      (documented deviation, not a bug to fix here).
- [ ] Two catalog ids that derive the same `name:tag`.
- [ ] Empty catalog / nothing provisioned — `/api/tags` returns `{"models":[]}`, not an error.
- [ ] Nothing resident — `/api/ps` returns `{"models":[]}`.
- [ ] Idle TTL disabled — `expires_at` omitted, not `null`, not epoch 0.
- [ ] Request with no `Authorization` header — 401 on **every** `/api/*` route including `/api/tags`.
- [ ] Stream failure after the NDJSON header is committed — one `{"error":…}` line, no second HTTP status.
- [ ] `abort` message containing a `"` — must not break the JSON line.

## Validation Commands

```bash
# JVM unit tests — the CI unit-test job. Run after any change under src/main or src/test.
cd Android/src
./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest

# Compile (do not run) the on-device probe suite, so OllamaCompatProbe.kt cannot rot.
./gradlew :app:compileFullOpenDebugAndroidTestKotlin

# One debug APK, when a device check is needed (never `assembleDebug` — ambiguous across dist x policy).
./gradlew :app:assembleFullOpenDebug
```

```bash
# On-device probe (hardware only, NOT in CI). Live node = Pixel 9 "comet" (4A111FDKD0000C).
adb -s <serial> shell am instrument -w \
  -e class cc.grepon.relais.OllamaCompatProbe \
  com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -s RelaisOllamaProbe
```

`report-worker` is **not** touched, so `cd report-worker && npm run typecheck && npm test` is not part
of this feature's validation.

### Manual Validation

```bash
KEY=<access-key>; NODE=https://<phone-ip>:8443

# 1. Discovery — only provisioned models, Ollama-shaped names.
curl -k -H "Authorization: Bearer $KEY" $NODE/api/tags
curl -k -H "Authorization: Bearer $KEY" $NODE/api/version

# 2. Auth is mandatory — MUST be 401, including on /api/tags.
curl -k -o /dev/null -w '%{http_code}\n' $NODE/api/tags          # expect 401

# 3. Streaming with `stream` OMITTED — must stream (Ollama's default is true).
curl -k -N -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"<name:tag>","messages":[{"role":"user","content":"count to five"}]}' \
  $NODE/api/chat
# expect: several bare JSON lines, NO `data:` prefix, NO blank lines, last line has "done":true

# 4. The format x default-stream collision — must be 200, NOT the /v1 400.
curl -k -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"model":"<name:tag>","messages":[{"role":"user","content":"a json object with key a"}],"format":"json"}' \
  $NODE/api/chat                                                  # expect 200

# 5. Unsupported options are ignored, not rejected.
curl -k -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"<name:tag>","stream":false,"messages":[{"role":"user","content":"hi"}],"options":{"num_predict":10,"top_k":40,"stop":["x"]}}' \
  $NODE/api/chat                                                  # expect 200 + x_relais_ignored_options

# 6. Embeddings: 2-D vs legacy flat 1-D.
curl -k -H "Authorization: Bearer $KEY" -d '{"model":"<name:tag>","input":["a","b"]}' $NODE/api/embed
curl -k -H "Authorization: Bearer $KEY" -d '{"model":"<name:tag>","prompt":"a"}'      $NODE/api/embeddings

# 7. Resident-model view, during and after a request.
curl -k -H "Authorization: Bearer $KEY" $NODE/api/ps

# 8. Unknown model — Ollama envelope, 404, engine never touched.
curl -k -i -H "Authorization: Bearer $KEY" -d '{"model":"nope:latest","messages":[]}' $NODE/api/chat

# 9. Streaming tool call with `stream` OMITTED — tool_calls must survive, arguments must be an OBJECT,
#    and the terminal line must OMIT eval_count rather than report 0.
curl -k -N -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"<name:tag>","messages":[{"role":"user","content":"weather in Paris?"}],
       "tools":[{"type":"function","function":{"name":"get_weather","description":"current weather",
       "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}]}' \
  $NODE/api/chat
# expect: a line with message.tool_calls[0].function.arguments as {"city":"Paris"} (NOT a string),
#         then a line with "done":true and NO "eval_count" key

# 10. The admission gate reaches /api/chat in the OLLAMA envelope. Saturate the queue (QUEUE_CAPACITY
#     concurrent long generations) and fire one more; or run it on a thermally-shedding node.
for i in $(seq 1 12); do
  curl -k -s -o /dev/null -w '%{http_code} ' -H "Authorization: Bearer $KEY" \
    -H 'Content-Type: application/json' \
    -d '{"model":"<name:tag>","stream":false,"messages":[{"role":"user","content":"write 500 words"}]}' \
    $NODE/api/chat &
done; wait; echo
# expect: some 429s (or 503s when hot), each body {"error":"…"} with a Retry-After header —
#         NOT the OpenAI {"error":{"message":…,"type":…}} envelope. Then confirm the node recovers:
curl -k -H "Authorization: Bearer $KEY" $NODE/metrics | grep -E 'relais_queue_depth|relais_endpoint'
# expect: queue depth back to 0 (no leaked permit) and an /api/chat latency series present
```

**Deferred owner gate (do NOT claim as done in the PR):** point **Open WebUI** at `https://<phone>:8443`
as an **Ollama** connection and confirm the model list populates, a streamed chat renders
token-by-token, and the vision/tool affordances appear per `/api/show` `capabilities`. The owner runs
this.

## Acceptance Criteria

- [ ] All eight routes answer with the documented Ollama shapes; `/api/chat` and `/api/generate` stream
      NDJSON (no `data:` prefix, no blank-line separator, no `[DONE]`, terminal `done:true` line).
- [ ] **No second inference path:** the only `RelaisEngine.generate` / embedder call sites remain the
      existing ones. Grep-verifiable: the three new main files contain **no** `RelaisEngine.` call.
- [ ] **`/v1/*` is byte-identical.** `OpenAiWire` is the identity wire; every existing `/v1` test passes
      **unmodified**, and **no existing test file is edited** to accommodate this feature.
- [ ] `git diff` leaves `RelaisHttpServer.kt:1469` (Anthropic `SseWriter`), `:1381` (Anthropic
      `rejectIfModelUnavailable`) and the `/v1/messages` metric literals at `:1396`, `:1459`, `:1468`
      **untouched**.
- [ ] Bearer required on every `/api/*` route (401 without the key); **no path added to the `/health`
      carve-out**; `/api/tags` and `/api/version` are **not** anonymous discovery endpoints.
- [ ] **Metrics are right, not just labelled:** `endpointLabel` returns a normalized `/api/*` label per
      route, **and** `grep -n 'recordRequest("/v1/chat/completions"' RelaisHttpServer.kt` returns
      **nothing**. An Ollama chat request increments an `/api/chat` counter, not a `/v1` one. No
      request-derived string ever enters a metric label (security M6).
- [ ] Every `/api/*` failure carries `{"error": …}` with the original status — including the two
      `rejectIfModelUnavailable` paths (404 unknown model, 503 swap + `Retry-After`) and the embeddings
      400/501/503, none of which flow through `wire.body`.
- [ ] **`/api/chat` and `/api/generate` run inside the shared inference gate.**
      `grep -n 'withInferenceAdmission("/api/' RelaisHttpServer.kt` returns **two** hits. A
      thermally-shedding node answers `/api/chat` with **503 + `Retry-After`** and a saturated queue
      answers **429 + `Retry-After`**, both in the **Ollama** envelope (test 28), and
      `relais_endpoint_latency` records under `/api/chat` — not `/v1/chat/completions`, not nothing.
- [ ] **The shared permit is not leaked.** Concurrent `/api/chat` load reaches the same queue bound as
      `/v1/chat/completions` and returns to depth 0 afterwards — verified on-device, since a leak is
      invisible to any single-request test.
- [ ] `/api/embed` still sheds **503 + `Retry-After`** when thermal — i.e. `shedIfHot` lives in the
      embeddings **core**, not the shell (test 26) — **and takes no queue permit**, matching
      `/v1/embeddings`.
- [ ] **Streaming tool calls survive.** A streaming `/api/chat` with `tools[]` emits a line carrying
      `message.tool_calls` (with `arguments` as an **object**) before its terminal line, and the
      terminal line **omits** `eval_count`/`prompt_eval_count` rather than zeroing them (test 27).
- [ ] **`OllamaStreamSink` exists as a named class and does the fan-out.** `NdjsonWriter` contains no
      Ollama vocabulary and writes exactly one line per `send`; the two-line tool case is measured at
      the byte level (test 30), not only at the `chatChunk` return value (test 27).
- [ ] **`/api/generate` is stateless.** No `resolveSessionKey` call reaches it — `resolveSessionKey`
      falls back to a hashed peer IP (`:1549-1552`), so inheriting it would silently share one session
      across every client behind a NAT. `/api/chat` keeps `/v1/chat/completions` parity.
- [ ] **The wire is per-request.** No `RelaisWire` implementation other than `OpenAiWire` is an
      `object`; two concurrently constructed chat wires do not share duration or ignored-option state
      (test 24).
- [ ] **`/api/show` `capabilities[]` describes the requested model**, not the resident engine: no
      `"vision"` for a non-resident model, and `x_relais_capabilities_note` says why (test 29).
- [ ] `format:"json"` with Ollama's default `stream:true` returns a valid answer, **not** the 400 that
      `stream + response_format` produces on `/v1`.
- [ ] Unsupported `options{}` keys are ignored, never 400, and are named back in
      `x_relais_ignored_options`.
- [ ] `/api/tags` lists only provisioned models; a name it lists resolves and serves, and a name it does
      not list 404s through the existing #180 policy.
- [ ] Tests 1-30 each **failed before** and pass after; tests 1, 9, 14, 23, 27 additionally
      mutation-checked (`[[relais-prove-tests-red-first]]`: two shipped regression tests in this repo
      passed under the bug they claimed to pin).
- [ ] **`RelaisOllamaRoutes.kt` compiles as written**, i.e. every symbol in the visibility-widening
      inventory was actually widened, and nothing outside that inventory needed widening. If a
      widening not on the list turns out to be required, add it to the plan rather than to the diff
      alone.
- [ ] `./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`
      green; `:app:compileFullOpenDebugAndroidTestKotlin` green.
- [ ] `RelaisHttpServer.kt` grew by **under ~75 net lines** (`wc -l` before/after in the PR body). The
      revision-1 figure of ~40 predated the admission wrappers and the visibility pass and was not
      achievable; an honest 75 is better than a criterion guaranteed to fail.
- [ ] `docs/ollama-api.md`, `docs/openapi.yaml`, and `README.md` updated; every code deviation has a
      matching doc line.
- [ ] The four unverified wire details (Task 10) are each confirmed or corrected.
- [ ] CI green **and** independent `code-reviewer` + `security-reviewer` APPROVE on the **final** diff
      (green CI ≠ reviewed), re-run after every fix round.

## Completion Checklist

- [ ] Codebase patterns followed (NAMING_CONVENTION AGPL header, ERROR_HANDLING single envelope,
      LOGGING_PATTERN `TAG` + no logging of client 4xx, HANDLER_PATTERN router-branch + thin handler +
      pure builder, TEST_STRUCTURE device-free JUnit, PROBE_STRUCTURE `am instrument` header).
- [ ] Error handling complete — every branch of every delegated handler reaches a translator; the
      five-seam checklist in § Notes walked explicitly — including seam 5, which is not visible from
      inside any handler.
- [ ] Logging appropriate — `Log.e` only for post-header stream failures; no per-request logging.
- [ ] Tests written and passing, each proven RED first; four mutation-checked.
- [ ] No hardcoded values — `OLLAMA_COMPAT_VERSION` and every limit are named constants.
- [ ] Documentation updated (`docs/ollama-api.md`, `docs/openapi.yaml`, `README.md`).
- [ ] No scope additions — nothing from § NOT Building crept in.
- [ ] Self-contained — no new dependency, no `build.gradle.kts` change, no Room migration, no manifest
      permission.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **`stream` default inversion** written as OpenAI's `false` | High | High — every client that omits the field hangs or blocks | Test 1 + mutation check; explicit GOTCHA in Task 2; manual curl #3 |
| **Tool-call `arguments` passed through as a string** | High | High — breaks every tool-using client, invisible to non-tool smoke tests | Test 9 + mutation check |
| **`format` × default `stream` 400** | High | High — 400s most JSON-mode Ollama requests | Translator forces `stream:false`; test 23 + mutation check; manual curl #4 |
| **`/api/chat` registered outside `withInferenceAdmission`** — no thermal 503, no queue 429, **the shared permit never taken** (which also corrupts the gate's accounting for concurrent `/v1` clients), no endpoint latency | **High** — revision 1 did exactly this | **High** | Router branches mirror `:433-437` (§ Task 6); grep criterion for two `withInferenceAdmission("/api/` hits; test 28; on-device concurrency check for permit release |
| **Streaming tool calls dropped** — the one tool chunk carries `delta` **and** `finish_reason`, and mapping it to a bare terminal line discards the `tool_calls`; Ollama streams by default, so this breaks every tool client on the default path | **High** — revision 1 did exactly this | **High** | Task 3's two-line rule; test 27 + mutation check; probe assertion in Task 9 |
| **The wire written as a singleton `object`** — `x_relais_ignored_options` and the start clock never reach the terminal NDJSON line, silently unmeeting an acceptance criterion | Medium | Medium | Per-request class (§ Task 5); test 24 asserts two instances do not share state |
| **`format`→`stream:false` answers with a single JSON object, not a committed NDJSON stream** — a client that requires ≥2 lines or switches on `Content-Type: application/x-ndjson` will not parse it | Medium | Medium — silently breaks JSON-mode on strict clients | **Unresolved by design.** Declared in Task 2, documented in `docs/ollama-api.md`, and measured against a real client by the Task 9 probe rather than assumed safe. If a client rejects it, the fallback is committing the NDJSON header in the route |
| **A fifth dialect leak** (four already found: 9 hardcoded metric labels, single-consumption body, `rejectIfModelUnavailable`'s own `respond`, and the admission gate's 503/429) | Medium | High | Reviewers **grep the final diff** for every `respond(`, `recordRequest(`, and `SseWriter(` reachable from an `/api/*` request rather than trusting the seam by inspection |
| **Task-5 find-replace regresses `/v1/messages`** (`SseWriter(` at 1469, `rejectIfModelUnavailable` at 1381, metric literals at 1396/1459/1468) | Medium | High — silently breaks a shipped endpoint | Explicit line list in Task 5 GOTCHA; acceptance criterion asserts those lines are untouched in `git diff` |
| **`shedIfHot` left in the embeddings shell** — `/api/embed` skips thermal backpressure | Medium | High — silent removal of NPU backpressure | Task 7 GOTCHA #2; test 26; acceptance criterion |
| **The `stream` default is not actually `true`** — the doc never states it; revision 1 asserted it as a doc fact and hung test 1, its mutation check and the top risk row on it | Low (the wording strongly implies it) | **Very High** — the feature's central premise; Task 2 would need reworking | Task 10 item 1 proves it empirically **before** the translator is trusted, not after |
| **The error envelope is not `{"error": …}`** — genuinely undocumented, the only row with no doc backing | Medium | Medium — error messages unreadable in every client | Task 10 item 2 against a real `ollama serve` before merge; doc marks it an assumption until then |
| **Reviewer trusts a stale doc-verification claim** — revision 1's "COULD NOT VERIFY" list was wrong in *both* directions from a partial read | Medium | Medium — verification budget spent on settled facts, false confidence on the unsettled one | The § External Documentation table now cites the doc's own wording per row; re-read the doc, do not re-read this plan's summary of it |
| **`RelaisHttpServer.kt` grows** past the point anyone reads it whole (already 2202 vs a target under 800) | Medium | Medium | Hard budget: under ~75 net added lines, measured in the PR body; extract rather than append if exceeded. The eight handlers live in `RelaisOllamaRoutes.kt`, not here — see § Alternatives for the tradeoff that bought |
| **`/api/generate` without `context[]`** loses multi-turn for clients that rely on it | Medium | Low | Documented, with `/api/chat` as the answer. Do **not** fake a `context` array |
| **Shared rate limiter across two dialects** — a chatty client polling `/api/tags` eats the 30 req/60 s budget that real inference needs | Medium | Low | Watch on-device; not worth a per-dialect limiter in v1 |
| **Anonymous probe → 401 reads as "server down"** | High | Low | Stated plainly in `docs/ollama-api.md`; correct behavior, not a bug |
| **`x_relais_*` extension fields** rejected by a strict client | Low | Low | Additive and ignored by everything tested; cheap to drop if one objects |
| **`digest` is an HF commit hash, not a blob digest** | Low | Low | Documented deviation; no client is known to verify it |

## Notes

### Auth — the recommendation

**Bearer stays mandatory on every `/api/*` route. No carve-out, no default-off weakening, no anonymous
discovery.** `/api/*` inherits the existing gate by construction — auth, the 30 req/60 s per-IP limiter,
and the 32 MB body cap are all applied at `RelaisHttpServer.kt:265-289`, *before* the routing `when`,
to every path that is not `GET /health` (`:266`). The security work here is **not adding an exception**;
it costs zero code.

The cost is real and gets documented rather than engineered around:

| Client | Can send a bearer key? | Verdict |
|---|---|---|
| Open WebUI (Ollama connection) | Yes | Works, zero compromise |
| Continue (`apiKey` in `config.yaml`) | Yes | Works |
| Enchanted (custom headers) | Yes | Works |
| Raycast Ollama extension | Yes | Works |
| Home Assistant Ollama integration | **No auth field** | Does not work directly — reverse proxy / overlay hop that injects the header |
| Some Obsidian Ollama plugins | **No auth field** | Same |

A path-prefix key (`https://<phone>:8443/<key>/api/chat`) would make the no-auth-field clients work
while keeping the key required. **Deferred**, and if ever added it must be explicitly opt-in and
default-off: keys in URLs leak into client logs, crash reports and shell history in a way an
`Authorization` header does not, and `endpointLabel` would have to strip the prefix or the key lands in
a metric label. One integration is not worth that by default. Recorded here so the tradeoff is not
re-litigated from scratch.

Two auth-adjacent rules for review: (1) no `/api/*` path may be added to the `GET /health` carve-out;
(2) `/api/version` and `/api/tags` are **not** exempt "discovery" endpoints — `/api/tags` enumerates the
node's models and is exactly the reconnaissance the bearer gate exists to stop.

### The five-seam error checklist

Reaching every failure takes all five seams, not just `wire.body`. **Seam 5 is new in revision 2** and
was the CRITICAL finding: it sits *above* the handler, in the router branch, so no amount of reading
`handleOpenAi` reveals it.

1. Errors **inside** `handleOpenAi` / `handleToolCompletion` / `handleStructuredCompletion` (400 unknown
   template `:1231`, 400 unsupported `response_format` `:1274`, 400 stream+format `:1692`, 422
   structured-output exhaustion `:1755`) → `wire.body(...)`.
2. Errors from `rejectIfModelUnavailable` (`respond` at `:1173`, `:1185`, `:1196`; `recordRequest` at
   `:1172`, `:1184`, `:1195` — **three** outcomes: 503 swap, 404 unknown, 404 not-provisioned) →
   `wire::errorBody` / `wire::notFoundBody` + `wire.endpoint`.
3. Errors from `handleEmbeddings` (400 / 501 / 503) → the derived-`RequestContext` translator.
4. Errors raised in the Ollama route itself (malformed JSON, unresolvable name) → `error()` directly.
5. **Errors from `withInferenceAdmission` before the handler ever runs** — the thermal **503 +
   `Retry-After`** from `shedIfHot` (`:537-552`) and the admission **429 + `Retry-After` +
   `"code":"queue_full"`** from `rejectIfQueueFull` (`:554-576`). Both are written by the `reply`
   lambda **handed to the gate in the router branch**, so they arrive in the OpenAI envelope unless
   that branch passes the translating `ollamaReply`. This is the one seam that is invisible from
   inside the handler, and the only one whose omission also degrades `/v1` (an untaken shared permit
   corrupts the gate's depth accounting for every other client). → stateless `ollamaReply`, test 28.

Seams 1-4 reshape a body the node decided to send; **seam 5 decides whether the node serves at all**.

The `RelaisError` type/code taxonomy (`RelaisError.kt:32-55`) is deliberately dropped on `/api/*` —
Ollama's envelope has nowhere to put it; the same failures stay fully typed on `/v1/*`. The **401 from
the shared auth gate is produced before routing** (`RelaisHttpServer.kt:267-269`) and therefore arrives
in the **OpenAI** envelope, not the Ollama one. A known, harmless asymmetry (the client is
unauthenticated either way); note it in the doc rather than moving the auth gate to accommodate it.

### Cross-plan sequencing (new in revision 2 — revision 1 claimed no sibling interaction)

Four sibling plans measured against the same tree and touch the same lines. None is fatal; all need an
ordering decision **before** this branch is cut, because they are textual conflicts, not logical ones.

| Sibling | Collision | Sequencing |
|---|---|---|
| **feature-20** (`x_relais_ttft_ms`) | Edits `RelaisHttpServer.kt` at **`:1300, :1349, :1356, :1644, :1747`** — inside the exact response objects and final chunk this feature's seam rewrites at `1302/1349/1646/1749`. **Direct textual conflict.** | **Land feature-20 first**, then re-measure this plan's entire Task-5 edit inventory. Every line number in that table moves. Do not rebase and trust the old numbers. |
| **feature-18** (trusted-LAN cert) | T2 restructures the **auth gate at `:265-286`** and adds an *unauthenticated* `GET /ca.crt`. That gate is this feature's entire security argument (*"`/api/*` inherits the existing gate by construction"*). Also adds arms to `endpointLabel` at `:1877-1899`. | Either order works, but whichever lands second must **re-verify the carve-out list**: this plan's § Auth claim that `/health` is the only exemption is true today and false after feature-18. Restate it as "the exemption list, whatever it then contains, gains no `/api/*` entry." |
| **feature-09** (web dashboard) | Changes `RelaisMetrics.recordRequest`'s signature (`inRecentLog: Boolean = true`), teaches `authorized()` HTTP Basic, adds a router arm. Same three seams. | Signature change is source-compatible (defaulted param), so ordering is free. **feature-09 has cut its own `RequestContext private→internal` task, so this plan is the sole owner of that widening** — it must not be dropped from § Files to Change on the assumption a sibling does it. |
| **feature-22** (idle unload) | Adds `RelaisEngine.idleSeconds` over the same `private lastActivityAtMs` that `/api/ps`'s `expires_at` needs (`feature-22…:438`). | **Prefer feature-22 first**: then `/api/ps` derives `expires_at` from `idleSeconds` + the TTL and this feature touches `RelaisEngine.kt` not at all. If this lands first, feature-22 consumes what this adds. Either way, one accessor. |
| **feature-21** (Home Assistant) | This plan's UX table says HA's Ollama integration is *"still unusable directly — the integration exposes no auth field"* and needs a header-injecting proxy. | **That statement stands and feature-21 is being aligned to it.** feature-21 must not claim HA's Ollama integration works against this shim. If HA ever gains an auth field, this row changes — nothing else in the design does. |

### Revision-2 disposition (no silent drops)

Every critic finding is either fixed above or declined here with a reason.

**Fixed:** C1 (admission gate + seam 5) · H1 (per-request wire) · H2 (streaming tool calls + declared
missing counts) · H3 (`abort` default on the interface only; "SseWriter unchanged" corrected) ·
H4 (visibility inventory + extension-function design + honest line budget) · M1 (per-requested-model
`capabilities[]`; `think`×`format` gap documented) · M2 (`RelaisEngine.kt` added for the idle
accessor) · M3 (doc-verification split rewritten from a re-fetch) · M4 (`dataUriBytes` citation
corrected) · M5 (cross-plan sequencing table above) · L1 (`:979-1019`, `endpointLabel` `:1877-1899`).

**Declined:**

- **Declined: forcing `stream:false` when `tools[]` is present** (H2's second option) — it would
  create a second instance of the single-JSON-object framing deviation on the path most likely to be
  driven by an automated client; framing correctness beats the cosmetic token counts it would recover.
  Reasoning in full at § Task 2 GOTCHA 4.
- **Declined: dropping `"thinking"` from `/api/show` `capabilities[]`** (M1's second half) — thinking
  does work on the ordinary streaming path, which is the overwhelmingly common request; suppressing
  the capability to protect the `think`+`format` corner would hide a working feature. The corner is
  documented as a deviation instead.
- **Declined: shortening the plan** (L1's closing remark that 1000+ lines is long for one feature) —
  the length is load-bearing here. Revision 1 was shorter and shipped a CRITICAL.

### Alternatives considered and rejected

- **A standalone `RelaisOllamaServer` with its own engine calls.** Rejected: two inference paths means
  two thermal policies, two admission gates, two session behaviors, and two places for #180's model
  policy to drift. The seam costs one interface.
- **Translating at the socket layer** (rewrite bytes in/out around the existing router). Rejected:
  streaming makes it stateful, and the error paths that never reach the router (401, 429, 413) would be
  the ones it could not reach.
- **Putting the eight handlers *inside* `RelaisHttpServer.kt`** as private methods, instead of
  `internal` extension functions in `RelaisOllamaRoutes.kt`. This was the critic's other option for
  H4 and it does compile with **zero** visibility widening. Rejected because it adds ~120 lines to a
  file already at 2202 against a repo target under 800 (`CLAUDE.md` § Style rules explicitly prefers
  extracting a new file over growing this one, citing the `RelaisHttpIo.kt` extraction as precedent).
  The extension-function form keeps the new code in a new file and costs six `internal` keywords, none
  of which widens anything past the module boundary. **The two designs are otherwise equivalent** —
  if the widening inventory turns out to be wrong or to grow during Task 6, switching to this option
  is a mechanical, non-behavioral change, and is preferable to widening a symbol not on the list.
- **Widening `withInferenceAdmission` to `internal`** so the route file could call it directly.
  Rejected: it is `private inline` (`:518`), and an `internal inline` function may not reference
  `private` declarations, so this cascades into `@PublishedApi internal` on `shedIfHot`,
  `rejectIfQueueFull` and `admissionGate` — three more annotations on the most safety-critical code in
  the file, to save nothing. Wrapping in the router branch is both smaller and a closer mirror of
  `:433-437`.
- **A `dialect` enum threaded through the handlers** instead of an interface. Rejected: every emit site
  would grow a `when`, which is exactly the shape that lets one site be forgotten — the failure mode
  three of these seams already exhibited.
- **Honoring `keep_alive`.** Rejected on security grounds (see § NOT Building).
- **Listing the full catalog in `/api/tags`.** Rejected: since #180 those ids 404, so it is a menu of
  failures.
- **Weakening auth for `/api/*`** — an anonymous-discovery carve-out for `/api/tags` + `/api/version`,
  or a path-prefix key (`https://<phone>:8443/<key>/api/chat`) to serve the clients with no auth field.
  **Both rejected for v1**; the full reasoning, the per-client support table, and the conditions under
  which the path-prefix key could be revisited (explicitly opt-in, default-off) are in
  § Notes → **Auth — the recommendation** above.

### Open questions for the user

1. **`OLLAMA_COMPAT_VERSION` value.** Some clients gate features on it. Proposal: pin a recent, plausible
   `0.x.y` and document it as "the API version emulated, not a Relais version". Confirm the pin in
   Task 10 against whatever `ollama serve` reports.
2. **Should `/api/tags` fall back to listing un-provisioned catalog entries** when *nothing* is
   provisioned (so a fresh node does not look empty), or stay strictly honest with `{"models":[]}`?
   This plan chooses strictly honest.
3. **Is the Home Assistant integration important enough** to reconsider the path-prefix key sooner? This
   plan defers it; the answer changes the priority, not the design.
4. **`docs/ollama-api.md` naming** — the repo's existing docs are `docs/<thing>-api.md`; confirm that is
   the wanted filename rather than folding into `docs/openapi.yaml` alone.
5. ~~Merge order against feature-20~~ **DECIDED (JD, 2026-09-07, via HANDOFF build order): feature-20
   B1-A lands first.** This plan's assumption holds — Task 5's edit inventory against
   `RelaisHttpServer.kt:1300/1349/1356/1644/1747` must be re-measured from scratch against feature-20's
   actual landed diff before Task 5 starts, not against the line numbers in this plan today.
6. ~~Is the single-JSON-object framing on the `format`→`stream:false` path acceptable?~~ **DECIDED (JD,
   2026-09-07): accept it, document it as a risk.** This is a narrow combination — structured output
   AND explicitly non-streaming — that real Ollama clients rarely hit; the fallback (committing an
   NDJSON header and re-framing) stays specified-but-unbuilt in the Risks table, and Task 9's probe
   still measures a real client's behavior against the accepted framing rather than assuming it's safe.

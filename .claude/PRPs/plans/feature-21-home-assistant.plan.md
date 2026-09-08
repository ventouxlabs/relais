# Plan: Home Assistant Integration (docs-first, minimal code)

> Branch: `docs/home-assistant-integration`. **Sequence after feature-18 (trusted LAN cert)** — see
> *External Documentation* §3, the transport finding that reshapes this plan and rules out the most
> common HA install type entirely. Adds one docs page, one README bullet, and two small conformance
> tests. **No new endpoint, no new Android code path, no auth change, no new bind.**

## Summary

Relais already speaks OpenAI-compatible `/v1/chat/completions` over the LAN, which is exactly what a
Home Assistant conversation agent needs — so this feature is **mostly documentation**. The one thing
stopping it from being purely documentation is a transport mismatch nobody has written down: HA
verifies TLS against a `certifi` bundle it is handed explicitly, Relais serves the LAN only over
HTTPS with a self-signed cert, and **no HA LLM integration exposes a `verify_ssl` toggle**. The honest
deliverable is a page that is correct about *who can do this today* — HA Container and Supervised —
says plainly that HA OS cannot, and invents no workaround that doesn't exist.

## User Story

**As a** Home Assistant user with a Relais node on my LAN,
**I want** to use the phone as my Assist conversation agent,
**So that** my voice assistant answers from a model I own, on hardware I control, with nothing
leaving the house — and **so that** if my HA install type can't do this, I find out on line one
instead of after an hour of debugging TLS.

## Problem → Solution

| Problem (verified) | Solution |
|---|---|
| No documentation exists for pointing HA at Relais, and the obvious candidate integration (**OpenAI Conversation**) silently cannot work — it has no base-URL field at all and drives the Responses API. | A `docs/home-assistant.md` that names **core `litellm`** (HA ≥ 2026.8) as the path and documents OpenAI Conversation as **not supported, with the reason**. |
| **Relais has no LAN-reachable HTTP** (`RelaisNodeService.kt:189-190` binds HTTP to `127.0.0.1` and HTTPS to `0.0.0.0`), and HA OS has no supported way to add a private CA. | A "Before you start" compatibility matrix as **§1** of the page, so HA OS users learn immediately. Plus an explicitly-rejected option D so the "just add plain LAN HTTP" question stops being re-asked. |
| `/v1/models` returns the **whole curated catalog**, and since #180 a request naming a model that is **never provisioned** 404s on every turn (a model that *is* on disk but not resident 503s once with `Retry-After: 25`, then works — `RelaisHttpServer.kt:1163-1179`). HA's dropdown will happily offer either. | A prominent doc trap ("pick the model the control panel's `SERVING` row shows") plus a troubleshooting table that distinguishes the two, and an open question about filtering the endpoint. |
| The invariants HA depends on (unknown-top-level-key tolerance; a deterministic `/v1/models` body) are true today only **by construction** and could be broken silently by an unrelated change. | Two small JVM conformance tests that pin them — **one net-new assertion each**, because most of the surface is already covered. |
| **`GET /v1/models` is not cheap.** `handleModels` → `curatedModels()` (`RelaisModelCatalog.kt:108` → `:115` → `allowlistModels()` `:120-158`) does a **blocking upstream HTTP fetch** on cold cache, after the 5-minute `CURATED_TTL_MS` (`:35`), and on **every** call while the fetch is failing (failures are deliberately not cached, `:151-155`). HA gates its config flow on this endpoint with a **10 s** timeout. | Disclose the exposure in the page and measure it **cold** in Manual Validation. Also document the connectivity inversion: an offline node returns an empty catalog → the fallback branch (`RelaisHttpServer.kt:2073-2081`) → HA's dropdown shows **exactly one** (correct) model; an online node shows the whole catalog, mostly not provisioned. |

## Metadata

- **Complexity:** Small (code), Medium (research + doc accuracy)
- **Source PRD:** N/A
- **PRD Phase:** N/A
- **Estimated Files:** 5 (1 CREATE doc, 1 UPDATE README, 3 UPDATE tests)

## UX Design

**N/A — no Relais UI change.** The user-facing surface is Home Assistant's own config flow and
Assist pipeline; Relais renders nothing new. The "UX" this feature owns is the *ordering of the docs
page*, captured below.

### Interaction Changes

| Touchpoint | Before | After | Notes |
|---|---|---|---|
| Discovering that Relais works with HA | Nothing in the repo mentions HA | README "Works with" bullet → `docs/home-assistant.md` | Matches the existing bullet voice at `README.md:126-136` |
| An HA **OS** user attempting this | Discovers the TLS wall after an hour | Learns on §1 line one that their install type cannot, and what would change that | The single most valuable thing the page does |
| An HA **Container** user | No recipe | Step-by-step: trust cert → add LiteLLM → add agent → smoke test → wire into Assist | |
| Picking a model in HA's dropdown | Any catalog entry; a non-provisioned one 404s every turn | Doc trap: pick the model the control panel shows as **resident** | Behavior unchanged; the docs carry the warning |
| Node not yet ready when HA is configured | Opaque failure | Documented self-heal: `ConfigEntryNotReady` → HA retries with backoff | |

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisNodeService.kt` | 189-190, 197-198 | The bind posture that *is* the blocker: HTTP `127.0.0.1:8080`, HTTPS `0.0.0.0:8443`. Read the comment before proposing any transport change |
| **P0** | `SECURITY.md` | 14-18 | The network posture: *"HTTPS on the LAN, HTTP on loopback … the bearer API key therefore never crosses the network in cleartext."* Option D is rejected *because of this*. **Note:** the label "C1" appears nowhere in `SECURITY.md` — it lives only in the code comment at `RelaisNodeService.kt:187`, so grep there, not here |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 265-270, 906-911 | The auth gate (health is the only open route) and `handleModels` — the endpoint HA's 10 s config-flow gate hits |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisModelCatalog.kt` | 35, 108, 115-158 | **Read before repeating "`/v1/models` is cheap."** The no-arg `curatedModels()` overload (`:108`) delegates to `:115`, which blocking-fetches the upstream allowlist. 5-min TTL at `:35`; failures not cached at `:151-155` |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 2055-2095 | `buildModelsResponse`: deterministic and engine-free — **but note this is the *pure function*, not the endpoint.** The endpoint blocking-fetches the allowlist (row above). Also the source of the `provisioned` flag HA does not read |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisOpenAiParser.kt` | 85-100, 155-165 | `optString`/`optJSONObject`-based parsing — why HA's top-level `user` field is tolerated. The invariant test 1 pins |
| **P1** | `.claude/PRPs/plans/feature-18-trusted-lan-cert.plan.md` | header | The per-node private CA. Read before repeating the correction below — it is easy to get backwards |
| **P1** | `README.md` | 88-104, 126-136 | The `-k` caveat already documented, and the "Works with" list voice to match |
| **P2** | `docs/RUNBOOK.md` | 20-40 | The operator-facing framing the troubleshooting table should match |
| **P2** | `.claude/PRPs/plans/feature-22-idle-unload.plan.md` | Risks | The cold-start / 503 interaction referenced in the troubleshooting table |
| **P2** | `SPIKE-FINDINGS.md` | 1-20 | The date-stamp + version-pin convention this page should copy |

## External Documentation

All rows verified by reading Home Assistant core at tag **2026.9.1**. Nothing here has been observed
against a live HA instance — that is Task 1.

| Topic | Source URL | Key Takeaway |
|---|---|---|
| OpenAI Conversation integration | https://www.home-assistant.io/integrations/openai_conversation/ | Officially states it works **only** with the official OpenAI endpoint; no base-URL field exists |
| LiteLLM integration (core, HA 2026.8+) | https://www.home-assistant.io/integrations/litellm/ | Generic OpenAI-compatible client: `CONF_URL` + optional `CONF_API_KEY`. **The path.** |
| Extended OpenAI Conversation (HACS) | https://github.com/jekalmin/extended_openai_conversation | In the HACS **default store**; `CONF_BASE_URL`. The fallback for HA < 2026.8 |
| Ollama integration | https://www.home-assistant.io/integrations/ollama/ | Native Ollama API only (`/api/tags`, `/api/chat`) — never `/v1`. **And it exposes no API-key field**, so feature-17's shim is necessary but *not sufficient* |
| Zeroconf discovery | https://developers.home-assistant.io/docs/creating_integration_manifest/#zeroconf | Custom components may declare `zeroconf` in their manifest; HA browses **only declared types** |
| `conversation.process` action | https://www.home-assistant.io/integrations/conversation/ | Registered `SupportsResponse.OPTIONAL`; `agent_id` is the conversation **entity id** |

---

**Research item 1 — the integration to target changed**

- **KEY_INSIGHT:** `openai_conversation`'s entire user schema is `vol.Required(CONF_API_KEY): str` — no base-URL field anywhere — and the client is built as `AsyncOpenAI(api_key=…)` with no `base_url`. It also drives the **Responses API** (`client.responses.create(stream=True)`), not chat-completions. Core **`litellm`** (shipped HA 2026.8.0) is the generic OpenAI-compatible client despite the name: two fields, `vol.Required(CONF_URL)` and `vol.Optional(CONF_API_KEY)`.
- **APPLIES_TO:** The whole docs page; the README bullet; the HA-version floor.
- **GOTCHA:** Even the `OPENAI_BASE_URL` env-var trick would not rescue OpenAI Conversation — it would demand Relais implement streaming `POST /v1/responses`. **Do not document it as an option.** And do **not** recommend `michelle-avery/openai-compatible-conversation` despite the on-the-nose name: it has an open "Broken in Home Assistant 2026.9" issue.

**Research item 2 — what HA actually puts on the wire**

- **KEY_INSIGHT:** Relais already satisfies every requirement. HA sends a top-level `user` (set to `conversation_id`) on **every** request; sends `tools` when "Control Home Assistant" is on, up to `MAX_TOOL_ITERATIONS = 10` round trips; requires a non-empty `choices` array or raises `HomeAssistantError("API returned empty response")`; and validates the config flow with `GET {url}/models` inside a **10 s** timeout.
- **APPLIES_TO:** Tests 1 and 2; the troubleshooting table.
- **GOTCHA:** `litellm` is **non-streaming** — it calls `client.chat.completions.create(...)` without `stream=True`. Relais's SSE support is irrelevant on this path; the whole completion arrives at once. With no API key configured it sends the literal `sk-no-key-required`, which Relais will reject with 401 — the key field is effectively required for us.

**Research item 3 — the blocker: TLS trust**

- **KEY_INSIGHT:** HA resolves its CA bundle as `cafile = environ.get("REQUESTS_CA_BUNDLE", certifi.where())` and passes it to `ssl.create_default_context(cafile=…)`.
- **APPLIES_TO:** §1 and §3 of the docs page; the whole compatibility matrix.
- **GOTCHA:** Three sharp edges, all load-bearing. (a) **`SSL_CERT_FILE` is inert**, and so is the OS trust store (`update-ca-certificates`), because `cafile=` is passed explicitly. (b) **`REQUESTS_CA_BUNDLE` *replaces* certifi, it does not add to it** — pointing it at a bare Relais CA breaks TLS for **every other HTTPS integration in HA**; the instruction must be *concatenate onto a copy of certifi's bundle*. (c) It must be set **before the HA process starts** — the contexts are `@cache`d and pre-warmed at import. And **no integration exposes `verify_ssl`**: `litellm` and `openai_conversation` call `get_async_client(hass)` (defaulting `verify_ssl=True`); `ollama` hardcodes the verifying `get_default_context()`. The no-verify plumbing exists in HA but is unreachable from these.

**Research item 4 — mDNS auto-discovery**

- **KEY_INSIGHT:** `async_get_zeroconf` merges custom-integration manifests into the core table, so a HACS component declaring `_relais._tcp.local.` **is sufficient** — it does not need upstreaming into `homeassistant/generated/zeroconf.py`.
- **APPLIES_TO:** §9 "Not supported, and why".
- **GOTCHA:** HA browses **only declared types** and drops unmatched ones, so there is **no generic discovery surface** — a `_relais._tcp` advertisement with no integration declaring it is invisible to HA. And a manifest entry cannot be added to core `litellm` without a core PR. **Auto-discovery and the zero-install core path are therefore mutually exclusive.** An HA integration manifest is explicitly out of scope; record this so the tradeoff is not re-derived.

**Research item 5 — timeouts**

- **KEY_INSIGHT:** `litellm` inherits the OpenAI SDK default `Timeout(timeout=600, connect=5.0)` with 2 retries (HA's injected httpx client leaves the default in place). At ~5 tok/s, 200 tokens ≈ 40 s — a wide margin.
- **APPLIES_TO:** §7 of the page; the troubleshooting table.
- **GOTCHA:** The binding constraint is **not** the HTTP timeout, it is the **300 s Assist pipeline timeout** (`DEFAULT_PIPELINE_TIMEOUT = 60 * 5`) covering STT + LLM + TTS — quote that number. The real risk is the **5 s *connect*** timeout, which bites when the phone isn't listening yet, not when it's slow. A 503-while-loading self-heals: `async_config_entry_first_refresh()` raises `ConfigEntryNotReady`, so HA retries setup with backoff; once running, a failure only marks the entity unavailable and re-pings every 60 s.

### The compatibility decision — enumerate, pick, be honest about who is excluded

| Option | Works? | Verdict |
|---|---|---|
| **A.** HA **Container / Supervised** + `REQUESTS_CA_BUNDLE` (concatenated onto certifi, set pre-start) | Yes, today | **Document as the supported path now.** Supervised must persist the env var in the compose/systemd definition — the Supervisor recreates the container on update |
| **B.** **HA OS** | **No supported way** to set env vars on the core container | **Blocked. Do not fake a workaround** |
| **C.** feature-18 (per-node CA) | Improves A substantially; does **not** fix B | See the correction below |
| **D.** Add an opt-in LAN HTTP bind | Would work | **Rejected** — directly contradicts the C1 posture in `SECURITY.md` and would put the bearer key in cleartext on the LAN. Name it in the docs so the question stops being re-asked |
| **E.** TLS-terminating reverse proxy on the HA host | Yes | One line as an advanced escape hatch for Container/Supervised; it is the same trust problem moved one hop, and it is not our supported surface |

**Correction worth stating loudly, because it is easy to get backwards:** feature-18 mints a **per-node
private CA** with a SAN'd leaf and exports it out-of-band (`GET /ca.crt`, QR, share sheet). That is a
large win for option **A** — it turns the cert step from a fragile one-off into a stable import that
**survives DHCP churn** instead of breaking every time the phone's IP changes. But it is still a
*private* CA, and **HA OS's blocker is that it cannot set `REQUESTS_CA_BUNDLE` at all** — not the
cert's provenance. So feature-18 does **not** unblock HA OS. Only a **publicly-trusted** chain (real
domain + DNS-01) would, and that is outside "LAN-local". Sequence this plan after feature-18 so the
cert step is written once, against the stable CA, rather than documented twice.

## Patterns to Mirror

**NAMING_CONVENTION** — topic pages live at `docs/<topic>.md`, lowercase-hyphenated:
`docs/tasker-intent-abi.md`, `docs/litertlm-native-api.md`, `docs/input-content-guide.md`. (The
uppercase `docs/RUNBOOK.md` is the one exception and is not the pattern to copy — `home-assistant.md`
is a topic page.) Version-and-date-stamp
any page making external-software claims, the way `SPIKE-FINDINGS.md` does.

**ERROR_HANDLING** — the response shapes the troubleshooting table must describe accurately:

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
// SOURCE: RelaisHttpServer.kt:537-548 (:536 is the KDoc) — the canonical 503 + Retry-After shape
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

**LOGGING_PATTERN** — not exercised by this feature (no new production code paths), but the two tests
must not introduce any logging. For reference, the house form is `Log.i(TAG, "…")` with a file-level
`private const val TAG = "RelaisHttpServer"` (`RelaisHttpServer.kt:68`); never log a key or path.

**HANDLER_PATTERN** — the endpoint HA's config flow depends on. Note it is dispatched from the same
`when` block as everything else and does **no** engine work:

```kotlin
// SOURCE: RelaisHttpServer.kt:402
method == "GET" && path.startsWith("/v1/models") -> handleModels(ctx)

// SOURCE: RelaisHttpServer.kt:910
ctx.send(200, buildModelsResponse(refs, fallback, onDisk))
```

```kotlin
// SOURCE: RelaisHttpServer.kt:2055 (comment) + :2080, :2091 — the #180 provisioned flag HA does not read
// A fixed constant keeps buildModelsResponse pure and deterministic — no System.currentTimeMillis().
        .put("provisioned", fallbackId in provisionedIds)
          .put("provisioned", ref.modelId in provisionedIds)
```

**PARSER_PATTERN** — why HA's extra top-level fields are harmless. Every read is an `optString` /
`optJSONObject` / `optJSONArray`; nothing enumerates or rejects unknown keys:

```kotlin
// SOURCE: RelaisOpenAiParser.kt:90-95
when (part.optString("type")) {
  "text" -> text = part.optString("text")
  ... ->
    image = part.optJSONObject("image_url")?.optString("url")?.let { dataUriBytes(it) } ?: image
  ... ->
    audio = part.optJSONObject("input_audio")?.optString("data")?.let { decode(it) } ?: audio
```

```kotlin
// SOURCE: RelaisOpenAiParser.kt:286-292
private fun parseAssistantToolCalls(msg: JSONObject): List<ParsedToolCall> {
  val calls = msg.optJSONArray("tool_calls") ?: return emptyList()
  ...
    val name = function.optString("name")
```

**TEST_STRUCTURE** — JUnit 4, no `@RunWith` for pure logic, static-imported `org.junit.Assert.*`
members, backtick names, numbered section banners, and a class KDoc that states what the tests do
**not** cover:

```kotlin
// SOURCE: Android/src/app/src/test/java/cc/grepon/relais/RelaisIdleTtlTest.kt:19-45
package cc.grepon.relais

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic unit tests for the pure idle-unload decision in [RelaisIdleTtl.kt] (#178). No Context,
 * no Android types, no [RelaisEngine] — pure JVM, mirrors [RelaisAdmissionTest].
 * ...
 */
class RelaisIdleTtlTest {

  private val ttlMs = 15L * 60_000L // 15 minutes, matches IDLE_TTL_DEFAULT_MINUTES

  // -------------------------------------------------------------------------
  // 1. not ready -> never unload (nothing resident to release)
  // -------------------------------------------------------------------------

  @Test
  fun `never unloads when engine is not ready`() {
```

**BODY_LEVEL_PARSE_SEAM** — the *only* JVM-reachable function that takes the whole request body.
This distinction is load-bearing and easy to get wrong:

```kotlin
// SOURCE: RelaisToolParsing.kt:52 — takes the BODY. Testable, and the one place an unknown
// top-level key could plausibly be rejected.
fun parseTools(body: JSONObject): List<ToolSpec> {
  val arr = body.optJSONArray("tools") ?: return emptyList()
```

```kotlin
// SOURCE: RelaisOpenAiParser.kt:118 — takes the MESSAGES ARRAY, never the body. It cannot see a
// top-level `user`, `model`, or `tools`, so a "unknown top-level key" test written here is a
// tautology that no mutation can fail.
internal fun buildPromptParts(
  messages: JSONArray,
```

The body-level parse on the server, `parseOpenAiRequest(body: JSONObject)` (`RelaisHttpServer.kt:1777`),
is **private** on a `Context`-holding class and is **not** reachable from JVM tests. Do not plan
against it.

The three existing target suites — `RelaisToolParsingTest.kt`, `OpenAiRequestParserTest.kt`, and
`RelaisModelsResponseTest.kt` — all already live in
`Android/src/app/src/test/java/cc/grepon/relais/`; **extend them, do not add files.**

**PROBE_STRUCTURE** — **N/A.** This feature adds no on-device probe. The device-side verification is
a manual gate on a real HA instance (see *Deferred gate*), not an instrumented test — an HA instance
is not something `am instrument` can stand up.

## Files to Change

| File | CREATE/UPDATE | Justification |
|---|---|---|
| `docs/home-assistant.md` | **CREATE** | The deliverable: §§1-9 below |
| `README.md` | UPDATE | One "Works with" bullet appended to the list at `:126-136` |
| `Android/src/app/src/test/java/cc/grepon/relais/RelaisToolParsingTest.kt` | UPDATE | Test 1a — unknown **top-level** key tolerance, at the only body-taking seam (`parseTools`) |
| `Android/src/app/src/test/java/cc/grepon/relais/OpenAiRequestParserTest.kt` | UPDATE | Test 1b — unknown **message-level** key tolerance (this file's function takes the messages array, not the body) |
| `Android/src/app/src/test/java/cc/grepon/relais/RelaisModelsResponseTest.kt` | UPDATE (**exists** — verified: `:98-112` and `:118-131` already cover the fallback and round-trip cases) | Test 2 — the **one** net-new assertion: determinism |

## NOT Building

- **No new endpoint.** No `/v1/responses`, no HA-specific route, no discovery endpoint.
- **No opt-in LAN HTTP bind** (option D). It contradicts the C1 posture and would put the bearer key in cleartext on the LAN.
- **No HA custom integration / manifest.** Auto-discovery is out of scope and mutually exclusive with the zero-install core path (research item 4).
- **No change to `/v1/models` behavior.** The `provisioned` flag exists precisely so callers can see the difference; filtering it is a client-visible behavior change with its own compat risk — raised as an open question, decided separately, **never inside a docs PR**.
- **No Ollama shim.** That is feature-17. Reference it as a dependency; do not duplicate its design.
- **No auth change, no new bind, no TLS change.** feature-18 owns the cert work.
- **No claim that any of this has been validated against a live HA instance** until Task 1 is done.

## Step-by-Step Tasks

### Task 1 — Verify against a real HA instance (**do this first**)

- **ACTION:** Stand up HA Container against a live node and capture the exact `REQUESTS_CA_BUNDLE` recipe that works.
- **IMPLEMENT:** Confirm which install types can trust the cert; record the literal concatenation command, the mount path, and the compose fragment. Confirm the LiteLLM config flow completes and `/v1/models` answers inside 10 s.
- **MIRROR:** the date-stamp + version-pin convention of `SPIKE-FINDINGS.md`.
- **IMPORTS:** N/A.
- **GOTCHA:** Every claim in this plan is read off HA source at tag 2026.9.1, **not** observed. `litellm` shipped only in 2026.8 and is bronze quality scale — its config-flow URL handling has two sharp edges, which suggests little real-world use, so menu labels and field names may already have drifted.
- **VALIDATE:** A working Assist reply through Relais, screenshotted or logged.

### Task 2 — Write `docs/home-assistant.md`

- **ACTION:** Nine sections, ordered so the reader hits the blocker *before* wasting time.
- **IMPLEMENT:**
  1. **Before you start** — a matrix of {HA OS, Container, Supervised} × {LAN HTTP: never} × {self-signed today, stable per-node CA after feature-18}. HA OS users learn on line one that this does not work on their install, and that the fix is a Container/Supervised install (or an overlay network + a publicly-trusted cert) — **not** a future Relais release.
  2. **Prerequisites** — node running; the model you will pick showing in the control panel's `SERVING` row (not merely catalogued); phone IP or mDNS name; access key from the control panel's ACCESS KEY row; HA ≥ 2026.8.
  3. **Step 1 — trust the node's cert** (Container/Supervised): fetch the cert, concatenate onto a *copy* of certifi's bundle, mount it, set `REQUESTS_CA_BUNDLE` in the container definition, restart HA. The "this **replaces**, not adds" warning as a callout, plus how to verify with `curl --cacert`.
  4. **Step 2 — add the LiteLLM integration**: Settings → Devices & services → Add → LiteLLM. URL `https://<phone-ip>:8443` — **with** the scheme, **not** the full endpoint path. API key: the node's access key. Both URL traps verbatim.
  5. **Step 3 — add a conversation agent**: pick the model shown in the control panel's **`SERVING`** row (the #180 trap — use the literal on-screen label, `SERVING`, per `docs/dashboard-copy.md:91`; the word "resident" appears nowhere in the UI), set instructions, optionally enable "Control Home Assistant" for tool-calling.
  6. **Step 4 — prove it without voice**, Developer Tools → Actions (YAML):
     ```yaml
     action: conversation.process
     data:
       text: "Turn on the kitchen light"
       agent_id: conversation.relais_gemma_e2b
       conversation_id: relais_smoke_test
     response_variable: result
     ```
  7. **Step 5 — wire into Assist**: Settings → Voice assistants → set Conversation agent. Note there is no supported YAML for pipelines (config-entry/storage backed) — the UI selector is the path. Quote the **300 s** pipeline budget. **Also disclose the config-flow cost honestly here:** HA validates with `GET {url}/models` inside **10 s**, and that endpoint is *not* free — it blocking-fetches the upstream allowlist on a cold cache, after its 5-minute TTL, and on every call while that fetch is failing (`RelaisModelCatalog.kt:35`, `:120-158`, `:151-155`). Tell the reader to warm it (`curl … /v1/models` once) before starting the config flow if their node has just booted or the phone's uplink is flaky, and that an **offline** node shows exactly one model in HA's dropdown (the fallback branch) while an **online** node shows the whole catalog, most of it not provisioned.
  8. **Troubleshooting table** — mapped to *our* real failure modes, with the three-way model outcome kept distinct (`rejectIfModelUnavailable`, `RelaisHttpServer.kt:1163-1204`):
     - `401` — wrong key, or `litellm` sending its `sk-no-key-required` placeholder because the key field was left blank.
     - `404` **on every turn** — the model was **never provisioned** (`NotProvisioned`, `:1191-1201`). A separate 404 with a specific reason means the file *is* on disk but is incompatible (`Incompatible`, `:1180-1189`).
     - **`503 + Retry-After: 25`, then it works** — the **#180 model swap**: the requested model *is* provisioned but is not the resident one, so the node swaps and asks you to retry (`SwapThenRetry`, `:1163-1179`). **This is the most likely 503 an HA user hits after switching agents**, and it self-heals: the OpenAI SDK honors `Retry-After` up to 60 s, well inside the 300 s pipeline budget. Say so, or it reads as a hard failure.
     - other `503 + Retry-After` — thermal shed (`:537-548`), or the engine reloading after an idle unload (cross-link feature-22).
     - `429` — **almost always the per-IP rate limiter**: `RATE_LIMIT = 30` per `RATE_WINDOW_MS = 60_000` (`RelaisHttpServer.kt:87-88`), enforced at `:271-280` for every non-`/health` route. HA polls the entity every 60 s **and** can burn up to `MAX_TOOL_ITERATIONS = 10` requests inside one tool-using conversation, all from one source IP. The admission-queue 429 (`:571-576`, carrying `"code":"queue_full"` and `retry_after_seconds`) is the rarer second cause — list the limiter first.
     - config flow won't complete — the 10 s `/v1/models` gate (see the allowlist-fetch note in §7), or TLS trust.
     - entity unavailable then self-heals — `ConfigEntryNotReady`.
  9. **Not supported, and why** — OpenAI Conversation (no base URL; Responses API), Ollama (native API; pending feature-17), auto-discovery (research item 4), plain LAN HTTP (option D).
- **MIRROR:** NAMING_CONVENTION; the operator voice of `docs/RUNBOOK.md`.
- **GOTCHA:** The URL field has **no `cv.url` validation** and normalizes by `rstrip("/")` + append `/v1`. So pasting `…:8443/v1/chat/completions` yields `…/v1/chat/completions/v1` (broken), and omitting the scheme makes yarl read the IP as a *scheme*, yielding `192.168.1.50:/8443/v1` (broken). **Both traps must be in the page verbatim.**
- **VALIDATE:** A reader on HA Container can follow it literally, end to end.

### Task 3 — README "Works with" bullet

- **ACTION:** Append one bullet to the existing list.
- **IMPLEMENT:**
  > - **Home Assistant** — as an Assist conversation agent via the core **LiteLLM** integration
  >   (HA ≥ 2026.8). See [`docs/home-assistant.md`](docs/home-assistant.md); needs the node's cert
  >   trusted, which today means an HA Container/Supervised install.
- **MIRROR:** the voice of `README.md:126-136` — short, honest, "works with," not "depends on".
- **GOTCHA:** The list's closing sentence says *"These are 'works with,' not dependencies. Relais never calls the cloud and is not coupled to any gateway."* The new bullet must not undermine that — and must carry the install-type caveat, or the README becomes the thing that misleads HA OS users.
- **VALIDATE:** Read in context; the caveat survives.
- **SEQUENCING:** feature-17 edits this same list (`feature-17-ollama-compat-api.plan.md:64` cites `README.md:126-135`). Whichever lands second rebases; expect a textual conflict, not a semantic one.

### Task 4 — Test 1: unknown-key tolerance, at the seam that can actually see the keys

- **ACTION:** Pin that a chat body carrying HA's top-level `user` (and other unknown top-level keys) does not disturb parsing.
- **IMPLEMENT:** Two assertions in two different files, because **one function sees the body and the other does not**:
  - **1a (the real one), in `RelaisToolParsingTest.kt`:** call `parseTools(body)` (`RelaisToolParsing.kt:52`) with a body carrying `tools` **plus** `user`, `metadata`, and a nonsense top-level key; assert the returned `List<ToolSpec>` is identical to the same body without them. This is the only JVM-reachable function that takes the whole `JSONObject` body, so it is the only place an unknown top-level key could plausibly be rejected — and therefore the only place a mutation can make this test fail.
  - **1b, in `OpenAiRequestParserTest.kt`:** an unknown **message-level** key (e.g. `{"role":"user","content":"hi","name":"ha"}` plus a nonsense key) inside the `messages` array passed to `buildPromptParts`; assert the parsed parts are unchanged.
- **MIRROR:** TEST_STRUCTURE; BODY_LEVEL_PARSE_SEAM.
- **IMPORTS:** `org.json.JSONObject`, `org.junit.Assert.assertEquals`, `org.junit.Test` — all already used in both suites. `ToolSpec` is same-package.
- **GOTCHA:** **The obvious version of this test is a tautology.** `buildPromptParts` (`RelaisOpenAiParser.kt:118`) takes `messages: JSONArray`, *not* the body — asserting "a body with `user` parses the same as one without" there is passing the same `JSONArray` twice. It is green forever, and the mutation safeguard this plan relies on **cannot fail it either**. Test 1a exists precisely to avoid that. Both assertions are true today by construction, so both go GREEN on first run — the exact shape in which this repo has already shipped two worthless regression tests.
- **VALIDATE:** `./gradlew testFullOpenDebugUnitTest` green; then mutate `parseTools` to reject unknown top-level keys and confirm **1a goes RED**. If a mutation cannot make it fail, the test is worthless — delete it rather than ship it.

### Task 5 — Test 2: the `/v1/models` body is deterministic (**one assertion, not four**)

- **ACTION:** Add the single assertion that is not already shipped.
- **IMPLEMENT:** In `RelaisModelsResponseTest.kt`, assert that two successive `buildModelsResponse(...)` calls with identical inputs produce an identical body — the property the fixed `created` constant (`RelaisHttpServer.kt:2055`) exists to guarantee.
- **MIRROR:** TEST_STRUCTURE.
- **IMPORTS:** `buildModelsResponse` is `internal` (`RelaisHttpServer.kt:2067`) — reachable from the same-module test source set with no visibility change. Verified signature: `buildModelsResponse(refs: List<RelaisModelRef>, fallbackId: String, provisionedIds: Set<String> = emptySet())` (`:2067-2071`), so a two-arg call compiles.
- **GOTCHA:** **Compare `.toString()`, not the objects.** `org.json.JSONObject` does not override `equals()`, so `assertEquals(a, b)` on two response objects compares identity and **fails**. (The JVM suite runs against a real org.json — `Android/src/app/build.gradle.kts:307-309` pulls `org.json:json:20240303`, because the Android stubs return null from `put()`.) Also: **the other three assertions this task originally proposed are already shipped** — `RelaisModelsResponseTest.kt:98-112` already asserts the empty-catalog fallback is exactly one entry with `id` == fallback and `created` == 0, and `:118-131` already asserts `created` on populated entries. Do not re-add them. If the determinism assertion feels too thin to justify a task, **drop Task 5 entirely and record that the coverage already exists** — that is an acceptable outcome, not a gap.
- **VALIDATE:** `./gradlew testFullOpenDebugUnitTest` green; mutation-verify by making `created` read `System.currentTimeMillis()` and confirming RED.

### Task 6 — Cross-links

- **ACTION:** Wire the three sibling plans into the right places.
- **IMPLEMENT:** feature-18 (stable per-node CA — makes the Container/Supervised cert step durable across DHCP churn, but **does not** unblock HA OS); feature-17 (Ollama shim) as a *partial* future path — see the GOTCHA; feature-22 (idle unload) in the 503 troubleshooting row.
- **DO NOT RESTATE THE TRUST INSTRUCTIONS:** feature-18 already owns a docs sweep that creates `docs/tls-trust.md` and touches `SECURITY.md`, `README.md`, and `docs/RUNBOOK.md` (`feature-18-trusted-lan-cert.plan.md:457`, `:706`). This page's cert step should **link** `docs/tls-trust.md` and add only the HA-specific part (the certifi concatenation and the `REQUESTS_CA_BUNDLE` mount). Restating it is the whole thing sequencing after feature-18 was meant to avoid.
- **GOTCHA:** Plan-local feature numbers are **not** GitHub issue numbers. Do not write "#17"/"#18"/"#22". **And do not describe Ollama as merely "waiting on feature-17."** HA's Ollama integration exposes **no auth field** (`feature-17-ollama-compat-api.plan.md:62`: *"Still unusable directly — the integration exposes no auth field"*), and Relais's bearer gate is not being weakened for it. The shim is **necessary but not sufficient**; the answer is a header-injecting reverse proxy or an overlay hop. Promising HA users a second path that will not arrive is the same defect as the HA-OS overclaim this page exists to avoid.
- **VALIDATE:** Links resolve; the feature-18 correction reads the right way round in all its occurrences.

## Testing Strategy

### Unit Tests

| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| **1a** `unknown top-level keys do not disturb tool parsing` (`RelaisToolParsingTest.kt`) | `parseTools(body)` where body = `{tools, user:"conv-1", metadata:{}, zzz_nonsense:1}` vs the same body without the three extras | identical `List<ToolSpec>` | Yes — HA sends `user` on **every** request and `tools` when "Control HA" is on. **The only seam where a mutation can fail this** |
| **1b** `unknown message-level keys are ignored` (`OpenAiRequestParserTest.kt`) | a `messages` array whose entry carries `name` + a nonsense key | identical parsed parts; no throw | Yes — forward compat at the messages level |
| **2** `models response is deterministic` (`RelaisModelsResponseTest.kt`) | two successive `buildModelsResponse` calls, identical inputs | equal **`.toString()`** — `JSONObject` has no `equals()` | Guards the fixed-`created` purity. **The only net-new assertion** |
| ~~`models response is non-empty with an empty catalog`~~ | — | — | **Already shipped** at `RelaisModelsResponseTest.kt:98-112`. Do not re-add |
| ~~`every models entry carries id and created`~~ | — | — | **Already shipped** at `:118-131`. Do not re-add |
| ~~`chat body with a top-level user field parses unchanged` via `buildPromptParts`~~ | — | — | **Cut — tautology.** That function takes the messages array, not the body |

### Edge Cases Checklist

- [ ] `user` present **and empty string** — must not be mistaken for a message field.
- [ ] A body with `stream: false` (what `litellm` actually sends) still returns a full `choices` array — HA raises `HomeAssistantError` on an empty one.
- [ ] A model id in the catalog but not on disk still appears in `/v1/models` — **documented**, not tested away; changing it is out of scope.
- [ ] Every test **mutation-verified**: both tests will go GREEN on first run, which is the dangerous case.

## Validation Commands

```bash
# JVM unit tests — the CI job. Run from Android/src.
cd Android/src
./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest
```

No probe is added, so `:app:compileFullOpenDebugAndroidTestKotlin` is not required by this plan.
`report-worker/` is untouched — no `npm` job applies.

### Manual Validation

```bash
# 1. The endpoint HA's config flow gates on — must answer well inside 10 s.
#    MEASURE IT COLD. A warm curl passes trivially and proves nothing: curatedModels() caches for
#    5 minutes (RelaisModelCatalog.kt:35). Restart the node (or wait >5 min idle) first.
#    Run it a THIRD time with the phone's uplink disabled — failures are not cached
#    (RelaisModelCatalog.kt:151-155), so every offline call re-attempts the fetch.
time curl -k -H "Authorization: Bearer <access-key>" https://<phone-ip>:8443/v1/models

# 2. Exactly what litellm sends: non-streaming, with a top-level `user`.
curl -k https://<phone-ip>:8443/v1/chat/completions \
  -H "Authorization: Bearer <access-key>" \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma-4-e2b-it","user":"conv-smoke",
       "messages":[{"role":"user","content":"reply one word: ping"}]}'

# 3. Prove the cert bundle actually verifies (this is the step HA is really doing).
curl --cacert /path/to/certifi-plus-relais.pem https://<phone-ip>:8443/health

# 4. The two documented URL traps must NOT be what the user pastes.
#    wrong: https://<ip>:8443/v1/chat/completions   -> litellm appends /v1 again
#    wrong: <ip>:8443                                -> yarl reads the IP as a scheme
```

- [ ] In HA: LiteLLM config flow completes; conversation agent added against the **resident** model.
- [ ] Developer Tools → Actions: the `conversation.process` snippet returns a coherent reply.
- [ ] Enable "Control Home Assistant"; confirm one tool round trip.
- [ ] Attach the agent to an Assist pipeline; complete one voice interaction inside the 300 s budget.
- [ ] Add the integration **while the node is still loading**; confirm HA self-heals via `ConfigEntryNotReady`.

## Acceptance Criteria

- [ ] `docs/home-assistant.md` exists and a reader on **HA Container** can go from zero to a working Assist agent by following it literally, including the cert step and both URL traps.
- [ ] The page states plainly, near the top, that **HA OS cannot do this** — and that feature-18 does not change that — offering no fabricated workaround.
- [ ] OpenAI Conversation is documented as **not supported**, with the reason (no base URL; Responses API).
- [ ] The Ollama integration is described as **auth-blocked** — HA's integration has no API-key field, so feature-17's shim is necessary but not sufficient and a header-injecting proxy is the answer. It is **not** described as "coming with feature-17."
- [ ] The #180 model-listing trap is documented using the literal on-screen label **`SERVING`** (`docs/dashboard-copy.md:91`), and the never-provisioned 404 is distinguished from the provisioned-but-not-resident 503-then-works case.
- [ ] The page discloses that `GET /v1/models` can block on an upstream allowlist fetch, and Manual Validation step 1 was measured **cold**.
- [ ] The page is date-stamped and names the HA version it was verified against.
- [ ] Test 1a lives in `RelaisToolParsingTest.kt` against `parseTools(body)` — **not** a body-vs-body comparison in `OpenAiRequestParserTest.kt`, which cannot fail — and is **proven RED** by a mutation. Test 2 is the single determinism assertion, comparing `.toString()`.
- [ ] README carries the "Works with" bullet **with the install-type caveat**.
- [ ] `./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest` green; CI green; independent code review APPROVE on the **final** diff.

## Completion Checklist

- [ ] Patterns followed (docs naming + date-stamp convention, house test structure)
- [ ] Error handling: the troubleshooting table describes the **actual** 401/404/429/503 shapes, including the top-level `retry_after_seconds` sibling
- [ ] Logging: no new log lines; no key, path, or IP anywhere in the new tests
- [ ] Tests written, RED-proven by mutation (both go GREEN on first run — that is the risk)
- [ ] No hardcoded values: the page uses `<phone-ip>` / `<access-key>` placeholders throughout, never a real key
- [ ] Docs updated (the page **is** the deliverable) + README
- [ ] No scope additions — the NOT Building list held; `/v1/models` behavior unchanged
- [ ] Self-contained: no endpoint, no bind, no auth, no TLS change

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| The whole page is version-pinned to HA 2026.9.1 behavior; menu labels and flow fields drift | High | Medium | Date-stamp and name the verified HA version, as `SPIKE-FINDINGS.md` does. Task 1 re-verifies against the live UI |
| Every claim is read off HA source, not observed | Certain (until Task 1) | **High** | Task 1 **is** that verification. **Do not claim the page is validated until it is done** |
| `REQUESTS_CA_BUNDLE` is a foot-gun handed to the user — set wrong, *all* their other integrations lose TLS verification | Medium | **High** | The concatenate-onto-a-copy-of-certifi instruction must be impossible to misread; include the `curl --cacert` verification step |
| The #180 model-listing trap is a genuine UX hazard, not just a doc note — HA users will pick a catalog entry that 404s every turn | High | Medium | Prominent doc warning now; the endpoint-filtering question raised separately |
| A reader concludes from the README bullet that this works on their HA OS box | Medium | Medium | The caveat lives **in the bullet**, not only in the page |
| `litellm` is bronze quality scale and shipped only one release ago; it may change or be superseded | Medium | Medium | Document the `extended_openai_conversation` fallback; keep the version floor explicit |
| A reader takes "`/v1/models` is cheap" at face value and HA's 10 s config-flow gate times out on a cold cache or a flaky uplink | Medium | Medium | The endpoint blocking-fetches the upstream allowlist (`RelaisModelCatalog.kt:120-158`). Disclosed in §7; Manual Validation step 1 is specified **cold** and once **offline** |
| A conformance test is written at a seam that cannot see the keys it claims to pin, and passes forever | **Was certain** — the original Task 4 did exactly this | High | Task 4 now targets `parseTools(body)` (`RelaisToolParsing.kt:52`), the only body-taking JVM-reachable function, and requires a mutation to prove RED. `buildPromptParts` takes the messages array and is explicitly called out |
| Nothing here weakens the node | — | — | No new endpoint, no auth change, no new bind; C1 loopback-only-HTTP preserved and option D explicitly rejected rather than left open |

## Notes

**Decisions made**

- **Target core `litellm`, not OpenAI Conversation.** The latter cannot be pointed anywhere else — no base-URL field, and it drives the Responses API.
- **Be honest that HA OS is excluded**, rather than shipping a workaround that does not exist. This is the single highest-value property of the page.
- **Reject option D (opt-in LAN HTTP) explicitly in the docs**, so the question stops being re-asked in every future planning round.
- **Sequence after feature-18**, so the cert step is written once against a stable per-node CA.
- **Two tests, not zero — but two *assertions*, not six.** The invariants HA depends on are true only by construction, and a future strict-parsing change would break every HA user silently. Most of the surface, though, is **already covered**: `RelaisModelsResponseTest.kt:98-112` and `:118-131` ship four of the six assertions originally proposed. What is left is one unknown-top-level-key test at the right seam and one determinism assertion.
- **Test the body-taking function, not the messages-taking one.** `parseTools(body: JSONObject)` (`RelaisToolParsing.kt:52`) is the only JVM-reachable seam that sees a top-level `user`. `buildPromptParts` (`RelaisOpenAiParser.kt:118`) takes `messages: JSONArray`; the server's `parseOpenAiRequest(body)` (`RelaisHttpServer.kt:1777`) is private on a `Context`-holding class. A "does an unknown top-level key break parsing?" test written against `buildPromptParts` passes the same array twice — green forever, unfailable by mutation.
- **Ollama is auth-blocked, not feature-17-blocked.** HA's Ollama integration exposes no API-key field; a Relais-side shim does not change that.

**Alternatives considered and rejected**

- *`OPENAI_BASE_URL` env-var trick with OpenAI Conversation* — rejected; it would demand Relais implement streaming `POST /v1/responses`.
- *`michelle-avery/openai-compatible-conversation`* — rejected despite the on-the-nose name; open "Broken in Home Assistant 2026.9" issue.
- *Shipping a HACS custom integration with a `zeroconf` manifest entry* — rejected as out of scope, and it is mutually exclusive with the zero-install core path.
- *A reverse proxy on the HA host* — mentioned in one line as an advanced escape hatch; it moves the trust problem one hop and is not our supported surface.

**Findings accepted from review, and the two declined**

Every finding in `critic-21.md` (0 CRITICAL · 4 HIGH · 4 MEDIUM · 2 LOW) was re-verified against
source before being applied; all eight HIGH/MEDIUM findings held and are fixed above. Both LOW
findings were also fixed (the `shedIfHot` snippet is now cited `:537-548` with the KDoc noted at
`:536`; NAMING_CONVENTION no longer cites uppercase `RUNBOOK.md` as an example of the
lowercase-hyphenated convention). **Nothing was declined.**

Worth recording for whoever reviews the next plan from this planner: the failure mode here was **not**
stale line numbers — an independent spot-check of ~15 citations found all of them correct and every
snippet verbatim. It was *semantic overreach from a correct line*: a pure function's determinism
(`:2055`, genuinely true) generalized into an endpoint-latency invariant, and a genuinely lenient
parser (`:90-95`) generalized into a body-level test that cannot be written. Check what a citation is
being used to **argue**, not just that it resolves.

**Open questions for the user**

1. **Should `/v1/models` be filtered to provisioned models only?** It would remove the #180 footgun for HA's dropdown, but the `provisioned` flag exists precisely so callers can see the difference — it is a client-visible behavior change with its own compat risk. Decide separately; **not** in a docs PR.
2. **Is an HA instance available for Task 1?** Everything downstream is unverified until it is, and the plan should not merge claiming otherwise.
3. **Does the README bullet belong before or after feature-17 ships?** Once an Ollama shim exists, the core `ollama` integration becomes a second (and for HA OS users, still cert-blocked) path, and the bullet will want rewording.

## Deferred gate (document in the PR, do **not** fake)

On a real Home Assistant instance (Container preferred), against a live node: complete the LiteLLM
config flow; confirm `/v1/models` answers inside 10 s; run the `conversation.process` snippet and get
a coherent reply; enable "Control Home Assistant" and confirm a tool round trip; attach the agent to
an Assist pipeline and complete one voice interaction inside the 300 s budget. Also confirm the
self-heal claim by adding the integration **while the node is still loading** and verifying HA
connects on its own. Owner runs later; do not claim it as done in the PR.

---

## Report

- **File:** `.claude/PRPs/plans/feature-21-home-assistant.plan.md`
- **Complexity:** Small (code) / Medium (research + live verification)
- **Scope:** 5 files (1 CREATE doc, 1 UPDATE README, 3 UPDATE tests), 6 tasks
- **Key Patterns:** the body-taking parse seam `parseTools(body: JSONObject)` (`RelaisToolParsing.kt:52`) vs the messages-taking `buildPromptParts` (`RelaisOpenAiParser.kt:118`) — the distinction the tests turn on; the `/v1/models` handler chain (`RelaisHttpServer.kt:402` → `:906-911` → `RelaisModelCatalog.kt:108`/`:115`/`:120-158` → `buildModelsResponse` `:2067-2081`); the canonical `RelaisError.json` + `Retry-After` 503 shape (`RelaisHttpServer.kt:537-548`)
- **External Research:** HA core 2026.9.1 — `litellm` integration (`CONF_URL`/`CONF_API_KEY`, `GET {url}/models` 10 s gate, non-streaming, top-level `user`), `openai_conversation` (no base URL, Responses API), `extended_openai_conversation` HACS fallback, `ollama` (native API only → depends on feature-17), and the `cafile = environ.get("REQUESTS_CA_BUNDLE", certifi.where())` replace-not-add behavior
- **Top Risk:** Every claim is read off HA source rather than observed — Task 1 is the verification, and the page must not merge claiming validation it does not have. (Independent review has since confirmed the *external* HA research; the risk that actually materialized was on the internal-code side, in the two test tasks, both now rewritten)
- **Confidence Score:** 6/10 (revised down from 7 after review. The docs deliverable — the actual value — is sound and its HA research is now independently verified rather than merely asserted; the deduction is for the two test tasks, one of which could not pin what it claimed and the other of which was nearly all already shipped)

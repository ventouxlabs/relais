# Plan: Web Dashboard — Model Switch & Browser Auth (feature-09, refresh)

> **Refreshed 2026-09-06 against main `1276a351`** (`1276a351d0cda639fa6a123eaf403e6837cb080e`,
> "Repo glowup branding + Play Gate 1 clearance (#309)"). Every line number below was read on that
> commit. Re-verify with `wc -l` / `grep -n` if `main` has moved.
>
> **Revised 2026-09-07 against the `critic-09` adversarial review** (1 CRITICAL, 4 HIGH, 5 MEDIUM,
> 2 LOW). Every finding was re-verified against source before being accepted; the disposition of each
> is recorded in Notes → *Critic findings disposition*. Two findings are fixed **differently** from
> the critic's suggested wording because the suggested wording was itself wrong — see H1 and H3 there.

## Summary

The auth-gated `GET /` HTML dashboard is **already shipped** as a read-only page; this plan covers
only the two pieces its own source comment defers: the **model-switch form** and **browser-reachable
auth**. It adds `POST /select-model` — validating against the provisioned-model registry, re-checking
the same runtime-compat gate the request path gets, and persisting through `ModelSwitch` — and teaches
`authorized()` to accept HTTP Basic so a browser can actually load the page, with a `Sec-Fetch-Site`
guard on every Basic-authenticated request to replace the CSRF immunity that `Bearer`-only gave us.

**Scope discipline added in this revision:** the plan no longer relocates the shipped handlers. Only
the new `handleSelectModel` lives in the new file, and it takes primitives rather than the private
`RequestContext`, so this feature needs **zero** visibility changes to `RelaisHttpServer.kt`.

## User Story

**As an** operator running a Relais node on my LAN,
**I want** to open the node's dashboard in a normal browser and switch the served model from it,
**So that** I can check status and re-point the node without adb, curl, or physical access to the phone.

## Problem → Solution

| Problem | Solution |
|---|---|
| The dashboard page **cannot be opened in a browser at all** — the gate requires `Authorization: Bearer`, which no browser sends on a navigation (`RelaisHttpServer.kt:266-272`, `:1901-1905`) | Accept `Basic base64(user:key)` in `authorized()`; challenge with `WWW-Authenticate` for HTML clients only |
| Basic credentials are **ambient** — the UA re-attaches them per-origin with no script involved — so accepting Basic removes the CORS-preflight barrier that makes today's `Bearer`-only API CSRF-immune | `Sec-Fetch-Site` guard applied at the gate to **every** Basic-authenticated request, not just `/select-model`. `Bearer` requests keep today's behaviour, so no SDK regresses |
| The page is read-only; switching models requires the on-device Configure screen | `POST /select-model` → `RelaisEngine.ensureModelSwapInBackground` (`RelaisEngine.kt:409`, sole production caller today at `RelaisHttpServer.kt:1171`) |
| The **targeted** swap path has no compat gate — `resolveModel` (and its `refuseIfIncompatible`) is skipped when `target != null` (`RelaisEngine.kt:427-430`), so a dropdown click could load a known-bad model and take the node down on first inference with nothing to roll back | Filter the dropdown **and** re-check server-side in `handleSelectModel`, both on `RelaisRuntimeCompat.incompatibleReason` — the same predicate `rejectIfModelUnavailable` passes in at `:1155` |
| A second `SET MODEL` mid-swap would persist the new id while `ensureModelSwapInBackground` no-ops on its `swapDispatching` CAS (`RelaisEngine.kt:410`), leaving config ahead of the engine and answering `303` as if it worked | Make the swap function **return whether it won the CAS**, dispatch *before* persisting, and persist only on `true`; answer `503 + Retry-After` otherwise |
| Status is a point-in-time snapshot; operators re-load by hand | `<meta http-equiv="refresh" content="10">` — survives the scriptless CSP |
| Dashboard page-loads write themselves into the 20-slot request log (`:717` + `:1880`), which auto-refresh would turn into an all-`/` panel | `recordRequest(..., inRecentLog = false)` from the dashboard handler |

## Metadata

- **Complexity:** Medium-High (was Medium — the compat gate, the swap race, and the gate-wide CSRF guard are all correctness work, not rendering work)
- **Source PRD:** N/A
- **PRD Phase:** N/A
- **Estimated Files:** 12 (2 created, 10 updated), plus a `.claude/HANDOFF.md` section
- **Ships as two PRs** (O4, adopted): **PR-A** = Tasks 1-3 (auth + refresh + log hygiene); **PR-B** = Tasks 4-10 (the selector). PR-A is the security-sensitive half and gets a minimal blast radius for the security reviewer.
- **Blocked on:** all of `feature-18-trusted-lan-cert` landing first — see *Dependencies & Cross-plan Sequencing*.

## UX Design

### Before — `GET /` in a browser

```
┌──────────────────────────────────────────┐
│  {"error":{"message":"unauthorized",     │   ← the page is unreachable.
│   "type":"authentication_error"}}        │     No prompt, no way in.
└──────────────────────────────────────────┘
```

### After — `GET /` in a browser

```
┌──────────────────────────────────────────┐
│  Sign in to "Relais"                     │   ← Basic challenge (HTML clients only)
│  Username [            ]  ← leave blank  │
│  Password [••••••••••••]  ← the node key │
└──────────────────────────────────────────┘
              ↓
┌──────────────────────────────────────────┐
│ ┌─ Node Status ────────────────────────┐ │
│ │ status                      ● LIVE   │ │
│ │ model       litert-community/gemma…  │ │
│ │ thermal                       NONE   │ │
│ │ decode                 11.40 tok/s   │ │
│ │ uptime                    2h 5m 30s  │ │
│ │ queue depth                      0   │ │
│ │ errors total                     3   │ │
│ │ shed total                       0   │ │
│ │ switch model                         │ │  ← NEW
│ │ [ litert-community/gemma-4-E2B  ▾ ]  │ │
│ │ [       SET MODEL       ]            │ │
│ │ model set: …E2B — swapping, node     │ │  ← NEW, pending hint;
│ │ restarts itself                      │ │     only while config ≠ resident
│ └──────────────────────────────────────┘ │
│ ┌─ Recent Requests ────────────────────┐ │
│ │ endpoint           status      age   │ │
│ │ /v1/chat/completi…    200   4s ago   │ │  ← no `/ 200` rows any more
│ └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
   auto-refreshes every 10s
```

**This mock depicts the page as it will actually ship**, i.e. the *shipped* chrome (lowercase labels,
`Node Status` title-case panel heading, `%.2f tok/s`, `Ns ago`) plus this plan's additions. An earlier
draft drew a restructured page — CAPS labels, a separate `MODEL` panel with a `SERVING` row, a
tagline, a combined `QUEUE / ERRORS / SHED` row — none of which any task implements. Those deltas
between the shipped page and `docs/dashboard-copy.md`'s Appendix are **real and still open**, but they
predate this plan and are tracked separately (Task 9, M1). Do not read the mock as authorising them.

### Interaction Changes

| Touchpoint | Before | After | Notes |
|---|---|---|---|
| `GET /` from a browser | `401` JSON, no prompt | Basic challenge → page renders | Only when `Accept:` contains `text/html` |
| `GET /` from curl/SDK | `401` JSON, bare | **Unchanged** — no `WWW-Authenticate` | Deliberate; an unconditional challenge worsens every SDK's error path |
| `Authorization: Bearer` | Accepted | **Unchanged** | Basic is additive; the `Sec-Fetch-Site` guard does not apply to Bearer |
| `Authorization: <rawkey>` (no scheme) | **Accepted** — `removePrefix` returns the receiver unchanged when the prefix is absent | **Rejected** | Wire-visible tightening. Undocumented everywhere; every doc and the README specify `Bearer`. Decided, not open — see Notes → Decisions |
| Cross-site request carrying cached Basic creds | n/a (Basic not accepted) | `403` | `Sec-Fetch-Site` ∈ {`cross-site`, `same-site`}. `none` (address bar / bookmark) and `same-origin` are allowed |
| Page freshness | Manual reload | Auto every 10s | Meta refresh; page stays scriptless |
| RECENT REQUESTS panel | Includes every `/` self-load | No `/ 200` rows | The un-credentialed challenge `401` on `/` still records — it goes through the shared `reply()` (`:223-226`), not the dashboard handler |
| Model switch | On-device Configure screen only | `SET MODEL` on the page | Hot swap — **no app restart** |
| Node during switch | n/a | `STARTING`, form disabled, pending hint shown | `startupInProgress` for the lock; config-vs-resident id for the hint |
| Second `SET MODEL` mid-swap | n/a | `503 + Retry-After: 25`, **nothing persisted** | Mirrors the existing swap-busy answer at `:1176-1177` |

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisDashboard.kt` | 28-54, 76-112, 143-149, **160**, 163-320 | The shipped page. `:160` is the comment that defers exactly this plan's scope |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 235-262, 266-272, 291-295, 714-747, 1901-1905 | Header parse loop, auth gate, route table, `handleDashboard`, `authorized()` |
| **P0** | `.../RelaisHttpServer.kt` | 1138-1180 | `rejectIfModelUnavailable` — the hot-swap path to reuse (`incompatibleReason` wired in at `:1155`, `provisionedIds` at `:1160`, the swap dispatched at `:1171`), and the registry safety boundary at `:1128-1131` |
| **P0** | `.../RelaisRuntimeCompat.kt` | 86, 119-138 | **The compat gate the targeted swap path skips.** `loadability` derives `INCOMPATIBLE` *only* from `KNOWN_INCOMPATIBLE` (`:121`), and `incompatibleReason` is `KNOWN_INCOMPATIBLE[id]` (`:131`) — so `!isOfferable(id)` and `incompatibleReason(id) != null` denote the **same set**. Use `incompatibleReason` on both the filter and the re-check: symmetric by construction, and it hands you the reason string for the 400 body |
| **P0** | `.../ModelSwitch.kt` | 19-45 | *"The single source of truth for 'the operator picked a model'… MUST persist through here so they can't drift."* Two surfaces route through it today; the dashboard becomes the third. `applyManualId` (`:42-45`) does `clearModelRef` **unconditionally**, which `RelaisConfig.setModelId` does not (`RelaisConfig.kt:205` drops a ref only when its `modelId` *differs*) |
| **P0** | `.../RelaisEngine.kt` | 409-413, 427-434, 442-473 | The swap. The guard is the **`swapDispatching` CAS at `:410`**, not `startupInProgress` (which is merely *set* at `:413`). `target != null` skips `resolveModel` entirely (`:427-430`) — that is why C1 exists. The rollback at `:452-465` covers engine-**create** failures only |
| **P1** | `.../RelaisModelSwap.kt` | 24-32, 77-80, 100-121 | The eligibility rule to mirror. `requested == configuredModelId || requested in provisionedModelIds` (`:107`) — `configuredModelId` is eligible on its own *"so the operator's current selection works before it has been recorded"* (`:79-80`). **Check order is deliberate:** membership first, compat second (`:116-119` records why the reverse was a bug) |
| **P1** | `.../RelaisModelRegistry.kt` | 72, 106 | `provisionedIds(...): Set<String>` — **a `Set`, not a `List`**; `swapTargetFor(id, provisioned): ProvisionedModel?` |
| **P0** | `docs/dashboard-copy.md` | all (esp. §1.4 L85-96, §2.4 L199-210, §2.7 L232-237, Appendix L258-272 — the file is 272 lines) | **Source of truth for every user-visible string.** Copy literals verbatim |
| **P0** | `DESIGN.md` | 24-38 (Color), 40-46 (Type), 56-60 (Motion) | Amber `#FFB000` on `#0B0B0D`, monospace, dark-only, one accent |
| **P1** | `.../RelaisMetrics.kt` | 72-73, 113-137 | Ring buffer (`REQUEST_LOG_CAPACITY = 20`), `recordRequest`, `recentRequests()` |
| **P1** | `.../RelaisHttpServer.kt` | 218-232 | `reply()` / `replyBytes()` — they record **unconditionally** (`:224`, `:230`) and already accept `headers: List<String> = emptyList()`, so `WWW-Authenticate` needs no new seam. This is also why the challenge `401` on `/` still lands in the ring buffer (M4) |
| **P1** | `Android/src/app/src/test/java/cc/grepon/relais/RelaisDashboardTest.kt` | 21-24, 60-93, 99-118, **341** | Test idiom + the fixture builders. `:341` asserts *no* form — it must be inverted |
| **P1** | `SECURITY.md` | 12-29 | LAN is HTTPS-only (`:8443`); plaintext is loopback-bound — why Basic is safe |
| **P2** | `.../RelaisExperiments.kt` | 67, 167, 224-253 | The other HTML page — **not touched by this plan**; its in-page key input is unreachable today (R1) and Basic relieves that incidentally |
| **P2** | `.../RelaisConfigureActivity.kt` | 296, 306 | The *config-set* path's "Restart to apply" — **different semantics, do not copy** |

**Visibility facts that decide the seam** (all in `RelaisHttpServer.kt`, all verified on `1276a351`):
`RequestContext` is `private class` (`:690`) · `provisionedOnDisk()` is `private fun` (`:1119`) ·
`readBody` (`:1913`), `respondText` (`:1937`), `respondBytes` (`:1946`) are all private. **This plan
changes none of them** — see Task 8's seam. `RelaisEngine.startupInProgress` (`:237`) and
`RelaisEngine.residentModelId` (`:301`) are both public and readable.

## External Documentation

| Topic | Source URL | Key Takeaway |
|---|---|---|
| CSP directive list | https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Security-Policy | No directive governs `<meta http-equiv="refresh">` |
| `form-action` | https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Security-Policy/form-action | Restricts form POST targets; **not** covered by `default-src` |
| HTTP Basic | https://developer.mozilla.org/en-US/docs/Web/HTTP/Authentication | `WWW-Authenticate: Basic realm=…` triggers the browser prompt; credentials re-sent per-origin |
| `Sec-Fetch-Site` | https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Sec-Fetch-Site | Browser-set, unforgeable by page JS; absent on non-browser clients |
| POST/Redirect/GET | https://en.wikipedia.org/wiki/Post/Redirect/Get | `303` after a state change prevents re-POST on reload |

**Research item 1 — meta refresh vs. inline script**
- `KEY_INSIGHT:` CSP has no meta-refresh directive, so auto-refresh needs **no** CSP relaxation and keeps `script-src` absent.
- `APPLIES_TO:` Task 3.
- `GOTCHA:` Refresh resets scroll and discards any typed `<select>` choice. Acceptable on a readout; it is also why the interval is 10s, not 3s.

**Research item 2 — `form-action` is not covered by `default-src`**
- `KEY_INSIGHT:` `form-action` does **not** fall back to `default-src`. The dashboard's current CSP (`RelaisHttpServer.kt:741`) omits it entirely, so adding a form silently leaves submissions unrestricted.
- `APPLIES_TO:` Tasks 6, 8.
- `GOTCHA:` `/experiments` already sends `form-action 'none'` (`:767`). The dashboard needs `'self'`, **not** `'none'` — `'none'` would block the new form.

**Research item 3 — Basic auth over the LAN listener**
- `KEY_INSIGHT:` Basic base64 is encoding, not encryption — but `SECURITY.md:14-18` confirms the LAN listener is TLS-only on `:8443` and plaintext is bound to `127.0.0.1:8080`, so the key never crosses the network in the clear.
- `APPLIES_TO:` Task 4.
- `GOTCHA:` The self-signed cert interstitial appears **before** the auth prompt. Document it (R2); do not engineer around it.

**Research item 4 — `Sec-Fetch-Site` has four values, and two of them must be allowed**
- `KEY_INSIGHT:` Browser-set and unforgeable by page JS. Four values: `same-origin` (a request from the node's own page), `none` (**user-initiated from browser UI — address bar, bookmark, a typed URL**), `same-site`, `cross-site`. curl and SDKs send it never.
- `APPLIES_TO:` Task 3 (the gate) and Task 8.
- `GOTCHA:` **Reject only `cross-site` and `same-site`. Allow `none` and `same-origin`, and allow absent.** The obvious-looking rule "reject when present and ≠ `same-origin`" is **wrong and would break the feature**: typing `https://<phone-ip>:8443/` into the address bar sends `Sec-Fetch-Site: none`, so that rule `403`s the very first load of the dashboard. Allowing `none` costs nothing — an attacker's page cannot cause a request to be labelled `none`; that value is reserved for navigations the *user* initiated from browser chrome. Treating **absent** as reject would break every API client, since the header is not parsed today (grep: zero occurrences before this change).

**Research item 5 — Basic converts an explicit credential into an ambient one (the reason for item 4)**
- `KEY_INSIGHT:` Today the API is effectively CSRF-immune *by accident of the carrier*: a `Bearer` header must be set by script, and a cross-origin request carrying a custom `Authorization` header triggers a CORS preflight this server fails — there is not one `Access-Control-*` header in the tree (grep-confirmed). Browser-cached **Basic** credentials are re-attached by the UA itself, with no script and no preflight.
- `APPLIES_TO:` Task 3; risk R3.
- `GOTCHA:` `Content-Type` is never enforced on the JSON routes — `contentType` is parsed (`RelaisHttpServer.kt:259`) but read only for multipart boundaries (`:443`, `:624`). So a cross-site **simple** POST (`text/plain`, no preflight) reaches `handleOpenAi` and the rest. Impact is capped at *side effects only* — without CORS the response is opaque, so nothing is exfiltrated — but unmetered inference runs, `POST /v1/rag/documents` corpus injection, `/v1/sessions` mutation and `/v1/batch` job creation are all real. This is why the guard goes **at the gate for every Basic-authenticated request**, not on `/select-model` alone.

## Patterns to Mirror

**NAMING_CONVENTION** — pure render/assemble functions are top-level in a `Relais*.kt` file, `internal` when only tests need them:

```kotlin
/**
 * Maps Android [android.os.PowerManager] THERMAL_STATUS_* integers (0..6) to human labels.
 * Out-of-range values return "UNKNOWN" without throwing — defensive against future OS extensions.
 * Pure function; internal visibility (tested via [RelaisDashboardTest]).
 */
internal fun thermalLabel(status: Int): String =
  when (status) {
    0 -> "NONE"
    1 -> "LIGHT"
    // …
    else -> "UNKNOWN"
  }
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisDashboard.kt:114-129
```

**ERROR_HANDLING** — every error is a `RelaisError.json(message, type)` envelope with an explicit status; the gate returns early:

```kotlin
// Health is open; everything else needs the API key + is rate-limited per client IP.
if (!(method == "GET" && path.startsWith("/health"))) {
  if (!authorized(authorization)) {
    reply(401, RelaisError.json("unauthorized", RelaisError.AUTHENTICATION))
    return
  }
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:265-270
```

**AUTH_PATTERN** — one private predicate, one credential, constant-time compare. Task 4 extends *this exact function* and must not add a length check or an early return before `MessageDigest.isEqual`:

```kotlin
  private fun authorized(header: String?): Boolean {
    val token = header?.removePrefix("Bearer ")?.trim() ?: return false
    // Constant-time compare to avoid leaking the key via response-timing differences.
    return MessageDigest.isEqual(token.toByteArray(), apiKey.toByteArray())
  }
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:1901-1905
```

Note the shape Task 3 must preserve: the *scheme parse* is the only nullable step (`?: return false` today, `?: return null` after), and every non-null token reaches the same single comparison. Pulling the scheme parse out into a pure `internal fun extractApiKey(header: String?): Pair<AuthScheme, String>?` keeps that property — the Basic branch base64-decodes and strips `user:` there, then hands one candidate back to the unchanged compare. `authorized()` then returns `AuthScheme?` rather than `Boolean` — `null` for unauthenticated, the scheme for authenticated — so the gate can decide whether the `Sec-Fetch-Site` guard applies without re-parsing the header. It still does exactly one `MessageDigest.isEqual` and nothing else.

Two things this shape must **not** acquire: a length check or an early `return false` before the compare (that is the timing signal R6 exists to prevent), and a per-route branch (H1's whole point is that the guard lives at the one gate).

**LOGGING_PATTERN** — file-private `TAG`, `Log.e` for genuine faults, `Log.w` for swallowed ones. No request bodies, keys, or IPs:

```kotlin
private const val TAG = "RelaisHttpServer"
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:68

        Log.e(TAG, "Request handling error", e)
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:500

      .onFailure { Log.w(TAG, "session record failed (swallowed)") }
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:1541
```

**HANDLER_PATTERN** — one `when` arm per route delegating to a private `handleX(ctx)`; the handler records its own metric and calls `respondText` with `extraHeaders`:

```kotlin
          method == "GET" && path == "/" -> handleDashboard(ctx)

          method == "GET" && path == "/experiments" -> handleExperiments(ctx)
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:293-295

  private fun handleDashboard(ctx: RequestContext) {
    RelaisMetrics.recordRequest(ctx.endpoint, 200)
    val metricsJson = RelaisMetrics.renderJson(context)
    // …
    respondText(
      ctx.sock, 200, renderDashboardHtml(dashStatus), "text/html; charset=utf-8",
      listOf(
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
        "X-Content-Type-Options: nosniff",
        "X-Frame-Options: DENY",
        "Referrer-Policy: no-referrer",
      ),
    )
  }
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:714-747 (elided)

  private fun respondText(
    sock: java.net.Socket,
    status: Int,
    body: String,
    contentType: String,
    extraHeaders: List<String> = emptyList(),
  ) = respondBytes(sock, status, body.toByteArray(), contentType, extraHeaders)
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:1937-1943
```

**TEST_STRUCTURE** — JUnit4, plain `org.junit.Assert.*`, backtick names, numbered comment-banner sections, shared fixture builders, assertion messages on booleans:

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
// SOURCE: Android/src/app/src/test/java/cc/grepon/relais/RelaisDashboardTest.kt:21-24

  // ---------------------------------------------------------------------------
  // 1. Status-label mapping
  // ---------------------------------------------------------------------------

  @Test
  fun `engineReady true and not starting yields LIVE and live=true`() {
    val s = liveStatus()
    assertEquals("LIVE", s.statusLabel)
    assertTrue("live must be true when engine is ready", s.live)
  }
// SOURCE: Android/src/app/src/test/java/cc/grepon/relais/RelaisDashboardTest.kt:95-104
```

**PROBE_STRUCTURE** — `assumeTrue`-gated on `RELAIS_PROBE=1`, the runnable `adb` line in the file header, a loopback `RelaisHttpServer` on a high port:

```kotlin
/**
 * On-device probe for the Feature #11 `GET /v1/clientconfig` endpoint. DEFERRED — no device was
 * connected this session, so this is documentation of the intended check rather than a CI gate. It
 * is `assumeTrue`-gated (skips unless RELAIS_PROBE=1 is passed) so it never runs in the JVM unit
 * lane or unattended CI.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.ClientConfigEndpointProbe \
 *     -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ClientConfigEndpointProbe {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()
  private val port = 18099 // high loopback port unlikely to collide with the real :8080 listener
  private var server: RelaisHttpServer? = null

  @Before
  fun setUp() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    server = RelaisHttpServer(context, port = port, tls = false, bindAddr = "127.0.0.1").also { it.start() }
// SOURCE: Android/src/app/src/androidTest/java/cc/grepon/relais/ClientConfigEndpointProbe.kt:35-60
```

## Files to Change

| File | Action | Justification |
|---|---|---|
| `.../main/java/cc/grepon/relais/RelaisHttpPages.kt` | **CREATE** | Home for **`handleSelectModel` only**, written against primitives (no `RequestContext`) — CLAUDE.md forbids growing the 2202-line server. The shipped handlers stay put; see Task 8 and H2 in Notes |
| `.../main/java/cc/grepon/relais/RelaisDashboard.kt` | UPDATE | Extend `DashboardStatus` (+3 fields), render the form + pending hint + meta refresh, add the pure form parser and validator |
| `.../main/java/cc/grepon/relais/RelaisHttpServer.kt` | UPDATE | `/select-model` route arm + `endpointLabel` entry; parse `sec-fetch-site`; extend `authorized()` to report its scheme; gate-level Basic CSRF guard; conditional `WWW-Authenticate`; pass the new `assembleDashboardStatus` arguments. **No visibility changes** |
| `.../main/java/cc/grepon/relais/RelaisEngine.kt` | UPDATE | `ensureModelSwapInBackground` returns `Boolean` (won the `swapDispatching` CAS) — H3 |
| `.../main/java/cc/grepon/relais/ModelSwitch.kt` | UPDATE | KDoc only: name the dashboard as the third surface that persists through here — H4 |
| `.../main/java/cc/grepon/relais/RelaisMetrics.kt` | UPDATE | `recordRequest(inRecentLog: Boolean = true)` |
| `.../test/java/cc/grepon/relais/RelaisDashboardTest.kt` | UPDATE | Invert `:341`; add form/lock/pending-hint/escaping/parser/validator tests |
| `.../test/java/cc/grepon/relais/RelaisHttpAuthTest.kt` | **CREATE** | Basic/Bearer/bare-key parsing and the `Sec-Fetch-Site` predicate, isolated from dashboard concerns. **This is the first JVM coverage `authorized()` has ever had** (grep-confirmed: the only `Bearer` assertions in the unit lane are `RelaisExperimentsTest.kt:143/188/225/255`, and those assert on a rendered page, not the gate) |
| `.../test/java/cc/grepon/relais/RelaisMetricsIncrementsTest.kt` | UPDATE | Ring-buffer opt-out |
| `docs/dashboard-copy.md` | UPDATE | Amend §1.4 stale "restart to apply" (O1); **add** entries for the four new strings; strike already-satisfied Appendix rows |
| `SECURITY.md` | UPDATE | Basic as an accepted carrier, why TLS makes it safe, the `Sec-Fetch-Site` guard, and the bare-key tightening |
| `docs/RUNBOOK.md` | UPDATE | Operator steps to open the dashboard in a browser |

## Dependencies & Cross-plan Sequencing

**Build order: all of `feature-18-trusted-lan-cert` lands before any of feature-09.** These plans
overlap on three surfaces and feature-18's are the load-bearing ones.

| Sibling | Overlap | Resolution |
|---|---|---|
| **`feature-18-trusted-lan-cert`** | Its P0 reading pins `handleDashboard` at **714-747** and `respondText` at **1933-1943**, and it adds a Certificate panel to `renderDashboardHtml` (its Files-to-Change; task at its `:660-669`) | **feature-18 first.** This is the main reason the handler extraction (this plan's *original* Task 1) is cut: leaving `handleDashboard` at `:714-747` keeps every one of feature-18's line references valid. feature-09 then rebases its `renderDashboardHtml` edits onto the version with the cert panel |
| **`feature-18`**, CSP | Its acceptance criterion (its `:872`) is *"the CSP at `RelaisHttpServer.kt:741` is **unchanged**"*, and its Task GOTCHA (`:667`) says do not touch it | **Not a conflict — scope the rule correctly.** feature-18's "do not touch" is a constraint on *feature-18's own diff* (it has no QR and no data-URI images, so it must not relax the CSP to get them). feature-09 is the plan that legitimately amends `:741`, adding **only** `form-action 'self'`. Record this in the PR description so a later reader does not score it as breaking a merged rule |
| **`feature-17-ollama-compat-api`** | Its Files-to-Change specifies `RequestContext` `private`→`internal` + a `withReply` helper on `RelaisHttpServer.kt` | **feature-17 owns that widening outright.** feature-09 now needs none, so there is nothing to reconcile. Do not make the same change a second way |
| **`feature-22-idle-unload`** | Task 5 adds `NodeState.IDLE` and calls `assembleDashboardStatus` a *third* health derivation currently "being reworked by the feature-09 dashboard plan" (its `:469`, `:615`, `:636`) | feature-09 adds three fields to `assembleDashboardStatus` but does **not** touch its `statusLabel` derivation (`RelaisDashboard.kt:92-96`). Either order works; whichever lands second rebases. Flag to whoever sequences them — feature-22 explicitly asks the question at its `:636` |
| **`feature-19` / `-20`** | Both UPDATE `RelaisMetrics.kt` (histograms, counters) | Textually adjacent to Task 1's `recordRequest` signature change, semantically independent. Sequence, don't merge simultaneously |

## NOT Building

- **No fix for `/experiments`' own design** beyond the incidental reachability Basic gives it (R1 is filed separately).
- **No cookie/session auth**, no login form, no logout, no key rotation UI.
- **No JavaScript** on `/` — no live-updating counters, no fetch polling, no WebSocket/SSE.
- **No new metrics, charts, sparklines, or history** beyond the existing 20-entry buffer.
- **No model download/provisioning** from the web page — the selector offers only already-provisioned models.
- **No pagination or "show more"** on the request log (copy §2.6 bounds it at ~20).
- **No `MEMORY` row** (copy §1.3 reserves it, explicitly out of v1).
- **No light theme** (`DESIGN.md:38` — dark only).
- **No AICore path changes.**
- **No handler relocation.** `handleDashboard` (`:714-747`) and `handleExperiments` (`:749-771`) stay exactly where they are — cut deliberately, see H2 in Notes and *Dependencies & Cross-plan Sequencing*.
- **No visibility widening in `RelaisHttpServer.kt`.** `RequestContext`, `readBody`, `respondText`, `respondBytes`, `provisionedOnDisk` all stay `private`. feature-17 owns the `RequestContext` widening.
- **No font-stack change.** Dropped: `DESIGN.md:41` specifies bundled `FontFamily.Monospace`, "no font download", and naming four desktop families Android does not have would be an unapproved deviation for an effect that is inert anyway (CSP `default-src 'none'` blocks `font-src`, and every name falls through to `monospace`).
- **No normalization of the shipped page against `docs/dashboard-copy.md`'s Appendix** — the eight open deltas (title case, CAPS labels, `%.1f` vs `%.2f`, `AGE`/`Ns ago`, empty-state wording, the tagline, the styled header dot, the `MODEL`/`SERVING` panel restructure) predate this plan and are filed separately in Task 9.
- **No CORS headers.** Adding `Access-Control-*` would *remove* the preflight barrier described in research item 5. The server has none today and must keep none.

## Step-by-Step Tasks

> **PR-A = Tasks 1-3. PR-B = Tasks 4-10.** Task 3 is the security-sensitive change; keeping it in a
> small PR is O4, adopted.

### Task 1 — Stop the dashboard polluting its own request log

- **ACTION:** Add an opt-out to `RelaisMetrics.recordRequest`.
- **IMPLEMENT:** `fun recordRequest(endpoint: String, status: Int, inRecentLog: Boolean = true)`. Keep the `requestCounts` increment and the `errorsTotal` bump **unconditional**; guard only the `synchronized(requestLogLock)` append. Pass `inRecentLog = false` from `handleDashboard`.
- **MIRROR:** existing body of `recordRequest`, `RelaisMetrics.kt:113-125`.
- **IMPORTS:** none.
- **GOTCHA:** `endpointLabel` maps `/` → `/` (`RelaisHttpServer.kt:1880`) and the buffer holds **20** (`RelaisMetrics.kt:72`), so at a 10s refresh the panel becomes all-`/` in ~3.5 minutes. Do **not** filter `/` inside `recentRequests()` instead — that would also hide a genuine `401`/`429` on `/`. Default `true` keeps all other call sites unchanged. **This is a reduction, not an elimination** (M4): the shared `reply()` records unconditionally at `:224`, so the challenge `401` a browser gets on its *first* load of `/`, and every re-authentication after, still lands in the buffer. That is correct — an operator wants to see failed auth attempts. Only the `200` self-loads are suppressed.
- **VALIDATE:** test #9.

### Task 2 — Auto-refresh

- **ACTION:** Add `<meta http-equiv="refresh" content="10">` to the `<head>` in `renderDashboardHtml`.
- **IMPLEMENT:** Insert after the viewport meta (`RelaisDashboard.kt:204`). That is the whole change.
- **MIRROR:** the existing `<head>` block, `RelaisDashboard.kt:203-205`.
- **IMPORTS:** none.
- **GOTCHA:** The scriptless test (`RelaisDashboardTest.kt:306`) must stay green — meta refresh adds no `<script>`. No CSP change is needed or permitted here. **The font-stack swap an earlier draft bundled into this task is dropped** — `DESIGN.md:41` mandates bundled `FontFamily.Monospace` with no font download, so naming desktop families would be an unapproved deviation (and an inert one: `default-src 'none'` blocks `font-src` and every name falls through to `monospace`). Leave `font-family: monospace` at `:215` alone.
- **VALIDATE:** test #5 — **plus one thing only a real browser can answer.** Each 10s refresh is a Basic-authenticated navigation that now runs Task 3's `Sec-Fetch-Site` guard, and what a browser sends on a meta-refresh reload is not knowable from this repo's source. `same-origin` is expected and fine; `none` and absent are also fine under the rule as written; **`same-site` would make the page 403 itself every ten seconds**. Confirm in the manual browser check before believing the unit tests — none of them exercise a real navigation.

### Task 3 — Accept HTTP Basic, and replace the CSRF immunity it removes

- **ACTION:** Extend `authorized()` (`:1901-1905`) to accept Basic **and report which scheme succeeded**; add the gate-level `Sec-Fetch-Site` guard for Basic; emit a conditional challenge on 401.
- **IMPLEMENT:** Three pieces.
  1. `internal enum class AuthScheme { BEARER, BASIC }` and a pure `internal fun extractApiKey(header: String?): Pair<AuthScheme, String>?` — `Bearer <k>` → `(BEARER, k)`; `Basic <b64>` → base64-decode, take the substring **after the first `:`** (username ignored) → `(BASIC, k)`; **anything else, including a bare scheme-less key, → `null`**.
  2. **`authorized()` changes its return type from `Boolean` to `AuthScheme?`** — `null` means *not authenticated*, a non-null value is *authenticated via that scheme*:
     ```kotlin
     private fun authorized(header: String?): AuthScheme? {
       val (scheme, token) = extractApiKey(header) ?: return null
       // Constant-time compare to avoid leaking the key via response-timing differences.
       return if (MessageDigest.isEqual(token.toByteArray(), apiKey.toByteArray())) scheme else null
     }
     ```
     The gate at `:265-272` becomes `val scheme = authorized(authorization) ?: run { reply(401, …); return }` instead of `if (!authorized(authorization))`. **This is the only call site** — grep to confirm before changing it. One parse, one compare, and the scheme is available to step 3 without re-parsing the header.
  3. Pure `internal fun rejectsAsCrossSite(secFetchSite: String?): Boolean` — `true` **only** for `"cross-site"` and `"same-site"`. `null`, `"none"`, `"same-origin"` and any unrecognised value → `false`. Parse `sec-fetch-site` in the header loop (`:244-262`) alongside `accept`. Immediately after the gate's successful `authorized()` call: `if (scheme == AuthScheme.BASIC && rejectsAsCrossSite(secFetchSite)) { reply(403, …); return }`. Bearer requests never reach the check.

     **Codex review (P2, PR #310): the `null`-allowed branch is still CSRF-able for STATE-CHANGING
     requests.** A browser/WebView that omits `Sec-Fetch-Site` entirely (the header is Fetch-Metadata,
     not universal — older Safari, some embedded WebViews, some proxies strip it) reads as `null` and
     is allowed by design, so a foreign page loaded in such a client can still fire an authenticated
     Basic `POST /select-model` once credentials are cached. **Fix, scoped to state-changing methods
     only** so GET navigation (including the meta-refresh reload, which also has no `Sec-Fetch-Site`
     guarantee) is untouched: add `origin` and `referer` to the header loop (`:244-262`), and change
     the check to `internal fun rejectsAsCrossSite(method: String, secFetchSite: String?, origin:
     String?, referer: String?, host: String?): Boolean`. When `secFetchSite` is present, behavior is
     unchanged (only `cross-site`/`same-site` reject). **When `secFetchSite` is absent AND `method !=
     "GET"`:** extract the host from `origin` (preferred) or else `referer`, lowercase, and compare
     against the request's `host` header (also lowercased, port included) — mismatch, or absent
     `origin`/`referer` both, **rejects**. GET requests with absent `Sec-Fetch-Site` keep the old
     behavior (allowed) regardless of `origin`/`referer`, because a GET is not the state change this
     guard exists to stop and the address-bar/meta-refresh cases have no `Origin`/`Referer` to check
     either. Pin this with **test 8j** (new, distinct from 8i's six `Sec-Fetch-Site`-value cases):
     `POST /select-model`, no `Sec-Fetch-Site`, no `Origin`/`Referer` → 403; same request with
     `Origin: https://<node-host>` → passes the CSRF check (still needs a valid model id to 200);
     same request with `Origin: https://evil.example` → 403.
  4. On the 401, when `accept?.contains("text/html") == true`, add `WWW-Authenticate: Basic realm="Relais", charset="UTF-8"`. `reply()` already takes `headers: List<String> = emptyList()` (`:223-226`) — **no new seam is needed**.
- **MIRROR:** AUTH_PATTERN in *Patterns to Mirror* — one predicate, one credential, one constant-time compare; ERROR_HANDLING for the gate's early return.
- **IMPORTS:** `android.util.Base64` (already imported in `RelaisHttpServer.kt`).
- **GOTCHA:** Wrap the base64 decode in `runCatching` — malformed input must return `null`, never throw. **Do not add a length check or an early `return false`** before the compare; that reintroduces the timing signal the constant-time compare exists to remove. The scheme parse is the only nullable step and it is not key-dependent, so it leaks nothing. `accept` is lowercased at parse (`:257`); lowercase `sec-fetch-site` the same way and compare against lowercase literals. **Allow `none`** — see research item 4; rejecting it `403`s the operator's very first address-bar navigation, which is the feature. **The bare-key tightening is wire-visible**: today `header?.removePrefix("Bearer ")` returns the header *unchanged* when the prefix is absent, so `Authorization: <rawkey>` is accepted. This task rejects it. Every doc and the README specify `Bearer`; the acceptance is an accident of `removePrefix`, not a contract. Record it in `SECURITY.md` and `.claude/HANDOFF.md` and pin it with test 8g.
- **VALIDATE:** tests #8a-8j (8j is the new Origin/Referer-fallback test for method-scoped `Sec-Fetch-Site`-absent requests); `curl -i` still gets a bare 401; manual checks 1-3 and 6.

### Task 4 — Extend `DashboardStatus` with the selector inputs

- **ACTION:** Add three fields + three parameters to `assembleDashboardStatus`.
- **IMPLEMENT:**
  - `val availableModelIds: List<String>` — empty ⇒ render no form.
  - `val switchLocked: Boolean`.
  - `val pendingModelId: String?` — the configured id when it differs from the resident one, else `null`.

  In the handler (`RelaisHttpServer.kt:720-736`), build the list as
  **`(provisionedIds(provisionedOnDisk()) + RelaisConfig.modelId(context)).filter { RelaisRuntimeCompat.incompatibleReason(it) == null }.sorted()`**. Source `pendingModelId` by comparing `RelaisConfig.modelId(context)` against `RelaisEngine.residentModelId` (`RelaisEngine.kt:301`).
- **MIRROR:** the eligibility rule at `RelaisModelSwap.kt:107`; the existing 13-field data class + assembler, `RelaisDashboard.kt:34-112`.
- **IMPORTS:** none new in `RelaisDashboard.kt` (the fields are plain types); `RelaisRuntimeCompat` is same-package.
- **GOTCHA — four separate traps, all verified:**
  1. **`provisionedIds` returns `Set<String>`, not `List`** (`RelaisModelRegistry.kt:72`). `.sorted()` supplies both the `List` and a deterministic order.
  1b. **Ordering: lexicographic, and copy §1.4 L93's "catalog order" must be amended to say so.** There is no cheap catalog-order source. The one that exists, `RelaisModelCatalog.curatedModels()` (`RelaisModelCatalog.kt:108-118`), is **blocking and network-backed** behind a 5-minute TTL (`:35`, `:89`) — putting it in `handleDashboard` would make a page that auto-refreshes every 10s depend on a network fetch and stall on a cold cache offline, which is the opposite of what this node is for. `Set` iteration order would also be filesystem-enumeration order, which is not stable enough to assert in a test. Lexicographic is deterministic, offline, and test-pinnable. Fold this into O1's copy amendment — it is the same sign-off.
  2. **The configured id must be unioned in.** `RelaisModelSwap.kt:79-80` keeps `configuredModelId` swap-eligible on its own *"so the operator's current selection works before it has been recorded."* Source the dropdown from the registry alone and, in that pre-recording window, the **currently-serving model is missing from its own dropdown** — no `selected` option, and the operator can only switch away.
  3. **Compat filter is mandatory (C1).** `RelaisRuntimeCompat.incompatibleReason(id) != null` and `!isOfferable(id)` denote the *same* set — `loadability` derives `INCOMPATIBLE` only from `KNOWN_INCOMPATIBLE` (`RelaisRuntimeCompat.kt:121`) and `incompatibleReason` is that map's lookup (`:131`). Use `incompatibleReason` on **both** the filter here and the re-check in Task 8, so the two gates cannot drift and the re-check has the reason string to render.
  4. **`RelaisModelRegistry` is the safety boundary** — per the KDoc at `RelaisHttpServer.kt:1128-1131` it only grows on a locally-successful provision, so a client-named model can complete a download the operator already made but can never originate one. Do not widen past registry ∪ configured.

  **Single-read requirement:** compute `switchLocked` from the *same* `RelaisEngine.startupInProgress` read that feeds `assembleDashboardStatus` — capture one local `val` and pass it to both, or a mid-swap page can show `LIVE` beside a disabled form.
- **VALIDATE:** compile; tests #1, #1b, #10.

### Task 5 — Render the form

- **ACTION:** Add the switch form and the pending hint to the existing Node Status panel; **invert** the read-only test.
- **IMPLEMENT:** Per copy §1.4/§2.4 — `<form method="POST" action="/select-model">`, `<select name="model" id="model">` with one `<option value="…">` per available id (`selected` on the configured id), submit labelled `SET MODEL`. Amber `#FFB000` background, charcoal `#0B0B0D` text, bold, letter-spacing 2px, 6px radius. When `switchLocked`, add `disabled` to both controls, `opacity: 0.5`, and the literal `model locked while starting`. When `pendingModelId != null`, render copy §1.4's pending hint with O1's amended text. Delete the `READ-ONLY` claim at `RelaisDashboard.kt:160`.
- **MIRROR:** the row/panel structure already in `renderDashboardHtml` (`RelaisDashboard.kt:250-285`); the escaping discipline at `:164-174` (closed-set CSS strings are *not* escaped; user content always is).
- **IMPORTS:** none.
- **GOTCHA:** The form goes **inside the existing Node Status panel**, after the `shed total` row — there is no `MODEL` panel and no `SERVING` row on this page today (`:250-285` is one flat table with a lowercase `model` row), and building them is explicitly out of scope. `RelaisDashboardTest.kt:341` (`…contains no model-switch form or select-model action (read-only scope)`) **fails by construction** — replacing it with its positive form is an explicit task, not incidental cleanup. Every id goes through `escapeHtml` (`:143-149`) including inside `value="…"`, since that is attribute context. Copy §2.5: no new colors — `#FF5247` appears nowhere on this page. **The pending hint is the operator-visible signal for the one gap Task 7's `Boolean` return cannot close** (a swap that won the CAS but then bailed at `RelaisEngine.kt:431` because the file vanished): config reads B, resident reads A, the hint says so.
- **VALIDATE:** tests #1-4, #10.

### Task 6 — Pure form-body parser + validator

- **ACTION:** Add two pure functions to `RelaisDashboard.kt`.
- **IMPLEMENT:** `internal fun parseFormField(body: String, key: String): String?` — split on `&`, then on the **first** `=`, URL-decode both halves, return the match. `internal fun validateModelChoice(requested: String?, available: List<String>): String?` — returns the id iff present in `available`, else `null`.
- **MIRROR:** NAMING_CONVENTION (`internal`, pure, KDoc'd, test-covered).
- **IMPORTS:** `java.net.URLDecoder`, `java.nio.charset.StandardCharsets`.
- **GOTCHA:** **No form parser exists in this codebase** (grep-confirmed — the readers handle JSON and multipart only), so this is genuinely new code, not a reuse. `URLDecoder.decode` throws on a malformed `%` escape — wrap in `runCatching`. Bound the split (`limit`) so an `&`-flood can't blow up; the body is already capped by `MAX_BODY_BYTES` at `:282`. Keep both functions `Context`-free so they test on the JVM.
- **VALIDATE:** tests #6, #7.

### Task 7 — `ensureModelSwapInBackground` reports whether it dispatched

- **ACTION:** Change `RelaisEngine.ensureModelSwapInBackground` (`:409`) to return `Boolean`.
- **IMPLEMENT:** `fun ensureModelSwapInBackground(context: Context, target: ProvisionedModel? = null): Boolean` — `if (!swapDispatching.compareAndSet(false, true)) return false` at `:410`, `return true` after the `thread { … }` block is started. Nothing else in the body changes.
- **MIRROR:** the existing CAS at `:410` and its comment; do not restructure the thread.
- **IMPORTS:** none.
- **GOTCHA:** **`true` means "this call won the CAS and started a swap thread", not "the swap succeeded."** The thread still bails at `if (!File(path).exists()) return@thread` (`:431`), and engine-create failures roll back at `:452-465`. Task 5's pending hint is the operator-visible signal for the resulting config-ahead-of-engine state — the two are designed together, do not ship one without the other. **Caller audit before changing the signature:** grep-verified there is exactly **one** production call site, `RelaisHttpServer.kt:1171` (a plain statement call inside the `SwapThenRetry` arm), plus doc-comment mentions in `RelaisModelSwap.kt:37`, `RelaisModelRegistry.kt:99`, `RelaisModelProvisioner.kt:165` and two test comments. **No function reference (`::ensureModelSwapInBackground`) is bound anywhere** — that would fail to compile against a `(Context, ProvisionedModel?) -> Unit` type. Ignoring the new return value at `:1171` is correct and needs no change there.
- **VALIDATE:** unit lane green (no behavioural change on the existing path); test #11.

### Task 8 — `handleSelectModel`

- **ACTION:** Add the route arm in `RelaisHttpServer.kt` and the handler in `RelaisHttpPages.kt`.
- **SEAM (this is the whole point of the new file — read before writing):** `handleSelectModel` takes **primitives, not `RequestContext`**:
  ```kotlin
  internal fun handleSelectModel(
    context: Context,
    body: String,
    secFetchSite: String?,
    available: List<String>,
    respond: (status: Int, html: String, extraHeaders: List<String>) -> Unit,
  )
  ```
  The router arm in `RelaisHttpServer.kt` reads the body with the private `readBody`, computes `available` with the private `provisionedOnDisk()`, and passes a lambda closing over `respondText(ctx.sock, …)`. **Zero visibility changes**: `RequestContext` (`:690`), `readBody` (`:1913`), `respondText` (`:1937`), `provisionedOnDisk` (`:1119`) all stay `private`. feature-17 keeps sole ownership of the `RequestContext` widening.
- **IMPLEMENT:** Arm: `method == "POST" && path == "/select-model" -> handleSelectModel(ctx)` wrapper, placed next to the `GET /` arm at `:293`. Handler body, **in this order**:
  1. `parseFormField(body, "model")` → `validateModelChoice(requested, available)`. `null` ⇒ `400`, copy §1.6 page (`unknown model id — no change applied`, `‹ BACK` link), **nothing persisted**.
  2. `RelaisRuntimeCompat.incompatibleReason(id)?.let { … }` ⇒ `400` rendering that reason, **nothing persisted**.
  3. `RelaisEngine.ensureModelSwapInBackground(context, swapTargetFor(id, provisionedOnDisk()))`. `false` ⇒ `503` + `Retry-After: 25`, **nothing persisted**.
  4. Only on `true`: `ModelSwitch.applyManualId(context, id)`, then `303` with `Location: /`.

  CSP on every response from this handler carries `form-action 'self'`, as does `handleDashboard`'s (`:741`).
- **MIRROR:** HANDLER_PATTERN; the `SwapThenRetry` arm at `RelaisHttpServer.kt:1165-1180` — including its `503` + `Retry-After: 25` idiom at `:1176-1177`, which is why step 3 answers `503` rather than inventing a `409` for the same state.
- **IMPORTS:** `android.content.Context`; same-package `RelaisRuntimeCompat`, `ModelSwitch`, `swapTargetFor`.
- **GOTCHA — five, in priority order:**
  1. **Check order is load-bearing (C1 + M2).** Membership first, compat second. `RelaisModelSwap.kt:116-119` records why: an earlier revision checked compat first, so an **absent** known-bad id answered `Incompatible` — "telling the operator the file was unloadable when the real problem was that it was missing." Reversing steps 1 and 2 reintroduces exactly that regression.
  2. **The server-side compat re-check is not redundant with the dropdown filter.** A page rendered before a model became known-bad can still POST that id; the dropdown is client-supplied input. Both gates use `incompatibleReason` so they cannot drift.
  3. **Dispatch before persisting (H3).** Persisting first and then calling the swap is the bug: `ensureModelSwapInBackground` no-ops on its CAS (`RelaisEngine.kt:410`) while `swapDispatching` is held — cleared only in the `finally` at `:471`, i.e. at the end of the *whole* swap — so a second `SET MODEL` mid-swap would leave config naming B, the engine serving A, no retry scheduled, and a `303` that reads as success. The CAS is the only atomic gate; make it the arbiter.
  4. **Persist through `ModelSwitch`, never `RelaisConfig.setModelId` directly (H4).** `ModelSwitch`'s KDoc (`:20-27`) declares it the single source of truth for an operator model pick and lists the drift it consolidates. `applyManualId` (`:42-45`) calls `clearModelRef` **unconditionally**; `RelaisConfig.setModelId` drops a ref only when its `modelId` differs (`RelaisConfig.kt:205`) — a weaker guarantee. Update that KDoc in Task 9 to name the dashboard as the third surface.
  5. **`Sec-Fetch-Site` is handled at the gate in Task 3, not here.** Do not add a second per-route check. `form-action` does **not** inherit from `default-src`; `/experiments` sends `'none'` (`:767`) but the dashboard needs `'self'` or the form is blocked. Use `303`, not `302`, so the reload is a GET. Add `path == "/select-model" -> "/select-model"` to `endpointLabel` (`:1877-1900`) so the label doesn't fall through to an unbounded raw path (M6 cardinality) — place it beside the `path == "/"` arm at `:1880`; both are exact-match arms so ordering between them is immaterial, but it must come **before** any `startsWith` arm that could shadow it.
- **VALIDATE:** Manual curl block below; on-device gate.

### Task 9 — Docs

- **ACTION:** Amend five docs; file two issues.
- **IMPLEMENT:**
  - **`docs/dashboard-copy.md`** — (a) §1.4 L95 → `model set: <id> — swapping, node restarts itself` and §1.4 L93's option ordering from `catalog order` → `sorted by id` (**both O1, both need sign-off**), and note the hot swap; (b) **add entries for the four strings this plan introduces that the doc does not yet have**: the `503` swap-busy page, the `400` unknown-id page, the `400` incompatible-with-reason page (which interpolates `RelaisRuntimeCompat`'s reason), and the `WWW-Authenticate` realm string; (c) strike Appendix rows 5-6 as already satisfied (grep-confirmed: `#FFCC44` and `#FF5247` are absent from the shipped CSS, `#FF5247` surviving only in a comment at `RelaisDashboard.kt:185`).
  - **`SECURITY.md`** — Basic accepted; HTML-only challenge; safe under TLS; the `Sec-Fetch-Site` guard and why `none` is allowed; **the bare-key tightening** (M3).
  - **`docs/RUNBOOK.md`** — browse `https://<phone-ip>:8443/`, accept the cert, blank username, key as password.
  - **`ModelSwitch.kt` KDoc** (`:20-27`) — name the dashboard as the third surface (H4).
  - **`.claude/HANDOFF.md`** — new section, including the wire-visible bare-key change.
  - **File two issues:** R1 (`/experiments` unreachable from a browser) and the eight open `dashboard-copy.md` Appendix deltas (M1) — both predate this plan and neither is in scope.
- **MIRROR:** existing table/section idiom in each file.
- **GOTCHA:** `dashboard-copy.md` is the declared source of truth for user-visible strings — **amend it in the same PR rather than silently deviating**. Its L95 hint is stale, not authoritative (see Notes). Adding the four new entries is what makes the downgraded acceptance criterion ("every *new* string matches copy verbatim") checkable at all; without them it is unsatisfiable for the same reason the original criterion was.
- **VALIDATE:** review reads doc and code as one diff.

### Task 10 — Validation & review

- **ACTION:** Run the unit lane; independent review.
- **IMPLEMENT:** Commands below; then `code-reviewer` **and** `security-reviewer` on the **final** diff.
- **GOTCHA:** Green CI ≠ reviewed, and fix commits are the highest-risk diff in this repo — re-review after any fix rather than merging on the earlier approval.
- **VALIDATE:** Acceptance Criteria all checked.

## Testing Strategy

### Unit Tests

| # | Test | Input | Expected Output | Edge Case? |
|---|---|---|---|---|
| 1 | Form renders with options | `availableModelIds = [a, b]`, configured `b` | Contains `action="/select-model"`, `method="POST"`, 2 `<option>`, `selected` on `b` only | No |
| 1b | Configured id survives the union | registry `= {a}`, configured `= b` | `availableModelIds` contains **both**, `selected` on `b` — the pre-recording window (`RelaisModelSwap.kt:79-80`) | **Yes** |
| 1c | Known-bad id filtered out | registry `= {a, <a KNOWN_INCOMPATIBLE id>}` | Only `a` offered | **Yes** |
| 10 | Pending hint | configured `b`, resident `a` | Hint rendered with O1's text; absent when configured == resident | **Yes** |
| 11 | Swap dispatch is exclusive | two `ensureModelSwapInBackground` calls, second while the first holds the CAS | first `true`, second `false` — **on-device probe, not JVM** (`RelaisModelSwapTest.kt:26` records that this function needs a device) | **Yes** |
| 2 | Locked while starting | `switchLocked = true` | `disabled` on select + button; literal `model locked while starting` | No |
| 2b | Unlocked when live | `switchLocked = false` | Neither `disabled` nor the lock hint | No |
| 3 | Empty catalog renders no form | `availableModelIds = emptyList()` | No `<form`, no orphan `SET MODEL` button | **Yes** |
| 4 | Option escaping (XSS) | id `x" onfocus="alert(1)` | Fully escaped; raw `onfocus=` absent | **Yes** |
| 5 | Still scriptless + refresh | any status | No `<script`; exactly one `http-equiv="refresh"` | No |
| 6a | Form parse | `model=a%2Fb` | `"a/b"` | No |
| 6b | Form parse, missing key | `other=x` | `null` | **Yes** |
| 6c | Form parse, malformed escape | `model=%ZZ` | `null`, no throw | **Yes** |
| 6d | Form parse, value containing `=` | `model=a=b` | `"a=b"` (split on first `=`) | **Yes** |
| 7a | Validator accepts known | `("b", [a,b])` | `"b"` | No |
| 7b | Validator rejects unknown | `("zzz", [a,b])` | `null` | No |
| 7c | Validator rejects null | `(null, [a,b])` | `null` | **Yes** |
| 8a | Bearer still accepted | `Bearer KEY` | key extracted | No |
| 8b | Basic accepted | `Basic base64("any:KEY")` | key extracted | No |
| 8c | Basic, blank username | `Basic base64(":KEY")` | key extracted | **Yes** |
| 8d | Basic, key contains `:` | `Basic base64("u:K:EY")` | `"K:EY"` (split on first `:` only) | **Yes** |
| 8e | Malformed base64 | `Basic !!!!` | `null`, no throw | **Yes** |
| 8f | Unknown scheme / null | `Digest x`, `null`, `""` | `null` | **Yes** |
| 8g | **Bare key, no scheme** | `Authorization: KEY` | `null` — pins the M3 tightening. Accepted today; **rejected after this change** | **Yes** |
| 8h | Scheme returned, not just key | `Bearer KEY` vs `Basic base64(":KEY")` | `BEARER` vs `BASIC` — the gate branches on this | No |
| 8i | `Sec-Fetch-Site` predicate | `null`, `"none"`, `"same-origin"`, `"cross-site"`, `"same-site"`, `"garbage"` | `false, false, false, **true**, **true**, false` — **`none` must be allowed** or the first address-bar navigation 403s | **Yes** |
| 9 | Ring-buffer opt-out | `recordRequest("/", 200, inRecentLog = false)` | `recentRequests()` unchanged; aggregate counter **still incremented** | **Yes** |
| — | **Invert** `:341` | shipped fixture | now **asserts** the form is present | No |

### Edge Cases checklist

- [ ] Empty `availableModelIds` — no form, no orphan button.
- [ ] Model id with HTML metacharacters — escaped in both text and `value="…"` attribute context.
- [ ] `switchLocked` and `statusLabel` derived from one `startupInProgress` read (no self-contradiction mid-swap).
- [ ] Basic key containing `:` survives (split on first `:` only).
- [ ] Malformed base64 and malformed `%` escapes return `null` rather than throwing.
- [ ] `Sec-Fetch-Site` **absent** ⇒ allowed (curl/SDK path preserved); **`none` ⇒ allowed** (address-bar navigation, the primary way an operator opens this page).
- [ ] Unknown model id ⇒ `400`, and `RelaisConfig.modelId` is **unchanged** afterwards.
- [ ] Known-incompatible model id POSTed from a stale page ⇒ `400` with the reason, **nothing persisted**.
- [ ] Second `SET MODEL` while a swap holds the CAS ⇒ `503`, **nothing persisted** (config never ends up ahead of a swap that was dropped).
- [ ] Configured-but-unrecorded model appears in its own dropdown, `selected`.
- [ ] Aggregate metrics still count `/` even with the ring-buffer opt-out.
- [ ] `escapeHtml` still applied to `baseUrl`, `apiKeyMasked`, `capabilities` (existing coverage stays green).

**Prove every new test RED first.** This repo has shipped two regression tests that passed under the bug they claimed to pin. Mutate each assertion (or stub the function to a wrong constant) and watch it fail before trusting it.

## Validation Commands

```bash
# JVM unit lane — the CI job. Run from Android/src after any change under main/ or test/.
cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest

# Compile (does not run) the on-device probe suite.
cd Android/src && ./gradlew :app:compileFullOpenDebugAndroidTestKotlin

# Re-measure rather than quoting a remembered number — this repo has shipped a stale count twice.
wc -l Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt \
      Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpPages.kt

# Task 7 changes a public signature. Re-run the caller audit on the branch before believing it.
grep -rn 'ensureModelSwapInBackground' Android/src/app/src        # expect ONE production call site
grep -rn '::ensureModelSwapInBackground' Android/src/app/src      # expect ZERO — a bound reference would not compile

# Optional new probe, mirroring ClientConfigEndpointProbe's documented invocation:
adb shell am instrument -w -e class cc.grepon.relais.DashboardSelectModelProbe \
  -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
```

`report-worker` is **not** touched, so its lane is not run.

### Manual Validation

```bash
KEY=<node key>; IP=<phone ip>

# 1. Bearer unchanged; page still renders for API clients.
curl -sk -H "Authorization: Bearer $KEY" https://$IP:8443/ | head -20

# 2. Basic works (blank username) — the browser path.
curl -sk -u ":$KEY" https://$IP:8443/ | grep -c 'select-model'

# 3. HTML client gets a challenge; API client does NOT.
curl -sk -i -H 'Accept: text/html' https://$IP:8443/ | grep -i 'www-authenticate'   # expect a match
curl -sk -i https://$IP:8443/v1/models | grep -i 'www-authenticate'                 # expect NO match

# 4. Valid switch -> 303 back to /.
curl -sk -i -u ":$KEY" -H 'Sec-Fetch-Site: same-origin' \
  --data-urlencode 'model=<provisioned id>' https://$IP:8443/select-model | head -5

# 5. Unknown id -> 400, and the served model is unchanged.
curl -sk -i -u ":$KEY" -H 'Sec-Fetch-Site: same-origin' \
  --data-urlencode 'model=not-a-real-model' https://$IP:8443/select-model | head -5
curl -sk -u ":$KEY" https://$IP:8443/ | grep -A1 SERVING

# 6. Cross-site rejected for BASIC — and the guard is gate-wide, not /select-model-only.
curl -sk -i -u ":$KEY" -H 'Sec-Fetch-Site: cross-site' \
  --data-urlencode 'model=<provisioned id>' https://$IP:8443/select-model | head -3   # expect 403
curl -sk -i -u ":$KEY" -H 'Sec-Fetch-Site: cross-site' https://$IP:8443/v1/models     # expect 403
# ...but BEARER is untouched on the same route, so no SDK regresses:
curl -sk -i -H "Authorization: Bearer $KEY" -H 'Sec-Fetch-Site: cross-site' \
  https://$IP:8443/v1/models | head -1                                                # expect 200

# 6b. `none` (address-bar navigation) MUST be allowed — this is the operator's normal path.
curl -sk -i -u ":$KEY" -H 'Sec-Fetch-Site: none' https://$IP:8443/ | head -1          # expect 200

# 6c. Bare scheme-less key is now rejected (M3, wire-visible change).
curl -sk -i -H "Authorization: $KEY" https://$IP:8443/v1/models | head -1             # expect 401

# 7. Swap-busy: two SET MODELs back to back. The second must NOT persist.
#    TIMING-DEPENDENT — a targeted swap needs no network (RelaisEngine.kt:425-426), so on a warm
#    local file the first swap can release the CAS before the second curl lands, and you get 303.
#    A 303 here is NOT a plan failure; test #11 (the on-device probe) is the deterministic check.
curl -sk -o /dev/null -u ":$KEY" -H 'Sec-Fetch-Site: same-origin' \
  --data-urlencode 'model=<model A>' https://$IP:8443/select-model
curl -sk -i -u ":$KEY" -H 'Sec-Fetch-Site: same-origin' \
  --data-urlencode 'model=<model B>' https://$IP:8443/select-model | head -3   # expect 503 + Retry-After
curl -sk -u ":$KEY" https://$IP:8443/ | grep 'class="label">model</td>' -A1    # expect A, not B

# 8. Log hygiene. NOTE: `curl -u` sends Basic PREEMPTIVELY and never sees a 401, so the
#    credentialed loop alone passes vacuously. Drive the real browser path too.
for i in $(seq 1 25); do curl -sk -u ":$KEY" https://$IP:8443/ >/dev/null; done
curl -sk -u ":$KEY" https://$IP:8443/ | grep -c 'class="label">/</td>'   # expect 0 (no `/ 200` rows)

# 8b. Un-credentialed browser path: the challenge 401 DOES record, by design.
curl -sk -i -H 'Accept: text/html' https://$IP:8443/ | head -1           # expect 401 + WWW-Authenticate
curl -sk -u ":$KEY" https://$IP:8443/ | grep -c 'class="label">/</td>'   # expect >0 now — a `/ 401` row
```

- [ ] In a real browser: cert interstitial → Basic prompt → page renders; beacon pulses only when LIVE; **panel keeps refreshing every ~10s for several minutes without a 403** (the meta-refresh vs `Sec-Fetch-Site` question in Task 2 — one reload is not enough, watch it cycle).
- [ ] `SET MODEL` on a provisioned id → node goes STARTING → LIVE serving the new model, **no app restart**.
- [ ] Form is visibly disabled while STARTING.
- [ ] `/experiments` also loads in the browser now (incidental R1 relief).
- [ ] Re-verify on a **release** build (R8 on) — see R5.

## Acceptance Criteria

- [ ] `GET /` loads in a stock browser via a Basic prompt, including on a **typed-URL navigation** (`Sec-Fetch-Site: none`); `Authorization: Bearer` still works unchanged everywhere.
- [ ] No `WWW-Authenticate` on non-HTML requests (SDK/curl error paths unchanged).
- [ ] A **Basic**-authenticated request with `Sec-Fetch-Site: cross-site` or `same-site` gets `403` — **on every route, not just `/select-model`**; the same request carrying `Bearer` is unaffected.
- [ ] `Authorization: <rawkey>` with no scheme is rejected, and that change is recorded in `SECURITY.md` and `.claude/HANDOFF.md`.
- [ ] `POST /select-model` validates membership **first** and compat **second**, and persists nothing on either rejection.
- [ ] The dropdown offers exactly `(registry ∪ configured id)` minus anything with an `incompatibleReason`, sorted lexicographically, with copy §1.4 L93 amended from "catalog order" to match (Task 4 gotcha 1b).
- [ ] A valid switch **dispatches the swap first** and persists **through `ModelSwitch.applyManualId`** only when the dispatch won the CAS; a concurrent second submit gets `503 + Retry-After` and persists nothing. No app restart required.
- [ ] The form renders disabled while STARTING, from the same `startupInProgress` read as the status row; the pending hint appears exactly when the configured id differs from `RelaisEngine.residentModelId`.
- [ ] Page remains scriptless (no `<script>`); CSP still carries no `script-src`, now plus `form-action 'self'`.
- [ ] All dynamic values HTML-escaped, attribute context included; no raw key ever rendered.
- [ ] RECENT REQUESTS contains **no `/ 200` rows**; `/ 401` challenge rows still appear (they come through the shared `reply()`, deliberately); `/metrics` still counts `/`.
- [ ] Every **new** string matches `docs/dashboard-copy.md` verbatim, and the four strings this plan introduces were **added** to that doc in the same PR, with O1's amendment applied. *The eight pre-existing Appendix deltas on the shipped page are out of scope and filed as their own issue.*
- [ ] UI matches `DESIGN.md` — amber `#FFB000` on `#0B0B0D`, monospace, dark-only, no third accent, `#FF5247` absent, **no font-stack change**.
- [ ] `feature-18-trusted-lan-cert` is fully merged before this branch opens; `handleDashboard` was not relocated and `RelaisHttpServer.kt` gained no `internal` widening.
- [ ] Three-flavor unit lane green; every new test proven RED first.
- [ ] Independent `code-reviewer` + `security-reviewer` APPROVE on the **final** diff.

## Completion Checklist

- [ ] Patterns followed (NAMING_CONVENTION, ERROR_HANDLING, LOGGING_PATTERN, HANDLER_PATTERN, TEST_STRUCTURE, PROBE_STRUCTURE)
- [ ] Error handling explicit at every boundary; no silently swallowed failures; malformed input returns `null`, never throws
- [ ] Logging via file-private `TAG`; no key, IP, body, or raw path logged
- [ ] Tests written, proven RED first, three-flavor lane green
- [ ] No hardcoded values — colors from the `:root` token block, capacity from `REQUEST_LOG_CAPACITY`, model ids from the registry
- [ ] Docs updated (`dashboard-copy.md` — amended **and extended**, `SECURITY.md`, `RUNBOOK.md`, `HANDOFF.md`, `ModelSwitch.kt` KDoc)
- [ ] No scope additions beyond NOT Building — in particular, no handler relocation and no `internal` widening in `RelaisHttpServer.kt`
- [ ] Self-contained: no new dependency, no new framework, no external asset
- [ ] `feature-18` merged first; `feature-17` still owns the `RequestContext` widening
- [ ] Both issues filed: R1 (`/experiments` 401) and the eight `dashboard-copy.md` Appendix deltas
- [ ] Caller audit for Task 7 re-run on the branch (`grep -rn '::ensureModelSwapInBackground'` returns nothing)
- [ ] `security-reviewer` has seen PR-A (Basic + `Sec-Fetch-Site`) on its own, and re-reviewed after any fix commit

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **R1** — `/experiments` is unreachable from a browser today (401 on navigation) despite an in-page key input at `RelaisExperiments.kt:167`; a pre-existing bug this plan only incidentally relieves | **Certain** (verified) | Medium | File as its own issue so the finding survives independently of this PR; do **not** scope its redesign here |
| **R2** — Self-signed cert interstitial appears before the auth prompt | Certain | Low | Document in RUNBOOK; inherent to the TLS posture |
| **R3** — **Accepting Basic converts an explicit credential into an ambient one across ~20 routes** (rewritten; the previous "the carrier changes but the credential does not" framing was a false premise) | **Certain** | **Medium** | The mechanism, stated honestly: today a `Bearer` header must be set by script, and a cross-origin request carrying it triggers a CORS preflight this server fails (no `Access-Control-*` anywhere — grepped). Cached **Basic** credentials are re-attached by the UA itself, no script, no preflight; and `Content-Type` is never enforced on the JSON routes (`:259` parsed, read only for multipart at `:443`/`:624`), so a cross-site *simple* POST reaches `handleOpenAi`. Impact is capped at **side effects, no read** — the response is opaque without CORS — but that still buys an attacker page unmetered inference, RAG corpus injection, session mutation and batch-job creation. **Mitigation (Task 3): the `Sec-Fetch-Site` guard runs at the gate for every Basic-authenticated request**, not on `/select-model` alone. Residual: a browser too old to send `Sec-Fetch-Site` gets no protection — acceptable on a trusted LAN, documented in `SECURITY.md` |
| **R3b** — The `Sec-Fetch-Site` rule is easy to get wrong in the direction that breaks the feature | Medium | Medium | Reject **only** `cross-site` and `same-site`. Allow `none` — that is what an address-bar navigation sends, and an attacker page cannot produce it. A reviewer working from the obvious-sounding "reject unless `same-origin`" will 403 the first page load; test 8i pins all six cases. **For state-changing (non-GET) requests specifically**, an absent header falls back to an `Origin`/`Referer` same-host check rather than being allowed outright (codex P2 review, PR #310) — test 8j |
| **R4** — **A second `SET MODEL` mid-swap persists a selection whose swap was silently dropped** (re-diagnosed; the mitigation previously cited the wrong line) | Medium | **High** | The guard is the `swapDispatching` CAS at `RelaisEngine.kt:410`, **not** `startupInProgress` at `:413` (which is merely *set* there). `swapDispatching` clears only in the `finally` at `:471`, so a mid-swap call no-ops — config would say B, engine serve A, no retry ever scheduled, `303` reads as success. The disabled-form UI guard does not cover it: the meta refresh can repaint an unlocked form in the window between persist and `startupInProgress = true`, and a stale tab can POST at any time. **Fix (Tasks 7-8): the swap function returns whether it won the CAS; dispatch first, persist only on `true`, answer `503 + Retry-After` otherwise.** Residual: `true` means the thread started, not that it succeeded — it can still bail at `:431` — which is what the pending hint surfaces |
| **R5** — R8 minification is on in release and CI runs none of it | Low | High | No reflection added, so no new keep rules expected — but confirm on the on-device release gate, since CI cannot |
| **R6** — Timing side-channel reintroduced while refactoring `authorized()` | Low | High | No length check, no early return before `MessageDigest.isEqual`; called out in Task 3 and in security review. The scheme parse is the only nullable step and is not key-dependent, so returning `null` for an unknown scheme leaks nothing |
| **R7** — Copy drift from `dashboard-copy.md` | Medium | Low | Copy literals verbatim; amend **and extend** the doc in the same PR (O1 + the four new strings) |
| **R8** — Meta refresh discards an in-progress `<select>` choice | Medium | Low | 10s interval (not 3s); the form is a single control submitted immediately |
| **R9** — **A dashboard click loads a known-bad model and takes the node down with nothing to roll back** | **Was certain, now mitigated** | **High** | The targeted swap path skips `resolveModel` entirely (`RelaisEngine.kt:427-430`) and with it `refuseIfIncompatible` — the repo says so at `RelaisModelProvisioner.kt:165-166`. The rollback at `:452-465` catches engine-**create** failures only; this repo's own known-bad case is the Tensor G5 `gemma-4-E4B` **first-inference** SIGSEGV (SPIKE-FINDINGS, LiteRT-LM#2566), which creates fine and then kills the process. **Fix (Tasks 4 + 8): filter the dropdown and re-check server-side, both on `incompatibleReason`.** Ordering matters — persisting before the swap would leave the bad id in config with `KEY_MODEL_PATH` cleared (`RelaisConfig.kt:196-200`), re-bricking on restart; Task 8 persists last, and only through `ModelSwitch` |
| **R10** — Cross-plan collision with `feature-18` / `feature-17` / `feature-22` | **Was high, now sequenced** | Medium | Cutting the handler extraction removes the `handleDashboard`-relocation and `RequestContext`-widening collisions outright. What remains is ordering — see *Dependencies & Cross-plan Sequencing*. The one live overlap is the CSP at `:741`, which feature-18 pins as "unchanged" for its own diff and feature-09 legitimately amends; say so in the PR body |
| **R11** — `authorized()` has **never had a JVM test** | Certain (verified) | Medium | Grep-confirmed: the only `Bearer` assertions in the unit lane are `RelaisExperimentsTest.kt:143/188/225/255`, and they assert on a *rendered page*, not the gate. `RelaisHttpAuthTest.kt` is the first coverage this function gets — which is precisely why the M3 bare-key tightening would otherwise ship unnoticed. Prove every case RED first |
| **R12** — Task 7 changes a public signature on the engine | Low | Medium | Audited: one production call site (`RelaisHttpServer.kt:1171`, a plain statement call), zero bound function references. A `::ensureModelSwapInBackground` reference typed `(Context, ProvisionedModel?) -> Unit` **would not compile** — re-run both greps in the Validation block on the branch rather than trusting this line |

## Notes

### What the 2026-06 version of this plan assumed that is now wrong

The original was written before any of it shipped. On `1276a351`, **most of it is done**:

| Original assumption | Reality |
|---|---|
| `GET /` returns nothing; add the route | **Shipped** — `RelaisHttpServer.kt:293` → `handleDashboard` (`:714-747`) |
| Create `RelaisDashboard.kt` | **Shipped**, 333 lines (assembler, `thermalLabel`, `escapeHtml`, `maskApiKey`, renderer) |
| Add a ring buffer to `RelaisMetrics` | **Shipped** — capacity 20 (`RelaisMetrics.kt:72`), `recentRequests()` (`:131`) |
| No security headers exist | **Shipped for this route** — CSP + nosniff + DENY + no-referrer (`:739-745`) |
| Tests must be written | **Shipped** — `RelaisDashboardTest.kt`, 453 lines, 26 tests |
| `DashboardStatus` has 10 fields | **13** — adds `baseUrl`, `apiKeyMasked`, `capabilities` (a CLIENT CONFIG panel the original never specified) |
| Delete `#FFCC44` warn and `.stop` red (copy Appendix rows 5-6) | **Already satisfied** — grep-confirmed absent from the shipped CSS; `#FF5247` occurs only inside a comment at `RelaisDashboard.kt:185`. Not work |
| `/` is the only HTML route | `GET /experiments` also ships (`:295`) — and it carries an inline script under a per-request CSP **nonce**, so "the repo is scriptless" is no longer globally true |
| Browser access is unsolved; "leave it API-client-only for v1" was the default | Resolved here as Basic auth **plus a gate-wide `Sec-Fetch-Site` guard** — the guard is not optional garnish, it replaces the CSRF immunity that `Bearer`-only was providing by accident (R3) |
| The model switch is a matter of persisting an id and calling the swap | **Two gates the original never saw.** The *targeted* swap path skips `resolveModel` and therefore every compat check (`RelaisEngine.kt:427-430`); and `ensureModelSwapInBackground` silently no-ops when its CAS is already held (`:410`), so persist-then-swap can leave config ahead of the engine. Both were found by the `critic-09` review, not by the original plan |
| Persist with `RelaisConfig.setModelId` | `ModelSwitch` (`:19-45`) has since become the declared single source of truth for an operator model pick, and `setModelId` alone is the weaker path it exists to prevent |

The remaining scope is exactly what `RelaisDashboard.kt:160` defers: *"READ-ONLY — no model-switch form or /select-model action (deferred to a separate PR)"*, pinned by the test at `RelaisDashboardTest.kt:341`.

### Decisions

**The model switch does not need a restart — `docs/dashboard-copy.md:95` is stale.** That line specs the hint `model set: <id> — restart to apply`. But `rejectIfModelUnavailable` (`RelaisHttpServer.kt:1138-1180`) already calls `RelaisEngine.ensureModelSwapInBackground` on the `SwapThenRetry` branch (arm at `:1165-1180`, the call itself at `:1171`), and that function sets `startupInProgress = true` at `RelaisEngine.kt:413` and clears it at `:470` — the node transitions LIVE → STARTING → LIVE by itself. The `"Restart to apply"` strings at `RelaisConfigureActivity.kt:296,306` belong to the *config-set* path (persist a preference, take no engine action); do not carry their semantics across.

**Auto-refresh via meta refresh, not an inline script.** No CSP directive governs meta refresh, so it needs no header relaxation and keeps `script-src` absent. `dashboard-copy.md` §2.7 requires future interactivity to survive that CSP *before it gets copy*, and `RelaisDashboardTest.kt:306` pins the invariant. `/experiments`' nonce'd script is a genuinely interactive surface — not a precedent for a page whose whole content is a server-rendered readout.

**Browser auth via Basic, not a cookie.** Cookie needs a *new unauthenticated* key-entry route (which the original plan's Risks section forbids), `Cookie`/`Set-Cookie` parsing that exists nowhere in the codebase (grep: zero), and its own CSRF story — three new mechanisms. Basic needs one change to one existing function, and the browser then attaches credentials to every subsequent request on the origin, incidentally fixing `/experiments`. Safe because `SECURITY.md:14-18` puts the LAN listener on TLS only. **But "the browser attaches credentials on its own" is exactly the CSRF exposure in R3** — the convenience and the risk are the same property, which is why Task 3 pairs Basic with the gate-wide `Sec-Fetch-Site` guard rather than shipping it alone.

**O3 is answered: the wider option.** Basic is accepted on every endpoint (one gate, one comparison, no per-route auth branch), **and** the `Sec-Fetch-Site` guard is applied at that same gate to every Basic-authenticated request. The narrow alternative — accept Basic only on `GET /`, `GET /experiments` and `POST /select-model` — is also sound, but it puts an auth branch in the router, and a fourth HTML surface later would silently miss it.

**A scheme-less `Authorization: <rawkey>` is rejected from now on.** Today it is accepted, purely because `header?.removePrefix("Bearer ")` returns the receiver unchanged when the prefix is absent, so the `?: return false` fires only on a null header. Nothing documents that: the README, `SECURITY.md`, every `*-api.md`, and every example specify `Bearer`. It is an accident of the implementation, not a contract — tighten it, pin it with test 8g, and record it as wire-visible in `SECURITY.md` and `.claude/HANDOFF.md`. Deliberately **not** left as an open question: the evidence is one-sided.

**`503 + Retry-After: 25` for a busy swap, not `409`.** The server already answers exactly this state with exactly this pair at `RelaisHttpServer.kt:1176-1177` ("resident model differs from the requested model; swapping — retry shortly"). Inventing a second status code for the same condition on a sibling route would be a gratuitous divergence.

### Critic findings disposition (`critic-09`, 2026-09-07)

Every finding was re-verified against `1276a351` before being accepted. **12 of 12 fixed; 0 declined.**
Two are fixed *differently* from the critic's suggested wording, because the suggested wording was
itself wrong — those are marked **fixed, corrected**.

| # | Finding | Disposition |
|---|---|---|
| **C1** | Targeted swap path skips the only compat gate; a dropdown click can brick the node | **Fixed** — Tasks 4 (filter) + 8 (server-side re-check), both on `incompatibleReason`. Verified: `loadability` derives `INCOMPATIBLE` only from `KNOWN_INCOMPATIBLE` (`RelaisRuntimeCompat.kt:121`), so `!isOfferable` and `incompatibleReason != null` are the same set — using one predicate on both sides removes the drift the critic's two-predicate suggestion would have allowed, and yields the reason string for the 400 body. **Check order pinned** (membership first, compat second) per `RelaisModelSwap.kt:116-119`, which the finding did not specify |
| **H1** | Basic opens an API-wide CSRF surface; R3 dismissed it on a false premise | **Fixed, corrected** — took the wider option: gate-level guard on every Basic-authenticated request (Task 3), R3 rewritten to state the mechanism. **Corrected:** the finding's rule, "reject when present and ≠ `same-origin`", would `403` the operator's first address-bar navigation, since that sends `Sec-Fetch-Site: none`. The implemented rule rejects **only `cross-site` and `same-site`**; `none` is safe to allow because an attacker page cannot cause a request to be labelled `none`. Pinned by test 8i and R3b so a later reviewer does not "correct" it back |
| **H2** | The original Task 1 (handler extraction) does not compile — `RequestContext` (`:690`) and `provisionedOnDisk` (`:1119`) are private — and collides with feature-17/18 | **Fixed** — that task cut entirely; the remaining tasks are renumbered 1-10. **Corrected upward:** rather than moving `handleSelectModel` into the new file with a widened `RequestContext`, it takes primitives and the router arm resolves the private members at the call site, so this feature needs **zero** visibility changes and feature-17 keeps sole ownership of the widening |
| **H3** | R4's mitigation cites the wrong line; a second submit persists after the swap no-ops | **Fixed, corrected** — verified the guard is the `swapDispatching` CAS at `:410`, not `startupInProgress` at `:413`. **Corrected:** the finding's "check before persisting" is check-then-act and still races. Task 7 makes the CAS itself the arbiter (`ensureModelSwapInBackground` returns `Boolean`); Task 8 dispatches first and persists only on `true`. Caller audit done — one production call site (`:1171`), no bound function references |
| **H4** | Bypasses `ModelSwitch`, the declared single source of truth | **Fixed** — Task 8 persists through `ModelSwitch.applyManualId`; Task 9 updates its KDoc to name the dashboard as the third surface |
| **M1** | Acceptance criteria demand copy work no task scopes; the mock oversells the page | **Fixed** — criterion downgraded to *new* strings, the eight pre-existing Appendix deltas filed as their own issue, and the "After" mock redrawn to depict what actually ships. Copy §1.4's pending hint is **implemented**, not dropped (`pendingModelId`, Tasks 4-5), which is what makes O1's rewrite of its text meaningful. **Added beyond the finding:** this plan introduces four strings `dashboard-copy.md` does not have, so Task 9 *adds* them — without that, the downgraded criterion is unsatisfiable for the same reason the original was |
| **M2** | Dropdown eligibility is a partial copy of the swap rule; wrong type, wrong order | **Fixed** — Task 4 unions the configured id and `.sorted()`s the `Set` (`RelaisModelRegistry.kt:72`), which supplies both the `List` and a deterministic order. **The finding's "sort to catalog order or amend the copy" resolves to *amend the copy*:** the only catalog-order source, `RelaisModelCatalog.curatedModels()`, is blocking and network-backed behind a 5-min TTL, and a page that auto-refreshes every 10s must not depend on a fetch. Test 1b pins the pre-recording window |
| **M3** | Undocumented auth tightening hidden in the refactor; no test covers `authorized()` at all | **Fixed and decided** — tighten, pin with test 8g, record in `SECURITY.md` + HANDOFF. Not left open: every doc specifies `Bearer` and the acceptance is an artefact of `removePrefix`. Coverage gap logged as R11 |
| **M4** | "No `/` rows" is unachievable and its manual check passes vacuously | **Fixed** — restated as "no `/ 200` rows"; the challenge `401` recording through the shared `reply()` (`:224`) is documented as intended, not a leak. Manual block gains the un-credentialed browser path, since `curl -u` sends Basic preemptively and never takes it |
| **M5** | Not self-contained — six specific gaps | **Fixed** — `RelaisRuntimeCompat`, `ModelSwitch`, `RelaisModelSwap`, `RelaisModelRegistry` added to Mandatory Reading; a Visibility-facts block added; `provisionedIds`' `Set` return stated; `endpointLabel` corrected to `:1877-1900` with its insertion point named |
| **L1** | Citation drift (`endpointLabel` `:1879-1897` → `:1877-1900`; copy Appendix "L258-273" in a 272-line file) | **Fixed** — both corrected. A third drift the review did not catch is also fixed: the swap dispatch was cited at `:1165` (the `SwapThenRetry` arm) when the call itself is at `:1171` |
| **L2** | Font stack is an unapproved `DESIGN.md` deviation | **Fixed** — dropped, not deferred. `DESIGN.md:41` mandates bundled `FontFamily.Monospace`; the change was inert anyway (`default-src 'none'` blocks `font-src`) |

### Codex findings disposition (PR #310, 2026-09-07)

- **P2 — fixed.** `codex review --base main` on PR #310 found that Task 3's `null`-allowed branch
  (added for H1 above) is still CSRF-able for browsers/WebViews that omit `Sec-Fetch-Site` entirely,
  since a foreign page loaded there can fire an authenticated Basic `POST /select-model`. Fixed by
  scoping an `Origin`/`Referer` same-host fallback to non-GET methods only, so GET navigation
  (including the meta-refresh reload, which has the same missing-header property) is unaffected.
  New test 8j; R3b updated to describe the method-scoped fallback.

### Alternatives considered and rejected

- **Key as a query param** (`/?key=…`) — rejected: lands the key in history, server logs, and `Referer`. The original plan already leaned against it; nothing has changed.
- **Cookie + one-time key-entry form** — rejected: see above. Reconsider only if Basic proves unworkable in a target browser.
- **Leaving it API-client-only (the original's default (c)/(a))** — rejected: it leaves the shipped `/experiments` page permanently unusable and the dashboard reachable only by tools that don't need a dashboard.
- **SSE/WebSocket live updates** — rejected: needs `connect-src` and a script, contradicting §2.7 for a readout that changes every few seconds at most.
- **Filtering `/` inside `recentRequests()`** — rejected: also hides a genuine `401`/`429` on `/`. The opt-out belongs at the call site.
- **A separate `/dashboard` path leaving `/` untouched** — rejected: `/` already serves this page and `endpointLabel` already maps it.
- **Moving `handleDashboard`/`handleExperiments` into the new file** (the original Task 1) — rejected: does not compile without widening `RequestContext` and `provisionedOnDisk`, duplicates a change `feature-17` already owns, invalidates every one of `feature-18`'s line references into `:714-747`, and — by the plan's own admission — nets near-zero lines. Cutting it is the single highest-leverage change in this revision.
- **Widening `respondText`/`respondBytes`/`readBody` to `internal`** — rejected once `handleSelectModel` takes primitives: nothing needs them across the file boundary any more. Do not widen speculatively.
- **`409 Conflict` for a busy swap** — rejected: `503 + Retry-After: 25` is the established answer for this exact state at `:1176-1177`.
- **Adding `Access-Control-*` headers so the browser page can `fetch`** — rejected, and worth naming explicitly: the *absence* of CORS is what caps R3's impact at "side effects, no read". Adding it would uncap the exfiltration.
- **Checking `startupInProgress` before persisting** (the critic's H3 suggestion) — rejected as check-then-act: the window between the read and `setModelId` is exactly the window the race lives in. The CAS is already atomic; make it the arbiter instead.
- **Restructuring the page to the copy Appendix in this PR** (CAPS labels, `MODEL`/`SERVING` panel, tagline) — rejected as scope: eight independent deltas that predate this plan, none of which the model switch needs. Filed as its own issue in Task 9.

### Open questions for the user

Two remain. **O3 and O4 are now answered** (wider option; two PRs) and have moved to Decisions and
Metadata respectively; the critic-suggested O5 on the bare-key header was **decided rather than
opened** — see Decisions.

- **O1** — Two amendments to `docs/dashboard-copy.md` §1.4, both needing explicit sign-off rather than a silent deviation, since the doc is the declared source of truth for user-visible strings:
  - **L95**, `restart to apply` → `model set: <id> — swapping, node restarts itself`. **Now load-bearing:** Task 5 implements the pending hint this text belongs to, so shipping without the amendment means shipping a string the doc contradicts.
  - **L93**, option ordering `catalog order` → `sorted by id`. The only catalog-order source is blocking and network-backed (Task 4, gotcha 1b); a page that auto-refreshes every 10s and must work offline cannot depend on it.
- **O2** — Auto-refresh interval: **10s** proposed. 5s doubles the race against the STARTING window; 30s reads as dead on a status panel.

**Decision this plan could not make:** the sequencing of `feature-22-idle-unload`'s `NodeState.IDLE`
against this plan's three new `assembleDashboardStatus` parameters. They do not conflict semantically
(feature-09 leaves the `statusLabel` derivation at `RelaisDashboard.kt:92-96` untouched), so either
order works and the second one rebases — but feature-22 explicitly asks the question at its `:636` and
it belongs to whoever owns the release train, not to either plan.

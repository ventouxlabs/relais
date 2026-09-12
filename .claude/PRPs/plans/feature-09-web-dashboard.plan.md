# Plan: Web Dashboard — Model Switch & Browser Auth (feature-09, refresh)

> **Re-based 2026-09-12 onto `cf316146`** (`cf316146…`, "feat(tls): per-node CA and SAN'd leaf so LAN
> clients can drop curl -k (#318)"). **Every line number below was re-read on `cf316146`** — the
> 2026-09-06 refresh was against `1276a351`, and #314/#316/#317/#318 moved most of
> `RelaisHttpServer.kt` (now **2432** lines). Re-verify with `wc -l` / `grep -n` if `main` has moved
> again.
>
> **Revised 2026-09-07 against the `critic-09` adversarial review** (1 CRITICAL, 4 HIGH, 5 MEDIUM,
> 2 LOW). Every finding was re-verified against source before being accepted; the disposition of each
> is recorded in Notes → *Critic findings disposition*. Two findings are fixed **differently** from
> the critic's suggested wording because the suggested wording was itself wrong — see H1 and H3 there.
>
> **Revised 2026-09-12 against a second critic pass + an independent `/codex` pass, both scoped to
> PR-A (Tasks 1-3) on `cf316146`** (0 CRITICAL, 4 HIGH, 4 MEDIUM, 4 LOW). #317 extracted the gate
> into `RelaisHttpGate.decide`, which **falsified** — not merely renumbered — Task 3's mirrored
> pattern and its "immediately after `authorized()`" ordering. Disposition of each finding is in
> Notes → *Critic + codex findings disposition (2026-09-12, post-#318)*.
>
> **Revised again 2026-09-12 against a codex pass on that revision** (3 P1, 1 P2) — the round-1
> *prescriptions* proved no more reliable than the plan they corrected. The three P1s are **three
> instances of one defect class**: pure helpers are tested, injected fakes are tested, and the wiring
> between them is tested by nothing. Task 3 now specifies `java.util.Base64` (not the already-imported
> `android.util.Base64`, which would make every negative Basic test pass vacuously), a canonical
> authority comparison, and a pure `authenticate()` whose scheme preservation is pinned by a RED-proven
> test — plus one hardware probe for the two seams the JVM lane cannot reach. See Notes → *Codex
> round-2 disposition* and Testing Strategy → *Closing the seam class*.
>
> **Revised a third time 2026-09-12** (2 P1, 1 P2) — **the first P1 was a defect in round 2's own
> fix:** the helpers were placed as class *members*, so the seam-closing test could not have compiled.
> They move top-level, beside the twelve `internal fun`s already there. The probe gained the
> unauthenticated row that actually exercises the challenge, and the port-comparison rule became
> symmetric after the asymmetric version turned out to 403 the node's own form on a default-port
> listener. See Notes → *Codex round-3 disposition*, which also names the pattern: **three rounds,
> three defects, and each time the codebase had already written the answer down.**
>
> **Revised a fourth time 2026-09-12** (1 P1, 2 P2). The fallback compared authority but **never
> scheme**, so `Origin: http://node:8443` vs `Host: node:8443` on a TLS listener was allowed — and the
> URL-parsing shape it needs is already shipped in `batch/WebhookGuard.kt:54-58`, making it **four
> rounds, four defects, four answers already in the tree**. `AuthScheme` now moves top-level with the
> helpers, and the probe's request count is reconciled across all three places that state it. See
> Notes → *Codex round-4 disposition* and **The rule that falls out**, which is this branch's most
> transferable finding: *before writing any helper, grep for whether this codebase already has one.*
>
> **Revised a fifth time 2026-09-12** (1 P1, 1 P2) — **the P1 was in round 4's own fix.** Pointing at
> `WebhookGuard` wholesale would have imported an **outbound SSRF** policy into an **inbound** origin
> check: DNS per request, an allowlist bypassing the scheme rule, and `classify` blocking RFC1918 —
> the only network this dashboard is reachable on. The mirror is narrowed to the parse (`:54-58`) with
> an explicit NOT list, and 8j gains the RFC1918 and portless-IPv6 rows. The grep-first rule gains its
> caveat: **mirror the shape, not the policy.** Round 5 also *confirmed* three standing assumptions
> (`internal`-from-`test` visibility, Tasks 1-2 still tree-aligned, origin = scheme+host+port).

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
| The dashboard page **cannot be opened in a browser at all** — the gate requires `Authorization: Bearer`, which no browser sends on a navigation (`RelaisHttpGate.decide`, `RelaisHttpGate.kt:74-92`; the credential compare at `RelaisHttpServer.kt:2069-2073`) | Accept `Basic base64(user:key)` in `authorized()`; challenge with `WWW-Authenticate` for HTML clients only |
| Basic credentials are **ambient** — the UA re-attaches them per-origin with no script involved — so accepting Basic removes the CORS-preflight barrier that makes today's `Bearer`-only API CSRF-immune | `Sec-Fetch-Site` guard applied **inside `RelaisHttpGate.decide`** to every Basic-authenticated request, not just `/select-model`. `Bearer` requests keep today's behaviour, so no SDK regresses |
| A 403 has **no response vocabulary** in this tree: `reason()` (`RelaisHttpServer.kt:2086-2100`) has no 403 arm and falls through to `else -> "ERR"` (`:2098`), and `RelaisError` (`RelaisError.kt:33-56`) has eight types, none for forbidden | Add `403 -> "Forbidden"` and a `PERMISSION` type in **PR-A** — the PR that introduces the first 403 in the tree (MEDIUM-0) |
| The page is read-only; switching models requires the on-device Configure screen | `POST /select-model` → `RelaisEngine.ensureModelSwapInBackground` (`RelaisEngine.kt:433`, sole production caller today at `RelaisHttpServer.kt:1330`) |
| The **targeted** swap path has no compat gate — `resolveModel` (and its `refuseIfIncompatible`) is skipped when `target != null` (`RelaisEngine.kt:451-454`), so a dropdown click could load a known-bad model and take the node down on first inference with nothing to roll back | Filter the dropdown **and** re-check server-side in `handleSelectModel`, both on `RelaisRuntimeCompat.incompatibleReason` — the same predicate `rejectIfModelUnavailable` passes in at `:1314` |
| A second `SET MODEL` mid-swap would persist the new id while `ensureModelSwapInBackground` no-ops on its `swapDispatching` CAS (`RelaisEngine.kt:434`), leaving config ahead of the engine and answering `303` as if it worked | Make the swap function **return whether it won the CAS**, dispatch *before* persisting, and persist only on `true`; answer `503 + Retry-After` otherwise |
| Status is a point-in-time snapshot; operators re-load by hand | `<meta http-equiv="refresh" content="10">` — survives the scriptless CSP |
| Dashboard page-loads write themselves into the 20-slot request log (`:866` + `:2048`); auto-refresh would make `/` the loudest voice in it | `recordRequest(..., inRecentLog = false)` from the dashboard handler. **A reduction, not an elimination, and `/` is not the only offender** — `/health` records through `ctx.send` → `reply` → `recordRequest` (`:855` → `:812` → `:309-310`) and #318's `handleCaCert` records at `:839`, so a monitoring poller floods the same 20 slots harder than a 10s refresh would (LOW-3) |

## Metadata

- **Complexity:** Medium-High (was Medium — the compat gate, the swap race, and the gate-wide CSRF guard are all correctness work, not rendering work)
- **Source PRD:** N/A
- **PRD Phase:** N/A
- **Estimated Files:** 17 (3 created — including `BasicAuthGateProbe.kt`, the only cover for the two seams the JVM lane cannot reach — and 14 updated), plus a `.claude/HANDOFF.md` section
- **Ships as two PRs** (O4, adopted): **PR-A** = Tasks 1-3 (auth + refresh + log hygiene + the 403 vocabulary); **PR-B** = Tasks 4-10 (the selector). PR-A is the security-sensitive half and gets a minimal blast radius for the security reviewer — which is also why the `Referrer-Policy` narrowing HIGH-3 requires is **defined** in PR-A and **shipped** in PR-B, in the commit that adds the form.
- **Blocked on:** ~~all of `feature-18-trusted-lan-cert` landing first~~ — **satisfied.** #318 merged as `cf316146`; this plan is re-based onto it. See *Dependencies & Cross-plan Sequencing* for what remains (ordering against `feature-17`/`-19`/`-20`/`-22` only).

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
| RECENT REQUESTS panel | Includes every `/` self-load | No `/ 200` rows | The un-credentialed challenge `401` on `/` still records — it goes through the shared `reply()` (`:309-313`, which calls `recordRequest` at `:310`), not the dashboard handler |
| Basic + cross-site on **any** route | n/a | `403 Forbidden`, `{"error":{…,"type":"permission_error"}}` | Both the reason phrase and the type are **new in PR-A** — see MEDIUM-0 |
| Model switch | On-device Configure screen only | `SET MODEL` on the page | Hot swap — **no app restart** |
| Node during switch | n/a | `STARTING`, form disabled, pending hint shown | `startupInProgress` for the lock; config-vs-resident id for the hint |
| Second `SET MODEL` mid-swap | n/a | `503 + Retry-After: 25`, **nothing persisted** | Mirrors the existing swap-busy answer at `:1332-1337` |

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpGate.kt` | **all 129 lines, and `:44-73` twice** | **The single most important read for Task 3, and new since this plan was last refreshed (#314/#317).** `decide` (`:74-92`) is the gate now; its 9-line body (`:83-91`) is a pure *ordering* function. Its KDoc states the two theses Task 3 must honour: ordering is load-bearing and the 401 deliberately precedes rate limiting (`:47-53`), and **"Every effect is a supplier, not a boolean, and that is load-bearing"** (`:61-67`). `authExempt` (`:127-128`) exempts `GET /health` **and `GET /ca.crt`** (`isCaCertPath`, `:124`) |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisDashboard.kt` | 34-54, 83-112, 159-174, **176**, 179-423 | The shipped page. `:176` is the comment that defers exactly this plan's scope |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 300-318, 330-349, 356-392, 403-405, 862-899, 2069-2073, 2086-2100 | `reply`/`replyBytes` (which record at `:310`/`:316`), header parse loop, **the gate call + the exhaustive reject `when`**, route table, `handleDashboard`, `authorized()`, `reason()` |
| **P0** | `.../batch/WebhookGuard.kt` | **54-58 to mirror; 60-85 to read and NOT mirror** | **Read the whole function, then copy only its first five lines.** `:54-58` is the URL-parse shape `rejectsAsCrossSite` needs — `runCatching { URI(…) }.getOrNull() ?: return <reject>`, `scheme?.lowercase()`, `host?.lowercase() ?: return <reject>`, fail closed on both. **Everything below is an OUTBOUND SSRF policy and must not come along**: DNS resolution (`:63-64`), an allowlist bypass that short-circuits *past* the scheme check (`:66` before `:68`), and `classify` (`:77-85`) blocking loopback and `isSiteLocalAddress` — *"private // 10/8, 172.16/12, 192.168/16"* — i.e. **the only network this dashboard is ever reached on.** This row is the caveat to the grep-first rule: *mirror the shape, not the policy* |
| **P0** | `.../RelaisError.kt` | 17-30, 33-56 | The envelope's own KDoc makes cross-endpoint consistency the file's thesis and names LiteLLM/Open WebUI as the clients that trip on drift. Eight types today, **none for 403** — MEDIUM-0 adds the ninth |
| **P0** | `.../RelaisHttpServer.kt` | 1284-1339 | `rejectIfModelUnavailable` — the hot-swap path to reuse (`incompatibleReason` wired in at `:1314`, `provisionedIds` at `:1319`, the swap dispatched at `:1330`), and the registry safety boundary at `:1287-1290` |
| **P0** | `.../RelaisRuntimeCompat.kt` | 86, 119-138 | **The compat gate the targeted swap path skips.** `loadability` derives `INCOMPATIBLE` *only* from `KNOWN_INCOMPATIBLE` (`:121`), and `incompatibleReason` is `KNOWN_INCOMPATIBLE[id]` (`:131`) — so `!isOfferable(id)` and `incompatibleReason(id) != null` denote the **same set**. Use `incompatibleReason` on both the filter and the re-check: symmetric by construction, and it hands you the reason string for the 400 body |
| **P0** | `.../ModelSwitch.kt` | 19-45 | *"The single source of truth for 'the operator picked a model'… MUST persist through here so they can't drift."* Two surfaces route through it today; the dashboard becomes the third. `applyManualId` (`:42-45`) does `clearModelRef` **unconditionally** (`:43`), which `RelaisConfig.setModelId` does not (`RelaisConfig.kt:193-209` drops a ref only when the new value *differs*, at `:206`) |
| **P0** | `.../RelaisEngine.kt` | 433-437, 451-458, 474-496 | The swap. The guard is the **`swapDispatching` CAS at `:434`**, not `startupInProgress` (which is merely *set* at `:437`). `target != null` skips `resolveModel` entirely (`:451-454`) — that is why C1 exists. The rollback at `:474-490` covers engine-**create** failures only; the `finally` that clears both flags is at `:493-496` |
| **P1** | `.../RelaisModelSwap.kt` | 24-32, 77-83, 100-121 | The eligibility rule to mirror. `requested == configuredModelId || requested in provisionedModelIds` (`:107`) — `configuredModelId` is eligible on its own *"so the operator's current selection works before it has been recorded"* (`:79-80`). **Check order is deliberate:** membership first, compat second (`:117-120` records why the reverse was a bug) |
| **P1** | `.../RelaisModelRegistry.kt` | 72, 106 | `provisionedIds(...): Set<String>` — **a `Set`, not a `List`**; `swapTargetFor(id, provisioned): ProvisionedModel?` |
| **P0** | `docs/dashboard-copy.md` | all (esp. §1.4 L85-96, §2.4 L199-210, §2.7 L232-237, Appendix L258-272 — the file is 272 lines) | **Source of truth for every user-visible string.** Copy literals verbatim |
| **P0** | `DESIGN.md` | 24-38 (Color), 40-46 (Type), 56-60 (Motion) | Amber `#FFB000` on `#0B0B0D`, monospace, dark-only, one accent |
| **P1** | `.../RelaisMetrics.kt` | 72-73, 131-155 | Ring buffer (`REQUEST_LOG_CAPACITY = 20` at `:72`), `recordRequest` (`:131`, signature `(endpoint: String, status: Int)`, the guarded append at `:138-141`), `recentRequests()` (`:149`) |
| **P1** | `.../RelaisHttpServer.kt` | 300-318 | `reply()` / `replyBytes()` — `handle()`-local functions that record **unconditionally** (`:310`, `:316`) and already accept `headers: List<String> = emptyList()`, so `WWW-Authenticate` needs no new seam. This is also why the challenge `401` on `/` still lands in the ring buffer (M4), and why `/health` records too (`handleHealth` `:855` → `RequestContext.send` `:812` → `reply` `:309`) |
| **P1** | `Android/src/app/src/test/java/cc/grepon/relais/RelaisHttpGateTest.kt` | 44-63, 64-80, **318-326**, **341-343**, **353-354**, **367-368**, **376-377** | **The migration surface for Task 3, and the evidence that ordering did not move.** Two private wrappers whose `authorized: Boolean = true` parameter must be retyped; `private class Counting : () -> Boolean`; four blocks of call-counting assertions that must stay byte-identical. See *Test-file migration (`RelaisHttpGateTest.kt`)* under Testing Strategy before touching it |
| **P1** | `Android/src/app/src/test/java/cc/grepon/relais/RelaisDashboardTest.kt` | 21-24, 60-93, 99-118, **360-362**, **396-403** | Test idiom + the fixture builders. `:360-362` is the scriptless invariant (must stay green); `:396-403` asserts *no* form — it must be inverted |
| **P1** | `Android/src/app/src/test/java/cc/grepon/relais/RelaisErrorTest.kt` | 57-66 | Enumerates all eight `RelaisError` type constants by literal value. **MEDIUM-0's new constant needs a row here** or it ships unpinned |
| **P1** | `SECURITY.md` | 12-29 | LAN is HTTPS-only (`:8443`); plaintext is loopback-bound — why Basic is safe |
| **P2** | `.../RelaisExperiments.kt` | 60-70, 170, 240-355 | The other HTML page — **not touched by this plan**; its in-page key input (`:170`) is unreachable today (R1) and Basic relieves that incidentally. Its four `fetch()` calls all send `Authorization: Bearer` (`:244`, `:279`, `:314`, `:350`), which is why the Basic-only CSRF guard never applies to them |
| **P2** | `.../RelaisConfigureActivity.kt` | 296, 306 | The *config-set* path's "Restart to apply" — **different semantics, do not copy** |

**Visibility facts that decide the seam** (all in `RelaisHttpServer.kt`, all re-verified on `cf316146`):
`RequestContext` is `private class` (`:800`) · `provisionedOnDisk()` is `private fun` (`:1278`) ·
`readBody` (`:2081`), `respondText` (`:2105`), `respondBytes` (`:2114`) are all private. **This plan
changes none of them** — see Task 8's seam. `RelaisEngine.startupInProgress` and
`RelaisEngine.residentModelId` (`:325`) are both public and readable.

## External Documentation

| Topic | Source URL | Key Takeaway |
|---|---|---|
| CSP directive list | https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Security-Policy | No directive governs `<meta http-equiv="refresh">` |
| `form-action` | https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Security-Policy/form-action | Restricts form POST targets; **not** covered by `default-src` |
| HTTP Basic | https://developer.mozilla.org/en-US/docs/Web/HTTP/Authentication | `WWW-Authenticate: Basic realm=…` triggers the browser prompt; credentials re-sent per-origin |
| `Sec-Fetch-Site` | https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Sec-Fetch-Site | Browser-set, unforgeable by page JS; absent on non-browser clients. `same-site` means *different origins on the same site* — so it cannot describe a request whose target URL is identical to the initiator's |
| `Referrer-Policy` — effect on `Origin` | https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Referrer-Policy | For non-CORS, non-GET/HEAD requests (i.e. HTML form submissions), `no-referrer` makes the UA send **`Origin: null`** and omit `Referer` entirely |
| POST/Redirect/GET | https://en.wikipedia.org/wiki/Post/Redirect/Get | `303` after a state change prevents re-POST on reload |
| RFC 7235 §2.1 | https://www.rfc-editor.org/rfc/rfc7235#section-2.1 | The `auth-scheme` token is **case-insensitive** — `bearer` and `Bearer` are the same scheme by spec, though the shipped `removePrefix("Bearer ")` is case-sensitive |
| RFC 7230 §3.1.2 | https://www.rfc-editor.org/rfc/rfc7230#section-3.1.2 | The reason phrase is advisory and clients must not parse it — so `403 ERR` is cosmetic on the wire, unlike the envelope `type`, which clients do branch on |

**Research item 1 — meta refresh vs. inline script**
- `KEY_INSIGHT:` CSP has no meta-refresh directive, so auto-refresh needs **no** CSP relaxation and keeps `script-src` absent.
- `APPLIES_TO:` Task 2.
- `GOTCHA:` Refresh resets scroll and discards any typed `<select>` choice. Acceptable on a readout; it is also why the interval is 10s, not 3s. The *other* cost of 10s is budget, not UX — see research item 7.

**Research item 2 — `form-action` is not covered by `default-src`**
- `KEY_INSIGHT:` `form-action` does **not** fall back to `default-src`. The dashboard's current CSP (`RelaisHttpServer.kt:893`) omits it entirely, so adding a form silently leaves submissions unrestricted.
- `APPLIES_TO:` Tasks 6, 8 — **PR-B only.** PR-A adds no form, so the absent directive restricts nothing that exists and PR-A must **not** add `form-action 'none'` defensively.
- `GOTCHA:` `/experiments` already sends `form-action 'none'` (`:920`, inside the CSP that starts at `:918`). The dashboard needs `'self'`, **not** `'none'` — `'none'` would block the new form.

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
- `GOTCHA:` `Content-Type` is never enforced on the JSON routes — `contentType` is parsed (`RelaisHttpServer.kt:345`) but read only for multipart boundaries (`:553`, `:734`). So a cross-site **simple** POST (`text/plain`, no preflight) reaches `handleOpenAi` and the rest. Impact is capped at *side effects only* — without CORS the response is opaque, so nothing is exfiltrated — but unmetered inference runs, `POST /v1/rag/documents` corpus injection, `/v1/sessions` mutation and `/v1/batch` job creation are all real. This is why the guard goes **inside `RelaisHttpGate.decide` for every Basic-authenticated request**, not on `/select-model` alone.

**Research item 6 — `Referrer-Policy: no-referrer` nulls the `Origin` on the node's own form (HIGH-3)**
- `KEY_INSIGHT:` The dashboard sends `Referrer-Policy: no-referrer` (`RelaisHttpServer.kt:896`). Per MDN, for a **non-CORS, non-GET** request — which is exactly an HTML form POST — that policy makes the UA send `Origin: null` and omit `Referer` entirely.
- `APPLIES_TO:` **defined** in Task 3 (PR-A), **bites** Tasks 5/8 (PR-B).
- `GOTCHA:` Task 3's `Sec-Fetch-Site`-absent fallback requires a same-host `Origin`/`Referer` on non-GET. Under `no-referrer` PR-B's own `POST /select-model` supplies neither, so **any UA that omits `Sec-Fetch-Site` would 403 the node's own form**. Accepting `Origin: null` is *not* the fix — sandboxed iframes and cross-origin redirects send exactly that, which hands an attacker the bypass. Resolution: narrow the **dashboard's** policy to `same-origin` in PR-B; see *Decisions → HIGH-3*. In PR-A the fallback branch is **dormant** (nothing in the tree POSTs from a browser page), which is precisely why it is easy to lose.

**Research item 7 — the 10s refresh costs 20% of the per-IP budget, and a 429 ends the chain (MEDIUM-1)**
- `KEY_INSIGHT:` `RATE_LIMIT = 30` per `RATE_WINDOW_MS` (`RelaisHttpServer.kt:92`), per IP. `/` is **not** auth-exempt, so it charges the standard budget, not the 120-request exempt one (`EXEMPT_RATE_LIMIT`, `:102`). One idle dashboard tab at 10s = 6 req/min = **20% of the budget**, shared with every SDK request from the same laptop. Two tabs = 40%.
- `APPLIES_TO:` Task 2; risk R13.
- `GOTCHA:` The failure mode is worse than throttling. A 429 answers **JSON**, which carries no `<meta http-equiv="refresh">` tag — so the refresh chain **stops dead permanently** and the operator is left staring at raw JSON until a manual reload. **This is pre-existing in the plan, not caused by #316/#317/#318.** Decision: **keep 10s** (see *Decisions*), state the cost, and record the terminate-on-429 behaviour rather than discovering it on a device.

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

**ERROR_HANDLING** — **rewritten 2026-09-12 (HIGH-1).** The snippet this section carried until now was the *pre-#317 inline gate*, complete with the conflated `if (!(method == "GET" && path.startsWith("/health")))` condition. **That code no longer exists**, and copying it would re-inline exactly the bug #317 extracted (`RelaisHttpGate.kt:19-21`: exempting `/health` from auth silently exempted it from rate limiting and the body cap too). The pattern to mirror now is a **pure decision plus one reply**: `decide` yields a `Reject`, an exhaustive `when` yields only a *body*, and a single `reply` takes its status from `reject.status` rather than a repeated literal:

```kotlin
        val reject =
          RelaisHttpGate.decide(
            method = method,
            path = path,
            authorized = { authorized(authorization) },
            rateLimitOk = { rateLimiter.allow(ip) },
            exemptRateLimitOk = { exemptRateLimiter.allow(ip) },
            contentLength = contentLength,
            maxBody = MAX_BODY_BYTES,
          )
        if (reject != null) {
          // The `when` builds the body rather than performing the reply, which makes it an
          // EXPRESSION — so Kotlin enforces exhaustiveness at compile time. […]
          val body =
            when (reject) {
              RelaisHttpGate.Reject.UNAUTHORIZED ->
                RelaisError.json("unauthorized", RelaisError.AUTHENTICATION)
              // …
            }
          reply(reject.status, body)
          return
        }
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:356-392 (elided)
```

Three properties Task 3 must preserve: the `when` stays an **expression** (as a statement Kotlin only warns, and a new `Reject` with no branch would send no reply at all); the status comes from `reject.status`, never a literal; and every effect stays a **supplier**, per `RelaisHttpGate.kt:61-67`.

**AUTH_PATTERN** — one private predicate, one credential, constant-time compare. Task 4 extends *this exact function* and must not add a length check or an early return before `MessageDigest.isEqual`:

```kotlin
  private fun authorized(header: String?): Boolean {
    val token = header?.removePrefix("Bearer ")?.trim() ?: return false
    // Constant-time compare to avoid leaking the key via response-timing differences.
    return MessageDigest.isEqual(token.toByteArray(), apiKey.toByteArray())
  }
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:2069-2073
```

Note the shape Task 3 must preserve: the *scheme parse* is the only nullable step (`?: return false` today, `?: return null` after), and every non-null token reaches the same single comparison. Pulling the scheme parse out into a pure `internal fun extractApiKey(header: String?): Pair<AuthScheme, String>?` keeps that property — the Basic branch base64-decodes and strips `user:` there, then hands one candidate back to the unchanged compare. `authorized()` then returns `AuthScheme?` rather than `Boolean` — `null` for unauthenticated, the scheme for authenticated — so the gate can decide whether the `Sec-Fetch-Site` guard applies without re-parsing the header. It still does exactly one `MessageDigest.isEqual` and nothing else.

**`grep 'fun authorized('` returns a decoy** (LOW-1): `RelaisTaskerActivity.kt:82` defines an unrelated `private fun authorized(token: String): Boolean` — the intent-ABI path's own constant-time compare, called at `:71`. It has no header, no scheme, and nothing to do with this task. Do not "harmonize" it.

Two things this shape must **not** acquire: a length check or an early `return false` before the compare (that is the timing signal R6 exists to prevent), and a per-route branch (H1's whole point is that the guard lives at the one gate).

**LOGGING_PATTERN** — file-private `TAG`, `Log.e` for genuine faults, `Log.w` for swallowed ones. No request bodies, keys, or IPs:

```kotlin
private const val TAG = "RelaisHttpServer"
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:68

        Log.e(TAG, "Request handling error", e)
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:610

      .onFailure { Log.w(TAG, "session record failed (swallowed)") }
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:1703
```

**HANDLER_PATTERN** — one `when` arm per route delegating to a private `handleX(ctx)`; the handler records its own metric and calls `respondText` with `extraHeaders`:

```kotlin
          method == "GET" && path == "/" -> handleDashboard(ctx)

          method == "GET" && path == "/experiments" -> handleExperiments(ctx)
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:403-405

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
        "Referrer-Policy: no-referrer",   // :896 — PR-B narrows THIS ONE to `same-origin`; see HIGH-3
      ),
    )
  }
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:862-899 (elided)

  private fun respondText(
    sock: java.net.Socket,
    status: Int,
    body: String,
    contentType: String,
    extraHeaders: List<String> = emptyList(),
  ) = respondBytes(sock, status, body.toByteArray(), contentType, extraHeaders)
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:2105-2112
```

Note `handleDashboard` records its own metric at `:866` because `respondText` — unlike `reply` — does **not** record. #318's `handleCaCert` (`:832`) does the same at `:839`, with a comment saying so. Task 1's opt-out therefore has to be threaded through the handler's own `recordRequest` call, not through `reply`.

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

| File | PR | Action | Justification |
|---|---|---|---|
| `.../main/java/cc/grepon/relais/RelaisHttpGate.kt` | **A** | UPDATE | `authorized: () -> AuthScheme?`, a new `rejectsAsCrossSite: () -> Boolean` supplier, `Reject.CROSS_SITE(403)`, and the two-line ordering change in `decide` (`:83-91`). **The Origin/Referer comparison algorithm does not go here** — see Task 3 |
| `.../main/java/cc/grepon/relais/RelaisError.kt` | **A** | UPDATE | One new `const val` for the 403 envelope type (MEDIUM-0). Eight types today (`:33-56`), none for forbidden |
| `.../main/java/cc/grepon/relais/RelaisMetrics.kt` | **A** | UPDATE | `recordRequest(endpoint, status, inRecentLog: Boolean = true)` (`:131`) |
| `.../main/java/cc/grepon/relais/RelaisHttpServer.kt` | A + B | UPDATE | **PR-A:** parse `sec-fetch-site`, `origin`, `referer` **and `host`** in the header loop (`:340-348` — `host` has no arm today, and it is the one the `Origin` comparison is *against*); four new **pure, `android.*`-free, `internal`, TOP-LEVEL** helpers — `extractApiKey`, `authenticate`, `rejectsAsCrossSite`, `challengeHeaders` — placed **after the class closes at `:2151`**, beside the twelve `internal fun`s already there, **never as members** (a member needs an instance, needs a `Context`, and the JVM tests would not compile); `authorized()` (`:2069`) reduced to a one-line delegation and left `private`; pass the two new suppliers to `decide` (`:356-367`); a `CROSS_SITE` arm in the reject `when` (`:373-389`); conditional `WWW-Authenticate` alongside the single `reply` (`:390`); `403 -> "Forbidden"` in `reason()` (`:2086-2100`); `inRecentLog = false` at `:866`. **PR-B:** `/select-model` route arm beside `:403` + `endpointLabel` entry beside `:2048`; `form-action 'self'` on the dashboard CSP (`:893`); **`Referrer-Policy: no-referrer` → `same-origin` at `:896` (HIGH-3 — load-bearing for PR-A's Origin fallback; ships here, defined in Task 3)**; the new `assembleDashboardStatus` arguments. **No visibility changes in either** |
| `.../test/java/cc/grepon/relais/RelaisHttpGateTest.kt` | **A** | UPDATE | Retype the two wrappers (`:44`, `:64`), add `CountingAuth`, add the CROSS_SITE ordering tests. **Four assertion blocks must stay byte-identical and be re-proven RED after the migration** — see Testing Strategy |
| `.../androidTest/java/cc/grepon/relais/BasicAuthGateProbe.kt` | **A** | **CREATE** | **Test 8t — the only coverage seams S2/S3a/S3b get. FIVE real requests, not four** — the fifth is unauthenticated with `Accept: text/html` and is the **only** thing covering S3b (that `challengeHeaders(...)` is actually called); drop it and deleting that call passes everything else. Against a loopback `RelaisHttpServer`, mirroring `ClientConfigEndpointProbe.kt`'s `assumeTrue`-gated shape and header `adb` line. Hardware-gated, **not CI** — see *Closing the seam class* |
| `.../test/java/cc/grepon/relais/RelaisHttpAuthTest.kt` | **A** | **CREATE** | Pure-function coverage: `extractApiKey`, **`authenticate`** (test 8s — the parse→compare seam), `rejectsAsCrossSite` and `challengeHeaders` (tests 8a-8m, 8s). **Every function under test here must be `android.*`-free** or `isReturnDefaultValues = true` turns its negative rows vacuous. **The gate-ordering tests 8n-8p do NOT live here** — they belong in `RelaisHttpGateTest.kt` beside the counting blocks they extend. **This is the first JVM coverage `authorized()` has ever had** (grep-confirmed: the only `Bearer` assertions in the unit lane are `RelaisExperimentsTest.kt:143/188/225/255`, and those assert on a rendered page, not the gate) |
| `.../test/java/cc/grepon/relais/RelaisErrorTest.kt` | **A** | UPDATE | One row in the type-constant enumeration at `:57-66` for MEDIUM-0's new constant |
| `.../test/java/cc/grepon/relais/RelaisMetricsIncrementsTest.kt` | **A** | UPDATE | Ring-buffer opt-out |
| `.../main/java/cc/grepon/relais/RelaisDashboard.kt` | A + B | UPDATE | **PR-A:** the meta-refresh tag after the viewport meta (`:293`). **PR-B:** extend `DashboardStatus` (+3 fields, `:34`), render the form + pending hint, add the pure form parser and validator, delete the READ-ONLY claim at `:176` |
| `.../main/java/cc/grepon/relais/RelaisHttpPages.kt` | B | **CREATE** | Home for **`handleSelectModel` only**, written against primitives (no `RequestContext`) — CLAUDE.md's under-800 target, against a server file now at **2432** lines. The shipped handlers stay put; see Task 8 and H2 in Notes |
| `.../main/java/cc/grepon/relais/RelaisEngine.kt` | B | UPDATE | `ensureModelSwapInBackground` (`:433`) returns `Boolean` (won the `swapDispatching` CAS at `:434`) — H3 |
| `.../main/java/cc/grepon/relais/ModelSwitch.kt` | B | UPDATE | KDoc only: name the dashboard as the third surface that persists through here — H4 |
| `.../test/java/cc/grepon/relais/RelaisDashboardTest.kt` | A + B | UPDATE | **PR-A:** the meta-refresh assertion. **PR-B:** invert `:396-403`; add form/lock/pending-hint/escaping/parser/validator tests |
| `docs/dashboard-copy.md` | B | UPDATE | Amend §1.4 stale "restart to apply" (O1); **add** entries for the four new strings; strike already-satisfied Appendix rows |
| `SECURITY.md` | A | UPDATE | Basic as an accepted carrier, why TLS makes it safe, the `Sec-Fetch-Site` guard, the bare-key tightening, and the scheme-token case decision (LOW-2) |
| `docs/RUNBOOK.md` | A | UPDATE | Operator steps to open the dashboard in a browser — **including that a plain `curl -u ":$KEY" -X POST` now 403s** unless it hand-sets `Sec-Fetch-Site` or a same-host `Origin` |

## Dependencies & Cross-plan Sequencing

**Build order: `feature-18-trusted-lan-cert` is MERGED (`cf316146`, #318).** This section's sequencing
constraint is discharged; what follows records what that changed and what ordering remains.

| Sibling | Overlap | Resolution |
|---|---|---|
| **`feature-18-trusted-lan-cert`** | **Landed as `cf316146`.** It added the Certificate panel to `renderDashboardHtml`, the `/ca.crt` route (`handleCaCert`, `:832`) and its auth exemption (`RelaisHttpGate.isCaCertPath`, `:124`) | **Done — rebase, don't sequence.** This plan is re-based onto it: the `cert` field is already in `DashboardStatus`, and `/ca.crt` is a **second auth-exempt path**, which Task 3 must account for (an exempt request never runs `authorized()`, so it has no scheme and the CSRF guard cannot fire on it) |
| **No handler relocation** | The *original* Task 1 moved `handleDashboard` into a new file | **Still cut, but the reason has changed (LOW-4).** The old justification — "keeps every one of feature-18's line references valid" — is **spent**: feature-18 has merged and its references have already been consumed. The conclusion stands on CLAUDE.md grounds alone: `RelaisHttpServer.kt` is **2432 lines** against a repo target of well under 800, and the repo's rule is *prefer extracting a new file over growing an existing large file* — which a relocation of already-working code does not serve, while it would force `RequestContext` and `provisionedOnDisk` to widen (H2). Extract **new** code (`handleSelectModel`), leave shipped code alone |
| **`feature-18`**, CSP | Its acceptance criterion was *"the CSP at `RelaisHttpServer.kt:741` is **unchanged**"* | **Moot as a sequencing question, still worth a PR-description line.** feature-18's "do not touch" bound *its own diff*; it merged with the CSP intact (now at `:893`). feature-09 **PR-B** legitimately amends it, adding **only** `form-action 'self'`, and separately narrows `Referrer-Policy` at `:896` (HIGH-3). PR-A touches neither. Say so in PR-B's body so a later reader does not score it as breaking a merged rule |
| **`feature-17-ollama-compat-api`** | Its Files-to-Change specifies `RequestContext` `private`→`internal` + a `withReply` helper on `RelaisHttpServer.kt` | **feature-17 owns that widening outright.** feature-09 now needs none, so there is nothing to reconcile. Do not make the same change a second way |
| **`feature-22-idle-unload`** | Task 5 adds `NodeState.IDLE` and calls `assembleDashboardStatus` a *third* health derivation currently "being reworked by the feature-09 dashboard plan" (its `:469`, `:615`, `:636`) | feature-09 adds three fields to `assembleDashboardStatus` but does **not** touch its `statusLabel` derivation (`RelaisDashboard.kt:92-96`). Either order works; whichever lands second rebases. Flag to whoever sequences them — feature-22 explicitly asks the question at its `:636` |
| **`feature-19` / `-20`** | Both UPDATE `RelaisMetrics.kt` (histograms, counters) | Textually adjacent to Task 1's `recordRequest` signature change, semantically independent. Sequence, don't merge simultaneously. #316 has already landed one such change — it moved `recordRequest` from `:113` to **`:131`** and touched nothing about its behaviour, exactly as anticipated |
| **`feature-18`'s `RelaisHttpGate`** | #314/#317 extracted the gate feature-09 Task 3 modifies | **This is the live one.** Task 3 changes `decide`'s signature. Any sibling plan that also edits `RelaisHttpGate.kt` collides head-on; nothing currently does, but re-grep before cutting the branch |

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
- **No handler relocation.** `handleDashboard` (`:862-899`) and `handleExperiments` (`:901-932`) stay exactly where they are — cut deliberately, see H2 in Notes and *Dependencies & Cross-plan Sequencing*.
- **No visibility widening in `RelaisHttpServer.kt`.** `RequestContext` (`:800`), `readBody` (`:2081`), `respondText` (`:2105`), `respondBytes` (`:2114`), `provisionedOnDisk` (`:1278`) all stay `private`. feature-17 owns the `RequestContext` widening.
- **No Origin/Referer parsing inside `RelaisHttpGate`.** The gate learns the *outcome* (`rejectsAsCrossSite: () -> Boolean`), never the header values. `decide` is a 9-line ordering function and must stay one — see Task 3's structural note.
- **No `form-action` directive in PR-A.** PR-A adds no form, so the absent directive at `:893` restricts nothing that exists; adding `'none'` defensively is scope PR-A does not need and PR-B would immediately have to undo.
- **No hidden CSRF token in the form** (PR-B). It would harden `/select-model` only, while the gate-wide guard still covers ~20 routes with no form and no token — so the `Origin`/`Referer` fallback does not go away and the added surface buys nothing. Considered and declined, not overlooked.
- **No change to `/experiments`' `Referrer-Policy`** (`:923`). It stays `no-referrer`: the page has no form (`form-action 'none'` at `:920` forbids one), its `fetch()` calls are CORS-mode so the `Origin`-nulling rule does not apply to them, and they carry `Bearer` (`:244`/`:279`/`:314`/`:350`) so the Basic-only guard never fires. Do not "harmonize" the two pages.
- **No font-stack change.** Dropped: `DESIGN.md:41` specifies bundled `FontFamily.Monospace`, "no font download", and naming four desktop families Android does not have would be an unapproved deviation for an effect that is inert anyway (CSP `default-src 'none'` blocks `font-src`, and every name falls through to `monospace`).
- **No normalization of the shipped page against `docs/dashboard-copy.md`'s Appendix** — the eight open deltas (title case, CAPS labels, `%.1f` vs `%.2f`, `AGE`/`Ns ago`, empty-state wording, the tagline, the styled header dot, the `MODEL`/`SERVING` panel restructure) predate this plan and are filed separately in Task 9.
- **No CORS headers.** Adding `Access-Control-*` would *remove* the preflight barrier described in research item 5. The server has none today and must keep none.

## Step-by-Step Tasks

> **PR-A = Tasks 1-3. PR-B = Tasks 4-10.** Task 3 is the security-sensitive change; keeping it in a
> small PR is O4, adopted. PR-A also carries MEDIUM-0's 403 vocabulary, because PR-A is the change
> that introduces the first 403 in this tree.

### Task 1 — Stop the dashboard polluting its own request log

- **ACTION:** Add an opt-out to `RelaisMetrics.recordRequest`.
- **IMPLEMENT:** `fun recordRequest(endpoint: String, status: Int, inRecentLog: Boolean = true)`. Keep the `requestCounts` increment and the `errorsTotal` bump **unconditional**; guard only the `synchronized(requestLogLock)` append (`RelaisMetrics.kt:138-141`). Pass `inRecentLog = false` from `handleDashboard`'s own `recordRequest` call at `RelaisHttpServer.kt:866`.
- **MIRROR:** existing body of `recordRequest`, `RelaisMetrics.kt:131-142`.
- **IMPORTS:** none.
- **GOTCHA:** `endpointLabel` maps `/` → `/` (`RelaisHttpServer.kt:2048`) and the buffer holds **20** (`RelaisMetrics.kt:72`), so at a 10s refresh a `/`-heavy panel fills in ~3.5 minutes. Do **not** filter `/` inside `recentRequests()` instead — that would also hide a genuine `401`/`429` on `/`. Default `true` keeps all other call sites unchanged. **Two bounds on the claim, both verified:**
  - **A reduction, not an elimination** (M4): `reply` records unconditionally at `:310`, so the challenge `401` a browser gets on its *first* load of `/`, and every re-authentication after, still lands in the buffer. That is correct — an operator wants to see failed auth attempts. Only the `200` self-loads are suppressed.
  - **`/` is not the only self-recording route** (LOW-3): `handleHealth` (`:855`) records via `ctx.send` (`:812`) → `reply` (`:309-310`), and #318's `handleCaCert` records explicitly at `:839`. A monitoring poller on `/health` floods the same 20 slots harder than a 10s dashboard refresh does. The task's value is real but it is *not* the last word on ring-buffer hygiene — do not write acceptance criteria that imply it is.
- **VALIDATE:** test #9.

### Task 2 — Auto-refresh

- **ACTION:** Add `<meta http-equiv="refresh" content="10">` to the `<head>` in `renderDashboardHtml`.
- **IMPLEMENT:** Insert after the viewport meta (`RelaisDashboard.kt:293`). That is the whole change.
- **MIRROR:** the existing `<head>` block, `RelaisDashboard.kt:291-295`.
- **IMPORTS:** none.
- **GOTCHA:** The scriptless test (`RelaisDashboardTest.kt:360-362`) must stay green — meta refresh adds no `<script>`. No CSP change is needed or permitted here. **The font-stack swap an earlier draft bundled into this task is dropped** — `DESIGN.md:41` mandates bundled `FontFamily.Monospace` with no font download, so naming desktop families would be an unapproved deviation (and an inert one: `default-src 'none'` blocks `font-src` and every name falls through to `monospace`). Leave `font-family: monospace` at `:304` alone.
- **GOTCHA — the interval has a rate-limit cost, and the failure mode is terminal (MEDIUM-1, R13):** `/` is **not** auth-exempt, so each refresh charges the standard `RATE_LIMIT = 30`/60s per-IP budget (`RelaisHttpServer.kt:92`), not the 120-request exempt one (`:102`). One idle tab = 6 req/min = **20% of the budget**, shared with any SDK traffic from the same machine; two tabs = 40%. And a 429 answers **JSON**, which carries no meta-refresh tag — so the first 429 **stops the refresh chain permanently** and leaves the operator looking at raw JSON. **Decision: 10s stands.** Widening to 30s reads as dead on a status panel (O2), and the exposure is a *pre-existing* property of the plan, not something #316/#317/#318 introduced. Record the cost in `SECURITY.md`/`RUNBOOK.md` rather than trading the interval for it. If a later operator report shows real 429 contention, the right fix is making `/` auth-exempt-budget-eligible or widening `RATE_LIMIT` — both out of scope here.
- **VALIDATE:** test #5 — **plus one thing worth checking on a real browser, though not the one this task used to name.** The previous wording said what a browser sends on a meta-refresh reload *"is not knowable from this repo's source"* and treated `same-site` as a live 403 risk. **That is settled by the spec, not by a device** (MEDIUM-2): `same-site` means the initiator and target share a site but are **different origins**, and a meta refresh targets the *identical URL*. The only reachable values are `same-origin` (document-initiated) or `none` (if a UA treats it as user-originated), and the rule allows both — **`same-site` cannot occur, so the page cannot 403 itself.** Keep the on-device check anyway, re-aimed at the question the spec does *not* answer: **does the UA keep re-attaching cached Basic credentials across many minutes of refreshes without re-challenging**, and does the challenge/credential-cache cycle survive it? Watch it cycle for several minutes; one reload proves nothing.

### Task 3 — Accept HTTP Basic, and replace the CSRF immunity it removes

- **ACTION:** Extend `authorized()` (`:2069-2073`) to accept Basic **and report which scheme succeeded**; teach `RelaisHttpGate.decide` a `CROSS_SITE` reject for Basic; give a 403 the response vocabulary it currently lacks; emit a conditional challenge on 401.

- **STRUCTURE (read before writing — the shape of this task changed with #317):** the gate is no longer an inline `if` in `handle()`; it is `RelaisHttpGate.decide` (`RelaisHttpGate.kt:74-92`), a 9-line **ordering** function. Of the three ways to add the guard, take **(c)**:
  - **(a) pass the raw headers into `decide`** — rejected. Not because parsing in a pure object is wrong (it is not: `accept` and `authorization` already parse in the header loop at `:340-348`, and `decide` would only receive `String?` primitives, no different in kind from `contentLength`), but because the `Origin`/`Referer` host comparison is a **second algorithm**, and `decide`'s entire job is ordering. Putting an algorithm inside the ordering function is what makes it stop being one.
  - **(b) capture the scheme out of the `authorized` lambda via a mutable side-channel** — rejected. It re-splits the decision #317 deliberately unified and creates a second place where the auth outcome lives.
  - **(c) the gate learns the *outcome*, never the header values** — adopted. It honours the merged design's own thesis, stated in its KDoc at `RelaisHttpGate.kt:61`: *"Every effect is a supplier, not a boolean, and that is **load-bearing**."*

  ```kotlin
  fun decide(
    method: String,
    path: String,
    authorized: () -> AuthScheme?,          // was () -> Boolean
    rejectsAsCrossSite: () -> Boolean,      // new; consulted ONLY when the scheme is BASIC
    rateLimitOk: () -> Boolean,
    exemptRateLimitOk: () -> Boolean,
    contentLength: Int,
    maxBody: Int,
  ): Reject?
  ```
  plus a new `CROSS_SITE(403)` member on the `Reject` enum (`:37-42`). The pure predicates (`extractApiKey`, `rejectsAsCrossSite`) live beside the auth code in `RelaisHttpServer.kt`; `handle()` closes over the already-parsed header locals and passes a closure, exactly as it already does for `rateLimiter.allow(ip)`. **This is also why the codex-P2 `Origin`/`Referer` fallback needs no extra gate parameters** — `method`, `origin`, `referer` and `host` are all captured by the lambda.

- **ORDERING — load-bearing, and the plan's old phrasing no longer names a unique position.** "Immediately after the gate's successful `authorized()` call" was written against a gate where auth and rate limiting shared one `if`. Post-#317 there are two candidate positions and they differ in whether a cross-site reject **consumes the operator's budget**. Write the body as a nested `if`, not a one-liner:
  ```kotlin
  val exempt = authExempt(method, path)
  if (!exempt) {
    val scheme = authorized() ?: return Reject.UNAUTHORIZED
    if (scheme == AuthScheme.BASIC && rejectsAsCrossSite()) return Reject.CROSS_SITE
  }
  // …then the two budgets, then the body cap — unchanged
  ```
  `CROSS_SITE` fires **before both rate suppliers**. Reason: an attacker page runs **in the operator's own browser**, so it shares the operator's source IP — metering those rejects would let a hostile page burn the operator's own 30/60s budget. That is the identical NAT-shared-budget argument the gate already makes for the 401 at `RelaisHttpGate.kt:48-52`. *(Codex reached the same placement from response ordering — otherwise a 429/413 would precede the 403. The two justifications are complementary.)* The nesting also makes HIGH-1's tail **visible in the code rather than derived**: an exempt path never runs `authorized()`, so it has no scheme and the guard structurally cannot fire on it.

  **Invocation counts are unchanged for every request shape reachable on `main` today.** (c) adds a supplier call only for `BASIC` + cross-site, a shape that cannot exist before this task, because Basic is not accepted at all.

- **IMPLEMENT:** Five pieces.
  1. **Top-level** `internal enum class AuthScheme { BEARER, BASIC }` (top-level for the same reason the functions are — see PLACEMENT) and a pure **top-level** `internal fun extractApiKey(header: String?): Pair<AuthScheme, String>?` — `Bearer <k>` → `(BEARER, k)`; `Basic <b64>` → base64-decode **with `java.util.Base64.getDecoder()`**, then take the substring after the first `:` **using `decoded.substringAfter(':', "")`, or an explicit `if (decoded.indexOf(':') < 0) return null`** → `(BASIC, k)`; **anything else, including a bare scheme-less key, → `null`**.

     **P1-1 — `java.util.Base64`, NOT `android.util.Base64`, and this is not a style preference.** `android.util.Base64` is already imported at `RelaisHttpServer.kt:20`, which makes it the obvious reach — and it would quietly destroy this task's entire negative test surface. `isReturnDefaultValues = true` (`Android/src/app/build.gradle.kts:209`) makes unmocked `android.*` calls return defaults in the JVM lane, and that file's own comment admits the tradeoff: *"masks accidental unmocked-Android calls."* **The failure is asymmetric, which is what makes it dangerous:**
     - 8a/8b/8c/8d (valid Basic decodes to the key) would **fail loudly** — a defaulted decode returns no bytes, so no key comes out.
     - 8e (malformed base64) and **8k (colon-less — HIGH-2's whole point)** would **pass for the wrong reason**: a defaulted decode yields nothing, `null` comes back, and `null` is exactly what they assert.

     An implementer who hits only the loud half may "fix" it by mocking `android.util.Base64` — which cements the vacuous negatives and leaves HIGH-2 pinned by a test that cannot fail. **The repo already wrote this rule down three times**, and the third even settles the API-level question:
     - `RelaisHttpIo.kt:270` — *"Pure and device-free: base64-encodes via `java.util.Base64` (NOT `android.util.Base64`) so it runs…"*
     - `RelaisImagesEndpoint.kt:25-26` — *"…so the envelope round-trips in plain JVM tests… **minSdk 31 carries java.util.Base64 (API 26+)**."* (`minSdk = 31`, `build.gradle.kts:44` — confirmed.)
     - `RelaisAnthropicParser.kt:145-150` + `:157` — the *injection* variant: `decode: (String) -> ByteArray = { b64 -> JvmBase64.getDecoder().decode(b64) }`, with production passing the Android-backed decoder.

     Use the **direct** `java.util.Base64` form (the `RelaisHttpIo`/`RelaisImagesEndpoint` shape), not the injected one — `extractApiKey` has no production reason to want a different decoder, and an injection point here is a seam an implementer could wire wrong (see R14). Use `getDecoder()`, **not** `getMimeDecoder()`: the strict decoder throws on non-alphabet input, which is what makes 8e's `runCatching` → `null` a real assertion rather than a silent acceptance. `RelaisHttpServer.kt:2084`'s existing `private fun decode` stays on `android.util.Base64` — it serves the multimodal image path and is not this task's concern.

     **HIGH-2 — the delimiter default is the whole point.** Kotlin's `String.substringAfter(delimiter)` defaults `missingDelimiterValue = this`, so a **colon-less** payload returns the receiver unchanged: `Authorization: Basic <base64(rawkey)>` would decode to the raw key and **authenticate**. That is the `removePrefix` footgun at `:2070` — the one this task exists to close — rebuilt in the new carrier, inside the security-sensitive PR, past a test table (8c/8d) that looks thorough precisely where the gap is. Pin it with **test 8k** and **prove it RED**: implement the bare `substringAfter(":")` first, watch 8k fail, then fix.

  2. **`authorized()` changes its return type from `Boolean` to `AuthScheme?`** — `null` means *not authenticated*, a non-null value is *authenticated via that scheme*. **Put the parse AND the compare in a pure `internal` helper, and make `authorized()` a one-line delegation** (P1-3):
     ```kotlin
     /** Pure: parses the header and performs the constant-time compare. JVM-testable. */
     internal fun authenticate(header: String?, apiKey: String): AuthScheme? {
       val (scheme, token) = extractApiKey(header) ?: return null
       // Constant-time compare to avoid leaking the key via response-timing differences.
       return if (MessageDigest.isEqual(token.toByteArray(), apiKey.toByteArray())) scheme else null
     }

     private fun authorized(header: String?): AuthScheme? = authenticate(header, apiKey)
     ```
     **Why the extra function rather than putting the body in `authorized()`.** Without it, the scheme-preservation step is tested by **nothing**. Tests 8a-8l exercise `extractApiKey` (pure); tests 8n-8p exercise `decide` with `CountingAuth(BASIC)` **injected**. An implementation that parses Basic flawlessly and then returns `BEARER` after a successful compare **passes every one of them** — and then every real Basic request skips the CSRF guard, so the feature silently does not exist with a green suite. Confirmed against the tree: there is **no JVM test class for `RelaisHttpServer`**, and **no test anywhere calls `authorized(`**. This extraction does **not** breach the plan's twice-stated no-visibility-widening rule: `authorized()` stays `private`, and `authenticate` is a **new** `internal` helper in exactly the sense `extractApiKey` already is — a member is not being widened. `MessageDigest.isEqual` is `java.security`, so the helper is JVM-clean (which is also why piece 1's `java.util.Base64` matters — an `android.util.Base64` call inside `extractApiKey` would defeat this test too).

     Pinned by **test 8s**, proven RED by implementing the compare to `return BEARER` unconditionally.

     **This is the third instance of this repo's signature failure, and the first caught in a plan rather than on hardware** — see R14, which names the two seams test 8s does *not* close.

- **PLACEMENT — all four helpers go TOP-LEVEL, after the class closes at `:2151`. This is load-bearing for testability, not style.**

  `authorized()` at `:2069` is an **indented `private fun` — a member** of `class RelaisHttpServer`, which opens at `:197` and closes at `:2151`. Putting `extractApiKey`, `authenticate`, `rejectsAsCrossSite` or `challengeHeaders` "beside `authorized()`" makes them **members too**, and an `internal` member still needs an *instance*, which needs a `Context`. **Tests 8s and 8m would not compile in the JVM lane** — the seam-closing test would silently never run, which is the exact failure R14 exists to prevent, reproduced in R14's own fix.

  **The repo already settles this, and it is the third time in this review that the answer was sitting in the codebase.** After the class closes there are **twelve** top-level `internal fun`s — `estimatePromptTokens` (`:2168`), `buildToolAssistantMessage` (`:2183`), `buildUsageObject` (`:2220`), `attachRelaisExtras` (`:2240`), `streamIncludeUsage` (`:2251`), `buildModelsResponse` (`:2267`), `parseEmbeddingInputs` (`:2323`), `validateEmbeddingInputs` (`:2348`), `resolveEmbeddingModel` (`:2364`), `buildEmbeddingsResponse` (`:2375`), `buildEmbeddingsError` (`:2402`) and `bindOrClose` (`:2423`). That is this file's established home for pure, JVM-testable, instance-free logic. Put the four there, in the same region.

  **`internal enum class AuthScheme` moves too — it is a fifth declaration, not a detail of the four.** If the enum stays nested beside `authorized()` while the helpers go top-level, every unqualified `AuthScheme` reference in them **fails to compile**, and the plausible "fix" is to drag the helpers back inside the class — undoing P1-1 and re-breaking 8s. Declare it top-level in the same region as the four functions.

  The parameter lists already filed require **no change** to move — `extractApiKey(header)`, `authenticate(header, apiKey)`, `rejectsAsCrossSite(method, secFetchSite, origin, referer, host, tls)` and `challengeHeaders(status, accept)` take everything explicitly and read no instance state. That the signatures survive the move intact is the check that the placement is right.

  **Write the reason into a comment at the helpers, naming the property not the mechanism** (this repo's own lesson — a `mainHandler.post`-based safety argument was falsified by an unrelated swap to an executor): something of the form *"Top-level, not members: these are unit-tested directly from `RelaisHttpAuthTest` in the JVM lane, which cannot construct a `RelaisHttpServer`."* Without it someone tidies them into the class and the tests stop compiling for a reason nobody connects back to here.

     **`authorized()` has exactly one call site** — the lambda at `:360`. Grep to confirm, and note the decoy (LOW-1): `grep 'fun authorized('` also returns `RelaisTaskerActivity.kt:82` (called at `:71`), an unrelated private `authorized(token: String)` on the intent-ABI path. It takes a bare token, not a header, and is **not** part of this change.

     **LOW-2 — settle the scheme-token case and the `.trim()`, explicitly.** Today `removePrefix("Bearer ")` is case-**sensitive**; RFC 7235 makes the `auth-scheme` token case-**insensitive**. **Decision: keep matching case-sensitively (`Bearer ` / `Basic `).** Accepting `bearer`/`basic` would be a *widening* shipped in the same diff as an advertised tightening, and nothing in the repo or its docs ever sends a lowercase scheme (`RelaisExperiments.kt:244/279/314/350`, `HttpChatTransport.kt:78`, and `RelaisClientConfig.kt:94` all use `Bearer`). **Keep the `.trim()`** on both branches — it is existing behaviour on the Bearer path (`:2070`) and dropping it would be a second silent tightening. Record both choices in `SECURITY.md`; a reviewer who reads RFC 7235 and not this line will otherwise file it as a bug.

  3. Pure **top-level** `internal fun rejectsAsCrossSite(method: String, secFetchSite: String?, origin: String?, referer: String?, host: String?, tls: Boolean): Boolean`. Pass `{ rejectsAsCrossSite(method, secFetchSite, origin, referer, host, tls) }` as the gate's new supplier — the closure captures `tls` from the constructor property at `:200`. Bearer requests never reach it.

     **Four headers to parse, not three — and `host` is the one the whole comparison rests on.** The header loop's `when` (`:340-348`) today has arms for `content-length`, `authorization`, `accept`, `content-type` and the gated `x-relais-session`. There is **no `host` arm**, so the request's own host is not available anywhere in `handle()`. Add all four — `sec-fetch-site`, `origin`, `referer`, `host` — alongside `accept`. Forgetting `host` does not fail to compile; it produces a `null` that makes the fallback reject *every* non-GET request with an `Origin`, which looks like the guard working.

     Rules:
     - **`secFetchSite` present:** `true` **only** for `"cross-site"` and `"same-site"`. `"none"`, `"same-origin"` and any unrecognised value → `false`.
     - **`secFetchSite` absent AND `method != "GET"`** (codex P2, PR #310): derive a **canonical authority** from `origin` (preferred) or else `referer`, derive one from the `host` header, and compare them. Mismatch, **or both `origin` and `referer` absent**, **or `host` absent**, → `true`. See the next paragraph — "compare the host" is the under-specification that sinks this.
     - **`secFetchSite` absent AND `method == "GET"`:** `false`, regardless of `origin`/`referer`. A GET is not the state change this guard exists to stop, and the address-bar and meta-refresh cases have no `Origin`/`Referer` to check either.

     **P1-2 — "compare the host" has two opposite wrong readings, and test 8j as originally filed passed under both.** The node advertises itself as `https://<ip>:8443` (`RelaisHttpServer.kt:883`), so a same-origin form POST arrives as `Origin: https://<ip>:8443` with `Host: <ip>:8443`. Conventional URI host extraction (`java.net.URI(origin).host`) **drops the port**. Therefore:
     - Compare the extracted *hostname* against the raw `host` header value and you compare `<ip>` to `<ip>:8443` — **every same-origin POST falsely rejects.**
     - Strip the port from both sides to "fix" that and a **cross-origin different-port** attacker (`https://<ip>:9999`) now compares equal — **the guard is open.**

     Both faults were invisible to 8j as first written, because it used `https://<node-host>` with **no port** and had no `Referer`-only case. Specify the comparison, do not leave it to be inferred:

     > **Mirror ONLY the parse shape of `WebhookGuard.check` — lines `:54-58`, and nothing below them.** Reuse exactly four moves: `runCatching { URI(s) }.getOrNull() ?: return <reject>` (malformed rejects, never throws) → `uri.scheme?.lowercase()` → `uri.host?.lowercase() ?: return <reject>` (hostless rejects) → fail **closed** on both. Everything after `:58` belongs to a different threat model — see the boundary below.
     >
     > **1. Scheme first.** The parsed `origin`/`referer` scheme, lowercased, must equal the listener's — `"https"` iff `tls`, else `"http"`. Mismatch ⇒ **cross-site**, before any authority comparison runs.
     >
     > **2. Then canonical authority**, computed **symmetrically on both sides** = lowercased host **plus** an explicit port. From `origin`/`referer`: parse with `java.net.URI`, never string-slice, and fill an absent port from **that URL's own scheme** (`https` → 443, `http` → 80). From the `host` header: lowercase it and fill an absent port from **the listener's scheme** via `tls` (`tls` → 443, else 80). Compare as whole strings.
     >
     > **Split the `Host` header bracket-aware, never on "contains `:`".** IPv6 literals keep their brackets on both sides (`[::1]:8443`) — `URI.getHost()` returns them bracketed and the `Host` header carries them bracketed, so they agree **provided neither side is string-sliced**. But a portless `Host: [::1]` *contains* colons, so a "has a colon ⇒ has a port" test skips the default-port fill on the `Host` side while the `Origin` side fills to `[::1]:443`, and a valid same-origin request rejects. **Find the port as the segment after the LAST `]` (bracketed form) or after the only `:` (unbracketed); a bracketed host with nothing after `]` is portless.** Pinned by 8j row (xiii).

     **BOUNDARY — `rejectsAsCrossSite` must NOT call `WebhookGuard.check`, and must not copy anything below `:58` (round-5 P1).** `WebhookGuard` decides whether an **outbound** URL is safe for *this node to call*; `rejectsAsCrossSite` decides whether an **inbound** `Origin` names *this listener*. Different threat models, opposite directions. Three of its steps are actively wrong here, and one of them breaks the feature outright:

     | `WebhookGuard` step | Why it must not come along |
     |---|---|
     | DNS-resolves the host (`:63-64`) | **Network I/O on every gated request.** The `Origin` header is a *name to compare*, not an address to reach; resolving it adds a blocking lookup to the hot path and a DNS-failure mode to an auth decision |
     | Allowlist bypass (`:66`) | Returns `Allowed` **before** the scheme check at `:68`, so an allowlisted host skips it entirely. A bypass around a rule this task exists to enforce |
     | `classify()` on resolved addresses (`:70-72`) | **This one breaks the dashboard.** `classify` blocks `isSiteLocalAddress` — *"private // 10/8, 172.16/12, 192.168/16"* (`:81`) — and `isLoopbackAddress` (`:78`). Blocking RFC1918 is exactly what an SSRF guard is *for*, and **RFC1918 is the only address this dashboard is ever reached on.** Copy it and every legitimate same-origin POST 403s, while looking like the guard working |

     Task 3 does **no** DNS resolution, **no** address classification, and consults **no** allowlist. It compares two strings. Pinned by **8j row (xii)**: same-origin RFC1918 (`https://192.168.1.2:8443` vs `Host: 192.168.1.2:8443`, `tls = true`) → **allowed**. Without that row an SSRF-copy failure is indistinguishable from the guard working.

     **Correction to this plan's own earlier wording:** rounds 4's text described `WebhookGuard` as putting *"the scheme check ahead of the policy decision."* **That is not what the file does.** The order is parse → resolve → **allowlist bypass (`:66`)** → scheme check (`:68`) → classify. The first policy decision is the allowlist, and the scheme check sits *after* it — which is precisely the step that must not be mirrored. The claim was written from the four lines quoted rather than from the function; it is the same "citing a file is not reading it" error the grep-first rule is supposed to prevent, committed inside the fix that introduced the rule.

     **The scheme check is not redundant with `tls`, and omitting it leaves the guard open (round-4 P1).** `tls` as introduced affects only *omitted*-port defaulting; it never constrains the scheme itself. So `Origin: http://node:8443` against `Host: node:8443` on a **TLS** listener canonicalises to `node:8443` on both sides, **compares equal, and is allowed** — despite `http://node:8443` and `https://node:8443` being genuinely different origins, which is the entire thing this function decides. Every 8j row as previously filed shared a scheme between the two sides, so **none of them could catch it**; new row (x) is the explicit-port scheme mismatch.

     A URL that parses but has **no host** (`uri.host == null`) must reject, exactly as `WebhookGuard.kt:57-58` does — not fall through to a comparison against `null`.

     **`rejectsAsCrossSite` therefore takes a sixth parameter, `tls: Boolean`** — `RelaisHttpServer` already holds it as a constructor property (`private val tls: Boolean`, `:200`), so the closure in `handle()` supplies it exactly as it supplies `host`.

     **Retraction — an earlier draft of this rule was asymmetric, and its justification was wrong twice over.** That draft filled the port on the `Origin` side only, took `Host` raw, and argued (a) that a `tls` parameter would "push server state into a pure function", and (b) that the asymmetry failed "strict, not loose — the direction a security check should fail." Both legs are wrong:

     - **(a) is a category error.** A *parameter* is not state. `rejectsAsCrossSite(…, tls)` is exactly as pure as without it: same inputs, same output, no instance, no ambient read. `decide` already takes `contentLength` and `maxBody` in precisely this spirit — the gate's own KDoc calls them *"matching the types at the call site rather than widening at the boundary"* (`RelaisHttpGate.kt:71-72`). The purity objection was protecting nothing.
     - **(b) is wrong in the direction that matters, and contradicts HIGH-3.** On a listener bound to a **default** port, `https://node/` sends `Origin: https://node` and `Host: node`. The asymmetric rule canonicalises the origin to `node:443`, compares it against a raw `node`, mismatches, and **403s a genuine same-origin form submission**. That is not the guard tightening; it is the feature breaking — *the identical failure mode HIGH-3 spends a whole section arguing against* ("the form renders enabled, the operator clicks `SET MODEL`, and gets an opaque 403 with no diagnostic"). And 8j as filed covered only explicit `:8443`, so **no test would have caught it.** Symmetric normalization makes the default-port case match correctly; row (ix) of 8j pins it.

     **`tls` is load-bearing — comment it as such at the parameter.** Name the property, not the mechanism: *"`tls` selects the default port for a `Host` header that omits one. Today the node binds `:8443`/`:8080` so `Host` always carries a port and this never fires — but a move to 443 or 80 makes it the only thing keeping a same-origin POST from 403ing."* A future 443/80 migration will read that line at the moment it matters; a note buried in a plan document will not.

     A malformed `origin`/`referer` must parse to `null` and therefore **reject** — not throw, not silently pass. Wrap the parse in `runCatching`, mirroring `WebhookGuard.kt:54-55`. Extended coverage is **test 8j's fourteen rows** below — and see the note there on *why* row count is not a proxy for coverage.

     **This branch is DORMANT in PR-A and load-bearing in PR-B — and that is exactly how it gets lost.** Nothing in the tree POSTs from a browser page until PR-B adds the form, so PR-A ships a rule no PR-A test exercises against a real navigation. Its correctness in PR-B **depends on a header PR-B must change**: the dashboard sends `Referrer-Policy: no-referrer` (`:896`), which per MDN makes a form POST arrive with `Origin: null` and no `Referer` — so under the rule above, *any UA that omits `Sec-Fetch-Site` would 403 the node's own form*. See research item 6, *Decisions → HIGH-3*, and the PR-B Files-to-Change row, which is flagged as a blocker. **Accepting `Origin: null` is not the alternative** — sandboxed iframes and cross-origin redirects send exactly that.

  4. **MEDIUM-0 — give 403 a response vocabulary. Four sub-steps; the third is the one that ships silently if skipped.**
     1. `reason()` (`:2086-2100`) has no 403 arm and falls through to `else -> "ERR"` (`:2098`) — a 403 would put `HTTP/1.1 403 ERR` on the wire. Add `403 -> "Forbidden"` beside `401 -> "Unauthorized"`.
     2. `RelaisError` (`RelaisError.kt:33-56`) has exactly eight types and none for forbidden. Add one — `const val PERMISSION = "permission_error"` — with a one-line KDoc matching its neighbours' style (*"Valid credentials, rejected request context — cross-site use of ambient Basic credentials (403)."*).
     3. **Add its row to the type-constant enumeration in `RelaisErrorTest.kt:57-66`**, which asserts all eight existing constants by literal value. A ninth constant with no row there is unpinned.
     4. Use it at the single new call site — the `CROSS_SITE` arm of the reject `when` (`:373-389`).

     **Why this rides in PR-A rather than PR-B.** The reason phrase is cosmetic on the wire (RFC 7230 §3.1.2 makes it advisory), but the **type is substantive**: reusing `AUTHENTICATION` for a CSRF 403 is actively wrong — the credential *is* valid, the request *context* is what is rejected — and an OpenAI-compatible client seeing `authentication_error` may enter a credential-refresh/retry loop against a request that can never succeed. `RelaisError`'s own KDoc (`:17-30`) makes envelope consistency the file's thesis and names LiteLLM and Open WebUI as the clients that trip on drift. PR-A is the change that introduces the **first 403 in the tree** (grep: the only other 403 handling is client-side, `RelaisHuggingFace.kt:186` and `ModelDownload.kt:72`); deferring means PR-A knowingly ships a mislabelled envelope and hands PR-B a defect it did not cause.

  5. **MEDIUM-3 — `WWW-Authenticate` needs a concrete insertion point.** Task 3's old claim that `reply()` takes headers so *"no new seam is needed"* is true but insufficient: the rejection is now a **single** `reply(reject.status, body)` at `:390`, fed by an exhaustive `when` that yields **only a body** (`:373-389`). The implementer needs a *parallel* computation, and must not attach the challenge to 429/413:
     **Extract the decision as a pure helper (P2-4) so test 8m is actually executable.** Inline in `handle()`, 8m has nothing to call — `handle()` is not JVM-reachable — which is why the placement table previously hedged it to "manual-only if no helper is extracted". Scope the helper; do not leave the hedge:
     ```kotlin
     /**
      * Pure: the 401-and-HTML-only Basic challenge. Takes the STATUS, not `RelaisHttpGate.Reject` —
      * see below. JVM-testable ([RelaisHttpAuthTest]).
      */
     internal fun challengeHeaders(status: Int, accept: String?): List<String> =
       if (status == 401 && accept?.contains("text/html") == true)
         listOf("WWW-Authenticate: Basic realm=\"Relais\", charset=\"UTF-8\"")
       else emptyList()

     // …at the single reject site:
     reply(reject.status, body, challengeHeaders(reject.status, accept))
     ```
     **Take `status: Int`, not the `Reject` enum.** Typing the parameter as `RelaisHttpGate.Reject` would compile — same module — but it drags a gate type into the auth helper and into `RelaisHttpAuthTest.kt`, which otherwise touches no gate types at all; the `Reject` idiom belongs in `RelaisHttpGateTest.kt`, and splitting it across both files is how the placement table starts lying again. `status` is exactly equivalent here (`UNAUTHORIZED` is the only `Reject` carrying 401) and it keeps 8m in one file with one vocabulary. The call site still reads `reject.status`, so the "never a repeated literal" property from the ERROR_HANDLING pattern is preserved.

     Keep the `when` an **expression** yielding the body only — that is what makes Kotlin enforce exhaustiveness, and it is why `CROSS_SITE` cannot be added without also adding its arm. **Test 8m** is now a real JVM test in `RelaisHttpAuthTest.kt`: `401` + `text/html` → the challenge; `429`, `413` and **`403`** with the same `Accept` → `emptyList()`; `401` with a non-HTML `Accept` → `emptyList()`. *The `403` row matters: a 403 means the credential was accepted, so challenging for it would tell a browser to re-prompt for a key that is already correct.* Note what 8m still does **not** cover — that `handle()` actually calls this at the reject site — which is R14's seam S3, closed by the probe.

     **Provenance of the non-GET fallback — codex review (P2, PR #310).** The `null`-allowed branch added for H1 is still CSRF-able for state-changing requests: a browser/WebView that omits `Sec-Fetch-Site` entirely (Fetch Metadata is not universal — older Safari, some embedded WebViews, some header-stripping proxies) reads as `null` and is allowed by design, so a foreign page loaded in such a client could fire an authenticated Basic `POST /select-model` once credentials are cached. Scoping the fallback to non-GET is what keeps GET navigation — address bar, bookmark, and the meta-refresh reload, which has the same missing-header property — untouched. Pinned by **test 8j**.

- **MIRROR:** four things, three of them in the tree already — **grep before writing any of them** (see *The rule that falls out*):
  - **not** the old inline gate — that code no longer exists (HIGH-1). Extend `RelaisHttpGate.decide`; the error envelope and the reply stay in `handle()`. Mirror the current reject block at `RelaisHttpServer.kt:368-392` (the exhaustive body-only `when`, the single `reply(reject.status, …)`).
  - the supplier discipline documented at `RelaisHttpGate.kt:61-67`.
  - **`WebhookGuard.kt:54-58` — and *only* `:54-58` — for the URL parse in `rejectsAsCrossSite`**: malformed-rejects, scheme lowercased, host lowercased-or-reject, fail closed. Do not write that parse from scratch; it exists and is already security-reviewed. **Do NOT call `WebhookGuard.check`, resolve DNS, call `classify`, or consult an allowlist** — those are outbound-SSRF policy, and `classify` blocks RFC1918, which is the only network this dashboard runs on. Read the boundary table in piece 3 before touching this.
  - AUTH_PATTERN in *Patterns to Mirror* — one predicate, one credential, one constant-time compare.
- **IMPORTS:** **`java.util.Base64`** — aliased (`import java.util.Base64 as JvmBase64`), matching `RelaisAnthropicParser.kt:157`'s idiom, because `android.util.Base64` is already imported unaliased at `RelaisHttpServer.kt:20` and the two would collide. **Do not reach for the already-imported `android.util.Base64`** — see piece 1. `java.security.MessageDigest` is already imported and stays.
- **GOTCHA:**
  - Wrap the base64 decode in `runCatching` — malformed input must return `null`, never throw. Same for the `Origin`/`Referer` URL parse.
  - **This task adds four pure helpers to a file the plan itself cites as too large — name the tension, do not let a reviewer find it.** `RelaisHttpServer.kt` is **2432** lines against an under-800 target, and the repo's rule (CLAUDE.md) is *prefer extracting a new file over growing an existing large file* — the very rule this plan invokes to justify **not** relocating `handleDashboard` (LOW-4) and to justify `RelaisHttpPages.kt` in PR-B. Adding `extractApiKey`, `authenticate`, `rejectsAsCrossSite` and `challengeHeaders` here grows it further. **The deferral is deliberate:** PR-A's minimal blast radius for the security reviewer (O4) outranks file hygiene for ~40 lines, and all four are pure and `internal`, so relocating them later is a move with no call-site changes beyond an import. Say so in PR-A's description rather than leaving it to be scored as the plan breaking its own rule. If a reviewer prefers a new `RelaisHttpAuth.kt` from the start, that is a reasonable call and costs nothing structural — it is the *timing* that is being deferred, not the principle.
  - **Nothing added in this task may call `android.*`.** `extractApiKey`, `authenticate`, `rejectsAsCrossSite` and `challengeHeaders` are all pure and JVM-testable **by design, not by accident** — that is the only reason PR-A has any real test coverage at all, given there is no `RelaisHttpServer` unit test. A single `android.util.*` call inside any of them silently converts its negative test rows into vacuous ones under `isReturnDefaultValues = true` (`build.gradle.kts:209`).
  - **Do not add a length check or an early `return false`** before the compare; that reintroduces the timing signal the constant-time compare exists to remove (R6). The scheme parse is the only nullable step and it is not key-dependent, so it leaks nothing.
  - `accept` is lowercased at parse (`:343`); lowercase `sec-fetch-site` the same way and compare against lowercase literals.
  - **Allow `none`** — see research item 4; rejecting it `403`s the operator's very first address-bar navigation, which is the feature.
  - **`/health` is no longer the only auth-exempt path.** #318 added `GET /ca.crt` (`RelaisHttpGate.isCaCertPath`, `:124`; `authExempt`, `:127-128`). This is benign for the CSRF guard — an exempt request never runs `authorized()`, so it has no scheme and the check structurally cannot fire — but the nested-`if` shape above is what makes that true by construction rather than by argument. Do **not** hoist the guard out of the `!exempt` block.
  - **The bare-key tightening is wire-visible**: today `header?.removePrefix("Bearer ")` returns the header *unchanged* when the prefix is absent, so `Authorization: <rawkey>` is accepted. This task rejects it. Every doc and the README specify `Bearer`; the acceptance is an accident of `removePrefix`, not a contract. Record it in `SECURITY.md` and `.claude/HANDOFF.md` and pin it with test 8g. **HIGH-2 is the same bug wearing the new carrier** — fix both or neither is fixed.
  - **The manual curl checks below are not evidence that a browser works.** Checks 4, 5 and 7 pass only because they hand-set `-H 'Sec-Fetch-Site: same-origin'`. A plain `curl -u ":$KEY" -X POST` will 403 under this rule. Document that in `RUNBOOK.md` so an operator does not read it as a broken node.
- **VALIDATE:** tests #8a-8r, each in the file the *Which file each new test lands in* table names — 8n/8o/8p go in `RelaisHttpGateTest.kt`, **not** the new auth test, so the ordering evidence stays in one place. Plus the `RelaisHttpGateTest.kt` migration and its RED re-proof (see *Test-file migration* under Testing Strategy); `curl -i` still gets a bare 401; manual checks 1-3, 6, 6d, 6e and 6f — **6e is the only verification 8r's reason phrase gets**, deliberately.

### Task 4 — Extend `DashboardStatus` with the selector inputs

- **ACTION:** Add three fields + three parameters to `assembleDashboardStatus`.
- **IMPLEMENT:**
  - `val availableModelIds: List<String>` — empty ⇒ render no form.
  - `val switchLocked: Boolean`.
  - `val pendingModelId: String?` — the configured id when it differs from the resident one, else `null`.

  In the handler (`RelaisHttpServer.kt:869-891`), build the list as
  **`(provisionedIds(provisionedOnDisk()) + RelaisConfig.modelId(context)).filter { RelaisRuntimeCompat.incompatibleReason(it) == null }.sorted()`**. Source `pendingModelId` by comparing `RelaisConfig.modelId(context)` against `RelaisEngine.residentModelId` (`RelaisEngine.kt:325`).
- **MIRROR:** the eligibility rule at `RelaisModelSwap.kt:107`; the existing data class + assembler, `RelaisDashboard.kt:34-112` — note #318 added a 14th field, `cert`, so this task's three make **17**.
- **IMPORTS:** none new in `RelaisDashboard.kt` (the fields are plain types); `RelaisRuntimeCompat` is same-package.
- **GOTCHA — four separate traps, all verified:**
  1. **`provisionedIds` returns `Set<String>`, not `List`** (`RelaisModelRegistry.kt:72`). `.sorted()` supplies both the `List` and a deterministic order.
  1b. **Ordering: lexicographic, and copy §1.4 L93's "catalog order" must be amended to say so.** There is no cheap catalog-order source. The one that exists, `RelaisModelCatalog.curatedModels()` (`RelaisModelCatalog.kt:108-115`), is **blocking and network-backed** behind a 5-minute TTL (`CURATED_TTL_MS`, `:35`; the cache check at `:123`) — putting it in `handleDashboard` would make a page that auto-refreshes every 10s depend on a network fetch and stall on a cold cache offline, which is the opposite of what this node is for. `Set` iteration order would also be filesystem-enumeration order, which is not stable enough to assert in a test. Lexicographic is deterministic, offline, and test-pinnable. Fold this into O1's copy amendment — it is the same sign-off.
  2. **The configured id must be unioned in.** `RelaisModelSwap.kt:79-80` keeps `configuredModelId` swap-eligible on its own *"so the operator's current selection works before it has been recorded."* Source the dropdown from the registry alone and, in that pre-recording window, the **currently-serving model is missing from its own dropdown** — no `selected` option, and the operator can only switch away.
  3. **Compat filter is mandatory (C1).** `RelaisRuntimeCompat.incompatibleReason(id) != null` and `!isOfferable(id)` denote the *same* set — `loadability` derives `INCOMPATIBLE` only from `KNOWN_INCOMPATIBLE` (`RelaisRuntimeCompat.kt:121`) and `incompatibleReason` is that map's lookup (`:131`). Use `incompatibleReason` on **both** the filter here and the re-check in Task 8, so the two gates cannot drift and the re-check has the reason string to render.
  4. **`RelaisModelRegistry` is the safety boundary** — per the KDoc at `RelaisHttpServer.kt:1287-1290` it only grows on a locally-successful provision, so a client-named model can complete a download the operator already made but can never originate one. Do not widen past registry ∪ configured.

  **Single-read requirement:** compute `switchLocked` from the *same* `RelaisEngine.startupInProgress` read that feeds `assembleDashboardStatus` — capture one local `val` and pass it to both, or a mid-swap page can show `LIVE` beside a disabled form.
- **VALIDATE:** compile; tests #1, #1b, #10.

### Task 5 — Render the form

- **ACTION:** Add the switch form and the pending hint to the existing Node Status panel; **invert** the read-only test.
- **IMPLEMENT:** Per copy §1.4/§2.4 — `<form method="POST" action="/select-model">`, `<select name="model" id="model">` with one `<option value="…">` per available id (`selected` on the configured id), submit labelled `SET MODEL`. Amber `#FFB000` background, charcoal `#0B0B0D` text, bold, letter-spacing 2px, 6px radius. When `switchLocked`, add `disabled` to both controls, `opacity: 0.5`, and the literal `model locked while starting`. When `pendingModelId != null`, render copy §1.4's pending hint with O1's amended text. Delete the `READ-ONLY` claim at `RelaisDashboard.kt:176`.
- **MIRROR:** the row/panel structure already in `renderDashboardHtml` (`RelaisDashboard.kt:340-375`, the flat status table); the escaping discipline at `:159-174` (closed-set CSS strings are *not* escaped; user content always is).
- **IMPORTS:** none.
- **GOTCHA:** The form goes **inside the existing Node Status panel**, after the `shed total` row (`:371`) — there is no `MODEL` panel and no `SERVING` row on this page today (the table is flat, with a lowercase `model` row at `:347`), and building them is explicitly out of scope. `RelaisDashboardTest.kt:396-403` (`…contains no model-switch form or select-model action (read-only scope)`) **fails by construction** — replacing it with its positive form is an explicit task, not incidental cleanup. Every id goes through `escapeHtml` (`:159-174`) including inside `value="…"`, since that is attribute context. Copy §2.5: no new colors — `#FF5247` appears on this page only inside a comment (`:274`). **The pending hint is the operator-visible signal for the one gap Task 7's `Boolean` return cannot close** (a swap that won the CAS but then bailed at `RelaisEngine.kt:455-458` because the file vanished): config reads B, resident reads A, the hint says so.
- **GOTCHA — the form's arrival is what makes PR-A's dormant `Origin` fallback live (HIGH-3).** This is the commit that must also narrow `Referrer-Policy: no-referrer` → `same-origin` on the dashboard (`RelaisHttpServer.kt:896`), leave `/experiments` (`:923`) alone, add the naming-the-property comment at the header site, and add the unit test that makes a revert fail. See *Decisions → HIGH-3* for all four; shipping the form without them 403s the node's own form on any UA that omits `Sec-Fetch-Site`.
- **VALIDATE:** tests #1-4, #10.

### Task 6 — Pure form-body parser + validator

- **ACTION:** Add two pure functions to `RelaisDashboard.kt`.
- **IMPLEMENT:** `internal fun parseFormField(body: String, key: String): String?` — split on `&`, then on the **first** `=`, URL-decode both halves, return the match. `internal fun validateModelChoice(requested: String?, available: List<String>): String?` — returns the id iff present in `available`, else `null`.
- **MIRROR:** NAMING_CONVENTION (`internal`, pure, KDoc'd, test-covered).
- **IMPORTS:** `java.net.URLDecoder`, `java.nio.charset.StandardCharsets`.
- **GOTCHA:** **No form parser exists in this codebase** (grep-confirmed — the readers handle JSON and multipart only), so this is genuinely new code, not a reuse. `URLDecoder.decode` throws on a malformed `%` escape — wrap in `runCatching`. Bound the split (`limit`) so an `&`-flood can't blow up; the body is already capped by `MAX_BODY_BYTES` (`:78`, enforced by the gate at `:366`). Keep both functions `Context`-free so they test on the JVM.
- **VALIDATE:** tests #6, #7.

### Task 7 — `ensureModelSwapInBackground` reports whether it dispatched

- **ACTION:** Change `RelaisEngine.ensureModelSwapInBackground` (`:433`) to return `Boolean`.
- **IMPLEMENT:** `fun ensureModelSwapInBackground(context: Context, target: ProvisionedModel? = null): Boolean` — `if (!swapDispatching.compareAndSet(false, true)) return false` at `:434`, `return true` after the `thread { … }` block is started. Nothing else in the body changes.
- **MIRROR:** the existing CAS at `:434` and its comment; do not restructure the thread.
- **IMPORTS:** none.
- **GOTCHA:** **`true` means "this call won the CAS and started a swap thread", not "the swap succeeded."** The thread still bails at `if (!File(path).exists())` (`:455-458`), and engine-create failures roll back at `:474-490`. Task 5's pending hint is the operator-visible signal for the resulting config-ahead-of-engine state — the two are designed together, do not ship one without the other. **Caller audit before changing the signature:** grep-verified there is exactly **one** production call site, `RelaisHttpServer.kt:1330` (a plain statement call inside the `SwapThenRetry` arm), plus doc-comment mentions in `RelaisModelSwap.kt`, `RelaisModelRegistry.kt`, `RelaisModelProvisioner.kt` and two test comments. **No function reference (`::ensureModelSwapInBackground`) is bound anywhere** — that would fail to compile against a `(Context, ProvisionedModel?) -> Unit` type. Ignoring the new return value at `:1330` is correct and needs no change there. Re-run both greps in the Validation block on the branch rather than trusting these numbers.
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
  The router arm in `RelaisHttpServer.kt` reads the body with the private `readBody`, computes `available` with the private `provisionedOnDisk()`, and passes a lambda closing over `respondText(ctx.sock, …)`. **Zero visibility changes**: `RequestContext` (`:800`), `readBody` (`:2081`), `respondText` (`:2105`), `provisionedOnDisk` (`:1278`) all stay `private`. feature-17 keeps sole ownership of the `RequestContext` widening.
- **IMPLEMENT:** Arm: `method == "POST" && path == "/select-model" -> handleSelectModel(ctx)` wrapper, placed next to the `GET /` arm at `:403`. Handler body, **in this order**:
  1. `parseFormField(body, "model")` → `validateModelChoice(requested, available)`. `null` ⇒ `400`, copy §1.6 page (`unknown model id — no change applied`, `‹ BACK` link), **nothing persisted**.
  2. `RelaisRuntimeCompat.incompatibleReason(id)?.let { … }` ⇒ `400` rendering that reason, **nothing persisted**.
  3. `RelaisEngine.ensureModelSwapInBackground(context, swapTargetFor(id, provisionedOnDisk()))`. `false` ⇒ `503` + `Retry-After: 25`, **nothing persisted**.
  4. Only on `true`: `ModelSwitch.applyManualId(context, id)`, then `303` with `Location: /`.

  CSP on every response from this handler carries `form-action 'self'`, as does `handleDashboard`'s (`:893`).
- **MIRROR:** HANDLER_PATTERN; the `SwapThenRetry` arm at `RelaisHttpServer.kt:1324-1339` — including its `503` + `Retry-After: 25` idiom at `:1332-1337`, which is why step 3 answers `503` rather than inventing a `409` for the same state.
- **IMPORTS:** `android.content.Context`; same-package `RelaisRuntimeCompat`, `ModelSwitch`, `swapTargetFor`.
- **GOTCHA — five, in priority order:**
  1. **Check order is load-bearing (C1 + M2).** Membership first, compat second. `RelaisModelSwap.kt:117-120` records why: an earlier revision checked compat first, so an **absent** known-bad id answered `Incompatible` — "telling the operator the file was unloadable when the real problem was that it was missing." Reversing steps 1 and 2 reintroduces exactly that regression.
  2. **The server-side compat re-check is not redundant with the dropdown filter.** A page rendered before a model became known-bad can still POST that id; the dropdown is client-supplied input. Both gates use `incompatibleReason` so they cannot drift.
  3. **Dispatch before persisting (H3).** Persisting first and then calling the swap is the bug: `ensureModelSwapInBackground` no-ops on its CAS (`RelaisEngine.kt:434`) while `swapDispatching` is held — cleared only in the `finally` at `:493-496`, i.e. at the end of the *whole* swap — so a second `SET MODEL` mid-swap would leave config naming B, the engine serving A, no retry scheduled, and a `303` that reads as success. The CAS is the only atomic gate; make it the arbiter.
  4. **Persist through `ModelSwitch`, never `RelaisConfig.setModelId` directly (H4).** `ModelSwitch`'s KDoc (`:19-28`) declares it the single source of truth for an operator model pick and lists the drift it consolidates. `applyManualId` (`:42-45`) calls `clearModelRef` **unconditionally** (`:43`); `RelaisConfig.setModelId` (`:193-209`) drops a ref only when the new value differs *and* the ref names a different model (`:206`) — a weaker guarantee. Update that KDoc in Task 9 to name the dashboard as the third surface.
  5. **`Sec-Fetch-Site` is handled inside `RelaisHttpGate.decide` in Task 3, not here.** Do not add a second per-route check. `form-action` does **not** inherit from `default-src`; `/experiments` sends `'none'` (`:920`) but the dashboard needs `'self'` or the form is blocked. Use `303`, not `302`, so the reload is a GET. Add `path == "/select-model" -> "/select-model"` to `endpointLabel` (`:2042-2068`) so the label doesn't fall through to `else -> "other"` and lose the series (M6 cardinality) — place it beside the `path == "/"` arm at `:2048`; both are exact-match arms so ordering between them is immaterial, but it must come **before** any `startsWith` arm that could shadow it.
- **VALIDATE:** Manual curl block below; on-device gate.

### Task 9 — Docs

- **ACTION:** Amend five docs; file two issues.
- **IMPLEMENT:**
  - **`docs/dashboard-copy.md`** — (a) §1.4 L95 → `model set: <id> — swapping, node restarts itself` and §1.4 L93's option ordering from `catalog order` → `sorted by id` (**both O1, both need sign-off**), and note the hot swap; (b) **add entries for the four strings this plan introduces that the doc does not yet have**: the `503` swap-busy page, the `400` unknown-id page, the `400` incompatible-with-reason page (which interpolates `RelaisRuntimeCompat`'s reason), and the `WWW-Authenticate` realm string; (c) strike Appendix rows 5-6 as already satisfied (grep-confirmed: `#FFCC44` and `#FF5247` are absent from the shipped CSS, `#FF5247` surviving only in a comment at `RelaisDashboard.kt:274`).
  - **`SECURITY.md`** (PR-A) — Basic accepted; HTML-only challenge; safe under TLS; the `Sec-Fetch-Site` guard and why `none` is allowed; the non-GET `Origin`/`Referer` fallback; **the bare-key tightening** (M3); **the scheme-token case decision** (LOW-2 — case-sensitive, `.trim()` kept, deliberately narrower than RFC 7235); the new `permission_error` type; and the 10s-refresh budget cost with its 429-terminates-the-chain behaviour (MEDIUM-1).
  - **`docs/RUNBOOK.md`** (PR-A) — browse `https://<phone-ip>:8443/`, accept the cert, blank username, key as password. **Plus: a plain `curl -u ":$KEY" -X POST` now returns 403** — scripted state changes must send `Sec-Fetch-Site: same-origin` or a same-host `Origin`. This is a behaviour change an operator will otherwise report as a broken node.
  - **`ModelSwitch.kt` KDoc** (`:19-28`, PR-B) — name the dashboard as the third surface (H4).
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
| 8i | `Sec-Fetch-Site` predicate | `GET` + `null`, `"none"`, `"same-origin"`, `"cross-site"`, `"same-site"`, `"garbage"` | `false, false, false, **true**, **true**, false` — **`none` must be allowed** or the first address-bar navigation 403s | **Yes** |
| 8j | Non-GET `Origin`/`Referer` fallback — **authority comparison (P1-2)** | `POST`, no `Sec-Fetch-Site`, `Host: <ip>:8443` throughout: (i) no `Origin`/`Referer`; (ii) `Origin: https://<ip>:8443`; (iii) `Origin: https://evil.example`; **(iv) `Origin: https://<ip>:9999`** — same host, **different port**; **(v) `Host: [::1]:8443` + `Origin: https://[::1]:8443`** — bracketed IPv6 literal; **(vi) `Host` absent entirely**; **(vii) no `Origin`, `Referer: https://<ip>:8443/` only**; (viii) `Origin: ht!tp://[[[` — malformed; **(ix) `tls = true`, `Host: node` (no port), `Origin: https://node` (no port)** — the default-port case; **(x) `tls = true`, `Host: node:8443`, `Origin: http://node:8443`** — same authority, **different scheme**; (xi) `Origin: https:///path` — parses, but no host; **(xii) `tls = true`, `Host: 192.168.1.2:8443`, `Origin: https://192.168.1.2:8443`** — same-origin **RFC1918**; **(xiii) `tls = true`, `Host: [::1]`, `Origin: https://[::1]`** — IPv6 **default** port, both sides portless; **(xiv) `tls = false`, `Host: node`, `Origin: http://node`** — **plaintext** default port | `true, false, true, **true**, **false**, **true**, **false**, **true**, **false**, **true**, **true**, **false**, **false**` — rows (ii)+(iv) catch port-stripping, (ii)+(v) catch string-slicing, (vii) is the only `Referer`-fallback coverage, (viii) must reject rather than throw, **(ix) catches asymmetric normalization**, **(x) catches the missing scheme check** (every other row shares a scheme, so only (x) can), **(xii) catches an SSRF-guard copy** — `WebhookGuard.classify` blocks RFC1918, so a wholesale mirror rejects the only network this dashboard lives on, and (xii) is the only row that would notice, and **(xiii) catches "contains `:` ⇒ has a port"**, which is true of *every* bracketed IPv6 literal and so skips the default-port fill on the `Host` side only, and **(xiv) catches default-port POLARITY** — (ix) and (xiii) both use `tls = true`, so a mutation that always fills **443** passes all thirteen and still rejects a valid default-HTTP same-origin request; (xiv) is the only row where the correct fill is **80**. **Each successive version of this table still passed the next round's bug:** three rows passed both port bugs, eight passed the asymmetric one, ten passed the scheme hole, eleven passed both the SSRF-copy and the IPv6 one, thirteen passed the polarity one | **Yes** |
| **8s** | **The real `authenticate()` PRESERVES the scheme (P1-3)** | `authenticate("Basic " + b64(":KEY"), "KEY")` and `authenticate("Bearer KEY", "KEY")` | **`BASIC`** and `BEARER` respectively. **Prove RED by implementing the compare to `return AuthScheme.BEARER` unconditionally** — under that bug 8a-8l and 8n-8p all still pass, every real Basic request bypasses the CSRF guard, and the feature silently does not exist. This row is the *only* JVM test of the parse→compare seam | **Yes** |
| **8t** | **End-to-end gate wiring (`BasicAuthGateProbe.kt`, on-device)** | a real loopback `RelaisHttpServer`, **five** real requests — see *Closing the seam class* below | Basic+`cross-site` → **403**; Basic+`same-origin` → 200; **Bearer**+`cross-site` → 200; Basic `POST` + **`Origin: http://127.0.0.1:<port>`** (`http`, not `https` — the probe server is `tls = false`; see the note under that table), no `Sec-Fetch-Site` → **not 403**; **(5) NO `Authorization`, `Accept: text/html` → 401 carrying exactly `WWW-Authenticate: Basic realm="Relais", charset="UTF-8"`** — the only cover for S3b, and the row a reader skimming the outcome list has twice now dropped. **Not CI** — hardware-gated, like every `*Probe.kt` | **Yes** |
| **8k** | **Basic, colon-less payload (HIGH-2)** | `Basic base64("KEY")` | **`null`** — no `:`, so no username field, so nothing to strip. **Prove RED by implementing bare `substringAfter(":")` first**; under that implementation this authenticates, which is the bare-key hole reopened through Basic | **Yes** |
| 8l | Scheme-token case is deliberately narrow (LOW-2) | `bearer KEY`, `basic base64(":KEY")` | `null` for both — pins the decision *not* to widen to RFC 7235's case-insensitive token in this diff | **Yes** |
| 8m | Challenge is 401-and-HTML-only (MEDIUM-3) | `challengeHeaders(status, accept)` over `401`/`429`/`413`/**`403`** × `Accept: text/html`, plus `401` × `application/json` | the challenge **only** for `401` + `text/html`; `emptyList()` for all five others. **The `403` row is the new one** — a 403 means the credential was accepted, so challenging would re-prompt for a key that is already correct | **Yes** |
| 8n | `CROSS_SITE` precedes both budgets (HIGH-4) | `decide` with `CountingAuth(BASIC)`, `rejectsAsCrossSite = { true }`, `Counting` on both budgets | `Reject.CROSS_SITE`; **`rate.calls == 0` and `exempt.calls == 0`** — a cross-site reject must not burn the operator's own budget, since the attacker page runs on the operator's IP | **Yes** |
| 8o | Bearer is untouched by the guard | `CountingAuth(BEARER)`, `rejectsAsCrossSite` as a **counting** supplier | not rejected; **the cross-site supplier is never called** (`calls == 0`) | **Yes** |
| 8p | An auth-exempt path cannot trip the guard | `GET /health` and `GET /ca.crt`, `rejectsAsCrossSite = { true }` | not rejected; **`auth.calls == 0`** — no `authorized()` runs, so there is no scheme (HIGH-1 tail; `/ca.crt` is exempt since #318) | **Yes** |
| 8q | 403 envelope **type** (MEDIUM-0) | `RelaisError.PERMISSION` | `"permission_error"` — one row added to `RelaisErrorTest.kt:57-66`'s constant enumeration, in that file's existing idiom | **Yes** |
| **8r** | 403 **reason phrase** (MEDIUM-0) — **manual-only, by design** | `reason(403)` | `"Forbidden"`, not the `else -> "ERR"` fall-through. **This half has no JVM coverage and must not acquire any:** `reason()` is `private fun` (`:2086`) and `RelaisHttpServer` has **no** unit test at all (R11) — reaching it would need an `internal` widening this plan forbids in *NOT Building* and in the Acceptance Criteria. Verified by **manual check 6e** instead. Do not "fix" this by widening; do not silently drop it either | **Yes** |
| 9 | Ring-buffer opt-out | `recordRequest("/", 200, inRecentLog = false)` | `recentRequests()` unchanged; aggregate counter **still incremented** | **Yes** |
| — | **Invert** `RelaisDashboardTest.kt:396-403` | shipped fixture | now **asserts** the form is present | No |
| — | **Referrer-Policy gate (PR-B, HIGH-3)** | the dashboard's header list | contains `Referrer-Policy: same-origin`, with an assertion message naming the CSRF dependency, so a revert to `no-referrer` **fails a test** instead of silently reopening HIGH-3 | **Yes** |

### Closing the seam class, not just the instance (R14)

**The three findings above are three instances of ONE defect**, and fixing them one at a time leaves
the class open. The shape: *a pure helper is tested, an injected fake is tested, and the wiring
between them is tested by nothing.* Three such seams exist in Task 3:

| # | Seam | Covered by |
|---|---|---|
| **S1** | `extractApiKey` → the constant-time compare: does a successful Basic auth **report `BASIC`**? | **Test 8s** (JVM), via the `authenticate()` extraction |
| **S2** | header loop → the `rejectsAsCrossSite` supplier: does `handle()` parse all four headers and pass them into the **right slots**? | **Nothing in the JVM lane.** 8i/8j call the predicate directly *with* a host argument; 8n injects `{ true }`. **P1-2's missing `host` arm lands exactly here** — omit it and every JVM test still passes |
| **S3a** | `authorized()` → its call site in `handle()`: is it called at all? | **Nothing in the JVM lane.** Probe rows 1-4 |
| **S3b** | `challengeHeaders()` → the `extra` argument of the sole `reply()`: is it called at all? | **Nothing in the JVM lane, and nothing in probe rows 1-4 either** — they are all authenticated, so none produces a challenge. **Probe row 5** |

S2 and S3 are not reachable from the JVM lane at any price PR-A should pay. This repo already has
the instrument for exactly that situation — CLAUDE.md: *"Endpoint/IO code that needs a real
capability check belongs in `androidTest` as a `*Probe.kt`"* — with direct precedent in
`ClientConfigEndpointProbe.kt` (already quoted as this plan's PROBE_STRUCTURE) and
`IncompatibleModel404Probe.kt`, which probes gate behaviour specifically.

**So: one new probe, `BasicAuthGateProbe.kt`, five requests against a real loopback server (test
8t).** Each request covers a seam no unit test can reach:

| Request | What it proves that no JVM test does |
|---|---|
| Basic + `Sec-Fetch-Site: cross-site` → **403** | The whole chain: header parsed → scheme preserved → supplier wired → gate ordered → `CROSS_SITE` arm reached |
| Basic + `Sec-Fetch-Site: same-origin` → **200** | The guard is not simply rejecting all Basic |
| **Bearer** + `Sec-Fetch-Site: cross-site` → **200** | The guard is scheme-scoped **end to end**, not just in `decide`'s injected view — this is the one that fails if `authenticate()` returns the wrong scheme in production but 8s was mutated away |
| Basic `POST`, no `Sec-Fetch-Site`, **`Origin: http://127.0.0.1:<port>`** → **not 403** *(scheme matters — see below)* | `host` is actually parsed (S2) and the authority comparison agrees with a real `Host` header — **the P1-2 fault, in the only place it is observable** |
| **No `Authorization` at all, `Accept: text/html`** → **401** carrying **exactly** `WWW-Authenticate: Basic realm="Relais", charset="UTF-8"` | **The other half of S3.** Every row above is *authenticated*, so deleting `challengeHeaders(reject.status, accept)` from the sole `reply()` call would pass 8m (which tests the pure helper in isolation) **and all four of them**. Without this row the probe reproduces, inside itself, the exact vacuity it was built to fix — the same shape as S2's "omit the `host` arm and every JVM test still passes" |

**The fifth row is not padding.** S3 has two halves — *is `authorized()` called?* and *is
`challengeHeaders()` called?* — and the first four rows only reach the first. `challengeHeaders` is
the more fragile of the two precisely because its output is a header nobody looks at unless a test
looks: an implementer who inlines the reply, or drops the `extra` argument during a rebase, breaks
nothing visible. Assert the header **exactly**, not merely that a 401 came back.

**`http://`, not `https://`, in the fourth row — and this is a trap, not a typo.** The probe pattern
this mirrors builds its server as `RelaisHttpServer(context, port = port, tls = false, bindAddr =
"127.0.0.1")` (`ClientConfigEndpointProbe.kt`, quoted in PROBE_STRUCTURE). With `tls = false` the
origin is `http://`, so an `Origin: https://127.0.0.1:<port>` would canonicalise against a scheme the
listener is not speaking and **mismatch even under a correct implementation** — and an implementer
watching the row fail would "fix" the comparison to make it pass, breaking the real rule to satisfy a
wrong test. Add a fifth row if you want the negative: `Origin: http://127.0.0.1:<other-port>` → 403,
which is the different-port case (8j row iv) observed end to end.

**State the limitation honestly rather than implying this is automated:** probes are **not CI** — they
need hardware and an explicit `-e RELAIS_PROBE 1`. This is a discipline gate, and it is the same bar
feature-18 shipped under. The repo's own lesson applies: *hardware-verified-or-not-done.* Put the
runnable `adb` line in the probe's file header, matching `ClientConfigEndpointProbe.kt`.

*Alternative considered and declined:* extracting the header-parse `when` (`:340-348`) into a pure
function would make S2 JVM-testable. Rejected — it restructures shipped code inside the
security-sensitive PR, which *NOT Building* forbids, and it trades a bounded hardware check for an
unbounded refactor. Revisit if a later PR has reason to touch that loop anyway.

**Which file each new test lands in.** The ordering evidence must not end up split across two files,
or the next reviewer cannot tell whether the counting discipline was preserved:

| Tests | File | Why |
|---|---|---|
| 8a-8h, 8k, 8l, **8s** | `RelaisHttpAuthTest.kt` (**new**) | Pure `extractApiKey` / `authenticate` — no gate involved. **8s is the seam test; it must be here and it must be proven RED** |
| 8i, 8j | `RelaisHttpAuthTest.kt` (**new**) | Pure `rejectsAsCrossSite` predicate — the *rule*, tested directly |
| **8n, 8o, 8p** | **`RelaisHttpGateTest.kt`** | These call `RelaisHttpGate.decide` with `CountingAuth`/`Counting`. They belong **beside** the four byte-identical blocks whose idiom they extend — that adjacency is what makes the ordering evidence readable as one argument |
| **8m** | **`RelaisHttpAuthTest.kt`** | **No longer conditional (P2-4):** Task 3 piece 5 scopes the pure `challengeHeaders(status, accept)` helper, so 8m is a real JVM test. The earlier "…if a helper is extracted, otherwise manual" hedge contradicted Task 3's claim that 8m *pins* the behaviour; the hedge is removed and the helper is scoped. **It takes `status: Int`, not `RelaisHttpGate.Reject`** — deliberately, so this file stays free of gate types and the `Reject` vocabulary stays in `RelaisHttpGateTest.kt` |
| 8q | `RelaisErrorTest.kt` | One row in the existing constant enumeration |
| 8r | **manual check 6e only** | `reason()` is private and this plan forbids widening it — see the table above |
| **8t** | **`androidTest/.../BasicAuthGateProbe.kt`** (**new**) | The only thing covering S2 and S3. Hardware-gated, not CI |

### Test-file migration (`RelaisHttpGateTest.kt`) — read before editing it

Retyping `authorized` to `() -> AuthScheme?` forces changes in the one test file that holds the
**mechanical evidence that gate ordering did not move**. A hand-migrated fake is precisely where
behaviour changes silently while every assertion still passes — the failure mode this repo has hit
twice. The boundary:

**Must stay byte-identical** — every `assertEquals` row *and* its message string:

- `:341-343` — `"auth must be evaluated"` / `"a failed-auth request must stay unmetered"` / `"a failed-auth request must not touch the exempt budget either"`
- `:353-354` — `"an exempt path must not run the key comparison"` / `"an exempt path is still metered"`
- `:367-368` — `"the exempt budget must be charged"` / `"the standard budget must be untouched"`
- `:376-377` — `"the standard budget must be charged"` / `"the exempt budget must be untouched"`

Do not reword, renumber, merge, or "improve" them. New shapes get new `@Test` functions (8n-8p).

**Cannot stay unchanged, by necessity:** `private class Counting(result: Boolean) : () -> Boolean`
(`:318-326`) is passed into the **auth** slot and stops typechecking. Add a sibling
`private class CountingAuth(private val result: AuthScheme?) : () -> AuthScheme?` with the same
`var calls` shape, used **only** in the auth position; `Counting` itself stays exactly as it is for
the two rate suppliers. **Four auth-slot call sites, not two** — two named locals and two inline
arguments, and missing the inline ones is a compile error the first two edits will not surface:

| Site | Today | After |
|---|---|---|
| `:334` (named `val auth`) | `Counting(false)` | `CountingAuth(null)` |
| `:349` (named `val auth`) | `Counting(false)` | `CountingAuth(null)` |
| `:367` (inline, 3rd arg) | `Counting(false)` | `CountingAuth(null)` |
| `:376` (inline, 3rd arg) | `Counting(true)` | `CountingAuth(AuthScheme.BEARER)` |

The two private wrappers also need their parameter retyped: `decide(...)` at `:44` and `reason(...)`
at `:64`, both currently `authorized: Boolean = true` → `authorized: AuthScheme? = AuthScheme.BEARER`,
plus the new `rejectsAsCrossSite: Boolean = false` argument they must forward.

**Then re-prove those rows RED after the migration.** Mutate `decide` two ways and watch the right
row fail:

1. call `authorized()` **twice** → `:341` must fail;
2. evaluate `rateLimitOk()` **eagerly, before** the auth check → `:342` must fail.

If they do not fail, **the migration destroyed the evidence** and the counts prove nothing — stop and
fix the fakes before continuing.

### Edge Cases checklist

- [ ] Empty `availableModelIds` — no form, no orphan button.
- [ ] Model id with HTML metacharacters — escaped in both text and `value="…"` attribute context.
- [ ] `switchLocked` and `statusLabel` derived from one `startupInProgress` read (no self-contradiction mid-swap).
- [ ] Basic key containing `:` survives (split on first `:` only).
- [ ] **Basic payload with NO `:` at all ⇒ `null`**, not the raw key (HIGH-2 — `substringAfter(':', "")`).
- [ ] Malformed base64 and malformed `%` escapes return `null` rather than throwing.
- [ ] `Sec-Fetch-Site` **absent** ⇒ allowed **on GET** (curl/SDK read path preserved); on non-GET it falls back to the `Origin`/`Referer` same-host check; **`none` ⇒ allowed** (address-bar navigation, the primary way an operator opens this page).
- [ ] **A successful Basic auth reports `BASIC`** — the parse and the compare agree (8s). The single most consequential assertion in PR-A: without it the guard can be wired perfectly and still never fire.
- [ ] Same host, **different port** ⇒ cross-site (`https://<ip>:9999` vs `Host: <ip>:8443`); same host, same port ⇒ allowed. Both, or the comparison is wrong in one of the two opposite directions.
- [ ] Bracketed IPv6 authorities compare equal to themselves (`[::1]:8443`), and an absent `Host` rejects.
- [ ] A `Referer`-only request (no `Origin`) still resolves — the fallback's second limb is exercised, not just its first.
- [ ] A cross-site 403 charges **neither** rate-limit budget (HIGH-4) — the attacker page shares the operator's IP.
- [ ] A `CROSS_SITE` reject carries **no** `WWW-Authenticate` — the credential was valid, so re-prompting for it is wrong.
- [ ] An auth-exempt path (`GET /health`, `GET /ca.crt`) can never trip the CSRF guard, because no `authorized()` runs there.
- [ ] A 403 answers `403 Forbidden` with `type: permission_error` — **not** `403 ERR` with `authentication_error`.
- [ ] `WWW-Authenticate` appears on the 401 with `Accept: text/html` and on **nothing else** — not the 429, not the 413.
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

# Task 3 changes `authorized()`'s return type. ONE header-taking call site; the other hit is a decoy.
grep -rn 'fun authorized(' Android/src/app/src
#   expect RelaisHttpServer.kt:2069  (the one this task changes)
#   expect automation/RelaisTaskerActivity.kt:82  (LOW-1 decoy: intent-ABI path, bare token, DO NOT TOUCH)

# Task 3 changes `RelaisHttpGate.decide`'s signature. Confirm nothing else calls it.
grep -rn 'RelaisHttpGate.decide' Android/src/app/src              # expect handle() + RelaisHttpGateTest.kt only

# R15: the four new helpers must be android.*-free, or isReturnDefaultValues silently guts their
# negative test rows. Run this on the diff, not the file.
git diff main -- Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt | grep '^+' | grep 'android\.'
#   expect NOTHING in extractApiKey / authenticate / rejectsAsCrossSite / challengeHeaders
grep -n 'isReturnDefaultValues' Android/src/app/build.gradle.kts   # :209 — read the comment above it

# R14 seam: confirm the gap this plan is closing actually exists before trusting 8s is worth writing.
ls Android/src/app/src/test/java/cc/grepon/relais/ | grep -i 'RelaisHttpServerTest'  # expect NOTHING
grep -rn 'authorized(' Android/src/app/src/test Android/src/app/src/androidTest      # expect NOTHING pre-change

# The probe is NOT part of CI. Compile it, then actually run it on hardware.
cd Android/src && ./gradlew :app:compileFullOpenDebugAndroidTestKotlin
adb shell am instrument -w -e class cc.grepon.relais.BasicAuthGateProbe \
  -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner

# MEDIUM-0: confirm PR-A really is introducing the first server-side 403.
grep -rn '403' Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt
grep -rn 'reply(403\|respond(sock, 403' Android/src/app/src/main   # expect ZERO before this change

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

# 6d. HIGH-2: the same key smuggled through Basic with NO colon must ALSO be rejected. If this
#     returns 200 the bare-key hole was reopened through the new carrier and 8k is not really RED.
curl -sk -i -H "Authorization: Basic $(printf '%s' "$KEY" | base64 -w0)" \
  https://$IP:8443/v1/models | head -1                                                # expect 401

# 6e. MEDIUM-0: the 403 must carry a real reason phrase and the right envelope type.
curl -sk -i -u ":$KEY" -H 'Sec-Fetch-Site: cross-site' https://$IP:8443/v1/models | head -1
#   expect `HTTP/1.1 403 Forbidden`, NOT `403 ERR`
curl -sk -u ":$KEY" -H 'Sec-Fetch-Site: cross-site' https://$IP:8443/v1/models
#   expect type `permission_error`, NOT `authentication_error`

# 6f. MEDIUM-3: the challenge rides the 401 only. Blow the budget, then check the 429.
for i in $(seq 1 40); do curl -sk -o /dev/null -H 'Accept: text/html' -u ":$KEY" https://$IP:8443/v1/models; done
curl -sk -i -H 'Accept: text/html' -u ":$KEY" https://$IP:8443/v1/models | grep -i 'www-authenticate'
#   expect NO match on the 429

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

- [ ] In a real browser: cert interstitial → Basic prompt → page renders; beacon pulses only when LIVE; **panel keeps refreshing every ~10s for several minutes** — and watch for the two things only a device answers: (a) the UA keeps re-attaching cached Basic credentials without re-challenging, and (b) **the refresh chain survives a 429.** At 6 req/min against a 30/60s budget, any concurrent SDK traffic can push the tab over; a 429 answers JSON, which has no meta-refresh tag, so the chain ends permanently (MEDIUM-1, R13). *A 403 here is no longer an expected failure mode — `same-site` is unreachable on a meta refresh by spec (MEDIUM-2).*
- [ ] `SET MODEL` on a provisioned id → node goes STARTING → LIVE serving the new model, **no app restart**.
- [ ] Form is visibly disabled while STARTING.
- [ ] `/experiments` also loads in the browser now (incidental R1 relief).
- [ ] Re-verify on a **release** build (R8 on) — see R5.

## Acceptance Criteria

- [ ] `GET /` loads in a stock browser via a Basic prompt, including on a **typed-URL navigation** (`Sec-Fetch-Site: none`); `Authorization: Bearer` still works unchanged everywhere.
- [ ] No `WWW-Authenticate` on non-HTML requests (SDK/curl error paths unchanged).
- [ ] A **Basic**-authenticated request with `Sec-Fetch-Site: cross-site` or `same-site` gets `403` — **on every route, not just `/select-model`**; the same request carrying `Bearer` is unaffected.
- [ ] The `403` answers **`403 Forbidden`** (not `403 ERR`) with envelope type **`permission_error`** (not `authentication_error`), and the new constant has a row in `RelaisErrorTest.kt`'s type enumeration.
- [ ] `CROSS_SITE` is decided **before either rate-limit supplier runs** — pinned by a call-counting test asserting `rate.calls == 0` and `exempt.calls == 0`, not merely by reading the status.
- [ ] `RelaisHttpGate.decide` still receives only **suppliers and primitives** — no `Origin`/`Referer`/`Sec-Fetch-Site` string ever crosses into it, and its body is still an ordering function, not an algorithm.
- [ ] `Authorization: <rawkey>` with no scheme is rejected, **and so is `Basic base64(rawkey)` with no colon** (HIGH-2), and both are recorded in `SECURITY.md` and `.claude/HANDOFF.md`.
- [ ] `WWW-Authenticate` rides the `401` with `Accept: text/html` and **nothing else** — a 429 and a 413 with the same `Accept` carry no challenge.
- [ ] The four byte-identical assertion blocks in `RelaisHttpGateTest.kt` survived the `CountingAuth` migration **and were re-proven RED against both mutations** (double `authorized()` call; eager `rateLimitOk()`).
- [ ] **A valid Basic credential reports `BASIC`, not `BEARER`** — test 8s, proven RED by a compare that returns `BEARER` unconditionally. Without it the feature can ship non-existent with a green suite (R14).
- [ ] **Every new function in Task 3 is `android.*`-free** — `extractApiKey`, `authenticate`, `rejectsAsCrossSite`, `challengeHeaders`. Base64 is `java.util.Base64`, not the already-imported `android.util.Base64` (R15). Grep the new code for `android.` before review.
- [ ] The `Origin`/`Referer` check compares **canonical authorities** (host + explicit-or-scheme-default port, brackets intact), and test 8j covers matching port, **mismatching port**, bracketed IPv6, absent `Host`, `Referer`-only, and a malformed URL — the three-row version passed under two opposite bugs (P1-2).
- [ ] `host` is parsed in the header loop. *(It has no arm today; omitting it fails no JVM test.)*
- [ ] **`BasicAuthGateProbe.kt` was actually run on hardware** and all **five** requests pass — row 5 included, since it is the only cover for S3b. CI runs none of it; an unrun probe means seams S2/S3a/S3b shipped unverified.
- [ ] `authorized()` is still `private`, and no existing member of `RelaisHttpServer.kt` was widened — `authenticate` and `challengeHeaders` are **new** `internal` helpers, which is not the same thing.
- [ ] **All four new helpers are TOP-LEVEL** (after the class closes at `:2151`, beside the twelve existing top-level `internal fun`s), **not members** — an `internal` member needs an instance, needs a `Context`, and 8s/8m would not compile. A comment at the helpers says so.
- [ ] The authority comparison **default-fills the port on BOTH sides** (`tls` supplies the `Host` side's scheme), and 8j row (ix) — `Host: node`, `Origin: https://node`, both portless — **allows**. An asymmetric rule 403s the node's own form on any default-port listener.
- [ ] **The origin's scheme is compared against the listener's, ahead of the authority comparison** — 8j row (x), `Origin: http://node:8443` vs `Host: node:8443` on TLS, **rejects**. `tls` alone does not do this; every other 8j row shares a scheme and cannot catch it.
- [ ] `rejectsAsCrossSite`'s URL handling **mirrors `WebhookGuard.kt:54-58` and nothing below it** — malformed rejects (never throws), scheme and host lowercased, no-host rejects. **No `WebhookGuard.check` call, no DNS resolution, no `classify`, no allowlist**: those are outbound-SSRF policy, and `classify` blocks RFC1918 — the only network this dashboard runs on.
- [ ] **8j row (xii) — same-origin RFC1918 (`https://192.168.1.2:8443` vs `Host: 192.168.1.2:8443`) is ALLOWED.** This is the row that distinguishes a correct guard from an SSRF-guard copy, which otherwise 403s everything while looking right.
- [ ] **8j row (xiii) — portless IPv6 (`Host: [::1]`, `Origin: https://[::1]`, `tls`) matches.** Port detection is bracket-aware (segment after the last `]`), never "contains `:`", which is true of every IPv6 literal.
- [ ] **`AuthScheme` is top-level too**, not just the four functions — otherwise the helpers do not compile and the tempting fix undoes the placement.
- [ ] **Probe row 5 exists and asserts the exact challenge header** — without it, deleting `challengeHeaders(...)` from the reply passes 8m and all four other probe rows.
- [ ] `POST /select-model` validates membership **first** and compat **second**, and persists nothing on either rejection.
- [ ] The dropdown offers exactly `(registry ∪ configured id)` minus anything with an `incompatibleReason`, sorted lexicographically, with copy §1.4 L93 amended from "catalog order" to match (Task 4 gotcha 1b).
- [ ] A valid switch **dispatches the swap first** and persists **through `ModelSwitch.applyManualId`** only when the dispatch won the CAS; a concurrent second submit gets `503 + Retry-After` and persists nothing. No app restart required.
- [ ] The form renders disabled while STARTING, from the same `startupInProgress` read as the status row; the pending hint appears exactly when the configured id differs from `RelaisEngine.residentModelId`.
- [ ] Page remains scriptless (no `<script>`); CSP still carries no `script-src`, now plus `form-action 'self'`.
- [ ] All dynamic values HTML-escaped, attribute context included; no raw key ever rendered.
- [ ] RECENT REQUESTS contains **no `/ 200` rows**; `/ 401` challenge rows still appear (they come through the shared `reply()`, deliberately); `/metrics` still counts `/`. *`/health` and `/ca.crt` rows are unaffected and still appear — this task reduces `/` noise, it does not make the panel quiet (LOW-3).*
- [ ] Every **new** string matches `docs/dashboard-copy.md` verbatim, and the four strings this plan introduces were **added** to that doc in the same PR, with O1's amendment applied. *The eight pre-existing Appendix deltas on the shipped page are out of scope and filed as their own issue.*
- [ ] UI matches `DESIGN.md` — amber `#FFB000` on `#0B0B0D`, monospace, dark-only, no third accent, `#FF5247` absent, **no font-stack change**.
- [ ] ~~`feature-18-trusted-lan-cert` is fully merged before this branch opens~~ — **satisfied, `cf316146`.** What remains checkable: `handleDashboard` was not relocated, and `RelaisHttpServer.kt` gained no `internal` widening (still 2432 lines plus this plan's additions — re-measure, do not quote).
- [ ] **PR-B only:** the dashboard's `Referrer-Policy` is `same-origin` (`:896`), `/experiments` is still `no-referrer` (`:923`), the header carries a comment naming the *property* (not the mechanism), and a unit test fails if it reverts.
- [ ] Three-flavor unit lane green; every new test proven RED first.
- [ ] Independent `code-reviewer` + `security-reviewer` APPROVE on the **final** diff.

## Completion Checklist

- [ ] Patterns followed (NAMING_CONVENTION, ERROR_HANDLING, LOGGING_PATTERN, HANDLER_PATTERN, TEST_STRUCTURE, PROBE_STRUCTURE)
- [ ] Error handling explicit at every boundary; no silently swallowed failures; malformed input returns `null`, never throws
- [ ] Logging via file-private `TAG`; no key, IP, body, or raw path logged
- [ ] Tests written, proven RED first, three-flavor lane green — **including 8s (scheme preservation) and 8k (colon-less Basic), the two whose RED proof is the only thing distinguishing them from vacuous rows**
- [ ] `BasicAuthGateProbe.kt` **run on hardware**, not merely compiled — CI covers seams S2/S3 not at all
- [ ] No hardcoded values — colors from the `:root` token block, capacity from `REQUEST_LOG_CAPACITY`, model ids from the registry
- [ ] Docs updated (`dashboard-copy.md` — amended **and extended**, `SECURITY.md`, `RUNBOOK.md`, `HANDOFF.md`, `ModelSwitch.kt` KDoc)
- [ ] No scope additions beyond NOT Building — in particular, no handler relocation and no `internal` widening in `RelaisHttpServer.kt`
- [ ] Self-contained: no new dependency, no new framework, no external asset
- [ ] ~~`feature-18` merged first~~ (satisfied, `cf316146`); `feature-17` still owns the `RequestContext` widening
- [ ] `RelaisHttpGateTest.kt`'s four byte-identical assertion blocks re-proven RED after the `CountingAuth` migration (both mutations), and all four auth-slot call sites migrated — including the two **inline** ones at `:367`/`:376`
- [ ] Both issues filed: R1 (`/experiments` 401) and the eight `dashboard-copy.md` Appendix deltas
- [ ] Caller audit for Task 7 re-run on the branch (`grep -rn '::ensureModelSwapInBackground'` returns nothing)
- [ ] `security-reviewer` has seen PR-A (Basic + `Sec-Fetch-Site`) on its own, and re-reviewed after any fix commit

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **R1** — `/experiments` is unreachable from a browser today (401 on navigation) despite an in-page key input at `RelaisExperiments.kt:170`; a pre-existing bug this plan only incidentally relieves | **Certain** (verified) | Medium | File as its own issue so the finding survives independently of this PR; do **not** scope its redesign here |
| **R2** — Self-signed cert interstitial appears before the auth prompt | Certain | Low | Document in RUNBOOK; inherent to the TLS posture |
| **R3** — **Accepting Basic converts an explicit credential into an ambient one across ~20 routes** (rewritten; the previous "the carrier changes but the credential does not" framing was a false premise) | **Certain** | **Medium** | The mechanism, stated honestly: today a `Bearer` header must be set by script, and a cross-origin request carrying it triggers a CORS preflight this server fails (no `Access-Control-*` anywhere — grepped). Cached **Basic** credentials are re-attached by the UA itself, no script, no preflight; and `Content-Type` is never enforced on the JSON routes (`:345` parsed, read only for multipart at `:553`/`:734`), so a cross-site *simple* POST reaches `handleOpenAi`. Impact is capped at **side effects, no read** — the response is opaque without CORS — but that still buys an attacker page unmetered inference, RAG corpus injection, session mutation and batch-job creation. **Mitigation (Task 3): the `Sec-Fetch-Site` guard runs inside `RelaisHttpGate.decide` for every Basic-authenticated request**, not on `/select-model` alone. Residual: a browser too old to send `Sec-Fetch-Site` gets no protection — acceptable on a trusted LAN, documented in `SECURITY.md` |
| **R3b** — The `Sec-Fetch-Site` rule is easy to get wrong in the direction that breaks the feature | Medium | Medium | Reject **only** `cross-site` and `same-site`. Allow `none` — that is what an address-bar navigation sends, and an attacker page cannot produce it. A reviewer working from the obvious-sounding "reject unless `same-origin`" will 403 the first page load; test 8i pins all six cases. **For state-changing (non-GET) requests specifically**, an absent header falls back to an `Origin`/`Referer` same-host check rather than being allowed outright (codex P2 review, PR #310) — test 8j. *One thing this risk previously over-stated is now settled: a meta refresh **cannot** send `same-site`, because `same-site` means "same site, **different** origin" and a meta refresh targets the identical URL. The page cannot 403 itself (MEDIUM-2).* |
| **R3c** — **PR-A ships the `Origin`/`Referer` fallback DORMANT; PR-B is where it can 403 the node's own form** | **Medium** | **High** | Nothing in the tree POSTs from a browser page until PR-B adds the form, so no PR-A test exercises the branch against a real navigation and no PR-A reviewer has a reason to think about `Referrer-Policy`. Under the dashboard's `no-referrer` (`RelaisHttpServer.kt:896`) a form POST arrives with `Origin: null` and no `Referer` (MDN), so the fallback 403s the node's own form on any UA that omits `Sec-Fetch-Site` — Safari < 16.4, older embedded WebViews, header-stripping proxies. The failure is opaque: the form renders enabled, the operator clicks `SET MODEL`, and gets a 403 with no diagnostic. **Mitigation is three mechanical measures, not a note** — see *Decisions → HIGH-3*. Accepting `Origin: null` is **not** among them: sandboxed iframes and cross-origin redirects send exactly that |
| **R14** — **The seam between the tested halves is tested by nothing — this repo's signature failure, third occurrence** | **Medium** | **High** | Pure helpers get unit tests, `decide` gets injected fakes, and the **wiring** gets neither. An implementation that parses Basic perfectly and returns `BEARER` after the compare passes 8a-8l **and** 8n-8p, while every real Basic request bypasses the CSRF guard: the feature does not exist, suite green. Verified: **no JVM test class for `RelaisHttpServer`**, and **no test in the tree calls `authorized(`**. Precedent — in-app chat lost its `(Application)` ctor with every layer green, and the report Worker could not boot past 24 green tests, a clean dry-run and two codex passes. **Mitigation: test 8s** (JVM, closes seam S1, proven RED) **plus probe 8t** (closes S2/S3 — the only reach into `handle()`). **Residual, stated rather than papered over:** 8t is `androidTest`, so **CI catches none of it**; if the probe is not run on hardware, S2 and S3 ship unverified. Same bar feature-18 shipped under — *hardware-verified-or-not-done*. See *Closing the seam class* |
| **R15** — **`android.util.Base64` in the pure extractor would make the negative tests vacuous** | Medium | **High** | `isReturnDefaultValues = true` (`build.gradle.kts:209`, whose own comment admits it *"masks accidental unmocked-Android calls"*) makes unmocked `android.*` return defaults in the JVM lane. The failure is **asymmetric**: positive rows fail loudly, but 8e and **8k — HIGH-2's only pin** — pass for the wrong reason, because a defaulted decode yields `null` and `null` is what they assert. An implementer who "fixes" the loud half by mocking cements the vacuous half. **Mitigation: `java.util.Base64` (aliased), stated three ways** in Task 3 piece 1, the IMPORTS line and a GOTCHA, with the repo's three existing precedents cited. The general rule — *nothing added in this task may call `android.*`* — is what keeps `authenticate` and `challengeHeaders` testable too |
| **R13** — **A 10s refresh spends 20% of the per-IP budget, and the first 429 ends the refresh chain for good** (MEDIUM-1; **pre-existing in this plan, not caused by #316/#317/#318**) | **Medium** | Medium | `/` is not auth-exempt, so each refresh charges `RATE_LIMIT = 30`/60s (`:92`), not the 120 exempt budget (`:102`): one idle tab = 6 req/min = 20%, two = 40%, shared with SDK traffic from the same machine. A 429 answers **JSON**, which carries no meta-refresh tag, so the chain **stops dead** and the tab shows raw JSON until a manual reload. **Decision: keep 10s** — 30s reads as dead on a status panel (O2) and the cost is stated rather than traded away. Record it in `SECURITY.md`/`RUNBOOK.md` and watch for it in the on-device check. If operators hit it, the fix is the budget (exempt-eligibility for `/`, or a higher `RATE_LIMIT`), not the interval — both out of scope here |
| **R4** — **A second `SET MODEL` mid-swap persists a selection whose swap was silently dropped** (re-diagnosed; the mitigation previously cited the wrong line) | Medium | **High** | The guard is the `swapDispatching` CAS at `RelaisEngine.kt:434`, **not** `startupInProgress` at `:437` (which is merely *set* there). `swapDispatching` clears only in the `finally` at `:493-496`, so a mid-swap call no-ops — config would say B, engine serve A, no retry ever scheduled, `303` reads as success. The disabled-form UI guard does not cover it: the meta refresh can repaint an unlocked form in the window between persist and `startupInProgress = true`, and a stale tab can POST at any time. **Fix (Tasks 7-8): the swap function returns whether it won the CAS; dispatch first, persist only on `true`, answer `503 + Retry-After` otherwise.** Residual: `true` means the thread started, not that it succeeded — it can still bail at `:455-458` — which is what the pending hint surfaces |
| **R5** — R8 minification is on in release and CI runs none of it | Low | High | No reflection added, so no new keep rules expected — but confirm on the on-device release gate, since CI cannot |
| **R6** — Timing side-channel reintroduced while refactoring `authorized()` | Low | High | No length check, no early return before `MessageDigest.isEqual`; called out in Task 3 and in security review. The scheme parse is the only nullable step and is not key-dependent, so returning `null` for an unknown scheme leaks nothing |
| **R7** — Copy drift from `dashboard-copy.md` | Medium | Low | Copy literals verbatim; amend **and extend** the doc in the same PR (O1 + the four new strings) |
| **R8** — Meta refresh discards an in-progress `<select>` choice | Medium | Low | 10s interval (not 3s); the form is a single control submitted immediately |
| **R9** — **A dashboard click loads a known-bad model and takes the node down with nothing to roll back** | **Was certain, now mitigated** | **High** | The targeted swap path skips `resolveModel` entirely (`RelaisEngine.kt:451-454`) and with it `refuseIfIncompatible`. The rollback at `:474-490` catches engine-**create** failures only; this repo's own known-bad case is the Tensor G5 `gemma-4-E4B` **first-inference** SIGSEGV (SPIKE-FINDINGS, LiteRT-LM#2566), which creates fine and then kills the process. **Fix (Tasks 4 + 8): filter the dropdown and re-check server-side, both on `incompatibleReason`.** Ordering matters — persisting before the swap would leave the bad id in config with `KEY_MODEL_PATH` cleared (`RelaisConfig.kt:198-201`), re-bricking on restart; Task 8 persists last, and only through `ModelSwitch` |
| **R10** — Cross-plan collision with `feature-18` / `feature-17` / `feature-22` | **Was high, now largely discharged** | Low-Medium | Cutting the handler extraction removed the `handleDashboard`-relocation and `RequestContext`-widening collisions outright, and **feature-18 has merged** (`cf316146`), so its half is settled rather than sequenced. What remains: PR-B amends the dashboard CSP at `:893` (which feature-18 pinned as "unchanged" *for its own diff*) and narrows `Referrer-Policy` at `:896` — say so in PR-B's body so a later reader does not score either as breaking a merged rule. The new live surface is `RelaisHttpGate.kt`, which Task 3 changes the signature of; re-grep for sibling plans touching it before cutting the branch |
| **R11** — `authorized()` has **never had a JVM test** | Certain (verified) | Medium | Grep-confirmed: the only `Bearer` assertions in the unit lane are `RelaisExperimentsTest.kt:143/188/225/255`, and they assert on a *rendered page*, not the gate. `RelaisHttpAuthTest.kt` is the first coverage this function gets — which is precisely why the M3 bare-key tightening would otherwise ship unnoticed. Prove every case RED first |
| **R12** — Task 7 changes a public signature on the engine | Low | Medium | Audited: one production call site (`RelaisHttpServer.kt:1330`, a plain statement call), zero bound function references. A `::ensureModelSwapInBackground` reference typed `(Context, ProvisionedModel?) -> Unit` **would not compile** — re-run both greps in the Validation block on the branch rather than trusting this line |

## Notes

### What the 2026-06 version of this plan assumed that is now wrong

The original was written before any of it shipped. On `cf316146`, **most of it is done**:

| Original assumption | Reality |
|---|---|
| `GET /` returns nothing; add the route | **Shipped** — `RelaisHttpServer.kt:403` → `handleDashboard` (`:862-899`) |
| Create `RelaisDashboard.kt` | **Shipped**, 423 lines (assembler, `thermalLabel`, `escapeHtml`, `maskApiKey`, renderer, and #318's cert panel) |
| Add a ring buffer to `RelaisMetrics` | **Shipped** — capacity 20 (`RelaisMetrics.kt:72`), `recentRequests()` (`:149`) |
| No security headers exist | **Shipped for this route** — CSP + nosniff + DENY + no-referrer (`:893-896`) |
| Tests must be written | **Shipped** — `RelaisDashboardTest.kt`, 603 lines |
| `DashboardStatus` has 10 fields | **14** — adds `baseUrl`, `apiKeyMasked`, `capabilities` (a CLIENT CONFIG panel the original never specified) and #318's `cert` |
| The gate is an inline `if` in `handle()` | **Extracted** by #314/#317 into `RelaisHttpGate.decide` (`RelaisHttpGate.kt:74-92`), with auth, the two rate budgets and the body cap as **independent** checks and every effect passed as a **supplier**. This is the single biggest change to Task 3's shape, and it falsified the pattern this plan told the implementer to mirror |
| `/health` is the only auth-exempt path | **`/ca.crt` is too** since #318 (`RelaisHttpGate.isCaCertPath`, `:124`; `authExempt`, `:127-128`) |
| Delete `#FFCC44` warn and `.stop` red (copy Appendix rows 5-6) | **Already satisfied** — grep-confirmed absent from the shipped CSS; `#FF5247` occurs only inside a comment at `RelaisDashboard.kt:185`. Not work |
| `/` is the only HTML route | `GET /experiments` also ships (`:295`) — and it carries an inline script under a per-request CSP **nonce**, so "the repo is scriptless" is no longer globally true |
| Browser access is unsolved; "leave it API-client-only for v1" was the default | Resolved here as Basic auth **plus a gate-wide `Sec-Fetch-Site` guard** — the guard is not optional garnish, it replaces the CSRF immunity that `Bearer`-only was providing by accident (R3) |
| The model switch is a matter of persisting an id and calling the swap | **Two gates the original never saw.** The *targeted* swap path skips `resolveModel` and therefore every compat check (`RelaisEngine.kt:451-454`); and `ensureModelSwapInBackground` silently no-ops when its CAS is already held (`:434`), so persist-then-swap can leave config ahead of the engine. Both were found by the `critic-09` review, not by the original plan |
| Persist with `RelaisConfig.setModelId` | `ModelSwitch` (`:19-45`) has since become the declared single source of truth for an operator model pick, and `setModelId` alone is the weaker path it exists to prevent |

The remaining scope is exactly what `RelaisDashboard.kt:176` defers: *"READ-ONLY — no model-switch form or /select-model action (deferred to a separate PR)"*, pinned by the test at `RelaisDashboardTest.kt:396-403`.

### Decisions

**The model switch does not need a restart — `docs/dashboard-copy.md:95` is stale.** That line specs the hint `model set: <id> — restart to apply`. But `rejectIfModelUnavailable` (`RelaisHttpServer.kt:1297-1360`) already calls `RelaisEngine.ensureModelSwapInBackground` on the `SwapThenRetry` branch (arm at `:1324-1339`, the call itself at `:1330`), and that function sets `startupInProgress = true` at `RelaisEngine.kt:437` and clears it at `:494` — the node transitions LIVE → STARTING → LIVE by itself. The `"Restart to apply"` strings at `RelaisConfigureActivity.kt:296,306` belong to the *config-set* path (persist a preference, take no engine action); do not carry their semantics across.

**Auto-refresh via meta refresh, not an inline script.** No CSP directive governs meta refresh, so it needs no header relaxation and keeps `script-src` absent. `dashboard-copy.md` §2.7 requires future interactivity to survive that CSP *before it gets copy*, and `RelaisDashboardTest.kt:306` pins the invariant. `/experiments`' nonce'd script is a genuinely interactive surface — not a precedent for a page whose whole content is a server-rendered readout.

**Browser auth via Basic, not a cookie.** Cookie needs a *new unauthenticated* key-entry route (which the original plan's Risks section forbids), `Cookie`/`Set-Cookie` parsing that exists nowhere in the codebase (grep: zero), and its own CSRF story — three new mechanisms. Basic needs one change to one existing function, and the browser then attaches credentials to every subsequent request on the origin, incidentally fixing `/experiments`. Safe because `SECURITY.md:14-18` puts the LAN listener on TLS only. **But "the browser attaches credentials on its own" is exactly the CSRF exposure in R3** — the convenience and the risk are the same property, which is why Task 3 pairs Basic with the gate-wide `Sec-Fetch-Site` guard rather than shipping it alone.

**O3 is answered: the wider option.** Basic is accepted on every endpoint (one gate, one comparison, no per-route auth branch), **and** the `Sec-Fetch-Site` guard is applied at that same gate to every Basic-authenticated request. The narrow alternative — accept Basic only on `GET /`, `GET /experiments` and `POST /select-model` — is also sound, but it puts an auth branch in the router, and a fourth HTML surface later would silently miss it.

**A scheme-less `Authorization: <rawkey>` is rejected from now on.** Today it is accepted, purely because `header?.removePrefix("Bearer ")` returns the receiver unchanged when the prefix is absent, so the `?: return false` fires only on a null header. Nothing documents that: the README, `SECURITY.md`, every `*-api.md`, and every example specify `Bearer`. It is an accident of the implementation, not a contract — tighten it, pin it with test 8g, and record it as wire-visible in `SECURITY.md` and `.claude/HANDOFF.md`. Deliberately **not** left as an open question: the evidence is one-sided.

**`503 + Retry-After: 25` for a busy swap, not `409`.** The server already answers exactly this state with exactly this pair at `RelaisHttpServer.kt:1332-1337` ("resident model differs from the requested model; swapping — retry shortly"). Inventing a second status code for the same condition on a sibling route would be a gratuitous divergence.

**HIGH-3 — the dashboard's `Referrer-Policy` narrows to `same-origin`; `/experiments` does not. Defined in PR-A, shipped in PR-B.**

The rule Task 3 introduces needs the node's own form POST to carry a real `Origin`. Under
`Referrer-Policy: no-referrer` (`RelaisHttpServer.kt:896`) it carries `Origin: null` and no `Referer`,
so PR-B's form would 403 on every UA that omits `Sec-Fetch-Site`.

*Why narrowing is not the regression it looks like.* `no-referrer` protects against leaking **this
page's URL to a third party**. The dashboard has no third party it could leak to: CSP is
`default-src 'none'` (`:893`) so there is no image, font, stylesheet, fetch or subresource of any
kind; `grep "a href"` on `RelaisDashboard.kt` returns **nothing**, so the page links nowhere; and the
page is scriptless, which Tasks 2 and 5 preserve. The only requests it can originate are same-origin
— the meta-refresh navigation and (PR-B) the form POST — and for those, the two policies differ only
in whether the node is told its own URL, a self-referential disclosure to itself. `same-origin`
**retains** the property that matters: `Referer` is still omitted on any cross-origin request, so a
future outbound link is already protected. `same-origin` is simply the correct policy for a page that
submits a form to itself; `no-referrer` is correct for a page that links out. This is the former.

*Why not the alternative.* "Document that no-`Sec-Fetch-Site` UAs cannot use the form" degrades the
feature for Safari < 16.4, older embedded WebViews and header-stripping proxies, and the failure mode
is bad: the form renders **enabled**, the operator clicks `SET MODEL`, and gets an opaque 403 with no
diagnostic. That means shipping a visible control that silently does not work on some clients.

*Why `/experiments` (`:923`) stays `no-referrer` — record this so nobody "harmonizes" the pages.* It
has no form and `form-action 'none'` (`:920`) forbids one; its inline script uses `fetch()`, which
defaults to `mode: "cors"`, and the referrer-policy `Origin`-nulling rule applies **only to non-CORS
requests**, so those calls send a proper `Origin` regardless of policy; and they carry
`Authorization: Bearer` (`:244/279/314/350`), so the Basic-only CSRF guard never applies to them at
all. The header appearing on both pages does not widen the remedy — it sharpens it to exactly one.

*Why it ships in PR-B, not PR-A.* PR-A is the deliberately-minimal security-review diff (Metadata,
O4). A security-header **narrowing** with no justification visible inside PR-A's own diff is exactly
what should make a reviewer stop — and the test that gates it cannot live in a PR that does not
change the header. Nothing is lost by the pair travelling together, because the revert risk begins
the moment the header is set.

*Making the cross-PR coupling survive* — the failure shape is PR-B being written months later by
someone reading only its own scope. Three **mechanical** measures, not a note:
1. **Written into PR-A's plan text** (done): Task 3 step 3, research item 6, R3c, and the PR-B
   Files-to-Change row, which is flagged as a blocker.
2. **A comment at the CSP site naming the PROPERTY, not the mechanism** — this repo's own lesson, a
   `mainHandler.post`-based safety argument falsified by an unrelated swap to an executor. Shape:
   *"`same-origin`, not `no-referrer`: the CSRF fallback for UAs that omit `Sec-Fetch-Site` requires
   this page's own POSTs to carry a real `Origin`. `no-referrer` nulls it."*
3. **The comment converted into a gate** — a unit test asserting the dashboard's header list contains
   `Referrer-Policy: same-origin`, with an assertion message naming the CSRF dependency. A revert then
   **fails a test** instead of silently reopening HIGH-3.

**MEDIUM-1 — the refresh interval stays at 10s, with the cost stated rather than traded away.** One
idle tab spends 20% of the per-IP budget and a 429 ends the refresh chain permanently (research item
7, R13). Widening to 30s reads as dead on a status panel (O2) and would not remove the failure mode,
only delay it. The honest framing is that this is a *pre-existing* property of the plan — it predates
#316/#317/#318 and none of them caused it — and the real fix, if operators hit it, is the budget
(exempt-eligibility for `/`, or a higher `RATE_LIMIT`), not the interval. Both are out of scope here.

**MEDIUM-0 — the 403 vocabulary ships in PR-A, not PR-B.** PR-A introduces the first 403 in this
tree, and `reason()` would put `403 ERR` on the wire while the envelope claimed
`authentication_error` — actively wrong, since the credential *is* valid and the request *context* is
what was rejected, and a client seeing `authentication_error` may enter a credential-refresh loop
against a request that can never succeed. Deferring means PR-A knowingly ships a mislabelled envelope
and hands PR-B a defect it did not cause. Roughly four lines plus a test row.

**LOW-2 — the scheme token stays case-SENSITIVE, and the Bearer branch keeps its `.trim()`.** RFC
7235 makes `auth-scheme` case-insensitive, so accepting `bearer`/`basic` would be defensible in
isolation — but it is a **widening**, and shipping one in the same diff as an advertised tightening
is how a security reviewer loses the thread. Nothing in the repo or its docs sends a lowercase scheme
(`RelaisExperiments.kt:244/279/314/350`, `HttpChatTransport.kt:78`, `RelaisClientConfig.kt:94`).
Decided, not left open; pinned by test 8l and recorded in `SECURITY.md` so a reviewer who reads the
RFC and not this line does not file it as a bug. Dropping the `.trim()` would likewise be a second
silent tightening — keep it.

### Critic findings disposition (`critic-09`, 2026-09-07)

Every finding was re-verified against `1276a351` before being accepted. **12 of 12 fixed; 0 declined.**
Two are fixed *differently* from the critic's suggested wording, because the suggested wording was
itself wrong — those are marked **fixed, corrected**.

> **Historical record — line numbers in THIS table are as-of `1276a351` and are deliberately left
> unchanged**, so the round reads as it was argued. Do not navigate by them; #314/#316/#317/#318 moved
> nearly all of them. The live numbers are in the task bodies above and in the 2026-09-12 section
> below, which also records which of this round's *rationales* (not just citations) were falsified.

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

### Critic + codex findings disposition (2026-09-12, post-#318)

Second review round, scoped to **PR-A (Tasks 1-3) on `cf316146`**: a critic pass (4 HIGH, 4 MEDIUM,
4 LOW) plus an independent `/codex` pass. Codex agreed on the `CROSS_SITE` placement from a different
direction (response ordering — 429/413 would otherwise precede the 403) and **independently found the
403-vocabulary gap** that appears below as MEDIUM-0. Everything else is critic's.

**Every line number in this revision was re-read on `cf316146` rather than carried forward**, and
where a finding falsified a stated *rationale* rather than a line number, **the rationale was
rewritten** — a fresh citation stapled to a dead reason is the failure this round existed to catch.
**13 of 13 fixed; 0 declined.** Two ship in PR-B by design, noted as such.

| # | Finding | Disposition |
|---|---|---|
| **Structural** | `decide` must gain the guard — via (a) raw headers, (b) a mutable side-channel, or (c) a typed auth supplier + one lazy CSRF supplier | **Fixed — (c) adopted**, with the plan's *reasoning* for rejecting (a) replaced. The old objection ("pushes per-request header parsing into a pure decision object") was **cosmetic and is dropped**: parsing stays in the header loop at `:340-348`, and `decide` would only receive `String?` primitives, no different in kind from `contentLength`. The real cost is that the `Origin`/`Referer` host comparison is a **second algorithm** inside a 9-line **ordering** function. (c) is argued from the merged design's own thesis — `RelaisHttpGate.kt:61`, *"Every effect is a supplier, not a boolean, and that is load-bearing"* — rather than from taste. (b) rejected: it re-splits the decision #317 unified |
| **HIGH-1** | The `ERROR_HANDLING` pattern the plan told the implementer to **mirror no longer exists** — plan text quoted the pre-#317 inline gate *including its conflated auth/rate-limit condition* | **Fixed — falsified, not renumbered.** The snippet is replaced with the current reject block (`:368-392`) and MIRROR re-worded to *"extend `RelaisHttpGate.decide`; the envelope stays in `handle()`."* Following the old line would have re-inlined precisely the bug #317 extracted (`RelaisHttpGate.kt:19-21`). Same class, smaller: the plan's claim that `/health` is the only auth-exempt path is corrected — **`/ca.crt` is exempt too** (`:124`, `:127-128`) — and the plan now *states* why that is benign (an exempt request never runs `authorized()`, so it has no scheme) instead of leaving the reader to derive it. The nested-`if` shape in Task 3 makes it true by construction |
| **HIGH-2** | `substringAfter(":")` reintroduces, **through Basic**, the exact bare-key hole Task 3 exists to close | **Fixed.** Kotlin defaults `missingDelimiterValue = this`, so a colon-less payload returns the receiver: `Basic base64(rawkey)` would authenticate. Plan now specifies `decoded.substringAfter(':', "")` or an explicit `indexOf(':') < 0` guard. **Test 8k added** (the old table covered `":KEY"` and `"u:K:EY"` but had **no** colon-less case, so it looked thorough exactly where the gap was) with a required RED proof: implement the bare `substringAfter(":")` first, watch 8k fail, then fix. Manual check 6d added |
| **HIGH-3** | `Referrer-Policy: no-referrer` (`:896`) makes the codex-P2 `Origin` fallback 403 **the node's own form** | **Fixed in PR-B, defined in PR-A.** Resolution: narrow the **dashboard only** to `same-origin`, in the commit that adds the form; leave `/experiments` (`:923`) alone, for three stated reasons. Full argument and the three coupling measures are in *Decisions → HIGH-3*; the rule is cross-referenced from Task 3, research item 6, R3c, Task 5's GOTCHA and the PR-B Files-to-Change row. Accepting `Origin: null` explicitly rejected (sandboxed iframes and cross-origin redirects send exactly that). A hidden CSRF token was considered and **declined**, recorded in *NOT Building* so it is a declined option rather than an unconsidered one |
| **HIGH-4** | `CROSS_SITE` ordering vs. the rate limiters is unspecified; *"immediately after the gate's successful `authorized()` call"* **no longer names a unique position** post-#317 | **Fixed.** `CROSS_SITE` fires after auth and **before both rate suppliers**: an attacker page runs in the operator's own browser on the operator's own IP, so metering those rejects would let a hostile page burn the operator's 30/60s budget — the same NAT-shared-budget argument the gate already makes for the 401 at `RelaisHttpGate.kt:48-52`. Pinned by **test 8n** (`rate.calls == 0`, `exempt.calls == 0`), not by reading the status |
| **MEDIUM-0** | *(codex's find; critic confirmed)* A 403 has **no response vocabulary**: `reason()` falls through to `else -> "ERR"` and `RelaisError` has no forbidden type | **Fixed, in PR-A.** Four sub-steps in Task 3 piece 4, including the one that ships silently if skipped — the row in `RelaisErrorTest.kt:57-66`'s constant enumeration. Verified: **zero** occurrences of `403` anywhere in `RelaisHttpServer.kt`, so PR-A really is the first. The reason phrase is cosmetic (RFC 7230 §3.1.2) but the **type is substantive** — reusing `AUTHENTICATION` is wrong (the credential is valid, the context is not) and can put an OpenAI-compatible client into a credential-refresh loop against a request that can never succeed. **The two halves verify differently, and the plan says so rather than implying parity:** the type is a JVM test (8q); the reason phrase is **manual-only** (8r → check 6e), because `reason()` is `private fun` (`:2086`), `RelaisHttpServer` has no unit test at all (R11), and reaching it would need exactly the `internal` widening this plan forbids twice over. An implementer who finds 8r untestable is meant to reach for the manual check, not the widening |
| **MEDIUM-1** | The 10s refresh spends 20% of the per-IP budget, and the first 429 **kills the refresh chain permanently** | **Fixed — recorded, interval unchanged.** New research item 7 and **R13**, with the provenance stated (pre-existing in the plan; **not** caused by #316/#317/#318). **Decision: 10s stands** — see *Decisions → MEDIUM-1*. 30s reads as dead on a status panel (O2) and only delays the failure; the real fix is the budget, which is out of scope. The on-device check is re-aimed to watch for it |
| **MEDIUM-2** | Task 2 **overstated its own uncertainty**: `same-site` is unreachable on a meta refresh | **Fixed — open unknown replaced with spec reasoning.** `same-site` means initiator and target share a site but are **different origins**; a meta refresh targets the *identical URL*, so only `same-origin` or `none` are reachable and both are allowed. The page cannot 403 itself. The on-device check is **kept but re-aimed** at what the spec does not answer: the challenge/credential-cache cycle over several minutes, and (per MEDIUM-1) whether a 429 ends the chain. R3b amended so a later reader does not reintroduce the phantom risk |
| **MEDIUM-3** | `WWW-Authenticate` has **no obvious insertion point** in the post-#317 reject block | **Fixed.** The old claim (`reply()` takes headers, "no new seam is needed") was true but insufficient: the rejection is one `reply(reject.status, body)` at `:390` fed by an exhaustive `when` yielding **only a body**. Task 3 piece 5 gives the parallel `extra` computation and requires the `when` stay an expression. **Test 8m** pins the negative — 429 and 413 with `Accept: text/html` carry no challenge |
| **LOW-1** | `grep authorized(` hits a decoy | **Fixed** — `RelaisTaskerActivity.kt:82` (called at `:71`), an unrelated private `authorized(token: String)` on the intent-ABI path, is named in AUTH_PATTERN, in Task 3 piece 2, and in the Validation grep block with an expect-two-hits annotation |
| **LOW-2** | Scheme-token case and `.trim()` unspecified — accepting `bearer`/`basic` would be a **widening shipped alongside the advertised tightening** | **Fixed and decided, not opened.** Stay case-**sensitive**; keep the `.trim()`. Grep-verified nothing sends a lowercase scheme (`RelaisExperiments.kt:244/279/314/350`, `HttpChatTransport.kt:78`, `RelaisClientConfig.kt:94`). Pinned by **test 8l** and recorded in `SECURITY.md` — deliberately narrower than RFC 7235, stated so a reviewer who reads the RFC does not file it as a bug |
| **LOW-3** | Task 1's "all-`/` panel" framing is true but **less unique than claimed** | **Fixed.** `/health` records through `ctx.send` (`:812`) → `reply` (`:309-310`) — chain verified end to end rather than taken from the report — and #318's `handleCaCert` records at `:839`, so a monitoring poller floods the same 20 slots harder. Task 1's GOTCHA, the Problem→Solution row and the acceptance criterion now all say "a reduction in `/` noise", not "the panel is clean" |
| **LOW-4** | The sequencing notes are **spent** — #318 landed as `cf316146` | **Fixed, and the rationale replaced rather than recited.** The do-not-relocate conclusion stands, but no longer on "keeps feature-18's line references valid" (those have been consumed). It now rests on CLAUDE.md: `RelaisHttpServer.kt` is **2432** lines against an under-800 target, and the repo's rule is *extract new code rather than grow the file* — which relocating working code does not serve, while it would force two visibility widenings (H2). `Blocked on:` struck from Metadata, the two table rows rewritten, and the acceptance criterion restated as satisfied fact. The stale "2202-line server" figure in Files-to-Change is corrected — that number is exactly what the conclusion now rests on |

### Codex round-2 disposition (2026-09-12, against revision `98e18683`)

A second codex pass **on the revision itself** found four issues, three P1. All four applied; both
load-bearing claims independently re-verified against the tree before acceptance. The theme is that
the *prescriptions* in round 1 were not reliable either — and the three P1s turn out to be **three
instances of one defect class**, which is the finding that mattered most.

| # | Finding | Disposition |
|---|---|---|
| **P1-1** | `android.util.Base64` makes the Basic tests non-hermetic | **Fixed, and the evidence is stronger than reported.** Verified `isReturnDefaultValues = true` at `build.gradle.kts:209` with its own "masks accidental unmocked-Android calls" comment, and `minSdk = 31` at `:44`. The report cited one precedent; there are **three** — `RelaisHttpIo.kt:270`, `RelaisImagesEndpoint.kt:25-26` (which also pre-settles the API level: *"minSdk 31 carries java.util.Base64 (API 26+)"*), and `RelaisAnthropicParser.kt:145-157` (the injection variant). Specified the **direct** `java.util.Base64` form, aliased to avoid colliding with the existing unaliased import at `:20`, and `getDecoder()` not `getMimeDecoder()` so 8e's `runCatching` is a real assertion. Generalised to a rule — **nothing added in this task may call `android.*`** — because `authenticate` and `challengeHeaders` have the same exposure. Recorded as **R15**. The existing `private fun decode` at `:2084` stays on the Android decoder; it serves the multimodal path |
| **P1-2** | The authority comparison is underspecified in a way 8j cannot catch | **Fixed.** Confirmed the node advertises `https://<ip>:8443` (`:883`), so `Origin` carries a port and `java.net.URI.getHost()` drops it. Both opposite bugs — comparing hostname-to-`host:port` (rejects every same-origin POST) and stripping ports from both sides (accepts a different-port cross-origin attacker) — passed the original three-row 8j. Specified **canonical authority** (lowercased host + explicit-or-scheme-default port, parsed not sliced, brackets intact) and extended 8j from three rows to **eight**: matching port, mismatching port, bracketed IPv6, absent `Host`, `Referer`-only, and a malformed URL that must reject rather than throw. Noted the scheme-default fill is defensive — this node never binds 443/80 — so the rule is right for its own reasons rather than for a port number a later change could move |
| **P1-3** | The seam between the two tested halves is tested by nothing | **Fixed as proposed, then widened — see below.** Confirmed both halves: no JVM test class for `RelaisHttpServer`, and **no test in the tree calls `authorized(`**. Adopted the `internal fun authenticate(header, apiKey)` extraction; it does not breach the no-widening rule, since `authorized()` stays `private` and this adds a **new** `internal` helper as `extractApiKey` already is. Test **8s** pins scheme preservation, RED-proven by a compare returning `BEARER` unconditionally. Recorded as **R14** |
| **P2-4** | 8m is still not executable as filed | **Fixed by scoping the helper, not by downgrading.** Task 3 piece 5 specifies a pure `internal fun challengeHeaders(status: Int, accept: String?): List<String>`, so 8m becomes a real JVM test in `RelaisHttpAuthTest.kt` and the placement table's "…if a helper is extracted, otherwise manual" hedge is deleted. The two places now agree. **It takes `status`, not `RelaisHttpGate.Reject`:** the enum version compiles (same module) but drags a gate type into a test file that otherwise touches none, re-splitting the vocabulary the placement table exists to keep together; `status` is exactly equivalent, since `UNAUTHORIZED` is the only `Reject` carrying 401, and the call site still reads `reject.status` so no literal is repeated. Added a row the finding did not ask for: **a 403 must carry no challenge** — the credential was *accepted*, so challenging would tell the browser to re-prompt for a key that is already correct |

**Three corrections to this round's own fixes, caught before the commit landed** — the same lesson as
rounds 1 and 2, now applied to my own output:

- **~~The `Host`-side scheme default contradicted the boundary two rounds went into defending.~~**
  **SUPERSEDED BY ROUND 3's P2 — do not follow this bullet.** It recorded a first draft that filled the
  `Host` side's absent port from the listener's scheme, then "corrected" it to an **asymmetric** rule
  (default-fill the `Origin` side only, take `Host` raw) on the grounds that a `tls: Boolean` parameter
  would push server state into a pure function, and that the asymmetry failed "strict, not loose."
  **Both grounds were wrong** — a parameter is not state, and on a default-port listener the asymmetric
  rule 403s the node's own form. Round 3 restored symmetric normalization *with* the `tls` parameter.
  Left visible rather than deleted, because the wrong reasoning is the useful part of the record.
- **`challengeHeaders` took a gate type it did not need** — see the P2-4 row above.
- **Probe row 4 used `https://` against a `tls = false` probe server**, so it would have failed under a
  *correct* implementation and invited an implementer to "fix" the comparison to satisfy a wrong test.
  Corrected to `http://`, with the reason written down beside it.

**Where I went past the report — P1-3 closes an instance; the class needed more.** Asked whether test
8s alone is sufficient, the answer is no, and P1-2 is the proof: **three** seams of this shape exist
in Task 3, and P1-2's missing `host` arm lands squarely in the one that 8s does not touch.

- **S1** — parse → compare (does Basic report `BASIC`?): closed by **8s**, JVM.
- **S2** — header loop → the `rejectsAsCrossSite` supplier (are all four headers parsed and passed to
  the right slots?): **uncovered**. 8i/8j call the predicate directly *with* a host; 8n injects
  `{ true }`. Omit the `host` arm entirely and **every JVM test still passes.**
- **S3** — `authorized()` / `challengeHeaders()` → their call sites in `handle()` (are they called at
  all?): **uncovered**, and unreachable without the widening this plan forbids.

S2 and S3 cannot be reached from the JVM lane at a price PR-A should pay, so they are closed by one
new hardware probe — **`BasicAuthGateProbe.kt`, test 8t** — using the repo's own documented
instrument for exactly this situation (CLAUDE.md's `*Probe.kt` rule; precedent in
`ClientConfigEndpointProbe.kt`, already this plan's PROBE_STRUCTURE, and `IncompatibleModel404Probe.kt`).
Four requests, each covering a seam no unit test reaches; the Bearer + `cross-site` → 200 row is the
one that catches a wrong-scheme production bug even if 8s were mutated away, and the
`Origin`-with-real-`Host` row is the only place P1-2's fault is observable. Full reasoning, and the
declined JVM alternative (extracting the header-parse `when`), are under *Closing the seam class*.

**The residual is stated, not papered over:** probes are `androidTest`, so **CI catches none of this**.
An unrun probe means S2 and S3 ship unverified. That is the same bar feature-18 shipped under, and the
repo's own rule — *hardware-verified-or-not-done* — is why the acceptance criteria now ask whether the
probe was **actually run**, not whether it exists.

### Codex round-3 disposition (2026-09-12, against revision `fd3f5513`)

Three findings, two P1 — **and the first is a defect in round 2's fix, not in the plan round 2 was
fixing.** All three applied; all verified against the tree. **Findings by round: 13, 4, 3.**

| # | Finding | Disposition |
|---|---|---|
| **P1-1** | The helpers as placed are **members**, so test 8s cannot compile | **Fixed, and the evidence is stronger than reported.** Verified: `class RelaisHttpServer` opens at `:197` and closes at `:2151`, and `authorized()` at `:2069` is an indented `private fun` **inside** it — so "beside `authorized()`" makes the new helpers members, an `internal` member needs an instance, an instance needs a `Context`, and **the seam-closing test would never have run.** R14's own fix contained R14's defect. The report named **six** top-level `internal fun`s after the class; there are **twelve** (`:2168` … `:2423`), which makes the convention unambiguous. All four helpers move top-level, with a comment naming the property so nobody tidies them back in. **The parameter lists needed no change to move** — that they survive intact is the check that the placement is right |
| **P1-2** | Probe 8t does not close the half of S3 it was built for | **Fixed.** All four rows were authenticated, so none produced an HTML 401 or inspected `WWW-Authenticate` — deleting `challengeHeaders(reject.status, accept)` from the sole `reply()` would pass 8m (pure helper, isolated) **and every probe row**. The probe reproduced, inside itself, the vacuity it existed to fix. Added **row 5**: unauthenticated + `Accept: text/html` → 401 carrying *exactly* the challenge. S3 is now split into **S3a** (`authorized()` called — rows 1-4) and **S3b** (`challengeHeaders()` called — row 5) so the two halves cannot be conflated again |
| **P2** | The "fails strict" argument for the asymmetric port rule is wrong in the direction that matters | **Fixed, and the earlier reasoning retracted in place rather than quietly replaced.** On a default-port listener, `https://node/` sends `Origin: https://node` + `Host: node`; the asymmetric rule canonicalises to `node:443` vs raw `node`, mismatches, and **403s a genuine same-origin form** — not the guard tightening but **the exact HIGH-3 failure mode this plan spends a section arguing against**, and 8j's explicit-`:8443`-only rows could not catch it. Took option (a), symmetric normalization with `tls: Boolean` passed in, over option (b) (document the constraint): (b) leaves a latent trap that fires during an unrelated migration, (a) costs one parameter. **My original objection to that parameter was itself the error** — a *parameter* is not state; `rejectsAsCrossSite(…, tls)` is exactly as pure as without it, and `decide` already takes `contentLength`/`maxBody` in that spirit (`RelaisHttpGate.kt:71-72`). New 8j row (ix) pins the default-port case; the migration trigger goes **at the `tls` parameter**, where someone doing a 443/80 move will read it, not in this document |

**What this round did not change:** `java.util.Base64.getDecoder()` and the one-compare
`authenticate()` shape were re-confirmed sound.

*(For the pattern this round continues, see* The rule that falls out *below.)*

### Codex round-4 disposition (2026-09-12, against revision `6b6ccd2b`)

Three findings, one P1. All three applied; all verified against the tree. **P1s per round: many → 3 → 2 → 1.**

| # | Finding | Disposition |
|---|---|---|
| **P1** | The fallback compares **authority but never scheme**, so a cross-origin POST passes | **Fixed.** Confirmed: `tls` as introduced in round 3 affects only *omitted*-port defaulting and never constrains the scheme, so `Origin: http://node:8443` vs `Host: node:8443` on a **TLS** listener canonicalises equal on both sides and is **allowed** — two genuinely different origins, which is the only thing this function decides. **Every 8j row shared a scheme between the two sides, so none could catch it**; new row (x) is the explicit-port scheme mismatch, and row (xi) covers a URL that parses with no host. The scheme check now stands **ahead** of the authority comparison |
| **P2** | `AuthScheme` did not move with the helpers | **Fixed.** Round 3's relocation instruction named four *functions*; the enum is a fifth declaration. Left nested beside `authorized()`, every unqualified `AuthScheme` reference in the now-top-level helpers **fails to compile**, and the plausible "fix" is dragging the helpers back inside the class — undoing P1-1 and re-breaking 8s. Declared top-level in the same region, with that consequence written down |
| **P2** | Probe request count disagreed with itself across three places | **Fixed.** The Files-to-Change row said "Four real requests", the test-table row said "five" but enumerated four outcomes, and only the prose had all five. A reader following either table reintroduces exactly the S3b vacuity round 3 fixed. All three now say five and enumerate five, and the fifth is labelled *"the row a reader skimming the outcome list has twice now dropped"* |

**Round 4 also confirmed two round-3 fixes actually work** — worth recording, since this plan has
twice shipped assertions that could not fail: 8j row (ix) **does** fail under the old asymmetric
rule, and the S3b probe row **is** non-vacuous against deletion of `challengeHeaders(...)`. No live
contradiction was found from the struck round-2 reasoning, so marking it rather than deleting it
holds up.

### Codex round-5 disposition (2026-09-12, against revision `3ce3d574`)

Two findings, one P1 — **and the P1 is a defect in round 4's steer, which was mine to act on.** Both applied; both verified.

| # | Finding | Disposition |
|---|---|---|
| **P1** | Mirroring `WebhookGuard` is **unsafe for an inbound check** — it is an outbound SSRF guard | **Fixed by narrowing the mirror to the parse (`:54-58`) and writing an explicit NOT list.** Verified all three hazards in the file: DNS resolution at `:63-64`; the allowlist bypass at `:66` returning `Allowed` **before** the scheme check at `:68`; and `classify` (`:77-85`) blocking `isLoopbackAddress` and `isSiteLocalAddress` — *"private // 10/8, 172.16/12, 192.168/16"*. The third breaks the feature outright: **RFC1918 is the only network this dashboard is ever reached on**, so a wholesale copy 403s every legitimate same-origin POST while looking like the guard working. Task 3 now states it does no DNS, no `classify`, no allowlist, and must not call `check`. Pinned by new **8j row (xii)**, same-origin RFC1918 → allowed |
| **P2** | The eleven-row table is blind to an **IPv6 default port** | **Fixed.** An implementation testing "contains `:`" for "has a port" is right for `node:8443`, wrong for *every* bracketed IPv6 literal: portless `Host: [::1]` contains colons, so the `Host` side skips its default-port fill while `Origin: https://[::1]` fills to `[::1]:443`, and a valid same-origin request rejects. Added **row (xiii)** and specified bracket-aware parsing — port is the segment after the **last `]`**, or after the only `:` when unbracketed |

**Plus a third, found while verifying P1 — in this plan's own round-4 wording.** It described
`WebhookGuard` as putting *"the scheme check ahead of the policy decision."* The actual order is parse
→ resolve → **allowlist bypass (`:66`)** → scheme check (`:68`) → classify, so the scheme check is
**not** the first policy decision; the allowlist is, and it is one of the steps that must not be
mirrored. The sentence was written from the five lines quoted rather than from the function. Corrected
in place, and recorded under *The rule that falls out* as the third sub-lesson.

**Three things round 5 confirmed rather than found** — recorded because they close open assumptions
rather than opening new ones:

1. **`internal`-from-`test` visibility: CONFIRMED.** Same Android Kotlin module (`build.gradle.kts:17-20`), and `RelaisImagesEndpointTest.kt:59` already calls an `internal fun` declared at `RelaisImagesEndpoint.kt:89`, green. I had flagged this as an unverified assumption of the same shape as round 3's P1-1; it checks out **against the build config**, which is the difference from the `authorized()` case — there, "every existing test proves it" was reasoning about a function no test touched.
2. **Tasks 1 and 2 are still aligned with the tree** — the dashboard writes its own metric and `respondText` does not record (`:862-890`); the refresh insertion point follows the viewport meta (`RelaisDashboard.kt:291-294`). Untouched since round 1 and still correct.
3. **An origin is scheme + host + port, and nothing else is missing** — userinfo, path and query are not origin components; uppercase host is handled by the lowercasing; `Origin: null` and opaque origins fail closed through the no-host path; preferring `Origin` over `Referer` when both are present is correct.

**Why the 8j table kept needing rows — the mechanism, not just the tally.** Individually, **every
reject row also passes an "always reject" implementation, and every allow row passes an "always
allow" one.** Only the *combination* discriminates. So rows were being added that were individually
valid and collectively still blind along whatever axis nobody had thought of yet — which is exactly
how three rows passed both port bugs, eight passed the asymmetric one, ten passed the scheme hole,
and eleven passed both the SSRF-copy and the IPv6 one. A table of this shape is only as strong as its
worst-covered *axis*, and row count is not a proxy for axis count.

### The rule that falls out

**Four rounds, four defects, and in every single case the correct answer was already written in this
repo:**

| Round | The defect | Where the answer already was |
|---|---|---|
| 1 | Mirrored a gate that no longer existed; wrong supplier discipline | `RelaisHttpGate.kt:61` — *"Every effect is a supplier, not a boolean, and that is load-bearing"* |
| 2 | `android.util.Base64` would make every negative Basic test vacuous | `RelaisHttpIo.kt:270` — *"base64-encodes via `java.util.Base64` (NOT `android.util.Base64`)"*, plus `RelaisImagesEndpoint.kt:25-26` and `RelaisAnthropicParser.kt:145-157` |
| 3 | Helpers placed as class members, so the seam test could not compile | The **twelve** top-level `internal fun`s after `:2151` |
| 4 | URL parse invented from scratch, missing the scheme check | `batch/WebhookGuard.kt:54-58` — malformed-rejects, scheme lowercased, host-or-reject, fail closed. *(Round 5: only these five lines. The rest is outbound-SSRF policy — see the caveat below.)* |

The reviews found all four **by reading the tree**. The fixes were proposed without reading it —
mine included, and the ones proposed to me included. The count fell each round (13 → 4 → 3 → 3) but
the *kind* never changed, which is what makes this transferable rather than incidental.

> **Implementation rule for Task 3: before writing any helper, grep for whether this codebase already
> has one.** Not "check the style guide" — check for a working implementation of the same shape. On
> this branch that check would have caught four of four defects, and it costs one `grep` per helper
> against review rounds that cost a session each.

**The caveat, learned the hard way in round 5: mirror the SHAPE, not the POLICY.** Told to reuse
`WebhookGuard`, round 4 pointed at the whole function — and `WebhookGuard` decides whether an
**outbound** URL is safe to call, while `rejectsAsCrossSite` decides whether an **inbound** `Origin`
names this listener. Beyond the parse it DNS-resolves, carries an allowlist that bypasses its own
scheme check, and **blocks RFC1918** — which for a LAN dashboard reachable *only* on RFC1918 would
have rejected every legitimate request while looking exactly like the guard working.

**A security function copied into a different threat model is its own failure mode, and a nasty one:
it arrives with the authority of shipped, reviewed code, which is precisely what stops anyone asking
whether its *decisions* still apply.** Reuse the parse, the normalization, the fail-closed structure.
**Re-derive every policy decision.** The question to ask before "does this function exist?" is *"what
is this function deciding, and is that my question?"*

The four-row table above stays right — round 5 did not overturn any of it. The rule just needs this
attached, because round 4's own fix is the counter-example: it found the right file and still got the
wrong answer out of it, by citing five lines instead of reading seventy.

**A third sub-lesson, from round 4's wording:** that fix described `WebhookGuard` as putting *"the
scheme check ahead of the policy decision."* It does not — the allowlist bypass at `:66` is the first
policy decision and the scheme check sits after it. The sentence was written from the quoted excerpt
rather than the function. **Citing a file is not reading it**, and this plan did it inside the very
fix that introduced the rule against it.

This is the most transferable output of the planning phase, not a footnote to it. It belongs in the
PR description and in `.claude/HANDOFF.md`, not only here.

### Citations

**Every reference in this document was re-derived by `grep`, not taken from
the review.** The six the lead verified — `handleDashboard` **:862**, CSP **:893** (plus the second CSP
at **:918** for `/experiments`), `respondText` **:2105**, `authorized()` **:2069**, `recordRequest`
**`RelaisMetrics.kt:131`** with signature `(endpoint: String, status: Int)`, and
`RelaisHttpServer.kt` = **2432** lines — plus a further ~40 the review did not list, among them:
`RequestContext` :690→**:800**, `readBody` :1913→**:2081**, `provisionedOnDisk` :1119→**:1278**,
`endpointLabel` :1877-1900→**:2042-2068**, `rejectIfModelUnavailable` :1138-1180→**:1284-1339**,
the swap dispatch :1171→**:1330**, `reply` :223-226→**:309-313**, the header loop :244-262→**:340-348**,
`ensureModelSwapInBackground` :409→**:433** with its CAS :410→**:434**, `residentModelId` :301→**:325**,
and in `RelaisDashboard.kt` the READ-ONLY comment :160→**:176**, the viewport meta :204→**:293**,
`escapeHtml` :143-149→**:159-174** and the read-only test :341→**:396-403**.

### Alternatives considered and rejected

- **Key as a query param** (`/?key=…`) — rejected: lands the key in history, server logs, and `Referer`. The original plan already leaned against it; nothing has changed.
- **Cookie + one-time key-entry form** — rejected: see above. Reconsider only if Basic proves unworkable in a target browser.
- **Leaving it API-client-only (the original's default (c)/(a))** — rejected: it leaves the shipped `/experiments` page permanently unusable and the dashboard reachable only by tools that don't need a dashboard.
- **SSE/WebSocket live updates** — rejected: needs `connect-src` and a script, contradicting §2.7 for a readout that changes every few seconds at most.
- **Filtering `/` inside `recentRequests()`** — rejected: also hides a genuine `401`/`429` on `/`. The opt-out belongs at the call site.
- **A separate `/dashboard` path leaving `/` untouched** — rejected: `/` already serves this page and `endpointLabel` already maps it.
- **Moving `handleDashboard`/`handleExperiments` into the new file** (the original Task 1) — rejected: does not compile without widening `RequestContext` and `provisionedOnDisk`, duplicates a change `feature-17` already owns, and — by the plan's own admission — nets near-zero lines. *The "invalidates feature-18's line references" leg of this argument is now spent (#318 merged, `cf316146`); the conclusion rests on the other three plus CLAUDE.md's extract-don't-grow rule against a **2432**-line file.* Cutting it was the single highest-leverage change in the 2026-09-07 revision.
- **Putting the `Origin`/`Referer` comparison inside `RelaisHttpGate.decide`** (option (a)) — rejected. Not because a pure object may not parse (it may; `decide` would only receive `String?` primitives), but because the comparison is a **second algorithm**, and `decide`'s whole job is ordering. See the disposition table's Structural row.
- **Capturing the auth scheme out of the `authorized` lambda via a mutable side-channel** (option (b)) — rejected: re-splits the decision #317 deliberately unified, and creates a second place where the auth outcome lives.
- **Accepting `Origin: null` on the form POST** — rejected: sandboxed iframes and cross-origin redirects send exactly that, so it would hand an attacker the bypass the guard exists to close. The fix is the `Referrer-Policy` narrowing (HIGH-3), not a looser rule.
- **Widening the scheme token to RFC 7235's case-insensitive match** — rejected for this diff: a widening shipped alongside an advertised tightening. See *Decisions → LOW-2*.
- **Deferring the 403 vocabulary to PR-B** — rejected: PR-A is the change that introduces the first 403 in the tree, so deferring means knowingly shipping a mislabelled envelope and handing PR-B a defect it did not cause. See *Decisions → MEDIUM-0*.
- **Widening the refresh interval to 30s to buy back rate-limit budget** — rejected: 30s reads as dead on a status panel (O2), and it delays rather than removes the 429-ends-the-chain failure. State the 20% cost instead. See *Decisions → MEDIUM-1*.
- **Adding `form-action 'none'` to the dashboard CSP in PR-A** — rejected as scope: PR-A adds no form, so the directive would restrict nothing that exists, and PR-B would immediately have to change it to `'self'`.
- **Widening `respondText`/`respondBytes`/`readBody` to `internal`** — rejected once `handleSelectModel` takes primitives: nothing needs them across the file boundary any more. Do not widen speculatively.
- **`409 Conflict` for a busy swap** — rejected: `503 + Retry-After: 25` is the established answer for this exact state at `:1332-1337`.
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
- **O2** — Auto-refresh interval: **10s, decided** (see *Decisions → MEDIUM-1*). 5s doubles the race against the STARTING window; 30s reads as dead on a status panel. Now carries a stated cost rather than an implied one: 20% of the per-IP budget per idle tab, and a 429 that ends the refresh chain permanently (R13). Raise it only if an operator report shows real 429 contention — and then fix the budget, not the interval.

**Decision this plan could not make:** the sequencing of `feature-22-idle-unload`'s `NodeState.IDLE`
against this plan's three new `assembleDashboardStatus` parameters. They do not conflict semantically
(feature-09 leaves the `statusLabel` derivation at `RelaisDashboard.kt:92-96` untouched), so either
order works and the second one rebases — but feature-22 explicitly asks the question at its `:636` and
it belongs to whoever owns the release train, not to either plan.

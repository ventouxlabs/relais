> ## ⚠ STATUS: PREMATURE — DO NOT BUILD
> The user has already decided this is not worth building until **more than one user has more than
> one node**. This plan exists so the design decisions are recorded rather than re-derived later, and
> so the trigger condition is written down. It is deliberately the shortest of the three.
>
> **Trigger to revisit:** a second operator with a second node, or one operator running two nodes with
> real traffic across both. Branch when it happens: `tools/relais-router`. **Not part of the Android
> app.**
>
> **Before writing line one, evaluate LiteLLM proxy** — see *Notes*. It may close this feature with a
> config file instead of a program.

# Plan: Multi-Node Client-Side Router

## Summary

A standalone host-side tool that discovers `_relais._tcp` nodes on the LAN and presents **one**
OpenAI-compatible endpoint in front of them, load-balancing and failing over. It is a client-side
convenience and changes nothing on the node — no new endpoint, no Android code, no protocol change.
Every signal it needs (health, queue depth, thermal state, model identity, per-node base URL) already
exists.

## User Story

**As an** operator running two or more Relais nodes,
**I want** one endpoint that picks a healthy, cool, un-queued node and transparently re-dispatches
when one sheds,
**So that** my clients keep a single base URL and a `503` from a hot phone becomes a retry on another
phone instead of a user-visible failure.

## Problem → Solution

| Problem | Solution |
|---|---|
| With N nodes, every client must know N base URLs and N keys, and pick between them by hand. | One front door; the router holds the per-node keys and presents a single client key. |
| Nodes emit real backpressure, but **only three of the seven 503 sites mean "this node is unhealthy"** — thermal shed (`RelaisHttpServer.kt:543`), the imagegen exclusive-gate drain failure (`:598`), and engine-not-ready (`:647`). The other four mean **"retry *here*, I am loading what you asked for"**: embeddings `:1002`, rerank `:1039`, imagegen `:1084`, the embedder resolve `:1836`, plus the #180 model swap at `:1220` and the TTS voice at `:370`. Today all of it surfaces to the client as a failure. | Treat only the **node-level** three as a routing signal: mark the node unavailable for its `Retry-After` window and re-dispatch. Treat the per-model kicks as **retry-same-node**, and scope any unavailability marking to the endpoint/model — never the node. Re-dispatching a provisioning 503 routes *away* from the only node currently loading the requested model. This is the single highest-value behavior in the tool, and it is the one most easily specified against the wrong contract. |
| Since #180 a node **404s** a model it hasn't provisioned. A naive round-robin sends requests to nodes that cannot serve them. | Model-aware routing off the TXT `model` record and the `/v1/models` `provisioned` flag. |
| Session memory is **node-local** (Room, on-device), so failing over a session silently loses conversation context. | Sticky sessions that fail **loudly** rather than silently failing over. |
| Nodes serve self-signed TLS; a router is a tempting place to `verify=False` once and for all. | TOFU **leaf-SPKI** pinning per node — the public key, not the certificate DER, so feature-18's leaf re-minting on every IP change does not trip the pin. A router that disables verification centralizes the MITM risk across every node at once. |

## Metadata

- **Complexity:** Medium
- **Source PRD:** N/A
- **PRD Phase:** N/A
- **Estimated Files:** 3 (1 CREATE script, 1 CREATE test, 1 CREATE doc) — **plus zero changes to the Android app**
- **Estimated size, stated honestly:** "3 files" is literally true but understates the build. Config parse + the `0600` gate + SPKI pinning + two pollers with backoff + a threaded front door + SSE relay + re-dispatch with a 503 taxonomy + a session-pin map + model eligibility + two mDNS output parsers + a merged `/v1/models` lands around **700-1000 lines** in one module — at or past the repo's own 800-line ceiling (CLAUDE.md style rules). "Single file, no build step" is a real virtue but it is not a smallness claim, and the *NOT Building* list is a **day-one** boundary, not a future one.

## UX Design

The "UI" is an invocation and a config file. No screens, no web page.

### Before

```
client A ──► https://192.168.1.50:8443/v1   (node "comet", key A)
client B ──► https://192.168.1.51:8443/v1   (node "rango", key B)
                     ▲
        every client hardcodes a node + key;
        a 503 from that node is a user-visible failure
```

### After

**Bind: `127.0.0.1:8000`, loopback only.** The router runs on the machine that uses it. This mirrors
the node's own posture (`RelaisNodeService.kt:187-188`: *"plaintext HTTP is loopback-only … so the
bearer key never crosses the network in cleartext"*) and it is what keeps this a single stdlib file:
a LAN-reachable router would put the client key — and, on compromise, **every node's key** — in
cleartext on the LAN, so it would have to terminate TLS itself, with its own cert and its own trust
story. That is a different, larger tool. Multiple *processes* on the host share the router; multiple
*machines* each run their own.

```
    (all on ONE host — the router binds 127.0.0.1 only)
                    ┌────────────────────────────────┐
proc A ──►          │  relais-router  127.0.0.1:8000 │
proc B ──►  ────────►  one base URL, one client key  │
                    │  health cache · SPKI pin       │
                    └───────┬────────────────┬───────┘
                            │                │
              https+pinned  │                │  https+pinned
                            ▼                ▼
                   node "comet"        node "rango"
                   ready · q=0         ready · q=3 · hot
                        ▲                    │
                   picked (least-loaded)     └── 503 → marked unavailable
                                                  for Retry-After, request
                                                  re-dispatched to comet
```

### Interaction Changes

| Touchpoint | Before | After | Notes |
|---|---|---|---|
| Client base URL | one per node | one, stable: `http://127.0.0.1:8000/v1` | **Loopback only.** Per-node URLs keep working — the router must be bypassable |
| API key held by the client | that node's key | the **router's** client key | A client never holds every node's key |
| `503`/`429` from a node | surfaces to the client | absorbed; re-dispatched to the next eligible node | Honors the `Retry-After` window |
| Session requests (`/v1/sessions`) | pinned by construction (one node) | pinned by hash; a down pinned node is a **hard error** | Failing over would silently lose context |
| Streaming | direct SSE | relayed byte-for-byte, unbuffered | Non-negotiable |
| Invocation | N/A | `python3 scripts/relais_router.py ~/.config/relais-router.json` | stdlib-only; no build step. **Positional argument, no `argparse`** — matching `webhook-receiver.py`; and the module name is **underscored**, because `relais-router` is not an importable module name and the test file must import it |
| Config | N/A | `~/.config/relais-router.json`, mode `0600` | Per-node keys + pinned leaf SPKI hashes; never logged |

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisClientConfig.kt` | 100-114 | `buildDiscoveryTxt` — the exact TXT key set the router parses, and the proof that **no API key is ever advertised** |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisNodeService.kt` | 189-190 | HTTPS-only on the LAN, self-signed. Determines the whole TLS design |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 265-270, 707-712 | `/health` is the only unauthenticated route **today** — the router can poll it cheaply and unauthenticated. **Re-check on merge:** feature-18 adds an unauthenticated `GET /ca.crt` (`feature-18-trusted-lan-cert.plan.md:112`), so this framing is accurate now and stale later |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 537-548, 554-576 | The `503`/`429` + `Retry-After` shapes the router must parse, including the top-level `retry_after_seconds` sibling |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 271-280, 87-88, 565-576 | **The two different 429s.** `:271-280` is the per-**socket-IP** rate limiter (`RATE_LIMIT = 30` / `RATE_WINDOW_MS = 60_000`) and carries **no** `Retry-After` and **no** `retry_after_seconds`; `:565-576` is the admission queue and carries both plus `"code":"queue_full"`. The router must tell them apart |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 775-781 | `handleMetrics` — JSON **only** when `Accept` contains `application/json`, otherwise Prometheus text. Getting this wrong means `queue_depth` simply is not in the response |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/core/NodeState.kt` | 16-42 | `THERMAL_HOT_THRESHOLD = 3` and the health precedence the router should mirror rather than reinvent |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt` | 403-405 (Prometheus), 452-453, 456, 462 (JSON) | `queue_depth`, `shed_total`, and `decode_tokens_per_second` (`:456`) — the tie-break. **The router reads the JSON render**, so the Prometheus rows are context, not the target |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 2055-2095 | The `/v1/models` shape and the `provisioned` flag that makes routing model-aware |
| **P1** | `.claude/scripts/webhook-receiver.py` | 1-27 | **The only Python precedent in the repo** — stdlib-only, docstring-as-runbook |
| **P2** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisDiscovery.kt` | 103-110 | `updateModel` has **zero callers**. Read its KDoc *and* the correction in *Notes* — the KDoc's reasoning is itself out of date |
| **P2** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisEngine.kt` | 409-431 | `ensureModelSwapInBackground` — #180 swaps the resident model **in-process, without a restart**. This is why the TXT `model` record goes stale |
| **P2** | `SECURITY.md` | 39-50 | mDNS is unauthenticated; the self-signed MITM limitation is already on record |
| **P2** | `.claude/PRPs/plans/feature-22-idle-unload.plan.md` | Problem→Solution, Task 4 | An idle-unloaded node keeps `ready=false` and gains `"state":"IDLE"`. Read before writing the eligibility predicate |
| **P2** | `.claude/PRPs/plans/feature-18-trusted-lan-cert.plan.md` | 29, 112, 143, 158 | Leaf re-minted on IP change **reusing the leaf key**; `--pinnedpubkey` pins the end-entity SPKI; CA SPKI ≠ leaf SPKI |
| **P2** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 431, 913-914 | `/v1/clientconfig` — the existing single-node answer to "how do I point a client at this" |

## External Documentation

| Topic | Source URL | Key Takeaway |
|---|---|---|
| LiteLLM proxy (multi-backend routing) | https://docs.litellm.ai/docs/proxy/configs | Already does OpenAI-compatible multi-backend routing with retries and fallbacks, config-file driven. **Evaluate before writing code** |
| `avahi-browse` service resolution | `man avahi-browse` (`-rpt` for parseable, resolved, terminating output) | Stdlib-free mDNS browsing by shelling out on Linux |
| `dns-sd` browse/resolve | `man dns-sd` (macOS) | The macOS equivalent; output format differs and needs its own parser |
| Python `ssl` certificate access | https://docs.python.org/3/library/ssl.html | `getpeercert(binary_form=True)` returns the whole DER — hash the **public key** out of it, not the DER, or every leaf re-mint breaks the pin |
| SSE relay semantics | https://html.spec.whatwg.org/multipage/server-sent-events.html | Events are `\n\n`-delimited; any buffering layer breaks streaming clients |

**Research item — LiteLLM proxy**
- **KEY_INSIGHT:** It covers ~80% of this plan (multi-backend, retries, fallbacks, per-backend keys) for the cost of a YAML file. **Do not lean on feature-21 here:** that plan is about HA's core **`litellm` integration**, a generic OpenAI-compatible *client*, which is a different thing from the LiteLLM **proxy server** and corroborates nothing about it.
- **APPLIES_TO:** The go/no-go decision itself.
- **GOTCHA:** It does **not** do mDNS discovery, thermal-aware ranking, or node-local sticky sessions. If those three turn out not to matter in practice, this feature closes without code — which is the outcome the repo's research-and-reuse rule points at.

**Research item — avahi/dns-sd shell-out**
- **KEY_INSIGHT:** Keeps the script stdlib-only, avoiding the repo's first `requirements.txt`.
- **APPLIES_TO:** Task 6.
- **GOTCHA:** Two different output formats to parse, and neither tool exists on Windows. **mDNS across VLANs and guest networks frequently does not work at all** — which is why the static list is the baseline and discovery is the enhancement, never the other way round.

**Research item — SPKI pinning**
- **KEY_INSIGHT:** TOFU pinning gives real protection against a spoofed mDNS advertisement without a CA.
- **APPLIES_TO:** Tasks 2 and 6.
- **GOTCHA:** **Pin the leaf SPKI, not the certificate DER.** `getpeercert(binary_form=True)` hands you the whole DER, and hashing that is the obvious-but-wrong move: feature-18 re-mints the leaf on every LAN-IP change and at 90-day expiry, **reusing the leaf key** specifically so an SPKI pin survives (`feature-18-trusted-lan-cert.plan.md:29`, `:143`). A DER pin turns every DHCP lease change into this plan's own mandated hard raise. Pin the **end-entity** key, not the CA's — they differ (`:158`). Separately: if feature-18 lands, trusting its CA may replace pinning entirely — simpler and survives rotation. Check feature-18's status at build time. **Extraction mechanism (codex P1, PR #310): the stdlib has no X.509 parser to pull the SPKI out of the DER — shell out to `openssl x509 -pubkey -noout -inform DER` (see Task 2's GOTCHA) rather than adding a pip dependency or falling back to a DER pin.**

## Patterns to Mirror

> **Assumption stated explicitly:** the brief said to mirror "the repo's existing Python under
> `scripts/`". **There is none.** `scripts/` is bash-only — `compile-tensor-g5-model.sh`,
> `dump-litertlm-api.sh`, `fetch-tensor-dispatcher.sh`. The repo's *only* Python is
> `.claude/scripts/webhook-receiver.py`, and that is what the patterns below cite. There is no
> `pyproject.toml` and no `requirements.txt` anywhere in the repo.

**NAMING_CONVENTION / MODULE_DOCSTRING_AS_RUNBOOK** — the module docstring carries the numbered setup
steps *and* the honest limitations; no `argparse`, positional `sys.argv`:

```python
# SOURCE: .claude/scripts/webhook-receiver.py:1-27
#!/usr/bin/env python3
"""Throwaway local webhook receiver for the #14 hermetic delivery gate.

Use this only when the device has no internet (so webhook.site is unreachable). It records the incoming
signed POST and verifies the HMAC over the EXACT received bytes — the same bytes WebhookDelivery signed.

Setup (see .claude/PRPs/plans/ondevice-gates-13-14.plan.md Task 4 "ALTERNATIVE"):
  1. Read the node's HMAC secret via a throwaway probe (RelaisConfig.webhookHmacSecret) and export it:
       export RELAIS_WEBHOOK_SECRET=<secret>
  2. Run this receiver on the host:    python3 .claude/scripts/webhook-receiver.py 9999
  3. Map the device loopback to the host:   adb -s <serial> reverse tcp:9999 tcp:9999
  ...

NOTE: this exercises signing + SSRF-bypass + delivery plumbing but NOT the TLS/SNI/chain path. Prefer the
public webhook.site receiver (valid cert, public IP, no allowlist) when the device has internet.
"""
import hashlib
import hmac
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

SECRET = os.environ.get("RELAIS_WEBHOOK_SECRET", "").encode()
HEADER = "X-Relais-Signature"  # WebhookSigner.HEADER
```

Note the trailing-comment idiom (`# WebhookSigner.HEADER`) tying a constant back to its Kotlin
source of truth — the router's TXT keys and metric names should be annotated the same way.

**SERVICE/HANDLER_PATTERN** — the precedent uses `http.server` from the stdlib with a
`BaseHTTPRequestHandler` subclass and `# noqa: N802` on the API-mandated method names:

```python
# SOURCE: .claude/scripts/webhook-receiver.py:29-43
class Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802 (http.server API)
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        received = self.headers.get(HEADER, "")

        print(f"\n--- POST {self.path} ---")
        ...
        if SECRET:
            expected = "sha256=" + hmac.new(SECRET, body, hashlib.sha256).hexdigest()
            ok = hmac.compare_digest(expected, received)
            print(f"  HMAC: {'OK — signature matches' if ok else 'MISMATCH'}")
```

The router's front door mirrors this: `do_POST` for `/v1/*`, `do_GET` for `/v1/models` and its own
`/health`, `print()` for operator-visible logging (never a key). `ThreadingHTTPServer`, not
`HTTPServer`, so a held streaming connection does not block every other client.

**PROTOCOL_CONTRACT the router consumes** — the two Kotlin sources the parser must track:

```kotlin
// SOURCE: RelaisClientConfig.kt:100-114 — the TXT record the router parses
fun buildDiscoveryTxt(modelId: String, version: String, httpsPort: Int, caps: Capabilities): Map<String, String> =
  linkedMapOf(
    "model" to capTxt(modelId),
    "version" to capTxt(version),
    "https" to httpsPort.toString(),
    "api" to "openai",
    "path" to "/v1",
    "auth" to "bearer",
    "caps" to capTxt(caps.toCapsString()),
  )
```

```kotlin
// SOURCE: RelaisHttpServer.kt:536-548 — the backpressure shape the router treats as a routing signal
reply(
  503,
  RelaisError.json("thermal backpressure; retry later", RelaisError.SERVICE_UNAVAILABLE)
    .put("retry_after_seconds", retry),
  listOf("Retry-After: $retry"),
)
```

`retry_after_seconds` is a **top-level sibling** of `error`, not nested inside it — a parser that
looks in `error.retry_after_seconds` finds nothing. Prefer the `Retry-After` **header**, which is
standard, and fall back to the JSON field.

**ERROR_HANDLING** — fail closed and loudly. A pinned session whose node is down raises; a changed
leaf SPKI hash raises; an empty eligible set returns a clean OpenAI-shaped error rather than
crashing. Never `verify=False`, never swallow a TLS error.

**LOGGING_PATTERN** — `print()` to stdout in the precedent's style (`--- POST /path ---` banners,
two-space indented detail lines). **Never log a bearer key, and never log a full request body.**

**TEST_STRUCTURE** — `unittest` from the stdlib (no pytest dependency), mirroring the repo's
"pure logic is unit-tested, IO is probed" split. Pure selection functions go in the same file and are
called directly; the HTTP front door and real discovery are **manual smoke tests** documented in the
module docstring, exactly as `webhook-receiver.py` documents its own `adb` setup. There is no Kotlin
test to mirror here — the Android suite's conventions (JUnit 4, backtick names) do not carry over to
Python; what carries over is the *split*, not the syntax.

**PROBE_STRUCTURE** — **N/A.** This tool runs on a host, not a device. Its equivalent of a probe is
the two-live-node manual smoke test in *Validation Commands*.

## Files to Change

| File | CREATE/UPDATE | Justification |
|---|---|---|
| `scripts/relais_router.py` | **CREATE** | The tool. Single file, stdlib-only, executable, `#!/usr/bin/env python3`. **Underscored** so the test file can import it; `scripts/` has no `__init__.py` |
| `scripts/test_relais_router.py` | **CREATE** | `unittest` suite for the pure selection/parsing functions |
| `docs/multi-node-router.md` | **CREATE** | Operator page: config-file shape, invocation, the bypass guarantee, the limits |
| *(nothing under `Android/`)* | — | **The node is unchanged.** No endpoint, no TXT change, no auth change |

## NOT Building

- **No UI.** No web page, no TUI.
- **No persistence** beyond the config file and the in-memory session-pin map.
- **No metrics endpoint of its own**, no request logging to disk, no queuing, no multi-tenancy, no auth beyond the single client key. If any of these start to feel necessary, that is the signal the deployment has outgrown a single-file script and wants a real reverse proxy.
- **No Android change of any kind.** Every signal the router needs already exists.
- **No `zeroconf` dependency** in v1 — it would introduce the repo's first `requirements.txt`.
- **No blanket `verify=False`.**
- **No silent session failover.**

## Step-by-Step Tasks

### Task 1 — Evaluate LiteLLM proxy first (**no code until this is answered**)

- **ACTION:** Decide whether an existing tool already closes this feature, and write the decision down.
- **IMPLEMENT:** Stand up LiteLLM proxy against the two nodes with a `config.yaml` listing each as an OpenAI-compatible backend (`api_base` = the node's `https://…:8443/v1`, `api_key` = that node's key) and point one client at it. Test the four behaviors this plan exists for: (a) does it route across both nodes, (b) does it absorb a real `503 + Retry-After` from a thermally shedding node rather than surfacing it, (c) does streaming survive the hop, (d) can a per-backend self-signed cert be verified rather than disabled. Then answer the only question that matters: **are mDNS discovery, thermal-aware ranking, and node-local sticky sessions worth a program of their own?** If LiteLLM covers enough, close this feature with a short doc pointing at it and stop — that outcome is a success, not a failure.
- **MIRROR:** nothing (no code produced); record the finding the way `SPIKE-FINDINGS.md` records settled facts — dated, versioned, do-not-re-derive.
- **IMPORTS:** none.
- **GOTCHA:** LiteLLM's gaps are the *whole* justification for tasks 2-8; if this task is skipped, every later task is unjustified work. Also check the repo's research-and-reuse rule before dismissing it — "it lacks three features" is only an argument if those three are load-bearing for a real deployment, and today there is no such deployment. Do not evaluate against a hypothetical fleet.
- **VALIDATE:** A written go/no-go in the PR description naming the LiteLLM version tested and which of (a)-(d) it passed. **No file under `scripts/` is created before this exists.**

### Task 2 — Static-list router core

- **ACTION:** Config file, per-node keys, leaf-SPKI pinning, health polling, least-loaded selection.
- **IMPLEMENT:** Read `~/.config/relais-router.json` (path from positional `sys.argv[1]`, defaulting to that), refusing to start if it is not mode `0600`. Shape: a list of `{name, host, port, key, spki_sha256?}` plus a `client_key`. A background thread polls `GET /health` (unauthenticated) every **10 s** per node and `GET /metrics` **with `Accept: application/json`** (bearer) every **30 s**, backing off to 60 s for a node down >5 minutes. Routing reads **cached** state only — never poll on the request path, or a dead node adds latency to a request that isn't going there.
  **Eligibility — `ready` alone is not enough:** eligible = `ready == true` **or** `state == "IDLE"`. feature-22 gives an idle-unloaded node `ready=false` deliberately (that field's meaning is not changing) and a new `"state":"IDLE"`; keying only on `ready` drops every idled node out of the pool **permanently**, and nothing in the router wakes it — the router would report "no eligible node" for a perfectly healthy fleet, recoverable only by a client bypassing the router to re-warm a node directly. Rank IDLE **last**: it is eligible, but it pays a cold start. If a node's `/health` has no `state` field (a node predating feature-22), fall back to `ready` alone.
  Then: `thermal_state >= 3` drops to last resort (a hot node will shed anyway); among eligible, lowest `queue_depth`, tie-broken by highest `decode_tokens_per_second` (`RelaisMetrics.kt:456`).
- **MIRROR:** MODULE_DOCSTRING_AS_RUNBOOK, SERVICE/HANDLER_PATTERN, ERROR_HANDLING.
- **IMPORTS:** `json`, `os`, `ssl`, `socket`, `hashlib`, `threading`, `time`, `http.client`, `http.server`, `sys` — all stdlib.
- **GOTCHA:** `3` is `THERMAL_HOT_THRESHOLD` from `core/NodeState.kt:20`; annotate the constant with that source the way `webhook-receiver.py` annotates `HEADER`. **Pin the leaf SPKI, not the cert DER.** `ssl.getpeercert(binary_form=True)` gives the whole DER; hash instead the public key extracted from it, because feature-18 re-mints the leaf on every LAN-IP change and at 90-day expiry while **reusing the leaf key** precisely so an SPKI pin survives (`feature-18-trusted-lan-cert.plan.md:29`, `:143`). A cert-DER pin turns every DHCP lease change into this plan's own mandated hard raise. Pin the **end-entity** SPKI, not the CA's (`:158` — they differ). TOFU still means recording on **first** contact; a changed pin afterwards must **raise**, never silently re-pin.

  **Codex review (P1, PR #310): Python's stdlib has no X.509 parser and no API to pull the SPKI
  out of the DER `ssl.getpeercert(binary_form=True)` returns.** `ssl`/`hashlib` alone can only hash
  the *whole* DER, which is exactly the cert-DER pin this GOTCHA says not to do (it breaks on every
  feature-18 re-mint). This plan is stdlib-only (see Files to Change / M4's honest size note), so
  the fix cannot be "add `cryptography`" without abandoning that constraint outright. **Fix: shell
  out to the system `openssl` binary** — `openssl x509 -pubkey -noout -inform DER` on the raw DER
  bytes (stdin, via `subprocess.run(["openssl", "x509", "-pubkey", "-noout", "-inform", "DER"],
  input=der_bytes, capture_output=True, check=True)`), then hash the returned PEM public key with
  `hashlib.sha256`. `openssl` is present on effectively every Linux/macOS box this router would run
  on (it's what curl's `--pinnedpubkey` already assumes for the very same pin, `:143`); this is a
  runtime dependency on a system binary, not a new pip package, so it does not reopen M4. If
  `openssl` is absent, fail closed at startup with a clear error naming the missing binary — never
  silently fall back to a cert-DER pin.
- **VALIDATE:** `python3 -m unittest discover -s scripts -p 'test_*.py'` (selection tests) — **not** `python3 -m unittest scripts.test_relais_router`, which needs a `scripts/__init__.py` that does not exist. The mode-`0600` refusal is observable by `chmod 644` and re-running.

### Task 3 — OpenAI-compatible front end with SSE pass-through

- **ACTION:** Accept OpenAI requests on one port and relay to the chosen node.
- **IMPLEMENT:** `ThreadingHTTPServer(("127.0.0.1", 8000), …)` + a `BaseHTTPRequestHandler`. Validate the client bearer against `client_key`; swap in the chosen node's key upstream. **Read the request body into memory first**, bounded by the node's own `MAX_BODY_BYTES` (`RelaisHttpServer.kt:73` = 32 MB) — `self.rfile` is a **single-consumption** stream, so streaming it straight upstream makes task 4's re-dispatch mechanically impossible. "No buffering" applies to the **response** only: for `"stream": true`, relay the upstream body **byte-for-byte with no buffering**, flushing after every write. Also serve the router's own `/health` and a merged `/v1/models`.
- **MIRROR:** SERVICE/HANDLER_PATTERN; LOGGING_PATTERN.
- **IMPORTS:** `http.server.ThreadingHTTPServer`, `http.client.HTTPSConnection`.
- **GOTCHA:** SSE breaks the moment anything buffers. Do not read the upstream *response* into a string; do not let a framework add `Content-Length`. Because streaming holds a connection open for the whole generation, `HTTPServer` (single-threaded) would serialize every client — `ThreadingHTTPServer` is required, not preferred. The request/response asymmetry is the thing to get right: **buffer the request, stream the response.** Bind loopback explicitly; `ThreadingHTTPServer(("", 8000))` defaults to `0.0.0.0` and would silently put the client key on the LAN.
- **VALIDATE:** Manual smoke against two live nodes (below); tokens must arrive incrementally, not in one block.

### Task 4 — Backpressure-aware re-dispatch

- **ACTION:** Turn a `503`/`429` into a transparent retry.
- **IMPLEMENT:** Discriminate before reacting. **Node-level 503 → re-dispatch:** only thermal shed (`RelaisHttpServer.kt:543`), the imagegen drain failure (`:598`), and engine-not-ready (`:647`). Read `Retry-After` (header first, then the top-level `retry_after_seconds` JSON field), mark that node unavailable until `now + N`, and re-dispatch to the next eligible node. **Per-model 503 → retry the SAME node, and never mark it unavailable:** embeddings (`:1002`), rerank (`:1039`), imagegen (`:1084`), the embedder resolve (`:1836`), the #180 swap (`:1220`, `Retry-After: 25`) and the TTS voice (`:370`). Their KDocs are explicit — the 503 kicks a one-time background load **on that node** and asks the caller to retry **there** (`:998`, `:1125`). If unavailability is tracked at all for these, scope it to the endpoint or the model, never the node.
  **Two different 429s:** the admission-queue 429 (`:565-576`) carries `"code":"queue_full"`, `retry_after_seconds`, and a `Retry-After` header — treat it like a node-level 503. The per-socket-IP rate-limit 429 (`:271-280`) carries **none of those**, so "mark unavailable until `now + N`" has an undefined N and would walk the whole fleet out of the eligible set. **Pass that one straight through to the client unchanged.** Discriminate on `code == "queue_full"`.
  Cap total attempts at the number of eligible nodes; if none remain, return the last upstream error unchanged.
- **MIRROR:** the ERROR_HANDLING shape at `RelaisHttpServer.kt:537-548`; the queue-429 shape at `:565-576`.
- **IMPORTS:** none new.
- **GOTCHA:** A **streaming** request that has already emitted bytes **cannot** be re-dispatched — the client has seen a partial answer. Only re-dispatch before the first byte reaches the client. And never re-dispatch a non-idempotent session-bearing request (task 5). **Re-dispatch also depends on task 3 buffering the request body** — you cannot replay `self.rfile`. Finally, note the aggregate ceiling this design creates and document it: every client behind the router shares **one 30-requests-per-60-seconds bucket per node**, because the node rate-limits per socket IP (`:87-88`, `:271-280`) and reads no `X-Forwarded-For` anywhere in the codebase.
- **VALIDATE:** Unit test with a fake node table; on device, force thermal shed and watch the request land elsewhere.

### Task 5 — Sticky sessions + model-aware routing

- **ACTION:** Pin session traffic; never route a model a node cannot serve.
- **IMPLEMENT:** Hash any session id (`/v1/sessions`, `X-Relais-Session`) to a node and record the mapping in memory. A pinned node that is down is a **hard error**, not a failover. For models: build the eligible set from each node's `/v1/models` `provisioned` flag, refreshed with the 30 s metrics poll; a model no node has provisioned returns a clean OpenAI-shaped 404 naming which models *are* available.
- **MIRROR:** ERROR_HANDLING (fail loudly).
- **IMPORTS:** `hashlib` (already imported).
- **GOTCHA:** **Session memory is node-local** (Room, on-device) — silent failover loses conversation context, which is a correctness bug, not a degraded experience. Separately: do **not** trust the TXT `model` record as the source of truth. `RelaisDiscovery.updateModel` (`RelaisDiscovery.kt:110`) has **zero callers**, and its KDoc's reassurance (*"a model switch requires a process restart … so the TXT is always fresh after a switch"*, `:103-108`) **is itself stale** — #180's `ensureModelSwapInBackground` (`RelaisEngine.kt:409-431`) swaps the resident model **in-process, with no restart**, which is exactly the hot-swap path that KDoc says to call `updateModel` from. Nobody does. Use TXT as a hint and `/v1/models` as the authority.
- **VALIDATE:** Unit tests 3 and 4 below.

### Task 6 — mDNS discovery, degrading to the static list

- **ACTION:** Populate the node list from `_relais._tcp` where possible.
- **IMPLEMENT:** Shell out to `avahi-browse -rpt _relais._tcp` (Linux) or `dns-sd -B`/`-L` (macOS), parse host/port/TXT, and **merge** into the static list — discovered nodes still need a key and a pinned SPKI from the config before they are eligible.
- **MIRROR:** the precedent's shell-out-and-parse pragmatism; stdlib only.
- **IMPORTS:** `subprocess`, `shutil.which`.
- **GOTCHA:** mDNS is **unauthenticated** (`SECURITY.md:39-50`) — a spoofed advertisement could add a hostile "node". **Discovery without SPKI pinning must not ship.** A discovered node with no configured key is listed as *known but unusable*, never silently trusted. Neither tool exists on Windows; absence must degrade to the static list, not crash.
- **VALIDATE:** Unit test 5 (TXT parsing) against captured `avahi-browse` output; manual on a real LAN.

### Task 7 — `unittest` suite + smoke runbook

- **ACTION:** Cover the pure functions; document what only two live nodes can prove.
- **IMPLEMENT:** `scripts/test_relais_router.py` with tests 1-6 from *Testing Strategy*, importing the router as `import relais_router`. Put the two-node smoke procedure in the router's **module docstring**, mirroring how `webhook-receiver.py` documents its `adb reverse` setup.
- **NAMING IS LOAD-BEARING:** the script must be `scripts/relais_router.py`, **underscored**. `relais-router.py` is not a valid module name and the test file could not import it without `importlib.util.spec_from_file_location` boilerplate. `scripts/` has no `__init__.py` (it is three `.sh` files today), so `discover -s scripts` works but `unittest scripts.test_relais_router` does not — use the former everywhere.
- **MIRROR:** TEST_STRUCTURE; MODULE_DOCSTRING_AS_RUNBOOK.
- **IMPORTS:** `unittest`.
- **GOTCHA:** Streaming pass-through and real discovery are **not** unit-testable here — do not fake them into green tests. Say so in the docstring.
- **VALIDATE:** `python3 -m unittest discover -s scripts -p 'test_*.py' -v`.

### Task 8 — `docs/multi-node-router.md`

- **ACTION:** One operator page.
- **IMPLEMENT:** Config-file shape and permissions, invocation, the routing policy in three sentences, the **bypass guarantee** (per-node URLs keep working and stay documented), and the explicit limits (no UI, no persistence, no metrics of its own).
- **MIRROR:** `docs/RUNBOOK.md` voice.
- **GOTCHA:** Lead with the premature-status note so a reader does not assume this ships.
- **VALIDATE:** Manual read-through.

## Testing Strategy

### Unit Tests

| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| **1. Node selection** | `[(ready=T,thermal=0,q=2,tps=5), (ready=T,thermal=0,q=0,tps=4)]` | the `q=0` node | Least-loaded wins over faster |
| 1b. hot deprioritized | one node `thermal=3`, one `thermal=0` | the cool node, even at higher `q` | Yes |
| 1c. unready excluded | all `ready=False` **and no `state`** | clean error, **not** a crash | Yes — empty eligible set |
| **1e. IDLE is eligible, ranked last** | one node `ready=False, state="IDLE"`, one `ready=True` | the ready node; the IDLE node is still **in** the pool | Yes — **the feature-22 collision.** Keying on `ready` alone drops every idled node out forever |
| 1f. only IDLE nodes left | all nodes `ready=False, state="IDLE"` | an IDLE node is chosen (cold start accepted), **not** "no eligible node" | Yes |
| 1g. node predating feature-22 | `/health` with no `state` key | falls back to `ready` alone; no `KeyError` | Yes — forward/backward compat |
| 1d. tie-break | two nodes, equal `q` | higher `decode_tokens_per_second` | |
| **2. Backpressure (node-level)** | thermal `503` with `Retry-After: 30` | node marked unavailable 30 s; next request goes elsewhere | Yes |
| 2b. window expiry | same, clock advanced 31 s | node eligible again | Yes |
| 2c. header absent | `503` with only top-level `retry_after_seconds` | same treatment | Yes — the shape is a **top-level sibling** of `error` |
| **2d. per-model 503 is NOT a node signal** | `503` "embeddings model is provisioning" (`:1002` shape) | retried on the **same** node; node **not** marked unavailable | Yes — **re-dispatching here routes away from the only node loading what was asked for** |
| 2e. #180 swap 503 | `503` "resident model differs … swapping" + `Retry-After: 25` | retried on the same node | Yes — same class as 2d |
| **2f. queue 429** | `429` with `"code":"queue_full"` + `retry_after_seconds` | treated like a node-level 503 | Yes |
| **2g. rate-limit 429** | `429` with no `code`, no `Retry-After`, no `retry_after_seconds` | **passed through to the client unchanged**; node not marked | Yes — undefined N would walk the whole fleet out of the pool |
| 2h. request body replay | non-streaming request re-dispatched after a node-level 503 | the second node receives the **identical** body | Yes — proves the body was buffered, not consumed |
| **3. Sticky sessions** | same session id, twice | same node both times | |
| 3b. pinned node down | pinned node `ready=False` | **raises**; does not fail over | Yes — silent failover loses context |
| **4. Model routing** | model provisioned on exactly one node | that node | |
| 4b. model nowhere | model on no node | clean 404 naming available models | Yes |
| **5. TXT parsing** | the `buildDiscoveryTxt` key set | all seven keys parsed | |
| 5b. 63-byte truncation | a `model` value at the cap | parses; no crash | Yes |
| 5c. unknown/missing key | a newer node's extra key | ignored; parse succeeds | Yes — forward compat |
| **6. SPKI pinning** | recorded SPKI hash ≠ presented | **raises**; not silently accepted | Yes — the anti-MITM property |
| 6b. first contact | no recorded pin | records it, proceeds | TOFU |
| 6c. leaf re-minted, same key | a new cert DER carrying the **same** public key | **accepted** — this is why the pin is on the SPKI, not the DER | Yes — a cert-DER pin would raise on every DHCP lease change |

### Edge Cases Checklist

- [ ] Config file not mode `0600` → refuse to start (it holds every node's key).
- [ ] Zero nodes configured → clean startup error, not a traceback.
- [ ] A node returning malformed JSON from `/metrics` → that node degrades to health-only, does not poison the table.
- [ ] Streaming request already emitting bytes → **must not** be re-dispatched.
- [ ] `avahi-browse`/`dns-sd` absent → static list only, no crash.
- [ ] A discovered node with no configured key → listed as known-but-unusable, never silently trusted.
- [ ] Clock used for `Retry-After` windows must be monotonic, not wall-clock (an NTP step must not un-blacklist a node).
- [ ] `GET /metrics` **without** `Accept: application/json` returns Prometheus text and has no `queue_depth` key — the poller must send the header, and must not silently treat a parse failure as "queue depth 0".
- [ ] The router binds `127.0.0.1` explicitly — verify with `ss -ltnp`, not by reading the code (`ThreadingHTTPServer(("", 8000))` defaults to `0.0.0.0`).

## Validation Commands

The Gradle commands do **not** apply — this feature touches no Kotlin. `report-worker/` is untouched,
so its `npm` job does not apply either.

```bash
# Unit tests (stdlib unittest, no dependencies, no venv)
cd /var/home/user/Documents/vibe-code/relais
python3 -m unittest discover -s scripts -p 'test_*.py' -v

# Syntax/style sanity without adding a linter dependency
python3 -m py_compile scripts/relais_router.py
```

### Manual Validation — requires two live nodes (this is the real gate)

```bash
# 0. Config must be locked down or the router refuses to start.
chmod 600 ~/.config/relais-router.json

# 1. Start the router.
python3 scripts/relais_router.py ~/.config/relais-router.json

# 2. Non-streaming round trip through the router's single key.
curl http://127.0.0.1:8000/v1/chat/completions \
  -H "Authorization: Bearer <router-client-key>" \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma-4-e2b-it","messages":[{"role":"user","content":"reply one word: ping"}]}'

# 3. STREAMING must arrive incrementally, not in one block — the non-negotiable behavior.
curl -N http://127.0.0.1:8000/v1/chat/completions \
  -H "Authorization: Bearer <router-client-key>" \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma-4-e2b-it","stream":true,"messages":[{"role":"user","content":"count to twenty"}]}'

# 4. Backpressure: load one node until it sheds, confirm the request lands on the other.
#    (watch the router's stdout for the re-dispatch line)

# 5. Bypass guarantee: the per-node URL still works directly.
curl -k https://<node-ip>:8443/health

# 6. SPKI pinning: point a node entry at a different host and confirm it RAISES.

# 7. Bind address: prove it is loopback-only, do not take the code's word for it.
ss -ltnp | grep 8000     # must show 127.0.0.1:8000, never 0.0.0.0:8000
```

- [ ] mDNS discovery finds both nodes on a flat LAN; confirm it degrades cleanly on a VLAN where it does not.
- [ ] Kill one node mid-conversation on a **non**-session request → transparent re-dispatch.
- [ ] Kill the pinned node mid-**session** → loud error, not a silently-context-less reply.

## Acceptance Criteria

- [ ] The trigger condition is met and recorded in the PR (this plan is otherwise shelved).
- [ ] **LiteLLM proxy was evaluated first** and the decision to write code instead is written down with reasons.
- [ ] A single OpenAI-compatible base URL serves clients across ≥2 nodes with per-node keys held only by the router.
- [ ] A **node-level** `503` (`:543`/`:598`/`:647`) or a `"code":"queue_full"` `429` is absorbed and re-dispatched — demonstrated against a real thermally-shedding node. A **per-model** 503 (provisioning, #180 swap) is retried on the **same** node and never marks it unavailable. A rate-limit `429` is passed straight through.
- [ ] An idle-unloaded node (`ready=false`, `"state":"IDLE"`) is still **eligible**, ranked last — demonstrated by idling a node past its TTL and confirming the router still routes to it rather than reporting an empty pool.
- [ ] The request body is buffered before dispatch (bounded by 32 MB) so a re-dispatch replays it identically; only the **response** is streamed.
- [ ] Streaming is relayed byte-for-byte; a `-N` curl shows incremental tokens.
- [ ] Sticky sessions never silently fail over; a down pinned node is a loud error.
- [ ] TLS is verified by a pinned **leaf SPKI** (not a cert DER, which feature-18's leaf re-minting would break); **no `verify=False` anywhere** in the file.
- [ ] The listener is `127.0.0.1:8000`, verified with `ss -ltnp`.
- [ ] A model no node has provisioned returns a clean 404, never a blind dispatch.
- [ ] Stdlib-only: no `requirements.txt`, no `pyproject.toml` added to the repo. The module is `scripts/relais_router.py` (underscored, importable) and the suite runs via `discover -s scripts`.
- [ ] The 30-requests-per-60-seconds **aggregate per-node** ceiling the router creates is documented, not discovered in production.
- [ ] The bypass guarantee holds — per-node URLs still work and are documented.
- [ ] `python3 -m unittest discover -s scripts -p 'test_*.py'` green; independent code review APPROVE on the **final** diff.

## Completion Checklist

- [ ] Patterns followed (module-docstring-as-runbook, stdlib `http.server`, constants annotated with their Kotlin source)
- [ ] Error handling: fails closed on SPKI mismatch, empty eligible set, and down pinned session; per-model 503s are retried in place rather than treated as node failures
- [ ] Logging: operator-visible re-dispatch and health transitions; **no key, no request body**
- [ ] Tests written for every pure function; streaming and discovery honestly documented as manual
- [ ] No hardcoded values — thresholds are named constants; nodes/keys/SPKI pins come from config
- [ ] Docs updated (`docs/multi-node-router.md`), leading with the premature status
- [ ] No scope additions — the NOT Building list held; **zero** Android changes
- [ ] Self-contained: one script, one test file, one doc; no build step, no new toolchain

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **The premise never holds** — one node stays the only case | High | Low (shelved work) | Revisit only on the trigger condition |
| An existing tool (LiteLLM proxy) makes this redundant | Medium | Medium (wasted build) | Evaluate **before writing line one**; record the decision |
| The router becomes a new single point of failure in front of nodes that were individually reachable | Certain by design | High | Clients must be able to bypass it — keep per-node URLs working **and documented** |
| Silent session failover loses conversation context | Medium (if built naively) | **High** — a correctness bug | Sticky pin; a down pinned node raises. Covered by test 3b |
| A spoofed mDNS advertisement adds a hostile node | Low | **Critical** — key exfiltration | SPKI pinning is what makes discovery safe. **Discovery without pinning must not ship** |
| **The 503 taxonomy is got wrong and the router re-dispatches a provisioning kick** | **Was certain** — the first draft specified exactly this | **High** — routes away from the only node loading the requested model, *and* blacklists it for all traffic for 10-30 s | Task 4 now enumerates all seven 503 sites by line and splits them; tests 2d/2e pin the per-model behavior |
| Sibling plans move the signals the router routes on | **High** | High | feature-22 changes `ready` semantics in practice (task 2 eligibility), feature-18 changes the cert lifecycle (SPKI pin) and adds a second unauthenticated route. **Re-verify `ready`/`state`, the cert lifecycle, and the 503 inventory at build time** rather than trusting today's line numbers |
| The router funnels every client into one per-node rate-limit bucket | Certain by design | Medium | 30/60 s per node, per socket IP, no `X-Forwarded-For` read anywhere. Documented as a design constraint; the rate-limit 429 is passed through rather than absorbed |
| Streaming quietly buffers and every SSE client breaks | Medium | High | Byte-for-byte relay with per-write flush; the `-N` curl is a required manual gate |
| Stale TXT `model` after a #180 swap routes to a node that 404s | Medium | Medium | TXT is a hint; `/v1/models` is the authority |
| mDNS fails across VLANs/guest networks | High | Low | Static list is the baseline, discovery the enhancement — never the reverse |
| The single-file script grows a UI, persistence, and metrics | Medium | Medium | Those are the explicit signal to stop and adopt a real reverse proxy instead |

## Notes

### Codex findings disposition (PR #310, 2026-09-07)

- **P1 — fixed.** `codex review --base main` on PR #310 found that Task 2's SPKI-pinning GOTCHA
  named the right thing to pin (leaf SPKI, not cert DER) but no way to extract it: Python's stdlib
  has no X.509 parser. Fixed by shelling out to the system `openssl` binary
  (`openssl x509 -pubkey -noout -inform DER`) rather than adding a pip dependency (which would
  reopen the "single-file, stdlib-only" decision below) or silently falling back to a DER pin
  (which breaks on every feature-18 re-mint — the exact failure this plan's own Testing Strategy
  row 6c exists to prevent). Still **DO NOT BUILD** — Task 1's LiteLLM evaluation gate is unchanged
  and unaffected by this fix.

**Decisions made**

- **Single-file Python under `scripts/`.** It matches the throwaway-host-side-helper precedent and needs no build step. **Go** would give a static binary and better concurrency but adds a *third* toolchain to a repo already juggling Gradle and a Node/TS worker, for a tool one person runs — rejected. **A Cloudflare-Worker-style TS** is wrong by construction: the router must live **on the LAN** to reach the nodes and browse mDNS — rejected.
- **Static node list is the baseline; mDNS is the enhancement.** mDNS across VLANs and guest networks frequently does not work, and a static list must always be sufficient.
- **Shell out to `avahi-browse`/`dns-sd` rather than take the `zeroconf` dependency.** Keeps the stdlib-only convention and avoids the repo's first `requirements.txt`. Accept the dependency only if the shell-out proves painful in practice.
- **Bind loopback, not the LAN.** A LAN-reachable router would put the client key — and on compromise every node's key — in cleartext on the LAN, inverting the node's own posture, so it would have to terminate TLS with its own cert and trust story. That is a larger tool. One router per host; the "After" diagram shows processes on one machine, not machines on a LAN.
- **Pin the leaf SPKI, not the certificate DER.** feature-18 re-mints the leaf on every IP change while reusing the leaf key precisely so an SPKI pin survives; a DER pin would raise on every DHCP lease.
- **TOFU pinning, never `verify=False`.** A router that disables verification centralizes across every node the exact MITM risk `SECURITY.md` already flags as a known limitation.
- **Sticky sessions fail loudly.** This is a correctness constraint, not an optimization.

**Alternatives considered and rejected**

- *A LAN-reachable router with its own TLS* — rejected as a different, larger tool; see the bind decision above.
- *`zeroconf` PyPI package* — deferred behind the shell-out; would introduce the first `requirements.txt`.
- *Go single static binary* — rejected on toolchain cost.
- *TS/Worker* — rejected; the router must be on the LAN.
- *nginx upstream / static DNS round-robin* — rejected as the *primary* design because neither can read `/health`, honor `Retry-After` as a routing signal, or keep sessions pinned. Still a reasonable fallback for someone who wants none of that.
- *Persisting the session-pin map* — rejected; sessions are node-local and ephemeral, and persistence is the first step toward the "outgrown a script" signal.

**A repo fact this review turned up — worth more than the plan edit**

Review flagged the "stale TXT after a #180 swap" justification as overstated, on the strength of
`RelaisDiscovery.updateModel`'s own KDoc (`:103-108`): *"an in-app model switch currently requires a
process restart … and restart re-registers via `register` — so the TXT is always fresh after a
switch."* **That KDoc is itself out of date.** #180's `ensureModelSwapInBackground`
(`RelaisEngine.kt:409-431`) swaps the resident model **in-process, with no restart** — it is precisely
the *"future hot-swap path that changes the model without a restart"* the KDoc tells you to call
`updateModel` from. `grep -rn "updateModel("` over `Android/src/app/src` returns the definition and
nothing else. So the advertisement **is** stale after a swap, the justification stands as originally
written, and the KDoc should be corrected in whichever plan next touches `RelaisDiscovery.kt`. The
design conclusion — TXT is a hint, `/v1/models` is the authority — is unchanged either way.

**Findings accepted from review, and the two declined**

Every finding in `critic-23.md` (0 CRITICAL · 6 HIGH · 4 MEDIUM · 1 LOW) was re-verified against
source before being applied. All six HIGHs held and are fixed above (503 taxonomy split; request body
buffered; `state == "IDLE"` eligibility; leaf-SPKI pinning; the two 429s discriminated; the bind
address declared as loopback with the diagram corrected). M1 (`Accept: application/json`), M2 (module
name, `discover`, one invocation form) and M4 (size honesty) are fixed. Of M3's two halves, the
LiteLLM one is fixed — see question 1 — and the stale-TXT one is **corrected rather than accepted**,
per the note above. L1 (`/health` cited `:700-712`, actually `:707-712`) is fixed.

- **Declined: restructuring `NOT Building` into a formal architecture boundary.** Review is right that
  *"if it grows a UI, persistence, or metrics, adopt a real reverse proxy"* reads as a day-one
  boundary rather than a future one, and the Metadata size note now says so plainly. Rebuilding the
  section around it would be rewriting a plan that opens with **DO NOT BUILD**.
- **Declined: pre-answering open question 2 (does feature-18's CA retire pinning?).** Now that the pin
  is on the leaf SPKI it survives feature-18's re-minting either way, so the question stopped being
  blocking — but it is still the user's call, not the plan's.

**Open questions for the user**

1. **Does LiteLLM proxy close this feature outright?** It already does OpenAI-compatible multi-backend routing with retries, config-file driven. **The supporting claim has been corrected:** feature-21 established that HA talks to its own core **`litellm` integration** — a generic OpenAI-compatible *client* — which says nothing about the LiteLLM **proxy server**. Task 1 remains the right gate; it just is not corroborated by feature-21. LiteLLM lacks mDNS, thermal-aware ranking, and node-local sticky sessions — **are those three worth a program?** Answer before any code.
2. **If feature-18's per-node CA ships, does pinning become unnecessary?** Trusting that CA would be simpler and would survive cert rotation. Less urgent now that the pin is on the leaf SPKI, which survives re-minting regardless.
3. **Should the router expose its own `/metrics`?** Currently a scope cut, but it is the natural place to see cross-node load — and the point at which a single-file script arguably stops being the right shape.

---

## Report

- **File:** `.claude/PRPs/plans/feature-23-multi-node-router.plan.md`
- **Complexity:** Medium
- **Scope:** 3 files (1 CREATE script, ~700-1000 lines; 1 CREATE test; 1 CREATE doc) + **zero** Android changes, 8 tasks
- **Key Patterns:** module-docstring-as-runbook and stdlib `http.server` handler shape (`.claude/scripts/webhook-receiver.py:1-27`, `:29-43`); constants annotated with their Kotlin source (`THERMAL_HOT_THRESHOLD`, `core/NodeState.kt:20`); the node's **node-level** 503 contract (`RelaisHttpServer.kt:537-548`, `:598`, `:647`) as the re-dispatch trigger, held apart from the six per-model kicks; the mDNS TXT contract (`RelaisClientConfig.buildDiscoveryTxt:100-114`)
- **External Research:** LiteLLM proxy config-driven multi-backend routing (the reason Task 1 is an evaluation, not code); `avahi-browse -rpt` / `dns-sd` output formats; Python `ssl.getpeercert(binary_form=True)` and why the SHA-256 pin must be on the extracted **leaf SPKI**, not the DER; SSE `\n\n` framing and why any buffering layer breaks it
- **Top Risk:** The premise never holds — one node stays the only case, so this is shelved work; the near risk is building it at all when LiteLLM proxy may already close it (Task 1). The near-*technical* risk is that two sibling plans are actively changing the exact signals the router routes on: feature-22 the `ready`/`state` semantics, feature-18 the cert lifecycle and the unauthenticated-route inventory. Re-verify both at build time
- **Confidence Score:** 5/10 (revised down from 8 after review. The strategic judgment survives intact — the evaluate-first gate is correctly placed, there is zero Android surface, and the instincts hold — but the tool's single highest-value behavior was specified against a 503 contract that four of seven call sites do not match, and was mechanically blocked by the transport the very next task selected. Both are fixed; the score reflects that a plan needing six HIGH corrections was not an 8, and that its inputs are still moving)

# Backend — HTTP API & Node Lifecycle

<!-- Generated: 2026-08-25 | Files scanned: RelaisHttpServer(2202L)+Engine(1026L)+extracted handler/gate files + embed/rerank/rag/tts/batch/nodetools + report-worker | main @ 4679d924 -->

## Routes (RelaisHttpServer, **2202L** — pure parse→gate→dispatch over ~20 `handleX(ctx: RequestContext)` handlers)
**No route changes since 2026-07-30.** The 20 paths below match `docs/openapi.yaml` exactly
(cross-checked 2026-08-25), so the spec needed no regeneration this pass.
Auth: bearer token, checked before dispatch; all routes except `/health` gated.
```
GET  /health                       → handleHealth                          (no auth)
GET  /                             → handleDashboard (HTML status page)
GET  /experiments                  → handleExperiments
GET  /metrics                      → handleMetrics (Prometheus | JSON)
GET  /v1/models                    → handleModels
GET  /v1/clientconfig              → handleClientConfig
POST /generate                     → withInferenceAdmission inline
POST /v1/chat/completions          → withInferenceAdmission → handleOpenAi
                                       → handleToolCompletion / handleStructuredCompletion
POST /v1/messages                  → withInferenceAdmission → handleAnthropicMessages (#179)
POST /v1/embeddings                → handleEmbeddings (EmbeddingGemma, 768-dim)
POST /v1/rerank                    → handleRerank (bi-encoder via EmbeddingGemma)      [NEW]
POST /v1/images/generations        → handleImages (exclusive admission gate)
POST /v1/audio/speech              → inline (sherpa-onnx/Piper TTS)                    [NEW]
POST /v1/audio/transcriptions      → handleAudioToText(task=transcribe)
POST /v1/audio/translations        → handleAudioToText(task=translate)
GET/POST/DELETE /v1/rag/documents  → handleRagIngest/List/Delete
POST /v1/rag/query                 → handleRagQuery
POST /v1/batch                     → handleBatchSubmit
GET  /v1/batch/{job_id}            → handleBatchStatus
GET/DELETE /v1/sessions            → handleSessionInfo/Clear
```
Shed order: thermal 503 → queue 429 → auth 401 → run 200. `resolveEmbeddingModel` (embeddings+rerank): echo the client's `model` if present, else the true embedder id — never the resident LLM (issue #190).

## RequestContext (shared, constructed once per request)
`sock, reader, contentLength, path, endpoint, accept, sessionEnabled, sessionHeader, reply/send`. Every extracted handler takes `ctx: RequestContext`.

## Extracted helper files (was inline in the old 1570L god-method)
| File | Purpose |
|---|---|
| RelaisTls.kt | self-signed keystore + HTTPS :8443 socket |
| RelaisLanIp.kt | LAN IP resolution (dashboard/mDNS) |
| RelaisHttpIo.kt | request line/body reading, multipart parsing |
| RelaisAdmission.kt | pure admission-decision types + retry-after scaling |
| RelaisAdmissionGate.kt | shared-semaphore + exclusive-drain-all gate impl |
| withInferenceAdmission | inline: thermal→queue gate + latency + finally-release |

## Runtime-compat gate (#220 → #236/#237/#243)
`RelaisRuntimeCompat` (207L) is the single measured table, pinned to `PINNED_LITERTLM_VERSION` and guarded by a test that reads the real `libs.versions.toml` — a litertlm bump without re-measuring fails CI. `loadability()` is VERIFIED/INCOMPATIBLE/SUSPECT/UNKNOWN; only INCOMPATIBLE is withheld, because over-blocking would silently shrink the catalog on every unmeasured upstream addition. Gating (`requiresHfToken`) is an independent axis — a license-gated repo can still be perfectly loadable.

| Chokepoint | Call |
|---|---|
| catalog / `/v1/models` | `RelaisModelCatalog.isNodeRunnable` → `isOfferable` |
| provisioner | `refuseIfIncompatible` in **both** `ensureModel` and `resolveModel` (the second read closes a mid-provision selection change) |
| per-request swap | `resolveModelRequest(incompatibleReason = …)` → `ModelRequestOutcome.Incompatible` → 404 |
| legacy Gallery download | `DownloadRepository` → `incompatibleReasonForDownloadUrl` (URL-keyed; `Model` carries no id) |

Operator-facing copy is single-sourced: `refusalMessage` for the UI/provisioner lanes, `incompatibleModelMessage`/`notProvisionedModelMessage` (in `RelaisModelSwap.kt`) for the HTTP 404 bodies — deliberately different wording, since "Choose a different model" is advice an API client cannot act on. `repoIdFromDownloadUrl` parses with `java.net.URI` (host compare case-insensitive; path never folded, because HF repo ids are case-sensitive).

## RelaisEngine.kt (1026L)
Resident engine lifecycle, `generate()` backend dispatcher (GPU/NPU/TPU), `generateWithTools`, **native mid-decode cancel** (`conversation.cancelProcess()`, off-thread, issue #165) alongside cooperative thermal-cancel.

## Supporting packages
| Package | Purpose |
|---|---|
| embed/ | EmbeddingGemma embedder + provisioning + tokenizer |
| rerank/ | `RelaisRerankEndpoint.kt` — pure parse/order/score, completes the RAG triad |
| rag/ | chunking + Room-backed vector store (256-dim MRL) + cosine query |
| tts/ | sherpa-onnx + Piper voice synthesis, voice provisioning |
| batch/ | async job queue + HMAC-signed webhook delivery + SSRF guard |
| nodetools/ | tool-calling registry (calculator, unit convert, …) |

## Admission / backpressure
Shared semaphore for normal inference (chat/generate/audio/TTS); exclusive drain-all for image-gen. Unprovisioned features: 501 (not registered) / 503+Retry-After (provisioning in progress, kicks background fetch) / 200 (ready).

## Client egress — NOT a server route [#258 + durable send #273]
**The send is retried, not one-shot.** `chat/ContentReportSendStep.kt#attemptReportSend()` records
each attempt against the row (`sendState`/`sendAttempts`/`lastAttemptAt`, schema **v7**) and
schedules `worker/ReportSendWorker` per the pure policy in `chat/ContentReportRetry.kt` — which
classifies **429 apart from 5xx apart from other 4xx**, so a rate-limited attempt waits out the
Worker's own 60-minute window instead of burning an attempt. Throttling can therefore never be what
permanently fails a report. See `data.md` for the columns and `frontend.md` for the review-screen
SEND affordance. Coverage: `ContentReportRetryTest` + `ReportSendWorkerTest` (JVM).

`chat/ContentReportDelivery.kt` POSTs an opted-in report to the fixed compile-time endpoint
`https://report.ventouxlabs.com/report` (redirects disabled, `IOException`-narrow catch,
`finally`-cleanup; no SSRF pinning needed — no attacker-controlled host). Receiver:
**`report-worker/`** (Cloudflare Worker, KV): accepts only `POST /report`, allowlist schema
(`reasonId`∈6, `surface`∈{chat,gallery_chat}), byte-counted 32 KiB body cap enforced off the
stream, per-IP limiter (10/hr on a salted SHA-256, renewing TTL — never "one hour retention" on a
form), 180-day report TTL, fixed never-echo replies, and `isPlaintextRequest` — refuses any
request whose edge scheme markers don't all say https (`cf-ray` backstop when none present).
`schema-parity.test.ts` pins the client↔worker vocabulary; `report-worker.yml` CI boots real
workerd (unit tests can't see workerd-only failures, #268). Deploy runbook + required post-deploy
curls: `report-worker/README.md`.

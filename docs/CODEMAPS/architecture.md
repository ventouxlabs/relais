# Relais — Architecture

<!-- Generated: 2026-08-25 | Files scanned: 330 main .kt + report-worker + docs/index.html | main @ 4679d924 -->

## What it is
Headless on-device LLM node: runs a model on the phone, serves an **OpenAI-compatible API over the LAN** — now spanning chat, embeddings, RAG, rerank (completes the "RAG triad"), TTS, tool-calling, structured output, and batch. Fork of `google-ai-edge/gallery`. Relais-authored node code (AGPL) lives under `Android/src/app/src/main/java/cc/grepon/relais/`; `ui/` + `customtasks/` are still **live inherited-Gallery code**, wired in via Hilt `@IntoSet` multibinding (not visible to a plain import search). The 29-file dead pocket identified on 07-19 has since been **removed** across 5 gated PRs (`0c84a125` and siblings), which is why `ui/` is now 90 files and `customtasks/` 38. See `frontend.md`.

> ⚠️ **`RelaisHttpServer.kt` is 2202 lines — measured 2026-08-25, not estimated.** The 08-17 sync
> recorded it dropping to ~1900L after an extraction; the file on `main` is 2202L, so that figure was
> wrong in both codemaps and is corrected here. `CLAUDE.md` separately says "~1700 lines" — also
> stale. The repo's own target is well under 800: **prefer extracting a new file** (as `RelaisHttpIo.kt`
> was) over growing this one. Re-measure with `wc -l`; do not carry the number forward by copying.

**[NEW #258] One component lives outside the Android tree: `report-worker/`** — a Cloudflare Worker
(`report.ventouxlabs.com`, KV-backed, deployed) receiving **opt-in** AI-content reports, the app's
ONLY developer-bound egress and the "to developers" half of Play's GenAI policy. Client side:
in-app REPORT dialog → Room `content_reports` (always local) → per-report default-off send
(`ContentReportDelivery`). The Worker refuses edge-marked plaintext (`isPlaintextRequest` → 403)
and its own CI job (`report-worker.yml`) **boots real workerd** — value exports from the entry
module kill the Worker while unit tests + `--dry-run` stay green (#268, learned the hard way).

## Layer boundaries
- **Node core** (Relais): HTTP API, engine adapter, headless FGS host, in-process inference seam.
- **Feature subsystems** (Relais): embed, rerank, rag, tts, nodetools, batch, imagegen, automation, triage/notifications, share, nfc, tile, widget, templates.
- **App shell** (Relais): `RelaisAppShell` NavHost — Dashboard/Chat/Models bottom nav, single `MainActivity` launcher.
- **Inherited Gallery, still live**: `ui/` (90 files) + `customtasks/` (38 files) — `GalleryNavGraph`/`GalleryApp()` are gone, but `ModelManagerViewModel`/`BenchmarkScreen` are wired directly into `RelaisAppShell`, and `agentchat`/`mobileactions`/`tinygarden`/`llmchat`/`llmsingleturn` are Hilt `@IntoSet`-bound into it. No known dead pocket remains (see `frontend.md`).

## Data flow
```
LAN client (OpenAI SDK)
   │ HTTPS :8443 (bearer, self-signed TLS)        loopback HTTP 127.0.0.1:8080
   ▼
RelaisHttpServer (2202L, pure parse→gate→dispatch) ───► ~20 handleX(ctx: RequestContext) handlers
   │                                                    + core/ pure seams (Admission, ToolParsing,
   │                                                    StructuredOutput, SessionPolicy, Reasoning)
   ▼
RelaisEngine (1026L) ──► litertlm 0.12.0 AAR — GPU_LITERTLM / NPU_AICORE / TPU_LITERTLM (Tensor G5)
   │         └─► native mid-decode cancel (conversation.cancelProcess(), off-thread, issue #165)
   ▼
side-systems: embed/ (EmbeddingGemma) rerank/ rag/ tts/ (sherpa-onnx+Piper) batch/ imagegen/ nodetools/
RelaisNodeService (FGS, START_STICKY): provision→engine init→bind HTTP/HTTPS→mDNS→kick workers
RelaisWatchdog (exact alarm, exp backoff) recovers · ThermalGovernor sheds/truncates
```

## Two consumer surfaces
- **HTTP** — LAN clients via `RelaisHttpServer` (:8443 TLS, :8080 loopback).
- **In-process** — `core/RelaisInference` lets tiles/widgets/share/nfc/triage/automation run inference with **no HTTP**, always **cold-start-guarded**.

## Inference model
GPU litertlm is the general-purpose resident path; TPU (Tensor G5, dispatcher-gated, requires an AOT-compiled `.litertlm`) is the fast lane on Pixel 10 — litertlm **pinned to 0.12.0** (0.14.0 tested + reverted: regresses the G5 TPU lane, issue #150). `BackendSelector` routes per modality (audio→GPU).

## Resilience & safety spine
FGS + watchdog + thermal governor + **native mid-decode cancel**; per-IP rate limit; semaphore admission (shared) + exclusive drain-all (image-gen); secrets in `EncryptedSharedPreferences`; metrics label-hygiene; HTTP loopback-only, HTTPS LAN bearer-gated.

**Runtime-compat gate (#220)** — `RelaisRuntimeCompat` is a measured table pinned to the litertlm version: the upstream allowlist drifts independently of the AAR we pin, so it offers models that download cleanly (multi-GB) and then fail engine-create. Only MEASURED failures are withheld; suspected/license-gated entries stay on offer and are badged. Four chokepoints, one source of truth — catalog (`isOfferable`), provisioner (`ensureModel` + `resolveModel`), per-request swap (`resolveModelRequest`), and the legacy Gallery download lane (`DownloadRepository`, keyed by URL since `Model` carries no id). Adding a fifth way to fetch a model needs its own gate.

## See also
`backend.md` · `frontend.md` · `data.md` · `dependencies.md`

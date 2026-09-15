# Relais Node — Model / Capability Card

This is the human-readable companion to [`docs/openapi.yaml`](openapi.yaml). It describes what a
Relais node can *actually* do right now — as opposed to what's compiled in but unprovisioned — so
a client (human or agent) can decide how to use the node without out-of-band knowledge. For the
route-level machine-readable contract, see the OpenAPI spec; for architectural detail, see
[`docs/CODEMAPS/backend.md`](CODEMAPS/backend.md) and [`docs/CODEMAPS/architecture.md`](CODEMAPS/architecture.md).

Two ways a client can query this at runtime instead of reading a doc:
- `GET /v1/models` — the locally provisioned curated chat-model list (OpenAI-compatible shape).
- `GET /v1/clientconfig` — resident model id, capability flags (`multimodal`/`tools`/`reasoning`),
  base URL, and paste-ready client configs. Bearer-gated.

Neither endpoint currently reports per-optional-feature provisioning state (embeddings/rerank
ready? image-gen ready? TTS ready?) — a client must probe the relevant endpoint and read the
501/503/200 outcome. That gap is a natural follow-up to this issue (see "Open questions" below).

## What kind of node this is

Relais is a **headless, single-device, single-tenant** inference node: one phone, one resident
chat model, one LAN. It is not a multi-tenant server — there is no user/org isolation, and the
admission queue (capacity 8) assumes one API-key holder driving it directly or through a handful
of concurrent client connections, not a public-internet fanout.

## Resident chat model

- Runtime: `com.google.ai.edge.litertlm` AAR, **pinned to version 0.12.0** (0.14.0 was tested and
  reverted — it regresses the Tensor G5 TPU lane; see issue #150).
- Default fallback id when no catalog entry resolves:
  `litert-community/gemma-4-E4B-it-litert-lm`; it is advertised only when locally provisioned and
  compatible with the pinned runtime.
- The actual resident model is operator-selected from a curated allowlist or an arbitrary
  HuggingFace `.litertlm` repo; `GET /v1/models` advertises only locally provisioned **curated**
  models. It can be swapped without a node code change.
- **Modalities in**: text always; image and audio additionally when the selected model is
  multimodal (`RelaisEngine.isMultimodal`, surfaced via `GET /v1/clientconfig`
  `capabilities.multimodal`). A non-multimodal model rejects audio input on
  `/v1/audio/{transcriptions,translations}` with a 400, and image content parts are simply not
  understood by the model (not rejected at the HTTP layer).
- **Modalities out**: text (+ optional native tool-calls / structured JSON). No native image or
  audio *output* from the chat model itself — those are separate optional runtimes (see below).

### Backends / accelerators

Selected per modality by `BackendSelector` (audio always routes to GPU):

| Backend | Where | Notes |
|---|---|---|
| `GPU_LITERTLM` | All supported devices | General-purpose resident path. |
| `NPU_AICORE` | Devices with AICore | `completion_tokens` usage reads 0 on this backend (no per-token counter exposed). |
| `TPU_LITERTLM` | Google Tensor G5 (Pixel 10) only | Fast lane; requires an AOT-compiled `.litertlm` file, dispatcher-gated, default-sampler-only. ~2.8x throughput vs. GPU on-device (measured). |

The response `backend` field on `POST /generate` (and engine telemetry more generally) reports
which of these actually served a given request.

### Tool-calling and structured output

- **Client tools**: standard OpenAI `tools`/`tool_choice`/`tool_calls` via the native LiteRT-LM
  tool API — not a prompt-injection scheme.
- **Node tools** (`node_tools`/`x_relais_node_tools: true`): curated built-ins (calculator, unit
  conversion, `rag_search`, ...) executed node-side in a single extra hop; a client-defined tool
  with the same name always wins on a name collision.
- **Structured output** (`response_format: json_object | json_schema`): `json_schema` is realized
  by advertising the caller's schema as a single native tool; both modes run a validate → repair →
  bounded-retry loop (max 2 retries) before failing with `422`. Not compatible with `stream: true`.
- **Reasoning**: `reasoning_effort` opts into the model's thinking channel; reasoning deltas stream
  as `delta.reasoning_content` (OpenAI/DeepSeek convention), non-streaming responses carry
  `message.reasoning_content`.

## Optional subsystems (compiled in, provisioning-gated)

These are separate runtimes from the resident LiteRT-LM model. Each reports **honest HTTP status**
rather than silently degrading: `501` = not registered/provisionable on this build; `503` +
`Retry-After` = background download in progress, retry shortly; `200` = ready.

| Feature | Endpoint(s) | Runtime | Notes |
|---|---|---|---|
| Embeddings | `POST /v1/embeddings` | EmbeddingGemma | 768-dim output. Query/document asymmetric prefixing via `embedding_task`. |
| Rerank | `POST /v1/rerank` | Same EmbeddingGemma instance as embeddings | Bi-encoder (cosine similarity → `[0,1]` relevance), Cohere/Jina-shaped. Shares provisioning state with embeddings — if one is ready, so is the other. A true cross-encoder is a future quality upgrade; the interface is stable across that swap. |
| RAG | `POST/GET/DELETE /v1/rag/documents`, `POST /v1/rag/query`, `rag`/`x_relais_rag` on chat | EmbeddingGemma (256-dim MRL) + Room-backed vector store | In-memory/on-disk brute-force cosine scan; bounded corpus (per-document and per-request size caps). |
| Image generation | `POST /v1/images/generations` | sd.cpp (process-isolated, separate from LiteRT-LM) | v1: single fixed 512x512 size, max 2 images/request, `b64_json` only (no hosted URLs off a LAN node). Heaviest GPU op — takes the admission gate *exclusively* (drains all in-flight decode) rather than sharing GPU memory with LLM decode. ~60-90s per image. |
| Text-to-speech | `POST /v1/audio/speech` | sherpa-onnx + a Piper voice | Produces `wav` (default) or raw little-endian `pcm`; mp3/opus/aac/flac all downgrade to `wav` (no on-device encoder for those containers). |

Speech-to-text (`/v1/audio/{transcriptions,translations}`) is **not** a separate optional
subsystem — it reuses the resident multimodal chat model's audio input path, so its availability
tracks `capabilities.multimodal`, not a provisioning flag.

## Limits / admission behavior

Sourced from `RelaisAdmission.kt`, `RelaisHttpServer.kt` constants, and the domain-limit objects
(`RERANK_LIMITS`, `TTS_LIMITS`, `IMAGE_GEN_LIMITS`) as of this writing — treat these as
representative, not contractual; they can change without a spec version bump today (see "Open
questions").

- **Admission queue capacity**: 8 in-flight (queued + running) requests across the shared gate
  (chat/generate/audio-to-text/embeddings/rerank/RAG/TTS). Beyond capacity: `429` +
  linearly-scaled `Retry-After` (2-30s).
- **Thermal shedding**: takes precedence over queue admission. When the device is hot, every
  gated endpoint returns `503` + jittered `Retry-After` regardless of queue depth.
- **Per-IP rate limit**: 30 requests / 60s window, fixed-window, evicted opportunistically.
- **Request body cap**: 32 MB (shared ceiling for base64 image/audio payloads); a multipart image
  upload is additionally capped at 12 MB decoded (bounds the transient base64 re-encode memory
  spike).
- **Embeddings**: max 64 inputs/request, 32,768 chars/input.
- **Rerank**: max 128 documents/request, 8,192 chars query, 32,768 chars/document.
- **RAG**: 1 MB max per ingested document, 8,192 chars max query, bounded corpus (ingest returns
  `413` at capacity).
- **TTS**: 4,096 chars max input (matches OpenAI's limit), speed clamped to `[0.25, 4.0]`.
- **Image generation**: max 2 images/request, steps clamped `[1, 50]` (model-specific default,
  e.g. SD-Turbo=4, SD-1.5=20), single supported size (512x512).
- **Batch**: max 256 queued jobs, 256 KB max stored request body, 2 KB max webhook URL
  (SSRF-guarded, optional operator allowlist).
- **Context window**: not currently reported by this node at any HTTP surface — it is a property
  of the operator-selected `.litertlm` model file, not a fixed node constant. A client that needs
  this must currently know it out-of-band for the selected model. Flagged as an open question below.

## Security posture relevant to capability discovery

- Every route except `GET /health` requires `Authorization: Bearer <api_key>` (constant-time
  compared).
- HTTPS on the LAN interface (self-signed cert, port 8443); plain HTTP is loopback-only (port
  8080) — the bearer key is never sent in cleartext over the network.
- `GET /v1/clientconfig` is the only route that returns the raw API key in its response body; safe
  because reaching it already required presenting that same key.
- mDNS (`_relais._tcp`) advertises non-secret routing metadata (model id, version, port, API
  shape, capabilities) for discovery — never the key.

## Open questions (not guessed on)

1. **"Publish" — file vs. served endpoint.** Issue #169's Scope line says "Served at a stable path
   (e.g. `/openapi.json`) and/or checked into `docs/`," and its Acceptance criteria says "`openapi.json`
   served + validates." That reads as wanting a live `/openapi.json` route. This change is
   docs-only per the task brief given to me (no `.kt` edits), so I committed the spec to
   `docs/openapi.yaml` only. Serving it from the node (plus the drift-guard test the issue also
   asks for — "a test fails if a route exists without a spec entry") is real `.kt`/test work and,
   if wanted, should land as a follow-up PR that also wires this file (or a generated equivalent)
   into `RelaisHttpServer`.
2. **Drift guard.** The issue's acceptance criteria wants an automated test that fails when a
   route exists without a spec entry. That requires either generating the spec from the route
   table or writing a Kotlin/JVM test that walks both structures — again real code, out of scope
   for this docs-only pass.
3. **No machine-readable provisioning-state endpoint.** `GET /v1/clientconfig` reports
   `capabilities.multimodal/tools/reasoning` but not whether embeddings/rerank/RAG/image-gen/TTS
   are actually provisioned right now. This model card describes that state qualitatively; a
   client wanting to probe it programmatically today still has to hit the endpoint and read the
   `200`/`501`/`503`.
4. **Context window** is model-dependent and not exposed anywhere in the current HTTP surface;
   documented as a gap above rather than inventing a number.

# Data Layer — Room, DataStore, DI

<!-- Generated: 2026-08-25 | Files scanned: data/(27) + di/ + rag/RagStore + tts/imagegen provisioners + RelaisModelRegistry | main @ 4679d924 -->

## Room — `relais.db` v7 (was v6; still additive-only, NO destructive fallback)
Accessed via static `RelaisDatabase.get(context)` (not Hilt-provided).

| Entity | Table | Key columns | Feature |
|---|---|---|---|
| SchemaMeta | schema_meta | id (PK=1) | meta |
| SessionTurn | session_turns | id, sessionKey, role, content, createdAt | server session memory |
| RagDocument | rag_documents | id, title, createdAt | RAG |
| RagChunk | rag_chunks | id, documentId, chunkIndex, text, embedding(BLOB,256-dim MRL), createdAt | RAG |
| BatchJob | batch_jobs | id, jobId(UNIQUE), status, requestJson, resultJson, webhookUrl | batch |
| Conversation | conversations | id, title, modelId, created/updatedAt | chat depth |
| ChatTurn | chat_turns | id, conversationId(FK CASCADE), role, content, attachmentPath?, answeredByBackend? | chat depth |
| **ContentReport** | content_reports | id(auto PK), reasonId(enum **id string**, not ordinal), excerpt, note?, modelId?, backend?, surface(`chat`/`gallery_chat`), createdAt(indexed), **sendState / sendAttempts / lastAttemptAt (v7)** | **AI-content reports, #258 + durable send #273** |

DAOs: SessionDao, RagDao, BatchDao, SchemaMetaDao, ChatDao (upsert/rename/touch/delete conversation, observe turns Flow, delete-turns-after for edit/retry), **ReportDao**. Migrations: 1→2→3→4→5 (unchanged) + 5→6 `content_reports` + **6→7 send-state columns** (`MIGRATION_6_7`, all `MIGRATION_*` registered in one array in `RelaisDatabase.kt`).

**Report egress (the one Room table with an off-device leg):** local insert is unconditional on
SUBMIT; delivery is per-report opt-in, default off — `ChatViewModel`/`ChatPanel` →
`deliverReport()` (`chat/ContentReportOutcome.kt`, shared gate) →
`chat/ContentReportSendStep.kt#attemptReportSend()` → `ContentReportDelivery` (POST
`https://report.ventouxlabs.com/report`, redirects disabled, notice generation-guarded per report).
Rows reviewable in-app: `ContentReportsActivity` (CONFIGURE › REPORTED OUTPUT).

**The send is durable, not one-shot (#273, schema v7).** `persistContentReport` returns the row id
and stamps `sendState` at write time, so an opt-in survives process death:

| Column | Meaning |
|---|---|
| `sendState` | `none` / `pending` / `sent` / `failed` (`ReportSendState`), default `none` |
| `sendAttempts` | attempts made; a **429 costs no attempt** (see below) |
| `lastAttemptAt` | epoch ms of the last attempt, nullable |

Retry is scheduled to `worker/ReportSendWorker` per the pure policy in `chat/ContentReportRetry.kt`,
which classifies **429 apart from 5xx apart from other 4xx** — a rate-limited attempt waits out the
Worker's 60-minute window rather than burning an attempt, so throttling can never be what
permanently fails a report (`ContentReportRetryTest`, `ReportSendWorkerTest`). A row with
`sendState = none` is never read by the worker and never transmitted; `CONFIGURE › REPORTED OUTPUT`
offers a manual **SEND** only on `pending`/`failed`, so the review screen cannot originate a
transmission the operator did not ask for.

## Download resume disposition (#287) — pure policy, `data/DownloadResumePolicy.kt` [NEW]
WorkManager's `ENQUEUED` is **two events wearing one name**: a download *starting* and a download
*resuming* after the system stopped its worker (lost network constraint; on Android 16+ a spent
JobScheduler runtime quota). `DefaultDownloadRepository` treated them identically, which on every
interruption overwrote the persisted start timestamp — so the duration reported on success measured
only the final leg — and fired a second "start" event, inflating starts against completions.
`EnqueueDisposition` separates them. Neither symptom is visible locally: they surface only as
implausibly fast downloads and a drifting start/finish ratio, which is why this is pure and
JVM-tested (`DownloadResumePolicyTest`) rather than checked by hand. `DownloadWorker` and
`data/Model.kt` carry the wiring.

## Proto DataStore (unchanged since 06-26)
Settings/UserData/Cutouts/BenchmarkResults/Skills — same 5 serializers, same facade (`DataStoreRepository`).

## Config storage (NOT DataStore)
`RelaisConfig` — `EncryptedSharedPreferences` for API key/TLS password/HF token; plaintext prefs for modelId, opt-ins, shed thresholds.

## Model registry (#180) — plaintext pref, not Room
`provisioned_models` holds `ProvisionedModel(modelId, path, displayName)` as a JSON array via `RelaisConfig.provisionedModels`/`setProvisionedModels`. It is the **safety boundary** for per-request model swaps: it only grows on a locally-successful provision, so a LAN client can complete a swap the operator already initiated but can never originate a download. **Pruned on READ**, not just on write — `pruneMissingProvisioned` drops entries whose file has vanished (storage cleared, model deleted, side-load removed), so eligibility reflects the filesystem rather than the last write.

## Model/voice provisioning (byte-size/filename-keyed on disk, NOT DB-tracked)
| Asset | Path | Completeness check |
|---|---|---|
| TTS voices | `externalFiles/tts/<voice>/` | onnx + tokens.txt + espeak-ng-data/ all present |
| Embedding model | `externalFiles/relais/embed/` | variant file + tokenizer, byte-exact size |
| Image-gen model | `externalFiles/relais/imagegen/` | byte-size check |
| Chat attachments | `filesDir/chat/<turnId>.<ext>` | tracked via `ChatTurn.attachmentPath`, Room |

## DI — Hilt `AppModule` (unchanged since 06-26)
5 proto Serializers → `DataStore<*>` → `DataStoreRepository`; `AppLifecycleProvider`; `DownloadRepository`. Still **no Room provider** (static `RelaisDatabase.get()`), no engine/HTTP/embedder/TTS in Hilt.

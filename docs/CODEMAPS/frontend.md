# Frontend — UI (unified Relais shell over still-live inherited Gallery code)

<!-- Generated: 2026-08-25 | Files scanned: RelaisAppShell + chat/(22) + Dashboard/Models screens + full ui/(90) + customtasks/(38) import+DI graph | main @ 4679d924 -->

> Re-verified 2026-08-25: `ui/` 90 files, `customtasks/` 38, `chat/` 22 — unchanged since the last
> sync. No UI changes landed in this window; the only user-visible behavior change is that an
> interrupted model download now resumes instead of appearing stalled (#287, pure policy in
> `data/DownloadResumePolicy.kt` — see `data.md`).

> ⚠️ **Reachability here is not visible to an import graph.** `ModelManagerViewModel` constructor-injects
> `Set<@JvmSuppressWildcards CustomTask>`, and `@Provides @IntoSet` modules in `customtasks/agentchat/`,
> `customtasks/mobileactions/`, `customtasks/tinygarden/`, `ui/llmchat/`, `ui/llmsingleturn/` feed it.
> **No Kotlin import connects them to `RelaisAppShell` — a plain grep/BFS misses this entirely** — yet
> Hilt instantiates them at runtime and deleting them breaks the build via annotation processing. A
> 2026-07-19 pass called 157 files dead on exactly that mistake and was corrected the same day; the real
> figure was 29, all since removed (`0c84a125` + 4 siblings). Audit with the DI graph, not just imports.

## Navigation — `RelaisAppShell.kt` (NavHost, replaces GalleryNavGraph as the live entry)
```
MainActivity → RelaisAppShell (Scaffold + NavHost) → bottom nav:
  dashboard  → DashboardScreen       (node status/start-stop/endpoints/key)
  chat       → RelaisChatActivity's ChatScreen (Room-persistent conversations)
  models     → ModelsScreen          (model catalog + switch)
  benchmark/{model} → defined, not yet linked from bottom nav
CONFIGURE stays an Intent-launched Activity (RelaisConfigureActivity), not a nav route.
Deep links: resolveShellDeepLink — global_model_manager→MODELS, else→CHAT.
```
`RelaisShellViewModel` hoists a shared 1s-polling `StateFlow` (`WhileSubscribed(5000)`) feeding Dashboard.

## Dashboard (`DashboardScreen.kt`)
Status dot/word + phase line, LAN(:8443)/LOCAL(:8080) copyable endpoints, masked `AccessKeyChip` (show/hide, copy, share), read-only model summary → Models, CONFIGURE link, one state-appropriate primary button.

## Chat (`chat/`, `RelaisChatActivity.kt`, `ChatViewModel.kt`, `ChatRepository.kt`)
Room-backed via `ChatDao` (conversations/turns, truncate/regenerate/edit-and-resend). **Hybrid transport**: `ChatTransportSelector` health-probes the loopback HTTP server per send, prefers `HttpChatTransport` (SSE), falls back to `InProcessChatTransport` (direct `RelaisEngine.generate`). Markdown rendering, image/PDF/WAV/text attachments, export-to-.md, share, in-chat model switch via `ModelSwitch.kt`.

**Speech playback (#211)** — assistant turns carry a `SPEAK` action that synthesizes the turn on-device and plays it. The label doubles as its own status readout (`SYNTHESIZING` / `STOP` / `FETCHING VOICE` / `SPEECH FAILED`), so playback adds no spinner and no second control; a screen-level `SpeakingStopStrip` keeps STOP reachable once the speaking row scrolls out of the `LazyColumn`.
- `chat/ChatSpeech.kt` — `SpeechState` sealed interface + pure state→label/enablement/stop-vs-start helpers (JVM-tested; the UI only renders and dispatches).
- `chat/ChatSpeechUi.kt` — `SpeakingStopStrip` and `RefreshOnResume`, extracted out of `RelaisChatActivity` so they're drivable by `ChatSpeechUiProbe` rather than by tap coordinates.
- `tts/SpeechText.kt` — markdown→speakable prose (drops fenced code, keeps link text, normalises table punctuation). Pure.
- `tts/TtsPlayer.kt` — `AudioTrack` playback in `MODE_STREAM`, one live track at a time, transient audio-focus handling, `COMPLETED`/`CANCELLED`/`FAILED` outcomes.
- `ChatViewModel` owns synthesis + playback behind a monotonic **generation token** (turn-id comparison is insufficient — stop-then-re-tap of the same turn yields identical ids). Availability is re-checked on `ON_RESUME` because **TTS registers at node startup, not app startup** (`TtsRegistration` ← `RelaisNodeService`), and `refreshSpeechOffered()` deliberately avoids `availability()` since that loads the ~64 MB voice model.
- Coverage: JVM (`SpeechTextTest`, `ChatSpeechTest`, `ChatViewModelSpeechTest`) + on-device `SpeechPlaybackProbe` (player/focus/real voice) and `ChatSpeechUiProbe` (Compose UI; the repo's first).

**AI-content reporting (#258, v1.0.18 capture + v1.0.19 send) [NEW]** — a REPORT affordance on
assistant turns on **both** chat surfaces (Relais `chat/` and inherited Gallery
`ui/common/chat/ChatPanel`):
- `chat/ContentReportDialog.kt` — reason picker (6 reasons), optional note, and an
  **ALSO SEND TO DEVELOPER** toggle, default off, decided per report; caption enumerates all six
  fields a send transmits (#277 — `surface` was the missing one; note `ContentReportSink`'s KDoc
  anticipates a third surface someday, at which point "which chat surface" needs re-checking).
- Local save is unconditional (`ContentReportSink` → Room `content_reports`); the "saved" notice
  shows **immediately**, then updates when an opted-in send resolves — outcomes are
  **generation-guarded per report** (`reportGeneration`/`reportOwns`, mirroring the speech
  pattern) so a stale in-flight send can't overwrite a newer report's notice.
- Both surfaces route through one gate: `chat/ContentReportOutcome.kt#deliverReport()` (the
  opt-in check lives there, pinned by `ChatViewModelReportTest` + `ContentReportOutcomeTest`) →
  `chat/ContentReportSendStep.kt#attemptReportSend()` → `ContentReportDelivery` → the Worker (see
  `backend.md` §Client egress).
- **Retry (#273)** — the send is durable, not one-shot. `persistContentReport` returns the row id and
  stamps `sendState` (`none`/`pending`/`sent`/`failed`, schema **v7**) at write time, so an opt-in
  survives process death. `attemptReportSend` records each attempt and schedules
  `worker/ReportSendWorker` per the pure policy in `chat/ContentReportRetry.kt`, which classifies
  **429 apart from 5xx apart from other 4xx** — a rate-limited attempt costs no attempt and waits out
  the Worker's 60-minute window, so throttling can never be what permanently fails a report
  (`ContentReportRetryTest`). `CONFIGURE › REPORTED OUTPUT` shows the send status and offers **SEND**
  — but only on `pending`/`failed` rows, never on `none`, so the review screen cannot originate a
  transmission the operator did not ask for.
- `chat/ContentReportsActivity.kt` (manifest-registered, launched from CONFIGURE) —
  `CONFIGURE › REPORTED OUTPUT`, the on-device review list Play's "use reports to inform
  moderation" answer points at.

## Models (`ModelsScreen.kt`)
Current-model header + bottom-sheet model selector, reload-polling feedback.

## Theme (`RelaisPalette.kt`) — DESIGN.md tokens confirmed still applied
Amber `#FFB000` / Charcoal `#0B0B0D`, `FontFamily.Monospace`, dark-only — consistently used across shell/dashboard/chat/models. `ui/theme/*` is itself LIVE (imported directly by `MainActivity`/`RelaisApplication`/`ModelManagerViewModel`'s fan-out) — the inherited Gallery light/Nunito variant ships in the same theme files, unused in practice but not dead code.

## Still LIVE despite looking like old-Gallery leftovers (do not delete)
- `ui/modelmanager/ModelManagerViewModel.kt` + `ui/benchmark/*` — directly constructed/composed by `MainActivity.kt`/`RelaisAppShell.kt` (the `benchmark/{model}` route, unlinked from bottom nav but live in the NavHost).
- `customtasks/agentchat/` (24 files: skill manager + URL-based skill install, MCP client with OAuth/header auth, WebView agent sandbox), `customtasks/mobileactions/`, `customtasks/tinygarden/`, `ui/llmchat/`, `ui/llmsingleturn/` — all Hilt `@IntoSet`-bound into `ModelManagerViewModel`'s `Set<CustomTask>`; **no equivalent of agentchat's skill/MCP capability exists anywhere in the new Relais-native stack** (`nodetools/` is a fixed 4-tool list, no user-facing skill or MCP management at all).
- `ui/home/LicensesActivity.kt` — manifest-declared (`AndroidManifest.xml`), self-contained, covered by `LicensesActivityProbe.kt`. Its only launch path (`ui/home/SettingsDialog.kt`) was removed by the dead-code cleanup, so it is manifest-live but unreachable in practice — a pre-existing product gap, deliberately left alone.
- `ui/common/MarkdownText.kt`, `ui/common/BufferedFadingMarkdownText.kt`, `ui/common/Accordions.kt` — siblings of the dead `ui/common/chat/` tree, but consumed directly by the new `chat/ChatMessageList.kt` and by `ui/benchmark/*`.

## Dead code — cleared
The 29 verified-dead files (the `GalleryApp()` → `ui/navigation/GalleryNavGraph.kt` chain and its
orphans) were removed across 5 gated PRs, which is why `ui/` is now 90 files and `customtasks/` 38.
No known dead pocket remains.

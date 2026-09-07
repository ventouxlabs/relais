# Gate 0.5 — Pre-Flight Spike Findings

> Settled facts from on-device investigation on **JD's Pixel 9 Pro Fold (Tensor G4 / zumapro, arm64-v8a)**.
> Feeds directly into `/goal relais-node-GOAL.md`. **Do not re-derive or contradict these.**
> Date captured: 2026-05-25. Gallery app version under test: 1.0.15. litertlm: `0.11.0`.

---

## TL;DR
- **Runtime foundation = LiteRT-LM (`com.google.ai.edge.litertlm.Engine`) on GPU.** Portable across Pixel 9 (now) and Pixel 10 (later). Full multimodal: **image + audio + text**, 32K context.
- **E4B on NPU is NOT available via LiteRT-LM on any device** — and not at all on the Pixel 9.
- **NPU is an opportunistic, Pixel-10-only, image+text-only backend** via AICore/Gemini Nano — never a gate, never for audio.
- Gates assert **"engine resident + correct multimodal response (+ tok/s floor)"**, never `backend == NPU`.
- **Proven on-device:** one resident GPU engine serves text + image + audio, headless. GPU baseline captured.

---

## Tensor G5 / Pixel 10 — `gemma-4-E4B` does NOT run; the runtime IS usable (2026-06-12)

> Corrects the TL;DR "portable across Pixel 9 and Pixel 10" assumption. Verified on the **Pixel 10
> Pro Fold (`rango`, Tensor G5)** against the **Pixel 9 Pro Fold (`comet`, Tensor G4)**.

**Finding:** the multimodal **`gemma-4-E4B-it.litertlm`** model **initializes** (`ready:true`) on Tensor
G5 but **SIGSEGVs natively** (null-pointer deref in `liblitertlm_jni.so`) on the **first inference**.
A **different, text-only** model (**`Qwen3-0.6B`**) **serves fine on the same G5 device**. So it is a
**model × SoC bug inside LiteRT-LM**, not a Relais bug, not the model file, and not a runtime version.

Discriminator matrix (all on `rango` / G5 unless noted):

| Model | Engine config | litertlm | Result |
|---|---|---|---|
| gemma-4-E4B | full (vision=GPU, audio=CPU) | 0.11.0 & 0.13.1 | **SIGSEGV** on 1st inference (CPU+GPU) |
| gemma-4-E4B | **text-only** (no vision/audio) | 0.13.1 | **SIGSEGV** (so not the multimodal backends) |
| gemma-4-E4B | full | 0.11.0 | serves on **G4** (`comet`) — byte-identical model |
| **Qwen3-0.6B** | text-only | 0.13.1 (direct) & 0.11.0 (node) | **serves on G5** — coherent output, ~3.5 tok/s |

- **Version-independent:** reproduced on `0.11.0` AND `0.13.1` (latest), different native BuildIds
  (`c2c2717…` vs `cae57ec…`), identical null-deref. A version bump is **not** the fix (H5 refuted).
- **Backend- and config-independent:** crashes on `Backend.GPU()` and `Backend.CPU()`, and with a
  text-only engine config (no vision/audio backend). So it is the **model's compute graph on G5**.
- Model is exonerated by checksum (sha256 `0b2a8980…bd52e0`, byte-identical to the G4-serving file).
- A native SIGSEGV **cannot** be caught (no throwable reaches `onError`); remediation must avoid the
  crashing path, not try/catch it.

**Also surfaced (a real Relais bug, now fixed):** `RelaisEngine` hardcoded `visionBackend`/`audioBackend`,
so `Engine.initialize()` (litertlm 0.11) or `createConversation()` (0.13) **rejected every text-only
model** with `NOT_FOUND: TF_LITE_*_ENCODER_*` — the node could not serve Qwen3-class models at all.

**Remediation (shipped — `RelaisEngine.kt`):**
1. **Adaptive engine config** — try the full multimodal config; on a missing image/audio encoder fall
   back to a text-only engine (model-agnostic; a non-encoder error is rethrown, so a multimodal model
   is never silently downgraded). Lets the node serve text-only models on **both** SoCs.
2. **Pixel-10 / G5 pre-flight gate** — refuse the known-incompatible `gemma-4-E4B` on Pixel 10 with a
   clear error (pick a G5-compatible model) instead of crash-looping.

**Verified on hardware:** G5 (`rango`) — Qwen3 serves via the real node path; gemma gated cleanly, no
SIGSEGV. G4 (`comet`) — gemma still builds the **full multimodal** engine and serves (no regression).

**Open:** upstream LiteRT-LM bug to file (gemma-4-E4B null-deref at first inference on Tensor G5; serves
on G4; Qwen3 serves on G5). Single G5 unit available, so all-Pixel-10 vs single-unit-fault unconfirmed.

> **UPDATE 2026-09-06 — the pre-flight gate above no longer exists; do not re-add it.** E4B was
> re-verified to init + serve (text, sustained decode, and vision) on Tensor G5 with **no SIGSEGV** on
> **litertlm 0.12.0** (2026-07-12, on `rango`) — the model×SoC bug above is fixed on that version.
> `RelaisEngine.kt:341-343` records the gate's removal. **litertlm 0.12.0 is what ships**
> (`libs.versions.toml`), so the crash this section describes is not reproducible on the current build.
> Reconciling with the discriminator matrix above: this bug is version-dependent after all — **fixed on
> 0.12.0, still reproduces on 0.11.0 and 0.13.1** (the two versions actually tested above). A litertlm
> version bump past 0.12.0 can silently reintroduce a first-inference-only crash on G5; re-run this
> section's discriminator matrix on any future bump before assuming E4B still serves there. This is
> also load-bearing for feature-22 (idle-unload): a reload after a bump-induced regression would hit
> the crash on every idle-triggered re-init, not just once at boot.

---

## Q1 — Can the active backend be read back programmatically? **NO.**
Decompiled `litertlm-android:0.11.0`. The public API exposes **no resolved/active-backend getter**:
- `Engine.getEngineConfig().backend` only **echoes the requested `Backend`** you constructed — not what the runtime resolved. If the native layer silently falls back, this still reports the request. Reading it proves nothing.
- `Engine` has only `isInitialized()`, `initialize()`, `createConversation()`, `createSession()`, `close()`. No `getActiveBackend()` / resolved-config / runtime capability query.
- `Capabilities` exposes only `hasSpeculativeDecodingSupport()` (static model introspection).

**Consequence for gate design:** prove backend behavior by **measured throughput** (`BenchmarkInfo` via `Conversation.benchmarkInfo` or top-level `benchmark(modelPath, backend, prefillTokens=256, decodeTokens=256, cacheDir=null)`), and otherwise assert **"resident + correct response."** On the AICore path the question is moot: `checkStatus() == AVAILABLE` + a successful inference *is* the NPU proof by construction (AICore only runs Nano on the Tensor NPU).

## Premise correction — "E4B runs on NPU" does NOT hold on this hardware
From the live `model_allowlist.json` (authoritative; fetched at runtime) and every bundled snapshot `1_0_4`→`1_0_15`:

| E4B path | Image | **Audio** | Context | Devices | Runtime |
|---|---|---|---|---|---|
| **AICore / Gemini Nano (NPU)** | ✅ | **❌** | Nano-managed (small) | **Pixel 10/11, Galaxy S26, SD-8850/MTK-6993 only** | ML Kit GenAI (`GenerativeModel`) |
| **LiteRT-LM (GPU)** | ✅ | ✅ | 32K | **any — Pixel 9 *and* 10** | `litertlm.Engine` |

- `Gemma-4-E4B-it` (the `.litertlm` file) is `accelerators: "gpu,cpu"` in **every** allowlist version — **NPU never offered.** The only LiteRT-LM NPU model anywhere is `Gemma3-1B-IT NPU` (a 1B model).
- The AICore E4B entry exists **only in the live allowlist** (repo snapshots are stale) and is gated by `Utils.isAICoreSupported()` — an **exact `Build.MODEL` string match** against the allowed groups. `Pixel 9 Pro Fold` is in **none**.
- Pixel quirk (`ModelAllowlist.kt:115-118`): on any Pixel the UI **relabels `npu`→`tpu`**, and `LlmChatModelHelper.kt:91-106` routes **both `NPU` and `TPU` labels → `Backend.NPU`**. This is the likely source of the original "E4B on NPU" misread — E4B actually defaults to **GPU** here.

## Q2 — Headless / screen-off / Doze survival — **NOT YET TESTED** (for the `/goal` run).
## Q3 — Inbound bind `0.0.0.0:<port>` + LAN curl — **NOT YET TESTED** (for the `/goal` run).

---

## PROVEN on-device (do NOT re-test)
Single resident `litertlm.Engine`, `Backend.GPU()` (visionBackend=GPU, audioBackend=CPU), one `initialize()`, headless (instrumented process, no UI):

| Stage | Result |
|---|---|
| Engine init (resident) | ~23 s, `isInitialized()=true` |
| **TEXT** ("reply one word: ping") | `"ping"` ✅ |
| **IMAGE** (red 64×64 PNG) | `"Red"` ✅ |
| **AUDIO** (synthetic WAV) | processed end-to-end ✅ (modality works; can't interpret a sine tone) |
| Engine close | clean ✅ |

### GPU baseline (E4B, Tensor G4, `gpuBenchmark`)
| metric | value |
|---|---|
| **decode** | **5.80 tok/s** |
| prefill | 111.1 tok/s |
| time-to-first-token | 2.48 s |
| (cold init) | 44.6 s |

**Gate floor:** `decode > 4.0 tok/s` (below that implies a regression off GPU; CPU is far slower).

**Throughput levers tried:** **Speculative decoding** — E4B reports support
(`Capabilities.hasSpeculativeDecodingSupport()=true`) but **measured a regression**: ~2.56 tok/s
on vs ~5.6 tok/s off (draft overhead > gains, no draft model bundled). **Left OFF.** Real speedup
on this device needs either the **NPU (Pixel 10 / AICore)** or the **MTP-enabled model variant**
(`gemma4_4b_..._thinking.litertlm`, a separate download per the allowlist `updatableModelFiles`).
~5.5 tok/s decode is the practical ceiling for E4B on the Tensor-G4 GPU via this runtime.

---

## Architecture (settled direction)
- **Foreground Service** holding ONE resident GPU `Engine`, alive across requests; streams tokens.
- **Modality-aware backend Selector** above the existing seam (`runtime/ModelHelperExt.kt` → `Model.runtimeHelper`, `LlmChatModelHelper` vs `AICoreModelHelper`, both `: LlmModelHelper`):
  - request has **audio** → **GPU/LiteRT-LM** (only option)
  - **image/text** + AICore available on device (Pixel 10+) → **AICore/NPU**
  - otherwise → **GPU/LiteRT-LM**
- **Endpoints** reuse existing **Ktor** dep + **MCP manager** (`McpManagerViewModel`): HTTP on `0.0.0.0:<port>` for LAN, and/or MCP tools for on-device apps. Normalize one multimodal request schema → per-runtime adapter.

`EngineConfig` multimodal recipe (from `LlmChatModelHelper.kt`): `backend=Backend.GPU()`, `visionBackend=Backend.GPU()`, `audioBackend=Backend.CPU()`, `maxNumTokens`, `cacheDir = getExternalFilesDir(...)`.

---

## Build / test / device facts (for the `/goal` run)
- **Model:** `Gemma-4-E4B-it` → `gemma-4-E4B-it.litertlm` (3.66 GB, `litert-community/gemma-4-E4B-it-litert-lm`). Already on device at `/sdcard/Android/data/com.google.aiedge.gallery/files/relais/` (and a copy at `/data/local/tmp/relais/`).
- **SELINUX GOTCHA:** the app process **cannot** read `/data/local/tmp` — model must live in the app's own files dir; `cacheDir` must be app-writable.
- **SDK / build:** `ANDROID_HOME=/home/user/Android/Sdk`; build from `Android/src` via `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`.
- **App IDs:** debug = `com.google.aiedge.gallery` (coexists with release `com.google.ai.edge.gallery`).
- **Validation pattern (reuse this):** `Android/src/app/src/androidTest/java/com/google/ai/edge/gallery/RelaisBackendBenchmarkTest.kt`. Run a gate:
  ```
  adb shell am instrument -w -e class com.google.ai.edge.gallery.RelaisBackendBenchmarkTest#<method> \
    -e model /sdcard/Android/data/com.google.aiedge.gallery/files/relais/gemma-4-E4B-it.litertlm \
    com.google.aiedge.gallery.test/androidx.test.runner.AndroidJUnitRunner
  ```
  Results via `adb logcat -s RelaisBench`.

## Gate results — node built & validated on Pixel 9 (2026-05-25)
Implemented in `app/.../relais/` (`RelaisEngine`, `RelaisNodeService`, `RelaisHttpServer`,
`BackendSelector`); validated via `RelaisNodeTest.kt` + host curl. All on-device, real runs:

- **G1 ✅** Resident multimodal engine IN the foreground service (`RelaisNodeService`, dataSync FGS +
  partial wake lock). `resident=true`; text→"ping", image(red)→"Red", audio→processed (routed GPU);
  streamed decode **5.63 tok/s** (>4.0 floor). Throughput timed by token streaming (BenchmarkInfo
  only populates via `benchmark()`, per Q1).
- **G2 ✅** Survival under **real Doze** (elapsed-time, not forced): screen off + battery
  unplugged-sim, real 5-min screen-off, then real ~15-min wait during which the device entered
  Doze on its own (`deviceidle=IDLE`). LAN inference then succeeded in 7.6s @ **5.37 tok/s** with a
  correct full-sentence answer — resident engine not evicted. Needed: foreground service (dataSync)
  + partial wake lock (both in impl). (A faster forced-idle pass corroborated this @5.59 tok/s.)
- **G3 ✅** Endpoint bound `0.0.0.0:8080` (raw `ServerSocket`, no dep). **Real LAN inbound** from a
  separate host to `192.168.68.57:8080`: `/health` ok; `/generate` text→"ping", **text+image→"Red"**,
  sentence gen @ 5.6 tok/s. Also reachable via adb-forward.
- **G4 ✅** `BackendSelector`: audio→GPU always; image/text→AICore on Pixel 10+ else GPU. The NPU
  path (`RelaisAicore`, ML Kit GenAI / Gemini Nano) is **fully wired with a real `checkStatus()`
  probe** — not a stub. On this Pixel 9 the probe returns `FEATURE_NOT_FOUND` (ML Kit error 606),
  empirically confirming the device is excluded from AICore, so all traffic resolves to GPU. The
  `g4b_npuAicorePathOrSkip` test runs the real NPU generation on a Pixel 10+ and auto-skips as
  **UNVERIFIED** here — the deferred gate closes by simply connecting a Pixel 10, no code change.
- **G4b — deferred gate CLOSED 2026-07-07 (Pixel 10 in hand): AICore is UNAVAILABLE on this
  Pixel 10, honest skip.** Ran `g4b_npuAicorePathOrSkip` (fullOpen debug, `main`-era build) on
  rango — Pixel 10 Pro Fold / Tensor G5 / **GrapheneOS** with sandboxed Play services
  (`app.grapheneos.gmscompat` + `com.google.android.gms` + Play Store present). Result:
  `RelaisBench: AICore/NPU available=false on Pixel 10 Pro Fold` → the real ML Kit
  `checkStatus()` probe reported not-available (no exception logged — a clean non-AVAILABLE
  `FeatureStatus`), so the test skipped via its assumption
  (`org.junit.AssumptionViolatedException`, instrument run `OK (1 test)`, 0.138 s). Root cause:
  the **AICore system app (`com.google.android.aicore`) is absent** — GrapheneOS does not ship
  it and sandboxed Play cannot install privileged system components, so ML Kit GenAI / Gemini
  Nano has no on-device runtime to bind. **Verdict:** the selector behaves correctly (everything
  resolves to GPU_LITERTLM, which is exactly what this node has been serving on rango all along);
  the NPU_AICORE path remains code-verified but hardware-unverified — it needs a **stock-OS
  Pixel 10**, not just Pixel 10 silicon. GOAL.md-wedge note: the wedge's NPU story on GrapheneOS
  devices runs through LiteRT-LM's accelerator delegation, not AICore.

## Robustness (workstream 3 — validated on Pixel 9)
- **Battery saver ✅** — inference under `low_power=1` works (6.0 tok/s).
- **Real Doze ✅** — engine survives genuine Doze while running (see G2).
- **Crash / OOM recovery ✅ (after a fix).** FINDING: `START_STICKY` alone did **not** restart the
  foreground service after an app-process crash (no recovery in 250s), and a plain background alarm
  hit `ForegroundServiceStartNotAllowed` (Android 12+). FIX: `RelaisWatchdog` — a self-rescheduling
  **exact** alarm (`setExactAndAllowWhileIdle` + `USE_EXACT_ALARM`), which grants the temporary
  FGS-start exemption. After a crash the node is revived in **~55s** (new PID, inference returns
  "Alive"). Gated by a `shouldRun` latch so an intentional stop is honored.
- **Wifi drop/reconnect + IP change ✅** — dropped wifi: server stayed alive (verified over
  USB-forward; the `0.0.0.0` bind survives the interface going down). Reconnect changed the IP
  (192.168.68.57 → .55); LAN health + inference worked on the new IP with no code change.
  Implication: **clients must rediscover the IP** after a change — add mDNS/NSD for zero-config.
- **OOM-kill:** covered by the same watchdog path as a crash (both are process death → exact-alarm
  restart); validated via `am crash`. A forced low-memory LMK kill needs root/mem-hog — not done.
- **Overnight/multi-hour Doze:** partial — a real ~20-min Doze passed (G2). Multi-hour soak left as
  a follow-up.

## Honesty / stop conditions for the `/goal` run
- Do **not** claim NPU on the Pixel 9. Do **not** add audio to the AICore path.
- Implement the AICore/NPU branch but **guard it behind a runtime `checkStatus()`/device probe**; on the Pixel 9 it must **skip with a logged TODO** and be marked **UNVERIFIED** (no Pixel 10 to validate).
- **Validation is mandatory per gate** — end each with an on-device proof captured in logcat/test result, never an assumption. If a gate can't be proven on hardware in hand, STOP and say so.

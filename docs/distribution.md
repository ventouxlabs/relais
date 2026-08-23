# Relais distribution & release runbook

How Relais is signed, built, and published to its three channels. This doc currently covers the
**release pipeline** (signing + CI). Store-listing metadata (Fastlane), the Play policy paperwork
(privacy policy / Data Safety / content rating), and the IzzyOnDroid RFP are follow-up sub-projects and
will be appended here as they land.

## Channels & artifacts

| Channel | Variant | Gradle task | Artifact | applicationId |
|---|---|---|---|---|
| Google Play | `fullPlaysafe` | `bundleFullPlaysafeRelease` | **AAB** | `com.ventouxlabs.relais` |
| IzzyOnDroid | `fullOpen` | `assembleFullOpenRelease` | **APK** | `com.ventouxlabs.relais.izzy` |
| GrapheneOS / GitHub | `degoogledOpen` | `assembleDegoogledOpenRelease` | **APK** | `com.ventouxlabs.relais.degoogled` |

The three are signed with **one** release key (below). `namespace` stays `cc.grepon.relais`; appId is set
per-channel in `build.gradle.kts` (`androidComponents.onVariants`). `degoogledPlaysafe` is intentionally
not shipped. F-Droid's main repo is **not** a target — the bundled `litertlm`/`litert` are proprietary
prebuilt native blobs, so the FOSS-only main repo can't accept any variant; IzzyOnDroid is the
F-Droid-ecosystem home.

### IzzyOnDroid — NOT PURSUED (decided 2026-08-05)

**#123, #250 and #252 are closed as not planned.** The prep was completed and the constraints below
are all verified — the decision is about cost/benefit, not feasibility.

**The APK is 2.1% of what an operator downloads.** `fullOpen` is 74.3 MiB; the E4B model is
3,490 MiB — **47x the APK**. Unbundling both native runtimes (#250 + #252) would cut total first-run
bytes by **1.1%**, in exchange for two native-library provisioning lanes, new hosting for a 29.5 MiB
`.so` that must version-match the app or crash natively instead of returning a clean 501, and *then*
a "rare" size exception plus a proprietary-components argument decided by one maintainer.

**Izzy adds discoverability, not delivery.** It tracks GitHub Release assets — the same ones
Obtainium tracks, which already works and shipped v1.0.17. For a headless node aimed at operators who
dedicate a spare phone to it, an F-Droid client listing is not the binding constraint on adoption.

The counter, recorded so a future revisit is not starting from a strawman: F-Droid-ecosystem presence
is real credibility for an AGPL on-device-AI project and compounds slowly. If the audience ever
widens past single-operator, this is worth more than it is today. Nothing here is one-way.

**Point Izzy-curious users at Obtainium + GitHub Releases.**

The verified constraints below stand, so a future attempt starts from evidence rather than scratch.
Three of them were previously assumed and wrong — do not re-derive them:

- **Size: ~30 MiB per app**, exceptions rare and "well reasoned". No build fits *as shipped today* —
  `fullOpen` is 74.3 MiB (248% of cap), `degoogledOpen` 33.6 MiB (112%) — but that is not a floor.
  Measured compressed bytes (`unzip -v`, column 3, against the published v1.0.17 artifact) show
  `degoogledOpen` carries **11.06 MiB of sherpa-onnx TTS runtime** (`libonnxruntime.so` 7.33 +
  `libsherpa-onnx-jni.so` 1.84 + `libsherpa-onnx-c-api.so` 1.76 + `libsherpa-onnx-cxx-api.so` 0.14).
  Unbundling it (#252) lands
  `degoogledOpen` at **~22.5 MiB — under the cap, no exception needed.**

  That figure is for `degoogledOpen`. **DECIDED 2026-08-05: IzzyOnDroid stays on `fullOpen`** — the
  channel table above is unchanged, and Izzy users would keep image generation, OCR and AICore rather
  than getting the stripped GMS-free build. An exception would therefore have been required. What it
  would have cost, measured — retained because it is the arithmetic the not-pursuing decision rests on:

  | `fullOpen` state | Size | vs 30 MiB cap | Exception ask |
  |---|---|---|---|
  | today | 74.02 MiB | 247% | 44 MiB over |
  | with image-gen unbundled (was #250) | 44.51 MiB | 148% | 14.5 MiB over |
  | with both runtimes unbundled (was #250 + #252) | 33.45 MiB | 112% | 3.45 MiB over |

  Even the best case never reaches the cap, and buying it costs two native-provisioning lanes for
  1.1% of first-run bytes. That is the trade that closed #123.

  **Both unbundlings are achievable — one verified against the AAR, one proven on hardware.**

  - **#250 (image-gen, 29.51 MiB) is tractable.** `llmedge` 0.4.7.2 ships
    `io.aatricks.llmedge.core.NativeLibraryLoader` exposing `loadLibraryFileOnce`,
    `resolveExactLibraryPath` and `loadCandidates` — a deliberate path-based loading seam, which is
    exactly what fetching a `.so` at runtime needs. It also exposes an `llmedge.disableNativeLoad`
    system property and `LLMEDGE_BUILD_NATIVE_LIB_PATH`.
  - **#252 (TTS runtime, 11.06 MiB) is also tractable — PROVEN ON DEVICE 2026-08-05.** An earlier
    revision of this doc claimed the opposite, reasoning that because `OfflineTts.class` carries
    `<clinit>` → `System.loadLibrary("sherpa-onnx-jni")` (18 sherpa classes do), and `loadLibrary`
    resolves through `ClassLoader.findLibrary()` against the APK's `nativeLibraryDir`, stripping the
    `.so` would throw `UnsatisfiedLinkError` before `dlopen`. **That was reasoned, not measured, and
    it is false.** `SherpaUnbundleProbe` on comet (Pixel 9, `fullOpen`) stripped all four libs from
    the APK (`PREMISE sherpa libs still in APK: []`), `System.load()`-ed them from `filesDir` in
    dependency order, then forced `OfflineTts`'s `<clinit>`:

    ```
    libonnxruntime.so / libsherpa-onnx-c-api.so / libsherpa-onnx-cxx-api.so /
    libsherpa-onnx-jni.so:  System.load OK
    VERDICT: CLINIT OK — System.load(path) SATISFIED sherpa's loadLibrary.
    ```

    ART resolves the already-loaded soname rather than failing at `findLibrary`. **No reflection, no
    custom ClassLoader, no fork of the sherpa bindings.** The libs must still land in app-private
    internal storage — `dlopen` refuses world-writable paths, so `externalFilesDir` will not do.

  So both unbundlings are viable and the RFP can be planned around **33.45 MiB / 3.45 MiB over**. The
  residual is not removable either way: ML
  Kit OCR (5.42 MiB native) can only be unbundled via Google Play Services, trading a size exception
  for a GMS dependency the F-Droid ecosystem likes less.
- **Venue: Codeberg `IzzyOnDroid/repodata/issues`.** The GitLab `IzzyOnDroid/repo` is archived and
  read-only.
- **Proprietary components:** the policy reads *"there should be no proprietary components"*,
  tolerated only *"if they are essential for the app's core functionality"* — it is not a routine
  `NonFreeDep` anti-feature flag, as this doc previously implied. Relais's case is strong (litertlm
  **is** the product) but must be argued in the RFP, not assumed.

Everything an RFP needs is already in place, should this ever be revisited: fastlane metadata complete
(short 77/80, full 2324/4000, icon, 3 screenshots, changelogs ≤500 chars), release-key signed, no
`debuggable`/`testOnly`, GitHub Releases as source. What was missing was never the paperwork — it was
the size, and the judgement that closing that gap is not worth its price. See #123 for the decision.

**Three lessons, each earned by getting it wrong here first.**

1. **Measure compressed, not uncompressed.** `unzip -v` column 3 is download cost; column 1 is the
   on-device install footprint, because `useLegacyPackaging = true` compresses `.so` ~2.6:1 (see
   `build.gradle.kts:95-98`). Reading column 1 overestimates native-lib savings ~3x.
2. **Do not treat the smallest current variant as a floor** without itemising what is inside it.
   `degoogledOpen` looked like a hard 33.6 MiB floor until the TTS runtime was itemised out of it.
3. **Do not assert platform behaviour from reasoning — run it.** The claim that stripping a `.so`
   makes `System.loadLibrary` fail at `findLibrary` before `dlopen` was argued from the AAR's
   bytecode and Android internals, written into this doc, an issue and a PR, and was **false**.
   A ~10 minute on-device spike (`SherpaUnbundleProbe`, strip → `System.load` → force `<clinit>`)
   settled it. Anything asserted about ART, `dlopen`, SELinux or scoped storage is a hypothesis
   until a device says otherwise.

## The signing key (generate once, never rotate)

IzzyOnDroid and Obtainium/GrapheneOS users update **in place**, which requires the APK signature to stay
constant forever. **If this key is lost or rotated, every sideload user must uninstall + reinstall.** Back
up the `.jks` and its passwords offline (e.g. a password manager + an encrypted backup).

```bash
keytool -genkeypair -v -keystore relais-release.jks -alias relais \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
# answer the prompts (CN, org, etc.); set a strong store password and key password.

base64 -w0 relais-release.jks   # copy the output into the RELEASE_KEYSTORE_BASE64 secret
```

For Google Play, **enrol in Play App Signing**: this key is used as the **upload key** (it signs the AAB
you upload); Google holds and manages the actual app-signing key. If the upload key is ever compromised it
can be reset with Google — unlike the sideload signature, which cannot change.

## GitHub Actions secrets

Add under **repo → Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the `base64 -w0` output of `relais-release.jks` |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | `relais` |
| `RELEASE_KEY_PASSWORD` | key password |

`build.gradle.kts` reads `RELEASE_STORE_FILE` + the three above from the environment. When they are
**absent**, the `release` build type falls back to debug signing — so local builds, contributor builds,
and `build_android.yaml` work with no secrets. The release CI sets `RELEASE_STORE_FILE` to the decoded
keystore path.

## Cutting a release

1. Bump `versionCode` / `versionName` in `Android/src/app/build.gradle.kts`, commit.
2. Tag and push: `git tag v1.0.16 && git push origin v1.0.16`.
3. `.github/workflows/release.yaml` runs on the `v*` tag: decodes the keystore, builds the AAB + the three
   APKs, re-runs the hard gates (**GMS=0** on degoogled, **playsafe-permission removal**, **16 KB**
   native alignment), `apksigner verify`s the APKs, and opens a **draft GitHub Release** with the AAB +
   the `fullOpen` and `degoogledOpen` APKs attached.
4. Review the draft Release, then publish it. The `fullOpen`/`degoogledOpen` APKs are now the
   GitHub-Release assets IzzyOnDroid and Obtainium track.
5. **Google Play (manual, first time):** upload the AAB to the Play Console, enrol in Play App Signing,
   complete the store listing + policy forms (separate sub-project), and submit for review. Subsequent
   uploads reuse the same upload key.

### Testing the pipeline without cutting a release
Run the **Release** workflow via *Actions → Release → Run workflow* (`workflow_dispatch`). It builds + gates
the artifacts and uploads them as a workflow artifact instead of creating a GitHub Release. (Without the
`RELEASE_*` secrets it builds debug-signed — fine for exercising the pipeline, not for store upload.)

## Notes
- `release.yaml` also assembles the `fullPlaysafe` **APK** purely so the permission + alignment gates run
  on the same merged manifest/libs the Play AAB ships; that APK is not published.
- Editing files under `.github/workflows/` can fail to auto-trigger the *Build Android APK* workflow on a
  PR (a known GitHub quirk); verify `release.yaml` via `workflow_dispatch` or a throwaway tag.


## Privacy policy (T16/#120)

- **Source of truth:** `docs/privacy-policy.md`. **Hosted copy:** `docs/privacy-policy.html`
  (same content, brand-styled), deployed by `.github/workflows/static.yml` to GitHub Pages at
  **`https://ventouxlabs.github.io/relais/privacy-policy.html`** — this is the URL for the Play
  Console *Privacy policy* field and the IzzyOnDroid metadata. Editing either policy file must
  keep the two in sync (the HTML is the rendered copy of the md).
- Hosting decision: GitHub Pages via the existing `static.yml` artifact deploy (no Jekyll), chosen
  over a raw-file URL because Play requires a real, stable web page. If a `ventouxlabs` domain
  materializes later, point it at the same file and update the console field.

## Play Data Safety form — question → answer mapping (T17/#121)

Variant under review: **`fullPlaysafe`** (`com.ventouxlabs.relais`). Every answer below is
traceable to code; the release workflow's permission gate enforces the playsafe manifest strips
(exact alarms, battery-optimization request, `READ_CALENDAR`, the notification listener +
`TriageControlActivity`).

**Overview answers**

| Console question | Answer | Code-level justification |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | No analytics/telemetry/crash-reporting SDKs (Firebase removed in the fork; no `com.google.firebase`/`analytics` deps in `build.gradle.kts`). The one developer-bound egress is #258's opt-in, per-report send (`ContentReportDelivery` → `report.ventouxlabs.com`, default off) — everything else is user-initiated traffic to user-chosen third-party services (below), which Play's Data Safety guidance treats under the user-initiated-action / service-provider carve-outs, since none of it reaches the developer. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | Model downloads + HF search/resolve are HTTPS (`RelaisHuggingFace`, `ImageModelProvisioner`, `EmbeddingModelProvisioner`, `DownloadWorker`); allowlist/release checks are HTTPS; webhooks are HTTPS-only unless the operator explicitly allowlists a host (`WebhookGuard`, `RelaisConfig.KEY_WEBHOOK_ALLOWLIST`); LAN API offers HTTPS :8443 (self-signed) alongside loopback HTTP :8080. The report send (`ContentReportDelivery` → `report.ventouxlabs.com/report`) is HTTPS three ways: the client hard-codes `https://` with redirects disabled, and the Worker itself refuses any request the edge marks plaintext (`isPlaintextRequest`, `report-worker/src/index.ts` → `403 https required`) — added after the deployed Worker was observed accepting and storing a report POSTed over plain http (the zone's Always Use HTTPS was off), so this answer is a property of the code on both ends, not of zone configuration. The zone's *Always Use HTTPS* toggle is defense in depth on top. |
| Do you provide a way for users to request that their data is deleted? | **Yes** | On-device data is deleted via in-app controls / Clear data / uninstall. Submitted reports are retained by the Worker for 180 days, and the salted rate-limit identifier for one hour after that caller's last **counted** request — `overRateLimit` renews the TTL on every request it counts (it returns before `put()` once the caller is over the limit), so it is a renewing TTL, not a one-hour cap (`report-worker/src/index.ts`). The record has no dedicated identity field and stores no link from a report back to its caller, so honoring a deletion request means the requester supplies enough of a report for a manual scan of the KV namespace; requests go to the contact email in the privacy policy. |

**Per-data-type notes a reviewer may probe.** Four rows below — Messages, App activity, Device IDs,
Personal info — are optional collection via #258's report send (default off, decided per report).
Everything else remains not collected:

| Data type | Why "not collected" holds |
|---|---|
| Messages / "Other in-app messages" | **Optional collection, moderation purpose.** A flagged **excerpt** is model output the operator chose to report, transmitted to the developer only when the operator also checks "send to developer" (`ContentReportDelivery`, default off). Outside a sent report: prompts + completions are processed in-process by the resident LiteRT-LM engine (`RelaisEngine`) and never transmitted off-device by the app; LAN clients receive responses over the operator's own network — the app is the server, not a collector. |
| Audio / Photos+videos | `RECORD_AUDIO`/`CAMERA` feed on-device transcription (`/v1/audio/transcriptions` bridges to the local engine) and on-device vision; no upload path exists in the code. |
| Personal identifiers / credentials | The HF token is user-provided, stored in `EncryptedSharedPreferences` (`RelaisConfig`), sent solely as a Bearer to `huggingface.co` on downloads the user initiates (one-way auth-drop on redirect, `ImageModelProvisioner`/`SkillSourceFetcher` patterns). The node access key never leaves the device except displayed/shared by the operator. **Resolved for #258 (declare): Personal info → optional collection, app-functionality (moderation) purpose.** A report's `excerpt`/`note` are free text — `parseReport` only length-bounds them, unlike the allowlisted `reasonId`/`surface` — so a report can carry a name, email or address the operator typed into the note, or that a flagged excerpt repeats back from the model. The absence of an identity **field** in the schema is not an absence of identifying **content**. Full reasoning: `store-submission.md` gate 1, Q1 (#267). |
| Device IDs | **Optional collection, fraud-prevention purpose.** The report Worker retains a salted SHA-256 of `cf-connecting-ip` to rate-limit the unauthenticated report endpoint, on a one-hour TTL that **renews on every counted request** rather than capping at an hour (`overRateLimit`, `report-worker/src/index.ts` — do not shorten this to "for one hour" on a form). Play counts a retained stable identifier as collection regardless of reversibility. Not read, used, or transmitted anywhere else in the app. |
| App activity / "App interactions" | **Optional collection, app-functionality purpose.** A sent report carries `reasonId` (which category the operator chose), `surface` (which in-app surface the output came from) — both describing app interaction — and the operator's free-text moderation **`note`**, typed here as **`Other user-generated content`** rather than Messages: Play defines that type as user bios, notes, or open-ended responses, which describes a moderation note, while "message to or from someone" does not. *(This row did not exist until 2026-08-08 — the type was introduced by gate 1's declaration and had no entry here, which is exactly the gap a per-type table is supposed to close.)* |
| Web browsing / location / contacts / calendar / health / financial | No code paths; `READ_CALENDAR` is stripped from the playsafe manifest (`src/playsafe/AndroidManifest.xml`, CI-gated). |

**Egress inventory (updated 2026-08-15 for #258's report send — the one leg that reaches the
developer; everything else from source sweep 2026-07-07):** `report.ventouxlabs.com` (opt-in report
send, `ContentReportDelivery` — the ONLY developer-bound leg), `huggingface.co` (model
search/resolve/download + optional user token), `dl.google.com` (inherited Gallery catalog model
downloads, `DownloadAndTryButton`/`DownloadWorker`), `raw.githubusercontent.com` (model allowlist
JSON, `ModelManagerViewModel`), `api.github.com` (release check, `NewReleaseNotification`),
operator-configured webhooks (`WebhookDelivery`, SSRF-guarded + IP-pinned), operator-provided skill
URLs (`SkillSourceFetcher`, pinned). Plus LAN-only serving + mDNS advertisement (`RelaisDiscovery`:
service name/port, no personal data).

## Play content rating (IARC) — questionnaire mapping (T17/#121)

- **Category:** Utility / productivity / developer tool.
- Violence / sexuality / language / controlled substances / gambling: **None** — the app ships no
  content; it is an inference server + control panel.
- **User interaction / UGC:** the app itself has no social features, sharing hub, or user community;
  content is generated locally on the operator's request. Answer "users can generate content via
  on-device AI" where the questionnaire asks about AI.
- **AI-generated content: Yes** — on-device LLM chat (+ image generation on this variant via
  sd.cpp). Mitigations to declare: fully local processing, operator-only access behind a
  device-generated bearer key, Gemma models ship under Google's Gemma Terms +
  Prohibited Use Policy (linked in-app: `ai.google.dev/gemma`).
- Unrestricted internet: the app accesses the internet (model downloads), but provides no browser.
- **Expected rating:** Everyone / PEGI 3 tier with the AI-content disclosure. (IARC's generative-AI
  question set is new — answer honestly per above; the rating may come back higher in some locales.)

**FGS disclosure (console "App content" page, related):** foreground service type `dataSync`
(`RelaisNodeService`) — declared use: keeping the local inference server resident while the
operator's devices use it; started only by explicit operator action (START / boot-start opt-in).

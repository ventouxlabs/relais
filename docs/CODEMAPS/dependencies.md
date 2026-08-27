# Dependencies, Flavors & Manifest Surface

<!-- Generated: 2026-08-25 | Files scanned: build.gradle.kts + libs.versions.toml + AndroidManifest + src/{full,degoogled,playsafe} + report-worker/package.json + .github/workflows | main @ 4679d924 -->

## External dependencies (catalog: `Android/src/gradle/libs.versions.toml`)
| Dep | Version | Purpose | Flavor |
|---|---|---|---|
| litertlm | **0.12.0** (was 0.11.0; 0.14.0 tested+reverted — regresses G5 TPU, #150) | resident LLM inference | all |
| litert | 1.4.2 | bundled TFLite runtime (EmbeddingGemma) | all |
| **sherpa-onnx** | **1.13.4 (JitPack) [NEW]** | on-device TTS (Piper voice, `/v1/audio/speech`) | all — GMS-free |
| **commons-compress** | **1.27.1 [NEW]** | decompress TTS voice `.tar.bz2` bundle | all |
| **kotlinx-coroutines-test** | **1.10.2 [NEW]** | virtual-time testing (polling-pause regression) | test |
| mlkit-genai-prompt | 1.0.0-beta2 | AICore/Gemini Nano (NPU) | full |
| llmedge | **0.4.7.2** | sd.cpp image-gen (CPU on Pixel 10 — see `ImageGenBackendPolicy`) | full |
| room | 2.7.1 | SQLite ORM (schema now **v7** — 6→7 adds report send-state columns, #273) | all |
| **robolectric** | **4.16** (was 4.14.1) | JVM Android tests | test |
| hilt-android | 2.58 | DI | all |
| bcpkix-jdk15to18 | 1.78.1 | self-signed TLS for LAN server | all |
| compose-bom | 2026.02.00 | UI | all |

Tensor SDK (G5 TPU) is **not a Gradle dep** — a committed native dispatcher (`libLiteRtDispatch_GoogleTensor.so` under jniLibs), spike-only. AGP 8.8.2, Kotlin 2.2.0, **compileSdk/targetSdk 36** (was 35; #284), minSdk 31, versionCode 38 / 1.0.20.

**The targetSdk 36 bump was gated on Robolectric, not on app code.** 4.14.1 caps at maxSdk 35, so at
targetSdk 36 the *entire* JVM suite dies at initialization; 4.15.1 does **not** fix it (verified),
4.16 does. AGP 8.8.2 warns about `compileSdk = 36` but does not fail. All 16 targeting-36 behavior
changes were audited: only large-screen orientation applied, and it measured as a **no-op** on a real
unfolded panel (identical `mAppBounds` at 35 and 36). Watch **Local Network Permission** — opt-in
today, and it targets LAN-serving apps, which is this app's whole function.

## Product flavors — `dist` × `policy` (unchanged shape)
| dist | policy | applicationId | Channel |
|---|---|---|---|
| full | playsafe | `com.ventouxlabs.relais` | Play Store |
| full | open | `com.ventouxlabs.relais.izzy` | IzzyOnDroid |
| degoogled | open | `com.ventouxlabs.relais.degoogled` | GrapheneOS/GitHub |

playsafe strip live (#76, `tools:node="remove"` × 6). degoogled = zero GMS, CI-enforced.

**appId ≠ namespace.** `namespace` stays `cc.grepon.relais`; `build.gradle.kts` `onVariants` sets the applicationId per channel (composing suffixes across both dimensions would double-suffix degoogled+open). This is why an on-device probe's instrument target is `com.ventouxlabs.relais.izzy.test` for `fullOpen`, not `cc.grepon.relais.test` — the latter resolves only to a pre-rebrand leftover package and fails at class-load. Resolve it per-device with `adb shell pm list packages | grep -E 'relais|ventoux'`.

**Releases** — published: `v1.0.16` (2026-08-04, the first), `v1.0.17`, `v1.0.18` (2026-08-14, capture half of #258), `v1.0.19` (send path), **`v1.0.20` (2026-08-18 — durable send + schema v7 + targetSdk 36)**. `release.yaml` builds to a **draft** the maintainer publishes. Artifacts per release: `degoogled-open` APK, `full-open` APK, `full-playsafe` AAB, all past the build/permission/ABI/16 KB-alignment/signature gates. R8 is ON for release builds (#231); every keep rule was earned from a real on-device failure, and CI runs no R8 — so proguard changes and reflective dep bumps need an on-device *inference* check, not just a green build.

**Signing continuity is verified per release, not assumed:** v1.0.19 and v1.0.20 carry identical
certs (`3468fbe6…5b9e9bd2`, `CN=Relais, O=grepon.cc`), so users upgrade in place. A locally-built
**pre-keystore** APK is signed with a different key and can NEVER be upgraded in place by a published
release — it needs an uninstall, which destroys on-device data. Check the installed signature before
planning any in-place upgrade.

## report-worker (the one non-Android component) [NEW #258]
`wrangler` ^4.0.0 · `vitest` ^3.0.0 · `typescript` ^5.7.0 · `@cloudflare/workers-types` ^5.x ·
Node ≥22 (wrangler's own engines floor — Node 20 installs a CLI that won't reliably run).
Cloudflare custom domain `report.ventouxlabs.com`, KV binding `REPORTS`; `workers_dev`/`preview_urls`
pinned **false** in the committed template (a fresh deploy would otherwise publish a
`*.workers.dev` twin).

## External hosts the app talks to (grepped, `main/java`)
`huggingface.co` (model downloads + OAuth) · `github.com`/`raw.githubusercontent.com` (release
checks, skill fetch) · `report.ventouxlabs.com` (opt-in report send — the ONLY developer-bound
leg) · `dl.google.com` · webhooks = operator-configured, HTTPS-only unless allowlisted
(`WebhookGuard`).

## License split (CI-enforced) [NEW since 06-26]
`.github/workflows/license-lint.yml` — net-new Relais files require an AGPL-3.0 header; Google-origin files keep Apache-2.0; scans `src/main/*.kt` only.

## Repo moved to the `ventouxlabs` org (2026-08-22) — two consequences [NEW]
`bearyjd/relais` → **`ventouxlabs/relais`**. `github.com` URLs redirect (releases included, verified);
**GitHub Pages URLs do not** — the privacy policy moved to
`https://ventouxlabs.github.io/relais/privacy-policy.html` and the old host now 404s.

**`gitleaks` broke on the move and had to be rewired.** `gitleaks/gitleaks-action@v2` refuses to run
for **organization**-owned repos without a paid `GITLEAKS_LICENSE`; it had run free for years under
the personal account. Because `gitleaks` is a **required status check** on `main`, it went
permanently red and blocked every PR. `secret-scan.yml` now runs the **gitleaks CLI** (MIT-licensed;
only the Action wrapper is commercial) from a pinned release binary. Note 8.x removed the `detect`
subcommand — full-history scanning is `gitleaks git`. The job id must stay `gitleaks` so branch
protection still matches it.

## CI workflows (`.github/workflows/`)
| Workflow | Job |
|---|---|
| `build_android.yaml` | JVM unit tests × 3 flavors; APK build; **compiles the `androidTest` probe suite** (#285 — compile only, probes need hardware and are not run in CI) |
| `secret-scan.yml` | `gitleaks` (CLI, pinned) + `trufflehog --only-verified` — both **required** |
| `license-lint.yml` | AGPL/Apache header split |
| `release.yaml` | signed artifacts + GMS-leak / Play-permission / 16 KB-alignment gates → draft release |
| `report-worker.yml` | boots **real workerd** (unit tests + `--dry-run` can't see workerd-only failures, #268) |
| `static.yml` | GitHub Pages deploy — `skills/**`, `docs/privacy-policy.html`, **`docs/index.html`** |

**`static.yml` triggers on paths, so a new published file must be added to the trigger** or it
deploys once and every later edit silently doesn't publish. Branch protection on `main` requires:
`Build Android APK`, `JVM unit tests`, `gitleaks`, `trufflehog`, `headers`.

## GitHub Pages site [NEW]
`static.yml` publishes three things to `https://ventouxlabs.github.io/relais/`: `skills/`, the
privacy policy (the URL on the Play Console form), and **`docs/index.html`** — a landing page added
2026-08-25. The page shares its `:root` token block verbatim with `privacy-policy.html` (DESIGN.md
amber-on-charcoal, monospace, dark-only) and is fully self-contained: no fonts, no CDN, no analytics.
Its `curl` example uses **`https://<ip>:8443`**, because HTTP 8080 is loopback-bound and unreachable
from the LAN (`RelaisNodeService.kt:189-190`).

## Manifest — exported surface
Unchanged core set (MainActivity, RelaisControlActivity, RelaisShareActivity, RelaisNfcActivity, RelaisTaskerActivity, tile/widget receivers) **+ new** `cc.grepon.relais.notifications.BootReceiver` (exported=true) alongside the existing opt-in `RelaisBootReceiver`.

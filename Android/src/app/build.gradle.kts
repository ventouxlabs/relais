/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  // Build-time only: scans the real dependency graph and generates R.raw.aboutlibraries, consumed by
  // the FOSS license screen (LicensesActivity). Fully GMS-free, so it applies to BOTH flavors (CI
  // dex-scan enforces the degoogled APK stays GMS-class-free). Replaces the GMS oss-licenses plugin.
  alias(libs.plugins.aboutlibraries)
  alias(libs.plugins.ksp)
  kotlin("kapt")
}

// Release signing material from CI secrets (the RELEASE_* env vars). Non-null only when the keystore
// env is set AND the file is present, so local + contributor builds (env absent) fall back to debug
// signing in buildTypes.release below — no secrets needed to build. The maintainer generates + owns
// this key and NEVER rotates it (IzzyOnDroid / Obtainium update continuity); see docs/distribution.md.
val releaseStoreFile: String? = System.getenv("RELEASE_STORE_FILE")?.takeIf { file(it).exists() }

android {
  namespace = "cc.grepon.relais"
  compileSdk = 36

  defaultConfig {
    applicationId = "cc.grepon.relais"
    minSdk = 31
    targetSdk = 36
    versionCode = 38
    versionName = "1.0.20"

    // NOTE: abiFilters is deliberately NOT set here. AGP takes the UNION of defaultConfig and
    // buildType abiFilters, so anything listed at this level can never be narrowed later — a release
    // filter of just arm64-v8a would silently still ship x86_64. The per-buildType filters in
    // `buildTypes` below are the single source of truth. See #123.

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "cc.grepon.relais.RelaisApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    // Populated only when the RELEASE_* env is present (the CI release job decodes the keystore from
    // the RELEASE_KEYSTORE_BASE64 secret). Left empty otherwise — buildTypes.release then signs with
    // debug (below), so no secrets are required to build.
    create("release") {
      releaseStoreFile?.let { path ->
        storeFile = file(path)
        storePassword = System.getenv("RELEASE_STORE_PASSWORD")
        keyAlias = System.getenv("RELEASE_KEY_ALIAS")
        keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
      }
    }
  }

  // Backend.NPU(nativeLibraryDir) resolves the Tensor TPU dispatcher (libLiteRtDispatch_GoogleTensor.so,
  // committed under src/main/jniLibs) by ABSOLUTE PATH in nativeLibraryDir — which only exists when
  // native libs are extracted at install time. Modern in-APK packaging maps libs compressed and would
  // NOT populate that path, so the TPU lane requires legacy packaging. Module-wide (all build types +
  // all shipping flavors) because the dispatcher now ships in RELEASE too — the same setting the
  // official LiteRT sample_app_tpu uses. Cost: native libs are extracted uncompressed (slightly larger
  // install); accepted as the price of the TPU lane (proven ~2.8x GPU on Tensor G5).
  packaging {
    jniLibs.useLegacyPackaging = true

    // #229: llmedge is a general-purpose on-device AI toolkit, not an image-gen library. It bundles
    // FOUR engines — sd.cpp (image), SmolLM (text), Bark (text→audio), Whisper (audio→text) — and
    // Relais uses only the image half (`io.aatricks.llmedge.image.*` from the :imagegen service).
    // The other three duplicate capability we already have: text is litertlm, TTS is sherpa-onnx/
    // Piper (#168), STT is our own /v1/audio/transcriptions.
    //
    // Cost: 81 MiB uncompressed / MEASURED 27.3 MiB of actual APK bytes (fullOpen release
    // 171.84 -> 144.50 MiB). NOTE for anyone sizing things here: useLegacyPackaging = true sets
    // extractNativeLibs=true, which COMPRESSES .so in the APK (~2.6:1) and expands them at install.
    // So a lib's uncompressed size is its on-device install footprint, NOT its download cost —
    // getting that backwards overestimates native-lib savings by roughly 3x.
    //
    // Safe because llmedge is modality-partitioned: LLMEdgeConfig composes independent
    // Text/Speech/Image/Vision runtime configs with per-modality loaders, and LLMEdge itself carries
    // no reference to smollm/bark/whisper. VERIFIED on rango, not assumed — ImageGenServiceProbe's
    // service-lifecycle tests pass with these stripped (see the PR).
    //
    // Deliberately KEPT: libggufreader.so and libomp.so. Both are small, and ggufreader plausibly
    // backs the ModelSpec/ModelRegistry path that the image side does use — not worth 0.9 MiB of risk.
    jniLibs.excludes += setOf(
      "**/libsmollm*.so",
      "**/libbark_jni.so",
      "**/libwhisper_jni.so",
    )
  }

  buildTypes {
    debug {
      // Both ABIs for development: DEVELOPMENT.md documents an x86_64 emulator as a supported dev
      // target, and the emulator is the only place x86_64 is genuinely used.
      ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    release {
      // arm64-v8a ONLY (#123). The bundled LiteRT runtime ships .so for 4 ABIs and the litertlm AAR
      // for 2, but x86_64 in a SHIPPING build is dead weight: no Android phone runs it, and the TPU
      // lane this node exists for is Tensor-only.
      //
      // MEASURED on fullOpen release: 231.88 MiB -> 171.84 MiB, a 60 MiB (26%) cut. v1.0.15 went
      // out at 232 MiB against IzzyOnDroid's ~30 MB rule-of-thumb.
      //
      // (An earlier version of this comment claimed useLegacyPackaging stores .so UNCOMPRESSED so
      // "every byte is a real APK byte". That is backwards — see the packaging block above. The
      // 60 MiB figure was measured, so it stands; only the explanation was wrong.)
      //
      // TRADE-OFF: this also drops x86_64 from the Play AAB, so x86 Chromebooks lose support. That
      // is deliberate — reach there is marginal for an on-device LLM node — but it is the one line
      // to change if Play/ChromeOS coverage is wanted back. Narrowing it to only the `open` policy
      // would need variant-level filters, which AGP does not express cleanly.
      ndk { abiFilters += listOf("arm64-v8a") }
      // R8 shrinking, ON as of #229. Dex was the single largest part of the APK — 80.8 MiB of
      // 144.5 — because minification had been off since the fork while `proguardFiles` pointed at a
      // proguard-rules.pro that did not exist (inert when minify is off, so nothing complained).
      // MEASURED: 144.50 -> 74.10 MiB; dex 80.8 -> ~9 MiB.
      //
      // The keep rules were NOT guessed — every one was added in response to a real failure on
      // rango. See proguard-rules.pro; the short version is that protobuf-javalite and litertlm's
      // JNI surface both break at RUNTIME under R8, in ways no JVM test can catch (a JVM test never
      // runs R8). Treat any change here as requiring an on-device inference check, not just green CI.
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Real release signing when the CI secrets are present; debug-signed otherwise so assemble*Release
      // still builds locally and in build_android.yaml without any secrets configured.
      signingConfig =
        if (releaseStoreFile != null) signingConfigs.getByName("release")
        else signingConfigs.getByName("debug")
    }
  }
  // Distribution flavor split. `full` is the Play-Store build (includes the Google-Mobile-Services-
  // pulling deps); `degoogled` is the IzzyOnDroid / self-hosted-F-Droid build that excludes ALL of
  // them. The GMS-touching code lives in src/full/ and is replaced by GMS-free stubs in src/degoogled/
  // (see RelaisAicore, AICoreModelHelper, ImageTextRecognizer). Inference is already non-GMS (bundled
  // litertlm + litert), so `degoogled` is a fully functional node — it just drops ML Kit OCR (#13) and
  // AICore/Gemini-Nano. (Third-party licenses are handled by the FOSS AboutLibraries screen in both.)
  flavorDimensions += listOf("dist", "policy")
  productFlavors {
    create("full") {
      dimension = "dist"
      // AICore (Gemini Nano) is available — see isAICoreSupported() / the RelaisAicore impl.
      buildConfigField("boolean", "SUPPORTS_AICORE", "true")
    }
    // Same applicationId as `full` (drop-in replacement for the alt-store channel); not installable
    // side-by-side with `full` on one device without an applicationIdSuffix (intentional).
    create("degoogled") {
      dimension = "dist"
      // No AICore: Gemini Nano needs Play Services. This flag makes isAICoreSupported() return false
      // regardless of device model, so AICORE models are filtered out of the catalog entirely (they
      // can't run on the GMS-free stub) — not just listed-then-perma-failed.
      buildConfigField("boolean", "SUPPORTS_AICORE", "false")
    }
    // Policy dimension (ventouxlabs channels). `open` = all power-user features (IzzyOnDroid /
    // GrapheneOS); `playsafe` = the (future) Google-Play-compliant subset. NOTE: this is
    // FOUNDATION-ONLY — playsafe currently equals open APART FROM the appId. A follow-up PR will add
    // src/playsafe/AndroidManifest.xml (tools:node="remove") to drop the risky perms/components and
    // will consume POLICY_OPEN (generated below but not yet read) to gate their entry points. Shipping
    // combos: fullOpen→IzzyOnDroid, fullPlaysafe→Play, degoogledOpen→GrapheneOS (degoogled+playsafe is
    // filtered out — see androidComponents below).
    create("open") {
      dimension = "policy"
      buildConfigField("boolean", "POLICY_OPEN", "true")
    }
    create("playsafe") {
      dimension = "policy"
      buildConfigField("boolean", "POLICY_OPEN", "false")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      // Unmocked android.* returns defaults in JVM unit tests (lets AllowedModel.toModel() run
      // without Robolectric). Tradeoff: masks accidental unmocked-Android calls — migrate
      // Android-touching tests to Robolectric (PR5).
      isReturnDefaultValues = true
      // Required for Robolectric to load AndroidManifest, resources, and assets when running
      // Context-touching tests as JVM unit tests (PR5).
      isIncludeAndroidResources = true
    }
  }
}

androidComponents {
  // Ship only 3 of the 4 dist×policy combos: drop degoogled+playsafe (GrapheneOS doesn't need the
  // Play-compliant subset — it sideloads the open build).
  beforeVariants(selector().withFlavor("dist" to "degoogled").withFlavor("policy" to "playsafe")) {
    it.enable = false
  }
  // appId follows the CHANNEL, not a flavor suffix — composing suffixes across two dimensions would
  // double-suffix the degoogled+open case. namespace stays cc.grepon.relais (no source-package churn).
  onVariants(selector().all()) { variant ->
    val dist = variant.productFlavors.firstOrNull { it.first == "dist" }?.second
    val policy = variant.productFlavors.firstOrNull { it.first == "policy" }?.second
    variant.applicationId.set(
      when {
        dist == "full" && policy == "playsafe" -> "com.ventouxlabs.relais"          // Play Store
        dist == "full" && policy == "open" -> "com.ventouxlabs.relais.izzy"         // IzzyOnDroid
        else -> "com.ventouxlabs.relais.degoogled"                                   // GrapheneOS (degoogled+open)
      }
    )
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  // Runtime TLS certs for the LAN endpoint: per-node CA + SAN'd leaf (feature-18). Software RSA
  // leaf key, because AndroidKeyStore keys can't sign the TLS server handshake via conscrypt
  // on-device. jdk15to18 variant is the Android-compatible (non-multi-release) build.
  //
  // PINNED AT 1.78.1 DELIBERATELY — latest stable is 1.85. This is a tracked gap, not neglect:
  //  - CVE-2025-8916 (PKIXCertPathReviewer allocation) affects 1.44–1.78; **1.78.1 is the patched
  //    version**, which is presumably why this pin is 1.78.1 rather than 1.78.
  //  - CVE-2026-5588 (MEDIUM, PKIX draft CompositeVerifier accepts empty signature sequences)
  //    affects 1.49.0–1.83.x, fixed in 1.84.0. **1.78.1 is inside that range.**
  //
  // Unreachable in our call paths — verified, and stated that way rather than "not affected":
  // Relais uses BouncyCastle purely as a certificate FACTORY, never as a verifier. The entire
  // surface is `asn1.x500`/`asn1.x509` structures, `cert.jcajce` builders and
  // `operator.jcajce.JcaContentSignerBuilder`; `grep -rn 'CompositeVerifier\|PKIXCertPathReviewer'`
  // over src returns nothing, and no chain validation goes through BC at all (the PKIX validation
  // in RelaisCertMintTest is the JDK's default provider, and is test-only). Both CVEs live in
  // verification paths this app never enters.
  //
  // BUMPING TO 1.85: worth doing, but sequence it. A BouncyCastle bump is a REFLECTIVE-dependency
  // bump, which per this repo's R8 history breaks release-only and invisibly to every green test —
  // and it would land on keep rules that have never once been exercised on a release build. Get
  // CertTrustProbe passing on a release APK at 1.78.1 first to establish a known-good R8 baseline,
  // THEN bump and re-run the same probe. Bumping first conflates "the keep rules are wrong" with
  // "the new version moved something" inside a failure CI cannot see.
  implementation("org.bouncycastle:bcpkix-jdk15to18:1.78.1")
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.litert) // bundled LiteRT runtime (no Play Services) — embeddings work de-Googled
  // On-device TTS (#168): sherpa-onnx runtime (Apache-2.0, GMS-free → all flavors incl. degoogled).
  // Ships ONNX Runtime + espeak-ng .so; abiFilters (arm64-v8a, x86_64) trim the rest at packaging.
  // Measured Piper RTF 0.12 on rango/G5 (TtsProbe). commons-compress extracts the .tar.bz2 voice bundle.
  implementation(libs.sherpa.onnx)
  implementation(libs.commons.compress)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  // FOSS third-party-license screen (LicensesActivity) — no GMS, works in BOTH flavors. Replaces the
  // Play-Services OSS-licenses viewer; the plugin (above) generates R.raw.aboutlibraries. We use
  // core (pure-Kotlin parsing) ONLY and render the list ourselves — the prebuilt aboutlibraries-
  // compose-m3 UI calls a FlowRow overload absent from this project's Compose BOM (runtime crash),
  // and its 14.x fix needs compileSdk 36.
  implementation(libs.aboutlibraries.core)
  implementation(libs.androidx.exifinterface)
  // DocumentFile (SAF) was previously on the classpath transitively via Firebase; declare it
  // explicitly now that Firebase is removed.
  implementation("androidx.documentfile:documentfile:1.0.1")
  implementation(libs.moshi.kotlin)
  // Shared on-device SQLite (RelaisDatabase) — vector store (#4), session memory (#5), batch queue
  // (#14) all add entities/DAOs. Static singleton accessor, not Hilt-provided (matches the node layer).
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  // Home-screen widget (#3): Glance app-widget renderer. Pinned 1.1.1 (cached for --offline builds).
  implementation(libs.androidx.glance.appwidget)
  testImplementation(libs.androidx.room.testing)
  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  testImplementation(libs.com.google.code.gson)
  testImplementation(libs.robolectric)
  // #220: lets a JVM test construct DefaultDownloadRepository (its ctor calls WorkManager.getInstance)
  // so the download-refusal gate is pinned by a test of the REAL repository, not just its helper.
  testImplementation(libs.androidx.work.testing)
  testImplementation(libs.kotlinx.coroutines.test) // virtual-time coroutine tests (#171 shell-polling)
  // Real org.json implementation for JVM unit tests: Android stubs return null for put() under
  // isReturnDefaultValues=true, which breaks buildModelsResponse. This jar provides the real impl.
  testImplementation("org.json:json:20240303")
  testImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  // GMS deps — `full` flavor only. AICore/Gemini-Nano (genai-prompt) + the ML Kit Latin OCR
  // pipeline for share-in images (#13), which transitively pulls play-services-mlkit-text-recognition
  // (NOT GMS-free). `degoogled` excludes both; the touching code is stubbed out in src/degoogled/.
  "fullImplementation"(libs.mlkit.genai.prompt)
  "fullImplementation"(libs.mlkit.text.recognition)
  // On-device image generation (sd.cpp/Vulkan) — feature #16, FULL FLAVOR ONLY (#16 PR-A). Used only
  // from the process-isolated :imagegen service (src/full/imagegen/), never the node process. Two
  // excludes (the set the plan scopes for PR-A): `sentence-embeddings` drags in onnxruntime (we have
  // our own embedder); llmedge's `io.ktor` is only its HF-download path (we provision via
  // ModelSpec.localFile, and Relais's own ktor 3.4.3 is a separate direct dep, untouched by this).
  // NOTE: llmedge also transitively pulls features image-gen doesn't use — com.tom-roush:pdfbox-android,
  // kotlinx-coroutines-play-services — left in for now (full APK bloat only; all isolated to `full`, so
  // degoogled stays GMS=0).
  // image-labeling IS excluded below: it drags in com.google.mlkit:vision-internal-vkp:18.2.2, whose
  // libmlkitcommonpipeline.so has 4 KB-aligned LOAD segments (0x1000) — the lone non-16 KB-aligned lib
  // in the full APK. On Pixel 10 / Android 16 (16 KB memory pages) that triggers a PageSizeMismatch
  // load error. image-labeling has zero Relais code references and is not used by the image-gen path,
  // so excluding it removes the 4 KB lib with no functional loss (verified: :imagegen class-loads with
  // no NoClassDefFoundError). The CI "16 KB native-lib alignment" gate guards against regressions.
  "fullImplementation"(libs.llmedge) {
    exclude(group = "io.gitlab.shubham0204", module = "sentence-embeddings")
    exclude(group = "io.ktor")
    exclude(group = "com.google.mlkit", module = "image-labeling")
  }
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}

// Room exports its schema JSON (committed under app/schemas) so future migrations (#4/#5/#14) are
// diffable and migration-testable.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

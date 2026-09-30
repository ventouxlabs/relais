/*
 * Copyright 2026 Google LLC
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

package cc.grepon.relais

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cc.grepon.relais.BuildConfig
import cc.grepon.relais.common.getJsonResponse
import cc.grepon.relais.common.isPixel10
import cc.grepon.relais.data.KEY_MODEL_COMMIT_HASH
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import cc.grepon.relais.data.KEY_MODEL_IS_ZIP
import cc.grepon.relais.data.KEY_MODEL_NAME
import cc.grepon.relais.data.KEY_MODEL_TOTAL_BYTES
import cc.grepon.relais.data.KEY_MODEL_UNZIPPED_DIR
import cc.grepon.relais.data.KEY_MODEL_URL
import cc.grepon.relais.data.AllowedModel
import cc.grepon.relais.data.BuiltInTaskId
import cc.grepon.relais.data.DefaultConfig
import cc.grepon.relais.data.Model
import cc.grepon.relais.data.ModelAllowlist
import cc.grepon.relais.data.RelaisModelRef
import cc.grepon.relais.data.RuntimeType
import cc.grepon.relais.worker.DownloadWorker
import java.io.File
import kotlinx.coroutines.CancellationException

private const val TAG = "RelaisModelProvisioner"

/** Same allowlist host Gallery uses; the revision is pinned by [RelaisModelProvisioner.ALLOWLIST_REVISION]. */
private const val ALLOWLIST_BASE_URL =
  "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists"

/**
 * Self-provisioning for the headless node: resolves the configured model from Gallery's allowlist
 * and downloads it (reusing the real [DownloadWorker] transport) if it isn't already on disk, so an
 * operator no longer has to side-load a multi-GB file by hand.
 *
 * All entry points are **blocking** and must be called off the main thread (the node calls them
 * from its `relais-init` thread). They reuse Gallery's download stack as-is — [ModelAllowlist] +
 * `AllowedModel.toModel()` for URL/path construction, [DownloadWorker] for the HTTP download with
 * resume + Bearer auth — and add no new transport.
 *
 * Note: unlike Gallery's [cc.grepon.relais.data.DefaultDownloadRepository], this does not
 * observe progress via `LiveData.observeForever` (which requires the main thread). It enqueues the
 * worker directly and awaits the terminal state via WorkManager's `ListenableFuture`, which is safe
 * from a background thread.
 */
object RelaisModelProvisioner {

  /**
   * Preferred default for a fresh Pixel 10 (Tensor G5): the TPU-native Gemma 4 E2B-it. Kept even
   * after the E4B `isG5Incompatible` load-gate was removed (E4B now serves on G5) — E2B-G5 runs on
   * the Tensor TPU at ~2× the throughput and better power efficiency of E4B on the GPU, which is the
   * right out-of-box default for an always-on node. E4B remains freely selectable.
   *
   * Values resolved via the HF API (commit 361a4010, LFS size 2 588 147 712 bytes) and pinned here
   * so no network is needed to select the default on first boot.
   */
  internal val G5_DEFAULT_REF =
    RelaisModelRef(
      modelId = "litert-community/gemma-4-E2B-it-litert-lm",
      modelFile = "gemma-4-E2B-it.litertlm",
      commitHash = "361a4010ad6d88fc5c86e148e333c0342b99763d",
      sizeInBytes = 2_588_147_712L,
      displayName = "Gemma 4 E2B-it (Tensor G5)",
      source = RelaisModelRef.SOURCE_HUGGINGFACE,
    )

  /**
   * Pure decision function: returns [G5_DEFAULT_REF] iff this is a fresh Pixel 10 whose operator
   * has not yet chosen a model — no persisted ref, and no model id ever written
   * ([RelaisConfig.hasExplicitModelId]). Returns null in all other cases — non-Pixel-10 behavior is
   * byte-identical to before.
   *
   * [hasExplicitModelId], not "the id equals [RelaisConfig.DEFAULT_MODEL_ID]" (#355): a manual pick
   * clears the ref, so an operator who typed the default E4B id looked exactly like a node nobody
   * configured, and every START swapped their E4B for E2B. An unwritten id reads as the default, so
   * the old comparison added nothing the explicitness check does not already cover.
   *
   * Unit-testable with no Context or Android SDK.
   */
  internal fun deviceDefaultRef(
    isPixel10: Boolean,
    hasPersistedRef: Boolean,
    hasExplicitModelId: Boolean,
  ): RelaisModelRef? =
    if (isPixel10 && !hasPersistedRef && !hasExplicitModelId) G5_DEFAULT_REF else null

  /**
   * Applies [deviceDefaultRef] from live config and returns the ref it applied, or null. Extracted
   * from [ensureModel] so the ARGUMENTS are testable (#355 review): the pure-function tests cannot
   * see which config value feeds `hasExplicitModelId`, and passing a constant there would survive
   * them. [isPixel10] is a parameter only so a test can stand in for the device.
   */
  internal fun applyDeviceDefaultIfFresh(context: Context, isPixel10: Boolean): RelaisModelRef? =
    deviceDefaultRef(
      isPixel10 = isPixel10,
      hasPersistedRef = RelaisConfig.modelRef(context) != null,
      hasExplicitModelId = RelaisConfig.hasExplicitModelId(context),
    )?.also { ref ->
      Log.i(TAG, "Fresh Pixel 10: defaulting to G5-compatible ${ref.modelId}")
      RelaisConfig.setModelRef(context, ref)
    }

  /**
   * A provisioned file together with the model id it holds the weights for.
   *
   * The two travel as ONE value on purpose: [resolveModelPath] takes this object rather than an
   * id and a path as separate parameters, so a caller cannot hand it a mismatched pair. Passing
   * them separately is what made the first cut of this fix wrong — two reads of the `@Volatile`
   * field below could straddle a write and yield one write's id beside another write's path,
   * which presents as a cache HIT on a correctly-matched id that returns the wrong weights: #337
   * again, via the accessor written to prevent it. `@Volatile` gives visibility, never atomicity
   * across fields.
   */
  internal data class CachedModelPath(val modelId: String, val path: String)

  /**
   * Last resolved/downloaded model path, cached so the engine can re-read it without a refetch —
   * **tagged with the id it was provisioned for, and readable only for that id** (#337).
   *
   * The tag is the invariant, not an optimisation. This used to be a bare path, and "which model
   * does it describe?" was answered by whoever happened to write it last. A model switch left it
   * naming the OUTGOING model while config named the incoming one, [resolveModelPath]'s caller
   * paired the two, and [RelaisEngine.ensureInitialized] wrote them as the resident pair — old
   * weights served under the new id, with #180's mismatch check then seeing agreement and never
   * swapping. The durable half (`KEY_MODEL_PATH`) was already cleared on every id change; this
   * process-local half was not, which is why the symptom disappeared across a restart.
   *
   * Tagging makes a stale read for a DIFFERENT model id impossible to express, rather than relying
   * on each future writer of the model id to remember an invalidation call.
   *
   * **The tag is a model id, not a build.** One id can name more than one file: the G5 TPU build of
   * E2B ([RelaisModelCatalog.G5_TPU_REFS]) shares its id with the GPU build ([G5_DEFAULT_REF]), and
   * Gemma3-1B-IT's allowlist and G5 AOT builds collide the same way. A same-id build pick made while
   * the node is up therefore still resolves to the build already cached (and the registry, one entry
   * per id, answers the same), so an idle reload serves the previous BUILD of the SAME model — the id
   * every surface reports stays true. The new build applies on a service restart, which is what
   * Configure's "Restart to apply" promises: STOP destroys the engine, and START provisions through
   * [ensureModel], whose fast path was cleared by [RelaisConfig.setModelRef] (or, for a manual pick of
   * the same id, by [RelaisConfig.clearModelRef]). Keying on the build
   * here would turn that announced deferral into an idle-reload ERROR for a not-yet-downloaded build.
   */
  @Volatile private var cachedModelPath: CachedModelPath? = null

  /**
   * Test/reset seam. This object outlives a single test in a shared Robolectric sandbox, so without
   * it one test's [remember] silently answers a later test's [pathFor] through rung 1.
   */
  internal fun resetPathCacheForTest() {
    cachedModelPath = null
  }

  /**
   * The upstream catalog revision this build reads (#227).
   *
   * Deliberately a CONSTANT, not `BuildConfig.VERSION_NAME`. This used to interpolate our version —
   * which coupled a routine version bump to an upstream repo we do not control. Upstream
   * `google-ai-edge/gallery/model_allowlists/` publishes `1_0_4.json` … `1_0_15.json` and **stops
   * there**; there is no `1_0_16.json`, even though upstream carries a 1.0.16 tag.
   *
   * So bumping `versionName` pointed the node at a 404. [RelaisModelCatalog] swallows fetch failures
   * by design (offline must not crash the selector), so the symptom was a permanently empty MODELS
   * screen — "Allowlist unreachable. Enter a model id below." — with no crash and no log, on every
   * device, forever. A silent product break triggered by an unrelated one-line change.
   *
   * Bump this only when upstream actually publishes a newer catalog AND its contents have been
   * checked against [RelaisRuntimeCompat] (a new entry may be unloadable on our pinned litertlm).
   */
  const val ALLOWLIST_REVISION = "1_0_15"

  /** The pinned upstream allowlist URL, e.g. `…/model_allowlists/1_0_15.json`. */
  fun allowlistUrl(): String = "$ALLOWLIST_BASE_URL/$ALLOWLIST_REVISION.json"

  /**
   * Refuses [modelId] if it is MEASURED not to load on the pinned runtime (#220).
   *
   * Single-sourced so every gate speaks with one voice: the message is operator-facing and IS the
   * failure the operator sees — [RelaisNodeService] renders it as "Init failed: …" and
   * [ModelsScreen] as a failed download — rather than a generic engine-create stack trace.
   *
   * Only measured failures are withheld. [RelaisRuntimeCompat.Loadability.SUSPECT] and UNKNOWN pass,
   * because over-blocking here would silently break every model nobody has run yet.
   */
  internal fun refuseIfIncompatible(modelId: String) {
    RelaisRuntimeCompat.incompatibleReason(modelId)?.let { why ->
      error(RelaisRuntimeCompat.refusalMessage(modelId, why))
    }
  }

  /**
   * Resolves the configured model to a [Model] (download URL + on-disk path populated). Prefers a
   * persisted [RelaisModelRef] — which needs no network and works for non-allowlist HF models —
   * and otherwise fetches the allowlist and matches [RelaisConfig.modelId]. Blocking. Throws with a
   * clear message if the allowlist can't be fetched or the configured id isn't in it.
   */
  fun resolveModel(context: Context): Model {
    val modelId = RelaisConfig.modelId(context)
    // #220 follow-up: gate the id THIS function actually resolves, in the same breath as reading it.
    // [ensureModel]'s gate reads the preference separately and earlier, so the two reads can disagree
    // — flip the selection mid-provision (ModelSwitch.applyRef while an earlier startup is still
    // running) and the id that gets resolved and DOWNLOADED is one no gate ever inspected. The #11
    // drift guard does not save us: it only declines to PERSIST the path, which is still returned and
    // still handed to engine init. Checking here closes that window by construction.
    //
    // This is also the ONLY compat gate on [RelaisEngine.ensureModelSwapInBackground]'s untargeted
    // path (`target == null`), which calls resolveModel directly and never passes through ensureModel.
    // Keep BOTH gates: this one cannot cover ensureModel's offline fast paths, which return before
    // resolveModel is ever reached.
    refuseIfIncompatible(modelId)
    // Ref fast path: a self-contained ref provisions any selected model offline. Gated to modelId
    // agreement so a bare id change (adb `--es modelId`) bypasses a stale ref and re-resolves the
    // new id via the allowlist below; RelaisConfig.setModelId also drops a diverged ref.
    RelaisConfig.modelRef(context)?.takeIf { it.modelId == modelId }?.let { ref ->
      Log.i(TAG, "Resolving from persisted ref (no allowlist): ${ref.modelId}/${ref.modelFile}")
      // preProcess() populates totalBytes (= sizeInBytes + extras), needed for download progress %.
      return modelFromRef(ref).also { it.preProcess() }
    }
    val url = allowlistUrl()
    // getJsonResponse sets connect/read timeouts, so a wedged network fails this fast on the
    // relais-init thread instead of hanging it indefinitely.
    Log.i(TAG, "Fetching allowlist $url to resolve modelId=$modelId")
    val allowlist =
      getJsonResponse<ModelAllowlist>(url)?.jsonObj
        ?: error("Could not fetch model allowlist from $url (offline?)")
    val allowed =
      allowlist.models.firstOrNull { it.modelId == modelId }
        ?: error("Model id '$modelId' not found in allowlist $url")
    return safeToModel(allowed, modelId, url).also { it.preProcess() }
  }

  /**
   * Wraps [AllowedModel.toModel] so that a null field left by Gson (which ignores Kotlin non-null
   * types) surfaces as an [IllegalStateException] — the existing `error(...)` contract — instead of
   * escaping as a raw [NullPointerException] on the `relais-init` thread. The boot path's upstream
   * handler already catches [IllegalStateException] and shows "Init failed: …"; a raw NPE bypasses
   * it and triggers a watchdog restart loop.
   *
   * Exposed as `internal` so the pure-JVM unit test can exercise the wrapping decision directly,
   * without needing a [Context] or network.
   */
  internal fun safeToModel(allowed: AllowedModel, modelId: String, url: String): Model =
    runCatching { allowed.toModel() }
      .getOrElse { e ->
        if (e is CancellationException) throw e
        error("Allowlist entry for '$modelId' is malformed ($url): ${e.message}")
      }

  /**
   * Builds a [Model] from a [RelaisModelRef] with no network. Reuses [AllowedModel.toModel] so the
   * download URL and on-disk layout are byte-identical to the allowlist path — a curated ref and
   * its allowlist entry resolve to the same file, so a model fetched one way is reused by the other.
   * Typed as a LiteRT-LM chat model (the only kind the node serves); the empty/default config is
   * fine because provisioning only needs the URL, file, version, and size.
   *
   * [Model.name] is the WorkManager unique-work key (`enqueueUniqueWork(model.name, …)`) and the
   * basis of the on-disk dir (`normalizedName`). Two arbitrary HF repos can share a trailing segment
   * (`org-a/gemma-2b` vs `org-b/gemma-2b`), so for HF refs the **full modelId** is the name — which
   * makes the work key unique. (`normalizedName` maps every non-alphanumeric to `_`, so the modelId
   * stays one safe dir segment, but it is NOT injective — `org-a/x` and `org_a/x` collide; on-disk
   * separation of distinct repos rests on the `{name}/{version}/…` layout, and `version` = the
   * commit, which differs.) Curated allowlist refs keep `displayName` so their `name`/path stay
   * byte-identical to the allowlist entry's `toModel()` and the "fetched one way, reused the other"
   * reuse holds.
   */
  internal fun modelFromRef(ref: RelaisModelRef): Model =
    AllowedModel(
        name = if (ref.source == RelaisModelRef.SOURCE_HUGGINGFACE) ref.modelId else ref.displayName,
        modelId = ref.modelId,
        modelFile = ref.modelFile,
        commitHash = ref.commitHash,
        description = "",
        sizeInBytes = ref.sizeInBytes,
        defaultConfig = DefaultConfig(null, null, null, null, null, null, null),
        taskTypes = listOf(BuiltInTaskId.LLM_CHAT),
        runtimeType = RuntimeType.LITERT_LM,
      )
      .toModel()

  /**
   * Returns the on-disk model path, downloading it via [DownloadWorker] if absent. Blocking — may
   * take minutes for a multi-GB model. [onProgress] receives 0..100 while downloading. For a
   * license-gated repo, set [RelaisConfig.setHfToken] first or the download 401s.
   */
  fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}): String {
    // Control-panel phase line (AUDIT.md §4.2/Q6): every path through this function starts in
    // RESOLVING — the offline fast paths below finish before ever reaching DOWNLOADING, so a node
    // that boots from a persisted/pre-staged model never shows a stale phase from a prior attempt.
    RelaisNodeProgress.phase = ProvisionPhase.RESOLVING
    // Fresh Pixel 10 (Tensor G5): default to the faster TPU-native E2B-G5 (see [G5_DEFAULT_REF])
    // before ANY provisioning. setModelRef clears the stale modelPath and sets modelId=E2B, so the
    // fast-paths below skip and the ref fast-path in resolveModel provisions E2B. Self-healing if
    // apply() races. (E4B is no longer blocked on G5 — this is a perf-preference default, not a gate.)
    applyDeviceDefaultIfFresh(context, isPixel10 = isPixel10())
    // Capture the id AFTER substitution so the issue-#11 drift guard doesn't see false drift
    // (the id is now E2B, and idAtStart must match for the persist gate to pass).
    val idAtStart = RelaisConfig.modelId(context)
    // Decided HERE, beside the capture, and never re-read further down. The staged-adoption branch
    // below adopts a file whose model identity is fixed by its NAME, so it may only ever be
    // remembered for DEFAULT_MODEL_ID; asking a fresh `RelaisConfig.modelId(context)` down there
    // would let the gate and the tag it authorises disagree, binding that file to another id.
    // Hoisting makes reintroducing that a visible new prefs call inside the branch, not a one-token
    // edit. Pinned by `the staged default file is not adopted for a non-default id` (the test flips
    // the id between this capture and the branch).
    val stagedFileIsAdoptable = idAtStart == RelaisConfig.DEFAULT_MODEL_ID
    // #220 follow-up: refuse a MEASURED-incompatible model HERE, before any fast path.
    //
    // NOT "the single chokepoint" — an earlier revision of this comment claimed that and was wrong.
    // This is the chokepoint for the NODE's provisioning path only. The upstream Gallery download
    // stack is a separate lane that never calls this function, and it has its own gate in
    // DefaultDownloadRepository.downloadModel; its resume path was still fetching known-bad models
    // on every launch after this one landed. If you add a third way to fetch a model, it needs its
    // own gate too — grep for callers of DownloadWorker and DownloadRepository, not just this file.
    //
    // Filtering RelaisModelCatalog only controlled what was OFFERED in the selector
    // and /v1/models; every other route into provisioning stayed open — a persisted ref from before
    // the model was known-bad, a ref built by HF search, a pre-staged file, and `adb --es modelId`,
    // which is literally issue #220's own reproduction command. All of them still downloaded
    // multiple GB and then died in engine-create, which is the exact symptom that issue exists to
    // prevent.
    //
    // Same lesson as the G5 default above: the chokepoint is ensureModel, NOT resolveModel — the
    // fast paths below return before resolveModel is ever reached, so a check there is bypassable.
    //
    // Keyed by repo id, matching RelaisRuntimeCompat's table. A repo entry therefore blocks EVERY
    // build in that repo: before marking a repo whose builds differ (Gemma3-1B-IT is the near miss
    // — its allowlist entry vs its Relais-pinned G5 AOT build), the table needs file-level keying.
    //
    // Paired with the gate inside resolveModel, which covers the case this one cannot: the id being
    // re-read there after an operator changes the selection mid-provision.
    refuseIfIncompatible(idAtStart)
    // Offline-safe fast path: a previously provisioned file still on disk needs no network. This
    // lets a rebooted / watchdog-restarted node boot without the allowlist when nothing to download.
    RelaisConfig.modelPath(context)?.let { saved ->
      if (File(saved).exists()) {
        Log.i(TAG, "Using persisted model path (no allowlist fetch needed): $saved")
        // Route through remember() rather than assigning cachedModelPath directly: this is the MOST
        // common start path (a node booting from an already-provisioned model), and skipping
        // remember() meant the #180 registry never learned about the resident model — /v1/models
        // reported it as not-provisioned and a request naming it would 404. Idempotent here: the
        // path is already persisted, so remember() only refreshes the cache and the registry.
        return remember(context, saved, persistForId = idAtStart)
      }
    }
    // Offline-safe fast path 2: a model an operator pre-staged at the conventional side-load
    // location ([RelaisEngine.defaultModelPath], e.g. pushed via adb into the app files dir) is
    // adopted as-is, so a fresh install whose model is already on disk boots LIVE without
    // re-downloading multiple GB over a slow link. Persisting it here means subsequent boots take
    // fast path 1 above. Gated to the default model id because that path's file name is the default
    // model's file specifically; a non-default id is resolved against the allowlist below instead.
    // Gated on idAtStart, NOT a fresh read: the tag below is idAtStart, and a gate that reads a
    // DIFFERENT id than the tag it authorises can bind this file to a model it does not hold. The
    // file's identity is DEFAULT_MODEL_ID by construction (its NAME is the default model's file —
    // the same reasoning resolveModelPath's rung 4 encodes), so the only id it may ever be
    // remembered for is that one. Every other branch in this function already tags with idAtStart.
    if (stagedFileIsAdoptable) {
      val staged = File(RelaisEngine.defaultModelPath(context))
      // length() > 0 (returns 0 when absent) also rejects an interrupted/empty `adb push` so a
      // 0-byte stub isn't adopted and then fails opaquely later in Engine init.
      if (staged.length() > 0L) {
        Log.i(TAG, "Adopting pre-staged model at default location (no download): ${staged.path}")
        return remember(context, staged.absolutePath, persistForId = idAtStart)
      }
      // If the staging dir exists but no app-readable model does, an operator likely side-loaded a
      // file the app can't read: an adb-pushed file is owned by `shell` with no "others" bit, so
      // File.length() reads 0 from the app uid and we silently fall through to a multi-GB download.
      // The app can't reliably stat the file itself in that state, so key the hint off the dir.
      if (staged.parentFile?.exists() == true) {
        Log.w(
          TAG,
          "Staging dir ${staged.parent} exists but no app-readable model — a side-loaded file may " +
            "be unreadable to the app (check perms: chmod 0644 the model, 0755 its dir). " +
            "Falling back to download.",
        )
      }
    }
    val model = resolveModel(context)
    val path = model.getPath(context)
    if (File(path).exists()) {
      Log.i(TAG, "Model already present, skipping download: $path")
      return remember(context, path, persistForId = idAtStart)
    }
    // #221: "absent" at [path] does NOT mean absent from the device. The on-disk directory is derived
    // from Model.name, and modelFromRef picks that name by PROVENANCE — modelId for a Hugging Face
    // ref, displayName for an allowlist entry — so one model id owns two possible directories with
    // byte-identical content. Checking only this route's path re-downloaded multiple GB of a model
    // the device already had (observed: 2.6 GB on rango). Probe the other route before concluding.
    siblingModelPath(context)
      ?.takeIf { it != path && File(it).exists() }
      ?.let { existing ->
        Log.i(TAG, "Adopting the existing copy at the sibling path (#221), no download needed: $existing")
        return remember(context, existing, persistForId = idAtStart)
      }
    model.accessToken = RelaisConfig.hfToken(context)
    Log.i(TAG, "Model absent; downloading ${model.name} from ${model.url} -> $path")
    download(context, model, onProgress)
    // No length check here (#363): DownloadWorker checks the .tmp against the server's declared size
    // before renaming it, which covers every lane — including a Gallery-lane worker whose file this
    // call would otherwise adopt unchecked via the "already present" branch above. A second check
    // here would only re-read the same number.
    require(File(path).exists()) { "Download reported success but file is missing: $path" }
    Log.i(TAG, "Model provisioned: $path")
    return remember(context, path, persistForId = idAtStart)
  }

  /**
   * The path this model would occupy had it been resolved via the OTHER provenance route (#221), or
   * null when there's no persisted ref to derive it from.
   *
   * [modelFromRef] keys the on-disk directory off `name`, and picks `name` by provenance alone —
   * `modelId` for [RelaisModelRef.SOURCE_HUGGINGFACE], `displayName` otherwise. Flipping just that
   * field therefore yields the sibling directory while reusing the exact same path construction, so
   * this can never drift from the real layout the way a hand-rolled second path would.
   *
   * Deliberately does NOT move, link, or delete anything: the goal is only to stop re-downloading a
   * model that is already here. Reclaiming the orphaned copy is a separate decision (#221 option 3).
   */
  internal fun siblingModelPath(context: Context): String? {
    val ref = RelaisConfig.modelRef(context)?.takeIf { it.modelId == RelaisConfig.modelId(context) } ?: return null
    val flipped =
      if (ref.source == RelaisModelRef.SOURCE_HUGGINGFACE) RelaisModelRef.SOURCE_ALLOWLIST
      else RelaisModelRef.SOURCE_HUGGINGFACE
    // Best-effort: a malformed ref must never fail a provision that would otherwise just download.
    return runCatching { modelFromRef(ref.copy(source = flipped)).getPath(context) }.getOrNull()
  }

  /**
   * True iff a freshly provisioned path may be persisted: no drift, or drift unknown.
   *
   * When [provisionedForId] is null the caller opted out of drift detection (preserves legacy
   * behavior). When set, the path is only persisted if the current model id still matches the one
   * captured at the start of provisioning — guarding against the race in issue #11 where the
   * operator changes the model mid-download and the stale path would otherwise poison the next boot.
   */
  internal fun shouldPersistPath(provisionedForId: String?, currentId: String): Boolean =
    provisionedForId == null || provisionedForId == currentId

  /**
   * Caches the resolved path in-memory (always — it's what this boot actually provisioned) and
   * persists it to [RelaisConfig] only if the model id hasn't drifted since provisioning started.
   * [persistForId] is the id captured at [ensureModel] entry and has **no default**: it is the
   * value the drift gate keys on AND the identity this file is remembered under, so a caller that
   * declines to state it cannot be answered with a fresh `RelaisConfig.modelId` read —
   * [recordProvisioned]'s KDoc forbids exactly that, and the old `?: currentId` fallback did it.
   */
  /**
   * Add (or refresh) this model in the provisioned registry (#180), pruning entries whose file has
   * since disappeared. Best-effort: a registry write must never fail a provision that succeeded —
   * the model IS on disk either way, and a missing registry entry only costs a swap opportunity.
   *
   * [id] MUST be the id [path] was actually provisioned FOR, never a fresh read of
   * [RelaisConfig.modelId] — see [remember]'s drift gate for why. A registry entry binds an id to a
   * file, and #180 makes that binding load-bearing: `swapTargetFor(id)` hands this exact path to
   * `ensureInitialized(modelPath = …, modelId = id)`, so a mismatched pair makes the node serve one
   * model's weights stamped with another model's id — permanently, since every later request for
   * that id then matches `residentModelId` and short-circuits to ServeResident.
   */
  private fun recordProvisioned(context: Context, path: String, id: String) {
    runCatching {
        val display = RelaisConfig.modelRef(context)?.takeIf { it.modelId == id }?.displayName ?: id
        val updated =
          upsertProvisioned(
            pruneMissingProvisioned(RelaisConfig.provisionedModels(context)) { File(it).exists() },
            ProvisionedModel(modelId = id, path = path, displayName = display),
          )
        RelaisConfig.setProvisionedModels(context, updated)
        Log.i(TAG, "Registry: ${updated.size} model(s) provisioned on device")
      }
      .onFailure { Log.w(TAG, "Could not update provisioned-model registry: ${it.message}") }
  }

  internal fun remember(context: Context, path: String, persistForId: String): String {
    // Read the current id once: it drives the gate AND the drift warning, and re-reading risks a
    // TOCTOU mismatch between the decision and the logged value.
    val currentId = RelaisConfig.modelId(context)
    // Tag the cache with the SAME id the persist gate below keys on, so the in-memory and durable
    // halves can never disagree about which model they describe. Still written unconditionally —
    // it is what this boot actually provisioned — but on drift that tag is the OUTGOING id, so a
    // read for the incoming model correctly misses instead of serving the wrong weights (#337).
    cachedModelPath = CachedModelPath(modelId = persistForId, path = path)
    if (shouldPersistPath(persistForId, currentId)) {
      RelaisConfig.setModelPath(context, path)
      // #180: the ONE funnel both the already-present and freshly-downloaded paths pass through, so
      // it is where the provisioned-model registry gains entries. Recording only on local success is
      // what keeps a per-request swap unable to originate a download.
      //
      // INSIDE the drift gate, and keyed on the id this path was provisioned FOR: the registry entry
      // and the persisted path are the same claim ("this id lives at this file"), so drift must
      // refuse both. Recording it above the gate — with a fresh read of RelaisConfig.modelId —
      // reintroduced the issue-#11 race one line before the guard that exists to stop it: an
      // operator switching models mid-download would bind the NEW id to the OLD model's file.
      recordProvisioned(context, path, persistForId)
    } else {
      Log.w(
        TAG,
        "Model id changed mid-provision (now $currentId, provisioned for $persistForId); " +
          "not persisting stale path",
      )
    }
    return path
  }

  /**
   * Where the weights for [modelId] live on this device.
   *
   * Replaces the former `cachedPathOrDefault(context)`, whose signature asked "which path?" without
   * an id in the question and so **failed open**: after a switch it answered with the outgoing
   * model's file (#337). The id is a required parameter for the same reason
   * [ModelSwitch.applyManualId]'s `resolvedPath` has no default — the caller must state which model
   * it means, because the wrong answer is silent.
   *
   * Callers wanting the operator's configured model pass `RelaisConfig.modelId(context)`; a swap
   * passes the target's id.
   */
  fun pathFor(context: Context, modelId: String): String? =
    resolveModelPath(
      modelId = modelId,
      // ONE read of the volatile field, passed by value. Reading it twice (once per field) is a
      // torn read: a write landing between the reads pairs the new id with the old path.
      cached = cachedModelPath,
      configuredId = RelaisConfig.modelId(context),
      persistedPath = RelaisConfig.modelPath(context),
      provisioned = RelaisConfig.provisionedModels(context),
      defaultPath = RelaisEngine.defaultModelPath(context),
      defaultModelId = RelaisConfig.DEFAULT_MODEL_ID,
      fileExists = { File(it).exists() },
    )

  /**
   * The pure resolution behind [pathFor] (#337) — unit-tested in `RelaisModelPathResolutionTest`.
   *
   * Precedence, each step gated on the answer actually belonging to [modelId]:
   *  1. the in-memory cache, **only when [cached]'s own id is [modelId]** — the tag is what makes a
   *     post-switch read miss instead of serving the outgoing model. It arrives as one value, not as
   *     an id and a path, so no caller can present a pair that never coexisted;
   *  2. the provisioned registry (#180), which is keyed by id and so can answer for ANY model on
   *     the device, including a swap target that is not the configured one;
   *  3. the persisted `KEY_MODEL_PATH`, **only when [modelId] is [configuredId]** — that pref holds
   *     a single un-keyed path and is cleared on every id change, so it describes the configured
   *     model and nothing else. Handing it to another id would re-create #337 one rung lower;
   *  4. [defaultPath], the conventional side-load location — **only for [defaultModelId]**. That
   *     path ends in one specific model's file name, so handing it to another id is the same defect
   *     as rungs 1–3 would be without their gates; [ensureModel]'s side-load adoption applies the
   *     identical gate for the identical reason. Returned UNCHECKED, because it is where a
   *     provision will write and a missing file there is reported by
   *     [RelaisEngine.ensureInitialized]'s `require`, which names the path.
   *
   * Returns **null** when nothing on this device is known to hold [modelId]'s weights. Null is the
   * honest answer and the caller must fail on it: the alternative — falling back to a file that
   * belongs to a different model — is #337 itself, one rung lower than where it was found.
   *
   * Steps 1–3 are skipped when the file is gone (a model deleted out from under the registry), so a
   * pruned entry degrades to the next source instead of returning a path that cannot load.
   *
   * [fileExists] has no default, deliberately: with one, a whole table of cases would silently run
   * against the real filesystem and stop discriminating.
   */
  internal fun resolveModelPath(
    modelId: String,
    cached: CachedModelPath?,
    configuredId: String,
    persistedPath: String?,
    provisioned: List<ProvisionedModel>,
    defaultPath: String,
    defaultModelId: String,
    fileExists: (String) -> Boolean,
  ): String? {
    val fromCache = cached?.takeIf { it.modelId == modelId }?.path
    val fromRegistry = swapTargetFor(modelId, provisioned)?.path
    val fromPrefs = persistedPath?.takeIf { modelId == configuredId }
    // [defaultPath] is a FILE NAME for one specific model, so it is id-bound like every rung above
    // it — see [ensureModel]'s identical gate on the side-load adoption path. Returned unchecked
    // (it is where a provision will write), but only for the model whose file it is.
    val fromDefault = defaultPath.takeIf { modelId == defaultModelId }
    return listOfNotNull(fromCache, fromRegistry, fromPrefs).firstOrNull(fileExists) ?: fromDefault
  }

  /**
   * The [DownloadWorker] input for [model], with the same [Data] contract as
   * [cc.grepon.relais.data.DefaultDownloadRepository.downloadModel]. Split out of [download] so a
   * test can fingerprint the REAL input for two builds of one id ([downloadSpecTag]).
   */
  internal fun downloadInput(model: Model): Data {
    val inputBuilder =
      Data.Builder()
        .putString(KEY_MODEL_NAME, model.name)
        .putString(KEY_MODEL_URL, model.url)
        .putString(KEY_MODEL_COMMIT_HASH, model.version)
        .putString(KEY_MODEL_DOWNLOAD_MODEL_DIR, model.normalizedName)
        .putString(KEY_MODEL_DOWNLOAD_FILE_NAME, model.downloadFileName)
        .putBoolean(KEY_MODEL_IS_ZIP, model.isZip)
        .putString(KEY_MODEL_UNZIPPED_DIR, model.unzipDir)
        .putLong(KEY_MODEL_TOTAL_BYTES, model.totalBytes)
    model.accessToken?.let { inputBuilder.putString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN, it) }
    return inputBuilder.build()
  }

  /**
   * Serialises query → decide → enqueue across this process's callers (#363): ModelsScreen and the
   * service's `relais-init` are the same process, so without it both could see "nothing queued" and
   * both enqueue. Held only for that step, never across the poll loop.
   */
  private val downloadLock = Any()

  /**
   * Downloads [model] via [DownloadWorker] under the unique-work key `model.name` (shared with
   * [cc.grepon.relais.data.DefaultDownloadRepository]) and blocks until the work is terminal.
   *
   * Single-flight (#363): an unfinished worker already fetching the IDENTICAL input
   * ([joinOrReplace]) is attached to, not replaced — REPLACE made ModelsScreen and the service
   * cancel each other. A different input (another build of the same id) still REPLACEs.
   */
  private fun download(context: Context, model: Model, onProgress: (Int) -> Unit) {
    val workManager = WorkManager.getInstance(context)
    val input = downloadInput(model)
    val specTag = downloadSpecTag(input)
    val request =
      OneTimeWorkRequestBuilder<DownloadWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(input)
        .addTag("modelName:${model.name}")
        .addTag(specTag)
        .build()

    val workId =
      synchronized(downloadLock) {
        val existing =
          workManager.getWorkInfosForUniqueWork(model.name).get().map {
            ExistingDownload(it.id, it.state, it.tags)
          }
        when (val join = joinOrReplace(specTag, existing)) {
          is DownloadJoin.Attach -> {
            Log.i(TAG, "Attaching to the in-flight download of ${model.name} (${join.id})")
            join.id
          }
          DownloadJoin.Replace -> {
            // Waited on (`result.get()`) INSIDE the lock: enqueue is asynchronous, and a second
            // caller's query must see this work, or it would REPLACE the download it should join.
            workManager.enqueueUniqueWork(model.name, ExistingWorkPolicy.REPLACE, request).result.get()
            request.id
          }
        }
      }

    val totalBytes = model.totalBytes
    var lastReceived = 0L
    var lastProgressAt = System.currentTimeMillis()
    var notRunningSince: Long? = null
    while (true) {
      val info: WorkInfo? = workManager.getWorkInfoById(workId).get()
      when (info?.state) {
        WorkInfo.State.SUCCEEDED -> {
          onProgress(100)
          return
        }
        WorkInfo.State.FAILED -> {
          val msg = info.outputData.getString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE) ?: "unknown error"
          error("Model download failed: $msg")
        }
        WorkInfo.State.CANCELLED -> error("Model download cancelled")
        WorkInfo.State.RUNNING -> {
          notRunningSince = null
          val received = info.progress.getLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, 0L)
          if (received > lastReceived) {
            lastReceived = received
            lastProgressAt = System.currentTimeMillis()
            RelaisNodeProgress.onDownloadProgress(received, totalBytes)
            if (totalBytes > 0) onProgress(((received * 100) / totalBytes).toInt().coerceIn(0, 99))
          }
        }
        else -> {
          // ENQUEUED / BLOCKED / null: the worker hasn't started yet (e.g. expedited quota spent →
          // deferred to regular scheduling). Keep the stall clock pinned to "now" so waiting-to-run
          // never counts as a stall — the stall timeout only measures a RUNNING job making no
          // progress. The wait itself is bounded separately (#363), from when it began.
          lastProgressAt = System.currentTimeMillis()
          if (notRunningSince == null) notRunningSince = lastProgressAt
        }
      }
      val now = System.currentTimeMillis()
      when (
        downloadWaitTimeout(
          runningIdleMs = now - lastProgressAt,
          notRunningMs = notRunningSince?.let { now - it } ?: 0L,
          stallTimeoutMs = STALL_TIMEOUT_MS,
          notStartedTimeoutMs = NOT_STARTED_TIMEOUT_MS,
        )
      ) {
        // Guard against a hung worker silently blocking the init thread forever: bail if a RUNNING
        // download makes no byte progress within the stall window. Legitimate slow downloads still
        // advance, so this only trips on a genuine stall (dead socket), not on size or queue wait.
        // An attached caller gets the same handling as the one that enqueued: both poll one id.
        DownloadWaitTimeout.STALLED -> {
          workManager.cancelWorkById(workId)
          error("Model download stalled (no progress for ${STALL_TIMEOUT_MS / 1000}s)")
        }
        // Not cancelled: the worker is not broken, only unscheduled, and may still run. A retry
        // attaches to it (same input) or finds the file, instead of restarting it from the queue.
        DownloadWaitTimeout.NOT_STARTED ->
          error(
            "Model download not started after ${NOT_STARTED_TIMEOUT_MS / 1000}s " +
              "(WorkManager state: ${info?.state ?: "none"}); left queued for a retry to pick up"
          )
        null -> Unit
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
  }

  private const val POLL_INTERVAL_MS = 1000L
  private const val STALL_TIMEOUT_MS = 120_000L
  // Far past a normal expedited-quota deferral (seconds to a few minutes), short enough that a caller
  // (the service's init thread, a ModelsScreen spinner) is not held indefinitely by a job the system
  // never schedules.
  private const val NOT_STARTED_TIMEOUT_MS = 600_000L
}

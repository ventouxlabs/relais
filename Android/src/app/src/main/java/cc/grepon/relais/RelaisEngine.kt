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
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONObject

private const val TAG = "RelaisEngine"
// 4096 (was 1024): the E-series models ship ekv4096 builds, and 1024 left no room for inlined
// document attachments + history + a reply. KV-cache memory grows linearly with this; fine on
// 12–16 GB devices. Prefill of near-full prompts is proportionally slower — that's inherent.
private const val MAX_NUM_TOKENS = 4096

// Default sampler params (used when a request omits them). topK has no OpenAI equivalent, so it is
// fixed; temperature/top_p/seed mirror the OpenAI request fields. Previous behavior was the hardcoded
// (64, 0.95, 1.0); these preserve it as the default while letting callers override per request.
private const val DEFAULT_TOP_K = 64
private const val DEFAULT_TOP_P = 0.95
private const val DEFAULT_TEMPERATURE = 1.0
private const val DEFAULT_SEED = 0

private val MISSING_ENCODER_REGEX = Regex("\\bTF_LITE_[A-Z0-9_]*ENCODER", RegexOption.IGNORE_CASE)

/**
 * Whether [t] is LiteRT-LM rejecting a model for lacking an optional image/audio encoder — the
 * signal that a model is text-only and the engine must be rebuilt without the vision/audio backends.
 * Anchored to the `NOT_FOUND` + `TF_LITE_*_ENCODER` token shape (not a bare "ENCODER" substring) so
 * an unrelated failure that merely mentions an encoder does NOT trigger a silent downgrade of a
 * multimodal model. Both known phrasings match: litertlm 0.11 "Failed to create engine: NOT_FOUND:
 * TF_LITE_VISION_ENCODER…" (at initialize) and 0.13 "…NOT_FOUND: TF_LITE_AUDIO_ENCODER_HW…" (at
 * createConversation).
 */
internal fun isMissingEncoder(t: Throwable): Boolean {
  val m = t.message ?: return false
  return m.contains("NOT_FOUND", ignoreCase = true) && MISSING_ENCODER_REGEX.containsMatchIn(m)
}

/** Which runtime/accelerator serves a request. See [BackendSelector]. */
enum class RelaisBackend {
  GPU_LITERTLM, // resident litertlm Engine on the GPU — full multimodal (text+image+audio)
  NPU_AICORE, // AICore/Gemini Nano on the NPU — image+text only, Pixel 10+ only (UNVERIFIED here)
  // Resident litertlm Engine on the Google Tensor TPU via libLiteRtDispatch_GoogleTensor.so —
  // dispatcher-gated (NOT AICore-gated), G5-AOT-compiled models only. Proven on rango:
  // T-2 (power-rail execution proof) + T-3 (8.51 tok/s vs 3.03 GPU). See docs/tensor-tpu-spike-plan.md.
  TPU_LITERTLM,
  // The accelerator is not known to the caller — used by the in-app chat's HTTP transport, which
  // talks to the loopback server and can't tell which backend served the reply from the SSE stream.
  UNKNOWN,
}

/** Modalities present in an inbound request. */
data class RequestModalities(val hasImage: Boolean, val hasAudio: Boolean)

/** A multimodal request: text plus optional image (PNG bytes) and audio (WAV bytes). */
data class RelaisRequest(
  val text: String,
  val imagePng: ByteArray? = null,
  val audioWav: ByteArray? = null,
  /** System prompt extracted from the OpenAI messages[] array. Null for the /generate path. */
  val systemPrompt: String? = null,
  /**
   * Prior conversation turns (oldest first) extracted from the OpenAI messages[] array.
   * Empty for the /generate path. Seeded into the LiteRT-LM conversation as
   * [ConversationConfig.initialMessages] in [RelaisEngine.generate] — prefilled as context, not
   * replayed turn-by-turn, so a multi-turn request costs one decode rather than one per turn.
   */
  val history: List<ParsedTurn> = emptyList(),
  /** OpenAI `tools` to advertise to the model. Empty for the non-tool path. */
  val tools: List<ToolSpec> = emptyList(),
  /** OpenAI `tool_choice`. Carried through; only [ToolChoice.None] suppresses tools (HTTP layer). */
  val toolChoice: ToolChoice = ToolChoice.Auto,
  /** Trailing tool-result turn (from a `role:"tool"` run) that drives a tool round-trip. */
  val toolResults: List<ToolResult> = emptyList(),
  /** OpenAI `temperature` (0.0–2.0). Null = unspecified -> engine default ([DEFAULT_TEMPERATURE]). */
  val temperature: Double? = null,
  /** OpenAI `top_p` (0.0–1.0). Null = unspecified -> engine default ([DEFAULT_TOP_P]). */
  val topP: Double? = null,
  /** Sampling seed for reproducibility. Null = unspecified -> [DEFAULT_SEED]. */
  val seed: Int? = null,
  /**
   * When true, request the model's reasoning ("thinking") channel: the engine passes
   * `extraContext["enable_thinking"]="true"` and captures the separate `message.channels["thought"]`
   * stream into [RelaisResult.reasoning] (surfaced as OpenAI `reasoning_content`). Derived from the
   * OpenAI `reasoning_effort` field (absent/"none" -> false). Default false = today's behavior (no
   * thinking, no latency tax). Honored on the streaming text path only; the tool and
   * structured-output paths ignore it (v1).
   */
  val enableThinking: Boolean = false,
  /**
   * When true (the `x_relais_node_tools` opt-in), the NODE executes a curated set of safe built-in
   * tools itself in a single hop (rag_search, calculator, current_datetime, unit_convert) rather than
   * only emitting the call for the client. Default false. See `cc.grepon.relais.nodetools` (#9).
   */
  val nodeToolsEnabled: Boolean = false,
) {
  val modalities: RequestModalities
    get() = RequestModalities(hasImage = imagePng != null, hasAudio = audioWav != null)

  /** Resolves the per-request [SamplerConfig], clamping to valid ranges and applying defaults. */
  @OptIn(ExperimentalApi::class)
  fun samplerConfig(): SamplerConfig =
    SamplerConfig(
      topK = DEFAULT_TOP_K,
      topP = (topP ?: DEFAULT_TOP_P).coerceIn(0.0, 1.0),
      temperature = (temperature ?: DEFAULT_TEMPERATURE).coerceIn(0.0, 2.0),
      seed = seed ?: DEFAULT_SEED,
    )
}

/** Result of one inference. */
data class RelaisResult(
  val text: String,
  val backend: RelaisBackend,
  val decodeTokensPerSec: Double,
  /**
   * Raw decode token count from the onMessage callback loop (completion_tokens in OpenAI usage).
   * Zero for the NPU/AICore path which does not expose per-token callbacks (UNVERIFIED path).
   *
   * NOTE: on a cooperative cancel — thermal truncate (via [ThermalGovernor.shouldTruncate]) OR a
   * broken pipe in the onToken lambda — this counter reflects all tokens the engine DECODED, which
   * may exceed those the client actually received over its SSE stream. Both causes skew the count;
   * only the thermal case is additionally surfaced via [finishReason] = [RelaisFinishReason.LENGTH]
   * (issue #22). The broken-pipe case is not (no client remains to read it).
   */
  val completionTokens: Int,
  /**
   * Tool calls the model emitted on the blocking tool path (empty on the streaming/text paths).
   * Populated only when [RelaisRequest.tools]/[RelaisRequest.toolResults] route through the
   * dedicated blocking tool branch in [RelaisEngine.generate].
   */
  val toolCalls: List<ParsedToolCall> = emptyList(),
  /**
   * Accumulated model reasoning from the `message.channels["thought"]` side-channel, when the request
   * opted in via [RelaisRequest.enableThinking]. Null when thinking was off or the model emitted no
   * reasoning. Surfaced as OpenAI `reasoning_content`; NOT included in [completionTokens] (reasoning
   * tokens are decoded but are not visible-answer tokens).
   */
  val reasoning: String? = null,
  /**
   * OpenAI `finish_reason` for this completion. Default [RelaisFinishReason.STOP] (natural end);
   * [generate] sets it to [RelaisFinishReason.LENGTH] when a thermal cooperative-cancel truncated the
   * decode (issue #22). Best-effort: LENGTH is reported only when a decode callback observes the
   * cancel before the native run's natural end (the decode is not natively interruptible) — see
   * [RelaisFinishReason]. The blocking tool path ([generateWithToolsLocked]) leaves the default — it has no
   * per-token truncation seam; the HTTP layer derives `"tool_calls"` from [toolCalls] when present.
   */
  val finishReason: String = RelaisFinishReason.STOP,
  /**
   * Seconds from conversation creation to the first visible token the CLIENT COULD OBSERVE — the
   * user-visible wait for the node to say anything, with queue wait and the thermal cool-down
   * excluded (both happen before the conversation is created).
   *
   * Null, never 0.0 — a zero would be a fabricated measurement:
   *  - on paths with no per-token callback at all: the blocking tool lane
   *    ([generateWithToolsLocked]) and AICore;
   *  - when no visible token was decoded (e.g. a cancel during the thinking phase);
   *  - when streaming and the first visible token never reached the consumer — a cooperative
   *    cancel that returned before the write, or a broken pipe that threw during it.
   *
   * Note this is NOT simply "a visible token was decoded": decoding also starts the throughput
   * window, which deliberately counts tokens the client may never receive. See [RelaisTtftTracker]
   * for why the two differ, and why the non-streaming path counts the reply-buffer append as
   * delivery.
   */
  val timeToFirstTokenSec: Double? = null,
  /**
   * Same endpoint as [timeToFirstTokenSec] but measured from `sendMessageAsync`, so it excludes
   * conversation creation. The difference between the two IS the system-prompt/history prefill.
   * Null under exactly the same conditions, so the two series stay comparable.
   */
  val decodeStartLatencySec: Double? = null,
)

/**
 * Modality-aware backend selector (Gate 4).
 *
 * Rule, from SPIKE-FINDINGS.md:
 *  - audio present            -> GPU_LITERTLM (AICore/Nano cannot do audio)
 *  - image/text + AICore avail -> NPU_AICORE (Pixel 10+ only)
 *  - otherwise                -> GPU_LITERTLM
 *
 * On the Pixel 9 the AICore branch is UNVERIFIED: [aicoreAvailable] is gated to false until a
 * Pixel 10 is in hand to wire and validate the real ML Kit `checkStatus()` probe.
 */
object BackendSelector {
  fun select(modalities: RequestModalities, aicoreAvailable: Boolean): RelaisBackend =
    when {
      modalities.hasAudio -> RelaisBackend.GPU_LITERTLM
      aicoreAvailable -> RelaisBackend.NPU_AICORE
      else -> RelaisBackend.GPU_LITERTLM
    }

  /**
   * Whether the AICore/Gemini-Nano NPU path is usable on this device, via a real ML Kit
   * `checkStatus()` probe ([RelaisAicore.available]). Returns false on the Pixel 9 (not in the
   * AICore device groups), so the GPU path always wins here; lights up on a Pixel 10.
   */
  fun aicoreAvailable(context: Context): Boolean = RelaisAicore.available(context)
}

/**
 * Process-wide holder for the single resident multimodal engine (Gate 1).
 *
 * One [Engine] is initialized once on the GPU (vision=GPU, audio=CPU) and kept alive for the
 * lifetime of [RelaisNodeService]. [generate] creates a short-lived conversation per request
 * against that resident engine, so the model is never re-loaded.
 */
object RelaisEngine {
  @Volatile private var engine: Engine? = null
  private val lock = Any()

  /**
   * Wall-clock ms of the last "activity": the engine became ready, or a request finished being
   * served. Drives idle-TTL auto-unload (#178) — see [releaseIfIdle] / [shouldUnloadIdleEngine].
   * Written under [lock] on init (in [ensureInitialized]) and unconditionally in [generate]'s outer
   * `finally` (every outcome — success, timeout, error — counts as activity, matching how
   * [RelaisMetrics.recordLatency] already treats "every outcome" elsewhere in this file).
   */
  @Volatile private var lastActivityAtMs: Long = System.currentTimeMillis()

  /**
   * True while the node is provisioning/initializing in this process (e.g. a first-run multi-GB
   * model download). The watchdog uses this to distinguish "still coming up" from "dead", so a slow
   * first start is not mistaken for a failure (no backoff escalation, no premature alarm).
   */
  @Volatile var startupInProgress: Boolean = false

  /**
   * True iff the engine's current not-ready state is a graceful idle-TTL unload ([releaseIfIdle],
   * #178), not a crash. [RelaisWatchdogReceiver] checks this BEFORE treating `!isReady` as a
   * failure — without it, the watchdog's own ~60s heartbeat would see the freshly-unloaded engine,
   * conclude the node is dead, escalate its failure-backoff step, and force a restart, undoing the
   * idle-unload within about one poll cycle (discovered auditing every [isReady] call site; see
   * #178 review). Set true by [releaseIfIdle] right after a successful [shutdown]; cleared by
   * [ensureInitialized] on the next successful init (any reason — idle-TTL, watchdog, an ordinary
   * request — restores normal "not ready" semantics once a real init attempt is underway).
   */
  @Volatile var wasIdleUnloaded: Boolean = false

  /** Guards [ensureInitializedInBackground] so a burst of requests during an idle-reload dispatches at most one thread. */
  private val backgroundReloadDispatching = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * Consecutive [shutdown] failures ([Engine.close] threw). Read by [shouldUnloadIdleEngine] as a
   * circuit breaker: after repeated close failures, idle-TTL stops attempting further auto-unloads
   * rather than silently repeating a possibly-leaking teardown forever (#178 review). Reset to 0 on
   * any successful close.
   */
  @Volatile var consecutiveCloseFailures: Int = 0
    private set

  /**
   * True when the most recent init attempt threw (set by [RelaisNodeService]'s init thread). Lets
   * [cc.grepon.relais.core.computeNodeState] surface ERROR rather than an indefinite STARTING when
   * provisioning/init fails. Cleared on a successful init.
   */
  @Volatile var lastInitFailed: Boolean = false

  /**
   * True iff the resident engine initialized with the FULL multimodal config (vision+audio
   * encoders). False on the text-only fallback path (model exposes no image/audio encoder, e.g.
   * Qwen3) and before any successful init. Read by [RelaisDiscovery] (mDNS `caps`) and the
   * `/v1/clientconfig` endpoint to advertise the model's true modality. Set truthfully in
   * [buildResidentEngine] — never assumed.
   */
  @Volatile
  var isMultimodal: Boolean = false
    private set

  /**
   * True iff the resident engine initialized on the Tensor TPU ([RelaisBackend.TPU_LITERTLM]):
   * the model file is Google-Tensor AOT-compiled AND the dispatcher lib is bundled (debug builds,
   * via scripts/fetch-tensor-dispatcher.sh). Set truthfully in [buildResidentEngine]. Requests then
   * report TPU_LITERTLM and run the engine-default sampler (the NPU executor crashes under a custom
   * [SamplerConfig] — see [RelaisTpuLane.requestUsesCustomSampler]).
   */
  @Volatile
  var residentIsTpu: Boolean = false
    private set

  /**
   * The model id actually resolved/loaded by the most recent successful [ensureInitialized] — null
   * before any successful init. The source of truth for "is the resident engine serving what a
   * request just asked for" (#180): compared against an inbound request's `model` field via
   * [resolveModelRequest] to decide whether a single-slot swap is needed. Set unconditionally on every
   * successful init (idle-reload, an ordinary request, or [ensureModelSwapInBackground]) — never
   * assumed.
   */
  @Volatile
  var residentModelId: String? = null
    private set

  /**
   * On-disk path of the resident engine, tracked alongside [residentModelId] so a FAILED swap can
   * reload what was working. Without it, a swap that shuts the old engine down and then fails to
   * initialize the new one leaves the node with no engine at all.
   */
  @Volatile
  var residentModelPath: String? = null
    private set

  /** Guards [ensureModelSwapInBackground] so a burst of mismatched requests dispatches at most one swap thread. */
  private val swapDispatching = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * Legacy hardcoded model location from the spike (manually side-loaded; see SPIKE-FINDINGS.md).
   * Now only a fallback — the node self-provisions to [Model.getPath]'s layout via
   * [RelaisModelProvisioner], and [ensureInitialized] defaults to that resolved path.
   */
  fun defaultModelPath(context: Context): String =
    File(context.getExternalFilesDir(null), "relais/gemma-4-E4B-it.litertlm").absolutePath

  val isReady: Boolean
    get() = engine?.isInitialized() == true

  /**
   * Idempotent. Initializes the resident GPU multimodal engine if not already up. Defaults to the
   * path resolved by [RelaisModelProvisioner] (falling back to [defaultModelPath] pre-provision).
   */
  @OptIn(ExperimentalApi::class)
  fun ensureInitialized(
    context: Context,
    modelPath: String = RelaisModelProvisioner.cachedPathOrDefault(context),
    modelId: String = RelaisConfig.modelId(context),
  ) {
    if (isReady) return
    synchronized(lock) {
      if (isReady) return
      require(File(modelPath).exists()) { "Model not found: $modelPath" }
      // NOTE: the former Pixel-10/Tensor-G5 pre-flight gate that refused gemma-4-E4B is gone —
      // E4B was verified to init + serve (text, sustained decode, and vision) on G5 with no SIGSEGV
      // on litertlm 0.12.0 (2026-07-12, on rango), so the model×SoC crash it guarded is resolved.
      val cacheDir = context.getExternalFilesDir(null)?.absolutePath
      // Speculative decoding is SUPPORTED by E4B (Capabilities.hasSpeculativeDecodingSupport()=true)
      // but MEASURED A REGRESSION on this E4B/GPU/Tensor-G4 config: ~2.56 tok/s with it on vs
      // ~5.63 tok/s off (draft overhead > gains, no draft model bundled). Left OFF deliberately.
      ExperimentalFlags.enableSpeculativeDecoding = false
      engine = buildResidentEngine(modelPath, cacheDir, context.applicationInfo.nativeLibraryDir)
      residentModelId = modelId // #180: the source of truth for what the resident engine is serving
      residentModelPath = modelPath
      lastActivityAtMs = System.currentTimeMillis() // idle-TTL clock (#178): init counts as activity
      wasIdleUnloaded = false // a real init attempt is underway; restore normal not-ready semantics
    }
  }

  /**
   * Kicks a background reload if the engine isn't already ready or already coming up — an idempotent
   * no-op otherwise, and safe to call from any thread without blocking it on the reload itself.
   *
   * Exists for request paths recovering from an idle-TTL unload (#178) that must not cold-start a
   * multi-GB model synchronously on the calling thread: [RelaisHttpServer]'s audio-to-text handler
   * (mirrors the async-provisioning-kick shape `/v1/embeddings` already uses) and
   * [cc.grepon.relais.core.RelaisInference] (only safe to call when [wasIdleUnloaded] is true — that
   * specifically proves the foreground service is alive right now, since idle-TTL only runs from
   * within it; [RelaisInference]'s own contract must still never blind-cold-start when the engine was
   * simply never initialized, since then the service might not be running at all).
   */
  fun ensureInitializedInBackground(context: Context) {
    if (isReady || startupInProgress) return
    if (!backgroundReloadDispatching.compareAndSet(false, true)) return // a reload is already dispatching
    thread(name = "relais-idle-reload") {
      try {
        startupInProgress = true // tell the watchdog "coming up", not "dead"
        ensureInitialized(context)
      } catch (e: Exception) {
        Log.w(TAG, "background idle-reload failed: ${e.message}")
      } finally {
        startupInProgress = false
        backgroundReloadDispatching.set(false)
      }
    }
  }

  /**
   * Kicks a background swap to [target] — the model the REQUEST named, resolved from
   * [RelaisModelRegistry] so its on-disk path is already known and no network is needed (#180,
   * single-slot swap-on-mismatch). [target] is null only when the caller could not find a registry
   * entry, i.e. the operator's configured selection has not been recorded yet; the configured model
   * is then resolved the long way, which may block on the allowlist. Single-flight (a burst of mismatched
   * requests dispatches at most one swap thread) — mirrors [ensureInitializedInBackground]'s
   * [backgroundReloadDispatching] pattern with its own dedicated guard, since the two can legitimately
   * race independently (an idle-reload and a swap are different triggers).
   *
   * Reuses [startupInProgress] for watchdog safety rather than inventing a parallel flag: it's the
   * same "still coming up, not dead" signal every existing not-ready window already relies on (see
   * [RelaisWatchdogReceiver]), and the swap's not-ready window — between [shutdown] and the next
   * successful [ensureInitialized] — IS exactly that case. Exhaustively grepped every call site of
   * `isReady`/`startupInProgress`/`wasIdleUnloaded` before adding this (#180 handoff); none of them
   * distinguish "coming up from a cold start" from "coming up from a swap" and none need to.
   *
   * Resolves the swap target BEFORE closing the old engine: a failed/offline resolve (e.g. the
   * allowlist fetch in [RelaisModelProvisioner.resolveModel] fails, or the resolved file isn't on
   * disk) leaves the OLD engine resident and serving, rather than tearing it down speculatively on a
   * request that turns out to name an unresolvable model — this node must never end up with ZERO
   * resident engine because one bad request triggered a swap attempt. Only [shutdown] + the actual
   * reload happen once the new model is confirmed present on disk.
   */
  fun ensureModelSwapInBackground(context: Context, target: ProvisionedModel? = null) {
    if (!swapDispatching.compareAndSet(false, true)) return // a swap is already dispatching
    thread(name = "relais-model-swap") {
      try {
        startupInProgress = true // tell the watchdog "coming up", not "dead" (same signal as any init)
        // Captured ONCE, before resolveModel runs, so the id stamped on the reloaded engine can never
        // drift from an operator config change happening mid-swap (#180 review, MEDIUM finding 2):
        // resolveModel() internally re-reads RelaisConfig.modelId(context) itself (its signature only
        // takes context, so that internal read can't be avoided), but THIS function must not read the
        // preference a second time after resolveModel() returns — reusing configuredModelId for the
        // final ensureInitialized() call guarantees at least this function's own view of "which model
        // id" stays single-sourced for the rest of the swap.
        // #180 (full): [target] names the model the REQUEST asked for, which is not necessarily the
        // operator's configured one now that any provisioned model is swap-eligible. Loading the
        // configured model here regardless — as the first cut did, correctly, because its guard made
        // requested == configured by construction — would 503 "swapping", reload the SAME model, and
        // 503 the client's retry forever. A targeted swap needs no network: the registry already
        // holds the on-disk path.
        val configuredModelId = target?.modelId ?: RelaisConfig.modelId(context)
        val path =
          target?.path
            ?: RelaisModelProvisioner.resolveModel(context).getPath(context) // blocking, may hit network
        if (!File(path).exists()) {
          Log.w(TAG, "model swap target not provisioned on disk: $path — leaving resident engine untouched")
          return@thread
        }
        // Atomic swap (#180 review, HIGH finding): close+reload under ONE lock acquisition so no
        // concurrent generate()/ensureInitialized() call can interleave in the gap between shutdown()
        // and the reload and observe (or build from) a half-swapped state — e.g. the OLD cached model
        // path with the NEW model id stamped over it. synchronized(lock) is reentrant on the JVM, and
        // shutdown()/ensureInitialized() both synchronize on this exact same `lock` field, so nesting
        // them here is safe: any other caller blocks on `lock` for the FULL close+reload, then sees the
        // fully-updated engine/residentModelId together.
        synchronized(lock) {
          // Capture what is working BEFORE tearing it down. `File.exists()` is the only pre-check we
          // can do — whether a model actually LOADS is unknowable until init runs — and the allowlist
          // ships models that download fine but fail engine-create (e.g. Qwen2.5-1.5B on litertlm
          // 0.12.0: "Failed to parse LlmMetadata"). #180 makes that reachable by request, since any
          // provisioned model is now a legal swap target. Without this rollback, one such request
          // would take a healthy node down until an operator restarted it.
          val previousPath = residentModelPath
          val previousId = residentModelId
          shutdown() // close the OLD engine only now that the NEW one is confirmed present on disk
          try {
            ensureInitialized(context, modelPath = path, modelId = configuredModelId)
          } catch (t: Throwable) {
            Log.w(TAG, "swap to $configuredModelId failed (${t.message}); restoring $previousId")
            if (previousPath != null && previousId != null) {
              runCatching { ensureInitialized(context, modelPath = previousPath, modelId = previousId) }
                .onFailure { Log.e(TAG, "could not restore previous model $previousId: ${it.message}") }
            }
            // Deliberately NOT rethrown. The failure is fully handled here — rolled back and logged —
            // and this is a bare thread: the outer handler catches Exception, so an Error (a native
            // engine-create can surface UnsatisfiedLinkError or OutOfMemoryError, and #180 makes
            // models that fail to load reachable by request) would escape it, hit Android's default
            // uncaught handler, and kill the WHOLE node process — moments after the rollback saved it.
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "model swap failed: ${e.message}")
      } finally {
        startupInProgress = false
        swapDispatching.set(false)
      }
    }
  }

  /**
   * Builds the resident engine, adapting the config to the model's actual modalities. Tries the
   * full multimodal config first (vision=GPU, audio=CPU); if the model exposes no image/audio
   * encoder ([createConversation] -> NOT_FOUND ..._ENCODER), rebuilds text-only. This lets the node
   * serve text-only models (e.g. Qwen3) as well as multimodal ones (gemma-4-E4B) without the
   * operator declaring modalities, and a non-encoder failure propagates (no silent downgrade of a
   * multimodal model). The probe creates+closes a conversation but runs NO inference, so it does not
   * trigger the gemma-4-E4B/G5 decode crash (guarded separately above).
   *
   * Two-rung ladder only (full multimodal | text-only): a single-modality model (vision-only or
   * audio-only) is requested both encoders, fails the missing one, and degrades to text-only. That is
   * acceptable today (gemma-4-E4B has both, Qwen3 has neither); add intermediate rungs if a
   * single-encoder model ever ships.
   */
  @OptIn(ExperimentalApi::class)
  private fun buildResidentEngine(modelPath: String, cacheDir: String?, nativeLibDir: String?): Engine {
    // TPU lane (T-4): dispatcher-gated, NOT AICore-gated — a Google-Tensor AOT-compiled model plus
    // libLiteRtDispatch_GoogleTensor.so in nativeLibraryDir. AOT models carry a FIXED KV size
    // (ekv marker) that maxNumTokens must match. Anything else stays on the proven GPU lane.
    val fileName = File(modelPath).name
    val tpu =
      RelaisTpuLane.isTpuCompiledModel(fileName) &&
        nativeLibDir != null &&
        File(nativeLibDir, RelaisTpuLane.DISPATCHER_LIB).exists()
    if (RelaisTpuLane.isTpuCompiledModel(fileName) && !tpu) {
      Log.w(TAG, "Model is Google-Tensor AOT-compiled but the TPU dispatcher lib is absent — GPU lane")
    }
    residentIsTpu = tpu
    // Non-null iff the TPU lane is selected (tpu implies nativeLibDir != null), so the NPU backend
    // can be built without a `!!` — a null-check smart-cast instead (Kotlin no-`!!` rule).
    val npuLibDir: String? = if (tpu) nativeLibDir else null
    val backend = if (npuLibDir != null) Backend.NPU(nativeLibraryDir = npuLibDir) else Backend.GPU()
    val maxTokens = if (tpu) RelaisTpuLane.tpuMaxNumTokens(fileName, MAX_NUM_TOKENS) else MAX_NUM_TOKENS
    if (tpu) Log.i(TAG, "TPU lane selected for $fileName (maxNumTokens=$maxTokens)")
    // TPU lane: visionBackend must be NPU too — an AOT model probed with visionBackend=GPU fails
    // with "Input tensor not found" (NOT classified as a missing encoder, so the text-only rung
    // never runs); with visionBackend=NPU a text-only AOT model fails correctly with
    // "TF_LITE_VISION_ENCODER not found" and degrades (matches the official sample_app_tpu).
    val multimodal =
      EngineConfig(
        modelPath = modelPath,
        backend = backend,
        visionBackend = if (npuLibDir != null) Backend.NPU(nativeLibraryDir = npuLibDir) else Backend.GPU(),
        audioBackend = Backend.CPU(),
        maxNumTokens = maxTokens,
        cacheDir = cacheDir,
      )
    buildIfModelAccepts(multimodal, "multimodal")?.let {
      isMultimodal = true // full multimodal config is the one that initialized
      return it
    }
    Log.i(TAG, "Model has no image/audio encoder; rebuilding a text-only engine")
    val textOnly =
      EngineConfig(
        modelPath = modelPath,
        backend = backend,
        maxNumTokens = maxTokens,
        cacheDir = cacheDir,
      )
    isMultimodal = false // text-only fallback: model exposes no image/audio encoder
    return buildIfModelAccepts(textOnly, "text-only")
      ?: error("Engine init failed for $modelPath")
  }

  /**
   * Initializes an [Engine] for [config] and probes it with a throwaway conversation. Returns the
   * ready engine, or null iff the model rejects this config for a missing image/audio encoder
   * ([isMissingEncoder]) — the caller then retries with fewer backends. Any other error is rethrown.
   */
  @OptIn(ExperimentalApi::class)
  private fun buildIfModelAccepts(config: EngineConfig, label: String): Engine? {
    Log.i(TAG, "Initializing resident $label engine from ${config.modelPath}")
    val e = Engine(config)
    return try {
      // A model lacking a requested image/audio encoder is rejected either at initialize() (litertlm
      // 0.11 — "Failed to create engine: NOT_FOUND: TF_LITE_VISION_ENCODER") or at createConversation
      // (0.13 — audio encoder), so wrap both. The probe runs NO inference, so it cannot trigger the
      // gemma-4-E4B/Tensor-G5 decode crash (gated separately in ensureInitialized).
      e.initialize()
      // Default ConversationConfig: the probe runs no decode — it only forces litertlm to resolve
      // the requested encoders so a text-only model is detected before the config is committed.
      // (Deliberately no custom SamplerConfig: the NPU compiled-model executor rejects one, T-3.)
      e.createConversation(ConversationConfig()).close()
      Log.i(TAG, "Resident $label engine ready: ${e.isInitialized()}")
      e
    } catch (t: Throwable) {
      runCatching { e.close() }.onFailure { Log.w(TAG, "Failed to close rejected $label probe engine", it) }
      if (isMissingEncoder(t)) {
        Log.w(TAG, "$label config rejected (model lacks an encoder): ${t.message}")
        null
      } else {
        throw t
      }
    }
  }

  /**
   * Runs one request against the resident engine. Routes via [BackendSelector]. If [onToken] is
   * provided, each decoded delta is delivered as it streams (used for SSE / chunked HTTP).
   */
  @OptIn(ExperimentalApi::class)
  fun generate(
    context: Context,
    request: RelaisRequest,
    onToken: ((String) -> Unit)? = null,
    shouldCancel: (() -> Boolean)? = null,
    onReasoning: ((String) -> Unit)? = null,
  ): RelaisResult {
    RelaisMetrics.incInFlight() // counts both queued (waiting on lock) and running -> queue_depth
    val reqStartNs = System.nanoTime()
    try {
      val requested = BackendSelector.select(request.modalities, BackendSelector.aicoreAvailable(context))

      // NPU path (Pixel 10+): Gemini Nano via AICore, image/text only. UNVERIFIED on Pixel 9 —
      // never selected here because aicoreAvailable() is false.
      // completionTokens = 0: AICore does not expose per-token callbacks; usage block will show
      // completion_tokens = 0 for this path until a token-counting API is available.
      if (requested == RelaisBackend.NPU_AICORE) {
        val text = RelaisAicore.generate(request)
        onToken?.invoke(text)
        return RelaisResult(text = text, backend = requested, decodeTokensPerSec = 0.0, completionTokens = 0)
      }

      val contents = buildList {
        request.imagePng?.let { add(Content.ImageBytes(it)) }
        request.audioWav?.let { add(Content.AudioBytes(it)) }
        if (request.text.isNotBlank()) add(Content.Text(request.text))
      }

      synchronized(lock) {
        // Resolve (and, if needed, lazily re-init) the resident engine INSIDE the same [lock] that
        // idle-TTL unload ([releaseIfIdle], #178) holds while releasing it. This is what makes the
        // unload race-free: [releaseIfIdle] can only null out `engine` while holding `lock`, so a
        // request either (a) is already past this point, holding a live `e` for the rest of this
        // block — in which case RelaisMetrics.queueDepth() (bumped by incInFlight() above, BEFORE
        // this block) is already >0 and releaseIfIdle's own re-check under `lock` aborts the unload
        // before it ever nulls `engine`; or (b) hasn't reached this point yet, in which case it just
        // blocks on `lock` until any in-progress unload finishes, then re-inits lazily right here
        // (ensureInitialized() below is idempotent/cheap when already ready, and does a real —
        // cold-start — init when not). Either way no request ever observes/uses a closed `engine`.
        ensureInitialized(context)
        val e = engine ?: error("Engine not initialized")
        // The resident engine's TRUE lane: after init, a Google-Tensor AOT model on the bundled
        // dispatcher reports TPU_LITERTLM (T-4) — the selector can't know this before init.
        val backend = if (residentIsTpu) RelaisBackend.TPU_LITERTLM else requested

        // Tool path: a request advertising tools OR carrying tool results round-trips through the
        // BLOCKING sendMessage API (not streaming) so reply.toolCalls is populated. Already holding
        // `lock` (caller, not this callee, per the #178 restructure above) + same thermal-cooldown +
        // RelaisMetrics scaffolding as the streaming path below.
        if (request.tools.isNotEmpty() || request.toolResults.isNotEmpty()) {
          return generateWithToolsLocked(e, request, backend)
        }

        // Thermal cool-down spaces *actual* decode runs: applied under the lock, not per-request on
        // the worker pool, so concurrent requests don't all sleep in parallel and then serialize.
        ThermalGovernor.cooldownMs().takeIf { it > 0 }?.let { runCatching { Thread.sleep(it) } }
        // Seed the system prompt + prior history into the conversation at creation via
        // ConversationConfig (systemInstruction + initialMessages). LiteRT-LM prefills these as
        // context; only the live user message below triggers a decode — so a multi-turn request
        // costs ONE generation, not one per history turn. (The prior replaySend path ran a full
        // generate-and-discard per turn — ~22 s/turn; a 2-exchange history hit the per-turn timeout
        // and 500'd. Validated on rango / Tensor-G5 / E2B: system honored, history recalled, one decode.)
        // TPU lane: NO custom SamplerConfig — the NPU compiled-model executor crashes mid-decode
        // under one ("new_step must be <= TokenCount()", T-3). Engine-default sampling instead;
        // warn when the request explicitly asked, rather than silently pretending it was honored.
        val tpuLane = backend == RelaisBackend.TPU_LITERTLM
        if (tpuLane && RelaisTpuLane.requestUsesCustomSampler(request.temperature, request.topP, request.seed)) {
          Log.w(TAG, "TPU lane: explicit sampler params (temperature/top_p/seed) unsupported by the NPU executor — using engine defaults")
        }
        val initialMessages = request.history.mapNotNull { it.toResidentMessage() }
        val conversationConfig =
          when {
            tpuLane && request.systemPrompt != null ->
              ConversationConfig(
                systemInstruction = Contents.of(request.systemPrompt),
                initialMessages = initialMessages,
              )
            tpuLane -> ConversationConfig(initialMessages = initialMessages)
            request.systemPrompt != null ->
              ConversationConfig(
                systemInstruction = Contents.of(request.systemPrompt),
                initialMessages = initialMessages,
                samplerConfig = request.samplerConfig(),
              )
            else -> ConversationConfig(initialMessages = initialMessages, samplerConfig = request.samplerConfig())
          }
        // TTFT baseline. Taken here, not at sendMessageAsync below, because the comment above says
        // the system prompt + history are prefilled by createConversation — so a clock started at
        // the send would exclude the dominant term. sendStartNs below measures the other end; the
        // gap between the two series is the prefill, which is how that claim gets tested rather
        // than assumed. Deliberately NOT reqStartNs (:585): that is taken before the engine lock
        // and includes queue wait + the thermal cool-down above.
        val convStartNs = System.nanoTime()
        val conversation = e.createConversation(conversationConfig)
        // True native mid-decode stop (issue #165, verified in #125): once a cooperative cancel is
        // decided in the callback, halt the native decode via `conversation.cancelProcess()` so the
        // node stops burning battery/thermal on tokens nobody reads. Two hard constraints from the
        // on-device probe (docs/litertlm-native-api.md §7.5): (1) cancelProcess() must run OFF the
        // callback thread (it IS the native decode thread) — so dispatch to a one-shot thread; (2) the
        // native run then ends via onError("Process cancelled."), which the post-await handling
        // classifies as a clean cancel, not an error. Fire once (CAS); the thread is joined in the
        // finally before conversation.close() so cancel and close never race natively. Declared out
        // here (not inside the try body) so the finally can join it.
        val cancelRequested = AtomicBoolean(false)
        val stopThread = AtomicReference<Thread?>(null)
        // Declared out here for the same reason as stopThread: assigned inside the try body, read
        // where the result is built.
        var sendStartNs = 0L
        fun requestNativeStop() {
          if (cancelRequested.compareAndSet(false, true)) {
            stopThread.set(
              thread(start = true, isDaemon = true, name = "relais-decode-cancel") {
                runCatching { conversation.cancelProcess() }
                  .onFailure { Log.w(TAG, "cancelProcess() failed: ${it.message}") }
              }
            )
          }
        }
        return try {
          // Stream so we can measure decode throughput by wall clock: BenchmarkInfo only populates
          // via the library's benchmark() path, not normal conversations (SPIKE-FINDINGS.md / Q1).
          val sb = StringBuilder()
          val reasoningSb = StringBuilder()
          var tokens = 0
          var firstTokenNs = 0L
          var lastTokenNs = 0L
          // TTFT is tracked SEPARATELY from firstTokenNs, which is the throughput window's start
          // and advances on every decoded visible token — including ones the two cooperative-cancel
          // returns below stop from ever reaching a streaming client. Only tokens the client can
          // observe count as a TTFT; see [RelaisTtftTracker].
          val ttft = RelaisTtftTracker(streaming = onToken != null)
          // Running cancel bookkeeping (issue #22). `canceled` stops streaming; `truncated` records a
          // device-protective thermal cut (-> finish_reason="length") and is set ONLY via a THERMAL
          // cancel, never a broken-pipe abort (client gone -> no reader -> stays "stop"). Mutated only
          // on the (sequential) callback thread; the post-await read below is safe via the latch's
          // happens-before. AtomicReference also covers the in-stream reads during the callback.
          val cancelState = AtomicReference(DecodeCancelState())
          val latch = CountDownLatch(1)
          val error = arrayOfNulls<Throwable>(1)
          // Request the reasoning channel only when the client opted in (OpenAI reasoning_effort).
          // "enable_thinking" is the extraContext key the Gemma chat template routes its <think>
          // content through; the default (off) passes emptyMap() — byte-for-byte the prior behavior.
          val extraContext =
            if (request.enableThinking) mapOf("enable_thinking" to "true") else emptyMap()
          sendStartNs = System.nanoTime()
          conversation.sendMessageAsync(
            Contents.of(contents),
            object : MessageCallback {
              override fun onMessage(message: Message) {
                val now = System.nanoTime()
                // Split this callback into its reasoning ("thinking") side-channel and its visible
                // answer delta. Reasoning is streamed + accumulated separately and is NEVER counted
                // as a completion token; only when thinking is off-or-absent does this collapse to
                // "emit the visible delta verbatim" (byte-for-byte the prior behavior).
                val thoughtDelta = if (request.enableThinking) message.channels["thought"] else null
                val step =
                  RelaisReasoning.classifyStreamDelta(request.enableThinking, message.toString(), thoughtDelta)

                step.reasoningToEmit?.let { reasoning ->
                  reasoningSb.append(reasoning)
                  if (!cancelState.get().canceled) {
                    try {
                      onReasoning?.invoke(reasoning)
                    } catch (t: Throwable) {
                      cancelState.updateAndGet { RelaisFinishReason.applyCancel(it, DecodeCancelCause.BROKEN_PIPE) }
                      requestNativeStop()
                    }
                  }
                }

                val delta = step.visibleToEmit
                if (delta == null) {
                  // Reasoning-only callback: no visible token to count/emit. Still honor cooperative
                  // cancel so a long thinking phase can be thermally truncated / pipe-aborted.
                  if (shouldCancel?.invoke() == true) {
                    cancelState.updateAndGet { RelaisFinishReason.applyCancel(it, DecodeCancelCause.THERMAL) }
                    requestNativeStop()
                  }
                  return
                }

                // Decode-throughput clock advances on VISIBLE tokens only — reasoning callbacks must
                // not stretch the window (they would understate decode_tok_s fed to ThermalGovernor).
                if (firstTokenNs == 0L) firstTokenNs = now
                lastTokenNs = now
                tokens++
                sb.append(delta)
                // Non-streaming: the append above IS delivery — the token comes back in the
                // response body even if a cancel returns immediately below. Inert when streaming.
                ttft.onVisibleTokenDecoded(now)
                if (cancelState.get().canceled) return
                // Cooperative cancel: thermal-truncate (device-protective) or client disconnect
                // (onToken throws on a broken pipe). It stops streaming to the client AND — via
                // requestNativeStop() -> conversation.cancelProcess() — halts the native decode itself
                // (issue #165; halts in ≤1 token-interval, verified in #125 / §7.5), instead of
                // decoding on to maxNumTokens. finish_reason is unchanged: THERMAL -> "length",
                // BROKEN_PIPE -> "stop".
                if (shouldCancel?.invoke() == true) {
                  cancelState.updateAndGet { RelaisFinishReason.applyCancel(it, DecodeCancelCause.THERMAL) }
                  requestNativeStop()
                  return
                }
                try {
                  onToken?.invoke(delta)
                  // Streaming: the delta is on the wire only once invoke returns without throwing.
                  ttft.onVisibleTokenDelivered(now)
                } catch (t: Throwable) {
                  cancelState.updateAndGet { RelaisFinishReason.applyCancel(it, DecodeCancelCause.BROKEN_PIPE) }
                  requestNativeStop()
                }
              }

              override fun onDone() = latch.countDown()

              override fun onError(throwable: Throwable) {
                error[0] = throwable
                latch.countDown()
              }
            },
            extraContext,
          )
          if (!latch.await(120, TimeUnit.SECONDS)) error("inference timed out")
          // When WE requested the native stop, litertlm ends the run via onError("Process cancelled.").
          // That terminal is expected — fold it into the already-decided finish_reason (length/stop)
          // below rather than throwing it as an inference error. Any OTHER error still propagates.
          error[0]?.let { err ->
            if (!(cancelRequested.get() && RelaisFinishReason.isCancellationTerminal(err.message))) throw err
          }
          val decodeSec = if (lastTokenNs > firstTokenNs) (lastTokenNs - firstTokenNs) / 1e9 else 0.0
          val tokS = if (decodeSec > 0 && tokens > 1) (tokens - 1) / decodeSec else 0.0
          RelaisMetrics.recordThroughput(tokens, tokS, backend.name)
          RelaisMetrics.recordCompletionTokens(tokens) // Feature #10: visible-token distribution
          ThermalGovernor.onDecodeThroughput(tokS)
          // Null (not 0.0) when no visible token ever reached the client — a cancel during the
          // thinking phase, or one that returned before the first delta made it to the socket.
          // Nothing is recorded in that case.
          val ttftSec = ttft.timeToFirstTokenSec(convStartNs)
          val decodeStartSec = ttft.decodeStartLatencySec(sendStartNs)
          ttftSec?.let { RelaisMetrics.recordTimeToFirstToken(it) }
          decodeStartSec?.let { RelaisMetrics.recordDecodeStartLatency(it) }
          RelaisResult(
            text = sb.toString(),
            backend = backend,
            decodeTokensPerSec = tokS,
            completionTokens = tokens,
            reasoning = reasoningSb.toString().takeIf { it.isNotEmpty() },
            finishReason = RelaisFinishReason.forCompletion(cancelState.get().truncated),
            timeToFirstTokenSec = ttftSec,
            decodeStartLatencySec = decodeStartSec,
          )
        } finally {
          // Join the cancel thread (if any) before closing so cancelProcess() and close() never run
          // concurrently against the same native conversation. Bounded — the cancel returns promptly.
          stopThread.get()?.let { runCatching { it.join(2_000) } }
          conversation.close()
        }
      }
    } finally {
      RelaisMetrics.recordLatency((System.nanoTime() - reqStartNs) / 1e9) // every outcome (HIGH-2)
      RelaisMetrics.decInFlight()
      lastActivityAtMs = System.currentTimeMillis() // idle-TTL clock (#178): every outcome counts
    }
  }

  /**
   * Blocking tool round-trip against the resident engine. Advertises [RelaisRequest.tools] to the
   * model (automaticToolCalling=false so the node — not the engine — runs tools), seeds system +
   * history, then sends ONE live message:
   *  - tool results present -> a TOOL message carrying each [Content.ToolResponse] (the round-trip
   *    where the model integrates tool output into a final answer);
   *  - otherwise -> the live USER message (the first turn, where the model may request a tool).
   *
   * Returns the reply text plus any [ParsedToolCall]s the model emitted. CALLER MUST HOLD [lock] —
   * see [generate], which resolves `e` and dispatches here from inside its own `synchronized(lock)`
   * block (the #178 restructure that makes idle-TTL unload race-free); this function no longer takes
   * the lock itself (it would be redundant — Kotlin/JVM monitors are reentrant, but a single lock
   * scope covering "resolve engine -> use engine" is the actual safety property, not merely "some
   * lock is held somewhere"). Runs no per-token callback (the blocking sendMessage returns the full
   * reply Message at once). Throughput is not measured here (no per-token wall clock), so
   * decodeTokensPerSec/completionTokens are 0.
   */
  @OptIn(ExperimentalApi::class)
  private fun generateWithToolsLocked(
    e: Engine,
    request: RelaisRequest,
    backend: RelaisBackend,
  ): RelaisResult {
    ThermalGovernor.cooldownMs().takeIf { it > 0 }?.let { runCatching { Thread.sleep(it) } }
    // TPU lane: same no-custom-sampler rule as the streaming path (NPU executor limitation, T-3).
    val tpuLane = backend == RelaisBackend.TPU_LITERTLM
    if (tpuLane && RelaisTpuLane.requestUsesCustomSampler(request.temperature, request.topP, request.seed)) {
      Log.w(TAG, "TPU lane (tools): explicit sampler params unsupported by the NPU executor — using engine defaults")
    }
    val providers = request.tools.map { tool(openApiToolOf(it.functionJson)) }
    val initialMessages = request.history.mapNotNull { it.toResidentMessage() }
    val config =
      when {
        tpuLane && request.systemPrompt != null ->
          ConversationConfig(
            systemInstruction = Contents.of(request.systemPrompt),
            initialMessages = initialMessages,
            tools = providers,
            automaticToolCalling = false,
          )
        tpuLane ->
          ConversationConfig(initialMessages = initialMessages, tools = providers, automaticToolCalling = false)
        request.systemPrompt != null ->
          ConversationConfig(
            systemInstruction = Contents.of(request.systemPrompt),
            initialMessages = initialMessages,
            tools = providers,
            automaticToolCalling = false,
            samplerConfig = request.samplerConfig(),
          )
        else ->
          ConversationConfig(
            initialMessages = initialMessages,
            tools = providers,
            automaticToolCalling = false,
            samplerConfig = request.samplerConfig(),
          )
      }
    val conversation = e.createConversation(config)
    return try {
      val liveMessage =
        if (request.toolResults.isNotEmpty()) {
          Message.tool(Contents.of(request.toolResults.map { Content.ToolResponse(it.name, it.content) }))
        } else {
          val items = buildList {
            request.imagePng?.let { add(Content.ImageBytes(it)) }
            request.audioWav?.let { add(Content.AudioBytes(it)) }
            if (request.text.isNotBlank()) add(Content.Text(request.text))
          }
          Message.user(Contents.of(items))
        }
      val reply = conversation.sendMessage(liveMessage, emptyMap())
      val mapped = reply.toolCalls.mapIndexed { index, tc ->
        val argumentsJson = runCatching { JSONObject(tc.arguments).toString() }.getOrElse { ex ->
          Log.w(TAG, "Failed to serialize arguments for tool '${tc.name}'; using {}: $ex")
          "{}"
        }
        ParsedToolCall(
          id = "call_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24),
          name = tc.name,
          argumentsJson = argumentsJson,
        )
      }
      val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
      RelaisResult(
        text = text,
        backend = backend,
        decodeTokensPerSec = 0.0,
        completionTokens = 0,
        toolCalls = mapped,
      )
    } finally {
      conversation.close()
    }
  }

  /**
   * Wraps an OpenAI `function` object ([functionJson]) as an [OpenApiTool] for the LiteRT-LM
   * `tool(...)` bridge. [OpenApiTool.execute] is only invoked when automaticToolCalling=true; the
   * node always runs with it OFF (tools execute client-side), so execute() should never fire — it
   * logs a warning and returns an empty object defensively.
   */
  private fun openApiToolOf(functionJson: String): OpenApiTool =
    object : OpenApiTool {
      override fun getToolDescriptionJsonString(): String = functionJson

      override fun execute(paramsJsonString: String): String {
        Log.w(TAG, "OpenApiTool.execute() called unexpectedly (automaticToolCalling is off); returning {}")
        return "{}"
      }
    }

  fun shutdown() {
    synchronized(lock) {
      try {
        engine?.close()
        consecutiveCloseFailures = 0
      } catch (e: Exception) {
        Log.e(TAG, "Error closing engine", e)
        consecutiveCloseFailures++
      } finally {
        engine = null
      }
    }
  }

  /**
   * Idle-TTL auto-unload (#178): releases the resident engine iff [shouldUnloadIdleEngine] says so —
   * i.e. it's ready, [ttlMs] is enabled (`> 0`), nothing is in flight, [lastActivityAtMs] is older
   * than [ttlMs], AND [consecutiveCloseFailures] hasn't tripped the circuit breaker (repeated
   * [Engine.close] failures stop further auto-unload attempts rather than risking a repeated native
   * resource leak — see [shouldUnloadIdleEngine]'s KDoc). Called periodically by [RelaisNodeService]
   * (see its idle-TTL ticker); a subsequent request reloads the engine lazily via [ensureInitialized]
   * (a short cold-start, as intended by #178 — see [shouldUnloadIdleEngine]'s KDoc). Sets
   * [wasIdleUnloaded] on a successful release so [RelaisWatchdogReceiver] doesn't mistake the
   * graceful unload for a crash.
   *
   * RACE SAFETY (the highest-risk part of #178): this takes the SAME [lock] that [generate] now
   * holds for its entire "resolve the resident engine -> use it" span (see the comment there). Two
   * checks:
   *  1. A cheap pre-check with no lock, so a poll tick that's obviously a no-op (not ready, disabled,
   *     or [RelaisMetrics.queueDepth] already nonzero) never contends for [lock].
   *  2. A re-check performed AFTER acquiring [lock], immediately before [shutdown]. This is not
   *     redundant: [RelaisMetrics.incInFlight] (in [generate]) is called before that function
   *     acquires [lock], so a request that started between the pre-check and the lock acquisition
   *     shows up in the re-check's queue depth and aborts the release — closing the exact TOCTOU
   *     window a naive single-check implementation would have.
   *  3. Conversely, if this function wins the race and closes the engine first, any request already
   *     blocked on [lock] simply proceeds once this returns and re-initializes lazily inside its own
   *     locked section — it can never observe or use a half-closed `engine`, because [generate] does
   *     not capture the `engine` field until it too is inside [lock].
   *
   * This composition is a lock-ordering argument, not something a hermetic pure-JVM test can
   * exercise (it needs the real, native, AAR-provided `Engine`) — see [shouldUnloadIdleEngine]'s
   * KDoc for why the decision itself IS unit-tested but this interleaving needs an on-device probe.
   *
   * @return true iff the engine was actually released by this call.
   */
  fun releaseIfIdle(ttlMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (!shouldUnloadIdleEngine(isReady, RelaisMetrics.queueDepth(), lastActivityAtMs, nowMs, ttlMs, consecutiveCloseFailures)) {
      return false
    }
    synchronized(lock) {
      if (!shouldUnloadIdleEngine(isReady, RelaisMetrics.queueDepth(), lastActivityAtMs, nowMs, ttlMs, consecutiveCloseFailures)) {
        return false
      }
      Log.i(TAG, "idle TTL exceeded (${nowMs - lastActivityAtMs}ms >= ${ttlMs}ms) — releasing resident engine")
      shutdown()
      wasIdleUnloaded = true // tell RelaisWatchdogReceiver this is a graceful unload, not a crash
      return true
    }
  }

  /**
   * Maps a parsed OpenAI history turn to a LiteRT-LM [Message] with the correct role, for seeding a
   * conversation via [ConversationConfig.initialMessages]. Roles map as:
   *  - "tool": a TOOL message carrying the result text as a [Content.ToolResponse] (null if the
   *    function name couldn't be resolved — a nameless tool response can't be addressed).
   *  - "assistant" with tool calls: a MODEL message carrying the calls (so the model sees its own
   *    prior tool requests); content is empty when the assistant turn had no text.
   *  - "assistant" (no calls): a MODEL message.
   *  - everything else: a USER message.
   * Returns null for an empty turn (no text and no media and no tool calls) so no blank turn seeds.
   */
  @OptIn(ExperimentalApi::class)
  private fun ParsedTurn.toResidentMessage(): Message? {
    if (role == "tool") {
      return toolName?.let { Message.tool(Contents.of(listOf(Content.ToolResponse(it, text)))) }
    }
    if (role == "assistant" && toolCalls.isNotEmpty()) {
      val contents = if (text.isBlank()) Contents.of(emptyList()) else Contents.of(text)
      return Message.model(contents, toolCalls.map { ToolCall(it.name, jsonToMap(it.argumentsJson)) }, emptyMap())
    }
    val items = buildList {
      imagePng?.let { add(Content.ImageBytes(it)) }
      audioWav?.let { add(Content.AudioBytes(it)) }
      if (text.isNotBlank()) add(Content.Text(text))
    }
    if (items.isEmpty()) return null
    val contents = Contents.of(items)
    return if (role == "assistant") Message.model(contents) else Message.user(contents)
  }

  /** Parses a JSON-object string into a `Map<String, Any?>` for [ToolCall.arguments]; {} on failure. */
  private fun jsonToMap(json: String): Map<String, Any?> =
    runCatching {
      val obj = JSONObject(json)
      buildMap {
        val keys = obj.keys()
        while (keys.hasNext()) {
          val key = keys.next()
          put(key, obj.get(key))
        }
      }
    }.getOrDefault(emptyMap())
}

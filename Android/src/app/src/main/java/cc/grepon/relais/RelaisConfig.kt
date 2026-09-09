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
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cc.grepon.relais.data.RelaisModelRef
import cc.grepon.relais.imagegen.DEFAULT_IMAGE_MODEL_ID
import java.util.UUID

/** Node config: API key for the LAN endpoint and the opt-in boot auto-start flag. */
object RelaisConfig {
  private const val TAG = "RelaisConfig"
  private const val PREFS = "relais"
  private const val SECURE_PREFS = "relais_secure"
  private const val KEY_API = "api_key"
  private const val KEY_AUTOSTART = "auto_start"
  private const val KEY_SHOULD_RUN = "should_run"
  private const val KEY_MODEL_ID = "model_id"
  private const val KEY_MODEL_REF = "model_ref"
  private const val KEY_PROVISIONED_MODELS = "provisioned_models"
  private const val KEY_HF_TOKEN = "hf_token"
  private const val KEY_MODEL_PATH = "model_path"
  private const val KEY_IMAGE_MODEL_ID = "image_model_id"
  private const val KEY_IMAGE_MODEL_URL = "image_model_url"
  private const val KEY_IMAGE_MODEL_SHA = "image_model_sha"
  private const val KEY_TTS_VOICE_ID = "tts_voice_id"
  private const val KEY_TLS_PASS = "tls_keystore_pass"
  private const val KEY_CA_PASS = "ca_keystore_pass"
  private const val KEY_RESTARTS = "restarts_total"
  private const val KEY_SHED_HEADROOM = "shed_headroom"
  private const val KEY_DECODE_FLOOR_TOK_S = "decode_floor_tok_s"
  private const val KEY_MODERATE_COOLDOWN_MS = "moderate_cooldown_ms"
  private const val KEY_TILE_TEMPLATE_ID = "tile_canned_template_id"
  private const val KEY_SHARE_ENABLED = "share_enabled"
  private const val KEY_SHARE_SYSTEM = "share_system_prompt"
  private const val KEY_NFC_ENABLED = "nfc_enabled"
  private const val KEY_SESSION_MEMORY = "session_memory_enabled"
  private const val KEY_SESSION_TTL_HOURS = "session_ttl_hours"
  private const val KEY_SESSION_MAX_TURNS = "session_max_turns"
  private const val KEY_SESSION_GLOBAL_MAX_TURNS = "session_global_max_turns"
  private const val KEY_WEBHOOK_HMAC_SECRET = "webhook_hmac_secret"
  private const val KEY_WEBHOOK_ALLOWLIST = "webhook_allowlist"
  private const val KEY_IDLE_TTL_MINUTES = "idle_ttl_minutes"

  // Server-side session memory (Feature #5) — DEFAULT-OFF. When disabled the HTTP path never reads
  // the header, opens the DB, or persists anything (zero behavior change). TTL + per-session turn
  // cap bound retention and DB growth; both are clamped on read AND write so a tampered prefs value
  // stays in band.
  private const val SESSION_TTL_HOURS_DEFAULT = 24
  private const val SESSION_TTL_HOURS_MIN = 1
  private const val SESSION_TTL_HOURS_MAX = 24 * 30 // 30 days
  private const val SESSION_MAX_TURNS_DEFAULT = 40
  private const val SESSION_MAX_TURNS_MIN = 2
  private const val SESSION_MAX_TURNS_MAX = 200
  // Global (all-sessions) stored-turn cap — the absolute ceiling on session_turns rows, so a client
  // that varies its session key can't grow the DB without bound between TTL passes. Clamped to a sane
  // band on read AND write, mirroring the per-session cap.
  private const val SESSION_GLOBAL_MAX_TURNS_DEFAULT = 5_000
  private const val SESSION_GLOBAL_MAX_TURNS_MIN = 100
  private const val SESSION_GLOBAL_MAX_TURNS_MAX = 50_000

  // Operational shed-threshold defaults + HARD CLAMP bands (Feature #10). Defaults equal the
  // ThermalGovernor consts these replace. Clamps applied on BOTH read and write so a pre-existing
  // out-of-band prefs value (or a bad write) can never disable a shed signal — in particular
  // DECODE_FLOOR_MIN stays > 0 so the throughput-floor signal is never switched off.
  private const val SHED_HEADROOM_DEFAULT = 0.95f
  private const val SHED_HEADROOM_MIN = 0.5f
  private const val SHED_HEADROOM_MAX = 1.5f
  private const val DECODE_FLOOR_DEFAULT = 3.0
  private const val DECODE_FLOOR_MIN = 0.5 // must stay > 0: floor signal must never be disabled
  private const val DECODE_FLOOR_MAX = 50.0
  private const val COOLDOWN_DEFAULT = 1_500L
  private const val COOLDOWN_MIN = 0L
  private const val COOLDOWN_MAX = 10_000L

  // Idle-TTL auto-unload (#178). IDLE_TTL_DISABLED_MINUTES (0) is a valid, in-band value (the "never
  // unload" opt-out) — unlike the shed thresholds above, disabling this signal is a legitimate
  // operator choice, not a device-safety hazard. Clamp band excludes the disabled sentinel from
  // coerceIn (0 is below IDLE_TTL_MIN_MINUTES) so it needs its own pass-through check on read+write.

  @Volatile private var securePrefsCache: SharedPreferences? = null

  /**
   * Default model the node self-provisions. The litert-community Gemma-4-E4B-it repo is an **open**
   * HuggingFace repo — it downloaded with no auth in the spike, so first run needs no token. Switch
   * to a license-gated `google/gemma-*` id only alongside a token set via [setHfToken].
   */
  const val DEFAULT_MODEL_ID = "litert-community/gemma-4-E4B-it-litert-lm"

  /** Plaintext prefs for non-sensitive node config (flags, model id, restart counter). */
  private fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /**
   * Encrypted prefs for secrets — API key, TLS keystore password, HF token. Backed by
   * [EncryptedSharedPreferences] (AndroidKeystore-wrapped AES). A one-time [migrateSecrets] moves
   * any pre-existing plaintext secrets out of [PREFS]. Security H2: no plaintext secret at rest, so
   * an `adb backup` / root / prefs-disclosure bug no longer yields the live key. The operator can
   * still read the key in-app because the node decrypts it for display.
   */
  private fun securePrefs(context: Context): SharedPreferences {
    securePrefsCache?.let { return it }
    return synchronized(this) {
      securePrefsCache
        ?: run {
          val appCtx = context.applicationContext
          // HIGH-3: EncryptedSharedPreferences.create can throw if the Keystore key is invalidated
          // (OS restore, lock-credential change on some OEMs). On a headless device with no
          // interactive recovery, a throw would brick the node into a watchdog restart loop. Reset
          // the unreadable store once and recreate; secrets regenerate downstream.
          val sp =
            runCatching { createEncrypted(appCtx) }
              .getOrElse {
                Log.e(TAG, "secure prefs unreadable; resetting encrypted store", it)
                appCtx.deleteSharedPreferences(SECURE_PREFS)
                createEncrypted(appCtx)
              }
          migrateSecrets(appCtx, sp)
          securePrefsCache = sp
          sp
        }
    }
  }

  private fun createEncrypted(appCtx: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(appCtx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(
      appCtx,
      SECURE_PREFS,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  /** Move any legacy plaintext secrets from [PREFS] into the encrypted store, once. */
  private fun migrateSecrets(context: Context, secure: SharedPreferences) {
    val plain = prefs(context)
    for (key in listOf(KEY_API, KEY_TLS_PASS, KEY_HF_TOKEN)) {
      val legacy = plain.getString(key, null) ?: continue
      if (secure.getString(key, null) == null) secure.edit().putString(key, legacy).apply()
      plain.edit().remove(key).apply()
    }
  }

  /** Intent-to-run latch: set true while the node is meant to be up; the watchdog honors it. */
  fun shouldRun(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOULD_RUN, false)

  fun setShouldRun(context: Context, value: Boolean) {
    prefs(context).edit().putBoolean(KEY_SHOULD_RUN, value).apply()
  }

  /** Stable per-install API key; generated and persisted (encrypted) on first access. */
  fun apiKey(context: Context): String {
    val sp = securePrefs(context)
    sp.getString(KEY_API, null)?.let {
      return it
    }
    val key = UUID.randomUUID().toString().replace("-", "")
    sp.edit().putString(KEY_API, key).apply()
    return key
  }

  /** Off by default — the node does not start on boot unless explicitly enabled. */
  fun autoStartEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOSTART, false)

  fun setAutoStart(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
  }

  /** Allowlist model id the node downloads & serves; defaults to the open [DEFAULT_MODEL_ID]. */
  fun modelId(context: Context): String =
    prefs(context).getString(KEY_MODEL_ID, null) ?: DEFAULT_MODEL_ID

  fun setModelId(context: Context, value: String) {
    val p = prefs(context)
    val changed = p.getString(KEY_MODEL_ID, null) != value
    val edit = p.edit().putString(KEY_MODEL_ID, value)
    if (changed) {
      // Switching models must invalidate the cached provisioned path, otherwise the offline
      // fast-path in RelaisModelProvisioner.ensureModel would keep serving the previous model's
      // file (it still exists on disk) and never resolve the new id.
      edit.remove(KEY_MODEL_PATH)
      // A bare id change (adb `--es modelId`, or the manual-id field) supersedes a persisted ref
      // unless they name the same repo. Drop a now-stale ref so resolveModel falls back to the
      // allowlist for the new id instead of serving the old ref's model.
      val refId = RelaisModelRef.fromJson(p.getString(KEY_MODEL_REF, null))?.modelId
      if (refId != null && refId != value) edit.remove(KEY_MODEL_REF)
    }
    edit.apply()
  }

  /** Selected image-gen model id (image-gen #16); defaults to the SD-Turbo [DEFAULT_IMAGE_MODEL_ID]. */
  fun imageModelId(context: Context): String =
    prefs(context).getString(KEY_IMAGE_MODEL_ID, null) ?: DEFAULT_IMAGE_MODEL_ID

  fun setImageModelId(context: Context, value: String) {
    prefs(context).edit().putString(KEY_IMAGE_MODEL_ID, value).apply()
  }

  /** Selected on-device TTS voice id (#168); defaults to the Piper [tts.DEFAULT_TTS_VOICE_ID]. */
  fun ttsVoiceId(context: Context): String =
    prefs(context).getString(KEY_TTS_VOICE_ID, null) ?: cc.grepon.relais.tts.DEFAULT_TTS_VOICE_ID

  fun setTtsVoiceId(context: Context, value: String) {
    prefs(context).edit().putString(KEY_TTS_VOICE_ID, value).apply()
  }

  /** Operator override URL for the `custom` image model (a self-hosted sd.cpp gguf), or null. */
  fun imageModelUrl(context: Context): String? =
    prefs(context).getString(KEY_IMAGE_MODEL_URL, null)?.takeIf { it.isNotBlank() }

  fun setImageModelUrl(context: Context, value: String?) {
    prefs(context).edit()
      .apply { if (value.isNullOrBlank()) remove(KEY_IMAGE_MODEL_URL) else putString(KEY_IMAGE_MODEL_URL, value) }
      .apply()
  }

  /** Optional SHA-256 to pin the `custom` image model, or null (no integrity check on a custom URL). */
  fun imageModelSha(context: Context): String? =
    prefs(context).getString(KEY_IMAGE_MODEL_SHA, null)?.takeIf { it.isNotBlank() }

  fun setImageModelSha(context: Context, value: String?) {
    prefs(context).edit()
      .apply { if (value.isNullOrBlank()) remove(KEY_IMAGE_MODEL_SHA) else putString(KEY_IMAGE_MODEL_SHA, value) }
      .apply()
  }

  /**
   * The persisted [RelaisModelRef] the operator selected, or null if none (legacy id-only config).
   * Authoritative for provisioning only when its [RelaisModelRef.modelId] still matches [modelId] —
   * see [RelaisModelProvisioner.resolveModel]. Null/malformed JSON decodes to null (the node then
   * resolves via the allowlist), so a corrupt entry never bricks a headless boot.
   */
  fun modelRef(context: Context): RelaisModelRef? =
    RelaisModelRef.fromJson(prefs(context).getString(KEY_MODEL_REF, null))

  /**
   * Persists [ref] as the selected model and keeps the legacy [KEY_MODEL_ID] coherent (set to the
   * ref's id). Clears [KEY_MODEL_PATH] on any change so the staged-path fast-path can't serve the
   * previously provisioned model. Writes the id directly (not via [setModelId]) so the stale-ref
   * cleanup there never fires against the ref being set.
   */
  fun setModelRef(context: Context, ref: RelaisModelRef) {
    val p = prefs(context)
    val json = ref.toJson()
    val changed =
      p.getString(KEY_MODEL_REF, null) != json || p.getString(KEY_MODEL_ID, null) != ref.modelId
    // One editor, one commit: a crash between two separate apply()s could persist the new ref+id
    // while keeping the old KEY_MODEL_PATH, and ensureModel's fast-path would then serve the
    // previous model's file for the new model. Mirrors setModelId's single-editor discipline.
    val edit = p.edit().putString(KEY_MODEL_REF, json).putString(KEY_MODEL_ID, ref.modelId)
    if (changed) edit.remove(KEY_MODEL_PATH)
    edit.apply()
  }

  /**
   * Models provisioned on THIS device (#180). Distinct from [modelRef], which holds only the single
   * current selection and is cleared when the id diverges — this is the durable inventory a
   * per-request model swap resolves against.
   */
  fun provisionedModels(context: Context): List<ProvisionedModel> =
    decodeProvisioned(prefs(context).getString(KEY_PROVISIONED_MODELS, null))

  fun setProvisionedModels(context: Context, entries: List<ProvisionedModel>) {
    prefs(context).edit().putString(KEY_PROVISIONED_MODELS, encodeProvisioned(entries)).apply()
  }

  /** Drops the persisted ref (e.g. reverting to a pure allowlist id). Leaves [KEY_MODEL_ID] intact. */
  fun clearModelRef(context: Context) {
    prefs(context).edit().remove(KEY_MODEL_REF).apply()
  }

  /**
   * Optional HuggingFace access token for license-gated repos (e.g. official `google/gemma-*`).
   * Null for open models. Stored encrypted. The headless node cannot do interactive OAuth, so a
   * gated model requires this to be pre-set via [setHfToken].
   */
  fun hfToken(context: Context): String? = securePrefs(context).getString(KEY_HF_TOKEN, null)

  fun setHfToken(context: Context, value: String?) {
    securePrefs(context).edit().putString(KEY_HF_TOKEN, value).apply()
  }

  /**
   * HMAC-SHA256 secret for signing batch webhook payloads (the `X-Relais-Signature` header, #14).
   * Generated + stored (encrypted) on first use; the operator reads it to configure their receiver.
   */
  fun webhookHmacSecret(context: Context): String {
    val sp = securePrefs(context)
    return sp.getString(KEY_WEBHOOK_HMAC_SECRET, null)
      ?: java.util.UUID.randomUUID().toString().replace("-", "").also {
        sp.edit().putString(KEY_WEBHOOK_HMAC_SECRET, it).apply()
      }
  }

  /**
   * Operator-allowlisted webhook hosts (lowercased) that bypass the SSRF https + private-IP checks —
   * for a trusted internal endpoint. Empty by default (so only public https destinations are allowed).
   */
  fun webhookAllowlist(context: Context): Set<String> =
    prefs(context).getString(KEY_WEBHOOK_ALLOWLIST, null)
      ?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
      ?: emptySet()

  fun setWebhookAllowlist(context: Context, hosts: Set<String>) {
    prefs(context).edit().putString(KEY_WEBHOOK_ALLOWLIST, hosts.joinToString(",")).apply()
  }

  /**
   * Last successfully provisioned on-disk model path. Persisted so a restarted node whose model is
   * already downloaded can boot **offline** without re-fetching the allowlist. Null until first
   * provision; see [RelaisModelProvisioner.ensureModel].
   */
  fun modelPath(context: Context): String? = prefs(context).getString(KEY_MODEL_PATH, null)

  fun setModelPath(context: Context, value: String) {
    prefs(context).edit().putString(KEY_MODEL_PATH, value).apply()
  }

  /**
   * Random per-install password protecting the runtime-generated TLS keystore. Generated and
   * persisted (encrypted) on first access, mirroring [apiKey]. Not a shared/committed secret.
   *
   * **`@Synchronized` is load-bearing, not decoration.** This is read-then-generate-then-write with
   * no atomicity of its own, and it now has genuinely concurrent callers: the accept thread minting
   * in `buildServerSocket` and the LAN re-issue thread. Two callers both reading null would each
   * generate a different UUID, last write wins — and the keystore written under the loser's
   * password becomes **permanently unreadable**, which routes straight into regenerating the leaf
   * key and silently changing the NODE KEY PIN every pinned client depends on.
   */
  @Synchronized
  fun tlsKeystorePassword(context: Context): String {
    val sp = securePrefs(context)
    sp.getString(KEY_TLS_PASS, null)?.let {
      return it
    }
    val pass = UUID.randomUUID().toString().replace("-", "")
    sp.edit().putString(KEY_TLS_PASS, pass).apply()
    return pass
  }

  /**
   * Random per-install password protecting the per-node **CA** keystore (feature-18), mirroring
   * [tlsKeystorePassword] exactly.
   *
   * A separate password for a separate file: the CA lives in its own PKCS12 so that the
   * `KeyManagerFactory` serving the TLS listener is never handed two key entries to choose between,
   * and so the CA private key is never in the store the handshake reads.
   *
   * Deliberately **absent** from [migrateSecrets]: that list migrates secrets that once shipped in
   * plaintext prefs. This key has only ever existed in the encrypted store, so there is nothing to
   * migrate and adding it would only widen the legacy read.
   *
   * `@Synchronized` for the same reason as [tlsKeystorePassword], and the consequence here is worse:
   * an unreadable CA keystore rotates the **CA**, invalidating every client's import.
   */
  @Synchronized
  fun caKeystorePassword(context: Context): String {
    val sp = securePrefs(context)
    sp.getString(KEY_CA_PASS, null)?.let {
      return it
    }
    val pass = UUID.randomUUID().toString().replace("-", "")
    sp.edit().putString(KEY_CA_PASS, pass).apply()
    return pass
  }

  /** Process (re)starts observed — survives process death; surfaced via /metrics (Gate 3). */
  fun restartCount(context: Context): Long = prefs(context).getLong(KEY_RESTARTS, 0L)

  fun incrementRestartCount(context: Context) {
    val p = prefs(context)
    p.edit().putLong(KEY_RESTARTS, p.getLong(KEY_RESTARTS, 0L) + 1).apply()
  }

  /**
   * Forecast-headroom shed threshold (Feature #10). Clamped to [SHED_HEADROOM_MIN]..[SHED_HEADROOM_MAX]
   * on read so even a tampered prefs value stays in band; [ThermalGovernor] reads this at register().
   */
  fun shedHeadroom(context: Context): Float =
    sanitizeHeadroom(prefs(context).getFloat(KEY_SHED_HEADROOM, SHED_HEADROOM_DEFAULT))

  fun setShedHeadroom(context: Context, value: Float) {
    prefs(context).edit().putFloat(KEY_SHED_HEADROOM, sanitizeHeadroom(value)).apply()
  }

  // coerceIn does NOT neutralize NaN (NaN < min and NaN > max are both false, so NaN passes
  // through). A non-finite threshold would silently disable the headroom shed signal, so map
  // NaN/Inf to the default BEFORE clamping — on both the read and write paths, so a tampered or
  // corrupt prefs value can never reach ThermalGovernor.
  private fun sanitizeHeadroom(v: Float): Float =
    (if (v.isFinite()) v else SHED_HEADROOM_DEFAULT).coerceIn(SHED_HEADROOM_MIN, SHED_HEADROOM_MAX)

  /**
   * Sustained-decode floor (tok/s) shed threshold (Feature #10). Clamped to a STRICTLY POSITIVE band
   * [DECODE_FLOOR_MIN]..[DECODE_FLOOR_MAX] so the throughput-floor signal can never be disabled.
   */
  fun decodeFloorTokS(context: Context): Double =
    sanitizeDecodeFloor(prefs(context).getFloat(KEY_DECODE_FLOOR_TOK_S, DECODE_FLOOR_DEFAULT.toFloat()).toDouble())

  fun setDecodeFloorTokS(context: Context, value: Double) {
    prefs(context).edit()
      .putFloat(KEY_DECODE_FLOOR_TOK_S, sanitizeDecodeFloor(value).toFloat())
      .apply()
  }

  // NaN/Inf -> default BEFORE clamping (coerceIn lets NaN through). Guarding both read and write
  // keeps the throughput-floor signal strictly positive even against a tampered prefs value.
  private fun sanitizeDecodeFloor(v: Double): Double =
    (if (v.isFinite()) v else DECODE_FLOOR_DEFAULT).coerceIn(DECODE_FLOOR_MIN, DECODE_FLOOR_MAX)

  /** Cool-down gap (ms) inserted while MODERATE (Feature #10). Clamped to [COOLDOWN_MIN]..[COOLDOWN_MAX]. */
  fun moderateCooldownMs(context: Context): Long =
    prefs(context).getLong(KEY_MODERATE_COOLDOWN_MS, COOLDOWN_DEFAULT)
      .coerceIn(COOLDOWN_MIN, COOLDOWN_MAX)

  fun setModerateCooldownMs(context: Context, value: Long) {
    prefs(context).edit()
      .putLong(KEY_MODERATE_COOLDOWN_MS, value.coerceIn(COOLDOWN_MIN, COOLDOWN_MAX))
      .apply()
  }

  /**
   * Idle-TTL auto-unload window in minutes (#178): [RelaisEngine.releaseIfIdle] releases the
   * resident engine after this many minutes with no request; the next request reloads it lazily.
   * [IDLE_TTL_DISABLED_MINUTES] (0) disables auto-unload — the engine then stays resident until the
   * node is stopped, matching pre-#178 behavior. Any other value is clamped to
   * [IDLE_TTL_MIN_MINUTES]..[IDLE_TTL_MAX_MINUTES] on read+write.
   */
  fun idleTtlMinutes(context: Context): Int =
    sanitizeIdleTtlMinutes(prefs(context).getInt(KEY_IDLE_TTL_MINUTES, IDLE_TTL_DEFAULT_MINUTES))

  fun setIdleTtlMinutes(context: Context, value: Int) {
    prefs(context).edit().putInt(KEY_IDLE_TTL_MINUTES, sanitizeIdleTtlMinutes(value)).apply()
  }

  private fun sanitizeIdleTtlMinutes(v: Int): Int =
    if (v <= IDLE_TTL_DISABLED_MINUTES) IDLE_TTL_DISABLED_MINUTES
    else v.coerceIn(IDLE_TTL_MIN_MINUTES, IDLE_TTL_MAX_MINUTES)

  /**
   * Optional canned-prompt template id run on a QS-tile tap (Feature #2). Null (the default) =
   * toggle-only: a tap just starts/stops the node and never triggers inference. A blank stored value
   * decodes to null so a cleared field can't enqueue an empty prompt. The tile gates the actual run
   * on engine readiness (never cold-starts) — this is only which template to use when it does run.
   */
  fun tileCannedTemplateId(context: Context): String? =
    prefs(context).getString(KEY_TILE_TEMPLATE_ID, null)?.takeIf { it.isNotBlank() }

  fun setTileCannedTemplateId(context: Context, id: String?) {
    val edit = prefs(context).edit()
    val clean = id?.takeIf { it.isNotBlank() }
    if (clean == null) edit.remove(KEY_TILE_TEMPLATE_ID) else edit.putString(KEY_TILE_TEMPLATE_ID, clean)
    edit.apply()
  }

  /**
   * Share-sheet inference target master switch (Feature #1). DEFAULT **true** — the node registers as
   * a `text/plain` share target out of the box. The trampoline gates the actual run on this AND engine
   * readiness (never cold-starts); turning this off makes a share report "Share target disabled"
   * instead of running. The manifest filter is always present (it's static), so this is the runtime
   * opt-out, not a way to remove the entry from the share sheet.
   */
  fun shareEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SHARE_ENABLED, true)

  fun setShareEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_SHARE_ENABLED, enabled).apply()
  }

  /** Opt-in NFC workflow triggers (#15). Default OFF — a tag does nothing until the operator enables it. */
  fun nfcEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_NFC_ENABLED, false)

  fun setNfcEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_NFC_ENABLED, enabled).apply()
  }

  /**
   * Optional operator override for the system prompt used on a share run (Feature #1). Null (the
   * default) means the share service falls back to its concise built-in summarize/answer prompt. A
   * blank stored value decodes to null so a cleared field reverts to the built-in.
   */
  fun shareSystemPrompt(context: Context): String? =
    prefs(context).getString(KEY_SHARE_SYSTEM, null)?.takeIf { it.isNotBlank() }

  fun setShareSystemPrompt(context: Context, value: String?) {
    val edit = prefs(context).edit()
    val clean = value?.takeIf { it.isNotBlank() }
    if (clean == null) edit.remove(KEY_SHARE_SYSTEM) else edit.putString(KEY_SHARE_SYSTEM, clean)
    edit.apply()
  }

  /**
   * Server-side session memory master switch (Feature #5). DEFAULT **false** — the node is stateless
   * unless an operator opts in. When false the HTTP path never captures the session header, opens the
   * session DB, or persists a turn (byte-for-byte the prior behavior). Privacy posture: opt-in only,
   * TTL-pruned, explicit DELETE, app-private storage, hashed IP keys.
   */
  fun sessionMemoryEnabled(context: Context): Boolean =
    prefs(context).getBoolean(KEY_SESSION_MEMORY, false)

  fun setSessionMemoryEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_SESSION_MEMORY, enabled).apply()
  }

  /** Session retention TTL in hours (Feature #5). Clamped to [SESSION_TTL_HOURS_MIN]..MAX on read+write. */
  fun sessionTtlHours(context: Context): Int =
    prefs(context).getInt(KEY_SESSION_TTL_HOURS, SESSION_TTL_HOURS_DEFAULT)
      .coerceIn(SESSION_TTL_HOURS_MIN, SESSION_TTL_HOURS_MAX)

  fun setSessionTtlHours(context: Context, value: Int) {
    prefs(context).edit()
      .putInt(KEY_SESSION_TTL_HOURS, value.coerceIn(SESSION_TTL_HOURS_MIN, SESSION_TTL_HOURS_MAX))
      .apply()
  }

  /** Per-session stored-turn cap (Feature #5). Clamped to [SESSION_MAX_TURNS_MIN]..MAX on read+write. */
  fun sessionMaxTurns(context: Context): Int =
    prefs(context).getInt(KEY_SESSION_MAX_TURNS, SESSION_MAX_TURNS_DEFAULT)
      .coerceIn(SESSION_MAX_TURNS_MIN, SESSION_MAX_TURNS_MAX)

  fun setSessionMaxTurns(context: Context, value: Int) {
    prefs(context).edit()
      .putInt(KEY_SESSION_MAX_TURNS, value.coerceIn(SESSION_MAX_TURNS_MIN, SESSION_MAX_TURNS_MAX))
      .apply()
  }

  /**
   * Global stored-turn cap across ALL sessions (Feature #5). The absolute row ceiling enforced by the
   * periodic prune so a client cycling its `X-Relais-Session` value can't grow the DB without bound
   * before TTL reclaims those sessions. Clamped to [SESSION_GLOBAL_MAX_TURNS_MIN]..MAX on read+write.
   */
  fun sessionGlobalMaxTurns(context: Context): Int =
    prefs(context).getInt(KEY_SESSION_GLOBAL_MAX_TURNS, SESSION_GLOBAL_MAX_TURNS_DEFAULT)
      .coerceIn(SESSION_GLOBAL_MAX_TURNS_MIN, SESSION_GLOBAL_MAX_TURNS_MAX)

  fun setSessionGlobalMaxTurns(context: Context, value: Int) {
    prefs(context).edit()
      .putInt(
        KEY_SESSION_GLOBAL_MAX_TURNS,
        value.coerceIn(SESSION_GLOBAL_MAX_TURNS_MIN, SESSION_GLOBAL_MAX_TURNS_MAX),
      )
      .apply()
  }
}

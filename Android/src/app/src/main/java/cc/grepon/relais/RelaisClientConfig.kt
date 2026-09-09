/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure builders for Feature #11 (client-config / mDNS). Two surfaces:
 *
 *  1. [buildDiscoveryTxt] — the dynamic mDNS TXT record advertised by [RelaisDiscovery]. Cleartext,
 *     LAN-broadcast — so it carries ONLY non-secret routing metadata (model id, version, port, api
 *     shape, capabilities). The API key is NEVER placed here (see [RelaisClientConfigTest]).
 *  2. [buildClientConfigJson] — the bearer-gated `GET /v1/clientconfig` payload. This is the ONLY
 *     place the raw API key appears, because the endpoint sits behind the bearer gate (the caller
 *     already proved possession of the key to read it).
 *
 * No Android types — JSONObject/JSONArray are org.json, available on the JVM — so every function
 * here is unit-testable hermetically (no Robolectric, no device).
 */
object RelaisClientConfig {

  /**
   * mDNS TXT values are bounded (a single TXT character-string is at most 255 bytes, and DNS-SD
   * convention keeps them short). Cap each emitted value so a pathological model id can't overflow
   * the record or get silently truncated by the resolver. 63 is a safe, conventional ceiling.
   */
  const val MAX_TXT_VALUE_BYTES = 63

  /**
   * Node + model capabilities surfaced to clients. `multimodal` is model-dependent (set from
   * [RelaisEngine.isMultimodal]); `tools` and `reasoning` are node-level — always supported via the
   * native LiteRT-LM API regardless of the model.
   */
  data class Capabilities(val multimodal: Boolean, val tools: Boolean, val reasoning: Boolean) {
    /**
     * Comma-joined list of the ENABLED capability names, in a stable order. A text-only model
     * yields "tools,reasoning"; a multimodal model yields "multimodal,tools,reasoning". Empty
     * string when nothing is enabled (no trailing/leading commas).
     */
    fun toCapsString(): String =
      buildList {
        if (multimodal) add("multimodal")
        if (tools) add("tools")
        if (reasoning) add("reasoning")
      }.joinToString(",")
  }

  /**
   * Caps a single TXT value to [MAX_TXT_VALUE_BYTES] **UTF-8 bytes** (the DNS-SD limit is a byte
   * limit, not a char count), truncating on a code-point boundary so a multi-byte character is never
   * split into an invalid sequence. ASCII values (the common case) behave like a simple length cap.
   */
  private fun capTxt(value: String): String {
    if (value.toByteArray(Charsets.UTF_8).size <= MAX_TXT_VALUE_BYTES) return value
    val sb = StringBuilder()
    var usedBytes = 0
    var i = 0
    while (i < value.length) {
      val cp = value.codePointAt(i)
      val cpChars = Character.charCount(cp)
      val cpBytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
      if (usedBytes + cpBytes > MAX_TXT_VALUE_BYTES) break
      sb.append(value, i, i + cpChars)
      usedBytes += cpBytes
      i += cpChars
    }
    return sb.toString()
  }

  /**
   * Builds the mDNS TXT attribute map for the `_relais._tcp` service. Keys:
   *   model   — the live model id (length-capped)
   *   version — the app version string (length-capped)
   *   https   — the HTTPS port
   *   api     — "openai" (the API shape clients should expect)
   *   path    — "/v1" (the OpenAI-compatible base path)
   *   auth    — "bearer" (clients send `Authorization: Bearer <key>`)
   *   caps    — comma-joined enabled capability names (length-capped)
   *
   * SECURITY: no value here is or contains a secret — the API key never enters this map (TXT is a
   * cleartext LAN broadcast). [RelaisClientConfigTest] pins this with a sentinel-key scan.
   */
  fun buildDiscoveryTxt(
    modelId: String,
    version: String,
    httpsPort: Int,
    caps: Capabilities,
  ): Map<String, String> =
    linkedMapOf(
      "model" to capTxt(modelId),
      "version" to capTxt(version),
      "https" to httpsPort.toString(),
      "api" to "openai",
      "path" to "/v1",
      "auth" to "bearer",
      "caps" to capTxt(caps.toCapsString()),
    )

  /** The unauthenticated CA-export route. One spelling, shared by the note and the `tls` block. */
  private const val CA_ROUTE = "/ca.crt"

  /**
   * Lead-with-cert-import TLS guidance (security-critical).
   *
   * Before feature-18 this note told clients to "import the relais self-signed cert as a trusted
   * CA" — advice that could not be followed: there was no route to fetch a certificate from, and
   * the certificate carried no `subjectAltName`, so importing it would not have made verification
   * work anyway. The note now names a route that exists and a certificate that verifies.
   *
   * The fallback is still stated, still scoped to this one base URL, and still carries the explicit
   * MITM caveat — we never emit guidance that globally disables TLS verification.
   */
  private const val TLS_NOTE =
    "This node serves a certificate issued by its own per-node CA. Preferred: import the CA once " +
      "(GET $CA_ROUTE, or scan the QR on the node's CONFIGURE screen) and pass it per connection, " +
      "e.g. curl --cacert relais-ca.crt. Verify the CA you fetched against the CA FINGERPRINT " +
      "shown on the node before trusting it. The certificate covers every LAN address the node " +
      "holds, so it keeps verifying when the node's IP changes. Fallback only: if you cannot " +
      "import the CA, scope any verify-disable to THIS LAN base URL only — never globally — and " +
      "understand that doing so removes MITM protection for that connection."

  /** Open WebUI configuration block: connection env vars plus the cert-import note. */
  fun buildOpenWebUiBlock(baseUrl: String, apiKey: String): JSONObject =
    JSONObject()
      .put("OPENAI_API_BASE_URL", baseUrl)
      .put("OPENAI_API_KEY", apiKey)
      .put(
        "note",
        "Add this as an OpenAI-compatible connection. $TLS_NOTE",
      )

  /** Continue.dev configuration block: a single-model `models[]` array entry. */
  fun buildContinueDevBlock(baseUrl: String, apiKey: String, modelId: String): JSONObject =
    JSONObject()
      .put(
        "models",
        JSONArray().put(
          JSONObject()
            .put("title", "relais")
            .put("provider", "openai")
            .put("model", modelId)
            .put("apiBase", baseUrl)
            .put("apiKey", apiKey),
        ),
      )

  /** Aider configuration block: env vars to export plus a ready-to-run command line. */
  fun buildAiderBlock(baseUrl: String, apiKey: String, modelId: String): JSONObject =
    JSONObject()
      .put(
        "env",
        JSONObject()
          .put("OPENAI_API_BASE", baseUrl)
          .put("OPENAI_API_KEY", apiKey),
      )
      .put("cmd", "aider --openai-api-base $baseUrl --model openai/$modelId")

  /**
   * Builds the bearer-gated `GET /v1/clientconfig` JSON payload: paste-ready Open WebUI /
   * Continue.dev / Aider configs for [baseUrl], a capability summary, and the self-signed-cert TLS
   * note. This is the ONLY builder that emits the raw [apiKey] — it is safe here precisely because
   * the endpoint is behind the bearer gate.
   */
  fun buildClientConfigJson(
    baseUrl: String,
    apiKey: String,
    modelId: String,
    caps: Capabilities,
    caFingerprint: String? = null,
    nodeKeyPin: String? = null,
  ): JSONObject =
    JSONObject()
      .put("base_url", baseUrl)
      .put("model", modelId)
      .put(
        "capabilities",
        JSONObject()
          .put("multimodal", caps.multimodal)
          .put("tools", caps.tools)
          .put("reasoning", caps.reasoning),
      )
      .put(
        "clients",
        JSONObject()
          .put("open_webui", buildOpenWebUiBlock(baseUrl, apiKey))
          .put("continue_dev", buildContinueDevBlock(baseUrl, apiKey, modelId))
          .put("aider", buildAiderBlock(baseUrl, apiKey, modelId)),
      )
      .put(
        "tls",
        JSONObject()
          // Still true, and still the point: the trust root is the node's own CA, which no public
          // authority vouches for. What changed is that it can now be imported and will verify.
          .put("self_signed", true)
          .put("note", TLS_NOTE)
          .put("ca_url", caUrl(baseUrl))
          .apply {
            // Absent rather than null when the node has not minted yet — a client must not be able
            // to read an empty string as a fingerprint and "check" it.
            caFingerprint?.let { put("ca_fingerprint", it) }
            nodeKeyPin?.let { put("node_key_pin", it) }
          },
      )

  /**
   * The absolute `/ca.crt` URL for a client that already reached [baseUrl].
   *
   * Derived from the base URL rather than rebuilt from an IP, so it names the exact host:port the
   * caller just used — including the loopback case behind `adb forward`, where re-deriving from the
   * LAN address would hand back a URL that host cannot reach.
   */
  private fun caUrl(baseUrl: String): String = baseUrl.removeSuffix("/v1") + CA_ROUTE
}

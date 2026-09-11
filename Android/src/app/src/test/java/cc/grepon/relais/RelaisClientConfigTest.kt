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

import cc.grepon.relais.RelaisClientConfig.Capabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the Feature #11 pure builders (no Context, no Android).
 *
 * The security spine of this feature is "the API key never enters the cleartext mDNS TXT broadcast,
 * and only ever appears in the bearer-gated /v1/clientconfig JSON". A sentinel key is threaded
 * through every builder and scanned for, so a future refactor that leaks the key into the TXT (or
 * unmasks it somewhere) trips a test rather than shipping.
 */
class RelaisClientConfigTest {

  private val sentinelKey = "SENTINEL_SECRET_abc123def456_DO_NOT_LEAK"
  private val textOnlyCaps = Capabilities(multimodal = false, tools = true, reasoning = true)
  private val multimodalCaps = Capabilities(multimodal = true, tools = true, reasoning = true)

  // ---------------------------------------------------------------------------
  // Discovery TXT — content + secret-leakage invariant
  // ---------------------------------------------------------------------------

  @Test
  fun `discovery TXT contains live model id and version`() {
    val txt = RelaisClientConfig.buildDiscoveryTxt(
      modelId = "litert-community/gemma-4-E4B-it",
      version = "1.0.15",
      httpsPort = 8443,
      caps = textOnlyCaps,
    )
    assertEquals("litert-community/gemma-4-E4B-it", txt["model"])
    assertEquals("1.0.15", txt["version"])
    assertEquals("8443", txt["https"])
    assertEquals("openai", txt["api"])
    assertEquals("/v1", txt["path"])
    assertEquals("bearer", txt["auth"])
  }

  @Test
  fun `discovery TXT caps string for a text-only model omits multimodal`() {
    val txt = RelaisClientConfig.buildDiscoveryTxt("m", "v", 8443, textOnlyCaps)
    assertEquals("tools,reasoning", txt["caps"])
    assertFalse("text-only caps must not advertise multimodal", txt["caps"]!!.contains("multimodal"))
  }

  @Test
  fun `discovery TXT caps string for a multimodal model includes multimodal`() {
    val txt = RelaisClientConfig.buildDiscoveryTxt("m", "v", 8443, multimodalCaps)
    assertEquals("multimodal,tools,reasoning", txt["caps"])
  }

  @Test
  fun `discovery TXT never contains the API key in any value`() {
    // The builder has no apiKey parameter at all — but defend against a future signature change by
    // scanning every emitted value for the sentinel. If the key ever leaks into TXT, this fails.
    val txt = RelaisClientConfig.buildDiscoveryTxt(
      modelId = "litert-community/gemma-4-E4B-it",
      version = "1.0.15",
      httpsPort = 8443,
      caps = multimodalCaps,
    )
    for ((k, v) in txt) {
      assertFalse("TXT key '$k' must not equal the API key", v == sentinelKey)
      assertFalse("TXT key '$k' must not contain the API key", v.contains(sentinelKey))
    }
  }

  @Test
  fun `discovery TXT length-caps a pathologically long model id`() {
    val longId = "litert-community/" + "x".repeat(200)
    val txt = RelaisClientConfig.buildDiscoveryTxt(longId, "1.0.15", 8443, textOnlyCaps)
    assertTrue(
      "model value must be capped to MAX_TXT_VALUE_BYTES",
      txt.getValue("model").toByteArray(Charsets.UTF_8).size <= RelaisClientConfig.MAX_TXT_VALUE_BYTES,
    )
  }

  @Test
  fun `discovery TXT caps multi-byte model id on a code-point boundary (bytes not chars)`() {
    // Each CJK char is 3 UTF-8 bytes; 60 of them = 180 bytes, well over the 63-byte cap. The cap
    // must measure bytes, and must not split a character into an invalid (replacement) sequence.
    val multibyte = "模".repeat(60)
    val txt = RelaisClientConfig.buildDiscoveryTxt(multibyte, "1.0.15", 8443, textOnlyCaps)
    val model = txt.getValue("model")
    assertTrue(
      "capped value must be within MAX_TXT_VALUE_BYTES",
      model.toByteArray(Charsets.UTF_8).size <= RelaisClientConfig.MAX_TXT_VALUE_BYTES,
    )
    assertFalse(
      "truncation must not produce a replacement char (split code point)",
      model.contains('�'),
    )
    assertTrue("every retained char must be the intact original", model.all { it == '模' })
  }

  @Test
  fun `discovery TXT caps a 4-byte emoji model id without splitting a surrogate pair`() {
    // Emoji like U+1F600 are SUPPLEMENTARY code points: 2 Java chars (a high+low surrogate pair) and
    // 4 UTF-8 bytes. This is the branch the CJK test above does NOT exercise (charCount == 2). capTxt
    // must advance by whole code points (Character.charCount) so the byte boundary never lands
    // between the surrogates — a lone surrogate is not valid UTF-8 and would corrupt the record.
    // 20 emoji = 80 bytes, comfortably over the 63-byte cap.
    val emoji = "😀" // U+1F600 GRINNING FACE: 4 UTF-8 bytes, charCount = 2
    val txt = RelaisClientConfig.buildDiscoveryTxt(emoji.repeat(20), "1.0.15", 8443, textOnlyCaps)
    val model = txt.getValue("model")
    val bytes = model.toByteArray(Charsets.UTF_8)
    assertTrue(
      "capped emoji value must be within MAX_TXT_VALUE_BYTES",
      bytes.size <= RelaisClientConfig.MAX_TXT_VALUE_BYTES,
    )
    // 63 / 4 = 15 whole emoji fit (60 bytes); the 16th would cross 63, so it is dropped WHOLE.
    assertEquals("must retain whole emoji only (15 * 4 = 60 bytes)", 60, bytes.size)
    assertFalse("no replacement char from a split code point", model.contains('�'))
    // A clean round-trip proves the retained bytes are valid UTF-8 with no lone surrogate left behind.
    assertEquals("retained value must round-trip through UTF-8 intact", model, String(bytes, Charsets.UTF_8))
    assertEquals("every retained code point is the intact emoji", 15, model.codePointCount(0, model.length))
  }

  // ---------------------------------------------------------------------------
  // Capabilities.toCapsString edge cases
  // ---------------------------------------------------------------------------

  @Test
  fun `caps string is empty when nothing is enabled and has no stray commas`() {
    val none = Capabilities(multimodal = false, tools = false, reasoning = false)
    assertEquals("", none.toCapsString())
    val onlyTools = Capabilities(multimodal = false, tools = true, reasoning = false)
    assertEquals("tools", onlyTools.toCapsString())
  }

  // ---------------------------------------------------------------------------
  // Client-config JSON — shape + secret placement
  // ---------------------------------------------------------------------------

  private fun clientConfig() = RelaisClientConfig.buildClientConfigJson(
    baseUrl = "https://192.168.1.42:8443/v1",
    apiKey = sentinelKey,
    modelId = "litert-community/gemma-4-E4B-it",
    caps = textOnlyCaps,
  )

  @Test
  fun `client config exposes base_url model and capabilities`() {
    val json = clientConfig()
    assertEquals("https://192.168.1.42:8443/v1", json.getString("base_url"))
    assertEquals("litert-community/gemma-4-E4B-it", json.getString("model"))
    val caps = json.getJSONObject("capabilities")
    assertFalse(caps.getBoolean("multimodal"))
    assertTrue(caps.getBoolean("tools"))
    assertTrue(caps.getBoolean("reasoning"))
  }

  @Test
  fun `open_webui block has OPENAI_API_BASE_URL and OPENAI_API_KEY`() {
    val ow = clientConfig().getJSONObject("clients").getJSONObject("open_webui")
    assertEquals("https://192.168.1.42:8443/v1", ow.getString("OPENAI_API_BASE_URL"))
    assertEquals(sentinelKey, ow.getString("OPENAI_API_KEY"))
    assertTrue("open_webui must carry a note", ow.has("note"))
  }

  @Test
  fun `continue_dev block has a models array with the connection fields`() {
    val cd = clientConfig().getJSONObject("clients").getJSONObject("continue_dev")
    val models = cd.getJSONArray("models")
    assertEquals(1, models.length())
    val m = models.getJSONObject(0)
    assertEquals("litert-community/gemma-4-E4B-it", m.getString("model"))
    assertEquals("https://192.168.1.42:8443/v1", m.getString("apiBase"))
    assertEquals(sentinelKey, m.getString("apiKey"))
    assertTrue(m.has("title"))
    assertTrue(m.has("provider"))
  }

  @Test
  fun `aider block has env vars and a cmd`() {
    val aider = clientConfig().getJSONObject("clients").getJSONObject("aider")
    val env = aider.getJSONObject("env")
    assertEquals("https://192.168.1.42:8443/v1", env.getString("OPENAI_API_BASE"))
    assertEquals(sentinelKey, env.getString("OPENAI_API_KEY"))
    assertTrue("aider must carry a runnable cmd", aider.getString("cmd").contains("aider"))
  }

  @Test
  fun `tls note leads with cert import and never globally disables verification`() {
    val tls = clientConfig().getJSONObject("tls")
    assertTrue("self_signed flag must be true", tls.getBoolean("self_signed"))
    val note = tls.getString("note").lowercase()
    // Preferred path leads with importing the cert as a trusted CA.
    assertTrue("tls note must mention importing the cert", note.contains("import"))
    assertTrue("tls note must mention the cert", note.contains("cert"))
    val importIdx = note.indexOf("import")
    // "import" must appear before any "fallback" mention — cert import is the lead, not an aside.
    val fallbackIdx = note.indexOf("fallback")
    if (fallbackIdx >= 0) {
      assertTrue("cert import must lead the note (before the verify-disable fallback)", importIdx < fallbackIdx)
    }
    // The scoped fallback must carry an explicit MITM caveat.
    assertTrue("tls note must include an explicit MITM caveat", note.contains("mitm"))
    // Must NOT instruct globally disabling verification. We forbid the affirmative dangerous
    // phrasings (a directive to turn TLS verification off everywhere). The note legitimately uses
    // "never globally" as a prohibition, so we match the dangerous DIRECTIVE forms specifically.
    assertFalse("must not direct globally disabling verification", note.contains("globally disable"))
    assertFalse("must not direct disabling verification globally", note.contains("disable verification globally"))
    assertFalse("must not direct disabling tls verification globally", note.contains("disable tls verification globally"))
    assertFalse("must not recommend the insecure curl -k flag", note.contains("curl -k"))
  }

  @Test
  fun `client config JSON top level contains the API key exactly in the client blocks`() {
    // The key is allowed here (bearer-gated endpoint). Confirm it is present — this is the single
    // surface that legitimately carries it — so a refactor that drops it (breaking paste-readiness)
    // also trips a test.
    val serialized = clientConfig().toString()
    assertTrue("client config must carry the api key for paste-readiness", serialized.contains(sentinelKey))
  }

  // ---------------------------------------------------------------------------
  // CA export surface (feature-18)
  // ---------------------------------------------------------------------------

  @Test
  fun `tls block names the ca route as an absolute URL derived from the base URL`() {
    val tls = clientConfig().getJSONObject("tls")

    // Derived from the base URL, not rebuilt from a LAN address: behind `adb forward` the caller
    // reached localhost, and handing back a LAN URL would name a host it cannot reach.
    assertEquals("https://192.168.1.42:8443/ca.crt", tls.getString("ca_url"))
  }

  @Test
  fun `tls note names the ca route and the cacert flag, and still refuses curl -k`() {
    val note = clientConfig().getJSONObject("tls").getString("note")

    assertTrue("the note must name the route that actually exists", note.contains("/ca.crt"))
    assertTrue("the note must lead with per-connection --cacert", note.contains("--cacert"))
    // Pre-existing guarantee, restated here because this test owns the rewritten note.
    assertFalse("must not recommend the insecure curl -k flag", note.lowercase().contains("curl -k"))
  }

  /**
   * The note must not promise capabilities this release does not ship, and must not repeat the
   * IP-change overclaim that hardware disproved.
   *
   * Both failures were live in shipped client-facing copy. The QR and the on-device CERTIFICATE
   * section are a deferred follow-up, so telling a user to "scan the QR" pointed at nothing; and
   * "keeps verifying when the node's IP changes" is false for a *running* node, which is exactly
   * when a user would rely on it. Re-issue happens at node start plus one boot-time shot.
   *
   * Restore the QR wording only alongside the QR.
   */
  @Test
  fun `tls note promises nothing this release does not ship`() {
    val note = clientConfig().getJSONObject("tls").getString("note").lowercase()

    assertFalse("the QR ships in a later release; do not point users at it", note.contains("qr"))
    assertFalse(
      "re-issue is at node start, not on a live address change",
      note.contains("keeps verifying when the node's ip changes"),
    )
    // `run-as` cannot read a non-debuggable package and would emit an encrypted PKCS12 the user
    // has no password for. Two rewrites produced two unperformable instructions; the third
    // attempt was to stop writing instructions.
    assertFalse("do not tell users to run an adb command that cannot work", note.contains("adb"))

    // The honest replacements, so this can't be satisfied by simply deleting the claims.
    assertTrue("must name the trust-on-first-use gap outright", note.contains("trust on first use"))
    assertTrue(
      "must say the status-page fingerprint does not close the first-fetch gap",
      note.contains("does not help") || note.contains("not help"),
    )
    assertTrue("must say a running node does not pick up an address change", note.contains("restart"))
  }

  /**
   * The note must also say what the feature **does** buy. An honest limitation with no counterweight
   * reads as "TLS here is not trustworthy", which is a bigger retreat than the truth: the gap is the
   * first fetch only, and a user who imports over a network they trust has the full property today.
   */
  @Test
  fun `tls note states the protection gained, not only the gap`() {
    val note = clientConfig().getJSONObject("tls").getString("note").lowercase()

    // NOT a bare "mitm protection" check: the fallback paragraph warns that disabling verification
    // *removes* MITM protection, so that phrase alone is satisfied by a note that states only the
    // downside. Proven by mutation — deleting the gained-protection sentence left such a check
    // green. Pin the claim that protection is gained on connections AFTER the first fetch.
    assertTrue(
      "must say protection applies to connections after the first fetch",
      note.contains("every subsequent connection"),
    )
    assertTrue(
      "must scope the gap to the first fetch rather than to TLS generally",
      note.contains("first fetch") || note.contains("first use"),
    )
    assertTrue(
      "must tell the user the actionable mitigation",
      note.contains("network you trust"),
    )
  }

  @Test
  fun `both fingerprints are exposed under distinct keys when the node has minted`() {
    val tls =
      RelaisClientConfig.buildClientConfigJson(
          baseUrl = "https://192.168.1.42:8443/v1",
          apiKey = sentinelKey,
          modelId = "litert-community/gemma-4-E4B-it",
          caps = textOnlyCaps,
          caFingerprint = "sha256/CA-VALUE",
          nodeKeyPin = "sha256//LEAF-VALUE",
        )
        .getJSONObject("tls")

    assertEquals("sha256/CA-VALUE", tls.getString("ca_fingerprint"))
    assertEquals("sha256//LEAF-VALUE", tls.getString("node_key_pin"))
    // Distinct keys for distinct values: --pinnedpubkey wants the leaf, --cacert verification wants
    // the CA, and conflating them fails with an error that names neither.
    assertNotEquals(tls.getString("ca_fingerprint"), tls.getString("node_key_pin"))
  }

  @Test
  fun `fingerprint keys are absent, not empty, before the node has minted`() {
    val tls = clientConfig().getJSONObject("tls")

    // An empty string would let a client "check" a fingerprint against nothing and believe it
    // passed. Absent forces the caller to handle not-yet-minted.
    assertFalse("ca_fingerprint must be absent", tls.has("ca_fingerprint"))
    assertFalse("node_key_pin must be absent", tls.has("node_key_pin"))
  }
}

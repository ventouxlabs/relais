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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hardware proof that the #220 `Incompatible` 404 is actually WIRED, not merely formatted.
 *
 * ### Why this exists
 * [RelaisModelSwapTest] pins which [ModelRequestOutcome] `resolveModelRequest` returns, and it pins
 * the text [incompatibleModelMessage] produces. Neither proves the server calls it: the branch lives
 * in `RelaisHttpServer.rejectIfModelUnavailable`, which is private and takes a [Socket], so no JVM
 * test can reach it. Delete the reason from that 404 and every device-free test still passes. This
 * probe closes exactly that gap by reading the bytes off the wire.
 *
 * ### How the branch is reached
 * `Incompatible` needs the requested model to be in the provisioned registry AND measured-bad. Since
 * #236/#237 gate provisioning, a known-bad model can no longer BE provisioned — so the probe
 * synthesizes the legacy state it protects against: a registry entry pointing at a placeholder file.
 * `provisionedOnDisk()` prunes on `File(path).exists()` only, so the placeholder need not be a real
 * model; the request is refused before anything tries to load it. The registry is saved and restored
 * in a `finally`, so the node's real inventory survives a failure.
 *
 * The resident model must be a DIFFERENT, loadable one — `resolveModelRequest` short-circuits to
 * ServeResident when the requested id matches the resident, and returns early unless the engine is
 * ready.
 *
 * ### Resolving the instrument target
 * `cc.grepon.relais` is the NAMESPACE, not an applicationId. `build.gradle.kts` sets the appId per
 * channel (`com.ventouxlabs.relais` / `.izzy` / `.degoogled`), so the runner target and the model's
 * data dir both follow the variant you installed — `cc.grepon.relais.test` resolves only to a
 * pre-rebrand leftover, and instrumenting it fails with ClassNotFoundException. Resolve both first:
 *   adb -s <serial> shell pm list packages | grep -E 'relais|ventoux'
 *   adb -s <serial> shell find /storage/emulated/0/Android/data/<appId>/files -name '*.litertlm'
 *
 * Verified run (comet / Pixel 9 Pro Fold, `fullOpen`, E4B staged) — PASS in 19.7 s:
 *   adb -s 4A111FDKD0000C shell am instrument -w \
 *     -e class cc.grepon.relais.IncompatibleModel404Probe \
 *     -e RELAIS_PROBE 1 \
 *     -e model /storage/emulated/0/Android/data/com.ventouxlabs.relais.izzy/files/relais/gemma-4-E4B-it.litertlm \
 *     com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class IncompatibleModel404Probe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  /** The measured-incompatible repo id from the #220 table. */
  private val knownBad = "litert-community/Qwen2.5-1.5B-Instruct"

  @Test
  fun aMeasuredIncompatibleModelIsRefused404WithTheReasonOnTheWire() {
    assumeTrue("pass -e RELAIS_PROBE 1 to run this hardware probe", args.getString("RELAIS_PROBE") == "1")
    val modelPath = args.getString("model")
    assumeTrue("pass -e model <a readable, LOADABLE .litertlm>", modelPath != null && File(modelPath).canRead())

    val reason =
      requireNotNull(RelaisRuntimeCompat.incompatibleReason(knownBad)) {
        "fixture drift: $knownBad is no longer in the measured-incompatible table"
      }

    RelaisEngine.ensureInitialized(context, requireNotNull(modelPath), RelaisConfig.modelId(context))
    assertTrue("engine not ready after init — the gate returns early and never 404s", RelaisEngine.isReady)
    assertFalse(
      "resident must NOT be the known-bad model, or the request short-circuits to ServeResident",
      RelaisEngine.residentModelId == knownBad,
    )

    val savedRegistry = RelaisConfig.provisionedModels(context)
    val placeholder = File(context.filesDir, "relais-probe-incompatible-placeholder.bin")
    val server = RelaisHttpServer(context, port = PORT, tls = false, bindAddr = "127.0.0.1")

    try {
      placeholder.writeBytes(ByteArray(1))
      RelaisConfig.setProvisionedModels(
        context,
        savedRegistry + ProvisionedModel(knownBad, placeholder.absolutePath, "probe-known-bad"),
      )

      server.start()
      Thread.sleep(300)

      val body = JSONObject().put("model", knownBad).put("stream", false).put(
        "messages",
        org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
      ).toString()
      val response = post(body, RelaisConfig.apiKey(context))

      Log.i(TAG, "status=${response.statusLine} body=${response.body.take(400)}")
      assertTrue(
        "a measured-incompatible model must be refused 404, got: ${response.statusLine}",
        response.statusLine.contains(" 404 "),
      )

      val error = JSONObject(response.body).getJSONObject("error")
      val message = error.getString("message")

      // The wiring assertion: the body must be EXACTLY what the extracted formatter produces. If
      // rejectIfModelUnavailable stops calling it, or the reason stops reaching the wire, this fails.
      assertEquals(
        "the 404 body must come from incompatibleModelMessage",
        incompatibleModelMessage(knownBad, reason),
        message,
      )
      // The case's whole reason for existing: this is NOT the missing-model diagnosis. The file is
      // present; re-downloading cannot help, so saying "not provisioned" would send the caller down
      // the wrong path.
      assertFalse(
        "must not read as a missing model, got: $message",
        message.contains("not provisioned"),
      )
      assertEquals("model_not_found", error.getString("code"))
    } finally {
      server.stop()
      RelaisConfig.setProvisionedModels(context, savedRegistry)
      placeholder.delete()
    }

    assertEquals(
      "the node's real registry must be restored exactly",
      savedRegistry.map { it.modelId },
      RelaisConfig.provisionedModels(context).map { it.modelId },
    )
  }

  private data class HttpResult(val statusLine: String, val body: String)

  private fun post(json: String, key: String?): HttpResult {
    val head =
      "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $key\r\n" +
        "Content-Type: application/json\r\nContent-Length: ${json.toByteArray().size}\r\nConnection: close\r\n\r\n"
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", PORT), 5_000)
      socket.soTimeout = RESPONSE_TIMEOUT_MS
      socket.getOutputStream().apply { write((head + json).toByteArray()); flush() }
      val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
      val statusLine = reader.readLine() ?: ""
      while (reader.readLine()?.isNotEmpty() == true) Unit
      return HttpResult(statusLine, reader.readText())
    }
  }

  private companion object {
    const val PORT = 18098
    const val RESPONSE_TIMEOUT_MS = 30_000
    const val TAG = "RelaisIncompatible404Probe"
  }
}

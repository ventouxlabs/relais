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

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import java.security.MessageDigest

private const val TAG = "RelaisControl"

/** Constant-time check of the presented adb token against the node's API key. */
internal fun controlTokenMatches(presented: String?, apiKey: String): Boolean =
  presented != null && MessageDigest.isEqual(presented.toByteArray(), apiKey.toByteArray())

/**
 * Key-gated adb trampoline for the Relais node — no UI, no launcher icon. Honors
 * `--es cmd start|stop|rotate_key` (and optional `--es modelId <id>` / `--es hfToken <t>`) gated behind the
 * node's API key, then forwards to [MainActivity], which hosts the unified shell (the control
 * panel now lives in the shell's dashboard destination, not here).
 *
 *   adb shell am start -n <appId>/cc.grepon.relais.RelaisControlActivity \
 *     --es cmd start --es token <apiKey> [--es modelId <allowlistId>] [--es hfToken <hfToken>]
 */
class RelaisControlActivity : ComponentActivity() {
  private fun handleCmd(intent: Intent?) {
    val cmd = intent?.getStringExtra("cmd") ?: return
    // Gate external start/stop: reject unless the caller presents the node's API key. Prevents a
    // co-installed app from toggling the service (loading a multi-GB model, binding the endpoint).
    if (!controlTokenMatches(intent.getStringExtra("token"), RelaisConfig.apiKey(this))) {
      Log.w(TAG, "Ignoring cmd=$cmd from intent: missing/invalid token")
      return
    }
    intent.getStringExtra("modelId")?.takeIf { it.isNotBlank() }?.let { RelaisConfig.setModelId(this, it) }
    intent.getStringExtra("hfToken")?.let { RelaisConfig.setHfToken(this, it.ifBlank { null }) }
    when (cmd) {
      "start" -> RelaisNodeService.start(this)
      "stop" -> RelaisNodeService.stop(this)
      // Invalidates the presented token; the new key is shown in the dashboard's control panel.
      "rotate_key" -> RelaisConfig.rotateApiKey(this)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleCmd(intent)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The control panel now lives in the unified shell (MainActivity NavHost). This
    // activity is kept only as the key-gated adb `--es cmd start|stop` trampoline; it
    // forwards to the shell and finishes so there is a single UI entry point.
    handleCmd(intent)
    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    finish()
  }
}

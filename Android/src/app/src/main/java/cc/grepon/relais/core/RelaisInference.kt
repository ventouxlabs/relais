/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */

package cc.grepon.relais.core

import android.content.Context
import android.net.Uri
import android.util.Log
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.RelaisRequest
import cc.grepon.relais.ThermalGovernor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "RelaisInference"

/**
 * In-process one-shot inference for NON-HTTP callers (tiles, widgets, share, NFC, triage, Tasker).
 * Wraps [RelaisEngine.generate].
 *
 * CRITICAL CONTRACT: this is a *consumer* of an already-resident engine. It does NOT cold-start or
 * provision a multi-GB model without the service-backed idle exception below — no download/init with no
 * foreground service behind it (which the OS would OOM-kill). When the engine isn't ready it fails
 * fast and **eagerly** with [NodeNotReadyException] (thrown by the call itself, before the Flow is
 * returned), so callers surface "node not running" instead of hanging or provisioning.
 *
 * EXCEPTION to "never cold-start": [RelaisEngine.wasIdleUnloaded] being true PROVES the foreground
 * service is alive right now — idle-TTL auto-unload (#178) only ever runs from a ticker owned by
 * [cc.grepon.relais.RelaisNodeService] itself, so if the engine was idle-unloaded, the OOM-kill
 * concern above (no service behind the reload) cannot apply. In that specific case this still fails
 * fast with [NodeNotReadyException] on THIS call (never blocks a UI tap on a multi-second reload),
 * but also kicks a background reload (provisioning configured weights if absent) so the tap that follows self-heals instead of surfacing "not
 * running" indefinitely until the caller happens to hit a text endpoint (#178 review finding — before
 * this, tile/share/Tasker/NFC/widget callers had no path back to ready after an idle-unload).
 */
object RelaisInference {

  class NodeNotReadyException : IllegalStateException("Relais node is not running")

  fun isReady(): Boolean = RelaisEngine.isReady

  /**
   * Streams the visible answer deltas. Reasoning ("thinking") deltas are intentionally dropped here
   * (UI surfaces want the answer, not the chain-of-thought; the HTTP path exposes reasoning_content).
   * The blocking engine call runs on [Dispatchers.IO]; tokens are delivered with back-pressure
   * (`trySendBlocking`) so none are dropped. Throws [NodeNotReadyException] eagerly if not ready.
   */
  fun complete(
    context: Context,
    prompt: String,
    system: String? = null,
    images: List<Uri> = emptyList(),
  ): Flow<String> {
    if (!RelaisEngine.isReady) {
      if (RelaisEngine.wasIdleUnloaded) RelaisEngine.ensureInitializedInBackground(context) // self-heal (#178)
      throw NodeNotReadyException() // EAGER: before returning the Flow — this call still fails fast
    }
    val request = RelaisRequest(
      text = prompt,
      systemPrompt = system,
      imagePng = images.firstOrNull()?.let { readPng(context, it) }, // single image (v1)
    )
    return callbackFlow {
      val job = launch(Dispatchers.IO) {
        try {
          // Re-assert readiness after the dispatch gap: if the node shut down between the eager guard
          // and here, fail fast instead of letting generate() cold-start the engine from a UI tap.
          if (!RelaisEngine.isReady) {
            if (RelaisEngine.wasIdleUnloaded) RelaisEngine.ensureInitializedInBackground(context) // self-heal (#178)
            throw NodeNotReadyException()
          }
          RelaisEngine.generate(
            context = context,
            request = request,
            onToken = { delta -> trySendBlocking(delta) }, // paces the producer to the collector
            // Parity with the HTTP paths (which pass ThermalGovernor.shouldTruncate): honor
            // thermal-critical truncation (device protection), and stop at the next token boundary
            // once the collector cancels — releasing the single engine lock promptly rather than
            // letting an abandoned decode hold it (and block HTTP inference) until the 120s timeout.
            shouldCancel = { !isActive || ThermalGovernor.shouldTruncate() },
          )
          close()
        } catch (t: Throwable) {
          close(t)
        }
      }
      awaitClose { job.cancel() }
    }
  }

  /**
   * Collects [complete] to the full answer text. The primary API for non-streaming callers. Forwards
   * [images] so non-streaming multimodal callers don't silently lose the image.
   */
  suspend fun completeText(
    context: Context,
    prompt: String,
    system: String? = null,
    images: List<Uri> = emptyList(),
  ): String = buildString { complete(context, prompt, system, images).collect { append(it) } }

  private fun readPng(context: Context, uri: Uri): ByteArray? =
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
      .onFailure { Log.w(TAG, "Failed to read image for inference", it) } // never silently swallow
      .getOrNull()
}

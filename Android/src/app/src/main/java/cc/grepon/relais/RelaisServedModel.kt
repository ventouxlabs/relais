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

package cc.grepon.relais

import org.json.JSONObject

/*
 * The served-model check (#352). [resolveModelRequest] classifies a request BEFORE the engine lock;
 * the answer is produced AFTER it. A swap to X that wins the lock in between makes X resident, and a
 * request classified for Y would then be answered by X under Y's name. Only a check under the lock
 * can see what actually serves, so [RelaisEngine.generate] compares [RelaisRequest.expectedModelId]
 * against `residentModelId` there and throws [ModelNotResidentException] on a mismatch. The HTTP layer
 * turns that into a 503 + Retry-After, which is only possible because [SseWriter] commits lazily:
 * a streaming request that fails before its first event has written nothing.
 *
 * Pure JVM (no Context, no Engine) — mirrors [resolveModelRequest] in RelaisModelSwap.kt.
 */

/**
 * The client's RAW `model` field as both the classifier and the check read it: `null` when absent or
 * blank — meaning "whatever is resident". One function so the two cannot drift: a check that
 * normalised differently from the classifier would 503 requests the classifier just served. (Exactly
 * the expression both chat handlers used before #352; a JSON `null` is left to org.json, unchanged.)
 */
internal fun requestedModelId(body: JSONObject): String? =
  body.optString("model", "").takeIf { it.isNotBlank() }

/**
 * True iff the request named a model ([expected] non-null) and something else is [resident]. A null
 * [expected] never mismatches: the client omitted `model`, so whatever serves is what it asked for.
 */
fun servedModelMismatch(expected: String?, resident: String?): Boolean =
  expected != null && expected != resident

/** Thrown under the engine lock when the resident model is not the one the request was classified for. */
class ModelNotResidentException(val expected: String, val resident: String?) :
  IllegalStateException("requested model '$expected' is not resident (resident: ${resident ?: "none"})")

// Which stream failures unwind to the dispatcher's 503 is decided by `isUncommittedRetryable` in
// RelaisModelPresence.kt — shared with #362's ModelNotOnDiskException, so the lanes route both alike.

/**
 * The 503 body for a [ModelNotResidentException] or a [ModelNotOnDiskException], in the envelope of the lane that raised it. Keyed on
 * the endpoint label because the answer is written by the dispatcher's catch, which knows only that.
 */
internal fun modelNotResidentBody(endpoint: String, message: String): JSONObject =
  if (endpoint == "/v1/messages") {
    buildAnthropicError(message, "overloaded_error")
  } else {
    RelaisError.json(message, RelaisError.SERVICE_UNAVAILABLE)
  }

/**
 * The `model` a STREAMING response echoes, resolved once, at its first event (#352). The client's own
 * id when it sent one (the OpenAI drop-in contract #192); otherwise [served], read at that moment —
 * which the handler points at `residentModelId` while an engine callback is running. The callback
 * thread does not hold the engine lock itself: `generate`'s thread holds it for the whole decode, and
 * `residentModelId`'s only writer needs that lock, so the value cannot change mid-stream and was
 * already checked against the request — it IS the model answering. (Change how `generate` hands
 * tokens across threads and this reasoning must be redone.) Otherwise [served] points at the returned
 * [RelaisResult.servedModelId] when the first event is written after `generate` (a zero-token stream).
 * Reading it any earlier — before the lock, as the chunks used to — can name a model that was swapped
 * or idle-unloaded away before this request ran. [fallback] only when nothing is known. `lazy` is
 * synchronized, so every chunk echoes one value even across the callback and handler threads.
 */
internal class StreamEchoModel(
  private val requested: String?,
  private val fallback: String,
  private val served: () -> String?,
) {
  val id: String by lazy { requested ?: served() ?: fallback }
}

/** The client-facing 503 text: the model changed under the request; a retry is decided afresh. */
internal const val MODEL_NOT_RESIDENT_MESSAGE =
  "the resident model changed before this request ran; retry shortly"

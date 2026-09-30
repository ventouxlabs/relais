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

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64 as JvmBase64

private const val TAG = "RelaisAnthropicParser"

/**
 * Pure functions for the Anthropic Messages API (`POST /v1/messages`, issue #179) wire shape.
 *
 * Anthropic's request/response shape differs enough from OpenAI's (nested content-block arrays
 * instead of a flat parts list, tool results folded into a `user` message's content rather than a
 * separate `role:"tool"` message, a flatter tool schema) that a parallel implementation is clearer
 * than bolting Anthropic parsing onto [RelaisOpenAiParser]'s `buildPromptParts`. This file mirrors
 * that file's structure/signature style (pure, no Android types beyond `android.util.Log` for a
 * parity warning, JVM-testable) and reuses its shared types ([ParsedTurn], [ToolResult], [ToolSpec],
 * [ToolChoice], [MAX_HISTORY_CHARS]) rather than redefining them.
 */

/**
 * Result of parsing an Anthropic `messages[]` array (mirrors OpenAI's `ParsedMessages`).
 *
 * There is no `lastUserAudio` — Anthropic's Messages API has no audio-input content-block type.
 */
data class ParsedAnthropicMessages(
  val systemPrompt: String?,
  val history: List<ParsedTurn>,
  val lastUserText: String,
  val lastUserImage: ByteArray? = null,
  /** Trailing `tool_result` content blocks (from the last message) that drive the live turn. */
  val liveToolResults: List<ToolResult> = emptyList(),
)

/**
 * Normalizes Anthropic's `system` field, which may be a plain string OR an array of
 * `{"type":"text","text":...}` blocks, to a single nullable string. Blank/absent -> null.
 */
private fun normalizeAnthropicSystem(system: Any?): String? =
  when (system) {
    is String -> system.takeIf { it.isNotBlank() }
    is JSONArray -> {
      val sb = StringBuilder()
      for (i in 0 until system.length()) {
        val block = system.optJSONObject(i) ?: continue
        if (block.optString("type") == "text") sb.append(block.optString("text"))
      }
      sb.toString().takeIf { it.isNotBlank() }
    }
    else -> null
  }

/** True when a message's `content` blocks array contains at least one `tool_result` block. */
private fun hasToolResultBlock(content: JSONArray): Boolean {
  for (i in 0 until content.length()) {
    if (content.optJSONObject(i)?.optString("type") == "tool_result") return true
  }
  return false
}

/**
 * Flattens a `tool_result` block's `content` (plain string OR an array of `{"type":"text",...}`
 * blocks) to a single string.
 */
private fun toolResultContent(block: JSONObject): String =
  when (val content = block.opt("content")) {
    is String -> content
    is JSONArray -> {
      val sb = StringBuilder()
      for (i in 0 until content.length()) {
        val part = content.optJSONObject(i) ?: continue
        if (part.optString("type") == "text") sb.append(part.optString("text"))
      }
      sb.toString()
    }
    else -> ""
  }

/**
 * Extracts text + first image + `tool_use` calls from a user/assistant content-blocks array.
 * `tool_result` blocks are NOT handled here (only ever appear in a `user` message and are resolved
 * by the caller, which already knows whether this message is the live trailing tool-result turn).
 *
 * [decode] base64-decodes an `image` block's `source.data`. Anthropic's inline image data is always
 * plain base64 (never a `data:` URI, unlike OpenAI's `image_url.url`), so no URL-stripping is needed.
 */
private fun extractAnthropicParts(
  content: JSONArray,
  decode: (String) -> ByteArray,
): Triple<String, ByteArray?, List<ParsedToolCall>> {
  var text = ""
  var image: ByteArray? = null
  val toolCalls = mutableListOf<ParsedToolCall>()
  for (i in 0 until content.length()) {
    val block = content.optJSONObject(i) ?: continue
    when (block.optString("type")) {
      "text" -> text += block.optString("text")
      "image" ->
        block.optJSONObject("source")?.optString("data")?.takeIf { it.isNotEmpty() }?.let {
          image = runCatching { decode(it) }.getOrNull() ?: image
        }
      "tool_use" -> {
        val id = block.optString("id")
        val name = block.optString("name")
        if (name.isNotEmpty()) {
          val input = block.optJSONObject("input")?.toString() ?: "{}"
          toolCalls.add(ParsedToolCall(id = id, name = name, argumentsJson = input))
        }
      }
      // "tool_result" is only ever a USER-message block; handled by the caller.
    }
  }
  return Triple(text, image, toolCalls)
}

/**
 * Pure function: walks the full Anthropic `messages[]` array and returns a [ParsedAnthropicMessages]
 * with the same shape/semantics as OpenAI's `buildPromptParts`:
 *  - [ParsedAnthropicMessages.systemPrompt]: the normalized top-level `system` field.
 *  - [ParsedAnthropicMessages.history]: all turns before the live turn, in order.
 *  - [ParsedAnthropicMessages.lastUserText]/[ParsedAnthropicMessages.lastUserImage]: the content of
 *    the final live user turn — only set when the last message is a NORMAL user turn (not a trailing
 *    tool-result turn). Only the last message's image is ever surfaced here ("last wins"), matching
 *    the OpenAI parser's behavior exactly; earlier images remain attached to their own history turn.
 *  - [ParsedAnthropicMessages.liveToolResults]: populated when the last message is a `user` message
 *    whose content contains `tool_result` blocks — Anthropic bundles an ENTIRE tool round-trip's
 *    results into ONE message's content array (unlike OpenAI's multiple sequential `role:"tool"`
 *    messages), so there is no multi-message "run" to detect here, only a single trailing message to
 *    check. When present, the whole message is treated as the tool-result turn: no user text is
 *    promoted from it, mirroring OpenAI's `hasTrailingToolRun` full-suppression behavior exactly.
 *
 * No Android types beyond [Log] (parity warning only) — pure JVM, unit-testable without a device.
 *
 * @param system  the top-level Anthropic `system` field (`body.opt("system")`): string, array, or
 *   absent (null).
 * @param decode  lambda to base64-decode an image block's `source.data` (injected for testability;
 *   production callers pass [RelaisHttpServer]'s `android.util.Base64`-backed decoder).
 */
internal fun buildAnthropicPromptParts(
  system: Any?,
  messages: JSONArray,
  maxHistoryChars: Int = MAX_HISTORY_CHARS,
  decode: (String) -> ByteArray = { b64 -> JvmBase64.getDecoder().decode(b64) },
): ParsedAnthropicMessages {
  val systemPrompt = normalizeAnthropicSystem(system)

  // tool_use id -> name map, scanned across ALL assistant messages. A later tool_result block
  // references the call it answers via tool_use_id; resolve names through this map.
  val toolUseNames = mutableMapOf<String, String>()
  for (i in 0 until messages.length()) {
    val msg = messages.optJSONObject(i) ?: continue
    if (msg.optString("role") != "assistant") continue
    val content = msg.opt("content") as? JSONArray ?: continue
    for (j in 0 until content.length()) {
      val block = content.optJSONObject(j) ?: continue
      if (block.optString("type") == "tool_use") {
        val id = block.optString("id")
        val name = block.optString("name")
        if (id.isNotEmpty() && name.isNotEmpty()) toolUseNames[id] = name
      }
    }
  }

  val lastIndex = messages.length() - 1
  val lastMsg = if (lastIndex >= 0) messages.optJSONObject(lastIndex) else null
  val lastContent = lastMsg?.opt("content") as? JSONArray
  val hasTrailingToolRun =
    lastMsg?.optString("role") == "user" && lastContent != null && hasToolResultBlock(lastContent)

  var lastUserText = ""
  var lastUserImage: ByteArray? = null
  val liveToolResults = mutableListOf<ToolResult>()
  val history = mutableListOf<ParsedTurn>()

  for (i in 0 until messages.length()) {
    val msg = messages.optJSONObject(i) ?: continue
    val role = msg.optString("role")
    if (role != "user" && role != "assistant") continue // unknown roles silently skipped

    if (hasTrailingToolRun && i == lastIndex) {
      val content = lastContent ?: continue
      for (j in 0 until content.length()) {
        val block = content.optJSONObject(j) ?: continue
        if (block.optString("type") == "tool_result") {
          val name = toolUseNames[block.optString("tool_use_id")].orEmpty()
          liveToolResults.add(ToolResult(name = name, content = toolResultContent(block)))
        }
      }
      continue // fully suppressed from history/live-user-text
    }

    val isLiveUserTurn = role == "user" && i == lastIndex && !hasTrailingToolRun

    when (val content = msg.opt("content")) {
      is String ->
        if (isLiveUserTurn) lastUserText = content else history.add(ParsedTurn(role = role, text = content))

      is JSONArray -> {
        // An EARLIER (non-trailing) user message may itself carry tool_result blocks from a prior
        // round-trip. Anthropic bundles a round-trip's results into one message's content array
        // (unlike OpenAI's multiple sequential role:"tool" messages), so surface one role:"tool"
        // ParsedTurn per block here — mirroring OpenAI's non-trailing role:"tool" history handling.
        if (role == "user" && !isLiveUserTurn && hasToolResultBlock(content)) {
          for (j in 0 until content.length()) {
            val block = content.optJSONObject(j) ?: continue
            if (block.optString("type") == "tool_result") {
              val name = toolUseNames[block.optString("tool_use_id")].orEmpty()
              history.add(ParsedTurn(role = "tool", text = toolResultContent(block), toolName = name))
            }
          }
          continue
        }
        val (text, image, toolCalls) = extractAnthropicParts(content, decode)
        when {
          isLiveUserTurn -> {
            lastUserText = text
            lastUserImage = image
          }
          // A pure tool_use turn (no visible text) is fully represented by the tool-result turn(s)
          // that answer it (added above, or by the trailing-tool-run branch) — mirroring OpenAI's
          // non-trailing role:"tool" handling, which likewise doesn't surface a separate assistant
          // entry for the call itself. An assistant turn that mixes real text with a tool_use block
          // still needs to appear in history.
          role == "assistant" && text.isBlank() && toolCalls.isNotEmpty() -> {}
          role == "assistant" ->
            history.add(ParsedTurn(role = role, text = text, imagePng = image, toolCalls = toolCalls))
          else -> history.add(ParsedTurn(role = role, text = text, imagePng = image))
        }
      }

      else -> if (!isLiveUserTurn) history.add(ParsedTurn(role = role, text = ""))
    }
  }

  // Normalize to user-first: drop an orphan leading assistant turn (no preceding user turn), matching
  // buildPromptParts' identical guard — the seeded conversation must start with a user turn.
  val userFirst = history.dropWhile { it.role != "user" }
  val truncated = applyAnthropicHistoryTruncation(userFirst, maxHistoryChars)

  return ParsedAnthropicMessages(
    systemPrompt = systemPrompt,
    history = truncated,
    lastUserText = lastUserText,
    lastUserImage = lastUserImage,
    liveToolResults = liveToolResults,
  )
}

/**
 * Drops oldest turns from [history] until the total char count fits [maxHistoryChars]. Identical
 * policy to `RelaisOpenAiParser.kt`'s private `applyHistoryTruncation` (same [MAX_HISTORY_CHARS]
 * budget, same oldest-first user+assistant pair dropping, same dangling-leading-turn guard) —
 * duplicated locally rather than shared because the original is `private` to its file; NOT a
 * divergent policy.
 */
private fun applyAnthropicHistoryTruncation(
  history: List<ParsedTurn>,
  maxHistoryChars: Int,
): List<ParsedTurn> {
  var totalChars = history.sumOf { it.text.length }
  if (totalChars <= maxHistoryChars) return history

  val mutable = history.toMutableList()
  var dropped = 0
  while (totalChars > maxHistoryChars && mutable.isNotEmpty()) {
    val firstUserIdx = mutable.indexOfFirst { it.role == "user" }
    if (firstUserIdx == -1) {
      for (turn in mutable) totalChars -= turn.text.length
      dropped += mutable.size
      mutable.clear()
      break
    }
    totalChars -= mutable[firstUserIdx].text.length
    mutable.removeAt(firstUserIdx)
    dropped++
    if (firstUserIdx < mutable.size && mutable[firstUserIdx].role == "assistant") {
      totalChars -= mutable[firstUserIdx].text.length
      mutable.removeAt(firstUserIdx)
      dropped++
    }
  }
  while (mutable.isNotEmpty() && mutable[0].role != "user") {
    totalChars -= mutable[0].text.length
    mutable.removeAt(0)
    dropped++
  }
  if (dropped > 0) {
    Log.w(TAG, "History truncated: dropped $dropped turns to fit context window ($maxHistoryChars chars)")
  }
  return mutable
}

/**
 * Parses Anthropic's `tools: [{"name","description","input_schema"}]` into [ToolSpec]s, synthesizing
 * the OpenAI-shaped `functionJson` (`{"name","description","parameters"}`) the native LiteRT-LM
 * `OpenApiTool` bridge requires. Returns `[]` when `tools` is absent/empty. Skips entries missing
 * `name`.
 */
internal fun parseAnthropicTools(body: JSONObject): List<ToolSpec> {
  val arr = body.optJSONArray("tools") ?: return emptyList()
  val out = mutableListOf<ToolSpec>()
  for (i in 0 until arr.length()) {
    val entry = arr.optJSONObject(i) ?: continue
    val name = entry.optString("name")
    if (name.isEmpty()) continue
    val functionJson =
      JSONObject()
        .put("name", name)
        .put("description", entry.optString("description"))
        .put("parameters", entry.optJSONObject("input_schema") ?: JSONObject())
        .toString()
    out.add(ToolSpec(name = name, functionJson = functionJson))
  }
  return out
}

/**
 * Parses Anthropic's `tool_choice` onto the shared [ToolChoice] hierarchy.
 *  - `tools` absent or empty -> [ToolChoice.None] (nothing to advertise).
 *  - `tool_choice` absent (tools present) -> [ToolChoice.Auto] (Anthropic's own default).
 *  - `{"type":"auto"}` -> [ToolChoice.Auto]; `{"type":"any"}` -> [ToolChoice.Required];
 *    `{"type":"tool","name":"x"}` -> [ToolChoice.Forced]. Unknown shapes fall back to [ToolChoice.Auto].
 */
internal fun parseAnthropicToolChoice(body: JSONObject): ToolChoice {
  val tools = body.optJSONArray("tools")
  if (tools == null || tools.length() == 0) return ToolChoice.None
  val choice = body.optJSONObject("tool_choice") ?: return ToolChoice.Auto
  return when (choice.optString("type")) {
    "any" -> ToolChoice.Required
    "tool" -> choice.optString("name").takeIf { it.isNotEmpty() }?.let { ToolChoice.Forced(it) } ?: ToolChoice.Auto
    else -> ToolChoice.Auto // "auto" + any unrecognized type
  }
}

/**
 * Anthropic's error envelope: `{"type":"error","error":{"type":[type],"message":[message]}}`. Distinct
 * from [RelaisError.json] (OpenAI's `{"error":{"message","type"}}`, no top-level `"type"`) — this
 * endpoint's own thin wrapper, matching the existing per-domain-handler pattern (embeddings/rerank/tts
 * each have their own error-shape wrapper despite [RelaisError] being the shared type vocabulary
 * source elsewhere). Use Anthropic's own type strings as literals at call sites (e.g.
 * `"invalid_request_error"`, `"authentication_error"`, `"not_found_error"`, `"rate_limit_error"`,
 * `"api_error"`).
 */
internal fun buildAnthropicError(message: String, type: String): JSONObject =
  JSONObject().put("type", "error").put("error", JSONObject().put("type", type).put("message", message))

/**
 * Maps a completed [RelaisResult] to Anthropic's `stop_reason` vocabulary: tool calls present ->
 * `"tool_use"`; a thermally-truncated decode ([RelaisFinishReason.LENGTH]) -> `"max_tokens"`
 * (Anthropic's closest equivalent — there is no per-request `max_tokens` enforcement on this node, see
 * `RelaisHttpServer.handleAnthropicMessages`, but a thermal truncation is still an incomplete decode,
 * matching `"max_tokens"`'s "hit a limit" semantics better than `"end_turn"`); otherwise `"end_turn"`.
 */
internal fun anthropicStopReason(result: RelaisResult): String =
  when {
    result.toolCalls.isNotEmpty() -> "tool_use"
    result.finishReason == RelaisFinishReason.LENGTH -> "max_tokens"
    else -> "end_turn"
  }

/** A fresh Anthropic message id (`msg_<epoch millis>`), mirroring the OpenAI path's `chatcmpl-` ids. */
internal fun newAnthropicMessageId(): String = "msg_" + System.currentTimeMillis()

/**
 * Builds the `content[]` array of a non-tool-path OR tool-path Anthropic response from a completed
 * [RelaisResult]. Ordering (Anthropic allows multiple content blocks in one response): a `thinking`
 * block first (only when [RelaisResult.reasoning] is present and non-blank), then a `text` block
 * (only when [RelaisResult.text] is non-blank), then one `tool_use` block per [RelaisResult.toolCalls].
 */
internal fun buildAnthropicContentBlocks(result: RelaisResult): JSONArray {
  val content = JSONArray()
  result.reasoning?.takeIf { it.isNotBlank() }?.let {
    content.put(JSONObject().put("type", "thinking").put("thinking", it))
  }
  if (result.text.isNotBlank()) {
    content.put(JSONObject().put("type", "text").put("text", result.text))
  }
  result.toolCalls.forEach { call ->
    content.put(
      JSONObject()
        .put("type", "tool_use")
        .put("id", call.id)
        .put("name", call.name)
        .put("input", runCatching { JSONObject(call.argumentsJson) }.getOrDefault(JSONObject()))
    )
  }
  return content
}

/**
 * Shapes the non-streaming `POST /v1/messages` success response body from a completed [RelaisResult].
 * [inputTokens] is the caller's own prompt-token estimate (reuses the same estimator the OpenAI path
 * uses for `usage.prompt_tokens`, see [estimatePromptTokens]) — Anthropic's `usage.input_tokens`.
 */
internal fun buildAnthropicMessageResponse(
  id: String,
  model: String,
  result: RelaisResult,
  inputTokens: Int,
): JSONObject =
  JSONObject()
    .put("id", id)
    .put("type", "message")
    .put("role", "assistant")
    .put("model", model)
    .put("content", buildAnthropicContentBlocks(result))
    .put("stop_reason", anthropicStopReason(result))
    .put("stop_sequence", JSONObject.NULL)
    .put(
      "usage",
      JSONObject().put("input_tokens", inputTokens).put("output_tokens", result.completionTokens),
    )

/** `event: message_start` payload. */
internal fun buildMessageStartEvent(id: String, model: String, inputTokens: Int): JSONObject =
  JSONObject()
    .put("type", "message_start")
    .put(
      "message",
      JSONObject()
        .put("id", id)
        .put("type", "message")
        .put("role", "assistant")
        .put("model", model)
        .put("content", JSONArray())
        .put("stop_reason", JSONObject.NULL)
        .put("stop_sequence", JSONObject.NULL)
        .put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", 0)),
    )

/**
 * `event: content_block_start` payload for a `thinking`, `text`, or `tool_use` block at [index].
 *
 * For `tool_use`, [id]/[name] MUST be supplied — Anthropic's real streaming contract requires the
 * `content_block_start` for a tool_use block to carry `{"type":"tool_use","id":...,"name":...,
 * "input":{}}` up front; the subsequent `input_json_delta` events only ever supply the arguments
 * incrementally, never the id/name (mirrors [buildAnthropicContentBlocks]'s non-streaming shape).
 */
internal fun buildContentBlockStartEvent(
  index: Int,
  blockType: String,
  id: String? = null,
  name: String? = null,
): JSONObject {
  val block = JSONObject().put("type", blockType)
  when (blockType) {
    "thinking" -> block.put("thinking", "")
    "text" -> block.put("text", "")
    "tool_use" -> {
      block.put("input", JSONObject())
      if (id != null) block.put("id", id)
      if (name != null) block.put("name", name)
    }
  }
  return JSONObject().put("type", "content_block_start").put("index", index).put("content_block", block)
}

/** `event: content_block_delta` payload for a `thinking_delta` at [index]. */
internal fun buildThinkingDeltaEvent(index: Int, delta: String): JSONObject =
  JSONObject()
    .put("type", "content_block_delta")
    .put("index", index)
    .put("delta", JSONObject().put("type", "thinking_delta").put("thinking", delta))

/** `event: content_block_delta` payload for a `text_delta` at [index]. */
internal fun buildTextDeltaEvent(index: Int, delta: String): JSONObject =
  JSONObject()
    .put("type", "content_block_delta")
    .put("index", index)
    .put("delta", JSONObject().put("type", "text_delta").put("text", delta))

/** `event: content_block_stop` payload for [index]. */
internal fun buildContentBlockStopEvent(index: Int): JSONObject =
  JSONObject().put("type", "content_block_stop").put("index", index)

/** `event: message_delta` payload (terminal stop_reason + cumulative output token count). */
internal fun buildMessageDeltaEvent(stopReason: String, outputTokens: Int): JSONObject =
  JSONObject()
    .put("type", "message_delta")
    .put("delta", JSONObject().put("stop_reason", stopReason).put("stop_sequence", JSONObject.NULL))
    .put("usage", JSONObject().put("output_tokens", outputTokens))

/** `event: message_stop` payload. */
internal fun buildMessageStopEvent(): JSONObject = JSONObject().put("type", "message_stop")

/**
 * Tracks/emits the `content_block_start`/`content_block_delta`/`content_block_stop` event sequence
 * for the NON-tool streaming path of `handleAnthropicMessages` (thinking block, if any, always
 * precedes the text block: thinking is index 0, text is index 1 when thinking is present else 0).
 * Extracted out of [RelaisHttpServer] per this repo's file-size convention — this is the imperative
 * bookkeeping counterpart to the pure JSON builders above ([buildContentBlockStartEvent] etc.), kept
 * in the same file since it's a thin wrapper around them, not new parsing logic.
 *
 * Pure behavior-preserving extraction: same event order/shape as the inline version it replaced.
 *
 * Also owns the stream's opening `message_start` ([messageStart]), emitted lazily before the first
 * event of any kind (#352). The handler used to send it before calling the engine, which committed
 * the 200 header before the engine's served-model check could run; deferred to here, a request that
 * fails before its first token has written nothing. [finish] emits it too, so a zero-token stream
 * is still well-formed.
 */
internal class AnthropicStreamSequencer(private val sse: SseWriter, private val messageStart: JSONObject) {
  private var started = false
  private var thinkingStarted = false
  private var thinkingStopped = false
  private var textStarted = false

  private fun startIfNeeded() {
    if (!started) {
      started = true
      sse.send("message_start", messageStart)
    }
  }

  private fun closeThinkingIfOpen() {
    if (thinkingStarted && !thinkingStopped) {
      sse.send("content_block_stop", buildContentBlockStopEvent(0))
      thinkingStopped = true
    }
  }

  /** Call from the engine's `onReasoning` callback for each reasoning-channel delta. */
  fun onReasoningDelta(text: String) {
    startIfNeeded()
    if (!thinkingStarted) {
      thinkingStarted = true
      sse.send("content_block_start", buildContentBlockStartEvent(0, "thinking"))
    }
    sse.send("content_block_delta", buildThinkingDeltaEvent(0, text))
  }

  /** Call from the engine's `onToken` callback for each visible-text delta. */
  fun onTextDelta(text: String) {
    startIfNeeded()
    closeThinkingIfOpen()
    val textIndex = if (thinkingStarted) 1 else 0
    if (!textStarted) {
      textStarted = true
      sse.send("content_block_start", buildContentBlockStartEvent(textIndex, "text"))
    }
    sse.send("content_block_delta", buildTextDeltaEvent(textIndex, text))
  }

  /** Call once generation completes: closes any open block(s) and emits the terminal event pair. */
  fun finish(stopReason: String, outputTokens: Int) {
    startIfNeeded()
    closeThinkingIfOpen()
    if (textStarted) {
      sse.send("content_block_stop", buildContentBlockStopEvent(if (thinkingStarted) 1 else 0))
    }
    sse.send("message_delta", buildMessageDeltaEvent(stopReason, outputTokens))
    sse.send("message_stop", buildMessageStopEvent())
  }
}

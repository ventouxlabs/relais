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
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Byte-oriented HTTP request reader for [RelaisHttpServer]. Exists because the previous
 * `BufferedReader(InputStreamReader(...))` decoded the socket through the platform charset, which is
 * lossy for arbitrary bytes — fine for the JSON endpoints, fatal for a binary `multipart/form-data`
 * audio upload (`POST /v1/audio/transcriptions`). This reader keeps header parsing line-oriented
 * (HTTP header lines are ASCII) while exposing the body as raw bytes so binary uploads survive intact.
 *
 * [readLine] and [readBodyBytes] share one [BufferedInputStream], so a body read continues exactly
 * where the header loop stopped. Pure I/O over an [InputStream] — unit-testable on the JVM with a
 * [java.io.ByteArrayInputStream] ([RelaisHttpIoTest]).
 */
class HttpRequestReader(input: InputStream) {
  private val stream = BufferedInputStream(input)

  /**
   * Reads one line: bytes up to (and consuming) the next `\n`, with a trailing `\r` stripped, decoded
   * as ISO-8859-1 (Latin-1 is a total, lossless byte->char map, correct for ASCII HTTP header lines).
   * A final line with no trailing newline is returned as-is. Returns null only at EOF with no bytes
   * read (so the header loop terminates cleanly). Splits on both CRLF and bare LF.
   */
  fun readLine(): String? {
    val buf = ByteArrayOutputStream(64)
    var sawAny = false
    while (true) {
      val b = stream.read()
      if (b < 0) {
        if (!sawAny) return null // EOF before any byte -> no more lines
        break // EOF terminates the final (newline-less) line
      }
      sawAny = true
      if (b == LF) break
      buf.write(b)
    }
    var bytes = buf.toByteArray()
    if (bytes.isNotEmpty() && bytes[bytes.size - 1] == CR_BYTE) {
      bytes = bytes.copyOf(bytes.size - 1) // strip the CR of a CRLF pair
    }
    return String(bytes, Charsets.ISO_8859_1)
  }

  /**
   * Reads exactly `min(length, MAX_BODY_BYTES)` raw bytes (or fewer if the stream ends early), never
   * pre-allocating the client-claimed size (a hostile Content-Length can't force a multi-MB alloc per
   * worker — security M1). The cap mirrors the shared body cap enforced up front by the server.
   */
  fun readBodyBytes(length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    val cap = minOf(length, MAX_BODY_BYTES)
    val out = ByteArrayOutputStream(minOf(cap, 64 * 1024))
    val chunk = ByteArray(8192)
    var remaining = cap
    while (remaining > 0) {
      val r = stream.read(chunk, 0, minOf(chunk.size, remaining))
      if (r < 0) break
      out.write(chunk, 0, r)
      remaining -= r
    }
    return out.toByteArray()
  }

  /**
   * Reads the body as UTF-8 text for a JSON endpoint, rejecting bodies nested deeper than
   * [MAX_JSON_DEPTH] with [JsonTooDeepException] BEFORE org.json's recursive-descent parser sees them
   * (a 32 MB `[[[[...` body would otherwise overflow the worker thread's stack).
   */
  fun readJsonBody(length: Int): String {
    val text = String(readBodyBytes(length), Charsets.UTF_8)
    if (jsonNestingExceeds(text, MAX_JSON_DEPTH)) throw JsonTooDeepException()
    return text
  }

  private companion object {
    const val LF = '\n'.code
    const val CR_BYTE = '\r'.code.toByte()
  }
}

/** One decoded `multipart/form-data` part: its form field [name], optional upload [filename] and
 *  declared [contentType], and its raw body [bytes] (verbatim — binary-safe). */
data class MultipartPart(
  val name: String,
  val filename: String?,
  val contentType: String?,
  val bytes: ByteArray,
) {
  // data class over a ByteArray: content-based equals/hashCode so tests compare by value.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is MultipartPart) return false
    return name == other.name && filename == other.filename &&
      contentType == other.contentType && bytes.contentEquals(other.bytes)
  }

  override fun hashCode(): Int {
    var result = name.hashCode()
    result = 31 * result + (filename?.hashCode() ?: 0)
    result = 31 * result + (contentType?.hashCode() ?: 0)
    result = 31 * result + bytes.contentHashCode()
    return result
  }
}

/** Cap on the number of parts a single multipart body may yield (the outer 32MB body cap bounds
 *  total size; this bounds part count so a pathological body can't spawn unbounded slices). */
private const val MULTIPART_MAX_PARTS = 16

/**
 * Extracts the `boundary` token from a `Content-Type: multipart/form-data; boundary=...` header
 * value, stripping optional surrounding quotes. Returns null when the value is not multipart/form-data
 * or carries no boundary. Pure — unit-testable on the JVM.
 */
fun parseMultipartBoundary(contentTypeHeader: String): String? {
  val lower = contentTypeHeader.lowercase()
  if (!lower.contains("multipart/form-data")) return null
  val marker = "boundary="
  val i = lower.indexOf(marker)
  if (i < 0) return null
  var v = contentTypeHeader.substring(i + marker.length).trim() // original case: boundaries are literal
  val semi = v.indexOf(';')
  if (semi >= 0) v = v.substring(0, semi).trim()
  if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
  val token = v.ifBlank { null } ?: return null
  // RFC 2046 caps a boundary at 70 chars. Reject absurd tokens so an attacker-supplied ~16KB boundary
  // (bounded only by the header-line cap) can't drive the naive byte scan below into quadratic time.
  return if (token.length <= 70) token else null
}

/**
 * Parses a `multipart/form-data` [body] delimited by [boundary] into its parts, binary-safe.
 *
 * Delimiters are the RFC-2046 `--<boundary>` sequences; a delimiter is honored only at the start of
 * the body or after a line break (or when it is the closing `--<boundary>--`), so the boundary string
 * appearing by accident inside binary content is not mistaken for a real boundary. Each part is split
 * at its first blank line into a header block (`Content-Disposition`, optional `Content-Type`) and its
 * raw body bytes (the single trailing CRLF before the next boundary is removed; a missing final CRLF
 * is tolerated). Preamble and epilogue are ignored, parsing stops at the closing delimiter, and at
 * most [MULTIPART_MAX_PARTS] parts are returned. Pure — unit-testable on the JVM.
 */
fun parseMultipartFormData(body: ByteArray, boundary: String): List<MultipartPart> {
  if (boundary.isEmpty()) return emptyList()
  val delim = ("--$boundary").toByteArray(Charsets.ISO_8859_1)
  val dash = '-'.code.toByte()
  val lf = '\n'.code.toByte()
  val cr = '\r'.code.toByte()

  // Collect real boundary offsets: at body start, after an LF (covers CRLF/LF line breaks), or when
  // immediately followed by "--" (the closing delimiter, which may abut non-CRLF-terminated content).
  // Bound the offset collection: 16 parts need at most 17 real boundaries, so stop once we have enough.
  // Without this, a 32MB body of "\n--b" yields ~8M boxed offsets — a memory-amplification DoS on a
  // RAM-constrained phone hosting a resident LLM. Parts beyond MULTIPART_MAX_PARTS are discarded below.
  val positions = ArrayList<Int>(MULTIPART_MAX_PARTS + 2)
  var idx = indexOf(body, delim, 0)
  while (idx >= 0 && positions.size <= MULTIPART_MAX_PARTS + 1) {
    val after = idx + delim.size
    val closing = after + 1 < body.size && body[after] == dash && body[after + 1] == dash
    if (idx == 0 || (idx > 0 && body[idx - 1] == lf) || closing) positions.add(idx)
    idx = indexOf(body, delim, idx + delim.size)
  }

  val parts = ArrayList<MultipartPart>()
  for (k in positions.indices) {
    if (parts.size >= MULTIPART_MAX_PARTS) break
    val p = positions[k]
    val afterDelim = p + delim.size
    // Closing boundary "--<boundary>--" terminates the body; ignore any epilogue after it.
    if (afterDelim + 1 < body.size && body[afterDelim] == dash && body[afterDelim + 1] == dash) break
    if (k + 1 >= positions.size) break // no next boundary to bound this part -> malformed tail, ignore

    // Header start: skip transport padding (SP/HT) then a single CRLF (or LF) after the boundary line.
    var hs = afterDelim
    while (hs < body.size && (body[hs] == ' '.code.toByte() || body[hs] == '\t'.code.toByte())) hs++
    if (hs < body.size && body[hs] == cr) hs++
    if (hs < body.size && body[hs] == lf) hs++

    // Part end: strip the single CRLF (or LF) that precedes the next boundary; tolerate its absence.
    var pe = positions[k + 1]
    if (pe >= 2 && body[pe - 2] == cr && body[pe - 1] == lf) pe -= 2
    else if (pe >= 1 && body[pe - 1] == lf) pe -= 1
    if (pe < hs) pe = hs

    // Split part into header block / body at the first blank line (CRLF CRLF, or LF LF).
    var sep = indexOf(body, "\r\n\r\n".toByteArray(Charsets.ISO_8859_1), hs)
    var sepLen = 4
    if (sep < 0 || sep >= pe) {
      sep = indexOf(body, "\n\n".toByteArray(Charsets.ISO_8859_1), hs)
      sepLen = 2
    }
    if (sep < 0 || sep >= pe) continue // no header/body separator -> malformed part, skip

    val headerText = String(body.copyOfRange(hs, sep), Charsets.ISO_8859_1)
    val bodyStart = sep + sepLen
    val partBytes = if (bodyStart <= pe) body.copyOfRange(bodyStart, pe) else ByteArray(0)

    var name: String? = null
    var filename: String? = null
    var partContentType: String? = null
    for (rawLine in headerText.split("\r\n", "\n")) {
      val line = rawLine.trim()
      if (line.isEmpty()) continue
      val lower = line.lowercase()
      when {
        lower.startsWith("content-disposition:") -> {
          name = dispositionParam(line, "name")
          filename = dispositionParam(line, "filename")
        }
        lower.startsWith("content-type:") -> partContentType = line.substringAfter(":").trim()
      }
    }
    if (name != null) parts.add(MultipartPart(name, filename, partContentType, partBytes))
  }
  return parts
}

/** Extracts a `key="value"` (or `key=value`) parameter from a Content-Disposition line, stripping
 *  optional surrounding quotes. Returns null when the parameter is absent. */
private fun dispositionParam(line: String, key: String): String? {
  for (token in line.split(';')) {
    val t = token.trim()
    val prefix = "$key="
    if (t.startsWith(prefix, ignoreCase = true)) {
      var v = t.substring(prefix.length).trim()
      if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
      return v
    }
  }
  return null
}

/** First index of [needle] in [haystack] at or after [from], or -1. Plain byte scan (needles here are
 *  short boundary/separator markers, so the naive search is more than adequate). */
private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
  if (needle.isEmpty() || needle.size > haystack.size) return -1
  val last = haystack.size - needle.size
  var i = maxOf(from, 0)
  while (i <= last) {
    var j = 0
    while (j < needle.size && haystack[i + j] == needle[j]) j++
    if (j == needle.size) return i
    i++
  }
  return -1
}

private const val DEFAULT_IMAGE_MIME = "image/jpeg"
private val IMAGE_MIME_REGEX = Regex("^image/[a-z0-9.+-]+$")

/**
 * Builds the synthetic OpenAI chat-completions request for the `multipart/form-data` convenience path
 * on `POST /v1/chat/completions`: one uploaded image ([fileBytes]) plus optional text fields become
 * the EXACT JSON body the standard JSON vision path already understands — a `user` message whose
 * `content[]` carries a `text` part and an `image_url` data-URI part (see extractParts in
 * [buildPromptParts]). The route hands the result straight to `handleOpenAi`, so all streaming,
 * session, and response logic is shared with the JSON path.
 *
 * Pure and device-free: base64-encodes via `java.util.Base64` (NOT `android.util.Base64`) so it runs
 * unchanged in JVM unit tests and on-device (minSdk 31), and constructs the body with `org.json` so
 * every UNTRUSTED field ([prompt], [system], [model]) and the data-URI is JSON-escaped by the
 * serializer — never string-concatenated into JSON.
 *
 * @param fileBytes  raw uploaded image bytes (base64-encoded into the data URI).
 * @param mimeType   the file part's declared Content-Type; sanitized to `image/<subtype>` (defaults to
 *                   `image/jpeg` for anything not a well-formed image type) so an arbitrary attacker
 *                   string never lands inside the emitted data URI.
 * @param prompt     user text (null -> empty string).
 * @param model      requested model id (null/blank -> omitted, so the node's resident model is used).
 * @param system     optional system prompt (null/blank -> no system message emitted).
 * @param stream     whether to request SSE streaming.
 */
fun buildMultipartChatRequest(
  fileBytes: ByteArray,
  mimeType: String?,
  prompt: String?,
  model: String?,
  system: String?,
  stream: Boolean,
): JSONObject {
  val encoded = java.util.Base64.getEncoder().encodeToString(fileBytes)
  val dataUri = buildString(encoded.length + 40) {
    append("data:").append(sanitizeImageMime(mimeType)).append(";base64,").append(encoded)
  }

  val userContent = JSONArray()
    .put(JSONObject().put("type", "text").put("text", prompt ?: ""))
    .put(
      JSONObject().put("type", "image_url")
        .put("image_url", JSONObject().put("url", dataUri)),
    )

  val messages = JSONArray()
  if (!system.isNullOrBlank()) {
    messages.put(JSONObject().put("role", "system").put("content", system))
  }
  messages.put(JSONObject().put("role", "user").put("content", userContent))

  val request = JSONObject().put("stream", stream).put("messages", messages)
  if (!model.isNullOrBlank()) request.put("model", model)
  return request
}

/**
 * Sanitizes a multipart file part's Content-Type into a safe `image/<subtype>` for the data URI.
 * Strips any parameters (`; charset=…`), lowercases, then accepts only `image/<token>` with a
 * conservative subtype charset; anything else (a non-image type, malformed value, or null) becomes
 * [DEFAULT_IMAGE_MIME]. The image decoder ignores the declared type anyway (it strips to `;base64,`),
 * so this is pure hygiene — it keeps an arbitrary attacker string out of the emitted data URI.
 */
private fun sanitizeImageMime(raw: String?): String {
  val base = raw?.substringBefore(';')?.trim()?.lowercase() ?: return DEFAULT_IMAGE_MIME
  return if (base.matches(IMAGE_MIME_REGEX)) base else DEFAULT_IMAGE_MIME
}

/** Max `[`/`{` nesting accepted in a request body; legitimate API payloads nest < 10 levels. */
internal const val MAX_JSON_DEPTH = 64

/** Thrown by [HttpRequestReader.readJsonBody]; the server maps it to HTTP 400. */
class JsonTooDeepException : IllegalArgumentException("JSON nesting too deep (max $MAX_JSON_DEPTH)")

/** True if [text] opens more than [max] nested `[`/`{` at once. Skips string literals; pure and O(n). */
internal fun jsonNestingExceeds(text: String, max: Int): Boolean {
  var depth = 0
  var inString = false
  var escaped = false
  for (c in text) {
    if (inString) {
      if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
      continue
    }
    when (c) {
      '"' -> inString = true
      '[', '{' -> if (++depth > max) return true
      ']', '}' -> if (depth > 0) depth--
    }
  }
  return false
}

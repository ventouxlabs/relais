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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the byte-safe HTTP request reader + multipart parser ([RelaisHttpIo]).
 * These are the load-bearing correctness guarantees for the binary `POST /v1/audio/transcriptions`
 * upload path: header lines stay ASCII/line-oriented while the body survives as raw bytes.
 */
class RelaisHttpIoTest {

  private fun reader(bytes: ByteArray) = HttpRequestReader(ByteArrayInputStream(bytes))

  /** Concatenates ISO-8859-1 strings, raw byte arrays, and single byte ints into one ByteArray. */
  private fun buf(vararg parts: Any): ByteArray {
    val out = ByteArrayOutputStream()
    for (p in parts) when (p) {
      is String -> out.write(p.toByteArray(Charsets.ISO_8859_1))
      is ByteArray -> out.write(p)
      is Int -> out.write(p)
      else -> error("unsupported part: $p")
    }
    return out.toByteArray()
  }

  // ---- HttpRequestReader.readLine ----

  @Test
  fun `readLine splits on CRLF and strips the CR`() {
    val r = reader("GET / HTTP/1.1\r\nHost: relais\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
    assertEquals("GET / HTTP/1.1", r.readLine())
    assertEquals("Host: relais", r.readLine())
    assertEquals("", r.readLine()) // blank line terminating the header block
    assertNull("stream is exhausted after the blank line", r.readLine())
  }

  @Test
  fun `readLine splits on a bare LF too`() {
    val r = reader("a\nb\nc\n".toByteArray(Charsets.ISO_8859_1))
    assertEquals("a", r.readLine())
    assertEquals("b", r.readLine())
    assertEquals("c", r.readLine())
    assertNull(r.readLine())
  }

  @Test
  fun `readLine returns a final line with no trailing newline then null at EOF`() {
    val r = reader("first\r\nlast-no-newline".toByteArray(Charsets.ISO_8859_1))
    assertEquals("first", r.readLine())
    assertEquals("last-no-newline", r.readLine())
    assertNull(r.readLine())
  }

  @Test
  fun `readLine returns null immediately on an empty stream`() {
    assertNull(reader(ByteArray(0)).readLine())
  }

  // ---- HttpRequestReader.readBodyBytes ----

  @Test
  fun `readBodyBytes preserves a full 0 to 255 byte sweep verbatim`() {
    val body = ByteArray(256) { it.toByte() } // every byte value 0x00..0xFF
    val r = reader(body)
    assertArrayEquals(body, r.readBodyBytes(256))
  }

  @Test
  fun `readBodyBytes survives NUL FF LF CR after ASCII header lines`() {
    val body = byteArrayOf(0x00, 0xFF.toByte(), 0x0A, 0x0D, 0x2D, 0x2D, 0x7F, 0x80.toByte())
    val r = reader(buf("Content-Length: 8\r\n", "X-Header: v\r\n", "\r\n", body))
    assertEquals("Content-Length: 8", r.readLine())
    assertEquals("X-Header: v", r.readLine())
    assertEquals("", r.readLine())
    assertArrayEquals(body, r.readBodyBytes(body.size))
  }

  @Test
  fun `readBodyBytes reads exactly length so successive reads split the stream`() {
    val r = reader("HELLOWORLD".toByteArray(Charsets.ISO_8859_1))
    assertArrayEquals("HELLO".toByteArray(), r.readBodyBytes(5))
    assertArrayEquals("WORLD".toByteArray(), r.readBodyBytes(5))
  }

  @Test
  fun `readBodyBytes returns what is available when the stream ends early`() {
    val r = reader("abc".toByteArray(Charsets.ISO_8859_1))
    assertArrayEquals("abc".toByteArray(), r.readBodyBytes(100))
  }

  @Test
  fun `readBodyBytes returns empty for a non-positive length`() {
    val r = reader("abc".toByteArray(Charsets.ISO_8859_1))
    assertArrayEquals(ByteArray(0), r.readBodyBytes(0))
    assertArrayEquals(ByteArray(0), r.readBodyBytes(-5))
  }

  // ---- parseMultipartBoundary ----

  @Test
  fun `parseMultipartBoundary extracts an unquoted boundary`() {
    assertEquals("abc123", parseMultipartBoundary("multipart/form-data; boundary=abc123"))
  }

  @Test
  fun `parseMultipartBoundary strips surrounding quotes`() {
    assertEquals("abc 123", parseMultipartBoundary("multipart/form-data; boundary=\"abc 123\""))
  }

  @Test
  fun `parseMultipartBoundary stops at a trailing parameter`() {
    assertEquals("XYZ", parseMultipartBoundary("multipart/form-data; boundary=XYZ; charset=utf-8"))
  }

  @Test
  fun `parseMultipartBoundary returns null for non-multipart or missing boundary`() {
    assertNull(parseMultipartBoundary("application/json"))
    assertNull(parseMultipartBoundary("multipart/form-data"))
  }

  // ---- parseMultipartFormData ----

  private val boundary = "----RelaisBoundary7MA4YWxkTrZu0gW"

  @Test
  fun `parseMultipartFormData parses a text field and a binary file field`() {
    val fileBytes = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x0A, 0x0D, 0x2D, 0x2D, 0x7F, 0x80.toByte())
    val body = buf(
      "--$boundary\r\n",
      "Content-Disposition: form-data; name=\"model\"\r\n\r\n",
      "gemma-4-e4b-it\r\n",
      "--$boundary\r\n",
      "Content-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\n",
      "Content-Type: audio/wav\r\n\r\n",
      fileBytes,
      "\r\n",
      "--$boundary--\r\n",
    )
    val parts = parseMultipartFormData(body, boundary)
    assertEquals(2, parts.size)

    val model = parts[0]
    assertEquals("model", model.name)
    assertNull(model.filename)
    assertNull(model.contentType)
    assertEquals("gemma-4-e4b-it", String(model.bytes, Charsets.UTF_8))

    val file = parts[1]
    assertEquals("file", file.name)
    assertEquals("clip.wav", file.filename)
    assertEquals("audio/wav", file.contentType)
    assertArrayEquals("binary body must survive byte-for-byte", fileBytes, file.bytes)
  }

  @Test
  fun `parseMultipartFormData tolerates a missing final CRLF after the closing boundary`() {
    val fileBytes = byteArrayOf(0x10, 0x20, 0x30)
    val body = buf(
      "--$boundary\r\n",
      "Content-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n\r\n",
      fileBytes,
      "\r\n",
      "--$boundary--", // no trailing CRLF / epilogue
    )
    val parts = parseMultipartFormData(body, boundary)
    assertEquals(1, parts.size)
    assertEquals("file", parts[0].name)
    assertArrayEquals(fileBytes, parts[0].bytes)
  }

  @Test
  fun `parseMultipartFormData decodes a UTF-8 text field via the part bytes`() {
    val utf8 = "café — 日本語".toByteArray(Charsets.UTF_8)
    val body = buf(
      "--$boundary\r\n",
      "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n",
      utf8,
      "\r\n",
      "--$boundary--\r\n",
    )
    val parts = parseMultipartFormData(body, boundary)
    assertEquals(1, parts.size)
    assertEquals("prompt", parts[0].name)
    assertEquals("café — 日本語", String(parts[0].bytes, Charsets.UTF_8))
  }

  @Test
  fun `parseMultipartFormData accepts a quoted boundary token`() {
    val extracted = parseMultipartBoundary("multipart/form-data; boundary=\"$boundary\"")
    assertEquals(boundary, extracted)
    val body = buf(
      "--$boundary\r\n",
      "Content-Disposition: form-data; name=\"file\"; filename=\"x.wav\"\r\n\r\n",
      byteArrayOf(1, 2, 3),
      "\r\n",
      "--$boundary--\r\n",
    )
    val parts = parseMultipartFormData(body, requireNotNull(extracted))
    assertEquals(1, parts.size)
    assertArrayEquals(byteArrayOf(1, 2, 3), parts[0].bytes)
  }

  @Test
  fun `parseMultipartFormData ignores preamble and epilogue`() {
    val body = buf(
      "preamble text to ignore\r\n",
      "--$boundary\r\n",
      "Content-Disposition: form-data; name=\"file\"; filename=\"x.wav\"\r\n\r\n",
      byteArrayOf(9, 8, 7),
      "\r\n",
      "--$boundary--\r\n",
      "trailing epilogue to ignore\r\n",
    )
    val parts = parseMultipartFormData(body, boundary)
    assertEquals(1, parts.size)
    assertArrayEquals(byteArrayOf(9, 8, 7), parts[0].bytes)
  }

  @Test
  fun `parseMultipartFormData returns empty for an empty boundary`() {
    assertTrue(parseMultipartFormData("--x\r\n\r\n".toByteArray(), "").isEmpty())
  }

  // ---- Resource-bound hardening (security review: parse-amplification DoS) ----

  @Test
  fun `parseMultipartBoundary rejects an over-long boundary (RFC 2046 caps at 70)`() {
    val ok = "a".repeat(70)
    val tooLong = "a".repeat(71)
    assertEquals(ok, parseMultipartBoundary("multipart/form-data; boundary=$ok"))
    // A ~16KB boundary (bounded only by the header cap) would drive the naive scan quadratic — reject it.
    assertNull(parseMultipartBoundary("multipart/form-data; boundary=$tooLong"))
  }

  @Test
  fun `parseMultipartFormData caps the part count on boundary-heavy input`() {
    // 40 well-formed parts: the parser must return at most MULTIPART_MAX_PARTS (16) and must not
    // collect an unbounded offset list (the memory-amplification guard). Terminating here at all is
    // itself the assertion that the bounded scan loop doesn't run away.
    val sb = StringBuilder()
    repeat(40) { i ->
      sb.append("--b\r\n")
        .append("Content-Disposition: form-data; name=\"f$i\"\r\n\r\n")
        .append("v$i\r\n")
    }
    sb.append("--b--\r\n")
    val parts = parseMultipartFormData(sb.toString().toByteArray(Charsets.ISO_8859_1), "b")
    assertTrue("part count must be capped at 16, was ${parts.size}", parts.size <= 16)
  }

  // ---- buildMultipartChatRequest (the multipart -> synthetic OpenAI vision request adapter) ----
  //
  // org.json is a real JVM impl here (build.gradle.kts testImplementation "org.json:json"), so these
  // assert directly on the constructed JSONObject — the same object the route hands to handleOpenAi.

  private val imageBytes = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x0A, 0x2D, 0x7F, 0x80.toByte())

  @Test
  fun `buildMultipartChatRequest emits a user turn with a text part and an image_url data-uri part`() {
    val req = buildMultipartChatRequest(imageBytes, "image/png", "what is this?", "gemma-3n-e4b", null, false)

    assertEquals("gemma-3n-e4b", req.getString("model"))
    assertFalse("stream defaults off for a plain upload", req.getBoolean("stream"))

    val messages = req.getJSONArray("messages")
    assertEquals("no system field -> user turn only", 1, messages.length())
    val user = messages.getJSONObject(0)
    assertEquals("user", user.getString("role"))

    val content = user.getJSONArray("content")
    assertEquals(2, content.length())
    val textPart = content.getJSONObject(0)
    assertEquals("text", textPart.getString("type"))
    assertEquals("what is this?", textPart.getString("text"))

    val imagePart = content.getJSONObject(1)
    assertEquals("image_url", imagePart.getString("type"))
    val url = imagePart.getJSONObject("image_url").getString("url")
    val expected = "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes)
    assertEquals("data uri must carry the exact base64 of the uploaded bytes", expected, url)
  }

  @Test
  fun `buildMultipartChatRequest prepends a system message only when the system field is present`() {
    val req = buildMultipartChatRequest(imageBytes, "image/jpeg", "hi", null, "be terse", true)
    val messages = req.getJSONArray("messages")
    assertEquals("system + user", 2, messages.length())
    val system = messages.getJSONObject(0)
    assertEquals("system", system.getString("role"))
    assertEquals("be terse", system.getString("content"))
    assertEquals("user", messages.getJSONObject(1).getString("role"))
    assertTrue("stream passed through", req.getBoolean("stream"))
    assertFalse("blank/absent model must be omitted so the resident model is used", req.has("model"))
  }

  @Test
  fun `buildMultipartChatRequest omits a blank system and a blank model`() {
    val req = buildMultipartChatRequest(imageBytes, "image/png", "", "   ", "   ", false)
    assertEquals("blank system -> user turn only", 1, req.getJSONArray("messages").length())
    assertEquals("user", req.getJSONArray("messages").getJSONObject(0).getString("role"))
    assertFalse("blank model omitted", req.has("model"))
  }

  @Test
  fun `buildMultipartChatRequest defaults a null prompt to an empty text part`() {
    val req = buildMultipartChatRequest(imageBytes, "image/png", null, null, null, false)
    val text = req.getJSONArray("messages").getJSONObject(0)
      .getJSONArray("content").getJSONObject(0)
    assertEquals("", text.getString("text"))
  }

  @Test
  fun `buildMultipartChatRequest JSON-escapes untrusted prompt and system with special chars`() {
    // Newline, double-quote, backslash, and a script-close sequence must round-trip verbatim through
    // serialization — proof the org.json path escapes rather than concatenates raw bytes into JSON.
    val nasty = "line1\nline2 \"q\" \\b </script>  end"
    val req = buildMultipartChatRequest(imageBytes, "image/png", nasty, null, nasty, false)

    // Serialize + reparse: a broken/unescaped string would fail to parse or come back mangled.
    val reparsed = JSONObject(req.toString())
    val messages = reparsed.getJSONArray("messages")
    assertEquals("system + user", 2, messages.length())
    assertEquals(nasty, messages.getJSONObject(0).getString("content"))
    assertEquals(
      nasty,
      messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"),
    )
    // The raw JSON text must carry the ESCAPED forms, never the literal control/quote sequences.
    val raw = req.toString()
    assertTrue("newline escaped", raw.contains("line1\\nline2"))
    assertTrue("quote escaped", raw.contains("\\\"q\\\""))
    assertFalse("no raw newline inside the JSON string value", raw.contains("line1\nline2"))
  }

  @Test
  fun `buildMultipartChatRequest sanitizes a non-image mime to image jpeg`() {
    val req = buildMultipartChatRequest(imageBytes, "application/octet-stream", "x", null, null, false)
    val url = req.getJSONArray("messages").getJSONObject(0)
      .getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url")
    assertTrue("bogus type falls back to image/jpeg", url.startsWith("data:image/jpeg;base64,"))
  }

  @Test
  fun `buildMultipartChatRequest sanitizes a null mime and strips mime parameters`() {
    val nullMime = buildMultipartChatRequest(imageBytes, null, null, null, null, false)
      .getJSONArray("messages").getJSONObject(0)
      .getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url")
    assertTrue("null type -> image/jpeg", nullMime.startsWith("data:image/jpeg;base64,"))

    val paramMime = buildMultipartChatRequest(imageBytes, "image/WEBP; charset=binary", null, null, null, false)
      .getJSONArray("messages").getJSONObject(0)
      .getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url")
    assertTrue("params stripped + lowercased", paramMime.startsWith("data:image/webp;base64,"))
  }

  @Test
  fun jsonNestingDepth_flatAndModerateBodiesAreAccepted() {
    assertFalse(jsonNestingExceeds("""{"a":[1,{"b":"c"}]}""", MAX_JSON_DEPTH))
    assertFalse(jsonNestingExceeds("[".repeat(MAX_JSON_DEPTH) + "]".repeat(MAX_JSON_DEPTH), MAX_JSON_DEPTH))
  }

  @Test
  fun jsonNestingDepth_deepBodyIsRejected() {
    val deep = "[".repeat(MAX_JSON_DEPTH + 1) + "]".repeat(MAX_JSON_DEPTH + 1)
    assertTrue(jsonNestingExceeds(deep, MAX_JSON_DEPTH))
    assertTrue(jsonNestingExceeds("[".repeat(1_000_000), MAX_JSON_DEPTH))
  }

  @Test
  fun jsonNestingDepth_bracketsInsideStringsAreIgnored() {
    val body = """{"text":"${"[".repeat(500)} \" ${"{".repeat(500)}"}"""
    assertFalse(jsonNestingExceeds(body, MAX_JSON_DEPTH))
  }

  @Test
  fun jsonNestingDepth_singleQuoteBypassIsRejected() {
    val body = "{'a':'\"','b':" + "[".repeat(1_000_000)
    assertTrue(jsonNestingExceeds(body, MAX_JSON_DEPTH))
    assertTrue(jsonNestingExceeds("{'a':1}", MAX_JSON_DEPTH))
  }

  @Test
  fun jsonNestingDepth_commentBypassesAreRejected() {
    assertTrue(jsonNestingExceeds("#\"\n" + "[".repeat(1_000_000), MAX_JSON_DEPTH))
    assertTrue(jsonNestingExceeds("// \"\n[[1]]", MAX_JSON_DEPTH))
    assertTrue(jsonNestingExceeds("/* \" */ [[1]]", MAX_JSON_DEPTH))
  }

  @Test
  fun jsonNestingDepth_unquotedLiteralQuoteBypassIsRejected() {
    assertTrue(jsonNestingExceeds("[ab\"c," + "[".repeat(1_000_000), MAX_JSON_DEPTH))
  }

  @Test
  fun jsonNestingDepth_standardJsonWithLiteralsIsAccepted() {
    assertFalse(jsonNestingExceeds("""{"a":-1.5e+3,"b":true,"c":null,"d":["x\"y"]}""", MAX_JSON_DEPTH))
  }

  @Test
  fun readJsonBody_throwsOnDeepNesting() {
    val deep = "[".repeat(MAX_JSON_DEPTH + 1)
    val r = reader(deep.toByteArray())
    try {
      r.readJsonBody(deep.length)
      org.junit.Assert.fail("expected JsonTooDeepException")
    } catch (_: JsonTooDeepException) {}
  }
}

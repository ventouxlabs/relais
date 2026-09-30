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

package cc.grepon.relais.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import cc.grepon.relais.data.KEY_MODEL_COMMIT_HASH
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import cc.grepon.relais.data.KEY_MODEL_URL
import cc.grepon.relais.data.TMP_FILE_EXT
import java.io.File
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_PARTIAL
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #363: the worker refuses a `.tmp` whose length disagrees with the size the SERVER declared, before
 * the rename makes it a model file. It lives in the worker so every lane gets it — the provisioner's
 * "already present" branch adopts a renamed file with no check at all.
 *
 * Robolectric only for the end-to-end rows, which run the real [DownloadWorker] against a loopback
 * HTTP server; that is what pins the WIRING (declared size read, check called before the rename).
 */
@RunWith(RobolectricTestRunner::class)
class DownloadWorkerTest {

  // ---- the declared size ----

  @Test
  fun `a resumed 206 declares the Content-Range total, not its Content-Length slice`() {
    assertEquals(1000L, serverDeclaredSize(HTTP_PARTIAL, "bytes 400-999/1000", contentLength = 600L))
    assertEquals(-1L, serverDeclaredSize(HTTP_PARTIAL, "bytes 400-999/*", contentLength = 600L))
    assertEquals(-1L, serverDeclaredSize(HTTP_PARTIAL, null, contentLength = 600L))
  }

  @Test
  fun `a full 200 declares its Content-Length, and an absent one is unknown`() {
    assertEquals(1000L, serverDeclaredSize(HTTP_OK, null, contentLength = 1000L))
    assertEquals(-1L, serverDeclaredSize(HTTP_OK, null, contentLength = -1L))
    assertEquals(-1L, serverDeclaredSize(HTTP_OK, null, contentLength = 0L))
    // A Content-Range on a 200 is not how a 200 declares its size.
    assertEquals(1000L, serverDeclaredSize(HTTP_OK, "bytes 0-9/10", contentLength = 1000L))
  }

  @Test
  fun `any other status declares nothing`() {
    assertEquals(-1L, serverDeclaredSize(302, "bytes 0-9/10", contentLength = 10L))
  }

  // ---- the check itself, on real files ----

  private val scratch: File = Files.createTempDirectory("dlw").toFile()

  private fun fileOf(size: Int) = File(scratch, "f$size.tmp").apply { writeBytes(ByteArray(size)) }

  @Test
  fun `a tmp matching the declared size is kept`() {
    val f = fileOf(10)
    assertNull(discardIfMisSized(f, declaredBytes = 10L))
    assertTrue(f.exists())
  }

  @Test
  fun `a shorter or longer tmp is deleted and refused`() {
    for (size in listOf(9, 11)) {
      val f = fileOf(size)
      val why = discardIfMisSized(f, declaredBytes = 10L)
      assertEquals("Downloaded ${f.name} is $size bytes, the server declared 10; discarded", why)
      assertFalse("size $size must be deleted", f.exists())
    }
  }

  @Test
  fun `an unknown declared size keeps any tmp`() {
    for (declared in listOf(-1L, 0L)) {
      val f = fileOf(5)
      assertNull(discardIfMisSized(f, declaredBytes = declared))
      assertTrue(f.exists())
    }
  }

  // ---- end to end: the real worker against a loopback server ----

  private val body = ByteArray(4096) { (it % 251).toByte() }
  private var server: ServerSocket? = null

  @After
  fun tearDown() {
    server?.close()
    scratch.deleteRecursively()
  }

  /**
   * A minimal HTTP/1.1 server on loopback (the JDK's `com.sun` server is not on the Android unit-test
   * classpath). Serves [body], one request per connection; when [honorRange] is false every Range
   * request gets a full 200, the way a server that ignores ranges would answer.
   */
  private fun serve(honorRange: Boolean): String {
    val socket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
    server = socket
    thread(isDaemon = true) {
      while (!socket.isClosed) {
        val conn = runCatching { socket.accept() }.getOrNull() ?: break
        conn.use { c ->
          val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
          val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
          val from =
            headers
              .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
              ?.substringAfter("bytes=")
              ?.removeSuffix("-")
              ?.trim()
              ?.toInt()
          val out = c.getOutputStream()
          if (honorRange && from != null) {
            out.write(
              ("HTTP/1.1 206 Partial Content\r\nContent-Length: ${body.size - from}\r\n" +
                  "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\nConnection: close\r\n\r\n")
                .toByteArray()
            )
            out.write(body, from, body.size - from)
          } else {
            out.write(
              "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                .toByteArray()
            )
            out.write(body)
          }
          out.flush()
        }
      }
    }
    return "http://127.0.0.1:${socket.localPort}/m"
  }

  private val ctx: Context = ApplicationProvider.getApplicationContext()
  private val dir: File get() = File(ctx.getExternalFilesDir(null), "d/v")
  private val finalFile: File get() = File(dir, "m.bin")
  private val tmpFile: File get() = File(dir, "m.bin.$TMP_FILE_EXT")

  private fun seedTmp(bytes: Int) {
    dir.mkdirs()
    tmpFile.writeBytes(body.copyOf(bytes))
  }

  private fun runWorker(url: String): ListenableWorker.Result = runBlocking {
    TestListenableWorkerBuilder<DownloadWorker>(ctx)
      .setInputData(
        workDataOf(
          KEY_MODEL_URL to url,
          KEY_MODEL_COMMIT_HASH to "v",
          KEY_MODEL_DOWNLOAD_MODEL_DIR to "d",
          KEY_MODEL_DOWNLOAD_FILE_NAME to "m.bin",
        )
      )
      .build()
      .doWork()
  }

  @Test
  fun `a full 200 appended to a partial tmp is refused and discarded, a clean one is kept`() {
    // The appended-overlap shape #363 worries about, produced deterministically: the server ignores
    // the Range, so the whole body lands after 1000 stale bytes.
    val url = serve(honorRange = false)
    seedTmp(1000)
    val bad = runWorker(url)
    assertTrue("mis-sized download must fail, was $bad", bad is ListenableWorker.Result.Failure)
    assertEquals(
      "Downloaded m.bin.$TMP_FILE_EXT is ${1000 + body.size} bytes, the server declared ${body.size}; discarded",
      (bad as ListenableWorker.Result.Failure).outputData.getString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE),
    )
    assertFalse("the bad tmp must be deleted", tmpFile.exists())
    assertFalse("the bad tmp must never become the model file", finalFile.exists())
    // Twin, same server: with no stale bytes the identical download succeeds and is renamed.
    val good = runWorker(url)
    assertTrue("clean download must succeed, was $good", good is ListenableWorker.Result.Success)
    assertArrayEquals(body, finalFile.readBytes())
  }

  @Test
  fun `a correct 206 resume is kept`() {
    val url = serve(honorRange = true)
    seedTmp(1000)
    val result = runWorker(url)
    assertTrue("resume must succeed, was $result", result is ListenableWorker.Result.Success)
    assertNotNull(finalFile.takeIf { it.exists() })
    assertArrayEquals(body, finalFile.readBytes())
  }
}

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

import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_PARTIAL
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #363: the size the provisioner's length guard compares against is what the SERVER declared for
 * the whole file — never the catalog size, and never a guess. Unknown must read as -1 so the guard
 * skips instead of rejecting a good file.
 */
class DownloadWorkerTest {

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
}

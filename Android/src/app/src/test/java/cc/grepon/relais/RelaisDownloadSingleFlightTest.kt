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

import androidx.work.Data
import androidx.work.WorkInfo
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import cc.grepon.relais.data.KEY_MODEL_TOTAL_BYTES
import cc.grepon.relais.data.KEY_MODEL_URL
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #363: ModelsScreen and the service both download `model.name` under one unique-work key, and
 * REPLACE made each cancel the other. The fix attaches to an unfinished worker fetching the SAME
 * bytes and still REPLACEs anything else — in particular another build of the same model id, which
 * is why the key alone (KEEP) was not enough.
 */
class RelaisDownloadSingleFlightTest {

  private val gpu = RelaisModelProvisioner.G5_DEFAULT_REF
  private val tpu = RelaisModelCatalog.G5_TPU_REFS.first { it.modelId == gpu.modelId }

  private fun tagOf(ref: cc.grepon.relais.data.RelaisModelRef): String =
    downloadSpecTag(RelaisModelProvisioner.downloadInput(RelaisModelProvisioner.modelFromRef(ref)))

  private fun work(state: WorkInfo.State, vararg tags: String) =
    ExistingDownload(id = UUID.randomUUID(), state = state, tags = tags.toSet())

  // ---- attach vs replace ----

  @Test
  fun `a GPU download is not joined by a TPU pick of the same id, but is by a GPU pick`() {
    // The reason KEEP was rejected: both builds resolve to ONE unique-work name.
    assertEquals(
      RelaisModelProvisioner.modelFromRef(gpu).name,
      RelaisModelProvisioner.modelFromRef(tpu).name,
    )
    val running = work(WorkInfo.State.RUNNING, "modelName:x", tagOf(gpu))
    assertEquals(DownloadJoin.Replace, joinOrReplace(tagOf(tpu), listOf(running)))
    assertEquals(DownloadJoin.Attach(running.id), joinOrReplace(tagOf(gpu), listOf(running)))
  }

  @Test
  fun `an identical unfinished download is attached to whether running, enqueued or blocked`() {
    val tag = tagOf(gpu)
    for (state in listOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)) {
      val w = work(state, tag)
      assertEquals("state $state", DownloadJoin.Attach(w.id), joinOrReplace(tag, listOf(w)))
    }
  }

  @Test
  fun `a finished download is never attached to`() {
    val tag = tagOf(gpu)
    for (state in listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) {
      assertEquals("state $state", DownloadJoin.Replace, joinOrReplace(tag, listOf(work(state, tag))))
    }
    // Twin: the same list with one unfinished identical worker added attaches to THAT one.
    val live = work(WorkInfo.State.RUNNING, tag)
    assertEquals(
      DownloadJoin.Attach(live.id),
      joinOrReplace(tag, listOf(work(WorkInfo.State.CANCELLED, tag), live)),
    )
  }

  @Test
  fun `nothing queued, or a worker without the spec tag, means replace`() {
    assertEquals(DownloadJoin.Replace, joinOrReplace(tagOf(gpu), emptyList()))
    // A worker enqueued before #363 carries no spec tag: its input is unknown, so it is replaced,
    // exactly as every download was before.
    assertEquals(
      DownloadJoin.Replace,
      joinOrReplace(tagOf(gpu), listOf(work(WorkInfo.State.RUNNING, "modelName:x"))),
    )
  }

  // ---- the fingerprint ----

  private fun input(url: String, file: String, token: String? = null, total: Long = 1L): Data =
    Data.Builder()
      .putString(KEY_MODEL_URL, url)
      .putString(KEY_MODEL_DOWNLOAD_FILE_NAME, file)
      .putLong(KEY_MODEL_TOTAL_BYTES, total)
      .apply { token?.let { putString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN, it) } }
      .build()

  @Test
  fun `the fingerprint follows the bytes, not the display size or the token value`() {
    val base = downloadSpecTag(input("https://h/a", "f"))
    assertEquals(base, downloadSpecTag(input("https://h/a", "f")))
    assertNotEquals(base, downloadSpecTag(input("https://h/b", "f")))
    assertNotEquals(base, downloadSpecTag(input("https://h/a", "g")))
    // totalBytes is display-only and computed differently by the Gallery lane.
    assertEquals(base, downloadSpecTag(input("https://h/a", "f", total = 999L)))
  }

  @Test
  fun `a token's presence splits the fingerprint and its value never appears in it`() {
    val withToken = downloadSpecTag(input("https://h/a", "f", token = "hf_secret"))
    assertNotEquals(downloadSpecTag(input("https://h/a", "f")), withToken)
    assertEquals(withToken, downloadSpecTag(input("https://h/a", "f", token = "hf_other")))
    assertFalse(withToken.contains("hf_secret"))
    assertTrue(withToken.startsWith(DOWNLOAD_SPEC_TAG_PREFIX))
  }

  @Test
  fun `field boundaries cannot be shifted between url and file name`() {
    assertNotEquals(
      downloadSpecTag(input("https://h/ab", "c")),
      downloadSpecTag(input("https://h/a", "bc")),
    )
  }

  // ---- bounded waits ----

  private fun timeout(runningIdleMs: Long, notRunningMs: Long) =
    downloadWaitTimeout(
      runningIdleMs = runningIdleMs,
      notRunningMs = notRunningMs,
      stallTimeoutMs = 100L,
      notStartedTimeoutMs = 1_000L,
    )

  @Test
  fun `a running download with no progress past the stall window is stalled`() {
    assertNull(timeout(runningIdleMs = 100L, notRunningMs = 0L))
    assertEquals(DownloadWaitTimeout.STALLED, timeout(runningIdleMs = 101L, notRunningMs = 0L))
  }

  @Test
  fun `a download that never starts is bounded, and a queue wait is not a stall`() {
    assertNull(timeout(runningIdleMs = 0L, notRunningMs = 1_000L))
    assertEquals(DownloadWaitTimeout.NOT_STARTED, timeout(runningIdleMs = 0L, notRunningMs = 1_001L))
    // Queued longer than the STALL window but inside the not-started window: still waiting.
    assertNull(timeout(runningIdleMs = 0L, notRunningMs = 500L))
  }

  // ---- the length guard ----

  @Test
  fun `a file whose length differs from the server's declared size is refused`() {
    assertNull(downloadLengthMismatch(expectedBytes = 2_588_147_712L, actualBytes = 2_588_147_712L))
    assertEquals(
      "file is 2588147713 bytes, the server declared 2588147712",
      downloadLengthMismatch(expectedBytes = 2_588_147_712L, actualBytes = 2_588_147_713L),
    )
    assertTrue(downloadLengthMismatch(expectedBytes = 10L, actualBytes = 9L) != null)
  }

  @Test
  fun `an unknown declared size skips the check`() {
    assertNull(downloadLengthMismatch(expectedBytes = -1L, actualBytes = 5L))
    assertNull(downloadLengthMismatch(expectedBytes = 0L, actualBytes = 5L))
    // Twin: a known size with the same file does check.
    assertTrue(downloadLengthMismatch(expectedBytes = 1L, actualBytes = 5L) != null)
  }
}

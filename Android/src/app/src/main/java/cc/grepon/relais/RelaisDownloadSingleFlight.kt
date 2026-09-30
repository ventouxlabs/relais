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
import cc.grepon.relais.data.KEY_MODEL_COMMIT_HASH
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import cc.grepon.relais.data.KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES
import cc.grepon.relais.data.KEY_MODEL_EXTRA_DATA_URLS
import cc.grepon.relais.data.KEY_MODEL_IS_ZIP
import cc.grepon.relais.data.KEY_MODEL_UNZIPPED_DIR
import cc.grepon.relais.data.KEY_MODEL_URL
import java.security.MessageDigest
import java.util.UUID

/*
 * Pure decisions behind one model download shared by every caller in the process (#363).
 *
 * ModelsScreen's `startDownload` and the service's `relais-init` both reach
 * [RelaisModelProvisioner.ensureModel] for the same `model.name`, which is also the WorkManager
 * unique-work key. Enqueuing with REPLACE made each caller cancel the other's worker: the service's
 * catch then tore its listeners down, and ModelsScreen showed "Model download cancelled".
 *
 * KEEP is not the fix. The key is a model ID, not a build: the G5 GPU build of E2B
 * ([RelaisModelProvisioner.G5_DEFAULT_REF]) and its TPU build ([RelaisModelCatalog.G5_TPU_REFS])
 * share one name, so under KEEP a TPU pick made during a GPU download would silently wait on the GPU
 * file. The rule is instead "attach only to an unfinished download of the SAME bytes, else REPLACE".
 */

/** Tag prefix carrying [downloadSpecTag]. The fingerprint is a hash, so the tag never holds a secret. */
internal const val DOWNLOAD_SPEC_TAG_PREFIX = "downloadSpec:"

/**
 * A fingerprint of everything in a [cc.grepon.relais.worker.DownloadWorker] input that decides which
 * bytes land where, carried as a WorkManager TAG because `WorkInfo` does not expose a worker's input
 * data. A tag lives in WorkManager's database, so it survives process death: a worker rescheduled
 * after a watchdog restart is still recognised, where an in-memory record would read as "unknown".
 *
 * Included: url, commit, dir, file name, zip settings, extra files, and WHETHER a token is present
 * (a token-bearing request must not wait on a tokenless worker that will 401). Excluded: the token
 * itself (tags are persisted), and `KEY_MODEL_TOTAL_BYTES`, which is display-only and which the
 * Gallery lane computes differently (it adds the extras), so including it would stop the two lanes
 * from ever matching. The name is not included either: it is the unique-work key already.
 *
 * Built from the [Data] actually enqueued, never re-derived from a [cc.grepon.relais.data.Model], so
 * the fingerprint and the worker's real input cannot drift.
 */
internal fun downloadSpecTag(input: Data): String {
  val fields =
    listOf(
      input.getString(KEY_MODEL_URL),
      input.getString(KEY_MODEL_COMMIT_HASH),
      input.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR),
      input.getString(KEY_MODEL_DOWNLOAD_FILE_NAME),
      input.getBoolean(KEY_MODEL_IS_ZIP, false).toString(),
      input.getString(KEY_MODEL_UNZIPPED_DIR),
      input.getString(KEY_MODEL_EXTRA_DATA_URLS),
      input.getString(KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES),
      (input.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN) != null).toString(),
    )
  // Length-prefixed so no field's content can masquerade as a boundary ("a|b"+"c" vs "a"+"b|c"), and
  // null kept distinct from "".
  val canonical = fields.joinToString("") { f -> if (f == null) "-1:" else "${f.length}:$f" }
  val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
  return DOWNLOAD_SPEC_TAG_PREFIX + digest.joinToString("") { "%02x".format(it) }
}

/** The slice of a `WorkInfo` the join decision reads; a plain value so the decision is JVM-testable. */
internal data class ExistingDownload(val id: UUID, val state: WorkInfo.State, val tags: Set<String>)

internal sealed interface DownloadJoin {
  /** Poll this already-queued worker and enqueue nothing. */
  data class Attach(val id: UUID) : DownloadJoin

  /** Enqueue with REPLACE, exactly as before #363: a newer, different pick wins. */
  data object Replace : DownloadJoin
}

/**
 * Attach to the first UNFINISHED worker under this unique name that carries [requestedSpecTag];
 * otherwise [DownloadJoin.Replace]. A finished worker is never attached to — a SUCCEEDED one whose
 * file is missing (deleted since) must download again, and a FAILED/CANCELLED one has no future.
 * A worker without the tag (enqueued before this change) is a different spec by definition, so it is
 * REPLACEd: the same outcome as before, never a wrong attach.
 */
internal fun joinOrReplace(
  requestedSpecTag: String,
  existing: List<ExistingDownload>,
): DownloadJoin =
  existing
    .firstOrNull { !it.state.isFinished && requestedSpecTag in it.tags }
    ?.let { DownloadJoin.Attach(it.id) } ?: DownloadJoin.Replace

internal enum class DownloadWaitTimeout {
  /** RUNNING with no byte progress: a dead socket. The caller cancels the worker (unchanged). */
  STALLED,

  /**
   * Not RUNNING (ENQUEUED / BLOCKED / no WorkInfo at all) for too long. The caller gives up WITHOUT
   * cancelling: the worker may still run, and the next call attaches to it or finds the file.
   */
  NOT_STARTED,
}

/**
 * Which bound, if any, a download poll has exceeded. [runningIdleMs] is the time since the last byte
 * of progress and is pinned to zero by the caller while the worker is not RUNNING, so a queue wait
 * never reads as a stall (the pre-#363 semantics). [notRunningMs] measures how long the worker has
 * been continuously out of RUNNING, reset on every RUNNING observation, so a worker the system
 * stopped for quota and re-ENQUEUED gets a fresh window rather than its whole history.
 *
 * Before #363 the second bound did not exist: a worker stuck ENQUEUED, or an id with no WorkInfo at
 * all, kept the caller polling forever — which matters more once callers ATTACH to someone else's
 * worker. Thresholds are required parameters so every row of the test states them.
 */
internal fun downloadWaitTimeout(
  runningIdleMs: Long,
  notRunningMs: Long,
  stallTimeoutMs: Long,
  notStartedTimeoutMs: Long,
): DownloadWaitTimeout? =
  when {
    runningIdleMs > stallTimeoutMs -> DownloadWaitTimeout.STALLED
    notRunningMs > notStartedTimeoutMs -> DownloadWaitTimeout.NOT_STARTED
    else -> null
  }

/**
 * Why a finished download's length disagrees with the size the SERVER declared for it, or null when
 * it agrees or the declared size is unknown (≤ 0, see `serverDeclaredSize` in the worker). A GUARD:
 * #363's overlapping-append corruption (a cancelled worker still appending to the `.tmp` the
 * replacing worker resumes from) is reasoned from the code, not observed — rango's existing TPU file
 * measured exactly its ref size. It also catches a server that answers a Range request with a full
 * 200, which the worker appends to the partial file.
 *
 * Deliberately NOT the catalog `sizeInBytes`: a stale catalog would reject a good file and re-download
 * it forever. The server's own figure is the only one that describes the bytes that were sent.
 */
internal fun downloadLengthMismatch(expectedBytes: Long, actualBytes: Long): String? =
  if (expectedBytes <= 0L || expectedBytes == actualBytes) null
  else "file is $actualBytes bytes, the server declared $expectedBytes"

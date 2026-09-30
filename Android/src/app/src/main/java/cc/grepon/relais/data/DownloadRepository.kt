/*
 * Copyright 2025 Google LLC
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

package cc.grepon.relais.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cc.grepon.relais.AppLifecycleProvider
import cc.grepon.relais.GalleryEvent
import cc.grepon.relais.R
import cc.grepon.relais.RelaisRuntimeCompat
import cc.grepon.relais.downloadSpecTag
import cc.grepon.relais.firebaseAnalytics
import cc.grepon.relais.worker.DownloadWorker
import java.util.UUID
import java.util.concurrent.Executors

private const val TAG = "AGDownloadRepository"
private const val MODEL_NAME_TAG = "modelName"
private const val TASK_ID_TAG = "taskId"

data class AGWorkInfo(val taskId: String, val modelName: String, val workId: String)

interface DownloadRepository {
  fun downloadModel(
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )

  fun cancelDownloadModel(model: Model)

  fun cancelAll(onComplete: () -> Unit)

  fun observerWorkerProgress(
    workerId: UUID,
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  )
}

private const val DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID = "___"

/**
 * Repository for managing model downloads using WorkManager.
 *
 * This class provides methods to initiate model downloads, cancel downloads, observe download
 * progress, and retrieve information about enqueued or running download tasks. It utilizes
 * WorkManager to handle background download operations.
 */
class DefaultDownloadRepository(
  private val context: Context,
  private val lifecycleProvider: AppLifecycleProvider,
) : DownloadRepository {
  private val workManager = WorkManager.getInstance(context)
  /**
   * Stores the start time of a model download.
   *
   * We use SharedPreferences to persist the download start times. This ensures that the data is
   * still available after the app restarts. The key is the model name and the value is the download
   * start time in milliseconds.
   */
  private val downloadStartTimeSharedPreferences =
    context.getSharedPreferences("download_start_time_ms", Context.MODE_PRIVATE)

  /**
   * Last reported byte count per model, so a re-enqueue can say where the download actually stands.
   *
   * In-memory only, and deliberately so: it is a display detail, not state the download depends on
   * — the authoritative resume point is the partial `.tmp` file on disk, which `DownloadWorker`
   * reads to build its `Range` request. Losing this map to process death costs a progress bar that
   * briefly reads zero, never a re-downloaded byte.
   */
  private val lastReceivedBytes = java.util.concurrent.ConcurrentHashMap<String, Long>()

  override fun downloadModel(
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    // #220: refuse a model MEASURED not to load on the pinned runtime, before a single byte moves.
    //
    // This is the real chokepoint for the legacy (upstream Gallery) download stack, and it is NOT
    // reachable from RelaisModelProvisioner's gates — the node provisioner drives DownloadWorker
    // itself. Two callers land here and only one of them goes through
    // ModelManagerViewModel.downloadModel:
    //   - a tap on a model row (via that method), and
    //   - processPendingDownloads(), which calls this method DIRECTLY to resume every
    //     PARTIALLY_DOWNLOADED model on each MainActivity launch.
    // The resume path is the one that matters: it needs no user action, it reads the RAW allowlist
    // (ModelManagerViewModel never consults RelaisModelCatalog), and a device holding a partial
    // pre-#220 Qwen2.5-1.5B download would resume that multi-GB transfer on every cold start for a
    // model the engine can never create against. Gating the ViewModel instead would miss it.
    //
    // Keyed off the URL because [Model] carries no model id; see
    // [RelaisRuntimeCompat.repoIdFromDownloadUrl]. An unidentifiable URL is ALLOWED — only measured
    // failures are withheld.
    RelaisRuntimeCompat.incompatibleReasonForDownloadUrl(model.url)?.let { why ->
      Log.w(TAG, "Refusing download of '${model.name}': $why")
      onStatusUpdated(
        model,
        ModelDownloadStatus(
          status = ModelDownloadStatusType.FAILED,
          errorMessage = RelaisRuntimeCompat.refusalMessage(model.name, why),
        ),
      )
      return
    }
    // Create input data.
    val builder = Data.Builder()
    val totalBytes = model.totalBytes + model.extraDataFiles.sumOf { it.sizeInBytes }
    val inputDataBuilder =
      builder
        .putString(KEY_MODEL_NAME, model.name)
        .putString(KEY_MODEL_URL, model.url)
        .putString(KEY_MODEL_COMMIT_HASH, model.version)
        .putString(KEY_MODEL_DOWNLOAD_MODEL_DIR, model.normalizedName)
        .putString(KEY_MODEL_DOWNLOAD_FILE_NAME, model.downloadFileName)
        .putBoolean(KEY_MODEL_IS_ZIP, model.isZip)
        .putString(KEY_MODEL_UNZIPPED_DIR, model.unzipDir)
        .putLong(KEY_MODEL_TOTAL_BYTES, totalBytes)

    if (model.extraDataFiles.isNotEmpty()) {
      inputDataBuilder
        .putString(KEY_MODEL_EXTRA_DATA_URLS, model.extraDataFiles.joinToString(",") { it.url })
        .putString(
          KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES,
          model.extraDataFiles.joinToString(",") { it.downloadFileName },
        )
    }
    if (model.accessToken != null) {
      inputDataBuilder.putString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN, model.accessToken)
    }
    val inputData = inputDataBuilder.build()

    // Create worker request.
    val downloadWorkRequest =
      OneTimeWorkRequestBuilder<DownloadWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(inputData)
        .addTag("$MODEL_NAME_TAG:${model.name}")
        .addTag("$TASK_ID_TAG:${task?.id ?: ""}")
        // #363: the same input fingerprint the node provisioner tags with, so a provisioner call for
        // the identical file attaches to this worker instead of REPLACEing it.
        .addTag(downloadSpecTag(inputData))
        .build()

    val workerId = downloadWorkRequest.id

    // Start! Still REPLACE (#363 left this lane's own policy alone): attaching here would need a
    // blocking WorkManager query on the caller's main thread, and this lane observes by worker id via
    // `observeForever`, so switching ids would mean restructuring the observer. The provisioner's
    // attach covers the Relais-owned callers (ModelsScreen, the service); a Gallery tap or a
    // launch-time resume can still REPLACE a provisioner download of the same model name.
    workManager.enqueueUniqueWork(model.name, ExistingWorkPolicy.REPLACE, downloadWorkRequest)

    // Observe progress.
    observerWorkerProgress(
      workerId = workerId,
      task = task,
      model = model,
      onStatusUpdated = onStatusUpdated,
    )
  }

  override fun cancelDownloadModel(model: Model) {
    workManager.cancelAllWorkByTag("$MODEL_NAME_TAG:${model.name}")
  }

  override fun cancelAll(onComplete: () -> Unit) {
    workManager
      .cancelAllWork()
      .result
      .addListener({ onComplete() }, Executors.newSingleThreadExecutor())
  }

  override fun observerWorkerProgress(
    workerId: UUID,
    task: Task?,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {
    workManager.getWorkInfoByIdLiveData(workerId).observeForever { workInfo ->
      if (workInfo != null) {
        when (workInfo.state) {
          WorkInfo.State.ENQUEUED -> {
            // ENQUEUED is two events with one name: a download starting, and a download RESUMING
            // after the system stopped its worker (a lost constraint, or on Android 16+ a spent job
            // quota). This branch used to treat both as a start, which silently restamped the start
            // timestamp and logged a second start event on every interruption — see
            // [dispositionForEnqueue].
            val disposition =
              dispositionForEnqueue(downloadStartTimeSharedPreferences.getLong(model.name, 0L))
            if (disposition.recordStartTime) {
              downloadStartTimeSharedPreferences.edit {
                putLong(model.name, System.currentTimeMillis())
              }
            }
            if (disposition.logStartEvent) {
              firebaseAnalytics?.logEvent(
                GalleryEvent.MODEL_DOWNLOAD.id,
                bundleOf("event_type" to "start", "model_id" to model.name),
              )
            }
            if (disposition.isResume) {
              // The app had NO stop observability before this; a stalled download was
              // indistinguishable from a throttled one. Seeing STOP_REASON_QUOTA here is the
              // evidence that would justify moving downloads off WorkManager entirely.
              Log.i(
                TAG,
                "download for %s re-enqueued, waiting to resume: %s"
                  .format(model.name, describeStopReason(workInfo.stopReason)),
              )
              // Report the last known byte count rather than nothing: dropping to zero would read
              // as "the download restarted", when in fact the bytes on disk are kept and the
              // transfer resumes with a Range request.
              onStatusUpdated(
                model,
                ModelDownloadStatus(
                  status = ModelDownloadStatusType.IN_PROGRESS,
                  totalBytes = model.totalBytes,
                  receivedBytes = lastReceivedBytes[model.name] ?: 0L,
                  waitingToResume = true,
                ),
              )
            }
          }

          WorkInfo.State.RUNNING -> {
            val receivedBytes = workInfo.progress.getLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, 0L)
            val downloadRate = workInfo.progress.getLong(KEY_MODEL_DOWNLOAD_RATE, 0L)
            val remainingSeconds = workInfo.progress.getLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, 0L)
            val startUnzipping = workInfo.progress.getBoolean(KEY_MODEL_START_UNZIPPING, false)

            if (!startUnzipping) {
              if (receivedBytes != 0L) {
                // Remembered so a later re-enqueue can report where the download actually is; the
                // ENQUEUED transition carries no progress data of its own.
                lastReceivedBytes[model.name] = receivedBytes
                onStatusUpdated(
                  model,
                  ModelDownloadStatus(
                    status = ModelDownloadStatusType.IN_PROGRESS,
                    totalBytes = model.totalBytes,
                    receivedBytes = receivedBytes,
                    bytesPerSecond = downloadRate,
                    remainingMs = remainingSeconds,
                  ),
                )
              }
            } else {
              onStatusUpdated(
                model,
                ModelDownloadStatus(status = ModelDownloadStatusType.UNZIPPING),
              )
            }
          }

          WorkInfo.State.SUCCEEDED -> {
            Log.d("repo", "worker %s success".format(workerId.toString()))
            onStatusUpdated(model, ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED))
            sendNotification(
              title = context.getString(R.string.notification_title_success),
              text = context.getString(R.string.notification_content_success).format(model.name),
              taskId = task?.id ?: DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID,
              modelName = model.name,
            )

            val startTime = downloadStartTimeSharedPreferences.getLong(model.name, 0L)
            val duration = System.currentTimeMillis() - startTime
            firebaseAnalytics?.logEvent(
              GalleryEvent.MODEL_DOWNLOAD.id,
              bundleOf(
                "event_type" to "success",
                "model_id" to model.name,
                "duration_ms" to duration,
              ),
            )
            downloadStartTimeSharedPreferences.edit { remove(model.name) }
            lastReceivedBytes.remove(model.name)
          }

          WorkInfo.State.FAILED,
          WorkInfo.State.CANCELLED -> {
            var status = ModelDownloadStatusType.FAILED
            val errorMessage = workInfo.outputData.getString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE) ?: ""
            Log.d(
              "repo",
              "worker %s FAILED or CANCELLED: %s".format(workerId.toString(), errorMessage),
            )
            if (workInfo.state == WorkInfo.State.CANCELLED) {
              status = ModelDownloadStatusType.NOT_DOWNLOADED
            } else {
              sendNotification(
                title = context.getString(R.string.notification_title_fail),
                text = context.getString(R.string.notification_content_success).format(model.name),
                taskId = "",
                modelName = "",
              )
            }
            onStatusUpdated(
              model,
              ModelDownloadStatus(status = status, errorMessage = errorMessage),
            )

            val startTime = downloadStartTimeSharedPreferences.getLong(model.name, 0L)
            val duration = System.currentTimeMillis() - startTime
            // TODO: Add failure reasons
            firebaseAnalytics?.logEvent(
              GalleryEvent.MODEL_DOWNLOAD.id,
              bundleOf(
                "event_type" to "failure",
                "model_id" to model.name,
                "duration_ms" to duration,
              ),
            )
            downloadStartTimeSharedPreferences.edit { remove(model.name) }
            lastReceivedBytes.remove(model.name)
          }

          else -> {}
        }
      }
    }
  }

  private fun sendNotification(title: String, text: String, taskId: String, modelName: String) {
    // Don't send notification if app is in foreground.
    if (lifecycleProvider.isAppInForeground) {
      return
    }

    val channelId = "download_notification"
    val channelName = "Relais download notification"

    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    val importance = NotificationManager.IMPORTANCE_HIGH
    val channel = NotificationChannel(channelId, channelName, importance)
    val notificationManager: NotificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(channel)

    val intent: Intent
    if (taskId.isEmpty()) {
      // If taskId is empty, it's a failed download. Just open the app's main screen.
      intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
    }
    // Download from global model manager. Open the global model manager screen.
    else if (taskId == DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID) {
      intent =
        Intent(Intent.ACTION_VIEW, "com.ventouxlabs.relais://global_model_manager".toUri())
          .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
    } else {

      // Otherwise, create the deep link as before.
      intent =
        Intent(
            Intent.ACTION_VIEW,
            "com.ventouxlabs.relais://model/$taskId/${modelName}".toUri(),
          )
          .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
    }

    // Create a PendingIntent
    val pendingIntent: PendingIntent =
      PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val builder =
      NotificationCompat.Builder(context, channelId)
        // TODO: replace icon.
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)

    with(NotificationManagerCompat.from(context)) {
      // notificationId is a unique int for each notification that you must define
      if (
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
      ) {
        // Permission not granted, return or handle accordingly. In real app, request permission.
        return
      }
      notify(1, builder.build())
    }
  }
}

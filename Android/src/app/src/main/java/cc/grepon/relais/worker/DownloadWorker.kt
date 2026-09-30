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

package cc.grepon.relais.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import cc.grepon.relais.data.describeStopReason
import androidx.work.WorkerParameters
import cc.grepon.relais.data.KEY_MODEL_COMMIT_HASH
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_EXPECTED_BYTES
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_RATE
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_REMAINING_MS
import cc.grepon.relais.data.KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES
import cc.grepon.relais.data.KEY_MODEL_EXTRA_DATA_URLS
import cc.grepon.relais.data.KEY_MODEL_IS_ZIP
import cc.grepon.relais.data.KEY_MODEL_NAME
import cc.grepon.relais.data.KEY_MODEL_START_UNZIPPING
import cc.grepon.relais.data.KEY_MODEL_TOTAL_BYTES
import cc.grepon.relais.data.KEY_MODEL_UNZIPPED_DIR
import cc.grepon.relais.data.KEY_MODEL_URL
import cc.grepon.relais.data.TMP_FILE_EXT
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGDownloadWorker"

data class UrlAndFileName(val url: String, val fileName: String)

/**
 * The complete size of the file the server is sending, or -1 when it did not say (#363).
 *
 * A 206 carries it as the total in `Content-Range: bytes a-b/TOTAL` (`*` = unknown); its
 * Content-Length is only the remaining slice. A 200 carries it as Content-Length, which is -1 when
 * absent — including when the platform transparently gunzipped the body. Anything else is unknown,
 * and an unknown size makes the caller skip its length check rather than guess.
 */
internal fun serverDeclaredSize(responseCode: Int, contentRange: String?, contentLength: Long): Long {
  val declared =
    when (responseCode) {
      HttpURLConnection.HTTP_PARTIAL -> contentRange?.substringAfter('/', "")?.trim()?.toLongOrNull()
      HttpURLConnection.HTTP_OK -> contentLength
      else -> null
    }
  return declared?.takeIf { it > 0L } ?: -1L
}

private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "model_download_channel_foreground"
private var channelCreated = false

class DownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  private val externalFilesDir = context.getExternalFilesDir(null)

  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  // Unique notification id.
  private val notificationId: Int = params.id.hashCode()

  init {
    if (!channelCreated) {
      // Create a notification channel for showing notifications for model downloading progress.
      val channel =
        NotificationChannel(
            FOREGROUND_NOTIFICATION_CHANNEL_ID,
            "Model Downloading",
            // Make it silent.
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply { description = "Notifications for model downloading" }
      notificationManager.createNotificationChannel(channel)
      channelCreated = true
    }
  }

  override suspend fun doWork(): Result {
    val fileUrl = inputData.getString(KEY_MODEL_URL)
    val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
    val version = inputData.getString(KEY_MODEL_COMMIT_HASH)!!
    val fileName = inputData.getString(KEY_MODEL_DOWNLOAD_FILE_NAME)
    val modelDir = inputData.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR)!!
    val isZip = inputData.getBoolean(KEY_MODEL_IS_ZIP, false)
    val unzippedDir = inputData.getString(KEY_MODEL_UNZIPPED_DIR)
    val extraDataFileUrls = inputData.getString(KEY_MODEL_EXTRA_DATA_URLS)?.split(",") ?: listOf()
    val extraDataFileNames =
      inputData.getString(KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES)?.split(",") ?: listOf()
    val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
    val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)

    return withContext(Dispatchers.IO) {
      if (fileUrl == null || fileName == null) {
        Result.failure()
      } else {
        return@withContext try {
          // Set the worker as a foreground service immediately.
          setForeground(createForegroundInfo(progress = 0, modelName = modelName))

          // Collect data for all files.
          val allFiles: MutableList<UrlAndFileName> = mutableListOf()
          allFiles.add(UrlAndFileName(url = fileUrl, fileName = fileName))
          for (index in extraDataFileUrls.indices) {
            allFiles.add(
              UrlAndFileName(url = extraDataFileUrls[index], fileName = extraDataFileNames[index])
            )
          }
          Log.d(TAG, "About to download: $allFiles")

          // Download them in sequence.
          // TODO: maybe consider downloading them in parallel.
          var downloadedBytes = 0L
          var expectedMainFileBytes = -1L
          val bytesReadSizeBuffer: MutableList<Long> = mutableListOf()
          val bytesReadLatencyBuffer: MutableList<Long> = mutableListOf()
          for (file in allFiles) {
            val url = URL(file.url)

            val connection = url.openConnection() as HttpURLConnection
            if (accessToken != null) {
              // Never log the token — not even a prefix: logcat is readable via adb / READ_LOGS, and
              // the old subSequence(0, 10) also threw IndexOutOfBounds on tokens shorter than 10 chars.
              Log.d(TAG, "Using a Bearer access token for $modelName")
              connection.setRequestProperty("Authorization", "Bearer $accessToken")
            }

            // Prepare output file's dir.
            val outputDir =
              File(
                applicationContext.getExternalFilesDir(null),
                listOf(modelDir, version).joinToString(separator = File.separator),
              )
            if (!outputDir.exists()) {
              outputDir.mkdirs()
            }

            // Read the tmp file and see if it is partially downloaded.
            val outputTmpFile =
              File(
                applicationContext.getExternalFilesDir(null),
                listOf(modelDir, version, "${file.fileName}.$TMP_FILE_EXT")
                  .joinToString(separator = File.separator),
              )
            val outputFileBytes = outputTmpFile.length()
            if (outputFileBytes > 0) {
              Log.d(
                TAG,
                "File '${outputTmpFile.name}' partial size: ${outputFileBytes}. Trying to resume download",
              )
              connection.setRequestProperty("Range", "bytes=${outputFileBytes}-")
              // Force the server to send non-compressed data to make download resuming work.
              connection.setRequestProperty("Accept-Encoding", "identity")
            }
            connection.connect()
            Log.d(TAG, "response code: ${connection.responseCode}")

            if (
              connection.responseCode == HttpURLConnection.HTTP_OK ||
                connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            ) {
              val contentRange = connection.getHeaderField("Content-Range")

              if (contentRange != null) {
                // Parse the Content-Range header
                val rangeParts = contentRange.substringAfter("bytes ").split("/")
                val byteRange = rangeParts[0].split("-")
                val startByte = byteRange[0].toLong()
                val endByte = byteRange[1].toLong()

                Log.d(
                  TAG,
                  "Content-Range: $contentRange. Start bytes: ${startByte}, end bytes: $endByte",
                )

                downloadedBytes += startByte
              } else {
                Log.d(TAG, "Download starts from beginning.")
              }
            } else {
              throw IOException("HTTP error code: ${connection.responseCode}")
            }
            // #363: record what the server says the MAIN file's complete size is, for the caller's
            // length guard. Extra files are not recorded: only the provisioner checks, and it has none.
            if (file === allFiles.first()) {
              expectedMainFileBytes =
                serverDeclaredSize(
                  responseCode = connection.responseCode,
                  contentRange = connection.getHeaderField("Content-Range"),
                  contentLength = connection.contentLengthLong,
                )
            }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(outputTmpFile, true /* append */)

            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead: Int
            var lastSetProgressTs: Long = 0
            var deltaBytes = 0L
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
              outputStream.write(buffer, 0, bytesRead)
              downloadedBytes += bytesRead
              deltaBytes += bytesRead

              // Report progress every 200 ms.
              val curTs = System.currentTimeMillis()
              if (curTs - lastSetProgressTs > 200) {
                // Calculate download rate.
                var bytesPerMs = 0f
                if (lastSetProgressTs != 0L) {
                  if (bytesReadSizeBuffer.size == 5) {
                    bytesReadSizeBuffer.removeAt(0)
                  }
                  bytesReadSizeBuffer.add(deltaBytes)
                  if (bytesReadLatencyBuffer.size == 5) {
                    bytesReadLatencyBuffer.removeAt(0)
                  }
                  bytesReadLatencyBuffer.add(curTs - lastSetProgressTs)
                  deltaBytes = 0L
                  bytesPerMs = bytesReadSizeBuffer.sum().toFloat() / bytesReadLatencyBuffer.sum()
                }

                // Calculate remaining seconds
                var remainingMs = 0f
                if (bytesPerMs > 0f && totalBytes > 0L) {
                  remainingMs = (totalBytes - downloadedBytes) / bytesPerMs
                }

                setProgress(
                  Data.Builder()
                    .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes)
                    .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                    .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs.toLong())
                    .build()
                )
                setForeground(
                  createForegroundInfo(
                    // Guard a non-positive totalBytes (a size-unknown HF ref resolves to -1): avoid a
                    // divide-by-zero / negative notification progress. Mirrors the provisioner's
                    // `totalBytes > 0` guard on its own progress callback.
                    progress =
                      if (totalBytes > 0L) (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
                      else 0,
                    modelName = modelName,
                  )
                )
                Log.d(TAG, "downloadedBytes: $downloadedBytes")
                lastSetProgressTs = curTs
              }
            }

            outputStream.close()
            inputStream.close()

            // Rename the tmp file to the original file name by removing the tmp file ext.
            val originalFilePath = outputTmpFile.absolutePath.replace(".$TMP_FILE_EXT", "")
            val originalFile = File(originalFilePath)
            if (originalFile.exists()) {
              originalFile.delete()
            }
            outputTmpFile.renameTo(originalFile)
            Log.d(TAG, "Download done")

            // Unzip if the downloaded file is a zip.
            if (isZip && unzippedDir != null) {
              setProgress(Data.Builder().putBoolean(KEY_MODEL_START_UNZIPPING, true).build())

              // Prepare target dir.
              val destDir =
                File(
                  externalFilesDir,
                  listOf(modelDir, version, unzippedDir).joinToString(File.separator),
                )
              if (!destDir.exists()) {
                destDir.mkdirs()
              }

              // Unzip.
              val unzipBuffer = ByteArray(4096)
              val zipFilePath =
                "${externalFilesDir}${File.separator}$modelDir${File.separator}$version${File.separator}${fileName}"
              val zipIn = ZipInputStream(BufferedInputStream(FileInputStream(zipFilePath)))
              var zipEntry: ZipEntry? = zipIn.nextEntry

              while (zipEntry != null) {
                val filePath = destDir.absolutePath + File.separator + zipEntry.name

                // Extract files.
                if (!zipEntry.isDirectory) {
                  // extract file
                  val bos = FileOutputStream(filePath)
                  bos.use { curBos ->
                    var len: Int
                    while (zipIn.read(unzipBuffer).also { len = it } > 0) {
                      curBos.write(unzipBuffer, 0, len)
                    }
                  }
                }
                // Create dir.
                else {
                  val dir = File(filePath)
                  dir.mkdirs()
                }

                zipIn.closeEntry()
                zipEntry = zipIn.nextEntry
              }
              zipIn.close()

              // Delete the original file.
              val zipFile = File(zipFilePath)
              zipFile.delete()
            }
          }
          Result.success(
            Data.Builder().putLong(KEY_MODEL_DOWNLOAD_EXPECTED_BYTES, expectedMainFileBytes).build()
          )
        } catch (e: IOException) {
          Log.e(TAG, e.message, e)
          Result.failure(
            Data.Builder().putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, e.message).build()
          )
        } finally {
          // Why the system took this worker away. `onStopped()` is final on CoroutineWorker, and a
          // stop cancels this coroutine, so a `finally` guarded on [isStopped] is the seam that
          // actually reports it.
          //
          // The app had NO stop observability before this: a halted download looked identical
          // whether the network dropped, the user cancelled, or the platform reclaimed the job. On
          // Android 16+ this is the only way to see `STOP_REASON_QUOTA` — jobs running alongside a
          // foreground service now count against the app's JobScheduler runtime quota regardless of
          // `targetSdkVersion`, and a multi-gigabyte model download is exactly the shape of work
          // that spends it.
          //
          // Nothing is cleaned up here on purpose: the partial `.tmp` file IS the resume point, and
          // deleting it would turn a recoverable pause into a full re-download.
          if (isStopped) {
            Log.i(TAG, "download worker stopped: ${describeStopReason(stopReason)}")
          }
        }
      }
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    // Initial progress is 0
    return createForegroundInfo(0)
  }

  /**
   * Creates a [ForegroundInfo] object for the download worker's ongoing notification. This
   * notification is used to keep the worker running in the foreground, indicating to the user that
   * an active download is in progress.
   */
  private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
    // Create a notification for the foreground service
    var title = "Downloading model"
    if (modelName != null) {
      title = "Downloading \"$modelName\""
    }
    val content = "Downloading in progress: $progress%"

    val intent =
      Intent(applicationContext, Class.forName("cc.grepon.relais.MainActivity")).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        applicationContext,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val notification =
      NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true) // Makes the notification non-dismissable
        .setProgress(100, progress, false) // Show progress
        .setContentIntent(pendingIntent)
        .build()

    return ForegroundInfo(
      notificationId,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }
}

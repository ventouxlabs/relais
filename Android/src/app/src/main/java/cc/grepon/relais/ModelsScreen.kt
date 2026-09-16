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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen MODELS destination (Unified App Shell, Task 5): shows the currently served model and
 * opens [RelaisModelSelectorSheet] to change it. Mirrors [RelaisConfigureActivity]'s
 * `showModelSheet` pattern — the selector is a `ModalBottomSheet`, so this screen is just a header
 * row plus the affordance that toggles it open. Persistence goes straight through [RelaisConfig],
 * same as Configure's `onPickRef`/`onPickManualId` bodies. Not wired into a NavHost yet — that's
 * Task 6. Deliberately not wrapped in `RelaisTheme`; the shell provides that once.
 */
@Composable
fun ModelsScreen() {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var modelId by remember { mutableStateOf(RelaisConfig.modelId(ctx)) }
  var modelRef by remember { mutableStateOf(RelaisConfig.modelRef(ctx)) }
  var showSheet by remember { mutableStateOf(false) }
  var reloading by remember { mutableStateOf(false) }
  var reloadJob by remember { mutableStateOf<Job?>(null) }
  var download by remember { mutableStateOf<ModelDownloadState>(ModelDownloadState.Idle) }
  var downloadJob by remember { mutableStateOf<Job?>(null) }

  // #217: picking a model used to ONLY persist the selection — the bytes were fetched later as a
  // side effect of the node starting, with no progress and no message, so a pick looked like a dead
  // button. Fetch here, visibly. `ensureModel` is idempotent, so this doubles as the retry path and
  // as an "is it already on disk?" check.
  fun startDownload() {
    if (download.isInFlight()) return
    downloadJob?.cancel()
    download = ModelDownloadState.Preparing
    downloadJob =
      scope.launch {
        val result =
          withContext(Dispatchers.IO) {
            runCatching {
              RelaisModelProvisioner.ensureModel(ctx) { pct ->
                download = ModelDownloadState.Downloading(pct)
              }
            }
          }
        download =
          result.fold(
            onSuccess = { ModelDownloadState.Ready(RelaisConfig.modelId(ctx)) },
            onFailure = { e ->
              ModelDownloadState.Failed(e.message ?: e::class.simpleName ?: "unknown error")
            },
          )
      }
  }

  // Mirror the in-chat selector's reload feedback (both routes persist through [ModelSwitch]): after
  // a pick, show "reloading model…" until the engine settles, so this screen and the chat sheet
  // behave identically instead of ModelsScreen switching silently. Cancel any in-flight poll first
  // so a rapid re-pick doesn't leave overlapping pollers flickering the flag.
  fun observeReload() {
    reloadJob?.cancel()
    reloading = true
    reloadJob = scope.launch { reloading = !ModelSwitch.awaitReload() }
  }

  Column(
    modifier = Modifier.systemBarsPadding().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      "MODEL",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
      letterSpacing = 1.5.sp,
    )
    Text(
      modelRef?.takeIf { it.modelId == modelId }?.displayName ?: modelId,
      color = Paper,
      fontFamily = FontFamily.Monospace,
      fontSize = 13.sp,
    )
    Text(
      "CHANGE MODEL ›",
      color = Amber,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 12.sp,
      modifier =
        Modifier.clip(RoundedCornerShape(6.dp)).clickable { showSheet = true }.padding(vertical = 4.dp),
    )
    // Explicit DOWNLOAD affordance: a pick auto-fetches, but this is the retry after a failure and
    // the way to fetch a model that was selected before this screen could download at all.
    if (!download.isInFlight()) {
      Text(
        "DOWNLOAD MODEL ›",
        color = Amber,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier =
          Modifier.clip(RoundedCornerShape(6.dp))
            .clickable { startDownload() }
            .padding(vertical = 4.dp),
      )
    }
    modelDownloadLine(download)?.let { line ->
      Text(
        line,
        color = if (download is ModelDownloadState.Failed) StopRed else Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
      )
    }
    modelDownloadHint(download)?.let { hint ->
      Text(
        hint,
        color = Paper,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
      )
    }
    if (reloading) {
      Text(
        "reloading model — $modelId…",
        color = Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
      )
    }
  }

  if (showSheet) {
    RelaisModelSelectorSheet(
      currentModelId = modelId,
      // The saved token (not an editable field here): HF resolve and the later download both
      // authenticate with the persisted token, mirroring RelaisConfigureActivity.
      hfToken = RelaisConfig.hfToken(ctx),
      onPickRef = { ref ->
        ModelSwitch.applyRef(ctx, ref)
        modelRef = ref
        modelId = ref.modelId
        showSheet = false
        download = ModelDownloadState.Idle
        startDownload()
        observeReload()
      },
      onPickManualId = { id ->
        // Entering a raw id is an explicit "resolve this via the allowlist" intent; [ModelSwitch]
        // drops any curated ref first so the pinned ref can't keep overriding allowlist resolution.
        // resolvedPath = null: an explicit allowlist-resolution intent, so resolveModel runs and
        // remembers the path itself. Nothing here bypasses resolution.
        ModelSwitch.applyManualId(ctx, id, resolvedPath = null)
        modelRef = null
        modelId = id
        showSheet = false
        download = ModelDownloadState.Idle
        startDownload()
        observeReload()
      },
      onDismiss = { showSheet = false },
    )
  }
}

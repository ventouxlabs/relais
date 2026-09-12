/*
 * Copyright 2026 Google LLC
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

package cc.grepon.relais

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
  state: RelaisControlPanelState,
  modelDisplay: String,
  onPrimaryAction: () -> Unit,
  onOpenConfigure: () -> Unit,
  onOpenModelSheet: () -> Unit,
) {
  val ctx = LocalContext.current
  val ip = remember { RelaisLanIp.lanIpv4() }
  val statusColor =
    when (state.status) {
      NodeStatus.LIVE -> Amber
      NodeStatus.STARTING -> Amber.copy(alpha = 0.6f)
      NodeStatus.OFFLINE -> Muted
    }
  // State transitions crossfade color over ~300ms (§5) instead of an instant color jump.
  val animatedStatusColor by animateColorAsState(statusColor, tween(300), label = "statusColor")

  // Beacon pulse stays exclusive to LIVE — STARTING is a static 60%-alpha dot, no spinner (§5).
  val pulse =
    if (state.status == NodeStatus.LIVE) {
      val t = rememberInfiniteTransition(label = "beacon")
      t.animateFloat(
          initialValue = 0.3f,
          targetValue = 1f,
          animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
          label = "alpha",
        )
        .value
    } else 1f

  Column(
    modifier =
      Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)
        .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(20.dp), // 20dp between divider-separated sections (§4.0)
  ) {
    // Header: dot + wordmark + state word, then the single detail/phase line (P8, P12 — the
    // old STATUS row and tagline are gone; the header + this one line carry all of it).
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier =
            Modifier.size(11.dp).clip(CircleShape)
              .background(animatedStatusColor.copy(alpha = pulse))
        )
        Spacer(Modifier.width(10.dp))
        Text(
          "RELAIS",
          color = Amber,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 22.sp, // Display tier
          letterSpacing = 5.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
          state.statusWord,
          color = animatedStatusColor,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          letterSpacing = 2.sp,
        )
      }
      Text(
        state.detailLine,
        // detailLineBright is derived once in the pure state (thermal shed or failed-init) —
        // Paper = attention by brightness, no new color; every other detail line stays Muted.
        color = if (state.detailLineBright) Paper else Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp, // Caption tier
      )
      if (state.showProgressBar) {
        val fraction = state.progressFraction ?: 0f
        LinearProgressIndicator(
          progress = { fraction },
          modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
          color = Amber,
          trackColor = Line,
        )
      }
    }

    Divider()

    // Connection: the daily values an operator leaves the app with (P3, P4).
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (state.lanEndpointLive) {
        HeroCopyableRow(label = "LAN (https)", value = "$ip:8443")
      } else {
        CopyableRow(label = "LAN (https)", value = "$ip:8443", valueColor = Muted)
      }
      if (state.showLocalEndpoint) {
        CopyableRow(label = "LOCAL (http)", value = "127.0.0.1:8080", valueColor = Paper)
      }
      AccessKeyChip(apiKey = RelaisConfig.apiKey(ctx), baseUrl = "https://$ip:8443/v1")
    }

    Divider()

    // Model summary (read-only; opens Configure) + the one deliberate tap to everything rare.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      ModelSummaryRow(value = modelDisplay, enabled = state.modelRowEnabled) {
        onOpenModelSheet()
      }
      state.modelLockedCaption?.let { caption ->
        Text(caption, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
      }
      ActionLink("CONFIGURE ›") { onOpenConfigure() }
    }
    PrimaryButton(state.primaryAction) { onPrimaryAction() }
  }
}

@Composable
private fun Divider() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** Amber tap affordance, e.g. "CONFIGURE ›" — matches the "SHARE CONNECTION ›" idiom. */
@Composable
private fun ActionLink(label: String, onClick: () -> Unit) {
  Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClick() }.padding(vertical = 4.dp)) {
    Text(label, color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
  }
}

/**
 * A copyable label/value row (LAN, LOCAL): tapping the row copies [value] and the trailing glyph
 * swaps `⧉` → `COPIED` for 1.5s (the [AccessKeyChip] timing, reused). [valueColor] carries the
 * Muted-preview-vs-Paper-live distinction (§4.1/§4.3).
 */
@Composable
private fun CopyableRow(label: String, value: String, valueColor: Color) {
  val clipboard = LocalClipboardManager.current
  var copied by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      delay(1500)
      copied = false
    }
  }
  Row(
    modifier =
      Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
        .clickable { clipboard.setText(AnnotatedString(value)); copied = true },
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Spacer(Modifier.width(16.dp))
    Text(
      value,
      color = valueColor,
      fontFamily = FontFamily.Monospace,
      fontSize = 13.sp,
      modifier = Modifier.weight(1f, fill = false),
    )
    Spacer(Modifier.width(10.dp))
    Text(
      if (copied) "COPIED" else "⧉",
      color = Amber,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 11.sp,
    )
  }
}

/**
 * The LIVE-only Hero-tier row (§4.3): label above, then the value at 17sp Paper — the single
 * highest-salience element on the screen — with the same copy affordance as [CopyableRow].
 */
@Composable
private fun HeroCopyableRow(label: String, value: String) {
  val clipboard = LocalClipboardManager.current
  var copied by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      delay(1500)
      copied = false
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(label, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Row(
      modifier =
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
          .clickable { clipboard.setText(AnnotatedString(value)); copied = true },
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        value,
        color = Paper,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp, // Hero tier — the screen's peak (at most one per state, §4.0)
        modifier = Modifier.weight(1f, fill = false),
      )
      Spacer(Modifier.width(10.dp))
      Text(
        if (copied) "COPIED" else "⧉",
        color = Amber,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
      )
    }
  }
}

/** Read-only MODEL summary row: value + amber `›`, tap invokes the caller's onClick (the shell routes it to the model selection destination). */
@Composable
private fun ModelSummaryRow(value: String, enabled: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled) { onClick() }
      .alpha(if (enabled) 1f else 0.5f).padding(vertical = 6.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text("MODEL", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp)
      Spacer(Modifier.width(16.dp))
      Text(
        value,
        color = Paper,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(10.dp))
      Text("›", color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
  }
}

/** The one state-appropriate primary action — full-width, last element, never more than one (§4.0). */
@Composable
private fun PrimaryButton(action: PrimaryAction, onClick: () -> Unit) {
  val label = when (action) {
    PrimaryAction.START -> "START"
    PrimaryAction.CANCEL -> "CANCEL"
    PrimaryAction.STOP -> "STOP"
  }
  if (action == PrimaryAction.START) {
    Button(
      onClick = onClick,
      shape = RoundedCornerShape(6.dp),
      colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Charcoal),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
    }
  } else {
    // CANCEL and STOP are both "stop the node" — StopRed's reserved meaning covers both (Q4/§7).
    OutlinedButton(
      onClick = onClick,
      shape = RoundedCornerShape(6.dp),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = StopRed),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
    }
  }
}

/**
 * The node's bearer key: masked by default (`••••…last-4`, Q2) with a `SHOW`/`HIDE` toggle, plus a
 * share action. Tapping the chip copies the FULL raw key regardless of mask state; "SHARE
 * CONNECTION" opens the system share sheet with the base URL + key.
 */
@Composable
private fun AccessKeyChip(apiKey: String, baseUrl: String) {
  val clipboard = LocalClipboardManager.current
  val ctx = LocalContext.current
  var copied by remember { mutableStateOf(false) }
  var revealed by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      delay(1500)
      copied = false
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("ACCESS KEY", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp)
      Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { revealed = !revealed }) {
        Text(
          if (revealed) "HIDE" else "SHOW",
          color = Amber,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
        )
      }
    }
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(6.dp))
          .border(BorderStroke(1.dp, Amber.copy(alpha = 0.5f)), RoundedCornerShape(6.dp))
          .clickable {
            clipboard.setText(AnnotatedString(apiKey))
            copied = true
          }
          .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          displayApiKey(apiKey, revealed),
          color = Paper,
          fontFamily = FontFamily.Monospace,
          fontSize = 13.sp,
          modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
          if (copied) "COPIED" else "TAP TO COPY",
          color = Amber,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 11.sp,
        )
      }
    }
    Box(
      modifier =
        Modifier.clip(RoundedCornerShape(6.dp))
          .clickable {
            val text = "Relais node\nBase URL: $baseUrl\nAPI key: $apiKey"
            val send =
              Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
              }
            ctx.startActivity(Intent.createChooser(send, "Share Relais connection"))
          }
          .padding(vertical = 4.dp)
    ) {
      Text("SHARE CONNECTION ›", color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
  }
}

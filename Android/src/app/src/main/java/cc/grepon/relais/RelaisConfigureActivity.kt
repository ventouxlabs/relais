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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.grepon.relais.chat.ContentReportsActivity
import cc.grepon.relais.nfc.NfcWriteActivity
import cc.grepon.relais.templates.PromptTemplateEditorActivity
import cc.grepon.relais.triage.TriageControlActivity
import kotlinx.coroutines.delay

private const val TAG = "RelaisConfigure"

/**
 * The node's setup/rare-controls surface (AUDIT.md §4.5, Q1): model picker, HF token, power
 * exemption, share/NFC toggles, prompt templates, notification triage. One deliberate tap away
 * from [RelaisControlActivity]'s MODEL row / `CONFIGURE ›` link; not exported, no launcher icon —
 * mirrors the existing [PromptTemplateEditorActivity] / [NfcWriteActivity] internal-screen pattern.
 * No START/STOP here: one place to operate the node, one place to set it up.
 */
class RelaisConfigureActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme(
        colorScheme =
          darkColorScheme(
            primary = Amber,
            onPrimary = Charcoal,
            background = Charcoal,
            onBackground = Paper,
            surface = Panel,
            onSurface = Paper,
            error = StopRed,
          )
      ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Charcoal) {
          ConfigureScreen(activity = this@RelaisConfigureActivity)
        }
      }
    }
  }
}

@Composable
private fun ConfigureScreen(activity: RelaisConfigureActivity) {
  val ctx = LocalContext.current

  // Liveness snapshot FIRST, engine flags after (the read-order rule in computeNodeState's KDoc).
  // `idle` is polled INTO Compose state here rather than read raw where it is used: a raw
  // `RelaisLivenessState.snapshot` read inside a derived `val` would not invalidate the composition.
  var idle by remember { mutableStateOf(RelaisLivenessState.snapshot.idleUnloaded) }
  var ready by remember { mutableStateOf(RelaisEngine.isReady) }
  var running by remember { mutableStateOf(RelaisConfig.shouldRun(ctx)) }
  val powerManager = remember { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }
  var batteryUnrestricted by remember {
    mutableStateOf(powerManager.isIgnoringBatteryOptimizations(ctx.packageName))
  }
  var autoStartEnabled by remember { mutableStateOf(RelaisConfig.autoStartEnabled(ctx)) }
  var idleTtl by remember { mutableStateOf(RelaisConfig.idleTtlMinutes(ctx)) }
  LaunchedEffect(Unit) {
    while (true) {
      val liveness = RelaisLivenessState.snapshot
      idle = liveness.idleUnloaded
      ready = RelaisEngine.isReady
      running = RelaisConfig.shouldRun(ctx)
      batteryUnrestricted = powerManager.isIgnoringBatteryOptimizations(ctx.packageName)
      delay(1000)
    }
  }
  // Same lockout rule as the home screen's MODEL row (P6): a mid-download model change could
  // resurrect a superseded path once the in-flight ensureModel() resolves. DELIBERATELY still locked
  // while idle-unloaded (feature-22): `onPickRef` below only persists the ref — nothing dispatches a
  // swap — and the request-driven reload pairs `cachedPathOrDefault` with the new configured id,
  // which loads the OLD weights under the NEW id with no error anywhere (the case
  // `ModelSwitch.applyManualId`'s KDoc describes). The dashboard's targeted swap is the mechanism
  // that works while idle; routing this pick through it is #337. Only the caption changes.
  val nodeBusy = running && !ready

  var modelId by remember { mutableStateOf(RelaisConfig.modelId(ctx)) }
  var modelRef by remember { mutableStateOf(RelaisConfig.modelRef(ctx)) }
  var showModelSheet by remember { mutableStateOf(false) }
  var modelNote by remember { mutableStateOf("") } // own confirmation channel — not shared with hfNote (P11)
  val modelDisplay = modelRef?.takeIf { it.modelId == modelId }?.displayName ?: modelId

  var hfToken by remember { mutableStateOf(RelaisConfig.hfToken(ctx) ?: "") }
  var hfRevealed by remember { mutableStateOf(false) }
  var hfNote by remember { mutableStateOf("") } // own confirmation channel (P11)

  var shareEnabled by remember { mutableStateOf(RelaisConfig.shareEnabled(ctx)) }
  val nfcAvailable = remember { NfcAdapter.getDefaultAdapter(ctx) != null }
  var nfcEnabled by remember { mutableStateOf(RelaisConfig.nfcEnabled(ctx)) }

  Column(
    modifier =
      Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)
        .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(20.dp), // 20dp between divider-separated sections (§4.0)
  ) {
    Row(
      modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { activity.finish() },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "‹ CONFIGURE",
        color = Amber,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 1.5.sp,
      )
    }

    Divider()

    // 1. MODEL — the home screen's ModelRow verbatim (value + amber ▸), unchanged sheet.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      SectionLabel("MODEL")
      ModelRow(value = modelDisplay, enabled = !nodeBusy) { showModelSheet = true }
      if (nodeBusy) {
        Text(
          if (idle) "model locked while engine released · switch from the dashboard" else "model locked while starting",
          color = Muted,
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
        )
      }
      if (modelNote.isNotEmpty()) {
        Text(modelNote, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
      }
    }

    Divider()

    // 2. HF TOKEN — masked field + SHOW toggle + its own save confirmation (P11).
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        SectionLabel("HF TOKEN")
        Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { hfRevealed = !hfRevealed }) {
          Text(
            if (hfRevealed) "HIDE" else "SHOW",
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
          )
        }
      }
      OutlinedTextField(
        value = hfToken,
        onValueChange = { hfToken = it; hfNote = "" },
        visualTransformation = if (hfRevealed) VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        "gated repos only · restart to apply",
        color = Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
      )
      OutlinedButton(
        onClick = {
          RelaisConfig.setHfToken(ctx, hfToken.trim().ifBlank { null })
          hfNote = "HF token saved. Restart to apply."
        },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
      ) {
        Text("SAVE HF TOKEN", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
      }
      if (hfNote.isNotEmpty()) {
        Text(hfNote, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
      }
    }

    Divider()

    // 3. POWER
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      SectionLabel("POWER")
      // Value only, no repeated "POWER" row label — SectionLabel above already names the section
      // (matches the MODEL section's ModelRow, which drops its own former "MODEL" label prefix).
      Text(
        if (batteryUnrestricted) "unrestricted" else "restricted",
        color = Paper,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
      )
      // The direct battery-exemption request needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which is
      // stripped from the Play (playsafe) build — hide the action there (POLICY_OPEN=false) rather
      // than surface a button that silently degrades to the generic settings list.
      if (BuildConfig.POLICY_OPEN && !batteryUnrestricted) {
        ActionLink("ALLOW UNRESTRICTED ›") { requestIgnoreBatteryOptimizations(ctx) }
      }
      ToggleRow("AUTO-START ON BOOT", autoStartEnabled) {
        val next = !autoStartEnabled
        RelaisConfig.setAutoStart(ctx, next)
        autoStartEnabled = next
      }
      // IDLE UNLOAD (#178, feature-22 PR-B task 3): the toggle owns disabling — writes the
      // IDLE_TTL_DISABLED_MINUTES sentinel directly, never a ladder rung. Turning it off first
      // remembers the current value so turning it back on restores it instead of resetting to the
      // default.
      ToggleRow("IDLE UNLOAD", idleTtl > 0) {
        if (idleTtl > 0) {
          RelaisConfig.setIdleTtlLastNonZeroMinutes(ctx, idleTtl)
          RelaisConfig.setIdleTtlMinutes(ctx, IDLE_TTL_DISABLED_MINUTES)
          idleTtl = IDLE_TTL_DISABLED_MINUTES
        } else {
          val restored = RelaisConfig.idleTtlLastNonZeroMinutes(ctx)
          RelaisConfig.setIdleTtlMinutes(ctx, restored)
          idleTtl = restored
        }
      }
      // Rendered only while enabled — disabling is the toggle's job, not the stepper's. Each tap
      // moves to the adjacent IDLE_TTL_LADDER rung (nextRung), never a fixed increment.
      if (idleTtl > 0) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text("IDLE AFTER", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp)
          Spacer(Modifier.weight(1f))
          Stepper("–") {
            val next = nextRung(idleTtl, IDLE_TTL_LADDER, up = false)
            if (next != idleTtl) {
              idleTtl = next
              RelaisConfig.setIdleTtlMinutes(ctx, next)
            }
          }
          Text(
            "$idleTtl min",
            color = Paper,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
          )
          Stepper("+") {
            val next = nextRung(idleTtl, IDLE_TTL_LADDER, up = true)
            if (next != idleTtl) {
              idleTtl = next
              RelaisConfig.setIdleTtlMinutes(ctx, next)
            }
          }
        }
      }
    }

    Divider()

    // 4. INTEGRATIONS — toggles as single tappable rows (P9), not paired readout + command-link.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      SectionLabel("INTEGRATIONS")
      ToggleRow("SHARE TARGET", shareEnabled) {
        val next = !shareEnabled
        RelaisConfig.setShareEnabled(ctx, next)
        shareEnabled = next
      }
      if (nfcAvailable) {
        ToggleRow("NFC WORKFLOWS", nfcEnabled) {
          val next = !nfcEnabled
          RelaisConfig.setNfcEnabled(ctx, next)
          nfcEnabled = next
        }
        if (nfcEnabled) {
          ActionLink("WRITE NFC TAG ›") { ctx.startActivity(Intent(ctx, NfcWriteActivity::class.java)) }
        }
      }
      ActionLink("PROMPT TEMPLATES ›") {
        ctx.startActivity(Intent(ctx, PromptTemplateEditorActivity::class.java))
      }
      // Deliberately NOT behind POLICY_OPEN: reviewing reported AI output is a Play requirement, so
      // the playsafe build is the one that needs it most (#258).
      ActionLink("REPORTED OUTPUT ›") {
        ctx.startActivity(Intent(ctx, ContentReportsActivity::class.java))
      }
      // Notification triage is stripped from the Play (playsafe) build — its listener service +
      // control activity are removed from that manifest (notification-access policy).
      if (BuildConfig.POLICY_OPEN) {
        ActionLink("NOTIFICATION TRIAGE ›") { ctx.startActivity(Intent(ctx, TriageControlActivity::class.java)) }
      }
    }

    if (showModelSheet) {
      RelaisModelSelectorSheet(
        currentModelId = modelId,
        // The saved token (not the editable field above): HF resolve and the later download both
        // authenticate with the persisted token, so a gated repo needs SAVE HF TOKEN first.
        hfToken = RelaisConfig.hfToken(ctx),
        onPickRef = { ref ->
          RelaisConfig.setModelRef(ctx, ref)
          modelRef = ref
          modelId = ref.modelId
          // Show the resolved file, not just the repo — an HF repo can hold several .litertlm
          // variants and the operator should see which one was chosen before Start.
          modelNote = "Selected ${ref.displayName} · ${ref.modelFile}. Restart to apply."
          showModelSheet = false
        },
        onPickManualId = { id ->
          // Entering a raw id is an explicit "resolve this via the allowlist" intent, so drop any
          // curated ref first — otherwise the pinned ref would keep overriding allowlist resolution.
          RelaisConfig.clearModelRef(ctx)
          RelaisConfig.setModelId(ctx, id)
          modelRef = null
          modelId = id
          modelNote = "Set model id $id. Restart to apply."
          showModelSheet = false
        },
        onDismiss = { showModelSheet = false },
      )
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(text, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp)
}

@Composable
private fun Divider() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** Amber tap affordance, e.g. "PROMPT TEMPLATES ›" — matches the control panel's "… ›" idiom. */
@Composable
private fun ActionLink(label: String, onClick: () -> Unit) {
  Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClick() }.padding(vertical = 4.dp)) {
    Text(label, color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
  }
}

/**
 * A single-row boolean toggle (P9): label left, `on`/`off` value right, the whole row tappable —
 * replaces the old paired readout-row + command-link idiom.
 */
@Composable
private fun ToggleRow(label: String, value: Boolean, onToggle: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onToggle() },
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Text(if (value) "on" else "off", color = Paper, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
  }
}

/**
 * Stepper +/- tap target for the IDLE AFTER row. Copied from
 * [cc.grepon.relais.triage.TriageControlActivity]'s private `Stepper` (each screen keeps its own
 * private row composables — see feature-22 PR-B task-3 brief) rather than shared or made public.
 */
@Composable
private fun Stepper(symbol: String, onClick: () -> Unit) {
  Box(
    Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 2.dp)
  ) {
    Text(symbol, color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
  }
}

/** Tappable model row: muted MODEL label, current selection, amber ▸ — opens the model selector. */
@Composable
private fun ModelRow(value: String, enabled: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled) { onClick() }
      .alpha(if (enabled) 1f else 0.5f).padding(vertical = 6.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
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
      Spacer(Modifier.width(10.dp)) // same value-to-glyph gap as the home screen's ModelSummaryRow
      Text("▸", color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
  }
}

/**
 * Opens the system "ignore battery optimizations" dialog so the always-on node survives
 * Doze/app-standby. Falls back to the battery-optimization settings list on OEMs that don't honor
 * the direct request action.
 */
@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(ctx: Context) {
  val direct =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
  runCatching { ctx.startActivity(direct) }
    .onFailure {
      runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        .onFailure { e -> Log.w(TAG, "Could not open battery-optimization settings: ${e.message}") }
    }
}

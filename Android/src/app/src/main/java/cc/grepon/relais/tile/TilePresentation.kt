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

package cc.grepon.relais.tile

import cc.grepon.relais.core.NodeState

/**
 * Tile-display value object + the pure [NodeState] → display mapping that the QS tile (#2) renders.
 *
 * [tileState] mirrors `android.service.quicksettings.Tile.STATE_*` by VALUE (UNAVAILABLE=0,
 * INACTIVE=1, ACTIVE=2) but is declared here as plain ints so this mapping stays a pure JVM unit
 * (no Android types) — under `isReturnDefaultValues=true` the framework constants would read 0 in a
 * unit test, which would silently break an assertion. [RelaisTileService] maps [TileState] →
 * `Tile.STATE_*` at the Android boundary; the boundary mapping is asserted equal to these values so
 * they can never drift.
 */
data class TilePresentation(val label: String, val subtitle: String?, val tileState: Int)

/** QS tile states, value-aligned with `android.service.quicksettings.Tile.STATE_*`. */
object TileState {
  const val UNAVAILABLE = 0
  const val INACTIVE = 1
  const val ACTIVE = 2
}

/**
 * Derives the tile display from the already-computed [NodeState] (the single source of truth —
 * [cc.grepon.relais.core.RelaisNodeController.state]). Deliberately a thin mapping over [NodeState]
 * rather than re-deriving from raw booleans: [cc.grepon.relais.core.computeNodeState] already owns
 * the state machine, so re-implementing it here (as the original plan's `computeTileStatus` sketch
 * did) would duplicate that logic and risk drift.
 *
 * - LIVE → ACTIVE "Relais · live" (engine resident, serving).
 * - HOT  → ACTIVE "Relais · hot" (resident but thermally throttling).
 * - STARTING → ACTIVE "Relais · starting…" (coming up — ACTIVE so the lit tile reads "I asked for
 *   this"; the subtitle disambiguates from LIVE).
 * - OFF → INACTIVE "Relais · off".
 * - ERROR → INACTIVE "Relais · error" (asked-to-run but last init failed; tap re-toggles, the
 *   control panel is where the operator diagnoses).
 * - IDLE → ACTIVE "Relais · idle" (running, engine released by idle-TTL — ACTIVE for the STARTING
 *   reason: the node IS up; the subtitle names the tap's action the way OFF's does).
 */
fun tilePresentation(state: NodeState): TilePresentation = when (state) {
  NodeState.LIVE -> TilePresentation("Relais · live", "engine resident", TileState.ACTIVE)
  NodeState.HOT -> TilePresentation("Relais · hot", "throttling — thermal", TileState.ACTIVE)
  NodeState.STARTING -> TilePresentation("Relais · starting…", "coming up", TileState.ACTIVE)
  NodeState.OFF -> TilePresentation("Relais · off", "tap to start node", TileState.INACTIVE)
  NodeState.ERROR -> TilePresentation("Relais · error", "init failed — tap to retry", TileState.INACTIVE)
  NodeState.IDLE -> TilePresentation("Relais · idle", "tap to warm", TileState.ACTIVE)
}

/**
 * What a single tile tap does. The tile is one-action, so the meaning is state-dependent. [WARM]
 * (feature-22) re-warms an idle-released engine in place via
 * [cc.grepon.relais.RelaisEngine.ensureInitializedInBackground] — NOT a [START], which is a full
 * service re-init that tears down and rebinds both listeners (killing in-flight LAN requests and
 * churning NSD) for a node whose listeners are already up.
 */
enum class TileAction { START, STOP, RUN_PROMPT, WARM }

/**
 * Pure decision for what a tile tap should do — device-safe and cold-start-safe by construction:
 *  - OFF / ERROR → START (bring the node up; a prompt is never run here — the engine isn't resident).
 *  - STARTING → STOP (a tap while coming up cancels the intent-to-run).
 *  - HOT → STOP (the device is thermally throttling; a tap relieves it — we NEVER add inference heat
 *    while hot, so a configured template is intentionally ignored in this state).
 *  - LIVE + a configured template + engine [ready] → RUN_PROMPT (the opt-in "query button": a tap on a
 *    live, configured tile runs the canned prompt and KEEPS the node up; stop it from the app/control
 *    panel or by clearing the template).
 *  - LIVE with no template (the default) → STOP (a plain start/stop toggle).
 *  - IDLE → WARM regardless of template or [ready] (the node is running; its engine was released by
 *    idle-TTL, so re-warm it behind the live listeners — never RUN_PROMPT, the engine is not
 *    resident; never START, that would bounce the listeners; never STOP, the operator asked for
 *    this node). IDLE + [ready]=true is reachable by a tear between the two reads and still WARMs —
 *    an idempotent no-op once the engine is in fact ready.
 *
 * RUN_PROMPT is returned ONLY when [ready] is true and the template is non-blank, so a tap can never
 * cold-start the engine, never fire a prompt on the same tap that stops the node, and never fire while
 * the node is merely STARTING (not yet resident).
 */
fun tileAction(state: NodeState, templateId: String?, ready: Boolean): TileAction = when (state) {
  NodeState.OFF, NodeState.ERROR -> TileAction.START
  NodeState.STARTING, NodeState.HOT -> TileAction.STOP
  NodeState.IDLE -> TileAction.WARM
  NodeState.LIVE -> if (ready && !templateId.isNullOrBlank()) TileAction.RUN_PROMPT else TileAction.STOP
}

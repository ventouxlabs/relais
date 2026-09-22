# Design System — Relais

## Product Context
- **What this is:** A headless, on-device LLM node. It runs a model on hardware you own and broadcasts an OpenAI-compatible API over the LAN.
- **Who it's for:** Builders running their own local/offline AI infrastructure.
- **Space/industry:** Local-first / self-hosted AI inference, developer infrastructure.
- **Project type:** Android app — a model browser tile ("Relais") + a node control tile ("Relais Node").

## Brand Thesis
"Your own relay station for AI." Relais is a relay: sovereign, local-first, always-on. Infrastructure you operate, not a cloud you rent. A node with a light on, transmitting on your network.

The one thing to remember: an amber relay light on a black panel.

## Aesthetic Direction
- **Direction:** Industrial signal-panel (rack-equipment status light meets ham-radio beacon, refined).
- **Decoration level:** Minimal. Type, status, and one accent do the work.
- **Mood:** Powered-on, technical, sovereign. Reads as a live machine you control.

## Logo / Mark
- **Mark:** Amber broadcast beacon — a node dot with two concentric arcs radiating upward — on a charcoal field.
- **Files:** `app/src/main/res/drawable/relais_icon_foreground.xml` (amber glyph) over a charcoal adaptive-icon background in `mipmap-anydpi-v26/ic_launcher.xml`. Shared by both launcher tiles.
- **Wordmark:** `RELAIS`, uppercase, monospace, bold, letter-spacing ~5sp, amber.

## Color
- **Approach:** Restrained — one loud accent on near-black.
- **Accent (signal amber):** `#FFB000` — live/transmitting. The only bright color; used for the wordmark, the live status, the primary action, and the mark.
- **Background (charcoal):** `#0B0B0D`
- **Surface / panel:** `#16171A`
- **Hairline / divider:** `#2A2B30`
- **Text (paper):** `#EDEAE3`
- **Muted text / labels:** `#8A8780`
- **Stop / destructive:** `#FF5247` (used only for Stop)
- **Status mapping:** LIVE = full amber + pulsing dot; STARTING = amber 60%, static dot, with a
  phase line (resolve / download+progress / engine load); IDLE = amber 60%, static dot, copy
  "idle · engine released — wakes on the next request", no phase line, no hero row (the hero LAN
  endpoint stays LIVE-only); OFFLINE = muted. THERMAL SHED = a LIVE
  sub-state, not a fourth status: dot stays pulsing amber; the detail line reads
  `thermal · shedding load` in Paper (salience by brightness — no new color). StopRed covers STOP
  and CANCEL-start (both are "stop the node").
- **Mode:** Dark only. This is infra tooling; a light theme is out of scope.

## Typography
- **Wordmark / labels / readouts / buttons:** Monospace (`FontFamily.Monospace`, bundled — no font download). The machine voice; also keeps endpoints/keys/model ids aligned.
- **Body / prose:** same monospace for now (control-panel consistency). If a prose surface is added later, pair with a clean grotesk for body. **Chat assistant prose** is that surface (2026-07-11): assistant markdown renders in the platform `FontFamily.SansSerif` (readable, zero-bundle); user turns, code blocks, readouts, and all other chrome stay monospace.
- **Scale (control screen):** display 22sp (wordmark) / hero 17sp (the state's single most-copied
  value — LIVE LAN endpoint only) / status 13sp / value 13sp / label 11sp @ +1.5sp tracking /
  caption 11sp / action link 12sp bold / button 14sp bold @ +2sp tracking. At most ONE hero element
  per screen state.

## Spacing & Layout
- **Density:** Comfortable. Readouts as label-left / value-right rows separated by hairline dividers.
- **Insets:** Always apply `systemBarsPadding()` — content must clear the status/nav bars.
- **Border radius:** Buttons 6dp; text fields use M3 default. Keep it crisp, not bubbly.
- **Rhythm:** 4dp base grid; 20dp between divider-separated sections; 10dp between rows within a
  section. **Primary action:** full-width, one per screen state, last in the layout; the home
  screen must fit a 6.1" display without scrolling.

## Motion
- **Approach:** Minimal-functional, one signature.
- **Signature:** The status dot pulses amber (alpha 0.3→1.0, ~900ms, reverse) only while the node is LIVE. A beacon heartbeat. Nothing else animates.
- State changes crossfade dot/status color over ~300ms; a determinate model download may show a
  2dp amber hairline progress bar under the phase line. Nothing else moves.

## Application
- **App label:** "Relais" (was "Edge Gallery"). **Single launcher icon** — the former separate
  "Relais Node" launcher was retired when the node folded into the unified shell (2026-07-11).
- **Icon:** the single launcher tile carries the amber-beacon mark.
- **Shell:** one Compose `NavHost` hosted by `MainActivity`; a DESIGN.md-styled bottom nav switches
  the three top-level destinations DASHBOARD / CHAT / MODELS. `RelaisControlActivity` is now only
  the key-gated adb trampoline (no UI, no launcher).
- **Control screen:** the node **dashboard** destination (`DashboardScreen`) — the canonical
  expression of this system.
- **Surfaces:** the control screen shows status, connection, model summary, and the single
  state-appropriate action (START / CANCEL / STOP — never more than one). Setup-time and rare
  controls live on the CONFIGURE screen (`RelaisConfigureActivity`), one tap away, in the same
  panel styling.
- Every copyable value (endpoints, access key) carries an explicit trailing copy affordance
  (`⧉` / TAP TO COPY) that acknowledges with `COPIED` for ~1.5s. Toggles render as single
  label/value rows, tappable across the row — never as paired readout + command-link rows.

## Decisions Log
| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-05-25 | Initial brand identity: amber signal-relay on near-black, monospace, broadcast-beacon mark | Created via /design-consultation. Amber-on-black breaks from the blue/purple AI-SaaS norm and says "live, transmitting, sovereign" — the relay thesis. Verified on-device (Pixel 9 Pro Fold). |
| 2026-07-07 | Control-panel buttonology pass: frequency-ranked layout, single state-appropriate primary action, Configure split, hero endpoint on LIVE, phase-line STARTING, thermal sub-state | AUDIT.md redesign audit |
| 2026-07-11 | Unified app shell: single launcher, one Compose NavHost hosted by MainActivity, node dashboard as home; new DESIGN.md-conformant bottom nav (DASHBOARD/CHAT/MODELS, charcoal Panel surface, Line hairline divider, amber-active monospace labels, no ripple/elevation); Configure + Benchmark reachable off the shell | docs/superpowers/specs/2026-07-11-unified-app-shell-design.md |
| 2026-07-26 | In-app speech playback: assistant turns gain a third action label, `SPEAK`, alongside `COPY`/`REGEN` — same 11sp bold monospace amber treatment, no new colour, no new motion. The label doubles as the readout for its own state (`SYNTHESIZING` / `STOP` / `FETCHING VOICE`, and `SPEECH FAILED` in StopRed, self-clearing like the `COPIED` ack), so playback needs no spinner, no progress bar, and no second control. Hidden entirely when no TTS engine is registered or the turn is an `[error]` turn | Issue #211 (split from the #168 TTS epic) |
| 2026-07-11 | Chat depth: persistent Room-backed conversations, hybrid HTTP/in-process transport, generation controls (stop/regenerate/edit/copy), reused compose-richtext markdown, in-chat model switch with reload state, Markdown share/export. Assistant prose renders in platform `FontFamily.SansSerif` (the prose pairing anticipated in §Typography); monospace elsewhere | docs/superpowers/specs/2026-07-11-chat-depth-design.md |
| 2026-09-22 | IDLE status on every surface (feature-22); tile/widget warm an idle node; Configure POWER gains IDLE UNLOAD / IDLE AFTER | feature-22 idle-unload plan |

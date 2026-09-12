# Dashboard Copy & Visual Direction — `GET /`

Spec for the auth-gated, scriptless, server-rendered web dashboard (feature-09). This is the
source of truth for **every user-visible string** on the page and for how the fixed `DESIGN.md`
tokens apply to this surface. The implementer copies strings **verbatim** (including case and
punctuation) into `RelaisDashboard.kt`.

Constraints recap (from the plan): one static HTML page, no JavaScript, bearer-gated, rendered
from `DashboardStatus`. The only interactive element is the model-selector `<form method="POST"
action="/select-model">`. Do not add features beyond this spec.

---

## 0. Case register — the one decision

- **CAPS** = structure: wordmark, section headings, readout labels, table column headers,
  buttons/links, and state values (`LIVE` / `STARTING` / `OFFLINE`, thermal levels).
- **lowercase** = commentary: captions, hints, empty states, error lines. No terminal period on
  single-line strings; join facts with `·`, attach a consequence with `—`.
- **as-emitted** = data values: model ids, endpoints, numbers, units. Never re-cased.

(The Android control screen mixes sentence-case hints; the web surface standardizes on lowercase
commentary — its precedent is the control screen's own tagline `on-device relay ·
OpenAI-compatible LAN endpoint`.)

---

## 1. Microcopy — every string on the page

### 1.1 Page chrome & header

| Element | Literal string | Notes |
|---|---|---|
| `<title>` | `RELAIS — node status` | wordmark caps + lowercase descriptor; em dash, not hyphen |
| Wordmark | `RELAIS` | 22px, bold, letter-spacing 5px, amber `#FFB000`, preceded by the beacon dot (a styled `<span>`, **not** a text bullet — see §2.1) |
| Header status (right-aligned) | `LIVE` / `STARTING` / `OFFLINE` | 13px bold, letter-spacing 2px; color per state (§2.1) |
| Tagline (caption under header) | `on-device relay · OpenAI-compatible LAN endpoint` | 11px muted; identical to the control screen |

### 1.2 Node status labels

Node state (exhaustive, from `assembleDashboardStatus`):

| State | Literal |
|---|---|
| engine ready | `LIVE` |
| starting up | `STARTING` |
| neither | `OFFLINE` |

Thermal labels (Android thermal status 0..6; **confirmed** — these are the exact words, already
implemented in `thermalLabel()`; keep them):

| Level | Literal |
|---|---|
| 0 | `NONE` |
| 1 | `LIGHT` |
| 2 | `MODERATE` |
| 3 | `SEVERE` |
| 4 | `CRITICAL` |
| 5 | `EMERGENCY` |
| 6 | `SHUTDOWN` |
| out of range | `UNKNOWN` |

No degree symbols, no raw integers, no `°C` — the label **is** the reading.

### 1.3 Readout rows — section `NODE STATUS`

Section heading literal: `NODE STATUS` (11px, caps, letter-spacing 2px, muted).

Rows are label-left / value-right on hairline dividers. Labels verbatim:

| Label (left, muted caps) | Value (right, paper) — format | Example |
|---|---|---|
| `STATUS` | state literal, preceded by the beacon dot | `● LIVE` |
| `THERMAL` | thermal literal | `NONE` |
| `DECODE` | one decimal + unit: `%.1f tok/s`; no sample yet → `—` | `11.4 tok/s` |
| `UPTIME` | `<h>h <m>m <s>s`, omit leading zero units, no padding | `2h 5m 30s` · `12m 3s` · `47s` |
| `QUEUE` | plain integer | `0` |
| `ERRORS` | plain integer (cumulative) | `3` |
| `SHED` | plain integer (cumulative) | `0` |

- `—` (em dash) is the universal no-data value. Never `N/A`, never `0.00` as fake precision.
- Counters stay **paper at any value** — no red, no amber (see §2.5).
- Reserved (not in v1, for consistency if ever surfaced): `MEMORY` → integer + `MiB`, e.g. `812 MiB`.

### 1.4 Model section — `MODEL`

Section heading literal: `MODEL` (same heading treatment).

| Element | Literal string | Notes |
|---|---|---|
| Current-model row label | `SERVING` | value = current model id, verbatim, HTML-escaped, e.g. `litert-community/gemma-4-E4B-it-litert-lm` |
| Selector `<label for="model">` | `SWITCH MODEL` | muted caps, same as row labels |
| `<select name="model" id="model">` options | the provisioned model ids ∪ the configured id, minus any the runtime-compat table rejects, verbatim, **sorted by id** | current id gets `selected`; option text = the id itself, nothing prettified. **Not catalog order:** the only catalog-order source is blocking and network-backed behind a 5-minute TTL, and a page that auto-refreshes every 10s must not depend on a fetch or stall on a cold cache offline |
| Submit button | `SET MODEL` | caps, bold, letter-spacing 2px — amber primary (§2.4) |
| Pending hint (configured id ≠ resident id) | `model set: <id> — not serving it yet` | 11px muted, under the form; `<id>` HTML-escaped. **Not "restart to apply"** — the swap is in-process and needs no restart. **And not "— swapping"**: this state also arises from a swap that already bailed (target file missing) or rolled back (engine-create failed), and the page carries no swap-liveness signal to tell those apart, so the string states the fact and predicts nothing |
| Locked state (node `STARTING`) | `model locked while starting` | 11px muted; render the `<select>` + button `disabled` (§2.4) — same race guard as the control screen |
| No models to offer | *(the whole form is omitted)* | An empty `<select>` beside a live `SET MODEL` reads as broken rather than as "nothing else provisioned" |
| `400` unknown id page | `unknown model id — no change applied` | Own page, dashboard palette, with a `‹ BACK` link. Nothing is persisted |
| `400` incompatible page | `<id> cannot be loaded — <reason>` | `<reason>` comes from the runtime-compat table verbatim; both halves HTML-escaped. Nothing is persisted |
| `503` swap-busy page | `a model swap is already running — retry shortly` | Carries `Retry-After: 25`, matching the request path's answer for the same state. Nothing is persisted |

### 1.5 Recent requests — `RECENT REQUESTS`

Section heading literal: `RECENT REQUESTS`.

Column headers (11px, caps, muted — same treatment as section headings, letter-spacing 1px):

| Column | Literal | Value format |
|---|---|---|
| 1 | `ENDPOINT` | normalized endpoint label, verbatim, e.g. `/v1/chat/completions` — never a raw path, IP, or key |
| 2 | `STATUS` | integer HTTP status, e.g. `200` |
| 3 | `AGE` | single coarsest unit, right-aligned: `<n>s` / `<n>m` / `<n>h` — **no** `ago` (the header already says AGE) |

Empty state (single row spanning all columns, centered, muted):

```
no requests yet
```

### 1.6 Error responses

Rendered in the same page skeleton (wordmark header + one panel) when HTML is practical; the
plain-text fallback is the one-liner alone.

**Rejected model change — `400` on `POST /select-model`:**

| Element | Literal |
|---|---|
| `<title>` | `RELAIS — error` |
| Heading row | `400` (label) · `BAD REQUEST` (value) |
| Message line | `unknown model id — no change applied` |
| Back link | `‹ BACK` (amber, caps, links to `/`) |
| Plain-text fallback body | `400 unknown model id — no change applied` |

**Unauthorized — `401` (if ever surfaced as a page/text rather than bare JSON):**

| Element | Literal |
|---|---|
| Heading row | `401` (label) · `UNAUTHORIZED` (value) |
| Message line | `bearer key required — authenticate with the node api key` |
| Plain-text fallback body | `401 bearer key required` |

Never echo the submitted model id, any header, or any key material in an error body.

### 1.7 Client-config panel (already shipped in the read-only draft — keep, normalized)

Section heading literal: `CLIENT CONFIG`.

| Label | Value |
|---|---|
| `BASE URL` | e.g. `https://192.168.1.42:8443/v1`, verbatim |
| `API KEY` | masked form only (`abcd…wxyz`), muted — the raw key is never rendered |
| `CAPABILITIES` | comma-joined names as emitted, e.g. `tools,reasoning` |
| Caption row | `GET /v1/clientconfig (with your bearer key) for paste-ready Open WebUI / Continue.dev / Aider configs.` |

---

## 2. Visual direction — applying DESIGN.md to this page

Tokens are fixed; use exactly these and nothing else:
bg `#0B0B0D` · surface `#16171A` · hairline `#2A2B30` · paper `#EDEAE3` · muted `#8A8780` ·
amber `#FFB000` · stop `#FF5247` (see §2.5). `font-family: monospace` everywhere. Dark only —
no light theme, no `prefers-color-scheme` handling.

### 2.1 Header, beacon dot, and the LIVE-only pulse

- Header row: **beacon dot** (10–11px circle `<span>`, `border-radius: 50%`) + 8–10px gap +
  `RELAIS` wordmark, with the state literal right-aligned on the same line. Tagline on the line
  below, 11px muted. This mirrors the control screen exactly.
- Dot color by state: `LIVE` → `#FFB000` (full); `STARTING` → `rgba(255,176,0,0.6)` (amber 60%);
  `OFFLINE` → `#8A8780` (muted). The status text takes the same color as the dot.
- **Pulse — the only motion on the page.** Pure CSS, gated server-side to LIVE by conditionally
  emitting the class (no JS, keeps CSP `script-src`-free honest):

  ```css
  .dot-pulse { animation: pulse 900ms ease-in-out infinite alternate; }
  @keyframes pulse { from { opacity: 0.3; } to { opacity: 1.0; } }
  ```

  STARTING and OFFLINE dots are static. Nothing else animates — no transitions, no hover motion,
  no skeleton shimmer.
- The beacon *mark* (dot + radiating arcs) is not reproduced on this page in v1 — the pulsing dot
  alone carries the identity. Do not attempt the arcs in CSS.

### 2.2 Panels and readout rows

- Panels: surface `#16171A`, 1px border `#2A2B30`, 6px radius, 16px vertical gap between panels.
  Panel heading: 11px, uppercase, letter-spacing 2px, muted, ~10px 14px padding, bottom hairline.
- Rows: label-left (muted, 12px, letter-spacing 1px) / value-right (paper, 13px, right-aligned),
  separated by 1px `#2A2B30` hairlines; ~9–10px vertical / 14px horizontal cell padding.
  Comfortable density — do not compress below 9px vertical padding.
- Page: 24px top/bottom, 16px side padding; single column; `max-width: 640px` centered so LAN
  desktop views don't stretch rows across the screen.
- Long values (model ids, base URL): `overflow-wrap: anywhere` — wrap, don't clip, don't scroll
  horizontally.

### 2.3 State dimming

STARTING and OFFLINE dim only the **status signals** (dot + state text) per the mapping in §2.1.
The rest of the page keeps normal paper/muted contrast in every state — an offline panel is still
a readable panel. Do not gray out whole sections.

### 2.4 Model selector (the one form)

- `<select>`: full panel width, surface background, 1px hairline border, 6px radius, paper text,
  monospace, ~10px padding. `:focus-visible` → 1px amber outline. Native dropdown chrome is fine;
  do not fight the OS picker.
- Submit button `SET MODEL`: the **primary action** — amber `#FFB000` background, charcoal
  `#0B0B0D` text, bold, letter-spacing 2px, 6px radius, ~10px 18px padding. No hover effect
  beyond `cursor: pointer` (nothing else animates). `:focus-visible` → 1px amber outline offset.
- Disabled (node STARTING): `select` and button get `disabled` + `opacity: 0.5` (matches the
  control screen's disabled treatment) with the `model locked while starting` hint below.
- Hints under the form: 11px muted.
- Form semantics only — no JS validation; the server is the validator (400 on unknown id).

### 2.5 Color discipline (hard rules)

- **Amber `#FFB000` is reserved** for: the wordmark, the LIVE dot/state, the `SET MODEL` primary
  button, focus outlines, and the `‹ BACK` link. Nothing else is amber.
- **`#FF5247` appears only on a destructive control. There is none in v1, so it appears nowhere
  on this page.** Error counters, 4xx/5xx status codes, and 400/401 error pages are **not**
  destructive controls — they render paper/muted.
- Everything else is paper `#EDEAE3` (values) or muted `#8A8780` (labels, captions, hints, empty
  states). No third accent, no invented warn color (`#FFCC44` in the current draft is off-palette
  — remove it).
- Salience without color: in the request log, render status codes `>= 400` in paper and `< 400`
  in muted — errors stand out by contrast, not by hue.

### 2.6 Request-log table

- Monospace `<table>`, three columns per §1.5; headers muted caps 11px; rows hairline-separated
  like readout rows. `ENDPOINT` left-aligned paper; `STATUS` right-aligned (paper/muted per
  §2.5); `AGE` right-aligned muted.
- Bounded at ~20 rows by the ring buffer — no pagination, no scroll region, no "show more".

### 2.7 Scriptless constraint

The page contains **zero** `<script>` tags, zero inline handlers, zero external assets (styles in
one inline `<style>` block — allowed by the planned CSP `style-src 'unsafe-inline'`). The pulse
is pure CSS (§2.1); the form is plain HTML POST. Any future interactivity proposal must survive
CSP `default-src 'none'` with no `script-src` before it gets copy.

---

## 3. Copy-tone cheat-sheet

Rules for any future string on this surface:

1. **CAPS are structure, lowercase is commentary.** Labels, headings, buttons, states → CAPS.
   Hints, empty states, errors → lowercase, no terminal period.
2. **Real units, real values.** `tok/s`, `s`/`m`/`h`, `MiB`. No-data is `—` — never `N/A`,
   never zero-padded fake precision.
3. **A panel reports; it doesn't chat.** No filler ("please", "oops", "successfully"), no hype,
   no emoji, no exclamation marks. State the fact and, if needed, the operator's next move.
4. **One line per message.** Join facts with `·`, attach the consequence with `—`
   (`model set: <id> — restart to apply`).
5. **Never render secrets or identifying data.** Keys masked or absent; no IPs, raw paths, or
   header contents in logs or errors.

---

## Appendix — deltas from the shipped read-only draft (`RelaisDashboard.kt`)

For the implementer extending the existing render; normalize these while adding the selector:

| Current draft | This spec |
|---|---|
| `<title>RELAIS — Node Status</title>` | `RELAIS — node status` |
| Wordmark line `&#x25CF; RELAIS` (text bullet) | styled dot `<span>` + `RELAIS`; state right-aligned on the header row |
| Row labels lowercase (`status`, `queue depth`, `errors total`, `shed total`) | CAPS, shortened: `STATUS`, `QUEUE`, `ERRORS`, `SHED` (matches the control screen's `Readout()` register) |
| `%.2f tok/s` | `%.1f tok/s` |
| Log age `42s ago` | `42s` under an `AGE` header |
| Empty state `no recent requests` | `no requests yet` |
| `.warn { color: #FFCC44 }` for 4xx/shed | delete — off-palette; use paper-vs-muted contrast (§2.5) |
| `.stop` red on 5xx rows and `errors total > 0` | delete — `#FF5247` is destructive-controls-only; render paper |
| No tagline | add `on-device relay · OpenAI-compatible LAN endpoint` |

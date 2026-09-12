# Relais — Operations Runbook

Operating a **running** Relais node. Build & config → [`../DEVELOPMENT.md`](../DEVELOPMENT.md).
Architecture → [`CODEMAPS/`](CODEMAPS/). Per-endpoint API references → the `*-api.md` files in this folder.

## Deploy / install
1. Pick a channel/variant (see the flavor table in `DEVELOPMENT.md`). Sideload the APK or
   `./gradlew :app:install<Variant>Debug`.
2. Provision a model — default `gemma-4-E4B-it`; **Tensor G5 must use E2B** (see Known issues). Either
   download in-app, or stage files directly into the app's external files dir:
   `/storage/emulated/0/Android/data/<appId>/files/…` (the provisioner adopts a staged model on start).
3. Set the API key + start the node (control ABI below, or the Quick Settings tile / control activity).

## Control ABI (headless start/stop)
```
adb -s <serial> shell am start -n <appId>/cc.grepon.relais.RelaisControlActivity \
  --es cmd start --es token <api-key>
```
- `<appId>` follows the channel: `com.ventouxlabs.relais` (Play) / `.izzy` (IzzyOnDroid) / `.degoogled` (GrapheneOS/GitHub). The activity class FQN stays in the `cc.grepon.relais` namespace.
- Stop: `--es cmd start` → `--es cmd stop`. Optional `--es modelId <allowlistId>` to switch model.
- The API key lives in `EncryptedSharedPreferences` — read it off the control-panel "ACCESS KEY" field.

## Health & monitoring
- `GET /health` (no auth) → `{status, ready, thermal_state}`.
- `GET /metrics` → Prometheus text (or JSON via `Accept: application/json`). Dashboards/alerts:
  [`relais-grafana-dashboard.json`](relais-grafana-dashboard.json), [`relais-alerts.md`](relais-alerts.md).
- Reach a node: `adb -s <serial> forward tcp:8443 tcp:8443` → `curl -k https://localhost:8443/health`.
- LAN discovery: mDNS `_relais._tcp`; HTTPS `0.0.0.0:8443` (bearer), loopback HTTP `127.0.0.1:8080`.

### Reading time-to-first-token (and why there are two series)

On an on-device node prefill usually dominates the wait, so end-to-end latency alone can't tell you
whether a slow request was slow to *start* or slow to *finish*. Two histograms share an endpoint —
the first visible token — and differ only in where the clock starts:

| Series | Clock starts at | Answers |
|---|---|---|
| `relais_time_to_first_token_seconds` | conversation creation | "how long before the node said anything" — the user-visible wait |
| `relais_decode_start_latency_seconds` | `sendMessageAsync` | decode start only, with conversation setup excluded |

**Their difference is the prompt/history prefill time, and that is the number to watch when tuning
system prompts.** Take it from the means, not by subtracting quantiles (a difference of p95s is not
the p95 of the difference):

```bash
curl -ks -H "Authorization: Bearer <key>" https://<node>:8443/metrics \
  | grep -E 'relais_(time_to_first_token|decode_start_latency)_seconds_(sum|count)'
# prefill ≈ ttft_sum/ttft_count − decode_start_sum/decode_start_count
```

A large gap means a long system prompt is being re-prefilled on every request — shorten it, or move
the stable part where it can be reused. A gap near zero means prefill is lazy and the two series
carry the same information.

Three things that surprise operators:

- **TTFT excludes queue wait and thermal cool-down** — both happen before the conversation exists.
  A request that waited behind another shows a normal TTFT and a large
  `relais_inference_duration_seconds`. Compare the two to see queueing; do not read TTFT as request
  latency.
- **Some requests contribute no sample at all, and that is correct.** Tool-calling and AICore run no
  per-token callback, and a streamed request whose client disconnected or was thermally truncated
  before the first token reached the socket delivered nothing. These are omitted rather than
  recorded as zero, so `_count` is legitimately lower than your request count.
- **`x_relais_ttft_ms`** on a response body is the same measurement per-request, and is absent — not
  zero — on those same paths.

### Opening the dashboard in a browser

`GET /` serves a status page. Browsers never send `Authorization: Bearer` on a navigation, so the
node also accepts **HTTP Basic**:

1. Browse to `https://<phone-ip>:8443/`.
2. Accept the self-signed-certificate interstitial — it appears **before** the auth prompt. (Install
   the node CA from `GET /ca.crt` to stop seeing it; see `SECURITY.md`.)
3. At the Basic prompt, leave **username blank** and paste the **node key** as the password.

The page auto-refreshes every 10 s. Each refresh spends one of the 30 req / 60 s per-IP budget, so an
idle tab costs ~20% of it — and if the budget is exhausted the refresh answers JSON, which carries no
refresh tag, so **the page stops refreshing until you reload it by hand**. Close idle dashboard tabs
on a node that is also serving inference.

### `403` on a scripted POST is expected, not a fault

Accepting Basic means the browser re-attaches credentials to *any* page's request, so every
**Basic**-authenticated request passes a cross-site check. A plain command-line POST has no
provenance and is rejected:

```bash
curl -sk -u ":$KEY" -X POST https://$IP:8443/...              # 403 Forbidden (permission_error)
curl -sk -u ":$KEY" -H 'Sec-Fetch-Site: same-origin' -X POST https://$IP:8443/...   # works
curl -sk -u ":$KEY" -H "Origin: https://$IP:8443" -X POST https://$IP:8443/...      # also works
```

`Bearer` is unaffected — scripts and SDKs that send `Authorization: Bearer` need no change, and that
remains the recommended carrier for automation. GET requests are unaffected under either scheme.

A `403` here means the **key was accepted** and the request context was refused; the envelope says
`permission_error`, not `authentication_error`. Retrying with a new key will not help.

**Also wire-visible:** `Authorization: <rawkey>` with no scheme used to be accepted and is now
rejected. Add the `Bearer ` prefix. The scheme token is case-sensitive.

## Common issues
| Symptom | Cause | Action |
|---|---|---|
| First-inference SIGSEGV on **Tensor G5** with E4B | upstream LiteRT-LM #2566 (G5-specific) | Pin **E2B** on G5 (the default is already gated); don't re-file |
| `503` + `Retry-After` | thermal shed or model still provisioning | Back off; let the device cool; check `/health` `ready` |
| `429` + `Retry-After` | admission queue full (cap 16) | Retry after the header delay |
| `POST /v1/images/generations` → `501` | image-gen backend unregistered (#16) | Expected on `degoogled`; full builds register image-gen. G3/G4 use Vulkan (~5 min cold); G5 uses the safe CPU fallback (~279 s) because PowerVR Vulkan still deadlocks (#69). |
| `401 unauthorized` | missing/wrong bearer token | Pass the node's API key (constant-time compared) |
| Node won't start after a dev branch switch | stale Hilt codegen | `./gradlew :app:clean` |
| CI build fails on GMS / permission gate | `degoogled` dexed a GMS class, or `playsafe` kept a restricted perm | Re-check the offending flavor's deps / `src/playsafe/AndroidManifest.xml` |

## Rollback
- **App:** sideload the prior APK for the **same channel** (same `applicationId` = in-place update).
  Different channels have different `applicationId`s and install side-by-side.
- **Database:** Room `relais.db` is at **v7** with **additive, non-destructive** migrations and **no
  down-migrations** — to roll back to an older schema you must uninstall (clears staged models too).

## Escalation
- Native inference crashes/hangs → upstream **LiteRT-LM** issues (G5 E4B = #2566, open).
- Production node = a separate device from the destructive-test spares; do destructive/model-swap work on a spare.

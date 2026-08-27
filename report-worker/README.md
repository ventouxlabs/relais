<!--
  Copyright (C) 2026 Entrevoix / grepon.cc — AGPL-3.0-or-later.
  Deploy runbook for the Relais AI-content report receiver (#258).
-->

# Relais report receiver

Receives **opt-in** AI-content reports from Relais and stores them for the maintainer to review.
This exists to satisfy the *"to developers"* half of Play's
[AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936)
— see [`docs/store-submission.md`](../docs/store-submission.md) gate 1 for why a local-only record
does not.

**Nothing sends here by default.** The app records reports on-device; transmission is a per-report
action the operator chooses.

**Default-off does NOT keep the Data Safety answer at "collects nothing".** An earlier version of
this file claimed it did. Wrong: a report arriving here is transmitted off-device to the
**first-party developer**, which Google counts as collection. Default-off makes that collection
*optional*, not absent — Play's user-initiated carve-out covers **sharing to third parties**, which
is not what this is. Once the client send path ships, the form answers **Yes** with the report
contents declared as optional collection for app functionality. Full wording in
[`docs/store-submission.md`](../docs/store-submission.md) gate 1.

The default still matters — optional collection is a materially better posture than mandatory, and
it is what lets everything else on the form stay "not collected". It just is not the same as
collecting nothing.

## What it stores, and what it does not

| | |
|---|---|
| **Stored** | under the key `report:<receivedAt>:<uuid>`: reason id, the reported excerpt, the operator's optional note, the model id and backend that produced it, which surface it came from, and a server timestamp. `reasonId` and `surface` are allowlisted; `modelId` and `backend` are only length-bounded, so a hostile caller can put arbitrary text in those two |
| **Retained on a sliding one-hour window** | a second KV entry, `rl:<hash>`, where the **key** is a **salted SHA-256 of the caller's IP** and the **value** counts that caller's requests which got past the limiter — not accepted reports: it increments *before* parsing, so malformed and oversized bodies count too, and it is only written when `cf-connecting-ip` is non-empty (`overRateLimit`, `src/index.ts`). The **raw IP is never stored** and the hash is not reversible — but do not over-read that: Play counts a stable identifier retained off-device as **collection**, so it must be declared (Device or other IDs, optional, fraud-prevention purpose). See `docs/store-submission.md` gate 1 |
| **Not stored** | the raw IP, and anything not listed above |
| **Retention** | reports: 180 days from receipt, then dropped by KV TTL. Identifier: one hour from that caller's last **counted** request — `put()` re-sets `expirationTtl` each time it runs, and it does not run once the caller is over the limit (`overRateLimit` returns first), so sustained *under-limit* traffic keeps one alive indefinitely. A renewing TTL, **not** a one-hour retention cap; do not shorten it to "retained one hour" on a privacy form |

Every row above is load-bearing for the privacy policy, and "anything not listed above" is a
completeness claim — derive it from the `put()` calls in `src/index.ts`, both of them, key *and*
value. If you change what is stored, update
`docs/privacy-policy.md`, its `.html` twin, and `docs/distribution.md` §"Play Data Safety form" in
the same change.

## Threat model

Relais is AGPL and its source is public, so **any credential shipped in the APK is public too**.
There is no useful client authentication: this endpoint is effectively open. The defenses are
therefore structural, not secret-based — a hard body cap enforced before parsing, an allowlist schema
that drops unknown fields, per-IP rate limiting, and fixed response strings that never echo input.

Read the header comment in `src/index.ts` before changing any of that.

## Deploy

**Prerequisites:** a Cloudflare account with Workers and KV, and **Node 22 or newer** — the pinned
`wrangler` 4.120.0 declares `engines: node >=22.0.0`, so on Node 20 you install a CLI that will not
reliably run the commands below. `package.json` carries the same constraint, so `npm install` warns
you rather than letting it fail later at an unhelpful place.

Run from this directory.

```bash
npm install
npm run typecheck && npm test        # both must pass before deploying

npx wrangler login

# 1. Create the KV namespace.
#    The `[[kv_namespaces]]` block in wrangler.toml is COMMENTED OUT so this command can run —
#    wrangler rejects an empty binding id while parsing the config, before running anything, which
#    would otherwise make this step fail on the config it is meant to fill in.
npx wrangler kv namespace create REPORTS

# 1b. Uncomment the `[[kv_namespaces]]` block in wrangler.toml and paste the printed id.
#     Do NOT commit the id — it would point every fork's deploy at your storage.

# 2. Set the rate-limit salt. Any long random string; rotating it only resets in-flight windows.
openssl rand -hex 32 | npx wrangler secret put RATE_LIMIT_SALT

# 3. Deploy.
npm run deploy
```

Then map a route (`report.ventouxlabs.com/report`, or a path on an existing zone) in the Cloudflare
dashboard, and **add a Rate Limiting rule at the edge as well** — the in-Worker limiter is a counter
on a renewing TTL (the window only resets after an hour with no counted request) and is deliberately
not exact under concurrency; the edge rule is what absorbs a real flood before it reaches Worker
invocations.

**Also turn on *Always Use HTTPS* for the zone, and confirm it.** The Data Safety form answers
"encrypted in transit: yes" for this leg. The Worker enforces this itself — `index.ts` refuses any
request whose edge scheme markers don't all say https (`403 https required`, keyed off
`x-forwarded-proto` / `cf-visitor`, with `cf-ray` as the through-the-edge backstop when neither
marker arrives) — because the zone toggle is dashboard state this repo cannot pin, and it was
observed off: before the guard, a full report **POSTed over plain http was accepted and stored**
(202, `visit_scheme=http`, `tls=off` per `/cdn-cgi/trace`), not merely probed. The toggle is still
worth flipping: it redirects before any bytes reach the Worker, and it covers the rest of the zone.

Be precise about what the guard buys: it means **no report is accepted or stored over plaintext**.
It cannot un-send bytes — a non-app caller who POSTs in the clear has already put the body on the
wire by the time the 403 comes back. The app itself never does: `ContentReportDelivery` hard-codes
`https://` with redirects disabled, so the app's own sends are encrypted by construction and the
guard is the backstop for curl users, forks, and misconfiguration.

**Required after every deploy, both curls** — this is the only check that observes which headers
the real edge sends, which no local run can:

```bash
curl -sI http://report.ventouxlabs.com/report                                # expect 403
curl -sI -H 'x-forwarded-proto: https' http://report.ventouxlabs.com/report  # expect 403
```

The first proves the deployed build has the guard at all (a 405 means a pre-guard build — the one
that accepted and stored plaintext POSTs). The second is the spoof probe: it passes only if the
edge overwrites a client-supplied `x-forwarded-proto`, which is documented but observed nowhere in
this repo — a 405/404 there means the bypass is live, and the fix is to stop trusting
`x-forwarded-proto` and key the guard on `cf-visitor` + `cf-ray` alone. Record what both return in
the deploy notes. **Allow a minute for propagation before concluding anything**: observed on the
2026-08-17 deploy, a plaintext POST ~30 s after `wrangler deploy` still hit the old build (202,
stored — cleaned from KV afterwards) while GETs in the same seconds already saw the new one; a
minute later every probe refused. A mixed result right after deploying means wait and re-run, and
any 202 that slipped through means a junk `report:` key to delete.

## Verify locally first — no Cloudflare account needed

**Do this before deploying.** It runs the real Worker under the real runtime (`workerd`) with local
KV, so it catches things no unit test can. It is how the "Incorrect type for map entry" bug was
found: the Worker could not start *at all*, while this repo's suite was green and
`wrangler deploy --dry-run` passed. Neither boots the runtime — `--dry-run` only bundles, and vitest
imports the module into Node, where a value export is just a value.

```bash
cd report-worker && npm ci

# The committed wrangler.toml ships the KV block commented out (see the note there), so make a
# local-only config that binds it. Both files are gitignored.
sed 's/^# \[\[kv_namespaces\]\]/[[kv_namespaces]]/; s/^# binding = "REPORTS"/binding = "REPORTS"/; s/^# id = .*/id = "local"/' \
  wrangler.toml > wrangler.local.toml
echo 'RATE_LIMIT_SALT = "local-dev-only"' > .dev.vars

npx wrangler dev -c wrangler.local.toml --port 8787 --local
```

⚠ **Use that command, not `npm run dev`.** `package.json` does define `dev` as a bare `wrangler dev`,
but bare `wrangler dev` reads the committed `wrangler.toml` — whose `[[kv_namespaces]]` block is
commented out on purpose — so it does not pick up the local binding this flow sets up. The `-c
wrangler.local.toml --local` form above is the supported local path. The four scripts in
`package.json` are `typecheck`, `test`, `dev`, `deploy`; every one used by this runbook is spelled
out here.

In a second shell, run the checks under "Verify a deploy" below with
`BASE=http://127.0.0.1:8787` — they are written against `$BASE` so the same three commands serve
both local and deployed. The plaintext guard is locally verifiable too
(`curl -s -H 'x-forwarded-proto: http' "$BASE/report"` → 403, and a bare local request passes —
no edge headers, nothing to enforce). What no local run can observe is which headers the **real**
edge sends this Worker; that is what the two required post-deploy curls above exist for.

Worth exercising beyond those three, since these are the behaviors the Data Safety declaration
describes and they are cheap to confirm here:

```bash
BASE=http://127.0.0.1:8787

# The limiter cuts at 10 per caller, and callers are independent.
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "%{http_code} " -X POST "$BASE/report" \
    -H 'content-type: application/json' -H 'cf-connecting-ip: 203.0.113.7' \
    -d '{"reasonId":"other","surface":"chat","excerpt":"x"}'
done; echo   # => ten 202s, then 429 429

# The counter increments BEFORE parsing, so rejected bodies count against a caller even though no
# report is stored. Three 400s and one 202 from a fresh caller => counter 4, one report: key.
for i in 1 2 3; do
  curl -s -o /dev/null -X POST "$BASE/report" -H 'content-type: application/json' \
    -H 'cf-connecting-ip: 198.51.100.5' -d '{"reasonId":"BAD","surface":"chat","excerpt":"x"}'
done
curl -s -o /dev/null -X POST "$BASE/report" -H 'content-type: application/json' \
  -H 'cf-connecting-ip: 198.51.100.5' -d '{"reasonId":"other","surface":"chat","excerpt":"ok"}'
```

Now read what actually landed. `kv key list` returns key **names only** — the claims gate 1 makes are
about the stored **values**, so each one needs a `kv key get`:

```bash
KV="--binding REPORTS --local -c wrangler.local.toml"

# The record must be exactly seven fields: the six schema fields plus receivedAt.
KEY=$(npx wrangler kv key list $KV | python3 -c \
  "import json,sys; print([k['name'] for k in json.load(sys.stdin) if k['name'].startswith('report:')][0])")
npx wrangler kv key get "$KEY" $KV | python3 -m json.tool

# Each rl: value must be a COUNT, not a flag. The 198.51.100.5 caller above should read 4,
# against exactly one report: key of theirs — the counter outrunning stored reports.
npx wrangler kv key get \
  "rl:$(python3 -c "import hashlib; print(hashlib.sha256(b'local-dev-only:198.51.100.5').hexdigest())")" $KV
```

That last command also re-derives the identifier independently: if the key it builds resolves, then
`callerHash` really is `sha256(salt + ":" + ip)`, which is what the declaration describes.

## Verify a deploy

These drive `$BASE`, so they serve both targets. Set it to whichever you are checking — and **skip
that line entirely if you already set `BASE` for the local run above**, or you will point these at
the wrong server.

```bash
BASE=https://report.ventouxlabs.com   # your deployed route
# BASE=http://127.0.0.1:8787          # the local server from the section above

# Expect 202
curl -si "$BASE/report" -H 'content-type: application/json' \
  -d '{"reasonId":"other","surface":"chat","excerpt":"test","note":null,"modelId":null,"backend":null}' | head -1

# Expect 400 — reason id outside the allowlist
curl -si "$BASE/report" -H 'content-type: application/json' \
  -d '{"reasonId":"nope","surface":"chat","excerpt":"test"}' | head -1

# Expect 405
curl -si "$BASE/report" | head -1
```

## Read the reports

**`--remote` is load-bearing.** wrangler v4's `kv key` commands default to the **local** Miniflare
store when run next to a `wrangler.toml` — without `--remote` these commands list a local store
that is empty unless you've run local workerd, and the warning banner saying so goes to stderr,
exactly where a script piping through `2>/dev/null` loses it. This burned a real session on
2026-08-17: production writes were "missing" for 40 minutes while every list ran against local
state, and deletes "cleaned up" the wrong store. If a listing disagrees with what the Worker
demonstrably does (a 429 proves the counter exists), suspect which store you're reading before
suspecting the data.

```bash
npx wrangler kv key list --remote --binding REPORTS --prefix 'report:'
npx wrangler kv key get  --remote --binding REPORTS '<key>'
```

## After it is live

The client send path (#258 step 2) is built: `ContentReportDelivery.kt` posts to
`https://report.ventouxlabs.com/report`, and the privacy policy and Data Safety updates landed in
the same PR as the client path, per the rule above.

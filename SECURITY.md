# Security policy & threat model

Relais is a network service: it binds a port on a phone and answers LLM requests
over the LAN. This document states what it defends against, what it does not, and
how to report issues.

## Reporting a vulnerability

Email **security@grepon.cc** with details and a proof of concept if you have one.
Please do not open a public issue for an unpatched vulnerability.

## Network posture (default)

- **HTTPS on the LAN, HTTP on loopback.** The node serves the LAN only over TLS
  (`https://<phone-ip>:8443`). The plaintext HTTP listener is bound to
  `127.0.0.1:8080` and is reachable only from the device itself (the in-app
  control panel and on-device tooling). The bearer API key therefore never
  crosses the network in cleartext.
- **Bearer-token auth on everything except `/health`.** A 32-hex-char key is
  generated per install, stored in `EncryptedSharedPreferences` (Keystore-wrapped),
  shown in the Relais Node control screen, and compared in constant time.
  (`/ca.crt` is also exempt — it serves the node's **public** CA certificate, which
  a client needs *before* it can verify the node at all. See "Verifying the node's
  certificate" below.)
- **Per-IP rate limiting**, with bounded, self-evicting state, in **two separate budgets** per HTTP
  listener:
  - **30 req / 60 s** for the authenticated routes (inference and everything else).
  - **120 req / 60 s** for the auth-exempt routes (`/health`, `/ca.crt`) — deliberately larger,
    because these are cheap, unauthenticated, and what monitoring polls. A shared budget would let a
    poller starve inference: a 10 s `/health` poll would spend a fifth of the inference budget doing
    nothing, and the race runs backwards, since the O(1) poll refills continuously while an
    expensive completion takes the `429`.

  Both budgets apply to every request that clears the auth check — including `/health`, which until
  #314 was silently unmetered and uncapped, because all three checks shared one condition. Which
  budget a request is charged is decided by the *same* predicate that decides its auth exemption, so
  the two cannot drift apart. Rate limiting does **not** apply to requests rejected for bad auth: the
  401 is returned before any limiter is consulted, so a failed-auth request costs no budget and brute
  force against the authenticated routes is still unlimited. That is deliberate — metering failed
  auth against the same per-IP bucket would let an unauthenticated flood exhaust a legitimate
  client's budget from behind the same NAT address — and it is tracked as a separate follow-up, not
  fixed here. The two listeners each hold their own pair of budgets, so loopback traffic from the app
  cannot exhaust a LAN client's. The Tasker intent lane is not an HTTP listener and is not
  rate-limited.
- **Body and header caps**, a per-read socket timeout, and a bounded worker pool
  to resist slow-client and oversized-request abuse. The body cap, like the rate
  limit, applies to `/health` too.
- **Thermal backpressure**: under sustained heat the node returns `503` +
  `Retry-After` instead of running the device into a throttle cliff.
- **No cloud egress for inference.** Inference is fully on-device. (Telemetry is
  being removed; see "Known limitations".)

### Operators: `/health` is rate-limited as of #314

`/health` needs no API key and never has. What changed is that it is now **rate
limited**, where before it was exempt from rate limiting and the body cap as a side
effect of being exempt from auth. It was unbounded; it is now bounded.

**If you poll `/health` from a monitoring script, uptime check, or load-balancer
probe, keep it under 120 requests per minute per source IP or it will start
receiving `429`.** That is a 0.5 s interval — comfortable for any realistic probe;
a 1 s or 10 s poll is nowhere near it. The `429` body quotes the budget you hit, so
you can tell the two apart.

`/health` and `/ca.crt` are metered on their **own** 120/min budget, not the 30/min
one the authenticated routes use, and the two are counted separately. Polling
`/health` therefore cannot consume the budget an inference client needs, in either
direction. The budget is still per source IP, so several probes behind one NAT
share the 120.

In-app chat is unaffected: it probes loopback `/health` once per send, via
`ChatTransportSelector.select()`. Those probes are charged to the auth-exempt
budget while the chat completion is charged to the authenticated one, so the two
never compete — which is what makes this safe regardless of how fast a turn
fails or how quickly a user sends.

## What Relais assumes

Relais is designed for a **trusted LAN** (your home or a private VLAN). With the
default posture it is safe to run there. For **untrusted networks** (cafe Wi-Fi,
shared hotspots, guest VLANs) you should additionally use one of:

- a private overlay network (WireGuard / Tailscale) and treat the node as
  reachable only over it; or
- certificate pinning (below) so a man-in-the-middle cannot impersonate the node.

## Verifying the node's certificate

The node mints a **per-node CA** once, and issues itself a short-lived (90-day)
leaf certificate under it carrying every address the node holds as a
`subjectAltName`. Verification is therefore possible, which it previously was
not: the old certificate had a `CN=relais-node` subject and **no SAN extension at
all**, and every modern TLS client ignores CN entirely — so `--cacert` failed
even against a certificate you had explicitly trusted, and `-k` was the only
thing that worked.

Fetch the CA once and pass it per connection:

```
curl -k -o relais-ca.crt https://<phone-ip>:8443/ca.crt   # -k only for this bootstrap fetch
curl --cacert relais-ca.crt https://<phone-ip>:8443/health
```

### That first fetch is trust-on-first-use

**This release has no way to verify the CA out of band, and you should know that
rather than infer it.** If someone is already intercepting the link you fetch
`/ca.crt` over, you import their CA and everything afterwards verifies perfectly
against the wrong party.

**The fingerprint on the node's status page does not close this.** It is served
over the same connection you are trying to verify, so an interceptor supplies the
certificate *and* the page showing its fingerprint. Comparing them proves the two
came from the same place, not that the place is your node. We say this explicitly
because the comparison looks like a real check, and treating it as one is worse
than knowing there isn't one.

So: **fetch the CA over a network you already trust** — your own LAN, or a USB
tether — and out-of-band verification (a QR shown on the node itself) lands in a
later release.

**What this does buy, which is most of the value.** Once the CA is imported, `-k`
is gone and every subsequent connection has real MITM protection. The gap is the
first fetch only — not "TLS is unverified". Import on a network you trust and you
have the full property today.

Once you have the file, this prints its fingerprint — useful for confirming two
copies match, or identifying the right certificate in a trust store:

```
echo "sha256/$(openssl x509 -in relais-ca.crt -pubkey -noout \
  | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64)"
```

The `sha256/` prefix is part of the value: every fingerprint the node publishes
carries it, so a command printing bare base64 would show a mismatch for a
perfectly good CA. Compare the whole string, prefix included.

The node publishes **two** `sha256/...` values and they are not interchangeable:

| Value | What it is | Used for |
|---|---|---|
| `CA FINGERPRINT` | the CA's public key | checking the `ca.crt` you downloaded |
| `NODE KEY PIN` | the **leaf's** public key | `curl --pinnedpubkey sha256//<value>` |

Pasting the CA value into `--pinnedpubkey` fails with an error that names
neither. The leaf key is generated once and **reused** across every re-issue, so a
`--pinnedpubkey` pin survives a re-issue; only the certificate is re-minted.

**When re-issue actually happens, which is narrower than it sounds:** at node
start, plus once when the LAN first appears after a boot-time start. It is **not**
triggered by an address change while the node is running — a phone that moves
network mid-session keeps serving a certificate that no longer covers its address,
and clients will fail hostname verification until the node is restarted. Restart
after moving networks.

The first fetch is trust-on-first-use — see "That first fetch is
trust-on-first-use" above for what that does and does not protect.

Prefer per-connection `--cacert` over installing the CA into a system trust
store. A system-store install is trusted by *everything* on that client — see the
blast-radius limitation below.

`/ca.crt` is served as `application/x-x509-ca-cert` with
`Content-Disposition: attachment`. That MIME type is the one desktop and mobile
platforms associate with "a CA to install", which pulls in the direction this
document argues against; the `attachment` disposition is what keeps a browser
saving the file rather than offering to trust it system-wide. Save it and pass it
per connection.

## Removing the CA when you are done with it

**Do this before selling, trading in, or handing on the phone**, and on any client
you no longer want trusting the node. This is the other half of the trust story
and the mitigation for the ten-year lifetime described below — the CA is **not**
removed when Relais is uninstalled.

- **Per-connection use (`--cacert`)** — delete the `relais-ca.crt` file. That is
  the whole removal; nothing else on the client ever trusted it. This is the main
  reason to prefer that path.
- **Linux system store** — remove the file from `/usr/local/share/ca-certificates/`
  and run `sudo update-ca-certificates --fresh`.
- **macOS** — Keychain Access → System (or login) → find `Relais Node CA <hex>` →
  delete. Or: `sudo security delete-certificate -c "Relais Node CA <hex>"`.
- **Windows** — `certmgr.msc` → Trusted Root Certification Authorities →
  Certificates → find `Relais Node CA <hex>` → delete.
- **iOS/iPadOS** — Settings → General → VPN & Device Management → remove the
  profile; also uncheck it under Certificate Trust Settings if you enabled full
  trust.
- **Android** — Settings → Security → Encryption & credentials → Trusted
  credentials → User → find it → remove. (Recall that apps ignore user-installed
  CAs from Android 7 onward, so this store only ever affected browsers and apps
  that opted in.)
- **On the node itself** — clearing Relais's app data discards the CA and leaf
  keystores. The node mints a fresh CA on next start, and every client must
  re-import; the old CA becomes useless to anyone holding a copy.

The CA's subject is `Relais Node CA <8 hex characters>`, which is how you identify
the right one in a store containing several.

## Known limitations (tracked)

- **Verification is client-side opt-in, and the node cannot enforce or observe
  it.** The node serves the identical certificate to a `--cacert` client and to a
  `-k` client. Everything above holds only for clients that actually imported the
  CA; removing `-k` from this repo's documentation does not remove it from
  anyone's scripts.
- **The node now holds a CA private key.** It is stored app-private, alongside the
  leaf key, and signs only for the node's own names. Compromise of the phone was
  already total for the node, so this is not new exposure *for the node* — but if
  a user installed the CA into a client's **system trust store**, whoever holds
  that key can impersonate any name the CA signs for, to that client. This is why
  the docs lead with per-connection `--cacert`, which scopes the trust to one
  connection instead of everything that client does.

  **`NameConstraints` was built, measured, and deliberately removed** — it is not
  an oversight, and re-adding it would be a regression. A CA constrained to
  private/CGNAT/loopback ranges plus the `localhost` and `local` DNS subtrees
  looked like free defence in depth. It was not:

  - **Inert on half the clients.** Neither the JDK's PKIX validator nor
    BouncyCastle's applies a *trust anchor's* own name constraints — the JDK
    refuses them outright, BC accepts a prohibited leaf silently. Every
    Java/Android/JSSE client got nothing.
  - **Actively harmful on the path we document.** The node puts every address it
    holds in the certificate, including globally routable ones on a cellular
    hotspot or an ISP that hands out public addresses. Those SANs fall outside the
    permitted ranges, so `curl --cacert` — the flow recommended above — rejects
    the whole chain. The main observable effect was breaking the recommended
    client.
  - **Fail-closed for verifiers we never test.** RFC 5280 requires the extension
    be critical, and a verifier that processes but does not understand a critical
    extension must reject the certificate.
  - **Unverifiable in CI**, as a direct consequence of the first point.

  It never constrained issuance for LAN addresses in any case, which is precisely
  where an attacker on your network already is.
- **`GET /ca.crt` is unauthenticated.** It serves only the public CA certificate —
  never the leaf, never a private key — so the disclosure is nil. The hazard is
  the bootstrap: a user who fetches it over an already-compromised link has
  trusted the attacker, and **this release gives them no way to detect that** —
  the fingerprints the node publishes travel over the same connection. Fetch it
  over a link you already trust; out-of-band verification lands with the QR in a
  follow-up.
- **The leaf's full SAN list is readable pre-auth.** Anyone who can complete a
  `ClientHello` against `:8443` learns every address the node holds, with no
  bearer token — including **overlay** (WireGuard/Tailscale) addresses, which are
  deliberately included so overlay clients can verify. Nothing here is secret in a
  strong sense; it is the node's own reachability, and an attacker already on the
  LAN learns the same by scanning. It is still a reconnaissance change from the
  old address-free certificate.

  The same applies to **identity across networks**: the CA's public key and its
  `Relais Node CA <8 hex>` subject are fixed for the life of an install and
  presented before any authentication, so the same phone is recognisable as the
  same phone on every LAN it joins. That is inherent to a per-node CA rather than
  something to engineer around, but it is worth knowing if you move the node
  between networks you would rather not correlate.

- **The CA outlives Relais.** It is valid for ten years, is imported once, and is
  **not** removed when the app is uninstalled or the phone is wiped from the
  client's point of view. A phone that is sold, repaired, or handed on takes its
  CA private key with it while the previous owner's clients keep trusting it. See
  "Removing the CA when you are done with it" above — that section exists for this
  bullet. The certificate's extended key usage limits it to server and client
  authentication (not code signing, e-mail, or timestamping), which bounds but
  does not eliminate the exposure.
- **A node running longer than 90 days serves an expired leaf.** Re-issue is
  computed at node start (and once when the LAN first comes up after a boot-time
  start), not on a timer. Restart to re-issue. Tracked as a follow-up.
- **mTLS ("hardened mode") is not implemented.** The work above authenticates the
  *server* to the client; mTLS would authenticate the *client* to the server. It
  composes cleanly on top of the CA built here and remains deferred.
- **mDNS discovery is unauthenticated** (link-local `_relais._tcp`). The advice to
  pair it with certificate verification is now actionable rather than
  aspirational — a spoofed advertisement lands on a certificate a verifying client
  refuses — but the advertisement itself is still an unauthenticated LAN
  broadcast that a hostile advertiser fully controls.
- **Bundled analytics.** The upstream fork still compiles Firebase Analytics/FCM;
  removal is tracked for the OSS release so the "no cloud" property holds for the
  whole app, not just inference.
- **Upgrade-in-place key remnant.** Upgrading from a pre-hardening build that stored
  the API key in plaintext may leave a recoverable remnant in on-disk SharedPreferences
  until it is overwritten. Fresh installs are unaffected. A key-rotation action is
  tracked; until then, a clean reinstall clears any remnant.

## Reproducing the hardening checks

`./gradlew :app:assembleDebug` builds the app; the `secret-scan` and
`license-lint` GitHub workflows gate secrets and license-header integrity on
every push.

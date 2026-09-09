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

**Check what you fetched.** Compare it against the `CA FINGERPRINT` shown on the
node before trusting it — fetching the CA over a link that is already
man-in-the-middled and skipping this check is *worse* than `-k`, because it feels
verified:

```
openssl x509 -in relais-ca.crt -pubkey -noout \
  | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
```

The node publishes **two** `sha256/...` values and they are not interchangeable:

| Value | What it is | Used for |
|---|---|---|
| `CA FINGERPRINT` | the CA's public key | checking the `ca.crt` you downloaded |
| `NODE KEY PIN` | the **leaf's** public key | `curl --pinnedpubkey sha256//<value>` |

Pasting the CA value into `--pinnedpubkey` fails with an error that names
neither. The leaf key is generated once and **reused** across every re-issue, so a
`--pinnedpubkey` pin keeps working after the node's IP changes; only the
certificate is re-minted.

Prefer per-connection `--cacert` over installing the CA into a system trust
store. A system-store install is trusted by *everything* on that client — see the
blast-radius limitation below.

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
  that key can impersonate any name the CA is permitted to sign for, to that
  client. This is why the docs lead with per-connection `--cacert`.

  The CA carries a critical `NameConstraints` extension limiting it to
  private/CGNAT/loopback IP ranges and the `localhost` and `local` DNS subtrees.
  Two limits on how much that is worth, both of which matter:

  **It blocks public names, not your own network.** The CA cannot sign for
  `google.com` or a public IP. It can still sign for *any* RFC1918, CGNAT or
  loopback address — i.e. everything on your LAN. Against an attacker already on
  your network the constraints buy nothing; what they bound is a stolen key's
  reach to private networks rather than the whole internet.

  **Whether they are enforced at all depends on the client.** OpenSSL applies a
  root's name constraints, so they are real for `curl --cacert`, the path these
  docs recommend — verified, not assumed: a leaf for `8.8.8.8` is rejected with
  `permitted subtree violation`. Java-based clients enforce **nothing** here:
  neither the JDK's PKIX validator nor BouncyCastle's applies a trust anchor's own
  constraints (the JDK refuses them outright, BC accepts such a leaf silently).
  They ignore the extension rather than rejecting it, so it costs no
  compatibility — but do not count on it outside the documented path.
- **`GET /ca.crt` is unauthenticated.** It serves only the public CA certificate —
  never the leaf, never a private key — so the disclosure is nil. The hazard is
  the bootstrap: a user who fetches it over an already-compromised link and skips
  the fingerprint check has trusted the attacker. Treat it as a convenience;
  the out-of-band fingerprint is what makes it safe.
- **The leaf's full SAN list is readable pre-auth.** Anyone who can complete a
  `ClientHello` against `:8443` learns every address the node holds, with no
  bearer token — including **overlay** (WireGuard/Tailscale) addresses, which are
  deliberately included so overlay clients can verify. Nothing here is secret in a
  strong sense; it is the node's own reachability, and an attacker already on the
  LAN learns the same by scanning. It is still a reconnaissance change from the
  old address-free certificate.
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

# Plan: Trusted LAN Certificate (kill `curl -k`)

## Summary

The node's HTTPS listener serves a self-signed certificate with **zero SAN extensions**
(`RelaisTls.kt:78-87`), so every quickstart in the repo ends in `curl -k` and the bearer API key is
handed to an unverified peer on every request. This plan replaces that with a **per-node CA plus a
short-lived SAN'd leaf**: the CA is minted once and exported out-of-band (QR / share sheet /
`GET /ca.crt`), the leaf is re-minted whenever the LAN IP changes, and a client that trusts the CA
once keeps verifying across DHCP churn. `RelaisClientConfig.TLS_NOTE` (`RelaisClientConfig.kt:122-126`)
already instructs clients to "import the relais self-signed cert as a trusted CA" — a route that does
not exist; this makes that instruction true.

## User Story

**As a** developer running a Relais node on my home LAN,
**I want** to reach `https://<phone-ip>:8443` with TLS verification enabled after a one-time cert
import,
**So that** my API key is not exposed to anyone who can ARP-spoof or hijack the mDNS advertisement on
my network, and so my tooling keeps working when the phone's DHCP lease changes.

## Problem → Solution

| | |
|---|---|
| **Problem 1** | The leaf has **no SAN extension at all** (`RelaisTls.kt:82-84` sets only `X500Name("CN=relais-node")`). Modern TLS clients ignore CN entirely for hostname verification, so `--cacert` against today's cert fails even when the cert *is* trusted. Verification is not merely inconvenient — it is impossible. |
| **Problem 2** | Pinning a bare self-signed leaf breaks the moment the phone's IP changes, which the mDNS work already acknowledged as a real event (`RelaisDiscovery.kt:29-31`: "fixes the IP-change problem the wifi soak surfaced"). |
| **Problem 3** | `SECURITY.md:42-50` documents the resulting MITM exposure as a tracked limitation, and `README.md:98-100`, `docs/RUNBOOK.md:27`, `Bug_Reporting_Guide.md:26,29`, `Function_Calling_Guide.md:20,59`, `docs/input-content-guide.md:37`, `docs/soak/soak.sh:44,49` all teach `-k`. |
| **Solution** | Mint a per-node **EC P-256 CA** (10y, never handed to a `KeyManagerFactory`) that signs an **RSA-2048 leaf** (90d) carrying every live LAN/overlay/loopback address as a SAN. Re-mint the leaf at node start when the SAN set changes, **reusing the leaf key** so `--pinnedpubkey` survives. Export the CA out-of-band via QR + share sheet on the CONFIGURE screen, with an unauthenticated `GET /ca.crt` as a convenience. Docs lead with per-connection `--cacert`, not a system-store install. |

## Metadata

| | |
|---|---|
| **Complexity** | **Large** |
| **Source PRD** | N/A |
| **PRD Phase** | N/A |
| **Estimated Files** | **44 total** — **33 code/res** (17 created: 7 main Kotlin + 7 JVM tests + 3 probes; 16 updated: 10 main Kotlin (adds `RelaisNodeService.kt` for T5b) + `file_paths.xml` + `proguard-rules.pro` + a conditional `build.gradle.kts` + 3 existing JVM tests) **+ 11 docs** (1 created: `docs/tls-trust.md`; 10 updated). Scope a PR off the 33, not the 44. T2 is one of those PRs on its own |

## Cross-plan & Dependencies

**Build order: ALL of feature-18 lands before feature-09.** This supersedes the interleaved order in
`.claude/HANDOFF.md:50` (which had #09 landing between #18's T2 and its T6/T9, straight through the
handlers T6/T9 edit). Confirm the HANDOFF line is updated when this plan is scheduled.

| Plan | Relationship | What this plan must do |
|---|---|---|
| **feature-09 (web dashboard)** | Lands **after** all of #18 | #09 **cut** the task that moved `handleDashboard` into `RelaisHttpPages.kt`, so `RelaisHttpServer.kt:714-747` and the CSP at `:741` **stay where they are** — T6/T9 target the current file and current line numbers. #09 still (a) extends `authorized()` with HTTP Basic and (b) changes `recordRequest(endpoint, status)` to `recordRequest(endpoint, status, inRecentLog = true)`. Both land **on top of** T2's restructured gate, so **#09 re-bases onto T2 — T2 is never re-applied on top of #09.** #09 also creates `RelaisHttpAuthTest.kt`; T2's `RelaisHttpGateTest.kt` (H2) is a **different, narrower** file and the two must not be merged |
| **feature-23 (multi-node router)** | Consumes #18's output | #23 pins the **leaf SPKI**, not the certificate DER. Decision 3's leaf-key reuse is therefore a **contract, not an optimization**: *the leaf key pair is generated once and survives every re-mint; only the certificate changes.* `RelaisCertReissueTest` asserts it explicitly (see Testing Strategy) so #23 cannot be broken silently by a later refactor |
| **feature-22 / feature-17** | No overlap | Neither touches `RelaisTls.kt`, the gate, or the cert surfaces |

## UX Design

### Before

```
DASHBOARD (DashboardScreen.kt)              CONFIGURE (RelaisConfigureActivity.kt)
┌────────────────────────────┐              ┌────────────────────────────┐
│  ● RELAIS          LIVE    │              │  MODEL                     │
│  gemma-4-E4B-it            │              │  gemma-4-E4B-it         ▸  │
│  ────────────────────────  │              │  ────────────────────────  │
│  LAN (https)               │              │  HF TOKEN                  │
│    https://192.168.1.40:…⧉ │              │  [                      ]  │
│  LOCAL (http)              │              │  SAVE HF TOKEN             │
│    http://127.0.0.1:8080 ⧉ │              │  ────────────────────────  │
│  ┌──────────────────────┐  │              │  POWER                     │
│  │ ACCESS KEY  ••••7f2a │  │              │  ────────────────────────  │
│  │ SHOW      TAP TO COPY│  │              │  INTEGRATIONS              │
│  └──────────────────────┘  │              │  SHARE TARGET         off  │
│  ────────────────────────  │              │  NFC WORKFLOWS        off  │
│  MODEL                     │              └────────────────────────────┘
│  gemma-4-E4B-it         ▸  │
│  CONFIGURE ›               │              (no certificate surface anywhere)
│  ┌──────────────────────┐  │
│  │        STOP          │  │
│  └──────────────────────┘  │
└────────────────────────────┘
```

### After

```
DASHBOARD — ONE new line only            CONFIGURE — new section, above INTEGRATIONS
┌────────────────────────────┐           ┌────────────────────────────┐
│  ● RELAIS          LIVE    │           │  MODEL                     │
│  gemma-4-E4B-it            │           │  ...                       │
│  ────────────────────────  │           │  ────────────────────────  │
│  LAN (https)               │           │  HF TOKEN / POWER ...      │
│    https://192.168.1.40:…⧉ │           │  ────────────────────────  │
│  LOCAL (http)              │           │  CERTIFICATE               │
│    http://127.0.0.1:8080 ⧉ │           │   ┌────────────────────┐   │
│  ┌──────────────────────┐  │           │   │ ███ ▄▄█ ▀█▄ █▄ ███ │   │
│  │ ACCESS KEY  ••••7f2a │  │           │   │ █▄▀ ██▄ ▄█▀ ▀█ ▄▀█ │   │
│  │ SHOW      TAP TO COPY│  │           │   │ ███ ▀▄█ █▀▄ ██ ███ │   │
│  └──────────────────────┘  │           │   └────────────────────┘   │
│  ────────────────────────  │           │   scan to import the CA    │
│  MODEL                     │           │  CA FINGERPRINT            │
│  gemma-4-E4B-it         ▸  │           │    sha256/9Kf2…c1Ae     ⧉  │
│  CONFIGURE ›               │           │  NODE KEY PIN              │
│  CERTIFICATE ›     ← NEW   │           │    sha256/Lm81…77Bd     ⧉  │
│  ┌──────────────────────┐  │           │  EXPIRES        in 87 days │
│  │        STOP          │  │           │  COVERS   192.168.1.40, …  │
│  └──────────────────────┘  │           │  EXPORT CA CERT ›          │
└────────────────────────────┘           │  ────────────────────────  │
                                          │  INTEGRATIONS              │
                                          └────────────────────────────┘
```

**Placement rationale (DESIGN.md decides this, three ways):** "Setup-time and rare controls live on
the CONFIGURE screen (`RelaisConfigureActivity`), one tap away, in the same panel styling"; "the home
screen must fit a 6.1" display without scrolling"; "At most ONE hero element per screen state". A
one-time CA export is the textbook rare setup-time control, and a QR block + two fingerprint rows
would blow the dashboard's no-scroll budget.

### Interaction Changes

| Touchpoint | Before | After | Notes |
|---|---|---|---|
| Dashboard, below `CONFIGURE ›` | — | `CERTIFICATE ›` opens CONFIGURE | One `ActionLink`; no layout growth beyond one 12sp line |
| CONFIGURE, above INTEGRATIONS | — | `CERTIFICATE` section: QR, two labelled fingerprint rows, expiry, SAN list, `EXPORT CA CERT ›` | New `SectionLabel` block, matching `RelaisConfigureActivity.kt:251-262` |
| `EXPORT CA CERT ›` | — | `ACTION_SEND` chooser with a `relais-ca.crt` attachment | Mirrors `ui/common/Utils.kt:375-403` |
| Fingerprint rows | — | Tap to copy, `COPIED` ack ~1.5s | DESIGN.md: "Every copyable value … carries an explicit trailing copy affordance" |
| Served `GET /` | Node Status / Client Config / Recent Requests | + a **text-only** Certificate panel | No QR — see GOTCHA in T9 |
| `GET /ca.crt` | 404 | 200 `application/x-x509-ca-cert` PEM, **unauthenticated**, rate-limited | New route |
| `GET /health` | unauthenticated, **unmetered** | unauthenticated, **rate-limited** | Behavior change from T2; see Risks |
| Node start after an IP change | serves a stale cert nobody verified | re-mints the leaf; CA and pin unchanged | Panel/docs say "restart to re-issue" |

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisTls.kt` | 33-88 (whole object) | The file being rewritten. Lines 47-51 carry the non-negotiable conscrypt constraint; 78-87 is the SAN-less cert that is the bug |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 265-286 | The auth/rate-limit/body-cap gate. **T2 restructures this**; read before touching |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 288-297, 690-712 | Route dispatch `when` + `RequestContext` + the shortest handler to mirror |
| **P0** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisLanIp.kt` | 22-54 (whole object) | `lanIpv4()` returns **one** address; the SAN builder needs all of them |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | 714-747, 1877-1899, 1937-1943 | `handleDashboard` (CSP at 741 — do not touch), `endpointLabel`, `respondText` (`:1933` is `respond`, a different helper) |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisConfig.kt` | 338-350 | `tlsKeystorePassword` — the exact idiom `caKeystorePassword` must mirror |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisConfigureActivity.kt` | 249-262, 315-331 | Where the CERTIFICATE section goes + the `SectionLabel`/`Divider`/`ActionLink` idiom |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisClientConfig.kt` | 116-126, 164-198 | `TLS_NOTE` and the `tls` block in `/v1/clientconfig` — both must name `/ca.crt` |
| **P1** | `Android/src/app/src/androidTest/java/cc/grepon/relais/ClientConfigEndpointProbe.kt` | 34-80 | The exact probe shape (`assumeTrue` gate, `am instrument` header, in-probe server) `CertTrustProbe` mirrors |
| **P1** | `Android/src/app/src/main/java/cc/grepon/relais/ui/common/Utils.kt` | 375-403 | The only file-backed share in the app — the `.crt` share sheet copies it |
| **P2** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisNodeService.kt` | 189-190 | Where the TLS server is constructed (`tls = true, bindAddr = "0.0.0.0"`) |
| **P2** | `Android/src/app/src/main/java/cc/grepon/relais/DashboardScreen.kt` | 198-202, 210-244, 428-439 | `ActionLink` idiom; `CopyableRow`; the duplicated `lanIpv4()` T1 consolidates |
| **P2** | `Android/src/app/src/test/java/cc/grepon/relais/RelaisControlPanelPaletteGuardTest.kt` | 115-118 | Proves `RelaisConfigureActivity.kt` **is** palette-guarded — the new section must use tokens only |
| **P2** | `SECURITY.md` | 40-57 | The "Known limitations" block this feature retires and replaces |
| **P2** | `Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt` | 241-258 | A **second** `endpointLabel(raw)`, distinct from `RelaisHttpServer.kt:1877-1899` and used only by `RelaisMetricsIncrementsTest`. A `/ca.crt` label must be added to **both** or the metrics test drifts |
| **P2** | `.github/workflows/build_android.yaml` | 73-86 | The GMS dex gate any QR dependency must survive |

## External Documentation

| Topic | Source URL | Key Takeaway |
|---|---|---|
| X.509 SAN & hostname verification | RFC 6125 §6.4.4 / RFC 2818 §3.1 | CN is deprecated for identity; clients that find *any* `subjectAltName` **must ignore CN entirely** |
| IP address SANs | RFC 5280 §4.2.1.6 | An IP literal goes in `iPAddress` (4 or 16 raw octets), never `dNSName` |
| Name constraints | RFC 5280 §4.2.1.10 | `iPAddress` constraints are CIDR subtrees, `dNSName` constraints are suffix subtrees |
| curl public-key pinning | `curl --pinnedpubkey` man page | `sha256//<base64>` pins the **server's end-entity** SPKI, not the CA's |
| BouncyCastle cert building | `JcaX509v3CertificateBuilder` javadoc | `.addExtension(Extension.subjectAlternativeName, false, GeneralNames)`. **The repo uses the builder (`RelaisTls.kt:83-86`) but calls `.addExtension` nowhere — today's cert carries no extensions at all.** This is net-new usage, not an existing idiom to copy |
| Android user CA trust | Android 7.0 (API 24) network-security-config changes | User-installed CAs are **not trusted by apps** unless the app opts in |
| Apple certificate trust | iOS/macOS certificate trust settings | Installing a root profile is **not enough** — full trust is a separate toggle |

**Research items:**

**R1 — SAN is mandatory, CN is inert**
- `KEY_INSIGHT`: Because today's cert has *no* SAN, a client falls back to CN and most modern stacks
  refuse outright. Adding a CA without adding SANs would change nothing observable.
- `APPLIES_TO`: T3 (`RelaisCertMint.buildSanList` / `mintLeaf`), T4.
- `GOTCHA`: An IPv4 literal in a `dNSName` GeneralName is silently ignored by verifiers — it must be
  `GeneralName.iPAddress`. This fails *quietly*, which is why T4 asserts a real handshake.

**R2 — Two different SPKI hashes**
- `KEY_INSIGHT`: The QR carries the **CA** SPKI hash; `--pinnedpubkey` needs the **leaf** SPKI hash.
- `APPLIES_TO`: T3 (formatters), T8 (labels), T11 (docs).
- `GOTCHA`: Pasting the CA value into `--pinnedpubkey` fails with an opaque error. Label them
  `CA FINGERPRINT` and `NODE KEY PIN` on every surface; T3's test asserts they differ.

**R3 — Name constraints can reject your own loopback SANs**
- `KEY_INSIGHT`: Java's PKIX validator enforces NameConstraints. A permitted set covering the LAN CIDR
  and `.local` does **not** cover the bare `localhost` dNSName or `127.0.0.1`.
- `APPLIES_TO`: T3 (`mintCa`), T4.
- `GOTCHA`: This breaks the `adb forward` path that `docs/RUNBOOK.md:27` documents — and it breaks it
  on a *developer's* machine, not in CI, unless T4 asserts loopback against the real extension set.

**R4 — Android/Apple client trust each have a second step**
- `KEY_INSIGHT`: Android 7+ apps ignore the user CA store; iOS needs "Enable Full Trust" after install.
- `APPLIES_TO`: T11 (`docs/tls-trust.md`).
- `GOTCHA`: Both look like a successful install and then silently fail. Lead the docs with per-connection
  `--cacert`, which has neither problem.

**R5 — QR capacity**
- `KEY_INSIGHT`: An RSA-2048 CA is ~1.0-1.2 KB DER; byte-mode QR only reaches that at version 40
  (177×177), unscannable off a phone screen. An EC P-256 CA is far smaller — but the payload should
  still be a **URL + fingerprint**, not the certificate.
- `APPLIES_TO`: T3 (EC CA), T7 (payload + capacity assert).
- `GOTCHA`: Byte counts are model knowledge, not measurement. T7 asserts against the **real** DER.

## Patterns to Mirror

### NAMING_CONVENTION — `internal object`, file named for the object, AGPL header, KDoc states *why*

```kotlin
/**
 * LAN IPv4 discovery for the `/v1/clientconfig` base URL (extracted from `RelaisHttpServer` — #173).
 * No UI/Context dependency, so it's a pure network-interface helper.
 */
internal object RelaisLanIp {
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisLanIp.kt:18-22
```

`RelaisCertMint` takes the same shape: `internal object`, own file, KDoc naming the issue and the
Context-free property. AGPL-3.0 header on every net-new file (`license-lint.yml` enforces it; copy the
header from `RelaisLanIp.kt:1-11`).

### ERROR_HANDLING — one envelope, typed constants, `runCatching` with a total fallback

```kotlin
  /** Missing/incorrect credentials (401). */
  const val AUTHENTICATION = "authentication_error"
  ...
  /** `{"error":{"message":[message],"type":[type]}}` — the OpenAI-compatible error envelope. */
  fun json(message: String, type: String): JSONObject =
    JSONObject().put("error", JSONObject().put("message", message).put("type", type))
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisError.kt:36-37, 57-59
```

```kotlin
  fun lanIpv4(): String =
    runCatching {
      val nis = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
      ...
      "0.0.0.0"
    }.getOrDefault("0.0.0.0")
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisLanIp.kt:43-53
```

Interface enumeration throws on some devices; every read is wrapped with a **total** fallback.
`allLanAddresses()` falls back to the loopback-only set, never to an empty list.

### LOGGING_PATTERN — `private const val TAG`, `Log.i` on state change, never the secret

```kotlin
internal object RelaisTls {
  private const val TAG = "RelaisTls"
  ...
    Log.i(TAG, "Generated self-signed TLS keystore at ${file.path}")
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisTls.kt:40-41, 73
```

Log the *event* and a non-secret locator. Log the CA fingerprint (public) and the SAN set; never the
keystore password or any private key.

### HANDLER_PATTERN — the four places a new route must be registered

```kotlin
        // Health is open; everything else needs the API key + is rate-limited per client IP.
        if (!(method == "GET" && path.startsWith("/health"))) {
          if (!authorized(authorization)) {
            reply(401, RelaisError.json("unauthorized", RelaisError.AUTHENTICATION))
            return
          }
          val ip = (sock.inetAddress?.hostAddress) ?: "unknown"
          if (!rateLimiter.allow(ip)) {
            reply(429, RelaisError.json("rate limit exceeded ($RATE_LIMIT/${RATE_WINDOW_MS / 1000}s)",
                RelaisError.RATE_LIMIT_EXCEEDED))
            return
          }
          if (contentLength > MAX_BODY_BYTES) {
            reply(413, RelaisError.json("request too large", RelaisError.INVALID_REQUEST))
            return
          }
        }

        // Request-scoped context threaded into the extracted route handlers (#173).
        val ctx = RequestContext(sock, reader, contentLength, path, endpoint, accept, sessionEnabled, sessionHeader, ::reply)
        when {
          method == "GET" && path.startsWith("/health") -> handleHealth(ctx)
          method == "GET" && path == "/" -> handleDashboard(ctx)
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:265-294
```

```kotlin
  private fun handleHealth(ctx: RequestContext) {
    ctx.send(
      200,
      JSONObject().put("status", "ok").put("ready", RelaisEngine.isReady).put("thermal_state", ThermalGovernor.statusValue),
    )
  }
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:707-712
```

```kotlin
    respondText(
      ctx.sock, 200, renderDashboardHtml(dashStatus), "text/html; charset=utf-8",
      listOf(
        // Scriptless page — no script-src at all; default-src 'none' blocks everything else.
        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
        "X-Content-Type-Options: nosniff",
        "X-Frame-Options: DENY",
        "Referrer-Policy: no-referrer",
      ),
    )
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:737-747
```

```kotlin
  private fun endpointLabel(path: String): String =
    when {
      path.startsWith("/health") -> "/health"
      path == "/" -> "/"
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:1877-1881
```

So `/ca.crt` needs: the gate condition (T2), a `when` arm, a `handleCaCert(ctx)`, **and** an
`endpointLabel` arm — miss the last and the route reports as `"other"` in `/metrics`.

### TEST_STRUCTURE — hermetic JVM, AGPL header, backtick names, KDoc says why it is testable

```kotlin
package cc.grepon.relais

import cc.grepon.relais.RelaisClientConfig.Capabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the mDNS TXT attribute map that [RelaisDiscovery] advertises.
 *
 * `NsdServiceInfo` is an Android type that can't be instantiated in a plain JVM unit test, so we
 * test the pure source of the TXT attributes — [RelaisClientConfig.buildDiscoveryTxt] ...
 */
class RelaisDiscoveryTxtTest {

  @Test
  fun `txt attribute keys are exactly the advertised routing set`() {
    val txt = RelaisClientConfig.buildDiscoveryTxt(
      modelId = "litert-community/gemma-4-E4B-it",
      version = "1.0.15",
      httpsPort = 8443,
      caps = Capabilities(multimodal = true, tools = true, reasoning = true),
    )
    assertEquals(setOf("model", "version", "https", "api", "path", "auth", "caps"), txt.keys)
  }
// SOURCE: Android/src/app/src/test/java/cc/grepon/relais/RelaisDiscoveryTxtTest.kt:19-52
```

JUnit 4 + `org.junit.Assert.*` (not kotlin.test), one test file per subject file, `*Test.kt`.

### PROBE_STRUCTURE — `assumeTrue` gate, `am instrument` line in the header, server started in-probe

```kotlin
/**
 * On-device probe for the Feature #11 `GET /v1/clientconfig` endpoint. ... It
 * is `assumeTrue`-gated (skips unless RELAIS_PROBE=1 is passed) so it never runs in the JVM unit
 * lane or unattended CI.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.ClientConfigEndpointProbe \
 *     -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ClientConfigEndpointProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()
  private val port = 18099 // high loopback port unlikely to collide with the real :8080 listener
  private var server: RelaisHttpServer? = null

  @Before
  fun setUp() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    server = RelaisHttpServer(context, port = port, tls = false, bindAddr = "127.0.0.1").also { it.start() }
    Thread.sleep(300) // let the accept loop bind before the first connect
  }

  @After
  fun tearDown() { server?.stop(); server = null }
// SOURCE: Android/src/app/src/androidTest/java/cc/grepon/relais/ClientConfigEndpointProbe.kt:34-68
```

**⚠ Caveat on this reference:** `ClientConfigEndpointProbe`'s own KDoc says *"DEFERRED — no device was
connected this session, so this is documentation of the intended check rather than a CI gate."* It is a
correct **shape** to copy, but it has never run on hardware. `BatchE2eProbe.kt:34-50` is the same shape
with a companion logcat line and is the better second reference:

```kotlin
 *   adb shell am instrument -w -e class cc.grepon.relais.BatchE2eProbe -e RELAIS_PROBE 1  *     com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 *   # in another shell: adb logcat -s RelaisBatch:* RelaisEngine:*
// SOURCE: Android/src/app/src/androidTest/java/cc/grepon/relais/BatchE2eProbe.kt:47-50
```

`CertTrustProbe` is this with `tls = true` and a real `SSLSocket` client. The ctor it relies on:

```kotlin
class RelaisHttpServer(
  private val context: Context,
  private val port: Int = 8080,
  private val tls: Boolean = false,
  private val bindAddr: String = "127.0.0.1", // safe default; callers opt into 0.0.0.0 for TLS (C1)
) {
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt:170-175
```

### CONFIGURE_SECTION_PATTERN — where the new UI goes

```kotlin
    Divider()

    // 4. INTEGRATIONS — toggles as single tappable rows (P9), not paired readout + command-link.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      SectionLabel("INTEGRATIONS")
      ToggleRow("SHARE TARGET", shareEnabled) { ... }
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisConfigureActivity.kt:249-254
```

```kotlin
/** Amber tap affordance, e.g. "PROMPT TEMPLATES ›" — matches the control panel's "… ›" idiom. */
@Composable
private fun ActionLink(label: String, onClick: () -> Unit) {
  Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClick() }.padding(vertical = 4.dp)) {
    Text(label, color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
  }
}
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisConfigureActivity.kt:325-331
```

### CONFIG_ACCESSOR_PATTERN — lazy-generate-and-persist into encrypted prefs

```kotlin
  fun tlsKeystorePassword(context: Context): String {
    val sp = securePrefs(context)
    sp.getString(KEY_TLS_PASS, null)?.let {
      return it
    }
    val pass = UUID.randomUUID().toString().replace("-", "")
    sp.edit().putString(KEY_TLS_PASS, pass).apply()
    return pass
  }
// SOURCE: Android/src/app/src/main/java/cc/grepon/relais/RelaisConfig.kt:342-350
```

## Files to Change

| File | CREATE/UPDATE | Justification |
|---|---|---|
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpGate.kt` | **CREATE** | The pure gate decision T2 extracts out of `private fun handle()`. Its own file because the gate is the security-critical surface and must be reachable from a JVM test (H2) |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisCertMint.kt` | **CREATE** | Context-free CA/leaf minting, SAN builder, re-issue predicate. Context-free so T4 can run it in a JVM test |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisCertFingerprint.kt` | **CREATE** | SPKI-base64 and colon-hex formatters. Separate file so the UI can import it without pulling BouncyCastle minting in |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisCertInfo.kt` | **CREATE** | Immutable snapshot (`caFingerprint`, `nodeKeyPin`, `sanList`, `leafNotAfter`) read by the UI, `/`, and `/ca.crt` |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisQrEncoder.kt` | **CREATE** | QR bitmatrix → `ImageBitmap`. Own file so a dependency swap is one file |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisCertSection.kt` | **CREATE** | The CONFIGURE Composable section. `RelaisConfigureActivity.kt` is already 387 lines; CLAUDE.md says extract rather than grow |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisCertExport.kt` | **CREATE** | FileProvider write + `ACTION_SEND` for `relais-ca.crt` |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisTls.kt` | UPDATE | Becomes the thin Android shim: read config → call `RelaisCertMint` → build the socket. Two keystores |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisNodeService.kt` | UPDATE | **T5b (new, closes codex P1 on T5):** owns `httpsServer`, so it — not `RelaisTls` — registers the `NetworkCallback` and does the stop/reconstruct on LAN-up |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisLanIp.kt` | UPDATE | Add `allLanAddresses()`; keep `lanIpv4()`/`localLanIp()` as-is |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt` | UPDATE | T2 gate restructure; `GET /ca.crt` arm + handler; `endpointLabel` arm |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisConfig.kt` | UPDATE | `caKeystorePassword(context)` mirroring `:342-350` |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt` | UPDATE | The **second** `endpointLabel` at `:241-258` needs the `/ca.crt` arm too, or `RelaisMetricsIncrementsTest` drifts from the server's label set |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisDashboard.kt` | UPDATE | Text-only Certificate panel in `renderDashboardHtml` |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisClientConfig.kt` | UPDATE | `TLS_NOTE` names `/ca.crt`; `tls` block gains `ca_url` + both fingerprints |
| `Android/src/app/src/main/java/cc/grepon/relais/RelaisConfigureActivity.kt` | UPDATE | Call `CertificateSection()` above INTEGRATIONS |
| `Android/src/app/src/main/java/cc/grepon/relais/DashboardScreen.kt` | UPDATE | One `ActionLink("CERTIFICATE ›")`; drop the duplicated `lanIpv4()` at `:428-439` |
| `Android/src/app/src/main/res/xml/file_paths.xml` | UPDATE | New `<cache-path>` entry for the cert export |
| `Android/src/app/build.gradle.kts` | UPDATE | **Only if** T7 picks a QR library |
| `Android/src/app/proguard-rules.pro` | UPDATE | **Expected, not optional-feeling**: R8 is on for release (`build.gradle.kts:146`) and there are zero BouncyCastle rules today. Confirmed by the release-APK acceptance criterion, not by CI |
| `…/src/test/java/cc/grepon/relais/RelaisHttpGateTest.kt` | **CREATE** | **T2's coverage.** `/ca.crtXYZ` → 401, `/ca.crt?x=1` → 401, POST `/ca.crt` → 401, `/health` exempt, rate limit + body cap unconditional. RED-first |
| `…/src/test/java/cc/grepon/relais/RelaisCertSanTest.kt` | **CREATE** | SAN builder: loopback trio, dedupe, stable sort, 32-cap, `iPAddress` tagging |
| `…/src/test/java/cc/grepon/relais/RelaisCertFingerprintTest.kt` | **CREATE** | Known-answer vectors; **CA fingerprint ≠ node key pin** |
| `…/src/test/java/cc/grepon/relais/RelaisCertMintTest.kt` | **CREATE** | CA/leaf extension shapes, chain verify, validity bounds |
| `…/src/test/java/cc/grepon/relais/RelaisCertReissueTest.kt` | **CREATE** | Re-issue predicate + the leaf-key-reuse (pin-survival) property |
| `…/src/test/java/cc/grepon/relais/RelaisTlsHandshakeTest.kt` | **CREATE** | **The end-to-end verified handshake — the highest-value test in the plan** |
| `…/src/test/java/cc/grepon/relais/RelaisQrPayloadTest.kt` | **CREATE** | Payload shape + capacity against the real minted DER |
| `…/src/test/java/cc/grepon/relais/RelaisClientConfigTest.kt` | UPDATE | `:227` already asserts `TLS_NOTE` contains no `curl -k`; T9's rewrite must keep it green and add assertions for `ca_url` + both fingerprints |
| `…/src/test/java/cc/grepon/relais/RelaisDashboardTest.kt` | UPDATE | The new text-only certificate panel on `GET /` (T9) |
| `…/src/test/java/cc/grepon/relais/RelaisDiscoveryTxtTest.kt` | UPDATE | Only if T12 (optional `spki=`) is taken |
| `…/src/androidTest/java/cc/grepon/relais/CertTrustProbe.kt` | **CREATE** | Real conscrypt handshake on hardware (what the JVM test cannot prove) |
| `…/src/androidTest/java/cc/grepon/relais/CertReissueProbe.kt` | **CREATE** | Network switch → new SANs, same CA, same leaf key |
| `…/src/androidTest/java/cc/grepon/relais/CaKeystoreProbe.kt` | **CREATE** | **Resolves open question 1** — AndroidKeyStore EC key as a `ContentSigner` |
| `docs/tls-trust.md` | **CREATE** | The per-client trust/install guide (the only created doc) |
| `SECURITY.md`, `README.md`, `docs/RUNBOOK.md`, `Bug_Reporting_Guide.md`, `Function_Calling_Guide.md`, `docs/input-content-guide.md`, `docs/soak/soak.sh`, `docs/openapi.yaml`, `DESIGN.md`, `docs/CODEMAPS/` | UPDATE | T11 docs sweep. **`README.md` needs two separate edits** — the command at `:91` and the prose at `:97-100` ("hence `-k` for now") |

## NOT Building

- **mTLS / client certificates.** `SECURITY.md:46-47` floats an "optional mTLS hardened mode" — separate feature.
- **Mid-session certificate hot-swap.** A bound `SSLServerSocket` cannot pick up a new cert without a
  re-reading `X509ExtendedKeyManager`. Steady-state re-issue happens at node **start**; the docs say
  "restart to re-issue". **Carve-out:** the *boot-race* case in T5 is not a hot-swap — it closes and
  re-creates the 8443 listener once, the first time a non-loopback address appears. That is in scope
  precisely because the alternative is an appliance that serves a useless cert for its whole uptime.
- **ACME / publicly-trusted certificates.** No public DNS name, no inbound :80/:443.
- **Tailscale `tailscale cert` integration.** Per assumption A1 the Android client exposes no such API.
  Overlay addresses land in the SAN set for free — that is the whole win.
- **A QR *scanner*.** Export only. No `CAMERA`-driven cert import.
- **Certificate revocation (CRL/OCSP).** A 90-day leaf and a user-deletable CA are the revocation story.
- **Changing `GET /`'s CSP** (`RelaisHttpServer.kt:741`) to allow images.
- **Auto-installing the CA on the host device.** No `KeyChain.createInstallIntent()` flow.
- **Rotating the API key.** Tracked separately in `SECURITY.md:54-57`.

## Step-by-Step Tasks

### T1 — `RelaisLanIp.allLanAddresses()` + de-duplicate the copy

- **ACTION**: UPDATE `RelaisLanIp.kt`; UPDATE `DashboardScreen.kt`.
- **IMPLEMENT**: Add `fun allLanAddresses(): List<InetAddress>` — enumerate `NetworkInterface`,
  **filter `it.isUp && !it.isLoopback` exactly as `RelaisLanIp.kt:45` already does**, keep every
  non-loopback `Inet4Address` and every non-loopback, non-link-local `Inet6Address`, dedupe by
  `hostAddress`, sort by `hostAddress` for stability. **Do not cap here** — T3 caps once, after the
  fixed loopback entries are prepended. Wrap in `runCatching { … }.getOrDefault(emptyList())`.
  Delete `DashboardScreen.kt:428-439` and call `RelaisLanIp.lanIpv4()` at `DashboardScreen.kt:84`.
- **MIRROR**: ERROR_HANDLING (`RelaisLanIp.kt:43-53`) — same `runCatching` + total fallback.
- **IMPORTS**: `java.net.Inet4Address`, `java.net.Inet6Address`, `java.net.InetAddress`,
  `java.net.NetworkInterface`.
- **GOTCHA**: (i) Do **not** filter out `tun*`/`wg*`/`ts*` — including overlay addresses is the entire
  Tailscale story. (ii) Do exclude IPv6 link-local (`fe80::`): it needs a scope id and is useless in a
  URL. (iii) The sort must be total, or T3's set-equality re-issue check thrashes. (iv) **Dropping the
  `isUp` filter is a re-mint thrash bug, not a cosmetic one**: a down interface contributes a stale
  address, the SAN set differs on the next start, `needsReissue` fires, and — because the *cert*
  changes while the key does not — it churns the cert for no reason on every restart. (v) An empty
  return is the boot-race signal T5 acts on; do not paper over it with a `0.0.0.0` fallback.
- **VALIDATE**: `RelaisCertSanTest` (T3's tests exercise this); `./gradlew testFullOpenDebugUnitTest`.

### T2 — Restructure the auth/rate-limit/body-cap gate ⚠ SECURITY-CRITICAL, LAND ALONE

- **ACTION**: CREATE `RelaisHttpGate.kt` + `RelaisHttpGateTest.kt`; UPDATE `RelaisHttpServer.kt:265-286`.
- **IMPLEMENT**: The gate today lives inside `private fun handle()` and is therefore unreachable from
  any JVM test. **Extract the decision into a pure function first**, then make `handle()` call it:
  ```kotlin
  internal object RelaisHttpGate {
    /** Null = let the request through; otherwise the HTTP status to reject with. */
    fun decide(method: String, path: String, authorized: Boolean,
               rateLimitOk: Boolean, contentLength: Long, maxBody: Long): Int? {
      val authExempt = method == "GET" && (path.startsWith("/health") || path == "/ca.crt")
      if (!authExempt && !authorized) return 401
      if (!rateLimitOk) return 429          // now unconditional (was skipped for /health)
      if (contentLength > maxBody) return 413 // now unconditional
      return null
    }
  }
  ```
  `handle()` keeps ownership of the side effects — calling `authorized(authorization)` and
  `rateLimiter.allow(ip)` — and passes their booleans in, so `decide` stays pure and hermetic.
  Keep every `RelaisError` message and type byte-identical.
- **MIRROR**: HANDLER_PATTERN (`RelaisHttpServer.kt:265-286`) — same error envelopes, same early return;
  NAMING_CONVENTION (`RelaisLanIp.kt:18-22`) for the new `internal object`.
- **IMPORTS**: none new.
- **TEST (RED first — this is the coverage H2 says the task is missing)**: `RelaisHttpGateTest.kt`
  asserts, at minimum: `/ca.crtXYZ` unauthenticated → **401** (the `startsWith` bug); `/ca.crt?x=1`
  unauthenticated → **401** (the query-string bug — the dispatch `when` at `:288-297` matches the
  parsed path, so this is the documented behavior, not an accident); `POST /ca.crt` → **401**;
  `GET /ca.crt` and `GET /health` exempt → null; an authenticated request over the body cap → **413**;
  a rate-limited `/health` → **429** (the behavior change). **Per `relais-prove-tests-red-first`,
  mutate each assertion against the pre-change gate and confirm it goes RED before trusting it** —
  two shipped tests in this repo passed under the bug they claimed to pin. Manual curl is not coverage.
- **GOTCHA**: Today's structure means `/health` is **unmetered**; after this it is rate-limited. That
  is a fix, but a monitoring script polling `/health` hard will start seeing 429s. **Decided (JD,
  2026-09-07): use the existing `rateLimiter.allow(ip)` budget unchanged — `RATE_LIMIT = 30` per
  `RATE_WINDOW_MS = 60_000` (`RelaisHttpServer.kt:87-88`), the same budget every other route already
  gets.** No new constant, no route special-casing — that's what makes the gate uniform. Call this out
  in the PR body and `SECURITY.md`. Note `/health` uses
  `startsWith` while `/ca.crt` must be exact `==` (a `startsWith("/ca.crt")` would exempt
  `/ca.crtXYZ`). (ii) **The 401 deliberately still precedes the rate limiter**, so failed-auth requests
  remain unmetered and brute-force against `/v1/*` stays unlimited — this restructure does **not** fix
  that (see Δ7). Reordering would let an unauthenticated flood consume a legitimate client's per-IP
  budget from the same NAT address; metering failed auth separately is a follow-up, not this task.
  (iii) feature-09 later extends `authorized()` with HTTP Basic and re-signatures `recordRequest`
  — it re-bases onto this gate; this gate is never re-applied on top of #09 (see Cross-plan).
  Land this as its own PR, before T6.
- **VALIDATE**: `RelaisHttpGateTest` (RED-proven) →
  `./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`.
  Manual curl is a **smoke check on top of** that, never instead of it: unauthenticated `/v1/models`
  still 401; `/health` still 200; 31 rapid `/health` hits → 429. Review: `security-reviewer` **and**
  `/codex review` (0/5 finding overlap per `relais-dual-review-disjoint`), re-running codex after fixes.

### T3 — `RelaisCertMint` + `RelaisCertFingerprint` + `RelaisCertInfo`

- **ACTION**: CREATE all three.
- **IMPLEMENT**:
  - `buildSanList(addrs: List<InetAddress>): List<GeneralName>` — always prepend `127.0.0.1` (iPAddress),
    `::1` (iPAddress), `localhost` (dNSName); append `relais-node.local` (dNSName); then T1's addresses
    as `iPAddress`. Dedupe, stable sort, then **cap the final list at 32 — the single cap in the whole
    pipeline** (T1 does not cap). **As-built the fixed set is two, not four** — `::1` and
    `relais-node.local` were both removed as certified-but-unreachable; see assumption A2 and the
    `.local` risk row. The text above is the original spec, kept for the record.
    The fixed entries are prepended *before* the cap and are never
    the ones dropped, so a multi-homed device loses only surplus real addresses, never loopback.
  - `mintCa(): KeyStore` — EC P-256 (`KeyPairGenerator.getInstance("EC")` + `ECGenParameterSpec("secp256r1")`),
    `SHA256withECDSA`, `CN=Relais Node CA (<8 hex chars of the SPKI hash>)`, 10 years,
    `BasicConstraints(0)` critical, `KeyUsage(keyCertSign or cRLSign)` critical. **No NameConstraints — see open question 5, decided
    then reversed.** They were implemented and removed; do not re-add them.
  - `mintLeaf(caKey, caCert, leafPublicKey, sans): X509Certificate` — `SHA256withECDSA` (the **CA's**
    key signs), 90 days, backdated 60s for skew (mirroring `RelaisTls.kt:80`), `BasicConstraints(false)`,
    `KeyUsage(digitalSignature or keyEncipherment)`, `ExtendedKeyUsage(id_kp_serverAuth)`,
    `subjectAlternativeName` non-critical.
  - `needsReissue(leaf: X509Certificate, liveSans: List<GeneralName>, now: Long): Boolean` — true when
    the SAN sets differ **or** `notAfter - now < 15 days`.
  - `RelaisCertFingerprint.spkiSha256Base64(key: PublicKey): String` (→ `sha256/<b64>`) and
    `certSha256Hex(cert: X509Certificate): String` (→ colon-uppercase-hex).
- **MIRROR**: NAMING_CONVENTION (`RelaisLanIp.kt:18-22`); the BouncyCastle builder call at `RelaisTls.kt:83-86`
  (note: `.addExtension` is net-new — the existing cert has no extensions).
- **IMPORTS**: `org.bouncycastle.asn1.x500.X500Name`, `org.bouncycastle.asn1.x509.{BasicConstraints,
  Extension, ExtendedKeyUsage, GeneralName, GeneralNames, KeyPurposeId, KeyUsage, NameConstraints,
  GeneralSubtree}`, `org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}`,
  `org.bouncycastle.operator.jcajce.JcaContentSignerBuilder`, `java.security.*`,
  `java.security.spec.ECGenParameterSpec`, `java.security.cert.X509Certificate`,
  `java.security.MessageDigest`, `java.util.Base64`.
- **GOTCHA**: (i) `GeneralName.iPAddress` must be constructed from the **literal string** (`"192.168.1.40"`),
  which BouncyCastle encodes to raw octets — passing a `dNSName` for an IP silently produces a cert no
  verifier will match. (ii) **No `android.*` imports whatsoever** — T4 depends on it. (iii) The leaf's
  `SHA256withECDSA` is correct even though the leaf key is RSA: the *issuer's* key algorithm picks the
  signature algorithm. (iv) `spkiSha256Base64` hashes `key.encoded` (the DER `SubjectPublicKeyInfo`),
  which is what curl pins — not the raw modulus.
- **VALIDATE**: `RelaisCertSanTest`, `RelaisCertFingerprintTest`, `RelaisCertMintTest`,
  `RelaisCertReissueTest`; `./gradlew testFullOpenDebugUnitTest`.

### T4 — `RelaisTlsHandshakeTest` (the highest-value item in the plan)

- **ACTION**: CREATE `Android/src/app/src/test/java/cc/grepon/relais/RelaisTlsHandshakeTest.kt`.
- **IMPLEMENT**: Mint CA + leaf in-test. Load the leaf + chain into an in-memory `KeyStore`, build an
  `SSLContext` from a `KeyManagerFactory`, bind an `SSLServerSocket` on `127.0.0.1:0`. Client side:
  a `TrustManagerFactory` over a truststore holding **only the CA**, and — critically —
  `sslParameters.endpointIdentificationAlgorithm = "HTTPS"` so hostname verification actually runs.
  Connect with the **`createSocket(host: String, port: Int)` overload passing the literal string
  `"127.0.0.1"`** — never the `InetAddress` overload (see GOTCHA ii). Assert `startHandshake()`
  succeeds. Then three negatives: a SAN-less leaf (today's shape); a leaf from a *different* CA; a
  leaf whose SANs omit `127.0.0.1`. Each negative asserts **both** that it throws **and why** — walk
  the exception's `cause` chain and require a message naming the hostname/SAN mismatch (or, for the
  wrong-CA case, path building). An assertion of bare `SSLHandshakeException` is not enough: a missing
  cipher suite or an expired cert throws the same type and the test would look green for the wrong
  reason.
- **MIRROR**: TEST_STRUCTURE (`RelaisDiscoveryTxtTest.kt:19-52`).
- **IMPORTS**: `javax.net.ssl.{SSLContext, SSLServerSocket, SSLSocket, KeyManagerFactory,
  TrustManagerFactory, SSLHandshakeException}`, `java.security.KeyStore`, `org.junit.Test`,
  `org.junit.Assert.*`.
- **GOTCHA**: (i) **Without `endpointIdentificationAlgorithm = "HTTPS"` the JSSE skips hostname
  verification entirely and the test passes on a SAN-less cert** — it would then verify nothing, which
  is exactly the failure mode `relais-prove-tests-red-first` records (two shipped tests passed under
  the bug they pinned). Run the negative cases FIRST and confirm they are RED. (ii) **Do not use
  `SSLSocketFactory.createSocket(InetAddress, port)`** — the JSSE may reverse-resolve `127.0.0.1` to
  `localhost`, which then matches the `localhost` dNSName the SAN builder always includes, so the
  "must fail" case passes for a reason unrelated to the property under test. (iii) Handshake on a
  background thread or use a `SSLServerSocket` accept in a separate thread — a single-threaded
  accept+connect deadlocks. (iv) The JVM's JSSE is **not** conscrypt; this cannot substitute for
  `CertTrustProbe` (T10).
- **VALIDATE**: `./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`.

### T5 — Rewire `RelaisTls` onto `RelaisCertMint`

- **ACTION**: UPDATE `RelaisTls.kt`; UPDATE `RelaisConfig.kt`.
- **IMPLEMENT**: Add `RelaisConfig.caKeystorePassword(context)` mirroring `:342-350` with a new
  `KEY_CA_PASS`. In `RelaisTls`: `relais_ca.p12` (alias `relais-ca`, CA only, **never** given to a
  `KeyManagerFactory`) and `relais_tls.p12` (alias `relais-tls`, chain `[leaf, ca]`). On
  `buildServerSocket`: load-or-mint the CA; load-or-generate the **leaf key**; call
  `needsReissue(...)` and re-mint the leaf certificate **with the existing key** when true.
  **Invariant (contract for feature-23): the leaf key pair is generated exactly once and is reused
  across every re-mint — only the certificate changes, so the NODE KEY PIN never moves.** Expose
  **two** readers: `fun certInfo(context: Context): RelaisCertInfo` (load-**or-mint**, called only
  from the node start path) and `fun certInfoOrNull(context: Context): RelaisCertInfo?` (load-**only**,
  returns null when no keystore exists — this is the one the UI calls, so opening a settings screen
  can never create key material; see T8). **Boot race:** when `allLanAddresses()` returns empty at
  bind time (the usual state on `BOOT_COMPLETED` — `RelaisBootReceiver.kt:27-30` starts the service
  before DHCP completes, and `RelaisNodeService.kt:190` binds `0.0.0.0:8443` immediately), mint the
  loopback-only cert to get the listener up, **and expose `fun needsLanReissue(context: Context):
  Boolean` plus `fun reissueForLan(context: Context)` (re-mints the leaf with the SAME key) so the
  caller in T5b below can force a re-mint once an interface comes up.**  Without this an auto-started
  appliance serves a cert with no LAN SAN for its entire uptime and every LAN client fails hostname
  verification until a human restarts it — the exact scenario `README.md:149` advertises. Upgrade path: if `relais_tls.p12` exists but has
  no CA in the chain, generate the CA and re-mint — keep the old leaf key if extractable, else
  generate a new one.

  **Codex review (P1, PR #310): `RelaisTls` cannot itself close and recreate the 8443 listener — it
  has no reference to it.** `RelaisNodeService` holds the `httpsServer: RelaisHttpServer?` field
  (`RelaisNodeService.kt:70`) and is the only thing that can call `httpsServer?.stop()`
  (`:234`) and construct a replacement (`:190`). `RelaisHttpServer` itself owns the bound
  `ServerSocket`/accept loop (`RelaisHttpServer.kt:185-195`). A `NetworkCallback` registered inside
  `RelaisTls.buildServerSocket` can re-mint the keystore but has no path to make the *running*
  listener serve the new cert. **Fix, see T5b:** move the `NetworkCallback` registration to
  `RelaisNodeService` (which already owns `httpsServer` and the service lifecycle), not to
  `RelaisTls`. `RelaisTls` only mints; `RelaisNodeService` re-mints-and-restarts.
- **MIRROR**: CONFIG_ACCESSOR_PATTERN (`RelaisConfig.kt:342-350`); LOGGING_PATTERN (`RelaisTls.kt:73`).
- **IMPORTS**: `java.security.cert.X509Certificate` added to the existing set.
- **GOTCHA**: (i) `ks.setKeyEntry(alias, privateKey, pass, chain)` — the chain array must be
  `[leaf, ca]` in that order; a bare `[leaf]` leaves clients unable to build the path. (ii) Migration
  is safe precisely because **nobody trusts today's cert** (everyone uses `-k`) — there is no
  established trust to break. (iii) `RelaisNodeService.kt:189-190` constructs both listeners; the
  loopback one passes `tls = false` and must keep short-circuiting at `RelaisTls.kt:53` before any
  cert work. (iv) Re-minting on a cold start adds latency to `start()` — mint lazily inside the
  existing `buildServerSocket` call at `RelaisHttpServer.kt:191`, not at app init. (v) `needsLanReissue`
  is a pure check callable from `RelaisNodeService`'s callback without touching the listener.
  (vii) **R8**: `build.gradle.kts:146` sets `isMinifyEnabled = true` for release and
  `proguard-rules.pro` has **zero** BouncyCastle rules today (verified: no match). BC resolves its
  provider and `SecureRandom` reflectively, and per `relais-R8-minification-ci-blindspot` every keep
  rule in this repo was earned from a real on-device failure while CI runs no R8 at all. Assume keep
  rules will be needed and prove it on a **release** build, not just `FullOpenDebug`.
- **VALIDATE**: `./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`;
  then T10's `CertTrustProbe` on hardware **on a release (minified) APK**, plus the reboot check in
  Manual Validation.

### T5b — Restart the 8443 listener when the LAN comes up (new, closes codex P1 on T5)

- **ACTION**: UPDATE `RelaisNodeService.kt`. **Depends on T5.**
- **IMPLEMENT**: In the branch that constructs `httpsServer` (`:190`), after `RelaisTls.certInfo`
  mints the initial (possibly loopback-only) cert, check `RelaisTls.needsLanReissue(applicationContext)`;
  if true, register a one-shot `ConnectivityManager.NetworkCallback` on the service's own
  `ConnectivityManager`. On the first `onAvailable` that yields a non-loopback address: call
  `RelaisTls.reissueForLan(applicationContext)` (mints with the same key, per T5's invariant), then
  on the SAME thread that owns `httpsServer` — post to the service's main-thread handler, do not
  call from the callback's own thread — do `httpsServer?.stop(); httpsServer =
  RelaisHttpServer(applicationContext, port = 8443, tls = true, bindAddr = "0.0.0.0").also {
  it.start() }`, mirroring the exact construction at `:190`. Unregister the callback immediately
  after the first successful rebind (in a `finally` around the stop/restart, not just after a
  successful re-mint) so a flapping Wi-Fi link can't re-create the listener repeatedly.
- **MIRROR**: the `httpsServer` construction site itself (`RelaisNodeService.kt:190`) — T5b must
  produce a byte-for-byte-identical `RelaisHttpServer` construction, just later and inside the
  callback.
- **IMPORTS**: `android.net.ConnectivityManager`, `android.net.Network`, `android.net.NetworkRequest`.
- **GOTCHA**: (i) `NetworkCallback.onAvailable` runs on a binder/handler thread, not the service's
  own thread — hopping to the thread that owns `httpsServer` before touching it avoids a data race
  with an in-flight `accept()` on the old socket. (ii) In-flight connections on the old listener are
  dropped by `stop()`; that's acceptable for a listener that's been serving an untrusted loopback-only
  cert to LAN clients anyway — nothing was trusting it. (iii) A second `NetworkCallback` firing after
  the first successful rebind (e.g. two interfaces coming up close together) must be a no-op — guard
  with a single `AtomicBoolean` set before the re-mint, not after, so a slow re-mint can't let two
  callbacks both proceed. (iv) `ACCESS_NETWORK_STATE` is already granted (`AndroidManifest.xml:42`);
  no manifest change needed.
- **VALIDATE**: no JVM test reaches `RelaisNodeService` (it's a `Service`); this is manual-only —
  covered by the reboot-then-LAN-curl acceptance criterion in Notes and Manual Validation step 9.

### T6 — `GET /ca.crt`

- **ACTION**: UPDATE `RelaisHttpServer.kt`. **Depends on T2.**
- **IMPLEMENT**: Add `method == "GET" && path == "/ca.crt" -> handleCaCert(ctx)` to the `when` at
  `:288-297`. Handler: `RelaisMetrics.recordRequest(ctx.endpoint, 200)`, then `respondText(ctx.sock,
  200, pem, "application/x-x509-ca-cert", listOf("Content-Disposition: attachment;
  filename=\"relais-ca.crt\"", "X-Content-Type-Options: nosniff", "Cache-Control: no-store"))`. Add
  `path == "/ca.crt" -> "/ca.crt"` to `endpointLabel` at `:1877-1899`.
- **MIRROR**: HANDLER_PATTERN — `handleHealth` (`:707-712`) for brevity, `handleDashboard` (`:736-746`)
  for the `respondText` + headers shape.
- **IMPORTS**: `java.util.Base64` for PEM encoding.
- **GOTCHA**: (i) Exact `==`, never `startsWith` — see T2. (ii) **Emit the CA, never the leaf, and
  never any private key.** (iii) PEM needs `-----BEGIN CERTIFICATE-----`, 64-char base64 lines,
  `-----END CERTIFICATE-----`, trailing newline — some tools reject unwrapped base64. (iv) Forgetting
  the `endpointLabel` arm silently buckets the route as `"other"` in `/metrics`.
- **VALIDATE**: `curl -sS --cacert /dev/null -k -D- https://<ip>:8443/ca.crt` returns 200 without an
  `Authorization` header; `curl -k https://<ip>:8443/metrics -H "Authorization: Bearer $KEY" | grep ca.crt`.

### T7 — QR encoder

- **ACTION**: CREATE `RelaisQrEncoder.kt`; CREATE `RelaisQrPayloadTest.kt`; possibly UPDATE `build.gradle.kts`.
- **IMPLEMENT**: `buildPayload(baseHost: String, caFingerprint: String): String` →
  `https://<host>:8443/ca.crt#<caFingerprint>`. `encode(payload: String): ImageBitmap` — amber modules
  on charcoal, from `RelaisPalette`. Evaluate `com.google.zxing:core` against a hand-rolled encoder.
- **MIRROR**: NAMING_CONVENTION; TEST_STRUCTURE.
- **IMPORTS**: TBD by the dependency decision.
- **GOTCHA**: (i) `com.google.zxing.*` does **not** match the CI grep
  `Lcom/google/(android/(gms|apps/aicore)|mlkit)` (`.github/workflows/build_android.yaml:79`) — but
  **verify the actual transitive closure, don't take the namespace argument on faith.** (ii) The
  payload is a URL + fingerprint, **not** the DER certificate (research R5). (iii) The test must
  assert capacity against the **real** minted DER byte count, not an assumed one.
- **VALIDATE**: `./gradlew testFullOpenDebugUnitTest`; if a dependency is added,
  `./gradlew :app:assembleDegoogledOpenRelease` + the dex scan at `build_android.yaml:73-86`.

### T8 — Compose: CERTIFICATE section on CONFIGURE + one dashboard link

- **ACTION**: CREATE `RelaisCertSection.kt`, `RelaisCertExport.kt`; UPDATE `RelaisConfigureActivity.kt`,
  `DashboardScreen.kt`, `res/xml/file_paths.xml`.
- **IMPLEMENT**: `@Composable fun CertificateSection()` — `SectionLabel("CERTIFICATE")`, the QR, a
  `CopyableRow("CA FINGERPRINT", …)`, a `CopyableRow("NODE KEY PIN", …)`, an expiry row, a SAN summary
  row, and `ActionLink("EXPORT CA CERT ›")`. **It loads its own state off the main thread and never
  mints:**
  ```kotlin
  var info by remember { mutableStateOf<RelaisCertInfo?>(null) }
  var loaded by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    info = withContext(Dispatchers.IO) { RelaisTls.certInfoOrNull(ctx) } // load-only, never mints
    loaded = true
  }
  ```
  Three render states: **loading** (nothing but the section label — no spinner, DESIGN.md allows only
  the one pulse signature); **never started** (`loaded && info == null`) → a read-only muted line,
  e.g. `no certificate yet · start the node once`, with the QR and EXPORT affordances absent, not
  disabled-looking; **ready** → the full section. Insert it above INTEGRATIONS at
  `RelaisConfigureActivity.kt:249`, preceded by a `Divider()`. `RelaisCertExport.shareCaCert(context)`
  writes `cacheDir/certs/relais-ca.crt` and fires the `ACTION_SEND` chooser. Add
  `<cache-path name="cache_certs" path="certs/"/>` to `file_paths.xml`. On `DashboardScreen.kt`, add
  `ActionLink("CERTIFICATE ›")` after the existing `CONFIGURE ›` and delete `lanIpv4()` at `:428-439`.
- **MIRROR**: CONFIGURE_SECTION_PATTERN (`RelaisConfigureActivity.kt:249-254, 325-331`);
  `ui/common/Utils.kt:375-403` for the FileProvider share.
- **IMPORTS**: `androidx.core.content.FileProvider`, `android.content.Intent`,
  `androidx.compose.ui.graphics.ImageBitmap`, `androidx.compose.runtime.{LaunchedEffect, remember,
  getValue, setValue, mutableStateOf}`, `kotlinx.coroutines.Dispatchers`,
  `kotlinx.coroutines.withContext`, plus the existing palette/Compose set.
- **GOTCHA**: (i) `RelaisConfigureActivity.kt` **is** palette-guarded
  (`RelaisControlPanelPaletteGuardTest.kt:115-118`) — `RelaisPalette` tokens only, zero hex literals,
  and the same applies to the extracted `RelaisCertSection.kt` if it is added to that guard's list.
  (ii) FileProvider authority is `${applicationId}.provider` (`AndroidManifest.xml:124`), which differs
  per flavor — never hardcode it, read `context.packageName`. (iii) Without the `file_paths.xml` entry
  the share throws `IllegalArgumentException: Failed to find configured root`. (iv) DESIGN.md: no new
  color, no new motion; the QR is a **new element type** and needs JD's sign-off. (v) **Never call the
  load-or-mint `certInfo()` from this screen, and never call anything cert-related synchronously in
  composition.** The producer does RSA-2048 keygen plus EC P-256 CA generation — hundreds of
  milliseconds to seconds on cold state, on the main thread, i.e. an ANR risk; and minting from a
  settings screen silently creates key material for a node the user has never started. `certInfoOrNull`
  + `Dispatchers.IO` is the whole mitigation. (vi) Guard the `ActionLink`/QR behind `info != null` —
  exporting a CA that does not exist yet must be impossible, not merely unlikely.
- **VALIDATE**: `./gradlew testFullOpenDebugUnitTest` (palette guard); `./gradlew :app:assembleFullOpenDebug`
  then launch and open CONFIGURE **on a device that has never started the node** (proves the
  never-started state renders and that no keystore file appears afterwards), then again after a start.
  Per `relais-isolation-testing-blindspot`, smoke-launch the real screen and check logcat — this repo
  has twice shipped a screen that every test layer passed and that could not assemble at runtime.

### T9 — Text-only cert panel on `/` + `TLS_NOTE` update

- **ACTION**: UPDATE `RelaisDashboard.kt`, `RelaisClientConfig.kt`, `RelaisHttpServer.kt` (handler wiring).
- **IMPLEMENT**: A fourth `<div class="panel">` in `renderDashboardHtml` after Client Config: CA
  fingerprint, node key pin, expiry, SAN list, and `<a href="/ca.crt">`. Rewrite `TLS_NOTE`
  (`RelaisClientConfig.kt:122-126`) to name `GET /ca.crt` and the CA fingerprint; add `ca_url` and both
  fingerprints to the `tls` block at `:193-198`.
- **MIRROR**: the existing panel structure in `RelaisDashboard.kt:288-309`.
- **IMPORTS**: none new.
- **GOTCHA**: (i) **Do not touch the CSP at `RelaisHttpServer.kt:741`** — no QR here; a data-URI `<img>`
  is blocked by `default-src 'none'` and widening a hardened header to show a picture is a bad trade.
  (ii) Every dynamic value through `escapeHtml` (`RelaisDashboard.kt:143`) — `RelaisDashboardTest`
  pins this. (iii) `RelaisClientConfigTest.kt:227` asserts the note never contains `curl -k`.
- **VALIDATE**: `./gradlew testFullOpenDebugUnitTest`; `curl -k -H "Authorization: Bearer $KEY" https://<ip>:8443/ | grep -i fingerprint`.

### T10 — On-device probes

- **ACTION**: CREATE `CertTrustProbe.kt`, `CertReissueProbe.kt`, `CaKeystoreProbe.kt` in `androidTest`.
- **IMPLEMENT**: `CertTrustProbe` — start `RelaisHttpServer(context, port = 18443, tls = true,
  bindAddr = "127.0.0.1")`, connect an `SSLSocket` trusting only the on-device CA with
  `endpointIdentificationAlgorithm = "HTTPS"`, assert the handshake succeeds and `GET /health` returns
  200. `CertReissueProbe` — capture the SAN set, switch networks, restart, assert new SANs + unchanged
  CA + unchanged leaf public key. `CaKeystoreProbe` — generate an AndroidKeyStore EC key and attempt
  `JcaContentSignerBuilder("SHA256withECDSA").setProvider("AndroidKeyStore").build(privateKey)`;
  log PASS/FAIL. **This probe resolves the §Notes open question on CA key storage.**
- **MIRROR**: PROBE_STRUCTURE (`ClientConfigEndpointProbe.kt:34-68`) — `assumeTrue` gate, `am instrument`
  line in the file header, port ≠ 8443.
- **IMPORTS**: as `ClientConfigEndpointProbe` plus `javax.net.ssl.*`, `java.security.KeyStore`,
  `android.security.keystore.KeyGenParameterSpec` (CaKeystoreProbe only).
- **GOTCHA**: (i) On-device the TLS stack is **conscrypt, not the JVM's JSSE** — this is the only place
  the EC-CA-signs-RSA-leaf combination is actually proven. (ii) `assumeTrue` gate or these run in the
  wrong lane. (iii) Per `.claude/HANDOFF.md`, rango re-locks and adb taps drift on the foldable — these
  probes are pure adb, so they are unaffected, but the device must be unlocked.
- **VALIDATE**:
  ```
  ./gradlew :app:compileFullOpenDebugAndroidTestKotlin
  ./gradlew :app:installFullOpenDebug :app:installFullOpenDebugAndroidTest
  adb shell am instrument -w -e class cc.grepon.relais.CertTrustProbe \
    -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
  adb shell am instrument -w -e class cc.grepon.relais.CaKeystoreProbe \
    -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
  adb shell am instrument -w -e class cc.grepon.relais.CertReissueProbe \
    -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
  adb logcat -d -s RelaisCertTrustProbe:I RelaisCaKeystoreProbe:I
  ```

### T11 — Docs sweep

- **ACTION**: UPDATE 10 docs (11 edits — `README.md` twice); CREATE `docs/tls-trust.md`.
- **IMPLEMENT**: Replace `-k` with `--cacert relais-ca.crt` at `README.md:91` **and separately rewrite
  the prose at `README.md:97-100` ("hence `-k` for now") — two distinct edits in the same file**;
  also
  `Bug_Reporting_Guide.md:26,29`, `Function_Calling_Guide.md:20,59`, `docs/input-content-guide.md:37`,
  `docs/RUNBOOK.md:27`, `docs/soak/soak.sh:44,49`. New `docs/tls-trust.md` with the per-client install
  table. Rewrite `SECURITY.md:40-57` — retire "self-signed, no pinning yet", add the `/ca.crt` route,
  the system-store blast radius, the fetch-over-MITM caveat, and the `/health` rate-limit change. Add
  `/ca.crt` to `docs/openapi.yaml`. **Add a `DESIGN.md` Decisions Log row** for whatever JD approves in
  T8 (match the 2026-07-26 SPEAK row's granularity). Refresh `docs/CODEMAPS/`.
  **The `SECURITY.md` rewrite must express Δ1–Δ9 from Notes → "Threat-model deltas" — including the
  two that are new exposures (Δ5 CA private key, Δ6 unauthenticated `/ca.crt`), Δ8, which says the
  trusted-LAN assumption at `SECURITY.md:30-38` does *not* get softened, and Δ9, that verification is
  client-side opt-in and unenforceable by the node, and Δ10, the pre-auth SAN disclosure (including
  overlay addresses).** `SECURITY.md:45-47` must be **rewritten, not
  deleted**: it currently promises "**Mitigation in progress**: trust-on-first-use pinning with an
  in-app fingerprint and an optional mTLS 'hardened' mode." This feature ships a **per-node CA**, which
  is a different design from TOFU, and mTLS stays deferred (alternative (e)). Deleting the bullet
  wholesale leaves that forward-looking promise pointing at a design nobody is building.
- **MIRROR**: the existing `SECURITY.md` "Known limitations" bullet style.
- **IMPORTS**: N/A.
- **GOTCHA**: (i) `docs/soak/soak.sh` is executable — the flag change must not break the script's
  `-o/-w` argument order. (ii) **Android clients**: user-installed CAs are not trusted by apps since
  Android 7 — say it plainly, it is the most likely support question. (iii) **iOS**: installing the
  profile is not enough; full trust is a separate toggle. (iv) Lead every recommendation with
  per-connection `--cacert`; system-store install is the caveated convenience path.
- **VALIDATE**: `grep -rn "curl -k" --include=*.md --include=*.sh . | grep -v .claude/PRPs` → empty;
  `grep -n "hence .-k. for now" README.md` → empty;
  `./gradlew testFullOpenDebugUnitTest` (`RelaisClientConfigTest.kt:227` already asserts `TLS_NOTE`
  contains no `curl -k`, so that half is guarded — the doc prose is not).

### T12 — OPTIONAL: `spki=` in the mDNS TXT record

- **ACTION**: UPDATE `RelaisClientConfig.kt:100-114`, `RelaisDiscoveryTxtTest.kt`.
- **IMPLEMENT**: Add `"spki" to capTxt(caFingerprint)` to `buildDiscoveryTxt`; thread the value from
  `RelaisDiscovery.buildServiceInfo` (`RelaisDiscovery.kt:54-68`).
- **MIRROR**: the existing `linkedMapOf` at `RelaisClientConfig.kt:106-114`.
- **GOTCHA**: The TXT is a **cleartext, spoofable** LAN broadcast — this is convenience for discovery
  clients, **not** a security control, and must be documented as such. `RelaisDiscoveryTxtTest` pins
  the exact key set, so that assertion changes too. **Low priority; defer freely.**
- **VALIDATE**: `./gradlew testFullOpenDebugUnitTest`.

## Testing Strategy

### Unit Tests

| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| `RelaisHttpGateTest.exactMatchOnly` | `GET /ca.crtXYZ`, unauthorized | **401** (a `startsWith` implementation returns null — mutate to prove RED) | ✅ |
| `RelaisHttpGateTest.queryStringNotExempt` | `GET /ca.crt?x=1`, unauthorized | **401** — documented, not accidental | ✅ |
| `RelaisHttpGateTest.methodMatters` | `POST /ca.crt`, unauthorized | **401** | ✅ |
| `RelaisHttpGateTest.caCertExempt` | `GET /ca.crt`, unauthorized, under caps | `null` (allowed) | — |
| `RelaisHttpGateTest.healthStillExempt` | `GET /health`, unauthorized | `null` | — |
| `RelaisHttpGateTest.healthNowRateLimited` | `GET /health`, `rateLimitOk = false` | **429** — the behavior change | ✅ |
| `RelaisHttpGateTest.bodyCapUnconditional` | authorized, `contentLength > maxBody` | **413** | ✅ |
| `RelaisHttpGateTest.authPrecedesRateLimit` | unauthorized **and** `rateLimitOk = false` | **401**, not 429 — pins the Δ7 carve-out so a later reorder is a deliberate, visible change | ✅ |
| `RelaisCertSanTest.downInterfaceExcluded` | an interface with `isUp = false` | its addresses absent (the re-mint-thrash bug) | ✅ |
| `RelaisCertSanTest.loopbackAlwaysPresent` | `emptyList()` | SANs contain `127.0.0.1`, `::1`, `localhost` | ✅ |
| `RelaisCertSanTest.dedupes` | `["192.168.1.40", "192.168.1.40"]` | one entry | ✅ |
| `RelaisCertSanTest.stableSort` | shuffled 5-address list, twice | identical output both times | ✅ |
| `RelaisCertSanTest.capsAt32` | 40 addresses | exactly 32, loopback trio retained | ✅ |
| `RelaisCertSanTest.excludesIpv6LinkLocal` | `["fe80::1", "2001:db8::1"]` | only `2001:db8::1` | ✅ |
| `RelaisCertSanTest.includesOverlay` | `["100.101.102.103"]` (CGNAT) | present as `iPAddress` | — |
| `RelaisCertSanTest.rfc1918OnlyWhenRestricted` **(gated on open question 8)** | `["192.168.1.40", "100.101.102.103"]`, opt-out on / off | on → CGNAT excluded; off → present. Δ10 is the only new pre-auth disclosure without coverage otherwise — if JD takes the opt-out, this is the test that stops it regressing silently | ✅ |
| `RelaisCertSanTest.ipIsIpAddressType` | `["192.168.1.40"]` | tag is `GeneralName.iPAddress`, not `dNSName` | ✅ |
| `RelaisCertFingerprintTest.knownAnswer` | fixed DER `SubjectPublicKeyInfo` | matches a precomputed `sha256/<b64>` | — |
| `RelaisCertFingerprintTest.caPinDiffersFromNodePin` | minted CA+leaf | **the two values are not equal** | ✅ |
| `RelaisCertFingerprintTest.spkiDiffersFromCertHash` | one cert | SPKI hash ≠ cert hash | ✅ |
| `RelaisCertFingerprintTest.hexFormat` | any cert | `AA:BB:…`, uppercase, 32 groups | — |
| `RelaisCertReissueTest.leafKeySurvivesReissue` | mint, re-mint with a changed SAN set | **the leaf SPKI (NODE KEY PIN) is byte-identical**; the cert DER is not. **Contract for feature-23** | ✅ |
| `RelaisCertMintTest.caIsCa` | `mintCa()` | `BasicConstraints` CA=true, pathLen 0, critical | — |
| `RelaisCertMintTest.leafIsNotCa` | `mintLeaf(...)` | CA=false; EKU = `serverAuth` | — |
| `RelaisCertMintTest.leafChainsToCa` | CA + leaf | `leaf.verify(ca.publicKey)` succeeds | — |
| `RelaisCertMintTest.leafValidity` | `mintLeaf(...)` | ≤ 90d; `notBefore` ≤ now − 30s | ✅ |
| `RelaisCertMintTest.nameConstraintsCoverEveryLeafSan` | CA + leaf | if NameConstraints kept, every leaf SAN is inside a permitted subtree | ✅ |
| `RelaisCertReissueTest.sameSetNoReissue` | identical SAN sets, fresh leaf | `false` | — |
| `RelaisCertReissueTest.changedSetReissues` | SAN set + one address | `true` | — |
| `RelaisCertReissueTest.nearExpiryReissues` | 10 days left, same SANs | `true` | ✅ |
| `RelaisCertReissueTest.leafKeyByteIdentical` | re-mint after IP change | `old.publicKey.encoded contentEquals new.publicKey.encoded` | ✅ **the pin-survival property** |
| `RelaisTlsHandshakeTest.verifiedConnectSucceeds` | CA-only truststore, `HTTPS` endpoint id | handshake OK against `127.0.0.1` | — |
| `RelaisTlsHandshakeTest.sanlessLeafFails` | today's SAN-less cert | `SSLHandshakeException` | ✅ **RED-first** |
| `RelaisTlsHandshakeTest.wrongCaFails` | leaf from another CA | `SSLHandshakeException` | ✅ |
| `RelaisTlsHandshakeTest.missingLoopbackSanFails` | SANs without `127.0.0.1` | `SSLHandshakeException` | ✅ |
| `RelaisQrPayloadTest.payloadShape` | host + CA fingerprint | `https://<h>:8443/ca.crt#sha256/<b64>` | — |
| `RelaisQrPayloadTest.fitsQrVersion` | payload from a **real** minted CA | encodes at ≤ the target version | ✅ |
| `RelaisDashboardTest.certPanelLabelled` | status with both fingerprints | both rendered **with distinguishing labels** | — |
| `RelaisDashboardTest.certPanelEscaped` | fingerprint containing `<script>` | escaped | ✅ |
| `RelaisClientConfigTest.noteNamesCaRoute` | `TLS_NOTE` | contains `/ca.crt`; **no** `curl -k` (`:227`) | — |

### Edge Cases

- [ ] Zero non-loopback interfaces (airplane mode) → loopback-only cert still mints and serves
- [ ] `NetworkInterface.getNetworkInterfaces()` throws → `emptyList()`, not a crash
- [ ] 40 interfaces → capped at 32, loopback trio retained
- [ ] IPv6-only LAN → leaf has IPv6 `iPAddress` SANs and verifies
- [ ] IPv6 link-local only → excluded; falls back to loopback-only
- [ ] Upgrade from an existing single-cert `relais_tls.p12` → CA minted, leaf re-issued, node starts
- [ ] `relais_ca.p12` present but corrupt → re-mint rather than crash the listener
- [ ] Leaf expired while the node ran >90 days → documented gap; re-mints on next start
- [ ] Two rapid `/ca.crt` requests → both 200, second counted by the rate limiter
- [ ] `/ca.crtXYZ` → **401**, not 200 (exact-match exemption)
- [ ] `/health` at 31 req/60s → 429 (the T2 behavior change)
- [ ] Share sheet before `file_paths.xml` is updated → must not ship; verify the `<paths>` entry exists
- [ ] `degoogledOpen` variant → cert section renders, QR encodes, dex scan clean

## Validation Commands

```bash
# --- JVM unit tests (the CI test job). ---
# The Gradle root is Android/src (verified: Android/src/gradlew + Android/src/settings.gradle.kts;
# there is no wrapper at the repo root or at Android/).
cd Android/src
./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest

# --- Compile the probe suite (does NOT run it; probes need hardware, not in CI) ---
./gradlew :app:compileFullOpenDebugAndroidTestKotlin

# --- One debug APK (never `assembleDebug` — ambiguous across dist×policy) ---
./gradlew :app:assembleFullOpenDebug

# --- Only if T7 adds a dependency: prove the degoogled dex stays GMS-free ---
./gradlew :app:assembleDegoogledOpenRelease   # then the dex scan at build_android.yaml:73-86

# --- On-device probes (rango, fullOpen = com.ventouxlabs.relais.izzy) ---
./gradlew :app:installFullOpenDebug :app:installFullOpenDebugAndroidTest
adb shell am instrument -w -e class cc.grepon.relais.CertTrustProbe \
  -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class cc.grepon.relais.CaKeystoreProbe \
  -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class cc.grepon.relais.CertReissueProbe \
  -e RELAIS_PROBE 1 com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s RelaisCertTrustProbe:I RelaisCaKeystoreProbe:I RelaisCertReissueProbe:I
```

`report-worker/` is **not touched** by this feature, so its CI job is not run.

### Manual Validation

```bash
# 1. Fetch the CA without any credential (the whole point of the exemption)
curl -k -sS -D- -o relais-ca.crt https://<phone-ip>:8443/ca.crt
#    -> 200, Content-Type: application/x-x509-ca-cert, Content-Disposition: attachment

# 2. Compare against the in-app CA FINGERPRINT (out-of-band check)
openssl x509 -in relais-ca.crt -pubkey -noout \
  | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64

# 3. THE ACCEPTANCE TEST — no -k anywhere
curl --cacert relais-ca.crt https://<phone-ip>:8443/v1/chat/completions \
  -H "Authorization: Bearer <access-key>" -H "Content-Type: application/json" \
  -d '{"model":"gemma-4-e4b-it","messages":[{"role":"user","content":"reply one word: ping"}]}'

# 4. Loopback via adb forward must also verify (the RUNBOOK path)
adb forward tcp:8443 tcp:8443
curl --cacert relais-ca.crt https://localhost:8443/health

# 5. Pinning instead of trusting (note: the NODE KEY PIN, not the CA fingerprint)
curl --pinnedpubkey "sha256//<node-key-pin>" https://<phone-ip>:8443/health --cacert relais-ca.crt

# 6. Inspect the SANs
openssl s_client -connect <phone-ip>:8443 </dev/null 2>/dev/null \
  | openssl x509 -noout -text | grep -A2 "Subject Alternative Name"

# 7. Exact-match exemption
curl -k -o /dev/null -w '%{http_code}\n' https://<phone-ip>:8443/ca.crtXYZ    # expect 401

# 8. IP-change survival: move the phone to another Wi-Fi, restart the node, re-run step 3
#    with the SAME relais-ca.crt. It must succeed with no re-import.

# 9. BOOT RACE (H3) — the appliance case. No JVM test or probe covers this.
#    Enable auto-start, `adb reboot`, wait for BOOT_COMPLETED, then WITHOUT touching the phone:
adb shell 'logcat -d -s RelaisTls:* | tail -20'   # expect a re-mint + rebind after the first address
curl --cacert relais-ca.crt https://<phone-ip>:8443/health   # must verify, not just answer
#    Re-run with Wi-Fi disabled at boot and enabled 60s later — same expectation.

# 9a. EVERY SAN MUST HAVE SOMETHING LISTENING ON IT — the only check of cert-vs-listener.
openssl s_client -connect <phone-ip>:8443 </dev/null 2>/dev/null \
  | openssl x509 -noout -text | grep -A2 "Subject Alternative Name"
#     then for EVERY address listed:
curl --cacert relais-ca.crt --max-time 5 https://<that-address>:8443/health   # must answer
#     The GET / SAN row reports what the CERT carries and can be correct while nothing serves the
#     address — that is how IPv6 was certified against an IPv4-only listener, invisibly to an
#     IPv4-only hardware run.

# 9b. STOP MUST ACTUALLY STOP (security review H2) — RELEASE-BLOCKING, no test covers it.
#     Run from a SECOND machine. Start the node, then stop it from the app:
curl -k --max-time 5 https://<phone-ip>:8443/health   # after STOP: must FAIL to connect
nmap -Pn -p 8443 <phone-ip>                           # after STOP: 8443 must NOT be open
#     Repeat with a network change between start and stop — a queued NetworkCallback post is what
#     resurrects the listener. A listener still answering here means every user-visible surface
#     (notification, QS tile, control panel, mDNS) says "off" while :8443 serves the LAN, with no
#     in-app way to stop it.

# 10. RELEASE BUILD (M5) — R8 is on (build.gradle.kts:146) and CI runs none of it.
#     Install the release APK on rango, start the node cold (forces a fresh mint), then repeat step 3.
#     A NoClassDefFoundError / NoSuchAlgorithmException from BouncyCastle here means proguard-rules.pro
#     needs keep rules — expected, and invisible to every debug check.
```

## Acceptance Criteria

- [ ] `curl --cacert relais-ca.crt https://<phone-ip>:8443/v1/chat/completions …` succeeds with **no `-k`**
- [ ] The same works for `https://localhost:8443` via `adb forward`
- [ ] After moving the phone to a different network and restarting the node, the **same** `relais-ca.crt` still verifies with no re-import
- [ ] The **NODE KEY PIN** is unchanged across that re-issue, so `--pinnedpubkey` still works
- [ ] **After a reboot with auto-start enabled and no manual restart**, a LAN client verifies with `--cacert` (the boot-race mitigation actually fires)
- [ ] The same works on a **release (minified) APK** — cert mint + LAN handshake, not just `FullOpenDebug`
- [ ] `RelaisHttpGateTest` covers `/ca.crtXYZ`, `/ca.crt?x=1`, `POST /ca.crt`, `/health` exemption, and the now-unconditional rate limit — each assertion **proven RED** against the pre-change gate
- [ ] `RelaisCertReissueTest` asserts the feature-23 invariant: the leaf key pair survives a re-mint, so the NODE KEY PIN is byte-identical before and after
- [ ] Opening CONFIGURE on a device that has **never started the node** creates no keystore file and does not block the main thread
- [ ] `GET /ca.crt` returns 200 with no `Authorization` header, and **is rate-limited**
- [ ] Every other route still returns 401 without a bearer token; `/ca.crtXYZ` returns 401
- [ ] The leaf carries `iPAddress` SANs for every live LAN/overlay address plus `127.0.0.1`, `::1`, and a `localhost` dNSName
- [ ] The CA private key is never emitted by any route, log line, or share
- [ ] CONFIGURE shows the QR, both **distinctly labelled** fingerprints, expiry, and the SAN list
- [ ] Dashboard grows by exactly one `CERTIFICATE ›` line and still fits a 6.1" display without scrolling
- [ ] `GET /` shows a text-only cert panel; **the CSP at `RelaisHttpServer.kt:741` is unchanged**
- [ ] `grep -rn "curl -k" --include=*.md --include=*.sh .` returns nothing outside `.claude/PRPs/`
- [ ] `SECURITY.md` no longer lists "Self-signed TLS cert, no pinning yet", and its "Mitigation in
      progress: trust-on-first-use … optional mTLS" sentence is **rewritten** to describe the shipped
      per-node-CA design (not silently deleted)
- [ ] `SECURITY.md` **gains** a Known-limitations bullet for the node's CA private key and the
      system-store blast radius (Δ5)
- [ ] `SECURITY.md` **gains** a Known-limitations bullet for the unauthenticated `/ca.crt` route and
      the fetch-over-an-already-MITM'd-link hazard (Δ6)
- [ ] `SECURITY.md` states plainly that verification is client-side opt-in and the node cannot
      enforce it (Δ9)
- [ ] All three JVM unit-test variants pass; `compileFullOpenDebugAndroidTestKotlin` succeeds
- [ ] `CertTrustProbe` passes on rango (conscrypt accepts the EC-CA-signed RSA leaf)
- [ ] `degoogledOpen` release dex scan still reports zero GMS classes

## Completion Checklist

- [ ] **Patterns followed** — `internal object` + own file + AGPL header; JUnit 4 with `org.junit.Assert.*`; probes `assumeTrue`-gated with an `am instrument` header line; `RelaisError` envelope for every error path
- [ ] **Error handling** — every `NetworkInterface`/keystore read wrapped with a total fallback; a corrupt keystore re-mints instead of crashing the listener; no silently swallowed exceptions
- [ ] **Logging** — `private const val TAG`; `Log.i` on mint/re-issue with the SAN set and CA fingerprint; **no key material, no keystore password**
- [ ] **Tests** — 29 unit tests across 6 new files; `RelaisTlsHandshakeTest` negatives proven RED **before** the fix; 3 on-device probes
- [ ] **No hardcoded values** — ports from the existing constants; FileProvider authority from `context.packageName`; colors from `RelaisPalette`; validity/cap/threshold as named constants
- [ ] **Docs updated** — `SECURITY.md`, `README.md`, `docs/tls-trust.md`, `docs/RUNBOOK.md`, `Bug_Reporting_Guide.md`, `Function_Calling_Guide.md`, `docs/input-content-guide.md`, `docs/soak/soak.sh`, `docs/openapi.yaml`, `DESIGN.md` Decisions Log, `docs/CODEMAPS/`
- [ ] **No scope additions** — nothing from the NOT Building list crept in (especially mTLS and cert hot-swap)
- [ ] **Self-contained** — no new permission, no new native code; a QR dependency only if T7 justifies it
- [ ] **Independent review** — T2 reviewed by `security-reviewer` **and** `/codex review`, re-run after fixes

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **Boot-start race mints a loopback-only cert for the whole uptime.** `RelaisBootReceiver.kt:27-30` starts the service on `BOOT_COMPLETED`; `RelaisNodeService.kt:190` binds `0.0.0.0:8443` before DHCP completes, so `allLanAddresses()` is empty and the leaf gets no LAN SAN. Re-issue is computed **only at node start**, so every LAN client fails hostname verification until a human restarts — in exactly the unattended appliance mode `README.md:149` advertises | **High** (auto-start is the advertised deployment) | **High** — a working demo and a broken appliance | T5b (in `RelaisNodeService`, which owns `httpsServer`) registers a one-shot `ConnectivityManager.NetworkCallback` and re-mints + stops/reconstructs the 8443 listener on the first non-loopback address (no new permission — `AndroidManifest.xml:42`). Acceptance criterion covers reboot-then-LAN-curl. **Not** covered by any JVM test or probe — this one is manual |
| **R8 strips BouncyCastle's reflective provider lookups in release.** `build.gradle.kts:146` has `isMinifyEnabled = true`; `proguard-rules.pro` has **zero** BC rules today | Medium | **High** — release APK fails to mint or handshake while every debug check is green | Per `relais-R8-minification-ci-blindspot`, CI runs no R8 and every keep rule here was earned from a real on-device failure. Acceptance criterion requires the mint + LAN handshake on a **release** APK, not `FullOpenDebug` |
| **T8 mints a CA on the main thread from a settings screen** — ANR plus key material for a node never started | Medium | Medium | `certInfoOrNull` (load-only) + `LaunchedEffect`/`Dispatchers.IO` + an explicit never-started render state (T8) |
| **Conscrypt rejects the EC-CA-signed RSA leaf on-device**, or serves the chain differently than the JVM | Medium | **High** — the feature does not work at all | `CertTrustProbe` (T10) gates the ship. The JVM test structurally cannot catch this (JSSE ≠ conscrypt). Fallback: an RSA CA, costing QR size but not correctness |
| **T2 introduces an auth bypass** | Low | **Critical** — every route open | Land alone, own PR, `security-reviewer` + `/codex review`. Per `relais-dual-review-disjoint`, 0/5 finding overlap across three rounds — run both, and **re-run codex after fixing** |
| **`RelaisTlsHandshakeTest` passes without actually verifying** (missing `endpointIdentificationAlgorithm`) | Medium | **High** — the one test that matters verifies nothing | Prove all four negatives RED first. `relais-prove-tests-red-first`: two shipped tests in this repo passed under the bug they claimed to pin |
| **NameConstraints reject the loopback SANs**, breaking `adb forward` | Medium | Medium — breaks on a developer's machine, not in CI | T4 asserts loopback with the CA's **real** extension set; or drop NameConstraints (defensible — never the mitigation) |
| Users install the CA system-wide without grasping the blast radius | Medium | Medium — a compromised phone becomes a universal MITM for that client | Lead with `--cacert`; NameConstraints where honored; state it plainly in `docs/tls-trust.md` and `SECURITY.md` |
| Users fetch `/ca.crt` over an already-MITM'd link and skip the fingerprint check | Medium | Medium — **worse than `-k`**, because it feels safe | QR is the primary path; both fingerprints on every surface; `/ca.crt` documented as convenience-only |
| The two fingerprints get conflated (`--pinnedpubkey` with the CA value) | **High** | Low — confusing failure, no security loss | Distinct labels everywhere; `RelaisCertFingerprintTest` asserts they differ; docs name which is which |
| `/health` rate-limiting breaks someone's monitoring | Low | Medium | Decide the exempt-route budget deliberately; call it out in the PR body and `SECURITY.md` |
| Android-client users hit the Android-7 user-CA wall | **High** | Low — support noise | Documented prominently. Expect the question anyway |
| A QR dependency drags in a GMS-tainted transitive | Low | Medium — breaks the degoogled gate | Verify the real closure against `build_android.yaml:73-86`; hand-rolled encoder sidesteps it |
| Multi-interface device (VPN + Wi-Fi + tethering) churns the SAN set and re-mints every start | Low | Low | Stable sort + set equality + the 32 cap; re-mint is cheap |
| A node up >90 days serves an expired leaf | Low | Medium | Re-mint at 15 days remaining, checked on start. **Accepted gap** — a periodic timer is a follow-up |
| ~~`.local` SAN implies a name that does not resolve~~ **HAPPENED** | ~~Low~~ | ~~Low~~ | **Mitigation was false.** "Absent from every UI string" was untrue the moment T6 shipped the certificate panel: `RelaisDashboard.kt` renders the whole SAN set, so the unresolvable name was displayed as covered. Fixed by removing it (A2), not by hiding it |
| QR is a new visual element with no DESIGN.md precedent | Medium | Low | Needs JD's explicit sign-off; amber-on-charcoal, no new color, no motion |

## Notes

### Codex findings disposition (PR #310, 2026-09-07)

- **P1 — fixed.** `codex review --base main` on PR #310 found that T5's `NetworkCallback`
  re-mint-and-rebind was specified inside `RelaisTls`, which has no reference to the running
  8443 listener (`RelaisNodeService` owns `httpsServer`; `RelaisHttpServer` owns the bound socket).
  Split the task: T5 now only exposes `needsLanReissue`/`reissueForLan` (pure mint logic); the new
  **T5b** in `RelaisNodeService.kt` owns the callback registration and the stop/reconstruct of
  `httpsServer`. Files to Change, the Risks table, and the file count (43 → 44) updated.

### Stated assumptions

- **A1.** The Android Tailscale client exposes **no** third-party API for `tailscale cert` — no CLI, no
  documented intent/AIDL surface. Not verified this pass; it collapses option (b) to documentation.
- **A2.** `NsdManager` gives the app no control over the mDNS **host** A record, so the `.local` name
  the device answers to is neither knowable nor stable from app code. **VERIFIED 2026-09-10, and it
  holds.** `NsdServiceInfo` exposes `getHostname()` and no `setHostname()` in android-36 (the
  compileSdk) or android-37, so the host record is the platform's to choose. `serviceName =
  "relais-node"` registers the DNS-SD *service instance* `relais-node._relais._tcp.local`, which is
  a different namespace from the *host* A record `relais-node.local`. On the device, the platform's
  name derived from `device_name` (`portage-e2e-comet`), and `relais-node.local` did not resolve.
  Consequence: `relais-node.local` was **removed** from the fixed SAN set, which is now two entries
  (`127.0.0.1`, `localhost`). The risk row below was accepted on a premise this implementation
  invalidated — see it.
- **A3.** An EC P-256 self-signed CA is ~400-550 bytes DER. **A claim to test, not to trust** — T7's
  capacity assertion runs against the real minted DER.

### Decisions

1. **EC P-256 CA, RSA-2048 leaf.** `RelaisTls.kt:47-51` documents that AndroidKeyStore keys cannot sign
   the TLS handshake through conscrypt; the *leaf* key shape is proven on-device and is not touched.
   The CA key never enters a handshake, so it is free to be EC — which buys a QR-sized CA cert.
2. **Two keystore files.** A CA entry inside `relais_tls.p12` would give the `KeyManagerFactory` at
   `RelaisTls.kt:56` two key entries to choose from. Splitting removes the ambiguity outright.
3. **The leaf key is generated once and reused; only the certificate is re-minted.** This is what keeps
   `--pinnedpubkey` valid across an IP change — without it, option (c) breaks the first time DHCP moves
   the phone.
4. **Re-issue at node start, not on a network callback.** A bound `SSLServerSocket` cannot hot-swap its
   certificate without a re-reading `X509ExtendedKeyManager`.
5. **QR payload is a URL + CA fingerprint, not the certificate.** Scannability, and the fingerprint is
   what makes the subsequent fetch safe.
6. **QR in Compose, never on `/`.** `GET /` has `default-src 'none'` with no `img-src`. Widening a
   hardened security header to display a picture is a bad trade.
7. **CONFIGURE, not the dashboard.** DESIGN.md's "rare setup-time controls" rule and the explicit
   no-scroll budget for the home screen both point the same way.
8. **Docs lead with per-connection `--cacert`.** System-store install is the caveated convenience path.
9. **The gate decision is extracted into a pure `RelaisHttpGate.decide(...)` before it is changed.**
   The current gate is inside `private fun handle()` and unreachable from a JVM test; the most
   security-critical edit in the plan cannot be the one with no automated coverage.
10. **The 401 stays ahead of the rate limiter.** Metering failed auth would let an unauthenticated
    flood consume a legitimate client's per-IP budget from the same NAT address. The consequence —
    brute-force against `/v1/*` stays unmetered — is stated in Δ7 and pinned by
    `RelaisHttpGateTest.authPrecedesRateLimit` rather than left implicit.
11. **The boot-race rebind is in scope, unlike general hot-swap.** Auto-start on boot is the
    advertised deployment (`README.md:149`); a cert with no LAN SAN for the whole uptime is not an
    edge case there, it is the default outcome.
12. **The UI never mints.** `certInfoOrNull` is load-only; `certInfo` (load-or-mint) is reachable only
    from the node start path.

### Threat-model deltas vs `SECURITY.md`

What this feature changes in the document's own terms. T11 rewrites the affected blocks; this is the
delta it must express, and the honest list of what it does *not* fix.

| # | `SECURITY.md` today | After this feature | Direction |
|---|---|---|---|
| Δ1 | `SECURITY.md:42-48` — "Self-signed TLS cert, no pinning yet … clients currently connect with `curl -k`" listed under **Known limitations** | Retired **as a node-side limitation**: verification is now possible and is the documented default; `-k` disappears from every doc (T11). It is not retired for a client that keeps passing `-k` — see Δ9 | **Improvement** — the headline one |
| Δ2 | `SECURITY.md:44-45` — an on-L2 MITM who also spoofs mDNS "could impersonate the node and harvest the key" | For a client that verifies, the MITM must also obtain the node's CA private key, so the API key stops being harvestable by an on-L2 attacker. For a client that does not verify, this sentence stays true verbatim | **Improvement, conditional on the client** |
| Δ3 | `SECURITY.md:49-50` — "mDNS discovery is unauthenticated; pair with cert pinning before trusting discovery" | Still unauthenticated, but the advice is now actionable rather than aspirational: a spoofed advertisement lands on a cert the client refuses. The optional `spki=` TXT (T12) does **not** authenticate mDNS — it is a convenience, and a hostile advertiser controls it | **Improvement, with a caveat that must stay written down** |
| Δ4 | `SECURITY.md:38` — "certificate pinning (below)" named as an untrusted-network mitigation with no mechanism | Two concrete mechanisms, and the docs must name which is which: the **CA fingerprint** (verify the `/ca.crt` you fetched) and the **NODE KEY PIN** (`curl --pinnedpubkey sha256//…`, the leaf SPKI). Conflating them is its own row in Risks | **Improvement** |
| Δ5 | *No current entry* — the node holds one software RSA key | The node now also holds a **CA private key** that signs for its own SANs. Compromise of the phone was already total for the node; it is not newly total for the client, **unless** the user installed the CA system-wide, in which case that client trusts the attacker for those SANs | **New exposure** — must be a new Known-limitations bullet, not omitted |
| Δ6 | *No current entry* — nothing is served unauthenticated except `/health` | `GET /ca.crt` is a second unauthenticated route. It serves only the public CA certificate (no key, no leaf), so the disclosure is nil; the real hazard is a user fetching it over an already-MITM'd link and skipping the fingerprint check — **worse than `-k`, because it feels safe** | **New exposure** — QR stays the primary path; `/ca.crt` documented as convenience-only |
| Δ7 | `SECURITY.md:19-22` — "Bearer-token auth on everything except `/health`" + per-IP rate limiting stated as if independent | More accurate, **with one carve-out that must be written down.** Today the `/health` exemption also skips rate limiting and the body cap (`RelaisHttpServer.kt:265-286`); T2 makes the exemption auth-only, so rate limiting and the body cap now apply to every *authorized-or-exempt* route. **It does not become true of every request:** the 401 still precedes `rateLimiter.allow(ip)`, so failed-auth requests remain unmetered and brute-force against `/v1/*` stays unlimited. That is deliberate (reordering lets an unauthenticated flood eat a legitimate client's per-IP budget behind the same NAT) and is a tracked follow-up, not a win to claim here | **Improvement, partial** — say what it does *not* fix |
| Δ8 | Assumption at `SECURITY.md:30-38` — "designed for a **trusted LAN**" | Unchanged, and the plan does not claim otherwise. Verified TLS raises the floor on a hostile LAN but the overlay recommendation stays: mDNS is still unauthenticated, and the API key is still a bearer token | **No change — resist the temptation to soften this line** |
| Δ9 | *Not stated today, because nothing was verifiable* | **Verification is client-side opt-in, and the node can neither enforce nor observe it.** The node serves the identical certificate to a `--cacert` client and to a `-k` client; removing `-k` from this repo's docs does not remove it from anyone's scripts or client config. Δ1/Δ2/Δ4 hold only for clients that actually imported the CA | **New caveat — must be written down, not assumed** |
| Δ10 | *Not stated today, because the cert carries no SANs at all* | The leaf's **full SAN list becomes readable pre-auth**: anyone who can complete a `ClientHello` against :8443 gets every address in it — no bearer token needed. Because T1 deliberately keeps `tun*`/`wg*`/`ts*` addresses (the Tailscale win), a LAN scanner learns the node's **overlay** addresses too, i.e. the exact recommendation at `README.md:99-100` becomes self-disclosing. Nothing here is secret in the strong sense — it is the node's own reachability — but it is a real reconnaissance delta from today's `CN=relais-node` | **New exposure** — needs a `SECURITY.md` line; an opt-out that restricts SANs to RFC1918 is the mitigation if JD wants one (open question 8) |

Not fixed by this feature, and still Known limitations: bundled analytics, the upgrade-in-place
plaintext key remnant, and API-key rotation (`SECURITY.md:51-57`).

### Alternatives considered

**(a) Per-node CA + short-lived SAN'd leaf — ADOPTED, the spine.**
The only option that survives an IP change without user action. The CA is minted once and never
changes; the leaf churns freely underneath it. Cost: it hands the user a real CA, which is the source
of the system-store blast-radius risk above.

**(c) Fingerprint + pinning helpers — ADOPTED, as part of (a), not as an alternative.**
Considered as a standalone: print the SPKI and let users `--pinnedpubkey`. Rejected as a *substitute*
because pinning alone leaves `--cacert` impossible (no SANs) and gives no path for tools that only
accept a CA file. Adopted as an *ingredient* because (a) has a bootstrap hole — fetching the CA over
the connection you do not yet trust is circular — and an out-of-band fingerprint is exactly what closes
it. The QR is the trustworthy channel; `/ca.crt` is convenience.

**(b) Tailscale / overlay real certs — REJECTED as an integration, kept as documentation.**
Per A1 there is nothing to call. What *is* real: an overlay interface's address appears in
`NetworkInterface.getNetworkInterfaces()` like any other, so **including overlay addresses in the SAN
set** gives overlay users a verifying connection with zero extra machinery. That is a two-line win
inside (a). `SECURITY.md:32-38` and `README.md:100` already recommend the overlay; the addition is
"and the node's cert covers the overlay address too."

**(d) Skip the CA; add SANs to the existing self-signed leaf and have clients `--cacert leaf.pem` —
REJECTED.** Genuinely simpler, and it avoids handing the user a CA at all (removing the largest new
risk in this plan). Rejected on the IP-change requirement: every address change invalidates the pinned
file and forces a re-import. The CA exists precisely to absorb that. **Worth revisiting if JD decides
the system-store blast radius outweighs IP-change convenience** — the SAN work (T1/T3/T4) is shared, so
this remains a cheap pivot right up until T5.

**(e) mTLS "hardened mode" — DEFERRED.** Floated in `SECURITY.md:46-47`. Orthogonal: it authenticates
the *client*, this authenticates the *server*. Would compose cleanly on top of the CA built here.

### Open questions for JD

1. **Can an AndroidKeyStore EC key back `JcaContentSignerBuilder` for CA signing?** The documented
   conscrypt limitation (`RelaisTls.kt:47-51`) is specifically about signing the TLS *handshake*; the CA
   key only does occasional JCA signing. If it works, the CA private key never exists as a file.
   **Resolve with `CaKeystoreProbe` (T10), not by reasoning** — extrapolating the leaf constraint to the
   CA key is exactly the failure shape recorded in `relais-claim-stronger-than-code`.
2. ~~`/health` becoming rate-limited (T2)~~ **DECIDED (JD, 2026-09-07): yes, use the existing 30/60s
   budget unchanged (`RATE_LIMIT`/`RATE_WINDOW_MS`, `RelaisHttpServer.kt:87-88`) — no new constant.**
   Decision 10's unmetered-failed-auth trade-off is accepted as documented; no separate brute-force
   meter scheduled for now.
3. **Is a QR an acceptable new element type at all?** DESIGN.md already settles *placement* (CONFIGURE);
   this is narrowly about the element. Approve, or is share-sheet + fingerprint text enough for v1?
4. **`com.google.zxing:core` vs a ~200-line hand-rolled encoder** (T7) — dependency footprint and
   GMS-gate confidence vs code to own.
5. ~~Keep NameConstraints or drop them?~~ **DECIDED (JD, 2026-09-07): keep them, scoped to the node's
   own SAN set. REVERSED (JD, 2026-09-08): drop them entirely.** The reversal was driven by evidence
   found while implementing, not by a change of mind:
   - **Inert where it mattered most.** Java's PKIX validator cannot enforce a trust anchor's own name
     constraints (it reads them from the `TrustAnchor` object, never the anchor certificate, and
     refuses them if supplied); BouncyCastle's accepts the parameter and ignores it — measured, it
     validated a leaf for `8.8.8.8` under a CA that prohibited it. Every Java/Android/JSSE client got
     zero benefit.
   - **Actively harmful on the documented path.** `allLanAddresses()` admits globally routable IPv4,
     so on a cellular hotspot or a public-IP ISP the leaf carries a prohibited SAN and
     `curl --cacert` — the flow SECURITY.md recommends — rejects the entire chain.
   - **Critical means fail-closed for unknown verifiers** (RFC 5280 requires criticality; a verifier
     that processes but does not understand it must reject).
   - **Untestable in CI**, as a consequence of the first point. An early version of the tests passed
     `TrustAnchor(ca, null)`, which applies no constraints, so three tests were green while asserting
     nothing.
   The benefit was partial regardless: constraints never limited issuance for LAN addresses, which is
   where an attacker on the LAN already sits. `RelaisCertMintTest` now pins the **absence** of the
   extension so it is not re-added as a presumed oversight. The loopback/cross-range concern from
   research R3 is retained as a test of the CA/leaf split itself (`a CA minted on one network still
   validates a leaf minted on another`), which is what acceptance criterion 3 rests on.
6. **Document the system-store CA install at all, or `--cacert`-only?** Documenting it is more useful
   and more dangerous.
7. **Is A1 right — does Android Tailscale really expose no cert issuance to third-party apps?** If it
   somehow does, (b) is worth revisiting for a genuinely publicly-trusted cert.
8. ~~Restrict SANs to RFC1918, or keep overlay addresses in the cert (Δ10)?~~ **DECIDED (JD,
   2026-09-07): keep all LAN addresses, no opt-out.** An attacker already on the LAN sees the node's
   IP the moment it completes a `ClientHello` to scan it; the SAN list doesn't meaningfully add to
   what's already visible. This preserves the Tailscale story and keeps option (b) demoted. Document
   the disclosure in Δ10 and `SECURITY.md`; no config surface needed.
### Critic review disposition (`critic-18.md`, 0 CRITICAL · 4 HIGH · 6 MEDIUM · 3 LOW)

Fixed: H1 (Cross-plan & Dependencies section, rebased onto the "all of #18 before #09" decision —
#09 cut the `handleDashboard` move, so T6/T9 targets stand, and the `authorized()`/`recordRequest`
drift is noted as landing *after* T2), H2 (T2 extracts `RelaisHttpGate.decide` + `RelaisHttpGateTest`,
RED-first), H3 (Risk row + T5 `NetworkCallback` rebind + acceptance criterion + manual step 9),
H4 (T8 `certInfoOrNull` + `LaunchedEffect`/`Dispatchers.IO` + never-started state), M1 (Δ10 + open
question 8), M2 (Δ7 rewritten with the carve-out, Decision 10, and a test that pins it), M3 (`isUp`
filter restored in T1; the 32-cap now applies once, in T3, after the fixed entries), M4 (counts
recomputed to 42; `RelaisDashboardTest`/`RelaisClientConfigTest`/`RelaisDiscoveryTxtTest`/
`docs/CODEMAPS/`/`RelaisMetrics.kt` added; `docs/tls-trust.md` no longer double-counted; the second
`README.md` edit at `:97-100` called out), M5 (release-build Risk + acceptance criterion + manual
step 10 + the T5 R8 gotcha), M6 (T4 requires the `createSocket(String, Int)` overload and asserts on
the failure cause), L2 (`respondText` is `:1937-1943`; `:1933` is `respond`), L3 (`RelaisMetrics.kt`
`endpointLabel` at `:241-258` added to Mandatory Reading and Files to Change).

**Declined: L1** — the report puts `ActionLink` at `RelaisConfigureActivity.kt:324-331` with "KDoc at
`:324`". Verified: `:324` is blank, the KDoc is `:325`, and the function body runs `:327-331`. The
plan's existing `325-331` is correct as written.

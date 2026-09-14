# Issue 320: Bind dual-stack and restore IPv6 SANs

## Goal

Make the HTTPS node reachable through both IPv4 and IPv6 while ensuring that every
address the node certificate advertises is actually served. Restore IPv6 addresses,
including `::1`, to the leaf certificate only with that dual-stack listener support.

## Approach

- Replace the single HTTPS listener ownership in `RelaisNodeService` with an atomic
  dual-listener lifecycle: bind `0.0.0.0:8443` and `::`:8443, publish success only
  when both are live, and stop/clear both together on every failure and teardown path.
- Extend LAN-address discovery and certificate SAN construction to retain valid IPv6
  addresses and the fixed IPv6 loopback SAN, preserving deterministic ordering and
  the existing SAN cap.
- Cover IPv6 SAN construction and dual-listener lifecycle cleanup in the JVM test
  suite. The production Service itself remains outside JVM reach.
- Update the architecture/backend codemaps and the user-facing README and SECURITY
  documentation to describe the dual-stack endpoint and its validation procedure.
- Verify on a device by checking every advertised IPv4 and IPv6 certificate SAN with
  a trusted HTTPS request; this is a release gate because Android socket behavior
  varies across versions and `java.net.preferIPv4Stack` configurations.

## Files and areas

- `Android/src/app/src/main/java/cc/grepon/relais/RelaisNodeService.kt`
- `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt`
- `Android/src/app/src/main/java/cc/grepon/relais/RelaisLanIp.kt`
- `Android/src/app/src/main/java/cc/grepon/relais/RelaisCertMint.kt`
- Android unit and instrumentation/manual probes for listener and certificate checks
- `docs/CODEMAPS/architecture.md`, `docs/CODEMAPS/backend.md`, `README.md`, and
  `SECURITY.md`

## Out of scope

- Automatic certificate reissue/rebind after DHCP or network transitions (#321).
- Changes to plaintext HTTP exposure: it remains loopback-only.
- Fallbacks that certify an address when the matching listener could not bind.

## Risks and verification

The principal risk is platform-dependent IPv6/dual-stack socket behavior. A failed
dual bind must leave no listener live or reported healthy. CI proves SAN generation
and failure cleanup; device verification proves that each SAN is actually reachable
and hostname-valid over HTTPS.

# Issue #315 — Correct stale metrics KDoc reference

## Goal

Remove the nonexistent `RelaisMetricsLeakTest` reference from the label-hygiene KDoc on
`RelaisMetrics`.

## Evidence and decision

`RelaisMetricsIncrementsTest` exists and tests the normalized endpoint-label whitelist, but it does
not test the broader claim that metrics never expose model paths, credentials, or IP addresses.
Relinking to it would make the KDoc misleading. The obsolete test reference will therefore be
removed; this documentation-only correction does not add or change runtime behavior.

## Scope

- Update the KDoc in `Android/src/app/src/main/java/cc/grepon/relais/RelaisMetrics.kt`.
- Do not change metric behavior or duplicate an unrelated test.

## Validation

- Confirm `RelaisMetricsLeakTest` no longer appears in the repository.
- Confirm `RelaisMetricsIncrementsTest` remains the existing endpoint-label coverage.
- Run `git diff --check`.

## TDD note

This has no executable behavior change; a red/green test cycle would not be meaningful. The
existing test was inspected before changing the KDoc to avoid the duplicate-test failure mode named
by the issue.

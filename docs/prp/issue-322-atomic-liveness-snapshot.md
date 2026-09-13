# Issue #322 — Atomic node-liveness snapshot

## Goal

Replace separately-read listener and startup flags with one immutable liveness snapshot, so every
surface renders a state that actually existed rather than a torn cross-thread composite.

## Approach

- Replace `RelaisListenerState.listenersUp` and the lifecycle use of
  `RelaisEngine.startupInProgress` with a process-wide immutable snapshot containing both values.
- Publish each transition as one volatile snapshot replacement; writer helpers preserve the other
  component so listener changes and startup changes cannot overwrite one another.
- Migrate all readers to read one snapshot before deriving a state: the control panel, node
  controller/tile, watchdog, dashboard, experiments surface, and model-switch polling.
- Migrate every existing lifecycle writer: `RelaisNodeService` startup/listener transitions and
  `RelaisEngine` idle-reload/model-swap transitions.
- Add JVM coverage for the healthy `STARTING -> LIVE` publication sequence and for the false
  `listenersUp=false, startupInProgress=false` composite that the new publication model excludes.

## Files / areas

- Liveness-state holder (replacing `RelaisListenerState.kt`)
- `RelaisNodeService.kt` and `RelaisEngine.kt` lifecycle publications
- `RelaisShellViewModel.kt`, `core/RelaisNodeController.kt`, `RelaisWatchdog.kt`,
  `RelaisHttpServer.kt`, and `ModelSwitch.kt` readers
- JVM tests for liveness publication and affected state assemblers

## Out of scope

- Changing listener binding, startup/retry policy, watchdog timing, or UI copy.
- Changing idle-reload or model-swap behavior beyond publishing their existing transitions through
  the shared snapshot.

## Risks

- Current main has idle-reload and model-swap writers beyond the service described in the issue;
  omitting either would make their status stale or reintroduce a separate signal.
- The new publisher must preserve the counterpart field under concurrent lifecycle changes, not
  merely wrap two independent volatile reads in an accessor.

## Verification

- Run the three-flavor Android JVM unit-test lane.
- Independently review the final diff and resolve every confirmed finding.

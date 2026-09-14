# Issue #321: boot-race LAN rebind

## Goal

Restore the unattended-startup path: if Relais starts before DHCP assigns a LAN address, it must
reissue its leaf certificate and rebuild the HTTPS listeners once a stable LAN address set exists.
The CA and leaf key must remain stable.

## Approach

* Give `RelaisNodeService` a lifecycle-owned, serial LAN-rebind controller. Network callbacks only
  report observations; listener construction remains exclusively in `startHttpsListeners()`.
* Observe active-network link-property changes, coalesce them, and act only after a non-empty,
  non-link-local address snapshot remains unchanged for 15 seconds. A new stable snapshot is a
  genuine change; a snapshot that returns to the currently certified addresses is a no-op.
* On a confirmed change, mint/load one TLS snapshot, stop and rebuild the listener group through
  its existing owner, then republish liveness. Failed rebinding leaves no stale listener reference
  and remains recoverable by the existing startup path.
* On service teardown, unregister the callback, invalidate queued work, and join the controller
  before closing listeners. No queued callback may create a listener after `onDestroy`.
* Cover pure snapshot/debounce/lifecycle decisions in JVM tests. Extend the hardware probe and
  operating documentation for cross-network TLS, stable CA/SPKI identity, and stop-must-stop
  verification from a second machine.

## Acceptance criteria

* A boot-time loopback-only certificate is replaced after a stable LAN address appears, and the
  HTTPS listener serves the new address.
* Brief loss or churn does not reissue a usable certificate; a sustained network move does.
* CA fingerprint and leaf SPKI remain unchanged across reissue.
* Stop wins every race: port 8443 is closed and cannot be resurrected by queued rebind work.
* JVM CI passes and the documented physical-device checks are run, including a network change and
  independent-machine listener/stop checks.

## Out of scope

* Changes to plaintext loopback HTTP, certificate trust policy, or unrelated service lifecycle
  behavior.
* Merging PR #328 or this PR.

## Risks

This was deliberately removed after repeated concurrency defects. The serial controller, stable
observation window, and teardown join are correctness requirements, not optimizations. The final
gate is device and second-machine verification because a JVM test cannot instantiate Android's
`Service` and real network callbacks.

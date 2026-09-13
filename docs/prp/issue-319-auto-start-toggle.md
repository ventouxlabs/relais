# Issue #319 — Restore opt-in boot auto-start

## Goal

Restore the documented opt-in boot auto-start capability by exposing the existing
`RelaisConfig.autoStartEnabled` preference on the CONFIGURE surface.

## Approach

- Add an `AUTO-START ON BOOT` row to the CONFIGURE screen, reusing its existing full-row
  `ToggleRow` affordance and design-system typography.
- Initialize the row from `RelaisConfig.autoStartEnabled(context)` and persist every toggle through
  `RelaisConfig.setAutoStart(context, enabled)`.
- Cover the preference's default-off and enable/disable round trip in the JVM test suite.
- Audit production callers of configuration setters while implementing; record, but do not expand
  this PR to fix, unrelated write-only settings.

## Audit result

A production-source audit found these configuration writers without call sites: image-model
selection/override (`setImageModelId`, `setImageModelUrl`, `setImageModelSha`),
`setTtsVoiceId`, `setWebhookAllowlist`, the three thermal-governor thresholds,
`setIdleTtlMinutes`, `setTileCannedTemplateId`, `setShareSystemPrompt`, and the four session
memory settings. They remain out of scope for this focused auto-start change. Idle TTL is already
tracked by #22; this PR does not assert tracking coverage for the remaining settings.

## Acceptance criteria

- The CONFIGURE screen displays `AUTO-START ON BOOT` with its current `on`/`off` value.
- Tapping the row immediately persists and displays the inverse value.
- A fresh installation remains opt-out by default.
- `RelaisBootReceiver` consequently receives a preference an operator can actually enable.

## Out of scope

- Changes to `RelaisBootReceiver`, boot permissions, or boot-time service behavior.
- The boot-time LAN certificate rebind deferred in #321.
- Idle-TTL configuration, tracked separately by feature #22.

## Verification

- Run the three-flavor JVM unit-test lane.
- Review the final diff independently and resolve every confirmed finding.

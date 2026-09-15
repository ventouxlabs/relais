# Issue #312 — List only provisioned chat models

## Decision

`GET /v1/models` is provisioned-only. Every returned chat-model id must be accepted by the
per-request model router as locally provisioned, so clients cannot select an advertised id and
immediately receive `404 model_not_found`.

## Scope

- Make the pure `buildModelsResponse` helper retain curated refs only when their ids are in the
  read-pruned on-disk registry supplied by `handleModels`.
- When the curated catalog is unavailable, emit the fallback id only if it is provisioned.
- Return an empty OpenAI list when the node has no provisioned chat model; do not invent an
  unserviceable fallback entry.
- Update the OpenAPI, model-card, and backend codemap descriptions to say that this endpoint lists
  usable local models, not the downloadable catalog.

## TDD plan

1. Add failing JVM coverage proving that a mixed curated list emits only the provisioned ids, all
   marked `provisioned: true`.
2. Add failing JVM coverage for offline fallback: it is emitted only when provisioned; otherwise
   the list is empty.
3. Update the pure helper, run the focused JVM test red then green, and run the required full
   three-flavor JVM suite before handoff.

## Non-goals

- Do not change model downloading, model-swap routing, the provisioning registry, or optional
  embedding/image/TTS feature discovery.
- Do not expose model file paths or add a download catalog endpoint.

## Acceptance checks

- Every id in a curated response belongs to `provisionedIds`.
- Offline responses never advertise an unprovisioned fallback.
- Existing stable OpenAI fields (`id`, `object`, `owned_by`, `created`) remain intact.
- OpenAPI describes the new client-visible contract.

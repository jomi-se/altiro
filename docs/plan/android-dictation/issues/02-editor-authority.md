# 02 — Accessibility editor authority and fake insertion

Status: ready-for-agent
Blocked by: 01

## Authority

[Specification](../spec.md), sections 6 and 8; [build order](../../vertical-slice-build-order.md).

## Deliverable

Use a fake recognizer returning `Dictation test: café, mañana, Kubernetes.`
Implement the API 33 accessibility InputMethod and three-argument,
void-returning `commitText`. Track editor/service epochs, revisions, selection,
composition, password state, and per-app disable. No model integration.

## Acceptance evidence

- Real cursor insertion and selection replacement work in fixture editors.
- Restart, switch, cursor movement away/back, same-length edits, composition,
  reconnect, ambiguous identity, and passwords block stale insertion.
- One consumed dispatch attempt; no retry or whole-field replacement fallback.
- Explicit Insert creates fresh authority; Copy is explicit.
- Unit/API checks and emulator/device evidence are recorded separately.
- Gate A remains unverified until the physical overlay/microphone experiment.

## Comments

No execution evidence recorded yet.

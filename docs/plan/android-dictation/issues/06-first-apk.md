# 06 — Model manager, onboarding, recovery, and first APK

Status: ready-for-agent
Blocked by: 05

## Authority

[Specification](../spec.md), sections 6, 11, and 14–17; [build order](../../vertical-slice-build-order.md).

## Deliverable

Complete Gate C: verified model download/import/delete, onboarding, language,
per-app disable, result actions, ephemeral cleanup, and visible recovery.
Produce a labeled debug APK unless secure signing material is separately supplied.

## Acceptance evidence

- New-install flow works without account, quota, subscription, or mandatory server.
- Installed model enables airplane-mode dictation; offline import supported.
- Corrupt/cancelled acquisition, busy deletion, permissions, missing model,
  unsupported editor, and invalid destination have recoverable outcomes.
- Two-minute recording and disclosed five-minute limit exercised.
- First-release checklist, device matrix, and known limitations have actual status.
- Dependency/model license inventory and native packaging checks included.
- Building does not publish a release or submit to a store.

## Comments

The offline preview implements verified file-picker model import, deletion
when idle, explicit language selection, progress/cancellation, and browser
download handoff without an Internet permission. The phone acceptance matrix
and release gate remain outstanding. These source additions do not mark this
ticket done.

No execution evidence recorded yet.

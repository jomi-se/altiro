# 06 — Model manager, onboarding, recovery, and first APK

Status: in-progress
Acceptance dependency: Gate A and B device evidence

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

The everyday preview implements verified one-tap stock model downloads with
Internet permission limited to explicit acquisition, verified file-picker
import, idle deletion, Setup, language selection, result actions and Console.
Chilean profiles remain import-only until compatible public binaries exist.
Foreground-start recovery, visible five-minute recording warnings and a
content-free interruption notice are implemented. See
[current work](../../current-work.md) and the
[physical interface matrix](../../../testing/everyday-interface.md).

Host compilation, JVM acquisition/session tests, lint and packaged native
16 KiB alignment have passed for the everyday interface. Instrumentation is
compiled; framework execution, rendered native review, the full physical-phone
matrix and release acceptance remain outstanding. This ticket is not done.

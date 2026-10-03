# 04 — Serialized session and ephemeral audio lifecycle

Status: in-progress
Blocked by: 03

## Authority

[Specification](../spec.md), sections 9 and 10.1; [build order](../../vertical-slice-build-order.md).

## Deliverable

One coordinator/reducer owns the active session and generations. Capture
validated 16 kHz mono PCM16 in owned private temporary storage; finalize its
WAV header. Separate Stop/Cancel, reject stale completions, and sweep abandoned
audio at startup.

## Acceptance evidence

- Double Start/Stop/Cancel, cancel-versus-result, and next-session races covered.
- Capture stops before recognition; cancellation invalidates the generation first.
- Partial reads, empty/failed capture, disk-full, and sample-rate errors handled.
- WAV header/frame count agree; no samples relabeled at a different rate.
- Files are not deleted while readers still hold them.
- No content in Intents, logs, backup, clipboard reading, or default history.

## Comments

Source implemented in the integration spike. Core behavior tests and compiled
Android APIs support source correctness. Physical/editor/lifecycle evidence
remains unverified; see [Gate A](../../../testing/gate-a.md).

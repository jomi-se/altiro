# 08 — Measured performance and quality refinement

Status: ready-for-agent
Blocked by: 07

## Authority

[Specification](../spec.md), sections 10, 16, and Gate E; [build order](../../vertical-slice-build-order.md).

## Deliverable

Use the speech corpus and latency/memory measurements to select justified
improvements: residency, quantization, VAD/chunking, headset routing, or a
separately scoped local cleanup experiment. No unmeasured platform expansion.

## Acceptance evidence

- Comparable before/after runtime/model/settings/device data for each improvement.
- Recognition and cleanup quality measured separately, including intent preservation.
- Added chunking handles boundaries, repetition, missing/reordered segments,
  language switches, and corrections; no global text deduplication.
- Cancellation, memory, thermal behavior, and destination guards remain correct.
- Only sanitized conclusions enter Git; raw recordings and output remain external.
- No invented benchmarks or unverified all-editor/device claim.

## Comments

No execution evidence recorded yet.

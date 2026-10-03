# 05 — Pinned local Whisper inference

Status: ready-for-agent
Blocked by: 04

## Authority

[Specification](../spec.md), sections 10–11; [build order](../../vertical-slice-build-order.md).

## Deliverable

Integrate an exact `whisper.cpp` revision and one verified multilingual model.
CPU/arm64 first, serialized native ownership, structured progress, and real
native cancellation. No chunk optimization or local cleanup model yet.

## Acceptance evidence

- Runtime notices and real model source/revision/size/SHA-256/license recorded.
- Airplane-mode 10/30/120-second samples in English/French/Spanish tested.
- Native cancellation finishes before context unload; stale results cannot insert.
- Long audio is not truncated at the 30-second model window.
- Cold/warm latency and memory measured on physical hardware.
- Model recommendation and Gate B status follow evidence.
- Bundled native libraries checked for 16 KiB page-size support.

## Comments

No execution evidence recorded yet.

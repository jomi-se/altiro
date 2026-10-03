# 05 — Pinned local Whisper inference

Status: in-progress
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

Pinned runtime/model manifest, CPU JNI wrapper, native cancellation, full-WAV
processing, explicit language selection, and verified offline file import are
implemented. Initial Gate A recording/cancel/insertion are user-reported;
remaining editor/lifecycle evidence is still required. See
[the continuation decision](../../../decisions/0001-offline-preview.md).

Host JNI recognition and >30-second audio have been exercised with the actual
base model and upstream public speech sample. Android compilation/native
packaging, strengthened decoding cancellation, and phone language/quality
evidence are recorded in [current work](../../current-work.md). Gate B is not
passed; no phone latency or Chilean Spanish quality score is claimed.

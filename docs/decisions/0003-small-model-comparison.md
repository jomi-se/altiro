# 0003 — Small model selection and same-recording comparison

Status: accepted. Date: 2026-10-03.

## Context

The operator requests Small Q8_0 and FP16, easy in-app selection and live phone
comparison, reproducible preparation, and a Chilean Whisper fine-tune. Pixel 7
is the reference device. General quantization evidence supports Q8 as a first
candidate, but does not establish its phone latency or Chilean speech accuracy.

## Decision

Expand the allowlist to stock multilingual Small Q8_0 and FP16, the existing
Base profile, and experimental ES-CL-2 Small Q8_0 and FP16. Pin actual artifact
sizes, hashes, source revisions, notices, and conversion inputs. Keep models
outside Git and the APK, with separate durable private import slots. New installs
select Small Q8; upgrades preserve the installed Base and saved selection.

Settings own the model picker. Everyday floating dictation uses that selection;
no picker is added to the overlay. Model/language settings are snapshotted at
recording start and cannot change during recording, import, or native work.

An explicit in-app comparison processes one temporary recording through stock
Q8 and FP16, optionally adding either Chilean variant, sequentially. Release one
native context before loading the next. Show each transcript and its elapsed
time, including verification and cold loading. Comparison creates no insertion
payload; Copy is explicit per result. Results expire after ten minutes and are
cleared by a new recording or process loss. Audio is deleted after all models
finish, any failure, or cancellation; no replay/history is added.

Preparation is CPU-only and uses pinned upstream conversion/quantization tools.
Audit converted FP16 tensors against source safetensors. Reproducing stock Q8
must match the published hash. Host sample recognition establishes runtime
compatibility, not Spanish accuracy or Pixel performance. The source model's
Apache-2.0 declaration and upstream MIT notices are retained; redistribution
provenance review remains part of release qualification.

## Consequences and evidence

The one-model preview limit is superseded within this explicit comparison scope.
Language remains an independent setting; use ES consistently for Spanish tests.
Four simultaneous resident Small contexts are avoided. Sequential timing can be
biased by run order, CPU temperature, and background work; repeat measurements
before interpreting a small difference. Full Gate A/B remain unverified.

See the [model catalog](../../inference-whisper/src/main/assets/whisper-models.json),
[preparation procedure](../development.md),
[Chilean research](../research/chilean-spanish.md), and
[phone comparison procedure](../testing/model-comparison.md).

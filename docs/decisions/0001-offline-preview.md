# 0001 — Offline preview after the initial phone smoke test

Accepted: 2026-10-03.

The recording-route default below is superseded by
[0002 — Record in place by default](0002-record-in-place-default.md).

The operator reports that the integration APK inserts its fixed phrase and
shows microphone capture that stops on Cancel. The operator prioritizes real
local recognition and Chilean Spanish quality next; visual polish is deferred.
This is initial user-reported smoke evidence, not full Gate A acceptance.
Composition, changed-target, password, lock, service-disable, OS/build metadata,
and the complete editor matrix remain to be recorded.

Continue the runnable source increment with pinned multilingual Whisper base
and verified file import while keeping the visible recording-screen route,
explicit Insert fallback, and existing editor guards. Do not claim Gate A,
Gate B, or release readiness. Device tests remain required before recommending
the model or preferring direct foreground-service startup.

Keep the app without Internet permission. Model acquisition occurs in a browser
after an explicit action; Storage Access Framework import copies and verifies
the supported artifact before native loading. Recheck the artifact before
every inference. Unknown models are rejected.

The first inference increment cold-loads on each run and releases its context
before completion. This bounds idle native memory and simplifies lifecycle
verification. Warm residency is deferred until device timing/memory evidence
justifies it. Cancellation reaches the native abort/encoder callbacks; upstream
model initialization is synchronous, so a cancelled cold load must finish
before its context can be safely released. Prevent another native session or
model mutation until that release finishes.

After microphone release, the existing foreground service switches to
`dataSync` for local WAV-file processing, with progress and Cancel.
[Android documents local file processing under this service type](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync).
No microphone access continues during recognition. Stop the foreground service
on completion, failure, cancellation, or system timeout. Phone behavior remains
to be verified.

Evaluate a [Chilean Spanish candidate](../research/chilean-spanish.md) separately;
do not claim that fine-tuning metadata proves better recognition of casual speech.

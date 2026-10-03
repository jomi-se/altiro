# Current work

Updated: 2026-10-03.

## Offline recognition preview

The operator reports that the first fixed-phrase APK inserts, shows microphone
capture, and cancels capture on a phone. UI polish is deferred; actual local
recognition and Chilean Spanish quality are the next priorities. This is smoke
feedback, not the full Gate A editor/lifecycle matrix. See
[the continuation decision](../decisions/0001-offline-preview.md).

The operator identifies the test phone as a Pixel 7 and supplies an actual
transcription from the offline app. Multilingual, airplane-mode, resource,
and editor/lifecycle acceptance evidence is still outstanding.

Source now connects completed PCM16/16 kHz WAV capture to a pinned
whisper.cpp 1.9.4 CPU JNI bridge and verified Base/Small model imports.
Small Q8_0 (264 MB) and FP16 (488 MB) have separate slots and an in-app picker.
Two converted Chilean ES-CL-2 Small variants are explicitly experimental.
New installs select Small Q8; upgrades preserve their existing Base and saved
selection. See [the comparison decision](../decisions/0003-small-model-comparison.md).
Recognition processes all audio windows, has native cancellation and structured
progress, and supports Auto/EN/FR/ES. Model and language are fixed at recording
start. **Record a comparison** processes identical audio through stock Q8 and
FP16, optionally adding either Chilean variant, one context at a time. Results
show separate transcripts and verification/load/recognition timings; Copy is
explicit per result. Comparison produces no floating insertion payload. Cold-load each session, then release native
memory before completion; warm residency is deferred. Rechecking the model,
context ownership, and audio deletion share one serialized native worker.

The app has no Internet permission. Explicit browser download and file-picker
import acquire the model. Import enforces actual byte size/SHA-256, bounded
copy, cancellation, and atomic installation. Failed imports preserve a valid
previous model. A model is reverified before native load. Replacement/deletion
and new inference stay blocked until native work finishes.

Capture releases the microphone before switching its foreground notification
to local file processing with progress/Cancel. Audio is deleted after the last native
comparison returns, failure, or cancellation; abandoned files are swept on startup. Results
remain process-memory only, with Insert/Copy/Discard and ten-minute expiry.
Existing password/composition/stale-target/one-attempt guards remain.

Application IDs are `org.altiro.app` and `org.altiro.fixture`; API 33 minimum,
API 37 compile/target. Recording from the floating mic without switching apps
is now the default in all build types. **Record without leaving your app**
selects this route; turning it off uses the separate recording screen, whose
focus change requires explicit Insert after recognition. **Open recording
screen** remains available if recording in place is refused. See
[the default change](../decisions/0002-record-in-place-default.md).
Toolchain/native/model pins and commands are in
[development](../development.md).

## Verification and gates

Actual base-model recognition through the production JNI bridge has passed on
the host using the upstream public speech sample. Tests cover audio beyond
30 seconds, native decoding cancellation (including automatic-language mode),
pre-cancelled and stale handles, exact digital-zero silence, and malformed WAV
rejection. All four Small profiles also passed public-sample recognition through the
production JNI bridge using the app's greedy, four-thread decode path. These
are host-native tests, not Android or Chilean quality evidence.

The Kotlin suite has eighteen passing tests, including sequential same-audio
comparison, cancellation before later models, corrupted model rejection, audio
cleanup on failure, and comparison having no insertion payload. Both apps and instrumentation
APKs build, Android lint has no errors, and ARM64/x86-64 JNI libraries compile.
Actual ELF/APK 16 KiB alignment passes for bundled native libraries. Formatting
and model manifest/artifact validation pass. Instrumentation is compiled
separately from execution. Read-only CI includes build checks and an API 33 emulator job but
has not been run remotely in this checkout. No local emulator has executed.

[Gate A](../testing/gate-a.md) is partially user-reported and remains
**unverified** overall. [Gate B](../testing/gate-b.md) is **unverified** on the
phone. No phone recognition latency, memory, multilingual accuracy, or runtime
16 KiB compatibility is claimed. A debug APK is not a signed release.

## Next work

Execute Gate B in airplane mode using the installed real model,
including native cancellation and 10/30/120-second English/French/Spanish
samples. Finish Gate A's composition, changed-target, password, lock, service
disable, and editor matrix alongside it. Record device/keyboard metadata and
failures rather than assuming initial insertion proves compatibility.

[Chilean Spanish research](../research/chilean-spanish.md) identifies an
Apache-declared Whisper Small fine-tune. ES-CL-2 FP16/Q8 artifacts are converted
with pinned upstream tooling and allowlisted by actual size/hash. Every FP16
tensor matches its source at conversion precision; both converted variants
load and recognize public upstream speech on the host. No weights are bundled
in Git or the APK. The preparation command also reproduces stock Q8 from FP16
with the published hash. Retained conversion input metadata and notices
accompany the files. Release provenance, held-out casual Chilean quality,
phone latency and memory remain unverified. Use the
[same-recording procedure](../testing/model-comparison.md) for that evidence.

Optional remote recognition/cleanup and visual polish remain later work.
Pushes, publication, store submission, and credential changes are operator-owned.

# Current work

Updated: 2026-10-03.

## Offline recognition preview

The operator reports that the first fixed-phrase APK inserts, shows microphone
capture, and cancels capture on a phone. UI polish is deferred; actual local
recognition and Chilean Spanish quality are the next priorities. This is smoke
feedback, not the full Gate A editor/lifecycle matrix. See
[the continuation decision](../decisions/0001-offline-preview.md).

Source now connects completed PCM16/16 kHz WAV capture to a pinned
whisper.cpp 1.9.4 CPU JNI bridge and a supported multilingual base model.
Recognition processes all audio windows, has native cancellation and structured
progress, and supports Auto/EN/FR/ES. Cold-load each session, then release native
memory before completion; warm residency is deferred. Rechecking the model,
context ownership, and audio deletion share one serialized native worker.

The app has no Internet permission. Explicit browser download and file-picker
import acquire the model. Import enforces actual byte size/SHA-256, bounded
copy, cancellation, and atomic installation. Failed imports preserve a valid
previous model. A model is reverified before native load. Replacement/deletion
and new inference stay blocked until native work finishes.

Capture releases the microphone before switching its foreground notification
to local file processing with progress/Cancel. Audio is deleted after native
return, failure, or cancellation; abandoned files are swept on startup. Results
remain process-memory only, with Insert/Copy/Discard and ten-minute expiry.
Existing password/composition/stale-target/one-attempt guards remain.

Application IDs are `org.altiro.app` and `org.altiro.fixture`; API 33 minimum,
API 37 compile/target. The visible recording Activity remains default. Its
focus change requires explicit Insert after recognition. Direct startup remains
a labeled debug experiment. Toolchain/native/model pins and commands are in
[development](../development.md).

## Verification and gates

Actual base-model recognition through the production JNI bridge has passed on
the host using the upstream public speech sample. Tests cover audio beyond
30 seconds, native decoding cancellation (including automatic-language mode),
pre-cancelled and stale handles, exact digital-zero silence, and malformed WAV
rejection. These are host-native tests, not Android or Chilean quality evidence.

The Kotlin suite has thirteen passing tests. Both apps and instrumentation
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

Install/import the real model on the phone and execute Gate B in airplane mode,
including native cancellation and 10/30/120-second English/French/Spanish
samples. Finish Gate A's composition, changed-target, password, lock, service
disable, and editor matrix alongside it. Record device/keyboard metadata and
failures rather than assuming initial insertion proves compatibility.

[Chilean Spanish research](../research/chilean-spanish.md) identifies an
Apache-declared Whisper small fine-tune. It is not bundled or allowlisted yet;
conversion, provenance, stock-small comparison, held-out casual speech,
quality, latency, and memory remain to verify. The current base model is a
reference candidate, not a proven high-quality Chilean profile.

Optional remote recognition/cleanup and visual polish remain later work.
Pushes, publication, store submission, and credential changes are operator-owned.

# Current work

Updated: 2026-10-06.

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

The operator's diagnostics now show inference dominates: on one 36.96-second
recording, stock Small Q8 took 23.55 seconds and Chilean Small Q8 took 37.54
seconds for inference. FP16 passes took 58.75 and 73.49 seconds respectively.
The comparison order was fixed; heat/scheduling were not controlled. Reported
text quality is promising, including Chilean Q8; this English sample does not
establish casual Chilean Spanish accuracy. A dedicated diagnostics screen
now traces phases without text/audio/editor data and offers Copy/Share. It
separately times verification of all installed files on process startup. Each
comparison model has separate verification/load/inference/release rows, with
native failure/cancellation cleanup retained. Cold loading and decode settings
remain unchanged. See [the decision](../decisions/0004-recognition-diagnostics.md)
and [phone procedure](../testing/recognition-diagnostics.md).

The requested GPU experiment adds a CPU/Vulkan setting in the app (CPU default),
plus **Compare CPU and GPU** for the selected model, with reversible order.
Model/language/backend choices are fixed at recording start. Native work runs
in a bound non-exported process, freshly started for each pass. CPU disables
Vulkan registration; requested GPU initialization must succeed explicitly.
Driver process death discards partial text, with no automatic CPU rerun.
Structured capabilities/status/failure codes and total Whisper compute counters
join phase timing exports. One bounded content-free checkpoint survives restart
outside backup until cleared or replaced; transcript/audio history remains absent.
Visible recording and diagnostics screens stay awake during active work;
manual lock still cancels. See [decision 0005](../decisions/0005-vulkan-device-experiment.md)
and [phone experiment](../testing/gpu-experiment.md). GPU/device IPC execution
and any speedup remain unverified until phone testing.

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

The Kotlin suite has twenty-four passing tests, including sequential same-audio
comparison (including the same model with two backends and no silent fallback),
cancellation before later models, corrupted model rejection, audio
cleanup on failure, and comparison having no insertion payload. Both apps and instrumentation
APKs build, Android lint has no errors, and ARM64/x86-64 JNI libraries compile.
Actual ELF/APK 16 KiB alignment passes for bundled native libraries. Kotlin formatting is applied automatically with ktfmt before verification;
ktlint style/line-length rules have been removed. Model manifest/artifact validation
passes. Instrumentation is compiled
separately from execution. Read-only CI includes build checks and an API 33 emulator job but
has not been run remotely in this checkout. No local emulator has executed.

Diagnostics tests cover running/final monotonic durations, processing time
excluding capture, native cleanup after cancellation, failure-phase preservation,
trace replacement/clearing, and no paths in exports. The production JNI host
smoke verifies ordered phase callbacks and context-release callbacks after
cancellation alongside the existing speech/silence/malformed-audio checks. It
also checks compute counters and a typed GPU-unavailable failure in a CPU-only
host build, without model loading or automatic CPU inference. The compiled
worker-isolation instrumentation checks failure reporting and process death;
it has not been executed on Android. No host Vulkan compute was executed.

[Gate A](../testing/gate-a.md) is partially user-reported and remains
**unverified** overall. [Gate B](../testing/gate-b.md) is **unverified** on the
phone. User-supplied CPU latency traces are recorded above; controlled phone
benchmarks, GPU behavior, memory, multilingual accuracy and runtime 16 KiB
compatibility remain unverified. A debug APK is not a signed release.

## Next work

Test CPU/GPU comparison on the reference phone with one installed Small Q8
model and a short fixed-language recording. Export diagnostics on both success
and failure, then reverse order and test cancellation/manual lock. Use FP16
only after GPU initialization succeeds. Collect memory/thermal/quality evidence
before choosing a GPU default or enabling other compute optimizations. Inference
dominates the supplied CPU traces; warm residency is not the main latency target.

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
controlled phone latency and memory remain unverified. Use the
[same-recording procedure](../testing/model-comparison.md) for that evidence.

Optional remote recognition/cleanup and visual polish remain later work.
Pushes, publication, store submission, and credential changes are operator-owned.

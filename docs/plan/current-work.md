# Current work

Updated: 2026-10-07.

## Productization direction

The owner now prioritizes everyday product use over adding recognition models.
The accepted [productization brief](productization.md) captures the maintainer
as primary user, one-tap model downloads including the Chilean fine-tune, a
bold native app with a quiet translucent overlay, bubble language access,
automatic insertion for valid destinations, dynamic windows, visible working
motion, and an in-app diagnostic console. `PRODUCT.md` summarizes confirmed
product facts for optional design tools. Their installation and harness files
are ignored; the public product docs are independent of those tools.

Version 0.5.0 adds the owner-approved centered-mic native screen and treats the
real accessibility overlay over other apps as the highest craft priority. Home,
Models, Setup and Console separate daily use from experiments. The overlay has
translucent idle material, one-touch EN/ES, frozen busy language, distinct
recording/processing motion, separate Cancel, drag handling and recovery actions.
Owned window events are excluded only with a known matching overlay ID; unknown
or other windows still invalidate destination authority. Busy app/overlay work
requests screen-on, and consumed dispatch permits another recording without
Discard. These mechanics need the [phone checks](../testing/everyday-interface.md).

Explicit stock-model downloads now stream from pinned HTTPS URLs through bounded
size/hash verification and atomic installation, with connection cancellation,
progress and sanitized failures. Imports stay available. The upstream Chilean
training repository has no converted binary; both audited variants remain
selectable but import-only. No release assets have been published. Network use
occurs only on explicit acquisition, never recognition. See
[decision 0009](../decisions/0009-everyday-native-interface.md).

Host verification passes for version 0.5.1: 30 core and 8 acquisition tests,
debug Android assemblies, lint, packaged native 16 KiB alignment, and app/fixture
instrumentation APK compilation. The update APK retains the previous debug
signing identity. Instrumentation tests were not executed; source review and
concept approval do not establish native visual, gesture or device acceptance.

Version 0.5.1 hardens foreground-service startup teardown for immediate
Stop/Cancel and microphone refusal; normal capture still requires the microphone
foreground type. See [decision 0010](../decisions/0010-foreground-start-teardown.md).
Home, the actual overlay and the recording notification warn at 4:30 before
automatic Stop at 5:00. A saved interrupted session has a dismissible Home
notice while its content-free Console checkpoint remains available. Pre-native
Fail/Cancel now persist their terminal outcome to avoid a false restart notice.
Framework startup regression tests and API 33/37 emulator jobs, including a
16 KiB image, are prepared. Execution and physical recovery checks remain pending.
Their stable `emulator` aggregate matches the protected branch's required check;
every configuration must pass before that gate reports success.

Version 0.5.2 adds the optional local vocabulary required by specification 10.4.
Setup edits a bounded 100-term/4 KiB list; empty is the default, and Clear plus
Save disables hints. Inputs snapshot the list at recording start for every
comparison pass. IPC/JNI carries strict UTF-8 without exposing words in diagnostic
reports, which show only the configured count. Whisper treats it as a prompt,
with its own token limit and final-window anti-hallucination behavior retained.
See [decision 0011](../decisions/0011-local-vocabulary-hints.md).
Host verification passes: 35 core and 8 acquisition tests, Android assembly and
lint, native 16 KiB packaging, and instrumentation APK compilation. Production
JNI checks pass with empty/Unicode hints, all-audio processing, digital silence,
cancellation and invalid byte inputs; requested unavailable GPU still fails
without a CPU retry. Physical vocabulary quality, noise and editing checks remain
pending in [the phone procedure](../testing/vocabulary.md).

Public-repository preparation adds private-reporting guidance, staged and
full-history Gitleaks scans, pinned read-only scanning CI, and Gradle dependency
update configuration alongside existing GitHub Actions updates. Remote security
settings have been verified on the public repository. Its initial everyday
source is now public and secret scanning has passed. Android verification failed
workflow parsing before jobs started: runner context is invalid in job-level
environment expressions. The [local fix](../issues/0001-invalid-workflow-context.md)
sets the output directory in a runner step and adds an independent checksum-pinned
workflow validator. Corrected Android execution and newer local commits await
the operator's push. See the
[publication review](public-repository.md). The owner explicitly retains
existing public author identities.

The [first-release checklist](release-readiness.md) separates source/host
verification from native appearance, physical editor/lifecycle/quality gates,
public Chilean binaries and release qualification. It is not a completed release.

Version 0.5.3 adds a reviewed runtime-input inventory and explicit license text
assets: 96 external graph components, 62 unique AAR/JAR inputs and 43 embedded
license records, distinct from native/model notices. Maven metadata declares
Apache-2.0, including Guava's inherited declaration; source review additionally
identified Kotlin's BSD-licensed time code and retained its exact notice plus
upstream attribution texts. Full verification exports the real graph and rejects
inventory/artifact/text drift, including APK contents. Debug/release input graphs
match. The final Android build/lint/packaging checks and 35 core/8 acquisition
tests pass. Release graph parity does not validate a signed release APK; physical
and signed-artifact acceptance remain pending.

The owner selected concepts before implementation, approved the refined centered
mic in misty sage/chalk/charcoal, and emphasized the actual floating accessibility
overlay. The rejected palettes are not product authority. Generated concept
images and local design tools remain ignored. Native capture-based visual
acceptance remains separate from code review and successful build checks.

## Offline recognition preview

The operator reports that the first fixed-phrase APK inserts, shows microphone
capture, and cancels capture on a phone. That report preceded the interface redesign; local
recognition and Chilean Spanish quality remain device-quality targets. This is smoke
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
and [phone experiment](../testing/gpu-experiment.md). The operator now reports
successful stock Small Q8 CPU/Vulkan comparisons in both orders on Pixel 7:

| Order | Audio | CPU inference | Vulkan inference | Vulkan time reduction |
| --- | --- | --- | --- | --- |
| Vulkan, CPU | 24.56 s | 38.30 s | 24.93 s | 34.9% |
| CPU, Vulkan | 11.88 s | 36.50 s | 24.04 s | 34.1% |

Both reports use Auto language, finish without typed failures, and report an
initialized Mali-G710 GPU backend with Vulkan 1.4.343. These are two different
recordings, each shared between its own passes; temperature and clock rates
were not measured. The similar advantage in both orders supports a GPU speed
improvement in these samples without establishing a controlled benchmark.
The operator also reports two stock Small FP16 comparisons, both with fixed EN
and Vulkan first: 9.64 seconds of audio took 11.31 seconds GPU / 33.16 seconds
CPU inference (encoder counters 8.52 / 31.37 seconds); 33.52 seconds of audio
took 20.59 seconds GPU / 72.71 seconds CPU inference (encoder 14.68 / 65.25
seconds). Both finish without failures and initialize the GPU backend. These
reports strengthen the FP16 GPU smoke evidence, but different recordings and
unmeasured temperatures prevent attributing differences against Q8 solely to
quantization. All these phone reports have Flash Attention off in version 0.4.0.

In the two Q8 Auto reports, total encoder counters remain about 20 seconds on
Vulkan and 33–34 seconds on CPU despite the different audio durations. The pinned runtime uses a full
30-second audio context by default and Auto performs an additional encoder
pass for language detection before transcription. Fixed EN/ES skips that pass;
its phone latency benefit is not yet measured. See the
[latency explanation](../testing/gpu-experiment.md#short-recordings-and-auto-language).

The requested [Flash Attention experiment](../decisions/0006-flash-attention-experiment.md)
adds an in-app GPU attention switch, enabled for Vulkan by default in this
experiment. CPU passes keep it off. The setting is frozen before recording and
reported in checkpoints before native work; initialized context configuration
and encoder/decoder call counts supplement total compute counters. Operation
placement is not traced. The off switch restores the previous attention path;
there is no automatic alternate-method retry.

The operator reports two completed version 0.4.1 comparisons on Pixel 7, both
fixed EN and Vulkan first. GPU requests Flash Attention on; CPU keeps it off.
Both reports initialize the GPU backend and enable Flash Attention in the
Whisper context, finish without failures, and report one encoder call per pass:

| Model | Audio | GPU inference | CPU inference | GPU encoder | CPU encoder |
| --- | --- | --- | --- | --- | --- |
| Stock Small FP16 | 21.96 s | 13.50 s | 36.59 s | 9.103 s | 31.784 s |
| Multilingual Base | 21.00 s | 7.09 s | 9.52 s | 3.541 s | 8.046 s |

The Small comparison reports 63 decoder calls in each pass; Base reports 75
GPU / 76 CPU calls. Base GPU decode takes 2.376 seconds versus CPU 1.306
seconds, and GPU batch takes 0.971 seconds versus CPU 0.043 seconds; the encoder
advantage does not carry through every compute phase. These counters are not
an additive wall-time partition. From Stop through GPU worker shutdown, the
Small pass takes about 16.8 seconds and Base about 8.6 seconds. Whole-comparison
post-Stop time includes both sequential passes, not ordinary GPU-only dictation.
The operator reports imperfect but much faster Base transcription.

These reports establish initial user-reported completion with the Flash
Attention graph setting on, not operation placement or general Mali stability.
They compare two backend/attention configurations together and use different
recordings across models; they do not isolate Flash Attention or model quality.
The earlier 9.64-second EN Small FP16 GPU encoder counter with attention off
was 8.518 seconds, versus 9.103 seconds here with it on. Different recordings
and unmeasured temperature/clocks prevent attributing that difference to the
attention method; there is no demonstrated encoder speed improvement from
Flash Attention in these samples. Version 0.4.1 cancellation/manual lock,
failure recovery, multilingual accuracy and device gates remain unverified.

The operator then reports two single-model Small FP16 Vulkan runs with fixed
EN, Flash Attention on first and off second. Both finish without failures,
initialize the GPU backend, and report a context attention setting matching
the requested switch. Each makes one encoder call:

| Flash Attention | Audio | Encoder counter | Decoder calls | Inference | After Stop |
| --- | --- | --- | --- | --- | --- |
| On | 11.48 s | 9.222 s | 27 | 11.82 s | 14.43 s |
| Off | 10.96 s | 8.746 s | 41 | 12.24 s | 15.02 s |

The on run has a 5.4% higher encoder counter and a 0.59-second shorter
post-Stop wait. It also makes fewer decoder calls and spends 0.26 seconds
stopping its worker versus 0.74 seconds in the off run. The recordings differ,
and temperature/clocks were not measured; these results neither establish an
attention-specific speedup nor prove a regression. They show comparable
ordinary-dictation latency and successful use of both switch settings. The
remaining roughly nine-second encoder cost persists in both configurations.

The requested [dynamic-window experiment](../decisions/0007-dynamic-audio-window.md)
adds an enabled-by-default short-recording mode and a full-window switch. The
native bridge uses decoded sample count, adds one second of padding and rounds
up to five-second windows, capped at the normal context. Recordings of 30 seconds
or more retain normal full-window processing. Model files stay unchanged.
**Compare full and dynamic windows** uses one recording with the selected model,
backend and attention setting held fixed, with reversible order and no insertion
payload. Modes are frozen before capture and included in result/trace identity.
Native-selected context frames and Auto's separate full-window detection are
exported. The operator reports a completed 0.4.2 same-recording comparison on
Pixel 7: 6.84 seconds of audio, fixed EN, stock Small FP16, Vulkan and Flash
Attention on for both passes. Dynamic selected 10 seconds and took 5.40 seconds
inference / 3.131 seconds encoder; full selected 30 seconds and took 11.67
seconds inference / 9.266 seconds encoder. Both report one encoder call and 24
decoder calls, without typed failures. This is promising short-clip latency
evidence, not a controlled thermal benchmark; transcript quality and ending
preservation were not supplied. The full accuracy and lifecycle gates remain
unverified.

Explicit in-app stock-model download or file-picker import acquires the model.
The app has Internet permission for acquisition only. Both paths enforce byte size/SHA-256, bounded
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

The JVM suites have 30 core and 8 acquisition tests, including sequential same-audio
comparison (including the same model with two backends and no silent fallback),
same-model/backend window comparisons with distinct results and diagnostic traces,
cancellation before later models, corrupted model rejection, audio
cleanup on failure, and comparison having no insertion payload. Both apps and instrumentation
APKs build, Android lint has no errors, and ARM64/x86-64 JNI libraries compile.
Actual ELF/APK 16 KiB alignment passes for bundled native libraries. Kotlin formatting is applied automatically with ktfmt before verification;
ktlint style/line-length rules have been removed. Model manifest/artifact validation
passes. Instrumentation is compiled
separately from execution. Read-only CI includes build checks and API 33/37
emulator jobs, including a 16 KiB image, but
has not been run remotely in this checkout. No local emulator has executed.

Diagnostics tests cover running/final monotonic durations, processing time
excluding capture, native cleanup after cancellation, failure-phase preservation,
trace replacement/clearing, and no paths in exports. The production JNI host
smoke verifies ordered phase callbacks and context-release callbacks after
cancellation alongside the existing speech/silence/malformed-audio checks. It
also checks compute counters and a typed GPU-unavailable failure in a CPU-only
host build, without model loading or automatic CPU inference. The compiled
worker-isolation instrumentation checks failure reporting and process death;
it has not been executed on Android. The production CPU JNI bridge also passes
real speech, recordings longer than 30 seconds and cancellation with Flash
Attention both off and on, including reporting the initialized graph setting.
An explicit GPU request with Flash Attention enabled also returns the typed
GPU-unavailable failure on the CPU-only host. No host Vulkan compute was executed.
Dynamic-window production JNI checks also pass speech with attention off/on,
sentence-ending retention, Auto's separate full context, native cancellation and
audio beyond 30 seconds retaining full windows. Exhaustive sub-30-second sample
counts cover sizing, padding and rounding boundaries. These checks use the
public English sample on CPU; short-window phone/Chilean accuracy is unverified.

[Gate A](../testing/gate-a.md) is partially user-reported and remains
**unverified** overall. [Gate B](../testing/gate-b.md) is **unverified** on the
phone. User-supplied CPU/Vulkan latency traces are recorded above; controlled
benchmarks, GPU failure/cancellation/lock behavior, memory, multilingual accuracy
and runtime 16 KiB compatibility remain unverified. A debug APK is not a signed release.

## Next work

Q8 runs in both backend orders and FP16 GPU/CPU runs already have user-reported
smoke evidence. Next compare full/dynamic windows on the same short recording
using Small FP16, Vulkan and fixed EN/ES, holding attention constant. Follow the
[dynamic-window procedure](../testing/dynamic-window.md), compare encoder time
and text (especially sentence endings), and evaluate Chilean Spanish separately.
Verify long recordings retain full windows. Auto encoding reuse remains a
separate possible optimization; it is not implemented by this experiment.
GPU cancellation/manual lock and failure recovery still need phone evidence.
Collect memory/thermal/quality evidence before choosing a GPU default or
accepting short-window quality parity. Inference dominates the supplied CPU
traces; warm residency is not the main latency target.

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

Optional remote recognition/cleanup remain later work. The everyday interface
is implemented; native visual and gesture acceptance still need phone evidence.
Pushes, publication, store submission, and credential changes are operator-owned.

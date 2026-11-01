# Current work

Updated: 2026-11-01.

## Optional self-hosted recognition

[`server/`](../../server/README.md) now contains the optional Python GPU API,
browser client, fixed model provenance, locked dependencies, Windows launchers
and a restricted dedicated WSL2 deployment, as requested by the maintainer.
See [decision 0014](../decisions/0014-optional-self-hosted-server.md). The reference
passed 18 containment checks plus real speech, silence, malformed audio and
restart checks. The default sign-in launcher selects WSL2. Machine state,
weights, environments, disks and measurements remain ignored.

Version **0.7.0-remote-first** integrates the configured HTTPS speech server.
Saving an endpoint enables remote first unless explicitly disabled. Readiness is
checked while recording; an unavailable/busy server or failed single POST falls
back visibly to the selected installed local model. No endpoint is bundled and
local-only mode does not probe/upload. Green/server, blue/phone and neutral/check
indicators cover the actual route without changing overlay geometry or insertion
authority. Remote-only startup is supported; comparisons remain local-only.
See [decision 0025](../decisions/0025-remote-first-local-fallback.md) and
[phone checks](../testing/remote-first.md). Verification passes: 99 JVM tests,
54 Python checks, Android app/fixture builds, lint, instrumentation APK
compilation, runtime notices, installability and native 16 KiB alignment.
Independent source review fixes are included; no phone end-to-end networking,
rendered bubble or real-server Android acceptance is claimed. LLM cleanup and
a hard server native-job watchdog remain outside this increment.
The additional five host wire tests use actual loopback TLS sockets for framing,
trust/hostname rejection, redirect/replay refusal and late-response cancellation.
They run without changing production code, dependencies or the verified Android
APK; scripted server responses are not evidence of actual GPU execution.

## Android preview
Two local UI corrections are implemented: pending-result Copy notices derive
their 48 dp minimum height from the effective action, and an already-open
Termux paste confirmation honors the settings lock with a fresh busy-state
check before changing preferences. See [issue 0008](../issues/0008-pending-copy-touch-target.md)
and [issue 0009](../issues/0009-termux-confirmation-settings-lock.md).
Independent source review, Kotlin formatting, repository/model/Python checks
and core/network JVM tests pass. Android compilation/lint still need an Android
SDK. Rendered touch-target and dialog/overlay interaction checks on a phone
remain pending.

Version **0.6.9-opencl-int8** implements the owner-authorized next GPU
campaign with Astra: actual pinned Arm OpenCL kernels and a scale-preserving
signed-integer Vulkan path. The per-model schedule excludes unsupported FP16
INT8 cases and retains matched controls and content-free diagnostics. See
[decision 0024](../decisions/0024-opencl-and-integer-gpu-experiments.md) and the
[phone procedure](../testing/opencl-int8.md). Host verification passes: 74 JVM
tests, 54 Python checks, native layout and fake-driver lifecycle checks, and
host-JNI dispatch/cancellation smoke checks. Android app/fixture builds, lint,
instrumentation APK compilation, notices, installability and 16 KiB alignment
checks pass. The [completed phone campaign](../research/mali-kernel-hunt.md#completed-069-opencl-and-int8-campaign)
passes 27/27 cases, including all 24 ACL and 12 INT8 shape records. INT8 improves
both large matrix families by 1.23–2.04× against matched Q8 controls, including
per-call quantization, with 0.329–0.437% floating-reference output error. ACL is
mixed and contraction loses. These synthetic results motivate a selective
full-encoder experiment, not a measured model-level gain. Thermal/baseline drift
limits generalization; new-kernel cancellation and transcription-quality gates
remain open. Everyday recognition defaults remain unchanged.

Version **0.6.8-packed-gpu** implements the next opt-in **Packed GPU**
experiment: original register-blocked Vulkan matrix kernels, complete GGML
controls, fixed/recording-derived shapes, independent products and ordinary-app
OpenCL/Vulkan capability probes. See [decision 0023](../decisions/0023-packed-gpu-experiments.md)
and the [phone procedure](../testing/packed-gpu.md). Host verification passes: 73 JVM tests, 40 Python checks, native packing/output
ownership and fake OpenCL lifecycle tests, plus actual host-JNI capability
dispatch/cancellation and JSON-schema checks. Android app/fixture builds, lint,
instrumentation APK compilation, notices, installability and 16 KiB alignment
checks pass. The signing identity and unchanged Whisper library match the
previous verified APK. The phone campaign below verifies the exercised paths;
broad stability and speech quality remain open. This does not include an ACL
backend or change everyday recognition defaults.

The **0.6.8 phone campaign completed 26/26 cases** on one 18.48-second recording:
32 packed shape records pass numerical/placement checks, with all GPU timestamps
available. The original packed expansion reduces matched warm complete-call
time by 11–31% across single-product shapes, while contraction increases it by
16–55%; no whole-encoder packed gain has been measured. The app successfully
executes its OpenCL FP32 probe, and Vulkan reports accelerated signed packed
8-bit dot products. Actual ACL and scale-correct integer kernels are now viable
experiments, not measured wins. See the
[completed campaign analysis](../research/mali-kernel-hunt.md#completed-068-packed-gpu-campaign).
CPU drift and NONE–LIGHT thermal status qualify comparisons. Defaults remain
unchanged.

A fresh independent Astra web/source review after the GPU tuning campaign finds
credible untested implementation leads, especially Arm's G710-specific packed
GEMM family, exact device capability/counter evidence and verified Fabric graph
deltas. It does not establish an architectural CPU-only limit or promise a GPU
speedup. The [reassessment](../research/transcribepsy-gpu-reassessment.md) records
immutable source pins, arithmetic estimates, ranked bounded experiments and
stop criteria. Research only; no backend migration or new runtime defaults.

Version **0.6.7-encoder-tuning** implements the authorized opt-in follow-up to
source review: blocking fence waits, a 256-thread 64×64 scalar tile, selective
split-K two/four and dense bias/SiLU epilogues. The separate **GPU tuning** campaign
keeps F32 GPU and CPU controls, actual-length focused shapes, independent route
counters, boundary GPU timestamps and double/unfused numerical checks. See
[decision 0022](../decisions/0022-source-guided-encoder-tuning.md) and the
[phone procedure](../testing/encoder-tuning.md). Host checks pass: 72 JVM tests,
31 Python checks and production-JNI focused
CPU execution for both formats (16 shapes, 96 reported graph calls), including
full boundary references and cancellation. Both public-speech format checks and
ordinary cancellation pass. The dense fusion guard fixture checks consumers,
views, outputs and rounding/reduction constraints. Android app/fixture builds,
lint, instrumentation APK compilation, installability, runtime notices and 16 KiB
packaging checks pass. The APK preserves its signing identity and the unchanged
Whisper library. The following phone campaign confirms execution and bounded
numerical checks; speech quality and ordinary product acceptance remain open.
Ordinary recognition defaults and insertion behavior are unchanged.

The 0.6.7 phone campaign completed **42/42 cases** on one 16.04-second clip:
112 focused shapes and 672 reported graph calls pass the route/finite/numerical
screen, all boundary timestamps are present, and fusion checks match complete
unfused GPU outputs exactly. Blocking waits reduce full-encoder process CPU work
about 15–16 times without reducing wall time. The 256-thread tile is slower;
split-K is near flat and FP16 bias+SiLU fusion shows a roughly 2.2% candidate
gain against stable F32 controls. Q8 CPU-reference feature error remains above
the provisional full-encoder screen. CPU baselines drift and thermal status
reaches LIGHT; no ordinary defaults change. See the
[completed campaign analysis](../research/mali-kernel-hunt.md#completed-067-gpu-tuning-campaign).

Version **0.6.6-kernel-fixes** corrects two defects exposed by the first live
Q8 Kernel hunt campaign: GPU synthetic tests excluded integrated Vulkan
devices, and forced-small scalar tiles had invalid logical-warp geometry on
subgroup-16 devices. See [defect 0006](../issues/0006-kernel-hunt-gpu-selection-and-small-tiles.md).
The executable host regression checks explicit backend selection/failure and
unique, bounded ownership of every output cell for four subgroup sizes.
Host verification passes: 71 JVM tests, 25 Python checks, Android app/fixture
builds and lint, instrumentation APK compilation, installability, notices and
16 KiB packaging. Production-JNI CPU checks cover 64 synthetic shapes (384
reported graph calls), both speech formats and cancellation. TranscribePsy
was rebuilt for Android; the unchanged Whisper library matches the prior
verified APK byte for byte. The following phone campaign accepts the two
specific harness defects; model-quality acceptance remains separate.
Everyday dictation settings and the opt-in relay are unchanged. The receiver
serves its current APK only at the generic download route, avoiding stale
version-specific aliases.

The 0.6.6 live phone campaign completed **48/48 cases** on one 11.56-second
recording, with both formats and profiling. All **640 synthetic shapes** pass
the finite/1% RMS screen, including 576 GPU shapes; there are 3,840 reported
synthetic graph calls. Forced-small full-encoder features now match stock GPU
numerics, including finite small-F32 output. Defect 0006 is accepted on hardware.
Warm encoder medians are 0.923 s for Q8/CPU two threads, 1.815 s for FP16/CPU
two threads, and roughly 1.45–1.49 s for stock GPU. These exclude setup/decoder.
Global tile overrides do not provide a large win. The profiler attributes
80% of encoder wall time to matrix multiplication, with two shape families
accounting for about 47%. See the [completed campaign analysis](../research/mali-kernel-hunt.md#completed-066-phone-campaign).

Source review identifies explicit busy-spinning for small graphs, mat-vec-only
bias fusion, and an unpopulated Mali core-count gate that disables automatic
split-K. Next investigation: blocking waits plus boundary timestamps, a
256-thread 64×64 specialization that preserves reuse, and selective split-K
at actual encoder widths. Dense-GEMM epilogues are a separate bounded candidate.
See the [source-guided candidates](../research/mali-kernel-hunt.md#source-guided-optimization-candidates).
These are research proposals; no new runtime defaults are applied. Ordinary
TranscribePsy still uses four CPU threads; a controlled two-thread product path
is the immediate CPU candidate. CPU baselines drift and the relay was enabled; repeat
close timing rankings disconnected. FP16/F32 reduces feature error to 0.121%;
Q8's GPU feature difference remains above the provisional full-encoder screen.
Neither numerical screens nor this encoder-only campaign establish dictation
quality or everyday end-to-end latency. Earlier invalid small-tile offline
compiler results remain withdrawn pending regeneration.

The 0.6.5 live relay is now confirmed on the phone: all 23 planned Q8 case
records arrived, including the final `FINISHED_WITH_FAILURES` snapshot.
Thirteen cases finished, ten failed; all nine GPU synthetic cases failed
before producing shape measurements. Full-encoder GPU execution worked.
Forced-small features were corrupt, including nonfinite F32 output.
Warm GPU encoder medians were about 1.42–1.45 seconds; two CPU threads reached
0.825 seconds, with drifting four-thread baselines. Rectangular F32 candidates
did not improve timings in this run. These observations do not establish a
stable ranking or speech quality. See [the investigation](../research/mali-kernel-hunt.md).

The 0.6.3 physical-phone Full run completed all 40 cases and 160 samples on one
11.08-second recording. Disk-seeded pipeline caching reduced FP16 first-encoder
time from 4.445 to 1.542 seconds, and Q8 from 4.754 to 1.818 seconds, in fresh
workers. The files were approximately 181 KB; nested shader setup fell from
seconds to 28–44 ms. FP16 weights with F32 accumulation reduced relative RMS
feature differences from 1.24% to 0.137% at roughly 4% warm-time cost. Q8 CPU
two-thread encoding remains competitive, but CPU baselines drifted during the
long run. See the [Full measurement analysis](../research/transcribepsy-performance-investigation.md#full-encoder-lab-phone-follow-up-063).
The phase-capacity defect is accepted on hardware. Next candidates are persistent
pipeline caching and preparation during recording, with FP16/F32 accumulation
for GPU. Their combined implementation, end-to-end latency, resource lifecycle
and transcription quality still need validation; everyday defaults have not changed.

Version 0.6.3 fixes the lab's [diagnostic phase budget](../issues/0005-encoder-lab-phase-capacity.md),
with regression coverage of all 40 Full-suite worker lifecycles and elapsed
timings. The 0.6.2 phone Quick report establishes real GPU execution: 13/16 cases
completed; the last three hit the ordinary 64-phase trace limit before worker
startup. The lab now explicitly allows 1,024 phases; ordinary sessions keep 64.
The following Full report confirms the corrected long suite completes. On the same
7.52-second clip, warm stock FP16 GPU encoding reached 0.834 seconds versus
1.291 seconds CPU, while the first GPU call spent 4.085 seconds in nested shader
registration/compilation. See the [measurement analysis](../research/transcribepsy-performance-investigation.md#encoder-lab-phone-follow-up-062).

Version 0.6.2 fixes [encoder-lab backend registration](../issues/0004-encoder-lab-backend-registration.md).
The maintainer successfully installed the corrected 0.6.1 package and exported
all 40 Full-suite case records. All 24 attempted GPU cases were rejected by the
parent's diagnostic identity check before worker startup, not by GPU kernels;
14 CPU cases completed and two cache-seed cases were skipped. The lab now
registers both backends for each selected model and labels unclassified setup
failures. Physical GPU execution is now confirmed by the following Quick report;
numerical and transcription-quality acceptance remain pending.

The first 0.6.1 APK was unintentionally marked `android:testOnly` by an
ABI-targeted build, blocking normal tap-to-install updates despite a valid,
unchanged signing certificate. Verification now explicitly disables that flag,
checks the packaged binary manifest, and follows AGP's APK artifact providers
instead of checking an older cached output. Subsequent phone execution confirms
the corrected package installed; the report above concerns a separate harness bug.

The maintainer's 0.6.0 Pixel 7 comparisons now establish successful CPU and
Vulkan encoder/decoder execution for Q8 and FP16 in those trials, with no
unsupported operations. CPU inference was approximately twice as fast as
Vulkan for the paired short recordings; on one identical 31.88-second CPU
recording Q8 took 5.44 seconds versus FP16's 7.77 seconds. These are individual
phone observations, not controlled latency/quality or comprehensive resource
acceptance. The [code-level performance investigation](../research/transcribepsy-performance-investigation.md)
traces lazy pipeline compilation inside encoder timings, matrix types/shapes,
direct versus lowered convolution, graph optimization and decoder dependencies.
The encoder experiments now ship behind the opt-in [Encoder lab](../testing/encoder-lab.md)
in 0.6.1 (decision [0019](../decisions/0019-encoder-lab.md)). One recording drives
first/warm repetitions, CPU threads and Vulkan graph/kernel-routing variants,
content-free partial reports and an optional timestamp profiler. The decoder
lookahead proposal remains unimplemented. Everyday runtime defaults are unchanged.
Host CPU execution checks cover both formats. Physical Quick GPU measurements
and Full GPU measurements are now available; combined-path numerical/quality
and product resource/latency acceptance remain pending.

Version 0.6.0 adds the authorized [TranscribePsy experiment](../decisions/0018-transcribepsy-comparison.md):
ARM64 Q8 and true FP16 profiles, explicit CPU/Vulkan selection and a frozen
four-pass same-recording comparison. Q8 has a pinned in-app download; FP16 is
reproduced from original weights and imported after exact byte/hash verification.
Existing Whisper selection and recording/insertion paths stay in place.
Language detection is automatic for this engine; unsupported Whisper controls
are hidden. Diagnostics include actual graph backends, unsupported operation
type/shape, CPU features, completed graph counts and sampled process memory.
Comparison retains the runner's fail-fast behavior; a failed pass prevents the
remaining passes and discards comparison transcripts, while timings remain.
See [the test procedure](../testing/transcribepsy.md). Host JNI smoke tests
have exercised both precisions on CPU, a reference phrase, malformed audio and
cancellation. Full host verification passes: 52 core and 8 acquisition tests, Android app/fixture
builds and lint, instrumentation APK compilation, native page alignment and
packaged notices. The later phone observations above supplement these host
checks; controlled performance, cancellation/resource gates and multilingual
quality acceptance remain open.

The maintainer reports improved ordinary insertion before this experiment.
The earlier comprehensive editor/resource gates remain open. Current work
targets recognition speed without changing delivery authority.

Version 0.5.9 removes Home's hardcoded “Chilean Small” shortcut, reported as a
second model selector stuck on Chilean. The remaining capsule and Models radio
picker share the saved selection. Diagnostics add `Selected model now` while
preserving the recording trace's frozen `Models` identifier. Source tracing
finds no model substitution between selection, verified catalog file and native
load. Phone persistence/selection acceptance and the reported decline in Chilean
idiom recognition remain unverified; see
[defect 0003](../issues/0003-misleading-model-selection.md).
Host verification passes: 50 core and 8 acquisition tests, Android builds/lint,
native packaging/notices and instrumentation APK compilation. No device model
selection or speech-quality test ran on this host.

Immediate acceptance priority: basic automatic text delivery and bubble recovery.
The maintainer reports false “Finish typing” blocks in empty ordinary fields
and rejects 0.5.7's removal of bubble Copy. Version 0.5.8 selects ordinary paste
at the cursor, without waiting for composition callbacks or requiring an empty
field. It uses the freshly focused node's advertised paste action when available,
otherwise the live input connection's public paste context action, one attempt
only. Changes after destination capture still invalidate delivery. Copy is
available directly on the bubble after blocked and unconfirmed results;
successful Copy releases the retained session and returns to the microphone.
See [decision 0017](../decisions/0017-paste-at-cursor-and-bubble-copy.md) and
[the active defect](../issues/0002-overlay-visibility-and-recovery.md).
Host verification passes: 50 core and 8 acquisition tests, Android application
and fixture builds/lint, native packaging/notices, and instrumentation APK
compilation. The two real-editor paste fixtures are compiled, not executed.
Signing identity is preserved. Phone delivery/appearance remain pending.
Source review identified hidden-app/no-input visibility and late callback
knowledge changes; the follow-up patch preserves hide preferences, extends
no-input recovery only for blocked results and excludes the callback-arrived
bit from paste authority while retaining real range changes. Follow-up core
tests, application build/lint and instrumentation compilation pass, and the
review confirms these corrections. Physical acceptance remains pending.

Version 0.5.5 corrects the active-window-only editor lookup after the maintainer
reports missing controls in ChatGPT, Termux and Reddit even after fresh setup.
It uses global input focus and the focused application window, adds one bounded
settle sequence per input and records 48 content-free visibility transitions.
Content events are coalesced into five checks over 1.5 seconds; streaming app
content does not perpetually probe. Visual ticks redraw cached editor state,
while actual editor events and recording/insertion checks refresh authority.
Copy works before recording and preserves the last matched external input.
Current installed model count/readiness is separate from startup timing rows.
Home's reported jumpy recording arc now uses Compose's frame clock rather than
32 ms timer steps, with angle read only during drawing. See
[decision 0013](../decisions/0013-focused-editor-lookup.md). Android compilation
and actual editor/motion acceptance are separate; phone confirmation is pending.
Full host verification passes for 0.5.5: 38 core and 8 acquisition tests,
Android debug assemblies/lint, native packaging/runtime notices and
instrumentation APK compilation. The update retains the preview's signing
identity. No device tests or frame-pacing measurements were executed here.

Version 0.5.6 corrects bubble visibility independently of insertion identity.
The reported missing surfaces are ChatGPT search/composer, Reddit search/comments,
Twitter comments and Termux terminal. Started TEXT/RAW input in a positively
matching, visible focused application window can show the ordinary mic with a
connection or visible keyboard. A present contradictory node and password/lock/
hidden/display gates still reject it. Mic startup and Hide/Restore use this
recording host; insertion keeps its stricter identity and lifecycle checks.
The maintainer rejected the proposed Copy-mode UI, which is removed. No added
Copy button, label, clipboard behavior or recording delivery mode is shipped.
See [decision 0015](../decisions/0015-recording-bubble-visibility.md) and
[phone checks](../testing/interface-rewrite.md). A race is not confirmed; the
persistent identity gate explains the observed absence. Keyboard and recording
host facts are now exported to distinguish late metadata from insertion failure.
Full host verification passes: 43 core and 8 acquisition tests, Android builds/
lint, native packaging/notices and instrumentation APK compilation. A regression
checks that a host token cannot gain automatic insertion when a real field appears.
Physical compatibility remains pending; no device tests were executed. The update
preserves the signing identity and native runtime.

The 0.5.6 phone follow-up confirms Termux terminal mic visibility but unavailable
insertion. ChatGPT still lacks the mic with its keyboard open. Repeated matching
TEXT/connection/keyboard evidence is rejected by a fallback host's `focused=false`
bit. At the maintainer's request, [upstream source research](../research/android-overlay-compatibility.md)
now compares four pinned Android dictation implementations and Termux's typing/
paste paths before more compatibility patches. Next: remove this recording-only
focus veto while preserving strict insertion authority, add compact copied logs
and complete file export, then capability-test terminal paste separately. No
new implementation or device acceptance is claimed by this research increment.

## Productization direction

Version 0.5.7 implements [automatic delivery and bubble controls](../decisions/0016-automatic-delivery-and-bubble-controls.md):
connected TEXT identities use input-method epochs/window metadata when node
focus is incomplete; ordinary selection/composition and stale-token gates stay
in force. A positively focused Termux terminal has a distinct paste route using
its documented shortcut after one-time Settings confirmation that Termux's
hardware shortcuts are enabled, with line breaks flattened and controls stripped.
Delivery is selected before one consumed attempt; no uncertain retry is added.
The mic is a larger 76 dp target, language opens an explicit menu, and the old
Copy/Insert/Discard result cluster is removed. The overlay measures before
resizing at its dock. Console Copy is bounded; Share exports a complete private
diagnostic file. Build verification and physical checks are tracked separately;
the [updated phone procedure](../testing/interface-rewrite.md) remains pending.
Full host verification passes: 48 core and 8 acquisition tests, Android builds/
lint, native packaging/runtime notices and instrumentation APK compilation.
An independent source review resolved terminal classification/shortcut gating,
clipboard ownership, unrelated window invalidation and language-menu position.
The file-provider instrumentation regression is compiled, not executed here.
The signing identity is preserved; hardware compatibility remains unverified.

The maintainer has rejected the tested floating control and the layered
configuration/console navigation. A chat-field bubble is missing, and restoring
hidden apps appears ineffective. See [the reported defect](../issues/0002-overlay-visibility-and-recovery.md).
The next increment is compatibility diagnosis and a structural design revision,
with fresh native acceptance; the previous implementation is not an accepted
visual or interaction baseline.

Version 0.5.4 rewrites the real overlay around one translucent capsule, one mic
backing and progress replacing the mic while recognizing; idle drag-to-hide has
Undo and a TalkBack action. Home, Models, Settings and Console are peers.
Routine runtime controls move to Settings, restoration has count/confirmation,
and Console leads with phase timings rather than repeated explanatory text.
The old Setup toggle, duplicate Console Activity and verbose experiment controls
are removed. Fixed current/last editor reason codes support the missing-bubble
diagnosis without weakening insertion authority. See
[decision 0012](../decisions/0012-interface-rewrite.md) and the
[new phone checks](../testing/interface-rewrite.md). Build and native acceptance
are tracked separately; this paragraph is source implementation evidence.

Full host verification passes for 0.5.4: 35 core/8 acquisition tests, Android
assemblies/lint, native packaging, runtime notices and instrumentation compilation.
An independent source review resolved Console Stop/Cancel access, stale external
editor reason retention and calculated idle contrast. The actual native captures
and ChatGPT/Termux compatibility remain pending; the identity gate is unchanged.

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
The app uses Internet permission for acquisition and the separately enabled
debug lab relay (decision 0021). Both acquisition paths enforce byte size/SHA-256, bounded
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

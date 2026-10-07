# First local release readiness

Status: in-progress. Authority: [productization](productization.md),
[specification](android-dictation/spec.md), and
[first-APK ticket](android-dictation/issues/06-first-apk.md).

This checklist covers the first local Android product for daily dictation.
Optional remote recognition and cleanup remain a separate later gate. Source
implementation, host checks, emulator execution and physical acceptance are
different evidence. A generated concept is not a native screen capture.

## Implemented requirements and remaining acceptance

| Requirement | Current evidence | Remaining acceptance |
| --- | --- | --- |
| Clear install/setup; preserve existing models and keyboard | Native Setup and Models; 0.5.3 update APK has matching debug signing identity | Fresh install and update on Pixel; microphone, notifications, accessibility and restricted-setting recovery |
| One-tap verified model acquisition | Stock Base/Small HTTPS downloads, exact size/hash checks, progress, Cancel, atomic install; 8 acquisition JVM tests | Phone interruption, low storage, relaunch and failed replacement; installed model works in airplane mode |
| Chilean Small choices and one-tap installation | Both converted precision variants are allowlisted with source/license metadata; verified import works in source | Compatible public binary assets and verified direct URLs; currently import-only, so this requirement is incomplete |
| Approved centered-mic native interface | Sage/chalk/charcoal Home, Models, Setup and Console implemented | Actual native light/dark captures, font scaling and review; concepts and compilation do not establish polish |
| Almost transparent floating overlay over other apps | Small native View, non-focusable overlay, separate busy Cancel, drag handling, bounded positioning | Physical light/dark/busy editors, keyboard and rotation; reachable touch targets without stealing focus or intercepting outside touches |
| One-touch bubble language change | EN/ES changes the next session; active model/language snapshot frozen; Auto/FR available inside app | Pixel touch and accessibility behavior while typing with the normal keyboard |
| Insert normal dictation automatically | Serialized session authority, consumed single attempt, invalidation and explicit Insert/Copy recovery; core tests | Full Gate A selection, composition, moved cursor, changed field, password, WebView, filters and second-keyboard matrix; check delivered text |
| Dynamic encoder window capped at 30 seconds | Native window selection, diagnostics and same-audio comparison; actual host JNI short/long audio checks; earlier Pixel timing evidence | Redesign regression on phone; clipped endings, longer audio and quality across languages |
| Visible work, Stop versus Cancel, keep awake while busy | Distinct overlay/Home processing motion, phase text, screen-on flag, teardown and notification cancellation | Disabled animation setting, manual lock, timeout, immediate Stop/Cancel and microphone refusal; no later insertion or leaked capture |
| Console and recoverable interruption | Live/final phase timing, runtime counters, content-free checkpoint, Copy/Share/Clear, Home interruption notice | Phone crash/restart/export checks; no words, audio, editor identity or private paths |
| Optional explicit vocabulary | Empty default; 100-term/4 KiB validation, private persistence, snapshot and strict UTF-8 JNI; actual speech/silence/cancellation checks | Phone persistence/editing/IME checks; no hints hallucinated into real noise; technical English and Chilean Spanish comparison |
| Ephemeral audio and disclosed resource limit | Owned temporary audio, no default transcript history, cancellation cleanup; 4:30 warning and 5:00 automatic Stop | Kill/reopen, audio interruption, service disable and memory-pressure checks; two-minute and five-minute physical runs |
| Free, local operation | No account, billing, quota, mandatory server or automatic model download; networking restricted to explicit acquisition in source | Airplane-mode capture/recognition/insertion and traffic inspection; 10/30/120-second EN/FR/ES, natural Chilean speech, silence and noise |
| Portable public repository and security | Initial source public; ignored local skills/artifacts; pinned read-only CI, grouped Dependabot and secret scans | Operator push of newer commits; corrected Android CI execution and required checks green |
| Runtime, model and dependency provenance | Pinned runtime/catalog and conversion metadata; reviewed runtime-input inventory, Apache/BSD/attribution assets verified inside APK; debug/release graphs match; native libraries pass 16 KiB packaging checks | API 33/37 execution including 16 KiB image; signed-artifact/native/model redistribution qualification |

## Verified host baseline

The 0.5.3 debug artifact passed 35 core and 8 acquisition JVM tests, Android
assemblies, lint, native packaging, runtime notice graph/APK checks and
instrumentation APK compilation. Production JNI checks on the unchanged 0.5.2
native source used the real runtime and verified multilingual Base, including
empty/Unicode hints, speech, exact digital silence, cancellation, all-audio
processing and invalid prompt bytes. An unavailable requested GPU fails without
automatic CPU inference. Artifact hashes, signing inspection, test XML and review
reports are retained outside Git. Source changes require appropriate revalidation.

Instrumentation APK compilation does not execute framework tests. This baseline
does not establish Pixel appearance, general editor compatibility, real-noise
behavior, Chilean speech accuracy or sustained thermal performance.

## Release blockers and order

1. Push corrected CI through the operator's normal workflow. Require build and
   all API/page-size device tests to pass; workflow validation alone cannot pass
   the Android gates. See [the confirmed CI defect](../issues/0001-invalid-workflow-context.md).
2. Execute [everyday interface](../testing/everyday-interface.md),
   [startup](../testing/service-startup.md), [vocabulary](../testing/vocabulary.md),
   [Gate A](../testing/gate-a.md) and [Gate B](../testing/gate-b.md) on the Pixel.
   Retain synthetic-editor native captures and sanitized observed results outside
   Git; fix and recheck failures before promoting compatibility or polish claims.
3. Publish reviewed compatible Chilean binaries with notices and conversion
   metadata, then pin real direct-download URLs and test both installations.
   Do not point the app at a training checkpoint or an unpublished URL.
4. Complete the [dependency review](../dependencies.md), secure signing and
   artifact provenance qualification for a signed release. Signing keys, uploads,
   releases and store submission remain separately authorized operator actions.

The current debug preview is useful for device review. This checklist remains
open until the evidence above supports the intended daily-use release.

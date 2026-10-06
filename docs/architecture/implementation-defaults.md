# Implementation defaults

The [Android specification](../plan/android-dictation/spec.md) owns detailed
behavior. These defaults make its implementation boundary easy to find.

## Native Android

Minimum Android 13/API 33. Select and pin the latest stable compile/target SDK
and mutually compatible Gradle, AGP, Kotlin, Compose, JDK, NDK, and CMake when
creating the build scaffold. Verify upstream compatibility rather than copying
old example versions. Pin the wrapper checksum and all dependencies.

Use Kotlin, coroutines, StateFlow, and Compose for Activities. A conventional
View is sufficient for the small non-focusable accessibility overlay. Preserve
the user's IME. No `SYSTEM_ALERT_WINDOW`, hidden APIs, replacement keyboard,
or whole-field `ACTION_SET_TEXT` fallback.

## Boundaries

| Module | Responsibility |
| --- | --- |
| `app` | Activities, accessibility/input tracking, overlay, capture service, settings, composition root |
| `core` | Pure Kotlin session contracts, reducer, destination eligibility, and policies |
| `inference-whisper` | Pinned native runtime, JNI, model lifecycle, progress, cancellation |
| `network` | Optional recognition and cleanup transports; no Android UI or screen access |
| `editor-fixture` | Separate application exposing real editors and controlled mutations |

Keep Android objects out of the domain layer. The accessibility service owns
editor access and overlay lifetime; the recording service owns capture. One
process-scoped coordinator serializes session events. Use manual constructor
injection or a small container, not a global Activity reference.

## Delivery and cancellation

Capture destination authority at recording start. Invalidate it on relevant
editor, content, selection, composition, lifecycle, and focus changes.
Recheck before dispatch using the current input connection. Consume one attempt
before invoking the public accessibility commit API. Successful dispatch is
unconfirmed delivery, and must never trigger an automatic fallback mutation.

Stop releases capture then transcribes; Cancel invalidates the session and
releases its resources. Native cancellation must finish before unloading a
context. Audio stays in owned private temporary storage. No content in Intents,
diagnostic logs, analytics, or default history.

## Local and remote recognition

Begin with fake recognition. Later pin `whisper.cpp` to an exact revision and
a verified multilingual model; no native moving-branch builds or placeholder
hashes. The accepted [comparison increment](../decisions/0003-small-model-comparison.md)
adds a pinned multi-profile catalog and explicit sequential comparison without
making phone accuracy or latency claims. CPU/arm64 is the reference phone path. Measure tiny/base/small rather
than promising latency or Pixel accelerator support.

The accepted [GPU experiment](../decisions/0005-vulkan-device-experiment.md)
adds opt-in Vulkan and same-recording CPU/GPU comparison. Native inference
runs in a bound, non-exported worker process, one fresh process/context per
pass. CPU disables Vulkan registration; GPU failure is explicit. Keep one
small content-free restart checkpoint, with no transcript/audio history.

Local dictation makes no network calls. Remote STT and cleanup are independently
configured and opt-in, with exact HTTPS endpoints, bounded responses, safe
secret storage, no cross-origin credential forwarding, and no automatic POST
retry. Cleanup failure retains raw text.

## Evidence

Follow the [build gates](../plan/vertical-slice-build-order.md) and
[device-validation procedure](../testing/device-validation.md). Build success,
API compatibility, emulator behavior, physical-device integration, and speech
quality are different claims. Keep native 16 KiB page-size verification in the
release gate when native libraries exist.

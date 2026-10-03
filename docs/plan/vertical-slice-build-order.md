# Vertical slice build order

The [canonical Android specification](android-dictation/spec.md), sections 4
and 18, defines the gates. Follow this sequence; each increment records what
works, what was checked, what remains unverified, and the next ticket.

| Order | Ticket | Exit condition |
| --- | --- | --- |
| 01 | [Native scaffold](android-dictation/issues/01-native-scaffold.md) | Pinned build, minimal app, separate editor fixture, local/CI compilation |
| 02 | [Editor authority and fake insertion](android-dictation/issues/02-editor-authority.md) | Real accessibility API, selection replacement, stale-target and composition guards |
| 03 | [Overlay and microphone spike](android-dictation/issues/03-overlay-microphone.md) | Physical-device Gate A; direct or visible-Activity recording route documented |
| 04 | [Session and audio lifecycle](android-dictation/issues/04-session-audio-lifecycle.md) | Serialized coordinator, cancellation/race coverage, owned ephemeral audio |
| 05 | [Offline inference](android-dictation/issues/05-offline-inference.md) | Gate B: pinned runtime/model and offline language/duration/cancellation evidence |
| 06 | [Usable first APK](android-dictation/issues/06-first-apk.md) | Gate C: model manager, onboarding, recovery, privacy, acceptance matrix |
| 07 | [Optional remote adapters](android-dictation/issues/07-remote-adapters.md) | Gate D: independent STT/cleanup contracts, raw fallback, local behavior preserved |
| 08 | [Measured refinement](android-dictation/issues/08-measured-refinement.md) | Gate E: measured improvements and regression evidence |

Local source work may continue when hardware is unavailable, but Gate A and B
remain unverified. Keep the visible recording Activity fallback and explicit
Insert path available. A debug APK is not a signed release or store acceptance.

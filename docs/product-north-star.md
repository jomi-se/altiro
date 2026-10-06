# Product North Star: good dictation stays available

Established: 2026-10-03.

Altiro makes high-quality Android dictation available as free, open-source
software. Its everyday value must survive without a subscription, trial, word
allowance, account, or company-operated service.

## The daily interaction

The maintainer's daily Pixel 7 use is the initial product reference: technical
English and Chilean Spanish, with French also supported. See the accepted
[productization brief](plan/productization.md) for setup, model acquisition,
overlay, motion and console requirements and their rationale.

Keep using your preferred keyboard in another app. Tap a floating microphone,
speak naturally, stop, and receive useful text at the intended cursor or
selection. English, French, and Spanish technical conversation are the initial
quality targets. Pixel 7 is the reference physical device, not a claim that all
devices or editors have passed compatibility testing.

## Ownership and cost

Local transcription is the first distributable product, works offline after
model installation, and has no business usage limit. Explicit recording limits
protect device resources and must be documented. Optional remote recognition
or cleanup uses a user-selected endpoint and its compute or provider charges;
the free app does not promise free hosted inference.

No analytics, advertising, broad screen reading, default transcript history, or
always-listening microphone. Explain exactly which data leaves the phone when
the user enables a remote mode.

## Quality means trust as well as recognition

Preserve language, intent, negation, numbers, names, and meaningful repetition.
Measure recognition quality separately from text cleanup. Cleaned text must
not invent, answer, translate, or silently change what the speaker meant.

The app must avoid putting text in the wrong field, duplicating an uncertain
insertion, replacing an entire message, or sending a message. When the original
destination becomes invalid, retain the result and offer explicit Insert or
Copy. Prefer an honest, visible recovery path.

## Implementation direction

Follow the [Android specification](plan/android-dictation/spec.md). Establish
Android input and microphone behavior with a fake recognizer before integrating
local inference. Keep Gboard selected. Prove the interaction on hardware and
publish compatibility claims only to the extent supported by evidence.

This is an independent product with its own identity and implementation. Other
dictation apps are interaction references; their private services, code, assets,
or unverified model choices are not dependencies.

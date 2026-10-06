# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Design first for the maintainer's daily dictation on a Pixel 7: English and
Chilean Spanish, including technical conversation, while using their existing
keyboard in other apps. Other Android users benefit from the same portable
product; device compatibility still needs evidence.

## Product Purpose

Altiro is free, open-source Android dictation that stays useful without an
account, subscription, word allowance, or hosted recognition service. Speak,
stop, and receive useful text at the intended cursor with minimal interruption.

## Operating Context

Everyday use happens in another app through a small floating microphone.
Installation, model selection, experiments, and diagnostics live in Altiro's
own screens. Initial setup includes microphone permission, accessibility
access, and an explicitly requested model download. Recognition works offline
after installation. Keep the user's preferred keyboard selected.

## Capabilities and Constraints

- Native Kotlin, coroutines, StateFlow, Compose, and a bounded Android View
  overlay; C++ is limited to inference/JNI.
- Stock models support explicit one-tap in-app acquisition from pinned HTTPS
  sources. Experimental Chilean variants remain selectable through verified
  import; direct acquisition needs an audited public distribution destination.
- Use dynamic audio windows up to the model's normal 30-second context.
- Automatically insert ordinary dictation when its original destination is
  still valid. A changed destination requires explicit recovery. Dispatch is
  not proof of delivery; never retry an uncertain insertion.
- Stop transcribes; Cancel discards and releases resources. Comparison runs
  never insert automatically.
- Audio is ephemeral, transcript history is absent by default, and diagnostic
  exports exclude speech, audio, editor identity, and private paths.
- Keep language changes within reach on the bubble without opening the app.
- Include a dedicated console for live phases, timings, runtime failures,
  saved checkpoints, and explicit diagnostic export.

## Brand Commitments

The name is Altiro. The user requests an expressive, playful app with purposeful
motion and few text labels. Its floating control must be almost transparent
and unobtrusive when idle. Accessibility names and necessary setup explanations
remain available even where visible labels are reduced.

## Evidence on Hand

[Current work](docs/plan/current-work.md) records user-reported phone results
and distinguishes them from automated checks and unpassed device gates.
The current interface is a functional experiment, not an approved visual
baseline. No formal Chilean conversational accuracy evaluation exists yet.

## Product Principles

1. Make useful dictation available without a business gate.
2. Keep the speaker in their editor; reduce repeated actions.
3. Protect destination authority and avoid duplicate or misplaced text.
4. Make activity and failures understandable without exposing dictated content.
5. Offer advanced experiments without making everyday use an experiment console.

## Authorities

The [north star](docs/product-north-star.md),
[specification](docs/plan/android-dictation/spec.md), and
[productization brief](docs/plan/productization.md) contain the canonical
requirements and rationale. This record summarizes them for design tools.

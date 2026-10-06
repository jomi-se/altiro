# From recognition preview to everyday dictation

Status: accepted product direction; implementation is tracked in
[current work](current-work.md). Established: 2026-10-06.

## Primary use

Design for the maintainer's daily Pixel 7 use first: technical English and
Chilean Spanish, ordinary editors, and the existing keyboard. A successful
session is tap, speak, stop, inserted text, with no trip through Altiro's main
screen. Preserve the input-authority protections from the specification.

## Choices and their reasons

| Choice | Why | Acceptance evidence |
| --- | --- | --- |
| One-tap model download inside Altiro | The recognizer is the app's essential component. Browser/file-picker choreography makes installation feel incomplete. | Download progress, cancel, low-storage and offline recovery; size/hash verification and atomic install; recognition works in airplane mode afterward. |
| Include Chilean Small as an explicit experimental choice | Chilean Spanish is a daily quality need. The user finds the fine-tune promising, but its training label is not an accuracy result. | Both precision choices remain identifiable; source/license attribution; converted artifacts downloadable and verified; conversational Spanish evaluation remains separate. |
| Keep the idle bubble almost transparent and compact | Dictation supports another app. Its controls must not cover the editor or demand constant attention. | Test over light/dark content, keyboard, rotation and dragging. Transparent decoration does not shrink the 48 dp touch targets. |
| Change language from the bubble | English/Spanish switching happens during composition. Opening the app interrupts the thought and can invalidate the destination. | Change the next session's language without focus transfer; active sessions retain their start-time language. Auto/EN/ES/FR stay available. |
| Use icons, color and purposeful motion with fewer labels | The current experiment screen exposes too much machinery. Common actions should be recognizable at a glance. | Accessible names for every icon; sufficient contrast; no color-only state; font scaling and reduced animation behavior; concise text for permission and recovery decisions. |
| Automatically insert normal dictation | Requiring a second confirmation on every result defeats the daily interaction. | Valid original destination receives one dispatch; changed field/cursor/composition, password, lock or cancellation blocks it. No whole-field replacement or retry after uncertain delivery. |
| Dynamic audio windows, capped at 30 seconds | Short clips should not pay for encoding a full padded window. | Record selected native window and encoder calls; compare identical audio; preserve all audio for longer recordings and evaluate clipped endings/accuracy. |
| A small, unmistakable working animation | Long inference currently looks stalled. The user needs to know whether recording, verification, model loading or recognition is happening. | Distinct recording/processing states, elapsed time and accessible phase text; Cancel always available; animation stops when work ends and respects system animation settings. |
| Console inside the app | Phase timings made the performance bottleneck obvious. Advanced diagnostics must remain easy to extract as the product becomes simpler. | Live and final per-pass timings, backend/window/attention details, saved crash checkpoint, Copy/Share/Clear. No speech, audio, editor names or private paths. |
| Bold, expressive app; quiet overlay | The user chose a playful visual direction. The main app can have personality while the overlay respects the host app. | A coherent native Material 3 theme in light/dark; setup, models, daily controls and console separated by purpose. |

## Installation and model distribution

Explain why microphone and accessibility access are needed before sending the
user to system settings. Show completed steps, a clear next action, model size,
and whether the selected model is ready offline. An existing verified model
must remain usable after a failed download or import. Never start a download
merely because the app or bubble opened.

The owner chose in-app download rather than embedding large model files in
every APK. Acquisition may use the Internet; local recording, recognition and
insertion must not. Keep file import for offline transfer and experimentation.
Make download hosts and model attribution inspectable.

Stock converted Base/Small models have pinned HTTPS files. The current Chilean
entries link to the original training-model repository, not downloadable
Whisper.cpp conversions. Publish reviewed converted artifacts with licenses
and exact hashes before marking their one-tap installation complete. Do not
silently download an incompatible training checkpoint or a different model.

## Information architecture

- **Daily use:** readiness, selected model/language and one recording action.
- **Setup:** permission/accessibility steps and the essential first model.
- **Models:** selection, size, download/import, verification and deletion.
- **Console:** phase traces and export, with comparisons and runtime experiments
  available as advanced tools rather than occupying the daily home screen.

The bubble exposes microphone/Stop, Cancel while busy, and language. Recovery
actions appear only when needed. Automatic insertion still uses the public
accessibility input connection; editor differences do not authorize a silent
fallback that replaces a field or changes the keyboard.

## Repository and public readiness

Borrow portable practices from the other public projects: Markdown product
authority, explicit decisions, pinned CI actions with read-only permissions,
Dependabot, a checksum-pinned Gitleaks installer, full-history and staged secret
scans, and private vulnerability reporting. Keep local design tools ignored.
Do not copy their web stack or private deployment instructions.

Before the first push, inspect every historical tracked blob and commit
metadata for personal information, machine paths, setup/configuration,
credentials and generated artifacts. Secret scanning is one check, not a
complete privacy audit. GitHub publication/settings and operator-owned pushes
are separate from local implementation. A configured empty public repository
does not prove its CI has run or that an APK is released.

## Implementation sequence

1. Record this direction, acquisition policy, and public repository protections.
2. Establish the visual direction with native Android affordances.
3. Add verified, cancellable model acquisition and a concise setup flow.
4. Replace the experiment-heavy home, then make the overlay compact with
   language control and state-specific motion.
5. Preserve comparisons and diagnostic exports in the console.
6. Build, inspect actual phone/emulator captures, and test the editor/resource
   gates before claiming daily-use or general-device readiness.

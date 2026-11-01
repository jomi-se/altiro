# 0009 — Open Termux confirmation bypasses the settings lock

Status: in-progress; source correction and SDK-free verification complete,
Android compilation/lint and phone acceptance pending.

## Reproduction and source evidence

Open the Termux paste confirmation while idle, background the app, begin
dictation from the overlay, then return before the work finishes. The
originating toggle is disabled while busy, but the already-open dialog's
Enable action can still write the preference and refresh editor state.

[Decision 0012](../decisions/0012-interface-rewrite.md) locks configuration
during work. Disable confirmation when unavailable and check the current
session, recognition and model-transfer busy values before changing the
preference. Dismissing the dialog remains available.

This is a settings-lock defect, not evidence that stale or wrong-field
insertion guards can be bypassed. Preserve those guards and the existing
clipboard warning and explicit opt-in.

## Acceptance evidence

- While idle, confirmation can enable the existing preference.
- While session, recognition or model work is busy, the action is disabled
  and a queued callback refuses the change using current values.
- Compile and lint the Android sources.
- On a device, exercise idle acceptance and the background/overlay/return
  sequence. Host compilation does not execute this UI scenario.

The source now disables Enable using the screen's availability state and
rechecks all three current busy values before writing. Independent source
review, Kotlin formatting, repository/model checks, Python tests and
core/network JVM tests pass. Android compilation and lint still need an
Android SDK; the phone checks above have not been executed.

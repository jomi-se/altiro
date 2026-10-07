# 0003 — Home shows a fixed Chilean model shortcut

Status: local fix; phone acceptance pending.

## Reproduction and impact

Select a stock model in Models, then return Home. The selected-model capsule
updates, but a separate navigation row still says “Chilean Small”. The maintainer
reports two apparent selection surfaces, one stuck on the Chilean model, and
questions whether the intended model is running after weaker dialect results.

## Cause and correction

The second row is a hardcoded navigation shortcut, not a second selection
store. Remove it: Home keeps one capsule bound to `ModelStore.selected`; Models
keeps its radio picker. Full and compact diagnostic exports now include
`Selected model now` separately from the frozen recording trace's `Models`.
Changing settings after a recording must not relabel the model that processed it.

Source tracing confirms selection writes the shared preference and StateFlow;
ordinary recording captures that profile and its catalog filename before audio
capture. The batch verifies that file's size and SHA-256 against the same profile
before the worker passes its path through JNI to Whisper. Each pass creates a
fresh context, and no model substitution or fallback is present in this path.
This is source evidence, not observation of the maintainer's phone session.

## Acceptance

- Home has one model-selection entry; its name matches Models' selected radio.
- Switch between installed stock and Chilean profiles, restart Altiro, and
  confirm the choice persists.
- Record once with each: the recording trace's `Models` must match the choice
  at recording start. Changing selection afterward changes only `Selected model
  now`, not the previous trace.
- Missing or corrupt selected files must block recognition rather than select
  another model.

Chilean idiom quality is a separate, unresolved report. It needs the actual
recording configuration and comparable speech evidence; the hardcoded label
does not establish its cause.

## Host verification

The repository verification command passes: 50 core and 8 acquisition tests,
Android builds/lint, native packaging/notices and instrumentation APK
compilation. Existing batch tests exercise per-profile verification, distinct
model passes and prevention of native load for a corrupted selected file.
Physical selection persistence, visual acceptance and Chilean speech quality
remain phone checks.

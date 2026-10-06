# Architectural decisions

The [Android specification](../plan/android-dictation/spec.md) establishes the
initial choices. No additional decision records are needed to restate it.

- [0001 — Offline preview after initial phone smoke](0001-offline-preview.md):
  bounded local-model continuation with remaining hardware gates explicit.
- [0002 — Recording in place by default](0002-record-in-place-default.md):
  explicit preview default with visible recording fallback.
- [0003 — Small model comparison](0003-small-model-comparison.md):
  separate imports, in-app selection, and ephemeral same-recording comparison.
- [0004 — Recognition diagnostics](0004-recognition-diagnostics.md):
  content-free phase traces with separate startup verification timings.
- [0005 — Vulkan device experiment](0005-vulkan-device-experiment.md):
  isolated native workers, explicit backend selection and device evidence.
- [0006 — Flash Attention experiment](0006-flash-attention-experiment.md):
  reversible GPU attention configuration and context diagnostics.
- [0007 — Dynamic audio windows](0007-dynamic-audio-window.md):
  bounded short-clip contexts with same-recording comparisons.
- [0008 — Explicit model acquisition](0008-explicit-model-acquisition.md):
  one-tap verified downloads, separate from offline recognition.
- [0009 — Everyday native interface](0009-everyday-native-interface.md):
  approved visual direction and the actual overlay's interaction priorities.
- [0010 — Foreground-start teardown](0010-foreground-start-teardown.md):
  acknowledged startup before abandoned capture, with microphone checks retained.
- [0011 — Local vocabulary hints](0011-local-vocabulary-hints.md):
  bounded, explicit names and terms frozen per recording, with content-free diagnostics.

Record meaningful departures or newly resolved forks as numbered Markdown
files with title, status, date, context, decision, consequences, and evidence.
Use `proposed`, `accepted`, or `superseded`; a proposal does not authorize an
architecture change.

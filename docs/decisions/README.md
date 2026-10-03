# Architectural decisions

The [Android specification](../plan/android-dictation/spec.md) establishes the
initial choices. No additional decision records are needed to restate it.

- [0001 — Offline preview after initial phone smoke](0001-offline-preview.md):
  bounded local-model continuation with remaining hardware gates explicit.

- [0002 — Recording in place by default](0002-record-in-place-default.md):
  explicit preview default with visible recording fallback.
- [0003 — Small model comparison](0003-small-model-comparison.md):
  separate imports, in-app selection, and ephemeral same-recording comparison.

Record meaningful departures or newly resolved forks as numbered Markdown
files with title, status, date, context, decision, consequences, and evidence.
Use `proposed`, `accepted`, or `superseded`; a proposal does not authorize an
architecture change.

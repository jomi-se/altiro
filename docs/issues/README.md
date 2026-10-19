# Confirmed defects

- [0001 — Invalid workflow context](0001-invalid-workflow-context.md): local fix
  validated; execution of the corrected public workflow remains pending.
- [0002 — Overlay visibility and recovery](0002-overlay-visibility-and-recovery.md):
  maintainer reports a missing chat-field bubble and ineffective restore action;
  compatibility diagnosis and native design correction are pending.
- [0003 — Misleading model selection](0003-misleading-model-selection.md):
  remove Home's fixed Chilean shortcut; selected and recorded model identifiers
  are separate in diagnostic exports. Phone confirmation remains pending.
- [0004 — Encoder lab backend registration](0004-encoder-lab-backend-registration.md):
  0.6.1 rejected GPU cases before worker startup; 0.6.2 corrects the frozen
  diagnostic plan. Native GPU execution is confirmed by the next phone report.
- [0005 — Encoder lab phase capacity](0005-encoder-lab-phase-capacity.md):
  0.6.2 exhausted the ordinary 64-phase trace during a multi-model lab run;
  0.6.3 uses an explicitly bounded lab budget. Phone completion remains pending.

Use focused numbered Markdown files with status, reproducible behavior,
expected behavior, impact, and acceptance evidence. Index new defects here.
Accepted feature work belongs in [plans](../plan/vertical-slice-build-order.md).

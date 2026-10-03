# Domain documentation

Altiro has one application domain. Read the authorities in `AGENTS.md` and
applicable decisions in `docs/decisions/` before exploring.

If root `CONTEXT.md` exists, use its vocabulary. Create it only when real
terminology needs clarification; do not invent another copy of the spec.

Keep these distinctions precise:

- Recognition turns audio into text; cleanup transforms that text.
- Stop finalizes a recording for recognition; Cancel discards the session.
- A destination token authorizes a particular editor state, not an app forever.
- Dispatch means an insertion call occurred; it does not confirm delivery.
- Local mode and model download are separate network/privacy operations.
- A build, emulator check, device gate, and distributable release are different
  evidence claims.

Record architecture conflicts with their decision ID and evidence. Do not
silently override an accepted decision or reopen choices settled in the spec.

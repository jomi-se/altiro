# Repository guidance

## Product boundary

Altiro is a free, open-source, local-first Android dictation app. Preserve the
user's default keyboard. On-device recognition must remain useful without an
account, a subscription, a remote service, or a business word quota. Optional
remote recognition and text cleanup are separate, explicit user choices.

Read these authorities before making scope or architecture decisions:

1. `docs/product-north-star.md` for product intent.
2. `docs/plan/android-dictation/spec.md` for implementation requirements.
3. `docs/architecture/implementation-defaults.md` for repository defaults.
4. `docs/plan/current-work.md` and `docs/plan/vertical-slice-build-order.md`
   for implementation state and the next increment.

The imported specification is guidance, not implementation evidence. Record a
verified constraint or changed decision in `docs/decisions/` before departing
from it. Never silently change the architecture to hide a platform failure.

## Current scope

The current milestone is an offline recognition preview using a pinned native
runtime and verified multilingual base model import, with a separate editor
fixture app. Initial phone recording, cancellation, and insertion are
user-reported; full Gate A and B remain unverified. Read
`docs/decisions/0001-offline-preview.md` for this bounded continuation.
Chilean Spanish is an explicit quality target; evaluate specialized models
separately rather than assuming their training labels establish quality.

Keep the implementation native: Kotlin, coroutines, StateFlow, Compose for
ordinary screens, and a small View for the accessibility overlay. Use C++ only
for the inference/JNI boundary. Start with `app`, `core`, `inference-whisper`,
`network`, and the separate `editor-fixture` application.

Do not introduce desktop targets, a replacement keyboard, always-listening
audio, broad screen extraction, cloud accounts, billing, automatic sending,
automatic cross-provider failover, or a mandatory hosted backend.

## Android correctness

- Compile against public APIs. Accessibility `commitText(text, 1, null)` returns
  no success flag; dispatch does not prove delivery.
- Keep one serialized session owner. A stale result, changed field, cursor,
  composition, password field, cancellation, or lock must block automatic
  insertion. Never revive an invalid destination token.
- Consume one insertion attempt before dispatch. Do not retry uncertain
  insertion or use whole-field replacement as a fallback.
- Preserve Stop versus Cancel. Release capture before transcription; connect
  cancellation to native work before freeing native handles.
- Treat microphone foreground-service startup from the overlay as a device
  gate. Use the specified genuinely visible Activity fallback when necessary.
- Do not lower target SDK, use hidden APIs, or alter permission policy to make
  an unverified interaction appear to work.
- Keep audio ephemeral, history absent by default, and remote cleanup off by
  default. Preserve raw text on cleanup failure.

## Validation and commands

Use `./scripts/verify.sh` for formatting checks, core tests, Android compilation,
lint, and instrumentation APK compilation. `--core-only` runs JVM checks without
an Android SDK; `--docs-only` checks the documentation foundation. See
`docs/development.md`. Compiling test APKs does not execute device tests.

For routine non-interactive checks use `scripts/quiet-run.sh`, which delegates
to shared `quiet-run` when installed and otherwise runs the command directly.
Use shared detached runs for slow checks; collect the exact returned handle
and clean retained failure logs after investigation. Keep reads bounded and
format once near final verification.

Test observable behavior and costly failure modes: wrong-field insertion,
duplicate text, leaked microphone/native resources, and unintended networking.
Emulator results supplement physical-phone evidence. Do not call Gate A or B
passed without the hardware evidence specified in the canonical plan.

## Documentation and local tracking

- Product authority: `docs/product-north-star.md`.
- Architecture: `docs/architecture/`.
- Accepted decisions: `docs/decisions/`.
- Accepted plans and implementation tickets: `docs/plan/`.
- Confirmed defects: `docs/issues/`.
- Unaccepted proposals: `docs/ideas/`.
- External research: `docs/research/`.
- Review findings: `docs/reviews/`.

Follow `docs/agents/issue-tracker.md`, `docs/agents/triage-labels.md`, and
`docs/agents/domain.md`. Markdown is canonical; no external issue tracker is
required. Update the earliest source of truth that changed and keep
`docs/plan/current-work.md` useful for the next session.

`CLAUDE.md` imports this file. Keep durable cross-harness guidance here.
Local skill installations are optional and ignored; do not copy unrelated web
or desktop workflows into Android guidance or claim an absent skill is installed.

## Public repository and artifacts

Keep product requirements and portable examples here. Private cross-project
sequencing belongs outside this repository. Machine configuration and operator
runbooks belong in the operator's bootstrap repository. Generated APKs,
recordings, screenshots, benchmark output, and dated execution logs belong
outside Git; reference sanitized conclusions here.

Never stage personal hostnames, tailnets, IP addresses, emails, tunnel IDs,
machine names, absolute home paths, credential locations, live request IDs,
signing keys, bearer secrets, private dictation, or device serial numbers.
Use reserved example endpoints. Keep `local.properties`, SDK installations,
Gradle caches, models, and all build/run output out of Git. Clean temporary
files created by the current task as soon as they are no longer needed.

## Git and publication boundary

Review, stage, and commit completed work. Push actions are operator-owned.
Never run `git push` or use tools to write remote refs, synchronize repositories,
publish releases, upload APKs, or submit to a store without separate explicit
authorization. A commit is not publication approval.

Do not weaken the operator's passphrase-protected SSH key, change credentials,
or use an ambient token/helper to bypass that boundary. Inspect staged content
for private identifiers; stage explicit paths when scopes are mixed.

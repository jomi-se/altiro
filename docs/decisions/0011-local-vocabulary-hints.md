# 0011: Optional local vocabulary hints

Status: accepted. Date: 2026-10-06.

## Context

Specification section 10.4 requires a small editable vocabulary for names and
technical terms. The everyday preview has not implemented that requirement.
Technical English and Chilean Spanish are the primary use cases; spelling
hints may help uncommon terms, but their effect on recognition must be measured.

## Decision

Provide an optional names-and-terms editor in Setup, empty by default. Store
only explicitly entered terms in private application preferences, with backups
already disabled. Do not import contacts, read surrounding editor text or collect
clipboard contents. Clearing the list disables hints.

Accept at most 100 terms and 4 KiB of UTF-8 prompt data. Preserve Unicode names,
reject control characters and malformed Unicode, and validate before saving.
Freeze the immutable vocabulary with the model and language at recording start;
every comparison pass uses the same snapshot. Block changes while work owns a
session. Diagnostics may report counts and typed failure categories, never words.

Pass the list as Whisper's initial recognition prompt using correctly encoded
UTF-8 bytes across JNI. It supplies optional recognition context, never forced
transcript replacement, cleanup, instructions to a separate language model or
additional network access. Keep empty vocabulary equivalent to the existing
recognition path. Apply the same native cancellation and resource ownership.

The pinned Base/Small decoder retains only the last 223 prompt tokens; a large
list can therefore lose earlier hints even within the storage limit. Upstream
may drop hints for a final window with under five seconds left. Preserve that
anti-hallucination behavior. Explain that short lists work best and report only
the configured term count, without claiming every word reached every window.

## Evidence required

Test UTF-8/count limits, normalization, invalid input and snapshot immutability
in the core suite. Exercise actual production JNI with empty/nonempty hints,
Unicode, speech, silence and cancellation. Compile the app and instrumentation
after the IPC/JNI contract changes. Phone tests must check spelling quality,
no dictionary dump in silence/noise, persistence, clearing and busy-state editing.
No accuracy improvement or physical-device acceptance is claimed from the feature
implementation alone.

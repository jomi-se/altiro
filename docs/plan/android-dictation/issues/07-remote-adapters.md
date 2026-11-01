# 07 — Optional remote recognition and cleanup

Status: in-progress
Blocked by: 06

## Authority

[Specification](../spec.md), sections 12–13; [build order](../../vertical-slice-build-order.md).

## Deliverable

Independently configured opt-in modes with exact HTTPS endpoints, bounded
multipart recognition and non-streaming cleanup contracts, protected secrets,
and no screen or clipboard content. Preserve useful local-only behavior.

## Acceptance evidence

- Mock-server checks cover request shape, language fields, redirects, bounds,
  authentication, throttling, interrupted upload, and cancellation.
- No automatic POST replay, hidden remote fallback, or cross-origin secret forwarding.
- Real configured self-hosted endpoint smoke test when available.
- Cleanup failure retains raw text; suspicious changes require review.
- Spoken instructions remain data; names/numbers/negation/languages evaluated.
- Local dictation stays network-isolated and requires no remote configuration.

## Comments

The fixed self-hosted recognition adapter is implemented under
[decision 0025](../../../decisions/0025-remote-first-local-fallback.md). Android
build/phone acceptance is tracked in current work; independent cleanup and
general provider credentials remain outside this increment.

# 07 — Optional remote recognition and cleanup

Status: ready-for-agent
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

No execution evidence recorded yet.

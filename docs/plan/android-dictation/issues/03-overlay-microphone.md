# 03 — Overlay and microphone lifecycle spike

Status: in-progress
Blocked by: 02

## Authority

[Specification](../spec.md), sections 4 and 7; [build order](../../vertical-slice-build-order.md).

## Deliverable

Implement the small non-focusable accessibility overlay, explicit recording
controls, microphone foreground service, and genuinely visible recording
Activity fallback. Preserve Gboard. Handle startup restrictions, permission
and notification denial, cancellation, lock, and service disable.

## Acceptance evidence

- On physical Pixel 7, the overlay preserves editor focus and default keyboard.
- Direct microphone startup is tested on the actual OS and target SDK.
- When denied, visible Activity starts capture while resumed and invalidates
  automatic destination authority; later insertion is explicit at a fresh target.
- Stop/Cancel, lock, and service disable release real capture promptly.
- Startup/permission failures provide truthful recovery controls.
- Gate A and the chosen recording route have physical evidence, or remain
  explicitly unverified. No SDK downgrade or hidden-API workaround.

## Comments

Source implemented in the integration spike. Core behavior tests and compiled
Android APIs support source correctness. Physical/editor/lifecycle evidence
remains unverified; see [Gate A](../../../testing/gate-a.md).

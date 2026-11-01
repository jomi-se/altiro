# 0015: Show the recording bubble independently of insertion identity

Status: accepted correction; physical acceptance pending. Supersedes the
Copy-mode direction in [0014](0014-copy-only-editor-recovery.md).

## Evidence

The maintainer corrects the failed surfaces to ChatGPT search/composer, Reddit
search/comments, Twitter comments and Termux's terminal. The 0.5.5 trace contains
persistent connected TEXT input without a focused node, and RAW input with a
focused noneditable node. These failures can outlast the bounded settle probes;
the ring does not identify each app. A timing race is possible, not established.

The maintainer rejects new Copy controls and prioritizes getting the actual
recording bubble to appear. The source currently couples visibility and recording
startup to insertion identity. This is a sufficient explanation for persistent
absence when input exists but its accessibility node is missing or noneditable.

## Correction

A recording host is separate from an insertion identity. With a started TEXT/RAW
input, show the ordinary mic when its package positively matches the visible,
input-focused application root on the default display and either a current input
connection or a visible default-display keyboard window exists. A present node
must be focused, visible and in that matching package/window; contradictory nodes
fail closed. Password metadata, lock and hidden apps still hide the mic. A host
with late/no connection grants recording only, never insertion.

Use the existing mic, language, working, Stop/Cancel and result controls. Remove
the proposed Copy label, Copy primary action, delivery mode and recorder extra.
No additional result step or new clipboard behavior is introduced. A host-only
result disables the existing insertion arrow and reports unavailable insertion
instead of repeatedly asking the user to refocus the field they already selected.
The mic starts recording in place through the existing service route; the explicit visible
Activity fallback remains available. Hide/Undo/Restore works with recording-host
metadata, without recreating a destination token.

Keep focused-node identity, connection, selection/composition, stale-result and
single-attempt checks for insertion. RAW inputs never acquire insertion identity.
A recording begun without identity cannot later insert automatically merely
because another field appears. Existing explicit result recovery remains intact.
No terminal text or editor actions are sent as a visibility workaround.

## Timing and diagnostics

[Android InputMethod source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/accessibilityservice/InputMethod.java)
assigns its connection before invoking onStartInput. It also invokes onFinishInput
before clearing that connection. A connected INPUT_FINISH trace therefore does
not prove a live destination survived finish. Focused nodes/window metadata can
arrive independently; retain the bounded per-input settle sequence and real
window/focus probes. Never preserve an old destination across finish or switch.

Trace root visibility, keyboard visibility and recording-host availability separately from node
and connection facts. `RECORDING_AVAILABLE` explains why the mic may appear
without claiming insertion eligibility. Sensitive-view fixture fields test the
possible node-withholding mechanism; no accessibility-tool declaration change,
hierarchy traversal, text collection or content polling is added.

## Acceptance

Core checks cover unavailable TEXT nodes, RAW hosts, late connection with visible
keyboard, contradictory nodes, password/lock/hidden/window/display restrictions
and lack of insertion authority. Existing session/insertion regressions remain.
Host compilation is separate from phone execution. Test mic appearance and
recording startup on every reported surface, plus native/Compose/password fixtures,
Hide/Restore, delayed focus, keyboard closure and app switches. Insertion delivery
and Home frame pacing remain separate physical gates.

# 0014: Copy-only recovery for unavailable and raw editors

Status: accepted implementation direction; phone acceptance pending.

## Evidence and scope

The maintainer's 0.5.5 trace has a started TEXT input, connected input connection
and matching input-focused application window, but no node from either focused
lookup. A separate RAW input has a focused, visible, matching-window node marked
noneditable. The maintainer identifies the failed surfaces as ChatGPT search and
composer, Reddit search and Termux's terminal; Reddit comments work. The report
does not map every retained event to an app, and its 48-event ring omits the
earliest attempts. All five models are verified and ready.

The remaining failure is not evidence for changing permissions, model loading,
the selected keyboard or `isAccessibilityTool`. Android may withhold sensitive
views from this general-purpose service. The source review found that platform
focus lookup falls back to a provider's host when the provider has no focused
virtual node. A sensitive focused view, including one under an ancestor that
filters obscured touches, can instead be omitted entirely. This is a plausible
explanation for the missing nodes, not a confirmed diagnosis of those apps.
See the primary [Android focus controller](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/AccessibilityInteractionController.java)
and [View sensitivity rules](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java).

## Keep focused lookup; recover through Copy

Do not ship the proposed root-tree walk: it cannot recover withheld sensitive
nodes and adds cross-process work on large chat hierarchies. Retain decision
0013's focused-node lookup and bounded settling. Read no extra editor content,
change no accessibility-tool declaration and never substitute accessibility focus.
Automatic insertion still requires a positively identified editable node; RAW
inputs are explicitly excluded even if a host reports itself editable.

Separate an overlay recording host from an insertion identity. A started TEXT
or RAW input with a live connection, a visible application root and a positively
input-focused, matching-package application window on the default display may
show a Copy recording bubble when insertion identity is unavailable. If a node
exists it must additionally be focused, visible and in that matching window and
package; a contradictory node cannot be ignored. Lock, password metadata and
hidden apps still hide it. Withheld nodes do not become insertion destinations.

This deliberately lets a connected input show a Copy mic even without node
focus evidence. The keyboard connection and focused window provide recording
context, not insertion permission. The tradeoff is a possible extra mic on a
custom input that cannot accept pasted prose. A custom PIN pad that fails to
expose password metadata can also qualify for Copy; no programmatic delivery is
allowed there. No editor text is read or delivered.
Terminals use the same Copy path; there is no app-package allowlist.

Freeze COPY_ONLY delivery at recording start. The core reducer and the final
dispatch boundary both prohibit insertion for that session, including after
switching to a normal field. Show a Copy primary action on completion; clipboard
write remains an explicit tap. Never auto-copy, perform editor actions, send keys
or normalize terminal text into an assumed safe command. Termux's public input
connection forwards text to its terminal, so ordinary text dispatch is not the
same operation as inserting into a message composer.

Normal identifiable editors keep default automatic insertion. Recording-host
metadata is private and never grants insertion authority. Hide/Restore uses
that host only for preferences, never to revive insertion authority.

## Acceptance

Test missing-node and raw-input recording eligibility, contradictory nodes,
password/lock/hidden/display/connection gates, copy-only dispatch rejection after
field switches and normal delivery regressions. Build/lint/native packaging and APK compilation remain host evidence.
Recheck ChatGPT search/composer, Reddit search/comments and Termux terminal/input
box on the Pixel. Verify no terminal text appears until explicit clipboard paste,
password/lock behavior remains closed, and recording motion stays smooth.

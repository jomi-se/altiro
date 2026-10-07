# 0017: Paste at the cursor and recover from the bubble

Status: accepted implementation direction; physical acceptance pending.
Refines [0016](0016-automatic-delivery-and-bubble-controls.md).

## Why

The maintainer reports that ordinary empty fields show “Finish typing” and
that removing the bubble's Copy action made failed insertion much harder to
recover from. Missing composition callbacks are not evidence of an unfinished
word. Dictation must also continue existing text at the cursor; field emptiness
is not a delivery requirement.

## Ordinary text delivery

Connected TEXT destinations select paste automatically. Stage the transcript
on the sensitive clipboard, refresh destination authority, then choose one
public paste transport before consuming the single attempt: the matching
focused node's advertised `ACTION_PASTE`, or the live accessibility input
connection's `performContextMenuAction(android.R.id.paste)` when no such node
is available. Do not follow an uncertain paste with commit, replacement or
another paste. Do not simulate menus, change focus or read existing field text.

The target editor owns its normal cursor/selection and paste behavior. There
is no empty-field check, append-by-whole-field-replacement, inferred word
completion, or automatic cursor movement. Existing text remains in place;
an explicit selection follows the editor's normal paste semantics. Leave
spacing to the transcript and editor rather than guessing from unseen text.

For this paste route, absent composition/selection callbacks and a stable
pre-existing composition do not themselves block delivery. Composition,
selection, text, destination and input-epoch changes after capture still
invalidate the token. Direct COMMIT retains its composition checks because
it can replace a composing region. Password, lock, disabled app, cancellation,
display and single-attempt restrictions remain for every route. RAW terminal
input remains separate; ordinary paste support is not assumed for Termux.
The callback-arrived knowledge bit is excluded from paste-token comparisons;
learning unchanged ranges must not manufacture a destination change. Actual
composition/selection ranges and revisions remain part of the authority.

The public input-connection paste call returns no delivery acknowledgement.
Record its dispatch separately from node paste and do not claim text arrived.
Clipboard restoration remains best-effort as described in 0016.

## Bubble recovery

Keep one-tap Copy on the bubble whenever a completed transcript remains,
including unconfirmed dispatch. A blocked result makes the primary control
Copy, with a separate discard action. A dispatched result keeps the normal
microphone and a secondary Copy control. Copying does not retry insertion,
changes no host text, releases the retained session only after clipboard write
succeeds, and returns the bubble to its microphone with a brief “Copied” notice.
An explicit discard also releases a retained result. No app navigation is
required. Measure and clamp the full window before showing recovery controls.
Only a blocked result extends visibility beyond a live input; unconfirmed
dispatch retains secondary Copy whenever the ordinary bubble is eligible.
Hidden-app preferences, password fields and lock still hide the overlay.

## Evidence

The [Android API](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection#performContextMenuAction(int))
exposes the paste context action from API 33.
Android's [TextView implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java)
routes accessibility paste through the editor's regular paste operation.
These interfaces establish an available implementation path, not compatibility
with every custom provider. Verify empty and populated fields, selected text,
stable pre-existing composition, cursor/field changes during recognition,
single dispatch, Copy after blocked/unconfirmed delivery and clipboard failure.
Device execution remains required.

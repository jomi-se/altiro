# 0016: Automatic delivery and a microphone-first bubble

Status: accepted implementation direction; physical acceptance pending.
Refines [0015](0015-recording-bubble-visibility.md) and the
[upstream comparison](../research/android-overlay-compatibility.md).

## Why

The maintainer rejects an extra insertion step after successful dictation,
accidental language changes and the clipped result controls. The latest trace
shows a matching live input and visible keyboard rejected because its fallback
node lacks focus. Requiring a useful accessibility node for every connected
text editor makes custom composers unusable.

## Destination and delivery

For TEXT input, a live accessibility input connection, started editor metadata
and the matching visible focused application window can anchor an insertion
identity even when node focus/editability is incomplete. Input start/restart/
finish, service epoch, editor events, selection/composition and window/package
changes still invalidate captured authority. A present node from another package
or window, or password metadata, blocks it. Known selection/composition and no
active composition remain required for ordinary text delivery. No screen tree
traversal, text extraction or accessibility-tool declaration change is added.

Prefer `commitText(text, 1, null)`. An advertised standard paste action on a
positively focused matching custom node selects paste before dispatch. Never
try commit and then paste because delivery appeared unsuccessful. Consume one
attempt before either mutation; a void API return is unconfirmed delivery.
Successful dispatch returns the overlay to its microphone without confirmation.

Termux's exact package, RAW/known character-mode input and focused noneditable
host receive a
separate terminal identity and paste route, never ordinary character commits.
Its [pinned terminal client](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java#L249)
handles Ctrl+Alt+V by calling its real clipboard paste method. Dispatch that
shortcut through the public accessibility input connection. This is a bounded
paste protocol, not menu automation or arbitrary commands. Hardware shortcut
configuration can disable it; disabled shortcuts instead reach the terminal as
control input. Altiro cannot read that private setting. The route therefore
starts off and requires a one-time Settings confirmation that Termux's hardware
shortcuts are enabled. After that confirmation, delivery is automatic per
recording. Without a delivery acknowledgement its outcome remains unconfirmed,
and no alternate mutation is attempted. Termux's node reports the base Android
View class; do not require the Java implementation class in accessibility metadata.

Terminal paste flattens line breaks and strips control characters. Never send
Enter, editor actions, terminal escape sequences or dictated control codes.
Terminal selection/composition does not have ordinary text-editor semantics;
validate the same input epoch, package, window and focused terminal before
dispatch. The terminal's internal application still controls how paste behaves.
Clipboard payloads are sensitive and never logged. Paste replaces the clipboard;
Android may deny background clipboard reads, so restoration/expiry cannot be
promised. Restore a prior readable clip
after the grace interval only if the current clip is still our uniquely labelled
payload; never overwrite a newer user clip or clear a prior unreadable clip.

Known non-application window geometry no longer invalidates a destination by
itself. Every such event still refreshes live editor/window authority. Application
and unknown window changes remain conservative; focus, selection and text events
from the input app still invalidate. Input restarts and focus transitions cannot
revive an old token.

## Bubble interaction

The mic is the largest target and stays on its docked edge. Language is a
secondary 48 dp target separated from the primary, opening an explicit menu for
Auto, English, Español and Français. The menu is part of the same non-focusable
accessibility overlay. Busy work closes it and freezes the recording language.
No pop-up Activity, keyboard switch or new focused window is introduced. Near
the keyboard the menu opens above the mic; closing restores its prior position.

Remove Copy/Insert/Discard from the bubble's successful result flow. Retain a
blocked result in Altiro with a brief overlay notice and access to the app;
starting another recording must not silently overwrite that result. Keep
distinct Stop and Cancel during work. Measure and clamp the whole window before
changing its size so edge docking cannot clip added controls or language choices.

## Diagnostics and acceptance

Console copies a bounded compact report and shares the full content-free report
as a private-cache file with a temporary URI read grant. No automatic sharing
or dictation content enters either report. Record selected delivery route and
fixed block/outcome codes without packages, IDs or editor text.

Verify no duplicate or wrong-field delivery after changes, restart, cancellation,
composition or lock; terminal control-character handling; clipping and language
targets; and report bounds. Compile instrumentation fixtures separately from
physical execution. ChatGPT/Reddit/Twitter and Termux delivery remain phone
gates until the maintainer tests the update.

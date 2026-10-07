# 0002: Missing floating microphone and ineffective recovery

Status: in-progress

## Report and impact

The maintainer reports that the floating microphone does not appear when focusing
ChatGPT's chat field. The action to restore hidden apps flashes on tap and gives
no other confirmation or visible recovery. The floating control's visual and
interaction quality is also rejected. The tested build still needs confirmation;
do not infer a broad editor compatibility result from this report.

This blocks the primary interaction: dictating in another app without changing
the keyboard or visiting Altiro.

The maintainer also reports a missing bubble in Termux's terminal view, while its
separate text input box does show it. Upstream [TerminalView source](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java)
uses a custom input connection with `TYPE_NULL` in one mode, and ordinary text
input metadata for the alternate view. This supports an input-surface difference;
it does not identify the actual failed Altiro gate on the maintainer's version.

On 0.5.4 the maintainer reports that resetting storage, completing setup and
reimporting models does not restore the bubble in ChatGPT's composer, Termux's
terminal or Reddit search. A supplied export has connected accessibility,
zero hidden apps and last reason `NO_FOCUS_NODE`. This establishes persistence
after fresh setup; it does not establish an identical failed gate in all three
editors. Imports performed after startup are absent from startup timing rows.

## Source evidence and uncertainty

At the reported 0.5.4 source baseline, idle overlay visibility requires both a connected
accessibility input method and a stable `EditorIdentity`. Identity requires a
focused, visible, editable accessibility node matching the input method package,
and either a nonzero field ID or an exposed node identifier. A field missing any
of these properties can hide the control. No device trace yet identifies which
condition failed in the reported chat editor.

The earlier Restore action clears the stored hidden-app set and refreshes the
editor without confirmation or a visible count; 0.5.4 adds both. It cannot resolve missing editor identity or
input-connection support. Clearing hidden preferences is not proof that a bubble
appeared, and the reported failure must not be dismissed as a successful write.

## Required correction and acceptance

- Make visibility and insertion eligibility separately understandable. Never
  weaken destination identity, cursor/composition, lock or password protections
  merely to display a microphone.
- Show actual hidden-app state and meaningful restore feedback, including when
  there are no hidden apps or the accessibility service is disconnected.
- Provide content-free eligibility diagnostics that distinguish hidden,
  disconnected, locked, password, unsupported display and unidentifiable editor
  cases without exporting editor names, IDs, text or paths.
- Recheck the reported chat editor and a separate native/Compose fixture with the
  normal keyboard, in both themes. Confirm restoration after intentional hiding
  and confirm no automatic insertion into an ambiguous or changed destination.
- Review actual rendered overlay states and touch behavior with the maintainer;
  previous concept approval and source review do not establish acceptance.

## Comments

The maintainer requests a new design consultation and a rewrite of the app's
configuration and console surfaces. Track that structural proposal separately;
navigation simplification alone does not fix editor compatibility.

Version 0.5.5 corrects the active-window-only lookup and adds bounded delayed
checks plus a content-free visibility timeline. See
[decision 0013](../decisions/0013-focused-editor-lookup.md). Termux still needs
positive focused/editable identity; no insertion guard is relaxed. Physical
execution and the three reported editor results remain pending.

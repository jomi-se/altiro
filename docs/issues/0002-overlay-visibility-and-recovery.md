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

Version 0.5.5 phone feedback identifies two failures: connected TEXT input with
no accessible focused node, and connected RAW input with a focused noneditable
host. ChatGPT search/composer and Reddit search/comments fail, according to the
maintainer's follow-up correction;
Termux's terminal still fails. The limited ring cannot assign each timestamp to
an app. All five models are now verified, so further storage resets are not a
recovery step.

The maintainer adds Twitter comments to the failed surfaces and rejects the
proposed Copy-mode direction. Version 0.5.6 instead corrects mic visibility and
startup independently of insertion identity. See
[decision 0015](../decisions/0015-recording-bubble-visibility.md). Matching started
input with a connection or visible keyboard can show the ordinary mic; positive
window/package and password/lock/hidden gates remain. There is no extra Copy UI.
A race remains possible, but the repeated node/editability gate itself explains
persistent absence. Phone visibility/startup confirmation is pending.

The 0.5.6 follow-up confirms that the mic appears in Termux's main terminal,
while insertion there is unavailable. ChatGPT's supplied capture has the keyboard
open without a mic. The Reddit search capture has no visible keyboard, so that
image alone does not establish a keyboard-up failure. The new trace repeatedly
rejects a matching, visible TEXT input with a connection and visible keyboard
because its fallback node reports `OTHER`, `focused=false`. The recording-host
focus requirement remains too strict; it is distinct from insertion authority.
The exported report is truncated when pasted; the responsible clipboard or
receiving-editor limit is not yet identified.

At the maintainer's request, investigate upstream implementations before another
patch. [Pinned source research](../research/android-overlay-compatibility.md)
finds independent keyboard/focus visibility signals, bounded pre-dispatch
recovery, and explicit terminal limitations. Apply the useful patterns within
Altiro's insertion contract; upstream source does not pass our phone gates.

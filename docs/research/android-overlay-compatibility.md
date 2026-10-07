# Android floating dictation: upstream compatibility patterns

Checked: 2026-10-07. Source inspection, not execution of these apps. Revisions
below pin the inspected implementations; they do not establish compatibility
with the maintainer's installed chat apps or OS. No upstream code is imported.

## Closest implementations

| Repository and checked revision | License file | Relevant implementation |
| --- | --- | --- |
| [EdiBianco/OpenWhispr](https://github.com/EdiBianco/OpenWhispr/tree/a668b696b66d55f2f2be05fbe3411a03151767f1) · `a668b696` | [Apache-2.0](https://github.com/EdiBianco/OpenWhispr/blob/a668b696b66d55f2f2be05fbe3411a03151767f1/LICENSE) | Input focus plus keyboard visibility; attached hidden overlay; periodic focus check |
| [trevornk/ramblr](https://github.com/trevornk/ramblr/tree/463ea5ee9eb2acaa42766e7345b565a1f6a80939) · `463ea5ee` | [GPL-3.0](https://github.com/trevornk/ramblr/blob/463ea5ee9eb2acaa42766e7345b565a1f6a80939/LICENSE) | Overlay availability separate from insertion targeting; terminal/custom-paste candidates; bounded empty-target recovery |
| [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper/tree/e6518cda437ed2c266f36cf476db857fa2690d87) · `e6518cda` | [Apache-2.0](https://github.com/kafkasl/phone-whisper/blob/e6518cda437ed2c266f36cf476db857fa2690d87/LICENSE) | Terminal/custom-paste classification and multiple insertion strategies |
| [codewith-rafi/clario](https://github.com/codewith-rafi/clario/tree/5f06269538a4b1320eb59c5a950284370f5fc5b8) · `5f062695` | [MIT](https://github.com/codewith-rafi/clario/blob/5f06269538a4b1320eb59c5a950284370f5fc5b8/LICENSE) | Debounced focus, periodic visibility check, keyboard-window fallback for a custom composer |

Several implementations have similar targeting code. Treat them as examples of
patterns, rather than four independent demonstrations that a strategy works.

## Mic availability when a composer exposes incomplete focus

OpenWhispr combines an accessibility focus signal with IME visibility using OR.
Its [service](https://github.com/EdiBianco/OpenWhispr/blob/a668b696b66d55f2f2be05fbe3411a03151767f1/app/src/main/kotlin/com/edib/openwhispr/WhisperAccessibilityService.kt#L224)
checks focus on events and every 500 ms. A `WindowInsets.Type.ime()` listener
supplies the keyboard signal. The overlay remains attached while invisible and
non-touchable, so hiding it does not remove its insets observer. Adding only an
insets listener to an overlay that Altiro removes while hidden would miss this
part of the design. Whether these insets reliably reach our overlay on the
reference phone remains a device question.

Ramblr's [visibility policy](https://github.com/trevornk/ramblr/blob/463ea5ee9eb2acaa42766e7345b565a1f6a80939/app/src/main/kotlin/com/trevornk/ramblr/OverlayVisibility.kt)
uses foreground-app, hidden and lock state without requiring an editable node.
It keeps the mic available more broadly than Altiro's input-triggered design.
The transferable idea is separate recording availability and insertion authority;
adopting its entire visibility policy would change our product behavior.

Clario combines [50 ms focus debounce](https://github.com/codewith-rafi/clario/blob/5f06269538a4b1320eb59c5a950284370f5fc5b8/android/app/src/main/kotlin/com/example/clairo/ClairoAccessibilityService.kt#L140)
with a [400 ms overlay check](https://github.com/codewith-rafi/clario/blob/5f06269538a4b1320eb59c5a950284370f5fc5b8/android/app/src/main/kotlin/com/example/clairo/overlay/FloatingOverlayManager.kt).
Its [WeChat detector](https://github.com/codewith-rafi/clario/blob/5f06269538a4b1320eb59c5a950284370f5fc5b8/android/app/src/main/kotlin/com/example/clairo/util/WeChatInputDetector.kt)
also uses `TYPE_INPUT_METHOD` windows when normal focus signals are absent.
That application's delivery is explicitly clipboard-only; it is evidence for
keeping a mic available with incomplete node metadata, not proof of automatic
insertion into ChatGPT, Reddit or Twitter.

Clario also requests `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`. Android's
[public API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_INCLUDE_NOT_IMPORTANT_VIEWS)
documents that this includes otherwise unimportant views and their events.
Altiro currently omits it. It is a candidate experiment, not an established
explanation for the failed fields or a reason to traverse their screen content.

## Terminals and paste are a separate delivery capability

Ramblr and phone-whisper recognize `TerminalView` and custom action labels
containing “paste”. Their insertion paths attempt custom or standard
accessibility paste actions. Both nevertheless warn that Termux's main terminal
is nonstandard and recommend its alternate input box:
[Ramblr compatibility notes](https://github.com/trevornk/ramblr/blob/463ea5ee9eb2acaa42766e7345b565a1f6a80939/README.md#termux),
[phone-whisper compatibility notes](https://github.com/kafkasl/phone-whisper/blob/e6518cda437ed2c266f36cf476db857fa2690d87/README.md).
Their terminal classifier is not proof of working automatic terminal paste.

Termux revision `8629e632fcb95da272221be327db653fb24befe9` clarifies the distinction:

- [TerminalView](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalView.java#L309)
  can advertise `TYPE_NULL`; its custom input connection sends committed
  characters to the terminal. This class does not implement an accessibility
  `ACTION_PASTE` handler.
- [TermuxTerminalViewClient.doPaste](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java#L792)
  reads the clipboard and calls the emulator's paste method.
- [TerminalEmulator.paste](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java#L3541)
  wraps text in bracketed-paste sequences when the terminal enables that mode;
  character commits do not take this path.

Altiro cannot call an internal method in another app. Inspect actual exposed
actions on the focused terminal and test their delivery before selecting a paste
adapter. Do not simulate menus, type escape sequences, send Enter or retry an
uncertain dispatch to pretend a paste capability exists.

The character path also converts newlines into Enter and control characters
into terminal input. A raw terminal cannot inherit ordinary editor selection/
composition guarantees merely because a connection exists. Keep RAW outside
ordinary insertion identity; any terminal delivery needs its own contract.

## Recovery worth learning from

Ramblr's [recovery policies](https://github.com/trevornk/ramblr/blob/463ea5ee9eb2acaa42766e7345b565a1f6a80939/app/src/main/kotlin/com/trevornk/ramblr/InjectionRecovery.kt)
allow a single delayed rescan before insertion when no candidate exists. Its
clipboard cleanup leaves a newer clipboard value alone. These are useful
pre-dispatch timing and clipboard-ownership patterns, distinct from retrying a
mutation whose delivery is uncertain.

Other inspected paths recursively search windows, read existing field text,
replace entire fields or fall back after an uncertain paste. Altiro's
[insertion contract](../architecture/implementation-defaults.md) excludes those
behaviors. Ramblr's GPL implementation is a research reference; any reusable
ideas need an independent implementation compatible with Altiro's license.

## Application to the reported Altiro failure

The 0.5.6 phone trace repeatedly has started TEXT input, a connection, a visible
keyboard and a matching visible application/window. Its fallback node is visible
and in the correct package, but reports `OTHER` and `focused=false`. Altiro's
recording-host gate still requires that node's focus bit, rejecting the mic for
longer than the settle interval. This is a persistent gate mismatch; a separate
timing race is not ruled out.

The next implementation should:

1. Use live started-input, matching focused application/window and keyboard or
   connection evidence for recording availability. A matching host's missing
   focus/editability bit must not by itself veto the mic. Preserve password,
   lock, hidden-app, display, window and package restrictions.
2. Keep strict insertion tokens independent. Showing a mic must not grant an
   unidentifiable host automatic insertion or revive a stale destination.
3. Retain bounded event-driven settling first. Only add an attached insets
   observer or periodic check if a measured missed-signal case needs it.
4. Treat terminal paste as a separate, capability-tested increment. No extra
   Copy UI is introduced as a substitute for fixing visibility.
5. Provide a compact copied report and a complete user-initiated diagnostic
   file export. The truncated paste does not establish an Android-wide
   clipboard size limit; file export avoids depending on that limit or the
   receiving editor's text limit.

See [the active defect](../issues/0002-overlay-visibility-and-recovery.md).
These are research-backed implementation targets, not shipped corrections or
passed physical-device gates.

A separate source review agrees with the visibility diagnosis and highlights
the remaining delivery gap: removing the mic veto will not make an unknown
node a valid insertion destination. A possible future identity could instead
anchor to a fresh input-method epoch, editor metadata, known selection/composition
and the matching focused window. This needs a separate decision and fixtures
for same-window field switches, reused field IDs, delayed callbacks and provider
restarts before changing the current insertion contract. It is a proposal, not
an implemented fallback or a demonstrated upstream solution.

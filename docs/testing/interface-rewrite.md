# Interface rewrite: phone checks

Status: pending physical execution. Source compilation does not prove these
interactions or visual quality. Keep native captures and observed results outside
Git, and use synthetic text instead of personal chat content.

## Navigation and controls

- Open Home, Models, Settings and Console directly from the same navigation bar.
  No Setup toggle or second Console activity should cover another page.
- In Settings, change language, processor, Flash Attention and dynamic windows.
  Verify persistence after reopening. Start dictation and confirm runtime choices
  are frozen and cannot be changed while capture/native cleanup is active.
- Edit Names & terms; Cancel preserves the saved list and Save changes it.
  Verify the keyboard does not cover Save, including with font scale 1.3.
- Confirm permissions explain their purpose when incomplete and show concise
  completed status afterward. Long descriptions must stay out of the default
  Settings and Console surfaces.

## Missing bubble and restore

- Focus ChatGPT's chat composer, then inspect Settings's last-editor reason.
  Returning to Altiro must preserve that external editor reason. Export Console
  diagnostics and record the precise build/version separately.
- Repeat with Termux's terminal view and its separate text input box. Record the
  difference without assuming both implement ordinary text-field semantics.
- On 0.5.5, also focus Reddit search. Copy includes a visibility transition
  timeline even with no dictation. Compare input-start/settled lookup, node and
  focused-window facts, then return to Altiro: the last external input snapshot
  must remain intact. Clear must remove that snapshot and timeline. Never export
  app/node names, dictated content or offsets.
- Stream a reply or terminal output after the 1.5-second settle budget. There
  must be no continuing CONTENT/SETTLED probe loop. Recording/native progress
  ticks must not generate repeated STATE tree probes. Normal editor focus/text/
  selection/window changes must still invalidate an old insertion destination.
- Intentionally hide an eligible app, restore that one app, and restore all.
  Check the count and visible confirmation. With no hidden apps, check the
  explicit empty-state feedback. Revisit the editor to confirm actual visibility.
- Disconnected accessibility, a password field, lock and unsupported destination
  must remain distinguishable from a manually hidden app. Restoration must not
  revive an old insertion token or cause a delayed insertion.

## Bubble visibility in 0.5.6

- Update without clearing storage. Models and settings must remain installed.
- Focus ChatGPT search/composer, Reddit search/comments, Twitter comments and
  Termux terminal/input box. The ordinary mic should appear for started matching
  TEXT/RAW input even without an editable node. No new Copy-mode UI should appear.
- Tap the mic in each surface, verify the microphone indicator, then Cancel.
  Repeat recording/Stop separately; startup must stay in place when permitted.
- Switch apps, close/reopen the keyboard and move between fields rapidly.
  The bubble must follow the live recording host without retaining stale insertion
  authority. Returning to Altiro preserves the external visibility snapshot.
- Export Console diagnostics if the mic stays absent: keyboardVisible and
  recordingHost are separate from lookup/node/connection facts. Allow the bounded
  settle period, and confirm it does not become continuous content polling.
- Hide an idle recording host, Undo and Restore in Settings. Verify real visibility.
- Check native/Compose fixtures and the API 34+ sensitive/filtering fields.
  Password, lock, contradictory app/window and unsupported display must hide it.
- Verify recording begun without insertion identity never inserts automatically
  after moving to a different field. RAW terminal input must never receive an
  automatic commit. Existing explicit result recovery remains unchanged.
- Recheck Home recording smoothness; the 0.5.5 frame-clock change remains in place.

## Overlay and Console

### Paste delivery and bubble recovery in 0.5.8

- Focus an ordinary native/Compose field, dictate and Stop. Valid unchanged
  destinations receive one editor-owned paste; the bubble returns to its mic
  with secondary Copy available. Confirm text arrival separately from the
  `PASTE_NODE_DISPATCHED_UNCONFIRMED`/`PASTE_INPUT_DISPATCHED_UNCONFIRMED` code.
  Missing composition callbacks must not block an empty field. Continue at the
  end and middle of existing text, and replace an explicit selection. A stable
  pre-existing composing word must remain intact; no whole-field replacement
  or inferred empty-field requirement is permitted.
  With Gboard, type a word without a trailing space, dictate, then type one
  more character. Verify that neither the typed word nor the pasted transcript
  is swallowed or rewritten by the keyboard. The fixture assertions do not
  establish this real-keyboard behavior.
- Repeat on ChatGPT, Reddit and Twitter with blank fields and after typing.
  Test same-window field switches, cursor changes, composition, keyboard close,
  app switches and lock during recognition. Changed destinations retain
  the transcript; they must not receive an automatic mutation. Copy must be
  immediately available from the bubble after blocked and unconfirmed delivery,
  including a provider ignoring paste. Copy releases the result only after its
  clipboard write succeeds; the next recording must work without opening Altiro.
  Discard must release a blocked result without changing host text. No recovery
  tap may retry insertion. Keep Copy usable if the original input disappears.
  Only a blocked result may extend visibility after the input disappears;
  successful/unconfirmed dispatch must not leave a bubble over the launcher.
  Copy reappears with its retained result in an eligible input. A hidden app
  must stay hidden even when a result is retained. A late composition callback
  with identical ranges must not invent a destination change.
- Tap language: a menu opens, with Auto/English/Español/Français and the current
  choice marked. Only tapping a choice changes language. Mic is the larger
  edge target. Menu opening must keep the keyboard and editor focus; recording
  closes the menu and freezes language. Check font scale 1.3 and both docks.
- Enable Settings → Termux paste only with Termux hardware shortcuts enabled.
  In its focused main terminal, paste a synthetic multiline transcript.
  Verify one paste, flattened newlines, no Enter and no commands sent. Test the
  setting off: no shortcut may be sent. Do not enable it with Termux's hardware
  shortcuts disabled, which would send control input instead of pasting.
  Its alternate text box stays an ordinary editor.
- Record and Cancel/Stop near both screen edges, open/close language choices,
  rotate and move the keyboard. No left result sliver or clipped Cancel/menu
  should appear during window resizing. Check minimum usable viewport sizes.
- Console Copy is at most 8,000 characters and includes delivery reason,
  visibility and timings. Share attaches a complete text file readable by the
  selected receiving app. The provider must expose no recordings or models.
- Clipboard-based delivery must leave a newer user clip alone; when both the
  prior clip and staged ownership are readable, check best-effort restoration.
  Ordinary TEXT now uses the clipboard too; this is a deliberate transport
  change. Explicit bubble Copy leaves the transcript available for manual paste.

These are pending physical checks, not executed device results.

- Capture idle, recording, recognition, cancellation, result recovery and failure
  over synthetic light/dark editors with the keyboard visible. Evaluate the
  actual control against the approved concept, not a generated screenshot.
- Check the language menu, 48 dp secondary actions, dragging without accidental recording,
  edge docking, rotation/insets, Stop versus Cancel and disabled animations.
- Recognition must use the work indicator without a misleading enabled mic.
  Insertion still requires the original valid destination and one consumed
  attempt; the ordinary keyboard stays selected.
- In Home, observe recording motion through several revolutions and Stop.
  The arc must run smoothly at the device's display cadence, preserve phase
  into recognition and stop when idle/offscreen. Repeat with animations disabled.
- Console opens on the latest phase timings. Copy/Share include runtime details,
  current/last editor reason codes and hidden count without editor identifiers,
  speech, vocabulary, audio or private paths. Extra logs stay secondary.
- Re-run the affected [Gate A](gate-a.md) lifecycle/editor cases; do not treat a
  visibility correction as broad compatibility acceptance.

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

## Copy recovery in 0.5.6

- Update without clearing storage. Models and settings must remain installed.
- Focus ChatGPT search/composer, Reddit search/comment and Termux terminal/input
  box. Allow the existing two-second settle period. An identifiable editor keeps
  automatic insertion; unavailable or RAW editors show a small Copy label.
- In each Copy surface record a short synthetic sentence, Stop, then tap the
  primary Copy icon. No text may appear in the host before the user explicitly
  pastes. Copy clears the completed session and permits another recording.
- Start in Termux's terminal, switch to a normal comment field while processing,
  and confirm the result still offers Copy and never inserts. Repeat after using
  the visible recorder fallback; delivery mode must remain Copy-only.
- Hide a Copy-only host with the idle drag action. Undo and Settings Restore must
  recover visibility, without granting an insertion destination.
- In the fixture (API 34+), test the explicit sensitive editor and obscured-touch
  filtering parent. A live connection may show Copy while the node is withheld.
  Compare with the ordinary native and Compose fields. This is a mechanism test,
  not proof of third-party implementation details.
- Verify a password, lock, lost input connection, another app window or unsupported
  display cannot offer a recording host. Clear and Console Copy must still work
  before recording; `COPY_ONLY` plus node/input/window facts identifies recovery.
- Recheck Home recording smoothness; the 0.5.5 frame-clock change remains in place.

## Overlay and Console

- Capture idle, recording, recognition, cancellation, result recovery and failure
  over synthetic light/dark editors with the keyboard visible. Evaluate the
  actual control against the approved concept, not a generated screenshot.
- Check one-touch EN/ES, 48 dp actions, dragging without accidental recording,
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

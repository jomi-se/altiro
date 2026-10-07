# 0012: Overlay-first revision and flat configuration

Status: accepted implementation direction; native acceptance pending.

## Trigger

The maintainer rejects the first everyday overlay and layered configuration/
console surfaces. ChatGPT's composer has no bubble; Termux's terminal also has
none while its separate input box does. Restore hidden apps flashes without
feedback. Explanatory text obscures settings and diagnostics. The newest priority
is the actual cross-app bubble, followed by Settings, then Console.

## Choices and reasons

- Preserve the approved sage/chalk/charcoal world. Amend decision 0009's overlay
  mechanics: one quiet capsule and one backed mic, short phase text inside the
  language slot, and a progress ring replacing the mic while recognizing.
  Ordinary processing should not resemble an available recording action.
- Mirror the capsule with its dock so the primary stays at the outer edge as
  phase text appears. Stop and Cancel remain separate, reachable actions. Idle
  material rests after inactivity; system-disabled animations remain respected.
  Rest fades material rather than the entire layout, keeping text/glyphs opaque.
  Theme-specific fill floors preserve calculated contrast over black/white editor
  endpoints; actual rendering and patterned backgrounds still need phone review.
- Replace hidden language long-press with idle drag-to-hide and an explicit
  accessibility Hide action. A short Undo restores the preference, never an old
  insertion token. Settings exposes hidden count, per-app restoration and
  restore-all confirmation.
- Use four peer destinations: Home, Models, Settings and Console. Remove the
  Setup overlay toggle, second Console activity and old experiment-specific
  settings components. Four direct destinations avoid putting routine settings
  behind the nested pages the maintainer rejects.
- Put language, processor, attention, dynamic window and vocabulary in Settings.
  Keep same-recording comparisons in Console using one compact tool panel.
  Configuration still snapshots at recording start and is locked during work.
- Default to controls, values, state and phase timings. Longer model/settings
  descriptions and extra logs are explicit inline detail, not default prose.
  Permission rationale remains concise and contextual. Full diagnostic Copy/
  Share remain available; a second raw report does not duplicate the default
  phase table.
- Export fixed visibility reason codes and hidden count, with a separate last
  external-editor reason preserved when the user opens Altiro. No editor names,
  identifiers, dictated content or vocabulary enter diagnostic exports.

## Compatibility boundary

Do not relax insertion authority to make the bubble visible. Existing focused/
editable/visible identity, package matching, connection, display, password,
composition, selection and consumed-attempt safeguards remain authoritative.
The input-focused-window probe is diagnostic only; it does not select an
insertion target. A reason code distinguishes manual hiding from incompatible
editor exposure, so restore does not imply either compatibility or delivery.

The design consultation used the actual source and approved concept, with no
native screenshot available. This revision needs the
[phone checks](../testing/interface-rewrite.md), affected Gate A cases and the
[reported defect](../issues/0002-overlay-visibility-and-recovery.md) reproduced
before acceptance. Compilation and source review do not prove visual quality,
ChatGPT/Termux compatibility, background Activity launch or TalkBack behavior.

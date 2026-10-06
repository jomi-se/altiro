# 0009: A quiet accessibility overlay and a focused native app

Accepted: 2026-10-06.

The owner approved the centered microphone concept in a sage, chalk and charcoal
palette, with fine circular rules and subtle directional depth. The highest
craft priority is the actual non-focusable accessibility overlay over other
apps. A capsule shown inside a concept screen is only a preview.

The main app separates daily recording, models and a diagnostic console.
Setup explains microphone/accessibility access and offers explicit model
acquisition. Experiments stay in the console. Light and dark modes use the
same geometry; native text, scalable controls and accessible names remain.

The overlay exposes mic/Stop and one-touch EN/ES language switching; Auto and
French remain in the app. Busy language is frozen and visibly disabled. Cancel
is a separate target. Idle material is translucent while glyphs remain legible;
recording and recognition have distinct shape, state text and bounded motion.
Motion follows system animation settings and stops on detach. A drag never
starts/stops recording; snapping happens only after an actual drag. The primary
action has no long-press behavior that could swallow a deliberate Stop press.
Holding language reveals an explicit Hide action instead of disabling the app
during a hold-to-drag gesture. Docked controls expand away from the capsule,
which keeps the mic anchored when Cancel appears. Theme changes reapply every
overlay color, and transient callbacks are cancelled at service disposal.

Automatic insertion retains the existing one-attempt destination authority.
Window changes proven to concern Altiro's own non-focusable overlay must not
invalidate that authority. Unknown or other-window events remain conservative.
The console exposes aggregate event counts without window/editor identifiers.
A consumed insertion permits the next recording without requiring Discard;
uncertain delivery must never permit another insertion of the same result.
A typed dispatch-failure state makes the check-field warning visible; ordinary
dispatch stays quiet and still does not claim confirmed delivery.

Busy overlays request FLAG_KEEP_SCREEN_ON, clearing it on idle or detach.
Manual lock continues to cancel. Physical-device checks must establish whether
Android honors the flag on an accessibility overlay; compilation is not proof.
Keep Stop/Cancel available when the destination becomes ineligible, without
showing dictated text over a password field. Insertion remains blocked.

Model download is explicit per decision 0008. Chilean conversions stay visible
as experimental choices with file import until exact audited binaries have a
public distribution location. Repository publication and device acceptance
remain separate from implementing this interface.

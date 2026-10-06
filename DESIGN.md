---
name: Altiro
description: A quiet native microphone for everyday offline dictation.
colors:
  light-forest: "#385D48"
  light-chalk: "#F4F6F0"
  light-sage: "#DCE2DA"
  light-charcoal: "#222621"
  light-muted-ink: "#4C5C51"
  light-outline: "#78867D"
  light-selected: "#CAD8CC"
  light-on-selected: "#243C2D"
  dark-forest: "#B6D4BE"
  dark-on-forest: "#182E21"
  dark-ground: "#1C2420"
  dark-chalk: "#303A33"
  dark-ink: "#E7EEE5"
  dark-muted-ink: "#B8C5BB"
  dark-outline: "#84958A"
  dark-selected: "#354C3C"
  dark-on-selected: "#DCEAD9"
  dark-overlay-chalk: "#2A342E"
  dark-overlay-ink: "#E9F2E6"
typography:
  headline:
    fontFamily: "Roboto"
    fontWeight: 600
    letterSpacing: "-1sp"
  body:
    fontFamily: "Roboto"
  label:
    fontFamily: "Roboto"
  console-mono:
    fontFamily: "Android system monospace"
  overlay-language:
    fontFamily: "sans-serif-medium"
    fontSize: "14sp"
    fontWeight: 500
  overlay-status:
    fontFamily: "Android system sans-serif"
    fontSize: "12sp"
  overlay-hide:
    fontFamily: "Android system sans-serif"
    fontSize: "13sp"
rounded:
  status: "12dp"
  cancel: "24dp"
  overlay: "26dp"
  model-status: "32dp"
  disc: "50%"
spacing:
  tight: "4dp"
  small: "8dp"
  compact: "12dp"
  regular: "16dp"
  section: "20dp"
  screen: "24dp"
components:
  microphone-dial-light:
    backgroundColor: "{colors.light-chalk}"
    rounded: "{rounded.disc}"
    size: "248dp"
  microphone-dial-dark:
    backgroundColor: "{colors.dark-chalk}"
    rounded: "{rounded.disc}"
    size: "248dp"
  microphone-idle-light:
    backgroundColor: "{colors.light-charcoal}"
    textColor: "{colors.light-chalk}"
    rounded: "{rounded.disc}"
    size: "116dp"
  microphone-idle-dark:
    backgroundColor: "{colors.dark-ink}"
    textColor: "{colors.dark-chalk}"
    rounded: "{rounded.disc}"
    size: "116dp"
  microphone-recording-light:
    backgroundColor: "{colors.light-forest}"
    textColor: "{colors.light-chalk}"
    rounded: "{rounded.disc}"
    size: "116dp"
  microphone-recording-dark:
    backgroundColor: "{colors.dark-forest}"
    textColor: "{colors.dark-chalk}"
    rounded: "{rounded.disc}"
    size: "116dp"
  model-status-light:
    backgroundColor: "{colors.light-chalk}"
    textColor: "{colors.light-charcoal}"
    rounded: "{rounded.model-status}"
    padding: "12dp 16dp"
  model-status-dark:
    backgroundColor: "{colors.dark-chalk}"
    textColor: "{colors.dark-ink}"
    rounded: "{rounded.model-status}"
    padding: "12dp 16dp"
  overlay-capsule:
    rounded: "{rounded.overlay}"
    width: "104dp"
    height: "52dp"
  overlay-primary:
    width: "52dp"
    height: "52dp"
    padding: "12dp"
  overlay-language:
    typography: "{typography.overlay-language}"
    width: "52dp"
    height: "52dp"
  overlay-cancel:
    rounded: "{rounded.cancel}"
    size: "48dp"
    padding: "12dp"
  overlay-hide:
    typography: "{typography.overlay-hide}"
    rounded: "{rounded.cancel}"
    size: "48dp"
  overlay-status:
    typography: "{typography.overlay-status}"
    rounded: "{rounded.status}"
    width: "168dp"
    padding: "2dp 6dp"
---

# Design System: Altiro

## Overview

**Creative North Star: "The quiet microphone"**

Sage ground, chalk surfaces and charcoal controls give Altiro a calm, tactile
native identity. A forest activity marker and fine circular rules carry the
expression. The microphone is the visual center; native controls keep model
management, setup and diagnosis readable.

The actual floating accessibility overlay over another app has the highest
polish priority. Its translucent idle capsule preserves the surrounding editor,
while backed glyphs remain legible. The app's larger concentric dial belongs to
the same world without making the overlay equally large.

This document records the implemented Kotlin design system. Phone visual and
interaction acceptance is pending: source inspection does not establish rendered
fidelity, overlay legibility over arbitrary apps, font-scale fit, or hardware
gates. Generated concept images are direction references, not shipping assets.

**Key Characteristics:**

- Native Material 3 controls and system Roboto typography.
- Shared authored icon geometry across Compose and Android Views.
- Identical dial geometry in light and dark themes.
- Motion tied to recording or recognition, with a static idle state.
- Transparent idle overlay, explicit Stop and separate Cancel.

## Colors

The static light and dark palettes preserve a muted green family rather than
deriving colors from wallpaper. Frontmatter owns the exact color values.

### Primary

- **Forest** (`light-forest` / `dark-forest`): Material `primary`, recording
  discs, work arcs, completed setup marks and diagnostic timing values.
- **Forest foreground** (`light-chalk` / `dark-on-forest`): Material `onPrimary`
  for ordinary filled controls. The custom dial uses its chalk surface token
  for the glyph instead.

### Secondary

- **Selected sage** (`light-selected` / `dark-selected`): Material
  `secondaryContainer`, including the active navigation indicator.
- **Selected ink** (`light-on-selected` / `dark-on-selected`): Material
  `onSecondaryContainer`.

### Neutral

- **Ground** (`light-sage` / `dark-ground`): Material `background` and `surface`.
- **Chalk layer** (`light-chalk` / `dark-chalk`): Material `surfaceVariant`;
  dial plate, model status capsule, navigation background and trace containers.
- **Ink** (`light-charcoal` / `dark-ink`): Material `onSurface`; ordinary text
  and the resting central microphone disc.
- **Muted ink** (`light-muted-ink` / `dark-muted-ink`): Material
  `onSurfaceVariant`; secondary explanations and trace labels.
- **Outline** (`light-outline` / `dark-outline`): Material `outline`; circular
  rules and subtle dividers. Setup/model dividers use alpha 0.2, ordinary
  content dividers 0.25, and the dial's outer rule 0.3.
- **Overlay chalk and ink**: the light overlay shares the light chalk and
  charcoal tokens; its dark counterpart uses `dark-overlay-chalk` and
  `dark-overlay-ink`. These are intentional separate source values, not exact
  matches to the dark Compose surface tokens. The overlay reapplies its native
  backgrounds and foregrounds when the system night configuration changes.

Unspecified Material roles, including error colors, remain library defaults;
they are not additional Altiro palette tokens.

**The Backed Glyph Rule.** Idle translucency belongs to the overlay capsule;
the mic and language rest on more opaque circular backing. Do not make the
glyph itself almost transparent.

## Typography

**Display and body font:** native system Roboto. Compose keeps the Material 3
type scale; Altiro does not install a custom `Typography` or font asset.
**Diagnostic font:** Android system monospace for numeric timing values,
runtime traces and saved checkpoints.

### Hierarchy

- **Headline:** `headlineLarge` for app/page headings; the main app applies
  semibold weight and the frontmatter's tighter tracking. The standalone
  Console uses the unmodified role; Recording uses `headlineMedium`.
- **Title:** `titleLarge` for the recording state and the setup introduction;
  `titleMedium` for model names and console sections.
- **Body:** `bodyLarge` for a result or latest outcome, `bodyMedium` for
  explanations and trace row names, `bodySmall` for supporting details and
  monospaced traces.
- **Label:** `labelLarge` for the current model, `labelMedium` for its
  installation state, `labelSmall` for experimental and trace metadata.
- **Overlay language/status/Hide:** the frontmatter records nominal text sizes.
  Language uses Android `sans-serif-medium` and autosizes from 10 to 14 sp in
  1 sp increments; the explicit Hide action uses ordinary system sans-serif
  and autosizes from 10 to 13 sp in 1 sp increments. These compact-control
  ranges are local exceptions, not a general body-text scale. Status keeps its
  scalable native size, allows two lines and ellipsizes at the end.

Material role sizes, line heights and default tracking remain owned by the
pinned Material library, rather than being guessed or frozen here.

**The Native Type Rule.** Keep system typography and `sp` scaling. Wrap
supporting action groups when space is constrained; never replace native type
with image labels or fixed pixel text.

## Layout

The main app is a vertically scrolling single column with Scaffold insets,
screen-token horizontal padding, regular vertical padding and section-token
spacing. Scroll state is keyed by destination and Setup state, keeping a Console
scroll offset from carrying into Home. Its header places the title and Setup
action at opposite ends. The
standalone Recording and Console screens use safe drawing insets, screen-token
padding and regular vertical spacing.

The main dial is centered within a column, with section-token padding above,
tight padding below, and regular spacing between dial, state, Cancel and
language controls. Row spacing usually uses small or compact tokens; setup
steps use regular spacing. Language choices use `FlowRow` with compact
horizontal and tight vertical spacing; Home result actions and model links use
compact horizontal spacing and wrap. Console actions wrap with small horizontal spacing, and trace rows
wrap with compact horizontal and tight vertical spacing.

The overlay has a tight outer row inset. The mic and language each occupy half
the capsule and retain left-to-right order. Cancel, Copy, Discard and Hide are
separate 48 dp targets with a small-token start margin. On the right dock the
outer row reverses direction so extra actions grow left into the available
space; the root and status follow the chosen dock edge. Status sits beneath the
row and grows vertically to fit up to two lines. System-bar and IME insets constrain
the whole overlay; vertical clamping reserves the larger of the current layout
height or 60 dp plus two status line heights and 4 dp. Dock side and vertical
position persist separately by orientation. Dragging crosses Android touch slop
before moving and snaps to the nearest horizontal edge only on release of an
actual drag.

The current implementation uses a bottom navigation bar and fixed dial/overlay
geometry; no expanded-width navigation rail or layout breakpoint is implemented.
Treat tablet adaptation and narrow-screen/font-scale fit as acceptance work,
not as established responsive behavior.

## Elevation & Depth

Chalk layers, translucent material and thin outlines carry most depth. The
Compose dial alone has a soft native circular shadow: 10 dp elevation, black
ambient alpha 0.05 and black spot alpha 0.1. The bottom navigation bar explicitly
uses zero tonal elevation. The overlay draws its material and rules directly;
it has no authored shadow.

**The Quiet Depth Rule.** Preserve subtle dial elevation and tonal separation.
Do not add hard offset shadows or make every section a raised card.

## Shapes

The circle is the signature: dial, mic disc, backed overlay controls and
round-ended activity arcs. The dial's canvas is inset 14 dp; concentric rules
use 0.7 dp strokes at radius offsets 2 and 9 dp, with the inner rule at alpha
0.14. Eight evenly spaced radial ticks use 0.8 dp strokes. The active arc uses
a round-ended 5 dp stroke.

The model status capsule, overlay capsule, Cancel and status use their named
frontmatter radii. Generic buttons, chips, switches, radio buttons and dialogs
retain native Material shapes. The overlay capsule uses a 0.7 dp white rule
(alpha 85/255 idle, 110/255 busy) and a 0.7 dp ink separator (alpha 48/255).

## Components

### Floating microphone

Small enough to live in the editor, with work and recovery stated plainly.

- **Idle:** capsule chalk alpha 86/255; mic and language circular backing
  alpha 212/255, radius 20 dp. The glyph stays ink-colored. Empty status is
  hidden, and no animation runs.
- **Recording:** mic becomes a forest-backed Stop glyph with chalk foreground.
  Busy capsule alpha rises to 238/255; a round-ended forest arc uses a 2 dp
  stroke and a 38-degree sweep. Cancel appears as its own target.
- **Recognizing:** mic is disabled, the active arc widens to a 100-degree sweep,
  and status reports the current phase. Disabled mic alpha is 0.42; busy
  language alpha is 0.55 and switching is disabled. Language is fixed for the
  recording rather than silently changing the active job.
- **Recovery:** an unconsumed result changes the mic to an insertion arrow and
  shows Copy/Discard when an eligible destination exists. The status describes
  recovery; the overlay never displays dictated text. A consumed insertion
  permits a new recording without reviving the previous insertion attempt.
  Ordinary consumed dispatch remains quiet on the bubble; a typed dispatch
  failure shows "Insertion uncertain · check the field" without permitting a
  retry of the consumed result.
- **Hide:** holding the language target reveals a separate explicit Hide action
  for five seconds when idle with no pending result. Only tapping that action
  hides the mic in the current app; holding or dragging never hides it directly.
  The primary has no long-press action, so slow Stop/Insert presses still click.
- **Feedback:** Cancel, Copy, Discard, Hide and status have chalk alpha 235/255.
  Primary/language backgrounds become opaque chalk while pressed; their
  resting touch backgrounds are transparent. Messages last five seconds.
  Status is a polite live region when the phase or a recovery message changes;
  elapsed-time and progress ticks stay silent. The primary's
  state description reports its phase without announcing each elapsed second.
- **Motion:** the active arc rotates linearly once every 1,400 ms while busy.
  It stops and resets when idle or detached, and does not start when Android
  animators are disabled. Disposal removes pending delayed message redraws.
  Window resizing/repositioning is immediate.
- **Authority:** the accessibility window and controls remain non-focusable.
  The window root handles dragging even over disabled controls. A drag cancels
  the child click before movement, so moving the bubble does not
  start or stop recording. Lock closes the overlay; Stop/Cancel remain
  available during work even if the destination becomes ineligible.

### Microphone dial

A chalk circular plate surrounds the central disc and shared authored glyph
(46 dp). Idle is still; recording changes the mic to Stop, uses the primary
disc and a 28-degree arc. Recognition retains the mic silhouette, disables
the action and uses a 48-degree arc. The canvas advances the angle by 8 degrees
every 32 ms only during recording/recognition when system animators are enabled.
Otherwise the arc rests at its initial angle. Cancel stays separate from Stop.

### Shared icons

`AltiroVisuals.kt` authors a single 24-unit icon coordinate system for native
Canvas and Compose Canvas. Most strokes are 1.7 units with rounded caps and
joins; Stop is a filled rounded square. The family covers microphone, Stop,
Close, Home, Model, Console, Settings, Globe, Check, Download, Copy, Share,
Delete, forward and back. Ordinary Compose icons render at 24 dp. Keep this
family consistent rather than mixing emoji, text glyphs, icon fonts or raster
controls. Accessibility names are carried by the controls.

### Native actions and selections

Filled buttons use primary/onPrimary for explicit acquisition; outlined buttons
serve Cancel, Import and Copy; text buttons serve supporting links and setup
actions. Filter chips expose EN/ES, with Auto/FR revealed by the globe or the
current selection; their minimum height is 48 dp. Model choices use whole-row
radio semantics. Native switches express settings, and controls disable during
conflicting model/session work. Native press, focus and disabled treatments
remain library-owned unless an overlay treatment is specified above.

### Model status and acquisition

The model capsule pairs the shared Model icon, model name, Check/Download and
Offline/Install text. Models lists verified installation state and experimental
status, then selected-model description, attribution, source host and storage
need. Explicit stock downloads show progress and cancellation; file import
remains available. Experimental Chilean conversions offer import until exact
audited binaries have a distribution location. Source and License links stay
beside supporting model actions and wrap rather than being squeezed away;
opening either link is separate from choosing a download.

### Navigation and Console

Home, Models and Console use a native bottom navigation bar with chalk layer
and selected-sage indicator. Setup is a header icon action; Android Back returns
to Home from Setup or another destination. Experiments live in Console, with
ordinary recognition separated from processor/window/model comparisons.

Console uses title sections, wrapping label/value rows, forest timing values
and chalk-backed selectable monospace traces. Copy, Share and Clear are explicit
actions. Acquisition phase, startup checks, current recognition phases, aggregate
overlay event counts and saved checkpoint are visible without speech, audio or
editor identifiers. The standalone Console and Recording screens share the
same theme and icon/dial components.

## Do's and Don'ts

### Do:

- **Do** prioritize the real floating overlay over concept-screen previews.
- **Do** keep the shared geometry in both themes and preserve distinct busy,
  disabled and pending-result states.
- **Do** retain accessible names, scalable native text and separate Stop/Cancel.
- **Do** keep language within reach on the bubble and advanced controls in Console.
- **Do** verify native phone output in light/dark and scaled text, including the
  overlay over another app with the keyboard visible, before claiming acceptance.

### Don't:

- **Don't** ship generated concept images as interactive raster controls.
- **Don't** steal editor focus, turn dragging into a recording action, or animate
  idle material continuously.
- **Don't** substitute an imitation waveform for the circular activity marker.
- **Don't** turn an insertion attempt into a delivered-success claim or offer a
  second insertion of an already consumed result.
- **Don't** document untested large-screen adaptation, phone fidelity or hardware
  behavior as a completed design rule.

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
  overlay-phase:
    fontFamily: "Android system sans-serif"
    fontSize: "11sp"
  overlay-status:
    fontFamily: "Android system sans-serif"
    fontSize: "12sp"
rounded:
  status: "16dp"
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
    height: "52dp"
  overlay-primary:
    width: "52dp"
    height: "52dp"
  overlay-language:
    typography: "{typography.overlay-language}"
    height: "52dp"
    padding: "0dp 14dp 0dp 10dp"
  overlay-cancel:
    rounded: "{rounded.disc}"
    size: "48dp"
    padding: "13dp"
  overlay-status:
    typography: "{typography.overlay-status}"
    rounded: "{rounded.status}"
    padding: "6dp 12dp"
---

# Design System: Altiro

## Overview

**Creative North Star: "The quiet microphone"**

Sage ground, chalk surfaces and charcoal controls give Altiro a calm, tactile
native identity. Forest marks recording and work; fine circular rules carry
expression without adding decoration. The actual cross-app microphone has the
highest polish priority, followed by Settings, then Console. Preserve this
approved world while reducing visible prose.

The overlay uses one translucent capsule and one faint mic backing. The app's
larger concentric dial shares its icon family; ordinary configuration uses
compact native rows and controls. No new imagery or shipping image assets are
part of the rewrite.

This is a record of implemented Kotlin source, aligned with
[decision 0012](docs/decisions/0012-interface-rewrite.md). Source review found no
remaining material findings after the identified fixes. No native screenshot,
Android runtime capture or web detector ran for this documentation pass. The
rejected phone rendering remains the available visual evidence; the approved
concept establishes direction. Visual fidelity, ChatGPT/Termux compatibility,
TalkBack and affected hardware gates remain unverified. Follow the
[phone checks](docs/testing/interface-rewrite.md) before claiming acceptance.

**Key Characteristics:**

- Native Material 3 controls and system typography.
- Shared authored Canvas icon geometry in light and dark themes.
- One quiet capsule, one backed primary and unbacked language.
- Progress replaces the overlay mic during recognition.
- Four peer destinations; detail appears only on request.
- Separate Stop and Cancel, with non-focusable cross-app controls.

## Colors

The static light/dark palettes preserve a muted green family rather than
following wallpaper. Frontmatter owns exact palette values; opacity below is
applied by native drawing code.

### Primary

- **Forest** (`light-forest` / `dark-forest`): Material primary, recording
  controls, work rings and timing values.
- **Forest foreground** (`light-chalk` / `dark-on-forest`): Material onPrimary.
  Custom Stop glyphs use the corresponding chalk surface instead.

### Secondary

- **Selected sage** (`light-selected` / `dark-selected`): secondaryContainer,
  including the active bottom-navigation indicator.
- **Selected ink** (`light-on-selected` / `dark-on-selected`):
  onSecondaryContainer.

### Neutral

- **Ground** (`light-sage` / `dark-ground`): background and surface.
- **Chalk layer** (`light-chalk` / `dark-chalk`): surfaceVariant; dial plate,
  model capsule, navigation and disclosed trace container.
- **Ink** (`light-charcoal` / `dark-ink`): onSurface; text and resting dial disc.
- **Muted ink** (`light-muted-ink` / `dark-muted-ink`): onSurfaceVariant;
  secondary state and metadata.
- **Outline** (`light-outline` / `dark-outline`): dividers and circular rules.
  Settings/model dividers use alpha 0.2, Console/content dividers 0.25, and the
  dial outer rule 0.3.
- **Overlay chalk and ink**: light uses the light chalk/charcoal pair; dark
  uses `dark-overlay-chalk` / `dark-overlay-ink`. The dark overlay intentionally
  differs from Compose. Native colors reapply on night-configuration changes.

Unspecified Material roles, including error colors, retain library defaults.

**The One Backing Rule.** The overlay's primary alone receives a faint circular
backing at rest. Keep language on the capsule material; do not rebuild two discs.
Quiet rest changes material opacity, preserving opaque idle glyphs and text.

## Typography

Compose uses the native Material 3 type scale and system Roboto, without a custom
Typography or font asset. Android system monospace distinguishes timing values,
runtime logs and checkpoints. Library-owned sizes and line heights remain native.

- **Headline:** headlineLarge, semibold with the frontmatter tracking override,
  for all four destination headings; headlineMedium on the recording fallback.
- **Title:** titleMedium for sections and models; titleLarge for recording state.
- **Body:** bodyLarge for Settings row names and latest outcome; bodyMedium for
  row values and phase names; bodySmall for concise rationale and disclosed logs.
- **Label:** labelLarge/labelMedium in the model capsule; labelSmall for phase
  metadata. Bottom-navigation labels appear on the selected destination.
- **Overlay language:** nominal frontmatter size at idle, reduced to 12 sp when
  phase text is present. Native sans-serif-medium, one line, no font padding.
- **Overlay phase:** frontmatter size, one line, tabular numerals; elapsed time,
  preparation, cancellation or inference percentage sit below language.
- **Overlay notice:** frontmatter size, at most two lines with end ellipsis.
  Routine phase text stays inside the capsule; exceptional feedback uses this
  separate note. These native sizes use sp; no autosizing is configured.

**The Native Type Rule.** Keep scalable system text, accessible action names and
wrapping action groups. Native font-scale fit still requires device evidence.

## Layout

Home, Models, Settings and Console are four direct destinations in one Activity.
The native bottom bar uses authored icons and shows the selected label. Android
Back returns other destinations to Home; there is no Setup toggle or second
Console Activity. The separate visible recording Activity remains the fallback.

Each destination scrolls in a single column with Scaffold insets consumed and
IME padding. Horizontal padding is 24 dp, vertical padding 16 dp; Home has 20 dp
section spacing and other destinations 8 dp. Scroll state is keyed by destination
so a Console offset does not transfer to Home. There is no implemented tablet
rail, breakpoint or expanded-width layout.

Settings action/switch rows have a 56 dp minimum height and 16 dp internal gap.
Recognition/language chips have a 48 dp minimum height. Actions and label/value
rows wrap in FlowRow; Console rows use 12 dp horizontal and 4 dp vertical gaps.
The Names & terms field expands in place; IME padding keeps the form scrollable.

The overlay capsule is 52 dp high with a 52 dp primary and a content-sized
language/phase slot (48 dp minimum width, 10 dp start/14 dp end padding). An 8 dp
outer row inset leaves room for its shadow. Cancel/Copy/Discard are separate
48 dp circles with 8 dp gaps. The capsule and extras mirror with the dock: the
primary stays at the screen edge and phase text grows toward the interior.

Dock side and vertical position persist per orientation. Dragging crosses
Android touch slop before moving and cancels the child click. Ordinary drag
release snaps to the nearest edge. System-bar and IME insets clamp the whole
window, reserving the larger of its measured height or 64 dp plus two notice
line heights and 16 dp. Notes follow the dock, up to 232 dp wide, with a 32 dp
minimum height or 48 dp when actionable. Narrow-screen fit, rotation and actual
target reachability remain phone checks.

## Elevation & Depth

Tonal layers and fine outlines provide ordinary depth. The Compose dial has
10 dp native elevation with black ambient alpha 0.05 and spot alpha 0.1; bottom
navigation has zero tonal elevation. The overlay draws an outside-only soft
shadow (8 dp blur, 2 dp downward offset; black alpha 40/255 light, 110/255 dark).
The inset prevents authored shadow clipping in source; native rendering still
needs inspection.

Awake idle capsule chalk alpha is 172/255 light and 216/255 dark; quiet rest
reduces it to 150/255 light and 192/255 dark. Busy/feedback fill is 242/255.
Its ink rim uses alpha 26/255 light and 40/255 dark; the white inner rim uses
120/255 light and 18/255 dark. The idle mic backing is ink alpha 20/255,
increasing to 46/255 while pressed. Language has no circular backing; its pressed
slot uses ink alpha 22/255. Notices and extra controls use chalk alpha 240/255.

After four seconds of quiet idle, the capsule material transitions to its rest
fill over 240 ms and reduces its shadow alpha by 30%. Idle glyph/text alpha stays
1; the whole layout does not fade. A touch or state change cancels/resets the
material animation; editor events alone do not wake it. With system animations
disabled, rest material applies immediately after the delay. Close/dispose also
cancel/reset it. Rendered contrast over arbitrary editors remains unverified,
especially in this resting state.

**The Quiet Depth Rule.** Preserve soft dial/capsule depth and tonal separation.
Do not turn every section into a raised card or add hard offset shadows.

## Shapes

Circles and round-ended activity rings form the signature. The app dial remains
248 dp with a 116 dp central disc and 46 dp glyph. Its canvas is inset 14 dp;
concentric 0.7 dp rules sit at radius offsets 2 and 9 dp, the inner at alpha 0.14.
Eight radial ticks use 0.8 dp strokes; the active arc uses a round-ended 5 dp stroke.

The overlay capsule radius is half its height. Its rims use 0.8 dp strokes and
the inset separator is 22 dp high, at least one physical pixel wide. The sole
primary backing has a 20 dp radius; progress uses a 17 dp radius and 2.5 dp stroke.
Model capsule and note use their frontmatter radii. Other controls retain native
Material shapes; do not invent a web component shape system for this Android UI.

## Components

### Floating microphone

- **Idle:** mic glyph over faint ink backing, unbacked language and no phase
  text. Idle settles as described above; no continuous idle animation runs.
- **Recording:** forest-backed Stop with chalk glyph and a faint surrounding
  forest rule. Elapsed time appears in the language slot; Cancel stays separate.
- **Working:** a progress ring replaces the mic. Preparation uses a 90-degree
  indeterminate arc rotating linearly every 1,400 ms; inference uses a percentage
  arc with a minimum 8-degree sweep. The primary is disabled but stays at full
  local alpha; language is disabled and its text alpha is 0.5. The job retains
  its start-time language/settings snapshot. Cancel remains available while busy.
- **Pending result:** ink-backed insertion arrow; Copy/Discard appear for an
  eligible destination. Feedback may request an explicit field selection. No
  dictated text appears on the overlay. Other disabled primary states use alpha
  0.42; consuming an attempt prevents another insertion of that result.
- **Missing model:** download glyph opens Altiro, with a concise installation
  note. This is a distinct state, not a recognition action.
- **Failure:** persistent content-free feedback includes recognition failure or
  “Insertion uncertain · check the field.” Consumed uncertain insertion cannot
  be retried. Ordinary consumed dispatch stays quiet.
- **Hide:** idle/no-model dragging reveals a non-touchable drop target above the
  IME. Releasing over it hides the current app and leaves “Hidden in this app ·
  Undo” for five seconds. The primary also exposes the custom accessibility
  action “Hide in this app.” No language hold or separate Hide button remains.
  Undo restores the preference without reviving an insertion token.
- **Feedback and accessibility:** note is a polite live region. Phase changes
  and the 4:30 warning announce once; elapsed/progress ticks stay silent. Primary
  and language slot expose Button semantics, named actions and state descriptions.
  The language slot switches English/Spanish; broader choices remain in Settings.
  No primary long-press handler intercepts a slow Stop/Insert press.
- **Lifecycle:** windows/controls stay non-focusable. Root-owned dragging works
  over disabled controls and cannot trigger their click. Ordinary destination
  invalidation retains Stop/Cancel during work; lock closes/cancels. Indeterminate
  rotation stops on detach, idle or system-disabled animation; delayed callbacks
  and rest animation are cleared during disposal. Window repositioning is immediate.

### Microphone dial and shared icons

The Home/fallback dial retains its approved concentric geometry. Recording uses
Stop and a 28-degree arc; recognition disables the mic action and uses a
48-degree arc. Its angle advances 8 degrees every 32 ms only during work when
system animators are enabled; otherwise it rests at zero. Cancel stays separate.

AltiroVisuals.kt shares a 24-unit icon coordinate system between native and
Compose Canvas. Rounded strokes are usually 1.7 units; Stop is a filled rounded
square. Ordinary Compose icons are 24 dp. Use this family with accessibility
names rather than emoji, icon fonts or raster controls.

### Settings and native controls

Access rows expose microphone permission, accessibility connection, the last
external-editor reason and hidden-app count. Per-app Restore stays directly
available; Restore all produces confirmation feedback, including the empty
state. Local app labels belong only in Settings, never diagnostic export.

Recognition contains language chips, CPU/GPU chips, Flash Attention, dynamic
window and Names & terms. Switch rows carry Role.Switch; model rows carry
Role.RadioButton. Recognition chips and runtime switches disable during
session/native/model work; Flash
Attention additionally requires Vulkan. Concise permission rationale appears
when access is missing; longer guidance appears under “About these settings.”
Native press, keyboard focus, error and disabled treatments remain library-owned.

Names & terms expands a multiline Material outlined field with explicit Save,
Clear and Cancel, validation and count/UTF-8 budget feedback. Saving empty text
clears the saved list; Cancel preserves it. The field and actions lock during
session/native work. No vocabulary enters diagnostic export.

### Models and acquisition

Home's model capsule shows name and Offline/Install state. Models uses native
radio rows with size, installation and experimental state. Stock download,
verified import, transfer progress/cancel and Source/License/Delete are explicit.
Long description, attribution and storage need appear only under Details.
Experimental Chilean binaries remain import-only until audited distribution exists.

### Console and comparisons

Console leads with current outcome, post-Stop duration and phase timing rows.
Copy/Share export the full content-free diagnostic report; Clear disables during
work. Named Stop/Cancel actions remain reachable for ordinary dictation as well
as comparisons. “Startup & saved logs” reveals startup verification, fixed
visibility reason codes, aggregate window events, acquisition state and one
selectable monospace current/saved report at a time.

One inline “Compare a recording” panel offers Models, CPU/GPU and Windows modes,
order/experimental-model choices and an explicit recording action. Same audio
runs sequentially without automatic insertion; result Copy stays explicit.
Recording entry requires installed models, microphone access and no conflicting
work or pending text. Configuration lives in Settings rather than separate
experiment-specific forms.

Reason codes and hidden count do not establish editor support. The last external
reason survives opening Altiro. The input-focused-window probe is diagnostic
only; it never selects an insertion destination or weakens focus, identity,
visibility, composition, selection, password or consumed-attempt safeguards.

## Do's and Don'ts

### Do:

- **Do** prioritize the actual overlay, then Settings, then Console.
- **Do** preserve sage/chalk/charcoal, shared geometry and native scalable text.
- **Do** keep the primary at the dock edge and progress distinct from a mic action.
- **Do** use concise values and phase rows, with longer detail explicitly revealed.
- **Do** retain accessible names, separate Stop/Cancel and explicit recovery.
- **Do** capture light/dark, rest, enlarged text, animation-off and IME states on
  the phone before claiming visual or interaction acceptance.

### Don't:

- **Don't** rebuild two backed overlay discs, a Setup toggle or a second Console Activity.
- **Don't** ship concept images as interactive controls or add imitation waveforms.
- **Don't** steal editor focus or turn a drag into a recording/insertion click.
- **Don't** revive an invalid token, retry uncertain insertion or claim delivery
  from dispatch.
- **Don't** claim ChatGPT/Termux support, contrast, TalkBack, tablet adaptation or
  hardware gates from source inspection or compilation.

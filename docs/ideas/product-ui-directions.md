# Native product UI directions

Status: proposed; owner selection pending. Requirements are accepted in the
[productization brief](../plan/productization.md). These alternatives do not
change the recognition or insertion architecture.

## Shared structure

Use Material 3 navigation and components, native Back, system/keyboard insets,
48 dp controls, light/dark schemes, accessible icon names and system animation
settings. Home shows readiness, model/language and the recording action. Setup
shows only incomplete installation steps. Models contains download/import and
selection. Console contains phase traces, export and advanced comparisons.

The idle bubble has a translucent background with legible controls. Language
is accessible there without transferring editor focus. Recording and processing
raise visibility; the current phase remains readable without color or motion.
The active session's language/model/window stay frozen. Stop and Cancel remain
distinct. Existing destination protections and automatic insertion remain.

## Pulse

Cobalt header, warm cream surfaces and a coral microphone action. Material
type hierarchy keeps setup and runtime state readable. A small animated ring
is the signature activity indicator, with different recording/processing
states and a static reduced-motion alternative. The first viewport shows
readiness before the recording action; model and language follow. The console
uses aligned, compact phase rows rather than repeating the decorative ring.

This direction borrows the commitment of a music sleeve's palette and the
clarity of a single focal action, while the layout remains an Android tool.
Risks: an oversized microphone or constant animation could distract; constrain
the motion to actual work and keep the floating control quiet at rest.

## Wordmark

Coral and ink, a strong typographic identity, and mint state accents. A compact
wordmark/activity transition carries the personality without decorating every
control. Daily controls remain native. Setup takes priority over display type
when incomplete; dense console text stays neutral and consistently aligned.
Risk: headline scale must yield to setup information and larger system fonts.

## Color-pop

Mint surfaces, coral/yellow/lilac accents and standard Material chips. A small
state dot moves with the active work phase, with text and shape doing the same
job when motion is disabled. Setup steps are especially easy to distinguish.
Risk: multiple accents need consistent roles or the home becomes distracting.

## Familiar Android

Conventional Material composition, expressive accent colors and subtle state
motion. This remains an available owner choice rather than an implicit fallback
that quietly overrides the requested bold direction.

## Reference judgment

The reference exploration considered typography specimens, timetable rhythm,
garden-guide color systems, coded record sleeves, vertical video emphasis and
constructed grids. Color-pop remains a full alternative because playful color
and a clear moving state marker fit the brief. The other references contribute
disciplines rather than their literal layouts: readable scale contrast, fixed
phase alignment, one clearly active action, generous space around the main
action and a consistent grid. Coded state without accessible labels, media-feed
navigation, decorative construction grids and angled controls do not carry
this product's everyday task well.

No visual concept is approved, no native captures exist for a redesigned build,
and no UI implementation changes are claimed by this proposal.

# 0002 — Record in place by default

Accepted: 2026-10-03. Supersedes the recording-route default in
[0001](0001-offline-preview.md).

The operator requests recording from the floating microphone without switching
apps as the default. The debug label “Enable direct-start device experiment”
hid the everyday interaction behind implementation language. The operator
identifies the test phone as a Pixel 7 and supplies an actual transcription;
this feedback does not complete the platform, editor, or recognition gates.

Use the existing accessibility-service recording route by default in every
build type. Replace the debug-only toggle with the ordinary setting **Record
without leaving your app**, enabled by default. Turning it off selects the
genuinely visible recording screen. Keep **Open recording screen** available
for immediate recovery when the platform refuses recording in place.

Use a new product preference rather than migrating the old debug probe's
default-off state. Existing installs adopt the new default once; a subsequent
explicit choice to use the separate screen persists across updates. Preserve
permission checks, startup exception handling, cancellation, and conservative
insertion. A refused start reports the recovery action; it must not silently
switch apps, retry capture, or bypass a platform restriction.

This is an operator-directed preview default change before full Gate A
acceptance. Continue device validation and report unsupported combinations.
The default is not a compatibility claim or release-readiness evidence.

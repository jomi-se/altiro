# 0013: Resolve input focus across windows and trace visibility transitions

Status: accepted implementation correction; physical compatibility pending.

## Evidence

After resetting app storage, completing setup and reimporting models, the
maintainer reports no bubble in ChatGPT's composer, Termux's terminal or Reddit's
search field. The alternate Termux text box works. The 0.5.4 export records
`NO_FOCUS_NODE`, but its single current/last reason cannot reconstruct focus
changes. No recording trace is expected before recording; that must not prevent
diagnosing bubble visibility.

The source searches only `rootInActiveWindow`. Android's active window can be
the currently touched window rather than the input-focused application. The
public [service-level input-focus lookup](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#findFocus(int))
searches across interactive windows. Android also documents that accessibility
hierarchies can change asynchronously; input-start callbacks do not prove that
the focused virtual node is already available.

## Correction and reasons

- Use service-level `findFocus(FOCUS_INPUT)`, with a bounded fallback to the
  input-focused application window's focused node. Never substitute accessibility
  focus, traverse arbitrary children or choose a merely editable node.
- Require the candidate's window to be the positively input-focused application
  window. Preserve package, field identity, visibility, editability, display,
  password, selection, composition and one-attempt insertion guards. This corrects
  the observation source; it does not grant insertion into an ambiguous terminal.
- Coalesce structural content changes only for the started input's package
  into the existing settle budget. Recheck a not-yet-identifiable input at
  100, 250, 500, 1000 and 1500 ms after scheduling, once per started input.
  Streaming replies or terminal output do not cause further queries.
  Cancel pending checks on finish/restart/disconnect.
  Late virtual nodes should not require another tap or a perpetual polling loop.
- Keep 48 content-free visibility transitions in memory. Record fixed triggers,
  reason/lookup/input/window/node categories and boolean eligibility facts. Do not
  retain/export app names, node/window IDs, selection offsets, text or paths.
  Preserve the last positively matched external input separately so returning
  to Altiro cannot erase its failure details. Clear removes both trace and snapshot.
- Copy includes this trace even without recording. Console exposes it only in
  existing details, keeping the daily surface compact. Export current installed
  count/readiness separately from startup checks: imports made after startup
  are not startup checks.
- Visual progress, language, model and recording ticks redraw the overlay from
  cached editor metadata. They no longer query focused windows/nodes. Retain a
  lock check and probe on a lock transition; input/events and pre-start/pre-dispatch
  checks still refresh authority. Owned-overlay window changes increment their
  counter without querying the host editor again.

## Recording motion

The maintainer reports jumpy Home recording motion. Source advances eight
degrees after every 32 ms coroutine delay, imposing about 31 discrete steps
per second and scheduling drift. Replace it with the existing Compose animation
clock: one linear 1440 ms revolution, read in the Canvas draw phase. Continuous
recording/recognition does not restart the loop; idle disposal and disabled
animations retain static state. Native frame pacing still needs phone review.

## Acceptance

Host tests verify trace retention, deduplication, bounded memory and clearing;
Android compilation is separate from actual focus lookup execution. Recheck the
three reported editors plus native/Compose/WebView fixtures, passwords, field
switches and return-to-app diagnostics. A noneditable or unidentified destination
remains unsupported for automatic insertion. No compatibility claim is accepted
until the device result identifies the lookup and actual behavior.

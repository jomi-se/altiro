# 0008 — Pending Copy recovery has an undersized minimum touch target

Status: in-progress; source correction and SDK-free verification complete,
Android compilation/lint and phone acceptance pending.

## Reproduction and source evidence

Keep a transcript pending after insertion is blocked. The overlay supplies
Copy as the fallback action on its notice. Its minimum height is currently
chosen from the notice's explicit action before that fallback is resolved,
so this clickable notice can retain the passive 32 dp minimum.

The [bubble specification](../plan/android-dictation/spec.md#71-bubble)
requires a 48 dp minimum touch target. Derive the minimum height from the
effective action, including the pending-result fallback. Passive notices
can retain their existing size. Do not change insertion, clipboard or
destination-authority behavior.

## Acceptance evidence

- Resolve the effective action before choosing the minimum height.
- Verify explicit notice actions and pending Copy both receive the 48 dp
  minimum; passive notices retain 32 dp.
- Compile and lint the Android sources.
- On a device, check the pending Copy target and adjacent overlay actions.
  Host compilation does not establish actual rendered target geometry.

The source now resolves the explicit or fallback action before choosing the
minimum height. Independent source review, Kotlin formatting, repository/model
checks, Python tests and core/network JVM tests pass. Android compilation and
lint still need an Android SDK; the phone checks above have not been executed.

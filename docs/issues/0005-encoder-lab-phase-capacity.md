# 0005 — Ordinary diagnostic phase limit aborts later lab cases

Status: done.

## Reproduction and impact

The maintainer's 0.6.2 Quick report contains 16 planned cases with both formats
and profiling. Thirteen cases completed, including native GPU samples and the
FP16 profiler. The last three Q8 cases failed in 15–22 ms with
`LAB_SETUP_FAILED` / `WORKER_SETUP`, before starting their native worker.

The diagnostic trace's fixed 64-completed-phase check was exhausted by the
preceding worker lifecycles. Backend registration was correct in this build;
the earlier successful GPU samples remain valid measurements. Full suites can
reach this limit even sooner relative to their planned case count.

## Correction and acceptance

Version 0.6.3 explicitly allocates the lab a bounded 1,024-phase trace. Ordinary
dictation retains its 64-phase budget. A new session resets the budget; callers
cannot request an unbounded trace. No phase rows or elapsed durations are dropped.
The separate bounded lab JSON and worker/cancellation ownership are unchanged.

The regression runs all 40 Full-suite worker lifecycles, including phase and
runtime callbacks, in both case orders. It checks every worker start/stop is
retained and processing/elapsed totals remain complete. Separate coverage checks
the capacity bounds and return to the ordinary budget on the next session.

The subsequent 0.6.3 physical-phone Full report contains all 40 planned cases,
all marked `FINISHED`, and all 160 encoder samples. Both formats retain their
final baselines and profiler. This satisfies phase-capacity acceptance; it does
not establish speech quality or end-to-end product latency for the variants.

Host verification passes: 60 core and 8 acquisition tests, 11 Python checks,
Android app/fixture builds and lint, test APK compilation, packaged native
alignment/notices and normal-install manifest checks. The native binaries are
unchanged and match the previous verified APK byte for byte.

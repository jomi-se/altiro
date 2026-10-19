# 0004 — Encoder lab rejects GPU cases before starting a worker

Status: in-progress.

## Reproduction and impact

Run the 0.6.1 full Encoder lab with CPU selected in everyday settings. The
maintainer's complete 40-case report has 14 completed CPU cases, 24 GPU cases
marked `WORKER_FAILED` / `failure: NONE`, and two skipped pipeline-cache cases.
The failed cases took only 4–22 ms and contain no native events or samples.
These records do not establish failure or performance of GPU kernels.

## Cause and correction

`DictationController.begin` registered only the saved backend in the ordinary
diagnostic plan. The lab independently switches backend per case. Before binding
its worker, `NativeCall.run` publishes runtime metadata; the diagnostic plan's
identity check rejected the unregistered GPU backend. This happened before the
worker's error classification, leaving the default `NONE` failure code.

Version 0.6.2 derives the diagnostic plan from both CPU and Vulkan for each
selected model, independently of the saved backend. The recognition plan and
everyday selection stay frozen as before. Identity checks remain strict for
unplanned models/windows. Parent phase timings select the case's actual backend;
an unclassified lab setup failure reports `LAB_SETUP_FAILED`, with an initial
`WORKER_SETUP` phase, instead of claiming no failure.

## Acceptance

- The production diagnostic-plan helper admits every case in Quick and Full,
  either suite order, either saved backend, and one or both model formats.
- Regression coverage reproduces the old rejection and retains rejection of
  an unplanned model or window.
- Android compilation, lint, APK installability and packaging checks pass.
- On the physical phone, Quick's GPU baseline reaches native events and either
  records four encoder samples or reports a genuine typed native/worker failure.
  No GPU optimization or numerical-quality acceptance is implied by host checks.

The last condition remains pending. Retain the original CPU measurements; the
failed 0.6.1 GPU entries are harness failures and must not enter speed rankings.

Host verification passes: 58 core and 8 acquisition tests, 11 Python checks,
Android app/fixture builds and lint, instrumentation APK compilation, native
alignment/notices and the normal-install manifest check. Native code is
unchanged; the packaged libraries match the previously verified build byte for
byte. The regression reproduces the previous backend rejection and admits all
Full-suite cases in both orders for either saved backend and both formats.

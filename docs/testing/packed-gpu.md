# Packed GPU phone experiment

Use version **0.6.8-packed-gpu** or later. Open **Console → Encoder lab**, choose
**Packed GPU**, enable **Q8 + FP16** if both TranscribePsy formats are installed,
and leave **GPU profiler** off for the first campaign. Record around 10–20 seconds
and stop. Keep the app visible until completion. Use the existing explicit live
relay pairing if desired, or share the report file afterward.

The suite has 13 cases per format (14 with the separate profiler), or 26 cases
for both formats without profiling. It retains CPU and full-encoder GPU controls, tests ordinary-app
OpenCL/Vulkan capabilities, and compares independent packed matrix kernels
against GGML Vulkan. The two full-encoder F32 controls bracket the packed cases.
Successive campaigns reverse treatments while retaining control positions.

Each packed case contains expansion and contraction matrices plus two ragged
boundary shapes, with one first and five warm repeats. Cases use recording-derived
sequence length, fixed 145 frames, fixed 201 frames and two independent products.
The two-product case charges both products on both implementations. Candidate
and control ordering alternates within each case.

## Read the results

- Compare warm **complete wall** times. Candidate activation packing is included;
  pipeline creation and static weight packing are reported separately. The
  candidate pipeline is created in setup; GGML may compile on its first call.
  Compare the five warm calls, not unmatched cold-start times.
- Read GPU compute and packing intervals alongside process CPU work. Missing
  timestamps are null, not zero. Readback checks are separate from timed compute.
- Candidate weights use FP16 storage with explicit unpacking and F32 shader
  arithmetic. This does not measure a native FP16 arithmetic kernel. Coherent
  host-visible allocation flags and device-local residency are reported; memory
  placement can matter on other devices.
- Numerical and packing checks must pass before interpreting speed. Large double
  references are sampled; comparison against GGML and finiteness scans cover the
  complete output. These checks do not establish real-speech accuracy.
- The synthetic Q8 case reconstructs Q8 source weights and rounds them to F16 on
  both routes. Its storage and arithmetic differ from a native Q8 full encoder.
- OpenCL `verified` means the app executed and checked a small FP32 GPU kernel.
  It does not mean ACL GEMM, FP16 or integer matrix kernels are available or fast.
  Unavailable statuses are useful evidence, not campaign crashes.
- Reported Vulkan integer features distinguish semantic support from accelerated
  packed-dot properties. `shaderInt8` alone does not establish an INT8 fast path.

If a case fails, keep its partial report. The next worker remains isolated, and
there is no automatic CPU retry. Export the complete report rather than copying
long diagnostics through the Android clipboard.

A win here justifies integration into a complete encoder and a second campaign;
it is not itself a production optimization. A loss rules out this particular
layout/kernel configuration, not all GPU implementations or Arm's own kernels.

## Reproduce host safeguards

`./scripts/verify.sh` discovers the packed-layout/output-ownership and fake
OpenCL ABI tests, checks pinned attribution, and builds the Android app and
instrumentation APKs. Fake API tests cover bounded enumeration, GPU-only device
selection, incorrect output, driver failures, cancellation and resource cleanup.
They do not execute a hardware GPU.

A CPU-only host build of the JNI library can additionally run
`NativeTranscribePsy --capabilities`. This verifies that capability dispatch does
not open a model or audio, reports API availability, and respects pre-cancellation.
The Java fixture is in `inference-transcribepsy/src/test-native/`.

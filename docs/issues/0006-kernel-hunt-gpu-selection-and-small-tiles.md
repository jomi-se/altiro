# 0006 — Kernel hunt rejects integrated GPUs and misconfigures small tiles

Status: done; corrected GPU selection and forced-small geometry accepted on the phone.

## Reproduction and impact

The first complete 0.6.5 Q8 Kernel hunt report contains all 23 planned cases.
Thirteen finished and ten failed. All nine GPU synthetic cases returned
`BACKEND_UNAVAILABLE` before producing any shape measurements, even though
full-encoder GPU cases executed successfully. The CPU synthetic case completed
all 32 shapes. Transport preserved the failures; this was not missing report data.

The forced-small full encoder also returned invalid features: relative RMS
differences exceeded 130% and changed between identical warm repetitions.
Its F32-accumulation counterpart produced nonfinite output. A completed worker
therefore does not establish numerical correctness or a usable optimization.

## Causes and corrections

The synthetic harness selected `GGML_BACKEND_DEVICE_TYPE_GPU` by exact device
type. The pinned Vulkan backend classifies an integrated GPU as `IGPU`, which
does not match that lookup. Version 0.6.6 selects a device from the **Vulkan
registry**, accepting either GPU type. CPU selection remains explicit. Missing
devices and initialization failures fail without another backend/device retry.

The pinned scalar 32×32 specialization uses a 32-thread workgroup and logical
warp size 16 on this device, giving two warps. Its original warp tile was 32×32:
only one warp fits. The second warp computes `warp_c=1`, reads beyond the
shared B tile and can overlap the following output tile. This invalid mapping
is consistent with the observed corruption, including the nonfinite F32 run.

The forced-small lab family now uses a 16×32 warp tile on scalar subgroup-16
devices. Both warps fit the 32×32 workgroup tile. Other subgroup sizes,
cooperative-matrix paths and ordinary dictation keep their prior settings.
The static shader-analysis script uses the corrected forced-small constants.
Earlier small-tile compiler results describe the invalid geometry and must
not guide performance choices.

## Verification and phone acceptance

An executable C++ regression covers integrated/discrete GPU selection, CPU
selection, absent/empty Vulkan registries, rejected CPU/accelerator/meta devices
and initialization failure without retry. It also enumerates scalar shader
output ownership for subgroup sizes 8, 16, 32 and 64: each of the 1,024 cells
must have exactly one owner, with no addresses outside the tile. The fixture
passes against both isolated API declarations and the pinned GGML headers.
Production-JNI CPU checks also pass for 64 synthetic shapes across both model
formats (384 reported graph calls), both speech reference phrases, invalid
audio, pre-cancellation and cancellation during work.

The subsequent 0.6.6 phone campaign completed all 48 cases with both formats
and profiling. All 640 synthetic shapes passed finiteness and the 1% RMS
screen, including 576 GPU shapes on `Vulkan0`. Their 3,840 reported graph calls
retain actual shader/routing evidence. Both forced-small synthetic cases used
the requested 32×32 family.

All four full-encoder repetitions for each forced-small format are finite.
Their maximum relative RMS differences match the corresponding stock GPU
case exactly: approximately 0.648% for FP16 and 1.344% for Q8. The corrected
small-F32 cases also match their F32 controls, with no nonfinite results.
This accepts the two specific harness defects. Q8's full-encoder difference
still exceeds the provisional 1% feature screen; speech quality and promotion
of performance variants require separate evidence. See the [measurement
analysis](../research/mali-kernel-hunt.md#completed-066-phone-campaign).

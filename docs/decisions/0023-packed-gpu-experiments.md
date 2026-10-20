# 0023 · Independent packed GPU experiments

Accepted: 2026-10-20. The owner requested implementation of the independent
Astra review's next optimization leads for physical-phone measurement.

Add **Packed GPU** to the opt-in Encoder lab. Preserve ordinary dictation
settings, isolated per-case workers, cancellation, bounded timeouts, partial
reports and the explicitly paired debug relay.

Test a different matrix implementation before undertaking a backend migration.
The original Vulkan kernel packs static half-precision weights by four output
rows, packs activations by four columns and accumulates a 4×4 output tile in
registers using F32 FMA. It does not copy or execute Arm Compute Library kernels
and cannot establish their performance. It tests the proposed packing/register
reuse mechanism independently of GGML's shared-memory tiling.

Compare complete candidate calls against a resident GGML Vulkan F32 control on
identical half-rounded inputs. Alternate candidate/control order per repeat.
Charge activation packing, submissions and waits to every candidate wall time;
report one-time weight packing and pipeline preparation separately. Synthetic
Q8 weights are reconstructed and rounded to F16 for both paths; record that
rounding rather than presenting this as the native Q8 encoder implementation.

Use actual recording-derived sequence length plus fixed 145/201-frame cases,
expansion/contraction shapes and ragged boundaries. A separate two-stream case
executes two distinct weight/activation products on both routes; its time covers
both products. Keep its throughput comparison separate from single-call latency.
Full small double references, sampled large references, full output finiteness,
complete GGML output comparison and lossless GPU packing checks screen defects.
These checks do not accept speech quality or a full-encoder speed improvement.

Probe public Vulkan dot-product features and acceleration properties separately.
Probe OpenCL through the ordinary app linker namespace with optional
`libOpenCL.so`; select GPU devices only and verify all outputs of a fixed FP32
kernel. Do not use hidden vendor paths or reinterpret an unavailable app library
as a hardware limitation. Advertised FP16 or integer extensions are capabilities,
not evidence that a matching accelerated kernel ran.

Report complete wall time, process CPU work, nullable GPU intervals, packing,
readback checks, resident/scratch allocations, layout and arithmetic semantics.
Do not rank capability records or packed microbenchmarks as full encoders.
Keep CPU and full-encoder GPU controls before/after treatments to expose drift.

Positional projection caching, actual ACL/OpenCL GEMM integration and real INT8
kernels remain separate follow-ups. Capability evidence gates those choices;
this APK does not silently promote them or change recognition defaults.

See the [phone procedure](../testing/packed-gpu.md). Host compilation and fake
API tests do not establish phone GPU correctness, stability or performance.

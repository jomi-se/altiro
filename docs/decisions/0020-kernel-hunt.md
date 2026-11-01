# 0020 · Phone-run kernel isolation

Accepted: 2026-10-19. The owner requested autonomous preparation of a broad
optimization campaign, with one exported phone run rather than remote access.

Extend the opt-in Encoder lab with **Kernel hunt**. Keep the existing single
session owner, per-case isolated worker, two-minute case deadline, forty-minute
suite limit, partial content-free checkpoints and manual-lock cancellation.
No remote connection, benchmark upload, clock control, root access or change to
ordinary dictation defaults is introduced.

Two complementary measurements are necessary. Full encoder cases establish
whether changing supported matrix tile selection affects the actual workload.
Synthetic matrix cases isolate arithmetic, shape boundaries, layout and working
set effects without model loading, preprocessing or a decoder. Synthetic inputs
are deterministic and unrelated to the recording. They are never presented as
speech-quality or end-to-end latency measurements.

The synthetic suite uses the measured model's feed-forward expansion/contraction,
QKV and projection dimensions, with eight sequence widths including 64/65,
128/129 and the observed 139. First execution is separate from five warm calls.
Each call checks the entire output for finiteness and 64 distributed cells per
independent stream against a double-precision reference of the stored weights.
Q8 pre-dequantization starts from Q8 values, preserving the source quantization;
its additional FP16 rounding stays visible in the error. These are numerical
screens, not exhaustive correctness proofs or transcription-quality acceptance.

Existing, device-supported small and large tile families may override the
runtime's stock choice. Two scalar rectangular specializations (64×32 / 32×64)
also test register-spill evidence from Mali offline analysis; their shader
dimensions and dispatch denominators change together, retaining the runtime's
shared-memory support checks. Record fallback-to-stock selection when the requested
family is unavailable; never describe an unchanged route as a successful tuning
experiment. Eight-stream tests use distinct buffers in one graph, measuring a
larger working set and amortized dispatch, not concurrent queues or a product
batching feature. Memory, setup and readback/checking remain separately visible.

Extend the optional serialized GPU profiler with operation/type/shape groups
including all batch dimensions and contiguity. Preserve fused intervals as fused
groups instead of charging their full time to every member. No tensor names or
values enter the report. These timings include dependency effects and cannot
substitute for unprofiled wall time or hardware utilization counters.

Offline shader analysis is a separate host tool. Retain source/binary hashes,
specialization choices and compiler identity with its reports. Static register,
spill and cycle estimates are hypotheses to check on the phone, not device
performance measurements. A physical-phone run remains required before choosing
an optimization or claiming a speedup.

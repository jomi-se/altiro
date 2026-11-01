# 0022 · Source-guided encoder tuning

Accepted: 2026-10-20. The owner requested implementation of the independent
source review's optimization candidates and a new phone campaign.

Add **GPU tuning** to the opt-in Encoder lab. Keep ordinary dictation defaults,
recognition graph settings, per-case process isolation, cancellation, timeouts,
local reports and explicit debug relay authorization unchanged. Unsupported
routes remain visible; no CPU retry or universal GPU speed claim is introduced.

The preceding phone campaign attributes roughly 80% of encoder time to matrix
multiplication. Source review finds concrete reasons to test four changes:

- The final fence uses a CPU polling loop. A blocking fence can reduce CPU work;
  it does not necessarily reduce GPU execution time.
- The scalar 64×64 tile uses 512 threads. A 256-thread specialization retains the
  tile's reuse while changing per-thread work and register pressure. Restrict it
  to validated device limits and subgroup geometry.
- Automatic split-K depends on a shader-core count that is absent on the measured
  Mali path. Explicit factors two and four partition only the hot M=640, K=2560
  contraction; reduction, scratch allocation and synchronization remain charged.
- Existing bias fusion is limited to matrix-vector products. A dense-GEMM
  epilogue can remove separate bias and SiLU passes for eligible single-consumer
  chains. Preserve output rounding, broadcast semantics, alias checks and
  barriers; never move nonlinear activation ahead of a split reduction.

Keep each change separate and compare F32-accumulating GPU controls before and
after treatments. Include two-thread and four-thread CPU controls, stock GPU
controls, both verified formats and reversed treatment order on successive runs.
Do not multiply isolated gains or promote a route that was not observed.

Focused synthetic cases compute the same dot+bias+SiLU result for every variant.
The first four shapes derive sequence width from the recorded length and pinned
model front-end metadata, followed by four fixed boundary shapes. Small outputs
receive a full double-reference check; large outputs use sampled double checks
and full finiteness scans. Fusion variants compare complete outputs against an unfused GPU control too. These
checks screen implementation defects; they do not establish speech quality.

Record graph-boundary GPU timestamp intervals separately from wall time, process
CPU time, enqueue time and fence waits. Respect timestamp availability and valid
bits; unavailable measurements stay null. This avoids mistaking serialized
per-operation profiler overhead for the behavior of the ordinary graph.

See the [phone procedure](../testing/encoder-tuning.md). Host compilation and
policy tests cannot accept GPU correctness, stability, speed or transcription
quality. Those remain physical-phone gates.

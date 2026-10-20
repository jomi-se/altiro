# Mali kernel hunt: hypotheses and measurement contract

Status: Kernel hunt, targeted GPU tuning, packed GPU, and 0.6.9 OpenCL/INT8
phone campaigns complete. No new ordinary recognition defaults are selected.
The independent [web/source reassessment](transcribepsy-gpu-reassessment.md)
motivated the later kernel comparisons; these campaigns do not establish a
CPU-only architectural limit.

## Completed 0.6.9 OpenCL and INT8 campaign

One **17.20-second** recording with **216 encoder frames**, Q8 before FP16
and forward treatment order completed **27/27 cases** with the live relay
enabled. All **24 ACL and 12 INT8 shape records** pass their declared numerical
screens, with positive GPU intervals throughout. Across six repeats per shape,
this covers 216 complete candidate calls and 216 matched control calls. The
ordinary app executed the pinned upstream Arm OpenCL kernels and actual Vulkan
signed packed dot instructions on the Pixel 7's Mali-G710.

The INT8 performance control retains the original Q8_0 weights with F32
accumulation. Both routes receive the same half-rounded activations; the
candidate additionally quantizes them per block on the GPU. These are synthetic
matrices at encoder dimensions, not measured complete feed-forward blocks or
model encoders. Complete-call times include activation quantization, submissions
and waits; static weight packing and pipeline setup are separate. Medians use
warm repetitions 1–5 with alternating candidate/control order.

| Frames | Expansion INT8 / GGML ms | Speedup | Contraction INT8 / GGML ms | Speedup |
| --- | ---: | ---: | ---: | ---: |
| 145 | 9.144 / 18.432 | 2.02× | 12.842 / 19.111 | 1.49× |
| 201 | 9.274 / 18.928 | 2.04× | 17.675 / 21.807 | 1.23× |
| 216 | 10.348 / 17.047 | 1.65× | 13.836 / 19.500 | 1.41× |

Expansion is `2560×N×640`, contraction `640×N×2560`. INT8 wins in 29 of
30 paired warm large-shape repetitions. Matched GPU intervals also improve, so
the advantage is not solely host submission overhead. Per-call activation
quantization costs 1.37–4.93 ms at the large shapes. The small ragged matrices
lose, so this is a selective implementation lead rather than a universal
replacement for matrix operations.

Maximum relative RMS error versus the full quantized integer/double oracle is
**0.0000163%**. Error versus the floating-input reference is **0.329–0.437%**:
integer arithmetic passes, while activation quantization intentionally changes
the result. This is not precision equivalence or a speech-quality measurement.
Pipeline creation costs 220–635 ms per isolated INT8 case; static weight packing
costs 0.70–8.01 ms per shape. Neither cost may disappear from eventual startup
or memory accounting merely because it is excluded from warm calls.

The tested ACL route uses actual reshaped-RHS FP16 kernels with half
accumulation/output, plus F32-to-F16 input and F16-to-F32 output transforms in
every complete call. Its control uses F16 weights and F32 accumulation. Maximum
ACL relative RMS error is **0.820%**. Expansion sometimes wins, but contraction
loses in all six format/width medians. At recording width 216, expansion is only
4% faster for Q8-derived weights and 6% slower for FP16. This fixed buffer/tile
configuration does not justify a general backend switch; it does not test the
complete ACL dispatcher, all configurations or mixed-precision alternatives.

Existing full-encoder controls remain unchanged, excluding setup and decoder:

| Case | Q8 start / end ms | FP16 start / end ms |
| --- | ---: | ---: |
| CPU, two threads | 2386.6 / 2511.8 | 7880.5 / 7664.1 |
| CPU, four threads | 3071.5 / 6256.7 | 6128.5 / 8311.9 |
| Stock Vulkan | 1682.2 / 2196.2 | 1883.8 / 1779.5 |
| F32 Vulkan | 1746.8 / 2015.7 | 2101.5 / 1927.1 |

Thermal status ranges LIGHT–MODERATE; battery temperature rises from 30.9 to
37.4°C. Q8 four-thread CPU time more than doubles and stock GPU drifts by 31%.
Paired local comparisons are more useful here than cross-format or historical
baseline rankings. One network-active run does not establish broad stability
or repeated production performance.

The next useful measurement is selective INT8 integration at the two large
feed-forward shapes, with resident static packing and activation quantization
inside the existing graph submission. Compare full-encoder outputs, actual
transcription, cold/warm latency and memory against unchanged controls. Do not
multiply this local speedup into a predicted encoder speedup. New-kernel
cancellation and pending-work recovery still require their separate phone tests.

## Completed 0.6.8 packed GPU campaign

One **18.48-second** recording with **232 encoder frames**, FP16 before Q8 and
reversed treatment order completed **26/26 cases** with the live relay enabled.
All **32 packed shape records** pass the numerical/placement screen: 192 repeat
batches cover 240 candidate matrix products and 240 matched GGML control products
(the independent-stream cases execute two products per repeat). GPU timestamps
are present throughout; all candidate allocations report device-local coherent
host-visible memory. Maximum packed-versus-double relative RMS error is
0.0001032%; full-output packed-versus-GGML error is at most 0.0000801%.
These are synthetic checks on half-rounded inputs, not transcription acceptance.

The ordinary app **compiled, executed and verified** the fixed FP32 OpenCL GPU
kernel on Mali-G710. The driver advertises FP16 arithmetic and the Khronos
integer-dot extension; the Arm matrix-multiply extension is absent. Vulkan
reports signed and unsigned packed 4×8-bit dot products accelerated, including
the signed accumulating-saturating property; mixed-signedness acceleration is
false. This unblocks testing actual ACL kernels and a signed-integer Vulkan
implementation. It does not establish their throughput, FP16 kernel execution,
or lossless compatibility with Q8_0 block scales and activation quantization.

Warm complete-call medians include activation packing and both candidate
submissions/waits. Weight packing and pipeline creation are separate. Candidate
and control order alternate within each six-repeat shape; medians use repeats
1–5. The following single-product results use synthetic Q8-reconstructed or F16
weights rounded to F16, matched half-rounded activations and F32 arithmetic.

| Source / frames | Expansion packed / GGML ms | Expansion time reduction | Contraction packed / GGML ms | Contraction time increase |
| --- | ---: | ---: | ---: | ---: |
| FP16 / 145 | 12.713 / 16.416 | 22.6% | 17.700 / 14.169 | 24.9% |
| FP16 / 201 | 13.468 / 18.595 | 27.6% | 16.849 / 11.274 | 49.5% |
| FP16 / 232 | 13.802 / 15.927 | 13.3% | 21.005 / 15.380 | 36.6% |
| Q8 / 145 | 11.862 / 17.184 | 31.0% | 15.421 / 10.855 | 42.1% |
| Q8 / 201 | 14.499 / 19.247 | 24.7% | 21.451 / 13.863 | 54.7% |
| Q8 / 232 | 11.909 / 13.413 | 11.2% | 18.809 / 16.224 | 15.9% |

The expansion layout is a selective candidate, especially at the two fixed
widths; using it for contraction loses. Two independent products retain an
expansion win (FP16 8.2%, Q8 30.5% less complete wall time), while contraction
still loses (39.1% and 21.8% more wall time). Ragged small shapes are slower too.
Combining the expansion candidate with the existing contraction would reduce
these synthetic pairs by roughly 5–19%; that arithmetic is not a measured FF
block or full-encoder gain. Do not sum unrelated shape wins or promote a whole
backend from this result.

Existing full-encoder controls, excluding setup and decoder:

| Case | Q8 start / end ms | FP16 start / end ms |
| --- | ---: | ---: |
| CPU, two threads | 1727.6 / 1608.6 | 2960.7 / 3217.6 |
| CPU, four threads | 2258.3 / 2441.5 | 3216.3 / 3460.9 |
| Stock Vulkan | 1783.2 / 1776.0 | 1775.3 / 1779.6 |
| F32 Vulkan | 1870.1 / 1864.1 | 1832.8 / 1830.4 |

GPU controls are stable; CPU controls drift. Thermal status ranges NONE–LIGHT,
with battery temperature 30.6–33.9°C. FP16 stock GPU is substantially faster than
its CPU controls in this campaign, while two-thread Q8 CPU remains faster than
stock GPU. Q8 GPU feature error versus its CPU reference remains above the
provisional 1% screen (stock 1.42%, F32 1.21%); FP16 is below it (0.787% and
0.111%). These feature errors are not word-error rates. One network-active run
accepts the exercised paths and motivates selective follow-ups, not broad
stability, speech quality or a repeated production performance claim.

## Completed 0.6.7 GPU tuning campaign

One **16.04-second** recording, 201 encoder frames and secondary width 401 drove
both formats with forward treatment order and the live relay enabled. All
**42/42 cases** finished. All **112 focused shapes** passed placement, requested
route, finiteness and the provisional 1% numerical screen: 672 reported focused
graph calls. All graph-boundary GPU timestamps were available. Maximum focused
RMS error was 0.0328%; both fusion variants matched complete unfused GPU outputs
exactly in the recorded comparisons. This accepts execution of these routes
for this campaign; it does not establish speech quality or broad GPU stability.

Warm full-encoder medians below exclude setup, model loading, reference work,
preprocessing, decoder and delivery. Every treatment uses F32 accumulation.

| Case | Q8 encoder ms | FP16 encoder ms |
| --- | ---: | ---: |
| F32 GPU control, start / end | 1857.88 / 1861.49 | 1679.84 / 1674.77 |
| Blocking final fence | 1856.84 | 1678.24 |
| 256-thread 64×64 tile | 2456.77 | 2253.36 |
| Selective split-K2 | 1835.12 | 1663.05 |
| Selective split-K4 | 1845.46 | 1664.74 |
| Dense bias fusion | 1883.67 | 1649.96 |
| Dense bias + SiLU fusion | 1858.89 | 1639.95 |

The strongest observed change is CPU work, not encoder latency. Blocking the
final fence reduces warm full-encoder process CPU time from **347.43 to 21.96
ms** for Q8 and **315.84 to 20.69 ms** for FP16, about 15–16 times less, with
virtually unchanged wall time and numerical output. Energy savings were not
measured. The 256-thread tile is 32–34% slower; preserving tile reuse and changing
register ownership did not yield a useful gain in this trial.

Split-K changes all 48 targeted contractions per full repetition but improves
whole-encoder time only around 1% here. FP16 bias+SiLU fusion reaches 1639.95 ms,
about 2.2% below the midpoint of its F32 controls; Q8 fusion is essentially flat.
These small differences need repeats with reversed order and relay disconnected.
They are candidate effects, not a new production policy or a large hidden win.
Default-accumulation FP16 stock GPU is about 1628 ms, also illustrating why
precision-matched comparisons matter.

The full FP16 F32 feature RMS difference from CPU is 0.1248%; Q8 is 1.5803%,
remaining above the provisional full-encoder screen. Synthetic double-reference
agreement and exact fusion-versus-unfused GPU agreement do not erase that
same-format CPU/GPU arithmetic difference or establish transcription accuracy.

CPU controls drift substantially: Q8 two-thread warm time goes from 1355 to
2503 ms, FP16 from 3035 to 2702 ms. Battery temperature spans 27.2–33.2 degrees C;
Android thermal status reaches LIGHT. F32 GPU controls stay within 0.4%.
This supports comparing the GPU treatments to their own controls but prevents a
confident CPU/GPU ranking from this sequential campaign. There is no CPU/GPU
frequency residency trace.

Focused graphs do not show a consistent wall-time gain from blocking either:
the sum of eight shape medians rises from 67.25 to 99.93 ms for Q8 and from
82.62 to 85.86 ms for FP16, while process CPU work falls. Boundary GPU interval
sums also rise, so the wall difference cannot simply be labeled host fence
wakeup overhead. The isolated workload, ordering, power management and gaps
between calls need closer controls; this run does not identify their cause.
Full-encoder measurements remain the practical latency evidence.

The next useful measurements are repeated precision-matched fusion controls,
interleaved isolated controls with scheduling/frequency telemetry where public
APIs permit it, and the end-to-end preparation/worker lifecycle. A large warm
encoder speedup is not demonstrated by these four source-guided leads.

## Completed 0.6.6 phone campaign

One **11.56-second** recording, with 145 encoder frames, drove both formats in
reverse interior order with profiling and the live relay enabled. All **48/48
cases** finished. All **640 synthetic shapes** passed the finite/1% RMS screen:
576 GPU and 64 CPU shapes, containing 3,840 reported graph calls. Maximum
synthetic relative RMS difference was 0.8313%. Raw reports and generated
analysis are retained outside Git.

The integrated-GPU lookup and forced-small geometry corrections are accepted
on the phone; see [defect 0006](../issues/0006-kernel-hunt-gpu-selection-and-small-tiles.md).
Full-encoder small-tile differences now match stock GPU output, and small-F32
matches its F32 control. Neither produces the prior corruption/nonfinite output.

### Full-encoder timings

These are **warm encoder medians**, excluding model loading, preprocessing,
the decoder and delivery. They are not after-Stop dictation latency.

| Format | CPU, 2 threads | Stock GPU, start / end | Small GPU | GPU, cached F32 accumulation |
| --- | ---: | ---: | ---: | ---: |
| FP16 | 1.815 s | 1.460 / 1.451 s | 1.406 s | 1.509 s |
| Q8 | 0.923 s | 1.477 / 1.486 s | 1.504 s | 1.559 s |

GPU is faster than CPU for FP16 here; Q8 with two CPU threads is fastest
overall. Forcing small tiles globally gives no substantial improvement.
Forcing large tiles is much worse: 2.691 s FP16 and 4.067 s Q8. Rectangular
F32 variants also lose at full-encoder scale in this run.

Four-thread CPU baselines drifted from 2.360 to 1.947 s for FP16 and from
1.274 to 1.479 s for Q8. GPU start/end medians stayed within 1%. The phone
reported thermal status 0 throughout and remained unplugged; battery temperature
rose from 23.7 to 29.4 degrees C. The relay and sequential case order remain
confounders. Repeat close timing differences with interleaved controls and
without the relay before promoting a policy.

FP16's stock GPU feature difference from same-format CPU is 0.648%; F32
accumulation reduces it to 0.121%. Q8 is 1.344% for stock and 1.387% for F32,
above the provisional full-encoder screen. These are feature comparisons,
not speech-quality scores or proof that the CPU reference has exact arithmetic.
The CPU quantized path also quantizes activations; the deterministic synthetic
reference instead uses double-precision dot products of reconstructed weights.

### Where warm GPU time goes

The last profiled repetition gives:

| Format | Encoder wall | GPU MUL_MAT intervals | Share of wall | Top two matrix-shape groups |
| --- | ---: | ---: | ---: | ---: |
| FP16 | 1,522.6 ms | 1,226.3 ms | 80.5% | 723.8 ms |
| Q8 | 1,535.5 ms | 1,230.1 ms | 80.1% | 719.4 ms |

The two largest groups are `M=640,K=2560,N=145` and
`M=2560,K=640,N=145`, each called 48 times. Together they occupy about 47%
of profiled encoder wall time. They are concrete targets for kernel work.
Both profiles have 1,613 dispatches, 18 submit batches and zero integer-dot
routes. Q8 continues to use a dequantizing floating-point matrix path.

The host upload timer is 0.14–0.19 ms, output readback 0.30–0.48 ms and
synchronization 0.11–0.78 ms. Synchronization overlaps compute; these host
timers are not additive. Upload/readback exclude cold model-weight upload,
and GPU-internal copies are separate: `CONT` takes roughly 44–50 ms.
External transfer or cold compilation does not explain the warm matrix intervals.
After matrix multiplication, ADD is about 97–124 ms and IM2COL about 49–54 ms.

### What the synthetic cases establish

Small tiles can help narrow shapes without helping the entire encoder.
For FP16 `M=640,N=32,K=640`, stock takes 3.253 ms and small takes 1.033 ms.
At `M=640,N=139,K=2560`, small loses: 12.472 ms versus 11.740 ms stock.
A universal small-tile override would therefore promote the wrong policy.

Summing per-shape warm medians, Q8 pre-dequantization improves the F32 case
by only 1.044 times. The FP16 deliberate no-op control improves by 1.033 times.
That comparison does not support a large isolated dequantization bottleneck.
Setup cost and additional resident storage are excluded from these compute times.

Eight independent streams in one graph improve aggregate throughput by
1.675 times for FP16 and 1.716 times for Q8. This can reflect amortized graph
submission/wait costs as well as GPU occupancy. It does not establish an
equivalent speedup for dependent layers in one dictation.

The isolated graph also differs from embedded execution. For example, FP16
`M=2560,N=139,K=640` takes 11.492 ms wall time in isolation, while the nearby
`N=145` profiled encoder group averages 6.649 ms per call. Isolated process
CPU time is near wall time; full-encoder process CPU time is much lower,
especially with profiling. Source inspection explains an important difference:
`ggml_vk_wait_for_fence` optionally blocks on an almost-ready fence, then polls
completion with 1,000 ARM YIELD instructions per iteration. This processor hint
does not put the thread to sleep. The almost-ready condition
`(n_nodes-i) < n_nodes/5` cannot fire for the one-node or eight-node synthetic
graphs; large graphs can block for most of their work. The profiler separately
uses blocking `waitForFences`.

This explains CPU usage without proving a wall-time penalty. CPU time overlaps
GPU work and cannot be added to wall time or treated as recoverable latency.
Submission cost, waiting policy, between-call gaps and GPU frequency still need
separate measurement. Wall time alone should not be labeled pure shader time.

### Source-guided optimization candidates

The blocking-wait, 256-thread tile, split-K and bias/SiLU candidates are
implemented as opt-in experiments in 0.6.7; see
[GPU tuning](../testing/encoder-tuning.md). They are not accepted production
policies or established speedups. The 0.6.6 campaign motivates them; the
0.6.7 campaign above measures their implementations. Queued multi-graph tests and contraction
scale/residual fusion below remain possible follow-ups, not shipped cases.

1. **Blocking-wait control and boundary timestamps.** Compare the current wait
   with a blocking final fence on identical actual-shape graphs. Record CPU,
   enqueue, wait, GPU interval and total wall separately; queue 1/2/8 repetitions
   before waiting. Lower CPU consumption is plausible even with unchanged GPU
   wall time. Shared power/cache effects and wakeup cost could move latency in
   either direction. Avoid per-operation profiler serialization when testing
   overlap. Keep shared-weight and distinct-weight repetitions separate.
2. **Keep 64×64 reuse with 256 threads.** The scalar `mul_mm.comp` can be tested
   with `BLOCK=256, BM=BN=64, BK=32, WM=WN=16, WMITER=2, TM=TN=2, WARP=16`.
   Sixteen logical warps cover a 4×4 grid of 16×16 warp tiles. Each thread owns
   16 scalar outputs instead of the stock 32, while preserving the 64×64
   workgroup output tile and shared input footprint. Aligned four-element loads
   advance 32 rows per iteration, covering each 64-row operand tile in two
   iterations. This avoids the rectangular candidates' loss of workgroup reuse.
   It could reduce register pressure/spills, but more threads can increase
   barrier cost and need not improve actual occupancy. Validate ownership,
   ragged boundaries, device limits and compiler allocation before phone tests.
3. **Selective split-K.** The ordinary Mali initialization does not populate
   the shader-core count used by `ggml_vk_guess_split_k`; a zero count leaves
   automatic splitting inactive. Existing scalar kernels and reduction code
   already support partial outputs. Test explicit lab-only factors 1/2/4 on
   `M=640,K=2560,N=145`: stock has only 30 output workgroups and 80 BK32 steps
   per group. More groups may help, but 30 does not itself prove inadequate
   occupancy. Include reduction, scratch traffic and barriers in timing. Four
   F32 partial outputs need about 1.48 MB versus 371 KB for the final output.
   Preserve split alignment and reject empty tails; reduction changes rounding.
   Do not insert a guessed physical core count into production heuristics.
4. **Actual dense-GEMM epilogues.** `mm_add_ok` in the Vulkan graph optimizer
   requires `ggml_nrows(mul)==1`, so existing matmul+bias fusion cannot handle
   these N145 matrices. `conformer_ff_graph` has explicit matmul → bias → SiLU
   → matmul → bias stages. A dense bias-only epilogue, then bias+SiLU and a
   separate contraction bias+scale+residual epilogue, can eliminate passes and
   intermediate traffic. Verify broadcasting, consumers, aliases and lifetimes
   before eliminating graph nodes. Preserve the original output rounding before
   F32 bias/activation; changing that is a separate numerical experiment. With
   split-K, nonlinear epilogues must run after partial-sum reduction.

Existing scalar shaders already use FMA, vector loads, shared buffers and
multiple accumulators. Merely enabling FMA or FP16 is not a new candidate.
An explicit packed FMA or weight layout should be justified by generated
instructions/register reports; the driver may already emit equivalent code.
Prepacking also needs its preparation/memory break-even included.

Zero integer-dot routes leave another diagnostic question, not permission to
force the route. The backend checks integer-dot support, packed signed-dot
acceleration, a usable Q8_1 pipeline, layout and non-F32 precision. Export those
individual gates before proposing an integer kernel; `shaderInt8` alone does
not establish them. Activation quantization changes numerical semantics.

### Next measurements and upside bounds

Prioritize the two dominant matrix families at **actual recording widths**,
including 145 and the secondary width 289 in this report. Add `M=640,K=1280`,
which contributes another 85–89 ms and is absent from the current synthetic grid.
Keep precision and layout matched while interleaving stock and candidate tiles.

Use a short interleaved batch: stock/blocking wait, stock64/256-thread64 at
matched precision, then split2/split4 on contraction only. Start with F32
accumulation on both sides of each comparison and test default accumulation
separately. Include before/after unprofiled full-encoder controls and numerical
checks. Repeat without the live relay before promoting close rankings.

At the current profiled shares, halving only the two dominant FF groups would
reduce encoder time from about 1.53 to 1.18 seconds, roughly a 1.31× speedup.
Halving all matrix intervals gives about 0.92 seconds, near the Q8 CPU2 result.
These are conditional serialized-profile extrapolations, not predictions.
Erasing all ADD alone would save at most 6–8%; a practical epilogue captures
only part of that, although it can also affect memory work within matmul.
Tile, split, packing and vector changes compete for overlapping matrix time;
their gains cannot be multiplied. Fusion also overlaps that traffic.

For an immediate product experiment, ordinary TranscribePsy JNI recognition
still sets `options.n_threads=4`. The two-thread Q8 case is 28–38% shorter than
this campaign's four-thread baselines, with CPU drift affecting that range.
Validate a selectable two-thread path in ordinary dictation, including resource
lifecycle and complete after-Stop time, before generalizing to other devices.
This is independent of GPU kernel work.

Persistent pipeline preparation addresses cold latency separately. The warm
measurements do not implement or validate an everyday warmup lifecycle.

## First live Q8 campaign

The 0.6.5 run used 10.32 seconds of audio and completed all 23 case records,
without the optional profiler. Thirteen workers finished and ten failed.
All nine GPU synthetic cases failed during backend selection, while actual
full-encoder GPU execution worked. The harness requested a dedicated GPU type
and missed the integrated Vulkan device. The forced 32×32 specialization also
has invalid logical-warp coverage for subgroup size 16. See [defect 0006](../issues/0006-kernel-hunt-gpu-selection-and-small-tiles.md)
for the source-level diagnosis, corrections and pending hardware checks.

Stock Q8 GPU warm encoder medians were approximately 1.42–1.45 seconds.
Two CPU threads reached 0.825 seconds, but four-thread baselines drifted
from 1.76 seconds at the start to 1.14 seconds at the end. Rectangular F32
candidates reached 1.63 and 2.01 seconds, without a speedup in this run.
Stock GPU relative RMS feature difference was about 1.27%; rectangular F32
was about 1.15%. Both exceed the provisional 1% screen. Forced-small outputs
were corrupt and cannot support timing conclusions. These are encoder feature
comparisons against same-format CPU output, not transcription-quality scores.

Charging state changed during the suite and the live relay was enabled.
Repeat promising candidates under consistent conditions without the relay
before treating a timing difference as a stable ranking. The missing GPU
synthetic shape records prevent attributing encoder time to individual GEMMs.

The owner requested an investigation prepared autonomously, without remote phone
access. The [phone procedure](../testing/kernel-hunt.md) runs a frozen batch and
exports one content-free JSON. The [decision](../decisions/0020-kernel-hunt.md)
keeps these changes isolated from everyday recognition.

## What would establish a hidden inefficiency

Compare identical arithmetic, precision and shapes before blaming a model's
architecture. First exclude setup/compilation using warm runs. Attribute matrix
time by input shapes, including batch dimensions. Isolate the dominant shapes
and look for disproportionate cliffs at tile boundaries. Compare contiguous
and strided inputs, source Q8 versus pre-dequantized source Q8 values, and
different working sets. Return promising kernel variants to the full encoder.
Numerical differences and profiler perturbation remain attached to the results.

The measured encoder uses many `M=2560,K=640` and `M=640,K=2560` multiplications,
as well as QKV and projection matrices. A prior 11.08-second recording produced
139 encoder frames. Widths 64/65 and 128/129 can reveal tile/padding cliffs;
139 connects the synthetic screen to an observed shape. Effective throughput
must account for increased work as N grows. Attention's batched/strided shapes
are profiled in the real graph but are not represented by the synthetic GEMMs.

## Offline shader evidence

The official Arm Mali Offline Compiler 2026.5.0, build `922df8`, successfully
analyzed the pinned runtime's generated scalar FP16/Q8 aligned GEMM SPIR-V.
The compiler selected Mali-G710 Valhall revision r0p0 with compiler backend
`r56p1-00rel0`. The sweep contains 36 stock tile/subgroup specializations and
eight rectangular candidates at subgroup size 16. No physical GPU was used.
Raw inputs, hashes, specialization arrays and reports are retained outside Git.

At **candidate subgroup size 16**, the static reports give:

| Weights / accumulation | 32×32 spill bytes | Stock 64×64 spill bytes | 128×128 spill bytes | 64×32 / 32×64 spill bytes |
| --- | ---: | ---: | ---: | ---: |
| FP16 / default FP16 | invalid geometry | 8 | 16 | 0 / 0 |
| FP16 / F32 | invalid geometry | 44 | 0 | 0 / 0 |
| Q8 / default FP16 | invalid geometry | 0 | 0 | 0 / 0 |
| Q8 / F32 | invalid geometry | 8 | 0 | 0 / 0 |

The original subgroup-16 small-tile reports used the invalid warp mapping
identified by the phone follow-up. Their spill estimates are withdrawn.
Regenerate them with the corrected 0.6.6 analysis script before comparison.

Every listed specialization reports 64 work registers and 50% thread occupancy.
Thus the rectangular candidates remove modeled spills in these reports; they
do **not** demonstrate higher modeled occupancy. Shared storage is 8,704 bytes
for the stock medium configuration and 6,528 bytes for the rectangular ones.
The rectangular kernels hold half as many output elements per thread but need
more workgroups and can sacrifice input reuse. None of these observations
establishes a net speedup on the phone.

The first phone campaign confirms subgroup size 16. The export includes that,
maximum workgroup invocations and shared-memory limit. Match those to a static
report before applying its conclusions. The offline compiler backend also need
not match the installed driver. The runtime's conditional FP16 round-to-even
SPIR-V patch is not applied to these offline input files. No cycle estimate
should be reported as a predicted encoder duration.

## Host numerical checks

The host numerical checks also caught a CPU benchmarking trap. The pinned
`CPU_REPACK` support predicate accepts a padded-stride right-hand operand, but
that synthetic execution produced relative RMS error above 160%. Kernel hunt
therefore only selects CPU prepacking for contiguous activations. The GPU
strided case remains unchanged, and the phone CPU baseline is contiguous.
Host probes now fail on excessive RMS error, not finiteness alone. This does
not establish that the production encoder supplies incompatible strides; its
graph is unchanged by this experiment.

## Reproduce static analysis

Obtain Arm's compiler explicitly from its official distribution and build the
pinned native runtime. Supply its generated `vulkan-shaders.spv` directory:

```sh
python3 scripts/analyze-mali-shaders.py --malioc /path/to/malioc --shader-dir /path/to/vulkan-shaders.spv --output /path/outside/checkout/mali-stock
python3 scripts/analyze-mali-shaders.py --malioc /path/to/malioc --shader-dir /path/to/vulkan-shaders.spv --output /path/outside/checkout/mali-rectangular --subgroups 16 --tiles 64x32 32x64
```

The script never downloads or installs tools. It accepts an executable wrapper
for a supported host architecture, records compiler identity, preserves the
exact SPIR-V hashes and supplies explicit specialization values. It reflects
`SpecId` decorations first: optimized SPIR-V removes unused constants, and
passing those removed locations makes the compiler reject the command. Reusing
an existing campaign output is rejected to protect its provenance. Raw tool
reports can contain local paths; do not commit them.

The native experiment modifies only the supported dense FP16/Q8 medium pipeline
specializations, not the shared vectors used by unrelated indexed matmuls.
Dispatch tile dimensions and specialization values change together. Conservative
stock shared-memory availability gates remain. The analyzer excludes requested
tile variants whose route counters show no actual selection.

## Remaining investigation after the phone run

Rank unprofiled full-encoder measurements first. A useful rectangular-kernel win
should survive integration and a numerical screen on the actual recording.
Small differences need repeated cool runs because earlier CPU baselines drifted
substantially over a long suite. Profiled intervals identify expensive shape
groups but include dependency effects; the no-remote batch does not measure
hardware GPU busy cycles, actual DRAM traffic, register spills on the live
driver or clock residency.

An independent optimized GEMM implementation, operator fusion, split-K tuning,
packing and hardware-counter profiling remain possible follow-ups. They are not
implemented or ruled out by this campaign. A weak result cannot prove a universal
CPU advantage or establish the GPU's achievable hardware ceiling.

Primary references:

- [Pinned runtime and Vulkan implementation](https://github.com/tether-ai-research/qvac-transcribepsy/tree/364ec36130dc87d2cd46e596598268a6e3313751/ggml/src/ggml-vulkan).
- [Arm Mali Offline Compiler](https://developer.arm.com/Tools%20and%20Software/Mali%20Offline%20Compiler).
- [Arm JSON report documentation](https://support.arm.com/documentation/101863/2026-5/Using-Mali-Offline-Compiler/Generating-JSON-reports).
- [Arm's static-analysis limitations](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/accelerating-shader-programs-with-mali-offline-compiler-7).
- [Vulkan profiling guidance](https://docs.vulkan.org/guide/latest/profiling.html).

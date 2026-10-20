# Mali kernel hunt: hypotheses and measurement contract

Status: corrected 48-case phone campaign complete; targeted optimization experiments
implemented for the next phone campaign.

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
policies or measured speedups. The preceding campaign motivates them but does
not measure their implementations. Queued multi-graph tests and contraction
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

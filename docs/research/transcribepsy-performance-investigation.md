# TranscribePsy: distinguish hardware limits from implementation costs

Investigated: 2026-10-19.
Status: source investigation and proposed experiments; instrumentation and
optimizations below are not implemented or accepted product defaults.

Scope: runtime `364ec36130dc87d2cd46e596598268a6e3313751`, the reviewed Q8/FP16
EU-sub files, and Altiro 0.6.0. Source anchors below refer to that exact revision.
The model metadata and code were inspected directly. Phone figures are
maintainer-reported same-recording comparisons, not a controlled benchmark.
No additional GPU measurements were performed by this investigation.

## What the existing phone evidence establishes

| Recording | Processor/format | Encoder | Decoder | Recognition wall |
| --- | --- | ---: | ---: | ---: |
| 15.28 s | CPU/Q8 | 2.229 s | 0.059 s | 2.31 s |
| same audio | Vulkan/Q8 | 4.251 s | 0.574 s | 4.85 s |
| 11.56 s | Vulkan/FP16 | 3.808 s | 0.960 s | 4.79 s |
| same audio | CPU/FP16 | 2.100 s | 0.122 s | 2.24 s |
| 31.88 s | CPU/Q8 | 5.275 s | 0.118 s | 5.44 s |
| same audio | CPU/FP16 | 7.438 s | 0.294 s | 7.77 s |

GPU graphs completed with zero unsupported operations. CPU is faster in these
runs, including tests with opposite processor orders. This does not establish
an architectural rule. Different formats were not compared on identical audio
in the first two pairs. The longer CPU comparison used two encoder windows.

For the Q8 pair, eliminating the GPU decoder entirely would still leave a
4.251 s encoder against approximately 2.306 s for the CPU's complete native
inference. A GPU encoder plus the observed CPU decoder would be approximately
4.310 s before additional integration costs. These are arithmetic bounds using
these timings, not measured hybrid performance. Encoder investigation has
priority for single-utterance latency.

## Follow the real timing and execution paths

The upstream root is [the pinned source tree][source].

| Boundary | Code anchor | What to separate |
| --- | --- | --- |
| Engine setup | `src/parakeet_engine.cpp`, constructor around 550–585 | Model read/upload, decoder weight preparation, persistent state/graphs |
| Encoder timer | same file, `transcribe_from_mel`, 789–858 | Whole `run_encoder` or windowed calls, not shader execution alone |
| Encoder graph | `src/parakeet_ctc.cpp`, `build_encoder_graph_cached`, 2294 onward; `run_encoder`, 2610 onward | Shape-cache miss, host masks, allocation, transfers, execution, readback |
| Backend wrapper | `ggml/src/ggml-backend.cpp`, `ggml_backend_graph_compute`, 444–448 | Async graph enqueue followed by backend synchronization |
| Lazy pipelines | `ggml/src/ggml-vulkan/ggml-vulkan.cpp`, `ggml_pipeline_request_descriptor_sets`, 2514–2522 | An uncompiled pipeline invokes `ggml_vk_load_shaders` from graph construction |
| Pipeline compile | same file, `ggml_vk_create_pipeline_func`, 2291 onward; compile loop, 3635–3674 | Shader module/pipeline creation and compiler-worker wait |
| Vulkan graph | same file, `ggml_backend_vk_graph_compute`, 15534 onward | Host recording, incremental submissions, GPU execution, dependencies |
| Final wait | same file, `ggml_vk_synchronize`, 15005 onward | Pending transfers, submission, fence waits, host copies |
| TDT routing | `src/parakeet_tdt.cpp`, `tdt_prepare_runtime`, 819–874 | CPU specialized loop versus GPU graph path |
| TDT loop | same file, 1120–1234 | Token/duration-dependent control, blank and nonblank work |

Consequently `encode_ms` includes first-use preparation. Altiro starts a fresh
process per comparison pass, so its current experiment does not isolate steady
state. Pipeline compilation can occur after the model-load phase. Persistent
Vulkan pipeline caching is opt-in through `GGML_VK_PIPELINE_CACHE_DIR`; Altiro
does not set it. Driver-managed caches may still exist, so call the test
"fresh process", not "guaranteed cold driver cache".

Our Android registration-only `optnone` patch affects `ggml_vk_load_shaders`.
That function is re-entered on demand for new pipelines. Its host registry work
must be timed separately from driver compilation before ruling it out. GPU
numerical kernels are not disabled by this patch.

## Actual shapes and number formats

The GGUF contains 24 encoder blocks, width 640, eight attention heads of width
80, feed-forward width 2560, a nine-tap convolution and 8x time subsampling.
The decoder has one 640-wide LSTM and a 640-wide joint layer. Its output has
8192 tokens, blank, and five duration scores.

Use `Y[M,N] = W[M,K] X[K,N]` when reporting matrix work. GGUF stores matrix
dimensions in the reverse listing order from this notation.

| Operation | M | K | N | Q8 artifact weight type |
| --- | ---: | ---: | --- | --- |
| Encoder FF expansion | 2560 | 640 | encoded frames T | Q8_0 |
| Encoder FF contraction | 640 | 2560 | T | Q8_0 |
| Stacked QKV | 1920 | 640 | T | Q8_0 |
| Encoder pointwise expansion | 1280 | 640 | T | Q8_0 |
| Joint output | 8198 | 640 | 1 for current decode; B for batched candidates | Q8_0 |
| Predictor input projection | 2560 | 640 | 1 or B | Q8_0 |

At roughly 15 seconds T is about 190, subject to exact subsampling rounding.
An FF expansion alone is about 623 million multiply/add operations at T=190
when counting a multiply and add separately. There are two FF modules per
block, each with expansion and contraction. Across 24 blocks, those FF
matrices alone are about 59.8 billion equivalent arithmetic operations. This
is a shape calculation, not a hardware instruction count or measured FLOP/s.
"The model is too small for GPU" is not an adequate conclusion from batch size
one. Profile the actual matrix shapes and shader paths.

Most encoder matrices and the decoder matrices are Q8_0 in the Q8 artifact;
depthwise-convolution weights remain F16 and normalization parameters F32.
The CPU decoder keeps suitable Q8 matrices packed and quantizes input vectors
for its dot-product kernel. Its non-Q8 matrices are expanded to F32. The
decoder's optional row worker pool is not enabled by our configuration; the
four-thread setting primarily describes encoder work.

On Vulkan, `shaderInt8=true` does not establish accelerated integer dot products
or selection of the integer matrix kernel. Matrix routing also checks the
integer-dot extension/property, compiler support, requested precision and
layout. `quantize_y` and `qx_needs_dequant` around lines 8183–8215 determine
activation packing and whether a separate weight conversion is necessary.
Record the selected pipeline rather than inferring it from GGUF type.

## Encoder candidates: profile before choosing

1. **Direct depthwise convolution.** `use_conv2d_dw = backend_is_cpu(backend)`
   at CTC line 2311 restricts the direct per-block convolution to CPU. The GPU
   path lowers it through `ggml_conv_1d_dw`; `ggml.c` 4531–4546 constructs an
   F16 im2col tensor and matrix multiplication. Yet Vulkan's support predicate
   at 16892–16894 advertises `CONV_2D_DW` with F16/F32 weights and F32 input.
   Experiment with the direct path only after checking actual layouts/shapes
   and numerical agreement. Preserve the separate subsampler's Mali workaround.
   Count the eliminated buffers and dispatches; do not assume this accounts for
   seconds without measuring it.
2. **Matrix kernels.** Benchmark the table's actual shapes and layouts with
   already resident buffers. Record integer-dot versus dequantizing paths,
   accumulator precision, alignment, workgroup/subgroup sizes and split-K.
   Compare a small tile sweep after establishing the baseline. Cooperative
   matrices are disabled in our build; a compile flag cannot create hardware
   support. Gate any alternative on actual device features and correctness.
3. **Fusion and layout.** Count `CONT`, transpose/materialization, affine
   normalization, activation and residual operations and their byte traffic.
   GPU correctness workarounds deliberately make some strided views contiguous.
   Try fused layout-aware kernels rather than simply deleting those copies.
4. **Graph ordering.** The scheduler calls `ggml_backend_graph_optimize` at
   backend.cpp 1417. QVAC's direct graph-compute path bypasses that hook; Vulkan
   has its own reorderer at 15934 onward. A bounded encoder-only experiment can
   expose/call it without restoring the scheduler reuse that upstream disabled
   for corruption. Rebuild graph-source snapshots after any reorder; preserve
   alias/write dependencies. Do not apply it blindly to persistent TDT state.
5. **Attention.** The encoder's Flash Attention implementation is behind
   `PARAKEET_EXPERIMENTAL_FLASH_ATTN` around CTC 1975–2018. It is not enabled by
   our build or Whisper's setting. Relative-position scores must become the
   correct additive mask. Time attention first, then check supported head width,
   numerical behavior and memory before a separate experiment.
6. **Submission granularity.** Vulkan graph compute around 15602–15625 uses a
   100-node limit and `min(100 MB, last_total_mul_mat_bytes / 40)` as a byte
   threshold, then grows that threshold for the first few submissions. The
   feedback is from the previous graph on this backend, not the current graph's
   semantic role. Alternating a large encoder and small decoder graphs can
   therefore change batching between first and repeated runs. Record actual
   thresholds/submission counts; compare fixed per-role budgets while preserving
   dependencies. Do not equate 77 graph calls with 77 queue submissions.

## Decoder dependencies and multiple prediction paths

For a fixed text-prefix state `u`, write the joint calculation as:

```text
A[t]       = W_encoder * acoustic[t] + b_encoder
P[u]       = W_predictor * predictor_state[u] + b_predictor
scores[t,u] = W_output * ReLU(A[t] + P[u]) + b_output
```

The GPU already caches encoder-side projections. The host controls the token
and duration walk. A blank advances audio without changing predictor state.
A nonblank emission updates the LSTM state; subsequent predictions depend on
that emitted token. A single greedy chain therefore cannot compute arbitrary
future tokens independently.

### Exact greedy lookahead across candidate audio frames

For the **same prefix**, compute joint outputs for B candidate frames in one
matrix-matrix operation, then walk the actual greedy decisions in order:

```text
while audio remains:
    candidates = joint_batch(A[t : t+B], P[current_prefix])
    while the current frame is inside this candidate block:
        token, duration = candidates[current frame]
        if token is blank:
            advance by max(1, duration)
        else:
            emit token and update predictor state
            apply the ordinary duration/symbol-limit transition
            discard candidates computed with the old prefix
            break
```

The ordinary state machine, including symbols-per-frame and end/window carry,
must be replayed exactly. This can preserve greedy semantics, apart from
floating-point differences that can alter close decisions. It does not accept
several nonblank tokens from an unchanged-prefix batch.

Measure `B = 1, 2, 4, 8, 16`. First collect blank-run lengths, emission count,
duration histogram and evaluated-versus-used candidate count. Upstream's
`PARAKEET_TDT_STATS` near TDT 1167 already sketches run statistics; export
bounded numeric aggregates rather than its raw stderr. TDT already skips
frames using predicted durations, so long useful blank runs cannot be assumed.
If prefix changes occur almost every step, batching wastes work and can lose.

### Other ways to amortize the serial path

| Alternative | What can improve | What the test must establish |
| --- | --- | --- |
| Pack token/frame inputs and token/duration outputs into paired control buffers | Fewer tiny transfer/readback calls | Actual submit/fence reduction, not just fewer API calls |
| GPU encoder, CPU decoder | Avoid per-step GPU control costs | Encoder must first approach CPU time; current arithmetic bound still loses |
| Independent decoder lanes, B=1/2/4/8 | More useful work per matrix and submission | Throughput per independent utterance versus single-utterance latency; separate states |
| Beam hypotheses in one batch | Amortize shared weights across prefixes; potentially improve accuracy | Equal-quality comparison against CPU beam search, not CPU greedy |
| GPU-controlled chunks of decoder steps | Reduce host round trips without pretending dependencies disappear | Correct indirect-dispatch/state barriers, bounded chunk length, cancellation |
| Single-workgroup persistent decoder | Keep state/control on device | Limited occupancy versus synchronization savings; bounded execution and watchdog behavior |

For GPU-controlled chunks, `vkCmdDispatchIndirect` reads work dimensions from
a device buffer. A control shader can choose the next predictor/joint work
without returning each decision to the CPU. This needs a command/data-flow
redesign, explicit indirect-read/compute-write dependencies and bounded work.
It is not equivalent to submitting the current host-controlled graphs in a
loop. A global spin barrier across arbitrary workgroups can deadlock and is
not an acceptable implementation shortcut.

Duplicating one hypothesis simply to fill the GPU measures saturation, not a
dictation improvement. Keep distinct labels for artificial load, independent
utterance throughput, beam-search quality and exact single-chain latency.
Avoid multiple isolated Android processes for batching: they duplicate models,
allocations and pipeline setup and can exhaust phone memory.

## Instrumentation that distinguishes the hypotheses

The first implementation should be an explicit diagnostic experiment reusing
one ephemeral recording, with no automatic insertion and no default history.
Keep the ordinary cold process path as the reference. A resident benchmark
engine is opt-in and released after that experiment; it is not a product default.

Keep three lifetimes distinct: fresh process/new engine; reused process/new
engine; reused process/reused engine. Our current JNI `create()` allocates a
cancellation operation, not a loaded model: `recognize()` constructs and
destroys its own `Engine`. Repeating the existing call in one process therefore
does not test model/graph residency. A dedicated single-request benchmark entry
point should construct one engine and repeat `transcribe_samples` explicitly.
Freeze one engine family/model/backend per worker series: Vulkan registration
is gated on its first use, so mixing CPU and GPU in a reused process is invalid
for the reference's CPU-without-Vulkan guarantee.

Use explicit series/pass/repeat IDs. The existing model/backend/window identity
does not distinguish repetitions and would collapse their runtime records.
Keep structured callbacks within the existing 8 KiB parser limit and saved
reports within the 64 KiB checkpoint bound, or change those contracts explicitly.
An eventual Console action can automate the experiment with one recording;
the user should not have to operate dozens of independent recording tests.

Collect bounded aggregates per pass:

- Fresh process versus reused engine; first run versus subsequent runs; tensor
  shape-cache hits; application-managed pipeline-cache status/seed bytes.
- Model loading, TDT preparation, graph construction, graph allocation, source
  uploads, source-support preflight, descriptor recording, pipeline registry
  traversal, driver compilation, queue submission, fence waits and readbacks.
- Both host wall time and process CPU time. Main-thread CPU time alone misses
  compiler workers and CPU pools. Do not sum overlapping host/GPU intervals.
- GPU timestamp intervals for the encoder and decoder, then sampled operator
  groups; timestamp period/valid bits, pipeline label from a fixed allowlist,
  input/output types, M/N/K/batch/strides, dispatch dimensions and conversion
  bytes. Distinguish graph calls, kernel dispatches, submissions and actual
  blocking fence waits.
- Blank/nonblank counts, duration and run-length histograms, evaluated/used
  lookahead candidates, and decoder thread count.
- Sampled PSS, supported thermal status, charging state and available clock/
  GPU-counter evidence. Missing privileged counters are `UNAVAILABLE`, not zero.

Measure the observer too: our support preflight, progress IPC and sampled PSS
collection are host work inside the phase boundaries. Compare diagnostic modes
with those samples disabled, without disabling support checks in ordinary use.

Vulkan already has `GGML_VK_PERF_LOGGER` and a concurrent mode, around lines
6381 and 15534–15920. The detailed mode introduces synchronization between
samples and prints logs: use it to locate hotspots, not as the production
latency baseline. Prefer a structured hook with aggregate exports. Check
timestamp support on the actual queue; read queries after the normal completion
fence rather than inserting a CPU wait after every node. Always measure again
with profiling disabled and check observer overhead.
An outer GPU timestamp span can include queue idle gaps; it is not an active
GPU-cycle counter. Correlate sampled kernel intervals with host submissions and
hardware counters before claiming arithmetic saturation or memory saturation.

An encoder output around T=190 contains approximately `190*640*4 = 486,400`
bytes. The current GPU encoder copies it to host, and GPU TDT uploads it again.
Measure this bounce separately; it is approximately a megabyte of payload,
not an unexplained multi-gigabyte transfer. Per-step control has tiny payloads
but potentially expensive synchronization. Count both rather than guessing.

## Experiment order and rejection criteria

Use a small public, licensed speech fixture initially. Test real multilingual
quality separately, with explicit ephemeral audio reuse. Freeze model hash,
runtime/compiler/shader pins and settings. Repeat CPU/GPU orders and report
individual samples plus median/p95; control thermal recovery and record state.
Never infer a stable p95 from only a few repeats.

| Order | Experiment | Discriminating result |
| --- | --- | --- |
| 1 | One engine, same audio/shape, first run plus repeated reset-state runs | Large warm improvement with fewer pipeline compiles identifies setup amortization |
| 2 | Fresh processes with empty/seeded app pipeline cache | Isolates persistent pipeline-cache benefits from graph/model residency; system cache remains uncontrolled |
| 3 | GPU timestamps versus host recording/compilation/submit/wait trace | Busy GPU identifies device/kernel work; long host gaps identify dispatch/compiler starvation |
| 4 | Isolated warm matrices and conv/layout kernels using actual shapes | Identifies arithmetic/layout bottlenecks without model/session setup |
| 5 | One encoder change at a time: direct conv, selected kernel, fusion/order, attention | Requires profiled hotspot reduction and full-run improvement with profiler off |
| 6 | Frozen encoder features, CPU and GPU decoders; then synthetic independent lanes | Isolates decoder work and tests whether batching improves throughput |
| 7 | Blank lookahead or GPU-controlled step chunks | Requires fewer waits/useful batching with correct greedy transitions and acceptable numerical behavior |
| 8 | Full short/30s-boundary/long recordings and repeated daily-use sessions | Establishes useful latency, quality, resource release, cancellation and energy tradeoffs |

Fresh-state repeats must reset the LSTM, token/duration carry and counters.
Upstream `tdt_greedy_decode` creates a fresh `TdtDecodeState`; custom benchmark entry
points must preserve that behavior. Keep recorded PCM/mel/features alive only
for the explicitly active benchmark and delete them afterwards. No text,
audio, token IDs, feature values, paths or exception messages enter reports.
Compare outputs in memory and export equality/counts only; manual quality
evaluation remains separate.

Reject a proposed optimization after non-finite tensors, state corruption,
systematic token divergence, worse unprofiled latency, cancellation regression
or unacceptable memory. A numerical tolerance is not proof of speech quality.
Changing any proposed path into an everyday default requires a separate accepted
decision and the relevant device gates.

The current conclusion is bounded: CPU wins the measured 0.6.0 fresh-process
tests. Finding a faster correct GPU path would falsify "CPU always wins".
Failure of this finite experiment set would justify CPU for this deployment,
not prove a universal property of FastConformer/TDT.

## Measurement references

- [Vulkan timestamps][timestamps]: queue support, period and dependency effects.
- [Indirect compute dispatch][indirect]: device-buffer dispatch parameters.
- [Android GPU Inspector supported devices][agi]: Pixel 7 is listed; actual
  OS/driver validation is still required. External profiling is optional,
  not a prerequisite for the app's structured diagnostic experiment.
- [Arm Streamline][streamline]: CPU/GPU counters and scheduling evidence where
  available. Do not require root or a driver change for the first experiment.

[source]: https://github.com/tether-ai-research/qvac-transcribepsy/tree/364ec36130dc87d2cd46e596598268a6e3313751
[timestamps]: https://docs.vulkan.org/samples/latest/samples/api/timestamp_queries/README.html
[indirect]: https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdDispatchIndirect.html
[agi]: https://developer.android.com/agi/supported-devices
[streamline]: https://developer.arm.com/tools-and-software/streamline-performance-analyzer

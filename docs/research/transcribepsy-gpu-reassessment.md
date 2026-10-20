# TranscribePsy GPU reassessment after the phone campaigns

Researched: 2026-10-20. Independent clean-context Astra web/source review,
followed by local source verification. Research only; no runtime change or new
GPU benchmark was performed. The [completed campaigns](mali-kernel-hunt.md)
remain the device evidence.

## Judgment

The phone results establish limited gains from the tested GGML Vulkan paths.
They do not establish that this model is intrinsically CPU-oriented, or that
another GPU implementation cannot improve substantially. CPU Q8 remains useful
for the product. Further GPU work should compare different implementations
before expanding another parameter sweep.

TranscribePsy is a compact FastConformer-TDT derived from Parakeet, as described
by the [authors](https://qvac.tether.io/blog/on-device-speech-recognition-transcribepsy)
and [CAFE paper, version 1](https://arxiv.org/html/2610.19134v1). Its CPU GGUF and
Apple accelerator deployments are implementation choices. Published benchmarks
on newer phones, different precisions and different accelerators cannot predict
this Pixel 7 configuration's speed.

In our measured Q8 GPU route, weights are dequantized for floating-point matrix
work. The CPU path uses efficient integer dot-product work. This is an important
implementation asymmetry, not evidence that dense Conformer arithmetic belongs
exclusively on CPUs. Nominal INT8 support is insufficient to establish a fast
integer GPU path.

## Remaining leads, ordered by information value

| Lead | Why it differs from the completed tests | First experiment | Cost / confidence |
| --- | --- | --- | --- |
| G710-specific packed GEMM | Different weight layout/dataflow, rather than another GGML tile override | Compare resident FP16 expansion/contraction with Arm Compute Library; check app OpenCL accessibility first | Medium; strongest concrete kernel lead, gain unknown |
| Device capability and performance counters | Separates driver gates, frequency effects and arithmetic/load-store limits | Export integer-dot properties; validate available counters on this exact OS/driver | Low to medium; high diagnostic value, no direct speed claim |
| Current upstream graph changes | Removes repeated constant work and some operator/decoder boundaries | Audit and test individual deltas with the current verified files | Low to medium; bounded encoder upside |
| True accelerated integer path | Changes GPU arithmetic rather than Q8 storage alone | Only after accelerated-dot support is established, benchmark packing + block-scale-aware W8A8 | High; potential unknown, numerical/quality work required |
| Independent Vulkan/compiled FF block | Tests a different implementation without full model migration | One FF block in ncnn or a compiled Vulkan runtime such as IREE | Medium to high; follow the narrower GEMM result |

A proposed continuation threshold is a repeatable 20% gain on the dominant
complete GEMM calls, or 10% on a complete FF block, at matched numerical quality.
These are investment criteria, not predicted speedups. Include preparation,
activation conversion, scratch, synchronization and memory costs before deciding
whether to integrate a winner.

### Arm's G710 kernel family

[Arm Compute Library at 80bbedb606746dcc1451cffedf3931d9fec3f53c](https://github.com/ARM-software/ComputeLibrary/blob/80bbedb606746dcc1451cffedf3931d9fec3f53c/src/gpu/cl/kernels/gemm/reshaped_only_rhs/ClGemmDefaultConfigReshapedRhsOnlyValhall.cpp)
selects `configure_G710_f16` for G710/G610, with reshaped-RHS and buffer/image
choices distinct from other generations. This is concrete vendor source for a
kernel family we have not benchmarked. It does not promise a gain.

First prove OpenCL initialization and kernel execution from an ordinary app on
this phone. If unavailable through permitted application interfaces, stop that
integration lane; its packing/dataflow remains a Vulkan implementation lead.

Compare the two dominant matrix families at frame widths 145 and 201. Our
notation is `Y[M,N] = W[M,K] X[K,N]`; to keep weights on ACL's static RHS, use the
transposed problem `Yᵀ[N,M] = Xᵀ[N,K] Wᵀ[K,M]`. Account for differing dimension
conventions and physical layouts. Match accumulation/output precision and
include both reused and distinct-weight repetitions. Report one-time packing
separately. A small complete-call gain is insufficient reason for a whole-model
port. [CLBlast](https://github.com/CNugteren/CLBlast) is an additional tunable
OpenCL control, not a demonstrated faster alternative here.

### Capabilities and counters

[Khronos integer-dot properties](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceShaderIntegerDotProductProperties.html)
distinguish accelerated packed signed dot products from general integer support.
Export those properties, selected pipelines and rejection gates. The existence
of the [Arm OpenCL integer-dot extension](https://registry.khronos.org/OpenCL/extensions/arm/cl_arm_integer_dot_product.txt)
does not prove this installed driver exposes it.

Q8_0 has blockwise scales. Repacking into a single per-channel scale is not
lossless; activation quantization changes arithmetic too. An integer path needs
both reconstructed-weight numerical references and actual transcription tests.
The CPU Q8 result is a useful deployment reference, not exact real arithmetic.

[AGI lists Pixel 7](https://developer.android.com/agi/supported-devices), subject
to device validation. Its [counter documentation](https://developer.android.com/agi/sys-trace/counters)
provides a stronger investigation route than wall timers alone. An AGI capture
requires a separate supported profiling setup; this review does not establish
access on this Android build. Collect GPU frequency/active cycles, memory/cache
and arithmetic/load-store indicators where available, plus CPU scheduling,
thermal state and an unprofiled control. Keep denied/missing counters unavailable.
No root, hidden API or remote-debugging setup is introduced by this proposal.

## Verified upstream graph differences

Current Fabric source was pinned to
`303198cf7a6e7c46c0e6b1be2add68468026793b` and fetched files were checked against
that revision. Compare its
[encoder](https://github.com/tetherto/qvac-fabric-speech.cpp/blob/303198cf7a6e7c46c0e6b1be2add68468026793b/engines/parakeet/src/parakeet_ctc.cpp)
with our [pinned original](https://github.com/tether-ai-research/qvac-transcribepsy/blob/364ec36130dc87d2cd46e596598268a6e3313751/src/parakeet_ctc.cpp).

- The original projects positional embeddings inside every layer's forward
  graph around line 1950. Fabric's cache construction around 4155–4240 and
  consumer around 3538 compute/reuse these per graph size. At 201 frames the
  retained F32 projections occupy about 23.50 MiB. The unfused path also caches
  their permutation. Preparation still pays the first computation.
- Fabric uses capability-checked `ggml_siglu` around line 3834. Its fallback
  deliberately materializes strided halves to avoid incorrect Vulkan results;
  deleting those copies without a supported fused operator is unsafe.
- Its [backend contracts](https://github.com/tetherto/qvac-fabric-speech.cpp/blob/303198cf7a6e7c46c0e6b1be2add68468026793b/engines/parakeet/docs/backends.md)
  describe fused LSTM/TDT operations and up to eight greedy steps per graph.
  These target decoder/complete dictation latency, not our encoder-only metric.
  They also distinguish validated Adreno OpenCL from the usual Mali Vulkan path.

The authors [recommend Fabric](https://qvac.tether.io/blog/on-device-speech-recognition-transcribepsy)
for performance. That is a reason to compare, not proof of a Pixel speedup.
A bounded trial must first load the exact existing GGUF and establish numerical
parity, actual per-operation placement and cancellation. New scheduler CPU
fallbacks must not silently enter Altiro's explicit GPU experiment. Do not
wholesale replace the pinned runtime based on desktop results.

## Compute budget and plausible upside

For the recorded width 640, 24 layers, FF width 2560, `T=201` and `P=401`, counting
multiply and add separately gives:

| Encoder work | Arithmetic equivalent, GF |
| --- | ---: |
| Two FF modules per block | 63.229 |
| QKV and attention output projections | 15.807 |
| Convolution pointwise projections | 11.855 |
| Positional projections | 7.884 |
| QK and attention-value products | 2.482 |
| Relative-position QP before shift | 2.476 |
| Partial total | 103.734 |

The original source around 1963 multiplies the full positional tensor before
shifting, confirming the last term. The total excludes subsampling, depthwise
convolution and elementwise work. It is an arithmetic-equivalent count, not
measured instructions; quantized implementations execute different operations.
At about 1.68 s, this corresponds to roughly 62 GF-equivalent/s for this partial
count. Dividing by a nominal chip TFLOP figure cannot establish utilization
without sustained clocks, actual instructions, occupancy and traffic.

The earlier profiler attributes about 80% to matrix multiplication and 47% to
the two largest shape groups. Under those approximate shares, doubling just
those two groups gives about 1.31× overall speed; doubling all matrix work gives
about 1.67×. These are Amdahl estimates from an earlier clip/profile, not measured
gains for a new kernel. Positional caching alone addresses a much smaller share
and shifts first-use work; it cannot explain a large FF acceleration.

Old Mali offline estimates concern candidate specializations and a different
compiler/driver model. They remain hypotheses until constants match the actual
selected phone pipeline; static spills are not hardware counter measurements.

## Alternatives and boundaries

[ncnn Vulkan](https://github.com/Tencent/ncnn/wiki/FAQ-ncnn-vulkan) and
[IREE Vulkan compilation](https://iree.dev/) provide independent implementation
controls. Start with one exact FF block, then the encoder only if warranted.
Full relative-position attention export is additional engineering work.

[LiteRT's GPU delegate](https://developers.google.com/edge/litert/performance/gpu)
executes its quantized models through floating-point views and warns about slow
CPU/GPU partitions. Conversion success is not proof of an accelerated integer
path or useful whole-encoder placement. [ONNX Runtime mobile](https://onnxruntime.ai/docs/tutorials/mobile/)
and [KleidiAI](https://github.com/ARM-software/kleidiai) are useful CPU comparison
routes; neither automatically supplies Mali acceleration. Qualcomm QNN/Hexagon
is a different device target. [NNAPI is deprecated](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)
and is not the first choice for a new backend effort.

Session preparation, pipeline caching and work while recording remain a
separate product lane. They can reduce after-Stop waiting without making the
encoder itself execute faster. This research does not change ordinary settings,
model selection, microphone lifecycle or delivery authority.

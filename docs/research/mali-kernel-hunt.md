# Mali kernel hunt: hypotheses and measurement contract

Status: implemented experiment; physical-phone execution pending.

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
| FP16 / default FP16 | 0 | 8 | 16 | 0 / 0 |
| FP16 / F32 | 0 | 44 | 0 | 0 / 0 |
| Q8 / default FP16 | 44 | 0 | 0 | 0 / 0 |
| Q8 / F32 | 316 | 8 | 0 | 0 / 0 |

Every listed specialization reports 64 work registers and 50% thread occupancy.
Thus the rectangular candidates remove modeled spills in these reports; they
do **not** demonstrate higher modeled occupancy. Shared storage is 8,704 bytes
for the stock medium configuration and 6,528 bytes for the rectangular ones.
The rectangular kernels hold half as many output elements per thread but need
more workgroups and can sacrifice input reuse. None of these observations
establishes a net speedup on the phone.

Subgroup size 16 here is a candidate configuration, not a previously measured
phone property. The new phone export includes the actual device subgroup size,
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

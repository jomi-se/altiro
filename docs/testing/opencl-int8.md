# OpenCL and INT8 phone experiment

Use **0.6.9-opencl-int8** or later. In **Console → Encoder lab**, select
**OpenCL + INT8**, enable **Q8 + FP16**, and leave **GPU profiler** off for the
first run. Record 10–20 seconds, stop, and keep the app visible until completion.
Reconnect the explicitly paired live receiver if it has restarted, or share the
complete report file afterward.

Without profiling there are **27 cases**: 15 for Q8 and 12 for FP16. INT8 is
Q8-only and is omitted from the FP16 schedule. Both formats retain CPU, stock
GPU and F32 GPU controls. Successive runs reverse treatments and model order.

The OpenCL cases execute pinned Arm Compute Library reshaped-RHS FP16 kernels
with a matched GGML Vulkan control using F16 weights and F32 accumulation.
They do not run the full ACL host library
or its automatic dispatcher. The integer cases execute signed packed Vulkan dot
products, preserving per-block Q8_0 weight scales and charging GPU activation
quantization to every complete call. Their matched GGML control retains the
original Q8_0 weights and uses F32 accumulation.

Each case contains four shapes and six repeats. Compare five warm complete-call
measurements, not unmatched first-call compilation. Static source/weight
preparation is separate; per-call packing, submissions and waits are included.
Missing GPU timing is unavailable, never zero. Reference work and readback checks
remain outside timed compute and are reported separately. The integer test's
full CPU oracle follows its first call and precedes warm calls, so first-versus-
warm differences also include CPU work and thermal/DVFS effects. Use paired warm
candidate/control comparisons, controls before/after, and cooled repeat runs.

Interpret integer-oracle agreement separately from the error introduced by
activation quantization. For OpenCL, read the declared half accumulation/output
and its error versus the F32 reference. Numerical screens are implementation
checks, not speech-quality scores. Neither family enters the full-encoder
ranking, and a synthetic win is not yet a production improvement.

If a driver rejects a program, feature, allocation or queue operation, keep the
partial export. Fixed failure codes identify the stage without exposing raw
compiler logs or dictated content. No CPU retry hides a failed GPU case.

## Host lifecycle regression

After configuring the pinned native build, run the fake-driver test against its
headers and generated ACL source envelope:

```sh
./scripts/check-acl-lifecycle.sh "$NATIVE_BUILD"
```

This executes the actual OpenCL wrapper with fake driver calls, covering normal
completion, cancellation after enqueue, enqueue/finish/read failures and cleanup.
Uncertain pending work quarantines resources and the loader until the isolated
worker exits; cleanup never adds an unbounded wait. The capability probe has a
network-free fake ABI test in the ordinary Python suite too. Neither test
executes a GPU. Android cancellation and new kernel execution remain phone gates.

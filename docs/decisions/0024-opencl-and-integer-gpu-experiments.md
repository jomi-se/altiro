# 0024 · Upstream OpenCL and signed-integer GPU experiments

Accepted: 2026-10-20. After the 0.6.8 phone campaign verified ordinary-app
OpenCL execution and advertised accelerated signed packed dot products, the
owner requested the next experiments and explicitly asked Astra to implement.

Add **OpenCL + INT8** to the opt-in Encoder lab. Keep ordinary recognition
settings, models, delivery and microphone behavior unchanged. Retain isolated
workers, deadlines, cancellation, partial exports and explicit live pairing.
No backend automatically retries a failed computation on the CPU.

## Two independent implementations

The OpenCL lane executes the unmodified Arm Compute Library reshaped-RHS
FP16 kernels from revision `80bbedb606746dcc1451cffedf3931d9fec3f53c`.
Fetch the archive by SHA-256 and verify every embedded source/header separately.
Only textual include expansion prepares the source; preserve vendor code and MIT
notices. This is a direct vendor-kernel comparison, not the complete ACL host
library, automatic dispatcher or an Altiro kernel described as vendor code.

Map `Y[M,N] = W[M,K] X[K,N]` to the transposed ACL problem so static weights are
the reshaped RHS. Report actual tile/build configuration and precision. Half
accumulation/output must not be represented as F32 arithmetic. Compare against
identical half-rounded inputs with a separate resident GGML Vulkan control using
F16 weights and F32 accumulation;
report reference and full-output errors before interpreting speed.

The integer lane uses actual Vulkan signed packed 4×8-bit dot instructions.
Require and enable the integer-dot feature and the appropriate public extension
or core version; additionally require the advertised accelerated signed-packed
property. Preserve Q8_0's original per-32-value half scale and signed weight
bytes, and quantize activations per 32 values on the GPU. Sum separately scaled
integer blocks into F32 outputs. Do not collapse scales into a per-channel
approximation or describe Q8 storage alone as integer execution.
The performance control uses the original Q8_0 weight bytes with F32
accumulation; the floating-point accuracy oracle is separate from this control.

The pinned shaderc frontend lacks the newer GLSL packed-dot builtin. Use the
standard `GL_EXT_spirv_intrinsics` mechanism to emit the signed packed
`OpSDotKHR` instruction; inspect the generated SPIR-V at build time for the exact
opcode, signed result type, packed operand format and required capabilities.
Reject unsigned/mixed/saturating substitutes and accidental Int8/Int64 types.
This preserves the pinned compiler while proving the emitted arithmetic.

Check integer arithmetic against a quantized integer/double oracle separately
from error introduced relative to the floating-point inputs. A tight integer
correctness screen does not establish acceptable model or speech quality.
INT8 cases apply only to the Q8 source; omit them from the FP16 queue and count
planned cases from the actual per-model schedule.

## Measurement and boundaries

Both lanes test actual recording-derived width and fixed widths 145/201, hot
expansion/contraction shapes and ragged boundaries. Use one first and five warm
calls, alternate candidate/control order, report static packing and compilation
separately, and charge per-call activation conversion/packing and synchronization
to complete wall time. Record nullable GPU intervals, CPU work, resources,
reference checks, source identity and explicit arithmetic semantics.

OpenCL candidates use a Vulkan matched control in the same worker. Keep the
legacy worker/control backend field for compatibility, and explicitly identify
`candidate_backend_requested` as OPENCL for these cases. Do not introduce a
production OpenCL selector or imply that ordinary recognition uses these kernels.

Host tests and compilation cannot establish physical GPU correctness or a
full-encoder gain. The [phone procedure](../testing/opencl-int8.md) records the
next campaign; broader integration requires measured wins and speech checks.

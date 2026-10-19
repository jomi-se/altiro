# 0019 · Opt-in encoder laboratory

Accepted: 2026-10-19. The owner requested automated phone experiments to explain
TranscribePsy encoder performance, rather than infer CPU superiority from two runs.

Console gains an explicit Encoder lab action. One recording, limited to 30 seconds
for this experiment, is reused by a frozen, sequential suite. Ordinary dictation
and its defaults do not change. The suite measures the encoder directly through
the pinned runtime's `run_encoder`, without running or timing the decoder.

Each case gets a fresh crash-contained recognition process. Within that case,
one loaded model and input shape are retained for a first execution and three
warm executions. This is a deliberate exception to decision 0018's one-shot
model lifetime, confined to the lab. First execution is reported separately;
warm median and range describe resident-model execution, not everyday latency.

The matrix includes CPU threads, CPU/Vulkan baselines, Vulkan direct depthwise
convolution, graph reordering, submission budgets, attention, accumulation
precision, and integer-dot-product routing. Stock baselines bracket the variants
to reveal order/thermal drift. Installed Q8 and FP16 profiles can both participate.
An optional profiling case collects upstream Vulkan timestamps and operation
groups; its timings are explicitly excluded from latency rankings.

Native telemetry separates graph construction/allocation, uploads, compute
including synchronization, readback, lazy shader registration, and submissions.
Fixed operation/type/shape metadata and selected shader routes contain no input
values. Each candidate compares its encoder output in memory with a CPU reference
of the same model and recording: shape, finiteness and relative RMS/max error.
Numerical agreement is evidence, not transcription-quality acceptance.

The parent saves bounded, content-free partial results after every callback,
including case options, repetitions, model digest, thermal status, battery
temperature and sampled memory. Features, audio and transcripts never enter the
report. Audio is deleted on completion, cancellation or failure. A failed case
is recorded and the suite continues in a new process; this is not automatic GPU
fallback. Stop/Cancel, manual-lock cancellation and the single recording owner
remain authoritative. A case deadline prevents a hung GPU from holding the suite.

Experimental knobs are disabled outside the lab. Unsupported operations, invalid
numerics and worker crashes remain visible results. GPU execution and numerical
acceptance require the physical phone; the CPU-only VM cannot establish them.

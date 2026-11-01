# TranscribePsy precision and processor experiment

Accepted: 2026-10-18.

The maintainer authorizes comparing TranscribePsy FP16 and Q8 on the Pixel 7's
CPU and Vulkan GPU after finding Whisper Small too slow and Base insufficiently
accurate. Add an experimental native backend; retain Whisper and the existing
recording, editor authority and one-attempt insertion paths.

Pin QVAC's runtime to `364ec36130dc87d2cd46e596598268a6e3313751` and its archive
SHA-256. Build a separate JNI library with statically linked, hidden backend
symbols so its ggml fork cannot interpose on Whisper's. Compile the ARM CPU path
with dot-product and FP16 support, without I8MM. Check hardware capabilities
before use. Vulkan is explicit; a requested GPU that resolves to CPU is an error,
not a successful GPU run. No automatic retry or runtime/model substitution.

The eight-language EU-sub model automatically recognizes language. Whisper's
fixed language, vocabulary hints, Flash Attention and audio-context switches do
not apply to it; make this visible rather than silently claiming they work.
Use completed 16 kHz mono recordings and explicitly bounded 30-second encoder
windows with context for longer recordings. Do not inherit upstream's
300-second window. No native streaming or latency/Chilean-quality claim.

Acquire the published Q8 through the existing explicit pinned HTTPS download
and byte/hash verification. Generate FP16 from the original pinned checkpoint,
not by expanding Q8; provide reproducible tooling and verified import until an
audited public FP16 binary exists. Keep models and intermediate checkpoints out
of Git. Existing saved model selection stays unchanged.

Freeze model, precision and processor before capture. Offer a four-pass
same-recording FP16/Q8 x CPU/Vulkan comparison in Console, sequentially, with no
automatic insertion. Ordinary dictation uses one selected model and processor.
The existing comparison runner is fail-fast: a failed pass stops the remaining
passes and does not retain comparison transcripts. Diagnostics retain completed
pass timings. If Q8 Vulkan fails, select FP16 separately to test that format;
there is no automatic retry or substitution.

Diagnostics report CPU features, the actual engine/encoder/decoder backends,
graph support checks, graph execution counts and preprocessing/encoder/decoder
timings. Upstream direct graph execution does not guarantee per-operation CPU
fallback: preflight unsupported GPU operations and fail explicitly. Reports
exclude speech, audio, paths and exception messages. Cancel never releases an
engine while native work is running; the existing isolated-process termination
bound remains the last resort.

Host builds and smoke tests establish packaging and API behavior only. Pixel 7
CPU/Vulkan execution, repeated dictation, memory, cancellation and EN/FR/Chilean
Spanish quality remain physical-phone acceptance checks. Publisher timings on
newer CPUs and Apple's Neural Engine are not Pixel 7 benchmarks.

Sources: [pinned runtime](https://github.com/tether-ai-research/qvac-transcribepsy/tree/364ec36130dc87d2cd46e596598268a6e3313751),
[build options](https://github.com/tether-ai-research/qvac-transcribepsy/blob/364ec36130dc87d2cd46e596598268a6e3313751/BUILD.md),
[published model](https://huggingface.co/qvac/TranscribePsy-250M-EU-sub-gguf),
[original weights](https://huggingface.co/qvac/TranscribePsy-250M-EU-sub).

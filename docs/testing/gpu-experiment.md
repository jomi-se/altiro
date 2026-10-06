# CPU versus Vulkan GPU on the phone

This is an experiment, not a GPU compatibility or speed claim. Update the APK
in place; installed models remain available and need no new download.

1. Open Altiro, select your preferred installed Small Q8 model, and choose a
   fixed language (ES for Spanish, EN for English). Leave **CPU** selected for
   everyday dictation initially.
2. Tap **Compare CPU and GPU**. Record 10–15 seconds of speech, then Stop.
   Keep the recording screen open. It stays awake while the test runs; a manual
   lock still cancels. Two passes use the same model and audio, sequentially.
   The first test includes fresh GPU pipeline initialization and may be slow.
3. Open **View phase timings** / **Recognition diagnostics**, and Copy or Share
   the report. It names the requested backend for every pass and distinguishes
   GPU probing, model loading, inference, release, and worker startup. `READY`
   on the Vulkan runtime confirms that the context contains a GPU backend;
   `GPU backend initialized in Whisper context: true` confirms initialization;
   `AVAILABLE` alone only means that Vulkan device discovery succeeded.
   A `null` capability was not queried, rather than reported unsupported;
   the Float16/Int8 probe uses Vulkan 1.2 core features.
4. If GPU works, compare text quality as well as post-Stop and inference times.
   Repeat with **Run GPU first**, resting the phone between runs to reduce
   thermal/order bias. Then try 30–40 seconds and the FP16 variant. CPU Q8 being
   faster than CPU FP16 does not predict the ordering on the GPU.
5. If recognition fails, copy diagnostics before starting another recording.
   `NO_GPU`, `STORAGE_16_UNSUPPORTED`, `GPU_INIT_FAILED`, `NATIVE_UNAVAILABLE`,
   `IPC_FAILED`, `OUT_OF_MEMORY`, `DEVICE_LOST`, and `WORKER_DIED` identify different failure classes. The last
   phase, hardware capabilities, Vulkan result code and Android worker exit
   reason (when available) help narrow them down. Failure does not
   trigger an automatic CPU retry. Select CPU explicitly to return to it.
6. If the whole app closes, reopen **Recognition diagnostics** and use **Copy
   saved checkpoint**. The single private, content-free checkpoint survives
   restart. A checkpoint still marked RUNNING describes interrupted work, not
   a resumed session. No transcript or audio is saved with it. **Clear
   diagnostics** deletes both the live trace and checkpoint.
7. Test Cancel during GPU probing/loading and inference. GPU calls may take
   time to return; after ten seconds of stalled cancellation the app terminates
   only the worker process. A new recording stays blocked until that worker
   has disconnected and audio is deleted. No cancelled result may be inserted.
   Also test manual lock and a new CPU recording after a GPU failure.

Vulkan mode permits Whisper's normal mixed GPU/CPU scheduling; it does not
claim every operation executes on the GPU. Total encoder/decoder/batch/prompt/
sampling counters come from the pinned runtime, not its per-call averages.
They are not an additive partition of wall time; features, language detection,
synchronization and scheduling also contribute. Native stderr, exception
messages, paths, speech and editor contents are excluded from exports.

Native inference uses a non-exported service in a separate process. Each pass
gets a fresh process to keep CPU runs independent of Vulkan registry state and
contain driver failures. Contexts are cold-loaded and released; no resident
model or durable transcription job is introduced. Worker overhead is measured
separately. Process separation is crash containment, not an OS security sandbox
or a guarantee against system-wide driver/device faults.

Host/build checks do not execute Android drivers or IPC lifecycle. Execute
`WorkerIsolationTest` on device as well as the manual procedure. Existing
[Gate A](gate-a.md) and [Gate B](gate-b.md) still apply.

## Short recordings and Auto language

The pinned whisper.cpp runtime uses the model's full audio context by default;
Whisper's standard window is 30 seconds. Short clips still pass through that
encoder window, so recognition time need not shrink in proportion to recording
length. The operator's 11.88- and 24.56-second Q8 traces show similar total
encoder counters in each backend; this is consistent with the implementation.
See [Whisper audio constants](https://github.com/openai/whisper/blob/main/whisper/audio.py)
and the [pinned encoder implementation](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/src/whisper.cpp#L1850-L1869).

With Auto, the pinned runtime calls the encoder for language identification,
then calls it again in the transcription loop. Selecting EN or ES skips the
automatic-language branch. This is an available setting, not a model change;
the phone latency improvement remains to be measured. See the pinned
[language detection](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/src/whisper.cpp#L3788-L3814),
[Auto branch](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/src/whisper.cpp#L6351-L6371),
and [transcription loop](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/src/whisper.cpp#L6532-L6555).

## Flash Attention update

Version 0.4.1 enables Flash Attention for Vulkan runs, with an in-app
**Flash Attention for GPU (experimental)** switch. CPU runs retain the previous
method. The setting is fixed when recording starts and applies to ordinary
GPU dictation and GPU passes in comparisons. No model download is needed.

Choose your already installed model and fixed EN/ES, select Vulkan and record
normally. Copy diagnostics after completion or failure. Look for requested
Flash Attention ON, an initialized Whisper context setting of true, total
encoder timing and encoder-call count. The graph setting does not prove that
all attention operations execute on GPU. Compare text quality as well as time;
prior version 0.4.0 reports used Flash Attention off.

If this path fails, use the existing checkpoint/failure recovery procedure,
then turn the switch off before recording again. Do not automatically retry an
uncertain recording or insertion. Test Cancel/manual lock and a new recording
after failure. Upstream enables Flash Attention by default, but physical Mali
stability and any attention-specific benefit remain to be established on the
phone. Initial user-reported version 0.4.1 Small FP16 and Base comparisons both
complete with Flash Attention enabled in the Whisper context; their timings
and evidence limits are recorded in [current work](../plan/current-work.md).
These CPU/GPU comparisons change both backend and attention setting, so they
do not isolate the attention method. See
[decision 0006](../decisions/0006-flash-attention-experiment.md).

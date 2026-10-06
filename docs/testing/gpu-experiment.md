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

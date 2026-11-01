# Encoder lab on a phone

For source-guided wait, tile, split-K and fusion experiments in 0.6.7, choose
[GPU tuning](encoder-tuning.md). Existing suites keep their previous variants.

Debug builds from 0.6.5 can optionally send completed-case checkpoints directly
to a paired Tailnet receiver. See [live results setup](kernel-hunt.md). This marks
the run as network-active; repeat timing candidates with the relay disconnected.

For the deeper no-remote-access matrix investigation in 0.6.4, select
[Kernel hunt](kernel-hunt.md). Quick and Full retain their existing matrix.

Use Altiro 0.6.3 or later. Version 0.6.1 rejected GPU cases before starting their
worker when CPU was selected; 0.6.2 fixed dispatch but could exhaust the ordinary
64-phase trace before later cases. Both issues are corrected in 0.6.3.
Select a ready **TranscribePsy** model, open **Console →
Encoder lab**, and start with **Quick**. Record 10–15 seconds of ordinary speech,
then Stop. The app runs the cases without further taps. The lab stops recording
at about 29 seconds; it does not change the normal dictation limit.

Leave the measurement screen visible. It stays awake; manual locking and Cancel
stop the suite. No benchmark inserts text. Share lab exports the complete JSON;
Copy diagnostics remains a compact clipboard report. Partial lab results survive
an app restart and are removed by Clear diagnostics or replaced by a new suite.
Keep the app open until cancellation finishes releasing the worker. If worker
death cannot be confirmed, new recognition stays blocked until it dies or the
app is force-stopped; the suite never overlaps an unconfirmed worker.

Once Quick works, choose **Full**, **Q8 + FP16** if both are installed, and **GPU
profiler**. Full can take several minutes per format. Each case has a two-minute
deadline; the suite has a forty-minute bound. A failed GPU case is retained and
the next case starts in a fresh process, without silently retrying that case on
CPU. Share the lab report even if some cases fail.

For a second run, let the phone cool, use similar battery/charging conditions,
and run the same suite. Successive suites automatically reverse the interior
variant order and model order, while retaining the bracketing baselines and
empty-before-seeded cache dependency. The actual order is in the report.
Thermal status and battery temperature are sampled,
not controlled; concurrent apps, charging, screen brightness and system power
management can affect the results. The CPU frequency readings are instantaneous
samples where Android permits them, not a frequency residency trace.

## Matrix

Each case uses one model and the same recorded input. First candidate execution
and three resident-model warm repetitions are retained individually. Nothing
changes the everyday model/backend selection.

| Case | Question |
| --- | --- |
| CPU 4 threads, at start and end | Is the reference stable over the suite? |
| GPU stock, at start and end | Is GPU performance stable, or drifting with heat/order? |
| CPU 1, 2, 6, 8 threads (Full) | Is encoder performance limited by useful CPU parallelism or scheduling? |
| GPU direct convolution | Does avoiding the lowered depthwise convolution path help? The separate Mali subsampler workaround stays intact. |
| GPU graph reorder | Does the existing Vulkan graph optimizer improve direct execution? |
| GPU 1 MB / 100 MB submission budget | Does CPU recording/queue submission granularity matter? These fixed budgets replace the adaptive byte threshold; the 100-node bound stays. |
| GPU Flash Attention | Is the upstream experimental attention graph supported and useful for this head width/mask? |
| GPU F32 accumulation | Does changing matrix precision/routing help or hurt? This is not an FP32-weight model. |
| GPU integer-dot disabled | Does the selected integer-dot route actually change? Zero route counts can make this a no-op. |
| CPU / GPU first 5 seconds | How does performance scale with actual input length? Compare this pair separately from full-length cases. |
| GPU empty / seeded app pipeline cache | Does explicitly persisted driver pipeline data improve the next process? System/driver caches remain uncontrolled. |
| GPU operation profiler | Which operation groups dominate GPU timestamp intervals? Exclude this case from latency rankings. |

Quick contains the two bracketing baselines, direct convolution, graph reorder
and the 1 MB submission variant. Full adds the remaining unprofiled variants.
Profiler is an independent optional final case. Q8 + FP16 repeats the suite for
each verified model; keep formats separate when interpreting numerical error.

## What the numbers mean

The lab uses the same pinned native `load_from_gguf`, `compute_log_mel` and
`run_encoder` implementation as recognition. It omits the decoder and engine
wrapper. This is a diagnostic benchmark, not end-to-end dictation latency.

Each worker first runs a same-format four-thread CPU encoder reference, releases
its model weights, then loads the candidate. Consequently **first candidate
execution is not a completely cold process measurement**, especially on CPU.
Reference setup and candidate loading are reported separately. Warm repetitions
reuse weights, graph, allocator and shader state; their median/range answers the
resident-encoder question. Model verification, IPC, reference generation,
feature comparison, report writing and PSS sampling are outside `wall_ms`.

`host_us` separates graph building/reservation, allocation, upload, compute with
synchronization, and readback. Shader registration/compilation and synchronization
are nested timers: **do not add every timer together**. Compile request counts
are requests for actual lazy driver pipelines, not shader source compilation.
Cache file counts/bytes before and after a case establish whether a seed exists;
a seeded case is skipped if no seed was written. They do not prove a driver cache
hit or that the driver accepted every cache entry.

Shader names, matrix type/M×N×K counts, integer-dot/dequant routes, dispatch and
submission counts expose the chosen implementation. Shader labels are bounded
to 64 distinct entries; a dropped-label counter makes truncation visible. No feature values are logged.
Graph shape counts appear when a cached graph is first built, not every reuse.

`process_cpu_ms` covers all worker threads. More than one CPU millisecond per
wall millisecond indicates CPU parallel work; low CPU time during a long GPU
compute span suggests waits, but cannot alone prove GPU saturation. Use the
profile alongside this ratio. PSS is sampled at report callbacks, not a continuous
peak measurement. It can include allocator/driver retention from the reference.

The profiler uses upstream per-operation timestamp queries, only when every
compute queue has 64 valid timestamp bits and the period is positive. It adds
serialization/dependencies and waits. Intervals are grouped by operation and can
include stalls/dependency effects; they are not hardware busy-cycle counters.
Unsupported timestamps produce an explicit failed case, not invented zeros.

`finite`, shape, relative RMS error and maximum absolute error compare candidate
features with the in-memory CPU reference. They do **not** establish transcription
accuracy. Features, text, token IDs, audio and private paths never enter the
report. A speedup with non-finite or materially divergent features is not a
usable optimization; inspect its numerics before testing speech quality.

## Analyze an exported report

```sh
python3 scripts/analyze-encoder-lab.py encoder-lab.json
```

This prints first/warm timings, repeated-baseline drift, numerical error and
profiled hotspots. The fastest-case screen excludes partial, wrong-backend,
non-finite, shorter-input and profiled cases, and uses a 1% relative RMS error
screen. That tolerance is for triage, not model-quality acceptance. Preserve
raw samples and compare more than one run before selecting a new default.

No phone GPU result is implied by a successful build or host CPU smoke test.
If the GPU remains slower, these measurements identify where to invest next:
shader arithmetic/layout tuning for hot kernels, reducing actual launch/submit
costs, or accepting CPU for this specific device/runtime configuration. They
cannot prove CPU is universally faster for this architecture.

## Optional scheduling trace

The app also emits fixed `altiro.encoder.reference`, `altiro.encoder.load` and
`altiro.encoder.repeat-N` ATrace sections from the recognition worker. These
coarse slices surround the JNI phases; native JSON timings remain the timing
authority. To inspect CPU scheduling/frequency alongside them, use the supplied
[Perfetto configuration](../../tools/profiling/encoder-lab.pbtxt) from a computer
with an already authorized ADB connection:

```sh
adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/altiro-encoder.perfetto-trace < tools/profiling/encoder-lab.pbtxt
adb pull /data/misc/perfetto-traces/altiro-encoder.perfetto-trace ./altiro-encoder.perfetto-trace
adb shell rm /data/misc/perfetto-traces/altiro-encoder.perfetto-trace
```

Start Quick while the first command is recording (three minutes). Open the local
trace in Perfetto and inspect the recognition worker's marked slices, scheduled
CPU threads, wakeups and frequency tracks. Look for runnable-but-unscheduled
workers versus sleeping workers, and correlate those intervals with the JSON's
compute/synchronize/compile measurements. The configuration does not collect
hardware GPU busy counters; unavailable kernel frequency events stay unavailable.
A system trace can include other process/thread metadata and is separate from
the content-free JSON; inspect it before sharing. No trace uploads automatically.
These optional ADB commands have not been executed against the maintainer's phone.
See the official [ATrace documentation](https://perfetto.dev/docs/data-sources/atrace)
and [system tracing guide](https://perfetto.dev/docs/getting-started/system-tracing).

The separate [Packed GPU campaign](packed-gpu.md) compares an original packed
register kernel against complete GGML matrix calls and probes ordinary-app GPU
capabilities. These synthetic and capability records are distinct from the
full-encoder ranking.

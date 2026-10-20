# Kernel hunt

Use **0.6.4 or later**. Install it as an update; existing models remain available.

1. Select an installed TranscribePsy model in Models.
2. Open **Console → Encoder lab → Kernel hunt**.
3. Enable **Q8 + FP16** if both are installed, and **GPU profiler**.
4. Tap **Record encoder lab**, speak for about 10 seconds, then Stop.
5. Leave that screen visible while the sequential batch finishes. It stays awake.
6. Tap **Share lab** and send the exported file, including if some cases failed.

In debug builds **0.6.5 or later**, you can instead open the paired receiver
page, copy its **Receiver link**, then use **Console → Encoder lab → Live
results → Connect** before recording. Each completed case and the final report
are sent over your Tailnet. **Received by…** confirms an exact saved-payload
receipt; **Not received** means check Tailscale and reconnect, or use Share lab.
The browser page shows the latest automatic analysis and also accepts a saved
Share lab file. Keep the complete pairing link private. Restarting Altiro clears
the connection. Disconnect stops new uploads; a request already in flight can
finish. Clear diagnostics also disconnects, but does not delete receiver files.

Live relay activity can affect timings. Reports mark it explicitly. Use this
for failure investigation and repeat promising timing candidates disconnected.
No remote commands, audio or transcripts are sent. Receiving results does not
automatically wake an inactive investigator session. See [decision 0021](../decisions/0021-live-lab-relay.md).

No remote access or additional app is required. The recording feeds full-encoder
cases only; synthetic cases use generated numbers. No transcripts are produced
or inserted. Audio is deleted at the end or cancellation. Reports survive restart;
they retain completed measurements before a crash. Clear diagnostics deletes them.
Cancel and manual lock stop the suite. Every case has a two-minute deadline and
the suite a forty-minute limit. Do not force-stop while it is writing a result
unless cancellation cannot release the worker.

Expect several minutes; duration is device-dependent. Failed cases remain visible
and the next worker starts only after confirmed termination. Keep charging,
brightness and other activity consistent across repeat runs. Let the phone cool
between runs. Alternate suites reverse the interior case and model order; start
and end baselines remain in place. The cache-empty/seeded pair retains its order.

## What runs

The two-format batch has forty-eight workers when profiling is enabled. It preserves
CPU/GPU start/end baselines, a CPU two-thread case and the empty/seeded pipeline
cache pair. Full encoder experiments add small tiles, large tiles, small tiles
with F32 accumulation, and persisted cache with F32 accumulation. The optional
GPU profiler remains separate from latency rankings. Two additional rectangular
tile candidates, 64×32 and 32×64 with F32 accumulation, test static compiler
evidence of register spilling in the stock 64×64 specialization.

Ten synthetic cases each run 32 matrix shapes, with one first and five warm
executions per shape. This is **640 shape records and 3,840 matrix-graph calls**
for two formats, in addition to full encoder measurements. Some graph calls
contain eight independent multiplications. Shapes are `M×N×K`, meaning
`weights[M,K] × input[K,N]`:

- `(M,K)`: `(2560,640)`, `(640,2560)`, `(1920,640)`, `(640,640)`.
- `N`: `32, 64, 65, 128, 129, 139, 256, 384`.

| Synthetic case | Hypothesis |
| --- | --- |
| Stock GPU | Establish latency and shape-boundary behavior. |
| Small / large GPU tiles | Find underutilization, padding or register/shared-memory costs. Unsupported tile families retain stock routing with an explicit counter. |
| GPU rectangular tiles | Test 64×32 / 32×64 outputs per workgroup with F32 accumulation; these specializations reduce output elements held per thread. |
| GPU F32 accumulation | Separate accumulation precision/routing from weight storage. |
| GPU pre-dequantized weights | Compare Q8 inline dequantization with a one-time Q8→FP16 preparation. FP16 input makes this a deliberate no-op control. |
| GPU strided activations | Measure padding/layout effects against contiguous F32 accumulation. |
| GPU eight streams | Use eight distinct weight/input/output sets in one graph; compare throughput and memory with the single-stream F32 case. |
| CPU two threads | Run identical stored weights/inputs with a CPU backend. |

The CPU case probes the runtime's optional extra weight buffers, matching the
production encoder's quantized prepacking path when supported. `cpu_repacked`
records whether that path was available; FP16 normally uses the default buffer.
This avoids comparing GPU GEMM against an artificially generic CPU allocation.

Each record includes six wall/process-CPU/readback-check durations, setup time,
resident backend-buffer bytes, shader names and route counts. Transfers and
reference generation are outside the reported compute wall time; synchronization
is inside it. `resident_bytes` excludes backend-internal scratch and driver memory;
sampled process PSS is a separate observation. Warm calls intentionally reuse
resident buffers; the eight-stream case broadens that working set but does not
establish a DRAM-bandwidth roof or eliminate cache reuse.
The backend also survives across shapes within one case: a shape's first call
can reuse pipelines compiled for earlier shapes. It is not a fresh-process
latency measurement.

All output cells must be finite. Sixty-four deterministic cells per stream are
compared to a double-precision dot product using reconstructed stored weights.
Q8-to-FP16 preparation includes extra rounding in the reported error. A 1% RMS
screen only filters suspicious candidates; it is not model-quality acceptance.
Every synthetic matrix is independent. These tests do not simulate the encoder's
intermediate distributions, attention batching or inter-layer dependencies.

## Interpreting the export

```sh
python3 scripts/analyze-encoder-lab.py encoder-lab.json
```

The analyzer separates synthetic measurements from full encoder rankings. It
excludes incomplete cases, wrong backend placement, invalid outputs and outputs
outside its numerical screen. It compares matching shapes, displays CPU timings,
and keeps eight-stream throughput separate from single-matrix latency. Consult
raw repetitions and thermal samples before trusting a small difference.

A shape cliff is a change in **effective throughput** or a disproportionate
latency increase around 64/65 or 128/129, not simply more time for more work.
Check shader labels and forced-tile counters before attributing it to a tile.
Faster pre-dequantized kernels must repay preparation time and extra RAM in the
full model. A synthetic win needs a follow-up full-encoder implementation and
speech-quality tests. Conversely, a weak result here does not prove a hardware
limit: alternative kernels, fusion and hardware-counter evidence remain open.

See [Encoder lab](encoder-lab.md) for lifecycle/timing definitions and optional
ADB scheduling traces. Hardware GPU busy/cache/occupancy counters are not included
in the no-remote-access export.

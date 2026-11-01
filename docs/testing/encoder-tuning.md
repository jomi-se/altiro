# GPU tuning on the phone

Use **0.6.7-encoder-tuning** or later. Open **Console → Encoder lab → GPU tuning**.
Select **Q8 + FP16** if both are installed, leave **GPU profiler** off for the
first run, and record roughly 10–15 seconds. Stop and leave the screen visible
while the suite runs. The screen stays awake; manual lock and Cancel stop it.

If the debug live receiver is already paired, completed-case checkpoints arrive
automatically. Reconnect after installing the update if needed. Otherwise use
**Share lab** for the complete report. Timings contain no audio, features or
transcription. Network-active runs are marked; repeat close results disconnected.

The suite has 21 cases per format, or 22 with the optional profiler. CPU controls
use two and four threads. F32 GPU controls bracket the six independent treatments:
blocking final wait, 256-thread 64×64 tile, split-K two/four, fused bias, and fused
bias+SiLU. Seven focused synthetic cases isolate those changes with eight shapes
each. Existing Quick, Full and Kernel hunt remain available.

Every full-encoder case has one first candidate call after an untimed CPU
reference, followed by three warm calls. Each synthetic shape has one first call
and five warm calls with resident deterministic inputs. Setup, reference work,
loading and output checking remain separate. Synthetic cases compute identical
bias+SiLU work even when it is unfused; a fusion test must actually take the fused
route. Fixed boundary shapes test ragged output dimensions and split boundaries.

Analyze the JSON with:

```sh
python3 scripts/analyze-encoder-lab.py encoder-lab.json
```

Look for:

- Lower **wall time** with finite, comparable full-encoder features.
- Positive route counters for the requested change; a fallback is not a win.
- Reduced **process CPU time** from blocking waits, even if wall time is flat.
- Graph-boundary **GPU time** versus wall/enqueue/wait time. Missing timestamps
  stay unavailable. Timers overlap and must not be added together.
- Double-reference error on focused tests and full unfused-reference error on
  fusion tests. A fast but
  divergent result is excluded from the numerical screen.
- Stable controls before/after treatments, and thermal/battery samples. Let the
  phone cool before repeating; the next run reverses treatments and model order.

Full Q8-versus-CPU feature error also reflects activation arithmetic differences.
The provisional 1% RMS screen is triage, not transcription-quality acceptance.
A favorable synthetic result still needs a full-encoder gain, repeated phone
runs and speech-quality testing before ordinary defaults change. Tile, split-K
and fusion gains overlap and cannot be multiplied.

The new GPU routes require phone validation. A passing host build is not evidence
that any of them executes correctly or faster on the device.

## Host safeguards

The native policy fixture checks tile output ownership, split coverage, frame
arithmetic and timestamp wrap handling. The fusion fixture exercises the pinned
GGML consumer guard and verifies why intermediate rounding and reduction order
matter. With `RUNTIME_SOURCE` pointing to the patched pinned source:

```sh
c++ -std=c++17 -Wall -Wextra -Werror \
  -isystem "$RUNTIME_SOURCE/ggml/include" -isystem "$RUNTIME_SOURCE/ggml/src" \
  inference-transcribepsy/src/test-native/DenseFusionGraphPolicies.cpp \
  -o /tmp/altiro-dense-policies
/tmp/altiro-dense-policies
rm /tmp/altiro-dense-policies
```

`NativeTranscribePsy.java --kernel-hunt MODEL WAV 40` additionally exercises the
focused graph, reference checks and cancellation through the production JNI on
CPU. Its null GPU timestamps must remain null. It cannot validate GPU shaders.

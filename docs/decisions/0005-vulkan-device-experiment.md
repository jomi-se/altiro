# Experimental Vulkan recognition on Android

Accepted: 2026-10-06.

The operator's Pixel 7 reports show inference dominating latency: a 36.96-second
recording took 23.55 seconds with stock Small Q8 and 37.54 seconds with Chilean
Small Q8. The four-model comparison used a fixed order, so thermal and scheduling
effects are not controlled. Reported transcription quality is promising; these
are observations, not an accuracy benchmark or hardware gate completion.

The operator requests a local GPU experiment with extractable runtime diagnostics.
Keep the pinned Whisper runtime and existing verified model files. Add an explicit
CPU / experimental Vulkan selector inside the app and a same-recording CPU/GPU
comparison of the selected model. CPU remains the default. GPU failure must be
visible; do not silently rerun audio on CPU or relabel CPU work as GPU inference.

Run native recognition in a non-exported, separately named application process.
It has the same application UID, can read owned model/audio files, and has no
network access. Bind only while recognizing, serialize model contexts, and
release contexts before acknowledging completion. Driver failure or worker death
discards incomplete text and blocks insertion. Parent-owned audio is deleted
after completion or confirmed worker disconnection. Cancellation uses the native
abort flag; if cleanup stalls, terminate only the recognition process and wait
for disconnection before deleting audio. No durable background work is promised.

Export structured, bounded diagnostics: requested backend, available GPU/device
capabilities, backend initialization, model/phase timings, Whisper compute
timings and typed failure codes. Do not export native stderr, exception text,
paths, speech, editor data, or device identifiers. Retain only the latest small
content-free runtime checkpoint in private no-backup storage, so a failed process
can be diagnosed after restart. It is explicitly clearable and replaced by the
next run; transcripts and audio remain ephemeral.

Visible recording and diagnostics screens keep the display awake during active
capture/processing, including comparison. Clear the flag when work ends or the
screen leaves composition. Manual screen-off/lock still cancels and invalidates
editor authority; this does not enable lock-screen recording.

Pin Vulkan build headers and host shader tools. Android Release omits DWARF
metadata for the large Vulkan dispatcher file while preserving Release
optimization, CPU code and GPU shader settings; installed APKs strip that
metadata. Source-level dispatcher debugging requires a Debug native build.
The pinned Android Clang expands shader registration to roughly 236,000 LLVM
instructions and spends tens of minutes generating it under host emulation.
A verified ARM64 compile completes when only `ggml_vk_load_shaders` has Clang's
`optnone` attribute. Apply that bounded Android Release workaround: this function
registers/initializes pipeline metadata, not numerical tensor computation.
CPU compute, other Vulkan dispatch code and GPU shader programs retain their
optimization settings. Initialization cost is included in phone phase timings;
its effect on the device remains part of the experiment. Preserve generic CPU support and
16 KiB ELF/APK alignment. Compile and host tests prove packaging and protocol
behavior only; Vulkan correctness, cancellation, latency, memory and thermal
behavior require physical-device evidence. No GPU speedup is claimed in advance.

Sources: [pinned Whisper Vulkan support](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/README.md#vulkan-gpu-support),
[Android Vulkan compute](https://developer.android.com/guide/topics/renderscript/migrate).

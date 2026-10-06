# Flash Attention experiment

Accepted: 2026-10-06.

The operator requests enabling Flash Attention after successful Pixel 7 CPU/Vulkan
comparisons with both Small Q8 and FP16. Keep the existing model files, pinned
runtime and cold context lifecycle. Flash Attention is already the default in
upstream whisper.cpp at the pinned revision; Altiro had explicitly disabled it.
That establishes an implemented runtime path, not Mali driver stability or a
phone performance guarantee.

Whisper is a Transformer encoder–decoder. Flash Attention evaluates its existing
scaled dot-product attention in tiles with an online softmax, avoiding the full
intermediate attention matrix. It preserves the mathematical attention operation
and model weights, with possible floating-point rounding differences. It reduces
memory traffic; other encoder operations still contribute to inference time.

Enable Flash Attention for Vulkan runs by default in this experiment. An in-app
**Flash Attention for GPU (experimental)** switch restores the previous method.
Keep CPU passes unchanged with Flash Attention off. Freeze the setting into each
recognition input when recording starts; GPU comparison and ordinary floating
mic dictation use that same snapshot. Never mutate an active native context or
automatically retry failed work with a different attention method.

Include the requested setting for every pass in the content-free diagnostic
checkpoint before native initialization. Report the initialized Whisper context
setting through the native bridge, plus encoder/decoder call counts alongside
existing total counters. Context configuration does not establish which GPU/CPU
backend executes each attention operation; do not claim exclusive GPU execution.
Preserve separate-process failure containment, cancellation, audio deletion and
insertion guards. Return typed failures through the existing path.

Compile both native ABIs and instrumented APKs, run the JVM suite and production
CPU JNI smoke with Flash Attention on and off, including cancellation. Phone
validation must check performance and text quality with fixed language on the
selected model, Cancel/manual lock, and recovery by switching Flash Attention
off. Do not mark the device gates passed from compilation or host evidence.

Sources: pinned [upstream defaults](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/src/whisper.cpp#L3385-L3399),
[pinned Vulkan attention support](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L17164-L17208),
[Whisper model architecture](https://github.com/openai/whisper/blob/main/whisper/model.py),
and [FlashAttention paper](https://arxiv.org/abs/2205.14135).

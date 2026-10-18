# TranscribePsy phone experiment

This is an additional local engine, not a replacement for saved Whisper choices.
The first build targets ARM64, including Pixel 7. No Pixel speed, GPU support or
Chilean accuracy claim follows from a successful host build.

## Install models

In **Models**, choose **TranscribePsy Q8**, then Download. The pinned public Q8
is 304,879,584 bytes. **TranscribePsy FP16** is 569,843,744 bytes and uses Import
file. Obtain the exact prepared GGUF or generate it from original weights using
the [development instructions](../development.md#prepare-transcribepsy-models).
Both pass exact size/SHA-256 verification before installation. Expanded Q8 is
not an FP16 reference. Models remain private files excluded from backup.

The engine recognizes eight languages automatically, including English, Spanish
and French. Its Auto control is intentional. Whisper's language, vocabulary,
Flash Attention and dynamic-context controls do not apply. Its native audio
length is bounded to 30-second encoder chunks, with context for longer speech.

## Compare on identical audio

1. Install both formats and select either TranscribePsy profile.
2. Open **Console → Compare a recording → 4-way**. Choose GPU first if desired.
3. Record 10–15 seconds of ordinary speech and Stop. Keep the phone unlocked;
   visible work requests screen-on. Each pass uses a fresh isolated process.
4. Share diagnostics after completion, or immediately after failure. Share
   exports the complete report; clipboard Copy is a compact view.
5. Compare transcription quality separately. Reports deliberately omit text.

Formats compares Q8/FP16 on the selected processor; CPU/GPU compares the selected
format on both processors. Four-way combines both, sequentially. Comparisons
never insert into another app. A failed pass stops the remaining passes and
discards comparison transcripts; completed timings remain in diagnostics.
If Q8 Vulkan fails, explicitly select FP16 and test CPU/GPU separately. No
automatic CPU retry, model substitution or repeated insertion occurs.

Use single-model Home or bubble dictation for everyday testing. Try the same
EN, FR and Chilean Spanish phrases across engines. Include local expressions,
names, punctuation, short fragments and pauses; training labels do not establish
quality. Verify ordinary automatic insertion and bubble Copy recovery separately.

## Read diagnostics

- Requested processor versus actual engine, encoder and decoder backends.
- CPU FP16/dot-product/I8MM capabilities. Pixel 7 does not need I8MM for this build.
- Completed encoder/decoder graph counts and graph-support checks. CPU decoding
  uses the engine's CPU path; GPU runs require actual decoder graphs.
- First unsupported operation, tensor type and shape after graph rejection.
  The runtime fails explicitly rather than promising per-operation CPU fallback.
- Audio reading, loading, recognition and release wall times, plus upstream
  preprocessing/encoder/decoder counters. Counters need not add to wall time.
- Sampled peak worker PSS. It is a sampled process-memory estimate, not GPU
  allocation accounting or a guaranteed peak.
- GPU name/features and typed Vulkan failure where probing succeeds far enough.

Reports exclude dictation, audio, editor identity, file paths and exception
messages. A restarted worker can leave the last content-free checkpoint. Clear
diagnostics removes it. Do not infer successful GPU execution solely from
device enumeration or requested settings.

## Acceptance still needed

Test both CPU precisions, then explicit GPU runs. Repeat dictation several times
to expose thermal/memory behavior. Cancel while loading and recognizing; check
the mic is released, the session ends and the next recording works. Lock during
work and verify cancellation. Test airplane mode after acquiring the models.
Try a recording over 30 seconds to exercise bounded multi-window recognition.
Full editor/resource gates remain separate from this experiment.

The host JNI smoke procedure runs the production bridge with both model files
and a public speech sample, validates a reference phrase, rejects malformed
audio and checks pre-start/in-flight cancellation. It establishes CPU API
behavior on that host; Android execution and Pixel CPU/GPU measurements are
pending physical-device evidence.

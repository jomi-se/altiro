# Dynamic audio window experiment

Accepted: 2026-10-06.

The operator requests dynamic Whisper windows after Pixel 7 traces show roughly
nine seconds of Small FP16 encoder work even for eleven-second recordings.
The specification's normal 30-second context remains the baseline. The pinned
runtime supports `audio_ctx`, labels it experimental, and warns about quality.
This accepted experiment changes input context length, not model weights.

Enable dynamic sizing in the app by default, with an in-app **Dynamic window
for short recordings (experimental)** switch that restores the full context.
Use actual decoded sample count: for recordings under 30 seconds add one second
of padding, round up to the next five-second window, minimum five seconds and
maximum the model's full context. Recordings of 30 seconds or more retain the
normal runtime path across all windows. Never trim the recording to fit a
shorter window or silently retry with another window after failure.

Freeze the selected mode before recording, including floating dictation and
model/backend comparisons. Add **Compare full and dynamic windows** for the
selected model/backend/attention setting on one recording, with reversible
order, separate results and no insertion payload. Changing the mode must not
merge traces or overwrite results for two passes of the same model/backend.

Diagnostics report requested modes and native-selected transcription context
frames/milliseconds for each pass. Automatic language detection remains the
pinned runtime's full-context pass before `audio_ctx` takes effect; report that
separately. This does not claim GPU placement or eliminate Auto's extra work.
All recording, cancellation, worker isolation, insertion and ephemeral-audio
guards remain in force.

Check boundary sample counts, ordinary dynamic speech with attention on/off,
cancel, Auto behavior and recordings beyond 30 seconds through production JNI.
Phone validation compares text and timings from identical audio, especially
sentence endings and Chilean Spanish. Do not claim quality parity or device
gate completion from host/build checks. Preserve the normal window switch.

Sources: [pinned context override and warning](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/include/whisper.h#L461-L464),
[encoder context and positional slice](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/src/whisper.cpp#L1913-L1955),
[Auto branch](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/src/whisper.cpp#L6351-L6371),
and [context assignment](https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/src/whisper.cpp#L6484-L6489).

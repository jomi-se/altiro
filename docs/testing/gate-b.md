# Gate B: offline speech recognition

Status: unverified on the phone. The model profile is a candidate, not an
accuracy or latency recommendation.

## Install and import

Update the debug APK without uninstalling to preserve accessibility settings.
Open Altiro, download the supported multilingual base model in your browser,
then choose **Import ggml-base.bin** and select it in the system file picker.
Expect 147,951,465 bytes; the app verifies the manifest's SHA-256 before install
and before every native load. Import requires a private copy and temporary
space; the browser's Downloads copy is separate. Unknown or truncated files
must fail without replacing an existing valid model.

The app itself has no Internet permission. After import, enable airplane mode
and disable Wi-Fi as well. Keep the visible recording route for this increment.
Your keyboard stays selected. Stop and focus the intended field before explicit
Insert. Copy is available when an editor is unsupported.

## Recognition and resource cases

- Record 10, 30, and 120 seconds in English, French, and Spanish. Use EN/FR/ES
  for single-language speech and separately evaluate Auto and code-switching.
- Speak past 30 seconds and confirm the whole recording is transcribed.
  Later repeat at the five-minute capture bound; never accept silent truncation.
- Test conversational Chilean Spanish at natural speed, local expressions,
  accented technical names, numbers, negation, and meaningful repetition.
  Preserve the intended wording instead of rewriting it into formal Spanish.
- Include silence, very quiet speech, and ordinary background noise. Exact
  digital-zero audio is rejected as empty; that does not prove ordinary silence
  is safe from hallucination.
- Watch the microphone indicator after Stop: capture must end before processing.
  The notification changes to recognizing offline with Cancel and progress.
- Cancel during model loading and decoding. No transcript may arrive. Model
  initialization may finish before native resources release; another recording
  and model replacement/deletion stay blocked while cancellation finishes.
- Cancel from the background notification, lock the screen, disable the
  accessibility service, and repeat sessions. No stale result may insert.
- Move the cursor, switch apps/fields, and compose a word while recognition
  runs. A changed destination requires explicit Insert or Copy.
- Delete/reimport a model when idle; restart after an interrupted import or
  capture. No partial model is loaded and no recording/session is replayed.

## Evidence

Record app/runtime/model revision, phone/API/build, language/decode configuration,
duration, cold-load and decode timing, stop-to-text latency, memory, thermal
behavior, and cancellation response. The first implementation cold-loads each
session; no warm-model benchmark can be claimed yet. Keep audio, transcripts,
screenshots, and raw device logs outside Git. No personal samples are published
without a separate explicit request.

The [Chilean candidate research](../research/chilean-spanish.md) defines the
comparison needed before adding another model profile. A small fine-tune's
self-reported WER is not evidence that it outperforms stock Whisper on these
recordings.

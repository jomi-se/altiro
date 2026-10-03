# Compare Small models on a phone

This is an experiment, not a published Pixel 7 performance claim.

## Install once

Update Altiro without uninstalling. Its existing Base file stays available.
Download and import the separate files, selecting the matching model row first:

| In-app choice | Filename | Approximate private storage |
| --- | --- | --- |
| Small Q8 | `ggml-small-q8_0.bin` | 264 MB |
| Small FP16 | `ggml-small.bin` | 488 MB |
| Chilean Small Q8 (experimental) | `ggml-small-es-cl-2-q8_0.bin` | 264 MB |
| Chilean Small FP16 (experimental) | `ggml-small-es-cl-2-f16.bin` | 488 MB |

Stock files have pinned browser download buttons. Converted Chilean files come
from the same installation page as the APK or the
[preparation command](../development.md); the app links to their source card.
Each import checks the selected profile's exact bytes/SHA-256. The filename
alone cannot establish identity. A wrong, truncated, cancelled, or corrupted
import must preserve every already installed profile. All four Small files use
approximately 1.5 GB in app storage; browser copies are additional. Native
working memory is separate, and only one model is resident at a time.

## Same recording

1. Set **ES** for Spanish. Keep the language the same for every compared model.
2. Under **Compare one recording**, optionally check either Chilean variant.
3. Tap **Record a comparison**, then **Start recording**, speak naturally for
   10–20 seconds, and tap **Stop and transcribe**.
4. Compare the result cards. Each has a separate Copy button and elapsed time.
   Comparison results are not passed to the floating Insert control.
5. Use **Clear comparison** or start another recording. Results otherwise expire
   after ten minutes and disappear when the app process closes.

The measurement includes file verification, model initialization, and inference
for each model; it excludes capture and queued work. Stock Q8 runs first, stock
FP16 second, followed by checked Chilean models. Repeat after the phone cools,
with similar background load. These elapsed times cannot establish isolated
quantized-kernel speed, battery consumption, peak memory, or statistical accuracy.

## Everyday switching and failure cases

Choose any installed model under **Speech model**. The next floating-mic session
uses it, and the choice persists after restart. The model and language are fixed
at recording start. Controls stay unavailable until capture/native work/import
finishes. Selecting an uninstalled profile explains the required import and
blocks ordinary recording rather than silently changing models.

Cancel during the first model and between models: no later model or stale result
may publish, the microphone must be released, and audio must be removed after
native work returns. Repeat after cancellation. Lock, disable accessibility,
and move the editor/cursor as in [Gate B](gate-b.md). Delete one model when idle
and confirm other installed models still work after process restart.

Use held-out natural Chilean speech with names, numbers, negation, local terms,
and Spanish/English switching. Inspect meaning errors as well as wording. The
fine-tune's training-family score and public English compatibility smoke are
not evidence that it wins these tests. Keep recordings/results outside Git.

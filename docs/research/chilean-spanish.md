# Chilean Spanish recognition candidates

Reviewed: 2026-10-03. Chilean conversational speech is an explicit quality target.
No candidate has been benchmarked on the reference phone. ES-CL-2 is now an
explicit experimental app profile; it is not a proven accuracy improvement.

## A specialized Whisper candidate

[rcastrovexler/whisper-small-es-cl-2](https://huggingface.co/rcastrovexler/whisper-small-es-cl-2)
declares Apache-2.0 and fine-tuning of Whisper small on OpenSLR Chilean Spanish.
Its author reports evaluation WER 4.8044. The card leaves training/evaluation
data detail and limitations incomplete, so this number is not an independently
verified comparison with stock Whisper or conversational dictation.

Inspected revision: `57e689bd5edc1ae84e7c8683235178a3c4505cea`.
The repository supplies `model.safetensors`, not a ready ggml Android artifact.
It is a possible second model for the existing Whisper runtime, rather than
requiring another recognition architecture. The pinned upstream converter now produces allowlisted FP16 and Q8 artifacts.
Every FP16 tensor was audited against its source at the converter's precision,
and both artifacts transcribed the public upstream speech sample on the host.
This establishes compatibility, not Chilean quality. Retain source metadata and audit provenance before
redistributing a converted artifact.

The same author's
[original profile](https://huggingface.co/rcastrovexler/whisper-small-es-cl)
and [third profile](https://huggingface.co/rcastrovexler/whisper-small-es-cl-3-colab)
also exist. Do not select one by its self-reported score alone.

## Published comparison of the earlier profile

The [Chilean ASR leaderboard's CSV](https://huggingface.co/spaces/idsudd/open_asr_leaderboard_cl/blob/7dfb852bee82c9b602548abb33213449c360e31b/results.csv)
at revision `7dfb852bee82c9b602548abb33213449c360e31b` reports:

| Model | Google Chilean, 4,374 samples | Datarisas, 50 samples | Common Voice, 152 samples |
| --- | --- | --- | --- |
| Stock Whisper small | 7.99 WER | 30.80 WER | 10.34 WER |
| Original Whisper small ES-CL | 2.37 WER | 30.13 WER | 13.40 WER |
| Stock Whisper large-v3-turbo | 2.86 WER | 17.07 WER | 4.94 WER |

These are the publisher's results, not independently reproduced here. They
evaluate the original ES-CL profile, not `-2`. The specialized model's strongest
score is on the same dataset family used in training; overlap needs auditing.
Its other-domain results give little basis for promising improved casual
speech. The larger turbo model is a useful quality comparator; these runtime
figures and accuracy do not establish Android feasibility or phone latency.

## Evaluation limits and next experiment

[OpenSLR SLR71](https://www.openslr.org/71/) contains Chilean Spanish sentences
recorded by volunteers and manually checked, under CC BY-SA 4.0. Its training
use by a candidate makes it unsuitable as the sole independent test set. Read
speech is also a different task from quick informal dictation.

Compare stock multilingual base, stock small, and the Chilean small candidate
on identical held-out recordings. Use Spanish (`es`) and transcription, with
the same decode settings. Include natural fast speech, local expressions,
negation, numbers, names, quiet speech, silence, and Spanish/English switching.
Measure word errors and meaning changes alongside phone latency, memory, and
cancellation. Include speakers and utterances outside fine-tuning data.

For release qualification and changes to the conversion: use the pinned upstream conversion tooling, verify
converted output against Transformers on fixed samples, evaluate any
quantization independently, obtain actual byte size/SHA-256, retain notices,
and add a separate allowlisted model manifest. Do not substitute this model
silently for the multilingual default. See the [accepted experiment](../decisions/0003-small-model-comparison.md) and
[phone comparison procedure](../testing/model-comparison.md). No GPU training or compute account work
has been initiated.

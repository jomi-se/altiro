# Network adapters

Pure-JVM code for the only network use Altiro allows today: explicit, verified
model acquisition (see `docs/decisions/0008-explicit-model-acquisition.md`).
Recording, recognition and insertion never call this module.

`ModelSourcePolicy` accepts only immutable Hugging Face
`resolve/<commit>/<filename>` URLs and redirects to HTTPS `huggingface.co` or
`*.hf.co` hosts. `ModelDownloader` streams one HTTPS hop at a time through
`VerifiedModel.install`, so the exact byte count and SHA-256 decide whether the
file is installed atomically. Cancellation closes the live connection; failures
are reported as sanitized `DownloadFailure` categories without URLs or headers.

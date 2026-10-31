# Recognition API

This is the project's bounded OpenAI-shaped subset, not the entire OpenAI API.
`GET /health` returns 200 with `ready: true` only after verified model load and
GPU warm-up. Loading/error returns 503. It reports `busy`, model/device and limits
without recordings, transcripts or editor identifiers.

`POST /v1/audio/transcriptions` uses multipart form fields:

| Field | Contract |
| --- | --- |
| `file` | Audio; prefer mono PCM16/16 kHz WAV; maximum 16 MiB and five minutes |
| `model` | `large-v3-turbo`; `whisper-1` accepted as an alias |
| `language` | `en`, `fr`, `es`; omitted or `auto` detects language |
| `prompt` | Optional vocabulary hints, at most 4 KiB UTF-8 |
| `response_format` | `json` (default), `text`, or `verbose_json` (same JSON payload) |
| `temperature` | Only `0` is supported |

Successful JSON includes `text`, `raw_text`, detected `language`, `duration`,
`processing_seconds`, `model`, `device: "cuda"` and `cleanup_applied: false`.
An empty string is valid no-speech output. No translation, word timestamps,
streaming, durable jobs or cleanup endpoint is implemented.

Errors include 400 invalid audio/options, 413 excessive input, 422 malformed
forms, 429 busy, and 503 unavailable/failed GPU. Requests are not queued and
there are no automatic retries. Cancellation does not promise immediate native
GPU interruption; the slot remains owned until its worker actually finishes.
The supervisor can restart a crashed worker. A hard hung-job watchdog is not
implemented. Temporary uploads are closed on ordinary request completion/errors.

The default deployment relies on private tailnet access. Optional
`ALTIRO_API_KEY` requires a matching `Authorization: Bearer ...` header on `/v1/`
routes. Supply it through private operator configuration, never Git. The Linux
unit accepts an optional root-readable `/etc/altiro-server.env` environment file.
The browser demo targets the default tailnet-only mode and does not currently
collect bearer credentials. Do not turn this profile into public internet hosting
without a separate deployment review.

Android integration must remain explicit and independently tested. It must not
silently replay a failed audio POST or bypass destination/cancellation guards.
Follow the [canonical specification](../docs/plan/android-dictation/spec.md).

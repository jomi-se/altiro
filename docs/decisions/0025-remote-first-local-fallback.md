# 0025 · Prefer the configured speech server, retain local fallback

Accepted: 2026-11-01. The maintainer explicitly requested Android integration
with their self-hosted GPU server, automatic local recognition when unavailable,
green remote/blue local bubble states, and a local merge into main after checks.

## Behavior and consent

Without a configured endpoint, ordinary dictation remains local and makes no
recognition network requests. Saving an endpoint follows a short disclosure
that recorded audio and configured vocabulary hints go to that server. Remote
first defaults on for this configured mode; a switch selects local only without
forgetting the endpoint. No endpoint, account, credential or machine identity is
bundled. The selected local model is the fallback; installing it is recommended,
not a prerequisite for remote-only dictation.

This is a specific adapter for the separately shipped large-v3-turbo server.
Accept its HTTPS origin or full `/v1/audio/transcriptions` URL and normalize to
that fixed contract. Derive `/health` on the same origin. Reject credentials in
URLs, query strings, fragments, alternate paths and cleartext. Use platform TLS
trust, no redirects, one streaming audio POST, and bounded bodies and deadlines.
The initial settings target the private tailnet deployment without application
bearer credentials; authenticated deployments need additional credential UI.

Probe readiness while recording after an explicit microphone tap; a settings
Test button also probes without uploading audio. No idle polling or startup
probe is added. Freeze endpoint, language, hints, model and processor for the
recording. Ready requires the expected CUDA/model identity and a free server.
The readiness check has a 1.5-second overall deadline; the transcription request
has a 45-second deadline and never automatically replays its POST.

On unavailable/busy/loading server, transport/protocol/configuration failure or
request failure, run the same recording with the selected installed local model.
Show the route switch and fixed diagnostic reason. Never send it to another
remote provider. A successful empty transcript is no speech, not a retry trigger.
Cancellation or a superseded session never starts fallback or inserts a result.
If no local model is installed, explain how to install one. Audio stays temporary
and is deleted after the remote result, local handoff completion or cancellation.

## Session and interface

The existing session reducer owns the single result and insertion attempt.
Remote and local results share its destination validation and recovery controls.
Comparison/Encoder lab modes remain local-only even when remote first is enabled.
TranscribePsy fallback retains automatic language; the server honors the frozen
user language selection. Whisper-only runtime controls remain local settings.

Keep the existing translucent capsule and large recording target. Green with a
server glyph means the remote GPU route; blue with a phone glyph means local;
neutral with a clock glyph means checking. Include route text/accessibility state,
not color alone. Local CPU versus Vulkan remains a separate setting. Route,
readiness time, remote request time and fixed failure codes enter the content-free
console/checkpoint; endpoint identities, audio, hints and transcript never do.

## Superseded boundaries and evidence

This decision explicitly replaces the earlier manual-local-retry preference in
specification section 12.1 and decision 0014's Android follow-up, only for the
maintainer-authorized configured-server-to-local route. Automatic remote POST
replay, cross-provider failover and automatic insertion retries remain forbidden.

JVM tests cover endpoint parsing, request/response bounds, strict Unicode JSON,
timeouts/cancellation, one POST and local-fallback decisions. Android compilation
does not establish phone networking, GPU recognition quality or overlay appearance.
Use the [phone procedure](../testing/remote-first.md) for those gates.

The subsequent host wire tests exercise real loopback HTTPS with an ephemeral,
per-connection test trust store, including default-trust and hostname rejection.
They verify streamed multipart bytes and cancellation/replay boundaries using
scripted server responses; they do not establish real GPU or phone execution.

For live integration verification, the maintainer then supplied a recording so
testing need not wait for an app-side run. Its canonical WAV conversion passed
through the compiled production client to the actual private CUDA endpoint.
Speech, valid empty silence output and the unavailable-endpoint routing decision
were verified. This closes the server-integration check; broader physical-device
and rendered-interface gates are unchanged. No endpoint identity, recording or
transcript enters this repository.

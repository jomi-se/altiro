# 0014: Keep an optional self-hosted speech server in the repository

Status: accepted by the maintainer; reference server verified, Android transport pending.

Date: 2026-10-31.

## Decision

The maintainer requested that the tested home speech server and its repeatable
setup live alongside the Android project. Add `server/` as a separate Python/WSL2
component. Preserve the existing Gradle module layout. Android remains the only
dictation application target; this service is an optional endpoint under
specification section 12.3, not a mandatory hosted backend or desktop dictation app.

Portable setup code, dependency locks, model download provenance, API tests,
browser test client and isolation policy belong in this repository. Actual
machine configuration, downloaded runtimes/models/VM disks, credentials, raw
benchmarks and recordings stay ignored and operator-local. No endpoint identity
or private machine path is published.

## Implementation and evidence

The reference uses faster-whisper 1.2.1, CTranslate2 4.8.4 and a pinned converted
Whisper large-v3-turbo model. A Windows host forwards tailnet HTTPS to a loopback
proxy in a dedicated Ubuntu 24.04 WSL2 environment. The unprivileged worker has
AF_UNIX sockets only; the proxy uses a separate identity. Code/model/system
storage is read-only; host integration and management sockets are hidden; host
mounting and privilege escalation are denied. The GPU/Windows driver remains
shared and is an explicit boundary limit.

Eighteen containment checks passed in the worker's effective policy, including
GPU/model access, denied IP sockets and host paths, and read-only storage.
Real GPU speech, silence, invalid-audio recovery and a VM restart passed.
A public 11-second English sample took about 0.8–0.9 seconds once warm on a
Pascal-class 8 GiB NVIDIA laptop GPU. These are smoke results, not controlled
benchmarks, phone-network latency, multilingual accuracy evidence or a security audit.

WSL left its PE executable handler registered despite disabled interop; a
root-owned boot guard unregisters it before starting the service. A private
read-only filesystem view hides systemd management sockets because masking the
entire directory with InaccessiblePaths failed namespace creation on this host.
The effective restrictions were rechecked after restart.

The reference accepts one request at a time and rejects concurrent work with 429;
uploads are bounded to five minutes and 16 MiB. No audio/transcript history is
retained. Optional bearer authentication supplements private-network access.
The selected deployment uses existing tailnet access rules and no application
token; it is not a public internet hosting profile. A hard per-job native-runtime
watchdog is not implemented; a hung driver can require service restart. Do not
claim every section 12.3 deployment-hardening item complete.

## Android boundary

No Android source, capture, insertion or permission behavior changes here.
Remote recognition still requires explicit endpoint consent/configuration and
its own adapter/contract tests. Preserve raw transcripts, reject stale results,
never automatically replay audio POSTs and offer explicit local retry on failure.
Availability probing and any future preference policy need their own accepted
Android design. Cleanup remains independently opt-in and is not installed.

## Follow-up

The maintainer subsequently authorized the Android integration and automatic
local fallback in [decision 0025](0025-remote-first-local-fallback.md). Its explicit
configuration/consent and phone gates supersede the manual-local-retry preference
above; the server still never retries requests or selects another provider.

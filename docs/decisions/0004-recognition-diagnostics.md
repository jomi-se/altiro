# 0004 — Content-free recognition phase diagnostics

Status: accepted. Date: 2026-10-06.

## Context

The operator reports roughly 40 seconds or more for dictation on the reference
phone and unclear loading feedback. This is user feedback, not a measured
benchmark. Source confirms each run verifies the model, cold-loads one context,
recognizes speech and frees it. Only explicit comparison runs multiple models;
additional installed models do not add recognition work to ordinary dictation.
App process startup independently hashes all installed models before allowing
recording; expose that pass separately, with per-model verification time/result
and total readiness time. This startup report stays only until process exit.

## Decision

Add a latest-session process-memory diagnostics screen, reachable from the main
and recording/results screens. Use monotonic phase timings for microphone
startup, recording, finalization, runtime startup, worker waiting, verification,
audio reading, cold loading, inference, text assembly, model release, audio
deletion and worker completion. Each comparison model gets separate phase rows.
Refresh active phase durations each second and preserve failed/cancelled traces.

The overlay and notification name the actual phase. Only inference shows
upstream percentage; other phases are indeterminate. Percentages do not estimate
remaining time. Offer explicit Copy and Android Share of app/OS versions,
permission state, catalog model IDs, language, audio duration, phase timings,
outcome, failure phase and pinned decode settings. Exclude dictated text, audio,
editor/app identities, paths and exception messages. No disk history, logcat
transcription, telemetry or new permissions. Replace on a new recording, expire
ten minutes after completion, and clear on process exit. Transcript discard
does not discard the trace, so automatically inserted results stay diagnosable.

Keep cold loading and four-thread CPU greedy recognition unchanged. Phone traces
determine whether to prioritize residency, verification caching or inference
optimization. Warm residency still needs a timeout, memory-pressure unloading
and cancellation/memory evidence. No latency improvement is claimed here.

## Measurement limits

Timings include scheduling delays. Inference aggregates features, optional Auto
language detection, encoder and decoder. Exact digital-zero audio skips loading.
Failed phases may be partial; startup/finalization are coordinator-observed.
Capture-only cancellation ends the trace at the request; native cancellation
measures through actual context/audio cleanup. Traces measure recognition,
not editor delivery. Full physical Gate A/B remain open.

See [phone diagnostics](../testing/recognition-diagnostics.md).

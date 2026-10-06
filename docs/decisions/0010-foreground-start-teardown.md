# 0010: Acknowledge foreground startup before abandoning capture

Status: accepted. Date: 2026-10-06.

## Context and evidence

A rapid Stop/Cancel, changed session, or microphone denial can reach service
teardown before `startForeground()` succeeds. Android's
[service implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/ActiveServices.java)
can crash the process when a foreground-started service is brought down with
its foreground obligation outstanding. Catching microphone denial and calling
`stopSelf()` alone is therefore insufficient recovery. This is a verified
platform constraint; its reproduction on the reference phone is still pending.

## Decision

Normal starts still promote the microphone foreground service and obey its
permission and while-in-use requirements. Only an abandoned/refused start uses
a brief non-microphone notification to acknowledge startup before stopping.
On Android 14 and newer this uses
[shortService](https://developer.android.com/develop/background-work/services/fgs/service-types#short-service),
which has no microphone prerequisite. Android 13 uses the already declared
dataSync type for teardown. Neither path creates an AudioRecord, launches
recognition, claims successful microphone capture, or retries microphone access.

Cancel during STARTING invalidates the session first, then delivers a Cancel
command to the pending service rather than destroying an unacknowledged start.
A stale START acknowledges and stops itself. Service destruction cancels the
current session directly, without issuing another start/stop service command.
Stop remains distinct from Cancel, including Stop before the first audio frame.
Capture also checks session ownership on each elapsed-second update and stops
itself when its session is gone. Failure is recorded before teardown promotion,
so a restart can inspect the content-free failed checkpoint.

## Consequences and validation

The manifest adds shortService; no new runtime permission or hidden API is
introduced. The visible recording-screen fallback remains necessary whenever
Android refuses microphone access. Foreground-start refusal, immediate Stop,
immediate Cancel, and parent survival need framework instrumentation and
physical-device checks. These fixes do not pass Gate A or change recording
in place from a bounded preview into a universal compatibility claim.

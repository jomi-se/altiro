# Explicit in-app model acquisition

Status: accepted. Date: 2026-10-06.

## Context

The owner requests one-tap installation of the app's essential recognizer,
including an experimental Chilean fine-tune. The preview currently delegates
download to a browser and imports a file. The earlier absence of Internet
permission describes that preview, not a permanent requirement to keep model
acquisition outside Altiro.

## Decision

Allow Internet permission for explicit model acquisition. Recording, local
recognition and editor insertion still make no network calls. No accounts,
telemetry, automatic downloads, remote recognition or cleanup are enabled by
this decision. Show the download size and host before the user starts it.

Download only immutable catalog entries over HTTPS. Follow a bounded number
of redirects to reviewed distribution hosts, without credentials. Check exact
byte count and SHA-256 and install atomically using the existing verifier.
Cancellation, corruption, offline failure, low storage and process interruption
must preserve previously installed models and remove abandoned partial files.
File import remains available for offline transfer.

Stock converted models have pinned direct sources. Chilean conversions require
reviewed distribution artifacts before their download action can be enabled;
their original training-model URL is attribution, not a binary download.

## Consequences and evidence

The APK may access the network, so old “no Internet permission” claims must
change when this ships. Disclose acquisition separately from transcription.
Test the transport and verifier's failure paths and confirm recognition with
airplane mode on a phone. Publishing converted artifacts is a separate release
action; this decision does not assert that distribution is already configured.

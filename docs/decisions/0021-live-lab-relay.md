# Opt-in live Encoder lab results

Accepted: 2026-10-20. The maintainer requested direct Tailnet delivery so the
investigation can respond to phone results without repeated chat attachments.

Debug builds expose **Live results** inside Encoder lab. The maintainer pastes
a temporary receiver pairing link and explicitly connects. Completed-case and
suite-final snapshots use the existing content-free report; no audio, tensor
values, transcript, editor identity or remote commands are added. Ordinary
recognition does not use the relay. Pairing and token stay in process memory;
restarting the app requires reconnecting. Clear diagnostics disconnects it.
An in-flight request may complete after Disconnect, but no new upload starts.

The client permits only direct numeric IPv4 addresses in the Tailnet range,
the report route and a high port. It bypasses proxies, refuses redirects, and
uses short timeouts. HTTP is allowed in the debug manifest for this explicitly
paired development route; Tailscale supplies transport encryption. The release
manifest continues to forbid cleartext traffic and omits the relay controls.
This is diagnostic transfer, not remote inference or a mandatory backend.

A temporary receiver uses a fresh bearer token, accepts bounded schema-checked
lab snapshots, atomically stores the latest report and receipt outside Git,
and runs the existing analyzer. A response acknowledges the exact payload's
SHA-256 after saving. The receiver never executes report content or serves a
directory. It can optionally serve one explicitly selected APK. The browser
page permits manual file upload as recovery and shows the latest analysis.

Uploads are conflated and limited to case boundaries, on an IO coroutine.
They can still overlap the next measurement and affect scheduling, battery,
temperature or clocks. A sticky `live_relay_enabled` report flag records this;
timing acceptance requires a follow-up run with the relay off. Network failure
never cancels a benchmark or discards its local report. Later checkpoints
supersede failed uploads; an unreceived final report requires Connect or Share
lab again. No durable background retry or remote phone control is introduced.

Receiving a report does not wake an inactive coding-agent session. The receiver
continues saving and analyzing independently, while an active investigator can
watch receipts and inspect the latest snapshot. Hardware acceptance of the
Android transport remains separate from host transport tests.

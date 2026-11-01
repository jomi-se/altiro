# Remote-first dictation

Use 0.7.0-remote-first or later. Keep the existing keyboard and accessibility
setup. In **Settings → Your speech server**, enter the private HTTPS server
origin or its full transcription URL, then **Save** and **Test**. For example,
`https://speech.example.net` normalizes to its documented transcription route.
The app uses the phone's existing VPN connection; it does not configure Tailscale.
The current settings support the server's private tailnet-only authentication
profile. No personal endpoint is included in the app.

Remote first is enabled for a newly configured server. Select/install a model
in Models for offline fallback. The app also permits remote-only recording;
if the server fails and no model is installed, it shows a setup recovery message.

Check on the phone:

1. Test with the server ready: green server indicator and GPU-ready feedback.
   Record from Home and an external editor. Green indicates the remote route;
   after Stop, the result uses the existing automatic insertion/recovery path.
2. Make the server unavailable or disable the phone's VPN: the next recording
   checks while recording, turns blue and uses the selected local model. Console
   shows the remote fallback reason and local phases. No second remote POST occurs.
3. Turn **Remote first** off: blue local route, no recognition health/upload
   requests, even while a server endpoint remains saved.
4. Cancel during checking, uploading and recognition. No result or fallback may
   arrive afterward. Start another recording immediately; a stale completion
   must not stop its service, change its destination or insert stale text.
5. Move/change the destination while recognition runs: retain text with Copy
   recovery rather than inserting into the changed editor.
6. Record silence: a valid empty remote result must not start local recognition.
7. Select Spanish/French and use vocabulary hints with the server. Select
   TranscribePsy as fallback, make the server unavailable, and confirm fallback
   uses Auto rather than pretending to support a fixed local language.
8. With remote first enabled, comparisons and Encoder lab must still use only
   the explicit local models. Inspect light/dark bubble badges, large text and
   TalkBack route descriptions; language and mic hit targets remain separate.
9. Restart Altiro after a successful remote recording: no false interrupted-run
   warning. Exported diagnostics must contain route/timing/failure codes, with
   no server address, transcript, vocabulary terms or audio.

Cold/loading/busy servers are unavailable for that attempt; no waiting room or
remote job retry is added. Keep the server model resident for its warm latency.
Measure whole time after Stop separately from GPU processing reported by the
server. Mock/JVM tests do not prove these phone gates.

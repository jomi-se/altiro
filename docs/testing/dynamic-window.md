# Dynamic windows on the phone

Version 0.4.2 enables **Dynamic window for short recordings (experimental)**
inside Altiro. Existing model files stay available; no new download is needed.
For recordings under 30 seconds, the runtime adds one second of padding and
rounds up to five-second windows, minimum five seconds, capped at 30 seconds.
A recording of 30 seconds or more keeps the normal full-window processing.
All recorded samples remain available; no audio is trimmed to fit the window.

1. Select an installed Small FP16 model, Vulkan and a fixed EN/ES language.
   Keep the attention setting the same for both passes.
2. Tap **Compare full and dynamic windows** and record 10–12 seconds, including
   a complete sentence ending. Stop and keep the recording screen open.
3. Compare both transcripts, particularly final words, negation, names and
   numbers. Each card labels its window mode and has its own Copy button.
   Comparison never inserts automatically; audio is deleted after both passes
   finish, or after cancellation cleanup.
4. Copy diagnostics. Expect requested FULL/DYNAMIC modes, separate phase and
   runtime records, and native-selected contexts of 30 seconds / 15 seconds for
   this recording. Use per-pass inference and encoder timings; total after-Stop
   time includes both passes. Compare text quality as well as speed.
5. **Run dynamic window first** reverses order when another comparison is useful.
   Temperatures, clocks and decoder-call differences can still affect timings.
6. Try ordinary dictation and Cancel. Turning the dynamic switch off restores
   the normal window. There is no automatic alternate-window retry.
7. Test a recording longer than 30 seconds: dynamic mode must report a full
   context, process later windows and retain the ending. Check Chilean Spanish
   separately; success on a public English sample is not that quality evidence.

Auto detection still performs a separate full-context encoder pass before the
transcription override takes effect. Diagnostics label that window explicitly;
fixed EN/ES avoids it. Some recordings near 30 seconds round up to a full window
even though dynamic mode was requested. Requested mode is not the selected size.

If recognition fails, copy diagnostics or the saved content-free checkpoint
before starting again. Restore the normal window explicitly for recovery.
Driver/device failures remain isolated through the existing worker path. The
[GPU failure and lock procedure](gpu-experiment.md) and existing device gates
still apply. Host/build checks do not establish phone speed or quality parity.
See [decision 0007](../decisions/0007-dynamic-audio-window.md).

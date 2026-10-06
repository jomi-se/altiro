# Recognition phase timing on a phone

Update in place to preserve installed models. Open **Recognition diagnostics**
near the top of Altiro, or **View phase timings** in the recording/results screen.
Both ordinary floating dictation and comparison are traced.
The separate **App startup checks** section times verification of every installed
file on process start. **Copy startup diagnostics** is available before any
recording; ordinary Copy/Share reports include it too. These checks do not load
native model contexts and are separate from the post-Stop total.

1. Choose one installed model and ES for Spanish. Record about ten seconds,
   Stop, and let recognition finish. Use ordinary dictation first; comparison
   adds every checked model's processing time.
2. Open diagnostics and tap **Copy diagnostics** or **Share diagnostics**.
   Reports contain no spoken text/audio. A single content-free checkpoint
   survives restart until cleared or replaced by the next recording; the live
   trace expires after ten minutes. Export before starting a new recording.
3. Repeat with the same model/duration, then another installed model. Keep
   language constant. The report names every model actually run.
4. Compare **Checking model file**, **Loading model into memory**,
   **Recognizing speech** and **Releasing model memory**. Long verification or
   loading suggests startup work; long inference suggests recognition compute.
   Processing/audio ratio measures elapsed time per second of audio, not accuracy.
5. Observe live phases in the screen, overlay and notification. Cancel during
   loading and recognition. Synchronous native loading can finish before
   cancellation takes effect; trace cleanup must end CANCELLED without insertion.

All runs currently cold-load. Faster repeats may reflect OS file caching, not a
resident model. Installed models occupy disk; one model context is live at a time.
Comparison is sequential. Run order, temperature, background work and Android
scheduling can bias results. Record conditions separately without private
dictation or device identifiers in Git.

An opt-in Vulkan backend adds GPU discovery/initialization and total Whisper
compute counters. See [CPU/GPU testing](gpu-experiment.md). A stalled native
cancellation terminates the worker after ten seconds and waits for confirmed
disconnection before deleting audio. Visible recording/diagnostics screens
stay awake during work; manual lock still cancels.

Inference includes features, Auto language detection when selected, encoder and
decoder. Startup/finalization include coordinator scheduling. The timing trace
survives transcript discard. Capture-only cancellation measures up to its
request; native cancellation measures actual cleanup. Host/build checks do not
prove phone timing or UI behavior. Full Gate A/B remain open.

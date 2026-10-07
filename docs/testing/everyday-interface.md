# Everyday interface: physical-device checks

Target: the 0.5.2 native interface, especially the real accessibility overlay in
other apps. Build checks and concept approval do not pass this matrix.

1. Install as an update with the same signing identity. Existing verified models,
   selection, language and processor preferences must survive. From a fresh
   install, Home must make microphone/accessibility/model setup easy to find.
2. Explicitly download a stock model. Check progress and Cancel during transfer;
   lose connectivity and retry. A failed/cancelled transfer must leave an existing
   model intact. Relaunch after killing an unfinished download; partial files
   must be swept. Confirm no launch or recording initiates a download.
3. Select an experimental Chilean profile. It must explain import-only status,
   accept only its exact converted file, and never fetch a training checkpoint.
   After a verified install, transcribe in airplane mode.
4. In a separate real editor, leave the normal keyboard selected. Confirm the
   small translucent overlay is legible over white, dark and busy host content.
   Check light/dark system themes and 1.3x font scale. It must not steal focus,
   intercept outside touches or obscure the active cursor unnecessarily.
5. Tap language once: EN/ES must change in place. Start recording; language must
   visibly freeze. Stop must transcribe; the separate Cancel must discard.
   Recognizing must look active without an invented percent or countdown.
6. Drag from mic, language and Cancel targets. Neither starting nor stopping
   recording may occur. A cancelled gesture must reset. An ordinary tap must
   not snap the bubble. Rotate and open/close the IME: position stays reachable.
7. Record with an unchanged eligible destination. Confirm one automatic insertion
   attempt; Console should show owned overlay events without editor/window IDs.
   Immediately record again without Discard. No previous text may be retried.
   Repeat with a moved cursor, edited text, another field and composition: the
   original destination must not regain authority when restored.
8. Change to a password/blocked field while working. Stop/Cancel remain available
   without exposing transcript content; insertion stays blocked. Manual power
   lock cancels. Use a short screen timeout and leave the phone untouched during
   inference: the busy overlay should keep it awake, then release the screen-on
   flag when idle. This Android behavior is a device gate, not assumed.
9. Disable animations in system settings. Work must remain readable, Cancel
   available, and no continuous animation continue after completion/detach.
10. Open Console during work; Copy/Share/Clear must operate with content-free
    reports. Clear is disabled while work owns its trace. Run a comparison from
    Runtime & comparisons: all passes and transcripts remain available, with no
    automatic insertion. Reopen after a worker crash and inspect the checkpoint.
11. Cancel or Stop immediately after tapping the mic, then start a new session.
    The app must survive and recover without capturing unintended audio. Also
    test denied microphone access using the [startup procedure](service-startup.md).
    Notification Cancel must discard during both capture and recognition.
12. Record through 4:30: Home, overlay and notification must warn before the
    5:00 automatic Stop. The overlay announces the warning once rather than
    reading every countdown tick. Killing unfinished work must show the Home
    interruption notice on restart; Dismiss preserves its Console evidence.

Use [Gate A](gate-a.md) for the full editor/resource matrix and
[Gate B](gate-b.md) for language quality, long audio and cancellation, plus
[vocabulary checks](vocabulary.md) for optional names and terms. Report
version, OS, device and exact step; export the Console trace without private
speech. Native screenshots for review should show idle/recording/recognizing
across a light and dark editor, Home, Models, Setup and Console. Capture only
synthetic/empty editors; keep images and execution logs outside Git.

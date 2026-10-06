# Local vocabulary checks

Target: 0.5.2. Host verification passes: 35 core and 8 acquisition tests, production
JNI speech/silence/cancellation/Unicode checks, Android assembly, lint and native
packaging. Instrumentation APKs compile; device tests were not executed. The
following physical-device checks remain pending.

1. In Setup, open Names & terms and enter a short list, one term per line. Include
   accents and technical spelling. Save, reopen, and restart the app: the saved
   list must persist. The default is empty, with no contact or clipboard import.
2. Expand the editor with the keyboard open, at 1.3x font scale and in both system
   themes. The field and Clear/Cancel/Save must remain reachable. Cancel preserves
   saved words; Clear changes the draft and Save commits its empty list.
3. Check 100 versus 101 distinct terms and ASCII versus accented text near the
   4 KiB UTF-8 prompt limit. Invalid input must remain editable without replacing
   the saved list. Repeated lines collapse; names' case and accents remain intact.
4. Start a recording and a comparison. Editing is disabled during capture,
   inference and cancellation cleanup. Every pass uses the start-time list;
   later settings must not alter an active run. Console shows the configured
   count without any words, including in Copy/Share and the restart checkpoint.
5. Compare real technical English and Chilean Spanish with hints empty and enabled.
   Evaluate names, numbers, negation and unrelated words. Hints are recognition
   context, not forced replacement; a quality improvement is not assumed.
6. Record exact silence, real near-silence and background noise. The result must
   not dump the list. Exercise Stop, Cancel and a recording over 30 seconds;
   cancellation and all-audio preservation remain required. Upstream may omit
   hints for a final window shorter than five seconds.

Use short lists: the pinned decoder retains only the last 223 prompt tokens,
which can discard early entries in a longer list. The 100-term/4 KiB storage
limits are not a promise that every entry reaches every decoding window.
Host silence tests cover digital zero; real phone noise remains a separate gate.

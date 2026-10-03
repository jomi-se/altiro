# Gate A: physical-phone integration experiment

Status: unverified. Building an APK or running JVM tests does not pass this gate.

## Preparation

Install Altiro and Altiro editors debug APKs. Record app revision, phone model,
Android API/build, target SDK, keyboard version, and permission state in external
evidence. Keep recordings, editor contents, device serials, and private app
identifiers out of Git.

In Altiro, read the disclosure and enable its accessibility service through
system settings. Grant microphone permission; allow recording notifications
when possible. Follow the phone's visible restricted-setting confirmation if
sideloading requires it. Keep Gboard selected.

## Visible-screen route (default)

1. Focus a fixture editor and type a word so selection/composition callbacks
   arrive. Tap floating Mic to open the recording screen.
2. Tap Start on the visible screen. Confirm Starting becomes Recording only
   after capture begins and Android shows the microphone indicator.
3. Return to the fixture. Stop using the bubble or notification; capture must
   end before preparing the test phrase.
4. The fixed result is ready. Because the recording screen changed focus,
   choose the destination and tap explicit Insert.
5. Repeat collapsed-cursor and selection replacement in stock/Compose fields,
   the second field, the length filter, WebView, and password variants.

Check the actual editor after dispatch. No automatic retry is allowed.
Transformed/truncated input is still an insertion attempt. Unknown composition
or ambiguous identity uses Copy instead of guessing.

## Direct-start experiment (debug only)

Enable the labeled direct-start device experiment in Altiro. Focus an editor
and tap Mic. This attempts microphone foreground-service startup from the
accessibility service. Record the result; general FGS exemptions alone do not
prove microphone permission while backgrounded.

On success, Stop without changing editor state and check one automatic
insertion attempt. On refusal, disable the experiment and use the visible
screen. The direct route remains experimental until hardware evidence passes.

## Failure and lifecycle cases

- Move the cursor away/back, switch editor/app, or replace same-length text
  while processing. Original authority must stay invalid.
- Keep a Gboard composing word active: insertion is blocked until the word is
  finished and Insert is explicitly requested, or Copy is used.
- Start twice or Cancel while starting/recording/processing: no queued session
  or later insertion.
- Stop/Cancel through notifications in the background: confirm actual capture ends.
- Lock/screen-off, revoke microphone access, or disable service: capture ends.
- Deny notifications independently of microphone permission: no crash.
- Run to five minutes: the disclosed resource limit Stops and returns the test phrase.
- Kill/reopen: no session replay or abandoned audio.
- Drag/rotate/open the keyboard: control bounds and normal typing remain usable.
- If background Activity launch is suppressed, use the launcher recovery path.

Record passed, failed, unsupported, or unverified per case. Gate A requires the
canonical specification's physical evidence before local inference integration.

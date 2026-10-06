# Foreground-start recovery

Status: framework tests implemented; device execution pending.

The [startup teardown decision](../decisions/0010-foreground-start-teardown.md)
preserves actual microphone checks while handling Android's foreground-start
obligation. Compile checks do not prove parent-process survival on a device.

`ServiceStartupTest` covers Cancel and Stop in the same main-thread block as
foreground-service launch, before Android can deliver `onStartCommand`. It also
covers missing microphone permission on a fresh install. It allows asynchronous
startup/teardown failures to reach the parent and checks that the Activity remains alive,
no text appears, and the actor is idle or failed as appropriate. These tests
use no models, speech or microphone capture. A phone with an existing microphone
grant skips only the denial case; tests never revoke that grant.

Run on Android 13 and Android 14 or newer:

```sh
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.altiro.app.ServiceStartupTest
```

The CI emulator matrix targets API 33 and 37, including a 16 KiB page-size image.
API 37 uses the published `android-37.0` SDK image name and 4 GiB AVD memory.
Remote CI has not run before the
operator's first push. Hardware checks additionally cover overlay-initiated
microphone refusal, ordinary capture, notification Cancel during capture and
recognition, startup cancellation, and a fresh dictation after every failure.
No refused start may invoke AudioRecord or produce a false microphone indicator.

Check the five-minute warning in Home, the overlay and the notification at 4:30;
recording must Stop and transcribe at 5:00. Cancel before recognition, reopen the
app, and verify that the saved checkpoint says CANCELLED instead of RUNNING.
Kill during recognition, reopen, and verify the content-free interruption notice
on Home. Dismissing that notice must preserve the Console checkpoint; a new
session resets it. Successful and cancelled sessions must not generate a false
interruption notice on restart.

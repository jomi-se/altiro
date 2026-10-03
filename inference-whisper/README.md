# Local inference boundary

Pinned whisper.cpp 1.9.4, CPU backend, JNI cancellation, structured progress,
and a versioned multilingual base model manifest. The source archive is fetched
at an exact commit and verified SHA-256 during the native build. Models are
imported through the app, size/hash verified, and rechecked before loading.
No model weights, native binaries, or build cache are stored in Git.

One worker owns a context per transcription. Cancel requests native abort;
release follows return from native code, including cancellation during cold
loading. Decode is greedy, four CPU threads, temperature zero, no translation,
no native transcript logging, and full recording processing. Idle contexts are
not retained in this first increment. Native device behavior and multilingual
quality remain hardware gates.

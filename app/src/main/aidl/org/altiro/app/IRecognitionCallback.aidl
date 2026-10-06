package org.altiro.app;

// Phase and runtime callbacks are synchronous: the parent checkpoints them
// before the worker enters potentially failing GPU driver code.
interface IRecognitionCallback {
    void onProgress(int percent);
    void onPhase(int phase);
    void onRuntime(String report);
    void onTextChunk(String text);
    void onComplete(int status);
}

package org.altiro.app;
import org.altiro.app.IRecognitionCallback;

interface IRecognitionWorker {
    void run(long request, String model, String audio, String language, boolean gpu, boolean flashAttention, IRecognitionCallback callback);
    void cancel(long request);
    void shutdown();
}

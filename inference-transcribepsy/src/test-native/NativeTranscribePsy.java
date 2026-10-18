package org.altiro.transcribepsy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone host probe of the actual JNI ABI; never claims Android device acceptance. */
public final class NativeTranscribePsy {
    static { System.loadLibrary("altiro-transcribepsy"); }
    private native long create();
    private native void cancel(long handle);
    private native void release(long handle);
    private native byte[] recognize(long handle, String model, String audio, boolean gpu, Object progress);

    public static class Progress {
        String latest = "";
        public void onPhase(int phase) {}
        public void onRuntime(String report) { latest = report; }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private String transcribe(String model, String audio, String expected) {
        long handle = create();
        Progress progress = new Progress();
        try {
            byte[] result = recognize(handle, model, audio, false, progress);
            require(result != null, "Recognition did not return text: " + progress.latest);
            String text = new String(result, StandardCharsets.UTF_8);
            require(!text.isBlank(), "Speech sample produced empty text");
            require(text.toLowerCase().contains(expected.toLowerCase()), "Reference phrase missing");
            require(progress.latest.contains("\"status\":\"FINISHED\""), "No finished runtime report");
            require(progress.latest.contains("\"encoder_backend\":\"CPU\""), "Unexpected encoder placement");
            System.out.println(progress.latest);
            return text;
        } finally { release(handle); }
    }

    private void cancellationAndFailure(String model, String audio) throws Exception {
        long before = create();
        Progress beforeProgress = new Progress();
        try {
            cancel(before);
            require(recognize(before, model, audio, false, beforeProgress) == null, "Pre-cancel returned text");
            require(beforeProgress.latest.contains("CANCELLED"), "Pre-cancel status missing");
        } finally { release(before); }
        long bad = create();
        Progress badProgress = new Progress();
        try {
            require(recognize(bad, model, audio + ".missing", false, badProgress) == null, "Missing audio returned text");
            require(badProgress.latest.contains("AUDIO_READ_FAILED"), "Missing audio failure not classified");
        } finally { release(bad); }

        Path malformed = Files.createTempFile("transcribepsy-invalid-", ".wav");
        long invalid = create();
        Progress invalidProgress = new Progress();
        try {
            Files.write(malformed, new byte[46]);
            require(recognize(invalid, model, malformed.toString(), false, invalidProgress) == null,
                    "Malformed WAV returned text");
            require(invalidProgress.latest.contains("AUDIO_READ_FAILED"), "Malformed WAV not classified");
        } finally { release(invalid); Files.deleteIfExists(malformed); }

        long running = create();
        CountDownLatch encoding = new CountDownLatch(1);
        AtomicReference<byte[]> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Progress progress = new Progress() {
            @Override public void onRuntime(String report) {
                super.onRuntime(report);
                if (report.contains("\"graph_checks\":1")) encoding.countDown();
            }
        };
        Thread worker = new Thread(() -> {
            try { result.set(recognize(running, model, audio, false, progress)); }
            catch (Throwable error) { failure.set(error); }
        });
        try {
            worker.start();
            require(encoding.await(60, TimeUnit.SECONDS), "Encoder did not start");
            Thread.sleep(100);
            cancel(running);
            worker.join(10_000);
            require(!worker.isAlive(), "Cancellation exceeded worker bound");
            require(failure.get() == null && result.get() == null, "Cancelled operation produced a result or exception");
            require(progress.latest.contains("CANCELLED"), "Running cancellation status missing");
        } finally {
            cancel(running);
            worker.join(10_000);
            // No release while a native operation is still in flight.
            if (!worker.isAlive()) release(running);
        }
        System.out.println("Pre-cancel, missing/malformed audio and in-flight cancellation passed.");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 4, "Expected Q8 model, FP16 model, WAV and reference phrase");
        NativeTranscribePsy runtime = new NativeTranscribePsy();
        String q8 = runtime.transcribe(args[0], args[2], args[3]);
        String fp16 = runtime.transcribe(args[1], args[2], args[3]);
        System.out.println("Q8 and FP16 reference phrase passed; exact transcripts equal: " + q8.equals(fp16));
        runtime.cancellationAndFailure(args[0], args[2]);
    }
}

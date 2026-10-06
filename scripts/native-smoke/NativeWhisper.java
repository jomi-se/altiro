package org.altiro.inference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host harness for the production JNI bridge; never prints recognized text. */
public final class NativeWhisper {
    interface Progress {
        void onProgress(int percent);
        default void onPhase(int phase) {}
        default void onRuntime(String report) {}
    }
    native long create();
    native void cancel(long operation);
    native void release(long operation);
    native String transcribe(long operation, String model, String wav, String language, boolean gpu, Progress progress);

    private String run(String model, String wav, String language, boolean cancelDuringProgress) {
        long handle = create();
        AtomicBoolean requested = new AtomicBoolean();
        Thread[] cancellation = new Thread[1];
        List<Integer> phases = new ArrayList<>();
        List<String> runtimeReports = new ArrayList<>();
        try {
            String text = transcribe(handle, model, wav, language, false, new Progress() {
              @Override public void onPhase(int phase) { phases.add(phase); }
              @Override public void onRuntime(String report) { runtimeReports.add(report); }
              @Override public void onProgress(int percent) {
                if (cancelDuringProgress && requested.compareAndSet(false, true)) {
                    cancellation[0] = new Thread(() -> {
                        try { Thread.sleep(200); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        cancel(handle);
                    });
                    cancellation[0].start();
                }
              }
            });
            if (cancelDuringProgress && (!requested.get() || text != null)) throw new AssertionError("Native cancellation failed");
            if (cancelDuringProgress && !phases.contains(5)) throw new AssertionError("Cancelled context not released");
            if (text != null && !text.isBlank() && !phases.equals(List.of(1, 2, 3, 4, 5))) throw new AssertionError("Missing or unordered phase callbacks");
            if (text != null && !text.isBlank() && runtimeReports.stream().noneMatch(value -> value.contains("\"encode_ms\""))) throw new AssertionError("Missing compute counters");
            if (runtimeReports.stream().anyMatch(value -> value.contains(model) || value.contains(wav))) throw new AssertionError("Runtime report disclosed paths");
            return text;
        } finally {
            if (cancellation[0] != null) {
                try { cancellation[0].join(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            release(handle);
            cancel(handle); // A stale operation is harmless.
        }
    }

    private static byte[] header(int length) {
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".getBytes()).putInt(length + 36).put("WAVEfmt ".getBytes())
            .putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000)
            .putInt(32000).putShort((short) 2).putShort((short) 16)
            .put("data".getBytes()).putInt(length).array();
    }

    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        var nativeWhisper = new NativeWhisper();
        String model = args[1], speech = args[2];
        Path scratch = Path.of(args[3]);
        if (args.length > 4 && "--gpu-unavailable".equals(args[4])) {
            long handle = nativeWhisper.create();
            List<Integer> phases = new ArrayList<>();
            List<String> reports = new ArrayList<>();
            try {
                nativeWhisper.transcribe(handle, model, speech, "en", true, new Progress() {
                    @Override public void onProgress(int percent) {}
                    @Override public void onPhase(int phase) { phases.add(phase); }
                    @Override public void onRuntime(String report) { reports.add(report); }
                });
                throw new AssertionError("Unavailable GPU silently succeeded");
            } catch (IllegalStateException expected) {
                if (!reports.stream().anyMatch(value -> value.contains("VULKAN_UNAVAILABLE"))) throw new AssertionError("Missing GPU failure code");
                if (phases.contains(2) || phases.contains(3)) throw new AssertionError("Unavailable GPU fell back to loading/inference");
            } finally { nativeWhisper.release(handle); }
            System.out.println("GPU-unavailable JNI smoke passed; no automatic CPU inference");
            return;
        }
        String shortText = nativeWhisper.run(model, speech, "en", false);
        if (shortText == null || !shortText.toLowerCase().contains("country")) throw new AssertionError("Speech recognition failed");
        byte[] source = Files.readAllBytes(Path.of(speech));
        byte[] pcm = Arrays.copyOfRange(source, 44, source.length);
        int repeats = Math.max(2, 16000 * 2 * 40 / pcm.length + 1);
        byte[] longAudio = new byte[44 + pcm.length * repeats];
        System.arraycopy(header(longAudio.length - 44), 0, longAudio, 0, 44);
        for (int i = 0; i < repeats; i++) System.arraycopy(pcm, 0, longAudio, 44 + i * pcm.length, pcm.length);
        Path longWav = scratch.resolve("long.wav");
        Files.write(longWav, longAudio);
        String longText = nativeWhisper.run(model, longWav.toString(), "en", false);
        if (longText == null || longText.length() < shortText.length() * 2) throw new AssertionError("Long recording was truncated");
        nativeWhisper.run(model, longWav.toString(), "en", true);
        nativeWhisper.run(model, speech, "auto", true);
        for (int i = 0; i < 20; i++) {
            long handle = nativeWhisper.create();
            try {
                nativeWhisper.cancel(handle);
                if (nativeWhisper.transcribe(handle, "unused", "unused", "es", false, percent -> {}) != null) throw new AssertionError();
            } finally { nativeWhisper.release(handle); }
        }
        Path silent = scratch.resolve("silence.wav");
        byte[] silentAudio = new byte[44 + 32000];
        System.arraycopy(header(32000), 0, silentAudio, 0, 44);
        Files.write(silent, silentAudio);
        if (!"".equals(nativeWhisper.run(model, silent.toString(), "es", false))) throw new AssertionError("Exact silence generated text");
        Path malformed = scratch.resolve("malformed.wav");
        Files.write(malformed, new byte[100]);
        try {
            nativeWhisper.run(model, malformed.toString(), "es", false);
            throw new AssertionError("Malformed WAV accepted");
        } catch (IllegalStateException expected) {
            if (!"Local recognition failed".equals(expected.getMessage())) throw expected;
        }
        System.out.println("JNI smoke passed: speech, >30s audio, decode/auto cancellation, stale handles, exact silence, malformed WAV");
    }
}

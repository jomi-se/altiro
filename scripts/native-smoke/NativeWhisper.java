package org.altiro.inference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
    native String transcribe(long operation, String model, String wav, String language, boolean gpu, boolean flashAttention, boolean dynamicWindow, Progress progress, byte[] initialPrompt);

    String transcribe(long operation, String model, String wav, String language, boolean gpu, boolean flashAttention, boolean dynamicWindow, Progress progress) {
        return transcribe(operation, model, wav, language, gpu, flashAttention, dynamicWindow, progress, new byte[0]);
    }

    private String run(String model, String wav, String language, boolean cancelDuringProgress) {
        return run(model, wav, language, cancelDuringProgress, false);
    }

    private String run(String model, String wav, String language, boolean cancelDuringProgress, boolean flashAttention) {
        return run(model, wav, language, cancelDuringProgress, flashAttention, false);
    }

    private String run(String model, String wav, String language, boolean cancelDuringProgress, boolean flashAttention, boolean dynamicWindow) {
        return run(model, wav, language, cancelDuringProgress, flashAttention, dynamicWindow, new byte[0]);
    }

    private String run(String model, String wav, String language, boolean cancelDuringProgress, boolean flashAttention, boolean dynamicWindow, byte[] prompt) {
        long handle = create();
        AtomicBoolean requested = new AtomicBoolean();
        Thread[] cancellation = new Thread[1];
        List<Integer> phases = new ArrayList<>();
        List<String> runtimeReports = new ArrayList<>();
        try {
            String text = transcribe(handle, model, wav, language, false, flashAttention, dynamicWindow, new Progress() {
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
            }, prompt);
            if (text != null && !text.isBlank() && runtimeReports.stream().noneMatch(value -> value.contains("\"flash_attention\":" + flashAttention))) throw new AssertionError("Flash Attention context setting missing or wrong");
            if (cancelDuringProgress && (!requested.get() || text != null)) throw new AssertionError("Native cancellation failed");
            if (cancelDuringProgress && !phases.contains(5)) throw new AssertionError("Cancelled context not released");
            if (text != null && !text.isBlank() && !phases.equals(List.of(1, 2, 3, 4, 5))) throw new AssertionError("Missing or unordered phase callbacks");
            if (text != null && !text.isBlank() && runtimeReports.stream().noneMatch(value -> value.contains("\"encode_ms\""))) throw new AssertionError("Missing compute counters");
            if (text != null && !text.isBlank()) {
                long samples;
                try { samples = (Files.size(Path.of(wav)) - 44) / 2; } catch (Exception failure) { throw new AssertionError(failure); }
                int context = runtimeReports.stream().filter(value -> value.contains("\"audio_ctx\":"))
                    .mapToInt(value -> Integer.parseInt(value.replaceAll(".*\"audio_ctx\":([0-9]+).*", "$1"))).findFirst().orElseThrow();
                if (!dynamicWindow || samples >= 30 * 16000) {
                    if (context != 1500) throw new AssertionError("Full or long recording used a shortened window");
                } else {
                    if (context < 250 || context > 1500 || context % 250 != 0 || context * 320L < samples) throw new AssertionError("Dynamic window did not cover the recording");
                    if (samples < 20 * 16000 && context >= 1500) throw new AssertionError("Short recording did not shrink");
                }
                String languageContext = "\"language_detection_audio_ctx\":" + (language.equals("auto") ? 1500 : 0);
                if (runtimeReports.stream().noneMatch(value -> value.contains(languageContext))) throw new AssertionError("Language detection window missing or wrong");
            }
            if (runtimeReports.stream().anyMatch(value -> value.contains(model) || value.contains(wav))) throw new AssertionError("Runtime report disclosed paths");
            String hint = new String(prompt, StandardCharsets.UTF_8);
            if (!hint.isEmpty() && runtimeReports.stream().anyMatch(value -> value.contains(hint.substring(0, Math.min(8, hint.length()))))) throw new AssertionError("Runtime report disclosed vocabulary");
            return text;
        } finally {
            if (cancellation[0] != null) {
                try { cancellation[0].join(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            release(handle);
            cancel(handle); // A stale operation is harmless.
        }
    }

    private void rejectsPrompt(String model, String wav, byte[] prompt) {
        long handle = create();
        List<Integer> phases = new ArrayList<>();
        List<String> reports = new ArrayList<>();
        try {
            transcribe(handle, model, wav, "en", false, false, false, new Progress() {
                @Override public void onProgress(int percent) {}
                @Override public void onPhase(int phase) { phases.add(phase); }
                @Override public void onRuntime(String report) { reports.add(report); }
            }, prompt);
            throw new AssertionError("Invalid vocabulary prompt accepted");
        } catch (IllegalStateException expected) {
            if (!"Local recognition failed".equals(expected.getMessage())) throw expected;
            if (!reports.equals(List.of("{\"status\":\"FAILED\",\"failure\":\"VOCABULARY_INVALID\"}"))) throw new AssertionError("Missing or extra vocabulary failure report");
            if (!phases.isEmpty()) throw new AssertionError("Invalid vocabulary reached audio or model loading");
        } finally { release(handle); }
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
                nativeWhisper.transcribe(handle, model, speech, "en", true, true, true, new Progress() {
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
        String flashText = nativeWhisper.run(model, speech, "en", false, true).toLowerCase();
        if (!flashText.contains("country")) throw new AssertionError("Flash Attention speech failed");
        nativeWhisper.run(model, speech, "en", true, true);
        String flashLong = nativeWhisper.run(model, longWav.toString(), "en", false, true).toLowerCase();
        if (flashLong.split("country", -1).length < 3) throw new AssertionError("Flash Attention lost an audio window");
        for (boolean flash : new boolean[]{false, true}) {
            String dynamicText = nativeWhisper.run(model, speech, "en", false, flash, true).toLowerCase();
            if (dynamicText.split("country", -1).length < 3) throw new AssertionError("Dynamic window lost sentence ending");
            nativeWhisper.run(model, speech, "en", true, flash, true);
        }
        String dynamicAuto = nativeWhisper.run(model, speech, "auto", false, false, true).toLowerCase();
        if (!dynamicAuto.contains("country")) throw new AssertionError("Dynamic Auto speech failed");
        String dynamicLong = nativeWhisper.run(model, longWav.toString(), "en", false, true, true).toLowerCase();
        if (dynamicLong.split("country", -1).length < 3) throw new AssertionError("Dynamic mode lost a long-audio window");
        for (int i = 0; i < 20; i++) {
            long handle = nativeWhisper.create();
            try {
                nativeWhisper.cancel(handle);
                if (nativeWhisper.transcribe(handle, "unused", "unused", "es", false, false, true, percent -> {}) != null) throw new AssertionError();
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
        // Vocabulary hints: optional context only. Never print hints or recognized text.
        byte[] hints = "Ñuñoa, Valparaíso, 𝔸ltiro, 中文, Kotlin".getBytes(StandardCharsets.UTF_8);
        if (!shortText.equals(nativeWhisper.run(model, speech, "en", false, false, false, new byte[0]))) throw new AssertionError("Empty vocabulary changed recognition");
        String hinted = nativeWhisper.run(model, speech, "en", false, false, false, hints);
        if (hinted == null || !hinted.toLowerCase().contains("country")) throw new AssertionError("Speech with vocabulary failed");
        String hintedLong = nativeWhisper.run(model, longWav.toString(), "en", false, false, true, hints);
        if (hintedLong == null || hintedLong.toLowerCase().split("country", -1).length < 3) throw new AssertionError("Vocabulary lost a long-audio window");
        if (!"".equals(nativeWhisper.run(model, silent.toString(), "es", false, false, true, hints))) throw new AssertionError("Exact silence with vocabulary generated text");
        nativeWhisper.run(model, longWav.toString(), "en", true, false, false, hints);
        long cancelled = nativeWhisper.create();
        try {
            nativeWhisper.cancel(cancelled);
            if (nativeWhisper.transcribe(cancelled, "unused", "unused", "es", false, false, true, percent -> {}, hints) != null) throw new AssertionError("Pre-cancelled vocabulary run produced text");
        } finally { nativeWhisper.release(cancelled); }
        StringBuilder full = new StringBuilder();
        String term = "Ñuñoa 𝔸 ";
        int termBytes = term.getBytes(StandardCharsets.UTF_8).length;
        while (full.toString().getBytes(StandardCharsets.UTF_8).length + termBytes <= 4096) full.append(term);
        while (full.toString().getBytes(StandardCharsets.UTF_8).length < 4096) full.append('x');
        byte[] maximum = full.toString().getBytes(StandardCharsets.UTF_8);
        if (maximum.length != 4096 || nativeWhisper.run(model, speech, "en", false, false, false, maximum) == null) throw new AssertionError("Maximum vocabulary prompt failed");
        nativeWhisper.rejectsPrompt(model, speech, Arrays.copyOf(maximum, 4097));
        nativeWhisper.rejectsPrompt(model, speech, new byte[] {'A', 0, 'B'});
        for (byte[] invalid : new byte[][] {{(byte) 0xC3}, {(byte) 0xC0, (byte) 0xAF}, {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0xF0, (byte) 0x9D, (byte) 0x94}}) {
            nativeWhisper.rejectsPrompt(model, speech, invalid);
        }
        System.out.println("JNI smoke passed: full/dynamic attention off/on speech and >30s audio, decode/auto/flash cancellation, stale handles, exact silence, malformed WAV; vocabulary empty/Unicode/long/silence/cancel/limit/NUL/malformed");
    }
}

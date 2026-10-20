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
    private native boolean benchmark(long handle, String model, String audio, boolean gpu, int threads, int variant, String cache, Object progress);
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

    private void encoderLab(String model, String audio) {
        final int[] samples = {0};
        Progress progress = new Progress() {
            @Override public void onRuntime(String report) {
                super.onRuntime(report);
                if (report.contains("\"event\":\"DEVICE\"")) {
                    require(report.contains("timestamp_period_ns") && report.contains("timestamp_bits_min"), "Missing timestamp capability metadata");
                }
                if (report.contains("\"event\":\"SAMPLE\"")) {
                    require(report.contains("\"finite\":true"), "Encoder produced invalid features");
                    require(report.contains("\"backend\":\"CPU\""), "Wrong encoder backend");
                    require(report.contains("compute_sync_us") && report.contains("readback_us"), "Missing stage measurements");
                    require(!report.contains(model) && !report.contains(audio), "Private paths in lab output");
                    samples[0]++;
                    System.out.println(report);
                }
            }
        };
        long handle = create();
        try {
            require(benchmark(handle, model, audio, false, 2, 0, "", progress), "Lab failed: " + progress.latest);
            require(samples[0] == 4, "First plus three warm executions required");
            require(progress.latest.contains("FINISHED"), "Lab did not finish");
        } finally { release(handle); }
        long cancelled = create();
        try {
            cancel(cancelled);
            require(!benchmark(cancelled, model, audio, false, 4, 0, "", progress), "Cancelled lab ran");
            require(progress.latest.contains("CANCELLED"), "Lab cancellation missing");
            require(!benchmark(cancelled, model, audio, false, 9, 0, "", progress), "Invalid lab options accepted");
        } finally { release(cancelled); }
    }

    private void cancelEncoderLab(String model, String audio) throws Exception {
        long handle = create();
        CountDownLatch encoding = new CountDownLatch(1);
        AtomicReference<Boolean> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Progress progress = new Progress() {
            @Override public void onRuntime(String report) {
                super.onRuntime(report);
                if (report.contains("\"event\":\"BEGIN\"")) encoding.countDown();
            }
        };
        Thread worker = new Thread(() -> {
            try { result.set(benchmark(handle, model, audio, false, 2, 0, "", progress)); }
            catch (Throwable error) { failure.set(error); }
        });
        try {
            worker.start();
            require(encoding.await(60, TimeUnit.SECONDS), "Lab encoder did not start");
            Thread.sleep(100);
            cancel(handle);
            worker.join(10_000);
            require(!worker.isAlive(), "Lab cancellation exceeded worker bound");
            require(failure.get() == null && Boolean.FALSE.equals(result.get()), "Cancelled lab produced success or exception");
            require(progress.latest.contains("CANCELLED"), "In-flight lab cancellation missing");
        } finally {
            cancel(handle); worker.join(10_000);
            if (!worker.isAlive()) release(handle);
        }
    }

    public static void main(String[] args) throws Exception {
        if ((args.length == 3 || args.length == 4) && args[0].equals("--kernel-hunt")) {
            NativeTranscribePsy runtime = new NativeTranscribePsy();
            int variant = args.length == 4 ? Integer.parseInt(args[3]) : 27;
            final boolean focused = variant >= 40 && variant <= 46;
            final int[] kernels = {0};
            Progress progress = new Progress() {
                @Override public void onRuntime(String report) {
                    super.onRuntime(report);
                    if (report.contains("\"event\":\"KERNEL\"")) {
                        require(report.contains("\"finite\":true"), "Nonfinite synthetic output");
                        require(report.contains("\"backend\":\"CPU\""), "Synthetic backend mismatch");
                        var error = java.util.regex.Pattern.compile("\"relative_rmse\":([-+0-9.eE]+)").matcher(report);
                        require(error.find() && Double.parseDouble(error.group(1)) <= 0.01, "Synthetic CPU reference mismatch");
                        if(variant == 25) require(report.contains("\"cpu_repacked\":false"), "Strided CPU input used incompatible prepacking");
                        require(!report.contains(args[1]) && !report.contains(args[2]), "Paths in synthetic export");
                        if (focused) {
                            require(report.contains("\"focused\":true"), "Focused metadata missing");
                            require(report.contains("\"sequence\":" + kernels[0] + ","), "Focused sequence mismatch");
                            require(report.contains("\"shape_family\":\"" + (kernels[0] < 4 ? "recording" : "boundary") + "\""), "Focused shape family mismatch");
                            require(report.contains("\"unfused_compared\":false") && report.contains("\"unfused_relative_rmse\":null") && report.contains("\"unfused_max_abs_error\":null"), "CPU must not claim an unfused GPU comparison");
                            require(report.contains("\"boundary_gpu_ns\":[null,null,null,null,null,null]"), "CPU must not fabricate GPU timestamps");
                            if (kernels[0] == 4 || kernels[0] == 5) {
                                int cells = kernels[0] == 4 ? 4032 : 4225;
                                require(report.contains("\"full_reference\":true") && report.contains("\"reference_cells\":" + cells + ","), "Boundary full-output check missing");
                            }
                        }
                        kernels[0]++;
                        System.out.println(report);
                    }
                }
            };
            long handle = runtime.create();
            try {
                require(runtime.benchmark(handle,args[1],args[2],false,2,variant,"",progress), "Synthetic suite failed: " + progress.latest);
                require(kernels[0] == (focused ? 8 : 32), "Missing synthetic shapes");
                require(progress.latest.contains("FINISHED"), "Missing completion");
            } finally { runtime.release(handle); }
            long cancelled = runtime.create();
            try {
                runtime.cancel(cancelled);
                require(!runtime.benchmark(cancelled,args[1],args[2],false,2,27,"",progress), "Cancelled synthetic suite ran");
                require(progress.latest.contains("CANCELLED"), "Synthetic cancellation missing");
            } finally { runtime.release(cancelled); }
            long inflight = runtime.create();
            Progress cancelProgress = new Progress() {
                @Override public void onRuntime(String report) {
                    super.onRuntime(report);
                    if(report.contains("\"event\":\"KERNEL\"")) runtime.cancel(inflight);
                }
            };
            try {
                require(!runtime.benchmark(inflight,args[1],args[2],false,2,variant,"",cancelProgress), "In-flight synthetic cancellation returned success");
                require(cancelProgress.latest.contains("CANCELLED"), "In-flight synthetic cancellation missing");
            } finally { runtime.release(inflight); }
            return;
        }
        if (args.length == 3 && args[0].equals("--encoder-lab")) {
            NativeTranscribePsy runtime = new NativeTranscribePsy();
            runtime.encoderLab(args[1], args[2]);
            runtime.cancelEncoderLab(args[1], args[2]);
            return;
        }
        require(args.length == 4, "Expected Q8 model, FP16 model, WAV and reference phrase");
        NativeTranscribePsy runtime = new NativeTranscribePsy();
        String q8 = runtime.transcribe(args[0], args[2], args[3]);
        String fp16 = runtime.transcribe(args[1], args[2], args[3]);
        System.out.println("Q8 and FP16 reference phrase passed; exact transcripts equal: " + q8.equals(fp16));
        runtime.cancellationAndFailure(args[0], args[2]);
    }
}

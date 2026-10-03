package org.altiro.inference;

import java.nio.file.Path;

/** Exercises every comparison profile through the production JNI bridge on public audio. */
public final class ModelComparisonSmoke {
    public static void main(String[] args) {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        var runtime = new NativeWhisper();
        for (int i = 2; i < args.length; i++) {
            long handle = runtime.create();
            try {
                String text = runtime.transcribe(handle, args[i], args[1], "en", percent -> {});
                if (text == null || !text.toLowerCase(java.util.Locale.ROOT).contains("country")) {
                    throw new AssertionError("Public sample recognition failed for model index " + i);
                }
            } finally { runtime.release(handle); }
        }
        System.out.println("Production JNI model comparison smoke passed for " + (args.length - 2) + " profiles; no recognized text logged.");
    }
}

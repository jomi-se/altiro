package org.altiro.core

/** Fixed native variants; no arbitrary native environment or command execution. */
data class EncoderExperiment(
    val id: String,
    val backend: RecognitionBackend,
    val threads: Int = 4,
    val variant: Int = 0,
    val profiled: Boolean = false,
) {
    val wire: String
        get() = "$threads:$variant"

    companion object {
        fun suite(
            full: Boolean,
            profile: Boolean,
            reverse: Boolean = false,
        ): List<EncoderExperiment> {
            fun cpu(n: Int, suffix: String = "") =
                EncoderExperiment("cpu-$n$suffix", RecognitionBackend.CPU, n)
            fun gpu(id: String, variant: Int = 0, profiled: Boolean = false) =
                EncoderExperiment(
                    id,
                    RecognitionBackend.VULKAN,
                    variant = variant,
                    profiled = profiled,
                )
            val planned = buildList {
                add(cpu(4, "-start"))
                add(gpu("gpu-start"))
                if (full) {
                    for (n in listOf(1, 2, 6, 8)) add(cpu(n))
                }
                add(gpu("gpu-direct-conv", 1))
                add(gpu("gpu-reorder", 2))
                add(gpu("gpu-submit-1mb", 3))
                if (full) {
                    add(gpu("gpu-submit-100mb", 4))
                    add(gpu("gpu-flash-attention", 5))
                    add(gpu("gpu-f32-accumulation", 6))
                    add(gpu("gpu-no-integer-dot", 7))
                    add(EncoderExperiment("cpu-short-5s", RecognitionBackend.CPU, variant = 10))
                    add(gpu("gpu-short-5s", 10))
                    add(gpu("gpu-cache-empty", 8))
                    add(gpu("gpu-cache-seeded", 8))
                }
                add(gpu("gpu-end"))
                add(cpu(4, "-end"))
                if (profile) add(gpu("gpu-op-profile", 9, true))
            }
            if (!reverse) return planned
            val end = planned.indexOfFirst { it.id == "gpu-end" }
            val middle = planned.subList(2, end)
            return planned.take(2) +
                middle.filter { it.variant != 8 }.reversed() +
                middle.filter { it.variant == 8 } +
                planned.drop(end)
        }

        /** Used at the process boundary too; reject unknown or out-of-range options. */
        fun options(wire: String): Pair<Int, Int>? {
            val parts = wire.split(':')
            if (parts.size != 2) return null
            val threads = parts[0].toIntOrNull() ?: return null
            val variant = parts[1].toIntOrNull() ?: return null
            return if (threads in 1..8 && variant in 0..10) threads to variant else null
        }

        fun median(samples: List<Double>): Double? {
            if (samples.isEmpty() || samples.any { !it.isFinite() || it < 0 }) return null
            val ordered = samples.sorted()
            val middle = ordered.size / 2
            return if (ordered.size % 2 == 1) ordered[middle]
            else (ordered[middle - 1] + ordered[middle]) / 2
        }
    }
}

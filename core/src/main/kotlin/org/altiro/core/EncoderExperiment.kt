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

    /** Synthetic OpenCL cases retain Vulkan only as their matched control backend. */
    val candidateBackend: String
        get() = if (isAcl(variant)) "OPENCL" else backend.name

    fun appliesTo(modelId: String): Boolean = !isInt8(variant) || modelId == "transcribepsy-q8"

    companion object {
        // A full two-format suite has 40 workers, each with its own phase lifecycle.
        const val DIAGNOSTIC_PHASE_CAPACITY = 1024

        /** Register every backend the lab may dispatch, independent of the saved selection. */
        fun diagnosticInputs(inputs: List<RecognitionInput>): List<RecognitionInput> {
            require(inputs.size in 1..2 && inputs.map { it.spec.id }.distinct().size == inputs.size)
            require(inputs.all { it.engine == RecognitionEngine.TRANSCRIBEPSY })
            return inputs.flatMap { input ->
                listOf(RecognitionBackend.CPU, RecognitionBackend.VULKAN).map { backend ->
                    input.copy(backend = backend)
                }
            }
        }

        fun suite(
            full: Boolean,
            profile: Boolean,
            reverse: Boolean = false,
            hunt: Boolean = false,
            tuning: Boolean = false,
            packed: Boolean = false,
            accelerators: Boolean = false,
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
                if (accelerators) {
                    add(gpu("gpu-capabilities", 70))
                    add(cpu(2, "-start"))
                    add(gpu("gpu-accelerator-f32-start", 30))
                    add(gpu("acl-recording", 90))
                    add(gpu("int8-recording", 80))
                    add(gpu("acl-145", 91))
                    add(gpu("int8-145", 81))
                    add(gpu("acl-201", 92))
                    add(gpu("int8-201", 82))
                    add(gpu("gpu-accelerator-f32-end", 30))
                    add(cpu(2, "-end"))
                } else if (packed) {
                    add(gpu("gpu-capabilities", 70))
                    add(cpu(2, "-start"))
                    add(gpu("gpu-packed-f32-start", 30))
                    add(gpu("packed-recording", 60))
                    add(gpu("packed-independent-streams", 63))
                    add(gpu("packed-145", 61))
                    add(gpu("packed-201", 62))
                    add(gpu("gpu-packed-f32-end", 30))
                    add(cpu(2, "-end"))
                } else if (tuning) {
                    add(cpu(2, "-start"))
                    add(gpu("gpu-tuning-f32-start", 30))
                    for ((index, label) in
                        listOf(
                                "blocking-wait",
                                "tile-256",
                                "split-2",
                                "split-4",
                                "fuse-bias",
                                "fuse-silu",
                            )
                            .withIndex()) {
                        add(gpu("gpu-tuning-$label", 31 + index))
                    }
                    for ((index, label) in
                        listOf(
                                "control",
                                "blocking-wait",
                                "tile-256",
                                "split-2",
                                "split-4",
                                "fuse-bias",
                                "fuse-silu",
                            )
                            .withIndex()) {
                        add(gpu("focused-$label", 40 + index))
                    }
                    add(gpu("gpu-tuning-f32-end", 30))
                    add(cpu(2, "-end"))
                } else if (hunt) {
                    add(gpu("gpu-cache-empty", 8))
                    add(gpu("gpu-cache-seeded", 8))
                    add(cpu(2))
                    add(gpu("gpu-tile-small", 11))
                    add(gpu("gpu-tile-large", 12))
                    add(gpu("gpu-small-f32", 13))
                    add(gpu("gpu-cached-f32", 14))
                    add(gpu("gpu-tile-64x32-f32", 15))
                    add(gpu("gpu-tile-32x64-f32", 16))
                    for ((index, label) in
                        listOf("stock", "small", "large", "f32", "predequant", "strided", "streams")
                            .withIndex()) add(gpu("kernel-$label", 20 + index))
                    add(EncoderExperiment("kernel-cpu-2", RecognitionBackend.CPU, 2, 27))
                    add(gpu("kernel-64x32-f32", 28))
                    add(gpu("kernel-32x64-f32", 29))
                } else {
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
                }
                add(gpu("gpu-end"))
                add(cpu(4, "-end"))
                if (profile) add(gpu("gpu-op-profile", 9, true))
            }
            if (!reverse) return planned
            val end = planned.indexOfFirst { it.id == "gpu-end" }
            if (accelerators) {
                val first = planned.indexOfFirst { isAcl(it.variant) || isInt8(it.variant) }
                val last = planned.indexOfLast { isAcl(it.variant) || isInt8(it.variant) }
                return planned.take(first) +
                    planned.subList(first, last + 1).reversed() +
                    planned.drop(last + 1)
            }
            if (packed) {
                val first = planned.indexOfFirst { isPacked(it.variant) }
                val last = planned.indexOfLast { isPacked(it.variant) }
                return planned.take(first) +
                    planned.subList(first, last + 1).reversed() +
                    planned.drop(last + 1)
            }
            if (tuning) {
                val firstTreatment = planned.indexOfFirst { it.variant == 31 }
                val lastTreatment = planned.indexOfLast { it.variant == 46 }
                return planned.take(firstTreatment) +
                    planned.subList(firstTreatment, lastTreatment + 1).reversed() +
                    planned.drop(lastTreatment + 1)
            }
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
            return if (
                threads in 1..8 &&
                    (variant in 0..16 ||
                        variant in 20..36 ||
                        variant in 40..46 ||
                        isPacked(variant) ||
                        variant == 70 ||
                        isAcl(variant) ||
                        isInt8(variant))
            )
                threads to variant
            else null
        }

        fun isAcl(variant: Int): Boolean = variant in 90..92

        fun isInt8(variant: Int): Boolean = variant in 80..82

        fun isPacked(variant: Int): Boolean = variant in 60..63

        fun isKernel(variant: Int): Boolean = variant in 20..29 || variant in 40..46

        fun kernelCount(variant: Int): Int = if (variant in 40..46) 8 else 32

        fun median(samples: List<Double>): Double? {
            if (samples.isEmpty() || samples.any { !it.isFinite() || it < 0 }) return null
            val ordered = samples.sorted()
            val middle = ordered.size / 2
            return if (ordered.size % 2 == 1) ordered[middle]
            else (ordered[middle - 1] + ordered[middle]) / 2
        }
    }
}

package org.altiro.core

enum class RecognitionBackend(val label: String) {
    CPU("CPU"),
    VULKAN("Vulkan GPU (experimental)"),
}

enum class RuntimeStatus {
    STARTING,
    PROBING,
    AVAILABLE,
    INITIALIZING,
    READY,
    FINISHED,
    FAILED,
    CANCELLED,
    WORKER_DIED,
}

enum class RuntimeFailure {
    NONE,
    NATIVE_UNAVAILABLE,
    VULKAN_UNAVAILABLE,
    NO_GPU,
    STORAGE_16_UNSUPPORTED,
    GPU_INIT_FAILED,
    MODEL_LOAD_FAILED,
    AUDIO_READ_FAILED,
    INFERENCE_FAILED,
    WORKER_DIED,
    WORKER_START_FAILED,
    IPC_FAILED,
    CANCEL_TIMEOUT,
    INVALID_REPORT,
    OUT_OF_MEMORY,
    DEVICE_LOST,
}

enum class WorkerExitReason {
    UNKNOWN,
    LOW_MEMORY,
    CRASH,
    NATIVE_CRASH,
    SIGNALED,
    ANR,
    OTHER,
}

/** Only hardware properties and compute counters; never accept arbitrary runtime logs. */
data class RuntimeDetails(
    val modelId: String,
    val backend: RecognitionBackend,
    val status: RuntimeStatus = RuntimeStatus.STARTING,
    val failure: RuntimeFailure = RuntimeFailure.NONE,
    val workerExit: WorkerExitReason = WorkerExitReason.UNKNOWN,
    val vulkanResult: Int? = null,
    val gpuName: String? = null,
    val vulkanVersion: String? = null,
    val driverVersion: Long? = null,
    val deviceCount: Int? = null,
    val storage16: Boolean? = null,
    val shaderFloat16: Boolean? = null,
    val shaderInt8: Boolean? = null,
    val gpuActive: Boolean? = null,
    val flashAttention: Boolean? = null,
    val encodeCalls: Int? = null,
    val decodeCalls: Int? = null,
    val encodeMillis: Long? = null,
    val decodeMillis: Long? = null,
    val batchMillis: Long? = null,
    val promptMillis: Long? = null,
    val sampleMillis: Long? = null,
) {
    init {
        require(modelId.matches(Regex("[a-z0-9-]{1,80}")))
        require(gpuName == null || gpuName.matches(Regex("[A-Za-z0-9 ._()+:-]{1,96}")))
        require(vulkanVersion == null || vulkanVersion.matches(Regex("[0-9.]{1,24}")))
        require(driverVersion == null || driverVersion in 0..0xffffffffL)
        require(deviceCount == null || deviceCount in 0..64)
        require(encodeCalls == null || encodeCalls in 0..1_000_000)
        require(decodeCalls == null || decodeCalls in 0..1_000_000)
        require(
            listOf(encodeMillis, decodeMillis, batchMillis, promptMillis, sampleMillis).all {
                it == null || it in 0..3_600_000
            }
        )
    }

    fun export(): String = buildString {
        appendLine("$modelId · ${backend.name}: ${status.name}; failure: ${failure.name}")
        if (status == RuntimeStatus.WORKER_DIED) {
            appendLine(
                "Android worker exit reason: ${workerExit.name} (may be unavailable immediately)"
            )
        }
        vulkanResult?.let { appendLine("Vulkan result code: $it") }
        gpuActive?.let { appendLine("GPU backend initialized in Whisper context: $it") }
        flashAttention?.let { appendLine("Flash Attention enabled in Whisper context: $it") }
        if (gpuName != null)
            appendLine(
                "GPU: $gpuName; Vulkan: $vulkanVersion; driver version (raw): $driverVersion"
            )
        if (deviceCount != null) {
            appendLine(
                "Vulkan devices: $deviceCount; storage16: $storage16; shaderFloat16: $shaderFloat16; shaderInt8: $shaderInt8"
            )
        }
        encodeCalls?.let { appendLine("Whisper calls: encoder=$it; decoder=$decodeCalls") }
        if (encodeMillis != null) {
            appendLine(
                "Whisper counters (ms): encode=$encodeMillis; decode=$decodeMillis; batch=$batchMillis; " +
                    "prompt=$promptMillis; sample=$sampleMillis"
            )
        }
    }
}

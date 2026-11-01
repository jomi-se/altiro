package org.altiro.core

/** Selected execution path, distinct from the local model's CPU/Vulkan setting. */
enum class RecognitionRoute {
    LOCAL,
    CHECKING,
    REMOTE_GPU,
}

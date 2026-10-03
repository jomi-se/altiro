package org.altiro.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException

data class ModelSpec(
    val id: String,
    val bytes: Long,
    val sha256: String,
) {
    init {
        require(bytes in 1..500_000_000)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

object VerifiedModel {
    /** A failed or cancelled import never replaces an existing verified model. */
    fun install(
        source: InputStream,
        destination: File,
        spec: ModelSpec,
        cancelled: () -> Boolean = { false },
        progress: (Int) -> Unit = {},
    ) {
        destination.parentFile?.mkdirs()
        val partial = File.createTempFile("import-", ".part", destination.parentFile)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            var previous = -1
            partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled()) throw CancellationException()
                    val size = source.read(buffer)
                    if (size < 0) break
                    count += size
                    require(count <= spec.bytes) { "Unsupported model size" }
                    digest.update(buffer, 0, size)
                    output.write(buffer, 0, size)
                    val percent = (count * 100 / spec.bytes).toInt()
                    if (percent != previous) {
                        previous = percent
                        progress(percent)
                    }
                }
                output.flush()
            }
            require(count == spec.bytes && hex(digest.digest()) == spec.sha256) { "Model verification failed" }
            if (cancelled()) throw CancellationException()
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            partial.delete()
        }
    }

    fun matches(
        file: File,
        spec: ModelSpec,
        cancelled: () -> Boolean = { false },
    ): Boolean {
        if (!file.isFile || file.length() != spec.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (cancelled()) throw CancellationException()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest()) == spec.sha256
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

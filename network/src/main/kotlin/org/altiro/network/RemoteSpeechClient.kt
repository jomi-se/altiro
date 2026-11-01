package org.altiro.network

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import org.altiro.core.Wav

/** Only a fixed, content-free code escapes the transport; never attach an I/O exception or body. */
class RemoteSpeechException(val code: String) : Exception(code)

/**
 * Blocking, sequential client for an explicitly selected self-hosted server. Calls belong on IO.
 * Each operation has an independent deadline and connection; cancellation predicates must be
 * thread-safe. Cancellation never replays a POST, and the caller retains ownership of its audio
 * file. No transcript, endpoint or credential logging.
 */
class RemoteSpeechClient
internal constructor(
    private val bearerToken: String?,
    private val openConnection: (URI) -> HttpURLConnection,
    private val healthDeadlineMillis: Long = 1_500,
    private val transcriptionDeadlineMillis: Long = 45_000,
) {
    constructor(
        bearerToken: String? = null
    ) : this(bearerToken, { it.toURL().openConnection() as HttpsURLConnection })

    private val active = AtomicReference<Operation?>(null)

    fun cancel() {
        active.get()?.abort("remote_cancelled")
    }

    fun health(
        endpoint: RemoteSpeechEndpoint,
        cancelled: () -> Boolean = { false },
    ): Boolean =
        run(healthDeadlineMillis, cancelled) { operation ->
            val connection = connect(endpoint.healthUri, operation, 1_200, 1_200)
            connection.requestMethod = "GET"
            connection.connect()
            operation.check()
            checkStatus(connection.responseCode)
            val response = response(connection, operation, 16 * 1024)
            if (response["ready"] !is Boolean || response["busy"] !is Boolean)
                fail("remote_invalid_response")
            if (response["model"] != MODEL || response["device"] != "cuda")
                fail("remote_incompatible_server")
            if (response["ready"] != true) fail("remote_not_ready")
            if (response["busy"] != false) fail("remote_busy")
            true
        }

    fun transcribe(
        endpoint: RemoteSpeechEndpoint,
        audio: File,
        language: String,
        prompt: String = "",
        cancelled: () -> Boolean = { false },
    ): String =
        run(transcriptionDeadlineMillis, cancelled) { operation ->
            if (language !in setOf("auto", "en", "es", "fr")) fail("remote_invalid_options")
            if (prompt.length > 4096) fail("remote_invalid_options")
            val promptBytes = strictUtf8(prompt)
            if (promptBytes.size > 4096) fail("remote_invalid_options")
            if (
                bearerToken != null &&
                    (bearerToken.length !in 1..8192 || !bearerToken.matches(BEARER_PATTERN))
            )
                fail("remote_invalid_options")

            // Validate our recorder's canonical PCM16/16 kHz/mono WAV, without loading its samples.
            val length = audio.length()
            if (length > MAX_AUDIO_BYTES) fail("remote_audio_too_large")
            if (!audio.isFile || length < 44 || (length - 44) % 2 != 0L)
                fail("remote_invalid_audio")
            val frames = (length - 44) / 2
            if (frames > Wav.MAX_FRAMES) fail("remote_audio_too_long")
            operation.check()
            val source = operation.register(audio.inputStream())
            val header = ByteArray(44)
            readExactly(source, header, operation)
            if (!header.contentEquals(Wav.header(frames))) fail("remote_invalid_audio")

            val boundary = "altiro-${UUID.randomUUID()}"
            val prefix =
                buildString {
                        fun field(name: String, value: String) {
                            append(
                                "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n"
                            )
                            append(value)
                            append("\r\n")
                        }
                        field("model", MODEL)
                        field("language", language)
                        field("response_format", "json")
                        if (prompt.isNotEmpty()) field("prompt", prompt)
                        append("--$boundary\r\n")
                        append(
                            "Content-Disposition: form-data; name=\"file\"; filename=\"recording.wav\"\r\n"
                        )
                        append("Content-Type: audio/wav\r\n\r\n")
                    }
                    .toByteArray(Charsets.UTF_8)
            val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII)
            val connection = connect(endpoint.uri, operation, 5_000, 40_000)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (bearerToken != null)
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            // Fixed-length streaming disables HttpURLConnection's buffered-body
            // authentication/retry path.
            connection.setFixedLengthStreamingMode(prefix.size.toLong() + length + suffix.size)
            connection.connect()
            operation.check()
            val output = operation.register(connection.outputStream)
            output.write(prefix)
            operation.check()
            output.write(header)
            val buffer = ByteArray(8192)
            var remaining = length - header.size
            while (remaining > 0) {
                operation.check()
                val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) fail("remote_invalid_audio")
                output.write(buffer, 0, count)
                remaining -= count
            }
            operation.check()
            if (source.read() != -1) fail("remote_invalid_audio")
            output.write(suffix)
            output.close()
            operation.check()
            checkStatus(connection.responseCode)
            val result = response(connection, operation, 1024 * 1024)
            if (
                (result.containsKey("model") && result["model"] != MODEL) ||
                    (result.containsKey("device") && result["device"] != "cuda")
            )
                fail("remote_incompatible_server")
            val text = result["text"] as? String ?: fail("remote_invalid_response")
            if (text.length > 65536 || text.toByteArray(Charsets.UTF_8).size > 65536)
                fail("remote_transcript_too_large")
            text
        }

    private fun connect(
        uri: URI,
        operation: Operation,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): HttpURLConnection {
        operation.check()
        val connection = openConnection(uri)
        operation.register(Closeable(connection::disconnect))
        operation.check()
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.allowUserInteraction = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Altiro")
        return connection
    }

    private fun response(
        connection: HttpURLConnection,
        operation: Operation,
        limit: Int,
    ): Map<*, *> {
        operation.check()
        if (connection.contentLengthLong > limit) fail("remote_response_too_large")
        if (
            !connection.contentEncoding.isNullOrBlank() &&
                !connection.contentEncoding.equals("identity", ignoreCase = true)
        )
            fail("remote_invalid_response")
        if (!connection.contentType?.substringBefore(';')?.trim().equals("application/json", true))
            fail("remote_invalid_response")
        val input = operation.register(connection.inputStream)
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            operation.check()
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - bytes.size()))
            if (count < 0) break
            bytes.write(buffer, 0, count)
            if (bytes.size() > limit) fail("remote_response_too_large")
        }
        operation.check()
        val text =
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (_: Exception) {
                fail("remote_invalid_response")
            }
        return RemoteSpeechJson(text).objectValue()
    }

    private fun readExactly(input: InputStream, target: ByteArray, operation: Operation) {
        var offset = 0
        while (offset < target.size) {
            operation.check()
            val count = input.read(target, offset, target.size - offset)
            if (count <= 0) fail("remote_invalid_audio")
            offset += count
        }
    }

    private fun strictUtf8(value: String): ByteArray =
        try {
            val encoded =
                Charsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: Exception) {
            fail("remote_invalid_options")
        }

    private fun checkStatus(status: Int) {
        when (status) {
            200 -> Unit
            in 300..399 -> fail("remote_redirect")
            401,
            403 -> fail("remote_unauthorized")
            413 -> fail("remote_audio_too_large")
            429 -> fail("remote_busy")
            503 -> fail("remote_not_ready")
            in 500..599 -> fail("remote_server_error")
            else -> fail("remote_http_error")
        }
    }

    private fun <T> run(
        deadlineMillis: Long,
        cancelled: () -> Boolean,
        action: (Operation) -> T,
    ): T {
        val operation = Operation(deadlineMillis, cancelled)
        if (!active.compareAndSet(null, operation)) fail("remote_client_busy")
        // Some platform DNS/connect calls ignore interruption. Keep the caller's deadline bounded
        // independently; the abandoned worker can never proceed to upload after cancellation.
        val task = FutureTask {
            try {
                operation.check()
                action(operation)
            } finally {
                operation.closeResources()
            }
        }
        val worker = Thread(task, "altiro-speech-request").apply { isDaemon = true }
        operation.worker = worker
        try {
            operation.check()
            worker.start()
            while (true) {
                operation.check()
                try {
                    val result = task.get(25, TimeUnit.MILLISECONDS)
                    operation.check()
                    return result
                } catch (_: TimeoutException) {
                    // Poll only the local completion/cancellation signal, never retry the request.
                } catch (error: ExecutionException) {
                    operation.check()
                    when (val cause = error.cause) {
                        is RemoteSpeechException -> throw cause
                        is SocketTimeoutException -> fail("remote_timeout")
                        else -> fail("remote_transport_error")
                    }
                }
            }
        } catch (_: InterruptedException) {
            operation.abort("remote_cancelled")
            Thread.currentThread().interrupt()
            fail("remote_cancelled")
        } finally {
            if (!task.isDone) operation.abort("remote_cancelled")
            active.compareAndSet(operation, null)
        }
    }

    private class Operation(deadlineMillis: Long, private val cancelled: () -> Boolean) {
        private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis)
        private val stopped = AtomicReference<String?>(null)
        private val resources = mutableListOf<Closeable>()
        @Volatile var worker: Thread? = null

        fun check() {
            if (cancelled()) abort("remote_cancelled")
            if (System.nanoTime() >= deadline) abort("remote_timeout")
            stopped.get()?.let { fail(it) }
        }

        fun <T : Closeable> register(resource: T): T {
            synchronized(resources) {
                if (stopped.get() == null) {
                    resources.add(resource)
                    return resource
                }
            }
            runCatching { resource.close() }
            fail(stopped.get() ?: "remote_cancelled")
        }

        fun abort(code: String) {
            if (!stopped.compareAndSet(null, code)) return
            worker?.interrupt()
            // Never make UI cancellation wait on a platform stream's synchronized close/disconnect.
            Thread(::closeResources, "altiro-speech-close").apply {
                isDaemon = true
                start()
            }
        }

        fun closeResources() {
            val snapshot =
                synchronized(resources) {
                    resources.toList().also { resources.clear() }
                }
            snapshot.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        private const val MODEL = "large-v3-turbo"
        private const val MAX_AUDIO_BYTES = 16L * 1024 * 1024
        private val BEARER_PATTERN = Regex("[A-Za-z0-9._~+/-]+=*")

        private fun fail(code: String): Nothing = throw RemoteSpeechException(code)
    }
}

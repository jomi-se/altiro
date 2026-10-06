package org.altiro.network

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException
import org.altiro.core.ModelSpec
import org.altiro.core.VerifiedModel

/** Sanitized categories; no URL, header, or host-supplied text reaches the user or diagnostics. */
enum class DownloadFailure {
    NOT_DOWNLOADABLE,
    OFFLINE,
    TIMEOUT,
    SECURE_CONNECTION,
    UNTRUSTED_REDIRECT,
    TOO_MANY_REDIRECTS,
    UNAVAILABLE,
    SERVER_BUSY,
    REFUSED,
    VERIFICATION_FAILED,
    INSUFFICIENT_STORAGE,
    INTERRUPTED,
}

sealed interface DownloadOutcome {
    data object Installed : DownloadOutcome

    data object Cancelled : DownloadOutcome

    data class Failed(val reason: DownloadFailure) : DownloadOutcome
}

object ModelSourcePolicy {
    const val HOST = "huggingface.co"
    private val commit = Regex("[0-9a-f]{40}")
    private val segment = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /**
     * Only an immutable `https://huggingface.co/<owner>/<repo>/resolve/<commit>/<filename>` is a
     * binary source. Repository pages (`tree/`, `blob/`) and branch names are attribution only.
     */
    fun directDownload(
        sourceUrl: String,
        filename: String,
    ): URI? {
        val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
        if (
            uri.scheme != "https" ||
                uri.host != HOST ||
                uri.port != -1 ||
                uri.rawUserInfo != null ||
                uri.rawQuery != null ||
                uri.rawFragment != null
        )
            return null
        val parts = uri.rawPath.orEmpty().split('/')
        val valid =
            parts.size == 6 &&
                parts[0].isEmpty() &&
                segment.matches(parts[1]) &&
                segment.matches(parts[2]) &&
                parts[3] == "resolve" &&
                commit.matches(parts[4]) &&
                parts[5] == filename
        return if (valid) uri else null
    }

    /** Hugging Face serves pinned files from regional `*.hf.co` CDN hosts. */
    fun trustedHop(uri: URI): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            (host == HOST || host.endsWith(".hf.co"))
    }
}

interface HttpResponse : Closeable {
    val status: Int
    val location: String?
    val contentLength: Long
    val contentEncoding: String?

    fun body(): InputStream
}

fun interface HttpTransport {
    /** Registers a closeable before blocking, so cancellation can abort connect or read. */
    fun open(
        uri: URI,
        register: (Closeable) -> Unit,
    ): HttpResponse
}

/** Cancels by flag and by closing the live connection, so a stalled read cannot pin the worker. */
class DownloadCancellation(
    private val closer: (Closeable) -> Unit = { resource ->
        // Closing a TLS socket may write; keep it off the caller's (main) thread.
        Thread({ runCatching { resource.close() } }, "altiro-download-cancel")
            .apply { isDaemon = true }
            .start()
    }
) {
    @Volatile
    var cancelled = false
        private set

    private var resource: Closeable? = null

    fun cancel() {
        val live =
            synchronized(this) {
                cancelled = true
                resource.also { resource = null }
            }
        live?.let(closer)
    }

    internal fun register(live: Closeable) {
        val closeNow =
            synchronized(this) {
                if (!cancelled) resource = live
                cancelled
            }
        if (closeNow) runCatching { live.close() }
    }
}

class ModelDownloader(
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val availableBytes: (File) -> Long = File::getUsableSpace,
    private val maxRedirects: Int = 5,
) {
    /** Blocking; call from the model worker. A failure never touches an existing [destination]. */
    fun install(
        source: URI,
        destination: File,
        spec: ModelSpec,
        cancellation: DownloadCancellation,
        progress: (Int) -> Unit = {},
    ): DownloadOutcome {
        if (!ModelSourcePolicy.trustedHop(source))
            return DownloadOutcome.Failed(DownloadFailure.NOT_DOWNLOADABLE)
        val directory = destination.absoluteFile.parentFile.apply { mkdirs() }
        if (availableBytes(directory) < spec.bytes + STORAGE_MARGIN)
            return DownloadOutcome.Failed(DownloadFailure.INSUFFICIENT_STORAGE)
        var current = source
        var redirects = 0
        try {
            while (true) {
                if (cancellation.cancelled) return DownloadOutcome.Cancelled
                transport.open(current, cancellation::register).use { response ->
                    when (response.status) {
                        in REDIRECTS -> {
                            val next =
                                response.location?.let {
                                    runCatching { current.resolve(it) }.getOrNull()
                                }
                            if (next == null || !ModelSourcePolicy.trustedHop(next))
                                return DownloadOutcome.Failed(DownloadFailure.UNTRUSTED_REDIRECT)
                            if (++redirects > maxRedirects)
                                return DownloadOutcome.Failed(DownloadFailure.TOO_MANY_REDIRECTS)
                            current = next
                        }
                        200 -> {
                            val encoding = response.contentEncoding
                            if (
                                (response.contentLength >= 0 &&
                                    response.contentLength != spec.bytes) ||
                                    (encoding != null && !encoding.equals("identity", true))
                            )
                                return DownloadOutcome.Failed(DownloadFailure.VERIFICATION_FAILED)
                            try {
                                VerifiedModel.install(
                                    response.body(),
                                    destination,
                                    spec,
                                    { cancellation.cancelled },
                                    progress,
                                )
                            } catch (_: IllegalArgumentException) {
                                return DownloadOutcome.Failed(DownloadFailure.VERIFICATION_FAILED)
                            }
                            return DownloadOutcome.Installed
                        }
                        else -> return DownloadOutcome.Failed(classify(response.status))
                    }
                }
            }
        } catch (_: CancellationException) {
            return DownloadOutcome.Cancelled
        } catch (error: IOException) {
            if (cancellation.cancelled) return DownloadOutcome.Cancelled
            return DownloadOutcome.Failed(
                when (error) {
                    is UnknownHostException,
                    is ConnectException,
                    is NoRouteToHostException -> DownloadFailure.OFFLINE
                    is SocketTimeoutException -> DownloadFailure.TIMEOUT
                    is SSLException -> DownloadFailure.SECURE_CONNECTION
                    else -> DownloadFailure.INTERRUPTED
                }
            )
        }
    }

    private fun classify(status: Int): DownloadFailure =
        when (status) {
            404,
            410 -> DownloadFailure.UNAVAILABLE
            408,
            429,
            in 500..599 -> DownloadFailure.SERVER_BUSY
            else -> DownloadFailure.REFUSED
        }

    companion object {
        const val STORAGE_MARGIN = 10_000_000L
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
